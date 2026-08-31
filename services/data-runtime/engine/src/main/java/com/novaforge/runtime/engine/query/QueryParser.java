package com.novaforge.runtime.engine.query;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.common.error.ProblemErrors;
import com.novaforge.metadata.EntityDefinition;
import com.novaforge.metadata.FieldDefinition;
import com.novaforge.metadata.FieldType;
import com.novaforge.metadata.ReportDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses and validates the query DSL against entity metadata (fields must exist; ops
 * must be v1; {@code contains} only on text fields; page size ≤ 200 — over-limit
 * rejects, never clamps, PHASE-1 §12 Q2).
 */
public final class QueryParser {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private QueryParser() {
    }

    public static QueryModel.ListQuery parseList(String json, EntityDefinition entity) {
        return parseList(MAPPER.readTree(json), entity);
    }

    public static QueryModel.ListQuery parseList(JsonNode root, EntityDefinition entity) {
        if (root == null || !root.isObject()) {
            throw validation("query", "query body must be a JSON object");
        }
        QueryModel.Filter filter = root.hasNonNull("filter")
                ? parseFilter(root.get("filter"), entity)
                : null;
        List<QueryModel.Sort> sort = new ArrayList<>();
        if (root.hasNonNull("sort")) {
            JsonNode sortNode = root.get("sort");
            if (!sortNode.isArray()) {
                throw validation("sort", "sort must be an array of {field, dir}");
            }
            for (JsonNode item : sortNode) {
                String field = requiredText(item, "field", "sort.field");
                requireSortField(entity, field);
                String dir = item.hasNonNull("dir") ? item.get("dir").asString() : "asc";
                if (!dir.equals("asc") && !dir.equals("desc")) {
                    throw validation("sort.dir", "sort dir must be asc|desc");
                }
                sort.add(new QueryModel.Sort(field, QueryModel.SortDir.valueOf(dir)));
            }
        }
        int size = QueryModel.DEFAULT_PAGE_SIZE;
        long offset = 0;
        if (root.hasNonNull("page")) {
            JsonNode page = root.get("page");
            if (page.hasNonNull("size")) {
                size = page.get("size").asInt();
                if (size < 1 || size > QueryModel.MAX_PAGE_SIZE) {
                    throw validation("page.size",
                            "page size must be 1.." + QueryModel.MAX_PAGE_SIZE + " (reject, never clamp)");
                }
            }
            if (page.hasNonNull("offset")) {
                offset = page.get("offset").asLong();
                if (offset < 0) {
                    throw validation("page.offset", "page offset must be >= 0");
                }
            }
        }
        return new QueryModel.ListQuery(filter, List.copyOf(sort), new QueryModel.Page(size, offset));
    }

    public static QueryModel.AggregateQuery parseAggregate(String json, EntityDefinition entity) {
        JsonNode root = MAPPER.readTree(json);
        if (root == null || !root.isObject()) {
            throw validation("query", "query body must be a JSON object");
        }
        QueryModel.Filter filter = root.hasNonNull("filter")
                ? parseFilter(root.get("filter"), entity) : null;
        List<QueryModel.GroupBy> groupBy = new ArrayList<>();
        if (root.hasNonNull("groupBy")) {
            for (JsonNode node : root.get("groupBy")) {
                if (node.isTextual()) {
                    // plain field — the pre-Phase-5 wire shape (roll-ups ride it)
                    requireField(entity, node.asString(), "groupBy");
                    groupBy.add(new QueryModel.GroupBy(node.asString()));
                    continue;
                }
                String field = requiredText(node, "field", "groupBy.field");
                requireField(entity, field, "groupBy");
                List<QueryModel.Bucket> buckets = new ArrayList<>();
                if (node.hasNonNull("buckets")) {
                    JsonNode bucketNode = node.get("buckets");
                    if (!bucketNode.isArray() || bucketNode.isEmpty()) {
                        throw validation("groupBy.buckets",
                                "buckets must be a non-empty array of {label, expression}");
                    }
                    for (JsonNode bucket : bucketNode) {
                        String label = requiredText(bucket, "label", "groupBy.buckets.label");
                        String expression = requiredText(bucket, "expression",
                                "groupBy.buckets.expression");
                        try {
                            com.novaforge.expression.Expression.parse(expression);
                        } catch (com.novaforge.expression.ExpressionException e) {
                            throw validation("groupBy.buckets.expression",
                                    "bucket expression does not parse: " + e.getMessage());
                        }
                        buckets.add(new QueryModel.Bucket(label, expression));
                    }
                }
                groupBy.add(new QueryModel.GroupBy(field, buckets));
            }
        }
        List<QueryModel.Aggregate> aggregates = new ArrayList<>();
        if (root.hasNonNull("aggregates")) {
            for (JsonNode node : root.get("aggregates")) {
                String op = requiredText(node, "op", "aggregates.op");
                QueryModel.AggregateOp aggregateOp;
                try {
                    aggregateOp = QueryModel.AggregateOp.valueOf(op);
                } catch (IllegalArgumentException e) {
                    throw validation("aggregates.op", "unknown aggregate op: " + op);
                }
                String field = node.hasNonNull("field") ? node.get("field").asString() : null;
                if (aggregateOp != QueryModel.AggregateOp.count) {
                    if (field == null) {
                        throw validation("aggregates.field", aggregateOp + " requires a field");
                    }
                    requireField(entity, field, "aggregates.field");
                    requireNumeric(entity, field, aggregateOp);
                }
                String alias = node.hasNonNull("alias") ? node.get("alias").asString() : null;
                // The alias rides the lowered SELECT list as a quoted identifier
                // (QueryLowering), so it is grammar-bound at the parse door exactly
                // like a report key — a quote, paren, or comma in an authored alias
                // must reject VALIDATION_FAILED here, before any SQL is built
                // (found in the 2025-08-27 review: identifier breakout otherwise
                // splices caller SQL into the aggregate statement).
                if (alias != null && !ReportDefinition.REPORT_KEY.matcher(alias).matches()) {
                    throw validation("aggregates.alias",
                            "alias must be a plain identifier (a letter or underscore, then "
                                    + "word characters): " + alias);
                }
                aggregates.add(new QueryModel.Aggregate(aggregateOp, field, alias));
            }
        }
        if (aggregates.isEmpty() && groupBy.isEmpty()) {
            throw validation("aggregates", "aggregate query requires aggregates and/or groupBy");
        }
        java.time.LocalDate asOf = null;
        if (root.hasNonNull("asOf")) {
            try {
                asOf = java.time.LocalDate.parse(root.get("asOf").asString());
            } catch (RuntimeException e) {
                throw validation("asOf", "asOf must be an ISO date (yyyy-MM-dd)");
            }
        }
        Integer limit = null;
        if (root.hasNonNull("limit")) {
            limit = root.get("limit").asInt();
            if (limit <= 0) {
                throw validation("limit", "limit must be a positive integer");
            }
        }
        return new QueryModel.AggregateQuery(filter, List.copyOf(groupBy),
                List.copyOf(aggregates), asOf, limit);
    }

    static QueryModel.Filter parseFilter(JsonNode node, EntityDefinition entity) {
        if (node.hasNonNull("and")) {
            return composite("and", node.get("and"), entity);
        }
        if (node.hasNonNull("or")) {
            return composite("or", node.get("or"), entity);
        }
        String field = requiredText(node, "field", "filter.field");
        Optional<FieldDefinition> fieldDef = entity.field(field);
        if (fieldDef.isEmpty() && !SYSTEM_LEAF_FIELDS.contains(field)) {
            // PHASE-7 §3.6: the two operational keys filter like authored fields;
            // every other reserved name stays rejected
            throw validation("filter.field", "unknown field on " + entity.apiName() + ": " + field);
        }
        String op = requiredText(node, "op", "filter.op");
        if (!QueryModel.OPERATORS.contains(op)) {
            throw validation("filter.op", "unknown operator: " + op);
        }
        if ("contains".equals(op)
                && (fieldDef.isEmpty() || !fieldDef.get().type().textual())) {
            throw validation("filter.op", "contains is allowed on text fields only");
        }
        if ("isNull".equals(op)) {
            return new QueryModel.Filter.Leaf(field, op, null);
        }
        if (!node.hasNonNull("value") && !"in".equals(op)) {
            throw validation("filter.value", "operator " + op + " requires a value");
        }
        JsonNode value = node.get("value");
        Object javaValue = switch (value.getNodeType()) {
            case STRING -> value.asString();
            case NUMBER -> value.decimalValue();
            case BOOLEAN -> value.asBoolean();
            case NULL -> null;
            case ARRAY -> {
                List<Object> items = new ArrayList<>();
                for (JsonNode item : value) {
                    items.add(item.isNumber() ? item.decimalValue() : item.isBoolean()
                            ? item.asBoolean() : item.asString());
                }
                yield items;
            }
            default -> throw validation("filter.value",
                    "unsupported filter value type: " + value.getNodeType());
        };
        if ("in".equals(op) && !(javaValue instanceof List)) {
            throw validation("filter.value", "in requires an array value");
        }
        if ("in".equals(op) && ((List<?>) javaValue).isEmpty()) {
            // an empty in-list lowers to "()" — a raw SQL syntax error, not a shaped
            // rejection at the door
            throw validation("filter.value", "in requires a non-empty array");
        }
        return new QueryModel.Filter.Leaf(field, op, canonicalizeSystemLeaf(field, javaValue));
    }

    private static QueryModel.Filter composite(String op, JsonNode children, EntityDefinition entity) {
        if (!children.isArray() || children.isEmpty()) {
            throw validation("filter." + op, op + " requires a non-empty array");
        }
        List<QueryModel.Filter> parsed = new ArrayList<>();
        for (JsonNode child : children) {
            parsed.add(parseFilter(child, entity));
        }
        return new QueryModel.Filter.Composite(op, parsed);
    }

    private static void requireField(EntityDefinition entity, String field, String scope) {
        if (entity.field(field).isEmpty()) {
            throw validation(scope, "unknown field on " + entity.apiName() + ": " + field);
        }
    }

    /** Filter/sort leaf fields the DSL accepts beyond authored fields (PHASE-7 §3.6,
     *  the G-5 harvest): the record's two operational keys — identity and optimistic-
     *  locking version. Every other reserved name stays rejected; queries by audit
     *  metadata ride the audit trail instead (PHASE-3 §5). */
    static final Set<String> SYSTEM_LEAF_FIELDS = Set.of("id", "version");

    /** Sort fields ride the same exemption: id/version order by their projection
     *  columns; authored names only otherwise. */
    private static void requireSortField(EntityDefinition entity, String field) {
        if (!SYSTEM_LEAF_FIELDS.contains(field)) {
            requireField(entity, field, "sort.field");
        }
    }

    /**
     * System-field leaves parse to their canonical forms at the door (PHASE-7 §3.6):
     * {@code id} to a UUID, {@code version} to an integer — so the lowering binds
     * JDBC-typed params against the projection columns and a malformed value rejects
     * VALIDATION_FAILED with field scope instead of surfacing downstream.
     */
    private static Object canonicalizeSystemLeaf(String field, Object value) {
        if (!SYSTEM_LEAF_FIELDS.contains(field) || value == null) {
            return value;
        }
        if (value instanceof List<?> items) {
            return items.stream().map(item -> canonicalizeSystemLeaf(field, item)).toList();
        }
        try {
            if ("id".equals(field)) {
                return UUID.fromString(String.valueOf(value));
            }
            java.math.BigDecimal decimal = new java.math.BigDecimal(String.valueOf(value));
            return decimal.longValueExact();   // version — non-integral rejects
        } catch (RuntimeException bad) {
            throw validation("filter.value", field + " leaf requires a "
                    + ("id".equals(field) ? "uuid" : "integer") + " value: " + value);
        }
    }

    private static void requireNumeric(EntityDefinition entity, String field,
                                       QueryModel.AggregateOp op) {
        if (entity.field(field).filter(f -> f.type().numeric()).isEmpty()) {
            throw validation("aggregates.field", op + " requires a numeric field: " + field);
        }
    }

    private static String requiredText(JsonNode node, String property, String scope) {
        if (node == null || !node.hasNonNull(property)) {
            throw validation(scope, property + " is required");
        }
        return node.get(property).asString();
    }

    private static PlatformException validation(String field, String message) {
        return new PlatformException(PlatformErrorCode.VALIDATION_FAILED, message,
                ProblemErrors.of(new ProblemErrors.FieldError(field, message, null)));
    }
}
