package com.novaforge.notification.events;

import com.novaforge.security.EventHeaders;
import com.novaforge.security.TracePropagation;
import io.micrometer.tracing.Tracer;
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
    private final int retentionDays;

    public NotificationOutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
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
                LOG.error("notification relay failed for outbox {} (will retry)", id, e);
                break;
            }
        }
        if (!published.isEmpty()) {
            jdbc.batchUpdate("UPDATE nf_event_outbox SET published_at = now() WHERE id = ?",
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
                DELETE FROM nf_event_outbox
                 WHERE published_at IS NOT NULL
                   AND published_at < now() - (? * interval '1 day')""",
                retentionDays);
        if (dropped > 0) {
            LOG.info("outbox retention dropped {} published row(s) older than {} day(s)",
                    dropped, retentionDays);
        }
        // the email delivery markers ride the same window: a marker older than the
        // retention period belongs to a key nothing will replay again (each scheduler
        // window fires once), so it is pure growth otherwise — one row per recipient
        // per keyed send, forever
        int markers = jdbc.update("""
                DELETE FROM nf_email_deliveries
                 WHERE delivered_at < now() - (? * interval '1 day')""",
                retentionDays);
        if (markers > 0) {
            LOG.info("email delivery markers dropped {} row(s) older than {} day(s)",
                    markers, retentionDays);
        }
    }
}
