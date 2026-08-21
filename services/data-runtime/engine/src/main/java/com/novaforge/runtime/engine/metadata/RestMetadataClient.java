package com.novaforge.runtime.engine.metadata;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/**
 * RestClient binding to the Metadata Service (PHASE-1 §4): the published-apps index and
 * per-app published bundles, with the caller's bearer token relayed from the incoming
 * request.
 */
@Component
public class RestMetadataClient implements MetadataClient {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final RestClient restClient;

    public RestMetadataClient(@Value("${novaforge.metadata-service.url:http://localhost:8081}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public List<PublishedApp> publishedApps() {
        return exchange("/api/v1/metadata/published-apps",
                new ParameterizedTypeReference<List<PublishedApp>>() {
                });
    }

    @Override
    public PublishedBundle publishedBundle(UUID appId) {
        return exchange("/api/v1/metadata/apps/" + appId + "/published",
                new ParameterizedTypeReference<PublishedBundle>() {
                });
    }

    private <T> T exchange(String path, ParameterizedTypeReference<T> type) {
        try {
            return restClient.method(HttpMethod.GET)
                    .uri(path)
                    .headers(this::relayAuth)
                    .retrieve()
                    .body(type);
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "metadata service call failed: " + e.getMessage(), null, e);
        }
    }

    private void relayAuth(HttpHeaders headers) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String authorization = attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization != null && !authorization.isBlank()) {
                headers.set(HttpHeaders.AUTHORIZATION, authorization);
            }
        }
    }
}
