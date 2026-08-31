package com.novaforge.workflow.process;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.expression.Expression;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Process starts (PHASE-4 §9): the event-start leg (a matching spine event starts
 * the process — filter evaluated against the record's current state, dedupe on the
 * event id so at-least-once redelivery collapses, the dedupe row riding the same
 * transaction as the engine start) and the manual leg (the Scheduler's
 * {@code processStart} target through the internal surface).
 *
 * <p>Started instances carry the platform variables {@code recordId}, {@code entityId},
 * {@code actorId}, {@code appApiName}, {@code systemPrincipal} — the bridge reads
 * them when a user task lands, and the per-app system principal is the identity the
 * engine acts as (ADR-004 #4).</p>
 */
@Service
public class ProcessStarts {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessStarts.class);

    private final ProcessRegistry registry;
    private final RuntimeService runtime;
    private final RecordFieldsSource records;

    public ProcessStarts(ProcessRegistry registry, RuntimeService runtime,
                         RecordFieldsSource records) {
        this.registry = registry;
        this.runtime = runtime;
        this.records = records;
    }

    /**
     * The event-start leg (§9): one spine event against every deployed
     * subscription. Event-type and entity match first — spine events carry the
     * app-qualified entity key ({@code App.Entity}), subscriptions bind the plain
     * entity name of the owning app, so the comparison qualifies — then a
     * nonblank filter evaluates against the fetched record; false or a gone
     * record skips quietly (a subscription is a matcher, not an error surface).
     */
    @Transactional
    public void onRecordEvent(UUID eventId, String eventType, UUID tenantId,
                              String entityId, UUID recordId, UUID actorId) {
        for (ProcessRegistry.DeployedWorkflow workflow : registry.deployedWithStarts()) {
            if (!workflow.tenantId().equals(tenantId)) {
                continue;
            }
            for (ProcessRegistry.StoredStart start : workflow.eventStarts()) {
                if (!eventType.equals(start.event())
                        || start.entity() == null
                        || !entityId.equals(workflow.app() + "." + start.entity())) {
                    continue;
                }
                if (start.filter() != null && !start.filter().isBlank()
                        && !filterMatches(start.filter(), tenantId, workflow.app(),
                                start.entity(), recordId)) {
                    continue;
                }
                if (!registry.claimStart(eventId, tenantId, workflow.workflowId())) {
                    LOG.debug("event-start redelivery collapsed for event {} workflow {}",
                            eventId, workflow.workflowId());
                    continue;
                }
                // One workflow's failure must not abort the rest of the delivery: a
                // throwing start here used to unwind onRecordEvent, so every
                // subscription after it on the same event silently never started.
                // The claim row rolls back with the failed start, so redelivery
                // retries exactly this workflow.
                try {
                    ProcessInstance instance = runtime.startProcessInstanceByKeyAndTenantId(
                            workflow.workflowId(), recordId == null ? null : recordId.toString(),
                            variables(tenantId, workflow.app(), entityId, recordId, actorId),
                            tenantId.toString());
                    registry.recordInstance(eventId, workflow.workflowId(), instance.getId());
                    LOG.info("event-start fired workflow {} on {} {}/{} → instance {}",
                            workflow.workflowId(), eventType, entityId, recordId,
                            instance.getId());
                } catch (RuntimeException e) {
                    LOG.error("event-start for workflow {} on event {} failed (redelivery "
                            + "will retry this workflow)", workflow.workflowId(), eventId, e);
                }
            }
        }
    }

    /**
     * The manual leg — the Scheduler's {@code processStart} target (§7/§9): a
     * deployed workflow of the named app, started with the caller's variables.
     */
    @Transactional
    public String start(UUID tenantId, String app, String process, UUID recordId,
                        Map<String, Object> variables) {
        var deployed = registry.find(tenantId, app, process);
        if (deployed.isEmpty() || !deployed.get().deployed()) {
            throw new PlatformException(PlatformErrorCode.NOT_FOUND,
                    "workflow " + process + " of app " + app + " is not deployed");
        }
        Map<String, Object> vars = variables(tenantId, app,
                variables == null ? null : String.valueOf(variables.get("entityId")),
                recordId, null);
        if (variables != null) {
            vars.putAll(variables);
        }
        ProcessInstance instance = runtime.startProcessInstanceByKeyAndTenantId(
                process, recordId == null ? null : recordId.toString(), vars,
                tenantId.toString());
        return instance.getId();
    }

    private boolean filterMatches(String filter, UUID tenantId, String app,
                                  String entityId, UUID recordId) {
        Map<String, Object> fields = records.fields(tenantId, app, entityId, recordId);
        if (fields == null) {
            return false;   // record gone — nothing to evaluate against
        }
        try {
            Object outcome = Expression.parse(filter)
                    .evaluate(Expression.Bindings.of(fields), java.time.Clock.systemUTC());
            return Boolean.TRUE.equals(outcome);
        } catch (RuntimeException e) {
            LOG.error("event-start filter failed for {} on {}/{}: {}", filter, entityId,
                    recordId, e.getMessage());
            return false;
        }
    }

    private static Map<String, Object> variables(UUID tenantId, String app, String entityId,
                                                 UUID recordId, UUID actorId) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("tenantId", tenantId.toString());
        variables.put("appApiName", app);
        variables.put("entityId", entityId);
        variables.put("recordId", recordId == null ? null : recordId.toString());
        variables.put("actorId", actorId == null ? null : actorId.toString());
        variables.put("systemPrincipal", UUID.nameUUIDFromBytes(
                ("system:" + app).getBytes()).toString());
        return variables;
    }
}
