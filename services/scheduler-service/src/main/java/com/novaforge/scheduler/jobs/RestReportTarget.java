package com.novaforge.scheduler.jobs;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The {@code report} target's client (PHASE-5 §7 — the target registered dormant in
 * Phase 4, activated here): the Reporting Service's internal delivery surface with
 * the platform service client's token. The run executes under the job's
 * {@code runAsRole} — the system principal over an explicitly permissioned scope —
 * and delivery rides the Notification Service; failures propagate so the run history
 * records them audibly.
 */
@Component
public class RestReportTarget {

    private final RestClient reports;
    private final RestClient auth;
    private final String clientId;
    private final String clientSecret;
    private final AtomicReference<Grant> grant = new AtomicReference<>();

    private record Grant(String token, long refreshAt) {
    }

    public RestReportTarget(
            @Value("${novaforge.reporting.url:http://localhost:8089}") String reportingUrl,
            @Value("${novaforge.auth.issuer-uri:http://localhost:8082/realms/novaforge}") String issuer,
            @Value("${novaforge.auth.service-client.id:novaforge-runtime}") String clientId,
            @Value("${novaforge.auth.service-client.secret:novaforge-runtime-secret}") String clientSecret) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(60_000);   // the delivery renders the export inline (§7)
        this.reports = RestClient.builder().baseUrl(reportingUrl).requestFactory(factory).build();
        this.auth = RestClient.builder().baseUrl(issuer).requestFactory(factory).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    /** Fires the scheduled delivery; returns the reporting service's summary. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(UUID tenantId, String appApiName, Map<String, Object> params) {
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("tenantId", tenantId.toString());
            body.put("app", appApiName);
            body.put("reportId", String.valueOf(params.get("reportId")));
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
                    .headers(headers -> headers.setBearerAuth(serviceToken()))
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "report delivery failed for " + params.get("reportId") + ": "
                            + e.getMessage(), null, e);
        }
    }

    @SuppressWarnings("unchecked")
    private String serviceToken() {
        Grant cached = grant.get();
        if (cached != null && System.currentTimeMillis() < cached.refreshAt()) {
            return cached.token();
        }
        Map<String, Object> granted = auth.post()
                .uri("/protocol/openid-connect/token")
                .headers(headers -> headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED))
                .body("grant_type=client_credentials&client_id=" + clientId
                        + "&client_secret=" + clientSecret)
                .retrieve()
                .body(Map.class);
        if (granted == null || granted.get("access_token") == null) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "service token grant returned no token");
        }
        long seconds = granted.get("expires_in") instanceof Number number
                ? number.longValue() : 0;
        grant.set(new Grant(String.valueOf(granted.get("access_token")),
                System.currentTimeMillis() + Math.max(0, seconds - 30) * 1000));
        return String.valueOf(granted.get("access_token"));
    }
}
