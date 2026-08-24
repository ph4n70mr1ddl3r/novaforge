package com.novaforge.metadata;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An entity definition as one document (fields, relationships, indexes, validations —
 * ARCHITECTURE.md §3). Entities are authored per app; {@code id} is assigned by the
 * Metadata Service on create.
 *
 * <p>Validation scope for record-level rules is a Phase 3 activation; the slots are
 * schema-accepted now (inert per PHASE-1 §1 out-of-scope).</p>
 *
 * <p>Phase 7 harvests (PHASE-7 §3): {@code freezeOnTerminal} makes a terminal state
 * freeze the record's whole document (all writes reject with {@code RECORD_FROZEN} —
 * the journal's append-only in fact, not convention); {@code periodLock} binds a
 * dated write to a period entity so writes dating into a {@code CLOSED} period reject
 * with {@code PERIOD_LOCKED}. Both land behind the same save/publish machinery as
 * every other definition attribute.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EntityDefinition(
        String id,
        String apiName,
        String label,
        @JsonProperty("label_i18n") Map<String, String> labelI18n,
        String displayField,
        String module,
        Boolean freezeOnTerminal,
        PeriodLock periodLock,
        List<FieldDefinition> fields,
        List<RelationshipDefinition> relationships,
        List<ValidationRule> validations,
        List<HookRule> hooks,
        List<IndexDefinition> indexes) {

    public EntityDefinition {
        fields = fields == null ? List.of() : List.copyOf(fields);
        relationships = relationships == null ? List.of() : List.copyOf(relationships);
        validations = validations == null ? List.of() : List.copyOf(validations);
        hooks = hooks == null ? List.of() : List.copyOf(hooks);
        indexes = indexes == null ? List.of() : List.copyOf(indexes);
        labelI18n = labelI18n == null ? Map.of() : Map.copyOf(labelI18n);
    }

    /** Pre-harvest constructor (Phase 1–6 drafts authored no freezing/period locks). */
    public EntityDefinition(String id, String apiName, String label, Map<String, String> labelI18n,
                            String displayField, String module, List<FieldDefinition> fields,
                            List<RelationshipDefinition> relationships,
                            List<ValidationRule> validations, List<IndexDefinition> indexes) {
        this(id, apiName, label, labelI18n, displayField, module, null, null, fields,
                relationships, validations, List.of(), indexes);
    }

    /** Pre-hook constructor (Phase 1 drafts authored no hooks). */
    public EntityDefinition(String id, String apiName, String label, Map<String, String> labelI18n,
                            String displayField, String module, List<FieldDefinition> fields,
                            List<RelationshipDefinition> relationships,
                            List<ValidationRule> validations, List<HookRule> hooks,
                            List<IndexDefinition> indexes) {
        this(id, apiName, label, labelI18n, displayField, module, null, null, fields,
                relationships, validations, hooks, indexes);
    }

    public Optional<FieldDefinition> field(String name) {
        return fields.stream().filter(f -> f.apiName().equals(name)).findFirst();
    }

    public Optional<RelationshipDefinition> relationship(String name) {
        return relationships.stream().filter(r -> r.apiName().equals(name)).findFirst();
    }

    /** Whether the terminal-state document freeze applies (requires a bound machine). */
    public boolean freezesOnTerminal() {
        return Boolean.TRUE.equals(freezeOnTerminal);
    }

    /** Copy helpers for immutable edits (builder-style; used by tests and PATCH handling). */
    public static EntityDefinition copyWithApiName(EntityDefinition e, String apiName) {
        return new EntityDefinition(e.id(), apiName, e.label(), e.labelI18n(), e.displayField(),
                e.module(), e.freezeOnTerminal(), e.periodLock(), e.fields(), e.relationships(),
                e.validations(), e.hooks(), e.indexes());
    }

    public static EntityDefinition copyWithField(EntityDefinition e, int index, FieldDefinition field) {
        java.util.ArrayList<FieldDefinition> fields = new java.util.ArrayList<>(e.fields());
        fields.set(index, field);
        return new EntityDefinition(e.id(), e.apiName(), e.label(), e.labelI18n(), e.displayField(),
                e.module(), e.freezeOnTerminal(), e.periodLock(), List.copyOf(fields),
                e.relationships(), e.validations(), e.hooks(), e.indexes());
    }

    public static EntityDefinition copyWithRelationship(EntityDefinition e, int index,
                                                        RelationshipDefinition relationship) {
        java.util.ArrayList<RelationshipDefinition> relationships = new java.util.ArrayList<>(e.relationships());
        relationships.set(index, relationship);
        return new EntityDefinition(e.id(), e.apiName(), e.label(), e.labelI18n(), e.displayField(),
                e.module(), e.freezeOnTerminal(), e.periodLock(), e.fields(),
                List.copyOf(relationships), e.validations(), e.hooks(), e.indexes());
    }

    public interface Edit {
        EntityDefinition apply(EntityDefinition e);
    }

    public static EntityDefinition copyWith(EntityDefinition e, Edit edit) {
        return edit.apply(e);
    }

    /** Inert until Phase 3: a validation rule slot (expression + message). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ValidationRule(String name, String scope, String expression, String message) {
    }

    /**
     * Period locking (PHASE-7 §3.2, resolved per §8's harvest spec): a write to the
     * bound entity resolves its {@code dateField} against the period entity's date
     * range; a date inside a {@code closedStatus} period rejects with
     * {@code PERIOD_LOCKED}. The status is app metadata (the {@code AccountingPeriod}
     * state machine of §4) — the platform never special-cases "CLOSED"; it only reads
     * the configured value. Period resolution is a date-range lookup (the resolved
     * §8 pin), not a {@code periodId} reference: documents carry dates, not period
     * pointers, and re-dating a document re-resolves its period under this same check.
     *
     * @param entity       the period entity (date-ranged rows with a status field)
     * @param dateField    the locked entity's date field resolved on every write
     * @param fromField    the period's range start (default {@code startDate})
     * @param toField      the period's range end (default {@code endDate})
     * @param statusField  the period's status field (default {@code status})
     * @param closedStatus the status value activating the lock (default {@code CLOSED})
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PeriodLock(
            String entity,
            String dateField,
            String fromField,
            String toField,
            String statusField,
            String closedStatus) {

        public String from() {
            return fromField == null ? "startDate" : fromField;
        }

        public String to() {
            return toField == null ? "endDate" : toField;
        }

        public String status() {
            return statusField == null ? "status" : statusField;
        }

        public String closed() {
            return closedStatus == null ? "CLOSED" : closedStatus;
        }
    }

    /** Entity-level index declaration; promoted fields lower to projection columns (§12 Q3). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IndexDefinition(List<String> fields, Boolean unique) {

        public IndexDefinition {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }

        public boolean uniqueOn() {
            return Boolean.TRUE.equals(unique);
        }
    }
}
