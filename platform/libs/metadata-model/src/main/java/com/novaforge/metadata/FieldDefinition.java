package com.novaforge.metadata;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A field definition (schema v0, PHASE-1 §3). Common attributes per ARCHITECTURE.md §3;
 * {@code formula}/{@code rollup} and expression {@code validations} are schema-accepted
 * but inert until Phase 3 — the slots exist so Phase 3 activates them without a schema
 * change (ADR-008's grammar-fixed-activates-later pattern).
 *
 * @param uniqueness tenant-scoped over live rows only; lowered to a partial unique index
 *                   on the promoted column at publish (PHASE-1 §6)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FieldDefinition(
        String apiName,
        String label,
        @JsonProperty("label_i18n") java.util.Map<String, String> labelI18n,
        FieldType type,
        Boolean required,
        Boolean uniqueness,
        Boolean readonly,
        Integer length,
        Integer precision,
        Integer scale,
        String group,
        @JsonProperty("default") DefaultValue defaultValue,
        String target,
        java.util.List<String> values,
        String currency,
        String formula,
        String rollup) {

    public FieldDefinition {
        values = values == null ? java.util.List.of() : java.util.List.copyOf(values);
        labelI18n = labelI18n == null ? java.util.Map.of() : java.util.Map.copyOf(labelI18n);
    }

    public boolean requiredOn() {
        return Boolean.TRUE.equals(required);
    }

    public boolean uniqueOn() {
        return Boolean.TRUE.equals(uniqueness);
    }

    public boolean readonlyOn() {
        return Boolean.TRUE.equals(readonly);
    }

    /** Convenience factory for the common minimal shape. */
    public static FieldDefinition of(String apiName, FieldType type) {
        return new FieldDefinition(apiName, null, null, type, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }
}
