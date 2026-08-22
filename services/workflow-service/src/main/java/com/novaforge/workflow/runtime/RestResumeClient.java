package com.novaforge.workflow.runtime;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The {@link ResumeClient} binding: the Data Runtime's internal resume surface with
 * the platform service client's token. Failures surface as exceptions — the suspended
 * instance records them and stays resolvable.
 */
@Component
public class RestResumeClient implements ResumeClient {

    private final RestClient runtime;
    private final RestClient auth;
    private final String clientId;
    private final String clientSecret;
    private final AtomicReference<Grant> grant = new AtomicReference<>();

    private record Grant(String token, long refreshAt) {
    }

    public RestResumeClient(@Value("${novaforge.data-runtime.url:http://localhost:8083}") String runtimeUrl,
                            @Value("${novaforge.auth.issuer-uri:http://localhost:8082/realms/novaforge}") String issuer,
                            @Value("${novaforge.auth.service-client.id:novaforge-runtime}") String clientId,
                            @Value("${novaforge.auth.service-client.secret:novaforge-runtime-secret}") String clientSecret) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(30_000);   // resume runs a flow graph — allow its budget
        this.runtime = RestClient.builder().baseUrl(runtimeUrl).requestFactory(factory).build();
        this.auth = RestClient.builder().baseUrl(issuer).requestFactory(factory).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public void resume(Resume resume) {
        try {
            runtime.post()
                    .uri("/api/v1/hooks/resume")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(serviceToken()))
                    .body(Map.of(
                            "tenantId", resume.tenantId().toString(),
                            "app", resume.app(),
                            "entityApiName", resume.entityApiName(),
                            "recordId", resume.recordId().toString(),
                            "hook", resume.hook(),
                            "afterStep", resume.afterStep() == null ? "" : resume.afterStep(),
                            "onReject", resume.onRejectJson() == null ? "" : resume.onRejectJson(),
                            "approved", resume.approved()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "runtime resume failed for hook " + resume.hook() + ": " + e.getMessage(),
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
