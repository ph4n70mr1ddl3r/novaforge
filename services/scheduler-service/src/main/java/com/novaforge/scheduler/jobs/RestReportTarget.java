package com.novaforge.scheduler.jobs;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.security.ServiceTokenClient;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The {@code report} target's client (PHASE-5 §7 — the target registered dormant in
 * Phase 4, activated here): the Reporting Service's internal delivery surface with
 * the shared service client's token. The run executes under the job's
 * {@code runAsRole} — the system principal over an explicitly permissioned scope —
 * and delivery rides the Notification Service; failures propagate so the run history
 * records them audibly.
 */
@Component
public class RestReportTarget {

    private final RestClient reports;
    private final ServiceTokenClient serviceToken;

    public RestReportTarget(
            @Value("${novaforge.reporting.url:http://localhost:8089}") String reportingUrl,
            ServiceTokenClient serviceToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(60_000);   // the delivery renders the export inline (§7)
        this.reports = RestClient.builder().baseUrl(reportingUrl).requestFactory(factory).build();
        this.serviceToken = serviceToken;
    }

    /** Fires the scheduled delivery; returns the reporting service's summary. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(UUID tenantId, String appApiName, Map<String, Object> params,
                                   String deliveryId) {
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("tenantId", tenantId.toString());
            body.put("app", appApiName);
            body.put("reportId", String.valueOf(params.get("reportId")));
            if (deliveryId != null && !deliveryId.isBlank()) {
                body.put("deliveryId", deliveryId);
            }
            if (params.get("params") instanceof Map<?, ?> runParams) {
                body.put("params", runParams);
            }
            if (params.get("runAsRole") != null) {
                body.put("runAsRole", String.valueOf(params.get("runAsRole")));
            }
            if (params.get("recipients") instanceof Map<?, ?> recipients) {
                body.put("recipients", recipients);
            }
            if (params.get("format") != null) {
                body.put("format", String.valueOf(params.get("format")));
            }
            return reports.post()
                    .uri("/api/v1/reports/internal/deliver")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(serviceToken.token()))
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "report delivery failed for " + params.get("reportId") + ": "
                            + e.getMessage(), null, e);
        }
    }
}
