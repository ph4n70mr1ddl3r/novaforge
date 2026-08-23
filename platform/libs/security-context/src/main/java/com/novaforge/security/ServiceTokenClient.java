package com.novaforge.security;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one client-credentials grant every trusted-service caller rides — the
 * outbound twin of {@link ServiceClientGate}: one issuer, one client, one
 * cache per JVM (twelve hand-rolled per-class copies collapsed at the Phase 5
 * review, mirroring the gate's own consolidation). Credentials are
 * form-encoded — a secret carrying {@code &} or {@code =} must not corrupt
 * the grant body — and the grant refreshes 30 s early; concurrent fetches
 * race benignly (both succeed, one wins the cache). Speaks the JDK's plain
 * HTTP client, never spring-web: the lib family's zero-web charter
 * (PHASE-0 §5.1) holds — services wrap their own RestClients around the
 * token this returns.
 */
public class ServiceTokenClient {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final HttpClient http;
    private final URI tokenEndpoint;
    private final String clientId;
    private final String clientSecret;
    private volatile Grant grant;

    private record Grant(String token, Instant refreshAt) {
    }

    public ServiceTokenClient(String issuer, String clientId, String clientSecret) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                URI.create(stripSlash(issuer) + "/protocol/openid-connect/token"),
                clientId, clientSecret);
    }

    ServiceTokenClient(HttpClient http, URI tokenEndpoint, String clientId, String clientSecret) {
        this.http = http;
        this.tokenEndpoint = tokenEndpoint;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    /** The cached-or-refreshed access token for the platform service client. */
    public String token() {
        Grant current = grant;
        if (current != null && Instant.now().isBefore(current.refreshAt())) {
            return current.token();
        }
        String form = "grant_type=client_credentials&client_id=" + url(clientId)
                + "&client_secret=" + url(clientSecret);
        HttpRequest request = HttpRequest.newBuilder(tokenEndpoint)
                .timeout(Duration.ofSeconds(10))   // a token grant is fast; never hold a caller long
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "service token grant failed: " + e.getMessage());
        }
        if (response.statusCode() != 200) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "service token grant failed: HTTP " + response.statusCode()
                            + " " + response.body());
        }
        Map<String, Object> granted = MAPPER.readValue(response.body(), Map.class);
        if (granted == null || granted.get("access_token") == null) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "service token grant returned no token");
        }
        long seconds = granted.get("expires_in") instanceof Number number ? number.longValue() : 0;
        grant = new Grant(String.valueOf(granted.get("access_token")),
                Instant.now().plusSeconds(Math.max(0, seconds - 30)));
        return grant.token();
    }

    private static String stripSlash(String issuer) {
        return issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
