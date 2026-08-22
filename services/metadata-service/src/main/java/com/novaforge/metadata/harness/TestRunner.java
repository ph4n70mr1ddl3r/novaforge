package com.novaforge.metadata.harness;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.metadata.EntityDefinition;
import com.novaforge.metadata.TestSuiteDefinition;
import com.novaforge.metadata.TestSuiteDefinition.Step;
import com.novaforge.metadata.TestSuiteDefinition.TestCase;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The builder test runner (ADR-010, PHASE-3 §7): executes suites against a scratch
 * tenant pinned to the app's *candidate* version — steps replay as synthetic actors
 * with role impersonation through the Data Runtime's generic APIs (no test mode in the
 * write path), assertions are DSL predicates under the run's frozen clock, and side
 * effects land in the scratch tenant only. Scratch tenants are fresh per run (tenant
 * offboarding is deliberately unmodeled in v1 — PHASE-2 §10).
 */
@Service
public class TestRunner {

    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([A-Za-z0-9_.\\[\\]]+)}");

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The admin/synthetic actor password grant — scratch tenants only, rotated per run. */
    private static final String SCRATCH_PASSWORD_PREFIX = "scratch-";

    private final RestClient runtime;
    private final RestClient metadata;
    private final RestClient auth;
    private final String clientId;
    private final String clientSecret;
    private final io.micrometer.core.instrument.MeterRegistry suiteRuns;

    public TestRunner(@Value("${novaforge.runtime.url:http://localhost:8083}") String runtimeUrl,
                      @Value("${novaforge.metadata.url:http://localhost:8081}") String metadataUrl,
                      @Value("${novaforge.auth.issuer-uri:http://localhost:8082/realms/novaforge}") String issuer,
                      @Value("${novaforge.auth.service-client.id:novaforge-runtime}") String clientId,
                      @Value("${novaforge.auth.service-client.secret:novaforge-runtime-secret}") String clientSecret,
                      io.micrometer.core.instrument.MeterRegistry suiteRuns) {
        this.runtime = RestClient.builder().baseUrl(runtimeUrl).build();
        this.metadata = RestClient.builder().baseUrl(metadataUrl).build();
        this.auth = RestClient.builder().baseUrl(issuer).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.suiteRuns = suiteRuns;
    }

    /** One suite run: scratch tenant → candidate publish → cases → verdict. */
    /** One suite run: scratch tenant → candidate publish → cases → verdict. */
    public Map<String, Object> run(AppDefinition candidate, TestSuiteDefinition suite, UUID actorId) {
        Instant frozenAt = Instant.now();
        String runId = UUID.randomUUID().toString();
        String scratchName = "scratch-" + runId.substring(0, 8);
        String adminUsername = "scratch-admin-" + runId.substring(0, 8);
        String adminPassword = SCRATCH_PASSWORD_PREFIX + UUID.randomUUID();

        // 1. scratch tenant with first admin (the admin API orchestrates Keycloak)
        Map<String, Object> tenant = adminCall(HttpMethod.POST, "/api/v1/admin/tenants",
                serviceToken(), Map.of(
                        "apiName", scratchName,
                        "adminUsername", adminUsername,
                        "adminPassword", adminPassword));
        String tenantId = String.valueOf(tenant.get("tenantId"));

        // 2. role-impersonating synthetic actors for every role the suite names
        Map<String, String> rolePasswords = new HashMap<>();
        rolePasswords.put("__admin__", adminPassword);
        rolePasswords.put("__admin-user__", adminUsername);
        for (String role : rolesUsedBy(suite)) {
            String username = "actor-" + role + "-" + runId.substring(0, 8);
            String password = SCRATCH_PASSWORD_PREFIX + UUID.randomUUID();
            Map<String, Object> provisioned = adminCall(HttpMethod.POST,
                    "/api/v1/admin/tenants/" + tenantId + "/users",
                    serviceToken(), Map.of("username", username, "password", password));
            adminCall(HttpMethod.POST,
                    "/api/v1/admin/tenants/" + tenantId + "/role-assignments",
                    serviceToken(),
                    Map.of("userId", provisioned.get("userId"), "role", role));
            rolePasswords.put(role, password);
        }

        // 3. publish the candidate into the scratch tenant (candidate versions only —
        //    the runtime-never-serves-drafts rule holds)
        publishCandidate(adminUsername, adminPassword, candidate);

        // 4. cases — fixtures then steps as synthetic actors, assertions frozen-clock
        List<Map<String, Object>> caseResults = new ArrayList<>();
        for (TestCase testCase : suite.cases()) {
            caseResults.add(runCase(testCase, rolePasswords, frozenAt));
        }
        boolean green = caseResults.stream().allMatch(r -> Boolean.TRUE.equals(r.get("passed")));
        // §9 suite pass-rate telemetry — the promotion gate's health at a glance.
        suiteRuns.counter("novaforge.suite.runs",
                        "app", candidate.apiName() == null ? "unknown" : candidate.apiName(),
                        "outcome", green ? "green" : "red")
                .increment();
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("runId", runId);
        artifact.put("suite", suite.apiName());
        artifact.put("appVersion", "candidate");
        artifact.put("tenantId", tenantId);
        artifact.put("frozenAt", frozenAt.toString());
        artifact.put("green", green);
        artifact.put("cases", caseResults);
        return artifact;
    }
    private Map<String, Object> runCase(TestCase testCase,
                                        Map<String, String> rolePasswords, Instant frozenAt) {
        Map<String, Object> scope = new LinkedHashMap<>();   // "Entity[n]" → last record map
        List<String> failures = new ArrayList<>();
        try {
            for (var fixture : testCase.fixtures()) {
                String token = actorToken(fixture.asRole(), rolePasswords);
                // fixture actors without a named role run as the scratch admin
                token = token.equals("__admin__")
                        ? passwordGrantAdmin(rolePasswords) : token;
                Map<String, Object> body = interpolate(fixture.template(), scope);
                JsonNode created = runtimeCall(HttpMethod.POST,
                        "/api/v1/runtime/" + fixture.entity(), token, MAPPER.writeValueAsString(body));
                remember(scope, fixture.entity(), created);
            }
            for (Step step : testCase.steps()) {
                String token = actorToken(step.asRole(), rolePasswords);
                token = token.equals("__admin__")
                        ? passwordGrantAdmin(rolePasswords) : token;
                String body = step.template() == null ? "{}"
                        : MAPPER.writeValueAsString(interpolate(step.template(), scope));
                JsonNode result = switch (step.op()) {
                    case "createRecord" -> runtimeCall(HttpMethod.POST,
                            "/api/v1/runtime/" + step.entity(), token, body);
                    case "updateRecord" -> runtimeCall(HttpMethod.PATCH,
                            "/api/v1/runtime/" + step.entity() + "/"
                                    + interpolateText(step.recordId(), scope), token, body);
                    case "deleteRecord" -> runtimeCall(HttpMethod.DELETE,
                            "/api/v1/runtime/" + step.entity() + "/"
                                    + interpolateText(step.recordId(), scope)
                                    + "?version=" + interpolateText(
                                    String.valueOf(step.template().get("version")), scope),
                            token, null);
                    default -> throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                            "unknown suite step op: " + step.op());
                };
                String expect = step.expect() == null ? "ok" : step.expect();
                if (!expectationHolds(expect, result)) {
                    failures.add("step " + step.op() + " " + step.entity() + " expected " + expect + " but got "
                            + MAPPER.writeValueAsString(result));
                }
                if (result.isObject() && result.hasNonNull("id")) {
                    remember(scope, step.entity(), result);
                }
            }
            // assertions: DSL predicates over ${Entity[n].path} — frozen clock
            Clock frozen = Clock.fixed(frozenAt, java.time.ZoneOffset.UTC);
            for (String assertion : testCase.assertExpressions()) {
                String resolved = interpolateAssertion(assertion, scope);
                Object outcome;
                try {
                    outcome = com.novaforge.expression.Expression.parse(resolved)
                            .evaluate(com.novaforge.expression.Expression.Bindings.of(Map.of()), frozen);
                } catch (RuntimeException e) {
                    failures.add("assertion '" + assertion + "' failed to evaluate: " + e.getMessage());
                    continue;
                }
                if (!Boolean.TRUE.equals(outcome)) {
                    failures.add("assertion '" + assertion + "' is false");
                }
            }
        } catch (Exception e) {
            failures.add("case aborted: " + e.getMessage());
        }
        Map<String, Object> verdict = new LinkedHashMap<>();
        verdict.put("name", testCase.name());
        verdict.put("passed", failures.isEmpty());
        verdict.put("failures", failures);
        return verdict;
    }

    private boolean expectationHolds(String expect, JsonNode result) {
        if ("ok".equals(expect)) {
            return result.isObject() && result.hasNonNull("id")
                    || (result.isObject() && result.has("status"));
        }
        if (expect.startsWith("error(") && expect.endsWith(")")) {
            return result.hasNonNull("code")
                    && expect.substring(6, expect.length() - 1).equals(result.path("code").asString());
        }
        if (expect.startsWith("validation(") && expect.endsWith(")")) {
            String rule = expect.substring(11, expect.length() - 1);
            return result.hasNonNull("errors")
                    && result.get("errors").toString().contains(rule);
        }
        return false;
    }

    // --- scratch tenant machinery ---

    /** Creates + publishes the candidate app in the scratch tenant (as its admin). */
    private void publishCandidate(String adminUsername, String adminPassword,
                                  AppDefinition candidate) {
        String token = passwordGrant(adminUsername, adminPassword);
        Map<String, Object> body = Map.of(
                "apiName", candidate.apiName(),
                "label", candidate.label() == null ? candidate.apiName() : candidate.label(),
                "settings", MAPPER.convertValue(candidate.settings(), Map.class),
                "entities", MAPPER.convertValue(candidate.entities(), List.class),
                "permissionSet", MAPPER.convertValue(candidate.permissionSet(), Map.class));
        Map<String, Object> created = metadata.post()
                .uri("/api/v1/metadata/apps")
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(MAPPER.writeValueAsString(body))
                .retrieve()
                .body(Map.class);
        metadata.post()
                .uri("/api/v1/metadata/apps/" + created.get("id") + "/publish")
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .body(String.class);
    }

    private void remember(Map<String, Object> scope, String entity, JsonNode record) {
        int n = 0;
        while (scope.containsKey(entity + "[" + n + "]")) {
            n++;
        }
        scope.put(entity + "[" + n + "]", MAPPER.convertValue(record, Map.class));
    }

    /** Actor token: the named role's synthetic actor; the scratch admin by default. */
    private String actorToken(String role, Map<String, String> rolePasswords) {
        if (role == null) {
            return "__admin__";
        }
        return passwordGrant(role, rolePasswords.get(role));
    }

    // --- ${…} interpolation ---

    private Map<String, Object> interpolate(Map<String, Object> template, Map<String, Object> scope) {
        Map<String, Object> out = new LinkedHashMap<>();
        template.forEach((key, value) -> out.put(key, interpolateValue(value, scope)));
        return out;
    }

    private Object interpolateValue(Object value, Map<String, Object> scope) {
        if (value instanceof String text) {
            return interpolateText(text, scope);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), interpolateValue(v, scope)));
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> interpolateValue(item, scope)).toList();
        }
        return value;
    }

    private String interpolateText(String text, Map<String, Object> scope) {
        Matcher matcher = REFERENCE.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    String.valueOf(lookup(scope, matcher.group(1)))));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** Assertions substitute references with exact decimal literals for numerics. */
    private String interpolateAssertion(String assertion, Map<String, Object> scope) {
        Matcher matcher = REFERENCE.matcher(assertion);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            Object value = lookup(scope, matcher.group(1));
            String literal = value instanceof BigDecimal decimal ? decimal.toPlainString()
                    : value instanceof Number number ? number.toString()
                    : value instanceof Boolean b ? b.toString()
                    : value == null ? "null" : "'" + value + "'";
            matcher.appendReplacement(result, Matcher.quoteReplacement(literal));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private Object lookup(Map<String, Object> scope, String path) {
        // Entity[0].total → scope key Entity[0], then .path walk
        String[] parts = path.split("\\.", 2);
        Object base = scope.get(parts[0]);
        if (base == null || parts.length == 1) {
            return base;
        }
        Object current = base;
        for (String segment : parts[1].split("\\.")) {
            if (current instanceof Map<?, ?> map && map.containsKey(segment)) {
                current = map.get(segment);
            } else {
                return null;
            }
        }
        return current;
    }

    private static java.util.Set<String> rolesUsedBy(TestSuiteDefinition suite) {
        java.util.Set<String> roles = new java.util.LinkedHashSet<>();
        for (TestCase testCase : suite.cases()) {
            testCase.fixtures().stream().map(f -> f.asRole()).filter(r -> r != null).forEach(roles::add);
            testCase.steps().stream().map(Step::asRole).filter(r -> r != null).forEach(roles::add);
        }
        return roles;
    }

    // --- transport ---

    /**
     * Runtime calls return the body for BOTH success and failure statuses — suite
     * expectations match {@code expect: error(code)} against the problem payload, so
     * a 4xx is a result, not an exception.
     */
    private JsonNode runtimeCall(HttpMethod method, String path, String token, String body) {
        String response = runtime.method(method).uri(path)
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body == null ? "" : body)
                .exchange((request, clientResponse) -> clientResponse.bodyTo(String.class));
        return MAPPER.readTree(response == null || response.isBlank() ? "{}" : response);
    }

    private Map<String, Object> adminCall(HttpMethod method, String path, String token,
                                          Map<String, Object> body) {
        return runtime.method(method).uri(path)
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(MAPPER.writeValueAsString(body))
                .retrieve()
                .body(Map.class);
    }

    /**
     * Synthetic-actor password grant — rides the public {@code novaforge-api} client
     * (the confidential service client has direct grants disabled, by design).
     */
    private String passwordGrant(String username, String password) {
        Map<String, Object> response = auth.post()
                .uri("/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=password&client_id=novaforge-api"
                        + "&username=" + url(username) + "&password=" + url(password))
                .retrieve()
                .body(Map.class);
        if (response == null || response.get("access_token") == null) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "password grant failed for " + username);
        }
        return (String) response.get("access_token");
    }

    @SuppressWarnings("unchecked")
    private String serviceToken() {
        Map<String, Object> response = auth.post()
                .uri("/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=client_credentials&client_id=" + url(clientId)
                        + "&client_secret=" + url(clientSecret))
                .retrieve()
                .body(Map.class);
        if (response == null || response.get("access_token") == null) {
            throw new PlatformException(PlatformErrorCode.INTERNAL, "service grant failed");
        }
        return (String) response.get("access_token");
    }

    /** The scratch admin's password grant — the default synthetic actor. */
    private String passwordGrantAdmin(Map<String, String> rolePasswords) {
        String cached = rolePasswords.get("__admin-token__");
        if (cached != null) {
            return cached;
        }
        String token = passwordGrant(rolePasswords.get("__admin-user__"),
                rolePasswords.get("__admin__"));
        rolePasswords.put("__admin-token__", token);
        return token;
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
