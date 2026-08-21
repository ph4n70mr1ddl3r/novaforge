package com.novaforge.audit.store;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Append-only audit persistence (PHASE-3 §5): at-least-once inserts dedup on event_id. */
@Repository
public class AuditStore {

    public record AuditEvent(UUID eventId, String entityType, String entityId, UUID recordId,
                             UUID actorId, Instant occurredAt, String payloadJson) {
    }

    private final JdbcTemplate jdbc;

    public AuditStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotent append — redelivered events collapse on the primary key. */
    public void append(UUID eventId, UUID tenantId, String entityId, UUID recordId,
                       String eventType, UUID actorId, Instant occurredAt, String payloadJson) {
        jdbc.update("""
                INSERT INTO audit_events (event_id, tenant_id, entity_id, record_id, event_type, actor_id, occurred_at, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (event_id, occurred_at) DO NOTHING""",
                eventId, tenantId, entityId, recordId, eventType, actorId,
                Timestamp.from(occurredAt), payloadJson);
    }

    public List<Map<String, Object>> forRecord(UUID tenantId, UUID recordId) {
        return jdbc.queryForList("""
                SELECT event_id, entity_id, record_id, event_type, actor_id, occurred_at, payload
                  FROM audit_events WHERE tenant_id = ? AND record_id = ?
                 ORDER BY occurred_at DESC""", tenantId, recordId);
    }

    public List<Map<String, Object>> forEntity(UUID tenantId, String entityId, int limit) {
        return jdbc.queryForList("""
                SELECT event_id, entity_id, record_id, event_type, actor_id, occurred_at, payload
                  FROM audit_events WHERE tenant_id = ? AND entity_id = ?
                 ORDER BY occurred_at DESC LIMIT ?""", tenantId, entityId, limit);
    }
}
