package com.novaforge.workflow.sla;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.metadata.SlaDefinition;
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
 * The {@link PublishedSlaSource} binding: the Metadata Service's published surface
 * with the shared service client's token — the same trusted path every service
 * consumer uses. Failures surface as INTERNAL (timers degrade loudly, not silently).
 */
@Component
public class RestPublishedSlaSource implements PublishedSlaSource {

    private final RestClient metadata;
    private final ServiceTokenClient serviceToken;

    public RestPublishedSlaSource(
            @Value("${novaforge.metadata.url:http://localhost:8081}") String metadataUrl,
            ServiceTokenClient serviceToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(5_000);
        this.metadata = RestClient.builder().baseUrl(metadataUrl).requestFactory(factory).build();
        this.serviceToken = serviceToken;
    }

    @Override
    public List<SlaDefinition> slasOf(UUID tenantId, String appApiName) {
        try {
            List<Map<String, Object>> apps = metadata.method(HttpMethod.GET)
                    .uri("/api/v1/metadata/published-apps")
                    .headers(headers -> headers.setBearerAuth(serviceToken.token()))
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {
                    })
                    // the service-caller index is cross-tenant: two tenants may publish
                    // same-named apps, and the first apiName match in ANY tenant is not
                    // this caller's app — the tenant pins the bundle
                    .stream().filter(app -> appApiName.equals(app.get("apiName"))
                            && tenantId.equals(UUID.fromString(
                                    String.valueOf(app.get("tenantId")))))
                    .toList();
            if (apps.isEmpty()) {
                return List.of();
            }
            Map<String, Object> bundle = metadata.method(HttpMethod.GET)
                    .uri("/api/v1/metadata/apps/" + apps.getFirst().get("appId") + "/published")
                    .headers(headers -> headers.setBearerAuth(serviceToken.token()))
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
}
