package com.novaforge.workflow.process;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.workflow.sla.SlaResolver;
import com.novaforge.workflow.task.TaskService;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.UserTask;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.task.service.impl.persistence.entity.TaskEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The §5-inbox bridge (PHASE-4 §9): BPMN {@code userTask}s of deployed workflows
 * create {@code wf_tasks} rows through the same task service approvals use — SLA
 * resolution and {@code task.*} events ride the existing path — and inbox
 * resolutions complete the engine task (a {@code resolution} variable carries the
 * outcome for the process's own gateways). Engine-side termination cancels
 * still-open rows; delegation of process-managed tasks rejects in v1 (Flowable's
 * single-task model does not map to §5's replacement-task chains).
 */
@Component
public class ProcessTaskBridge implements FlowableEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessTaskBridge.class);

    /** The variable the inbox resolution writes for the process's gateway routing. */
    public static final String RESOLUTION_VARIABLE = "resolution";

    private final ProcessRegistry registry;
    private final TaskService tasks;
    private final org.springframework.beans.factory.ObjectProvider<RepositoryService> repository;
    private final org.springframework.beans.factory.ObjectProvider<RuntimeService> runtime;
    private final org.springframework.beans.factory.ObjectProvider<org.flowable.engine.TaskService> engineTasks;
    private final SlaResolver slas;

    /**
     * The engine services arrive as lazy providers: this bean registers itself with
     * the engine at build time (the configurer injects it into the engine's event
     * listeners), so constructor injection of the engine's own services would be a
     * build-time cycle — resolved at first use instead, always after the context is
     * up.
     */
    public ProcessTaskBridge(ProcessRegistry registry, TaskService tasks,
                             org.springframework.beans.factory.ObjectProvider<RepositoryService> repository,
                             org.springframework.beans.factory.ObjectProvider<RuntimeService> runtime,
                             org.springframework.beans.factory.ObjectProvider<org.flowable.engine.TaskService> engineTasks,
                             SlaResolver slas) {
        this.registry = registry;
        this.tasks = tasks;
        this.repository = repository;
        this.runtime = runtime;
        this.engineTasks = engineTasks;
        this.slas = slas;
    }

    @Override
    public void onEvent(FlowableEvent event) {
        if (event instanceof org.flowable.common.engine.api.delegate.event.FlowableEntityEvent
                entityEvent && entityEvent.getEntity() instanceof TaskEntity task) {
            if (event.getType() == FlowableEngineEventType.TASK_CREATED) {
                taskCreated(task);
            } else if (event.getType() == FlowableEngineEventType.ENTITY_DELETED) {
                // task deletion: process end, boundary terminate, deployment cascade —
                // completions arrive here too, after the inbox row already resolved
                engineTaskEnded(task);
            }
        }
    }

    @Override
    public boolean isFailOnException() {
        return true;   // fail loudly — the creating transaction rolls back
    }

    @Override
    public boolean isFireOnTransactionLifecycleEvent() {
        return false;   // engine-command timing, not transaction-commit timing
    }

    @Override
    public String getOnTransaction() {
        return null;   // not a transaction-lifecycle listener
    }

    // --- engine → inbox ---

    /** A deployed workflow's user task becomes an inbox row (type todo, §9). */
    void taskCreated(TaskEntity task) {
        UUID tenantId = tenantOf(task);
        if (tenantId == null) {
            return;
        }
        var deployment = registry.byProcessDefinition(task.getProcessDefinitionId());
        if (deployment.isEmpty()) {
            return;   // not one of our published workflows — never bridged
        }
        FlowElement element = repository.getObject().getBpmnModel(task.getProcessDefinitionId())
                .getMainProcess().getFlowElement(task.getTaskDefinitionKey());
        if (!(element instanceof UserTask userTask)) {
            return;
        }
        UUID assignee = literalUuid(userTask.getAssignee());
        // candidate groups are role names, qualified with the owning app — the
        // platform's role identity is app-scoped (App.role, PHASE-2 §9)
        String role = userTask.getCandidateGroups() == null
                || userTask.getCandidateGroups().isEmpty()
                ? null : deployment.get().app() + "." + userTask.getCandidateGroups().getFirst();
        String entityId = variable(task.getProcessInstanceId(), "entityId");
        UUID recordId = literalUuid(variable(task.getProcessInstanceId(), "recordId"));
        UUID createdBy = literalUuid(variable(task.getProcessInstanceId(), "actorId"));
        if (createdBy == null) {
            createdBy = UUID.nameUUIDFromBytes(
                    ("system:" + deployment.get().app()).getBytes());
        }
        Instant now = Instant.now();
        // a literal ISO duration is the v1 step timeout; any other literal (an ISO
        // datetime, say) carries no step timer — never a parse failure rolling back
        // the creating transaction (the listener fails loudly by design)
        String dueDuration = userTask.getDueDate();
        String stepTimeout = null;
        if (dueDuration != null && !dueDuration.contains("${")) {
            try {
                stepTimeout = Duration.parse(dueDuration).toString();
            } catch (DateTimeParseException e) {
                LOG.debug("userTask {} dueDate '{}' is not an ISO duration — no step timer",
                        task.getTaskDefinitionKey(), dueDuration);
            }
        }
        SlaResolver.Timers timers = slas.resolve(tenantId, deployment.get().app(),
                entityId == null ? deployment.get().workflowId() : entityId, "todo",
                stepTimeout, null, now);
        // the matched SLA's escalation target rides the bridge task like any other
        // (§9: bridge tasks ride the same task service — SLA resolution included) —
        // a dropped target warned and breached but never escalated
        String escalateTo = timers.matched() != null && timers.matched().onBreach() != null
                && timers.matched().onBreach().escalateTo() != null
                ? timers.matched().onBreach().escalateTo().replaceFirst("^role:", "")
                : null;
        // §6's onBreach switch rides the bridge task like any other (§9)
        boolean notifyOn = timers.matched() == null || timers.matched().onBreach() == null
                || timers.matched().onBreach().notifyOn();
        var inboxTask = tasks.create(tenantId, "todo",
                entityId == null ? deployment.get().workflowId() : entityId,
                recordId, assignee, role, timers.dueAt(), timers.warnAt(), createdBy,
                null, null, escalateTo, notifyOn);
        registry.linkTask(inboxTask.id(), task.getId(), task.getProcessInstanceId(),
                deployment.get().workflowId());
        LOG.debug("bridged engine task {} to inbox task {} (workflow {})", task.getId(),
                inboxTask.id(), deployment.get().workflowId());
    }

    /** The engine task is gone (process end, boundary terminate, deployment cascade). */
    void engineTaskEnded(TaskEntity task) {
        var link = registry.linkByEngineTask(task.getId());
        if (link.isEmpty()) {
            return;
        }
        cancelTaskRow(tenantOf(task), link.get().taskId(), "process ended the task");
    }

    // --- inbox → engine (TaskService calls these; ObjectProvider keeps the cycle lazy) ---

    /** Approve/reject: complete the engine task with the outcome variable (§9). */
    @Transactional
    public void resolved(UUID tenantId, UUID taskId, boolean approved, String comment) {
        var link = registry.linkByTask(taskId);
        if (link.isEmpty()) {
            return;
        }
        Map<String, Object> variables = new java.util.LinkedHashMap<>();
        variables.put(RESOLUTION_VARIABLE, approved ? "APPROVED" : "REJECTED");
        variables.put("comment", comment == null ? "" : comment);
        engineTasks.getObject().complete(link.get().engineTaskId(), variables);
    }

    /** Claim/reassign mirrors the assignee into the engine. */
    @Transactional
    public void assigned(UUID tenantId, UUID taskId, UUID assignee) {
        var link = registry.linkByTask(taskId);
        if (link.isEmpty()) {
            return;
        }
        engineTasks.getObject().setAssignee(link.get().engineTaskId(), assignee == null ? null
                : assignee.toString());
    }

    /** Record deletion: complete the engine task so the process does not hang. */
    @Transactional
    public void recordCancelled(UUID tenantId, UUID taskId) {
        var link = registry.linkByTask(taskId);
        if (link.isEmpty()) {
            return;
        }
        Map<String, Object> variables = new java.util.LinkedHashMap<>();
        variables.put(RESOLUTION_VARIABLE, "CANCELLED");
        engineTasks.getObject().complete(link.get().engineTaskId(), variables);
    }

    /** Delegation of process-managed tasks rejects in v1 (§9) — explicit, not silent. */
    public void assertDelegatable(UUID taskId) {
        if (registry.linkByTask(taskId).isPresent()) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "process-managed tasks cannot be delegated in v1 (§9) — Flowable's "
                            + "single-task model does not map to replacement-task chains; "
                            + "reassign instead");
        }
    }

    /** Cancel an open inbox row whose engine side is gone (removal, termination). */
    @Transactional
    public void cancelTaskRow(UUID tenantId, UUID taskId, String reason) {
        if (tenantId == null || taskId == null) {
            return;
        }
        tasks.cancelProcessTask(tenantId, taskId, reason);
    }

    // --- helpers ---

    private UUID tenantOf(TaskEntity task) {
        try {
            return task.getTenantId() == null ? null : UUID.fromString(task.getTenantId());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String variable(String processInstanceId, String name) {
        Object value = runtime.getObject().getVariable(processInstanceId, name);
        return value == null ? null : String.valueOf(value);
    }

    static UUID literalUuid(String value) {
        if (value == null || value.isBlank() || value.contains("${")) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
