package com.novaforge.metadata;

import com.fasterxml.jackson.annotation.JsonCreator;
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
 *
 * <p>Because an explicit {@code warnAt: null} (the disable) and an absent
 * {@code warnAt} (the 0.8 default) are indistinguishable after a plain record
 * binding, deserialization goes through the presence-aware creator below — and
 * {@code warnAt} always serializes, so the authored disable round-trips.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SlaDefinition(
        String id,
        Scope scope,
        String target,
        @JsonInclude(JsonInclude.Include.ALWAYS) Double warnAt,
        OnBreach onBreach) {

    /** The authored-absent {@code warnAt} — the documented default fraction. */
    public static final double DEFAULT_WARN_AT = 0.8;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static SlaDefinition fromJson(tools.jackson.databind.JsonNode node) {
        return new SlaDefinition(
                node.path("id").asString(null),
                node.hasNonNull("scope")
                        ? DefinitionParser.mapper().treeToValue(node.get("scope"), Scope.class)
                        : null,
                node.path("target").asString(null),
                warnAtOf(node),
                node.hasNonNull("onBreach")
                        ? DefinitionParser.mapper().treeToValue(node.get("onBreach"), OnBreach.class)
                        : null);
    }

    /** Absent authors the 0.8 default; an explicit null is the authored disable. */
    private static Double warnAtOf(tools.jackson.databind.JsonNode node) {
        if (!node.has("warnAt")) {
            return DEFAULT_WARN_AT;
        }
        tools.jackson.databind.JsonNode warnAt = node.get("warnAt");
        return warnAt.isNull() ? null : Double.valueOf(warnAt.asDouble());
    }

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
