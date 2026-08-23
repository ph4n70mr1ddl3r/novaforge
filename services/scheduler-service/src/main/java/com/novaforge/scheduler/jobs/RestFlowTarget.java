package com.novaforge.scheduler.jobs;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.scheduler.jobs.JobRunner.FlowTarget;
import com.novaforge.security.ServiceTokenClient;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The {@link FlowTarget} binding (PHASE-4 §7): the Data Runtime's internal
 * scheduled-hook surface with the shared service client's token — the
 * compiled-graph engine, per-app system principal, synthetic {@code scheduled}
 * trigger context ({@code $record} absent).
 */
@Component
public class RestFlowTarget implements FlowTarget {

    private final RestClient runtime;
    private final ServiceTokenClient serviceToken;

    public RestFlowTarget(
            @Value("${novaforge.data-runtime.url:http://localhost:8083}") String runtimeUrl,
            ServiceTokenClient serviceToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(60_000);   // a flow graph gets its budget
        this.runtime = RestClient.builder().baseUrl(runtimeUrl).requestFactory(factory).build();
        this.serviceToken = serviceToken;
    }

    @Override
    public void run(UUID tenantId, String appApiName, String entityApiName, String hookName) {
        try {
            runtime.post()
                    .uri("/api/v1/hooks/scheduled")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(serviceToken.token()))
                    .body(Map.of("tenantId", tenantId.toString(), "app", appApiName,
                            "entityApiName", entityApiName, "hook", hookName))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "scheduled flow failed for " + hookName + ": " + e.getMessage(), null, e);
        }
    }
}
