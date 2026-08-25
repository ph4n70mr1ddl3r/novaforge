package com.novaforge.metadata.events;

import com.novaforge.security.EventHeaders;
import com.novaforge.security.TracePropagation;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
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
 * conventions ({@code X-Event-Id}/{@code X-Event-Type}/{@code X-Tenant-Id} — the
 * {@code security-context} constants) plus the publish request's captured W3C
 * {@code traceparent} (ARCHITECTURE.md §6) so consumers chain onto the trace that
 * published. Consumers changed only their client.
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
    private final Tracer tracer;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public MetadataPublishEventPublisher(KafkaTemplate<String, String> kafka,
                                         @Value("${novaforge.metadata.publish-transport:kafka}") String transport,
                                         Tracer tracer) {
        this.kafka = kafka;
        this.enabled = !"noop".equalsIgnoreCase(transport);
        this.tracer = tracer;
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
        record.headers().add(EventHeaders.EVENT_ID, eventId.getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE,
                "metadata.published".getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.TENANT_ID,
                tenantId.toString().getBytes(StandardCharsets.UTF_8));
        String traceparent = TracePropagation.capture(tracer);
        if (traceparent != null) {
            record.headers().add(EventHeaders.TRACEPARENT,
                    traceparent.getBytes(StandardCharsets.UTF_8));
        }
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
