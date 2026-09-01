package com.novaforge.runtime.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.ScriptDefinition;
import com.novaforge.security.ServiceTokenClient;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The script-engine binding's own contract (twenty-ninth pass coverage audit):
 * script hooks run CALLER-CONTEXT — the calling user's Authorization header is
 * relayed verbatim, and a hook without a caller token must fail LOUDLY rather
 * than silently escalate to the service account (§13 Q1). The scheduler's
 * recordless firing is the pinned exception: the shared service client on the
 * distinct /scheduled surface, never a fallback. Both legs map engine
 * problem+json back onto the write path.
 */
class RestScriptEngineClientTests {

    static volatile int status = 200;
    static volatile String responseBody = "{\"value\": 7, \"logs\": [\"ran\"]}";
    static final AtomicReference<String> lastAuth = new AtomicReference<>();
    static final AtomicReference<String> lastBody = new AtomicReference<>();
    static final AtomicReference<String> lastPath = new AtomicReference<>();

    private static HttpServer engine;
    private static final ServiceTokenClient SERVICE_TOKEN = serviceToken("svc-token-1");

    @BeforeAll
    static void stubEngine() throws Exception {
        engine = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        engine.createContext("/api/v1/scripts/execute", exchange -> {
            observe(exchange, "/api/v1/scripts/execute");
        });
        engine.createContext("/api/v1/scripts/scheduled", exchange -> {
            observe(exchange, "/api/v1/scripts/scheduled");
        });
        engine.start();
    }

    private static void observe(com.sun.net.httpserver.HttpExchange exchange, String path)
            throws IOException {
        lastPath.set(path);
        lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
        respond(exchange);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }

    @AfterAll
    static void stop() {
        engine.stop(0);
    }

    @BeforeEach
    void reset() {
        status = 200;
        responseBody = "{\"value\": 7, \"logs\": [\"ran\"]}";
        lastAuth.set(null);
        lastBody.set(null);
        lastPath.set(null);
    }

    private static ServiceTokenClient serviceToken(String token) {
        ServiceTokenClient client = mock(ServiceTokenClient.class);
        when(client.token()).thenReturn(token);
        return client;
    }

    private RestScriptEngineClient client() {
        return new RestScriptEngineClient("http://127.0.0.1:" + engine.getAddress().getPort(),
                SERVICE_TOKEN);
    }

    private void callerToken(String bearer) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", bearer);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    @DisplayName("execute relays the CALLING user's token verbatim — never the service account")
    void relaysCallerToken() {
        callerToken("Bearer caller-token-9");
        var outcome = client().execute("erp", 1, "stamp", "beforeSave",
                new ScriptDefinition("js", "return 1;", null), Map.of("k", "v"));

        assertThat(lastAuth.get()).isEqualTo("Bearer caller-token-9");
        assertThat(lastBody.get())
                .contains("\"app\":\"erp\"")
                .contains("\"hook\":\"stamp\"")
                .contains("\"trigger\":\"beforeSave\"")
                .contains("\"script\":\"return 1;\"");
        assertThat(outcome.value()).isEqualTo(7);
        assertThat(outcome.logs()).containsExactly("ran");
    }

    @Test
    @DisplayName("no caller token bound → the hook fails loudly instead of escalating")
    void noCallerTokenFailsLoudly() {
        RequestContextHolder.resetRequestAttributes();
        assertThatThrownBy(() -> client().execute("erp", 1, "stamp", "beforeSave",
                new ScriptDefinition("js", "return 1;", null), Map.of()))
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> assertThat(((PlatformException) e).errorCode())
                        .isEqualTo(PlatformErrorCode.INTERNAL));
        assertThat(lastAuth.get()).isNull();
    }

    @Test
    @DisplayName("the scheduled leg rides the shared service client with the tenant in the body")
    void scheduledUsesServiceClient() {
        var outcome = client().executeScheduled(TENANT, "erp", 3, "roll",
                new ScriptDefinition("js", "return 2;", null));

        assertThat(lastAuth.get()).isEqualTo("Bearer svc-token-1");
        assertThat(lastPath.get()).isEqualTo("/api/v1/scripts/scheduled");
        assertThat(lastBody.get())
                .contains("\"tenantId\":\"" + TENANT + "\"")
                .contains("\"app\":\"erp\"")
                .contains("\"appVersion\":3");
        assertThat(outcome.value()).isEqualTo(7);
    }

    private static final java.util.UUID TENANT =
            java.util.UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test
    @DisplayName("a known engine problem code maps onto the write path")
    void problemMaps() {
        callerToken("Bearer caller-token-9");
        status = 422;
        responseBody = "{\"code\":\"4000\",\"detail\":\"script refused\"}";
        assertThatThrownBy(() -> client().execute("erp", 1, "stamp", "beforeSave",
                new ScriptDefinition("js", "x", null), Map.of()))
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> {
                    var pe = (PlatformException) e;
                    assertThat(pe.errorCode()).isEqualTo(PlatformErrorCode.VALIDATION_FAILED);
                    assertThat(pe.getMessage()).contains("script hook stamp: script refused");
                });
    }
}
