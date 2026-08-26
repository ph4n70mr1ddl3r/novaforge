package com.novaforge.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The roll-up grammar (PHASE-3 §3; PHASE-7 §3.5's conditional growth): one parser
 * shared by save validation and the Data Runtime write path, so they can never
 * disagree about what an authored roll-up means.
 */
class RollupExpressionTests {

    @Test
    @DisplayName("classic forms parse exactly as before")
    void classicForms() {
        RollupExpression sum = RollupExpression.parse("SUM(lines.debit)");
        assertThat(sum.op()).isEqualTo("SUM");
        assertThat(sum.relationship()).isEqualTo("lines");
        assertThat(sum.field()).isEqualTo("debit");
        assertThat(sum.conditions()).isEmpty();

        RollupExpression count = RollupExpression.parse("COUNT(movements)");
        assertThat(count.op()).isEqualTo("COUNT");
        assertThat(count.field()).isNull();
        assertThat(count.conditions()).isEmpty();
    }

    @Test
    @DisplayName("PHASE-7 §3.5: a WHERE clause parses into AND-joined DSL leaves")
    void conditionalForm() {
        RollupExpression posted = RollupExpression.parse(
                "SUM(movements.qty WHERE status = 'POSTED')");
        assertThat(posted.conditions()).hasSize(1);
        RollupExpression.Condition condition = posted.conditions().getFirst();
        assertThat(condition.field()).isEqualTo("status");
        assertThat(condition.op()).isEqualTo("eq");
        assertThat(condition.value()).isEqualTo("POSTED");

        RollupExpression multi = RollupExpression.parse(
                "SUM(movements.qty WHERE kind = 'issue' AND status != 'VOID' AND qty >= 1)");
        assertThat(multi.conditions()).hasSize(3);
        assertThat(multi.conditions().get(0).op()).isEqualTo("eq");
        assertThat(multi.conditions().get(1).op()).isEqualTo("ne");
        assertThat(multi.conditions().get(2).value()).isEqualTo(new BigDecimal("1"));
    }

    @Test
    @DisplayName("symbolic operators canonicalize; in-lists and isNull parse")
    void operatorForms() {
        RollupExpression symbolic = RollupExpression.parse(
                "SUM(movements.qty WHERE qty > 0 AND rate <= 4.50)");
        assertThat(symbolic.conditions().get(0).op()).isEqualTo("gt");
        assertThat(symbolic.conditions().get(1).op()).isEqualTo("lte");

        RollupExpression inList = RollupExpression.parse(
                "SUM(movements.qty WHERE status in ('DRAFT','POSTED'))");
        RollupExpression.Condition condition = inList.conditions().getFirst();
        assertThat(condition.op()).isEqualTo("in");
        assertThat((List<Object>) condition.value()).containsExactly("DRAFT", "POSTED");

        RollupExpression absent = RollupExpression.parse(
                "SUM(movements.qty WHERE settledAt isNull)");
        assertThat(absent.conditions().getFirst().op()).isEqualTo("isNull");
        assertThat(absent.conditions().getFirst().value()).isNull();
    }

    @Test
    @DisplayName("quoted strings are opaque — an embedded AND or comma never splits")
    void quotedLiteralsAreOpaque() {
        RollupExpression expression = RollupExpression.parse(
                "SUM(entries.qty WHERE label = 'end AND close, net' AND status = 'POSTED')");
        assertThat(expression.conditions()).hasSize(2);
        assertThat(expression.conditions().getFirst().value())
                .isEqualTo("end AND close, net");
    }

    @Test
    @DisplayName("malformed sources reject with authoring guidance")
    void malformedReject() {
        assertThatThrownBy(() -> RollupExpression.parse("SUM(  )"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OP(relationship.field)");
        assertThatThrownBy(() -> RollupExpression.parse("SUM(movements)"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires relationship.field");
        assertThatThrownBy(() -> RollupExpression.parse("SUM(movements.qty WHERE total > )"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a value");
        assertThatThrownBy(() -> RollupExpression.parse("SUM(movements.qty WHERE x like '%a%')"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown rollup condition operator");
        assertThatThrownBy(() -> RollupExpression.parse("SUM(movements.qty WHERE amt > 0x10)"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plain decimals");
        // the legacy validator error shape — COUNT without field stays legal
        RollupExpression count = RollupExpression.parse("count(MOVEMENTS)");
        assertThat(count.op()).isEqualTo("COUNT");
        assertThat(count.relationship()).isEqualTo("MOVEMENTS");
    }
}
