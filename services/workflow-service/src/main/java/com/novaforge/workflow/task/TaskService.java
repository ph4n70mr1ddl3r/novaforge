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

    public TaskService(TaskStore tasks, org.springframework.jdbc.core.JdbcTemplate jdbc,
                       RoleLookup roles,
                       org.springframework.beans.factory.ObjectProvider<SuspensionService> suspensions) {
        this.tasks = tasks;
        this.jdbc = jdbc;
        this.roles = roles;
        this.suspensions = suspensions;
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
        if (assignee == null && role == null) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "a task requires an assignee or a role");
        }
        if (type == null || !(type.equals("approval") || type.equals("todo"))) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "task type must be approval or todo: " + type);
        }
        TaskStore.Task task = new TaskStore.Task(UUID.randomUUID(), tenantId, type, entityId,
                recordId, assignee, role, "OPEN", null, dueAt, warnAt, createdBy,
                contextRef == null ? null : contextRef, instance, Instant.now());
        tasks.insert(task);
        emit(task, "task.created", createdBy, null);
        if (assignee != null) {
            emit(task, "task.assigned", createdBy, null);
        }
        return task;
    }

    /** The inbox (§5): my tasks — assigned to me or to one of my roles. */
    public TaskStore.Page myTasks(UUID tenantId, UUID actor, String status, int page, int size) {
        return tasks.myTasks(tenantId, actor, roles.of(tenantId, actor),
                status == null || status.isBlank() ? "OPEN" : status, page, size);
    }

    public TaskStore.Task require(UUID tenantId, UUID id) {
        return tasks.find(tenantId, id).orElseThrow(() ->
                new PlatformException(PlatformErrorCode.NOT_FOUND, "task " + id + " not found"));
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
        if (toUser.equals(task.createdBy())) {
            throw new PlatformException(PlatformErrorCode.SOD_VIOLATION,
                    "the initiating actor cannot receive the approval back (§4)");
        }
        UUID chainRoot = task.contextRef() == null ? task.id() : task.contextRef();
        TaskStore.Task replacement = new TaskStore.Task(UUID.randomUUID(), tenantId,
                task.type(), task.entityId(), task.recordId(), toUser, task.role(),
                "OPEN", null, task.dueAt(), task.warnAt(), task.createdBy(), chainRoot,
                task.instance(), Instant.now());
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
        return reassigned;
    }

    /** Record deletion cancels the record's open tasks (§5) — the spine consumer calls here. */
    @Transactional
    public void cancelForRecord(UUID tenantId, UUID recordId) {
        for (TaskStore.Task task : tasks.cancelForRecord(tenantId, recordId)) {
            emit(task.withStatus("CANCELLED"), "task.cancelled", task.createdBy(),
                    "record deleted");
        }
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
        payload.put("recordId", task.recordId().toString());
        payload.put("actorId", actor.toString());
        payload.put("status", task.status());
        payload.put("type", task.type());
        payload.put("occurredAt", Instant.now().toString());
        if (comment != null) {
            payload.put("comment", comment);
        }
        jdbc.update("""
                INSERT INTO wf_event_outbox (id, tenant_id, task_id, event_type, payload)
                VALUES (?, ?, ?, ?, ?::jsonb)""",
                UUID.randomUUID(), task.tenantId(), task.id(), event,
                MAPPER.writeValueAsString(payload));
        LOG.debug("task event queued: {} for {}", event, task.id());
    }
}
