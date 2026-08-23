package com.novaforge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.novaforge.common.error.PlatformException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one grant every trusted-service caller rides: form-encoded credentials
 * (a secret carrying form-reserved characters must not corrupt the body), one
 * fetch per TTL with the cached grant served until 30 s before expiry, and a
 * grant without a token fails loudly (INTERNAL, the house rule). Serves the
 * endpoint through the JDK's own HttpServer — the client itself speaks the
 * zero-web JDK HttpClient (the lib family's charter, PHASE-0 §5.1).
 */
class ServiceTokenClientTest {

    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final HttpServer server;
    private final ServiceTokenClient client;
    private volatile String response = "{\"access_token\":\"tok-1\",\"expires_in\":300}";
    private volatile int status = 200;

    ServiceTokenClientTest() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/protocol/openid-connect/token", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        client = new ServiceTokenClient(HttpClient.newHttpClient(),
                URI.create("http://localhost:" + server.getAddress().getPort()
                        + "/protocol/openid-connect/token"),
                "novaforge-runtime", "s3cr&et=with=specials");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("credentials ride form-encoded; the token caches until the refresh window")
    void grantEncodesAndCaches() {
        assertThat(client.token()).isEqualTo("tok-1");
        assertThat(client.token()).isEqualTo("tok-1");   // cached — no second grant
        assertThat(bodies).containsExactly(
                "grant_type=client_credentials&client_id=novaforge-runtime"
                        + "&client_secret=s3cr%26et%3Dwith%3Dspecials");
    }

    @Test
    @DisplayName("an expired grant refetches")
    void grantRefreshes() {
        response = "{\"access_token\":\"tok-1\",\"expires_in\":0}";
        assertThat(client.token()).isEqualTo("tok-1");
        response = "{\"access_token\":\"tok-2\",\"expires_in\":300}";
        // expires_in 0 ⇒ refreshAt in the past ⇒ the next call refetches
        assertThat(client.token()).isEqualTo("tok-2");
        assertThat(bodies).hasSize(2);
    }

    @Test
    @DisplayName("a grant without a token fails loudly")
    void grantWithoutTokenFails() {
        response = "{\"error\":\"invalid_grant\"}";
        assertThatThrownBy(client::token)
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("service token grant returned no token");
        assertThat(bodies).hasSize(1);
    }

    @Test
    @DisplayName("a non-200 grant response fails loudly with the status and body")
    void grantFailureSurfacesStatus() {
        status = 401;
        response = "{\"error\":\"invalid_client\",\"error_description\":\"unauthorized\"}";
        assertThatThrownBy(client::token)
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("HTTP 401")
                .hasMessageContaining("invalid_client");
    }
}
