package com.novaforge.scheduler.jobs;

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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scheduler's outbound bindings had ZERO tests (twenty-ninth pass coverage
 * audit). RestFlowTarget: the fired job POSTs the runtime's internal scheduled-hook
 * surface with the shared service client (the synthetic recordless trigger —
 * §7's pinned execution mode). RestPublishedJobsSource: the published index fans
 * out to per-app bundles, each parsed for its jobs — a broken app surface fails
 * the whole sync audibly (INTERNAL), it never silently drops a schedule.
 */
class SchedulerRestAdapterTests {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID APP_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");

    static volatile int status = 200;
    static volatile String indexBody = "[{\"tenantId\": \"" + TENANT + "\", \"appId\": \""
            + APP_ID + "\", \"apiName\": \"erp\", \"version\": 1}]";
    static volatile String bundleBody = "{\"version\": 1, \"app\": {\"apiName\": \"erp\","
            + " \"jobs\": [{\"name\": \"nightly\", \"cron\": \"0 3 * * *\","
            + " \"target\": \"flow\"}]}}";
    static final AtomicReference<String> lastAuth = new AtomicReference<>();
    static final AtomicReference<String> lastBody = new AtomicReference<>();

    private static HttpServer stub;

    @BeforeAll
    static void stubUpstreams() throws Exception {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/api/v1/hooks/scheduled", exchange -> {
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            respond(exchange, "");
        });
        stub.createContext("/api/v1/metadata/published-apps", exchange -> {
            respond(exchange, indexBody);
        });
        stub.createContext("/api/v1/metadata/apps/", exchange -> {
            respond(exchange, bundleBody);
        });
        stub.start();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, Math.max(0, bytes.length));
        try (OutputStream out = exchange.getResponseBody()) {
            if (bytes.length > 0) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    @AfterAll
    static void stop() {
        stub.stop(0);
    }

    @BeforeEach
    void reset() {
        status = 200;
    }

    private static ServiceTokenClient serviceToken() {
        ServiceTokenClient token = mock(ServiceTokenClient.class);
        when(token.token()).thenReturn("svc-token-1");
        return token;
    }

    private String base() {
        return "http://127.0.0.1:" + stub.getAddress().getPort();
    }

    @Test
    @DisplayName("a fired flow job POSTs the recordless scheduled-hook surface with the service client")
    void flowTargetPosts() {
        RestFlowTarget target = new RestFlowTarget(base(), serviceToken());
        target.run(TENANT, "erp", "invoice", "age");

        assertThat(lastAuth.get()).isEqualTo("Bearer svc-token-1");
        assertThat(lastBody.get())
                .contains("\"tenantId\":\"" + TENANT + "\"")
                .contains("\"app\":\"erp\"")
                .contains("\"entityApiName\":\"invoice\"")
                .contains("\"hook\":\"age\"");
    }

    @Test
    @DisplayName("a failed firing surfaces as INTERNAL — the run history records it audibly")
    void flowFailureSurfaces() {
        status = 500;
        RestFlowTarget target = new RestFlowTarget(base(), serviceToken());
        assertThatThrownBy(() -> target.run(TENANT, "erp", "invoice", "age"))
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> assertThat(((PlatformException) e).errorCode())
                        .isEqualTo(PlatformErrorCode.INTERNAL));
    }

    @Test
    @DisplayName("the jobs sync fans the index out to per-app bundles and parses their jobs")
    void jobsSyncParses() {
        RestPublishedJobsSource source = new RestPublishedJobsSource(base(), serviceToken());
        List<PublishedJobsSource.AppJobs> all = source.all();

        assertThat(all).hasSize(1);
        assertThat(all.get(0).tenantId()).isEqualTo(TENANT);
        assertThat(all.get(0).appApiName()).isEqualTo("erp");
        assertThat(all.get(0).jobs()).hasSize(1);
        assertThat(all.get(0).jobs().get(0).name()).isEqualTo("nightly");
    }

    @Test
    @DisplayName("a broken upstream fails the sync audibly — schedules never silently vanish")
    void syncFailureSurfaces() {
        status = 500;
        RestPublishedJobsSource source = new RestPublishedJobsSource(base(), serviceToken());
        assertThatThrownBy(source::all)
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> assertThat(((PlatformException) e).errorCode())
                        .isEqualTo(PlatformErrorCode.INTERNAL));
    }
}
