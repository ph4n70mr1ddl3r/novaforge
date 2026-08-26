package com.novaforge.script.api;

import com.novaforge.common.context.TenantContext;
import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.script.engine.HttpProxy;
import com.novaforge.security.ServiceTokenClient;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@link HttpProxy} binding (PHASE-6 §4): the connector sandbox's egress —
 * the Integration Service's internal execution surface with the shared service
 * client's token. The §4 timeout/breaker/retry policy lives in the executor; this
 * client only needs to outlive one bounded attempt. Never a raw socket: the
 * sandbox's {@code IOAccess.NONE} holds, and {@code $http} is the only egress.
 */
@Component
public class IntegrationHttpProxy implements HttpProxy {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final RestClient integration;
    private final ServiceTokenClient serviceToken;

    public IntegrationHttpProxy(
            @Value("${novaforge.integration.url:http://localhost:8090}") String baseUrl,
            ServiceTokenClient serviceToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(11_000);   // one §4-bounded attempt, outlived
        this.integration = RestClient.builder().baseUrl(baseUrl)
                .requestFactory(factory).build();
        this.serviceToken = serviceToken;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object call(TenantContext.Context caller, String app, String connector,
                       String operation, Map<String, Object> template) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tenantId", caller.tenantId());
            body.put("app", app);
            body.put("connector", connector);
            body.put("operation", operation);
            body.put("template", template == null ? Map.of() : template);
            return integration.method(HttpMethod.POST)
                    .uri("/api/v1/integrations/internal/execute")
                    .headers(headers -> headers.setBearerAuth(serviceToken.token()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(MAPPER.writeValueAsString(body))
                    .retrieve()
                    .body(Map.class);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "$http call to " + connector + "." + operation + " failed: HTTP "
                            + e.getStatusCode() + " " + e.getResponseBodyAsString(), null, e);
        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "$http call unreachable: " + e.getMessage(), null, e);
        }
    }
}
