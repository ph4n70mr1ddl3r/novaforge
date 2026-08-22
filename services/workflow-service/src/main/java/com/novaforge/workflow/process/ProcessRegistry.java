package com.novaforge.workflow.process;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The BPMN registry tables (PHASE-4 §9): deployments (content-hash idempotency,
 * audible failure), event-start dedupe (the spine is at-least-once), and the
 * engine-task ↔ inbox-task bridge rows.
 */
@Repository
public class ProcessRegistry {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;

    public ProcessRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One registry row — the deployment's bookkeeping, not the engine's truth. */
    public record Deployment(UUID id, UUID tenantId, String app, String workflowId,
                             String contentHash, String deploymentId,
                             String processDefinitionId, String status, String error) {

        public boolean deployed() {
            return "DEPLOYED".equals(status);
        }
    }

    /** An event-start subscription as stored on the registry row. */
    public record StoredStart(String event, String entity, String filter) {
    }

    public static UUID rowId(UUID tenantId, String app, String workflowId) {
        return UUID.nameUUIDFromBytes((tenantId + ":" + app + ":" + workflowId).getBytes());
    }

    public Optional<Deployment> find(UUID tenantId, String app, String workflowId) {
        return find(rowId(tenantId, app, workflowId));
    }

    public Optional<Deployment> find(UUID id) {
        return jdbc.query("""
                        SELECT id, tenant_id, app, workflow_id, content_hash, deployment_id,
                               process_definition_id, status, error
                          FROM wf_process_deployments WHERE id = ?""",
                (rs, i) -> new Deployment(rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class), rs.getString("app"),
                        rs.getString("workflow_id"), rs.getString("content_hash"),
                        rs.getString("deployment_id"), rs.getString("process_definition_id"),
                        rs.getString("status"), rs.getString("error")),
                id).stream().findFirst();
    }

    public Optional<Deployment> byProcessDefinition(String processDefinitionId) {
        return jdbc.query("""
                        SELECT id, tenant_id, app, workflow_id, content_hash, deployment_id,
                               process_definition_id, status, error
                          FROM wf_process_deployments
                         WHERE process_definition_id = ? AND status = 'DEPLOYED'""",
                (rs, i) -> new Deployment(rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class), rs.getString("app"),
                        rs.getString("workflow_id"), rs.getString("content_hash"),
                        rs.getString("deployment_id"), rs.getString("process_definition_id"),
                        rs.getString("status"), rs.getString("error")),
                processDefinitionId).stream().findFirst();
    }

    /** The deployed rows with their subscriptions — the event-start matching set. */
    public List<DeployedWorkflow> deployedWithStarts() {
        return jdbc.query("""
                        SELECT d.tenant_id, d.app, d.workflow_id, d.event_starts
                          FROM wf_process_deployments d
                         WHERE d.status = 'DEPLOYED' AND d.event_starts IS NOT NULL""",
                (rs, i) -> new DeployedWorkflow(rs.getObject("tenant_id", UUID.class),
                        rs.getString("app"), rs.getString("workflow_id"),
                        MAPPER.readValue(rs.getString("event_starts"),
                                new TypeReference<List<StoredStart>>() {
                                })));
    }

    /** A deployed workflow plus its event-start subscriptions. */
    public record DeployedWorkflow(UUID tenantId, String app, String workflowId,
                                   List<StoredStart> eventStarts) {
    }

    /** Inserts/updates the row before the engine attempt; subscriptions ride along. */
    public void beginSync(UUID id, UUID tenantId, String app, String workflowId,
                          String contentHash, List<StoredStart> starts) {
        jdbc.update("""
                INSERT INTO wf_process_deployments (id, tenant_id, app, workflow_id,
                                                    content_hash, event_starts, status)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, 'DEPLOYED')
                ON CONFLICT (tenant_id, app, workflow_id) DO UPDATE SET
                  content_hash = EXCLUDED.content_hash,
                  event_starts = EXCLUDED.event_starts""",
                id, tenantId, app, workflowId, contentHash,
                MAPPER.writeValueAsString(starts));
    }

    public void markDeployed(UUID id, String deploymentId, String processDefinitionId) {
        jdbc.update("""
                UPDATE wf_process_deployments SET deployment_id = ?, process_definition_id = ?,
                                                  status = 'DEPLOYED', error = NULL,
                                                  updated_at = now()
                 WHERE id = ?""", deploymentId, processDefinitionId, id);
    }

    public void markFailed(UUID id, String error) {
        jdbc.update("""
                UPDATE wf_process_deployments SET status = 'FAILED', error = ?,
                                                  updated_at = now()
                 WHERE id = ?""", error, id);
    }

    public void markRemoved(UUID id) {
        jdbc.update("""
                UPDATE wf_process_deployments SET status = 'REMOVED', updated_at = now()
                 WHERE id = ?""", id);
    }

    /** Every non-removed row — the removal pass diffs against the desired set. */
    public List<Deployment> activeRows() {
        return jdbc.query("""
                        SELECT id, tenant_id, app, workflow_id, content_hash, deployment_id,
                               process_definition_id, status, error
                          FROM wf_process_deployments WHERE status <> 'REMOVED'""",
                (rs, i) -> new Deployment(rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class), rs.getString("app"),
                        rs.getString("workflow_id"), rs.getString("content_hash"),
                        rs.getString("deployment_id"), rs.getString("process_definition_id"),
                        rs.getString("status"), rs.getString("error")));
    }

    // --- event-start dedupe (§9): the spine event id is the key ---

    /**
     * True when this (event, workflow) pair was unseen (the caller starts); false
     * collapses redelivery — per workflow, so two subscriptions matching one event
     * both start.
     */
    public boolean claimStart(UUID eventId, UUID tenantId, String workflowId) {
        return jdbc.update("""
                INSERT INTO wf_process_starts (event_id, tenant_id, workflow_id)
                VALUES (?, ?, ?) ON CONFLICT (event_id, workflow_id) DO NOTHING""",
                eventId, tenantId, workflowId) > 0;
    }

    public void recordInstance(UUID eventId, String workflowId, String instanceId) {
        jdbc.update("""
                UPDATE wf_process_starts SET instance_id = ?
                 WHERE event_id = ? AND workflow_id = ?""",
                instanceId, eventId, workflowId);
    }

    // --- the §5-inbox bridge rows ---

    public void linkTask(UUID taskId, String engineTaskId, String processInstanceId,
                         String workflowId) {
        jdbc.update("""
                INSERT INTO wf_process_tasks (task_id, engine_task_id,
                                              process_instance_id, workflow_id)
                VALUES (?, ?, ?, ?)""", taskId, engineTaskId, processInstanceId, workflowId);
    }

    public record TaskLink(UUID taskId, String engineTaskId, String processInstanceId,
                           String workflowId) {
    }

    public Optional<TaskLink> linkByTask(UUID taskId) {
        return link("SELECT task_id, engine_task_id, process_instance_id, workflow_id"
                + " FROM wf_process_tasks WHERE task_id = ?", taskId);
    }

    public Optional<TaskLink> linkByEngineTask(String engineTaskId) {
        return link("SELECT task_id, engine_task_id, process_instance_id, workflow_id"
                + " FROM wf_process_tasks WHERE engine_task_id = ?", engineTaskId);
    }

    /** Open inbox rows linked to instances of a workflow — the removal pass cancels them. */
    public List<UUID> openTasksOfWorkflow(UUID tenantId, String workflowId) {
        return jdbc.query("""
                        SELECT p.task_id FROM wf_process_tasks p
                          JOIN wf_tasks t ON t.id = p.task_id
                         WHERE p.workflow_id = ? AND t.tenant_id = ?
                           AND t.status = 'OPEN'""",
                (rs, i) -> rs.getObject("task_id", UUID.class), workflowId, tenantId);
    }

    private Optional<TaskLink> link(String sql, Object key) {
        return jdbc.query(sql, (rs, i) -> new TaskLink(rs.getObject("task_id", UUID.class),
                rs.getString("engine_task_id"), rs.getString("process_instance_id"),
                rs.getString("workflow_id")), key).stream().findFirst();
    }

    static Timestamp timestamp(java.time.Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
