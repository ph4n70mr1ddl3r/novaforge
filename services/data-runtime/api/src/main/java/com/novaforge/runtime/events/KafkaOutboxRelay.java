package com.novaforge.runtime.events;

import com.novaforge.runtime.storage.outbox.OutboxStore;
import com.novaforge.runtime.storage.outbox.OutboxStore.OutboxEntry;
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
 * {@code tenantId:recordId} for per-record ordering; event id and tenant ride
 * headers for consumer dedup.
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
                String topic = topicFor(entry.eventType());
                String key = entry.tenantId() + ":" + entry.recordId();
                ProducerRecord<String, String> record =
                        new ProducerRecord<>(topic, key, entry.payloadJson());
                record.headers().add("X-Event-Id", String.valueOf(payload.get("eventId")).getBytes());
                record.headers().add("X-Event-Type", entry.eventType().getBytes());
                record.headers().add("X-Tenant-Id", entry.tenantId().toString().getBytes());
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
}
