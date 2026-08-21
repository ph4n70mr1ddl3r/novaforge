package com.novaforge.metadata;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A field {@code default}. Two authored forms (PHASE-1 §5):
 * <ul>
 *   <li>{@code {"value": <static>}} — a static value applied at create;
 *   <li>{@code {"sequence": "<definition>"}} — a sequence reference drawn exactly once
 *       at create in the write path's defaults step, before validations. The only
 *       authored surface that draws a sequence (expressions are pure — PHASE-2 Annex A);
 *   <li>{@code {"expression": "…"}} — a shared-DSL expression evaluated at the same
 *       defaults step (Phase 3 activation — PHASE-3 §3; a sequence reference is not
 *       an expression: allocation is a side effect, which the purity rule forbids).
 * </ul>
 */
public sealed interface DefaultValue permits DefaultValue.Static, DefaultValue.SequenceReference,
        DefaultValue.ExpressionDefault {

    @JsonValue
    Object json();

    /** Static default value; type-checked against the field type like any input. */
    record Static(Object value) implements DefaultValue {
        @Override
        public Object json() {
            return java.util.Map.of("value", value == null ? "" : value);
        }
    }

    /** Reference to an app-scoped sequence definition (settings branch). */
    record SequenceReference(String sequence) implements DefaultValue {
        public SequenceReference {
            if (sequence == null || sequence.isBlank()) {
                throw new IllegalArgumentException("sequence reference must name a definition");
            }
        }

        @Override
        public Object json() {
            return java.util.Map.of("sequence", sequence);
        }
    }

    /** A shared-DSL expression default, evaluated before validations (PHASE-3 §3). */
    record ExpressionDefault(String expression) implements DefaultValue {
        public ExpressionDefault {
            if (expression == null || expression.isBlank()) {
                throw new IllegalArgumentException("expression default must not be blank");
            }
        }

        @Override
        public Object json() {
            return java.util.Map.of("expression", expression);
        }
    }

    @JsonCreator
    static DefaultValue from(java.util.Map<String, Object> node) {
        if (node == null) {
            return null;
        }
        if (node.containsKey("sequence")) {
            return new SequenceReference((String) node.get("sequence"));
        }
        if (node.containsKey("expression")) {
            return new ExpressionDefault((String) node.get("expression"));
        }
        if (node.containsKey("value")) {
            return new Static(node.get("value"));
        }
        throw new IllegalArgumentException(
                "default must be {\"value\": …}, {\"sequence\": \"…\"}, or {\"expression\": \"…\"}: " + node);
    }
}
