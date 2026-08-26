package com.novaforge.metadata.harness;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.metadata.EntityDefinition;
import com.novaforge.metadata.TestSuiteDefinition;
import com.novaforge.metadata.TestSuiteDefinition.Step;
import com.novaforge.metadata.TestSuiteDefinition.TestCase;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
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
    private final RestClient workflow;
    private final RestClient reports;
    private final RestClient integration;
    private final String clientId;
    private final String clientSecret;
    private final io.micrometer.core.instrument.MeterRegistry suiteRuns;

    public TestRunner(@Value("${novaforge.data-runtime.url:http://localhost:8083}") String runtimeUrl,
                      @Value("${novaforge.metadata.url:http://localhost:8081}") String metadataUrl,
                      @Value("${novaforge.auth.issuer-uri:http://localhost:8082/realms/novaforge}") String issuer,
                      @Value("${novaforge.workflow.url:http://localhost:8086}") String workflowUrl,
                      @Value("${novaforge.reporting.url:http://localhost:8089}") String reportingUrl,
                      @Value("${novaforge.integration.url:http://localhost:8090}") String integrationUrl,
                      @Value("${novaforge.auth.service-client.id:novaforge-runtime}") String clientId,
                      @Value("${novaforge.auth.service-client.secret:novaforge-runtime-secret}") String clientSecret,
                      io.micrometer.core.instrument.MeterRegistry suiteRuns) {
        this.runtime = preEncoded(runtimeUrl);
        this.metadata = RestClient.builder().baseUrl(metadataUrl).build();
        this.auth = RestClient.builder().baseUrl(issuer).build();
        this.workflow = preEncoded(workflowUrl);
        this.reports = RestClient.builder().baseUrl(reportingUrl).build();
        this.integration = preEncoded(integrationUrl);
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.suiteRuns = suiteRuns;
    }

    /**
     * A client whose URIs are used verbatim: the runner pre-encodes its query
     * strings (the filter DSL node rides {@code ?filter=} as compact percent-encoded
     * JSON — PHASE-1 §5's canonical encoding), and RestClient's template mode would
     * re-encode the {@code %} sequences into {@code %25…} on arrival.
     */
    private static RestClient preEncoded(String baseUrl) {
        org.springframework.web.util.DefaultUriBuilderFactory uris =
                new org.springframework.web.util.DefaultUriBuilderFactory(baseUrl);
        uris.setEncodingMode(org.springframework.web.util.DefaultUriBuilderFactory.EncodingMode.NONE);
        return RestClient.builder().uriBuilderFactory(uris).build();
    }

    /** One suite run: scratch tenant → candidate publish → cases → verdict. */
    public Map<String, Object> run(AppDefinition candidate, TestSuiteDefinition suite, UUID actorId) {
        Instant frozenAt = Instant.now();
        String runId = UUID.randomUUID().toString();
        String scratchName = "scratch-" + runId.substring(0, 8);
        String adminUsername = "scratch-admin-" + runId.substring(0, 8);
        String adminPassword = SCRATCH_PASSWORD_PREFIX + UUID.randomUUID();

        // 0. the harness-provided mock connector (§10): a stub server in the scratch
        //    environment binds every connector's baseUrl so the bank-feed journey
        //    runs offline — nothing here reaches a real provider
        MockConnector mock = MockConnector.start(candidate);
        candidate = mock.rewrite(candidate);
        try {
            return runWithScratch(mock, candidate, suite, actorId, frozenAt, runId,
                    scratchName, adminUsername, adminPassword);
        } finally {
            mock.stop();
        }
    }

    private Map<String, Object> runWithScratch(MockConnector mock, AppDefinition candidate,
                                                TestSuiteDefinition suite, UUID actorId,
                                                Instant frozenAt, String runId,
                                                String scratchName, String adminUsername,
                                                String adminPassword) {

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
        // The provisioned username per role — `actor-<role>-<runId8>` (the token
        // grant needs the username, never the role name; found live 2026-08-26:
        // the grant rode the bare role and Keycloak answered user_not_found —
        // the journey tests stub the token endpoint, so only a real run caught it)
        Map<String, String> roleUsernames = new HashMap<>();
        roleUsernames.put("__admin__", adminUsername);
        for (String role : rolesUsedBy(suite)) {
            String username = "actor-" + role + "-" + runId.substring(0, 8);
            String password = SCRATCH_PASSWORD_PREFIX + UUID.randomUUID();
            Map<String, Object> provisioned = adminCall(HttpMethod.POST,
                    "/api/v1/admin/tenants/" + tenantId + "/users",
                    serviceToken(), Map.of("username", username, "password", password));
            // App roles assign app-scoped (PHASE-2 §9: the store CHECKs
            // `App.role` — PascalCase app, camelCase role — and RoleMatrix matches
            // the same spelling); a role already carrying a dot passes verbatim.
            // Found live 2026-08-26: the bare form 500'd on the constraint — the
            // journey tests stub this admin leg, so only a real run caught it.
            String assigned = role.contains(".") ? role
                    : candidate.apiName() + "." + role;
            adminCall(HttpMethod.POST,
                    "/api/v1/admin/tenants/" + tenantId + "/role-assignments",
                    serviceToken(),
                    Map.of("userId", provisioned.get("userId"), "role", assigned));
            rolePasswords.put(role, password);
            roleUsernames.put(role, username);
        }

        // 3. publish the candidate into the scratch tenant (candidate versions only —
        //    the runtime-never-serves-drafts rule holds)
        publishCandidate(adminUsername, adminPassword, candidate);

        // 3b. the scratch tenant's webhook secrets (§10): provisioned through the
        //     Integration Service's builder surface as the scratch admin — the
        //     harness then signs postWebhook steps with what it provisioned, so
        //     suites exercise the real HMAC path
        Map<String, String> hookSecrets = provisionHookSecrets(candidate,
                passwordGrantAdmin(rolePasswords));

        // 4. cases — fixtures then steps as synthetic actors, assertions frozen-clock
        List<Map<String, Object>> caseResults = new ArrayList<>();
        for (TestCase testCase : suite.cases()) {
            caseResults.add(runCase(testCase, rolePasswords, roleUsernames, frozenAt,
                    candidate.apiName(), tenantId, hookSecrets));
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
        if (mock.serves()) {
            artifact.put("mockConnector", Map.of("hits", mock.hits(),
                    "lastPath", String.valueOf(mock.lastPath())));
        }
        return artifact;
    }

    private Map<String, Object> runCase(TestCase testCase, Map<String, String> rolePasswords,
                                        Map<String, String> roleUsernames, Instant frozenAt,
                                        String appApiName, String tenantId,
                                        Map<String, String> hookSecrets) {
        Map<String, Object> scope = new LinkedHashMap<>();   // "Entity[n]" → last record map
        List<String> failures = new ArrayList<>();
        // PHASE-3 §7's per-case override: an explicit `clock` re-freezes the case
        // (default: the run's start instant) — period-lock-style cases advance it
        // explicitly instead of waiting on wall time
        Instant caseClock = frozenAt;
        if (testCase.clock() != null && !testCase.clock().isBlank()) {
            try {
                caseClock = Instant.parse(testCase.clock());
            } catch (RuntimeException malformed) {
                failures.add("case clock must be an ISO-8601 instant: " + testCase.clock());
            }
        }
        try {
            for (var fixture : testCase.fixtures()) {
                String token = actorToken(fixture.asRole(), rolePasswords, roleUsernames);
                // fixture actors without a named role run as the scratch admin
                token = token.equals("__admin__")
                        ? passwordGrantAdmin(rolePasswords) : token;
                Map<String, Object> body = interpolate(fixture.template(), scope);
                JsonNode created = runtimeCall(HttpMethod.POST,
                        "/api/v1/runtime/" + fixture.entity(), token, MAPPER.writeValueAsString(body));
                remember(scope, fixture.entity(), created);
            }
            for (Step step : testCase.steps()) {
                String token = actorToken(step.asRole(), rolePasswords, roleUsernames);
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
                    case "queryRecord" -> queryRecord(step, token, scope);
                    case "resolveTask" -> resolveTask(step, token, scope);
                    case "runReport" -> runReport(step, token, scope, appApiName);
                    case "postWebhook" -> postWebhook(step, scope, tenantId, hookSecrets);
                    case "scanSla" -> scanSla(step, scope, tenantId);
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
            // assertions: DSL predicates over ${Entity[n].path} — the case's frozen
            // clock (the run start, or the case's explicit override above)
            Clock frozen = Clock.fixed(caseClock, java.time.ZoneOffset.UTC);
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
        if (testCase.clock() != null && !testCase.clock().isBlank()) {
            verdict.put("clock", testCase.clock());   // the case's override, visible in the artifact
        }
        return verdict;
    }

    /**
     * ADR-010's named error forms resolve through the registry: any
     * {@link PlatformErrorCode} name ({@code error(SOD_VIOLATION)}, §12) maps onto
     * its code, so codes appended later need no harness change; numeric codes pass
     * through untouched.
     */
    private static String registryCode(String expected) {
        try {
            return PlatformErrorCode.valueOf(expected).code();
        } catch (IllegalArgumentException notARegistryName) {
            return expected;
        }
    }

    /**
     * queryRecord (§12): a filtered read as the step's role → {@code {count, ids}} in
     * scope as {@code ${Query[n]}}; entity {@code Task} queries the workflow inbox
     * (v1 filter: {@code {status: <string>}}) and remembers each row as
     * {@code ${Task[n]}} for status/assignee assertions. Both branches cap the page
     * at 200 — {@code count} is the full total; {@code ids} and the remembered rows
     * are the first page.
     */
    private JsonNode queryRecord(Step step, String token, Map<String, Object> scope) {
        Object filter = step.template() == null ? null
                : interpolateValue(step.template().get("filter"), scope);
        if ("Task".equals(step.entity())) {
            String status = "OPEN";
            if (filter != null) {
                if (!(filter instanceof Map<?, ?> taskFilter) || taskFilter.size() != 1
                        || !(taskFilter.get("status") instanceof String filtered)) {
                    throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                            "queryRecord Task filter supports {status: <string>} in v1: " + filter);
                }
                status = filtered;
            }
            JsonNode inbox = workflowCall(HttpMethod.GET, "/api/v1/workflow/tasks?status="
                    + url(status) + "&size=200", token, null);
            List<String> ids = new ArrayList<>();
            for (JsonNode task : inbox.path("rows")) {
                remember(scope, "Task", task);
                ids.add(task.path("id").asString());
            }
            if (ids.isEmpty()) {
                scope.putIfAbsent("Task[0]", Map.of());   // assertions see empties, not errors
            }
            return queryResult(scope, inbox.path("total").asLong(), ids);
        }
        StringBuilder uri = new StringBuilder("/api/v1/runtime/").append(step.entity())
                .append("?page=").append(url("{\"size\":200}"));
        if (filter != null) {
            uri.append("&filter=").append(url(MAPPER.writeValueAsString(filter)));
        }
        JsonNode page = runtimeCall(HttpMethod.GET, uri.toString(), token, null);
        List<String> ids = new ArrayList<>();
        for (JsonNode row : page.path("rows")) {
            ids.add(row.path("id").asString());
        }
        return queryResult(scope, page.path("total").asLong(), ids);
    }

    /** The §12 result shape — {@code {count, ids}}, remembered as the next ${Query[n]}. */
    private JsonNode queryResult(Map<String, Object> scope, long count, List<String> ids) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("count", count);
        query.put("ids", ids);
        return MAPPER.valueToTree(remember(scope, "Query", MAPPER.valueToTree(query)));
    }

    /**
     * runReport (PHASE-5 §9): a report run through the public surface as the step's
     * actor — the candidate app is published in the scratch tenant, so the Reporting
     * Service resolves it through the published read and executes the compiled query
     * under the actor's grants (report: execute + sharing row filters, §8). The result
     * lands in scope as the next {@code ${Report[n]}} carrying {@code {rowCount, totals}}
     * — the §7 A/R-vs-ledger reconciliation assertions read exactly those.
     */
    private JsonNode runReport(Step step, String token, Map<String, Object> scope,
                               String appApiName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("app", appApiName);
        body.put("params", step.template() == null ? Map.of()
                : interpolate(step.template(), scope));
        JsonNode run = MAPPER.readTree(call(reports, HttpMethod.POST,
                "/api/v1/reports/" + url(step.entity()) + "/run", token,
                MAPPER.writeValueAsString(body)));
        Map<String, Object> shaped = new LinkedHashMap<>();
        shaped.put("rowCount", run.path("rows").size());
        shaped.put("totals", MAPPER.convertValue(run.path("totals"), Map.class));
        return MAPPER.valueToTree(remember(scope, "Report", MAPPER.valueToTree(shaped)));
    }

    /** resolveTask (§12): approve/reject through the inbox API — no back door. */
    private JsonNode resolveTask(Step step, String token, Map<String, Object> scope) {
        String taskId = interpolateText(step.recordId(), scope);
        String action = step.template() == null ? "approve"
                : String.valueOf(step.template().getOrDefault("action", "approve"));
        if (!"approve".equals(action) && !"reject".equals(action)) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "resolveTask action must be approve or reject: " + action);
        }
        String body = null;
        if (step.template() != null && step.template().get("comment") != null) {
            body = MAPPER.writeValueAsString(Map.of("comment",
                    interpolateText(String.valueOf(step.template().get("comment")), scope)));
        }
        return workflowCall(HttpMethod.POST,
                "/api/v1/workflow/tasks/" + url(taskId) + "/" + action, token, body);
    }

    /**
     * Workflow calls mirror {@link #runtimeCall}: both success and failure statuses
     * return the body — {@code expect: error(code)} matches the problem payload, so
     * a 4xx (an SoD rejection, a resolution race) is a result, not an exception.
     */
    private JsonNode workflowCall(HttpMethod method, String uri, String token, String body) {
        return MAPPER.readTree(call(workflow, method, uri, token, body));
    }

    private boolean expectationHolds(String expect, JsonNode result) {
        if ("ok".equals(expect)) {
            return result.isObject() && result.hasNonNull("id")
                    || (result.isObject() && result.has("status"))
                    || (result.isObject() && result.has("count"))   // queryRecord results
                    || (result.isObject() && result.has("rowCount"))   // runReport results
                    || (result.isObject() && result.has("scanned"));   // scanSla results
        }
        if (expect.startsWith("error(") && expect.endsWith(")")) {
            String expected = registryCode(expect.substring(6, expect.length() - 1));
            return result.hasNonNull("code") && expected.equals(result.path("code").asString());
        }
        if (expect.startsWith("validation(") && expect.endsWith(")")) {
            String rule = expect.substring(11, expect.length() - 1);
            return result.hasNonNull("errors")
                    && result.get("errors").toString().contains(rule);
        }
        return false;
    }

    /**
     * postWebhook (PHASE-6 §10): the harness signs with the scratch tenant's hook
     * secret — the real HMAC path — and posts to the anonymous inbound surface.
     * Deliberately mangled signatures ride {@code template.headers} overrides
     * ({@code X-NovaForge-Signature}/{@code X-NovaForge-Timestamp}); the applied
     * record lands in scope under the hook's entity for assertions. Both success
     * bodies and problem bodies are results ({@code expect: error(SIGNATURE_INVALID)}).
     */
    private JsonNode postWebhook(Step step, Map<String, Object> scope, String tenantId,
                                 Map<String, String> hookSecrets) {
        Map<String, Object> template = interpolate(step.template(), scope);
        String hookId = String.valueOf(template.get("hookId"));
        String body = MAPPER.writeValueAsString(template.get("body"));
        String secret = hookSecrets.get(hookId);
        if (secret == null) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "postWebhook hookId has no provisioned scratch secret: " + hookId);
        }
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = hmac(secret, timestamp, body);
        // header overrides: the mangled-signature and stale-timestamp legs of the matrix
        Map<String, Object> headers = template.get("headers") instanceof Map<?, ?> overrides
                ? MAPPER.convertValue(overrides, Map.class) : Map.of();
        if (headers.get("X-NovaForge-Timestamp") instanceof String override) {
            timestamp = override;
        }
        if (headers.get("X-NovaForge-Signature") instanceof String override) {
            signature = override;
        }
        StringBuilder uri = new StringBuilder("/api/v1/webhooks/inbound/")
                .append(url(tenantId)).append("/").append(url(step.entity()))
                .append("/").append(url(hookId));
        final String ts = timestamp;
        final String sig = signature;
        String response = integration.post()
                .uri(uri.toString())
                .header("X-NovaForge-Timestamp", ts)
                .header("X-NovaForge-Signature", sig)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, clientResponse) -> clientResponse.bodyTo(String.class));
        JsonNode result = MAPPER.readTree(response == null || response.isBlank()
                ? "{}" : response);
        if (result.isObject() && result.hasNonNull("id")) {
            remember(scope, step.entity(), result);
        }
        return result;
    }

    /**
     * scanSla (PHASE-4 §12's clock-advanced leg): drives the Workflow Service's
     * scratch-scoped as-of scan with the platform service client — the same trusted
     * leg every internal surface rides — so warn/breach/escalation fire
     * deterministically at the governed instant, never by sleeping. The template
     * carries {@code advance} (an ISO-8601 duration past now — tasks were created
     * before the step, so an advance at or past the target breaches and one between
     * {@code warnAt} and the target warns) or an absolute {@code asOf}; the counts
     * land in scope as the next {@code ${Scan[n]}}.
     */
    private JsonNode scanSla(Step step, Map<String, Object> scope, String tenantId) {
        Map<String, Object> template = interpolate(step.template() == null ? Map.of()
                : step.template(), scope);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId);
        if (template.get("advance") instanceof String advance) {
            body.put("advance", advance);
        } else if (template.get("asOf") instanceof String asOf) {
            body.put("asOf", asOf);
        } else {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "scanSla requires template.advance or template.asOf");
        }
        JsonNode result = MAPPER.readTree(call(workflow, HttpMethod.POST,
                "/api/v1/workflow/internal/sla/scan", serviceToken(),
                MAPPER.writeValueAsString(body)));
        return MAPPER.valueToTree(remember(scope, "Scan", result));
    }

    /** The pinned scheme (§5): hex HMAC-SHA256 over {@code timestamp + "." + body}. */
    static String hmac(String secret, String timestamp, String body) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL, "harness HMAC failed");
        }
    }

    /** Provisions a secret per inbound webhook ref; returns hookId → material. */
    private Map<String, String> provisionHookSecrets(AppDefinition candidate, String adminToken) {
        Map<String, String> secrets = new HashMap<>();
        for (com.novaforge.metadata.WebhookDefinition webhook
                : candidate.integrations().webhooks()) {
            if (!com.novaforge.metadata.WebhookDefinition.INBOUND.equals(webhook.direction())) {
                continue;
            }
            if (secrets.containsKey(webhook.id())) {
                continue;
            }
            String material = "scratch-hook-" + UUID.randomUUID();
            integration.post()
                    .uri("/api/v1/integrations/secrets/" + url(webhook.secretRef()))
                    .headers(headers -> headers.setBearerAuth(adminToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(MAPPER.writeValueAsString(Map.of("material", material)))
                    .exchange((request, clientResponse) -> clientResponse.bodyTo(String.class));
            secrets.put(webhook.id(), material);
        }
        return secrets;
    }

    // --- scratch tenant machinery ---

    /** Creates + publishes the candidate app in the scratch tenant (as its admin). */
    private void publishCandidate(String adminUsername, String adminPassword,
                                  AppDefinition candidate) {
        String token = passwordGrant(adminUsername, adminPassword);
        // The full candidate bundle — every branch the suites exercise rides the
        // publish (state machines, reports, dashboards, jobs: PHASE-7's suites pin
        // machine-enforced posting, freezeOnTerminal, and report assertions; a
        // reduced candidate would silently drop them).
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apiName", candidate.apiName());
        body.put("label", candidate.label() == null ? candidate.apiName() : candidate.label());
        body.put("settings", MAPPER.convertValue(candidate.settings(), Map.class));
        body.put("entities", MAPPER.convertValue(candidate.entities(), List.class));
        body.put("permissionSet", MAPPER.convertValue(candidate.permissionSet(), Map.class));
        body.put("integrations", MAPPER.convertValue(candidate.integrations(), Map.class));
        body.put("stateMachines", MAPPER.convertValue(candidate.stateMachines(), List.class));
        body.put("slas", MAPPER.convertValue(candidate.slas(), List.class));
        body.put("jobs", MAPPER.convertValue(candidate.jobs(), List.class));
        body.put("workflows", MAPPER.convertValue(candidate.workflows(), List.class));
        body.put("reports", MAPPER.convertValue(candidate.reports(), List.class));
        body.put("dashboards", MAPPER.convertValue(candidate.dashboards(), List.class));
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

    /**
     * Remembers a record under the entity's next free index; re-observing the same id
     * (an update, a resolved task) overwrites that entry in place — {@code ${Entity[n]}}
     * is the record's latest state, so post-step assertions read current values, and
     * a resolved {@code ${Task[0]}} no longer shows its pre-resolution snapshot.
     */
    private Map<String, Object> remember(Map<String, Object> scope, String entity,
                                         JsonNode record) {
        String id = record.path("id").asString(null);
        if (id != null) {
            for (int n = 0; scope.containsKey(entity + "[" + n + "]"); n++) {
                if (id.equals(String.valueOf(
                        ((Map<?, ?>) scope.get(entity + "[" + n + "]")).get("id")))) {
                    Map<String, Object> converted = MAPPER.convertValue(record, Map.class);
                    scope.put(entity + "[" + n + "]", converted);
                    return converted;
                }
            }
        }
        int n = 0;
        while (scope.containsKey(entity + "[" + n + "]")) {
            n++;
        }
        Map<String, Object> converted = MAPPER.convertValue(record, Map.class);
        scope.put(entity + "[" + n + "]", converted);
        return converted;
    }

    /** Actor token: the named role's synthetic actor; the scratch admin by default. */
    private String actorToken(String role, Map<String, String> rolePasswords,
                              Map<String, String> roleUsernames) {
        if (role == null) {
            return "__admin__";
        }
        return passwordGrant(roleUsernames.get(role), rolePasswords.get(role));
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
        return MAPPER.readTree(call(runtime, method, path, token, body));
    }

    /**
     * One transport for both engines: a body only when present (the JDK client
     * rejects a GET with a body), and a 2xx with no body reads as
     * {@code {"status":"ok"}} — a delete's 204 satisfies {@code expect: ok}.
     */
    private static String call(RestClient client, HttpMethod method, String uri,
                               String token, String body) {
        RestClient.RequestBodySpec spec = client.method(method).uri(uri)
                .headers(headers -> headers.setBearerAuth(token));
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((request, clientResponse) -> {
            String response = clientResponse.bodyTo(String.class);
            return response == null || response.isBlank()
                    ? (clientResponse.getStatusCode().is2xxSuccessful() ? "{\"status\":\"ok\"}" : "{}")
                    : response;
        });
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

    /**
     * The harness-provided mock connector (PHASE-6 §10): an in-process stub server
     * standing in for every provider — connector baseUrls rewrite to it before the
     * candidate publishes, so callConnector journeys run offline. GETs answer with
     * an empty page, POSTs echo the request body; hit counts and the last path ride
     * the run artifact for journey verification.
     */
    static final class MockConnector {

        private final com.sun.net.httpserver.HttpServer server;
        private final java.util.concurrent.atomic.AtomicInteger hits =
                new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicReference<String> lastPath =
                new java.util.concurrent.atomic.AtomicReference<>();

        private MockConnector(com.sun.net.httpserver.HttpServer server) {
            this.server = server;
        }

        static MockConnector start(AppDefinition candidate) {
            if (candidate == null || candidate.integrations().connectors().isEmpty()) {
                return new MockConnector(null);
            }
            try {
                com.sun.net.httpserver.HttpServer server =
                        com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                MockConnector mock = new MockConnector(server);
                server.createContext("/", exchange -> {
                    mock.hits.incrementAndGet();
                    mock.lastPath.set(exchange.getRequestURI().getPath());
                    byte[] response = ("GET".equals(exchange.getRequestMethod())
                            ? "{\"object\": \"list\", \"data\": []}"
                            : new String(exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8))
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    try (java.io.OutputStream out = exchange.getResponseBody()) {
                        out.write(response);
                    }
                });
                server.start();
                return mock;
            } catch (IOException e) {
                throw new PlatformException(PlatformErrorCode.INTERNAL,
                        "mock connector failed to start: " + e.getMessage());
            }
        }

        /** The candidate with every connector's baseUrl pointed at the stub. */
        AppDefinition rewrite(AppDefinition candidate) {
            if (server == null) {
                return candidate;
            }
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            List<com.novaforge.metadata.ConnectorDefinition> connectors =
                    candidate.integrations().connectors().stream()
                            .map(connector -> new com.novaforge.metadata.ConnectorDefinition(
                                    connector.id(), connector.type(), base + "/"
                                            + connector.id(), connector.credential(),
                                    connector.operations()))
                            .toList();
            return withIntegrations(candidate, new com.novaforge.metadata.IntegrationsDefinition(
                    connectors, candidate.integrations().webhooks(),
                    candidate.integrations().credentials(), candidate.integrations().imports()));
        }

        private static AppDefinition withIntegrations(AppDefinition app,
                                                      com.novaforge.metadata.IntegrationsDefinition integrations) {
            return new AppDefinition(app.id(), app.apiName(), app.label(), app.labelI18n(),
                    app.description(), app.entities(), app.pages(), app.settings(),
                    app.permissionSet(), app.testSuites(), app.stateMachines(), app.slas(),
                    app.jobs(), app.workflows(), app.reports(), app.dashboards(), integrations,
                    app.translations(), app.gapLog());
        }

        boolean serves() {
            return server != null;
        }

        int hits() {
            return hits.get();
        }

        String lastPath() {
            return lastPath.get();
        }

        void stop() {
            if (server != null) {
                server.stop(0);
            }
        }
    }
}
