package com.novaforge.script.api;

import com.novaforge.common.context.TenantContext;
import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.script.engine.QueryProxy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@link QueryProxy} binding: {@code $data.query} forwards to the Data Runtime's
 * list API with the <em>calling</em> user's bearer relayed (ARCHITECTURE.md §5 item 4)
 * — authorization, tenant scoping, and field security stay the runtime's single data
 * path, so a script can never exceed its authorizing user's grants. There is no
 * service-account fallback on this leg by design: a script without a caller token
 * refuses rather than silently escalating (ADR-003 #2). The scheduled leg
 * ({@link #systemQuery}) is separate and explicit: it rides the runtime's internal
 * system-principal surface with the shared service client (PHASE-4 §7), never a
 * forged user context.
 */
@Component
public class DataRuntimeQueryClient implements QueryProxy {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final RestClient restClient;
    private final String baseUrl;
    private final com.novaforge.security.ServiceTokenClient serviceToken;

    public DataRuntimeQueryClient(
            @Value("${novaforge.data-runtime.url:http://localhost:8083}") String baseUrl,
            com.novaforge.security.ServiceTokenClient serviceToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(10_000);
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.serviceToken = serviceToken;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object query(TenantContext.Context caller, String entity, String queryJson) {
        StringBuilder uri = new StringBuilder("/api/v1/runtime/").append(entity);
        try {
            JsonNode query = MAPPER.readTree(queryJson);
            char separator = '?';
            for (String param : new String[] {"filter", "sort", "page"}) {
                JsonNode node = query.get(param);
                if (node != null && !node.isNull()) {
                    uri.append(separator).append(param).append('=')
                            .append(URLEncoder.encode(node.toString(), StandardCharsets.UTF_8));
                    separator = '&';
                }
            }
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "$data.query expects a {filter, sort, page} JSON object: " + e.getMessage());
        }
        try {
            // a pre-built URI skips template handling, so the encoded query stays intact
            return restClient.get()
                    .uri(java.net.URI.create(baseUrl + uri))
                    .headers(this::relayCaller)
                    .retrieve()
                    .body(Map.class);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            throw map(e);
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "data runtime query failed: " + e.getMessage(), null, e);
        }
    }

    /**
     * The scheduled leg (PHASE-4 §7): the runtime's internal system-principal query
     * surface with the shared service client — the app's system context, never a
     * user's. The query DSL rides verbatim; the runtime's single query path lowers it.
     */
    @Override
    @SuppressWarnings("unchecked")
    public Object systemQuery(TenantContext.Context principal, String app, String entity,
                              String queryJson) {
        Map<String, Object> body = Map.of(
                "tenantId", principal.tenantId(),
                "app", app,
                "entityApiName", entity,
                "query", queryJson == null || queryJson.isBlank() ? "{}" : queryJson);
        try {
            Map<String, Object> response = restClient.post()
                    .uri(baseUrl + "/api/v1/hooks/records/query")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(serviceToken.token()))
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            return response == null ? Map.of() : response.getOrDefault("result", Map.of());
        } catch (org.springframework.web.client.RestClientResponseException e) {
            throw map(e);
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "data runtime query failed: " + e.getMessage(), null, e);
        }
    }

    /** Principal relay: the user token that arrived with this execution, verbatim. */
    private void relayCaller(HttpHeaders headers) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String authorization = attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization != null && !authorization.isBlank()) {
                headers.set(HttpHeaders.AUTHORIZATION, authorization);
                return;
            }
        }
        throw new PlatformException(PlatformErrorCode.INTERNAL,
                "no caller token bound — scripts run caller-context only (ADR-003 #2)");
    }

    /** The runtime's problem+json rendered back through the sandbox boundary. */
    private static PlatformException map(org.springframework.web.client.RestClientResponseException e) {
        String detail = e.getResponseBodyAsString();
        try {
            JsonNode problem = MAPPER.readTree(detail);
            String code = problem.path("code").asString(null);
            String message = problem.path("detail").asString("data runtime query failed");
            for (PlatformErrorCode known : PlatformErrorCode.values()) {
                if (known.code().equals(code)) {
                    return new PlatformException(known, message);
                }
            }
        } catch (Exception ignored) {
            // not a problem body — fall through
        }
        LOG.warn("data runtime query returned an unrecognized body (status {}): {}",
                e.getStatusCode().value(), detail);
        return new PlatformException(PlatformErrorCode.INTERNAL,
                "data runtime query failed (upstream status "
                        + e.getStatusCode().value() + ")", null, e);
    }

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(DataRuntimeQueryClient.class);
}
