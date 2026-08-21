package com.novaforge.runtime.engine.event;

import java.util.List;
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

    /** Phase 1 binding: records events in memory for test observation. */
    class Recording implements DomainEventPublisher {

        private final List<DomainEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void publish(DomainEvent event) {
            events.add(event);
        }

        public List<DomainEvent> events() {
            return List.copyOf(events);
        }

        public void clear() {
            events.clear();
        }
    }
}
