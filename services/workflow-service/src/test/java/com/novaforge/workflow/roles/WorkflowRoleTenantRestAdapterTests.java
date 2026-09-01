package com.novaforge.workflow.roles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.security.ServiceTokenClient;
import com.novaforge.workflow.tenants.RestTenantLookup;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The authorization lookups behind approvals and SLA escalation had ZERO tests
 * (twenty-ninth pass coverage audit). RestRoleLookup: approver holders resolve
 * per tenant, and an UNREACHABLE runtime answers NULL — the caller treats an
 * unknown answer as "held" (the documented breach-path posture: a lookup outage
 * must not wedge approvals). RestTenantLookup: the tenant's apiName is cached
 * for 30 s (an outage fails CLOSED — unknown tenants are never scratch).
 */
class WorkflowRoleTenantRestAdapterTests {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID USER = UUID.fromString("22222222-2222-4222-8222-222222222222");

    static volatile int status = 200;
    static volatile String holdersBody = "[\"" + USER + "\"]";
    static volatile String tenantBody = "{\"apiName\": \"erp\"}";
    static final AtomicInteger tenantLookups = new AtomicInteger();

    private static HttpServer runtime;

    @BeforeAll
    static void stubRuntime() throws Exception {
        runtime = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        runtime.createContext("/api/v1/admin/tenants", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/users")) {
                respond(exchange, holdersBody);          // roles/{role}/users
            } else if (path.endsWith("/roles")) {
                respond(exchange, "[\"clerk\"]");          // users/{id}/roles
            } else {
                tenantLookups.incrementAndGet();
                respond(exchange, tenantBody);           // tenants/{id}
            }
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

    private static ServiceTokenClient serviceToken() {
        ServiceTokenClient token = mock(ServiceTokenClient.class);
        when(token.token()).thenReturn("svc-token-1");
        return token;
    }

    private String base() {
        return "http://127.0.0.1:" + runtime.getAddress().getPort();
    }

    @Test
    @DisplayName("approver holders resolve with the URL-encoded role and the service token")
    void holdersResolve() {
        RestRoleLookup lookup = new RestRoleLookup(base(), serviceToken());
        List<UUID> holders = lookup.holdersOf(TENANT, "erp clerk");

        assertThat(holders).containsExactly(USER);
    }

    @Test
    @DisplayName("a lookup outage answers NULL — unknown is treated as held, never wedges the breach path")
    void outageAnswersNull() {
        status = 500;
        try {
            RestRoleLookup lookup = new RestRoleLookup(base(), serviceToken());
            assertThat(lookup.holdersOf(TENANT, "clerk")).isNull();
        } finally {
            status = 200;
        }
    }

    @Test
    @DisplayName("an actor's own roles lookup fails audibly on outage")
    void actorRolesFailAudibly() {
        status = 500;
        try {
            RestRoleLookup lookup = new RestRoleLookup(base(), serviceToken());
            assertThatThrownBy(() -> lookup.of(TENANT, USER))
                    .isInstanceOf(PlatformException.class)
                    .satisfies(e -> assertThat(((PlatformException) e).errorCode())
                            .isEqualTo(PlatformErrorCode.INTERNAL));
        } finally {
            status = 200;
        }
    }

    @Test
    @DisplayName("the tenant apiName caches for the TTL window — one lookup, two calls")
    void tenantLookupCaches() {
        tenantLookups.set(0);
        RestTenantLookup lookup = new RestTenantLookup(base(), serviceToken());
        assertThat(lookup.apiNameOf(TENANT)).isEqualTo("erp");
        assertThat(lookup.apiNameOf(TENANT)).isEqualTo("erp");
        assertThat(tenantLookups.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a tenant lookup outage fails CLOSED — never read as scratch")
    void tenantOutageFailsClosed() {
        status = 500;
        try {
            RestTenantLookup lookup = new RestTenantLookup(base(), serviceToken());
            assertThatThrownBy(() -> lookup.apiNameOf(TENANT))
                    .isInstanceOf(PlatformException.class)
                    .satisfies(e -> assertThat(((PlatformException) e).errorCode())
                            .isEqualTo(PlatformErrorCode.INTERNAL));
        } finally {
            status = 200;
        }
    }
}
