package com.novaforge.workflow.sla;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.metadata.SlaDefinition;
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
 * The {@link PublishedSlaSource} binding: the Metadata Service's published surface
 * with the platform service client's token — the same trusted path every service
 * consumer uses. Failures surface as INTERNAL (timers degrade loudly, not silently).
 */
@Component
public class RestPublishedSlaSource implements PublishedSlaSource {

    private final RestClient metadata;
    private final RestClient auth;
    private final String clientId;
    private final String clientSecret;
    private final AtomicReference<Grant> grant = new AtomicReference<>();

    private record Grant(String token, long refreshAt) {
    }

    public RestPublishedSlaSource(
            @Value("${novaforge.metadata.url:http://localhost:8081}") String metadataUrl,
            @Value("${novaforge.auth.issuer-uri:http://localhost:8082/realms/novaforge}") String issuer,
            @Value("${novaforge.auth.service-client.id:novaforge-runtime}") String clientId,
            @Value("${novaforge.auth.service-client.secret:novaforge-runtime-secret}") String clientSecret) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(5_000);
        this.metadata = RestClient.builder().baseUrl(metadataUrl).requestFactory(factory).build();
        this.auth = RestClient.builder().baseUrl(issuer).requestFactory(factory).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public List<SlaDefinition> slasOf(UUID tenantId, String appApiName) {
        try {
            List<Map<String, Object>> apps = metadata.method(HttpMethod.GET)
                    .uri("/api/v1/metadata/published-apps")
                    .headers(headers -> headers.setBearerAuth(serviceToken()))
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {
                    })
                    .stream().filter(app -> appApiName.equals(app.get("apiName")))
                    .toList();
            if (apps.isEmpty()) {
                return List.of();
            }
            Map<String, Object> bundle = metadata.method(HttpMethod.GET)
                    .uri("/api/v1/metadata/apps/" + apps.getFirst().get("appId") + "/published")
                    .headers(headers -> headers.setBearerAuth(serviceToken()))
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            if (bundle == null || bundle.get("app") == null) {
                return List.of();
            }
            AppDefinition app = DefinitionParser.parse(
                    DefinitionParser.write(bundle.get("app")),
                    AppDefinition.class);
            return app.slas();
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "published SLA fetch failed for " + appApiName + ": " + e.getMessage(),
                    null, e);
        }
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
