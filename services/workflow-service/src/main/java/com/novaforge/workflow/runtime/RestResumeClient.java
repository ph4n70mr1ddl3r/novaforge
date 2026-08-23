package com.novaforge.workflow.runtime;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.security.ServiceTokenClient;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The {@link ResumeClient} binding: the Data Runtime's internal resume surface with
 * the shared service client's token. Failures surface as exceptions — the suspended
 * instance records them and stays resolvable.
 */
@Component
public class RestResumeClient implements ResumeClient {

    private final RestClient runtime;
    private final ServiceTokenClient serviceToken;

    public RestResumeClient(@Value("${novaforge.data-runtime.url:http://localhost:8083}") String runtimeUrl,
                            ServiceTokenClient serviceToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(30_000);   // resume runs a flow graph — allow its budget
        this.runtime = RestClient.builder().baseUrl(runtimeUrl).requestFactory(factory).build();
        this.serviceToken = serviceToken;
    }

    @Override
    public void resume(Resume resume) {
        try {
            runtime.post()
                    .uri("/api/v1/hooks/resume")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(serviceToken.token()))
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
}
