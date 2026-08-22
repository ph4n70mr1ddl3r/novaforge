package com.novaforge.workflow.task;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The task table (PHASE-4 §5): one row per human task, delegation chains linked by
 * {@code contextRef}. Statuses v1: OPEN → APPROVED | REJECTED | DELEGATED | ESCALATED
 * | CANCELLED — there is no COMPLETED status to event (§2).
 */
@Repository
public class TaskStore {

    /** The task model of §5, as stored. */
    public record Task(UUID id, UUID tenantId, String type, String entityId, UUID recordId,
                       UUID assignee, String role, String status, String comment,
                       Instant dueAt, Instant warnAt, UUID createdBy, UUID contextRef,
                       UUID instance, String escalateTo, Instant createdAt) {

        /** Pre-SLA shape (no escalation target resolved). */
        public Task(UUID id, UUID tenantId, String type, String entityId, UUID recordId,
                    UUID assignee, String role, String status, String comment,
                    Instant dueAt, Instant warnAt, UUID createdBy, UUID contextRef,
                    UUID instance, Instant createdAt) {
            this(id, tenantId, type, entityId, recordId, assignee, role, status, comment,
                    dueAt, warnAt, createdBy, contextRef, instance, null, createdAt);
        }

        public Task withStatus(String newStatus) {
            return new Task(id, tenantId, type, entityId, recordId, assignee, role,
                    newStatus, comment, dueAt, warnAt, createdBy, contextRef, instance,
                    escalateTo, createdAt);
        }

        public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("id", id.toString());
            json.put("type", type);
            json.put("entity", entityId);
            json.put("recordId", recordId == null ? null : recordId.toString());
            json.put("assignee", assignee == null ? null : assignee.toString());
            json.put("role", role);
            json.put("status", status);
            json.put("dueAt", dueAt == null ? null : dueAt.toString());
            json.put("sla", dueAt == null && warnAt == null ? null
                    : Map.of("warnAt", warnAt == null ? "" : warnAt.toString(),
                             "breachAt", dueAt == null ? "" : dueAt.toString()));
            json.put("createdBy", createdBy.toString());
            json.put("contextRef", contextRef == null ? null : contextRef.toString());
            json.put("createdAt", createdAt.toString());
            return json;
        }
    }

    private final JdbcTemplate jdbc;

    public TaskStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Task task) {
        jdbc.update("""
                INSERT INTO wf_tasks (id, tenant_id, type, entity_id, record_id, assignee,
                                      role, status, comment, due_at, warn_at, created_by,
                                      context_ref, instance_id, escalate_to, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                task.id(), task.tenantId(), task.type(), task.entityId(), task.recordId(),
                task.assignee(), task.role(), task.status(), task.comment(),
                task.dueAt() == null ? null : Timestamp.from(task.dueAt()),
                task.warnAt() == null ? null : Timestamp.from(task.warnAt()),
                task.createdBy(), task.contextRef(), task.instance(), task.escalateTo(),
                Timestamp.from(task.createdAt()));
    }

    public Optional<Task> find(UUID tenantId, UUID id) {
        return jdbc.query("""
                        SELECT id, tenant_id, type, entity_id, record_id, assignee, role,
                               status, comment, due_at, warn_at, created_by, context_ref,
                               instance_id, created_at
                          FROM wf_tasks WHERE tenant_id = ? AND id = ?""",
                TaskStore::mapRow, tenantId, id).stream().findFirst();
    }

    /** Terminal transition + optional resolution comment. */
    @Transactional
    public int resolve(UUID tenantId, UUID id, String status, String comment) {
        return jdbc.update("""
                UPDATE wf_tasks SET status = ?, comment = ?, updated_at = now()
                 WHERE tenant_id = ? AND id = ? AND status = 'OPEN'""",
                status, comment, tenantId, id);
    }

    /** Claim: a role-assigned task gains an assignee, staying OPEN (§5). */
    public int claim(UUID tenantId, UUID id, UUID assignee) {
        return jdbc.update("""
                UPDATE wf_tasks SET assignee = ?, updated_at = now()
                 WHERE tenant_id = ? AND id = ? AND status = 'OPEN' AND role IS NOT NULL""",
                assignee, tenantId, id);
    }

    /** Reassign (admin/builder): a fresh target — user or role — on the open task. */
    public int reassign(UUID tenantId, UUID id, UUID assignee, String role) {
        return jdbc.update("""
                UPDATE wf_tasks SET assignee = ?, role = ?, updated_at = now()
                 WHERE tenant_id = ? AND id = ? AND status = 'OPEN'""",
                assignee, role, tenantId, id);
    }

    /** Cancel every open task for a record — record deletion (§5). */
    public List<Task> cancelForRecord(UUID tenantId, UUID recordId) {
        List<Task> open = listForRecord(tenantId, recordId);
        for (Task task : open) {
            jdbc.update("""
                    UPDATE wf_tasks SET status = 'CANCELLED', updated_at = now()
                     WHERE tenant_id = ? AND id = ? AND status = 'OPEN'""", tenantId, task.id());
        }
        return open;
    }

    public List<Task> listForRecord(UUID tenantId, UUID recordId) {
        return jdbc.query("""
                        SELECT id, tenant_id, type, entity_id, record_id, assignee, role,
                               status, comment, due_at, warn_at, created_by, context_ref,
                               instance_id, created_at
                          FROM wf_tasks WHERE tenant_id = ? AND record_id = ?
                         ORDER BY created_at""",
                TaskStore::mapRow, tenantId, recordId);
    }

    /**
     * The inbox query (§5): tasks assigned to me or to one of my roles, status-filtered
     * (OPEN by default), server-side paged — the Phase 1 query conventions.
     */
    public Page myTasks(UUID tenantId, UUID actor, List<String> roles, String status,
                        int page, int size) {
        String anyRoles = roles == null || roles.isEmpty() ? null
                : roles.stream().map(r -> "?").reduce((a, b) -> a + "," + b).orElse(null);
        String filter = "(assignee = ?" + (anyRoles == null ? "" : " OR role IN (" + anyRoles + ")")
                + ") AND status = ?";
        List<Object> params = new ArrayList<>();
        params.add(actor);
        if (anyRoles != null) {
            params.addAll(roles);
        }
        params.add(status);
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM wf_tasks WHERE tenant_id = ? AND " + filter, Long.class,
                prepend(tenantId, params).toArray());
        List<Object> paged = prepend(tenantId, params);
        paged.add(size);
        paged.add(page * size);
        List<Task> rows = jdbc.query(
                "SELECT id, tenant_id, type, entity_id, record_id, assignee, role, status,"
                        + " comment, due_at, warn_at, created_by, context_ref, instance_id,"
                        + " created_at"
                        + " FROM wf_tasks WHERE tenant_id = ? AND " + filter
                        + " ORDER BY created_at LIMIT ? OFFSET ?",
                TaskStore::mapRow, paged.toArray());
        return new Page(rows, total == null ? 0 : total);
    }

    private static List<Object> prepend(UUID tenantId, List<Object> params) {
        List<Object> all = new ArrayList<>();
        all.add(tenantId);
        all.addAll(params);
        return all;
    }

    public record Page(List<Task> rows, long total) {
    }

    static Task mapRow(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        return new Task(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("type"), rs.getString("entity_id"),
                rs.getObject("record_id", UUID.class), rs.getObject("assignee", UUID.class),
                rs.getString("role"), rs.getString("status"), rs.getString("comment"),
                rs.getTimestamp("due_at") == null ? null
                        : rs.getTimestamp("due_at").toInstant(),
                rs.getTimestamp("warn_at") == null ? null
                        : rs.getTimestamp("warn_at").toInstant(),
                rs.getObject("created_by", UUID.class), rs.getObject("context_ref", UUID.class),
                rs.getObject("instance_id", UUID.class),
                rs.getTimestamp("created_at").toInstant());
    }
}
