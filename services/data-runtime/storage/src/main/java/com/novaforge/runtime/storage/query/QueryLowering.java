package com.novaforge.runtime.storage.query;

import com.novaforge.metadata.EntityDefinition;
import com.novaforge.metadata.FieldDefinition;
import com.novaforge.runtime.storage.query.QueryModel.Aggregate;
import com.novaforge.runtime.storage.query.QueryModel.AggregateQuery;
import com.novaforge.runtime.storage.query.QueryModel.Filter;
import com.novaforge.runtime.storage.query.QueryModel.ListQuery;
import com.novaforge.runtime.storage.query.QueryModel.Sort;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lowers the query DSL to deterministic SQL over the ADR-001 projection table
 * (PHASE-1 §9 item 1 golden tests pin the exact SQL). Text-family values compare as
 * canonical text; numerics compare cast to numeric; booleans compare as 'true'/'false'
 * text. Promoted fields use their projection columns, everything else the JSONB
 * expression — both shapes hit the indexes the materializer creates.
 */
public final class QueryLowering {

    /** SQL + bind params for one lowered statement. */
    public record Lowered(String sql, List<Object> params) {
        public Lowered {
            params = List.copyOf(params);
        }
    }

    private final Map<String, String> promotedColumns;
    private final Map<String, Boolean> numericFields;

    public QueryLowering(EntityDefinition entity) {
        this.promotedColumns = PromotionPolicy.promotedColumns(entity);
        this.numericFields = new HashMap<>();
        for (FieldDefinition field : entity.fields()) {
            numericFields.put(field.apiName(), field.type().numeric());
        }
    }

    public static String projectionTable(String entityApiName) {
        return "rec_" + Snake.caseName(entityApiName);
    }

    public Lowered list(String entityApiName, UUID tenantId, ListQuery query) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, version, created_at, updated_at, created_by, updated_by, deleted, data FROM ")
                .append(projectionTable(entityApiName))
                .append(" WHERE tenant_id = ? AND deleted = false");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        if (query.filter() != null) {
            sql.append(" AND ").append(lowerFilter(query.filter(), params));
        }
        sql.append(" ORDER BY ");
        if (query.sort().isEmpty()) {
            sql.append("id");
        } else {
            List<String> orderItems = new ArrayList<>();
            for (Sort sort : query.sort()) {
                orderItems.add(textExpr(sort.field()) + " "
                        + (sort.dir() == QueryModel.SortDir.desc ? "DESC" : "ASC"));
            }
            orderItems.add("id");
            sql.append(String.join(", ", orderItems));
        }
        sql.append(" LIMIT ? OFFSET ?");
        params.add(query.page().size());
        params.add(query.page().offset());
        return new Lowered(sql.toString(), params);
    }

    public Lowered count(String entityApiName, UUID tenantId, Filter filter) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM ")
                .append(projectionTable(entityApiName))
                .append(" WHERE tenant_id = ? AND deleted = false");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        if (filter != null) {
            sql.append(" AND ").append(lowerFilter(filter, params));
        }
        return new Lowered(sql.toString(), params);
    }

    public Lowered aggregate(String entityApiName, UUID tenantId, AggregateQuery query) {
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        List<String> selects = new ArrayList<>();
        for (String groupField : query.groupBy()) {
            selects.add(textExpr(groupField) + " AS " + Snake.caseName(groupField));
        }
        for (Aggregate aggregate : query.aggregates()) {
            String alias = aggregate.alias() != null ? aggregate.alias()
                    : aggregate.op().name()
                            + (aggregate.field() == null ? "" : "_" + Snake.caseName(aggregate.field()));
            String expr = switch (aggregate.op()) {
                case count -> "count(*)";
                case sum -> "sum(" + numericExpr(aggregate.field()) + ")";
                case avg -> "avg(" + numericExpr(aggregate.field()) + ")";
                case min -> "min(" + numericExpr(aggregate.field()) + ")";
                case max -> "max(" + numericExpr(aggregate.field()) + ")";
            };
            selects.add(expr + " AS " + alias);
        }
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(String.join(", ", selects))
                .append(" FROM ").append(projectionTable(entityApiName))
                .append(" WHERE tenant_id = ? AND deleted = false");
        if (query.filter() != null) {
            sql.append(" AND ").append(lowerFilter(query.filter(), params));
        }
        if (!query.groupBy().isEmpty()) {
            sql.append(" GROUP BY ").append(String.join(", ",
                    query.groupBy().stream().map(this::textExpr).toList()));
        }
        return new Lowered(sql.toString(), params);
    }

    // --- expressions ---

    private String textExpr(String field) {
        String column = promotedColumns.get(field);
        return column != null ? column : "(data->>'" + field + "')";
    }

    private String numericExpr(String field) {
        String column = promotedColumns.get(field);
        return column != null ? column : "((data->>'" + field + "')::numeric)";
    }

    private String lowerFilter(Filter filter, List<Object> params) {
        if (filter instanceof Filter.Composite composite) {
            String joiner = "or".equals(composite.op()) ? " OR " : " AND ";
            // The composite parenthesizes itself: an unparenthesized OR conjoined to the
            // fixed predicates would split on operator precedence.
            return composite.children().stream()
                    .map(child -> "(" + lowerFilter(child, params) + ")")
                    .reduce((a, b) -> a + joiner + b)
                    .map(joined -> "(" + joined + ")")
                    .orElseThrow();
        }
        Filter.Leaf leaf = (Filter.Leaf) filter;
        boolean numeric = numericFields.getOrDefault(leaf.field(), false);
        String expr = numeric ? numericExpr(leaf.field()) : textExpr(leaf.field());
        return switch (leaf.op()) {
            case "eq" -> {
                params.add(canonicalValue(leaf.value()));
                yield expr + " = ?";
            }
            case "ne" -> {
                params.add(canonicalValue(leaf.value()));
                yield "(" + expr + " IS DISTINCT FROM ?)";
            }
            case "gt", "gte", "lt", "lte" -> {
                params.add(canonicalValue(leaf.value()));
                String op = switch (leaf.op()) {
                    case "gt" -> ">";
                    case "gte" -> ">=";
                    case "lt" -> "<";
                    default -> "<=";
                };
                yield expr + " " + op + " ?";
            }
            case "contains" -> {
                params.add("%" + canonicalValue(leaf.value()) + "%");
                yield textExpr(leaf.field()) + " ILIKE ?";
            }
            case "isNull" -> textExpr(leaf.field()) + " IS NULL";
            case "in" -> {
                List<?> values = (List<?>) leaf.value();
                List<String> equals = new ArrayList<>();
                for (Object value : values) {
                    params.add(canonicalValue(value));
                    equals.add(expr + " = ?");
                }
                yield "(" + String.join(" OR ", equals) + ")";
            }
            default -> throw new IllegalArgumentException("unknown operator " + leaf.op());
        };
    }

    private Object canonicalValue(Object value) {
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return value;
    }
}
