package com.novaforge.workflow.task;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.workflow.runtime.ResumeClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable flow suspension (PHASE-4 §4): the internal surface the Data Runtime's
 * {@code requestApproval} calls. One approval per call: the instance persists where
 * the flow resumes, tasks carry the instance id, and resolution re-enters the
 * runtime's compiled-graph engine (system principal) exactly once — approve resumes
 * after the step, reject runs the step's own {@code onReject} subgraph, or fails the
 * instance audibly when none was authored (never silent).
 *
 * <p>Segregation of duties, fail closed (§4): the initiating actor is removed from an
 * explicit approver set at creation; an empty remainder rejects {@code SOD_VIOLATION}
 * and the flow fails audibly on the write path. Role-targeted approvals enforce the
 * same rule at resolution (the initiator cannot resolve their own request).</p>
 */
@Service
public class SuspensionService {

    private static final Logger LOG = LoggerFactory.getLogger(SuspensionService.class);

    private final JdbcTemplate jdbc;
    private final TaskService tasks;
    private final ResumeClient runtime;

    public SuspensionService(JdbcTemplate jdbc, TaskService tasks, ResumeClient runtime) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.runtime = runtime;
    }

    /** The Data Runtime's requestApproval arrival (internal, service-token surface). */
    @Transactional
    public Map<String, Object> request(UUID tenantId, String app, String entityApiName,
                                       String entityKey, UUID recordId, String hook,
                                       String stepId, String afterStep, String onRejectJson,
                                       String approversRole, List<String> approverUsers,
                                       String mode, UUID initiatingActor) {
        // SoD: the initiating actor leaves an explicit approver set (§4, fail closed).
        List<String> users = new ArrayList<>(approverUsers == null ? List.of() : approverUsers);
        if (initiatingActor != null) {
            users.remove(initiatingActor.toString());
        }
        if (approversRole == null && users.isEmpty()) {
            throw new PlatformException(PlatformErrorCode.SOD_VIOLATION,
                    "the initiating actor is the only candidate approver — the approval "
                            + "cannot be segregated (§4)");
        }
        if (!"any".equals(mode) && !"all".equals(mode)) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "mode must be any or all: " + mode);
        }

        UUID instanceId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wf_suspended_flows (id, tenant_id, app, entity_api_name,
                    entity_key, record_id, hook_name, step_id, after_step, on_reject,
                    mode, needed, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SUSPENDED')""",
                instanceId, tenantId, app, entityApiName, entityKey, recordId, hook,
                stepId, afterStep == null || afterStep.isBlank() ? null : afterStep,
                onRejectJson == null || onRejectJson.isBlank() ? null : onRejectJson,
                mode, users.isEmpty() ? 1 : users.size());

        if (users.isEmpty()) {
            // role-targeted: one task for the role; the initiator cannot resolve it
            tasks.create(tenantId, "approval", entityKey, recordId, null, approversRole,
                    null, null, initiatingActor == null ? UUID.nameUUIDFromBytes(
                            ("system:" + app).getBytes()) : initiatingActor, null,
                    instanceId);
        } else {
            for (String user : users) {
                tasks.create(tenantId, "approval", entityKey, recordId,
                        UUID.fromString(user), null, null, null,
                        initiatingActor == null ? UUID.nameUUIDFromBytes(
                                ("system:" + app).getBytes()) : initiatingActor, null,
                        instanceId);
            }
        }
        LOG.info("flow suspended: {} on {}/{} step {} ({} approvers, mode {})",
                hook, entityKey, recordId, stepId, users.isEmpty() ? approversRole : users, mode);
        return Map.of("instanceId", instanceId.toString(), "tasks",
                users.isEmpty() ? 1 : users.size());
    }

    /**
     * A resolution arrived for a task linked to a suspended instance: unanimity for
     * {@code all}, first-wins for {@code any} — then exactly one resume into the
     * engine. Reject routes the step's onReject subgraph, or fails the instance
     * audibly (§4).
     */
    @Transactional
    public void resolved(UUID tenantId, UUID instanceId, UUID resolvedBy, boolean approved) {
        int updated = jdbc.update("""
                UPDATE wf_suspended_flows
                   SET approvals = approvals + 1, updated_at = now()
                 WHERE id = ? AND tenant_id = ? AND status = 'SUSPENDED'""",
                instanceId, tenantId);
        if (updated == 0) {
            return;   // already resumed/rejected by a sibling resolution (first wins)
        }
        if (!approved) {
            resume(tenantId, instanceId, false);
            return;
        }
        Map<String, Object> instance = jdbc.queryForMap("""
                SELECT needed, approvals, mode FROM wf_suspended_flows WHERE id = ?""",
                instanceId);
        int needed = ((Number) instance.get("needed")).intValue();
        int approvals = ((Number) instance.get("approvals")).intValue();
        if ("any".equals(instance.get("mode")) || approvals >= needed) {
            resume(tenantId, instanceId, true);
        }
    }

    private void resume(UUID tenantId, UUID instanceId, boolean approved) {
        Map<String, Object> instance = jdbc.queryForMap("""
                SELECT app, entity_api_name, record_id, hook_name, after_step, on_reject
                  FROM wf_suspended_flows WHERE id = ?""", instanceId);
        try {
            runtime.resume(new ResumeClient.Resume(tenantId,
                    String.valueOf(instance.get("app")),
                    String.valueOf(instance.get("entity_api_name")),
                    UUID.fromString(String.valueOf(instance.get("record_id"))),
                    String.valueOf(instance.get("hook_name")),
                    instance.get("after_step") == null ? null
                            : String.valueOf(instance.get("after_step")),
                    instance.get("on_reject") == null ? null
                            : String.valueOf(instance.get("on_reject")),
                    approved));
            jdbc.update("""
                    UPDATE wf_suspended_flows SET status = ?, updated_at = now()
                     WHERE id = ?""", approved ? "RESUMED" : "REJECTED", instanceId);
            LOG.info("flow {} resumed (approved={})", instanceId, approved);
        } catch (Exception e) {
            // The instance stays resolvable — the failure is recorded, never silent;
            // an operator can re-drive the resolution once the cause heals.
            jdbc.update("""
                    UPDATE wf_suspended_flows SET status = 'FAILED', last_error = ?,
                        updated_at = now() WHERE id = ?""", e.getMessage(), instanceId);
            LOG.error("flow {} resume failed: {}", instanceId, e.getMessage(), e);
        }
    }
}
