package com.novaforge.runtime.storage.retry;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The after-hook retry ledger (PHASE-3 §2 failure policy): {@code hook.retry} events
 * consumed off the spine claim a row (event-id dedup — at-least-once delivery collapses
 * to one retry), the scanner claims due rows and re-drives them, and terminal states
 * ({@code ok} once the hook runs clean, {@code parked} when attempts exhaust or the
 * retry can never converge) persist for inspection — a failure is never silently
 * dropped.
 */
@Repository
public class HookRetryStore {

    /** One retry as the scanner sees it: identity + bookkeeping. */
    public record PendingRetry(UUID eventId, UUID tenantId, String entityId, UUID recordId,
                               String triggerName, String hookName, String kind, int attempt) {
    }

    private final JdbcTemplate jdbc;

    public HookRetryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the event id (idempotent consume): inserts a pending row unless one
     * already exists. Returns true when this call created the row — false means a
     * redelivery and the caller skips.
     */
    public boolean claim(UUID eventId, UUID tenantId, String entityId, UUID recordId,
                         String triggerName, String hookName, String kind, int attempt,
                         Instant dueAt) {
        int inserted = jdbc.update("""
                INSERT INTO hook_retry_log (event_id, tenant_id, entity_id, record_id,
                                            trigger_name, hook_name, kind, attempt, status,
                                            next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?)
                ON CONFLICT (event_id) DO NOTHING""",
                eventId, tenantId, entityId, recordId, triggerName, hookName, kind, attempt,
                Timestamp.from(dueAt));
        return inserted > 0;
    }

    /** Claims an event that can never re-drive (script hooks): parked at consume time. */
    public void parkAtConsume(UUID eventId, UUID tenantId, String entityId, UUID recordId,
                              String triggerName, String hookName, String kind, String reason) {
        jdbc.update("""
                INSERT INTO hook_retry_log (event_id, tenant_id, entity_id, record_id,
                                            trigger_name, hook_name, kind, attempt, status,
                                            next_attempt_at, last_error)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, 'parked', now(), ?)
                ON CONFLICT (event_id) DO NOTHING""",
                eventId, tenantId, entityId, recordId, triggerName, hookName, kind, reason);
    }

    /** Due pending retries, oldest first, bounded per scanner pass. */
    public List<PendingRetry> due(int limit) {
        return jdbc.query("""
                SELECT event_id, tenant_id, entity_id, record_id, trigger_name, hook_name,
                       kind, attempt
                  FROM hook_retry_log
                 WHERE status = 'pending' AND next_attempt_at <= now()
                 ORDER BY next_attempt_at
                 LIMIT ?""",
                (rs, i) -> new PendingRetry(
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("entity_id"),
                        rs.getObject("record_id", UUID.class),
                        rs.getString("trigger_name"),
                        rs.getString("hook_name"),
                        rs.getString("kind"),
                        rs.getInt("attempt")),
                limit);
    }

    /** Terminal success: the hook ran clean. */
    public void markOk(UUID eventId) {
        jdbc.update("""
                UPDATE hook_retry_log
                   SET status = 'ok', next_attempt_at = now(), updated_at = now()
                 WHERE event_id = ?""", eventId);
    }

    /** Terminal park: attempts exhausted, or the retry can never converge. */
    public void park(UUID eventId, int attempts, String reason) {
        jdbc.update("""
                UPDATE hook_retry_log
                   SET status = 'parked', attempt = ?, next_attempt_at = now(),
                       last_error = ?, updated_at = now()
                 WHERE event_id = ?""", attempts, reason, eventId);
    }

    /** Failed attempt, budget remains: next due time is the caller's backoff. */
    public void reschedule(UUID eventId, int nextAttempt, Instant dueAt, String error) {
        jdbc.update("""
                UPDATE hook_retry_log
                   SET attempt = ?, next_attempt_at = ?, last_error = ?, updated_at = now()
                 WHERE event_id = ?""",
                nextAttempt, Timestamp.from(dueAt), error, eventId);
    }

    /** Row status by event id (test/ops surface). */
    public Map<String, Object> statusOf(UUID eventId) {
        return jdbc.queryForMap("""
                SELECT status, attempt, last_error, trigger_name, hook_name
                  FROM hook_retry_log WHERE event_id = ?""", eventId);
    }

    public long pendingCount() {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM hook_retry_log WHERE status = 'pending'", Long.class);
        return count == null ? 0 : count;
    }
}
