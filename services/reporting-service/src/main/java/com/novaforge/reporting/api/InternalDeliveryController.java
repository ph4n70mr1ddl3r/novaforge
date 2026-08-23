package com.novaforge.reporting.api;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.reporting.export.ReportExporter;
import com.novaforge.reporting.notify.DeliveryClient;
import com.novaforge.reporting.run.ReportRunner;
import com.novaforge.security.ServiceClientGate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Scheduler's report target (§7): one internal, service-client-gated surface —
 * run the report under the job's {@code runAsRole} (the system principal over an
 * explicitly permissioned scope), render the export, deliver through the Notification
 * Service, and report back what happened. Failures propagate so the scheduler's run
 * history records them audibly (§7: failures visible in the job history).
 */
@RestController
@RequestMapping("/api/v1/reports/internal")
public class InternalDeliveryController {

    private final ReportRunner runner;
    private final ReportExporter exporter;
    private final DeliveryClient delivery;

    public InternalDeliveryController(ReportRunner runner, ReportExporter exporter,
                                      DeliveryClient delivery) {
        this.runner = runner;
        this.exporter = exporter;
        this.delivery = delivery;
    }

    public record DeliveryRequest(String tenantId, String app, String reportId,
                                  Map<String, Object> params, String runAsRole,
                                  Map<String, Object> recipients, String format) {

        String effectiveRunAsRole() {
            return runAsRole == null || runAsRole.isBlank() ? "reporting" : runAsRole;
        }

        String effectiveFormat() {
            return format == null || format.isBlank() ? "csv" : format;
        }
    }

    @PostMapping("/deliver")
    public Map<String, Object> deliver(@RequestBody DeliveryRequest request) {
        ServiceClientGate.require("report-delivery");
        if (request.tenantId() == null || request.app() == null || request.reportId() == null) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "deliveries require tenantId, app, and reportId");
        }
        UUID tenantId;
        try {
            tenantId = UUID.fromString(request.tenantId());
        } catch (IllegalArgumentException e) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "tenantId must be a uuid: " + request.tenantId());
        }
        ReportRunner.Resolved resolved = runner.resolve(tenantId, request.app(),
                request.reportId());
        String format = request.effectiveFormat();
        if (!format.equals("csv") && !format.equals("xlsx")) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "delivery format must be csv or xlsx: " + format);
        }
        Map<String, Object> run = runner.runScheduled(tenantId, request.app(),
                request.reportId(), request.effectiveRunAsRole(), request.params());
        Set<String> moneyColumns = ReportRunner.moneyColumns(resolved);
        byte[] rendered = format.equals("xlsx")
                ? exporter.xlsx(run, resolved.report(), moneyColumns, Locale.getDefault())
                : exporter.csv(run, moneyColumns, Locale.getDefault());
        // recipients ride the job's authored app roles — expanded here to the full
        // platform role names the Notification Service resolves holders by
        List<String> roles = recipientList(request.recipients(), "roles").stream()
                .map(role -> role.contains(".") ? role : request.app() + "." + role).toList();
        List<String> users = recipientList(request.recipients(), "users");
        if (roles.isEmpty() && users.isEmpty()) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "deliveries require recipients {roles: […], users: […]}");
        }
        Map<String, Object> delivered = delivery.deliver(tenantId, request.reportId(),
                request.app(), roles, users, format, rendered);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", "delivered");
        summary.put("report", request.reportId());
        summary.put("runAsRole", request.effectiveRunAsRole());
        summary.put("rows", ((List<?>) run.getOrDefault("rows", List.of())).size());
        summary.put("format", format);
        summary.put("delivery", delivered);
        return summary;
    }

    private static List<String> recipientList(Map<String, Object> recipients, String key) {
        if (recipients == null || !(recipients.get(key) instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

}
