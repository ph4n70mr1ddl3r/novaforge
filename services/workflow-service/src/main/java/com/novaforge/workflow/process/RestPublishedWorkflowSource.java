package com.novaforge.workflow.process;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.security.ServiceTokenClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@link PublishedWorkflowSource} binding: the Metadata Service's published
 * surface with the shared service client's token — the service-caller index
 * enumerates every tenant's apps, so one pass yields all deployments to sync (the
 * same path the Scheduler's jobs source rides). Failures surface as INTERNAL (the
 * deployer logs and retries next pass — degradation is loud, not silent).
 */
@Component
public class RestPublishedWorkflowSource implements PublishedWorkflowSource {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final RestClient metadata;
    private final ServiceTokenClient serviceToken;

    public RestPublishedWorkflowSource(
            @Value("${novaforge.metadata.url:http://localhost:8081}") String metadataUrl,
            ServiceTokenClient serviceToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(5_000);
        this.metadata = RestClient.builder().baseUrl(metadataUrl).requestFactory(factory).build();
        this.serviceToken = serviceToken;
    }

    @Override
    public List<AppWorkflows> all() {
        try {
            List<Map<String, Object>> apps = metadata.method(HttpMethod.GET)
                    .uri("/api/v1/metadata/published-apps")
                    .headers(headers -> headers.setBearerAuth(serviceToken.token()))
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {
                    });
            List<AppWorkflows> result = new ArrayList<>();
            for (Map<String, Object> app : apps == null ? List.<Map<String, Object>>of() : apps) {
                if (app.get("tenantId") == null || app.get("apiName") == null) {
                    continue;   // user-scoped rows (defensive): the service index carries both
                }
                Map<String, Object> bundle = metadata.method(HttpMethod.GET)
                        .uri("/api/v1/metadata/apps/" + app.get("appId") + "/published")
                        .headers(headers -> headers.setBearerAuth(serviceToken.token()))
                        .retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() {
                        });
                if (bundle == null || bundle.get("app") == null) {
                    continue;
                }
                AppDefinition parsed = DefinitionParser.parse(
                        MAPPER.writeValueAsString(bundle.get("app")), AppDefinition.class);
                if (!parsed.workflows().isEmpty()) {
                    result.add(new AppWorkflows(UUID.fromString(String.valueOf(app.get("tenantId"))),
                            String.valueOf(app.get("apiName")), parsed.workflows()));
                }
            }
            return result;
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "published-workflow sync failed: " + e.getMessage(), null, e);
        }
    }
}
