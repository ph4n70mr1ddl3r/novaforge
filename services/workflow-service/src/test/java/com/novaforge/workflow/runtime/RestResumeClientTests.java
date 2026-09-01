package com.novaforge.workflow.runtime;

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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The approval-resume leg's outbound contract (twenty-ninth pass coverage
 * audit): the completed approval POSTs the runtime's internal resume surface
 * with the shared service client — tenant, record, hook, the after-step, the
 * on-reject flow as JSON, the verdict, and the instance id (the dedupe key).
 * Failures surface audibly so the resume call can retry.
 */
class RestResumeClientTests {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID RECORD = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID INSTANCE = UUID.fromString("44444444-4444-4444-8444-444444444444");

    static volatile int status = 204;
    static final AtomicReference<String> lastAuth = new AtomicReference<>();
    static final AtomicReference<String> lastBody = new AtomicReference<>();

    private static HttpServer runtime;

    @BeforeAll
    static void stubRuntime() throws Exception {
        runtime = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        runtime.createContext("/api/v1/hooks/resume", exchange -> {
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            byte[] bytes = new byte[0];
            exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
            exchange.close();
        });
        runtime.start();
    }

    @AfterAll
    static void stop() {
        runtime.stop(0);
    }

    private RestResumeClient client() {
        ServiceTokenClient serviceToken = mock(ServiceTokenClient.class);
        when(serviceToken.token()).thenReturn("svc-token-1");
        return new RestResumeClient("http://127.0.0.1:" + runtime.getAddress().getPort(),
                serviceToken);
    }

    private ResumeClient.Resume resume() {
        return new ResumeClient.Resume(TENANT, "erp", "invoice", RECORD, "approve",
                "step-2", "{\"notify\": true}", true, INSTANCE);
    }

    @Test
    @DisplayName("resume POSTs the verdict envelope with the instance dedupe key")
    void postsTheResume() {
        client().resume(resume());

        assertThat(lastAuth.get()).isEqualTo("Bearer svc-token-1");
        assertThat(lastBody.get())
                .contains("\"tenantId\":\"" + TENANT + "\"")
                .contains("\"app\":\"erp\"")
                .contains("\"recordId\":\"" + RECORD + "\"")
                .contains("\"hook\":\"approve\"")
                .contains("\"afterStep\":\"step-2\"")
                .contains("\"onReject\":\"{\\\"notify\\\": true}\"")
                .contains("\"approved\":true")
                .contains("\"instanceId\":\"" + INSTANCE + "\"");
    }

    @Test
    @DisplayName("a failed resume surfaces audibly — the caller retries the spine leg")
    void failureSurfaces() {
        status = 500;
        try {
            assertThatThrownBy(() -> client().resume(resume()))
                    .isInstanceOf(PlatformException.class)
                    .satisfies(e -> assertThat(((PlatformException) e).errorCode())
                            .isEqualTo(PlatformErrorCode.INTERNAL));
        } finally {
            status = 204;
        }
    }
}
