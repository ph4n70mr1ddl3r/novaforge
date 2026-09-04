package com.novaforge.runtime.engine.hook;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.EntityDefinition;
import com.novaforge.metadata.FieldDefinition;
import com.novaforge.metadata.FlowStep;
import com.novaforge.metadata.HookRule;
import com.novaforge.metadata.RelationshipDefinition;
import com.novaforge.runtime.engine.metadata.EntityResolver.EntityHandle;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executes hooks on the write path (PHASE-3 §2, ADR-008): compiled flow-IR step
 * graphs, and script artifacts — the ADR-008 escape hatch (PHASE-3 §6, ADR-003) —
 * through the {@link ScriptClient}. Executable primitives: setField (expression),
 * createRecord/updateRecord (${…} record templates), publishEvent (payload template),
 * branch (guard), iterate (relationship body), transitionState (Phase 4: a guarded
 * field write validated by the write path's state-machine check). Grammar-fixed
 * primitives (requestApproval until the suspension leg, callConnector until Phase 6)
 * fail loudly.
 *
 * <p>Failure policy (ARCHITECTURE.md §2.5), uniform for flows and scripts:
 * beforeSave/beforeDelete failure aborts the transaction (the executor throws);
 * afterSave/afterDelete failure is recorded for retry — never lost, never blocks the
 * write.</p>
 */
@Component
public class HookExecutor {

    private final ScriptClient scripts;
    private final ApprovalClient approvals;
    private final ConnectorPort connectors;
    private final io.micrometer.core.instrument.MeterRegistry meters;

    public HookExecutor(ScriptClient scripts, ApprovalClient approvals,
                        ConnectorPort connectors,
                        io.micrometer.core.instrument.MeterRegistry meters) {
        this.scripts = scripts;
        this.approvals = approvals;
        this.connectors = connectors;
        this.meters = meters;
    }

    /** Defensive cap — compiled graphs are DAGs; this guards runaway recursion. */
    public static final int MAX_STEPS = 256;

    /** The Scheduler's synthetic recordless context (PHASE-4 §7). */
    public static final String SCHEDULED_CONTEXT = "scheduled";

    /** Nested engine calls (createRecord/updateRecord) get their own budget. */
    public static final int MAX_DEPTH = 4;

    private static final Pattern TEMPLATE = Pattern.compile("\\$\\{([^}]+)}");

    /** Response-node conversion (connector bindings): JsonNode → plain Java. */
    private static final tools.jackson.databind.json.JsonMapper JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    private static final Logger LOG = LoggerFactory.getLogger(HookExecutor.class);

    /** The runtime's integration point: executes one hook trigger for a record.
     *  The sink arrives per call — the runtime supplies itself, avoiding a bean cycle. */
    public interface HookSink {

        /** Create/update a record as the flow's system principal (nested engine call). */
        Map<String, Object> writeRecord(String entityApiName, Map<String, Object> body,
                                        String recordId, UUID systemPrincipal, int depth);

        /** Publish an app event to the spine (outbox). */
        void publishAppEvent(String name, Map<String, Object> payload, UUID tenantId,
                             String entityKey, UUID recordId, UUID systemPrincipal);

        /** The children of a record for iterate (query path, live rows). */
        List<Map<String, Object>> children(UUID tenantId, String appApiName,
                                           String parentEntityApiName, String relationship,
                                           UUID parentRecordId);

        /**
         * One record's canonical field view by id (the §3.7 bind read): the
         * in-transaction store read — the same observable state a caller's query
         * would see, without leaving the enclosing write. An absent record binds
         * the empty view; the graph's guards treat it as null (the script's
         * {@code item == null → no-op} shape).
         */
        Map<String, Object> record(UUID tenantId, String appApiName,
                                   String entityApiName, String recordId);
    }

    /** Result of a trigger run: aborted (before-hooks) or recorded retries (after). */
    public record Outcome(boolean aborted, List<Retry> retryQueue) {

        /** One failed after-hook, queued for the spine-driven retry leg (§2). */
        public record Retry(String hook, String kind, String error) {
        }

        static final Outcome CLEAN = new Outcome(false, List.of());
    }

    public Outcome runTrigger(AppDefinition app, EntityHandle handle, UUID tenantId,
                              UUID recordId, Map<String, Object> data, String trigger,
                              UUID systemPrincipal, UUID initiatingActor, HookSink sink) {
        return runTrigger(app, handle, tenantId, recordId, data, trigger,
                systemPrincipal, initiatingActor, null, sink);
    }

    /**
     * The Scheduler's firing (PHASE-4 §7): exactly the addressed hook, by name — its
     * authored trigger is irrelevant to a recordless firing, and no other hook on
     * the entity runs. Failures propagate to the scheduled surface: the run row and
     * its {@code scheduler.job.run} event are the audible failure record (§7), and
     * the spine's record-scoped retry leg is not this context's machinery.
     */
    public void runScheduled(AppDefinition app, EntityHandle handle, UUID tenantId,
                             HookRule hook, UUID systemPrincipal, HookSink sink) {
        // A fire key scopes this invocation's connector deliveries: the delivery
        // dedupe is permanent, so a recordless pull keyed only by hook+step would
        // answer every later fire with the first fire's recorded outcome — the
        // provider would never be called again (§7's registry fires forever). The
        // per-invocation key keeps each fire fresh; write-path keys stay
        // record-scoped so after-hook retries still collapse (§4).
        runOne(app, handle, tenantId, null, new LinkedHashMap<>(), SCHEDULED_CONTEXT,
                hook, systemPrincipal, null, null, UUID.randomUUID().toString(), sink);
    }

    /**
     * The write path's entry with the triggering write's state-machine edge
     * ({@code PRIOR->NEW}, null when no state changed) — the {@code transition} SLA
     * match binding requestApproval suspensions carry (PHASE-4 §6 / PHASE-2
     * Annex A).
     */
    public Outcome runTrigger(AppDefinition app, EntityHandle handle, UUID tenantId,
                              UUID recordId, Map<String, Object> data, String trigger,
                              UUID systemPrincipal, UUID initiatingActor,
                              String transition, HookSink sink) {
        List<Outcome.Retry> retries = new java.util.ArrayList<>();
        for (HookRule hook : handle.entity().hooks()) {
            if (!trigger.equals(hook.trigger())) {
                continue;
            }
            try {
                runOne(app, handle, tenantId, recordId, data, trigger, hook,
                        systemPrincipal, initiatingActor, transition, null, sink);
            } catch (RuntimeException e) {
                // before-hooks abort (§2.5); after-hooks ride the spine for retry —
                // never lost, never blocking the write (§2 failure policy).
                if (trigger.startsWith("before")) {
                    throw e;   // abort the transaction (§2.5)
                }
                LOG.warn("after-hook {} failed on {}: {} (recorded for retry)",
                        hook.name(), handle.entityKey(), e.getMessage());
                retries.add(new Outcome.Retry(hook.name(),
                        hook.script() == null ? "flow" : "script", e.getMessage()));
            }
        }
        return retries.isEmpty() ? Outcome.CLEAN : new Outcome(false, retries);
    }

    /**
     * The page-model {@code runFlow} action's leg (PHASE-2 §4 / PHASE-3 §8): runs
     * one named flow on demand against the record's current state — the per-app
     * system principal (declarative flows are reviewed artifacts, §13 Q1), the
     * initiating human recorded as context (PHASE-4 §13). Flow hooks only: script
     * hooks stay write-path caller-context (ADR-003 #2) and reject with guidance.
     * Persistence is the flow's own — {@code setField} shapes the in-memory view
     * exactly like the retry leg; anything the flow must keep it writes through its
     * own {@code updateRecord} steps (the standard hook sink, guarded writes).
     */
    public void runManual(AppDefinition app, EntityHandle handle, UUID tenantId,
                          UUID recordId, Map<String, Object> data, String hookName,
                          UUID systemPrincipal, UUID initiatingActor, HookSink sink) {
        HookRule hook = handle.entity().hooks().stream()
                .filter(h -> hookName.equals(h.name()))
                .findFirst()
                .orElseThrow(() -> new PlatformException(PlatformErrorCode.NOT_FOUND,
                        "hook " + hookName + " not found on " + handle.entityKey()));
        if (hook.script() != null) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "runFlow targets flow hooks — script hooks run caller-context on the "
                            + "write path (ADR-003 #2), never on demand");
        }
        runOne(app, handle, tenantId, recordId, data, "manual", hook,
                systemPrincipal, initiatingActor, null, null, sink);
    }

    /**
     * The retry leg's entry point (§2 failure policy): re-drives one named after-hook
     * against the record's current state, as the per-app system principal — the
     * identical context to the original execution (§13 Q1). Returns false when the hook
     * no longer exists in the published definition (republish drift) — the caller parks
     * the retry rather than loop on a hook that can never run again.
     */
    public boolean runOneByName(AppDefinition app, EntityHandle handle, UUID tenantId,
                                UUID recordId, Map<String, Object> data, String trigger,
                                String hookName, UUID systemPrincipal, HookSink sink) {
        for (HookRule hook : handle.entity().hooks()) {
            if (trigger.equals(hook.trigger()) && hookName.equals(hook.name())) {
                runOne(app, handle, tenantId, recordId, data, trigger, hook,
                        systemPrincipal, null, null, null, sink);
                return true;
            }
        }
        return false;
    }

    /**
     * The suspension leg's re-entry (PHASE-4 §4): a resolved approval resumes the
     * compiled graph — after the requestApproval step on approve, the step's own
     * onReject subgraph on reject — as the per-app system principal against the
     * record's current state. The triggering write committed long before; resume
     * writes go through the standard hook sink (guarded writes, no bypass).
     */
    public void resumeFrom(AppDefinition app, EntityHandle handle, UUID tenantId,
                           UUID recordId, Map<String, Object> data, HookRule hook,
                           String afterStep, FlowStep onReject, boolean approved,
                           UUID systemPrincipal, HookSink sink) {
        Context context = new Context(app, handle, tenantId, recordId, data,
                systemPrincipal, 0, sink);
        context.resume = true;
        context.index(hook.flow());
        FlowStep entry = approved ? context.step(afterStep) : onReject;
        if (entry == null) {
            return;   // approve with no remainder, or no onReject authored
        }
        int executed = 0;
        while (entry != null && executed++ < MAX_STEPS) {
            entry = executeStep(entry, context);
        }
    }

    private void runOne(AppDefinition app, EntityHandle handle, UUID tenantId,
                        UUID recordId, Map<String, Object> data, String trigger,
                        HookRule hook, UUID systemPrincipal, UUID initiatingActor,
                        String transition, String fireKey, HookSink sink) {
        String kind = hook.script() == null ? "flow" : "script";
        counted(handle, trigger, kind);
        // §9 hook-duration histogram — the write path's per-trigger latency shape.
        io.micrometer.core.instrument.Timer.Sample sample =
                io.micrometer.core.instrument.Timer.start(meters);
        try {
            if (hook.script() != null) {
                if (SCHEDULED_CONTEXT.equals(trigger)) {
                    // the Scheduler's script target (§7): recordless, per-app system
                    // principal — the engine's scheduled surface binds the context
                    runScheduledScriptHook(tenantId, handle, hook);
                } else {
                    runScriptHook(handle, recordId, data, trigger, hook);
                }
            } else {
                Context context = new Context(app, handle, tenantId, recordId, data,
                        systemPrincipal, 0, sink);
                context.initiatingActor = initiatingActor;
                context.currentHook = hook.name();
                context.transition = transition;
                context.fireKey = fireKey;
                // before-hooks merge into the pending write (the enclosing write
                // persists the field and the write path's state-machine check
                // validates it); every other trigger persists through its own
                // guarded write — an afterSave transition mutates an
                // already-persisted record, so an in-memory merge alone would be a
                // silent no-op on the store
                context.preWrite = trigger != null && trigger.startsWith("before");
                context.index(hook.flow());
                executeIndexed(hook.flow(), context);
            }
        } finally {
            sample.stop(io.micrometer.core.instrument.Timer
                    .builder("novaforge.hook.duration")
                    .tag("trigger", trigger).tag("kind", kind)
                    .description("Hook execution duration (flows and scripts alike)")
                    .register(meters));
        }
    }

    /**
     * Hook-kind telemetry (PHASE-3 §6, ADR-008 #5): flow vs script executions per app
     * version and trigger — the join side of the script-ratio KPI (the Script Engine
     * counts its own executions per app version).
     */
    private void counted(EntityHandle handle, String trigger, String kind) {
        meters.counter("novaforge.hook.executions",
                        "app", handle.appApiName(),
                        "version", String.valueOf(handle.version()),
                        "trigger", trigger, "kind", kind)
                .increment();
    }

    /**
     * The Scheduler's {@code script} target (PHASE-4 §7): a recordless firing as the
     * per-app system principal through the engine's service-gated scheduled surface —
     * {@code $record} absent, {@code $data.query} on the internal system-principal
     * leg. The return value is recorded in the firing's outcome (there is no record
     * to merge into); failures render as the job's failed run, audibly.
     */
    private void runScheduledScriptHook(UUID tenantId, EntityHandle handle, HookRule hook) {
        scripts.executeScheduled(tenantId, handle.appApiName(), handle.version(),
                hook.name(), hook.script());
    }

    /**
     * The escape hatch (PHASE-3 §6): the script runs caller-context through the
     * {@link ScriptClient}. Its return value is the write-back channel — a beforeSave
     * script's returned object merges into the record (declared fields only, mirroring
     * setField's contract; reserved names like the injected {@code id} pass through);
     * after-trigger returns are recorded in the outcome and nothing more. Failure
     * policy is uniform with flows (§2.5): throws before the persist for
     * before-triggers, surfaces as a retry for after-triggers.
     */
    private void runScriptHook(EntityHandle handle, UUID recordId, Map<String, Object> data,
                               String trigger, HookRule hook) {
        Map<String, Object> view = new LinkedHashMap<>(data);
        view.put("id", recordId == null ? null : recordId.toString());
        ScriptClient.ScriptOutcome outcome = scripts.execute(handle.appApiName(),
                handle.version(), hook.name(), trigger, hook.script(), view);
        if (outcome != null && "beforeSave".equals(trigger)
                && outcome.value() instanceof Map<?, ?> merged) {
            merged.forEach((key, value) -> {
                String field = String.valueOf(key);
                if (com.novaforge.metadata.DefinitionValidator.RESERVED_FIELD_NAMES
                        .contains(field)) {
                    return;   // the executor's injected view keys (id) — never declared
                }
                if (handle.entity().field(field).isPresent()) {
                    data.put(field, value);
                } else {
                    throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                            "script hook " + hook.name() + " returned an unknown field: " + field);
                }
            });
        }
    }

    private void executeIndexed(FlowStep entry, Context context) {
        FlowStep step = entry;
        int executed = 0;
        while (step != null) {
            if (executed++ > MAX_STEPS) {
                throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                        "hook exceeded " + MAX_STEPS + " steps — compiled flows are bounded DAGs");
            }
            step = executeStep(step, context);
        }
    }

    private FlowStep executeStep(FlowStep step, Context context) {
        if (step.op() == null) {
            return null;
        }
        switch (step.op()) {
            case "setField" -> {
                String field = step.param("field");
                Object value = context.evaluate(step.param("expression"));
                context.data.put(field, value);
                return byName(context, step.next());
            }
            case "bind" -> {
                // §3.7 (the G-2 harvest): the lookup target's canonical view binds
                // under the lookup field's apiName for the graph's remaining slots —
                // later steps address item.<field> dot-paths. An absent/unresolvable
                // target binds the empty view (guards see null — the fail-open the
                // compile check documents). Runtime re-checks what the compiler
                // pinned: the field exists and is a lookup.
                context.bind(step.param("lookup"));
                return byName(context, step.next());
            }
            case "branch" -> {
                boolean guard = Boolean.TRUE.equals(context.evaluate(step.param("guard")));
                return byName(context, guard ? step.onTrue() : step.onFalse());
            }
            case "createRecord" -> {
                // The G-1 harvest (§3.3, 2026-08-26): the created record enters step
                // scope — later steps address it as ${record.<stepId>.<path…>}, the
                // mirror of the connector-result namespace.
                Map<String, Object> created = context.sink.writeRecord(step.param("entity"),
                        resolveTemplateMap(step.params().get("template"), context),
                        null, context.systemPrincipal, context.depth + 1);
                context.recordResults.put(step.id(), created);
                return byName(context, step.next());
            }
            case "updateRecord" -> {
                context.sink.writeRecord(step.param("entity"),
                        resolveTemplateMap(step.params().get("template"), context),
                        resolveTemplateText(step.param("recordId"), context),
                        context.systemPrincipal, context.depth + 1);
                return byName(context, step.next());
            }
            case "publishEvent" -> {
                Object payload = resolveTemplateValue(step.params().get("payload"), context);
                @SuppressWarnings("unchecked")
                Map<String, Object> payloadMap = payload instanceof Map<?, ?> map
                        ? (Map<String, Object>) map : new LinkedHashMap<String, Object>();
                context.sink.publishAppEvent(step.param("name"), payloadMap, context.tenantId,
                        context.handle.entityKey(), context.recordId, context.systemPrincipal);
                return byName(context, step.next());
            }
            case "iterate" -> {
                String iteratePath = step.param("path");
                if (iteratePath != null && iteratePath.startsWith("connector.")) {
                    // Connector-response iteration (the scheduled pull shape,
                    // PHASE-6 §3's response mapping / PHASE-7 §5): the array a
                    // callConnector step landed in scope becomes the row set —
                    // each object row runs the body as the child overlay, so
                    // createRecord templates bind the row's fields directly.
                    Object rows = context.resolveTemplateValue(iteratePath);
                    if (rows == null) {
                        return byName(context, step.next());   // no array: no rows
                    }
                    if (!(rows instanceof List<?> rowList)) {
                        throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                                "iterate connector path must address an array: " + iteratePath);
                    }
                    for (Object row : rowList) {
                        if (!(row instanceof Map<?, ?> rowMap)) {
                            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                                    "connector response rows must be objects: " + iteratePath);
                        }
                        runIterateBody(step, context.forChild(castRow(rowMap)));
                    }
                    return byName(context, step.next());
                }
                for (Map<String, Object> child : context.sink.children(context.tenantId,
                        context.handle.appApiName(), context.handle.entity().apiName(),
                        iteratePath, context.recordId)) {
                    runIterateBody(step, context.forChild(child));
                }
                return byName(context, step.next());
            }
            case "transitionState" -> {
                // Phase 4 activation (§3): a guarded field write — the write path's
                // state-machine enforcement validates it after the hooks run, the
                // same check every human write goes through. No bypass exists.
                String to = step.param("to");
                var machine = context.app.stateMachineFor(context.handle.entity().apiName());
                if (machine.isEmpty()) {
                    throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                            "transitionState requires a state machine bound to "
                                    + context.handle.entity().apiName());
                }
                if (machine.get().state(to).isEmpty()) {
                    throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                            "unknown state on " + machine.get().id() + ": " + to);
                }
                Object prior = context.data.get(machine.get().stateField());
                context.data.put(machine.get().stateField(), to);
                if (prior != null && !to.equals(String.valueOf(prior))) {
                    // the flow-driven edge becomes this context's transition — a later
                    // requestApproval in the same flow suspends carrying it (the §6
                    // match binding), exactly like a human-driven state change
                    context.transition = prior + "->" + to;
                }
                if (context.resume || !context.preWrite) {
                    // No enclosing write persists the field here — the resume leg
                    // and every post-persist trigger (afterSave/afterDelete) alike
                    // ride the standard guarded write (updateAsPrincipal enforces
                    // the machine; no bypass exists). Found live authoring the
                    // Phase 4 exit journey: afterSave transitions mutated only the
                    // in-memory record map — the persisted record never moved and
                    // the machine never checked the edge.
                    context.sink.writeRecord(context.handle.entity().apiName(),
                            Map.of(machine.get().stateField(), to),
                            context.recordId == null ? null : context.recordId.toString(),
                            context.systemPrincipal, context.depth + 1);
                }
                return byName(context, step.next());
            }
            case "requestApproval" -> {
                // Phase 4 activation (§4): the approval is handed to the Workflow
                // Service (task + suspended instance); execution of this flow ends
                // here — the triggering write commits, resolution re-enters the
                // engine afterward. Never holds the enclosing transaction.
                // Approvers (§4): a role reference, or an expression resolving to
                // users — the shared FlowStep discriminator decides (a root identifier
                // naming a field of the bound entity, or `id`, is the expression form).
                Object approvers = step.params().get("approvers");
                String role = null;
                java.util.List<String> users = null;
                if (approvers instanceof String text
                        && com.novaforge.metadata.FlowStep.approversIsExpression(
                                text, context.handle.entity())) {
                    Object resolved = context.evaluate(text);
                    users = approverUsers(resolved, text);
                } else {
                    role = approvers instanceof String roleText ? roleText : null;
                    users = approvers instanceof List<?> list
                            ? list.stream().map(String::valueOf).toList() : null;
                }
                approvals.request(new ApprovalClient.Suspension(context.tenantId,
                        context.handle.appApiName(), context.handle.entity().apiName(),
                        context.handle.entityKey(), context.recordId,
                        context.currentHook, step.id(),
                        step.next(), step.body(), role, users,
                        step.param("mode") == null ? "any" : step.param("mode"),
                        step.param("timeout"), step.param("escalateTo"),
                        context.initiatingActor, context.transition));
                return null;   // suspended — the remainder runs on resolution
            }
            case "callConnector" -> {
                // Phase 6 activation (§4): a synchronous, bounded (10 s) call through
                // the Integration Service's circuit-breaker/credential machinery — no
                // flow suspension. Failure rides the §2 policy: before-hooks abort,
                // after-hooks retry via the spine. The step's dedupe key scopes
                // idempotency so a retried after-hook collapses onto the recorded
                // delivery instead of double-calling the provider.
                Map<String, Object> template = resolveTemplateMap(step.params().get("template"),
                        context);
                String dedupeKey = context.tenantId + ":" + context.handle.entityKey() + ":"
                        + (context.recordId == null ? "new" : context.recordId) + ":"
                        + context.currentHook + ":" + step.id()
                        + (context.fireKey == null ? "" : ":" + context.fireKey);
                var result = connectors.execute(context.tenantId.toString(),
                        context.handle.appApiName(), step.param("connector"),
                        step.param("operation"), template, dedupeKey);
                context.connectorResults.put(step.id(), result);
                return byName(context, step.next());
            }
            default -> throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "unknown op at runtime: " + step.op());
        }
    }

    /** Runs one iterate body chain under the row/child's context (bounded). */
    private void runIterateBody(FlowStep step, Context childContext) {
        FlowStep body = step.body();
        int executed = 0;
        while (body != null && executed++ < MAX_STEPS) {
            body = executeStep(body, childContext);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castRow(Map<?, ?> row) {
        return (Map<String, Object>) row;
    }

    /**
     * The approvers-expression outcome (§4): a user id or a collection of them —
     * anything else is an authoring error surfaced as problem+json, never a silent
     * empty approver set (SoD's fail-closed path would then reject ambiguously).
     */
    private static java.util.List<String> approverUsers(Object resolved, String expression) {
        if (resolved instanceof String user && !user.isBlank()) {
            return java.util.List.of(user);
        }
        if (resolved instanceof List<?> list && !list.isEmpty()
                && list.stream().allMatch(item -> item instanceof String s && !s.isBlank())) {
            return list.stream().map(String::valueOf).toList();
        }
        throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                "requestApproval approvers expression must resolve to a user id or a list "
                        + "of user ids: " + expression);
    }

    // --- template resolution (${…} — ADR-008 record templates, host-resolved) ---

    /**
     * Deep ${…} resolution (the G-1 harvest, §3.3): strings resolve wherever they sit
     * in the value tree — nested maps and arrays included, so an inline children array
     * inside a createRecord template binds per row. Non-string leaves pass through
     * unchanged (constants), exactly as before; structure is preserved (maps keep
     * their order, arrays their arity).
     */
    private Object resolveTemplateValue(Object template, Context context) {
        if (template instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            map.forEach((key, value) -> resolved.put(String.valueOf(key),
                    resolveTemplateValue(value, context)));
            return resolved;
        }
        if (template instanceof List<?> list) {
            java.util.List<Object> resolved = new java.util.ArrayList<>(list.size());
            for (Object item : list) {
                resolved.add(resolveTemplateValue(item, context));
            }
            return resolved;
        }
        return template instanceof String text ? resolveTemplateText(text, context) : template;
    }

    /** The record-template form (a map by construction — the compiler guarantees it). */
    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveTemplateMap(Object template, Context context) {
        Object resolved = resolveTemplateValue(template, context);
        return resolved instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
    }

    private String resolveTemplateText(String text, Context context) {
        if (text == null) {
            return null;
        }
        Matcher matcher = TEMPLATE.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result,
                    Matcher.quoteReplacement(String.valueOf(context.resolveTemplateValue(matcher.group(1)))));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private FlowStep byName(Context context, String id) {
        return id == null ? null : context.step(id);
    }

    /** Execution context: the record's data + the compiled graph's steps. */
    private final class Context {

        final AppDefinition app;
        final EntityHandle handle;
        final UUID tenantId;
        final UUID recordId;
        final Map<String, Object> data;
        final UUID systemPrincipal;
        final HookSink sink;
        final int depth;
        boolean resume;
        boolean preWrite;
        UUID initiatingActor;
        String currentHook;
        String transition;
        String fireKey;
        private final Map<String, Object> overlay = new HashMap<>();
        private final Map<String, FlowStep> steps = new HashMap<>();
        final Map<String, Object> connectorResults = new HashMap<>();
        final Map<String, Object> recordResults = new HashMap<>();

        Context(AppDefinition app, EntityHandle handle, UUID tenantId, UUID recordId,
                Map<String, Object> data, UUID systemPrincipal, int depth, HookSink sink) {
            this.app = app;
            this.handle = handle;
            this.tenantId = tenantId;
            this.recordId = recordId;
            this.data = data;
            this.systemPrincipal = systemPrincipal;
            this.depth = depth;
            this.sink = sink;
        }

        FlowStep step(String id) {
            FlowStep found = steps.get(id);
            if (found == null) {
                throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                        "hook step not found: " + id + " — the publish compiler rejected such graphs");
            }
            return found;
        }

        void index(FlowStep entry) {
            indexDeep(entry);
        }

        Object evaluate(String expression) {
            if (expression == null) {
                return null;
            }
            Map<String, Object> bindings = new LinkedHashMap<>(data);
            bindings.putAll(overlay);
            bindings.put("id", recordId == null ? null : recordId.toString());
            return com.novaforge.expression.Expression.parse(expression)
                    .evaluate(com.novaforge.expression.Expression.Bindings.of(bindings),
                            java.time.Clock.systemUTC());
        }

        /**
         * §3.7 (the G-2 harvest): binds one lookup field's target record into the
         * graph's expression scope under the lookup field's apiName — later steps
         * read it as dot-paths ({@code item.qtyOnHand}), which the shared
         * {@code Bindings.of} resolver walks through the nested view. In-transaction
         * read (the sink): the same observable state the caller's query surface
         * serves, without leaving the enclosing write.
         */
        void bind(String lookupField) {
            FieldDefinition field = lookupField == null ? null
                    : handle.entity().field(lookupField).orElse(null);
            if (field == null || field.type() != com.novaforge.metadata.FieldType.LOOKUP) {
                throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                        "bind step names a lookup field of " + handle.entity().apiName()
                                + ": " + lookupField + " — the publish compiler rejected such graphs");
            }
            Object raw = data.get(lookupField);
            Map<String, Object> view = java.util.Map.of();
            if (raw != null && !String.valueOf(raw).isBlank()) {
                String id = String.valueOf(raw);
                try {
                    UUID.fromString(id);
                } catch (IllegalArgumentException e) {
                    // a malformed id binds empty (guards see null), like any
                    // unresolvable reference — never an opaque 500; store failures
                    // below propagate (a beforeSave abort, an afterSave retry)
                    LOG.warn("bind {} target id is not a record id: {}", lookupField, id);
                    overlay.put(lookupField, view);
                    return;
                }
                view = sink.record(tenantId, handle.appApiName(), field.target(), id);
            }
            if (view == null) {
                view = java.util.Map.of();
            }
            overlay.put(lookupField, view);
        }

        Object resolveTemplateValue(String path) {
            if (path != null && path.startsWith("connector.")) {
                // Connector-response binding (the versioned growth this shape rides):
                // `connector.<stepId>.<path…>` addresses the settled response of the
                // callConnector step — PHASE-6 §3's response mapping surface for
                // flows. The response path itself is provider-shaped (compile checks
                // the step reference, never the provider's document); an absent step
                // or path resolves empty like every other unresolved reference.
                String[] parts = path.split("\\.", 3);
                if (parts.length >= 2
                        && connectorResults.get(parts[1]) instanceof ConnectorPort.ConnectorResult result) {
                    return walkNode(result.body(), parts.length == 3 ? parts[2] : "");
                }
                return null;
            }
            if (path != null && path.startsWith("record.")) {
                // Step-result binding (§3.3, the G-1 harvest): `record.<stepId>.<path…>`
                // addresses the created record of a createRecord step of this graph —
                // `${record.<stepId>.id}` is the created id. The step reference is
                // compile-checked; an absent path resolves empty like every other
                // unresolved reference.
                String[] parts = path.split("\\.", 3);
                if (parts.length >= 2 && recordResults.get(parts[1]) instanceof Map<?, ?> created) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> view = (Map<String, Object>) created;
                    return parts.length == 3 ? walk(view, parts[2]) : view;
                }
                return null;
            }
            Object direct = walk(data, path);
            if (direct != null) {
                return direct;
            }
            Object overlayValue = walk(overlay, path);
            if (overlayValue != null) {
                return overlayValue;
            }
            return path.equals("id") && recordId != null ? recordId.toString() : null;
        }

        /** Dot-path descent through nested maps (iterate rows may nest). */
        private static Object walk(Map<String, Object> source, String path) {
            if (source == null || path == null) {
                return null;
            }
            Object direct = source.get(path);
            if (direct != null || source.containsKey(path)) {
                return direct;
            }
            if (!path.contains(".")) {
                return null;
            }
            Object node = source;
            for (String segment : path.split("\\.")) {
                if (!(node instanceof Map<?, ?> map) || (node = map.get(segment)) == null) {
                    return null;
                }
            }
            return node;
        }

        /** Dot-path descent through a settled connector response (arrays by index). */
        private static Object walkNode(tools.jackson.databind.JsonNode node, String path) {
            tools.jackson.databind.JsonNode current = node;
            if (path != null && !path.isBlank()) {
                for (String segment : path.split("\\.")) {
                    if (current == null) {
                        return null;
                    }
                    if (current.isArray() && segment.matches("\\d+")) {
                        current = current.get(Integer.parseInt(segment));
                    } else if (current.isObject()) {
                        current = current.get(segment);
                    } else {
                        return null;
                    }
                }
            }
            // A path that walks off the document resolves empty — the selector's
            // null arm — never the raw NPE a null enum selector throws (the same
            // "unresolved reference" stance the step and path lookups above carry;
            // found live: a provider body without the bound key 500'd the flow).
            return switch (current == null ? null : current.getNodeType()) {
                case null -> null;
                case STRING -> current.asText();
                // Numbers bind as their exact decimal — decimalValue() carries the
                // node's full magnitude (DecimalNode/BigIntegerNode included). The
                // old double/long split broke the money rule twice on one line:
                // BigDecimal.valueOf(doubleValue()) re-typed a provider amount past
                // 17 significant digits as its float64 shadow (9999999999999999.99
                // → 1.0E16 — silently wrong money in the record), and
                // Long.valueOf(longValue()) threw a raw JsonNodeException on any
                // JSON integer past 64 bits — an opaque 500 where the binding owed
                // the value. The same rule the reporting cache leg pins for its own
                // JSON re-parse (ReportRunner's CACHE_READ): a platform-owned parse
                // never types money through the binary float.
                case NUMBER -> current.decimalValue();
                case BOOLEAN -> Boolean.valueOf(current.booleanValue());
                case OBJECT, ARRAY -> JSON.convertValue(current, Object.class);
                default -> null;   // null, missing — unresolved, never an error
            };
        }

        Context forChild(Map<String, Object> child) {
            Context childContext = new Context(app, handle, tenantId, recordId, data,
                    systemPrincipal, depth, sink);
            childContext.overlay.putAll(child);
            childContext.transition = transition;
            childContext.currentHook = currentHook;
            childContext.initiatingActor = initiatingActor;
            childContext.fireKey = fireKey;
            childContext.connectorResults.putAll(connectorResults);
            childContext.recordResults.putAll(recordResults);
            return childContext;
        }

        private void indexDeep(FlowStep step) {
            if (step == null) {
                return;
            }
            steps.put(step.id(), step);
            indexDeep(step.body());
        }
    }

    /** Numeric coercion helper for templates that write numeric fields. */
    static BigDecimal numeric(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String text) {
            return new BigDecimal(text);
        }
        throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                "expected a numeric template value");
    }
}
