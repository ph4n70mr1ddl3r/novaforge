package com.novaforge.runtime.api;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.FlowStep;
import com.novaforge.runtime.engine.hook.ApprovalClient;
import com.novaforge.security.ServiceClientGate;
import com.novaforge.security.ServiceTokenClient;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@link ApprovalClient} binding (PHASE-4 §4): {@code requestApproval} hands the
 * suspension to the Workflow Service's internal surface with the shared service
 * client's token — engine-driven actions run as the per-app system principal, and the
 * initiating actor travels in the payload for segregation-of-duties filtering. The
 * Workflow Service maps problem bodies back (SOD_VIOLATION renders onto the write
 * path like any validation failure).
 */
@Component
public class RestApprovalClient implements ApprovalClient {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final RestClient restClient;
    private final ServiceTokenClient serviceToken;

    public RestApprovalClient(@Value("${novaforge.workflow.url:http://localhost:8086}")
                              String baseUrl,
                              ServiceTokenClient serviceToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(10_000);
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.serviceToken = serviceToken;
    }

    @Override
    public void request(Suspension suspension) {
        try {
            restClient.post()
                    .uri("/api/v1/workflow/internal/approvals")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(serviceToken.token()))
                    .body(MAPPER.writeValueAsString(payload(suspension)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (org.springframework.web.client.RestClientResponseException e) {
            throw map(e);
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "approval request failed: " + e.getMessage(), null, e);
        }
    }

    private static java.util.Map<String, Object> payload(Suspension suspension) {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("tenantId", suspension.tenantId().toString());
        payload.put("app", suspension.appApiName());
        payload.put("entityApiName", suspension.entityApiName());
        payload.put("entityKey", suspension.entityKey());
        payload.put("recordId", suspension.recordId().toString());
        payload.put("hook", String.valueOf(suspension.hookName()));
        payload.put("stepId", String.valueOf(suspension.stepId()));
        payload.put("afterStep", suspension.afterStep() == null ? "" : suspension.afterStep());
        payload.put("onReject", suspension.onReject() == null ? ""
                : MAPPER.writeValueAsString(suspension.onReject()));
        payload.put("approversRole", suspension.approversRole() == null ? ""
                : suspension.approversRole());
        payload.put("approverUsers", suspension.approverUsers() == null
                ? java.util.List.of() : suspension.approverUsers());
        payload.put("mode", suspension.mode());
        payload.put("timeout", suspension.timeout() == null ? "" : suspension.timeout());
        payload.put("escalateTo", suspension.escalateTo() == null ? ""
                : suspension.escalateTo());
        payload.put("initiatingActor", suspension.initiatingActor() == null ? ""
                : suspension.initiatingActor().toString());
        payload.put("transition", suspension.transition() == null ? ""
                : suspension.transition());
        return payload;
    }

    /** The Workflow Service's problem+json rendered back onto the write path. */
    private static PlatformException map(
            org.springframework.web.client.RestClientResponseException e) {
        String detail = e.getResponseBodyAsString();
        try {
            JsonNode problem = MAPPER.readTree(detail);
            String code = problem.path("code").asString(null);
            String message = problem.path("detail").asString("approval request failed");
            for (PlatformErrorCode known : PlatformErrorCode.values()) {
                if (known.code().equals(code)) {
                    return new PlatformException(known, message);
                }
            }
        } catch (Exception ignored) {
            // not a problem body — fall through
        }
        return new PlatformException(PlatformErrorCode.INTERNAL,
                "approval request failed (status " + e.getStatusCode().value() + ")", null, e);
    }

    @Override
    public java.lang.String toString() {
        return "RestApprovalClient(" + ServiceClientGate.CLIENT_ID + ")";
    }
}
