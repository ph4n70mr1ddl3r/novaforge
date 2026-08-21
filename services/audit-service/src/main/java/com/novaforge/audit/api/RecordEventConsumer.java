package com.novaforge.audit.api;

import com.novaforge.audit.store.AuditStore;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code novaforge.record} into the append-only trail (PHASE-3 §5): the
 * durable audit PHASE-2 §9 promised, riding the spine with dedup on event_id
 * (at-least-once delivery is idempotent here).
 */
@Component
public class RecordEventConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(RecordEventConsumer.class);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final AuditStore store;

    public RecordEventConsumer(AuditStore store) {
        this.store = store;
    }

    @KafkaListener(topics = "novaforge.record", groupId = "novaforge-audit")
    public void onEvent(String payload) {
        try {
            Map<String, Object> event = MAPPER.readValue(payload, Map.class);
            store.append(
                    UUID.fromString(String.valueOf(event.get("eventId"))),
                    UUID.fromString(String.valueOf(event.get("tenantId"))),
                    String.valueOf(event.get("entityId")),
                    UUID.fromString(String.valueOf(event.get("recordId"))),
                    String.valueOf(event.get("event")),
                    UUID.fromString(String.valueOf(event.get("actorId"))),
                    Instant.parse(String.valueOf(event.get("occurredAt"))),
                    payload);
        } catch (Exception e) {
            LOG.error("invalid record event ignored: {}", payload, e);
        }
    }
}
