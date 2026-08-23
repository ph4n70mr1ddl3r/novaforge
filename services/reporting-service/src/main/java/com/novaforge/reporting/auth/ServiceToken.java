package com.novaforge.reporting.auth;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The client_credentials grant against the Keycloak issuer — one cached grant for
 * every leg the reporting service makes with the platform service client (metadata
 * published reads, the role-scoped runtime surface, role lookups, notification
 * delivery). Refreshed 30 s early; the house pattern every trusted-service caller
 * uses. Interactive report runs never touch this token — they relay the caller's.
 */
@Component
public class ServiceToken {

    private final RestClient auth;
    private final String clientId;
    private final String clientSecret;
    private volatile Grant grant;

    private record Grant(String token, Instant refreshAt) {
    }

    public ServiceToken(@Value("${novaforge.auth.issuer-uri:http://localhost:8082/realms/novaforge}") String issuer,
                        @Value("${novaforge.auth.service-client.id:novaforge-runtime}") String clientId,
                        @Value("${novaforge.auth.service-client.secret:novaforge-runtime-secret}") String clientSecret) {
        this.auth = RestClient.builder().baseUrl(issuer).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public String token() {
        Grant current = grant;
        if (current == null || Instant.now().isAfter(current.refreshAt())) {
            Map<String, Object> body = auth.post()
                    .uri("/protocol/openid-connect/token")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .body("grant_type=client_credentials&client_id=" + clientId
                            + "&client_secret=" + clientSecret)
                    .retrieve().body(Map.class);
            if (body == null || String.valueOf(body.get("access_token")).equals("null")) {
                throw new PlatformException(PlatformErrorCode.INTERNAL,
                        "service token grant returned no token");
            }
            long expiresIn = ((Number) body.getOrDefault("expires_in", 300)).longValue();
            current = new Grant(String.valueOf(body.get("access_token")),
                    Instant.now().plusSeconds(Math.max(30, expiresIn - 30)));
            grant = current;
        }
        return current.token();
    }
}
