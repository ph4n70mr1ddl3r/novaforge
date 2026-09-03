package com.novaforge.reporting.run;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.ReportDefinition;
import com.novaforge.metadata.Snake;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles a {@link ReportDefinition} into Data Runtime aggregate-query envelopes
 * (§3/§4) — filters, bucketed group-bys, aggregates — plus the un-grouped totals
 * twin. Saved filters are defaults: run params may tighten (override a field's
 * value, add fields), and the actor's sharing-rule row filters apply regardless
 * because the runtime enforces them on every query — params never loosen those.
 */
public final class ReportCompiler {

    private ReportCompiler() {
    }

    /** The compiled pair: the grouped query and its un-grouped totals twin. */
    public record Compiled(Map<String, Object> query, Map<String, Object> totalsQuery,
                    List<String> columns) {
    }

    public static Compiled compile(ReportDefinition report, Map<String, Object> params) {
        Map<String, Object> query = new LinkedHashMap<>();
        List<Map<String, Object>> filters = mergeFilters(report, params);
        if (!filters.isEmpty()) {
            query.put("filter", filters.size() == 1 ? filters.getFirst()
                    : Map.of("and", filters));
        }
        List<Object> groupBy = new ArrayList<>();
        List<String> columns = new ArrayList<>();
        for (ReportDefinition.GroupBy group : report.groupBy()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("field", group.field());
            if (!group.buckets().isEmpty()) {
                List<Map<String, Object>> buckets = new ArrayList<>();
                for (ReportDefinition.Bucket bucket : group.buckets()) {
                    buckets.add(Map.of("label", bucket.label(),
                            "expression", bucket.expression()));
                }
                entry.put("buckets", buckets);
            }
            groupBy.add(entry);
            columns.add(snakeOf(group.field()));
        }
        List<Map<String, Object>> aggregates = new ArrayList<>();
        for (ReportDefinition.AggregateField aggregate : report.aggregates()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("op", aggregate.op());
            if (aggregate.field() != null) {
                entry.put("field", aggregate.field());
            }
            if (aggregate.alias() != null) {
                entry.put("alias", aggregate.alias());
                columns.add(aggregate.alias());
            } else {
                columns.add(aggregate.op().toLowerCase()
                        + (aggregate.field() == null ? "" : "_" + snakeOf(aggregate.field())));
            }
            aggregates.add(entry);
        }
        if (groupBy.isEmpty() && aggregates.isEmpty()) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "report " + report.id() + " carries no group-by or aggregate");
        }
        if (!groupBy.isEmpty()) {
            query.put("groupBy", groupBy);
        }
        if (!aggregates.isEmpty()) {
            query.put("aggregates", aggregates);
        }
        Object asOf = params == null ? null : params.get("asOf");
        if (asOf != null) {
            // the governing evaluation date — a suite's frozen clock pins buckets (§3)
            query.put("asOf", String.valueOf(asOf));
        }
        // the totals twin drops the group-by — but a group-by-only report has no
        // aggregate to total, and a filter-only envelope is not a valid aggregate
        // query; the twin is the query itself then (no second leg runs)
        Map<String, Object> totals = new LinkedHashMap<>(query);
        totals.remove("groupBy");
        if (aggregates.isEmpty()) {
            totals = query;
        }
        return new Compiled(query, totals, columns);
    }

    /**
     * Param overrides merge over saved filters — the §4 tighten pin, mechanically:
     * a param naming a saved filter's field overrides that filter's VALUE (the saved
     * operator stands — an override op that differed would invert or drop the
     * author's constraint, which is loosening, and is rejected), a param naming a
     * new field appends a filter ({@code eq} by default, or the shaped
     * {@code {op, value}} form), and anything else in params is ignored except
     * {@code asOf}, which is not a filter. The actor's sharing-rule row filters
     * apply regardless — the runtime enforces them on every query.
     */
    private static List<Map<String, Object>> mergeFilters(ReportDefinition report,
                                                          Map<String, Object> params) {
        List<Map<String, Object>> filters = new ArrayList<>();
        for (ReportDefinition.Filter saved : report.filters()) {
            Map<String, Object> leaf = new LinkedHashMap<>();
            leaf.put("field", saved.field());
            leaf.put("op", saved.op());
            if (saved.value() != null) {
                leaf.put("value", saved.value());
            }
            filters.add(leaf);
        }
        if (params == null) {
            return filters;
        }
        for (Map.Entry<String, Object> override : params.entrySet()) {
            if (override.getKey().equals("asOf")) {
                continue;
            }
            String field = override.getKey();
            Object value = override.getValue();
            String op = "eq";
            if (value instanceof Map<?, ?> shaped && shaped.containsKey("op")) {
                op = String.valueOf(shaped.get("op"));
                value = shaped.get("value");
            }
            int savedAt = -1;
            for (int i = 0; i < filters.size(); i++) {
                if (filters.get(i).get("field").equals(field)) {
                    savedAt = i;
                    break;
                }
            }
            if (savedAt >= 0) {
                // tighten only (§4): the saved operator stands — a differing override
                // op would invert the constraint (eq → neq) or widen it (gt → gte),
                // which is exactly the loosening the spec forbids
                @SuppressWarnings("unchecked")
                Map<String, Object> savedLeaf = (Map<String, Object>) filters.get(savedAt);
                if (!savedLeaf.get("op").equals(op)) {
                    throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                            "report run param '" + field + "' may override the saved "
                                    + "filter's value only — its operator '"
                                    + savedLeaf.get("op") + "' is the definition's and "
                                    + "cannot become '" + op + "'");
                }
                if (value == null) {
                    savedLeaf.remove("value");
                } else {
                    savedLeaf.put("value", value);
                }
                continue;
            }
            Map<String, Object> leaf = new LinkedHashMap<>();
            leaf.put("field", field);
            leaf.put("op", op);
            if (value != null) {
                leaf.put("value", value);
            }
            filters.add(leaf);
        }
        return filters;
    }

    /** camelCase → snake_case, the runtime's column labels (the shared Snake). */
    public static String snakeOf(String camel) {
        return Snake.caseName(camel);
    }
}
