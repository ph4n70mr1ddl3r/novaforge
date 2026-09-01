package com.novaforge.runtime.engine.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.metadata.EntityDefinition;
import com.novaforge.metadata.PromotionPolicy;
import com.novaforge.runtime.engine.query.QueryLowering.Lowered;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Golden SQL (PHASE-1 §9 item 1): query-DSL fixtures → the exact lowered SQL and bind
 * params. Catches dialect drift and filter-lowering regressions.
 */
class GoldenSqlTests {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static final String ENTITY_JSON = """
            { "apiName": "JournalEntry",
              "displayField": "reference",
              "fields": [
                { "apiName": "reference", "type": "text", "uniqueness": true },
                { "apiName": "entryDate", "type": "date" },
                { "apiName": "status", "type": "enum", "values": ["DRAFT", "POSTED"] },
                { "apiName": "amount", "type": "decimal", "precision": 18, "scale": 4 },
                { "apiName": "memo", "type": "text" }
              ],
              "indexes": [ { "fields": ["entryDate"] } ] }
            """;

    private final EntityDefinition entity =
            DefinitionParser.parse(ENTITY_JSON, EntityDefinition.class);

    @Test
    @DisplayName("promoted columns: index fields + uniqueness + display + lookup promote")
    void promotionPolicy() {
        var promoted = PromotionPolicy.promotedColumns(entity);
        assertThat(promoted)
                .containsEntry("reference", "reference")
                .containsEntry("entryDate", "entry_date")
                .doesNotContainKeys("status", "amount", "memo");
    }

    @Test
    @DisplayName("golden: filtered + sorted list on promoted and unpromoted fields")
    void goldenList() {
        QueryLowering lowering = new QueryLowering(entity);
        QueryModel.ListQuery query = QueryParser.parseList("""
                { "filter": { "and": [
                    { "field": "status", "op": "eq", "value": "POSTED" },
                    { "field": "entryDate", "op": "gte", "value": "2026-01-01" },
                    { "field": "memo", "op": "contains", "value": "spike" },
                    { "field": "amount", "op": "gt", "value": 100.5 } ] },
                  "sort": [ { "field": "entryDate", "dir": "desc" } ],
                  "page": { "size": 50, "offset": 100 } }
                """, entity);
        // the entity key rides QUALIFIED: the projection table is shared by every
        // of the tenant's apps defining the same apiName, so the list scopes rows
        // by entity_id (the Erp-corpus/app collision found live, twenty-sixth pass)
        Lowered lowered = lowering.list("Erp.JournalEntry", TENANT, query);

        assertThat(lowered.sql()).isEqualTo(
                "SELECT id, version, created_at, updated_at, created_by, updated_by, deleted, data "
                        + "FROM rec_journal_entry WHERE tenant_id = ? AND deleted = false "
                        + "AND entity_id = ? "
                        + "AND (((data->>'status') = ?) AND (entry_date >= ?) "
                        + "AND ((data->>'memo') ILIKE ? ESCAPE '\\') "
                        + "AND ((CASE WHEN (data->>'amount')"
                        + " ~ '^-?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?$'"
                        + " THEN (data->>'amount')::numeric END) > ?)) "
                        + "ORDER BY entry_date DESC, id LIMIT ? OFFSET ?");
        assertThat(lowered.params()).containsExactly(
                TENANT, "Erp.JournalEntry", "POSTED", "2026-01-01", "%spike%",
                new BigDecimal("100.5"), 50, 100L);
    }

    @Test
    @DisplayName("golden: count, isNull, ne, in")
    void goldenCount() {
        QueryLowering lowering = new QueryLowering(entity);
        tools.jackson.databind.json.JsonMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        QueryModel.Filter filter = QueryParser.parseFilter(
                mapper.readTree("""
                        { "or": [
                          { "field": "memo", "op": "isNull" },
                          { "field": "status", "op": "ne", "value": "DRAFT" },
                          { "field": "reference", "op": "in", "value": ["JE-1", "JE-2"] } ] }
                        """), entity);
        Lowered lowered = lowering.count("Erp.JournalEntry", TENANT, filter);

        assertThat(lowered.sql()).isEqualTo(
                "SELECT count(*) FROM rec_journal_entry WHERE tenant_id = ? AND deleted = false "
                        + "AND entity_id = ? "
                        + "AND (((data->>'memo') IS NULL) OR (((data->>'status') IS DISTINCT FROM ?)) "
                        + "OR ((reference = ? OR reference = ?)))");
        assertThat(lowered.params()).containsExactly(
                TENANT, "Erp.JournalEntry", "DRAFT", "JE-1", "JE-2");
    }

    @Test
    @DisplayName("golden: aggregate with groupBy")
    void goldenAggregate() {
        QueryLowering lowering = new QueryLowering(entity);
        QueryModel.AggregateQuery query = QueryParser.parseAggregate("""
                { "groupBy": ["status"],
                  "aggregates": [
                    { "op": "count" },
                    { "op": "sum", "field": "amount", "alias": "total" },
                    { "op": "max", "field": "amount" } ] }
                """, entity);
        Lowered lowered = lowering.aggregate("Erp.JournalEntry", TENANT, query);

        // GROUP BY addresses select ordinals since PHASE-5 §3 (a bucketed CASE in the
        // select list would rebind every parameter if repeated in the tail); the
        // numeric leaf is the shape-gated cast (a legacy non-numeric string under a
        // re-typed field evaluates NULL — skipped by the aggregate, never an abort)
        assertThat(lowered.sql()).isEqualTo(
                "SELECT (data->>'status') AS \"status\", count(*) AS \"count\", "
                        + "sum((CASE WHEN (data->>'amount')"
                        + " ~ '^-?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?$'"
                        + " THEN (data->>'amount')::numeric END)) AS \"total\", "
                        + "max((CASE WHEN (data->>'amount')"
                        + " ~ '^-?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?$'"
                        + " THEN (data->>'amount')::numeric END)) AS \"max_amount\" "
                        + "FROM rec_journal_entry WHERE tenant_id = ? AND deleted = false "
                        + "AND entity_id = ? "
                        + "GROUP BY 1");
        assertThat(lowered.params()).containsExactly(TENANT, "Erp.JournalEntry");
    }

    @Test
    @DisplayName("parser rejects: unknown field, bad op, contains on enum, page size over 200")
    void parserRejections() {
        assertThatThrownBy(() -> QueryParser.parseList(
                "{\"filter\":{\"field\":\"nope\",\"op\":\"eq\",\"value\":1}}", entity))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("unknown field");
        assertThatThrownBy(() -> QueryParser.parseList(
                "{\"filter\":{\"field\":\"status\",\"op\":\"regex\",\"value\":\"x\"}}", entity))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("unknown operator");
        assertThatThrownBy(() -> QueryParser.parseList(
                "{\"filter\":{\"field\":\"status\",\"op\":\"contains\",\"value\":\"x\"}}", entity))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("text fields only");
        assertThatThrownBy(() -> QueryParser.parseList(
                "{\"page\":{\"size\":201}}", entity))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("never clamp");
        assertThatThrownBy(() -> QueryParser.parseAggregate(
                "{\"aggregates\":[]}", entity))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("aggregates and/or groupBy");
    }
}
