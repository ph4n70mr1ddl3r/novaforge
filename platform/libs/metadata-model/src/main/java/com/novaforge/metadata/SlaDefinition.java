package com.novaforge.metadata;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * An SLA definition (PHASE-4 §6): wall-clock duration from task {@code createdAt},
 * scoped to a task type plus a match expression over the task's scope bindings
 * ({@code entity}, {@code type}, {@code transition} — the PHASE-2 Annex A slot
 * bindings; the spec's own example matches {@code transition == 'DRAFT->SUBMITTED'}).
 * For approvals the transition is the state-machine edge of the write that
 * suspended the flow ({@code PRIOR->NEW}, empty when the triggering write changed
 * no state); BPMN-bridge tasks carry no transition context and bind empty.
 * {@code warnAt} is a fraction of {@code target}
 * (0.8 = warn at 80%); a matching definition takes precedence over a
 * {@code requestApproval} step's own {@code timeout}/{@code escalateTo} — the
 * governed overlay beats the inline default — and {@code warnAt: null} disables the
 * warn timer outright.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SlaDefinition(
        String id,
        Scope scope,
        String target,
        Double warnAt,
        OnBreach onBreach) {

    /** Which tasks the SLA governs: type + a match expression over scope bindings. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Scope(String taskType, String match) {
    }

    /** What a breach does: escalate to a role (replacement task, §6), notify, or both. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OnBreach(String escalateTo,
                           @com.fasterxml.jackson.annotation.JsonProperty("notify")
                           Boolean notifyFlag) {

        public boolean notifyOn() {
            return !Boolean.FALSE.equals(notifyFlag);
        }
    }

    /**
     * The scope bindings a {@code match} expression evaluates against:
     * {@code entity} (the qualified entity key), {@code type} (the task type), and
     * {@code transition} ({@code FROM->TO} on approval tasks, empty otherwise).
     */
    public static Map<String, Object> bindings(String entityKey, String taskType,
                                               String transition) {
        return Map.of("entity", entityKey == null ? "" : entityKey,
                "type", taskType == null ? "" : taskType,
                "transition", transition == null ? "" : transition);
    }
}
