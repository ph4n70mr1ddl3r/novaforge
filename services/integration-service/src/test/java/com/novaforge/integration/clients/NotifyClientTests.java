package com.novaforge.integration.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.novaforge.security.ServiceTokenClient;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The integration service's outbound notification leg had ZERO tests (twenty-ninth
 * pass coverage audit). The contract that matters: the job id doubles as the send's
 * idempotency key (a redelivered job event must collapse, not duplicate the inbox
 * row), and a notification outage NEVER fails the job that triggered it.
 */
class NotifyClientTests {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID JOB = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID USER = UUID.fromString("22222222-2222-4222-8222-222222222222");

    static volatile int status = 200;
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
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
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

    private NotifyClient client() {
        ServiceTokenClient serviceToken = Mockito.mock(ServiceTokenClient.class);
        Mockito.when(serviceToken.token()).thenReturn("svc-token-1");
        return new NotifyClient("http://127.0.0.1:" + notifications.getAddress().getPort(),
                serviceToken);
    }

    @Test
    @DisplayName("job-completed sends the built-in category with the job id as the idempotency key")
    void sendsWithJobIdempotencyKey() {
        client().jobCompleted(TENANT, JOB, USER, "import finished: 12 rows");

        assertThat(lastAuth.get()).isEqualTo("Bearer svc-token-1");
        assertThat(lastBody.get())
                .contains("\"category\":\"job-completed\"")
                .contains("\"deliveryId\":\"job-" + JOB + "\"")
                .contains("\"tenantId\":\"" + TENANT + "\"")
                .contains("\"recipients\":{\"users\":[\"" + USER + "\"]}")
                .contains("import finished: 12 rows");
    }

    @Test
    @DisplayName("a notification outage never fails the job that triggered it")
    void outageNeverFailsTheJob() {
        NotifyClient dead = new NotifyClient("http://127.0.0.1:1",
                Mockito.mock(ServiceTokenClient.class));
        assertThatCode(() -> dead.jobCompleted(TENANT, JOB, USER, "whatever"))
                .doesNotThrowAnyException();

        status = 500;
        try {
            assertThatCode(() -> client().jobCompleted(TENANT, JOB, USER, "whatever"))
                    .doesNotThrowAnyException();
        } finally {
            status = 200;
        }
    }
}
