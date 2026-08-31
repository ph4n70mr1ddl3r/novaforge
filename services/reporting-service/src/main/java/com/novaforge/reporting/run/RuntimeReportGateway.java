package com.novaforge.reporting.run;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.security.ServiceTokenClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The two execution legs into the Data Runtime (§4/§7): interactive runs relay the
 * caller's token to the public query surface — the runtime then enforces the actor's
 * matrix, field security, and sharing-rule row filters exactly as for lists — while
 * the scheduled leg rides the service-client-gated role-scoped surface with the
 * per-app system principal bounded by {@code runAsRole}. Never raw SQL either way.
 */
@Component
public class RuntimeReportGateway {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final RestClient runtime;
    private final ServiceTokenClient serviceToken;

    public RuntimeReportGateway(
            @Value("${novaforge.data-runtime.url:http://localhost:8083}") String runtimeUrl,
            ServiceTokenClient serviceToken) {
        this.runtime = RestClient.builder().baseUrl(runtimeUrl)
                        .requestFactory(bounded(runtimeUrl))
                        .build();
        this.serviceToken = serviceToken;
    }

    /** The caller-relayed aggregate query — runs as the requesting actor (§4). */
    public JsonNode queryAsCaller(String entity, Map<String, Object> query, String callerToken) {
        return exchange(HttpMethod.POST, "/api/v1/runtime/" + entity + "/query", callerToken,
                MAPPER.writeValueAsString(query));
    }

    /** The role-scoped internal leg — the system principal bounded by runAsRole (§7). */
    public JsonNode queryAsRole(UUID tenantId, String app, String entity, String asRole,
                                Map<String, Object> query) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("tenantId", tenantId.toString());
        body.put("app", app);
        body.put("entityApiName", entity);
        body.put("asRole", asRole);
        body.put("query", query);
        return exchange(HttpMethod.POST, "/api/v1/hooks/reports/query", serviceToken.token(),
                MAPPER.writeValueAsString(body)).path("result");
    }

    /**
     * The actor-scoped internal leg (§6's "same authorization as a run" for the async
     * export handoff): the runtime re-evaluates the actor's matrix, field security,
     * and owner-based sharing exactly as the interactive run did — a job cannot
     * re-scope an export wider than the actor who requested it.
     */
    public JsonNode queryAsActor(UUID tenantId, String app, String entity, UUID actor,
                                 Map<String, Object> query) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("tenantId", tenantId.toString());
        body.put("app", app);
        body.put("entityApiName", entity);
        body.put("asActor", actor.toString());
        body.put("query", query);
        return exchange(HttpMethod.POST, "/api/v1/hooks/reports/query", serviceToken.token(),
                MAPPER.writeValueAsString(body)).path("result");
    }

    /** The actor's role assignments — the report:execute grant check's input (§8). */
    public List<String> rolesOf(UUID tenantId, UUID actor) {
        JsonNode roles = exchange(HttpMethod.GET,
                "/api/v1/admin/tenants/" + tenantId + "/users/" + actor + "/roles",
                serviceToken.token(), null);
        List<String> held = new java.util.ArrayList<>();
        roles.forEach(role -> held.add(role.asString()));
        return held;
    }

    private JsonNode exchange(HttpMethod method, String uri, String token, String body) {
        return runtime.method(method).uri(uri)
                .headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body == null ? "" : body)
                .exchange((request, response) -> {
                    String text = new String(response.getBody().readAllBytes(),
                            StandardCharsets.UTF_8);
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw problemFrom(text, uri);
                    }
                    return text.isBlank() ? MAPPER.readTree("{}") : MAPPER.readTree(text);
                });
    }

    /** Problem payloads ride back as PlatformExceptions — error-as-error, not error-as-data. */
    private static PlatformException problemFrom(String body, String where) {
        try {
            JsonNode problem = MAPPER.readTree(body);
            return new PlatformException(PlatformErrorCode.valueOf(problem.path("title").asString()),
                    "runtime rejected the " + where + " leg: "
                            + problem.path("detail").asString());
        } catch (Exception e) {
            return new PlatformException(PlatformErrorCode.INTERNAL,
                    "runtime " + where + " leg failed: " + body);
        }
    }

    /**
     * East-west calls are bounded (the pattern the other internal clients already
     * ride): a hung upstream must fail in seconds, not hold the calling thread —
     * the job scanner runs jobs serially on one scheduler thread, so an unbounded
     * read stalls every tenant's pipeline.
     */
    private static org.springframework.http.client.SimpleClientHttpRequestFactory bounded(String ignored) {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(60_000);   // the export renders the dataset inline
        return factory;
    }
}
