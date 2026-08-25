package com.novaforge.runtime.events;

import com.novaforge.runtime.storage.outbox.OutboxStore;
import com.novaforge.runtime.storage.outbox.OutboxStore.OutboxEntry;
import com.novaforge.security.EventHeaders;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The outbox relay (PHASE-3 §4): publishes committed outbox rows to the Kafka spine
 * at-least-once, then marks them published. Topic naming: {@code novaforge.<family>}
 * ({@code record.created} → {@code novaforge.record}) keyed
 * {@code tenantId:entityId:recordId} — the §13 Q3 pin: entity_id is the
 * entity-definition id, and the record id keeps the key per-record so every record's
 * own events serialize. Event id, tenant, and the captured W3C {@code traceparent}
 * (ARCHITECTURE.md §6 — lifted from the append-time payload into the header) ride
 * the record headers for consumer dedup and trace linking.
 */
@Component
public class KafkaOutboxRelay {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaOutboxRelay.class);

    private final OutboxStore outbox;
    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final int batchSize;

    public KafkaOutboxRelay(OutboxStore outbox, KafkaTemplate<String, String> kafka,
                            @Value("${novaforge.events.relay-batch:100}") int batchSize) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${novaforge.events.relay-interval-ms:500}")
    public void relay() {
        List<OutboxEntry> entries = outbox.unpublished(batchSize);
        if (entries.isEmpty()) {
            return;
        }
        List<UUID> published = new ArrayList<>();
        for (OutboxEntry entry : entries) {
            try {
                Map<String, Object> payload = mapper.readValue(entry.payloadJson(), Map.class);
                ProducerRecord<String, String> record = new ProducerRecord<>(
                        topicFor(entry.eventType()), keyFor(entry), entry.payloadJson());
                stampHeaders(record, payload);
                kafka.send(record).get();
                published.add(entry.id());
            } catch (Exception e) {
                LOG.error("relay failed for outbox {} (will retry)", entry.id(), e);
                break;   // stop on first failure — order preserved within a record key
            }
        }
        outbox.markPublished(published);
    }

    /** record.created → novaforge.record (family topics, §4 topology). */
    static String topicFor(String eventType) {
        String family = eventType.contains(".")
                ? eventType.substring(0, eventType.indexOf('.')) : eventType;
        return "novaforge." + family;
    }

    /**
     * The §4 partition key: {@code tenantId:entityId:recordId} — true per-record
     * ordering (§13 Q3). Non-record families riding this outbox (hook.retry,
     * permission.*) carry the entity key and a record-ish id the same way, so the
     * shape holds uniformly.
     */
    static String keyFor(OutboxEntry entry) {
        return entry.tenantId() + ":" + entry.entityId() + ":" + entry.recordId();
    }

    /** The shared spine headers (PHASE-3 §4): event id/type/tenant + trace link. */
    static void stampHeaders(ProducerRecord<String, String> record, Map<String, Object> payload) {
        record.headers().add(EventHeaders.EVENT_ID,
                String.valueOf(payload.get("eventId")).getBytes(StandardCharsets.UTF_8));
        Object eventType = payload.get("event");
        if (eventType != null) {
            record.headers().add(EventHeaders.EVENT_TYPE,
                    String.valueOf(eventType).getBytes(StandardCharsets.UTF_8));
        }
        record.headers().add(EventHeaders.TENANT_ID,
                String.valueOf(payload.get("tenantId")).getBytes(StandardCharsets.UTF_8));
        if (payload.get("traceparent") instanceof String traceparent && !traceparent.isBlank()) {
            record.headers().add(EventHeaders.TRACEPARENT,
                    traceparent.getBytes(StandardCharsets.UTF_8));
        }
    }
}
