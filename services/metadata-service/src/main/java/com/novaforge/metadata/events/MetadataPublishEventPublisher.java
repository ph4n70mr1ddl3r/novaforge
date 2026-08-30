package com.novaforge.metadata.events;

import com.novaforge.security.TracePropagation;
import io.micrometer.tracing.Tracer;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Enqueues the {@code metadata.published} envelope (PHASE-1 §4) on the transactional
 * outbox (PHASE-3 §4's Kafka spine, delivered by {@link MetadataOutboxRelay}): the
 * Phase 1 interim — Redis pub/sub channel {@code novaforge.metadata.events} — is
 * retired; the same envelope rides family topic {@code novaforge.metadata} keyed
 * {@code tenantId:appId}, with the shared header conventions
 * ({@code X-Event-Id}/{@code X-Event-Type}/{@code X-Tenant-Id}) plus the publish
 * request's captured W3C {@code traceparent} (ARCHITECTURE.md §6) so consumers chain
 * onto the trace that published.
 *
 * <p>The outbox row commits atomically with the version it announces — a send inside
 * the publish transaction held the DB connection (and the app row lock) for the full
 * broker-send timeout on every publish, and a send that succeeded just before a
 * rollback emitted a phantom event for a version that does not exist. The relay
 * publishes at-least-once and retries until the broker returns, so consumers are
 * never left stale indefinitely — delivery just waits out the outage.
 * {@code novaforge.metadata.publish-transport=noop} disables emission (tests).
 */
@Component
public class MetadataPublishEventPublisher {

    /** The metadata family topic (PHASE-3 §4 topology: {@code novaforge.<family>}). */
    public static final String TOPIC = "novaforge.metadata";

    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final Tracer tracer;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public MetadataPublishEventPublisher(JdbcTemplate jdbc,
                                         @Value("${novaforge.metadata.publish-transport:kafka}") String transport,
                                         Tracer tracer) {
        this.jdbc = jdbc;
        this.enabled = !"noop".equalsIgnoreCase(transport);
        this.tracer = tracer;
    }

    public void publishMetadataPublished(UUID tenantId, UUID appId, int version,
                                         UUID actorId, Instant publishedAt) {
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
        String traceparent = TracePropagation.capture(tracer);
        jdbc.update("""
                INSERT INTO md_event_outbox (id, tenant_id, app_id, event_type, payload, traceparent)
                VALUES (?, ?, ?, 'metadata.published', ?::jsonb, ?)""",
                UUID.randomUUID(), tenantId, appId,
                mapper.writeValueAsString(envelope), traceparent);
    }
}
