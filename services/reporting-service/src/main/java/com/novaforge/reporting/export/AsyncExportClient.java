package com.novaforge.reporting.export;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.security.ServiceTokenClient;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * PHASE-5 §6's designed handoff, wired (PHASE-6 §7): a sync export over the 10k
 * cap creates its async job on the Integration Service — the job renders under the
 * initiating actor's own scopes (§6's "same authorization as a run": matrix, field
 * security, and owner-based sharing identical to the interactive export, never a
 * re-scoped role), streams to the File Service, and notifies the initiating user;
 * this client returns the job link the export endpoint answers with instead of the
 * cap error.
 */
@Component
public class AsyncExportClient {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final RestClient integration;
    private final ServiceTokenClient serviceToken;

    public AsyncExportClient(
            @Value("${novaforge.integration.url:http://localhost:8090}") String baseUrl,
            ServiceTokenClient serviceToken) {
        this.integration = RestClient.builder().baseUrl(baseUrl).build();
        this.serviceToken = serviceToken;
    }

    public record AsyncJob(UUID jobId, String jobLink) {
    }

    /** Creates the async export job; the response is the job link to hand back. */
    public AsyncJob create(UUID tenantId, String app, String reportId, String format,
                           Map<String, Object> params, UUID initiatedBy) {
        try {
            Map<String, Object> response = integration.method(HttpMethod.POST)
                    .uri("/api/v1/integrations/internal/report-exports")
                    .headers(headers -> headers.setBearerAuth(serviceToken.token()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(MAPPER.writeValueAsString(Map.of(
                            "tenantId", tenantId.toString(),
                            "app", app,
                            "reportId", reportId,
                            "format", format,
                            "params", params == null ? Map.of() : params,
                            "initiatedBy", initiatedBy.toString())))
                    .retrieve()
                    .body(Map.class);
            if (response == null || response.get("jobId") == null) {
                throw new PlatformException(PlatformErrorCode.INTERNAL,
                        "async export job creation returned no job id");
            }
            return new AsyncJob(UUID.fromString(String.valueOf(response.get("jobId"))),
                    String.valueOf(response.get("jobLink")));
        } catch (org.springframework.web.client.RestClientResponseException e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "async export job rejected: HTTP " + e.getStatusCode() + " "
                            + e.getResponseBodyAsString());
        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "integration service unreachable for the async export: " + e.getMessage(), null, e);
        }
    }
}
