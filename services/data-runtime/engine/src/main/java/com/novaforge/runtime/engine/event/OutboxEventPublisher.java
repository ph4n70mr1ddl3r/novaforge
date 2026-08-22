package com.novaforge.runtime.engine.event;

import com.novaforge.runtime.storage.outbox.OutboxStore;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The Phase 3 event-seam binding (PHASE-3 §4): record events ride the creating
 * transaction as outbox rows ({@code record.created/updated/deleted}); the relay
 * publishes them to the Kafka spine at-least-once after commit. Payloads carry the
 * event id used for consumer dedup and the tenant/entity/record identifiers.
 */
@Component
public class OutboxEventPublisher implements DomainEventPublisher {

    private final OutboxStore outbox;

    public OutboxEventPublisher(OutboxStore outbox) {
        this.outbox = outbox;
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
        payload.put("recordId", event.recordId().toString());
        payload.put("actorId", event.actorId().toString());
        payload.put("occurredAt", event.occurredAt() == null ? Instant.now().toString() : event.occurredAt());
        payload.putAll(metadata);
        outbox.append(UUID.randomUUID(), event.tenantId(), event.entityId(), event.recordId(),
                event.event(), payload);
    }
}
