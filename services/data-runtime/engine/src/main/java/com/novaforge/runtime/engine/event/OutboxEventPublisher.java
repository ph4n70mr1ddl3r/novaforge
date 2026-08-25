package com.novaforge.runtime.engine.event;

import com.novaforge.runtime.storage.outbox.OutboxStore;
import com.novaforge.security.TracePropagation;
import io.micrometer.tracing.Tracer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The Phase 3 event-seam binding (PHASE-3 §4): record events ride the creating
 * transaction as outbox rows ({@code record.created/updated/deleted}); the relay
 * publishes them to the Kafka spine at-least-once after commit. Payloads carry the
 * event id used for consumer dedup and the tenant/entity/record identifiers, plus
 * the captured W3C {@code traceparent} of the causing request (ARCHITECTURE §6 /
 * PHASE-3 §4) — the relay lifts it into the Kafka header where consumers link.
 */
@Component
public class OutboxEventPublisher implements DomainEventPublisher {

    private final OutboxStore outbox;
    private final Tracer tracer;

    public OutboxEventPublisher(OutboxStore outbox, Tracer tracer) {
        this.outbox = outbox;
        this.tracer = tracer;
    }

    @Override
    public void publish(DomainEvent event) {
        publish(event, Map.of());
    }

    @Override
    public void publish(DomainEvent event, Map<String, Object> metadata) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event.event());
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("tenantId", event.tenantId().toString());
        payload.put("entityId", event.entityId());
        // recordless app events (a scheduled flow's publishEvent tail) omit the
        // record id — the envelope's consumers key on the event id regardless
        if (event.recordId() != null) {
            payload.put("recordId", event.recordId().toString());
        }
        payload.put("actorId", event.actorId().toString());
        payload.put("occurredAt", event.occurredAt() == null ? Instant.now().toString() : event.occurredAt());
        payload.putAll(metadata);
        String traceparent = TracePropagation.capture(tracer);
        if (traceparent != null) {
            payload.put("traceparent", traceparent);
        }
        outbox.append(UUID.randomUUID(), event.tenantId(), event.entityId(), event.recordId(),
                event.event(), payload);
    }
}
