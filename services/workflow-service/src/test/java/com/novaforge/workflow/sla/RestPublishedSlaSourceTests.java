package com.novaforge.workflow.sla;

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
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The SLA source's tenant-pinning contract (twenty-ninth pass coverage audit):
 * the service-caller index is CROSS-TENANT — two tenants may publish same-named
 * apps — so the bundle fetch must pin the app by (apiName AND tenantId); the
 * first apiName match in any other tenant is never this caller's app. Pinned
 * with a same-named app in a foreign tenant ahead of the caller's own.
 */
class RestPublishedSlaSourceTests {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID FOREIGN_TENANT = UUID.fromString("99999999-9999-4999-8999-999999999999");
    private static final UUID CALLERS_APP = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID FOREIGN_APP = UUID.fromString("55555555-5555-4555-8555-555555555555");

    static final List<String> bundleFetches = List.of();
    static final java.util.concurrent.ConcurrentLinkedQueue<String> fetchedApps =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    private static HttpServer metadata;

    @BeforeAll
    static void stubMetadata() throws Exception {
        metadata = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        metadata.createContext("/api/v1/metadata/published-apps", exchange -> {
            // the foreign tenant's same-named app is indexed FIRST — the pin must skip it
            String index = "[{\"tenantId\": \"" + FOREIGN_TENANT + "\", \"appId\": \""
                    + FOREIGN_APP + "\", \"apiName\": \"erp\", \"version\": 1},"
                    + " {\"tenantId\": \"" + TENANT + "\", \"appId\": \"" + CALLERS_APP
                    + "\", \"apiName\": \"erp\", \"version\": 3}]";
            respond(exchange, index);
        });
        metadata.createContext("/api/v1/metadata/apps/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            fetchedApps.add(path);
            if (path.contains(String.valueOf(FOREIGN_APP))) {
                respond(exchange, "{\"version\": 1, \"app\": {\"apiName\": \"erp\","
                        + " \"slas\": [{\"name\": \"foreign\"}]}}");
                return;
            }
            respond(exchange, "{\"version\": 3, \"app\": {\"apiName\": \"erp\","
                    + " \"slas\": [{\"id\": \"sla-1\", \"target\": \"1h\"}]}}");
        });
        metadata.start();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }

    @AfterAll
    static void stop() {
        metadata.stop(0);
    }

    @Test
    @DisplayName("slasOf fetches the CALLER's tenant bundle, never a foreign same-named app")
    void tenantPinsTheBundle() {
        ServiceTokenClient serviceToken = mock(ServiceTokenClient.class);
        when(serviceToken.token()).thenReturn("svc-token-1");
        RestPublishedSlaSource source =
                new RestPublishedSlaSource("http://127.0.0.1:" + metadata.getAddress().getPort(),
                        serviceToken);

        List<com.novaforge.metadata.SlaDefinition> slas = source.slasOf(TENANT, "erp");

        assertThat(fetchedApps).hasSize(1);
        assertThat(fetchedApps.peek()).contains(String.valueOf(CALLERS_APP));
        assertThat(slas).hasSize(1);
        assertThat(slas.get(0).id()).isEqualTo("sla-1");
    }
}
