package com.novaforge.runtime.api;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.runtime.engine.hook.ConnectorPort;
import com.novaforge.security.ServiceTokenClient;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@link ConnectorPort} binding (PHASE-6 §4): {@code callConnector} steps call
 * the Integration Service's internal execution surface (port 8090) with the shared
 * service client's token — the executor owns the §4-pinned 10 s timeout, the
 * circuit breaker, bounded retries, and the credential machinery, so this
 * client's read timeout (11 s) only needs to outlive one bounded attempt.
 * Failures propagate to the hook failure policy unchanged: before-hooks abort
 * the transaction, after-hooks ride the spine's retry leg (PHASE-3 §2).
 */
@Component
public class RestConnectorPort implements ConnectorPort {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * The envelope's money stays decimal-exact (PLAN.md §1 / ARCHITECTURE.md §4): the
     * connector's provider response is a third-party document whose numbers we own
     * the parse of, and a default Map read types every JSON float as Double — a
     * provider amount past 17 significant digits lands in the flow's response
     * mapping as its float64 shadow (9999999999999999.99 → 1.0E16, silently wrong
     * money in the record). The same rule ReportRunner's cache read pins for its
     * own JSON re-parse (USE_BIG_DECIMAL_FOR_FLOATS there).
     */
    private static final JsonMapper EXACT_READ = JsonMapper.builder()
            .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private final RestClient integration;
    private final ServiceTokenClient serviceToken;

    public RestConnectorPort(@Value("${novaforge.integration.url:http://localhost:8090}")
                             String baseUrl,
                             ServiceTokenClient serviceToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(11_000);   // one §4-bounded attempt, outlived
        this.integration = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
        this.serviceToken = serviceToken;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ConnectorResult execute(String tenantId, String appApiName, String connector,
                                   String operation, Map<String, Object> template,
                                   String dedupeKey) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tenantId", tenantId);
            body.put("app", appApiName);
            body.put("connector", connector);
            body.put("operation", operation);
            body.put("template", template == null ? Map.of() : template);
            if (dedupeKey != null) {
                body.put("dedupeKey", dedupeKey);
            }
            String responseText = integration.method(HttpMethod.POST)
                    .uri("/api/v1/integrations/internal/execute")
                    .headers(headers -> headers.setBearerAuth(serviceToken.token()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(MAPPER.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);
            if (responseText == null || responseText.isBlank()) {
                throw new PlatformException(PlatformErrorCode.INTERNAL,
                        "connector executor returned no body for " + connector + "." + operation);
            }
            Map<String, Object> response = EXACT_READ.readValue(responseText, Map.class);
            return new ConnectorResult(
                    ((Number) response.getOrDefault("status", 500)).intValue(),
                    MAPPER.valueToTree(response.get("body")));
        } catch (org.springframework.web.client.RestClientResponseException e) {
            throw mapped(connector, operation, e);
        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "connector executor unreachable for " + connector + "." + operation
                            + ": " + e.getMessage(), null, e);
        }
    }

    private static PlatformException mapped(String connector, String operation,
                                            org.springframework.web.client.RestClientResponseException e) {
        String detail = e.getResponseBodyAsString();
        try {
            JsonNode problem = MAPPER.readTree(detail);
            String code = problem.path("code").asString(null);
            String message = "connector " + connector + "." + operation + ": "
                    + problem.path("detail").asString("failed");
            for (PlatformErrorCode known : PlatformErrorCode.values()) {
                if (known.code().equals(code)) {
                    return new PlatformException(known, message);
                }
            }
        } catch (Exception ignored) {
            // not a problem body — fall through
        }
        return new PlatformException(PlatformErrorCode.INTERNAL,
                "connector " + connector + "." + operation + " failed (executor status "
                        + e.getStatusCode().value() + ")", null, e);
    }
}
