package com.novaforge.scheduler.jobs;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.metadata.ScheduledJobDefinition;
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

/**
 * The {@link PublishedJobsSource} binding: the Metadata Service's published surface
 * with the shared service client's token — every published app's jobs, synced on
 * an interval (restart-safe; publish-driven at the source).
 */
@Component
public class RestPublishedJobsSource implements PublishedJobsSource {

    private final RestClient metadata;
    private final ServiceTokenClient serviceToken;

    public RestPublishedJobsSource(
            @Value("${novaforge.metadata.url:http://localhost:8081}") String metadataUrl,
            ServiceTokenClient serviceToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(5_000);
        this.metadata = RestClient.builder().baseUrl(metadataUrl).requestFactory(factory).build();
        this.serviceToken = serviceToken;
    }

    @Override
    public List<AppJobs> all() {
        try {
            List<Map<String, Object>> apps = metadata.method(HttpMethod.GET)
                    .uri("/api/v1/metadata/published-apps")
                    .headers(headers -> headers.setBearerAuth(serviceToken.token()))
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {
                    });
            List<AppJobs> result = new ArrayList<>();
            for (Map<String, Object> app : apps == null ? List.<Map<String, Object>>of() : apps) {
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
                        DefinitionParser.write(bundle.get("app")), AppDefinition.class);
                result.add(new AppJobs(UUID.fromString(String.valueOf(app.get("tenantId"))),
                        String.valueOf(app.get("apiName")), parsed.jobs()));
            }
            return result;
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "published-jobs sync failed: " + e.getMessage(), null, e);
        }
    }
}
