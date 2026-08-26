package com.novaforge.metadata;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The roll-up summary grammar (PHASE-3 §3; PHASE-7 §3.5 grows the conditional form):
 *
 * <pre>OP(relationship[.field] [WHERE condition [AND condition…]])</pre>
 *
 * with {@code OP ∈ SUM|COUNT|MIN|MAX|AVG} — COUNT is the one op that may omit the
 * field. A condition reuses the query DSL's leaf vocabulary verbatim
 * ({@link #CONDITION_OPS}; {@code contains} has no place in a stored-aggregate
 * condition), AND-joined only. Parsed identically by every consumer — the Data
 * Runtime's write path (in-memory inline aggregation and the store-side aggregate
 * query alike) and the Metadata Service's save validator — so they can never
 * disagree about what an authored roll-up means.
 *
 * <p>Condition values are exact literals — single-quoted strings (no escapes in v1),
 * bare decimals ({@link BigDecimal}, never float), or {@code true}/{@code false}.
 * G-15 motivated the WHERE clause: a stock roll-up must count only POSTED
 * movements, not every child row from its create.</p>
 */
public record RollupExpression(String op, String relationship, String field,
                               List<Condition> conditions) {

    /** The DSL leaf ops a condition may use (PHASE-7 §3.5). */
    public static final Set<String> CONDITION_OPS =
            Set.of("eq", "ne", "gt", "gte", "lt", "lte", "in", "isNull");

    /** One AND-joined condition leaf: {@code field op value} (value null for isNull). */
    public record Condition(String field, String op, Object value) {
    }

    private static final Pattern HEAD = Pattern.compile(
            "^(SUM|COUNT|MIN|MAX|AVG)\\(\\s*([a-zA-Z][a-zA-Z0-9]*)"
                    + "(?:\\s*\\.\\s*([a-zA-Z][a-zA-Z0-9]*))?"
                    + "(?:\\s+WHERE\\s+(.+))?\\s*\\)\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** The symbolic forms accepted beside the DSL op names, canonicalized at parse
     *  — a List in fixed order so ">=" is matched before ">" (Map order is unspecified). */
    private static final List<Map.Entry<String, String>> SYMBOLIC_OPS = List.of(
            Map.entry(">=", "gte"), Map.entry("<=", "lte"),
            Map.entry("!=", "ne"), Map.entry("=", "eq"),
            Map.entry(">", "gt"), Map.entry("<", "lt"));

    public RollupExpression {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }

    /**
     * Parses a full roll-up source; throws {@link IllegalArgumentException} with
     * authoring guidance on any malformation — save validation and the runtime both
     * surface it verbatim.
     */
    public static RollupExpression parse(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException(
                    "rollup must be OP(relationship.field) — COUNT(relationship) also allowed");
        }
        Matcher matcher = HEAD.matcher(source.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "rollup must be OP(relationship.field) [WHERE condition [AND …]] — "
                            + "COUNT(relationship) also allowed: " + source);
        }
        boolean count = "COUNT".equalsIgnoreCase(matcher.group(1));
        if (!count && matcher.group(3) == null) {
            throw new IllegalArgumentException("rollup op " + matcher.group(1)
                    + " requires relationship.field (only COUNT may omit the field): " + source);
        }
        return new RollupExpression(matcher.group(1).toUpperCase(Locale.ROOT),
                matcher.group(2), matcher.group(3),
                matcher.group(4) == null || matcher.group(4).isBlank()
                        ? List.of() : parseConditions(matcher.group(4), source));
    }

    // --- condition parsing ---

    private static List<Condition> parseConditions(String where, String source) {
        List<String> parts = splitTopLevel(where, "AND");
        if (parts.isEmpty()) {
            throw new IllegalArgumentException(
                    "a rollup WHERE clause requires at least one condition: " + source);
        }
        List<Condition> conditions = new ArrayList<>();
        for (String part : parts) {
            conditions.add(parseCondition(part.trim()));
        }
        return conditions;
    }

    /**
     * Splits on a top-level keyword only — single-quoted spans are opaque, so a
     * string literal carrying "AND"/"," never splits.
     */
    private static List<String> splitTopLevel(String body, String keyword) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\'') {
                inQuotes = !inQuotes;
                current.append(c);
                continue;
            }
            if (!inQuotes && Character.isWhitespace(c)
                    && body.regionMatches(true, i + 1, keyword + " ", 0, keyword.length() + 1)) {
                parts.add(current.toString());
                current.setLength(0);
                i += keyword.length();   // loop step clears the separator space after
                continue;
            }
            current.append(c);
        }
        parts.add(current.toString());
        return parts.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** Comma-list splitting with the same quote-opacity as {@link #splitTopLevel}. */
    private static List<String> splitTopLevelCommas(String body) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\'') {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (c == ',' && !inQuotes) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static Condition parseCondition(String condition) {
        int fieldEnd = 0;
        while (fieldEnd < condition.length() && !Character.isWhitespace(condition.charAt(fieldEnd))) {
            fieldEnd++;
        }
        String field = condition.substring(0, fieldEnd);
        if (field.isEmpty() || !Character.isLetter(field.charAt(0))
                || !field.chars().skip(1).allMatch(Character::isLetterOrDigit)) {
            throw new IllegalArgumentException(
                    "rollup condition must open with the child field name: " + condition);
        }
        String rest = condition.substring(fieldEnd).trim();
        if (rest.isEmpty()) {
            throw new IllegalArgumentException(
                    "rollup condition requires an operator: " + condition);
        }
        // isNull stands alone
        if (rest.equalsIgnoreCase("isNull")) {
            return new Condition(field, "isNull", null);
        }
        String lower = rest.toLowerCase(Locale.ROOT);
        // symbolic comparison operators first — fixed prefix order, so ">=" wins over ">"
        for (Map.Entry<String, String> symbol : SYMBOLIC_OPS) {
            if (rest.startsWith(symbol.getKey())) {
                String valuePart = rest.substring(symbol.getKey().length()).trim();
                return new Condition(field, symbol.getValue(),
                        requireLiteral(valuePart, condition));
            }
        }
        // DSL-named ops; "in" takes a parenthesized literal list
        String op = lower.split("\\s+", 2)[0];
        if ("in".equals(op)) {
            String listBody = rest.substring(2).trim();
            if (!(listBody.startsWith("(") && listBody.endsWith(")"))) {
                throw new IllegalArgumentException(
                        "rollup in-condition takes a parenthesized literal list: " + condition);
            }
            List<Object> values = new ArrayList<>();
            for (String item : splitTopLevelCommas(listBody.substring(1, listBody.length() - 1))) {
                values.add(parseLiteral(item.trim()));
            }
            if (values.isEmpty()) {
                throw new IllegalArgumentException("rollup in-condition requires literals: " + condition);
            }
            return new Condition(field, "in", values);
        }
        if (CONDITION_OPS.contains(op)) {
            String valuePart = lower.equals(op) ? "" : rest.substring(op.length()).trim();
            return new Condition(field, op, requireLiteral(valuePart, condition));
        }
        throw new IllegalArgumentException(
                "unknown rollup condition operator (use eq ne gt gte lt lte in isNull): "
                        + condition);
    }

    private static Object requireLiteral(String valuePart, String condition) {
        if (valuePart.isBlank()) {
            throw new IllegalArgumentException(
                    "rollup condition requires a value ('quoted', decimal, true/false): " + condition);
        }
        return parseLiteral(valuePart);
    }

    /** One condition value literal: 'text' | decimal | true/false. */
    static Object parseLiteral(String literal) {
        if (literal.length() >= 2 && literal.startsWith("'") && literal.endsWith("'")) {
            String text = literal.substring(1, literal.length() - 1);
            if (text.contains("'")) {
                throw new IllegalArgumentException(
                        "rollup string literals carry no escaped quotes in v1: " + literal);
            }
            return text;
        }
        if (literal.equals("true") || literal.equals("false")) {
            return Boolean.valueOf(literal);
        }
        try {
            return new BigDecimal(literal);
        } catch (NumberFormatException notNumeric) {
            throw new IllegalArgumentException(
                    "rollup condition values are 'quoted strings', plain decimals, or true/false: "
                            + literal);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(op).append('(').append(relationship());
        if (field != null) {
            sb.append('.').append(field);
        }
        sb.append(')');
        if (!conditions.isEmpty()) {
            sb.append(" WHERE ");
            for (int i = 0; i < conditions.size(); i++) {
                if (i > 0) {
                    sb.append(" AND ");
                }
                sb.append(renderCondition(conditions.get(i)));
            }
        }
        return sb.toString();
    }

    private static String renderCondition(Condition c) {
        if ("isNull".equals(c.op())) {
            return c.field() + " isNull";
        }
        StringBuilder sb = new StringBuilder(c.field()).append(' ').append(c.op()).append(' ');
        if (c.value() instanceof List<?> items) {
            sb.append('(');
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(renderLiteral(items.get(i)));
            }
            return sb.append(')').toString();
        }
        return sb.append(renderLiteral(c.value())).toString();
    }

    private static String renderLiteral(Object value) {
        return value instanceof String s ? "'" + s + "'" : String.valueOf(value);
    }
}
