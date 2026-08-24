package com.novaforge.metadata.events;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes the {@code metadata.published} envelope (PHASE-1 §4) on the Kafka spine
 * (PHASE-3 §4): the Phase 1 interim — Redis pub/sub channel {@code
 * novaforge.metadata.events} — is retired; the same envelope rides family topic
 * {@code novaforge.metadata} keyed {@code tenantId:appId}, with the shared header
 * conventions ({@code X-Event-Id}/{@code X-Event-Type}/{@code X-Tenant-Id}) so the
 * spine's dedupe/ordering rules apply uniformly. Consumers changed only their client.
 * The send is synchronous: a broker outage fails the publish audibly rather than
 * leaving definition consumers stale indefinitely.
 * {@code novaforge.metadata.publish-transport=noop} disables emission (tests).
 */
@Component
public class MetadataPublishEventPublisher {

    /** The metadata family topic (PHASE-3 §4 topology: {@code novaforge.<family>}). */
    public static final String TOPIC = "novaforge.metadata";

    private static final Logger LOG = LoggerFactory.getLogger(MetadataPublishEventPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final boolean enabled;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public MetadataPublishEventPublisher(KafkaTemplate<String, String> kafka,
                                         @Value("${novaforge.metadata.publish-transport:kafka}") String transport) {
        this.kafka = kafka;
        this.enabled = !"noop".equalsIgnoreCase(transport);
    }

    public void publishMetadataPublished(UUID tenantId, UUID appId, int version,
                                         UUID actorId, java.time.Instant publishedAt) {
        if (!enabled) {
            return;
        }
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> envelope = Map.of(
                "eventId", eventId,
                "event", "metadata.published",
                "tenantId", tenantId.toString(),
                "appId", appId.toString(),
                "version", version,
                "publishedAt", publishedAt.toString(),
                "actorId", actorId.toString());
        ProducerRecord<String, String> record =
                new ProducerRecord<>(TOPIC, tenantId + ":" + appId, mapper.writeValueAsString(envelope));
        record.headers().add("X-Event-Id", eventId.getBytes());
        record.headers().add("X-Event-Type", "metadata.published".getBytes());
        record.headers().add("X-Tenant-Id", tenantId.toString().getBytes());
        try {
            kafka.send(record).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.error("metadata.published delivery failed for app {} v{}", appId, version, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("metadata.published delivery failed", e);
        }
    }
}
