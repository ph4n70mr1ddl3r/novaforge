package com.novaforge.reporting.export;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.security.ServiceTokenClient;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The async handoff client's user-facing failure shape (the 2026-08-31 hunt): an
 * upstream rejection's BODY is the Integration Service's to disclose — connector
 * configs, internal ids, and stack detail ride problem payloads — so it stays in
 * the server-side log while the user answers with the status alone (code/status
 * semantics unchanged).
 */
class AsyncExportClientTests {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static HttpServer integration;

    @BeforeAll
    static void stubIntegration() throws Exception {
        integration = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        integration.createContext("/api/v1/integrations/internal/report-exports", exchange -> {
            byte[] body = ("{\"detail\": \"connector cred_stripe leaked-stack-trace "
                    + "jdbc:postgresql://internal:5432/novaforge\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(422, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        integration.start();
    }

    @AfterAll
    static void stopIntegration() {
        integration.stop(0);
    }

    @Test
    @DisplayName("an upstream rejection names its status to the user — never the upstream body")
    void upstreamBodyNeverReachesTheUser() {
        ServiceTokenClient serviceToken = mock(ServiceTokenClient.class);
        lenient().when(serviceToken.token()).thenReturn("test-token");
        AsyncExportClient client = new AsyncExportClient(
                "http://127.0.0.1:" + integration.getAddress().getPort(), serviceToken);

        assertThatThrownBy(() -> client.create(TENANT, "Erp", "arAging", "csv", null, UUID.randomUUID()))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("HTTP 422")           // the semantics ride on
                .hasMessageNotContaining("cred_stripe")     // the body never does
                .hasMessageNotContaining("internal:5432")
                .hasMessageNotContaining("leaked-stack-trace")
                .extracting(e -> ((PlatformException) e).errorCode())
                .isEqualTo(PlatformErrorCode.INTERNAL);
    }
}
