package com.novaforge.workflow.roles;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The {@link RoleLookup} binding: client-credentials token from Keycloak, then the
 * runtime's admin read surface — the same trusted-service path the test harness uses
 * (ADR-010). Answers are short-lived in-flight (one call per inbox request); no
 * caching of authorization data beyond the request.
 */
@Component
public class RestRoleLookup implements RoleLookup {

    private final RestClient runtime;
    private final RestClient auth;
    private final String clientId;
    private final String clientSecret;
    /** Cached grant: the token plus the instant it should refresh. */
    private record Grant(String token, long refreshAt) {
    }

    private final AtomicReference<Grant> token = new AtomicReference<>();

    public RestRoleLookup(@Value("${novaforge.data-runtime.url:http://localhost:8083}") String runtimeUrl,
                          @Value("${novaforge.auth.issuer-uri:http://localhost:8082/realms/novaforge}") String issuer,
                          @Value("${novaforge.auth.service-client.id:novaforge-runtime}") String clientId,
                          @Value("${novaforge.auth.service-client.secret:novaforge-runtime-secret}") String clientSecret) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(5_000);
        this.runtime = RestClient.builder().baseUrl(runtimeUrl).requestFactory(factory).build();
        this.auth = RestClient.builder().baseUrl(issuer).requestFactory(factory).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> of(UUID tenantId, UUID actor) {
        try {
            List<String> roles = runtime.method(HttpMethod.GET)
                    .uri("/api/v1/admin/tenants/" + tenantId + "/users/" + actor + "/roles")
                    .headers(headers -> headers.setBearerAuth(serviceToken()))
                    .retrieve()
                    .body(ArrayList.class);
            return roles == null ? List.of() : List.copyOf(roles);
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "role lookup failed for " + actor + ": " + e.getMessage(), null, e);
        }
    }

    @SuppressWarnings("unchecked")
    private String serviceToken() {
        Grant cached = token.get();
        if (cached != null && System.currentTimeMillis() < cached.refreshAt()) {
            return cached.token();
        }
        Map<String, Object> granted = auth.post()
                .uri("/protocol/openid-connect/token")
                .headers(headers -> headers.setContentType(
                        org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED))
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
        long refreshAt = System.currentTimeMillis() + Math.max(0, seconds - 30) * 1000;
        token.set(new Grant(String.valueOf(granted.get("access_token")), refreshAt));
        return String.valueOf(granted.get("access_token"));
    }
}
