package com.novaforge.workflow.roles;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.security.ServiceTokenClient;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The {@link RoleLookup} binding: the shared service client's token, then the
 * runtime's admin read surface — the same trusted-service path the test harness uses
 * (ADR-010). Answers are short-lived in-flight (one call per inbox request); no
 * caching of authorization data beyond the request.
 */
@Component
public class RestRoleLookup implements RoleLookup {

    private final RestClient runtime;
    private final ServiceTokenClient serviceToken;

    public RestRoleLookup(@Value("${novaforge.data-runtime.url:http://localhost:8083}") String runtimeUrl,
                          ServiceTokenClient serviceToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(5_000);
        this.runtime = RestClient.builder().baseUrl(runtimeUrl).requestFactory(factory).build();
        this.serviceToken = serviceToken;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<UUID> holdersOf(UUID tenantId, String role) {
        try {
            List<String> users = runtime.method(HttpMethod.GET)
                    .uri("/api/v1/admin/tenants/" + tenantId + "/roles/"
                            + java.net.URLEncoder.encode(role, java.nio.charset.StandardCharsets.UTF_8)
                            + "/users")
                    .headers(headers -> headers.setBearerAuth(serviceToken.token()))
                    .retrieve()
                    .body(ArrayList.class);
            if (users == null) {
                return List.of();
            }
            return users.stream().map(user -> UUID.fromString(String.valueOf(user))).toList();
        } catch (Exception e) {
            // an unreachable runtime must not wedge the breach path — the caller
            // treats an unknown answer as "held" (the pre-validation behavior)
            return null;
        }
    }

    public List<String> of(UUID tenantId, UUID actor) {
        try {
            List<String> roles = runtime.method(HttpMethod.GET)
                    .uri("/api/v1/admin/tenants/" + tenantId + "/users/" + actor + "/roles")
                    .headers(headers -> headers.setBearerAuth(serviceToken.token()))
                    .retrieve()
                    .body(ArrayList.class);
            return roles == null ? List.of() : List.copyOf(roles);
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "role lookup failed for " + actor + ": " + e.getMessage(), null, e);
        }
    }
}
