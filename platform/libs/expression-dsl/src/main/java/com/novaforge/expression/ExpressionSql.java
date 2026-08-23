package com.novaforge.expression;

import com.novaforge.expression.Expression.Node;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL lowering for expr/v1 (PHASE-5 §3/§4): compiles an authored expression — a report
 * bucket condition or a sharing-rule criteria — into a SQL boolean expression over the
 * record's columns, so grouping/filtering happens <em>in the aggregate pipeline, not
 * client-side shaping</em>. The same grammar powers the JVM evaluator; this class is the
 * second execution surface, and it is deliberately partial: every construct whose SQL
 * semantics could diverge from {@link Expression}'s Annex-A evaluator rejects loudly
 * ({@link #lower} throws) rather than silently evaluating differently.
 *
 * <p>Semantics parity with the evaluator, construct by construct:</p>
 * <ul>
 *   <li>Null-aware equality — {@code x == null} lowers to {@code x IS NULL},
 *       {@code !=} to {@code IS NOT NULL}; against a value, {@code ==} is {@code =}
 *       (SQL NULL → not-matched, as the evaluator's null-aware false) and {@code !=}
 *       is {@code IS DISTINCT FROM} (NULL ≠ value is TRUE, matching the evaluator).</li>
 *   <li>Ordered comparisons with a null operand are false — SQL NULL comparison is
 *       not-matched under WHERE/CASE, same outcome.</li>
 *   <li>Date arithmetic per Annex A: {@code date - date} → integer days,
 *       {@code date ± integer} → date; temporal values lower through PostgreSQL
 *       {@code CAST(… AS date)} over the canonical ISO text the platform stores
 *       (ADR-001), and equality/ordering on canonical text is exact without casts.</li>
 *   <li>Booleans compare in the platform's canonical {@code 'true'/'false'} text form
 *       (the query DSL's convention, PHASE-1 §5).</li>
 *   <li>Division guards the divisor with {@code NULLIF} so a zero divisor yields
 *       not-matched rather than aborting the statement — the per-row analogue of a
 *       row that cannot bucket.</li>
 * </ul>
 *
 * <p>Supported: literals, field references (host-resolved), {@code ! -} unary,
 * {@code && || == != < <= > >= in + - * /} binary, and the functions whose SQL and
 * JVM semantics provably agree — {@code today, now, date, datetime, abs, upper,
 * lower, trim, length, contains, startsWith}. Rejected: {@code round} (PostgreSQL
 * rounds half-away-from-zero, the evaluator half-even), {@code min/max} (LEAST/GREATEST
 * skip NULLs, the evaluator throws), {@code size} (collections do not lower), method
 * calls, and any type mismatch.</p>
 */
public final class ExpressionSql {

    /** The SQL-side types a lowered node can carry. */
    public enum SqlType { NUMBER, TEXT, DATE, DATETIME, TIME, BOOLEAN }

    /** A host-resolved field reference: its SQL expression and SQL-side type. */
    public record Field(String sql, SqlType type) {
    }

    /**
     * Resolves reference paths (field apiNames). Returning null marks the path
     * unresolved — the caller's compile check surfaces that as an authoring error.
     */
    public interface FieldResolver {
        Field resolve(String path);
    }

    /** A lowered node: SQL with ordered bind parameters, plus its type. */
    public record Lowered(String sql, List<Object> params, SqlType type) {
    }

    private final FieldResolver fields;
    private final LocalDate today;
    private final Instant now;

    private final List<Object> params = new ArrayList<>();

    private ExpressionSql(FieldResolver fields, LocalDate today, Instant now) {
        this.fields = fields;
        this.today = today;
        this.now = now;
    }

    /**
     * Lowers an expression to a boolean SQL fragment. The evaluation date/instants are
     * bound, not inlined — the run's governing clock (a suite's frozen clock pins
     * deterministic buckets, PHASE-3 §7).
     */
    public static Lowered lowerBoolean(Expression expression, FieldResolver fields,
                                       LocalDate today, Instant now) {
        Lowered lowered = new ExpressionSql(fields, today, now).lower(expression.root());
        if (lowered.type() != SqlType.BOOLEAN) {
            throw new ExpressionException(
                    "expression must lower to a boolean predicate: " + expression.source());
        }
        return lowered;
    }

    /** Lowering viability check — the save/publish compile gate calls this (PHASE-5 §3). */
    public static void checkLowerable(Expression expression, FieldResolver fields) {
        new ExpressionSql(fields, LocalDate.of(2000, 1, 1), Instant.parse(
                "2000-01-01T00:00:00Z")).lower(expression.root());
    }

    // --- lowering ---

    private Lowered lower(Node node) {
        return switch (node) {
            case Node.Literal literal -> literal(literal);
            case Node.Reference reference -> reference(reference);
            case Node.Unary unary -> unary(unary);
            case Node.Binary binary -> binary(binary);
            case Node.Call call -> call(call);
            case Node.ListLiteral list -> throw new ExpressionException(
                    "list literals lower only as the right side of 'in'");
            case Node.Method method -> throw new ExpressionException(
                    "method calls do not lower to SQL: ." + method.name() + "()");
        };
    }

    private Lowered literal(Node.Literal literal) {
        Object value = literal.value();
        if (value == null) {
            return new Lowered("NULL", List.copyOf(params), null);
        }
        if (value instanceof Boolean b) {
            return bind(b ? "true" : "false", SqlType.BOOLEAN);
        }
        if (value instanceof BigDecimal decimal) {
            return bind(decimal, SqlType.NUMBER);
        }
        if (value instanceof String s) {
            return bind(s, SqlType.TEXT);
        }
        throw new ExpressionException("literal does not lower to SQL: " + value);
    }

    private Lowered reference(Node.Reference reference) {
        Field field = fields.resolve(reference.path());
        if (field == null) {
            throw new ExpressionException(
                    "unresolved reference '" + reference.path() + "' in SQL lowering");
        }
        return new Lowered(field.sql(), List.copyOf(params), field.type());
    }

    private Lowered unary(Node.Unary unary) {
        Lowered operand = lower(unary.operand());
        return switch (unary.op()) {
            case "!" -> {
                requireBoolean(operand, "!");
                yield new Lowered("(NOT (" + operand.sql() + "))", List.copyOf(params),
                        SqlType.BOOLEAN);
            }
            case "-" -> {
                requireNumber(operand, "unary -");
                yield new Lowered("(-(" + operand.sql() + "))", List.copyOf(params),
                        SqlType.NUMBER);
            }
            default -> throw new ExpressionException("unknown unary operator " + unary.op());
        };
    }

    private Lowered binary(Node.Binary binary) {
        String op = binary.op();
        if (op.equals("&&") || op.equals("||")) {
            Lowered left = lower(binary.left());
            Lowered right = lower(binary.right());
            requireBoolean(left, op);
            requireBoolean(right, op);
            String joiner = op.equals("&&") ? " AND " : " OR ";
            // Sub-expressions self-parenthesize, so one wrapper keeps precedence exact.
            return new Lowered("(" + left.sql() + joiner + right.sql() + ")",
                    List.copyOf(params), SqlType.BOOLEAN);
        }
        if (op.equals("in")) {
            return membership(binary);
        }
        Lowered left = lower(binary.left());
        Lowered right = lower(binary.right());
        return switch (op) {
            case "==" -> equality(left, right, false);
            case "!=" -> equality(left, right, true);
            case "<", "<=", ">", ">=" -> ordered(op, left, right);
            case "+", "-" -> additive(op, left, right);
            case "*" -> {
                requireNumber(left, "*");
                requireNumber(right, "*");
                yield new Lowered("((" + left.sql() + ") * (" + right.sql() + "))",
                        List.copyOf(params), SqlType.NUMBER);
            }
            case "/" -> {
                requireNumber(left, "/");
                requireNumber(right, "/");
                yield new Lowered("((" + left.sql() + ") / NULLIF((" + right.sql()
                        + "), 0))", List.copyOf(params), SqlType.NUMBER);
            }
            default -> throw new ExpressionException("unknown operator " + op);
        };
    }

    /** Null-aware equality (Annex A): {@code == null}/{@code != null} become IS [NOT] NULL. */
    private Lowered equality(Lowered left, Lowered right, boolean negated) {
        if (left.type() == null) {
            return isNull(right, negated);
        }
        if (right.type() == null) {
            return isNull(left, negated);
        }
        requireSameType(left, right, negated ? "!=" : "==");
        if (negated) {
            return new Lowered("(" + left.sql() + " IS DISTINCT FROM " + right.sql() + ")",
                    List.copyOf(params), SqlType.BOOLEAN);
        }
        return new Lowered("(" + left.sql() + " = " + right.sql() + ")",
                List.copyOf(params), SqlType.BOOLEAN);
    }

    private Lowered isNull(Lowered operand, boolean negated) {
        return new Lowered("(" + operand.sql() + (negated ? " IS NOT NULL)" : " IS NULL)"),
                List.copyOf(params), SqlType.BOOLEAN);
    }

    /**
     * Ordered comparison — a null operand is false on both sides of the parity (the
     * evaluator returns false; SQL NULL is not-matched). Temporal types compare as the
     * canonical ISO text the platform stores, where lexicographic == chronological
     * (ADR-001) — no casts needed for ordering.
     */
    private Lowered ordered(String op, Lowered left, Lowered right) {
        requireSameType(left, right, op);
        String sqlOp = switch (op) {
            case "<" -> " < ";
            case "<=" -> " <= ";
            case ">" -> " > ";
            default -> " >= ";
        };
        return new Lowered("(" + left.sql() + sqlOp + right.sql() + ")",
                List.copyOf(params), SqlType.BOOLEAN);
    }

    /**
     * Date arithmetic per Annex A: {@code date - date} → integer days,
     * {@code date ± integer} → date, numerics stay numeric. Temporal operands lower
     * through {@code CAST(… AS date)}; {@code date + date} is not defined (as in the
     * evaluator).
     */
    private Lowered additive(String op, Lowered left, Lowered right) {
        boolean minus = op.equals("-");
        if (left.type() == SqlType.DATE && right.type() == SqlType.DATE) {
            if (!minus) {
                throw new ExpressionException("date + date is not defined (Annex A)");
            }
            return new Lowered("(CAST(" + left.sql() + " AS date) - CAST("
                    + right.sql() + " AS date))", List.copyOf(params), SqlType.NUMBER);
        }
        if (left.type() == SqlType.DATE && right.type() == SqlType.NUMBER) {
            String shift = "(CAST(" + left.sql() + " AS date) " + (minus ? "-" : "+")
                    + " CAST(" + right.sql() + " AS integer))";
            return new Lowered(shift, List.copyOf(params), SqlType.DATE);
        }
        requireNumber(left, op);
        requireNumber(right, op);
        return new Lowered("((" + left.sql() + ") " + (minus ? "-" : "+") + " ("
                + right.sql() + "))", List.copyOf(params), SqlType.NUMBER);
    }

    /** {@code in (…)} lowers to an OR-chain of equalities (the query DSL's shape). */
    private Lowered membership(Node.Binary binary) {
        if (!(binary.right() instanceof Node.ListLiteral options) || options.items().isEmpty()) {
            throw new ExpressionException("'in' requires a non-empty list operand");
        }
        Lowered left = lower(binary.left());
        List<String> equals = new ArrayList<>();
        for (Node option : options.items()) {
            Lowered lowered = lower(option);
            requireSameType(left, lowered, "in");
            equals.add(left.sql() + " = " + lowered.sql());
        }
        return new Lowered("(" + String.join(" OR ", equals) + ")",
                List.copyOf(params), SqlType.BOOLEAN);
    }

    private Lowered call(Node.Call call) {
        return switch (call.name()) {
            case "today" -> {
                requireNoArgs(call);
                yield bind(today.toString(), SqlType.DATE);
            }
            case "now" -> {
                requireNoArgs(call);
                yield bind(now.toString(), SqlType.DATETIME);
            }
            case "date" -> bind(oneStringArg(call), SqlType.DATE);
            case "datetime" -> bind(oneStringArg(call), SqlType.DATETIME);
            case "abs" -> {
                Lowered operand = oneArg(call, "abs");
                requireNumber(operand, "abs");
                yield new Lowered("abs(" + operand.sql() + ")", List.copyOf(params),
                        SqlType.NUMBER);
            }
            case "upper", "lower", "trim" -> {
                Lowered operand = oneArg(call, call.name());
                requireType(operand, SqlType.TEXT, call.name());
                String fn = call.name().equals("upper") ? "upper"
                        : call.name().equals("lower") ? "lower" : "btrim";
                yield new Lowered(fn + "(" + operand.sql() + ")", List.copyOf(params),
                        SqlType.TEXT);
            }
            case "length" -> {
                Lowered operand = oneArg(call, "length");
                requireType(operand, SqlType.TEXT, "length");
                yield new Lowered("length(" + operand.sql() + ")", List.copyOf(params),
                        SqlType.NUMBER);
            }
            case "contains", "startsWith" -> {
                if (call.args().size() != 2) {
                    throw new ExpressionException(
                            call.name() + "(a, b) takes two arguments");
                }
                Lowered haystack = lower(call.args().getFirst());
                Lowered needle = lower(call.args().get(1));
                requireType(haystack, SqlType.TEXT, call.name());
                requireType(needle, SqlType.TEXT, call.name());
                String pattern = call.name().equals("contains")
                        ? "'%' || (" + needle.sql() + ") || '%'"
                        : "(" + needle.sql() + ") || '%'";
                yield new Lowered("((" + haystack.sql() + ") LIKE " + pattern + ")",
                        List.copyOf(params), SqlType.BOOLEAN);
            }
            // Parity guards (class javadoc): these functions' SQL and JVM semantics
            // disagree, so they do not lower — authored expressions using them stay
            // JVM-only slots (validations, stored formulas).
            case "round" -> throw new ExpressionException(
                    "round() does not lower to SQL (rounding-mode parity: the evaluator "
                            + "is half-even, SQL half-up)");
            case "min", "max" -> throw new ExpressionException(
                    "min()/max() do not lower to SQL (NULL-handling parity)");
            case "size" -> throw new ExpressionException(
                    "size() does not lower to SQL (collections do not lower)");
            default -> throw new ExpressionException("function does not lower to SQL: "
                    + call.name() + "()");
        };
    }

    // --- helpers ---

    private Lowered bind(Object param, SqlType type) {
        params.add(param);
        return new Lowered("?", List.copyOf(params), type);
    }

    private static void requireNoArgs(Node.Call call) {
        if (!call.args().isEmpty()) {
            throw new ExpressionException(call.name() + "() takes no arguments");
        }
    }

    private Lowered oneArg(Node.Call call, String name) {
        if (call.args().size() != 1) {
            throw new ExpressionException(name + "() takes one argument");
        }
        return lower(call.args().getFirst());
    }

    /** {@code date(…)}/{@code datetime(…)} — a single literal string bound as text. */
    private String oneStringArg(Node.Call call) {
        if (call.args().size() != 1
                || !(call.args().getFirst() instanceof Node.Literal literal)
                || !(literal.value() instanceof String s)) {
            throw new ExpressionException(
                    call.name() + "() lowers only with a literal string argument");
        }
        return s;
    }

    private static void requireBoolean(Lowered lowered, String where) {
        if (lowered.type() != SqlType.BOOLEAN) {
            throw new ExpressionException(where + " requires a boolean operand");
        }
    }

    private static void requireNumber(Lowered lowered, String where) {
        if (lowered.type() != SqlType.NUMBER) {
            throw new ExpressionException(where + " requires a numeric operand");
        }
    }

    private static void requireType(Lowered lowered, SqlType type, String where) {
        if (lowered.type() != type) {
            throw new ExpressionException(where + " requires a " + type + " operand");
        }
    }

    private static void requireSameType(Lowered left, Lowered right, String where) {
        if (left.type() != right.type()) {
            throw new ExpressionException(where + " requires two operands of one type ("
                    + left.type() + " vs " + right.type() + ")");
        }
    }
}
