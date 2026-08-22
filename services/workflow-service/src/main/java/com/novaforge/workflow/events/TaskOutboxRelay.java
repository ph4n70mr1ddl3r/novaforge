package com.novaforge.workflow.events;

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
 * The workflow outbox relay (PHASE-4 §2): publishes committed {@code task.*} rows to
 * {@code novaforge.task} at-least-once, keyed {@code tenantId:taskId} for per-task
 * ordering — a task's created → warn/breach → terminal transitions serialize, and a
 * delegation chain rides each delegate task's own key (contextRef-linked). Consumers
 * dedupe on the payload's event id.
 */
@Component
public class TaskOutboxRelay {

    private static final Logger LOG = LoggerFactory.getLogger(TaskOutboxRelay.class);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final int batchSize;

    public TaskOutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
                           @Value("${novaforge.events.relay-batch:100}") int batchSize) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${novaforge.events.relay-interval-ms:500}")
    public void relay() {
        List<Map<String, Object>> entries = jdbc.queryForList("""
                SELECT id, tenant_id, task_id, payload FROM wf_event_outbox
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
                ProducerRecord<String, String> record = new ProducerRecord<>("novaforge.task",
                        entry.get("tenant_id") + ":" + entry.get("task_id"), payload);
                record.headers().add("X-Event-Id",
                        String.valueOf(event.get("eventId")).getBytes());
                record.headers().add("X-Event-Type",
                        String.valueOf(event.get("event")).getBytes());
                record.headers().add("X-Tenant-Id",
                        String.valueOf(entry.get("tenant_id")).getBytes());
                kafka.send(record).get();
                published.add(id);
            } catch (Exception e) {
                LOG.error("task relay failed for outbox {} (will retry)", id, e);
                break;   // stop on first failure — order preserved within a task key
            }
        }
        if (!published.isEmpty()) {
            jdbc.batchUpdate("UPDATE wf_event_outbox SET published_at = now() WHERE id = ?",
                    published.stream().map(p -> new Object[] {p}).toList());
        }
    }
}
