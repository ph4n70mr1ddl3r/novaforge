package com.novaforge.metadata.lifecycle;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The default {@link EnvironmentProvisioner} (PHASE-8 §2): provisions the
 * environment's tenant through the platform-admin API (the Phase 3 scratch
 * mechanism's provisioning path, minus the per-run wipe), imports the promoted
 * bundle as an app in that tenant, and publishes it — the environment pins the
 * version and owns an isolated data plane. One mechanism, three names.
 */
@Component
public class HttpEnvironmentProvisioner implements EnvironmentProvisioner {

    private final RestClient runtime;
    private final RestClient metadata;
    private final RestClient auth;
    private final String clientId;
    private final String clientSecret;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public HttpEnvironmentProvisioner(
            @Value("${novaforge.data-runtime.url:http://localhost:8083}") String runtimeUrl,
            @Value("${novaforge.metadata.url:http://localhost:8081}") String metadataUrl,
            @Value("${novaforge.auth.issuer-uri:http://localhost:8082/realms/novaforge}") String issuer,
            @Value("${novaforge.auth.service-client.id:novaforge-runtime}") String clientId,
            @Value("${novaforge.auth.service-client.secret:novaforge-runtime-secret}") String clientSecret) {
        // bounded like every sibling client: provisioning runs synchronously inside
        // the promote/rollback HTTP request — a hung leg wedged the builder's
        // request thread indefinitely
        this.runtime = RestClient.builder().baseUrl(runtimeUrl)
                .requestFactory(timedFactory()).build();
        this.metadata = RestClient.builder().baseUrl(metadataUrl)
                .requestFactory(timedFactory()).build();
        this.auth = RestClient.builder().baseUrl(issuer)
                .requestFactory(timedFactory()).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    private static org.springframework.http.client.ClientHttpRequestFactory timedFactory() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(10_000);
        return factory;
    }

    /**
     * Idempotent per (tenant, app, env): the environment tenant's name derives from
     * the key — a retried or crashed promotion adopts the existing tenant (the
     * by-name lookup) instead of provisioning a second one, the admin credential is
     * regenerated onto the same deterministic username (user provisioning is
     * idempotent and resets the password), and an app left by a partial prior attempt
     * is recreated (md_apps pins one apiName per tenant) before publishing. Every leg
     * converges to the same environment identity.
     */
    @Override
    public EnvironmentRef provision(UUID sourceTenantId, AppDefinition bundle, String envName) {
        String key = bundle.apiName() + "-" + envName + "-"
                + sourceTenantId.toString().substring(0, 8);
        String tenantName = key.toLowerCase();
        String adminUsername = ("env-" + envName + "-" + sourceTenantId.toString()
                .substring(0, 8)).toLowerCase();
        String adminPassword = "env-" + UUID.randomUUID();

        // adopt-before-create: a crashed first promotion leaves the tenant behind
        String tenantId = adoptTenant(tenantName);
        if (tenantId == null) {
            Map<String, Object> tenant = runtime.method(HttpMethod.POST)
                    .uri("/api/v1/admin/tenants")
                    .headers(headers -> headers.setBearerAuth(serviceToken()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(Map.of(
                            "apiName", tenantName,
                            "adminUsername", adminUsername,
                            "adminPassword", adminPassword)))
                    .retrieve()
                    .body(Map.class);
            tenantId = String.valueOf(tenant.get("tenantId"));
        } else {
            // the adopted tenant's admin still holds the password from the crashed
            // attempt (long lost) — user provisioning is idempotent and resets the
            // credential, so this attempt can grant with the fresh one. Without this
            // leg every retry died at the password grant and the intent never cleared.
            runtime.post()
                    .uri("/api/v1/admin/tenants/" + tenantId + "/users")
                    .headers(headers -> headers.setBearerAuth(serviceToken()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(Map.of(
                            "username", adminUsername,
                            "password", adminPassword)))
                    .retrieve()
                    .toBodilessEntity();
        }
        String token = passwordGrant(adminUsername, adminPassword);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apiName", bundle.apiName());
        body.put("label", bundle.label() == null ? bundle.apiName() : bundle.label());
        body.put("settings", mapper.convertValue(bundle.settings(), Map.class));
        body.put("entities", mapper.convertValue(bundle.entities(), List.class));
        body.put("permissionSet", mapper.convertValue(bundle.permissionSet(), Map.class));
        body.put("integrations", mapper.convertValue(bundle.integrations(), Map.class));
        body.put("stateMachines", mapper.convertValue(bundle.stateMachines(), List.class));
        body.put("slas", mapper.convertValue(bundle.slas(), List.class));
        body.put("jobs", mapper.convertValue(bundle.jobs(), List.class));
        body.put("workflows", mapper.convertValue(bundle.workflows(), List.class));
        body.put("reports", mapper.convertValue(bundle.reports(), List.class));
        body.put("dashboards", mapper.convertValue(bundle.dashboards(), List.class));
        body.put("translations", mapper.convertValue(bundle.translations(), List.class));
        // a partial prior attempt may have left the app behind (md_apps pins one
        // apiName per tenant) — retire it and import fresh, so the retry converges
        String existingApp = existingApp(token, bundle.apiName());
        if (existingApp != null) {
            metadata.delete()
                    .uri("/api/v1/metadata/apps/" + existingApp)
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .toBodilessEntity();
        }
        Map<String, Object> created = metadata.post()
                .uri("/api/v1/metadata/apps")
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(body))
                .retrieve()
                .body(Map.class);
        metadata.post()
                .uri("/api/v1/metadata/apps/" + created.get("id") + "/publish")
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .body(String.class);
        return new EnvironmentRef(UUID.fromString(tenantId),
                UUID.fromString(String.valueOf(created.get("id"))));
    }

    /** The by-name adopt leg: the environment tenant from a crashed attempt, if any. */
    private String adoptTenant(String tenantName) {
        try {
            Map<?, ?> found = runtime.get()
                    .uri(uri -> uri.path("/api/v1/admin/tenants")
                            .queryParam("apiName", tenantName).build())
                    .headers(headers -> headers.setBearerAuth(serviceToken()))
                    .retrieve()
                    .body(Map.class);
            return found == null ? null : String.valueOf(found.get("tenantId"));
        } catch (org.springframework.web.client.RestClientResponseException notFound) {
            return null;   // 404 — nothing to adopt
        }
    }

    /** The app a partial prior attempt left in the environment tenant, if any. */
    private String existingApp(String token, String apiName) {
        java.util.List<?> apps = metadata.get()
                .uri("/api/v1/metadata/apps")
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .body(java.util.List.class);
        if (apps == null) {
            return null;
        }
        for (Object app : apps) {
            if (app instanceof Map<?, ?> row && apiName.equals(String.valueOf(row.get("apiName")))) {
                return String.valueOf(row.get("id"));
            }
        }
        return null;
    }

    private String passwordGrant(String username, String password) {
        Map<String, Object> response = auth.post()
                .uri("/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=password&client_id=novaforge-api"
                        + "&username=" + url(username) + "&password=" + url(password))
                .retrieve()
                .body(Map.class);
        if (response == null || response.get("access_token") == null) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "environment admin grant failed for " + username);
        }
        return (String) response.get("access_token");
    }

    @SuppressWarnings("unchecked")
    private String serviceToken() {
        Map<String, Object> response = auth.post()
                .uri("/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=client_credentials&client_id=" + url(clientId)
                        + "&client_secret=" + url(clientSecret))
                .retrieve()
                .body(Map.class);
        if (response == null || response.get("access_token") == null) {
            throw new PlatformException(PlatformErrorCode.INTERNAL, "service grant failed");
        }
        return (String) response.get("access_token");
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
