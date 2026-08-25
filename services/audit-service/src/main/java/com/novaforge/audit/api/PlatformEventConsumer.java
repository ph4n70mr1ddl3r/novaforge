package com.novaforge.audit.api;

import com.novaforge.audit.store.AuditStore;
import com.novaforge.security.EventHeaders;
import com.novaforge.security.TracePropagation;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The audit-side families beyond record writes (ARCHITECTURE.md §5 item 5):
 * definition publishes ({@code metadata.published}), permission changes
 * ({@code permission.*} — the platform-admin API's writes, PHASE-2 §10/PHASE-3 §4),
 * auth events ({@code auth.*} — the deployed Keycloak event listener, PHASE-3 §5),
 * the human-task plane ({@code task.*}/{@code sla.*} — PHASE-4 §5/§13), scheduler
 * fires ({@code scheduler.job.run} — PHASE-4 §7), and notification deliveries
 * ({@code notification.delivered} — PHASE-5 §7). One consumer, one envelope
 * contract: {@code eventId}/{@code tenantId}/{@code event}/{@code occurredAt} plus
 * a family-specific record key (taskId, userId, appId, jobId, deliveryId — else the
 * event id). Dedup rides the event id: at-least-once delivery is idempotent here.
 */
@Component
public class PlatformEventConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformEventConsumer.class);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The record-key candidates, family-agnostic, first parseable UUID wins. */
    private static final String[] RECORD_KEYS = {
            "recordId", "taskId", "userId", "appId", "deliveryId", "jobId", "eventId"};

    private final AuditStore store;
    private final Tracer tracer;

    public PlatformEventConsumer(AuditStore store, Tracer tracer) {
        this.store = store;
        this.tracer = tracer;
    }

    @KafkaListener(topics = {"novaforge.metadata", "novaforge.permission", "novaforge.auth",
            "novaforge.task", "novaforge.sla", "novaforge.scheduler", "novaforge.notification"},
            groupId = "novaforge-audit-platform")
    public void onEvent(ConsumerRecord<String, String> message) {
        var header = message.headers().lastHeader(EventHeaders.TRACEPARENT);
        String traceparent = header == null ? null
                : new String(header.value(), StandardCharsets.UTF_8);
        String topic = message.topic();
        TracePropagation.inConsumerSpan(tracer, traceparent,
                topic + " audit", () -> consume(message.value()));
    }

    void consume(String payload) {
        try {
            Map<String, Object> event = MAPPER.readValue(payload, Map.class);
            String family = String.valueOf(event.get("event"));
            int dot = family.indexOf('.');
            String source = dot > 0 ? family.substring(0, dot) : family;
            store.append(
                    uuid(event, "eventId"),
                    uuid(event, "tenantId"),
                    String.valueOf(event.getOrDefault("entityId", "platform." + source)),
                    recordKey(event),
                    family,
                    actorId(event, source),
                    Instant.parse(String.valueOf(event.getOrDefault("occurredAt",
                            event.getOrDefault("publishedAt", Instant.now().toString())))),
                    payload);
        } catch (Exception e) {
            LOG.error("invalid platform event ignored: {}", payload, e);
        }
    }

    /** The trail's record key: the family's own record id, else the event id. */
    private static UUID recordKey(Map<String, Object> event) {
        for (String key : RECORD_KEYS) {
            Object value = event.get(key);
            if (value != null) {
                try {
                    return UUID.fromString(String.valueOf(value));
                } catch (IllegalArgumentException notAUuid) {
                    // family carries a name (scheduler's job label) — keep looking
                }
            }
        }
        throw new IllegalArgumentException("no uuid record key in event");
    }

    /** The acting user when the family carries one; the family's system identity otherwise. */
    private static UUID actorId(Map<String, Object> event, String source) {
        Object actor = event.get("actorId");
        if (actor != null) {
            try {
                return UUID.fromString(String.valueOf(actor));
            } catch (IllegalArgumentException notAUuid) {
                // fall through to the system identity
            }
        }
        return UUID.nameUUIDFromBytes(("audit:" + source).getBytes());
    }

    private static UUID uuid(Map<String, Object> event, String key) {
        return UUID.fromString(String.valueOf(event.get(key)));
    }
}
