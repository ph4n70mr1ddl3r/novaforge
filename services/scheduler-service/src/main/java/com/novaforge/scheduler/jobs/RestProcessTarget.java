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
 * The {@code processStart} target's client (PHASE-4 §7/§9): the Workflow Service's
 * internal start surface with the shared service client's token — the process
 * fires under the engine's per-app system principal, like every engine action.
 */
@Component
public class RestProcessTarget {

    private final RestClient workflow;
    private final ServiceTokenClient serviceToken;

    public RestProcessTarget(
            @Value("${novaforge.workflow.url:http://localhost:8086}") String workflowUrl,
            ServiceTokenClient serviceToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(30_000);   // the start may run synchronous BPMN legs
        this.workflow = RestClient.builder().baseUrl(workflowUrl).requestFactory(factory).build();
        this.serviceToken = serviceToken;
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
                    .headers(headers -> headers.setBearerAuth(serviceToken.token()))
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            return response == null ? null : String.valueOf(response.get("instanceId"));
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "process start failed for " + process + ": " + e.getMessage(), null, e);
        }
    }
}
