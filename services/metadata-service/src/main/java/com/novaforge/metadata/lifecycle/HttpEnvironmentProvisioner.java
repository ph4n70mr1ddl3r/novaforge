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
        this.runtime = RestClient.builder().baseUrl(runtimeUrl).build();
        this.metadata = RestClient.builder().baseUrl(metadataUrl).build();
        this.auth = RestClient.builder().baseUrl(issuer).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public EnvironmentRef provision(AppDefinition bundle, String envName) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String tenantName = (bundle.apiName() + "-" + envName + "-" + suffix).toLowerCase();
        String adminUsername = "env-" + envName + "-" + suffix;
        String adminPassword = "env-" + UUID.randomUUID();
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
        String tenantId = String.valueOf(tenant.get("tenantId"));
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
