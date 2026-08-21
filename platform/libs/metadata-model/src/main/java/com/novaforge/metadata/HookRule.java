package com.novaforge.metadata;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An event-hook rule (PHASE-3 §2): a trigger + a flow-IR step graph, record scope.
 * Triggers v1: {@code beforeSave | afterSave | beforeDelete | afterDelete}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HookRule(String name, String trigger, FlowStep flow) {

    public static final java.util.Set<String> TRIGGERS =
            java.util.Set.of("beforeSave", "afterSave", "beforeDelete", "afterDelete");
}
