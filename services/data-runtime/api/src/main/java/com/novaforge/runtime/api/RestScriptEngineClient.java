package com.novaforge.runtime.api;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.ScriptDefinition;
import com.novaforge.runtime.engine.hook.ScriptClient;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@link ScriptClient} binding: script hooks call the internal Script Engine
 * (port 8084 — no gateway route, PHASE-3 §6) with the <em>calling</em> user's token
 * relayed verbatim, so the script runs caller-context (§13 Q1) and every
 * {@code $data.query} it makes stays inside the user's grants. No service-account
 * fallback exists by design: without a caller token the hook fails loudly rather than
 * silently escalating — the read timeout therefore exceeds the engine's wall-clock
 * cap so capped scripts surface as problem+json, not as client timeouts.
 */
@Component
public class RestScriptEngineClient implements ScriptClient {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final RestClient restClient;

    public RestScriptEngineClient(@Value("${novaforge.script-engine.url:http://localhost:8084}")
                                  String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(60_000);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public ScriptOutcome execute(String appApiName, int appVersion, String hookName,
                                 String trigger, ScriptDefinition script,
                                 Map<String, Object> record) {
        Map<String, Object> body = Map.of(
                "app", appApiName,
                "appVersion", appVersion,
                "hook", hookName,
                "trigger", trigger,
                "language", script.language(),
                "script", script.source(),
                "record", record == null ? Map.of() : record);
        try {
            Map<String, Object> response = restClient.post()
                    .uri("/api/v1/scripts/execute")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(this::relayCaller)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (response == null) {
                throw new PlatformException(PlatformErrorCode.INTERNAL,
                        "script engine returned no body for hook " + hookName);
            }
            return new ScriptOutcome(response.get("value"),
                    (List<String>) response.getOrDefault("logs", List.of()));
        } catch (org.springframework.web.client.RestClientResponseException e) {
            throw map(hookName, e);
        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "script engine call failed for hook " + hookName + ": " + e.getMessage(), null, e);
        }
    }

    /** Principal relay: the user token on this write request, verbatim. */
    private void relayCaller(HttpHeaders headers) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String authorization = attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization != null && !authorization.isBlank()) {
                headers.set(HttpHeaders.AUTHORIZATION, authorization);
                return;
            }
        }
        throw new PlatformException(PlatformErrorCode.INTERNAL,
                "no caller token bound — script hooks run caller-context only (§13 Q1)");
    }

    /** The engine's problem+json rendered back onto the write path. */
    private static PlatformException map(String hookName,
                                         org.springframework.web.client.RestClientResponseException e) {
        String detail = e.getResponseBodyAsString();
        try {
            JsonNode problem = MAPPER.readTree(detail);
            String code = problem.path("code").asString(null);
            String message = "script hook " + hookName + ": "
                    + problem.path("detail").asString("failed");
            for (PlatformErrorCode known : PlatformErrorCode.values()) {
                if (known.code().equals(code)) {
                    return new PlatformException(known, message);
                }
            }
        } catch (Exception ignored) {
            // not a problem body — fall through
        }
        LOG.warn("script engine returned an unrecognized body for hook {} (status {}): {}",
                hookName, e.getStatusCode().value(), detail);
        return new PlatformException(PlatformErrorCode.INTERNAL,
                "script hook " + hookName + " failed (engine status "
                        + e.getStatusCode().value() + ")", null, e);
    }

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(RestScriptEngineClient.class);
}
