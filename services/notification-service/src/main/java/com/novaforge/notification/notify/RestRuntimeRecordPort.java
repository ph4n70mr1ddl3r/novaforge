package com.novaforge.notification.notify;

import com.novaforge.security.ServiceTokenClient;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The {@link RuntimeRecordPort} binding: the Data Runtime's internal record read
 * (the PHASE-4 §9 event-start surface) with the shared service client's token.
 * Best-effort by design — the fetch enriches template tokens; a gone record or an
 * unreachable runtime renders empty tokens and the fan-out still delivers (the
 * {@code task.*} event stays the assertable surface).
 */
@Component
public class RestRuntimeRecordPort implements RuntimeRecordPort {

    private static final Logger LOG = LoggerFactory.getLogger(RestRuntimeRecordPort.class);

    private final RestClient runtime;
    private final ServiceTokenClient serviceToken;

    public RestRuntimeRecordPort(
            @Value("${novaforge.data-runtime.url:http://localhost:8083}") String runtimeUrl,
            ServiceTokenClient serviceToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(5_000);
        this.runtime = RestClient.builder().baseUrl(runtimeUrl).requestFactory(factory).build();
        this.serviceToken = serviceToken;
    }

    @Override
    public Map<String, Object> recordOf(UUID tenantId, String entityKey, UUID recordId) {
        int dot = entityKey == null ? -1 : entityKey.indexOf('.');
        if (dot <= 0 || recordId == null) {
            return Map.of();   // not an app-qualified entity (a process-keyed task)
        }
        try {
            Map<String, Object> record = runtime.get()
                    .uri("/api/v1/hooks/records/{id}?tenantId={tenant}&app={app}&entity={entity}",
                            recordId, tenantId, entityKey.substring(0, dot),
                            entityKey.substring(dot + 1))
                    .headers(headers -> headers.setBearerAuth(serviceToken.token()))
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            return record == null ? Map.of() : record;
        } catch (Exception e) {
            LOG.warn("record fetch for template tokens failed ({} {}): {} — tokens render empty",
                    entityKey, recordId, e.getMessage());
            return Map.of();
        }
    }
}
