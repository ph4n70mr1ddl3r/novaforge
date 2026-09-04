package com.novaforge.runtime.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
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

/**
 * The connector-executor binding's contract (twenty-ninth pass coverage audit):
 * {@code callConnector} steps POST the integration service's internal execute
 * surface with the shared service client — tenant, app, connector, operation,
 * template and (when present) the idempotency dedupe key — and map the
 * executor's problem+json back onto the hook failure policy. Zero execution
 * anywhere before this suite: journey tests stub the port interface.
 */
class RestConnectorPortTests {

    private static final String TENANT = "11111111-1111-4111-8111-111111111111";

    static volatile int status = 200;
    static volatile String responseBody = "{\"status\": 201, \"body\": {\"ok\": true}}";
    static final AtomicReference<String> lastAuth = new AtomicReference<>();
    static final AtomicReference<String> lastBody = new AtomicReference<>();

    private static HttpServer integration;

    @BeforeAll
    static void stubIntegration() throws Exception {
        integration = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        integration.createContext("/api/v1/integrations/internal/execute", exchange -> {
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
            exchange.close();
        });
        integration.start();
    }

    @AfterAll
    static void stop() {
        integration.stop(0);
    }

    @BeforeEach
    void reset() {
        status = 200;
        responseBody = "{\"status\": 201, \"body\": {\"ok\": true}}";
    }

    private RestConnectorPort client() {
        ServiceTokenClient serviceToken = mock(ServiceTokenClient.class);
        when(serviceToken.token()).thenReturn("svc-token-1");
        return new RestConnectorPort("http://127.0.0.1:" + integration.getAddress().getPort(),
                serviceToken);
    }

    @Test
    @DisplayName("execute posts the connector envelope with the service token and dedupe key")
    void postsTheEnvelope() {
        var result = client().execute(TENANT, "erp", "stripe", "charge",
                Map.of("amount", 100), "dedupe-1");

        assertThat(lastAuth.get()).isEqualTo("Bearer svc-token-1");
        assertThat(lastBody.get())
                .contains("\"tenantId\":\"" + TENANT + "\"")
                .contains("\"app\":\"erp\"")
                .contains("\"connector\":\"stripe\"")
                .contains("\"operation\":\"charge\"")
                .contains("\"dedupeKey\":\"dedupe-1\"");
        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body().toString()).contains("ok");
    }

    @Test
    @DisplayName("no dedupe key → the key is omitted from the envelope")
    void dedupeKeyOmitted() {
        client().execute(TENANT, "erp", "stripe", "charge", null, null);
        assertThat(lastBody.get()).doesNotContain("dedupeKey").contains("\"template\":{}");
    }

    @Test
    @DisplayName("provider money survives the envelope parse decimal-exact — never its float64 shadow")
    void providerNumbersStayExact() {
        // 17+ significant digits of money and a 21-digit provider id — both past the
        // binary float's exact band. A default Map read types the amount Double and
        // the flow's response mapping binds 1.0E16 where the provider charged
        // 9999999999999999.99 (PLAN.md §1 money rule; the same stance ReportRunner's
        // cache read pins for its own JSON re-parse).
        responseBody = "{\"status\": 201, \"body\": {\"amount\": 9999999999999999.99, "
                + "\"providerId\": 123456789012345678901}}";
        var result = client().execute(TENANT, "erp", "stripe", "charge", Map.of(), null);

        assertThat(result.body().get("amount"))
                .isInstanceOf(tools.jackson.databind.node.DecimalNode.class);
        assertThat(result.body().get("amount").decimalValue())
                .isEqualByComparingTo(new java.math.BigDecimal("9999999999999999.99"));
        // a JSON integer past 64 bits keeps its full magnitude — never a long read
        assertThat(result.body().get("providerId").decimalValue())
                .isEqualByComparingTo(new java.math.BigDecimal("123456789012345678901"));
    }

    @Test
    @DisplayName("a known executor problem code maps onto the hook failure policy")
    void problemMaps() {
        status = 400;
        responseBody = "{\"code\":\"4000\",\"detail\":\"unknown operation\"}";
        assertThatThrownBy(() -> client().execute(TENANT, "erp", "stripe", "charge",
                Map.of(), null))
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> {
                    var pe = (PlatformException) e;
                    assertThat(pe.errorCode()).isEqualTo(PlatformErrorCode.VALIDATION_FAILED);
                    assertThat(pe.getMessage()).contains("connector stripe.charge: unknown operation");
                });
    }

    @Test
    @DisplayName("an unreachable executor maps to INTERNAL — the hook failure policy decides")
    void unreachableMapsToInternal() {
        ServiceTokenClient serviceToken = mock(ServiceTokenClient.class);
        when(serviceToken.token()).thenReturn("t");
        RestConnectorPort dead = new RestConnectorPort("http://127.0.0.1:1", serviceToken);
        assertThatThrownBy(() -> dead.execute(TENANT, "erp", "stripe", "charge", Map.of(), null))
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> assertThat(((PlatformException) e).errorCode())
                        .isEqualTo(PlatformErrorCode.INTERNAL));
    }
}
