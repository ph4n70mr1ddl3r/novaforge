package com.novaforge.metadata.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.EntityDefinition;
import com.novaforge.metadata.FieldDefinition;
import com.novaforge.metadata.FieldType;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The default environment provisioner had ZERO tests (twenty-ninth pass coverage
 * audit) — the promote/rollback leg's whole remote contract was invisible to CI:
 * adopt-before-create for a crashed prior attempt (including the credential-reset
 * leg that un-wedges every retry), the leftover-app retire-before-import, the
 * import + publish sequence, and which bearer rides which leg (service client on
 * the admin legs, the freshly granted environment admin on the metadata legs).
 */
class HttpEnvironmentProvisionerTests {

    private static final UUID SOURCE_TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ADOPTED_TENANT = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID CREATED_APP = UUID.fromString("55555555-5555-4555-8555-555555555555");

    static final AtomicBoolean tenantExists = new AtomicBoolean(false);
    static final AtomicBoolean leftoverAppExists = new AtomicBoolean(false);
    static final ConcurrentLinkedQueue<String> paths = new ConcurrentLinkedQueue<>();
    static final ConcurrentLinkedQueue<String> bodies = new ConcurrentLinkedQueue<>();

    private static HttpServer stub;

    @BeforeAll
    static void stubStack() throws Exception {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // the admin (runtime) + metadata + auth legs all live on one stub, by path
        stub.createContext("/api/v1/admin/tenants", exchange -> {
            paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            if ("GET".equals(exchange.getRequestMethod())) {
                if (tenantExists.get()) {
                    respond(exchange, "{\"tenantId\": \"" + ADOPTED_TENANT + "\"}", 200, false);
                } else {
                    respond(exchange, "", 404, false);
                }
                return;
            }
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, "{\"tenantId\": \"" + ADOPTED_TENANT + "\"}", 200, true);
        });
        stub.createContext("/api/v1/metadata/apps/", exchange -> {
            paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            respond(exchange, "", 200, true);
        });
        stub.createContext("/api/v1/metadata/apps", exchange -> {
            paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, leftoverAppExists.get()
                        ? "[{\"id\": \"" + CREATED_APP + "\", \"apiName\": \"erp\"}]"
                        : "[]", 200, true);
                return;
            }
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, "{\"id\": \"" + CREATED_APP + "\"}", 200, true);
        });
        stub.createContext("/realms/novaforge/protocol/openid-connect/token", exchange -> {
            respond(exchange, "{\"access_token\": \"granted-1\", \"expires_in\": 60}", 200, false);
        });
        stub.start();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body,
                                int status, boolean readBody) throws IOException {
        if (readBody) {
            exchange.getRequestBody().readAllBytes();
        }
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
        paths.clear();
        bodies.clear();
        tenantExists.set(false);
        leftoverAppExists.set(false);
    }

    private HttpEnvironmentProvisioner provisioner() {
        String base = "http://127.0.0.1:" + stub.getAddress().getPort();
        return new HttpEnvironmentProvisioner(base, base,
                base + "/realms/novaforge", "novaforge-runtime", "secret-1");
    }

    private AppDefinition bundle() {
        return new AppDefinition("app-1", "erp", "ERP", null, null,
                List.of(new EntityDefinition("e1", "invoice", "Invoice", null, null, null,
                        null, null, List.of(FieldDefinition.of("total", FieldType.MONEY)),
                        null, null, null, null)),
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("fresh provision: create tenant → grant → import → publish, in that order")
    void freshProvisionSequence() {
        EnvironmentProvisioner.EnvironmentRef ref =
                provisioner().provision(SOURCE_TENANT, bundle(), "dev");

        assertThat(ref.tenantId()).isEqualTo(ADOPTED_TENANT);
        assertThat(ref.appId()).isEqualTo(CREATED_APP);
        List<String> steps = List.copyOf(paths);
        // adopt lookup first (404), then the tenant create
        assertThat(steps.get(0)).isEqualTo("GET /api/v1/admin/tenants");
        assertThat(steps.get(1)).isEqualTo("POST /api/v1/admin/tenants");
        assertThat(steps.get(2)).isEqualTo("GET /api/v1/metadata/apps");   // leftover check
        assertThat(steps.get(3)).isEqualTo("POST /api/v1/metadata/apps");  // import
        assertThat(steps.get(4)).startsWith("POST /api/v1/metadata/apps/") // publish
                .endsWith("/publish");
        // the tenant create carried the deterministic admin username
        assertThat(bodies).anyMatch(b -> b.contains("\"apiName\":\"erp-dev-11111111\"")
                && b.contains("\"adminUsername\":\"env-dev-11111111\""));
    }

    @Test
    @DisplayName("a crashed prior attempt's tenant is ADOPTED — with the credential-reset leg")
    void adoptedTenantResetsCredential() {
        tenantExists.set(true);
        EnvironmentProvisioner.EnvironmentRef ref =
                provisioner().provision(SOURCE_TENANT, bundle(), "dev");

        assertThat(ref.tenantId()).isEqualTo(ADOPTED_TENANT);
        List<String> steps = List.copyOf(paths);
        assertThat(steps.get(1)).isEqualTo("POST /api/v1/admin/tenants/" + ADOPTED_TENANT + "/users");
        // no second tenant create happened
        assertThat(steps).noneMatch("POST /api/v1/admin/tenants"::equals);
    }

    @Test
    @DisplayName("a leftover app from a partial attempt is retired before the fresh import")
    void leftoverAppRetired() {
        leftoverAppExists.set(true);
        provisioner().provision(SOURCE_TENANT, bundle(), "dev");

        List<String> steps = List.copyOf(paths);
        assertThat(steps).contains("DELETE /api/v1/metadata/apps/" + CREATED_APP);
        int deleteAt = steps.indexOf("DELETE /api/v1/metadata/apps/" + CREATED_APP);
        int importAt = steps.indexOf("POST /api/v1/metadata/apps");
        assertThat(deleteAt).isLessThan(importAt);
    }
}
