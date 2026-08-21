package com.novaforge.runtime.api;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keycloak user provisioning over the Admin API — deployed configuration, not bespoke
 * identity code (PHASE-2 §10, ARCHITECTURE.md §7): the runtime's service account holds
 * {@code realm-management/manage-users} in the realm export; the single-realm strategy
 * (PHASE-0 §12 Q1) carries the tenant as the user's {@code tenant_id} attribute, mapped
 * into tokens by the novaforge.api client scope.
 */
@Component
public class KeycloakUserProvisioner implements com.novaforge.runtime.engine.admin.UserProvisioner {

    private final RestClient authClient;
    private final RestClient adminClient;
    private final String serviceClientId;
    private final String serviceClientSecret;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AtomicReference<Map<String, Object>> token = new AtomicReference<>();

    public KeycloakUserProvisioner(
            @Value("${novaforge.auth.issuer-uri:http://localhost:8082/realms/novaforge}") String issuer,
            @Value("${novaforge.auth.service-client.id:novaforge-runtime}") String serviceClientId,
            @Value("${novaforge.auth.service-client.secret:novaforge-runtime-secret}") String serviceClientSecret) {
        this.authClient = RestClient.builder().baseUrl(issuer).build();
        this.adminClient = RestClient.builder().baseUrl(issuer + "/admin/realms/novaforge").build();
        this.serviceClientId = serviceClientId;
        this.serviceClientSecret = serviceClientSecret;
    }

    /**
     * Creates the user in the realm (tenant attribute pinned) and returns the user id —
     * the same id the platform DB keys on; idempotent by username.
     */
    @Override
    public UUID createUser(String username, String email, UUID tenantId) {
        Map<String, Object> existing = findUser(username);
        if (existing != null) {
            return UUID.fromString(String.valueOf(existing.get("id")));
        }
        Map<String, Object> user = Map.of(
                "username", username,
                "enabled", true,
                "email", email == null ? (username + "@novaforge.local") : email,
                "emailVerified", true,
                "requiredActions", new String[0],
                "attributes", Map.of("tenant_id", new String[] {tenantId.toString()}));
        adminClient.post()
                .uri("/users")
                .headers(headers -> headers.setBearerAuth(serviceToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(user))
                .retrieve()
                .toBodilessEntity();
        Map<String, Object> created = findUser(username);
        if (created == null) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "keycloak user creation did not converge for " + username);
        }
        return UUID.fromString(String.valueOf(created.get("id")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findUser(String username) {
        Object found = adminClient.get()
                .uri(uri -> uri.path("/users").queryParam("username", username).build())
                .headers(headers -> headers.setBearerAuth(serviceToken()))
                .retrieve()
                .body(Object.class);
        if (found instanceof java.util.List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> first) {
            return (Map<String, Object>) first;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String serviceToken() {
        Map<String, Object> cached = token.get();
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
        Map<String, Object> response = authClient.post()
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
        token.set(response);
        return (String) response.get("access_token");
    }
}
