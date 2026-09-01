package com.novaforge.script.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.novaforge.common.context.TenantContext;
import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.security.ServiceTokenClient;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The sandbox's two egress bindings had ZERO tests (twenty-ninth pass coverage
 * audit). DataRuntimeQueryClient: {@code $data.query} relays the CALLING user's
 * token — authorization stays the runtime's single data path, and no caller
 * token refuses rather than escalating (ADR-003 #2); the scheduled leg is the
 * separate explicit system-principal surface. IntegrationHttpProxy: {@code $http}
 * is the sandbox's ONLY egress — the executor's internal surface, never a raw
 * socket; a refused call names the connector and operation.
 */
class ScriptEngineEgressAdapterTests {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");

    static volatile int status = 200;
    static volatile String responseBody = "{\"rows\": [1], \"total\": 1}";
    static final AtomicReference<String> lastAuth = new AtomicReference<>();
    static final AtomicReference<String> lastPath = new AtomicReference<>();
    static final AtomicReference<String> lastBody = new AtomicReference<>();

    private static HttpServer stub;

    @BeforeAll
    static void stubUpstreams() throws Exception {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/api/v1/runtime/", exchange -> {
            observe(exchange);
            respond(exchange);
        });
        stub.createContext("/api/v1/hooks/records/query", exchange -> {
            observe(exchange);
            respond(exchange);
        });
        stub.createContext("/api/v1/integrations/internal/execute", exchange -> {
            observe(exchange);
            respond(exchange);
        });
        stub.start();
    }

    private static void observe(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
        String query = exchange.getRequestURI().getQuery();
        lastPath.set(exchange.getRequestURI().getPath() + (query == null ? "" : "?" + query));
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
        stub.stop(0);
    }

    private static ServiceTokenClient serviceToken() {
        ServiceTokenClient token = mock(ServiceTokenClient.class);
        when(token.token()).thenReturn("svc-token-1");
        return token;
    }

    private void callerToken(String bearer) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", bearer);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private String base() {
        return "http://127.0.0.1:" + stub.getAddress().getPort();
    }

    private TenantContext.Context caller() {
        return new TenantContext.Context(TENANT.toString(),
                "22222222-2222-4222-8222-222222222222");
    }

    @Test
    @DisplayName("$data.query relays the CALLING user's token and encodes the query DSL")
    void queryRelaysCaller() {
        callerToken("Bearer caller-token-7");
        DataRuntimeQueryClient client = new DataRuntimeQueryClient(base(), serviceToken());
        Object result = client.query(caller(), "erp.invoice",
                "{\"filter\": {\"total >\": 10}, \"page\": {\"size\": 25}}");

        assertThat(lastAuth.get()).isEqualTo("Bearer caller-token-7");
        assertThat(lastPath.get()).startsWith("/api/v1/runtime/erp.invoice?");
        assertThat(result).isEqualTo(java.util.Map.of("rows", java.util.List.of(1), "total", 1));
    }

    @Test
    @DisplayName("no caller token → refuse, never escalate to the service account")
    void noCallerTokenRefuses() {
        RequestContextHolder.resetRequestAttributes();
        DataRuntimeQueryClient client = new DataRuntimeQueryClient(base(), serviceToken());
        assertThatThrownBy(() -> client.query(caller(), "erp.invoice", "{}"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("no caller token bound");
        assertThat(lastAuth.get()).isNull();
    }

    @Test
    @DisplayName("the scheduled system query rides the service client on the internal surface")
    void systemQueryUsesServicePrincipal() {
        DataRuntimeQueryClient client = new DataRuntimeQueryClient(base(), serviceToken());
        client.systemQuery(caller(), "erp", "invoice", "{\"filter\": {}}");

        assertThat(lastPath.get()).isEqualTo("/api/v1/hooks/records/query");
        assertThat(lastAuth.get()).isEqualTo("Bearer svc-token-1");
        assertThat(lastBody.get())
                .contains("\"tenantId\":\"" + TENANT + "\"")
                .contains("\"app\":\"erp\"")
                .contains("\"entityApiName\":\"invoice\"");
    }

    @Test
    @DisplayName("$http egress posts the executor's internal surface and names the connector on failure")
    void httpProxyEgress() {
        IntegrationHttpProxy proxy = new IntegrationHttpProxy(base(), serviceToken());
        Object result = proxy.call(caller(), "erp", "stripe", "charge", Map.of("a", 1));

        assertThat(lastAuth.get()).isEqualTo("Bearer svc-token-1");
        assertThat(lastPath.get()).isEqualTo("/api/v1/integrations/internal/execute");
        assertThat(lastBody.get())
                .contains("\"connector\":\"stripe\"")
                .contains("\"operation\":\"charge\"");

        status = 403;
        try {
            assertThatThrownBy(() -> proxy.call(caller(), "erp", "stripe", "charge", Map.of()))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("stripe.charge")
                    .hasMessageContaining("HTTP 403");
        } finally {
            status = 200;
        }
    }
}
