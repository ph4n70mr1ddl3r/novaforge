package com.novaforge.workflow.sla;

import com.novaforge.workflow.task.TaskService;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives SLA timers (PHASE-4 §6), in-process with the Workflow Service (ARCHITECTURE
 * §2.8 — escalation timers are not the Scheduler's): each pass warns once per open
 * task when {@code warnAt} elapses ({@code sla.warn}) and, at {@code dueAt}, breaches
 * — the open task goes ESCALATED, a replacement task is created for the escalation
 * role resolved at creation, {@code sla.breach} rides the outbox, and the
 * {@code novaforge.sla.breach} counter increments. Tasks without timers stay open
 * until resolved or cancelled (§6). Single-level escalation in v1 (§6).
 *
 * <p>Two entry points: the wall-clock {@link #scanOnce()} the scheduler drives, and
 * the as-of {@link #scanOnce(Instant, UUID)} the harness-facing scratch surface
 * drives (§12's clock-advanced leg — warn/breach/escalation assertions with no
 * sleeps; a scan path, never a write path, so ADR-010 #3's no-test-mode rule holds).</p>
 */
@Component
public class SlaScanner {

    private static final Logger LOG = LoggerFactory.getLogger(SlaScanner.class);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final TaskService tasks;
    private final io.micrometer.core.instrument.Counter breaches;
    private final io.micrometer.tracing.Tracer tracer;

    public SlaScanner(JdbcTemplate jdbc, TaskService tasks, MeterRegistry meters,
                      io.micrometer.tracing.Tracer tracer) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.breaches = meters.counter("novaforge.sla.breach");
        this.tracer = tracer;
    }

    @Scheduled(fixedDelayString = "${novaforge.sla.scan-interval-ms:5000}")
    public void scan() {
        scanOnce();
    }

    /** One pass — public so tests drive it deterministically instead of waiting. */
    public void scanOnce() {
        scanOnce(Instant.now(), null);
    }

    /**
     * One pass as of a governing instant, optionally scoped to one tenant — the
     * scratch surface's leg of §12: warn and breach fire exactly when the given
     * clock passes their timers, deterministically, without touching wall clock.
     * Returns the counts so suite verdicts can assert them.
     */
    public ScanCounts scanOnce(Instant asOf, UUID tenantId) {
        return new ScanCounts(warn(asOf, tenantId), breach(asOf, tenantId));
    }

    /** What one deterministic pass did — the scratch surface answers with these. */
    public record ScanCounts(int warned, int breached) {
    }

    private int warn(Instant now, UUID tenantId) {
        String sql = """
                SELECT id, tenant_id, id AS task_id, assignee, role, entity_id, record_id FROM wf_tasks
                 WHERE status = 'OPEN' AND sla_warned = false
                   AND warn_at IS NOT NULL AND warn_at <= ?""";
        List<Map<String, Object>> due = tenantId == null
                ? jdbc.queryForList(sql, Timestamp.from(now))
                : jdbc.queryForList(sql + " AND tenant_id = ?", Timestamp.from(now), tenantId);
        for (Map<String, Object> task : due) {
            jdbc.update("UPDATE wf_tasks SET sla_warned = true, updated_at = now() WHERE id = ?",
                    task.get("id"));
            emit(task, "sla.warn", null);
            LOG.info("sla warn: task {}", task.get("task_id"));
        }
        return due.size();
    }

    private int breach(Instant now, UUID tenantId) {
        String sql = """
                SELECT id, tenant_id, id AS task_id, assignee, role, type, entity_id,
                       record_id, created_by, context_ref, instance_id, escalate_to
                  FROM wf_tasks
                 WHERE status = 'OPEN' AND due_at IS NOT NULL AND due_at <= ?""";
        List<Map<String, Object>> due = tenantId == null
                ? jdbc.queryForList(sql, Timestamp.from(now))
                : jdbc.queryForList(sql + " AND tenant_id = ?", Timestamp.from(now), tenantId);
        int breached = 0;
        for (Map<String, Object> task : due) {
            int escalated = jdbc.update("""
                    UPDATE wf_tasks SET status = 'ESCALATED', updated_at = now()
                     WHERE id = ? AND status = 'OPEN'""", task.get("id"));
            if (escalated == 0) {
                continue;   // resolved between the query and the flip
            }
            breached++;
            emit(task, "task.escalated", "ESCALATED");
            emit(task, "sla.breach", "ESCALATED");
            breaches.increment();
            String escalateTo = task.get("escalate_to") == null ? null
                    : String.valueOf(task.get("escalate_to"));
            if (escalateTo != null) {
                // §6: a replacement task for the escalation role — single-level in v1.
                tasks.create((UUID) task.get("tenant_id"), (String) task.get("type"),
                        (String) task.get("entity_id"), (UUID) task.get("record_id"),
                        null, escalateTo, null, null, (UUID) task.get("created_by"),
                        (UUID) task.get("context_ref"), (UUID) task.get("instance_id"));
            }
            LOG.warn("sla breach: task {} escalated to {}", task.get("task_id"), escalateTo);
        }
        return breached;
    }

    private void emit(Map<String, Object> task, String event, String status) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("event", event);
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("taskId", String.valueOf(task.get("task_id")));
        payload.put("tenantId", String.valueOf(task.get("tenant_id")));
        payload.put("entityId", String.valueOf(task.get("entity_id")));
        payload.put("recordId", String.valueOf(task.get("record_id")));
        payload.put("assignee", task.get("assignee") == null ? ""
                : String.valueOf(task.get("assignee")));
        payload.put("role", task.get("role") == null ? "" : String.valueOf(task.get("role")));
        if (status != null) {
            payload.put("status", status);
        }
        payload.put("occurredAt", Instant.now().toString());
        String traceparent = com.novaforge.security.TracePropagation.capture(tracer);
        if (traceparent != null) {
            payload.put("traceparent", traceparent);
        }
        jdbc.update("""
                INSERT INTO wf_event_outbox (id, tenant_id, task_id, event_type, payload)
                VALUES (?, ?, ?, ?, ?::jsonb)""",
                UUID.randomUUID(), task.get("tenant_id"), task.get("task_id"), event,
                MAPPER.writeValueAsString(payload));
    }
}
