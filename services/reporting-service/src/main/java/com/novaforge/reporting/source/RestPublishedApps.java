package com.novaforge.reporting.source;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.reporting.source.PublishedApps.PublishedApp;
import com.novaforge.security.EventHeaders;
import com.novaforge.security.ServiceTokenClient;
import com.novaforge.security.TracePropagation;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The published read over HTTP (§2): the service client lists {@code published-apps},
 * fetches each app's bundle, and re-parses the AppDefinition — the same consumer
 * shape as the Scheduler's and Workflow Service's sources. The in-process cache
 * rides the definitions epoch: a {@code metadata.published} event bumps the tenant's
 * epoch key, so every cached entry keys stale the moment a publish lands. The epoch
 * is a latency optimization only — a Redis outage degrades to the in-process cache
 * (or a fresh fetch on a miss), never a failed run — and the TTL bounds staleness
 * a lost {@code metadata.published} delivery could otherwise serve forever.
 */
@Component
public class RestPublishedApps implements PublishedApps {

    private static final Logger LOG = LoggerFactory.getLogger(RestPublishedApps.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The epoch read's degraded sentinel: Redis unreachable, staleness bounded by the TTL. */
    static final long DEGRADED_EPOCH = -1L;

    private final RestClient metadata;
    private final org.springframework.data.redis.core.StringRedisTemplate redis;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final long bundleTtlMillis;

    private record Cached(long epoch, long cachedAtMillis, PublishedApp app) {
    }

    private final ServiceTokenClient serviceToken;
    private final io.micrometer.tracing.Tracer tracer;

    public RestPublishedApps(
            @Value("${novaforge.metadata.url:http://localhost:8081}") String metadataUrl,
            org.springframework.data.redis.core.StringRedisTemplate redis,
            ServiceTokenClient serviceToken,
            io.micrometer.tracing.Tracer tracer,
            @Value("${novaforge.reporting.bundle-cache-ttl-ms:30000}") long bundleTtlMillis) {
        this.metadata = RestClient.builder().baseUrl(metadataUrl)
                        .requestFactory(bounded(metadataUrl))
                        .build();
        this.redis = redis;
        this.serviceToken = serviceToken;
        this.tracer = tracer;
        this.bundleTtlMillis = bundleTtlMillis;
    }

    /**
     * metadata.published invalidates: the epoch bump retires every cached bundle.
     * PHASE-3 §4 rebind — the envelope rides the spine topic {@code novaforge.metadata}
     * (the Phase 1 Redis pub/sub channel is retired); at-least-once redelivery is safe
     * (an epoch increment is idempotent in effect). The bump is best-effort by the
     * cache's own contract (see {@link #epochOf}): a bump lost to a Redis outage
     * costs one TTL window of staleness, never a wedged tenant.
     */
    @KafkaListener(topics = "novaforge.metadata", groupId = "novaforge-reporting-definitions")
    public void onMetadataPublished(ConsumerRecord<String, String> message) {
        var header = message.headers().lastHeader(EventHeaders.TRACEPARENT);
        String traceparent = header == null ? null
                : new String(header.value(), StandardCharsets.UTF_8);
        TracePropagation.inConsumerSpan(tracer, traceparent,
                "novaforge.metadata consume", () -> invalidate(message.value()));
    }

    void invalidate(String message) {
        try {
            Map<String, Object> event = MAPPER.readValue(message, Map.class);
            String tenant = String.valueOf(event.get("tenantId"));
            try {
                redis.opsForValue().increment("novaforge:reporting:epoch:" + tenant);
            } catch (RuntimeException redisDown) {
                LOG.warn("epoch bump for tenant {} lost to a Redis failure — the cache "
                        + "TTL re-validates against the published index within the window: {}",
                        tenant, redisDown.getMessage());
            }
            LOG.debug("metadata.published invalidated definitions for tenant {}", tenant);
        } catch (Exception e) {
            LOG.warn("invalid metadata.published payload ignored: {}", e.getMessage());
        }
    }

    @Override
    public Optional<PublishedApp> byApiName(UUID tenantId, String appApiName) {
        Cached cached = cache.get(tenantId + ":" + appApiName);
        long epoch = epochOf(tenantId);
        if (cached != null && fresh(cached, epoch)) {
            return Optional.of(cached.app());
        }
        // the re-validation leg IS the published index: fetchAll re-reads it and
        // re-parses every bundle, so a version that moved with no observed epoch
        // bump (or past the TTL) lands here regardless of how the signal was lost
        for (PublishedApp app : fetchAll(tenantId)) {
            cache.put(tenantId + ":" + app.apiName(),
                    new Cached(epoch, System.currentTimeMillis(), app));
        }
        return Optional.ofNullable(cache.get(tenantId + ":" + appApiName)).map(Cached::app);
    }

    /**
     * A cached entry serves when the epoch still matches AND the TTL window holds.
     * The TTL is the self-heal the epoch alone cannot provide (the same pattern as
     * the Data Runtime resolver's H-18P2 fix): a dropped {@code metadata.published}
     * delivery — Redis down at bump time, an outbox crash, a consumer rebalance gap —
     * left the stale bundle serving FOREVER under this class's old epoch-only check.
     * Staleness now costs one TTL window, never a restart.
     */
    private boolean fresh(Cached cached, long epoch) {
        if (System.currentTimeMillis() - cached.cachedAtMillis() > bundleTtlMillis) {
            return false;
        }
        // DEGRADED_EPOCH: Redis is unreachable, so no epoch exists to compare — the
        // cache is a latency tool, never a correctness boundary (ReportRunner's own
        // cache posture): serve the in-process entry and let the TTL force the next
        // re-validation, the same way a Redis hiccup degrades a run to uncached.
        return epoch == DEGRADED_EPOCH || cached.epoch() == epoch;
    }

    private List<PublishedApp> fetchAll(UUID tenantId) {
        try {
            // the service-caller index: [{tenantId, appId, version}] for every app
            List<Map<String, Object>> index = metadata.method(HttpMethod.GET)
                    .uri("/api/v1/metadata/published-apps")
                    .headers(h -> h.setBearerAuth(serviceToken.token()))
                    .retrieve().body(List.class);
            List<PublishedApp> result = new java.util.ArrayList<>();
            if (index == null) {
                return result;
            }
            for (Map<String, Object> entry : index) {
                if (!tenantId.toString().equals(String.valueOf(entry.get("tenantId")))) {
                    continue;
                }
                Map<String, Object> bundle = metadata.method(HttpMethod.GET)
                        .uri("/api/v1/metadata/apps/{appId}/published",
                                String.valueOf(entry.get("appId")))
                        .headers(h -> h.setBearerAuth(serviceToken.token()))
                        .retrieve().body(Map.class);
                if (bundle == null) {
                    continue;
                }
                AppDefinition definition = DefinitionParser.parse(
                        DefinitionParser.write(bundle.get("app")), AppDefinition.class);
                result.add(new PublishedApp(tenantId, String.valueOf(entry.get("appId")),
                        definition.apiName(),
                        ((Number) bundle.getOrDefault("version", 0)).intValue(), definition));
            }
            return result;
        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "published-apps fetch failed: " + e.getMessage(), null, e);
        }
    }

    /**
     * The tenant's epoch, guarded the way ReportRunner guards its own cache legs: an
     * unguarded read turned every Redis blip into a hard {@link
     * org.springframework.data.redis.RedisConnectionFailureException} on EVERY report
     * run — even for bundles already cached in-process — while the epoch is only a
     * latency optimization (a miss re-validates against the published index anyway).
     * Degraded means: serve the in-process entry when present; on a miss, fetch fresh.
     * The trade-off is bounded staleness — a bump lost while Redis is down self-heals
     * via the TTL's forced re-validation.
     */
    private long epochOf(UUID tenantId) {
        try {
            String epoch = redis.opsForValue().get("novaforge:reporting:epoch:" + tenantId);
            return epoch == null ? 0 : Long.parseLong(epoch);
        } catch (RuntimeException e) {
            LOG.warn("definitions epoch read failed — serving the published-bundle "
                    + "cache degraded (TTL-bounded): {}", e.getMessage());
            return DEGRADED_EPOCH;
        }
    }

    /**
     * East-west calls are bounded (the pattern the other internal clients already
     * ride): a hung upstream must fail in seconds, not hold the calling thread —
     * this fetch enumerates the index and pulls every bundle on a cache miss, so
     * an unbounded read stalls the run's resolve leg and with it every tenant's
     * pipeline.
     */
    private static org.springframework.http.client.SimpleClientHttpRequestFactory bounded(String ignored) {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(60_000);   // the bundle set rides inline on the miss leg
        return factory;
    }
}
