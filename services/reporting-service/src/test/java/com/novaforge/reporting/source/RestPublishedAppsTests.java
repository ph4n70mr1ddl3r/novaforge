package com.novaforge.reporting.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.novaforge.security.ServiceTokenClient;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * The published-bundle source's own contract (the 2026-08-31 hunt): the metadata
 * leg is timeout-bounded like every sibling client in the service (an unbounded
 * read stalls the run's resolve leg), a Redis outage degrades — the epoch is a
 * latency optimization, never a run-killing dependency (ReportRunner's own cache
 * posture: hiccup → uncached, never failed) — and the in-process cache self-heals
 * through a TTL window when the epoch bump is lost (the resolver's H-18P2 pattern:
 * staleness costs one window, never a restart).
 */
@ExtendWith(MockitoExtension.class)
class RestPublishedAppsTests {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");

    /** The fake Metadata Service: a stub upstream on an ephemeral port. */
    private static HttpServer metadata;

    /** Index reads observed — the cache-miss/freshness signal. */
    private static final AtomicInteger indexReads = new AtomicInteger();

    /** The published version the stub serves — bumped to simulate a publish landing. */
    private static volatile int version = 1;

    private static String indexJson;

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;

    @BeforeAll
    static void stubMetadata() throws Exception {
        metadata = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        metadata.createContext("/api/v1/metadata/published-apps", exchange -> {
            indexReads.incrementAndGet();
            respond(exchange, indexJson);
        });
        metadata.createContext("/api/v1/metadata/apps/", exchange -> {
            // /apps/{appId}/published — the bundle leg; the app body rides inline
            respond(exchange, "{\"version\": " + version + ", \"app\": {\"apiName\": \"ArDesk\"}}");
        });
        metadata.start();
        indexJson = "[{ \"tenantId\": \"" + TENANT + "\", \"appId\": \"app-1\", "
                + "\"version\": " + version + " }]";
    }

    @AfterAll
    static void stopMetadata() {
        metadata.stop(0);
    }

    @BeforeEach
    void wire() {
        indexReads.set(0);
        version = 1;
        indexJson = "[{ \"tenantId\": \"" + TENANT + "\", \"appId\": \"app-1\", "
                + "\"version\": " + version + " }]";
        redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        values = ops;
        lenient().when(redis.opsForValue()).thenReturn(values);
    }

    private RestPublishedApps source(long ttlMillis) {
        ServiceTokenClient serviceToken = mock(ServiceTokenClient.class);
        lenient().when(serviceToken.token()).thenReturn("test-token");
        return new RestPublishedApps("http://127.0.0.1:" + metadata.getAddress().getPort(),
                redis, serviceToken, mock(io.micrometer.tracing.Tracer.class), ttlMillis);
    }

    private void epochAt(long epoch) {
        lenient().when(values.get("novaforge:reporting:epoch:" + TENANT))
                .thenReturn(epoch == 0 ? null : String.valueOf(epoch));
    }

    private void redisDown() {
        when(values.get("novaforge:reporting:epoch:" + TENANT))
                .thenThrow(new RedisConnectionFailureException("redis unreachable"));
    }

    @Test
    @DisplayName("the metadata client is timeout-bounded like every sibling client (2 s / 60 s)")
    void metadataClientIsBounded() throws Exception {
        // Reflection over the private fields is deliberate: the numbers ARE the fix —
        // an unbounded RestClient.builder() was this class's defect — and a behavioral
        // pin would mean a real multi-second hang assertion. RestClient (Spring 7)
        // exposes no getRequestFactory(); the DefaultRestClient field is the holder.
        var field = RestPublishedApps.class.getDeclaredField("metadata");
        field.setAccessible(true);
        var client = field.get(source(30_000));
        var factoryField = client.getClass().getDeclaredField("clientRequestFactory");
        factoryField.setAccessible(true);
        var factory = factoryField.get(client);
        assertThat(intField(factory, "connectTimeout")).isEqualTo(2_000);
        assertThat(intField(factory, "readTimeout")).isEqualTo(60_000);
    }

    /** Spring 7's factory exposes setters only — the fields are the pin's view. */
    private static int intField(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(target);
    }

    @Test
    @DisplayName("a Redis outage degrades: a warm cache serves, a cold cache fetches fresh — never a failed run")
    void redisOutageDegradesInsteadOfFailing() {
        RestPublishedApps apps = source(30_000);   // one instance, one in-process cache
        // cold: the epoch read fails, the fetch re-validates against the published
        // index — the resolve succeeds anyway
        redisDown();
        Optional<PublishedApps.PublishedApp> cold = apps.byApiName(TENANT, "ArDesk");
        assertThat(cold).isPresent();
        assertThat(cold.orElseThrow().version()).isEqualTo(1);
        assertThat(indexReads.get()).isEqualTo(1);

        // warm: the SAME outage must not re-fetch (or fail) — the in-process entry
        // serves within the TTL window; only its expiry forces re-validation
        Optional<PublishedApps.PublishedApp> warm = apps.byApiName(TENANT, "ArDesk");
        assertThat(warm).isPresent();
        assertThat(indexReads.get()).isEqualTo(1);   // no second fetch
    }

    @Test
    @DisplayName("an epoch bump lost to the Redis outage self-heals through the TTL window")
    void lostBumpSelfHealsThroughTheTtl() {
        RestPublishedApps apps = source(-1);   // always-expired: every read re-validates
        epochAt(7);
        assertThat(apps.byApiName(TENANT, "ArDesk").orElseThrow().version()).isEqualTo(1);
        assertThat(indexReads.get()).isEqualTo(1);

        // the publish lands, but the epoch bump is LOST (Redis was down at delivery):
        // the epoch still reads 7 — only the TTL's forced re-validation sees version 2
        version = 2;
        assertThat(apps.byApiName(TENANT, "ArDesk").orElseThrow().version()).isEqualTo(2);
        assertThat(indexReads.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("a matching epoch inside the window serves from cache — no metadata re-read")
    void matchingEpochServesWithinTheWindow() {
        epochAt(3);
        RestPublishedApps apps = source(30_000);
        assertThat(apps.byApiName(TENANT, "ArDesk")).isPresent();
        assertThat(apps.byApiName(TENANT, "ArDesk")).isPresent();
        assertThat(indexReads.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("an epoch bump the cache DOES observe retires the entry immediately")
    void observedBumpRetiresImmediately() {
        RestPublishedApps apps = source(30_000);
        epochAt(1);
        assertThat(apps.byApiName(TENANT, "ArDesk").orElseThrow().version()).isEqualTo(1);
        version = 2;
        epochAt(2);   // the bump landed and was observed
        assertThat(apps.byApiName(TENANT, "ArDesk").orElseThrow().version()).isEqualTo(2);
        assertThat(indexReads.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("a bump lost to a Redis failure in invalidate() never throws — TTL-bounded staleness")
    void invalidateSurvivesRedisOutage() {
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));
        assertThatCode(() -> source(30_000).invalidate(
                "{\"tenantId\": \"" + TENANT + "\", \"appId\": \"app-1\"}"))
                .doesNotThrowAnyException();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
