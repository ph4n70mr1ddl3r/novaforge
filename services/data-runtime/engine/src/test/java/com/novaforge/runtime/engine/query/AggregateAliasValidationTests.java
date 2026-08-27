package com.novaforge.runtime.engine.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.metadata.EntityDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The aggregate alias door (found in the 2025-08-27 review): the alias rides the
 * lowered SELECT list as a quoted identifier (QueryLowering), so an authored alias
 * is grammar-bound at the parse door exactly like a report key — anything that
 * could break out of the quotes rejects VALIDATION_FAILED before any SQL is built.
 */
class AggregateAliasValidationTests {

    private static final String ENTITY_JSON = """
            { "apiName": "JournalEntry",
              "fields": [
                { "apiName": "reference", "type": "text" },
                { "apiName": "amount", "type": "decimal", "precision": 18, "scale": 4 }
              ] }
            """;

    private final EntityDefinition entity =
            DefinitionParser.parse(ENTITY_JSON, EntityDefinition.class);

    @Test
    @DisplayName("plain aliases parse — camelCase and snake_case both ride")
    void plainAliasParses() {
        QueryModel.AggregateQuery camel = QueryParser.parseAggregate(
                "{\"aggregates\":[{\"op\":\"sum\",\"field\":\"amount\",\"alias\":\"debitTotal\"}]}",
                entity);
        assertThat(camel.aggregates().getFirst().alias()).isEqualTo("debitTotal");

        QueryModel.AggregateQuery snake = QueryParser.parseAggregate(
                "{\"aggregates\":[{\"op\":\"sum\",\"field\":\"amount\",\"alias\":\"sum_amount\"}]}",
                entity);
        assertThat(snake.aggregates().getFirst().alias()).isEqualTo("sum_amount");
    }

    @Test
    @DisplayName("an identifier-breaking alias rejects at the parse door")
    void hostileAliasRejects() {
        // the alias value parses to: x", (SELECT data FROM rec_other) AS "leak —
        // the breakout that would splice caller SQL into the aggregate statement
        String json = "{\"aggregates\":[{\"op\":\"count\","
                + "\"alias\":\"x\\\", (SELECT data FROM rec_other) AS \\\"leak\"}]}";
        assertThatThrownBy(() -> QueryParser.parseAggregate(json, entity))
                .isInstanceOfSatisfying(PlatformException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(PlatformErrorCode.VALIDATION_FAILED);
                    assertThat(e.getMessage()).contains("alias");
                });
    }

    @Test
    @DisplayName("milder breakouts — spaces, parens, leading digits — reject identically")
    void mildBreakoutsReject() {
        for (String alias : new String[] {"total amount", "count(x)", "1total", "a-b", "a.b"}) {
            String json = "{\"aggregates\":[{\"op\":\"count\",\"alias\":\""
                    + alias.replace("\"", "\\\"") + "\"}]}";
            assertThatThrownBy(() -> QueryParser.parseAggregate(json, entity))
                    .as("alias %s must reject", alias)
                    .isInstanceOf(PlatformException.class);
        }
    }
}
