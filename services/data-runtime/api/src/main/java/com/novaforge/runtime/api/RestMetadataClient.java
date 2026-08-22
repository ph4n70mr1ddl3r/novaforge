package com.novaforge.runtime.api;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.runtime.engine.metadata.MetadataClient;
import com.novaforge.common.error.PlatformException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
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
    private final RestClient tokenClient;
    private final String issuer;
    private final String serviceClientId;
    private final String serviceClientSecret;

    /** Cached client-credentials token for context-free calls (startup catch-up, subscriber). */
    private final AtomicReference<Map<String, Object>> serviceToken = new AtomicReference<>();

    public RestMetadataClient(@Value("${novaforge.metadata.url:http://localhost:8081}") String baseUrl,
                              @Value("${novaforge.auth.issuer-uri:http://localhost:8082/realms/novaforge}") String issuer,
                              @Value("${novaforge.auth.service-client.id:novaforge-runtime}") String serviceClientId,
                              @Value("${novaforge.auth.service-client.secret:novaforge-runtime-secret}") String serviceClientSecret) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.tokenClient = RestClient.builder().baseUrl(issuer).build();
        this.issuer = issuer;
        this.serviceClientId = serviceClientId;
        this.serviceClientSecret = serviceClientSecret;
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

    /**
     * Principal relay: user requests forward their bearer token; context-free calls
     * (startup catch-up, the publish subscriber) fall back to the service account.
     */
    private void relayAuth(HttpHeaders headers) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String authorization = attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization != null && !authorization.isBlank()) {
                headers.set(HttpHeaders.AUTHORIZATION, authorization);
                return;
            }
        }
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken());
    }

    @SuppressWarnings("unchecked")
    private String serviceToken() {
        Map<String, Object> cached = serviceToken.get();
        if (cached != null) {
            long expiresAt = ((Number) cached.getOrDefault("expires_at", 0L)).longValue();
            if (Instant.now().getEpochSecond() < expiresAt - 30) {
                return (String) cached.get("access_token");
            }
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", serviceClientId);
        form.add("client_secret", serviceClientSecret);
        Map<String, Object> response = tokenClient.post()
                .uri("/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        if (response == null || response.get("access_token") == null) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "service-account token grant failed for " + serviceClientId);
        }
        long expiresIn = response.get("expires_in") instanceof Number n ? n.longValue() : 300L;
        response.put("expires_at", Instant.now().getEpochSecond() + expiresIn);
        serviceToken.set(response);
        return (String) response.get("access_token");
    }
}
