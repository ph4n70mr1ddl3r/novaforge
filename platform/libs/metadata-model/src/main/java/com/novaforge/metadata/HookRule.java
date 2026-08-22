package com.novaforge.metadata;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An event-hook rule (PHASE-3 §2): a trigger + a flow-IR step graph, record scope.
 * Triggers v1: {@code beforeSave | afterSave | beforeDelete | afterDelete}. The body
 * is <em>either</em> a compiled flow <em>or</em> a script artifact (PHASE-3 §6,
 * ADR-003) — scripts attach to the same triggers as flows; exactly one of
 * {@code flow}/{@code script} is present (publish-enforced).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HookRule(String name, String trigger, FlowStep flow, ScriptDefinition script) {

    public static final java.util.Set<String> TRIGGERS =
            java.util.Set.of("beforeSave", "afterSave", "beforeDelete", "afterDelete");
}
