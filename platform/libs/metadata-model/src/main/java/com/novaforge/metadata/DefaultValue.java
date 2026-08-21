package com.novaforge.metadata;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A field {@code default}. Two authored forms (PHASE-1 §5):
 * <ul>
 *   <li>{@code {"value": <static>}} — a static value applied at create;
 *   <li>{@code {"sequence": "<definition>"}} — a sequence reference drawn exactly once
 *       at create in the write path's defaults step, before validations. The only
 *       authored surface that draws a sequence (expressions are pure — PHASE-2 Annex A).
 * </ul>
 */
public sealed interface DefaultValue permits DefaultValue.Static, DefaultValue.SequenceReference {

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

    @JsonCreator
    static DefaultValue from(java.util.Map<String, Object> node) {
        if (node == null) {
            return null;
        }
        if (node.containsKey("sequence")) {
            return new SequenceReference((String) node.get("sequence"));
        }
        if (node.containsKey("value")) {
            return new Static(node.get("value"));
        }
        throw new IllegalArgumentException(
                "default must be {\"value\": …} or {\"sequence\": \"…\"}: " + node);
    }
}
