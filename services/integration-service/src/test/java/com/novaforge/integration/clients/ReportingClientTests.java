package com.novaforge.integration.clients;

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
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The job-export leg's reporting binding had ZERO tests (twenty-ninth pass
 * coverage audit). Pinned: the actor-scoped vs role-scoped split rides the body
 * (runAsActor XOR runAsRole), the export returns DECODED bytes from the base64
 * content envelope, a content-less answer fails audibly, and a rejected export
 * carries the reporting service's HTTP status — the job history must say why.
 */
class ReportingClientTests {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ACTOR = UUID.fromString("22222222-2222-4222-8222-222222222222");

    static volatile int status = 200;
    static volatile String responseBody = "{\"contentBase64\": \""
            + Base64.getEncoder().encodeToString("csv-bytes".getBytes(StandardCharsets.UTF_8))
            + "\", \"format\": \"csv\"}";
    static final AtomicReference<String> lastAuth = new AtomicReference<>();
    static final AtomicReference<String> lastBody = new AtomicReference<>();

    private static HttpServer reporting;

    @BeforeAll
    static void stubReporting() throws Exception {
        reporting = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        reporting.createContext("/api/v1/reports/internal/export", exchange -> {
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
        reporting.start();
    }

    @AfterAll
    static void stop() {
        reporting.stop(0);
    }

    @BeforeEach
    void reset() {
        status = 200;
        responseBody = "{\"contentBase64\": \""
                + Base64.getEncoder().encodeToString("csv-bytes".getBytes(StandardCharsets.UTF_8))
                + "\", \"format\": \"csv\"}";
    }

    private ReportingClient client() {
        ServiceTokenClient serviceToken = mock(ServiceTokenClient.class);
        when(serviceToken.token()).thenReturn("svc-token-1");
        return new ReportingClient("http://127.0.0.1:" + reporting.getAddress().getPort(),
                serviceToken);
    }

    @Test
    @DisplayName("an actor-scoped export carries runAsActor and returns decoded bytes")
    void actorScopedExport() {
        byte[] bytes = client().export(TENANT, "erp", "r1", null, ACTOR, "csv", Map.of());

        assertThat(lastAuth.get()).isEqualTo("Bearer svc-token-1");
        assertThat(lastBody.get())
                .contains("\"runAsActor\":\"" + ACTOR + "\"")
                .doesNotContain("runAsRole")
                .contains("\"format\":\"csv\"");
        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("csv-bytes");
    }

    @Test
    @DisplayName("a role-scoped export carries runAsRole and never an actor")
    void roleScopedExport() {
        client().export(TENANT, "erp", "r1", "auditor", null, "xlsx", null);

        assertThat(lastBody.get())
                .contains("\"runAsRole\":\"auditor\"")
                .doesNotContain("runAsActor")
                .contains("\"params\":{}");
    }

    @Test
    @DisplayName("a content-less export answer fails audibly — the job history records why")
    void contentlessFails() {
        responseBody = "{}";
        assertThatThrownBy(() -> client().export(TENANT, "erp", "r1", "auditor", null,
                "csv", Map.of()))
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> assertThat(((PlatformException) e).errorCode())
                        .isEqualTo(PlatformErrorCode.INTERNAL));
    }

    @Test
    @DisplayName("a rejected export carries the reporting service's HTTP status and body")
    void rejectionCarriesStatus() {
        status = 500;
        responseBody = "boom";
        assertThatThrownBy(() -> client().export(TENANT, "erp", "r1", "auditor", null,
                "csv", Map.of()))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("HTTP 500")
                .hasMessageContaining("boom");
    }
}
