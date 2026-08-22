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
}
