package com.novaforge.runtime.engine.event;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The event seam (PHASE-1 §5): the write path emits through this port; the Phase 1
 * binding is a no-op recorder (asserted in tests, §9 item 5). Phase 3 binds the Kafka
 * producer for {@code record.created/updated/deleted} with transactional-outbox delivery
 * and no write-path rework (ARCHITECTURE.md §2.4/§3).
 */
public interface DomainEventPublisher {

    record DomainEvent(String event, UUID tenantId, String entityId, UUID recordId,
                       UUID actorId, String occurredAt) {
    }

    void publish(DomainEvent event);

    /**
     * Emits with envelope extensions (PHASE-3 §2 retry leg): the metadata map rides the
     * outbox payload alongside the envelope fields — {@code hook.retry} events carry the
     * trigger, hook, kind, attempt, and error this way. Bindings without an extensions
     * concept collapse to {@link #publish(DomainEvent)}.
     */
    default void publish(DomainEvent event, Map<String, Object> metadata) {
        publish(event);
    }

    /** Phase 1 binding: records events in memory for test observation. */
    class Recording implements DomainEventPublisher {

        /** One captured emission: the envelope plus its extensions, when present. */
        public record Captured(DomainEvent event, Map<String, Object> metadata) {
        }

        private final List<Captured> captured = new CopyOnWriteArrayList<>();

        @Override
        public void publish(DomainEvent event) {
            publish(event, Map.of());
        }

        @Override
        public void publish(DomainEvent event, Map<String, Object> metadata) {
            captured.add(new Captured(event, Map.copyOf(metadata)));
        }

        public List<DomainEvent> events() {
            return captured.stream().map(Captured::event).toList();
        }

        public List<Captured> captured() {
            return List.copyOf(captured);
        }

        public void clear() {
            captured.clear();
        }
    }
}
