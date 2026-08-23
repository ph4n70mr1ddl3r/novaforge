package com.novaforge.runtime.engine.query;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Query DSL v1 (PHASE-1 §5): structured JSON, never raw SQL. Operators: {@code and/or}
 * nesting plus leaves {@code eq ne in gt gte lt lte contains isNull} ({@code contains} on
 * text fields only). Aggregates: {@code count sum avg min max} with optional
 * {@code groupBy}. Paging: offset + total count, max page size 200.
 */
public final class QueryModel {

    public static final int MAX_PAGE_SIZE = 200;
    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final Set<String> OPERATORS =
            Set.of("eq", "ne", "in", "gt", "gte", "lt", "lte", "contains", "isNull");

    public enum SortDir { asc, desc }

    /** Filter tree: internal {@code and}/{@code or} nodes, operator leaves otherwise. */
    public sealed interface Filter {
        record Composite(String op, List<Filter> children) implements Filter {
        }

        record Leaf(String field, String op, Object value) implements Filter {
        }
    }

    public record Sort(String field, SortDir dir) {
    }

    public record Page(int size, long offset) {
    }

    public record ListQuery(Filter filter, List<Sort> sort, Page page) {
    }

    public enum AggregateOp { count, sum, avg, min, max }

    public record Aggregate(AggregateOp op, String field, String alias) {
    }

    /**
     * One group-by level (PHASE-5 §3): a plain field, or the same field bucketed by
     * ordered platform expressions — first matching bucket wins (branch semantics),
     * and the bucketing lowers to a CASE expression inside the aggregate pipeline,
     * never client-side shaping. {@code asOf} on the query binds {@code today()} in
     * bucket expressions — the run's governing clock (a suite's frozen clock pins
     * deterministic buckets, PHASE-3 §7).
     */
    public record GroupBy(String field, List<Bucket> buckets) {

        public GroupBy(String field) {
            this(field, List.of());
        }

        public GroupBy {
            buckets = buckets == null ? List.of() : List.copyOf(buckets);
        }

        public boolean bucketed() {
            return !buckets.isEmpty();
        }
    }

    /** A bucket of a bucketed group-by: the label is the grouped output value. */
    public record Bucket(String label, String expression) {
    }

    /** Aggregate query (POST /{entity}/query). */
    public record AggregateQuery(Filter filter, List<GroupBy> groupBy,
                                 List<Aggregate> aggregates, java.time.LocalDate asOf) {

        public AggregateQuery(Filter filter, List<GroupBy> groupBy, List<Aggregate> aggregates) {
            this(filter, groupBy, aggregates, null);
        }
    }

    public record QueryResult(List<Map<String, Object>> rows, long total) {
    }

    public record AggregateResult(List<String> groupBy, List<Map<String, Object>> rows) {
    }

    private QueryModel() {
    }
}
