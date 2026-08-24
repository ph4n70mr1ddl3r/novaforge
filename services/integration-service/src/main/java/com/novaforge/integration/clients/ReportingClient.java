package com.novaforge.integration.clients;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.security.ServiceTokenClient;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Reporting Service's internal export leg (PHASE-6 §7): the async job renders
 * under its pinned {@code runAsRole} through the same role-scoped surface the
 * Scheduler's deliveries ride (PHASE-5 §7) — bytes back, no notification; the
 * Integration Service streams them to the File Service and notifies the job's
 * initiating user itself.
 */
@Component
public class ReportingClient {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final RestClient reports;
    private final ServiceTokenClient serviceToken;

    public ReportingClient(@Value("${novaforge.reporting.url:http://localhost:8089}") String url,
                           ServiceTokenClient serviceToken) {
        this.reports = RestClient.builder().baseUrl(url).build();
        this.serviceToken = serviceToken;
    }

    /** Renders one report export under the role scope; returns the file bytes. */
    public byte[] export(UUID tenantId, String app, String reportId, String runAsRole,
                         String format, Map<String, Object> params) {
        try {
            Map<String, Object> response = reports.method(HttpMethod.POST)
                    .uri("/api/v1/reports/internal/export")
                    .headers(headers -> headers.setBearerAuth(serviceToken.token()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(MAPPER.writeValueAsString(Map.of(
                            "tenantId", tenantId.toString(),
                            "app", app,
                            "reportId", reportId,
                            "runAsRole", runAsRole,
                            "format", format,
                            "params", params == null ? Map.of() : params)))
                    .retrieve()
                    .body(Map.class);
            if (response == null || response.get("contentBase64") == null) {
                throw new PlatformException(PlatformErrorCode.INTERNAL,
                        "report export returned no content");
            }
            return Base64.getDecoder().decode(String.valueOf(response.get("contentBase64")));
        } catch (org.springframework.web.client.RestClientResponseException e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "report export rejected: HTTP " + e.getStatusCode() + " "
                            + e.getResponseBodyAsString());
        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "reporting unreachable: " + e.getMessage());
        }
    }
}
