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
 * Consumes {@code novaforge.record} into the append-only trail (PHASE-3 §5): the
 * durable audit PHASE-2 §9 promised, riding the spine with dedup on event_id
 * (at-least-once delivery is idempotent here).
 */
@Component
public class RecordEventConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(RecordEventConsumer.class);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final AuditStore store;
    private final Tracer tracer;

    public RecordEventConsumer(AuditStore store, Tracer tracer) {
        this.store = store;
        this.tracer = tracer;
    }

    @KafkaListener(topics = "novaforge.record", groupId = "novaforge-audit")
    public void onEvent(ConsumerRecord<String, String> message) {
        var header = message.headers().lastHeader(EventHeaders.TRACEPARENT);
        String traceparent = header == null ? null
                : new String(header.value(), StandardCharsets.UTF_8);
        TracePropagation.inConsumerSpan(tracer, traceparent,
                "novaforge.record audit", () -> consume(message.value()));
    }

    void consume(String payload) {
        Map<String, Object> event;
        try {
            event = MAPPER.readValue(payload, Map.class);
        } catch (Exception e) {
            LOG.error("invalid record event ignored: {}", payload, e);
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
                    String.valueOf(event.get("entityId")),
                    UUID.fromString(String.valueOf(event.get("recordId"))),
                    String.valueOf(event.get("event")),
                    UUID.fromString(String.valueOf(event.get("actorId"))),
                    Instant.parse(String.valueOf(event.get("occurredAt"))),
                    payload);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            LOG.error("malformed record event ignored: {}", payload, e);
        }
    }
}
