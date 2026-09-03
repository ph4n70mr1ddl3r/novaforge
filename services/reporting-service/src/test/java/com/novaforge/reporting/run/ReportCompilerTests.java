package com.novaforge.reporting.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.metadata.ReportDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The compile contract (PHASE-5 §3/§4): a report definition lowers to Data Runtime
 * aggregate envelopes — saved filters merged with param overrides (tighten only:
 * the saved operator stands, values override, new fields append; sharing row
 * filters always apply at the runtime), the totals twin stays a
 * valid envelope for every legal report shape, and columns name after the runtime's
 * snake_case labels.
 */
class ReportCompilerTests {

    private static final String REPORT_JSON = """
            { "id": "arAging", "entity": "Invoice",
              "filters": [ { "field": "status", "op": "eq", "value": "POSTED" } ],
              "groupBy": [
                { "field": "customer" },
                { "field": "dueDate", "buckets": [
                  { "label": "current", "expression": "today() - dueDate < 0" },
                  { "label": "60+", "expression": "today() - dueDate > 60" } ] } ],
              "aggregates": [ { "op": "sum", "field": "amountOutstanding" } ] }
            """;

    private static ReportDefinition report() {
        return DefinitionParser.parse(REPORT_JSON, ReportDefinition.class);
    }

    @Test
    @DisplayName("buckets and aggregates lower into the envelope; columns follow the runtime labels")
    void compilesTheEnvelope() {
        ReportCompiler.Compiled compiled = ReportCompiler.compile(report(), Map.of());
        assertThat(compiled.columns()).containsExactly("customer", "due_date",
                "sum_amount_outstanding");
        assertThat(compiled.query().get("groupBy")).isEqualTo(List.of(
                Map.of("field", "customer"),
                Map.of("field", "dueDate", "buckets", List.of(
                        Map.of("label", "current", "expression", "today() - dueDate < 0"),
                        Map.of("label", "60+", "expression", "today() - dueDate > 60")))));
        // the totals twin drops the group-by but keeps filters and aggregates
        assertThat(compiled.totalsQuery().containsKey("groupBy")).isFalse();
        assertThat(compiled.totalsQuery().get("filter"))
                .isEqualTo(compiled.query().get("filter"));
        assertThat(compiled.totalsQuery().get("aggregates"))
                .isEqualTo(compiled.query().get("aggregates"));
    }

    @Test
    @DisplayName("param overrides replace same-field values and append new fields; asOf rides the envelope")
    void paramOverridesMerge() {
        ReportCompiler.Compiled compiled = ReportCompiler.compile(report(), Map.of(
                "status", "VOID",                                        // replace the saved filter's value
                "customer", Map.of("op", "contains", "value", "acme"),   // shaped override
                "asOf", "2026-01-15"));                                  // the clock, not a filter
        assertThat(filtersOf(compiled)).containsExactly(
                Map.of("field", "status", "op", "eq", "value", "VOID"),
                Map.of("field", "customer", "op", "contains", "value", "acme"));
        assertThat(String.valueOf(compiled.query().get("asOf"))).isEqualTo("2026-01-15");
    }

    @Test
    @DisplayName("a shaped override cannot flip a saved filter's operator — tighten only (§4)")
    void savedFilterOperatorIsImmutable() {
        // `status eq POSTED` must never become `status neq POSTED` (or any other op):
        // inverting the author's constraint is the loosening §4 forbids — the saved
        // filters are the defaults, callers tighten within them or name new fields
        assertThatThrownBy(() -> ReportCompiler.compile(report(), Map.of(
                "status", Map.of("op", "neq", "value", "POSTED"))))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("status")
                .hasMessageContaining("eq")
                .hasMessageContaining("neq");
    }

    @Test
    @DisplayName("a same-operator shaped override still overrides the saved value only")
    void sameOperatorShapedOverrideReplacesValueOnly() {
        ReportCompiler.Compiled compiled = ReportCompiler.compile(report(), Map.of(
                "status", Map.of("op", "eq", "value", "DRAFT")));
        assertThat(filtersOf(compiled)).containsExactly(
                Map.of("field", "status", "op", "eq", "value", "DRAFT"));
    }

    @Test
    @DisplayName("a saved filter without an override rides every run verbatim — params cannot drop it")
    void unsavedFieldOverridesNeverDropSavedFilters() {
        ReportCompiler.Compiled compiled = ReportCompiler.compile(report(),
                Map.of("customer", "acme"));
        // both leaves survive: the untouched saved filter AND the appended param
        assertThat(filtersOf(compiled)).containsExactly(
                Map.of("field", "status", "op", "eq", "value", "POSTED"),
                Map.of("field", "customer", "op", "eq", "value", "acme"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> filtersOf(ReportCompiler.Compiled compiled) {
        Object filter = compiled.query().get("filter");
        if (filter instanceof Map<?, ?> and && and.containsKey("and")) {   // two+ leaves lower as an and-composite
            return (List<Map<String, Object>>) and.get("and");
        }
        return List.of((Map<String, Object>) filter);   // a single leaf IS the filter
    }

    @Test
    @DisplayName("a group-by-only report's totals twin is the query itself — no invalid filter-only leg")
    void groupByOnlyTotalsStayValid() {
        ReportDefinition groupOnly = new ReportDefinition("byCustomer", "Invoice", null, null,
                List.of(), List.of(new ReportDefinition.GroupBy("customer", List.of())),
                List.of(), null);
        ReportCompiler.Compiled compiled = ReportCompiler.compile(groupOnly, Map.of());
        assertThat(compiled.totalsQuery()).isEqualTo(compiled.query());
    }

    @Test
    @DisplayName("a report with no group-by and no aggregate is a compile error")
    void emptyReportRejects() {
        ReportDefinition empty = new ReportDefinition("empty", "Invoice", null, null,
                List.of(), List.of(), List.of(), null);
        assertThatThrownBy(() -> ReportCompiler.compile(empty, Map.of()))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("carries no group-by or aggregate");
    }

    @Test
    @DisplayName("snake naming matches the shared Snake (the runtime's column labels)")
    void snakeNamingMatchesRuntime() {
        assertThat(ReportCompiler.snakeOf("amountOutstanding"))
                .isEqualTo("amount_outstanding");
        assertThat(ReportCompiler.snakeOf("JournalEntry")).isEqualTo("journal_entry");
    }
}
