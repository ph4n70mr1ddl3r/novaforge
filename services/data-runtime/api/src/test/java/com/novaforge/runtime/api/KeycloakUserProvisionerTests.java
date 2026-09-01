package com.novaforge.runtime.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Keycloak Admin API provisioning contract (twenty-ninth pass coverage
 * audit): the platform's synthetic actors are created IDEMPOTENTLY by username
 * — the returned id is the same id the platform DB keys on — with the tenant
 * pinned as the {@code tenant_id} user attribute (the token-claim source),
 * platform roles as the multivalued {@code platform_roles} attribute, and
 * Keycloak 26's Verify Profile satisfied so provisioned actors are grantable
 * immediately. A supplied password is a NON-temporary credential.
 */
class KeycloakUserProvisionerTests {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID USER_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

    static final AtomicBoolean userExists = new AtomicBoolean(false);
    static final AtomicBoolean convergeOnPost = new AtomicBoolean(true);
    static final AtomicInteger createCalls = new AtomicInteger();
    static final AtomicInteger passwordCalls = new AtomicInteger();
    static final AtomicReference<String> lastCreateBody = new AtomicReference<>();
    static final AtomicReference<String> lastPasswordBody = new AtomicReference<>();
    static final ConcurrentLinkedQueue<String> paths = new ConcurrentLinkedQueue<>();

    private static HttpServer keycloak;

    @BeforeAll
    static void stubKeycloak() throws Exception {
        keycloak = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        keycloak.createContext("/realms/novaforge/protocol/openid-connect/token", exchange -> {
            respond(exchange, "{\"access_token\": \"kc-token-1\", \"expires_in\": 300}");
        });
        keycloak.createContext("/admin/realms/novaforge/users", exchange -> {
            paths.add(exchange.getRequestURI().getPath()
                    + (exchange.getRequestURI().getQuery() == null ? ""
                    : "?" + exchange.getRequestURI().getQuery()));
            switch (exchange.getRequestMethod()) {
                case "GET" -> respond(exchange, userExists.get()
                        ? "[{\"id\": \"" + USER_ID + "\", \"username\": \"demo\"}]"
                        : "[]");
                case "POST" -> {
                    createCalls.incrementAndGet();
                    lastCreateBody.set(new String(exchange.getRequestBody().readAllBytes(),
                            StandardCharsets.UTF_8));
                    if (convergeOnPost.get()) {
                        userExists.set(true);
                    }
                    respond(exchange, "");
                }
                default -> respond(exchange, "");
            }
        });
        keycloak.createContext("/admin/realms/novaforge/users/", exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/reset-password")) {
                passwordCalls.incrementAndGet();
                lastPasswordBody.set(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8));
            }
            respond(exchange, "");
        });
        keycloak.start();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, Math.max(0, bytes.length));
        try (OutputStream out = exchange.getResponseBody()) {
            if (bytes.length > 0) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    @AfterAll
    static void stop() {
        keycloak.stop(0);
    }

    private KeycloakUserProvisioner provisioner() {
        String root = "http://127.0.0.1:" + keycloak.getAddress().getPort();
        return new KeycloakUserProvisioner(root + "/realms/novaforge",
                "novaforge-runtime", "secret-1");
    }

    @Test
    @DisplayName("createUser provisions with tenant_id + platform_roles attributes and Verify-Profile fields")
    void provisionsTenantAttributeUser() {
        userExists.set(false);
        UUID id = provisioner().createUser("demo", "demo@x.dev", TENANT,
                "pw-1", List.of("admin", "user"));

        assertThat(id).isEqualTo(USER_ID);
        String body = lastCreateBody.get();
        assertThat(body)
                .contains("\"username\":\"demo\"")
                .contains("\"attributes\":{")
                .contains("\"tenant_id\":[\"" + TENANT + "\"]")
                .contains("\"platform_roles\":[\"admin\",\"user\"]")
                .contains("\"requiredActions\":[]")
                .contains("\"firstName\":\"Scratch\"");
        // the non-temporary credential leg fired with the supplied password
        assertThat(passwordCalls.get()).isEqualTo(1);
        assertThat(lastPasswordBody.get())
                .contains("\"type\":\"password\"")
                .contains("\"value\":\"pw-1\"")
                .contains("\"temporary\":false");
    }

    @Test
    @DisplayName("createUser is idempotent by username: an existing actor returns its id, no create")
    void idempotentByUsername() {
        userExists.set(true);
        createCalls.set(0);
        passwordCalls.set(0);
        UUID id = provisioner().createUser("demo", null, TENANT, null);

        assertThat(id).isEqualTo(USER_ID);
        assertThat(createCalls.get()).isZero();
        assertThat(passwordCalls.get()).isZero();
    }

    @Test
    @DisplayName("a create that does not converge (find still empty) fails INTERNAL — not a silent null")
    void nonConvergenceFails() {
        userExists.set(false);
        convergeOnPost.set(false);
        assertThatThrownBy(() -> provisioner().createUser("ghost", null, TENANT, null))
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> assertThat(((PlatformException) e).errorCode())
                        .isEqualTo(PlatformErrorCode.INTERNAL));
    }
}
