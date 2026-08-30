package com.novaforge.workflow.task;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.workflow.roles.RoleLookup;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The task lifecycle (PHASE-4 §5): create/claim/resolve/delegate/reassign/cancel with
 * server-side access checks — assignee, the task's role holders, or platform admin
 * (§13) — and a {@code task.*} event per terminal status riding the transactional
 * outbox ({@code task.created/assigned/approved/rejected/delegated/escalated/
 * cancelled}; no COMPLETED status exists to event). Delegation creates a replacement
 * task; the original goes DELEGATED with the chain preserved via {@code contextRef}.
 */
@Service
public class TaskService {

    private static final Logger LOG = LoggerFactory.getLogger(TaskService.class);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final TaskStore tasks;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final RoleLookup roles;
    private final org.springframework.beans.factory.ObjectProvider<SuspensionService> suspensions;
    private final org.springframework.beans.factory.ObjectProvider<com.novaforge.workflow.process.ProcessTaskBridge> processTasks;
    private final io.micrometer.tracing.Tracer tracer;

    public TaskService(TaskStore tasks, org.springframework.jdbc.core.JdbcTemplate jdbc,
                       RoleLookup roles,
                       org.springframework.beans.factory.ObjectProvider<SuspensionService> suspensions,
                       org.springframework.beans.factory.ObjectProvider<com.novaforge.workflow.process.ProcessTaskBridge> processTasks,
                       io.micrometer.tracing.Tracer tracer) {
        this.tasks = tasks;
        this.jdbc = jdbc;
        this.roles = roles;
        this.suspensions = suspensions;
        this.processTasks = processTasks;
        this.tracer = tracer;
    }

    /** Creates a task (OPEN) with its lifecycle events on the outbox. */
    @Transactional
    public TaskStore.Task create(UUID tenantId, String type, String entityId, UUID recordId,
                                 UUID assignee, String role, Instant dueAt, Instant warnAt,
                                 UUID createdBy, UUID contextRef) {
        return create(tenantId, type, entityId, recordId, assignee, role, dueAt, warnAt,
                createdBy, contextRef, null);
    }

    /** The suspension-aware create: approval tasks link their suspended instance. */
    @Transactional
    public TaskStore.Task create(UUID tenantId, String type, String entityId, UUID recordId,
                                 UUID assignee, String role, Instant dueAt, Instant warnAt,
                                 UUID createdBy, UUID contextRef, UUID instance) {
        return create(tenantId, type, entityId, recordId, assignee, role, dueAt, warnAt,
                createdBy, contextRef, instance, null);
    }

    /** Full create: timers plus the escalation target the scanner acts on (§6). */
    @Transactional
    public TaskStore.Task create(UUID tenantId, String type, String entityId, UUID recordId,
                                 UUID assignee, String role, Instant dueAt, Instant warnAt,
                                 UUID createdBy, UUID contextRef, UUID instance,
                                 String escalateTo) {
        return create(tenantId, type, entityId, recordId, assignee, role, dueAt, warnAt,
                createdBy, contextRef, instance, escalateTo, true);
    }

    /**
     * Full create with the breach-notify switch (§6's {@code onBreach.notify}):
     * {@code false} authors an escalation that never fans out as an sla-warning —
     * the flag stamps the task and the breach event's payload.
     */
    @Transactional
    public TaskStore.Task create(UUID tenantId, String type, String entityId, UUID recordId,
                                 UUID assignee, String role, Instant dueAt, Instant warnAt,
                                 UUID createdBy, UUID contextRef, UUID instance,
                                 String escalateTo, boolean notifyOn) {
        if (assignee == null && role == null) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "a task requires an assignee or a role");
        }
        if (type == null || !(type.equals("approval") || type.equals("todo"))) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "task type must be approval or todo: " + type);
        }
        TaskStore.Task task = new TaskStore.Task(UUID.randomUUID(), tenantId, type, entityId,
                recordId, assignee, role, "OPEN", null, dueAt, warnAt, false, createdBy,
                contextRef == null ? null : contextRef, instance, escalateTo, notifyOn,
                Instant.now());
        tasks.insert(task);
        emit(task, "task.created", createdBy, null);
        if (assignee != null) {
            emit(task, "task.assigned", createdBy, null);
        }
        return task;
    }

    /** The inbox (§5): my tasks — assigned to me or to one of my roles. */
    public TaskStore.Page myTasks(UUID tenantId, UUID actor, String status, int page, int size) {
        return myTasks(tenantId, actor, status, "createdAt", "asc", page, size);
    }

    public TaskStore.Page myTasks(UUID tenantId, UUID actor, String status, String sort,
                                  String dir, int page, int size) {
        return tasks.myTasks(tenantId, actor, roles.of(tenantId, actor),
                status == null || status.isBlank() ? "OPEN" : status,
                sort, dir, page, size);
    }

    public TaskStore.Task require(UUID tenantId, UUID id) {
        return tasks.find(tenantId, id).orElseThrow(() ->
                new PlatformException(PlatformErrorCode.NOT_FOUND, "task " + id + " not found"));
    }

    /** Task read (§13): the same access rule as every mutation — assignee, the task's
     *  role holders, or admin, enforced server-side. */
    public TaskStore.Task read(UUID tenantId, UUID actor, UUID id) {
        return requireAccess(tenantId, actor, id);
    }

    /** Approve/reject (§5): the acting user's resolution, audited with the comment. */
    @Transactional
    public TaskStore.Task resolve(UUID tenantId, UUID actor, UUID id, boolean approved,
                                  String comment) {
        TaskStore.Task task = requireAccess(tenantId, actor, id);
        // Segregation of duties at resolution (§4): the initiating actor never
        // resolves their own request — role-targeted approvals enforce it here.
        if (task.instance() != null && actor.equals(task.createdBy())) {
            throw new PlatformException(PlatformErrorCode.SOD_VIOLATION,
                    "the initiating actor cannot resolve their own approval (§4)");
        }
        String status = approved ? "APPROVED" : "REJECTED";
        if (tasks.resolve(tenantId, id, status, comment) == 0) {
            throw new PlatformException(PlatformErrorCode.CONFLICT_VERSION,
                    "task " + id + " is not open");
        }
        emit(task.withStatus(status), approved ? "task.approved" : "task.rejected",
                actor, comment);
        if (task.instance() != null) {
            suspensions.getObject().resolved(tenantId, task.instance(), actor, approved);
        }
        // Process-managed tasks (PHASE-4 §9): the engine task completes with the
        // outcome variable — the row is resolved first, so the engine's deletion
        // event finds it terminal and does not double-cancel.
        processTasks.ifAvailable(bridge -> bridge.resolved(tenantId, id, approved, comment));
        return task.withStatus(status);
    }

    /** Claim a role-assigned task (§5): the actor becomes the assignee, task stays OPEN. */
    @Transactional
    public TaskStore.Task claim(UUID tenantId, UUID actor, UUID id) {
        requireAccess(tenantId, actor, id);
        if (tasks.claim(tenantId, id, actor) == 0) {
            throw new PlatformException(PlatformErrorCode.CONFLICT_VERSION,
                    "task " + id + " is not an open role-assigned task");
        }
        TaskStore.Task claimed = require(tenantId, id);
        emit(claimed, "task.assigned", actor, "claimed");
        processTasks.ifAvailable(bridge -> bridge.assigned(tenantId, id, actor));
        return claimed;
    }

    /**
     * Delegation (§5): a replacement task for the delegate; the original goes
     * DELEGATED, the chain preserved via {@code contextRef}. Delegating to the
     * initiating actor is segregation-of-duties — rejected fail closed (§4).
     */
    @Transactional
    public TaskStore.Task delegate(UUID tenantId, UUID actor, UUID id, UUID toUser) {
        TaskStore.Task task = requireAccess(tenantId, actor, id);
        processTasks.ifAvailable(bridge -> bridge.assertDelegatable(id));
        if (toUser.equals(task.createdBy())) {
            throw new PlatformException(PlatformErrorCode.SOD_VIOLATION,
                    "the initiating actor cannot receive the approval back (§4)");
        }
        // the delegate must be a reachable assignee: a typo'd UUID (or a user with
        // no roles in the tenant) would produce an OPEN task nobody's inbox ever
        // matches — combined with a lost escalation target, an unreachable approval
        if (roles.of(tenantId, toUser).isEmpty()) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "delegation target " + toUser + " holds no roles in this tenant — "
                            + "not a reachable assignee (§5)");
        }
        UUID chainRoot = task.contextRef() == null ? task.id() : task.contextRef();
        // full-shape constructor: the replacement carries the chain's escalateTo,
        // warned state, and breach-notify switch — the pre-SLA constructor nulls
        // escalateTo, and a delegated approval that can never escalate wedges the
        // record at breach (§6)
        TaskStore.Task replacement = new TaskStore.Task(UUID.randomUUID(), tenantId,
                task.type(), task.entityId(), task.recordId(), toUser, task.role(),
                "OPEN", null, task.dueAt(), task.warnAt(), task.slaWarned(),
                task.createdBy(), chainRoot, task.instance(), task.escalateTo(),
                task.notifyOn(), Instant.now());
        tasks.insert(replacement);
        if (task.contextRef() == null) {
            jdbc.update("UPDATE wf_tasks SET context_ref = ?, updated_at = now() WHERE id = ?",
                    chainRoot, task.id());
        }
        if (tasks.resolve(tenantId, id, "DELEGATED", null) == 0) {
            throw new PlatformException(PlatformErrorCode.CONFLICT_VERSION,
                    "task " + id + " is not open");
        }
        emit(task.withStatus("DELEGATED"), "task.delegated", actor, "to " + toUser);
        emit(replacement, "task.assigned", actor, "delegated from " + id);
        return replacement;
    }

    /** Reassign (§5): admin/builder only, audited — a fresh target on the open task. */
    @Transactional
    public TaskStore.Task reassign(UUID tenantId, UUID actor, UUID id, UUID toUser,
                                   String toRole) {
        if (roles.of(tenantId, actor).stream().noneMatch(r -> r.equals("admin")
                || r.equals("builder"))) {
            throw new PlatformException(PlatformErrorCode.FORBIDDEN,
                    "reassign is admin/builder-only (§5)");
        }
        if (tasks.reassign(tenantId, id, toUser, toRole) == 0) {
            throw new PlatformException(PlatformErrorCode.CONFLICT_VERSION,
                    "task " + id + " is not open");
        }
        TaskStore.Task reassigned = require(tenantId, id);
        emit(reassigned, "task.assigned", actor,
                "reassigned to " + (toRole == null ? String.valueOf(toUser) : toRole));
        processTasks.ifAvailable(bridge -> bridge.assigned(tenantId, id, toUser));
        return reassigned;
    }

    /** Record deletion cancels the record's open tasks (§5) — the spine consumer calls here. */
    @Transactional
    public void cancelForRecord(UUID tenantId, UUID recordId) {
        for (TaskStore.Task task : tasks.cancelForRecord(tenantId, recordId)) {
            emit(task.withStatus("CANCELLED"), "task.cancelled", task.createdBy(),
                    "record deleted");
            // the engine task completes with CANCELLED so its process does not hang
            processTasks.ifAvailable(bridge -> bridge.recordCancelled(tenantId, task.id()));
        }
    }

    /**
     * The bridge's cancellation (PHASE-4 §9): an open inbox row whose engine side
     * ended — process termination, boundary terminate, workflow removal. No-op when
     * the row is already terminal (an inbox resolution completing the engine task
     * fires the same engine event).
     */
    @Transactional
    public void cancelProcessTask(UUID tenantId, UUID taskId, String reason) {
        TaskStore.Task task = require(tenantId, taskId);
        if (!"OPEN".equals(task.status())) {
            return;
        }
        if (tasks.resolve(tenantId, taskId, "CANCELLED", reason) == 0) {
            return;
        }
        emit(task.withStatus("CANCELLED"), "task.cancelled", task.createdBy(), reason);
    }

    // --- access (§13): assignee, the task's role holders, or platform admin ---

    private TaskStore.Task requireAccess(UUID tenantId, UUID actor, UUID id) {
        TaskStore.Task task = require(tenantId, id);
        List<String> held = roles.of(tenantId, actor);
        if (actor.equals(task.assignee()) || held.contains("admin")) {
            return task;
        }
        if (task.role() != null && held.contains(task.role())) {
            return task;
        }
        throw new PlatformException(PlatformErrorCode.FORBIDDEN,
                "task access requires the assignee, the task's role, or admin (§13)");
    }

    // --- events: transactional outbox, relayed at-least-once (§2) ---

    private void emit(TaskStore.Task task, String event, UUID actor, String comment) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("event", event);
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("taskId", task.id().toString());
        payload.put("tenantId", task.tenantId().toString());
        payload.put("entityId", task.entityId());
        payload.put("recordId", task.recordId() == null ? "" : task.recordId().toString());
        payload.put("actorId", actor.toString());
        payload.put("assignee", task.assignee() == null ? "" : task.assignee().toString());
        payload.put("role", task.role() == null ? "" : task.role());
        payload.put("status", task.status());
        payload.put("type", task.type());
        payload.put("occurredAt", Instant.now().toString());
        if (comment != null) {
            payload.put("comment", comment);
        }
        String traceparent = com.novaforge.security.TracePropagation.capture(tracer);
        if (traceparent != null) {
            payload.put("traceparent", traceparent);
        }
        jdbc.update("""
                INSERT INTO wf_event_outbox (id, tenant_id, task_id, event_type, payload)
                VALUES (?, ?, ?, ?, ?::jsonb)""",
                UUID.randomUUID(), task.tenantId(), task.id(), event,
                MAPPER.writeValueAsString(payload));
        LOG.debug("task event queued: {} for {}", event, task.id());
    }
}
