package com.novaforge.metadata.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.metadata.TestSuiteDefinition;
import com.novaforge.metadata.TestSuiteDefinition.Step;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The §12 runner legs that CI can reach without the live stack (ADR-010 #3's no-test-mode
 * rule keeps the write path real; the *harness transport itself* is what these tests pin):
 * queryRecord against the inbox (a GET — never a POST — with the v1 status filter and a
 * 200-size page), resolveTask through approve/reject, the error-as-result contract that
 * {@code expect: error(SOD_VIOLATION)} depends on, filter interpolation, and the
 * id-match scope so a resolved {@code ${Task[n]}} reflects its post-resolution state.
 *
 * <p>Five stub servers stand in for auth/runtime/metadata/workflow/reporting; the
 * runner's own HTTP behavior — methods, paths, encodings — is asserted by the stubs
 * being strict.
 */
class TestRunnerJourneyTests {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final List<HttpServer> SERVERS = new ArrayList<>();

    /** A stub handler — responds (or 500s); declared throwing so lambdas stay terse. */
    private interface Stub {
        void handle(HttpExchange exchange, String body) throws IOException;
    }

    private static HttpServer server(Stub handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                handler.handle(exchange, body);
            } catch (IOException | RuntimeException failure) {
                respond(exchange, 500, "{\"code\":\"5000\",\"detail\":\"stub failure: "
                        + failure.getMessage() + "\"}");
            } finally {
                exchange.close();
            }
        });
        server.start();
        SERVERS.add(server);
        return server;
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // --- what the stubs observed ---

    private static final AtomicReference<String> INBOX_METHOD = new AtomicReference<>();
    private static final AtomicReference<String> INBOX_QUERY = new AtomicReference<>();
    private static final AtomicReference<String> THING_FILTER = new AtomicReference<>();
    private static final AtomicReference<String> REPORT_METHOD = new AtomicReference<>();
    private static final AtomicReference<String> REPORT_BODY = new AtomicReference<>();
    private static final AtomicReference<String> WEBHOOK_PATH = new AtomicReference<>();
    private static final AtomicReference<String> WEBHOOK_TIMESTAMP = new AtomicReference<>();
    private static final AtomicReference<String> WEBHOOK_SIGNATURE = new AtomicReference<>();
    private static final AtomicReference<String> WEBHOOK_BODY = new AtomicReference<>();
    private static final AtomicReference<String> SECRET_PATH = new AtomicReference<>();
    private static final AtomicReference<String> SECRET_BODY = new AtomicReference<>();
    private static final AtomicReference<String> SCAN_METHOD = new AtomicReference<>();
    private static final AtomicReference<String> SCAN_PATH = new AtomicReference<>();
    private static final AtomicReference<String> SCAN_BODY = new AtomicReference<>();
    private static final AtomicReference<String> SCAN_TOKEN = new AtomicReference<>();
    private static final AtomicReference<String> REOBSERVE_METHOD = new AtomicReference<>();
    private static final AtomicReference<String> REOBSERVE_PATH = new AtomicReference<>();

    @BeforeAll
    static void stubs() throws IOException {
        // auth: password grants echo the username as the token; the service grant is opaque
        HttpServer auth = server((exchange, body) -> {
            String username = body.contains("grant_type=password")
                    ? URLDecoder.decode(body.replaceAll(".*username=([^&]+).*", "$1"),
                    StandardCharsets.UTF_8)
                    : "svc";
            respond(exchange, 200, "{\"access_token\":\"" + username + "\"}");
        });
        // runtime: the admin surface for scratch provisioning + the record list API
        HttpServer runtime = server((exchange, body) -> {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/api/v1/admin/")) {
                respond(exchange, 200, path.endsWith("/role-assignments")
                        ? "{}" : "{\"tenantId\":\"11111111-1111-1111-1111-111111111111\",\"userId\":\"22222222-2222-2222-2222-222222222222\"}");
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())
                    && path.equals("/api/v1/runtime/Thing/r-1")) {
                // the resolveTask re-observation leg: a successful resolution re-reads
                // the task's record so ${Entity[n]} assertions see the resumed state
                REOBSERVE_METHOD.set(exchange.getRequestMethod());
                REOBSERVE_PATH.set(path);
                respond(exchange, 200, "{\"id\":\"r-1\",\"name\":\"t-1\",\"status\":\"POSTED\"}");
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && path.equals("/api/v1/runtime/Thing")) {
                for (String param : exchange.getRequestURI().getRawQuery().split("&")) {
                    if (param.startsWith("filter=")) {
                        THING_FILTER.set(URLDecoder.decode(param.substring(7), StandardCharsets.UTF_8));
                    }
                }
                respond(exchange, 200, "{\"rows\":[{\"id\":\"r-1\",\"name\":\"t-1\"}],\"total\":1}");
                return;
            }
            respond(exchange, 405, "{\"code\":\"4000\",\"detail\":\"unexpected "
                    + exchange.getRequestMethod() + " " + path + "\"}");
        });
        // metadata: the candidate publish the runner drives
        HttpServer metadata = server((exchange, body) ->
                respond(exchange, 200, exchange.getRequestURI().getPath().endsWith("/publish")
                        ? "" : "{\"id\":\"33333333-3333-3333-3333-333333333333\"}"));
        // workflow: the inbox — the list is GET-only (TaskController), resolutions POST;
        // the internal SLA scan is POST-only with the service token (§12's clock leg)
        HttpServer workflow = server((exchange, body) -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.equals("/api/v1/workflow/tasks")) {
                INBOX_METHOD.set(method);
                INBOX_QUERY.set(exchange.getRequestURI().getRawQuery());
                if (!"GET".equals(method)) {
                    respond(exchange, 405, "{\"code\":\"4000\",\"detail\":\"inbox list is GET\"}");
                    return;
                }
                respond(exchange, 200, "{\"rows\":[{\"id\":\"t-1\",\"type\":\"approval\","
                        + "\"status\":\"OPEN\",\"assignee\":\"actor-manager\"}],\"total\":1}");
                return;
            }
            if (path.equals("/api/v1/workflow/internal/sla/scan")) {
                SCAN_METHOD.set(method);
                SCAN_PATH.set(path);
                SCAN_BODY.set(body);
                SCAN_TOKEN.set(exchange.getRequestHeaders().getFirst("Authorization"));
                if (!"POST".equals(method)) {
                    respond(exchange, 405, "{\"code\":\"4000\",\"detail\":\"sla scan is POST\"}");
                    return;
                }
                respond(exchange, 200, "{\"scanned\":true,\"asOf\":\"2026-08-24T12:00:00Z\","
                        + "\"warned\":1,\"breached\":1}");
                return;
            }
            if (path.endsWith("/approve") && "POST".equals(method)) {
                respond(exchange, 200, "{\"id\":\"t-1\",\"type\":\"approval\","
                        + "\"status\":\"APPROVED\",\"assignee\":\"actor-manager\","
                        + "\"entity\":\"JourneyApp.Thing\",\"recordId\":\"r-1\"}");
                return;
            }
            if (path.endsWith("/reject") && "POST".equals(method)) {
                // the §4 fail-closed SoD rejection, as the workflow service renders it
                respond(exchange, 400, "{\"code\":\"4011\",\"detail\":\"initiator cannot resolve their own request\"}");
                return;
            }
            respond(exchange, 405, "{\"code\":\"4000\",\"detail\":\"unexpected "
                    + method + " " + path + "\"}");
        });

        // reporting: the run surface (PHASE-5 §9) — POST-only, actor token, app-bound
        HttpServer reporting = server((exchange, body) -> {
            String path = exchange.getRequestURI().getPath();
            REPORT_METHOD.set(exchange.getRequestMethod());
            REPORT_BODY.set(body);
            if ("POST".equals(exchange.getRequestMethod())
                    && path.equals("/api/v1/reports/arAging/run")) {
                respond(exchange, 200, "{\"columns\":[\"customer\",\"sum_amount\"],"
                        + "\"rows\":[{\"customer\":\"acme\",\"sum_amount\":300.50}],"
                        + "\"totals\":{\"sum_amount\":300.50},\"chart\":{}}");
                return;
            }
            respond(exchange, 405, "{\"code\":\"4000\",\"detail\":\"unexpected "
                    + exchange.getRequestMethod() + " " + path + "\"}");
        });

        // integration (PHASE-6 §10): secret provisioning (the builder surface) and the
        // anonymous inbound webhook route — both strict about method and shape
        HttpServer integration = server((exchange, body) -> {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/api/v1/integrations/secrets/")) {
                SECRET_PATH.set(path);
                SECRET_BODY.set(body);
                respond(exchange, 200, "{\"ref\":\"hook_wh_feed\",\"status\":\"provisioned\"}");
                return;
            }
            if (path.startsWith("/api/v1/webhooks/inbound/")) {
                WEBHOOK_PATH.set(path);
                WEBHOOK_TIMESTAMP.set(exchange.getRequestHeaders().getFirst("X-NovaForge-Timestamp"));
                WEBHOOK_SIGNATURE.set(exchange.getRequestHeaders().getFirst("X-NovaForge-Signature"));
                WEBHOOK_BODY.set(body);
                respond(exchange, 200, "{\"id\":\"pay-9\",\"reference\":\"pay-9\",\"amount\":42.5}");
                return;
            }
            respond(exchange, 405, "{\"code\":\"4000\",\"detail\":\"unexpected \""
                    + exchange.getRequestMethod() + " " + path + "\"}");
        });

        runner = new TestRunner(
                "http://127.0.0.1:" + runtime.getAddress().getPort(),
                "http://127.0.0.1:" + metadata.getAddress().getPort(),
                "http://127.0.0.1:" + auth.getAddress().getPort(),
                "http://127.0.0.1:" + workflow.getAddress().getPort(),
                "http://127.0.0.1:" + reporting.getAddress().getPort(),
                "http://127.0.0.1:" + integration.getAddress().getPort(),
                "novaforge-runtime", "novaforge-runtime-secret", new SimpleMeterRegistry());
    }

    private static TestRunner runner;

    @AfterAll
    static void stop() {
        SERVERS.forEach(stub -> stub.stop(0));
    }

    @Test
    @DisplayName("§12 journey through the runner: inbox query → approve → SoD reject, errors as results")
    void inboxJourneyRunsGreen() {
        Map<String, Object> thingFilter = new LinkedHashMap<>();
        thingFilter.put("field", "name");
        thingFilter.put("op", "eq");
        thingFilter.put("value", "${Task[0].id}");   // interpolated before the query is sent
        TestSuiteDefinition suite = new TestSuiteDefinition("journey", null, List.of(
                new TestSuiteDefinition.TestCase("approval journey", List.of(), List.of(
                        new Step("queryRecord", "Task", "manager", null,
                                Map.of("filter", Map.of("status", "OPEN")), "ok"),
                        new Step("resolveTask", "Task", "manager", "${Task[0].id}",
                                Map.of("action", "approve", "comment", "go"), "ok"),
                        new Step("queryRecord", "Thing", "manager", null,
                                Map.of("filter", thingFilter), "ok"),
                        new Step("runReport", "arAging", "manager", null,
                                Map.of("status", "POSTED", "asOf", "2026-08-23"), "ok"),
                        new Step("resolveTask", "Task", "manager", "${Task[0].id}",
                                Map.of("action", "reject"), "error(SOD_VIOLATION)"),
                        new Step("scanSla", null, null, null,
                                Map.of("advance", "PT26H"), "ok")),
                        List.of("${Task[0].status} == 'APPROVED'",
                                "${Thing[0].status} == 'POSTED'",
                                "${Query[0].count} == 1",
                                "${Query[1].count} == 1",
                                "${Report[0].rowCount} == 1",
                                "${Report[0].totals.sum_amount} == 300.5",
                                "${Scan[0].warned} == 1",
                                "${Scan[0].breached} == 1"))));

        AppDefinition candidate = new AppDefinition(null, "JourneyApp", null, null, null,
                null, null, null, null, null, null, null, null);
        Map<String, Object> artifact = runner.run(candidate, suite, null);

        assertTrue(Boolean.TRUE.equals(artifact.get("green")),
                () -> "journey should be green: " + artifact.get("cases"));
        // the inbox query rode GET with the v1 status filter and a full first page
        assertEquals("GET", INBOX_METHOD.get());
        assertTrue(INBOX_QUERY.get().contains("status=OPEN"), INBOX_QUERY.get());
        assertTrue(INBOX_QUERY.get().contains("size=200"), INBOX_QUERY.get());
        // ${...} references in filters are interpolated before the query is sent
        assertEquals(MAPPER.readValue("{\"field\":\"name\",\"op\":\"eq\",\"value\":\"t-1\"}", Map.class),
                MAPPER.readValue(THING_FILTER.get(), Map.class));
        // the report run rode POST with the app bound and the params carried
        // (PHASE-5 §9: the step's actor token, the candidate app's apiName)
        assertEquals("POST", REPORT_METHOD.get());
        assertEquals(MAPPER.readValue(
                "{\"app\":\"JourneyApp\",\"params\":{\"status\":\"POSTED\",\"asOf\":\"2026-08-23\"},\"fresh\":true}", Map.class),
                MAPPER.readValue(REPORT_BODY.get(), Map.class));
        // §12's clock leg: the scan rode the internal surface, POST-only, with the
        // service client's token — never an actor's — and the scratch tenant bound
        assertEquals("POST", SCAN_METHOD.get());
        assertEquals("/api/v1/workflow/internal/sla/scan", SCAN_PATH.get());
        assertEquals(MAPPER.readValue(
                "{\"tenantId\":\"11111111-1111-1111-1111-111111111111\",\"advance\":\"PT26H\"}", Map.class),
                MAPPER.readValue(SCAN_BODY.get(), Map.class));
        assertEquals("Bearer svc", SCAN_TOKEN.get());
        // the resolveTask re-observation: a successful resolution re-reads the task's
        // record through the runtime (a GET, the app-prefixed entity stripped) so
        // post-resolution assertions read the resumed state — never a stale snapshot
        assertEquals("GET", REOBSERVE_METHOD.get());
        assertEquals("/api/v1/runtime/Thing/r-1", REOBSERVE_PATH.get());
    }

    @Test
    @DisplayName("§10 webhook journey: the harness provisions the scratch secret and signs the real HMAC path")
    void webhookJourneySigns() {
        TestSuiteDefinition suite = new TestSuiteDefinition("webhooks", null, List.of(
                new TestSuiteDefinition.TestCase("bank feed", List.of(), List.of(
                        new Step("postWebhook", "Payment", "manager", null,
                                Map.of("hookId", "wh_feed",
                                       "body", Map.of("data", Map.of("id", "evt-9",
                                               "ref", "pay-9", "amount", "42.50"))),
                                "ok")),
                        List.of("${Payment[0].amount} == 42.5"))));

        // the candidate carries the inbound webhook + its secret reference
        AppDefinition candidate = DefinitionParser.parseApp("""
                { "apiName": "JourneyApp",
                  "entities": [
                    { "apiName": "Payment", "displayField": "reference",
                      "fields": [
                        { "apiName": "reference", "type": "text", "required": true },
                        { "apiName": "amount", "type": "decimal", "precision": 18, "scale": 4 } ] } ],
                  "integrations": {
                    "webhooks": [
                      { "id": "wh_feed", "direction": "inbound", "entity": "Payment",
                        "secretRef": "hook_wh_feed",
                        "mapping": { "mode": "create",
                                     "fields": { "reference": "${data.ref}", "amount": "${data.amount}" } } } ] } }
                """);
        Map<String, Object> artifact = runner.run(candidate, suite, null);
        assertTrue(Boolean.TRUE.equals(artifact.get("green")),
                () -> "webhook journey should be green: " + artifact.get("cases"));

        // the scratch secret was provisioned through the builder surface (§10)
        assertTrue(SECRET_PATH.get().endsWith("/api/v1/integrations/secrets/hook_wh_feed"),
                SECRET_PATH.get());
        assertTrue(SECRET_BODY.get().contains("\"material\":\"scratch-hook-"), SECRET_BODY.get());
        // the post rode the anonymous route with the §5 HMAC scheme over the raw body
        assertTrue(WEBHOOK_PATH.get().matches(
                "/api/v1/webhooks/inbound/[0-9a-f-]{36}/Payment/wh_feed"), WEBHOOK_PATH.get());
        String expected = TestRunner.hmac(provisionedMaterial(), WEBHOOK_TIMESTAMP.get(),
                WEBHOOK_BODY.get());
        assertEquals(expected, WEBHOOK_SIGNATURE.get());
    }

    /** Pulls the provisioned material back out of the provisioning call's body. */
    private static String provisionedMaterial() {
        return java.util.regex.Pattern.compile("\"material\":\"([^\"]+)\"")
                .matcher(SECRET_BODY.get()).results().findFirst()
                .map(match -> match.group(1)).orElseThrow();
    }

    @Test
    @DisplayName("§7's per-case clock override: assertions resolve against the case's "
            + "frozen instant, not the run's — malformed values fail the case, not the run")
    void perCaseClockOverridesRunStart() {
        TestSuiteDefinition suite = new TestSuiteDefinition("clocks", null, List.of(
                // no override: the run-start clock — a far-future date must NOT hold
                new TestSuiteDefinition.TestCase("run start default", List.of(), List.of(),
                        List.of("today() != date('2030-01-01')"), null),
                // the override: every time function resolves against the case's instant
                new TestSuiteDefinition.TestCase("advanced", List.of(), List.of(),
                        List.of("today() == date('2030-01-01')",
                                "now() >= datetime('2030-01-01T00:00:00Z')"),
                        "2030-01-01T00:00:00Z"),
                // a malformed override is a case failure with guidance, never a crash
                new TestSuiteDefinition.TestCase("malformed", List.of(), List.of(),
                        List.of("true"), "not-an-instant")));
        AppDefinition candidate = new AppDefinition(null, "ClockApp", null, null, null,
                null, null, null, null, null, null, null, null);
        Map<String, Object> artifact = runner.run(candidate, suite, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cases = (List<Map<String, Object>>) artifact.get("cases");
        assertEquals("run start default", cases.get(0).get("name"));
        assertEquals(Boolean.TRUE, cases.get(0).get("passed"), () -> String.valueOf(cases.get(0)));
        assertEquals(Boolean.TRUE, cases.get(1).get("passed"), () -> String.valueOf(cases.get(1)));
        assertEquals("2030-01-01T00:00:00Z", cases.get(1).get("clock"));
        assertEquals(Boolean.FALSE, cases.get(2).get("passed"));
        assertTrue(String.valueOf(cases.get(2).get("failures")).contains("ISO-8601"),
                () -> String.valueOf(cases.get(2)));
        assertEquals(Boolean.FALSE, artifact.get("green"));
    }
}
