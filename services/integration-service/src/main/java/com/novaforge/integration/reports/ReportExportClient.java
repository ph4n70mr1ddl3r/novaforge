package com.novaforge.integration.reports;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.security.ServiceTokenClient;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The report-export render leg (PHASE-6 §7): the Reporting Service renders the
 * finished artifact — the job's {@code runAsRole} bounds the run exactly like a
 * scheduled delivery (sharing rules included), the same exporter that serves sync
 * exports does the bytes. The runner then stores those bytes through the File
 * Service; failures fail the job loudly.
 */
@Component
public class ReportExportClient {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final RestClient reports;
    private final ServiceTokenClient serviceToken;

    public ReportExportClient(@Value("${novaforge.reporting.url:http://localhost:8089}")
                              String url, ServiceTokenClient serviceToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(120_000);   // >10k-row renders stream here
        this.reports = RestClient.builder().baseUrl(url).requestFactory(factory).build();
        this.serviceToken = serviceToken;
    }

    /** The rendered export: file naming/mime for the store, rows for the audit. */
    public record Rendered(String fileName, String contentType, byte[] bytes, int rows) {
    }

    public Rendered export(UUID tenantId, String appApiName, String reportId,
                           Map<String, Object> params, String format, String runAsRole) {
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("tenantId", tenantId.toString());
            body.put("app", appApiName);
            body.put("reportId", reportId);
            body.put("params", params == null ? Map.of() : params);
            body.put("format", format);
            if (runAsRole != null && !runAsRole.isBlank()) {
                body.put("runAsRole", runAsRole);
            }
            String response = reports.post()
                    .uri("/api/v1/reports/internal/export")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(serviceToken.token()))
                    .body(MAPPER.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);
            JsonNode parsed = MAPPER.readTree(response == null ? "{}" : response);
            String encoded = parsed.path("bytes").asString(null);
            if (encoded == null) {
                throw new PlatformException(PlatformErrorCode.INTERNAL,
                        "report export returned no bytes for " + reportId);
            }
            return new Rendered(parsed.path("fileName").asString(reportId + "." + format),
                    parsed.path("contentType").asString("application/octet-stream"),
                    Base64.getDecoder().decode(encoded), parsed.path("rows").asInt(0));
        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "report export failed for " + reportId + ": " + e.getMessage(), null, e);
        }
    }
}
