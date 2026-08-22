package com.novaforge.metadata.api;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.common.error.ProblemErrors;
import com.novaforge.expression.Expression;
import com.novaforge.expression.ExpressionException;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.EntityDefinition;
import com.novaforge.metadata.FlowStep;
import com.novaforge.metadata.HookRule;
import com.novaforge.metadata.ScriptDefinition;
import java.util.HashMap;
import java.util.HashSet;
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
     * {@code entity} and {@code type} (SlaDefinition.bindings).
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
                                java.util.Set.of("entity", "type"), true));
            } catch (ExpressionException e) {
                findings.add(new ProblemErrors.FieldError(scope,
                        sla.scope().match() + " — " + e.getMessage(), sla.scope().match()));
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
                if (path == null || entity.relationship(path).isEmpty()) {
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
                    if (targetEntity.get().field(String.valueOf(entry.getKey())).isEmpty()) {
                        errors.add(new ProblemErrors.FieldError(where,
                                "template field must exist on " + target + ": " + entry.getKey(),
                                entry.getKey()));
                    }
                }
                if ("updateRecord".equals(step.op()) && step.param("recordId") == null) {
                    errors.add(new ProblemErrors.FieldError(where,
                            "updateRecord requires recordId (a ${…} template)", null));
                }
            }
            case "publishEvent" -> {
                String name = step.param("name");
                if (name == null || !name.matches("[a-z][a-zA-Z0-9.]*")) {
                    errors.add(new ProblemErrors.FieldError(where,
                            "publishEvent requires a dotted event name: " + name, name));
                }
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
                // Phase 4 activation (§4): approvers (a role reference or a user list)
                // and mode are compile-checked; the optional onReject subgraph rides
                // the step's body and is checked like any graph fragment.
                Object approvers = step.params().get("approvers");
                if (!(approvers instanceof String) && !(approvers instanceof java.util.List<?> list
                        && !list.isEmpty())) {
                    errors.add(new ProblemErrors.FieldError(where,
                            "requestApproval requires approvers — a role reference or a "
                                    + "non-empty user list", approvers));
                }
                String mode = step.param("mode");
                if (mode != null && !"any".equals(mode) && !"all".equals(mode)) {
                    errors.add(new ProblemErrors.FieldError(where,
                            "requestApproval mode must be any or all: " + mode, mode));
                }
            }
            default -> {
                // callConnector: grammar-fixed; execution activates with Phase 6.
            }
        }
        if (!"branch".equals(step.op())
                && (step.next() == null || !stepsById.containsKey(step.next()))
                && !isTerminal(step)) {
            errors.add(new ProblemErrors.FieldError(where,
                    "step must chain to a known step id: " + step.next(), step.next()));
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
            Expression.parse(source)
                    .compileCheck(Expression.CompilePolicy.recordContext(fields, true));
        } catch (ExpressionException e) {
            errors.add(new ProblemErrors.FieldError(where,
                    source + " — " + e.getMessage(), source));
        }
    }

    private FlowStep byId(String id) {
        return id == null ? null : stepsById.get(id);
    }
}
