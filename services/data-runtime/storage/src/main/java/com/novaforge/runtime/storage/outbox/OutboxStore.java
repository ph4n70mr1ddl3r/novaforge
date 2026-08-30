package com.novaforge.runtime.storage.outbox;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * The transactional outbox (PHASE-3 §4): events written in the record transaction,
 * published to Kafka by the relay at-least-once — consumers dedup on
 * (event_id, consumer).
 */
@Repository
public class OutboxStore {

    /** One unpublished event ready for the relay. */
    public record OutboxEntry(UUID id, UUID tenantId, String entityId, UUID recordId,
                              String eventType, String payloadJson) {
    }

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;

    public OutboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void append(UUID id, UUID tenantId, String entityId, UUID recordId,
                       String eventType, Map<String, Object> payload) {
        jdbc.update("""
                INSERT INTO event_outbox (id, tenant_id, entity_id, record_id, event_type, payload)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)""",
                id, tenantId, entityId, recordId, eventType, MAPPER.writeValueAsString(payload));
    }

    /** Claims up to {@code batchSize} unpublished rows oldest-first. */
    public List<OutboxEntry> unpublished(int batchSize) {
        return jdbc.query("""
                SELECT id, tenant_id, entity_id, record_id, event_type, payload
                  FROM event_outbox WHERE published_at IS NULL
                 ORDER BY created_at LIMIT ?""",
                (rs, i) -> new OutboxEntry(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("entity_id"),
                        rs.getObject("record_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("payload")),
                batchSize);
    }

    public void markPublished(List<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        Timestamp now = new Timestamp(System.currentTimeMillis());
        jdbc.batchUpdate("UPDATE event_outbox SET published_at = ? WHERE id = ?",
                ids.stream().map(id -> new Object[] {now, id}).toList());
    }

    public long unpublishedCount() {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM event_outbox WHERE published_at IS NULL", Long.class);
        return count == null ? 0 : count;
    }

    /**
     * Retention — the outbox is transport state, not history (the spine and its
     * consumers hold the durable record): published rows older than the window
     * leave. Unpublished rows never leave (delivery first); without this the
     * table — the platform's highest-volume outbox — grew forever.
     */
    public int retainPublishedOlderThanDays(int days) {
        return jdbc.update("""
                DELETE FROM event_outbox
                 WHERE published_at IS NOT NULL
                   AND published_at < now() - (? * interval '1 day')""", days);
    }
}
