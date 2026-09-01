package com.novaforge.runtime.engine.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.novaforge.metadata.DefinitionParser;
import com.novaforge.metadata.EntityDefinition;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Golden SQL for bucketed aggregates (PHASE-5 §3): buckets lower to CASE WHEN
 * branches inside the pipeline — first match wins, labels bind as params, the run's
 * evaluation date binds {@code today()} — and plain group-by fields keep their
 * pre-Phase-5 shape exactly (the roll-up goldens stay pinned). GROUP BY addresses
 * select ordinals: a repeated CASE would rebind every parameter, and an alias would
 * be ambiguous against the source column it shadows.
 */
class BucketedAggregateSqlTests {

    private static final String ENTITY_JSON = """
            { "apiName": "AgingAr",
              "displayField": "customerName",
              "fields": [
                { "apiName": "customerName", "type": "text" },
                { "apiName": "dueDate", "type": "date" },
                { "apiName": "status", "type": "enum", "values": ["DRAFT","POSTED"] },
                { "apiName": "amountOutstanding", "type": "decimal", "precision": 18, "scale": 4 } ],
              "indexes": [ { "fields": ["customerName", "dueDate", "amountOutstanding"] } ] }
            """;

    private static final String AGING_QUERY = """
            { "groupBy": [
                { "field": "customerName" },
                { "field": "dueDate", "buckets": [
                  { "label": "current", "expression": "today() - dueDate < 0" },
                  { "label": "0-30", "expression": "today() - dueDate >= 0 && today() - dueDate <= 30" },
                  { "label": "31-60", "expression": "today() - dueDate > 30 && today() - dueDate <= 60" },
                  { "label": "60+", "expression": "today() - dueDate > 60" } ] } ],
              "aggregates": [ { "op": "sum", "field": "amountOutstanding" } ] }
            """;

    private static final String CASE_EXPR = "CASE "
            + "WHEN ((CAST(? AS date) - CAST(due_date AS date)) < ?) THEN ? "
            + "WHEN (((CAST(? AS date) - CAST(due_date AS date)) >= ?) AND "
            + "((CAST(? AS date) - CAST(due_date AS date)) <= ?)) THEN ? "
            + "WHEN (((CAST(? AS date) - CAST(due_date AS date)) > ?) AND "
            + "((CAST(? AS date) - CAST(due_date AS date)) <= ?)) THEN ? "
            + "WHEN ((CAST(? AS date) - CAST(due_date AS date)) > ?) THEN ? "
            + "ELSE NULL END";

    @Test
    @DisplayName("an aging bucket lowers to CASE WHEN branches with the clock and labels bound")
    void bucketedAgingLowers() {
        EntityDefinition entity = DefinitionParser.parse(ENTITY_JSON, EntityDefinition.class);
        QueryModel.AggregateQuery query = QueryParser.parseAggregate(AGING_QUERY, entity);
        UUID tenant = UUID.randomUUID();
        QueryLowering.Lowered lowered = new QueryLowering(entity)
                .aggregate("Ar.AgingAr", tenant, query,
                        LocalDate.of(2026, 8, 23));

        assertThat(lowered.sql()).isEqualTo(
                "SELECT customer_name AS \"customer_name\", " + CASE_EXPR + " AS \"due_date\", "
                        + "sum(amount_outstanding) AS \"sum_amount_outstanding\" "
                        + "FROM rec_aging_ar WHERE tenant_id = ? AND deleted = false AND entity_id = ? "
                        + "GROUP BY 1, 2");
        // binds follow placeholder order: SELECT-list CASE first, tenant after
        String today = "2026-08-23";
        assertThat(lowered.params()).containsExactly(
                today, new BigDecimal("0"), "current",
                today, new BigDecimal("0"), today, new BigDecimal("30"), "0-30",
                today, new BigDecimal("30"), today, new BigDecimal("60"), "31-60",
                today, new BigDecimal("60"), "60+",
                tenant, "Ar.AgingAr");
        assertThat(lowered.params().get(16)).isEqualTo(tenant);
        assertThat(lowered.params().get(17)).isEqualTo("Ar.AgingAr");
    }

    @Test
    @DisplayName("plain group-by keeps the pre-Phase-5 shape (roll-ups ride it)")
    void plainGroupByUnchanged() {
        EntityDefinition entity = DefinitionParser.parse(ENTITY_JSON, EntityDefinition.class);
        QueryModel.AggregateQuery query = QueryParser.parseAggregate(
                "{\"groupBy\":[\"customerName\"],\"aggregates\":[{\"op\":\"count\"}]}",
                entity);
        QueryLowering.Lowered lowered = new QueryLowering(entity)
                .aggregate("Ar.AgingAr", UUID.randomUUID(), query, null);
        assertThat(lowered.sql()).isEqualTo(
                "SELECT customer_name AS \"customer_name\", count(*) AS \"count\" "
                        + "FROM rec_aging_ar WHERE tenant_id = ? AND deleted = false AND entity_id = ? "
                        + "GROUP BY 1");
        assertThat(lowered.params()).hasSize(2);
    }

    @Test
    @DisplayName("the query envelope pins asOf — a suite's frozen clock rides it")
    void asOfPinsTheClock() {
        EntityDefinition entity = DefinitionParser.parse(ENTITY_JSON, EntityDefinition.class);
        QueryModel.AggregateQuery query = QueryParser.parseAggregate(
                "{\"groupBy\":[{\"field\":\"dueDate\",\"buckets\":["
                        + "{\"label\":\"old\",\"expression\":\"today() - dueDate > 60\"}]}],"
                        + "\"aggregates\":[],\"asOf\":\"2026-01-15\"}",
                entity);
        UUID tenant = UUID.randomUUID();
        QueryLowering.Lowered lowered = new QueryLowering(entity)
                .aggregate("Ar.AgingAr", tenant, query,
                        LocalDate.of(2026, 8, 23));   // override loses to the pinned date
        assertThat(lowered.params().subList(0, 2)).containsExactly(
                "2026-01-15", new BigDecimal("60"));
    }
}
