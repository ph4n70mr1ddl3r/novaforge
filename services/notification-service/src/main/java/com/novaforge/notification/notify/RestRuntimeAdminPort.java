package com.novaforge.notification.notify;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.notification.notify.RecipientResolver.RuntimeAdminPort;
import com.novaforge.security.ServiceTokenClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The {@link RuntimeAdminPort} binding: the Data Runtime's admin read surface with
 * the shared service client's token — role holders and usernames for the fan-out.
 */
@Component
public class RestRuntimeAdminPort implements RuntimeAdminPort {

    private final RestClient runtime;
    private final ServiceTokenClient serviceToken;

    public RestRuntimeAdminPort(
            @Value("${novaforge.data-runtime.url:http://localhost:8083}") String runtimeUrl,
            ServiceTokenClient serviceToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(5_000);
        this.runtime = RestClient.builder().baseUrl(runtimeUrl).requestFactory(factory).build();
        this.serviceToken = serviceToken;
    }

    @Override
    public List<UUID> usersOfRole(UUID tenantId, String role) {
        List<String> users = runtime.method(HttpMethod.GET)
                .uri("/api/v1/admin/tenants/" + tenantId + "/roles/" + role + "/users")
                .headers(headers -> headers.setBearerAuth(serviceToken.token()))
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
                .headers(headers -> headers.setBearerAuth(serviceToken.token()))
                .retrieve()
                .body(Map.class);
        return body == null ? null : String.valueOf(body.get("username"));
    }
}
