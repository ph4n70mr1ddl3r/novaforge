package com.novaforge.reporting.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.security.ServiceTokenClient;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The scheduled-report fan-out's own contract had ZERO tests (twenty-ninth pass
 * coverage audit): the rendered export travels to the Notification Service with
 * the report-delivery category, the delivery id rides as the dedup key, the
 * attachment is inline base64 with its filename/content type derived from the
 * format, and a remote problem body surfaces as INTERNAL with the detail —
 * the scheduler's run history must record deliveries audibly.
 */
class DeliveryClientTests {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");

    static volatile int status = 200;
    static volatile String responseBody = "{}";
    static final AtomicReference<String> lastAuth = new AtomicReference<>();
    static final AtomicReference<String> lastBody = new AtomicReference<>();

    private static HttpServer notifications;

    @BeforeAll
    static void stubNotifications() throws Exception {
        notifications = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        notifications.createContext("/api/v1/notifications/internal/send", exchange -> {
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
            exchange.close();
        });
        notifications.start();
    }

    @AfterAll
    static void stop() {
        notifications.stop(0);
    }

    @BeforeEach
    void reset() {
        status = 200;
        responseBody = "{}";
    }

    private DeliveryClient client() {
        ServiceTokenClient serviceToken = Mockito.mock(ServiceTokenClient.class);
        Mockito.when(serviceToken.token()).thenReturn("svc-token-1");
        return new DeliveryClient("http://127.0.0.1:" + notifications.getAddress().getPort(),
                serviceToken);
    }

    @Test
    @DisplayName("deliver posts the report-delivery envelope: dedup key, recipients, inline attachment")
    void deliversTheEnvelope() {
        byte[] attachment = "id,total\n1,10.00\n".getBytes(StandardCharsets.UTF_8);
        Map<String, Object> summary = client().deliver(TENANT, "r1", "erp",
                List.of("erp.clerk"), List.of("u-1"), "csv", attachment, "window-42");

        assertThat(lastAuth.get()).isEqualTo("Bearer svc-token-1");
        String body = lastBody.get();
        assertThat(body)
                .contains("\"category\":\"report-delivery\"")
                .contains("\"deliveryId\":\"window-42\"")
                .contains("\"roles\":[\"erp.clerk\"]")
                .contains("\"users\":[\"u-1\"]")
                .contains("\"filename\":\"r1.csv\"")
                .contains("\"contentType\":\"text/csv\"");
        String expectedBase64 = Base64.getEncoder().encodeToString(attachment);
        assertThat(body).contains("\"contentBase64\":\"" + expectedBase64 + "\"");
        // the notification service's summary returns untouched
        assertThat(summary).isEqualTo(Map.of());
    }

    @Test
    @DisplayName("xlsx attachments carry the spreadsheet content type; a blank delivery id is omitted")
    void xlsxAttachmentAndOptionalDeliveryId() {
        client().deliver(TENANT, "r1", "erp", null, null, "xlsx", new byte[]{1, 2}, "  ");
        String body = lastBody.get();
        assertThat(body)
                .contains("\"contentType\":\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\"")
                .contains("\"filename\":\"r1.xlsx\"")
                .doesNotContain("deliveryId");
        assertThat(body).contains("\"roles\":[],\"users\":[]");
    }

    @Test
    @DisplayName("a remote failure surfaces as INTERNAL with the problem detail — audible in the run history")
    void remoteFailureSurfaces() {
        status = 502;
        responseBody = "{\"code\":\"5000\",\"detail\":\"notification store down\"}";
        assertThatThrownBy(() -> client().deliver(TENANT, "r1", "erp",
                List.of("erp.clerk"), null, "csv", new byte[]{1}, "w-1"))
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> {
                    var pe = (PlatformException) e;
                    assertThat(pe.errorCode()).isEqualTo(PlatformErrorCode.INTERNAL);
                    assertThat(pe.getMessage()).contains("notification store down");
                });
    }
}
