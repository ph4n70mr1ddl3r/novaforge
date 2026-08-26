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
 * epoch key, so every cached entry keys stale the moment a publish lands.
 */
@Component
public class RestPublishedApps implements PublishedApps {

    private static final Logger LOG = LoggerFactory.getLogger(RestPublishedApps.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final RestClient metadata;
    private final org.springframework.data.redis.core.StringRedisTemplate redis;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(long epoch, PublishedApp app) {
    }

    private final ServiceTokenClient serviceToken;
    private final io.micrometer.tracing.Tracer tracer;

    public RestPublishedApps(
            @Value("${novaforge.metadata.url:http://localhost:8081}") String metadataUrl,
            org.springframework.data.redis.core.StringRedisTemplate redis,
            ServiceTokenClient serviceToken,
            io.micrometer.tracing.Tracer tracer) {
        this.metadata = RestClient.builder().baseUrl(metadataUrl).build();
        this.redis = redis;
        this.serviceToken = serviceToken;
        this.tracer = tracer;
    }

    /**
     * metadata.published invalidates: the epoch bump retires every cached bundle.
     * PHASE-3 §4 rebind — the envelope rides the spine topic {@code novaforge.metadata}
     * (the Phase 1 Redis pub/sub channel is retired); at-least-once redelivery is safe
     * (an epoch increment is idempotent in effect).
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
            redis.opsForValue().increment("novaforge:reporting:epoch:" + tenant);
            LOG.debug("metadata.published invalidated definitions for tenant {}", tenant);
        } catch (Exception e) {
            LOG.warn("invalid metadata.published payload ignored: {}", e.getMessage());
        }
    }

    @Override
    public Optional<PublishedApp> byApiName(UUID tenantId, String appApiName) {
        long epoch = epochOf(tenantId);
        Cached cached = cache.get(tenantId + ":" + appApiName);
        if (cached != null && cached.epoch() == epoch) {
            return Optional.of(cached.app());
        }
        for (PublishedApp app : fetchAll(tenantId)) {
            cache.put(tenantId + ":" + app.apiName(), new Cached(epoch, app));
        }
        return Optional.ofNullable(cache.get(tenantId + ":" + appApiName)).map(Cached::app);
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

    private long epochOf(UUID tenantId) {
        String epoch = redis.opsForValue().get("novaforge:reporting:epoch:" + tenantId);
        return epoch == null ? 0 : Long.parseLong(epoch);
    }
}
