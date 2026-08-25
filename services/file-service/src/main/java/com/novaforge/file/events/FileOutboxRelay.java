package com.novaforge.file.events;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The file outbox relay (PHASE-6 §8, the PHASE-4 §2 pattern):
 * {@code file.completed} and {@code file.quarantined} publish to the
 * {@code novaforge.file} family topic at-least-once, tenant-scoped keys — the
 * quarantine audit leg rides it (download blocked at the service, the event
 * lands in the append-only trail). Consumers dedupe on the event id.
 */
@Component
public class FileOutboxRelay {

    private static final Logger LOG = LoggerFactory.getLogger(FileOutboxRelay.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final int batchSize;

    public FileOutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
                                  @Value("${novaforge.events.relay-batch:100}") int batchSize) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${novaforge.events.relay-interval-ms:500}")
    public void relay() {
        List<Map<String, Object>> entries = jdbc.queryForList("""
                SELECT id, tenant_id, event_type, payload FROM fl_event_outbox
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
                String key = String.valueOf(entry.get("tenant_id"));
                if (eventType.startsWith("import.")) {
                    key = key + ":" + event.get("jobId");   // per-job ordering (§2/§7)
                }
                ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, payload);
                record.headers().add(EventHeaders.EVENT_ID,
                        String.valueOf(event.get("eventId")).getBytes(StandardCharsets.UTF_8));
                record.headers().add(EventHeaders.EVENT_TYPE,
                        eventType.getBytes(StandardCharsets.UTF_8));
                record.headers().add(EventHeaders.TENANT_ID,
                        String.valueOf(entry.get("tenant_id")).getBytes(StandardCharsets.UTF_8));
                if (event.get("traceparent") instanceof String traceparent
                        && !traceparent.isBlank()) {
                    record.headers().add(EventHeaders.TRACEPARENT,
                            traceparent.getBytes(StandardCharsets.UTF_8));
                }
                kafka.send(record).get();
                published.add(id);
            } catch (Exception e) {
                LOG.error("integration relay failed for outbox {} (will retry)", id, e);
                break;
            }
        }
        if (!published.isEmpty()) {
            jdbc.batchUpdate("UPDATE fl_event_outbox SET published_at = now() WHERE id = ?",
                    published.stream().map(p -> new Object[] {p}).toList());
        }
    }
}
