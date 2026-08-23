package com.novaforge.workflow.process;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.security.ServiceTokenClient;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The {@link RecordFieldsSource} binding: the Data Runtime's internal record read
 * with the shared service client's token (the same trusted gate the resume
 * surface uses). NOT_FOUND maps to null — the record is gone, evaluation skips.
 */
@Component
public class RestRecordFieldsSource implements RecordFieldsSource {

    private final RestClient runtime;
    private final ServiceTokenClient serviceToken;

    public RestRecordFieldsSource(
            @Value("${novaforge.data-runtime.url:http://localhost:8083}") String runtimeUrl,
            ServiceTokenClient serviceToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(5_000);
        this.runtime = RestClient.builder().baseUrl(runtimeUrl).requestFactory(factory).build();
        this.serviceToken = serviceToken;
    }

    @Override
    public Map<String, Object> fields(UUID tenantId, String app, String entity, UUID recordId) {
        try {
            return runtime.method(HttpMethod.GET)
                    .uri(uri -> uri.path("/api/v1/hooks/records/" + recordId)
                            .queryParam("tenantId", tenantId.toString())
                            .queryParam("app", app)
                            .queryParam("entity", entity)
                            .build())
                    .headers(headers -> headers.setBearerAuth(serviceToken.token()))
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
        } catch (org.springframework.web.client.RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return null;   // record gone — evaluation skips, no start
            }
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "record fetch failed for " + entity + "/" + recordId + ": "
                            + e.getStatusCode().value() + " " + e.getResponseBodyAsString(), null, e);
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "record fetch failed for " + entity + "/" + recordId + ": " + e.getMessage(),
                    null, e);
        }
    }
}
