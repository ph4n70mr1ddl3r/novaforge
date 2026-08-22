package com.novaforge.metadata.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.novaforge.metadata.AppDefinition;
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
 * <p>Four stub servers stand in for auth/runtime/metadata/workflow; the runner's own
 * HTTP behavior — methods, paths, encodings — is asserted by the stubs being strict.
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
        // workflow: the inbox — the list is GET-only (TaskController), resolutions POST
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
            if (path.endsWith("/approve") && "POST".equals(method)) {
                respond(exchange, 200, "{\"id\":\"t-1\",\"type\":\"approval\","
                        + "\"status\":\"APPROVED\",\"assignee\":\"actor-manager\"}");
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

        runner = new TestRunner(
                "http://127.0.0.1:" + runtime.getAddress().getPort(),
                "http://127.0.0.1:" + metadata.getAddress().getPort(),
                "http://127.0.0.1:" + auth.getAddress().getPort(),
                "http://127.0.0.1:" + workflow.getAddress().getPort(),
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
                        new Step("resolveTask", "Task", "manager", "${Task[0].id}",
                                Map.of("action", "reject"), "error(SOD_VIOLATION)")),
                        List.of("${Task[0].status} == 'APPROVED'",
                                "${Query[0].count} == 1",
                                "${Query[1].count} == 1"))));

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
    }
}
