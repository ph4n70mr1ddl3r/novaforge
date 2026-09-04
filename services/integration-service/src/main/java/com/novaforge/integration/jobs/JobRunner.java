package com.novaforge.integration.jobs;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.integration.clients.FileClient;
import com.novaforge.integration.clients.NotifyClient;
import com.novaforge.integration.clients.PublishedIntegrations;
import com.novaforge.integration.clients.ReportingClient;
import com.novaforge.integration.clients.RuntimeClient;
import com.novaforge.integration.clients.RuntimeClient.Outcome;
import com.novaforge.integration.store.DeliveryStore;
import com.novaforge.integration.store.JobStore;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.ImportDefinition;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The async job engine (PHASE-6 §7): imports chunk-process through the batch API
 * as the integration principal with per-item outcomes, checkpointed for resume —
 * a killed run restarts from its last checkpoint and the per-row ledger skips
 * every settled row, so a row applies exactly once. Exports (entity datasets
 * paged under the job's pinned role; report renders through the Reporting
 * Service's internal role-scoped leg) stream to the File Service. Progress rides
 * {@code import.progress} (keyed {@code tenant_id:job_id}, the family's record);
 * completion notifies the initiating user through the built-in
 * {@code job-completed} category.
 */
@Component
public class JobRunner {

    private static final Logger LOG = LoggerFactory.getLogger(JobRunner.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final JobStore jobs;
    private final DeliveryStore deliveries;
    private final PublishedIntegrations definitions;
    private final RuntimeClient runtime;
    private final FileClient files;
    private final ReportingClient reports;
    private final NotifyClient notify;
    private final int chunkSize;
    /** The export ceiling (ReportRunner.requireWithinCeiling's pattern): the CSV is assembled in memory before the File Service upload — without a bound, a multi-million-row entity OOMs the pod on the shared scheduler pool. */
    private final long exportMaxRows;

    /** Jobs claimed for execution this pass — one runner per job, idempotent claim. */
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    public JobRunner(JobStore jobs, DeliveryStore deliveries, PublishedIntegrations definitions,
                     RuntimeClient runtime, FileClient files, ReportingClient reports,
                     NotifyClient notify,
                     @Value("${novaforge.jobs.chunk-size:100}") int chunkSize,
                     @Value("${novaforge.jobs.export-max-rows:200000}") long exportMaxRows) {
        this.jobs = jobs;
        this.deliveries = deliveries;
        this.definitions = definitions;
        this.runtime = runtime;
        this.files = files;
        this.reports = reports;
        this.notify = notify;
        this.chunkSize = chunkSize;
        this.exportMaxRows = exportMaxRows;
    }

    /** The scan: pending jobs (fresh or resumed from a checkpoint) claim in. */
    @Scheduled(fixedDelayString = "${novaforge.jobs.poll-interval-ms:1000}")
    public void scan() {
        for (JobStore.Job job : jobs.pending(50)) {
            if (running.add(job.id())) {
                try {
                    run(job);
                } finally {
                    running.remove(job.id());
                }
            }
        }
    }

    /** Resumes a paused job (the operational API's leg, §7): pending again from its checkpoint. */
    public void resume(UUID tenantId, UUID jobId) {
        JobStore.Job job = jobs.find(tenantId, jobId).orElseThrow(() ->
                new PlatformException(PlatformErrorCode.NOT_FOUND, "job " + jobId));
        if (!"paused".equals(job.status()) && !"failed".equals(job.status())) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "only paused or failed jobs resume — this one is " + job.status());
        }
        jobs.updateStatus(tenantId, jobId, "pending", null);
    }

    private void run(JobStore.Job job) {
        // the cross-replica fence: the in-process `running` set cannot see another
        // replica's scan — only the pass that CASes pending→running owns the job
        if (!jobs.claim(job.tenantId(), job.id())) {
            return;
        }
        try {
            switch (JobStore.Kind.of(job.kind())) {
                case IMPORT -> runImport(job);
                case EXPORT_ENTITY -> runEntityExport(job);
                case EXPORT_REPORT -> runReportExport(job);
            }
        } catch (Exception e) {
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            LOG.error("job {} failed: {}", job.id(), error, e);
            jobs.updateStatus(job.tenantId(), job.id(), "failed", error);
            progress(job, "failed", error);
            notify.jobCompleted(job.tenantId(), job.id(), job.initiatedBy(),
                    "job " + job.id() + " failed: " + error);
        }
    }

    // --- imports (§7: checkpointed, resumable, per-row exactly-once) ---

    private void runImport(JobStore.Job job) {
        AppDefinition app = definitions.byApiName(job.tenantId(), job.app())
                .orElseThrow(() -> new PlatformException(PlatformErrorCode.NOT_FOUND,
                        "app " + job.app() + " is not published"));
        ImportDefinition mapping = app.integrations().importMapping(job.importMapping())
                .orElseThrow(() -> new PlatformException(PlatformErrorCode.NOT_FOUND,
                        "import mapping " + job.importMapping() + " is not published"));
        byte[] content = files.download(job.tenantId(), job.fileId());
        List<Map<String, String>> rows = Csv.parse(new String(content, StandardCharsets.UTF_8));
        jobs.totalRows(job.tenantId(), job.id(), rows.size());

        // the resume ledger: rows settled `ok` by a previous pass never re-apply
        Set<Integer> settled = jobs.okRows(job.id());
        long processed = 0;
        long failed = 0;
        int index = 0;
        List<Map<String, Object>> chunk = new ArrayList<>();
        List<Integer> chunkRows = new ArrayList<>();
        List<Map<String, String>> chunkSources = new ArrayList<>();
        for (Map<String, String> row : rows) {
            if (settled.contains(index)) {
                processed++;
                index++;
                continue;   // exactly-once: the ledger already holds this row's outcome
            }
            Map<String, Object> mapped = mapRow(mapping, row);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("op", "create");
            item.put("entity", mapping.entity());
            item.put("record", mapped);
            if (ImportDefinition.MODE_UPSERT.equals(mapping.mode())
                    && !mapping.keyFields().isEmpty()) {
                item = upsertItem(job, mapping, mapped);
            }
            chunk.add(item);
            chunkRows.add(index);
            chunkSources.add(row);
            index++;
            if (chunk.size() >= chunkSize) {
                var settledNow = applyChunk(job, chunk, chunkRows, chunkSources);
                processed += settledNow[0];
                failed += settledNow[1];
                jobs.checkpoint(job.tenantId(), job.id(), processed, failed,
                        Map.of("nextRow", index));
                progress(job, "running", null);
                chunk.clear();
                chunkRows.clear();
                chunkSources.clear();
            }
        }
        if (!chunk.isEmpty()) {
            var settledNow = applyChunk(job, chunk, chunkRows, chunkSources);
            processed += settledNow[0];
            failed += settledNow[1];
        }
        jobs.checkpoint(job.tenantId(), job.id(), processed, failed, Map.of("nextRow", index));
        jobs.updateStatus(job.tenantId(), job.id(), "completed", null);
        progress(job, "completed", null);
        notify.jobCompleted(job.tenantId(), job.id(), job.initiatedBy(),
                "import completed: " + processed + " applied, " + failed + " failed");
    }

    /** Upsert items resolve their key through the integration-scoped lookup (§6). */
    private Map<String, Object> upsertItem(JobStore.Job job, ImportDefinition mapping,
                                          Map<String, Object> mapped) {
        // the query-DSL filter shape the runtime's list parser pins — the bare
        // {field: value} map that lived here 400s ("filter.field is required"),
        // so every upsert import failed its first row's lookup when the webhook
        // leg was migrated to the leaf shape and this leg was left behind
        Map<String, Object> filter = RuntimeClient.keyLookupFilter(mapping.keyFields(), mapped);
        RuntimeClient.ListPage found = runtime.lookup(job.tenantId(), mapping.entity(),
                Map.of("filter", filter, "page", Map.of("size", 1)));
        Map<String, Object> item = new LinkedHashMap<>();
        if (!found.rows().isEmpty()) {
            Map<String, Object> existing = found.rows().getFirst();
            item.put("op", "update");
            item.put("id", String.valueOf(existing.get("id")));
            item.put("version", existing.get("version"));
        } else {
            item.put("op", "create");
        }
        item.put("entity", mapping.entity());
        item.put("record", mapped);
        return item;
    }

    /** Applies one chunk through the batch API; outcomes land in the per-row ledger. */
    private long[] applyChunk(JobStore.Job job, List<Map<String, Object>> chunk,
                              List<Integer> chunkRows, List<Map<String, String>> sources) {
        List<Outcome> outcomes = runtime.write(job.tenantId(), chunk);
        long ok = 0;
        long errors = 0;
        for (int i = 0; i < outcomes.size(); i++) {
            Outcome outcome = outcomes.get(i);
            int rowIndex = chunkRows.get(i);
            if (outcome.ok()) {
                ok++;
                jobs.recordRow(job.id(), new JobStore.RowOutcome(rowIndex, "ok",
                        outcome.recordId(), null, null));
            } else {
                errors++;
                jobs.recordRow(job.id(), new JobStore.RowOutcome(rowIndex, "error", null,
                        outcome.code(), outcome.detail()));
            }
        }
        return new long[] {ok, errors};
    }

    /** Maps one CSV row per the ImportDefinition: target field ← source column (§7). */
    private static Map<String, Object> mapRow(ImportDefinition mapping, Map<String, String> row) {
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : mapping.mapping().entrySet()) {
            String source = String.valueOf(entry.getValue());
            if (source.startsWith("${") && source.endsWith("}")) {
                source = source.substring(2, source.length() - 1);
            }
            mapped.put(entry.getKey(), row.get(source));
        }
        return mapped;
    }

    // --- exports (§7: chunked, async, streamed to the File Service) ---

    private void runEntityExport(JobStore.Job job) {
        StringBuilder csv = new StringBuilder();
        long total = -1;
        long offset = 0;
        boolean header = true;
        while (total < 0 || offset < total) {
            RuntimeClient.ListPage page = runtime.list(job.tenantId(), job.entity(),
                    job.runAsRole(), Map.of("page", Map.of("size", chunkSize, "offset", offset)));
            if (total < 0) {
                total = page.total();
                if (total > exportMaxRows) {
                    // the assembly is in-memory (the File Service upload takes the
                    // whole body): a boundless export OOMs the pod on the shared
                    // scheduler pool — fail the job cleanly instead (the run catch
                    // records + notifies), like ReportRunner.requireWithinCeiling
                    throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                            "entity export of " + total + " rows exceeds the ceiling of "
                                    + exportMaxRows + " (novaforge.jobs.export-max-rows) — "
                                    + "export a filtered report or raise the ceiling");
                }
            }
            for (Map<String, Object> row : page.rows()) {
                if (header) {
                    csv.append(String.join(",", row.keySet())).append('\n');
                    header = false;
                }
                List<String> cells = new ArrayList<>();
                row.forEach((key, value) -> cells.add(csvCell(value)));
                csv.append(String.join(",", cells)).append('\n');
            }
            offset += page.rows().size();
            if (page.rows().isEmpty()) {
                break;
            }
            jobs.checkpoint(job.tenantId(), job.id(), offset, 0, Map.of("rows", offset));
            progress(job, "running", null);
        }
        UUID fileId = files.upload(job.tenantId(),
                (job.entity() == null ? "export" : job.entity()) + "-" + job.id() + ".csv",
                "text/csv;charset=UTF-8", csv.toString().getBytes(StandardCharsets.UTF_8),
                job.initiatedBy());
        finish(job, fileId, offset, total);
    }

    private void runReportExport(JobStore.Job job) {
        // actor-scoped: the interactive handoff's jobs render under the initiating
        // actor's own scopes — §6's "same authorization as a run", never a
        // re-scoped role wider than the requester
        byte[] rendered = reports.export(job.tenantId(), job.app(), job.reportId(),
                job.runAsRole(), job.initiatedBy(),
                job.format() == null ? "csv" : job.format(), job.params());
        UUID fileId = files.upload(job.tenantId(),
                (job.reportId() == null ? "report" : job.reportId()) + "-"
                        + job.id() + "." + (job.format() == null ? "csv" : job.format()),
                job.format() != null && job.format().equals("xlsx")
                        ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        : "text/csv;charset=UTF-8",
                rendered, job.initiatedBy());
        finish(job, fileId, -1, -1);
    }

    private void finish(JobStore.Job job, UUID fileId, long rows, long total) {
        jobs.checkpoint(job.tenantId(), job.id(), Math.max(rows, 0), 0,
                Map.of("fileId", String.valueOf(fileId)));
        jobs.updateStatus(job.tenantId(), job.id(), "completed", null);
        progress(job, "completed", null);
        notify.jobCompleted(job.tenantId(), job.id(), job.initiatedBy(),
                "export completed — attachment " + fileId + (total >= 0 ? " (" + rows + " rows)" : ""));
    }

    // --- progress + notifications ---

    private void progress(JobStore.Job job, String status, String error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jobId", job.id().toString());
        payload.put("kind", job.kind());
        payload.put("status", status);
        JobStore.Job current = jobs.find(job.tenantId(), job.id()).orElse(job);
        payload.put("processedRows", current.processedRows());
        payload.put("failedRows", current.failedRows());
        payload.put("totalRows", current.totalRows());
        if (error != null) {
            payload.put("error", error);
        }
        // import.* keys tenant_id:job_id — the job is the family's record (§2)
        deliveries.outbox(job.tenantId(), "import.progress", payload);
    }

    /**
     * RFC 4180 cell quoting plus formula neutralization (the reporting exporter's
     * rule, shared here): entity exports carry raw record data, and a leading
     * {@code =}, {@code +}, {@code -}, {@code @} (or tab/CR) must never ride into a
     * spreadsheet as a formula on open.
     */
    static String csvCell(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (!text.isEmpty() && ("=+-@".indexOf(text.charAt(0)) >= 0
                || text.charAt(0) == '\t' || text.charAt(0) == '\r')) {
            text = "'" + text;
        }
        if (text.contains("\"") || text.contains(",") || text.contains("\n")
                || text.contains("\r")) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }

    /** Minimal RFC 4180 reader: quoted cells, embedded commas/newlines/quotes. */
    static final class Csv {

        static List<Map<String, String>> parse(String content) {
            List<List<String>> table = new ArrayList<>();
            List<String> row = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (quoted) {
                    if (c == '"') {
                        if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                            cell.append('"');
                            i++;
                        } else {
                            quoted = false;
                        }
                    } else {
                        cell.append(c);
                    }
                } else if (c == '"') {
                    quoted = true;
                } else if (c == ',') {
                    row.add(cell.toString());
                    cell.setLength(0);
                } else if (c == '\n' || c == '\r') {
                    if (c == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                        i++;
                    }
                    row.add(cell.toString());
                    cell.setLength(0);
                    if (!(row.size() == 1 && row.getFirst().isEmpty())) {
                        table.add(row);
                    }
                    row = new ArrayList<>();
                } else {
                    cell.append(c);
                }
            }
            if (cell.length() > 0 || !row.isEmpty()) {
                row.add(cell.toString());
                table.add(row);
            }
            if (table.isEmpty()) {
                return List.of();
            }
            List<String> header = table.getFirst();
            List<Map<String, String>> rows = new ArrayList<>();
            for (int r = 1; r < table.size(); r++) {
                Map<String, String> mapped = new LinkedHashMap<>();
                List<String> cells = table.get(r);
                for (int c = 0; c < header.size(); c++) {
                    mapped.put(header.get(c), c < cells.size() ? cells.get(c) : null);
                }
                rows.add(mapped);
            }
            return rows;
        }
    }
}
