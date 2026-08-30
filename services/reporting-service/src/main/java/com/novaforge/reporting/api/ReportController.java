package com.novaforge.reporting.api;

import com.novaforge.common.context.TenantContext;
import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.reporting.export.AsyncExportClient;
import com.novaforge.reporting.export.ReportExporter;
import com.novaforge.reporting.run.ReportRunner;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * The report run/export surface (§4/§6): {@code POST /api/v1/reports/{id}/run} with
 * param overrides — saved filters are defaults, and the actor's sharing-rule row
 * filters bound every run because the runtime enforces them on the query path — and
 * {@code GET /api/v1/reports/{id}/export?format=csv|xlsx} streaming synchronously
 * under the same authorization, capped at 10k rows (§6). Dashboards ride the
 * Metadata Service's published read (§2); no dashboard-scoped API exists in v1.
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final ReportRunner runner;
    private final ReportExporter exporter;
    private final AsyncExportClient asyncExports;
    private final long exportMaxRows;

    public ReportController(ReportRunner runner, ReportExporter exporter,
                            AsyncExportClient asyncExports,
                            @Value("${novaforge.reporting.export-max-rows:10000}") long exportMaxRows) {
        this.runner = runner;
        this.exporter = exporter;
        this.asyncExports = asyncExports;
        this.exportMaxRows = exportMaxRows;
    }

    public record RunRequest(String app, Map<String, Object> params, Boolean fresh) {
    }

    @PostMapping("/{id}/run")
    public Map<String, Object> run(@PathVariable String id, @RequestBody RunRequest request) {
        var ctx = requireContext();
        requireApp(request);
        return runner.run(tenant(ctx), actor(ctx), request.app(), id, request.params(),
                callerToken(), Boolean.TRUE.equals(request.fresh()));
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable String id,
                                         @RequestParam String app,
                                         @RequestParam(defaultValue = "csv") String format,
                                         @RequestParam(value = "params", required = false) String params,
                                         @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE,
                                                 required = false) String acceptLanguage) {
        var ctx = requireContext();
        if (!format.equals("csv") && !format.equals("xlsx")) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "export format must be csv or xlsx: " + format);
        }
        Map<String, Object> runParams = params == null || params.isBlank()
                ? Map.of() : MAPPER.readValue(params, Map.class);
        ReportRunner.Resolved resolved = runner.resolve(tenant(ctx), app, id);
        Map<String, Object> run = runner.exportRows(tenant(ctx), actor(ctx), app, id,
                runParams, callerToken(), (int) exportMaxRows);
        // the §6 cap activates the PHASE-6 handoff: over-cap runs answer 202 with
        // the async export job's link instead of the Phase-5 cap error
        long rowCount = ((java.util.List<?>) run.getOrDefault("rows", java.util.List.of())).size();
        if (rowCount > exportMaxRows) {
            AsyncExportClient.AsyncJob job = asyncExports.create(tenant(ctx), app, id,
                    format, runParams, actor(ctx));
            return ResponseEntity.accepted()
                    .header(HttpHeaders.LOCATION, job.jobLink())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(MAPPER.writeValueAsBytes(Map.of(
                            "status", "accepted",
                            "rows", rowCount,
                            "jobId", job.jobId().toString(),
                            "jobLink", job.jobLink(),
                            "detail", "synchronous exports are capped at " + exportMaxRows
                                    + " rows — the async export job runs it and notifies "
                                    + "you on completion")));
        }
        Set<String> moneyColumns = ReportRunner.moneyColumns(resolved);
        Locale locale = localeOf(acceptLanguage);
        byte[] body = format.equals("csv")
                ? exporter.csv(run, moneyColumns, locale)
                : exporter.xlsx(run, resolved.report(), moneyColumns, locale);
        String filename = id + (format.equals("csv") ? ".csv" : ".xlsx");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(format.equals("csv")
                        ? MediaType.parseMediaType("text/csv;charset=UTF-8")
                        : MediaType.parseMediaType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    // --- helpers ---


    private static Locale localeOf(String acceptLanguage) {
        if (acceptLanguage != null && !acceptLanguage.isBlank()) {
            String first = acceptLanguage.split(",")[0].split(";")[0].trim();
            try {
                return Locale.forLanguageTag(first);
            } catch (IllegalArgumentException ignored) {
                // fall through to the JVM default
            }
        }
        return Locale.getDefault();
    }

    private static void requireApp(RunRequest request) {
        if (request == null || request.app() == null || request.app().isBlank()) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "report runs require the app's apiName: {\"app\": \"…\", \"params\": {…}}");
        }
    }

    private static String callerToken() {
        var attributes = org.springframework.web.context.request.RequestContextHolder
                .getRequestAttributes();
        if (attributes instanceof org.springframework.web.context.request
                .ServletRequestAttributes servlet) {
            String header = servlet.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
            if (header != null && header.startsWith("Bearer ")) {
                return header.substring(7);
            }
        }
        throw new PlatformException(PlatformErrorCode.FORBIDDEN,
                "no caller token bound — report runs execute as the requesting actor (§4)");
    }

    private static TenantContext.Context requireContext() {
        return TenantContext.current().orElseThrow(() ->
                new PlatformException(PlatformErrorCode.TENANT_MISSING,
                        "no tenant context bound (missing tenant_id claim?)"));
    }

    private static UUID tenant(TenantContext.Context ctx) {
        return UUID.fromString(ctx.tenantId());
    }

    private static UUID actor(TenantContext.Context ctx) {
        return UUID.fromString(ctx.actorId());
    }
}
