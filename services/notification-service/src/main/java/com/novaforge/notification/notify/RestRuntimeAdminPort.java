package com.novaforge.notification.notify;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.notification.notify.RecipientResolver.RuntimeAdminPort;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The {@link RuntimeAdminPort} binding: the Data Runtime's admin read surface with
 * the platform service client's token — role holders and usernames for the fan-out.
 */
@Component
public class RestRuntimeAdminPort implements RuntimeAdminPort {

    private final RestClient runtime;
    private final RestClient auth;
    private final String clientId;
    private final String clientSecret;
    private final AtomicReference<Grant> grant = new AtomicReference<>();

    private record Grant(String token, long refreshAt) {
    }

    public RestRuntimeAdminPort(
            @Value("${novaforge.data-runtime.url:http://localhost:8083}") String runtimeUrl,
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
    public List<UUID> usersOfRole(UUID tenantId, String role) {
        List<String> users = runtime.method(HttpMethod.GET)
                .uri("/api/v1/admin/tenants/" + tenantId + "/roles/" + role + "/users")
                .headers(headers -> headers.setBearerAuth(serviceToken()))
                .retrieve()
                .body(new ParameterizedTypeReference<List<String>>() {
                });
        return users == null ? List.of()
                : users.stream().map(UUID::fromString).toList();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String usernameOf(UUID user) {
        Map<String, Object> body = runtime.method(HttpMethod.GET)
                .uri("/api/v1/admin/users/" + user)
                .headers(headers -> headers.setBearerAuth(serviceToken()))
                .retrieve()
                .body(Map.class);
        return body == null ? null : String.valueOf(body.get("username"));
    }

    @SuppressWarnings("unchecked")
    private String serviceToken() {
        Grant cached = grant.get();
        if (cached != null && System.currentTimeMillis() < cached.refreshAt()) {
            return cached.token();
        }
        Map<String, Object> granted = auth.post()
                .uri("/protocol/openid-connect/token")
                .headers(headers -> headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED))
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
        grant.set(new Grant(String.valueOf(granted.get("access_token")),
                System.currentTimeMillis() + Math.max(0, seconds - 30) * 1000));
        return String.valueOf(granted.get("access_token"));
    }
}
