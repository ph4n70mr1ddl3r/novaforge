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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The runtime's metadata resolver binding (twenty-ninth pass coverage audit):
 * every published-bundle read rides the SERVICE ACCOUNT's client-credentials
 * grant (the token is cached with a 30 s expiry buffer — startup catch-up and
 * the subscriber run context-free), and the read timeout is bounded because
 * this call sits INSIDE @Transactional record writes — an unbounded read hung
 * the write while holding its DB connection. Pinned against a stub IdP +
 * metadata service: one token grant across two calls, the bearer on every read,
 * the bundle parse, and the failure mapping.
 */
class RestMetadataClientTests {

    static final AtomicInteger tokenGrants = new AtomicInteger();
    static final AtomicReference<String> lastIndexAuth = new AtomicReference<>();

    private static HttpServer stub;

    @BeforeAll
    static void stubMetadataAndIdp() throws Exception {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/realms/novaforge/protocol/openid-connect/token", exchange -> {
            tokenGrants.incrementAndGet();
            byte[] bytes = ("{\"access_token\": \"st-token-1\", \"expires_in\": 300}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
            exchange.close();
        });
        stub.createContext("/api/v1/metadata/published-apps", exchange -> {
            lastIndexAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = ("[{\"tenantId\": \"11111111-1111-4111-8111-111111111111\","
                    + " \"appId\": \"8f8f8f8f-1111-4111-8111-111111111111\","
                    + " \"apiName\": \"erp\", \"version\": 2}]").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
            exchange.close();
        });
        stub.createContext("/api/v1/metadata/apps/", exchange -> {
            byte[] bytes = ("{\"version\": 2, \"app\": {\"apiName\": \"erp\","
                    + " \"entities\": [{\"apiName\": \"invoice\", \"fields\": []}]}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
            exchange.close();
        });
        stub.start();
    }

    @AfterAll
    static void stop() {
        stub.stop(0);
    }

    @BeforeEach
    void reset() {
        tokenGrants.set(0);
        lastIndexAuth.set(null);
    }

    private RestMetadataClient client() {
        return new RestMetadataClient("http://127.0.0.1:" + stub.getAddress().getPort(),
                "http://127.0.0.1:" + stub.getAddress().getPort() + "/realms/novaforge",
                "novaforge-runtime", "secret-1");
    }

    @Test
    @DisplayName("publishedApps rides the client-credentials token; the grant is cached across calls")
    void tokenGrantCached() {
        RestMetadataClient client = client();
        var apps = client.publishedApps();

        assertThat(lastIndexAuth.get()).isEqualTo("Bearer st-token-1");
        assertThat(apps).hasSize(1);
        assertThat(apps.get(0).apiName()).isEqualTo("erp");
        assertThat(apps.get(0).version()).isEqualTo(2);

        client.publishedApps();
        assertThat(tokenGrants.get()).isEqualTo(1);   // second read reused the cached token
    }

    @Test
    @DisplayName("publishedBundle parses the versioned app bundle by app id")
    void bundleParses() {
        var bundle = client().publishedBundle(
                java.util.UUID.fromString("8f8f8f8f-1111-4111-8111-111111111111"));
        assertThat(bundle.version()).isEqualTo(2);
        assertThat(bundle.app().apiName()).isEqualTo("erp");
        assertThat(bundle.app().entities()).hasSize(1);
    }

    @Test
    @DisplayName("a failed token grant surfaces as INTERNAL — never a naked 4xx/5xx")
    void tokenGrantFailureMaps() {
        RestMetadataClient dead = new RestMetadataClient(
                "http://127.0.0.1:" + stub.getAddress().getPort(),
                "http://127.0.0.1:1/realms/novaforge",
                "novaforge-runtime", "secret-1");
        assertThatThrownBy(dead::publishedApps)
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> assertThat(((PlatformException) e).errorCode())
                        .isEqualTo(PlatformErrorCode.INTERNAL));
    }
}
