package com.novaforge.runtime.engine.query;

import com.novaforge.metadata.EntityDefinition;
import com.novaforge.metadata.PromotionPolicy;
import com.novaforge.metadata.Snake;
import com.novaforge.metadata.FieldDefinition;
import com.novaforge.runtime.engine.query.QueryModel.Aggregate;
import com.novaforge.runtime.engine.query.QueryModel.AggregateQuery;
import com.novaforge.runtime.engine.query.QueryModel.Filter;
import com.novaforge.runtime.engine.query.QueryModel.ListQuery;
import com.novaforge.runtime.engine.query.QueryModel.Sort;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

        /**
         * Appends a sharing clause ({@code AND (created_by IN …)}) with its params,
         * splicing before any trailing GROUP/ORDER/LIMIT tail so the SQL stays valid
         * (aggregates carry a GROUP BY tail since PHASE-5 §3 grew the pipeline).
         */
        public Lowered and(String clause, List<Object> clauseParams) {
            int tailStart = -1;
            for (String marker : new String[] {" GROUP BY ", " ORDER BY ", " LIMIT "}) {
                int at = sql.lastIndexOf(marker);
                if (at >= 0 && (tailStart < 0 || at < tailStart)) {
                    tailStart = at;
                }
            }
            if (tailStart < 0) {
                List<Object> merged = new java.util.ArrayList<>(params);
                merged.addAll(clauseParams);
                return new Lowered(sql + " AND (" + clause + ")", List.copyOf(merged));
            }
            String tail = sql.substring(tailStart);
            long tailParams = tail.chars().filter(c -> c == '?').count();
            List<Object> merged = new java.util.ArrayList<>(
                    params.subList(0, params.size() - (int) tailParams));
            merged.addAll(clauseParams);
            merged.addAll(params.subList(params.size() - (int) tailParams, params.size()));
            return new Lowered(sql.substring(0, tailStart) + " AND (" + clause + ")" + tail,
                    List.copyOf(merged));
        }

        public Lowered {
            params = List.copyOf(params);
        }
    }

    private final EntityDefinition entity;
    private final Map<String, String> promotedColumns;
    private final Map<String, Boolean> numericFields;

    public QueryLowering(EntityDefinition entity) {
        this.entity = entity;
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
                // numeric fields sort numerically: the text expression orders 9, 100,
                // 10 lexicographically on unpromoted decimals (the promoted column is
                // typed, so textExpr is only wrong on the JSONB path)
                boolean numeric = numericFields.getOrDefault(sort.field(), false)
                        && promotedColumns.get(sort.field()) == null
                        && !SYSTEM_COLUMN_EXPR.containsKey(sort.field());
                String expr = numeric ? numericExpr(sort.field()) : textExpr(sort.field());
                orderItems.add(expr + " "
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
        return aggregate(entityApiName, tenantId, query, null);
    }

    /**
     * Lowers an aggregate query (PHASE-5 §3 grows the group-by with buckets): plain
     * fields group on their promoted/JSONB text expression exactly as before; a
     * bucketed group-by lowers its authored expressions to CASE WHEN branches inside
     * the pipeline — first match wins, unmatched rows land in no bucket (NULL), and
     * {@code today()} binds the run's evaluation date.
     *
     * <p>Bind order matches placeholder order: SELECT-list CASE binds first, then the
     * tenant, then the filter. GROUP BY addresses the select ordinals — a repeated
     * CASE expression would rebind every parameter, and an alias would be ambiguous
     * against the source column it shadows.</p>
     */
    public Lowered aggregate(String entityApiName, UUID tenantId, AggregateQuery query,
                             java.time.LocalDate asOfOverride) {
        com.novaforge.expression.ExpressionSql.FieldResolver resolver =
                com.novaforge.metadata.ExpressionFields.resolver(entity);
        // the governing clock, in priority order: the query's pinned date (a suite's
        // frozen clock), the engine's date, and UTC now as the last resort
        java.time.LocalDate asOf = query.asOf() != null ? query.asOf()
                : asOfOverride != null ? asOfOverride
                : java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        // live instant for now() — parity with the Java evaluator (the gates);
        // asOf only shapes today() for bucketed group-bys
        java.time.Instant asOfInstant = java.time.Instant.now();
        List<Object> params = new ArrayList<>();
        List<String> selects = new ArrayList<>();
        for (QueryModel.GroupBy group : query.groupBy()) {
            String alias = Snake.caseName(group.field());
            if (!group.bucketed()) {
                // quoted — Postgres folds unquoted aliases to lowercase, which
                // rewrites authored camelCase group keys in the result map (found
                // live: the ERP trial balance's rows lost their authored aliases)
                selects.add(textExpr(group.field()) + " AS \"" + alias + "\"");
                continue;
            }
            StringBuilder branch = new StringBuilder("CASE");
            for (QueryModel.Bucket bucket : group.buckets()) {
                com.novaforge.expression.ExpressionSql.Lowered condition =
                        com.novaforge.expression.ExpressionSql.lowerBoolean(
                                com.novaforge.expression.Expression.parse(bucket.expression()),
                                resolver, asOf, asOfInstant);
                params.addAll(condition.params());
                branch.append(" WHEN ").append(condition.sql()).append(" THEN ?");
                params.add(bucket.label());
            }
            branch.append(" ELSE NULL END");
            selects.add(branch + " AS \"" + alias + "\"");
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
            // quoted — an authored camelCase alias ("debitTotal") must survive the
            // round trip; unquoted, Postgres answers "debittotal" and every
            // caller keyed on the authored alias reads nulls (found live: the
            // trial-balance totals read null through the reporting run path)
            selects.add(expr + " AS \"" + alias + "\"");
        }
        params.add(tenantId);
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(String.join(", ", selects))
                .append(" FROM ").append(projectionTable(entityApiName))
                .append(" WHERE tenant_id = ? AND deleted = false");
        if (query.filter() != null) {
            sql.append(" AND ").append(lowerFilter(query.filter(), params));
        }
        if (!query.groupBy().isEmpty()) {
            // select ordinals — the group-by entries lead the select list
            List<String> ordinals = new ArrayList<>();
            for (int i = 1; i <= query.groupBy().size(); i++) {
                ordinals.add(String.valueOf(i));
            }
            sql.append(" GROUP BY ").append(String.join(", ", ordinals));
            if (query.limit() != null) {
                // an unordered hash-aggregate truncates arbitrarily under LIMIT —
                // which groups survive would differ run to run; order by the group
                // keys so the truncation is reproducible
                sql.append(" ORDER BY ").append(String.join(", ", ordinals));
            }
        }
        if (query.limit() != null) {
            sql.append(" LIMIT ?");
            params.add(query.limit());
        }
        return new Lowered(sql.toString(), params);
    }

    // --- expressions ---

    /** System leaf fields (PHASE-7 §3.6) lower to their own projection columns, not
     *  the JSONB extract — a data->>'id' lookup would read nothing. */
    private static final Map<String, String> SYSTEM_COLUMN_EXPR = Map.of(
            "id", "id",
            "version", "version");

    private static final Set<String> SYSTEM_NUMERIC_FIELDS = Set.of("version");

    private String textExpr(String field) {
        String system = SYSTEM_COLUMN_EXPR.get(field);
        if (system != null) {
            return system;
        }
        String column = promotedColumns.get(field);
        return column != null ? column : "(data->>'" + field + "')";
    }

    private String numericExpr(String field) {
        String system = SYSTEM_COLUMN_EXPR.get(field);
        if (system != null) {
            return system;
        }
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
        boolean numeric = SYSTEM_NUMERIC_FIELDS.contains(leaf.field())
                || numericFields.getOrDefault(leaf.field(), false);
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
                // LIKE-escaped at bind time (backslash, then % and _) so a literal
                // wildcard in the searched value matches itself — strict substring
                // semantics, never an accidental wildcard search (the 2025-08-27
                // review aligned this leaf with the expression lowering's parity).
                params.add("%" + escapeLike(canonicalValue(leaf.value())) + "%");
                yield textExpr(leaf.field()) + " ILIKE ? ESCAPE '\\'";
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

    /** Escapes a value for LIKE/ILIKE matching: backslash first, then the wildcards. */
    static String escapeLike(Object value) {
        return String.valueOf(value)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
