package com.novaforge.integration.clients;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.security.EventHeaders;
import com.novaforge.security.ServiceTokenClient;
import com.novaforge.security.TracePropagation;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Published Integrations-branch reads (PHASE-6 §3): connectors, webhooks, and
 * credentials resolve through the Metadata Service's published surface with the
 * shared service client's token — the runtime-never-serves-drafts rule holds for
 * integration definitions exactly as for entities. The in-process cache rides the
 * definitions epoch (the Reporting Service's pattern): a {@code metadata.published}
 * event on the spine ({@code novaforge.metadata}, PHASE-3 §4) bumps the tenant's
 * epoch key and retires every cached bundle.
 */
@Component
public class PublishedIntegrations {

    private static final Logger LOG = LoggerFactory.getLogger(PublishedIntegrations.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private record Cached(long epoch, AppDefinition app) {
    }

    private final RestClient metadata;
    private final StringRedisTemplate redis;
    private final ServiceTokenClient serviceToken;
    private final Tracer tracer;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** The hermetic base for tests and tools: no listener, no fetch — override the lookups. */
    protected PublishedIntegrations() {
        this.metadata = null;
        this.redis = null;
        this.serviceToken = null;
        this.tracer = null;
    }

    public PublishedIntegrations(
            @Value("${novaforge.metadata.url:http://localhost:8081}") String metadataUrl,
            StringRedisTemplate redis,
            ServiceTokenClient serviceToken,
            Tracer tracer) {
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
    @KafkaListener(topics = "novaforge.metadata", groupId = "novaforge-integration-definitions")
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
            redis.opsForValue().increment("novaforge:integration:epoch:" + tenant);
            LOG.debug("metadata.published invalidated definitions for tenant {}", tenant);
        } catch (Exception e) {
            LOG.warn("invalid metadata.published payload ignored: {}", e.getMessage());
        }
    }

    /** The tenant's published app by apiName — cached until its epoch moves. */
    public Optional<AppDefinition> byApiName(UUID tenantId, String appApiName) {
        long epoch = epochOf(tenantId);
        Cached cached = cache.get(tenantId + ":" + appApiName);
        if (cached != null && cached.epoch() == epoch) {
            return Optional.of(cached.app());
        }
        for (AppDefinition app : fetchAll(tenantId)) {
            cache.put(tenantId + ":" + app.apiName(), new Cached(epoch, app));
        }
        return Optional.ofNullable(cache.get(tenantId + ":" + appApiName)).map(Cached::app);
    }

    /** Every published app of the tenant — the dispatch scan's and hook resolver's set. */
    public List<AppDefinition> allApps(UUID tenantId) {
        long epoch = epochOf(tenantId);
        List<AppDefinition> apps = fetchAll(tenantId);
        for (AppDefinition app : apps) {
            cache.put(tenantId + ":" + app.apiName(), new Cached(epoch, app));
        }
        return apps;
    }

    private List<AppDefinition> fetchAll(UUID tenantId) {
        try {
            // the service-caller index: [{tenantId, appId, version}] for every app
            List<Map<String, Object>> index = metadata.method(HttpMethod.GET)
                    .uri("/api/v1/metadata/published-apps")
                    .headers(h -> h.setBearerAuth(serviceToken.token()))
                    .retrieve().body(List.class);
            List<AppDefinition> result = new ArrayList<>();
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
                result.add(DefinitionParser.parse(
                        DefinitionParser.write(bundle.get("app")), AppDefinition.class));
            }
            return result;
        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "published-apps fetch failed: " + e.getMessage());
        }
    }

    private long epochOf(UUID tenantId) {
        String epoch = redis.opsForValue().get("novaforge:integration:epoch:" + tenantId);
        return epoch == null ? 0 : Long.parseLong(epoch);
    }
}
