package com.novaforge.metadata.api;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.common.error.ProblemErrors;
import com.novaforge.expression.Expression;
import com.novaforge.expression.ExpressionException;
import com.novaforge.expression.ExpressionSql;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.EntityDefinition;
import com.novaforge.metadata.FlowStep;
import com.novaforge.metadata.HookRule;
import com.novaforge.metadata.ScriptDefinition;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The publish-time flow compiler (PHASE-3 §2, ADR-008's Metadata-Service role):
 * reference/type-checks every step — ops are the closed v1 set, the graph is a DAG,
 * setField fields and guard expressions resolve and compile (the Phase 2 JVM engine),
 * record templates address fields that exist on their target entity, iterate paths are
 * relationships — so the runtime executes checked graphs and never re-parses per
 * request.
 */
final class FlowCompiler {

    private final Map<String, FlowStep> stepsById = new HashMap<>();

    private FlowCompiler() {
    }

    /** Compiles every hook of every entity; throws VALIDATION_FAILED with all findings. */
    static void compile(AppDefinition app) {
        java.util.List<ProblemErrors.FieldError> findings = new java.util.ArrayList<>();
        for (EntityDefinition entity : app.entities()) {
            for (HookRule hook : entity.hooks()) {
                new FlowCompiler().checkHook(app, entity, hook, findings);
            }
        }
        checkStateMachines(app, findings);
        checkSlas(app, findings);
        checkSharingRules(app, findings);
        checkWorkflows(app, findings);
        checkReports(app, findings);
        checkIntegrations(app, findings);
        if (!findings.isEmpty()) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "hook compilation failed", new ProblemErrors(findings, java.util.List.of()));
        }
    }

    /**
     * State-machine guards compile at publish (PHASE-4 §3) — the same JVM engine and
     * record-context policy as every other expression slot.
     */
    private static void checkStateMachines(AppDefinition app,
                                           java.util.List<ProblemErrors.FieldError> findings) {
        for (com.novaforge.metadata.StateMachineDefinition machine : app.stateMachines()) {
            var entity = app.entity(machine.entity() == null ? "" : machine.entity());
            if (entity.isEmpty()) {
                continue;   // structural rule — the save validator reports it
            }
            String scope = "stateMachine[" + machine.id() + "]";
            for (com.novaforge.metadata.StateMachineDefinition.Transition transition
                    : machine.transitions()) {
                if (transition.guard() != null) {
                    new FlowCompiler().checkExpression(transition.guard(),
                            scope + ".transition[" + transition.from() + "->" + transition.to() + "]",
                            entity.get(), findings);
                }
            }
        }
    }

    /**
     * SLA match expressions compile at publish (PHASE-4 §6) — the scope bindings are
     * {@code entity}, {@code type}, and {@code transition} (SlaDefinition.bindings;
     * the Annex A slot bindings — §6's example matches
     * {@code transition == 'DRAFT->SUBMITTED'}).
     */
    private static void checkSlas(AppDefinition app,
                                  java.util.List<ProblemErrors.FieldError> findings) {
        for (com.novaforge.metadata.SlaDefinition sla : app.slas()) {
            if (sla.scope() == null || sla.scope().match() == null) {
                continue;
            }
            String scope = "sla[" + sla.id() + "].scope.match";
            try {
                Expression.parse(sla.scope().match())
                        .compileCheck(Expression.CompilePolicy.recordContext(
                                java.util.Set.of("entity", "type", "transition"), true));
            } catch (ExpressionException e) {
                findings.add(new ProblemErrors.FieldError(scope,
                        sla.scope().match() + " — " + e.getMessage(), sla.scope().match()));
            }
        }
    }

    /**
     * Sharing-rule criteria compile at publish (PHASE-4 §10) — the bound entity's
     * fields are the binding set.
     */
    private static void checkSharingRules(AppDefinition app,
                                          java.util.List<ProblemErrors.FieldError> findings) {
        for (com.novaforge.metadata.SharingRuleDefinition rule
                : app.permissionSet().sharingRules()) {
            if (!com.novaforge.metadata.SharingRuleDefinition.CRITERIA.equals(rule.type())
                    || rule.criteria() == null) {
                continue;
            }
            var entity = app.entity(rule.entity() == null ? "" : rule.entity());
            if (entity.isEmpty()) {
                continue;   // structural rule — the save validator reports it
            }
            String scope = "sharingRule[" + rule.entity() + "].criteria";
            try {
                Set<String> fields = new HashSet<>();
                entity.get().fields().forEach(f -> fields.add(f.apiName()));
                Expression.parse(rule.criteria())
                        .compileCheck(Expression.CompilePolicy.recordContext(fields, true));
            } catch (ExpressionException e) {
                findings.add(new ProblemErrors.FieldError(scope,
                        rule.criteria() + " — " + e.getMessage(), rule.criteria()));
            }
        }
    }

    /**
     * Event-start filter expressions compile at publish (PHASE-4 §9) — the record
     * context of the subscription's bound entity, the same engine and policy as
     * every other expression slot.
     */
    private static void checkWorkflows(AppDefinition app,
                                       java.util.List<ProblemErrors.FieldError> findings) {
        for (com.novaforge.metadata.WorkflowDefinition workflow : app.workflows()) {
            for (com.novaforge.metadata.WorkflowDefinition.EventStart start
                    : workflow.eventStarts()) {
                if (start.filter() == null || start.filter().isBlank()) {
                    continue;   // a filterless subscription matches every event
                }
                var entity = app.entity(start.entity() == null ? "" : start.entity());
                if (entity.isEmpty()) {
                    continue;   // structural rule — the save validator reports it
                }
                String scope = "workflow[" + workflow.id() + "].eventStarts.filter";
                new FlowCompiler().checkExpression(start.filter(), scope, entity.get(),
                        findings);
            }
        }
    }

    /**
     * Report bucket expressions compile at publish (PHASE-5 §3) twice over: through
     * the JVM engine (record context, clock admissible — aging inputs compute at run
     * time) <em>and</em> through the SQL lowering (ExpressionSql over the same
     * promotion-aware field resolver the Data Runtime lowers with). An expression
     * that parses but cannot lower — {@code round()}, collections, method calls —
     * is an authoring error here, never a run-time surprise.
     */
    private static void checkReports(AppDefinition app,
                                     java.util.List<ProblemErrors.FieldError> findings) {
        for (com.novaforge.metadata.ReportDefinition report : app.reports()) {
            var entity = app.entity(report.entity() == null ? "" : report.entity());
            if (entity.isEmpty()) {
                continue;   // structural rule — the save validator reports it
            }
            Set<String> fields = new HashSet<>();
            entity.get().fields().forEach(f -> fields.add(f.apiName()));
            var resolver = com.novaforge.metadata.ExpressionFields.resolver(entity.get());
            for (com.novaforge.metadata.ReportDefinition.GroupBy group : report.groupBy()) {
                for (com.novaforge.metadata.ReportDefinition.Bucket bucket : group.buckets()) {
                    String scope = "report[" + report.id() + "].groupBy[" + group.field()
                            + "].bucket[" + bucket.label() + "]";
                    try {
                        Expression.parse(bucket.expression())
                                .compileCheck(Expression.CompilePolicy.recordContext(
                                        fields, true));
                    } catch (ExpressionException e) {
                        findings.add(new ProblemErrors.FieldError(scope,
                                bucket.expression() + " — " + e.getMessage(),
                                bucket.expression()));
                        continue;
                    }
                    try {
                        ExpressionSql.checkLowerable(Expression.parse(bucket.expression()),
                                resolver);
                    } catch (ExpressionException e) {
                        findings.add(new ProblemErrors.FieldError(scope,
                                bucket.expression() + " — does not lower to SQL (buckets "
                                        + "compute in the aggregate pipeline, §3): "
                                        + e.getMessage(), bucket.expression()));
                    }
                }
            }
        }
    }

    /**
     * The Integrations branch compiles at publish (PHASE-6 §4/§5): outbound webhook
     * event filters compile against the spine envelope (one binding set, both
     * directions of authoring — the dispatch scan evaluates them at run time), and
     * nothing more — connector/import shapes are save-validated, and their runtime
     * resolution (credentials, secrets) happens in the Integration Service.
     */
    private static void checkIntegrations(AppDefinition app,
                                          java.util.List<ProblemErrors.FieldError> findings) {
        for (com.novaforge.metadata.WebhookDefinition webhook : app.integrations().webhooks()) {
            if (!com.novaforge.metadata.WebhookDefinition.OUTBOUND.equals(webhook.direction())
                    || webhook.events() == null || webhook.events().isBlank()) {
                continue;
            }
            String scope = "webhook[" + webhook.id() + "].events";
            try {
                Expression.parse(webhook.events())
                        .compileCheck(Expression.CompilePolicy.recordContext(
                                com.novaforge.metadata.WebhookDefinition.EVENT_BINDINGS, true));
            } catch (ExpressionException e) {
                findings.add(new ProblemErrors.FieldError(scope,
                        webhook.events() + " — " + e.getMessage(), webhook.events()));
            }
        }
    }

    private void checkHook(AppDefinition app, EntityDefinition entity, HookRule hook,
                           java.util.List<ProblemErrors.FieldError> errors) {
        String scope = entity.apiName() + ".hooks[" + hook.name() + "]";
        if (hook.trigger() == null || !HookRule.TRIGGERS.contains(hook.trigger())) {
            errors.add(new ProblemErrors.FieldError(scope + ".trigger",
                    "trigger must be one of " + HookRule.TRIGGERS, hook.trigger()));
            return;
        }
        if (hook.script() != null) {
            if (hook.flow() != null) {
                errors.add(new ProblemErrors.FieldError(scope,
                        "a hook is either a flow or a script, not both", hook.name()));
                return;
            }
            checkScript(hook.script(), scope, errors);
            return;
        }
        if (hook.flow() == null || hook.flow().id() == null) {
            errors.add(new ProblemErrors.FieldError(scope,
                    "hook requires a flow entry step or a script", null));
            return;
        }
        indexSteps(hook.flow(), scope, entity, errors);
        Set<String> visited = new HashSet<>();
        Set<String> stack = new HashSet<>();
        checkStep(hook.flow(), app, entity, scope, errors, visited, stack);
    }

    /**
     * Script hooks (PHASE-3 §6, ADR-003): the escape hatch attaches to the same
     * triggers as flows. The publish check bounds the artifact — v0's language set and
     * size; semantic caps (CPU/heap/statements) live in the sandbox, which enforces
     * them per execution.
     */
    private static void checkScript(ScriptDefinition script, String scope,
                                    java.util.List<ProblemErrors.FieldError> errors) {
        if (script.language() == null || !ScriptDefinition.LANGUAGES.contains(script.language())) {
            errors.add(new ProblemErrors.FieldError(scope + ".script.language",
                    "script language must be one of " + ScriptDefinition.LANGUAGES,
                    script.language()));
        }
        if (script.source() == null || script.source().isBlank()) {
            errors.add(new ProblemErrors.FieldError(scope + ".script.source",
                    "script source must not be blank", null));
        } else if (script.source().length() > ScriptDefinition.MAX_SOURCE_CHARS) {
            errors.add(new ProblemErrors.FieldError(scope + ".script.source",
                    "script source exceeds " + ScriptDefinition.MAX_SOURCE_CHARS + " characters",
                    script.source().length()));
        }
    }

    /** Indexes the graph (incl. iterate bodies) for target resolution. */
    private void indexSteps(FlowStep step, String scope, EntityDefinition entity,
                            java.util.List<ProblemErrors.FieldError> errors) {
        if (step == null) {
            return;
        }
        if (step.id() == null || step.id().isBlank()) {
            errors.add(new ProblemErrors.FieldError(scope,
                    "every step requires an id", null));
            return;
        }
        if (stepsById.put(step.id(), step) != null) {
            errors.add(new ProblemErrors.FieldError(scope,
                    "duplicate step id: " + step.id(), step.id()));
        }
        indexSteps(step.body(), scope, entity, errors);
    }

    private void checkStep(FlowStep step, AppDefinition app, EntityDefinition entity,
                           String scope, java.util.List<ProblemErrors.FieldError> errors, Set<String> visited,
                           Set<String> stack) {
        if (step == null) {
            return;
        }
        if (stack.contains(step.id())) {
            errors.add(new ProblemErrors.FieldError(scope,
                    "cycle detected at step " + step.id() + " — flows are DAGs", step.id()));
            return;
        }
        if (!visited.add(step.id())) {
            return;   // diamond: already checked, not a cycle
        }
        stack.add(step.id());
        checkStepShape(step, app, entity, scope, errors);
        checkStep(byId(step.next()), app, entity, scope, errors, visited, stack);
        if ("branch".equals(step.op())) {
            checkStep(byId(step.onTrue()), app, entity, scope, errors, visited, stack);
            checkStep(byId(step.onFalse()), app, entity, scope, errors, visited, stack);
        }
        if ("iterate".equals(step.op()) || "requestApproval".equals(step.op())) {
            checkStep(step.body(), app, entity, scope, errors, visited, stack);
        }
        stack.remove(step.id());
    }

    private void checkStepShape(FlowStep step, AppDefinition app, EntityDefinition entity,
                                String scope, java.util.List<ProblemErrors.FieldError> errors) {
        String where = scope + "." + step.id();
        if (step.op() == null || !FlowStep.OPS.contains(step.op())) {
            errors.add(new ProblemErrors.FieldError(where,
                    "unknown op '" + step.op() + "' — the v1 set is " + FlowStep.OPS, step.op()));
            return;
        }
        switch (step.op()) {
            case "setField" -> {
                String field = step.param("field");
                if (field == null || entity.field(field).isEmpty()) {
                    errors.add(new ProblemErrors.FieldError(where,
                            "setField targets an existing field: " + field, field));
                }
                checkExpression(step.param("expression"), where, entity, errors);
            }
            case "branch" -> {
                checkExpression(step.param("guard"), where, entity, errors);
                if (step.onTrue() == null && step.onFalse() == null) {
                    errors.add(new ProblemErrors.FieldError(where,
                            "branch requires onTrue/onFalse targets", null));
                }
            }
            case "iterate" -> {
                String path = step.param("path");
                if (path != null && path.startsWith("connector.")) {
                    // Connector-response iteration (the scheduled pull shape,
                    // PHASE-7 §5): the array a callConnector step lands in scope —
                    // the addressed step must be one, the response path itself is
                    // provider-shaped and never compile-checked
                    checkStepReference(path, where, errors);
                    if (step.body() == null) {
                        errors.add(new ProblemErrors.FieldError(where,
                                "iterate requires a body", null));
                    }
                } else if (path == null || entity.relationship(path).isEmpty()) {
                    errors.add(new ProblemErrors.FieldError(where,
                            "iterate path must be a relationship: " + path, path));
                } else if (step.body() == null) {
                    errors.add(new ProblemErrors.FieldError(where,
                            "iterate requires a body", null));
                }
            }
            case "createRecord", "updateRecord" -> {
                String target = step.param("entity");
                var targetEntity = target == null ? java.util.Optional.<EntityDefinition>empty()
                        : app.entity(target);
                if (targetEntity.isEmpty()) {
                    errors.add(new ProblemErrors.FieldError(where,
                            "target entity must resolve within the app: " + target, target));
                    return;
                }
                Object template = step.params().get("template");
                if (!(template instanceof Map<?, ?> map)) {
                    errors.add(new ProblemErrors.FieldError(where,
                            step.op() + " requires a record template map", template));
                    return;
                }
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    String key = String.valueOf(entry.getKey());
                    var relationship = targetEntity.get().relationship(key);
                    if (relationship.isPresent()) {
                        // §3.3 (the G-1 harvest): an inline children array — each row is
                        // a field map on the child entity, template-resolved per row at
                        // run time. create-only: the flow update path merges fields.
                        if (!"createRecord".equals(step.op())) {
                            errors.add(new ProblemErrors.FieldError(where,
                                    "updateRecord templates address fields — inline children "
                                            + "arrays are the create path's: " + key, key));
                            continue;
                        }
                        checkInlineChildren(relationship.get(), entry.getValue(),
                                where + "." + key, app, errors);
                    } else if (targetEntity.get().field(key).isEmpty()) {
                        errors.add(new ProblemErrors.FieldError(where,
                                "template field must exist on " + target + ": " + key,
                                key));
                    }
                }
                scanStepReferences(template, where, errors);
                if ("updateRecord".equals(step.op())) {
                    if (step.param("recordId") == null) {
                        errors.add(new ProblemErrors.FieldError(where,
                                "updateRecord requires recordId (a ${…} template)", null));
                    } else {
                        // the recordId template may address a created record — the
                        // create-then-update chain (§3.3)
                        scanStepReferences(step.param("recordId"), where, errors);
                    }
                }
            }
            case "publishEvent" -> {
                String name = step.param("name");
                if (name == null || !name.matches("[a-z][a-zA-Z0-9.]*")) {
                    errors.add(new ProblemErrors.FieldError(where,
                            "publishEvent requires a dotted event name: " + name, name));
                }
                scanStepReferences(step.params().get("payload"), where, errors);
            }
            case "transitionState" -> {
                // Phase 4 activation (§3): a guarded field write through the same
                // write-path check — the entity must carry a machine and the target
                // must be one of its states.
                String to = step.param("to");
                var machine = app.stateMachineFor(entity.apiName());
                if (machine.isEmpty()) {
                    errors.add(new ProblemErrors.FieldError(where,
                            "transitionState requires a state machine bound to "
                                    + entity.apiName(), null));
                } else if (to == null || machine.get().state(to).isEmpty()) {
                    errors.add(new ProblemErrors.FieldError(where,
                            "transitionState target must be a state of "
                                    + machine.get().id() + ": " + to, to));
                }
            }
            case "requestApproval" -> {
                // Phase 4 activation (§4): approvers (a role reference, an expression
                // resolving to users, or a literal user list) and mode are
                // compile-checked; the optional onReject subgraph rides
                // the step's body and is checked like any graph fragment.
                Object approvers = step.params().get("approvers");
                if (!(approvers instanceof String) && !(approvers instanceof java.util.List<?> list
                        && !list.isEmpty())) {
                    errors.add(new ProblemErrors.FieldError(where,
                            "requestApproval requires approvers — a role reference, an "
                                    + "expression resolving to users, or a non-empty user list",
                            approvers));
                } else if (approvers instanceof String text
                        && com.novaforge.metadata.FlowStep.approversIsExpression(text, entity)) {
                    // the expression form (§4): its root identifier names a field of the
                    // bound entity, so it resolves against the record — a lookup walked to
                    // a user id, a user-list field, and the like. Plain names stay role
                    // references; the runtime applies the same discriminator.
                    try {
                        Expression.parse(text).compileCheck(Expression.CompilePolicy
                                .recordContext(recordFields(entity), true));
                    } catch (ExpressionException e) {
                        errors.add(new ProblemErrors.FieldError(where,
                                "requestApproval approvers expression — " + e.getMessage(), text));
                    }
                }
                String mode = step.param("mode");
                if (mode != null && !"any".equals(mode) && !"all".equals(mode)) {
                    errors.add(new ProblemErrors.FieldError(where,
                            "requestApproval mode must be any or all: " + mode, mode));
                }
            }
            case "callConnector" -> {
                // Phase 6 activation (§4): the connector and its operation must exist in
                // the app's Integrations branch, and the step's template must resolve
                // against the step context (the record's fields + id) — compiled here
                // so the runtime executes checked calls only.
                String connectorId = step.param("connector");
                String operation = step.param("operation");
                var connector = connectorId == null
                        ? java.util.Optional.<com.novaforge.metadata.ConnectorDefinition>empty()
                        : app.connector(connectorId);
                if (connector.isEmpty()) {
                    errors.add(new ProblemErrors.FieldError(where,
                            "callConnector targets a connector of the app: " + connectorId,
                            connectorId));
                    return;
                }
                if (operation == null || connector.get().operation(operation).isEmpty()) {
                    errors.add(new ProblemErrors.FieldError(where,
                            "callConnector operation must be one of "
                                    + connector.get().id() + "'s operations: " + operation,
                            operation));
                    return;
                }
                Object template = step.params().get("template");
                if (!(template instanceof Map<?, ?> map)) {
                    errors.add(new ProblemErrors.FieldError(where,
                            "callConnector requires a template map (operation params)", template));
                    return;
                }
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getValue() instanceof String text
                            && text.startsWith("${") && text.endsWith("}")) {
                        String reference = text.substring(2, text.length() - 1);
                        if (reference.startsWith("connector.") || reference.startsWith("record.")) {
                            checkStepReference(reference, where, errors);
                        } else if (!reference.equals("id") && entity.field(reference).isEmpty()) {
                            errors.add(new ProblemErrors.FieldError(where,
                                    "callConnector template reference must resolve on "
                                            + entity.apiName() + ": " + reference, reference));
                        }
                    }   // plain values are constants — they trivially resolve
                }
            }
            default -> {
                // every op of the closed v1 set is checked above
            }
        }
        if (!"branch".equals(step.op())
                && (step.next() == null || !stepsById.containsKey(step.next()))
                && !isTerminal(step)) {
            errors.add(new ProblemErrors.FieldError(where,
                    "step must chain to a known step id: " + step.next(), step.next()));
        }
    }

    /**
     * Inline children rows of a createRecord template (§3.3): each row's keys must be
     * fields of the relationship's target entity — the same shape the write path's
     * splitChildren accepts, compile-checked here so the runtime executes checked
     * graphs only.
     */
    private void checkInlineChildren(com.novaforge.metadata.RelationshipDefinition relationship,
                                     Object rows, String where, AppDefinition app,
                                     java.util.List<ProblemErrors.FieldError> errors) {
        var child = app.entity(relationship.target() == null ? "" : relationship.target());
        if (child.isEmpty()) {
            errors.add(new ProblemErrors.FieldError(where,
                    "relationship target must resolve within the app: "
                            + relationship.target(), relationship.target()));
            return;
        }
        if (!(rows instanceof List<?> list)) {
            errors.add(new ProblemErrors.FieldError(where,
                    "an inline children template must be an array of row objects", rows));
            return;
        }
        for (Object row : list) {
            if (!(row instanceof Map<?, ?> rowMap)) {
                errors.add(new ProblemErrors.FieldError(where,
                        "inline child rows must be objects: " + row, row));
                continue;
            }
            for (Map.Entry<?, ?> field : rowMap.entrySet()) {
                if (child.get().field(String.valueOf(field.getKey())).isEmpty()) {
                    errors.add(new ProblemErrors.FieldError(where,
                            "inline child field must exist on " + relationship.target()
                                    + ": " + field.getKey(), field.getKey()));
                }
            }
        }
    }

    /** Finds every `${connector.…}` / `${record.…}` reference in a template value tree. */
    private static final java.util.regex.Pattern CONNECTOR_REF =
            java.util.regex.Pattern.compile("\\$\\{(connector\\.[^}]+)}");
    private static final java.util.regex.Pattern RECORD_REF =
            java.util.regex.Pattern.compile("\\$\\{(record\\.[^}]+)}");

    /**
     * Step-result references — the two namespaces later steps address earlier steps'
     * outcomes through:
     * <ul>
     *   <li>{@code ${connector.<stepId>.<path…>}} — the settled response of a
     *       {@code callConnector} step (the versioned growth the scheduled pull
     *       rides, PHASE-6 §3);</li>
     *   <li>{@code ${record.<stepId>.<path…>}} — the created record of a
     *       {@code createRecord} step (§3.3, the G-1 harvest), {@code id} included.</li>
     * </ul>
     * The step reference is compile-checked — the addressed step must be of the
     * addressing op and belong to this graph; the path below it is run-time shaped
     * (provider document / created view) and resolves or resolves empty.
     */
    private void checkStepReference(String reference, String where,
                                    java.util.List<ProblemErrors.FieldError> errors) {
        if (reference.startsWith("connector.")) {
            checkResultReference(reference, where, "connector", "callConnector", errors);
        } else {
            checkResultReference(reference, where, "record", "createRecord", errors);
        }
    }

    /** The shared shape of both step-result namespaces: {@code <ns>.<stepId>.<path…>}. */
    private void checkResultReference(String reference, String where, String namespace,
                                      String op, java.util.List<ProblemErrors.FieldError> errors) {
        String[] parts = reference.split("\\.", 3);
        if (parts.length < 2 || parts[1].isBlank()) {
            errors.add(new ProblemErrors.FieldError(where,
                    namespace + " references address a " + op + " step: "
                            + namespace + ".<stepId>.<path>", reference));
            return;
        }
        FlowStep target = stepsById.get(parts[1]);
        if (target == null || !op.equals(target.op())) {
            errors.add(new ProblemErrors.FieldError(where,
                    namespace + " reference must address a " + op + " step of this flow: "
                            + parts[1], parts[1]));
        }
    }

    /** Deep-scans a template value tree for step-result references (both namespaces). */
    private void scanStepReferences(Object template, String where,
                                    java.util.List<ProblemErrors.FieldError> errors) {
        if (template instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                scanStepReferences(entry.getValue(), where, errors);
            }
        } else if (template instanceof List<?> list) {
            for (Object item : list) {
                scanStepReferences(item, where, errors);
            }
        } else if (template instanceof String text) {
            java.util.regex.Matcher connector = CONNECTOR_REF.matcher(text);
            while (connector.find()) {
                checkStepReference(connector.group(1), where, errors);
            }
            java.util.regex.Matcher record = RECORD_REF.matcher(text);
            while (record.find()) {
                checkStepReference(record.group(1), where, errors);
            }
        }
    }

    private static boolean isTerminal(FlowStep step) {
        return switch (step.op()) {
            case "requestApproval", "transitionState", "callConnector", "setField",
                    "createRecord", "updateRecord", "publishEvent" -> step.next() == null;
            case "iterate" -> false;   // body ends the path; next still required after
            default -> false;
        };
    }

    private void checkExpression(String source, String where, EntityDefinition entity,
                                 java.util.List<ProblemErrors.FieldError> errors) {
        if (source == null) {
            errors.add(new ProblemErrors.FieldError(where,
                    "expression is required", null));
            return;
        }
        try {
            Set<String> fields = new HashSet<>();
            entity.fields().forEach(f -> fields.add(f.apiName()));
            Expression parsed = Expression.parse(source);
            parsed.compileCheck(Expression.CompilePolicy.recordContext(fields, true));
            // PHASE-3 §2's type-aware leg: Annex A arithmetic violations the static
            // field types can name reject here, at save/publish — never as a
            // runtime 500 on the first matching record.
            parsed.arithmeticCheck(com.novaforge.metadata.ExpressionTypes.of(entity));
        } catch (ExpressionException e) {
            errors.add(new ProblemErrors.FieldError(where,
                    source + " — " + e.getMessage(), source));
        }
    }

    private FlowStep byId(String id) {
        return id == null ? null : stepsById.get(id);
    }

    /** The record-context binding set the approvers-expression form compiles against:
     *  the entity's field apiNames plus the executor's injected {@code id}. */
    private static Set<String> recordFields(EntityDefinition entity) {
        Set<String> fields = new HashSet<>();
        entity.fields().forEach(f -> fields.add(f.apiName()));
        fields.add("id");
        return fields;
    }
}
