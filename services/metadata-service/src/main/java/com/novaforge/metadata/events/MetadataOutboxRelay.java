package com.novaforge.metadata.events;

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
 * The publish outbox relay (the PHASE-4 §2 pattern): {@code metadata.published}
 * envelopes ride the {@code novaforge.metadata} family topic at-least-once, keyed
 * {@code tenantId:appId} (consumer ordering per app). The outbox row commits with the
 * version it announces; this relay delivers it and marks it published — a broker
 * outage delays the announcement (the row stays, delivery retries) instead of holding
 * the publish transaction's connection for the send timeout or emitting a phantom
 * event for a rolled-back version. Consumers dedupe on the event id.
 */
@Component
public class MetadataOutboxRelay {

    private static final Logger LOG = LoggerFactory.getLogger(MetadataOutboxRelay.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final int batchSize;
    private final int retentionDays;

    public MetadataOutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
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
                SELECT id, tenant_id, app_id, payload, traceparent FROM md_event_outbox
                 WHERE published_at IS NULL ORDER BY created_at LIMIT ?""", batchSize);
        if (entries.isEmpty()) {
            return;
        }
        List<UUID> published = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            UUID id = (UUID) entry.get("id");
            UUID tenantId = (UUID) entry.get("tenant_id");
            UUID appId = (UUID) entry.get("app_id");
            try {
                String payload = String.valueOf(entry.get("payload"));
                Map<String, Object> event = MAPPER.readValue(payload, Map.class);
                ProducerRecord<String, String> record = new ProducerRecord<>(
                        MetadataPublishEventPublisher.TOPIC, tenantId + ":" + appId, payload);
                record.headers().add(EventHeaders.EVENT_ID,
                        String.valueOf(event.get("eventId")).getBytes(StandardCharsets.UTF_8));
                record.headers().add(EventHeaders.EVENT_TYPE,
                        String.valueOf(event.get("event")).getBytes(StandardCharsets.UTF_8));
                record.headers().add(EventHeaders.TENANT_ID,
                        tenantId.toString().getBytes(StandardCharsets.UTF_8));
                if (entry.get("traceparent") instanceof String traceparent
                        && !traceparent.isBlank() && !"null".equals(traceparent)) {
                    record.headers().add(EventHeaders.TRACEPARENT,
                            traceparent.getBytes(StandardCharsets.UTF_8));
                }
                kafka.send(record).get();
                published.add(id);
            } catch (Exception e) {
                LOG.error("metadata publish relay failed for outbox {} (will retry)", id, e);
                break;
            }
        }
        if (!published.isEmpty()) {
            jdbc.batchUpdate("UPDATE md_event_outbox SET published_at = now() WHERE id = ?",
                    published.stream().map(p -> new Object[] {p}).toList());
        }
    }

    /**
     * Retention — the outbox is transport state, not history (the spine and its
     * consumers hold the durable record): published rows older than the configured
     * window leave on a slow schedule; unpublished rows never leave (delivery first).
     */
    @Scheduled(fixedDelayString = "${novaforge.events.retention-interval-ms:3600000}")
    public void retain() {
        int dropped = jdbc.update("""
                DELETE FROM md_event_outbox
                 WHERE published_at IS NOT NULL
                   AND published_at < now() - (? * interval '1 day')""",
                retentionDays);
        if (dropped > 0) {
            LOG.info("publish outbox retention dropped {} published row(s) older than {} day(s)",
                    dropped, retentionDays);
        }
    }
}
