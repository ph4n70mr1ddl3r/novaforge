package com.novaforge.runtime.engine.query;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Query DSL v1 (PHASE-1 §5): structured JSON, never raw SQL. Operators: {@code and/or}
 * nesting plus leaves {@code eq ne in gt gte lt lte contains isNull} ({@code contains} on
 * text fields only). Aggregates: {@code count sum avg min max} with optional
 * {@code groupBy}. Paging: offset + total count, max page size 200 — plus the §5
 * keyset growth: {@code page.after} seeks past a previous full page's
 * {@code nextAfter} cursor (offset pages keep their per-page count; seek pages skip
 * it and omit {@code total}).
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

    /**
     * Offset paging ({@code offset}, mutually exclusive with {@code after}), or the
     * §5 keyset growth: {@code after} — the opaque {@link SeekCursor} token the
     * previous full page returned as {@code nextAfter}. The parser enforces the
     * exclusivity and the cursor's contract; the lowering turns a present cursor
     * into one seek conjunct and drops the OFFSET tail.
     */
    public record Page(int size, long offset, String after) {

        public Page(int size, long offset) {
            this(size, offset, null);
        }
    }

    public record ListQuery(Filter filter, List<Sort> sort, Page page, SeekCursor cursor) {

        public ListQuery(Filter filter, List<Sort> sort, Page page) {
            this(filter, sort, page, null);
        }

        /** True when the page seeks past a cursor instead of offsetting. */
        public boolean seek() {
            return cursor != null;
        }
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

    /**
     * Aggregate query (POST /{entity}/query). {@code limit} bounds the grouped
     * result rows in SQL — the export and run doors set it just past their caps so
     * over-cap detection never materializes an unbounded dataset (§6's cap bounds
     * resources, not just the response).
     */
    public record AggregateQuery(Filter filter, List<GroupBy> groupBy,
                                 List<Aggregate> aggregates, java.time.LocalDate asOf,
                                 Integer limit) {

        public AggregateQuery(Filter filter, List<GroupBy> groupBy, List<Aggregate> aggregates) {
            this(filter, groupBy, aggregates, null, null);
        }

        public AggregateQuery(Filter filter, List<GroupBy> groupBy, List<Aggregate> aggregates,
                              java.time.LocalDate asOf) {
            this(filter, groupBy, aggregates, asOf, null);
        }
    }

    /**
     * A list page's result. Offset pages carry {@code total} (the per-page count);
     * a seek page skips the count the §5 measurement taxed at 364.9 ms/1M rows and
     * OMITS {@code total} — clients take it from an offset page and walk
     * {@code nextAfter} after. {@code nextAfter} rides only on a page that came back
     * full and whose every sort-key value survived the projection; absent otherwise.
     */
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record QueryResult(List<Map<String, Object>> rows, Long total, String nextAfter) {

        public QueryResult(List<Map<String, Object>> rows, long total) {
            this(rows, total, null);
        }
    }

    public record AggregateResult(List<String> groupBy, List<Map<String, Object>> rows) {
    }

    private QueryModel() {
    }
}
