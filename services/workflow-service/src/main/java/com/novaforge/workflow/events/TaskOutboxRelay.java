package com.novaforge.workflow.events;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.novaforge.security.EventHeaders;
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
    private final int retentionDays;

    public TaskOutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
                           @Value("${novaforge.events.relay-batch:100}") int batchSize,
                           @Value("${novaforge.events.retention-days:7}") int retentionDays) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.batchSize = batchSize;
        this.retentionDays = retentionDays;
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
                String eventType = String.valueOf(event.get("event"));
                String topic = "novaforge." + (eventType.contains(".")
                        ? eventType.substring(0, eventType.indexOf('.')) : eventType);
                ProducerRecord<String, String> record = new ProducerRecord<>(topic,
                        entry.get("tenant_id") + ":" + entry.get("task_id"), payload);
                record.headers().add(EventHeaders.EVENT_ID,
                        String.valueOf(event.get("eventId")).getBytes(StandardCharsets.UTF_8));
                record.headers().add(EventHeaders.EVENT_TYPE,
                        String.valueOf(event.get("event")).getBytes(StandardCharsets.UTF_8));
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
                LOG.error("task relay failed for outbox {} (will retry)", id, e);
                break;   // stop on first failure — order preserved within a task key
            }
        }
        if (!published.isEmpty()) {
            jdbc.batchUpdate("UPDATE wf_event_outbox SET published_at = now() WHERE id = ?",
                    published.stream().map(p -> new Object[] {p}).toList());
        }
    }

    /**
     * Retention — the outbox is transport state, not history (the spine and its
     * consumers hold the durable record): published rows older than the configured
     * window leave on a slow schedule. Unpublished rows never leave (delivery
     * first); without this the table grew forever on the busiest tenants.
     */
    @Scheduled(fixedDelayString = "${novaforge.events.retention-interval-ms:3600000}")
    public void retain() {
        int dropped = jdbc.update("""
                DELETE FROM wf_event_outbox
                 WHERE published_at IS NOT NULL
                   AND published_at < now() - (? * interval '1 day')""",
                retentionDays);
        if (dropped > 0) {
            LOG.info("outbox retention dropped {} published row(s) older than {} day(s)",
                    dropped, retentionDays);
        }
    }
}
