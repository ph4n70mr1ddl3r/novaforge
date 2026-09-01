package com.novaforge.notification.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

/**
 * The notification fan-out's runtime ports had ZERO tests (twenty-ninth pass
 * coverage audit). Pinned here: role holders resolve per tenant with the shared
 * service client; the template-token record fetch is BEST-EFFORT by design —
 * a gone record, a process-keyed (non-app) entity, or an unreachable runtime all
 * render empty tokens and the fan-out still delivers; the task.* event stays the
 * assertable surface.
 */
class RestRuntimePortsTests {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID USER = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID RECORD = UUID.fromString("33333333-3333-4333-8333-333333333333");

    static volatile int status = 200;
    static volatile String usersBody = "[\"" + USER + "\"]";
    static volatile String rolesBody = "[\"clerk\",\"auditor\"]";
    static volatile String recordBody = "{\"name\": \"Acme\", \"total\": \"10.00\"}";
    static final AtomicReference<String> lastAuth = new AtomicReference<>();
    static final AtomicReference<String> lastPath = new AtomicReference<>();

    private static HttpServer runtime;

    @BeforeAll
    static void stubRuntime() throws Exception {
        runtime = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        runtime.createContext("/api/v1/admin/", exchange -> {
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastPath.set(exchange.getRequestURI().getPath());
            respond(exchange, exchange.getRequestURI().getPath().endsWith("/users")
                    ? usersBody : rolesBody);
        });
        runtime.createContext("/api/v1/hooks/records/", exchange -> {
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastPath.set(exchange.getRequestURI().getPath() + "?" + exchange.getRequestURI().getQuery());
            respond(exchange, recordBody);
        });
        runtime.start();
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
        runtime.stop(0);
    }

    @org.junit.jupiter.api.BeforeEach
    void reset() {
        lastPath.set(null);
        lastAuth.set(null);
    }

    private static ServiceTokenClient serviceToken() {
        ServiceTokenClient token = mock(ServiceTokenClient.class);
        when(token.token()).thenReturn("svc-token-1");
        return token;
    }

    private String base() {
        return "http://127.0.0.1:" + runtime.getAddress().getPort();
    }

    @Test
    @DisplayName("role holders resolve per tenant with the service client's token")
    void usersOfRole() {
        RestRuntimeAdminPort port = new RestRuntimeAdminPort(base(), serviceToken());
        List<UUID> users = port.usersOfRole(TENANT, "clerk");

        assertThat(lastAuth.get()).isEqualTo("Bearer svc-token-1");
        assertThat(lastPath.get()).isEqualTo("/api/v1/admin/tenants/" + TENANT + "/roles/clerk/users");
        assertThat(users).containsExactly(USER);
    }

    @Test
    @DisplayName("membership answers from the tenant-scoped roles surface")
    void rolesOfUser() {
        RestRuntimeAdminPort port = new RestRuntimeAdminPort(base(), serviceToken());
        List<String> roles = port.rolesOfUser(TENANT, USER);

        assertThat(lastPath.get()).isEqualTo("/api/v1/admin/tenants/" + TENANT + "/users/" + USER + "/roles");
        assertThat(roles).containsExactly("clerk", "auditor");
    }

    @Test
    @DisplayName("the template-token fetch hits the internal record read with the app-qualified entity split")
    void recordFetchSplitsEntityKey() {
        RestRuntimeRecordPort port = new RestRuntimeRecordPort(base(), serviceToken());
        Map<String, Object> record = port.recordOf(TENANT, "erp.invoice", RECORD);

        assertThat(lastPath.get()).isEqualTo("/api/v1/hooks/records/" + RECORD
                + "?tenantId=" + TENANT + "&app=erp&entity=invoice");
        assertThat(record).containsEntry("name", "Acme");
    }

    @Test
    @DisplayName("a process-keyed entity (no app dot) short-circuits to empty tokens — no call")
    void processKeyedEntitySkips() {
        RestRuntimeRecordPort port = new RestRuntimeRecordPort(base(), serviceToken());
        Map<String, Object> record = port.recordOf(TENANT, "leave-process", RECORD);

        assertThat(record).isEmpty();
        assertThat(lastPath.get()).isNull();
    }

    @Test
    @DisplayName("a gone record or dead runtime renders empty tokens — the fan-out still delivers")
    void unreachableRendersEmptyTokens() {
        status = 404;
        try {
            RestRuntimeRecordPort port = new RestRuntimeRecordPort(base(), serviceToken());
            assertThat(port.recordOf(TENANT, "erp.invoice", RECORD)).isEmpty();
        } finally {
            status = 200;
        }
        RestRuntimeRecordPort dead = new RestRuntimeRecordPort("http://127.0.0.1:1",
                serviceToken());
        assertThat(dead.recordOf(TENANT, "erp.invoice", RECORD)).isEmpty();
    }
}
