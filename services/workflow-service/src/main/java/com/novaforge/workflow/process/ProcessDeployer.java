package com.novaforge.workflow.process;

import com.novaforge.metadata.WorkflowDefinition;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publish-driven deployment sync (PHASE-4 §9, the §7 definitions-vs-registry
 * split): published {@code WorkflowDefinition}s deploy into the embedded engine —
 * by content hash, so an unchanged re-publish deploys nothing; a changed one
 * deploys a new engine version (running instances finish on their own version);
 * a definition that left the published app loses its deployment (cascading
 * instances and cancelling the tasks those instances own).
 *
 * <p>Deploy attempts that fail — structurally invalid BPMN, or user tasks off the
 * v1 gate (a literal assignee UUID or literal candidate-group roles; §9) — record
 * status FAILED with the error and retry next pass; the previously deployed
 * version, if any, keeps serving starts (last-known-good, an audible registry
 * never a silent outage).</p>
 */
@Component
public class ProcessDeployer {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessDeployer.class);

    private final ProcessRegistry registry;
    private final PublishedWorkflowSource source;
    private final RepositoryService repository;
    private final ProcessTaskBridge bridge;

    public ProcessDeployer(ProcessRegistry registry, PublishedWorkflowSource source,
                           RepositoryService repository, ProcessTaskBridge bridge) {
        this.registry = registry;
        this.source = source;
        this.repository = repository;
        this.bridge = bridge;
    }

    @Scheduled(fixedDelayString = "${novaforge.process.sync-interval-ms:30000}")
    public void sync() {
        try {
            syncOnce();
        } catch (Exception e) {
            // the source raises INTERNAL when metadata is unreachable — retry next pass
            LOG.error("process deployment sync failed: {}", e.getMessage(), e);
        }
    }

    /** One sync pass — public for tests and the restart catch-up. */
    @Transactional
    public void syncOnce() {
        Set<UUID> desired = new HashSet<>();
        for (PublishedWorkflowSource.AppWorkflows app : source.all()) {
            for (WorkflowDefinition workflow : app.workflows()) {
                UUID id = ProcessRegistry.rowId(app.tenantId(), app.appApiName(),
                        workflow.id());
                desired.add(id);
                deployIfChanged(app.tenantId(), app.appApiName(), workflow, id);
            }
        }
        removeGone(desired);
    }

    private void deployIfChanged(UUID tenantId, String app, WorkflowDefinition workflow,
                                 UUID id) {
        String hash = sha256(workflow.bpmn());
        var existing = registry.find(id);
        // unchanged — an idempotent re-publish deploys nothing. The deploymentId
        // guard is crash recovery: the scheduled path reaches syncOnce() unproxied,
        // so the registry row and the engine deploy are not one transaction — a
        // crash between them leaves a DEPLOYED row with no deployment, which must
        // redeploy rather than skip forever.
        if (existing.isPresent() && existing.get().deployed()
                && existing.get().deploymentId() != null
                && hash.equals(existing.get().contentHash())) {
            return;
        }
        List<ProcessRegistry.StoredStart> starts = workflow.eventStarts().stream()
                .map(start -> new ProcessRegistry.StoredStart(start.event(), start.entity(),
                        start.filter()))
                .toList();
        registry.beginSync(id, tenantId, app, workflow.id(), hash, starts);
        try {
            Deployment deployment = repository.createDeployment()
                    .name(tenantId + ":" + app + ":" + workflow.id())
                    .key(workflow.id())
                    .addString(workflow.id() + ".bpmn20.xml", workflow.bpmn())
                    .tenantId(tenantId.toString())
                    .deploy();
            ProcessDefinition definition = repository.createProcessDefinitionQuery()
                    .deploymentId(deployment.getId())
                    .processDefinitionKey(workflow.id())
                    .singleResult();
            if (definition == null) {
                throw new IllegalStateException("deployment carried no process definition "
                        + "for key " + workflow.id());
            }
            String gateError = userTaskGate(repository.getBpmnModel(definition.getId()));
            if (gateError != null) {
                repository.deleteDeployment(deployment.getId(), true);
                registry.markFailed(id, gateError);
                LOG.error("workflow {}/{} failed the user-task gate: {}", app,
                        workflow.id(), gateError);
                return;
            }
            registry.markDeployed(id, deployment.getId(), definition.getId());
            LOG.info("deployed workflow {}/{} (definition {})", app, workflow.id(),
                    definition.getId());
        } catch (Exception e) {
            registry.markFailed(id, e.getMessage());
            LOG.error("workflow {}/{} failed to deploy: {}", app, workflow.id(),
                    e.getMessage(), e);
        }
    }

    /**
     * The v1 user-task gate (§9): every {@code userTask} carries a literal
     * {@code flowable:assignee} (a user UUID) or literal
     * {@code flowable:candidateGroups} (role names) — expressions are not evaluated
     * in v1, so they fail loudly here, at deploy, rather than silently at runtime.
     */
    static String userTaskGate(BpmnModel model) {
        if (model == null || model.getMainProcess() == null) {
            return "the BPMN carries no main process";
        }
        for (UserTask userTask : model.getMainProcess()
                .findFlowElementsOfType(UserTask.class)) {
            String where = "userTask '" + userTask.getId() + "'";
            String assignee = userTask.getAssignee();
            if (assignee != null) {
                if (assignee.contains("${")) {
                    return where + ": flowable:assignee expressions are not supported in v1 "
                            + "(a literal user UUID is required)";
                }
                try {
                    UUID.fromString(assignee);
                } catch (IllegalArgumentException e) {
                    return where + ": flowable:assignee must be a literal user UUID in v1: "
                            + assignee;
                }
            }
            List<String> groups = userTask.getCandidateGroups();
            if (groups != null) {
                for (String group : groups) {
                    if (group.contains("${")) {
                        return where + ": flowable:candidateGroups expressions are not "
                                + "supported in v1 (literal role names are required)";
                    }
                }
            }
            if ((assignee == null || assignee.isBlank())
                    && (groups == null || groups.isEmpty())) {
                return where + ": v1 user tasks require a literal flowable:assignee "
                        + "(user UUID) or flowable:candidateGroups (role names)";
            }
        }
        return null;   // gate passed
    }

    /** Definitions that left the published app: cancel owned tasks, cascade the engine. */
    private void removeGone(Set<UUID> desired) {
        for (ProcessRegistry.Deployment row : registry.activeRows()) {
            if (desired.contains(row.id())) {
                continue;
            }
            for (UUID taskId : registry.openTasksOfWorkflow(row.tenantId(),
                    row.workflowId())) {
                bridge.cancelTaskRow(row.tenantId(), taskId, "workflow removed from the "
                        + "published app");
            }
            if (row.deploymentId() != null) {
                repository.deleteDeployment(row.deploymentId(), true);
            }
            registry.markRemoved(row.id());
            LOG.info("removed workflow {}/{} (no longer published)", row.app(),
                    row.workflowId());
        }
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
