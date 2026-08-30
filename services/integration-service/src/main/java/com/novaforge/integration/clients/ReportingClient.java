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
 * under the initiating actor's own scopes — PHASE-5 §6's "same authorization as a
 * run", so the export can never re-scope wider than the actor who requested it —
 * bytes back, no notification; the Integration Service streams them to the File
 * Service and notifies the job's initiating user itself.
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

    /**
     * Renders one report export — actor-scoped when the job carries an initiating
     * actor (the interactive handoff's jobs), else the role scope — and returns the
     * file bytes.
     */
    public byte[] export(UUID tenantId, String app, String reportId, String runAsRole,
                         UUID runAsActor, String format, Map<String, Object> params) {
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("tenantId", tenantId.toString());
            body.put("app", app);
            body.put("reportId", reportId);
            if (runAsActor != null) {
                body.put("runAsActor", runAsActor.toString());
            } else {
                body.put("runAsRole", runAsRole);
            }
            body.put("format", format);
            body.put("params", params == null ? Map.of() : params);
            Map<String, Object> response = reports.method(HttpMethod.POST)
                    .uri("/api/v1/reports/internal/export")
                    .headers(headers -> headers.setBearerAuth(serviceToken.token()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(MAPPER.writeValueAsString(body))
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
