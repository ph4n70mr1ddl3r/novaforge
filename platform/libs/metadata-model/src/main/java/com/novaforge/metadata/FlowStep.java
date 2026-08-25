package com.novaforge.metadata;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * One node of a flow-IR step graph (PHASE-3 §2, ADR-008 #1): {@code {id, op, params,
 * next}} — {@code branch} carries {@code onTrue}/{@code onFalse} instead of {@code next};
 * {@code iterate} wraps a {@code body} over a relationship path. The graph is a DAG
 * (cycles rejected at publish); the runtime executes compiled graphs, never re-parsing.
 *
 * <p>Primitive set v1 — executable: {@code setField, createRecord, updateRecord,
 * publishEvent, branch, iterate, transitionState} (Phase 4 activation — a guarded
 * field write through the write path's state-machine check); grammar-fixed
 * (compile-checked, activation in a later phase): {@code requestApproval} (Phase 4
 * T5 — the durable-suspension leg), {@code callConnector} (Phase 6). Additions are
 * versioned platform features, never per-app code.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FlowStep(
        String id,
        String op,
        Map<String, Object> params,
        String next,
        String onTrue,
        String onFalse,
        FlowStep body) {

    public FlowStep {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** The v1 primitive set (ADR-008 #2's closed list). */
    public static final java.util.Set<String> OPS = java.util.Set.of(
            "setField", "createRecord", "updateRecord", "publishEvent", "branch",
            "iterate", "requestApproval", "transitionState", "callConnector");

    /** Ops whose execution arrives with a later phase (grammar-fixed now). */
    public static final java.util.Set<String> DEFERRED_OPS = java.util.Set.of(
            "requestApproval", "callConnector");

    public String param(String name) {
        Object value = params.get(name);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * The {@code requestApproval} approvers discriminator (PHASE-4 §4: "a role
     * reference or an expression resolving to users") — one rule, shared by the
     * publish compiler and the runtime executor so the two can never disagree.
     * A string whose <em>root identifier</em> (the name before the first dot, or the
     * whole string) names a field of the bound entity — or the injected {@code id} —
     * is an expression resolving against the record (a lookup walked to a user id,
     * a user-list field, …); every other string is a role reference. A role that
     * collides with a field name is therefore shadowed — the compiler's guidance
     * says to rename one.
     */
    public static boolean approversIsExpression(String approvers, EntityDefinition entity) {
        if (approvers == null || approvers.isBlank()) {
            return false;
        }
        // the leading identifier of the string ("manager.owner" → "manager",
        // "manager + something" → "manager") — paths and arities both route through it
        var match = java.util.regex.Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*")
                .matcher(approvers.trim());
        if (!match.find()) {
            return false;
        }
        String root = match.group();
        return "id".equals(root) || entity.field(root).isPresent();
    }
}
