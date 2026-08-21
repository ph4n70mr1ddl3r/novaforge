package com.novaforge.runtime.api;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import java.time.Instant;
import java.util.List;
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
        // The admin API hangs off the server root, not the realm URL: strip /realms/<realm>.
        String serverRoot = issuer.contains("/realms/")
                ? issuer.substring(0, issuer.indexOf("/realms/"))
                : issuer;
        this.adminClient = RestClient.builder()
                .baseUrl(serverRoot + "/admin/realms/" + realmOf(issuer)).build();
        this.serviceClientId = serviceClientId;
        this.serviceClientSecret = serviceClientSecret;
    }

    /**
     * Declares {@code tenant_id}/{@code platform_roles} as managed user-profile
     * attributes and enables unmanaged attributes — without this, Keycloak 26 drops
     * admin-API attribute writes silently and provisioned tokens carry no tenant claim.
     * Idempotent; runs once per boot before any provisioning.
     */
    @jakarta.annotation.PostConstruct
    void ensureUserProfile() {
        try {
            var profile = adminClient.get()
                    .uri("/users/profile")
                    .headers(headers -> headers.setBearerAuth(serviceToken()))
                    .retrieve()
                    .body(java.util.Map.class);
            if (profile == null) {
                return;
            }
            @SuppressWarnings("unchecked")
            java.util.List<java.util.Map<String, Object>> attributes =
                    (java.util.List<java.util.Map<String, Object>>) profile.get("attributes");
            boolean changed = false;
            for (String[] declaration : new String[][] {{"tenant_id", "false"}, {"platform_roles", "true"}}) {
                boolean present = attributes.stream()
                        .anyMatch(a -> declaration[0].equals(a.get("name")));
                if (!present) {
                    attributes.add(new java.util.LinkedHashMap<>(java.util.Map.of(
                            "name", declaration[0],
                            "displayName", declaration[0],
                            "multivalued", Boolean.parseBoolean(declaration[1]),
                            "permissions", java.util.Map.of("view", List.of("admin"), "edit", List.of("admin")))));
                    changed = true;
                }
            }
            if (!"ENABLED".equals(profile.get("unmanagedAttributePolicy"))) {
                profile.put("unmanagedAttributePolicy", "ENABLED");
                changed = true;
            }
            if (changed) {
                adminClient.put()
                        .uri("/users/profile")
                        .headers(headers -> headers.setBearerAuth(serviceToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(mapper.writeValueAsString(profile))
                        .retrieve()
                        .toBodilessEntity();
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(KeycloakUserProvisioner.class)
                    .warn("could not ensure the user profile declares tenant_id/platform_roles: {}", e.getMessage());
        }
    }

    private static String realmOf(String issuer) {
        int index = issuer.indexOf("/realms/");
        return index < 0 ? "novaforge" : issuer.substring(index + "/realms/".length());
    }

    /**
     * Creates the user in the realm (tenant attribute pinned) and returns the user id —
     * the same id the platform DB keys on; idempotent by username. When a password is
     * supplied it is set as a non-temporary credential (synthetic actors, scratch admins).
     */
    @Override
    public UUID createUser(String username, String email, UUID tenantId) {
        return createUser(username, email, tenantId, null);
    }

    public UUID createUser(String username, String email, UUID tenantId, String password) {
        return createUser(username, email, tenantId, password, java.util.List.of());
    }

    /** Full form: platform roles ride the platform_roles user attribute (token claim). */
    public UUID createUser(String username, String email, UUID tenantId, String password,
                           java.util.List<String> platformRoles) {
        Map<String, Object> existing = findUser(username);
        if (existing != null) {
            UUID id = UUID.fromString(String.valueOf(existing.get("id")));
            if (password != null) {
                resetPassword(id, password);
            }
            return id;
        }
        // firstName/lastName satisfied: Keycloak 26's Verify Profile action blocks
        // first login without them — provisioned actors must be grantable immediately.
        Map<String, Object> user = Map.of(
                "username", username,
                "enabled", true,
                "email", email == null ? (username + "@novaforge.local") : email,
                "emailVerified", true,
                "firstName", "Scratch",
                "lastName", "Actor",
                "requiredActions", new String[0],
                "attributes", Map.of(
                        "tenant_id", new String[] {tenantId.toString()},
                        "platform_roles", platformRoles.toArray(new String[0])));
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
        UUID id = UUID.fromString(String.valueOf(created.get("id")));
        if (password != null) {
            resetPassword(id, password);
        }
        return id;
    }

    private void resetPassword(UUID userId, String password) {
        adminClient.put()
                .uri("/users/" + userId + "/reset-password")
                .headers(headers -> headers.setBearerAuth(serviceToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(Map.of("type", "password", "value", password,
                        "temporary", false)))
                .retrieve()
                .toBodilessEntity();
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
