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
 * The {@code processStart} target's client (PHASE-4 §7/§9): the Workflow Service's
 * internal start surface with the platform service client's token — the process
 * fires under the engine's per-app system principal, like every engine action.
 */
@Component
public class RestProcessTarget {

    private final RestClient workflow;
    private final RestClient auth;
    private final String clientId;
    private final String clientSecret;
    private final AtomicReference<Grant> grant = new AtomicReference<>();

    private record Grant(String token, long refreshAt) {
    }

    public RestProcessTarget(
            @Value("${novaforge.workflow.url:http://localhost:8086}") String workflowUrl,
            @Value("${novaforge.auth.issuer-uri:http://localhost:8082/realms/novaforge}") String issuer,
            @Value("${novaforge.auth.service-client.id:novaforge-runtime}") String clientId,
            @Value("${novaforge.auth.service-client.secret:novaforge-runtime-secret}") String clientSecret) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(30_000);   // the start may run synchronous BPMN legs
        this.workflow = RestClient.builder().baseUrl(workflowUrl).requestFactory(factory).build();
        this.auth = RestClient.builder().baseUrl(issuer).requestFactory(factory).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    /** Starts the process; returns the engine instance id. */
    @SuppressWarnings("unchecked")
    public String run(UUID tenantId, String appApiName, String process, String recordId,
                      Map<String, Object> variables) {
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("tenantId", tenantId.toString());
            body.put("app", appApiName);
            body.put("process", process);
            if (recordId != null && !recordId.isBlank()) {
                body.put("recordId", recordId);
            }
            if (variables != null && !variables.isEmpty()) {
                body.put("variables", variables);
            }
            Map<String, Object> response = workflow.post()
                    .uri("/api/v1/workflow/internal/processes/start")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(serviceToken()))
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            return response == null ? null : String.valueOf(response.get("instanceId"));
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "process start failed for " + process + ": " + e.getMessage(), null, e);
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
