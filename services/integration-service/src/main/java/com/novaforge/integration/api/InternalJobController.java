package com.novaforge.integration.api;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.integration.store.JobStore;
import com.novaforge.security.ServiceClientGate;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The reporting handoff's landing surface (PHASE-6 §7, PHASE-5 §6): sync exports
 * over the 10k cap create their async job here — the reporting export endpoint
 * itself returns the job link, so the >10k report export needs no separate
 * builder call. The job renders under the report's role scope through the
 * Reporting Service's internal leg and streams to the File Service; completion
 * notifies the initiating user. Service-client gated — never user traffic.
 */
@RestController
@RequestMapping("/api/v1/integrations/internal")
public class InternalJobController {

    private final JobStore jobs;

    public InternalJobController(JobStore jobs) {
        this.jobs = jobs;
    }

    public record ReportExportRequest(String tenantId, String app, String reportId,
                                      String runAsRole, String format,
                                      Map<String, Object> params, String initiatedBy) {
    }

    @PostMapping("/report-exports")
    public Map<String, Object> createReportExport(@RequestBody ReportExportRequest request) {
        ServiceClientGate.require("report-export-job");
        if (request.tenantId() == null || request.app() == null || request.reportId() == null) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "report export jobs require tenantId, app, and reportId");
        }
        UUID tenantId;
        UUID initiatedBy;
        try {
            tenantId = UUID.fromString(request.tenantId());
            initiatedBy = UUID.fromString(request.initiatedBy() == null
                    ? "00000000-0000-0000-0000-000000000000" : request.initiatedBy());
        } catch (IllegalArgumentException e) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "tenantId/initiatedBy must be uuids: " + e.getMessage());
        }
        UUID job = jobs.create(tenantId, JobStore.Kind.EXPORT_REPORT, request.app(), null, null,
                request.reportId(), request.runAsRole(), request.params(), null, null,
                request.format() == null ? "csv" : request.format(), initiatedBy, null);
        return Map.of("jobId", job, "status", "pending",
                "jobLink", "/api/v1/integrations/jobs/" + job);
    }
}
