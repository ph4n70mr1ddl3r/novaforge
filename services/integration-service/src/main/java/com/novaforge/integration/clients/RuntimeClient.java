package com.novaforge.integration.clients;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.security.ServiceTokenClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Data Runtime's integration surfaces (PHASE-6 §6/§7): writes as the per-app
 * integration principal (the full write path — validations, state machines, hooks)
 * with per-item outcomes, and the two read scopes (role-scoped export paging,
 * integration-scoped key lookups). All service-client gated there; the single
 * write path is absolute — nothing here addresses tenant tables directly.
 */
@Component
public class RuntimeClient {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final RestClient runtime;
    private final ServiceTokenClient serviceToken;

    // dual constructors demand the marker: without it Spring falls back to the
    // no-arg hermetic base and every field stays null (found live, §)
    @org.springframework.beans.factory.annotation.Autowired
    public RuntimeClient(@Value("${novaforge.data-runtime.url:http://localhost:8083}") String url,
                         ServiceTokenClient serviceToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(30_000);
        this.runtime = RestClient.builder().baseUrl(url).requestFactory(factory).build();
        this.serviceToken = serviceToken;
    }

    /** The hermetic base for tests — override the legs, no HTTP client underneath. */
    protected RuntimeClient() {
        this.runtime = null;
        this.serviceToken = null;
    }

    /** One per-item write outcome (the §6/§7 contract). */
    public record Outcome(String status, Map<String, Object> record, String code, String detail) {

        public boolean ok() {
            return "ok".equals(status);
        }

        public String recordId() {
            return record == null || record.get("id") == null ? null
                    : String.valueOf(record.get("id"));
        }

        public Number recordVersion() {
            return record == null || !(record.get("version") instanceof Number number)
                    ? null : number;
        }
    }

    /** Applies one write chunk as the integration principal (per-item outcomes). */
    public List<Outcome> write(UUID tenantId, List<Map<String, Object>> items) {
        try {
            Map<String, Object> response = runtime.method(HttpMethod.POST)
                    .uri("/api/v1/hooks/integration/write")
                    .headers(headers -> headers.setBearerAuth(serviceToken.token()))
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(MAPPER.writeValueAsString(Map.of(
                            "tenantId", tenantId.toString(), "items", items)))
                    .retrieve()
                    .body(Map.class);
            List<?> outcomes = response == null ? List.of()
                    : (List<?>) response.getOrDefault("outcomes", List.of());
            return outcomes.stream().map(outcome -> MAPPER.convertValue(outcome, Outcome.class))
                    .toList();
        } catch (org.springframework.web.client.RestClientResponseException e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "integration write path rejected the chunk: HTTP " + e.getStatusCode()
                            + " " + e.getResponseBodyAsString());
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "integration write path unreachable: " + e.getMessage());
        }
    }

    /** One list page: role-scoped when {@code asRole} names a role, integration otherwise. */
    public ListPage list(UUID tenantId, String entity, String asRole, Map<String, Object> query) {
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("tenantId", tenantId.toString());
            body.put("entity", entity);
            if (asRole != null && !asRole.isBlank()) {
                body.put("asRole", asRole);
            }
            body.put("query", query == null ? Map.of() : query);
            Map<String, Object> response = runtime.method(HttpMethod.POST)
                    .uri("/api/v1/hooks/integration/list")
                    .headers(headers -> headers.setBearerAuth(serviceToken.token()))
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(MAPPER.writeValueAsString(body))
                    .retrieve()
                    .body(Map.class);
            Object result = response == null ? null : ((Map<?, ?>) response).get("result");
            Map<String, Object> shaped = MAPPER.convertValue(
                    result == null ? Map.of() : result, Map.class);
            return new ListPage((List<Map<String, Object>>) shaped.getOrDefault("rows", List.of()),
                    ((Number) shaped.getOrDefault("total", 0)).longValue());
        } catch (org.springframework.web.client.RestClientResponseException e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "integration read path rejected the page: HTTP " + e.getStatusCode()
                            + " " + e.getResponseBodyAsString());
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "integration read path unreachable: " + e.getMessage());
        }
    }

    /** The integration-scoped lookup (upsert keys, §6) — a one-page convenience. */
    public ListPage lookup(UUID tenantId, String entity, Map<String, Object> query) {
        return list(tenantId, entity, null, query);
    }

    /**
     * The upsert key's lookup filter in the query-DSL shape the runtime's list
     * parser pins (§6): one key lowers to a single {@code {field, op, value}} leaf;
     * several keys conjoin under {@code {and: […]}}. A bare {@code {field: value}}
     * map 400s at the parser ("filter.field is required" — found live on the
     * webhook leg), and a flat leaf assembled by looping the keys keeps only the
     * LAST field — a multi-key upsert then resolves by that field alone and can
     * rewrite a record its other keys exclude.
     */
    public static Map<String, Object> keyLookupFilter(List<String> keyFields,
                                                       Map<String, Object> values) {
        List<Map<String, Object>> leaves = new ArrayList<>();
        for (String key : keyFields) {
            Map<String, Object> leaf = new LinkedHashMap<>();
            leaf.put("field", key);
            leaf.put("op", "eq");
            leaf.put("value", values.get(key));
            leaves.add(leaf);
        }
        return leaves.size() == 1 ? leaves.getFirst() : Map.of("and", leaves);
    }

    /** A page of rows plus the full total (export paging loops on the former). */
    public record ListPage(List<Map<String, Object>> rows, long total) {
    }
}
