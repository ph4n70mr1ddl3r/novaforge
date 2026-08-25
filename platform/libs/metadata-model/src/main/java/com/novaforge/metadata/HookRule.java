package com.novaforge.metadata;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An event-hook rule (PHASE-3 §2): a trigger + a flow-IR step graph, record scope.
 * Triggers v1: {@code beforeSave | afterSave | beforeDelete | afterDelete}; the
 * vocabulary's first versioned growth adds {@code scheduled} (PHASE-7 §5's
 * bank-feed shape — a scheduled flow driving a connector): a recordless trigger
 * the write path never matches (no write carries it) and only the Scheduler's
 * by-name firing executes (PHASE-4 §7 — the addressed hook's authored trigger is
 * irrelevant to a recordless firing, so pre-{@code scheduled} hooks fire by name
 * unchanged). The body is <em>either</em> a compiled flow <em>or</em> a script
 * artifact (PHASE-3 §6, ADR-003) — scripts attach to the same triggers as flows;
 * exactly one of {@code flow}/{@code script} is present (publish-enforced).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HookRule(String name, String trigger, FlowStep flow, ScriptDefinition script) {

    public static final java.util.Set<String> TRIGGERS = java.util.Set.of(
            "beforeSave", "afterSave", "beforeDelete", "afterDelete", "scheduled");
}
