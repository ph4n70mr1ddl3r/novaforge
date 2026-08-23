package com.novaforge.metadata;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * A report definition (PHASE-5 §3): filters, grouped aggregates over projection-
 * promoted fields, and optional bucketed group-bys — versioned, promoted metadata that
 * compiles to Data Runtime query/aggregate calls, <em>never raw SQL</em>
 * (ARCHITECTURE.md §2.7). Buckets are authored as platform expressions over the bound
 * entity's record context ({@code today()} admissible — aging inputs compute at run
 * time against the governing clock, never stored: PHASE-3 §3) and lower to
 * branch-style CASE expressions inside the aggregate pipeline, not client-side
 * shaping.
 *
 * <p>Save-time validation pins: bound entity resolves; filter/group-by/aggregate
 * fields exist; aggregate fields are numeric (decimal sums stay BigDecimal — PLAN.md
 * §1's money rule); group-by fields are projection-promoted (reporting rides the
 * §4 materialized path) or the definition is rejected with guidance; bucket labels
 * are unique and non-empty. Publish-time compilation checks every bucket expression
 * against the JVM engine <em>and</em> the SQL lowering (an expression that cannot
 * lower — {@code round()}, collections, method calls — is an authoring error at
 * save, never a run-time surprise).</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportDefinition(
        String id,
        String entity,
        String label,
        @JsonProperty("label_i18n") Map<String, String> labelI18n,
        List<Filter> filters,
        List<GroupBy> groupBy,
        List<AggregateField> aggregates,
        DrillThrough drillThrough) {

    public ReportDefinition {
        labelI18n = labelI18n == null ? Map.of() : Map.copyOf(labelI18n);
        filters = filters == null ? List.of() : List.copyOf(filters);
        groupBy = groupBy == null ? List.of() : List.copyOf(groupBy);
        aggregates = aggregates == null ? List.of() : List.copyOf(aggregates);
    }

    /** Report ids are stable API names: a letter/underscore, then word characters. */
    public static final java.util.regex.Pattern REPORT_KEY =
            java.util.regex.Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    /** The v1 aggregate op set (the Data Runtime's aggregate vocabulary, §3). */
    public static final java.util.Set<String> AGGREGATE_OPS =
            java.util.Set.of("count", "sum", "avg", "min", "max");

    /** The query-DSL leaf operators a saved filter may carry (PHASE-1 §5). */
    public static final java.util.Set<String> FILTER_OPS = java.util.Set.of(
            "eq", "ne", "in", "gt", "gte", "lt", "lte", "contains", "isNull");

    /**
     * A saved filter — the query DSL's leaf shape ({@code op} from {@link #FILTER_OPS}).
     * Saved filters are defaults: run params may tighten (add or narrow), never loosen
     * past the actor's own sharing-rule row filters, which always apply (§4/§8).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Filter(String field, String op, Object value) {
    }

    /**
     * One group-by level: a plain promoted field, or the same field bucketed by
     * ordered expressions — multi-field group-by is v1's pivot (§3). The bucketed
     * groupBy rides its source field's promotion; the bucket itself computes
     * in-pipeline.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GroupBy(String field, List<Bucket> buckets) {

        public GroupBy {
            buckets = buckets == null ? List.of() : List.copyOf(buckets);
        }

        public boolean bucketed() {
            return !buckets.isEmpty();
        }
    }

    /**
     * A bucket: first matching expression wins (branch semantics — the §3 aging shape
     * depends on it). {@code expression} compiles in the record context of the bound
     * entity with the clock admissible.
     */
    public record Bucket(String label, String expression) {
    }

    /** An aggregate: {@code op} from {@link #AGGREGATE_OPS}; count takes no field. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AggregateField(String op, String field, String alias) {
    }

    /**
     * Drill-through (§5): links a result row to the runtime record list, carrying the
     * row's effective filters as a query-DSL payload when {@code carryFilters}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DrillThrough(String entity, Boolean carryFilters) {
    }
}
