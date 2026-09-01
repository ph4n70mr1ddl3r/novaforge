package com.novaforge.runtime.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.FlowStep;
import com.novaforge.runtime.engine.hook.ApprovalClient;
import com.novaforge.security.ServiceTokenClient;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * RestApprovalClient had ZERO execution anywhere (twenty-ninth pass coverage
 * audit): journey suites stub the port interface, so the adapter's cross-service
 * contract — the URL it POSTs, the bearer token it presents, the payload keys
 * the Workflow Service's internal surface reads, and the problem-body mapping
 * that renders a remote SOD_VIOLATION back onto the write path — was invisible
 * to CI. Pinned here against a stub upstream.
 */
class RestApprovalClientTests {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID RECORD = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID ACTOR = UUID.fromString("22222222-2222-4222-8222-222222222222");

    record Exchanged(String method, String path, String authorization, String body) {
    }

    static Exchanged last;
    static volatile int status = 204;
    static volatile String responseBody = "";
    static final ConcurrentLinkedQueue<Exchanged> CALLS = new ConcurrentLinkedQueue<>();

    private static HttpServer workflow;

    @BeforeAll
    static void stubWorkflow() throws Exception {
        workflow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workflow.createContext("/api/v1/workflow/internal/approvals", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
            last = new Exchanged(exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"), body);
            CALLS.add(last);
            exchange.getResponseHeaders().add("Content-Type",
                    status == 204 ? "application/json" : "application/problem+json");
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
            if (status != 204) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
        workflow.start();
    }

    @AfterAll
    static void stop() {
        workflow.stop(0);
    }

    @BeforeEach
    void reset() {
        last = null;
        status = 204;
        responseBody = "";
    }

    private RestApprovalClient client() {
        ServiceTokenClient serviceToken = Mockito.mock(ServiceTokenClient.class);
        Mockito.when(serviceToken.token()).thenReturn("svc-token-1");
        return new RestApprovalClient("http://127.0.0.1:" + workflow.getAddress().getPort(),
                serviceToken);
    }

    private ApprovalClient.Suspension suspension() {
        return new ApprovalClient.Suspension(TENANT, "erp", "invoice", "INV-1", RECORD,
                "approve", "step-1", "step-2", new FlowStep("s1", "notify", Map.of(), null, null, null, null),
                "approvers", List.of("u-1"), "ANY", "P3D", "manager", ACTOR, "approve");
    }

    @Test
    @DisplayName("requestApproval POSTs the suspension to the workflow internal surface with the service token")
    void postsTheSuspension() {
        client().request(suspension());

        assertThat(last.method()).isEqualTo("POST");
        assertThat(last.path()).isEqualTo("/api/v1/workflow/internal/approvals");
        assertThat(last.authorization()).isEqualTo("Bearer svc-token-1");
        assertThat(last.body())
                .contains("\"tenantId\":\"" + TENANT + "\"")
                .contains("\"app\":\"erp\"")
                .contains("\"entityApiName\":\"invoice\"")
                .contains("\"recordId\":\"" + RECORD + "\"")
                .contains("\"approversRole\":\"approvers\"")
                .contains("\"mode\":\"ANY\"")
                .contains("\"initiatingActor\":\"" + ACTOR + "\"");
    }

    @Test
    @DisplayName("a remote problem body with a known code maps onto the write path (SOD_VIOLATION stays SOD_VIOLATION)")
    void remoteProblemMaps() {
        status = 400;
        responseBody = "{\"code\":\"4011\",\"detail\":\"initiating actor is the only approver\"}";

        assertThatThrownBy(() -> client().request(suspension()))
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> {
                    var pe = (PlatformException) e;
                    assertThat(pe.errorCode()).isEqualTo(PlatformErrorCode.SOD_VIOLATION);
                    assertThat(pe.getMessage()).contains("initiating actor is the only approver");
                });
    }

    @Test
    @DisplayName("a non-problem remote failure maps to INTERNAL with the status named")
    void unknownFailureMapsToInternal() {
        status = 500;
        responseBody = "boom";

        assertThatThrownBy(() -> client().request(suspension()))
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> {
                    var pe = (PlatformException) e;
                    assertThat(pe.errorCode()).isEqualTo(PlatformErrorCode.INTERNAL);
                    assertThat(pe.getMessage()).contains("500");
                });
    }

    @Test
    @DisplayName("an unreachable workflow service maps to INTERNAL — never a raw IOException")
    void unreachableMapsToInternal() {
        RestApprovalClient dead = new RestApprovalClient("http://127.0.0.1:1",
                Mockito.mock(ServiceTokenClient.class));
        assertThatCode(dead::toString).doesNotThrowAnyException();
        assertThatThrownBy(() -> dead.request(suspension()))
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> assertThat(((PlatformException) e).errorCode())
                        .isEqualTo(PlatformErrorCode.INTERNAL));
    }
}
