package com.novaforge.metadata.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes the {@code metadata.published} envelope (PHASE-1 §4). Transport is Redis
 * pub/sub channel {@code novaforge.metadata.events} until the Kafka spine lands in
 * Phase 3, which rebinds the same envelope with no consumer change beyond the client.
 * {@code novaforge.metadata.publish-transport=noop} disables emission (tests).
 */
@Component
public class MetadataPublishEventPublisher {

    public static final String CHANNEL = "novaforge.metadata.events";

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public MetadataPublishEventPublisher(StringRedisTemplate redis,
                                         @Value("${novaforge.metadata.publish-transport:redis}") String transport) {
        this.redis = redis;
        this.enabled = !"noop".equalsIgnoreCase(transport);
    }

    public void publishMetadataPublished(UUID tenantId, UUID appId, int version,
                                         UUID actorId, Instant publishedAt) {
        if (!enabled) {
            return;
        }
        Map<String, Object> envelope = Map.of(
                "event", "metadata.published",
                "tenantId", tenantId.toString(),
                "appId", appId.toString(),
                "version", version,
                "publishedAt", publishedAt.toString(),
                "actorId", actorId.toString());
        redis.convertAndSend(CHANNEL, mapper.writeValueAsString(envelope));
    }
}
