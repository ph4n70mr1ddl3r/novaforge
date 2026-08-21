package com.novaforge.runtime.storage.query;

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

    /** Aggregate query (POST /{entity}/query). */
    public record AggregateQuery(Filter filter, List<String> groupBy, List<Aggregate> aggregates) {
    }

    public record QueryResult(List<Map<String, Object>> rows, long total) {
    }

    public record AggregateResult(List<String> groupBy, List<Map<String, Object>> rows) {
    }

    private QueryModel() {
    }
}
