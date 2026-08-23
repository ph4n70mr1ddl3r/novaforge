package com.novaforge.expression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.novaforge.expression.ExpressionSql.Field;
import com.novaforge.expression.ExpressionSql.FieldResolver;
import com.novaforge.expression.ExpressionSql.Lowered;
import com.novaforge.expression.ExpressionSql.SqlType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The SQL lowering's contract (PHASE-5 §3): lowered SQL binds its parameters in
 * left-to-right order, mirrors the Annex-A evaluator's null semantics, and rejects
 * every construct whose SQL semantics could diverge — loudly, so save-time
 * compilation catches it before a report ever runs.
 */
class ExpressionSqlTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 23);
    private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");

    /** A record-ish resolver: the field's SQL is just its quoted apiName here. */
    private static final FieldResolver FIELDS = path -> switch (path) {
        case "dueDate" -> new Field("'dueDate'", SqlType.DATE);
        case "amount" -> new Field("'amount'", SqlType.NUMBER);
        case "status" -> new Field("'status'", SqlType.TEXT);
        case "active" -> new Field("'active'", SqlType.BOOLEAN);
        default -> null;
    };

    private static Lowered lower(String source) {
        return ExpressionSql.lowerBoolean(Expression.parse(source), FIELDS, TODAY, NOW);
    }

    @Test
    @DisplayName("aging expressions lower with the clock and the field bound in order")
    void agingLowersWithBoundClock() {
        Lowered lowered = lower("today() - dueDate < 0");
        assertThat(lowered.sql()).isEqualTo(
                "((CAST(? AS date) - CAST('dueDate' AS date)) < ?)");
        // today binds as canonical ISO text — ordering rides canonical text (ADR-001),
        // arithmetic casts to date.
        assertThat(lowered.params()).containsExactly("2026-08-23", new BigDecimal("0"));
    }

    @Test
    @DisplayName("compound aging buckets bind left-to-right")
    void compoundBucketsBindInOrder() {
        Lowered lowered = lower(
                "today() - dueDate >= 0 && today() - dueDate <= 30 && amount > 100.50");
        assertThat(lowered.params()).containsExactly(
                "2026-08-23", new BigDecimal("0"), "2026-08-23", new BigDecimal("30"),
                new BigDecimal("100.50"));
        assertThat(lowered.sql()).startsWith("(").contains(" AND ").endsWith(")");
    }

    @Test
    @DisplayName("null-aware equality: == null / != null become IS [NOT] NULL")
    void nullAwareEquality() {
        assertThat(lower("dueDate == null").sql())
                .isEqualTo("('dueDate' IS NULL)");
        assertThat(lower("status != null").sql())
                .isEqualTo("('status' IS NOT NULL)");
    }

    @Test
    @DisplayName("!= against a value is IS DISTINCT FROM — null != value is true (Annex A)")
    void distinctFromMatchesEvaluatorNullSemantics() {
        assertThat(lower("status != 'VOID'").sql())
                .isEqualTo("('status' IS DISTINCT FROM ?)");
    }

    @Test
    @DisplayName("booleans lower in the canonical 'true'/'false' text form")
    void booleansCanonicalize() {
        Lowered lowered = lower("active == true");
        assertThat(lowered.sql()).isEqualTo("('active' = ?)");
        assertThat(lowered.params()).containsExactly("true");
    }

    @Test
    @DisplayName("in lowers to an OR-chain of equalities")
    void inLowersToOrChain() {
        Lowered lowered = lower("status in ('POSTED', 'OPEN')");
        assertThat(lowered.sql()).isEqualTo("('status' = ? OR 'status' = ?)");
        assertThat(lowered.params()).containsExactly("POSTED", "OPEN");
    }

    @Test
    @DisplayName("date ± integer shifts; date - date yields days; numerics stay numeric")
    void dateArithmeticShapes() {
        Lowered shift = lower("dueDate + 3 > today()");
        assertThat(shift.sql()).isEqualTo(
                "((CAST('dueDate' AS date) + CAST(? AS integer)) > ?)");
        assertThat(shift.params()).containsExactly(new BigDecimal("3"), "2026-08-23");
        assertThat(lower("today() - dueDate >= 30").sql()).isEqualTo(
                "((CAST(? AS date) - CAST('dueDate' AS date)) >= ?)");
    }

    @Test
    @DisplayName("division guards the divisor with NULLIF (a zero divisor cannot bucket)")
    void divisionGuardsZeroDivisor() {
        Lowered lowered = lower("amount / 3 > 0");
        assertThat(lowered.sql()).isEqualTo("((('amount') / NULLIF((?), 0)) > ?)");
        assertThat(lowered.params()).containsExactly(new BigDecimal("3"),
                new BigDecimal("0"));
    }

    @Test
    @DisplayName("text functions with proven parity lower; membership booleans lower")
    void parityProvenFunctionsLower() {
        assertThat(lower("upper(status) == 'POSTED'").sql())
                .isEqualTo("(upper('status') = ?)");
        assertThat(lower("contains(status, 'PENDING')").sql())
                .isEqualTo("(('status') LIKE '%' || (?) || '%')");
    }

    @Test
    @DisplayName("parity-divergent constructs reject loudly, never lower differently")
    void divergentConstructsReject() {
        assertThatThrownBy(() -> lower("round(amount, 2) > 1"))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("round() does not lower");
        assertThatThrownBy(() -> lower("min(amount, 3) > 1"))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("min()/max() do not lower");
        assertThatThrownBy(() -> lower("status.size() > 1"))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("method calls do not lower");
        assertThatThrownBy(() -> lower("unknownField > 1"))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("unresolved reference");
    }

    @Test
    @DisplayName("type mismatches reject (numeric vs text vs boolean)")
    void typeMismatchesReject() {
        assertThatThrownBy(() -> lower("amount == 'x'"))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("one type");
        assertThatThrownBy(() -> lower("!amount"))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("boolean operand");
        assertThatThrownBy(() -> lower("status + 1 == 'x1'"))
                .isInstanceOf(ExpressionException.class);
    }

    @Test
    @DisplayName("checkLowerable accepts a valid bucket and rejects a divergent one")
    void checkLowerableGatesAuthoring() {
        ExpressionSql.checkLowerable(Expression.parse("today() - dueDate > 60"), FIELDS);
        assertThatThrownBy(() -> ExpressionSql.checkLowerable(
                Expression.parse("round(amount, 0) > 1"), FIELDS))
                .isInstanceOf(ExpressionException.class);
    }

    @Test
    @DisplayName("unary operators lower with their parenthesized operand")
    void unaryOperators() {
        assertThat(lower("!active").sql()).isEqualTo("(NOT ('active'))");
        assertThat(lower("-amount < 0").sql()).isEqualTo("((-('amount')) < ?)");
    }

    @Test
    @DisplayName("a non-boolean expression refuses to lower as a predicate")
    void nonBooleanRejects() {
        assertThatThrownBy(() -> lower("amount + 1"))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("boolean predicate");
    }

    @Test
    @DisplayName("now() binds the run instant in canonical form")
    void nowBindsInstant() {
        Lowered lowered = lower("dueDate != null || active == false");
        assertThat(lowered.sql()).isEqualTo(
                "(('dueDate' IS NOT NULL) OR ('active' = ?))");
        assertThat(lowered.params()).containsExactly("false");
        assertThat(ExpressionSql.lowerBoolean(Expression.parse("now() != null"),
                FIELDS, TODAY, NOW).params())
                .containsExactly("2026-08-23T12:00:00Z");
    }
}
