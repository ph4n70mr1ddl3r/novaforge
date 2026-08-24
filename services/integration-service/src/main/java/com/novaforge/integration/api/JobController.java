package com.novaforge.integration.api;

import com.novaforge.common.context.TenantContext;
import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.integration.jobs.JobRunner;
import com.novaforge.integration.store.JobStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The jobs' operational surface (PHASE-6 §7): import runs and entity export jobs
 * are created, inspected, and resumed here under {@code /api/v1/integrations/**}
 * (the §9 builder gate) — the >10k report export needs no separate builder call;
 * it rides PHASE-5 §6's designed handoff from the reporting export endpoint
 * itself. Progress events drive the builder progress UI and completion
 * notifications.
 */
@RestController
@RequestMapping("/api/v1/integrations/jobs")
public class JobController {

    private final JobStore jobs;
    private final JobRunner runner;

    public JobController(JobStore jobs, JobRunner runner) {
        this.jobs = jobs;
        this.runner = runner;
    }

    /** Creates an import run: the uploaded file + the promoted ImportDefinition. */
    public record ImportRequest(String app, String importMapping, UUID fileId, String fileName) {
    }

    @PostMapping("/imports")
    public Map<String, Object> createImport(@RequestBody ImportRequest request) {
        if (request.app() == null || request.importMapping() == null || request.fileId() == null) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "import runs require app, importMapping, and fileId (the presigned upload)");
        }
        UUID job = jobs.create(tenant(), JobStore.Kind.IMPORT, request.app(), null,
                request.importMapping(), null, null, null, request.fileId(), request.fileName(),
                null, actor(), null);
        return Map.of("jobId", job, "status", "pending",
                "resume", "/api/v1/integrations/jobs/" + job + "/resume");
    }

    /** Creates an entity export job: the dataset pages under the pinned role (§7/§9). */
    public record ExportRequest(String app, String entity, String runAsRole, String format) {
    }

    @PostMapping("/exports/entity")
    public Map<String, Object> createEntityExport(@RequestBody ExportRequest request) {
        if (request.app() == null || request.entity() == null || request.runAsRole() == null) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "entity exports require app, entity, and runAsRole (an explicitly "
                            + "permissioned scope — the scheduled-report precedent, PHASE-5 §7)");
        }
        UUID job = jobs.create(tenant(), JobStore.Kind.EXPORT_ENTITY, request.app(),
                request.entity(), null, null, request.runAsRole(), null, null, null,
                request.format() == null ? "csv" : request.format(), actor(), null);
        return job(job);
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return jobs.list(tenant(), 100).stream().map(JobController::shape).toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        return jobs.find(tenant(), id).map(JobController::shape).orElseThrow(() ->
                new PlatformException(PlatformErrorCode.NOT_FOUND, "job " + id));
    }

    /** Per-item outcomes — the import ledger's retained audit trail (§7). */
    @GetMapping("/{id}/rows")
    public List<Map<String, Object>> rows(@PathVariable UUID id) {
        JobStore.Job job = jobs.find(tenant(), id).orElseThrow(() ->
                new PlatformException(PlatformErrorCode.NOT_FOUND, "job " + id));
        return jobs.rows(job.id()).stream()
                .map(row -> {
                    Map<String, Object> shaped = new LinkedHashMap<>();
                    shaped.put("row", row.rowIndex());
                    shaped.put("status", row.status());
                    shaped.put("recordId", row.recordId());
                    shaped.put("code", row.code());
                    shaped.put("detail", row.detail());
                    return shaped;
                }).toList();
    }

    /** Resumes a paused/failed import from its checkpoint (§11 item 4). */
    @PostMapping("/{id}/resume")
    public Map<String, Object> resume(@PathVariable UUID id) {
        runner.resume(tenant(), id);
        return Map.of("jobId", id, "status", "pending");
    }

    private Map<String, Object> job(UUID id) {
        return Map.of("jobId", id, "status", "pending",
                "resume", "/api/v1/integrations/jobs/" + id);
    }

    private static Map<String, Object> shape(JobStore.Job job) {
        Map<String, Object> shaped = new LinkedHashMap<>();
        shaped.put("id", job.id());
        shaped.put("kind", job.kind());
        shaped.put("status", job.status());
        shaped.put("app", job.app());
        shaped.put("entity", job.entity());
        shaped.put("importMapping", job.importMapping());
        shaped.put("reportId", job.reportId());
        shaped.put("runAsRole", job.runAsRole());
        shaped.put("fileId", job.fileId());
        shaped.put("format", job.format());
        shaped.put("totalRows", job.totalRows());
        shaped.put("processedRows", job.processedRows());
        shaped.put("failedRows", job.failedRows());
        shaped.put("error", job.error());
        shaped.put("createdAt", job.createdAt().toString());
        return shaped;
    }

    private static UUID tenant() {
        return UUID.fromString(TenantContext.require().tenantId());
    }

    private static UUID actor() {
        return UUID.fromString(TenantContext.require().actorId());
    }
}
