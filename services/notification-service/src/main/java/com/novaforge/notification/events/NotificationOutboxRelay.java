package com.novaforge.notification.events;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The notification outbox relay (PHASE-4 §2): {@code notification.delivered} rows
 * publish to {@code novaforge.notification} at-least-once, tenant-scoped keys,
 * consumers deduping on the event id.
 */
@Component
public class NotificationOutboxRelay {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationOutboxRelay.class);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final int batchSize;

    public NotificationOutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
                                   @Value("${novaforge.events.relay-batch:100}") int batchSize) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${novaforge.events.relay-interval-ms:500}")
    public void relay() {
        List<Map<String, Object>> entries = jdbc.queryForList("""
                SELECT id, tenant_id, event_type, payload FROM nf_event_outbox
                 WHERE published_at IS NULL ORDER BY created_at LIMIT ?""", batchSize);
        if (entries.isEmpty()) {
            return;
        }
        List<UUID> published = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            UUID id = (UUID) entry.get("id");
            try {
                String payload = String.valueOf(entry.get("payload"));
                Map<String, Object> event = MAPPER.readValue(payload, Map.class);
                String eventType = String.valueOf(event.get("event"));
                String topic = "novaforge." + (eventType.contains(".")
                        ? eventType.substring(0, eventType.indexOf('.')) : eventType);
                ProducerRecord<String, String> record = new ProducerRecord<>(topic,
                        String.valueOf(entry.get("tenant_id")), payload);
                record.headers().add("X-Event-Id",
                        String.valueOf(event.get("eventId")).getBytes());
                record.headers().add("X-Event-Type", eventType.getBytes());
                kafka.send(record).get();
                published.add(id);
            } catch (Exception e) {
                LOG.error("notification relay failed for outbox {} (will retry)", id, e);
                break;
            }
        }
        if (!published.isEmpty()) {
            jdbc.batchUpdate("UPDATE nf_event_outbox SET published_at = now() WHERE id = ?",
                    published.stream().map(p -> new Object[] {p}).toList());
        }
    }
}
