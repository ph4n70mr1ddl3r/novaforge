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
