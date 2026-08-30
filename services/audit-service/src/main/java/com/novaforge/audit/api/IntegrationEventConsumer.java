package com.novaforge.audit.api;

import com.novaforge.audit.store.AuditStore;
import com.novaforge.security.EventHeaders;
import com.novaforge.security.TracePropagation;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
    private final Tracer tracer;

    public IntegrationEventConsumer(AuditStore store, Tracer tracer) {
        this.store = store;
        this.tracer = tracer;
    }

    @KafkaListener(topics = {"novaforge.connector", "novaforge.webhook", "novaforge.import"},
            groupId = "novaforge-audit-integration")
    public void onEvent(ConsumerRecord<String, String> message) {
        var header = message.headers().lastHeader(EventHeaders.TRACEPARENT);
        String traceparent = header == null ? null
                : new String(header.value(), StandardCharsets.UTF_8);
        TracePropagation.inConsumerSpan(tracer, traceparent,
                "integration family audit", () -> consume(message.value()));
    }

    void consume(String payload) {
        Map<String, Object> event;
        try {
            event = MAPPER.readValue(payload, Map.class);
        } catch (Exception e) {
            LOG.error("invalid integration event ignored: {}", payload, e);
            return;   // unparseable — no redelivery can fix it
        }
        // Processing failures propagate: the store's insert failing (pool exhaustion,
        // failover, a serialization error) must redeliver — committing the offset over
        // a dropped append would leave a permanent, silent hole in the trail. Only
        // envelope-shape errors are terminal; the append's (event_id, occurred_at)
        // dedupe collapses the replay.
        try {
            store.append(
                    UUID.fromString(String.valueOf(event.get("eventId"))),
                    UUID.fromString(String.valueOf(event.get("tenantId"))),
                    String.valueOf(event.get("event")),
                    recordKey(event),
                    String.valueOf(event.get("event")),
                    UUID.nameUUIDFromBytes("integration".getBytes()),
                    Instant.parse(String.valueOf(event.get("occurredAt"))),
                    payload);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            LOG.error("malformed integration event ignored: {}", payload, e);
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
