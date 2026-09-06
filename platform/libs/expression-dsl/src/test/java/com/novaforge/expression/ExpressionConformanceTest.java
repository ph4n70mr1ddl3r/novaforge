package com.novaforge.expression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.novaforge.expression.Expression.CompilePolicy;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The conformance corpus (PHASE-2 §7): every case in
 * {@code conformance/expr-v1-corpus.json} runs against this engine; the TS twin runs
 * the same file — additions ship fixtures first.
 */
class ExpressionConformanceTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    @DisplayName("every corpus case passes (valid parses/evaluates as pinned, invalid rejects)")
    void corpus() throws Exception {
        String corpus;
        try (InputStream in = ExpressionConformanceTest.class.getResourceAsStream(
                "/conformance/expr-v1-corpus.json")) {
            corpus = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        JsonNode cases = MAPPER.readTree(corpus).get("cases");
        List<String> failures = new ArrayList<>();
        for (JsonNode item : cases) {
            String name = item.path("name").asString();
            try {
                run(item);
            } catch (Throwable t) {
                failures.add(name + ": " + t.getMessage());
            }
        }
        assertThat(failures).as("conformance failures").isEmpty();
    }

    @Test
    @DisplayName("a non-integer round scale is authoring feedback (ExpressionException, renders 400) — never the raw ArithmeticException that 500s the write path")
    void roundNonIntegerScaleIsAuthoringFeedback() {
        // round(x, 1.5) parses and compile-checks clean (arity 2, numeric shapes), so
        // the defect only surfaces at evaluation — on a stored formula that is the
        // write path. The evaluator must reject with ExpressionException (ProblemAdvice
        // renders it 400 VALIDATION_FAILED), not BigDecimal.intValueExact()'s
        // ArithmeticException, which fell to the 500 handler.
        Expression expression = Expression.parse("round(total, 1.5)");
        Map<String, Object> bindings = Map.of("total", new BigDecimal("50.00"));
        assertThatThrownBy(() -> expression.evaluate(Expression.Bindings.of(bindings),
                Clock.fixed(Instant.parse("2026-09-04T00:00:00Z"), ZoneOffset.UTC)))
                .isInstanceOf(ExpressionException.class)
                .isNotInstanceOf(ArithmeticException.class)
                .hasMessageContaining("integer scale");
    }

    @Test
    @DisplayName("a day count beyond long range is authoring feedback — never the raw ArithmeticException from longValueExact that 500s the write path")
    void outOfRangeDayCountIsAuthoringFeedback() {
        // date + 1e30 parses and compile-checks clean (numeric operand of date
        // arithmetic), so the defect only surfaces at evaluation — on a stored
        // formula that is the write path. BigDecimal.longValueExact()'s raw
        // ArithmeticException fell to the 500 handler instead of the 400 the
        // evaluation-failure contract pins.
        Expression expression = Expression.parse("entryDate + days");
        Map<String, Object> bindings = Map.of(
                "entryDate", LocalDate.parse("2026-08-31"),
                "days", new BigDecimal("1e30"));
        assertThatThrownBy(() -> expression.evaluate(Expression.Bindings.of(bindings),
                Clock.fixed(Instant.parse("2026-09-04T00:00:00Z"), ZoneOffset.UTC)))
                .isInstanceOf(ExpressionException.class)
                .isNotInstanceOf(ArithmeticException.class)
                .hasMessageContaining("calendar range");
    }

    @Test
    @DisplayName("a day count in long range but beyond the calendar is authoring feedback — plusDays' overflow exception must not escape raw")
    void calendarOverflowingDayCountIsAuthoringFeedback() {
        // 9e18 fits a long, so requireDays passes it — LocalDate.plusDays then
        // overflows with its own raw ArithmeticException/DateTimeException, which
        // equally 500s the write path evaluating the stored formula.
        Expression expression = Expression.parse("entryDate + days");
        Map<String, Object> bindings = Map.of(
                "entryDate", LocalDate.parse("2026-08-31"),
                "days", new BigDecimal("9000000000000000000"));
        assertThatThrownBy(() -> expression.evaluate(Expression.Bindings.of(bindings),
                Clock.fixed(Instant.parse("2026-09-04T00:00:00Z"), ZoneOffset.UTC)))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("outside the supported calendar range");
    }

    @Test
    @DisplayName("a round scale past the engines' supported range is authoring feedback (ExpressionException), never a raw crash — the int boundary and both signs included")
    void roundScalePastEngineRangeIsAuthoringFeedback() {
        // 2147483647 fits intValueExact(), but BigDecimal.setScale overflows its
        // compact range there (“BigInteger would overflow supported range”, the
        // same from ±646456884 up) — the raw ArithmeticException fell to the 500
        // handler. The rejection must stay the 400-shaped ExpressionException, and
        // it must hold for NEGATIVE scales too (the mirror band, -2147483000
        // included). Within the band the compact scales still compute.
        Clock clock = Clock.fixed(Instant.parse("2026-09-04T00:00:00Z"), ZoneOffset.UTC);
        for (String scale : new String[] {"2147483647", "646456884", "-646456884", "-2147483000"}) {
            Expression expression = Expression.parse("round(total, " + scale + ")");
            assertThatThrownBy(() -> expression.evaluate(
                    Expression.Bindings.of(Map.of("total", new BigDecimal("50.5"))), clock))
                    .as("scale " + scale + " must reject")
                    .isInstanceOf(ExpressionException.class)
                    .isNotInstanceOf(ArithmeticException.class)
                    .hasMessageContaining("out of range");
        }
        Object compact = Expression.parse("round(total, 1000000)").evaluate(
                Expression.Bindings.of(Map.of("total", new BigDecimal("50.5"))), clock);
        assertThat(((BigDecimal) compact).compareTo(new BigDecimal("50.5"))).isZero();
    }

    @Test
    @DisplayName("abs(MATH)/negate(MATH) round to the 34-digit context — the verdicts the TS twin's context-precision pin mirrors")
    void contextBearingUnaryOpsRound() {
        // The reference engine carries MathContext(34, HALF_EVEN) into negate and abs,
        // so a literal past 34 significant digits ROUNDs — the TS twin's unary minus
        // and abs() must answer the same value (frontend/shared test/
        // expression-context.test.ts pins the mirror). A future change here that
        // drops the context breaks twin parity silently — this pin makes it loud.
        BigDecimal fortyDigits = new BigDecimal("1234567890123456789012345678901234567890");
        BigDecimal expected = new BigDecimal("1234567890123456789012345678901235000000");
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        Object abs = Expression.parse("abs(x)").evaluate(
                Expression.Bindings.of(Map.of("x", fortyDigits)), clock);
        Object negated = Expression.parse("-x").evaluate(
                Expression.Bindings.of(Map.of("x", fortyDigits)), clock);
        assertThat(((BigDecimal) abs).compareTo(expected)).as("abs(MATH) rounds to the context").isZero();
        assertThat(((BigDecimal) negated).compareTo(expected.negate()))
                .as("negate(MATH) rounds to the context").isZero();
    }

    @Test
    @DisplayName("an INT field binding (Integer) compares by value in ordered comparisons — quantity >= 0 must not throw on the write path")
    void intBindingOrdersByValue() {
        // FieldCoercer canonicalizes INT to Integer; the evaluator's strict
        // instanceof-BigDecimal compare used to throw "ordered comparison requires two
        // operands of one comparable type" — a 400 on EVERY write of an entity whose
        // validation rule read an INT field (statically blessed as NUMERIC).
        Clock clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
        Object pass = Expression.parse("quantity >= 0").evaluate(
                Expression.Bindings.of(Map.of("quantity", 5)), clock);
        assertThat(pass).isEqualTo(Boolean.TRUE);
        Object againstDecimal = Expression.parse("quantity > 4.5").evaluate(
                Expression.Bindings.of(Map.of("quantity", 5)), clock);
        assertThat(againstDecimal).as("Integer 5 > decimal 4.5 compares by value").isEqualTo(Boolean.TRUE);
        Object fail = Expression.parse("quantity > 5").evaluate(
                Expression.Bindings.of(Map.of("quantity", 5)), clock);
        assertThat(fail).isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("an INT × MONEY formula evaluates to the exact decimal — never 'arithmetic requires numeric operands'")
    void intTimesMoneyFormulaEvaluates() {
        // lines.amount = quantity * unitPrice with an INT quantity passes the static
        // arithmetic check (INT is numeric()); the strict pair() used to reject the
        // same shape at evaluation — 400 on every write, on the corpus's own ERP
        // formula shape.
        Clock clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
        Object amount = Expression.parse("quantity * unitPrice").evaluate(
                Expression.Bindings.of(Map.of("quantity", 5,
                        "unitPrice", new BigDecimal("10.50"))), clock);
        assertThat(((BigDecimal) amount).compareTo(new BigDecimal("52.50"))).isZero();
    }

    @Test
    @DisplayName("quantity == 5 is TRUE when quantity is the Integer 5 — the silent wrong answer must stay dead")
    void intEqualityIsValueBased() {
        // Integer.equals(BigDecimal) is false, so the strict-equals fallback answered
        // quantity == 5 with FALSE on a quantity of 5 — silently flipping validation
        // outcomes instead of failing loudly. Equality across numeric carriers is by
        // value (the TS twin's either-Decimal leg), scale-insensitive, and a number
        // never equals a non-number.
        Clock clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
        assertThat(Expression.parse("quantity == 5").evaluate(
                Expression.Bindings.of(Map.of("quantity", 5)), clock)).isEqualTo(Boolean.TRUE);
        assertThat(Expression.parse("quantity == 5.0").evaluate(
                Expression.Bindings.of(Map.of("quantity", 5)), clock)).isEqualTo(Boolean.TRUE);
        assertThat(Expression.parse("quantity != 4").evaluate(
                Expression.Bindings.of(Map.of("quantity", 5)), clock)).isEqualTo(Boolean.TRUE);
        assertThat(Expression.parse("quantity == '5'").evaluate(
                Expression.Bindings.of(Map.of("quantity", 5)), clock))
                .as("a number never equals a string").isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("LONG, JSON-typed, and day-count carriers ride the same value domain — Long + literal and Integer day counts evaluate")
    void longAndJsonCarriersEvaluate() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
        Object sum = Expression.parse("bigCount + 1").evaluate(
                Expression.Bindings.of(Map.of("bigCount", 10L)), clock);
        assertThat(((BigDecimal) sum).compareTo(new BigDecimal("11"))).isZero();

        // a JSON-typed field rides through FieldCoercer unparsed — Jackson materializes
        // its numbers as Integer/Long/Double, and the hook/graph dot-paths read them raw
        Object product = Expression.parse("payload.factor * 4").evaluate(
                Expression.Bindings.of(Map.of("payload", Map.of("factor", 2.5))), clock);
        assertThat(((BigDecimal) product).compareTo(BigDecimal.TEN)).isZero();

        // an INT binding as a date day count: entryDate + graceDays
        Object dated = Expression.parse("entryDate + graceDays").evaluate(
                Expression.Bindings.of(Map.of("entryDate", LocalDate.parse("2026-08-31"),
                        "graceDays", 30)), clock);
        assertThat(dated).isEqualTo(LocalDate.parse("2026-09-30"));
    }

    private void run(JsonNode item) {
        String source = item.path("expr").asString();
        boolean invalid = item.path("invalid").asBoolean(false);
        Expression expression;
        try {
            expression = Expression.parse(source);
        } catch (ExpressionException e) {
            if (invalid) {
                return;
            }
            throw new AssertionError("parse failed: " + e.getMessage(), e);
        }
        if (invalid) {
            // Invalid cases must fail at parse, compile-check, or evaluation.
            assertThatThrownBy(() -> evaluate(item, expression))
                    .as(item.path("name").asString() + " must reject")
                    .isInstanceOf(Throwable.class);
            return;
        }
        Object actual = evaluate(item, expression);
        Object expected = decode(item.get("expect"));
        if (expected instanceof BigDecimal expectedDecimal) {
            assertThat(((BigDecimal) actual).compareTo(expectedDecimal))
                    .as(item.path("name").asString()).isZero();
        } else {
            assertThat(actual).as(item.path("name").asString()).isEqualTo(expected);
        }
    }

    private Object evaluate(JsonNode item, Expression expression) {
        JsonNode policyNode = item.get("policy");
        if (policyNode != null) {
            Set<String> bindings = new java.util.HashSet<>();
            policyNode.get("bindings").forEach(b -> bindings.add(b.asString()));
            expression.compileCheck(new CompilePolicy(bindings,
                    policyNode.path("allowClock").asBoolean(true)));
        }
        JsonNode typesNode = item.get("types");
        if (typesNode != null) {
            // The static arithmetic guard's corpus leg (PHASE-3 §2): binding shapes
            // declared per case — the same declaration the TS twin runs against.
            java.util.Map<String, Expression.ValueType> types = new java.util.HashMap<>();
            typesNode.properties().forEach(entry ->
                    types.put(entry.getKey(), Expression.ValueType.of(entry.getValue().asString())));
            expression.arithmeticCheck(types::get);
        }
        Map<String, Object> bindings = new LinkedHashMap<>();
        item.path("bindings").properties().forEach(entry ->
                bindings.put(entry.getKey(), decode(entry.getValue())));
        Clock clock = item.hasNonNull("clock")
                ? Clock.fixed(Instant.parse(item.get("clock").asString()), ZoneOffset.UTC)
                : Clock.systemUTC();
        return expression.evaluate(Expression.Bindings.of(bindings), clock);
    }

    /** Decodes corpus values: numbers as exact decimals, dates/instants tagged. */
    static Object decode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject() && node.has("$date")) {
            return LocalDate.parse(node.get("$date").asString());
        }
        if (node.isObject() && node.has("$instant")) {
            return Instant.parse(node.get("$instant").asString());
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.properties().forEach(entry -> map.put(entry.getKey(), decode(entry.getValue())));
            return map;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isArray()) {
            List<Object> items = new ArrayList<>();
            node.forEach(child -> items.add(decode(child)));
            return items;
        }
        return node.asString();
    }
}
