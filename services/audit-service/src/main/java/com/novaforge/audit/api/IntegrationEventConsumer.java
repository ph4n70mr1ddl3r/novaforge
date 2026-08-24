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
 * Consumes the Integration Service's family topics (PHASE-6 §3/§5/§7):
 * {@code connector.delivered}, {@code webhook.dispatched}, and
 * {@code import.progress} land in the append-only trail — deliveries are audited
 * like every platform action. Dedup rides the event id (at-least-once delivery is
 * idempotent here); the delivery/job id maps to the trail's record key.
 */
@Component
public class IntegrationEventConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(IntegrationEventConsumer.class);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final AuditStore store;

    public IntegrationEventConsumer(AuditStore store) {
        this.store = store;
    }

    @KafkaListener(topics = {"novaforge.connector", "novaforge.webhook", "novaforge.import"},
            groupId = "novaforge-audit-integration")
    public void onEvent(String payload) {
        try {
            Map<String, Object> event = MAPPER.readValue(payload, Map.class);
            store.append(
                    UUID.fromString(String.valueOf(event.get("eventId"))),
                    UUID.fromString(String.valueOf(event.get("tenantId"))),
                    String.valueOf(event.get("event")),
                    recordKey(event),
                    String.valueOf(event.get("event")),
                    UUID.nameUUIDFromBytes("integration".getBytes()),
                    Instant.parse(String.valueOf(event.get("occurredAt"))),
                    payload);
        } catch (Exception e) {
            LOG.error("invalid integration event ignored: {}", payload, e);
        }
    }

    /** The trail's record key: the delivery id, else the job id, else the event id. */
    private static UUID recordKey(Map<String, Object> event) {
        for (String key : new String[] {"deliveryId", "jobId", "record"}) {
            if (event.get(key) != null) {
                try {
                    return UUID.fromString(String.valueOf(event.get(key)));
                } catch (IllegalArgumentException ignored) {
                    // not a uuid — fall through to the event id
                }
            }
        }
        return UUID.fromString(String.valueOf(event.get("eventId")));
    }
}
