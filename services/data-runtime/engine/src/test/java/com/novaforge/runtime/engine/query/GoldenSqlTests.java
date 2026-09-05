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
    @DisplayName("sharing splice over a regex-sorted list: the tail's literal ?s are never counted as binds")
    void sharingSpliceOverNumericSortTail() {
        // applySharing lowers the sharing clause into list SQL through Lowered.and(),
        // which splits the statement at the ORDER BY tail and counts the tail's ?s to
        // keep the bind list aligned. Sorting by an UNPROMOTED numeric field rides
        // the shape-gated cast — whose regex literal carries four question marks of
        // its own. Counting those as placeholders mis-split the parameter list: the
        // splice threw (or, with a filter in front, silently reordered real binds).
        QueryLowering lowering = new QueryLowering(entity);
        QueryModel.ListQuery query = QueryParser.parseList("""
                { "sort": [ { "field": "amount", "dir": "desc" } ],
                  "page": { "size": 50, "offset": 0 } }
                """, entity);
        Lowered lowered = lowering.list("Erp.JournalEntry", TENANT, query);

        // the exact splice applySharing performs for an owner-set restriction
        UUID owner = UUID.randomUUID();
        Lowered shared = lowered.and("created_by IN (?)", List.of(owner));

        // every placeholder still gets a bind, in order: tenant, entity, owner, limit,
        // offset — the pinned SQL + params pin the alignment exactly (the tail's four
        // regex ?s are literal text, never bind slots)
        org.assertj.core.api.Assertions.assertThat(shared.sql())
                .isEqualTo("SELECT id, version, created_at, updated_at, created_by, updated_by, "
                        + "deleted, data FROM rec_journal_entry WHERE tenant_id = ? AND deleted = false "
                        + "AND entity_id = ? AND (created_by IN (?)) "
                        + "ORDER BY (CASE WHEN (data->>'amount')"
                        + " ~ '^-?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?$'"
                        + " THEN (data->>'amount')::numeric END) DESC, id LIMIT ? OFFSET ?");
        org.assertj.core.api.Assertions.assertThat(shared.params()).containsExactly(
                TENANT, "Erp.JournalEntry", owner, 50, 0L);
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

    @Test
    @DisplayName("parser rejects: an in-leaf without a value key rejects shaped, never NPEs")
    void parserInWithoutValue() {
        // The required-value guard exempts "in" (its own array check carries the
        // message for a present-but-not-array value), but a wholly ABSENT value key
        // rode that exemption straight into value.getNodeType() on a null node — a
        // raw NullPointerException 500 where the parse door owes VALIDATION_FAILED.
        assertThatThrownBy(() -> QueryParser.parseList(
                "{\"filter\":{\"field\":\"status\",\"op\":\"in\"}}", entity))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("in requires an array value");
        assertThatThrownBy(() -> QueryParser.parseList(
                "{\"filter\":{\"and\":[{\"field\":\"status\",\"op\":\"in\"}]}}", entity))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("in requires an array value");
    }

    @Test
    @DisplayName("parser rejects: a non-finite number literal (1e400) rejects shaped, never a raw JsonNodeException 500")
    void parserNonFiniteNumberValue() {
        // A JSON number past double range parses to a non-finite DoubleNode whose
        // decimalValue() throws JsonNodeException — not one of ProblemAdvice's 400
        // mappings, so every query door 500'd (unhandled-error log included) on a
        // malformed REQUEST VALUE. Same class as the in-leaf NPE: the door owes the
        // shaped VALIDATION_FAILED.
        assertThatThrownBy(() -> QueryParser.parseList(
                "{\"filter\":{\"field\":\"amount\",\"op\":\"eq\",\"value\":1e400}}", entity))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("unsupported filter value");
        assertThatThrownBy(() -> QueryParser.parseList(
                "{\"filter\":{\"field\":\"amount\",\"op\":\"gt\",\"value\":-1e400}}", entity))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("unsupported filter value");
        // the same throw hides one level deep: an in-list's items ride decimalValue() too
        assertThatThrownBy(() -> QueryParser.parseList(
                "{\"filter\":{\"field\":\"amount\",\"op\":\"in\",\"value\":[1,1e400]}}", entity))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("unsupported filter value");
        assertThatThrownBy(() -> QueryParser.parseAggregate(
                "{\"filter\":{\"field\":\"amount\",\"op\":\"lte\",\"value\":1.7976931348623157e309},"
                        + "\"aggregates\":[{\"op\":\"count\"}]}", entity))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("unsupported filter value");
        // sanity: the finite band around the hazard still parses and binds
        QueryParser.parseList(
                "{\"filter\":{\"field\":\"amount\",\"op\":\"eq\",\"value\":1e308}}", entity);
    }

    @Test
    @DisplayName("a seek cursor whose position its sort key cannot type rejects shaped at the lowering, never a raw ArithmeticException 500")
    void seekCursorRejectsUntypeablePosition() {
        // decode() validates the contract and the arity, but the POSITION VALUES are
        // typed only where they bind — QueryLowering.seekValue. "1e999999999" is a
        // legal JSON number and a legal BigDecimal, but longValueExact() throws a raw
        // ArithmeticException (not in ProblemAdvice's 400 mapping → 500); "abc"
        // against a numeric key threw an unscoped NumberFormatException. Both owe the
        // page.after-scoped VALIDATION_FAILED the cursor door's contract pins.
        String token = SeekCursor.encode(
                List.of(new QueryModel.Sort("version", QueryModel.SortDir.asc),
                        new QueryModel.Sort("id", QueryModel.SortDir.asc)),
                List.of("1e999999999", "22222222-2222-4222-8222-222222222222"));
        QueryModel.ListQuery query = QueryParser.parseList(
                "{\"sort\":[{\"field\":\"version\",\"dir\":\"asc\"}],"
                        + "\"page\":{\"size\":50,\"after\":\"" + token + "\"}}", entity);
        assertThat(query.seek()).isTrue();
        QueryLowering lowering = new QueryLowering(entity);
        assertThatThrownBy(() -> lowering.list("JournalEntry", TENANT, query))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("page.after")
                .hasMessageContaining("numeric");

        // the id key names its own domain
        String idToken = SeekCursor.encode(
                List.of(new QueryModel.Sort("id", QueryModel.SortDir.asc)),
                List.of("not-a-uuid"));
        QueryModel.ListQuery idQuery = QueryParser.parseList(
                "{\"page\":{\"size\":50,\"after\":\"" + idToken + "\"}}", entity);
        assertThatThrownBy(() -> lowering.list("JournalEntry", TENANT, idQuery))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("page.after")
                .hasMessageContaining("uuid");

        // and a well-typed cursor still lowers untouched (finite decimal pos)
        String goodToken = SeekCursor.encode(
                List.of(new QueryModel.Sort("amount", QueryModel.SortDir.asc),
                        new QueryModel.Sort("id", QueryModel.SortDir.asc)),
                List.of(new BigDecimal("90.00"), "22222222-2222-4222-8222-222222222222"));
        QueryModel.ListQuery goodQuery = QueryParser.parseList(
                "{\"sort\":[{\"field\":\"amount\",\"dir\":\"asc\"}],"
                        + "\"page\":{\"size\":50,\"after\":\"" + goodToken + "\"}}", entity);
        Lowered lowered = lowering.list("JournalEntry", TENANT, goodQuery);
        assertThat(lowered.sql()).contains("ORDER BY");
    }
}
