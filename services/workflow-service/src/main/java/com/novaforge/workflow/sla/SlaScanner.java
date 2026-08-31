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
 * <p>SLA metrics feed the Grafana baseline (§6): warn and breach counters, both
 * labeled per app — derived from the task's app-qualified entity key
 * ({@code App.Entity}); a task without one (a process-keyed BPMN bridge task) labels
 * with the raw value rather than inventing a bucket.</p>
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
    private final MeterRegistry meters;
    private final io.micrometer.tracing.Tracer tracer;
    private final com.novaforge.workflow.roles.RoleLookup roles;
    /** One transaction per task's warn/breach block: the flip, its events, and the
     *  replacement commit together or not at all — a crash between the statements
     *  previously left a task terminalized with no replacement and no retry (a
     *  suspended approval wedged permanently). */
    private final org.springframework.transaction.support.TransactionTemplate perTask;

    public SlaScanner(JdbcTemplate jdbc, TaskService tasks, MeterRegistry meters,
                      io.micrometer.tracing.Tracer tracer,
                      org.springframework.transaction.PlatformTransactionManager transactions,
                      com.novaforge.workflow.roles.RoleLookup roles) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.meters = meters;
        this.tracer = tracer;
        this.roles = roles;
        this.perTask = new org.springframework.transaction.support.TransactionTemplate(transactions);
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
        int flipped = 0;
        for (Map<String, Object> task : due) {
            // conditional flip: a second replica reading the same due row must not
            // double-warn (the breach path guards the same way)
            int warned = jdbc.update("""
                    UPDATE wf_tasks SET sla_warned = true, updated_at = now()
                     WHERE id = ? AND sla_warned = false""", task.get("id"));
            if (warned == 0) {
                continue;
            }
            emit(task, "sla.warn", null);
            counted("novaforge.sla.warn", appOf(task.get("entity_id")));
            LOG.info("sla warn: task {}", task.get("task_id"));
            flipped++;
        }
        // the FLIPPED count, not the selected count — a row another replica (or a
        // resolution between query and flip) already claimed emits nothing and must
        // not count (breach reports the same semantics; the scratch surface's
        // `warned` answer used to over-report)
        return flipped;
    }

    private int breach(Instant now, UUID tenantId) {
        String sql = """
                SELECT id, tenant_id, id AS task_id, assignee, role, type, entity_id,
                       record_id, created_by, context_ref, instance_id, escalate_to,
                       notify_on
                  FROM wf_tasks
                 WHERE status = 'OPEN' AND sla_breached = false
                   AND due_at IS NOT NULL AND due_at <= ?""";
        List<Map<String, Object>> due = tenantId == null
                ? jdbc.queryForList(sql, Timestamp.from(now))
                : jdbc.queryForList(sql + " AND tenant_id = ?", Timestamp.from(now), tenantId);
        int breached = 0;
        for (Map<String, Object> task : due) {
            Integer done = perTask.execute(status -> breachOne(task));
            if (done != null && done > 0) {
                breached++;
            }
        }
        return breached;
    }

    /**
     * The stay-OPEN breach's one-shot fence (V7): true when this pass flipped the
     * flag (the breach is ours to emit), false when a concurrent pass already did —
     * the warn path's conditional flip, applied to the branches that cannot terminalize.
     */
    private boolean markBreached(Map<String, Object> task) {
        return jdbc.update("""
                UPDATE wf_tasks SET sla_breached = true, updated_at = now()
                 WHERE id = ? AND sla_breached = false""", task.get("id")) > 0;
    }

    /** One task's breach block, atomic: flip + events + replacement together. */
    private Integer breachOne(Map<String, Object> task) {        String escalateTo = task.get("escalate_to") == null ? null
                : String.valueOf(task.get("escalate_to"));
        if (escalateTo == null) {
            // notify-only breach (§6's "escalate, notify, or both"): ESCALATED is
            // terminal and wf_tasks.resolve can never act on it — terminalizing an
            // approval here wedged the suspended instance forever with no surface to
            // resume it. The task stays OPEN (visible, resolvable) and the breach
            // rides the spine exactly once — the conditional flag flip is the
            // re-fire fence (V7): a still-open, still-overdue row without it was
            // re-emitted every scanner pass, each with a fresh event id no consumer
            // dedupe could collapse.
            if (!markBreached(task)) {
                return 0;
            }
            emit(task, "sla.breach", "OPEN");
            counted("novaforge.sla.breach", appOf(task.get("entity_id")));
            LOG.warn("sla breach without escalation target: task {} stays OPEN "
                    + "(notify-only SLA, still resolvable)", task.get("task_id"));
            return 0;
        }
        if (!escalationTargetReachable((UUID) task.get("tenant_id"), escalateTo)) {
            // the target role has no holders in the tenant (a typo'd ghost, or a
            // role emptied since authoring): a replacement addressed to it would be
            // an OPEN task no inbox ever matches — the same permanent wedge as no
            // target at all. The task stays OPEN and resolvable; the breach rides
            // the spine once (the V7 flag) so the misconfiguration is visible
            // without a per-pass flood.
            if (!markBreached(task)) {
                return 0;
            }
            emit(task, "sla.breach", "OPEN");
            counted("novaforge.sla.breach", appOf(task.get("entity_id")));
            LOG.warn("sla breach escalation target {} has no holders in tenant {} — task {} "
                    + "stays OPEN (fix the role binding)", escalateTo, task.get("tenant_id"),
                    task.get("task_id"));
            return 0;
        }
        int escalated = jdbc.update("""
                UPDATE wf_tasks SET status = 'ESCALATED', updated_at = now()
                 WHERE id = ? AND status = 'OPEN'""", task.get("id"));
        if (escalated == 0) {
            return 0;   // resolved between the query and the flip
        }
        emit(task, "task.escalated", "ESCALATED");
        // §6's onBreach.notify: an authored "notify: false" escalation still
        // rides the spine (metrics, audit) but carries the flag — the
        // Notification Service skips the sla-warning fan-out on it
        boolean notifyOn = !Boolean.FALSE.equals(task.get("notify_on"));
        emit(task, "sla.breach", "ESCALATED",
                notifyOn ? Map.of() : Map.of("notify", false));
        counted("novaforge.sla.breach", appOf(task.get("entity_id")));
        // §6: a replacement task for the escalation role — single-level in v1.
        tasks.create((UUID) task.get("tenant_id"), (String) task.get("type"),
                (String) task.get("entity_id"), (UUID) task.get("record_id"),
                null, escalateTo, null, null, (UUID) task.get("created_by"),
                (UUID) task.get("context_ref"), (UUID) task.get("instance_id"));
        LOG.warn("sla breach: task {} escalated to {}", task.get("task_id"), escalateTo);
        return 1;
    }

    /**
     * The escalation-target fence: an unheld role produces an unmatchable
     * replacement. Unknown (runtime unreachable) answers as held — availability of
     * the breach path beats the fence.
     */
    private boolean escalationTargetReachable(UUID tenantId, String escalateTo) {
        try {
            List<UUID> holders = roles.holdersOf(tenantId, escalateTo);
            return holders == null || !holders.isEmpty();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** The §6 metrics: warn/breach counters labeled per app (the Grafana feed). */
    private void counted(String name, String app) {
        io.micrometer.core.instrument.Counter.builder(name)
                .tag("app", app)
                .register(meters)
                .increment();
    }

    /** The app of an app-qualified entity key ({@code Erp.Invoice} → {@code Erp}). */
    static String appOf(Object entityKey) {
        String key = entityKey == null ? "" : String.valueOf(entityKey);
        int dot = key.indexOf('.');
        return dot > 0 ? key.substring(0, dot) : (key.isBlank() ? "unknown" : key);
    }

    private void emit(Map<String, Object> task, String event, String status) {
        emit(task, event, status, Map.of());
    }

    private void emit(Map<String, Object> task, String event, String status,
                      Map<String, Object> extra) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.putAll(extra);
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
