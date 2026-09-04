package com.novaforge.expression;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * expr/v1 — the single expression grammar behind validations, formulas, flow guards,
 * and UI bindings (PHASE-2 §7 / Annex A, ADR-008 #3).
 *
 * <p>Pure and deterministic given bindings + clock: evaluation has no side effects, and
 * the clock is injectable (server clock in production, the run's frozen clock in suites
 * — PHASE-3 §7). Compile-time reference resolution ({@link #compileCheck}) is part of
 * the save/publish compile-check; stored formula fields ban the clock functions.
 *
 * <pre>{@code
 * Expression e = Expression.parse("totalDebit == totalCredit && status != 'VOID'");
 * Object v = e.evaluate(Bindings.of(map), Clock.systemUTC());
 * }</pre>
 */
public final class Expression {

    public static final String VERSION = "expr/v1";

    /** Banker's rounding context per the money rule (ARCHITECTURE.md §4). */
    public static final MathContext MATH = new MathContext(34, RoundingMode.HALF_EVEN);

    private final String source;
    private final Node root;

    private Expression(String source, Node root) {
        this.source = source;
        this.root = root;
    }

    public static Expression parse(String source) {
        Parser parser = new Parser(new Lexer(source).lex());
        Node root = parser.parseExpression();
        if (!parser.atEnd()) {
            throw new ExpressionException("unexpected trailing input at position " + parser.position());
        }
        return new Expression(source, root);
    }

    public String source() {
        return source;
    }

    /** Evaluates against bindings + clock. BigDecimal semantics for all numerics. */
    public Object evaluate(Bindings bindings, Clock clock) {
        return new Evaluator(bindings, clock).eval(root);
    }

    /** All reference paths the expression reads (compile-check input). */
    public Set<String> references() {
        return new Collector().collect(root);
    }

    /** True when the expression calls a clock function (today/now). */
    public boolean usesClock() {
        return new ClockUse().scan(root);
    }

    /** The parsed tree — for host-side compilers over the same grammar (ExpressionSql). */
    public Node root() {
        return root;
    }

    /**
     * Compile check: every reference must resolve against the slot's declared bindings,
     * and clock functions are rejected where determinism demands it (stored formula
     * fields — PHASE-3 §3).
     */
    public void compileCheck(CompilePolicy policy) {
        if (!policy.allowClock() && usesClock()) {
            throw new ExpressionException("clock functions (today/now) are not allowed in this slot");
        }
        for (String reference : references()) {
            if (!policy.allows(reference)) {
                throw new ExpressionException(
                        "unresolved reference '" + reference + "' (slot bindings: " + policy.bindings() + ")");
            }
        }
    }

    /** Slot compile policy: declared bindings + whether the clock is admissible. */
    public record CompilePolicy(Set<String> bindings, boolean allowClock) {

        public boolean allows(String reference) {
            String head = reference.contains(".")
                    ? reference.substring(0, reference.indexOf('.'))
                    : reference;
            return bindings.contains(head);
        }

        /** Record context: field apiNames; validations/guards may read the clock. */
        public static CompilePolicy recordContext(java.util.Collection<String> fieldNames, boolean allowClock) {
            return new CompilePolicy(Set.copyOf(fieldNames), allowClock);
        }
    }

    /** The statically inferable value shapes of the Annex A lattice. */
    public enum ValueType {
        NUMERIC, TEXT, DATE, DATETIME, BOOLEAN, LIST, UNKNOWN;

        /** Corpus/wire name ("numeric", "text", …); unknown names stay UNKNOWN. */
        public static ValueType of(String name) {
            return switch (name == null ? "" : name.toLowerCase(java.util.Locale.ROOT)) {
                case "numeric" -> NUMERIC;
                case "text" -> TEXT;
                case "date" -> DATE;
                case "datetime" -> DATETIME;
                case "boolean" -> BOOLEAN;
                case "list" -> LIST;
                default -> UNKNOWN;
            };
        }
    }

    /**
     * Static arithmetic/logical guard (PHASE-3 §2, the compile-check's type-aware
     * leg): rejects the shapes Annex A forbids — {@code +}/{@code -} over statically
     * text/boolean/list operands, {@code date + date}, {@code *}/{@code /} over any
     * statically non-numeric operand, logical operators over statically non-boolean
     * predicates, unknown methods, and wrong function arities — at save/publish,
     * where the offending expression names itself, instead of at evaluation where
     * the same defect used to surface as an opaque 500. Deliberately fail-open:
     * bindings whose type the host cannot state statically (UNKNOWN) pass — the
     * evaluator stays the authority where types are not known.
     */
    public void arithmeticCheck(java.util.function.Function<String, ValueType> bindingTypes) {
        new ArithmeticGuard(bindingTypes).infer(root);
    }

    // --- bindings ---

    /** Host-supplied values: field apiNames in record contexts + slot-specific names. */
    public interface Bindings {
        Object resolve(String path);

        static Bindings of(java.util.Map<String, ?> values) {
            return path -> {
                Object current = values;
                for (String segment : path.split("\\.")) {
                    if (current instanceof java.util.Map<?, ?> map && map.containsKey(segment)) {
                        current = map.get(segment);
                    } else {
                        return null;
                    }
                }
                return current;
            };
        }
    }

    // --- nodes ---

    /**
     * The parsed tree. Public (and walkable by hosts) so lowering pipelines can compile
     * an authored expression into another execution surface — the Phase 5 SQL lowering
     * for report buckets and sharing criteria is the first (ExpressionSql). The permit
     * set stays closed: hosts consume, never extend.
     */
    public sealed interface Node permits Node.Literal, Node.Reference, Node.Unary, Node.Binary,
            Node.Call, Node.ListLiteral, Node.Method {
        record Literal(Object value) implements Node {
        }

        record Reference(String path) implements Node {
        }

        record Unary(String op, Node operand) implements Node {
        }

        record Binary(String op, Node left, Node right) implements Node {
        }

        record Call(String name, List<Node> args) implements Node {
        }

        record ListLiteral(List<Node> items) implements Node {
        }

        record Method(Node target, String name, List<Node> args) implements Node {
        }
    }

    // --- AST walkers ---

    /**
     * The static shape inferencer behind {@link #arithmeticCheck} — the evaluator's
     * vocabulary walked for types instead of values, mirroring its messages.
     */
    private static final class ArithmeticGuard {

        private final java.util.function.Function<String, ValueType> bindingTypes;

        ArithmeticGuard(java.util.function.Function<String, ValueType> bindingTypes) {
            this.bindingTypes = bindingTypes;
        }

        ValueType infer(Node node) {
            return switch (node) {
                case Node.Literal literal -> {
                    Object value = literal.value();
                    if (value == null) {
                        yield ValueType.UNKNOWN;   // the null literal — only comparisons consume it
                    }
                    yield switch (value) {
                        case BigDecimal ignored -> ValueType.NUMERIC;
                        case String ignored -> ValueType.TEXT;
                        case Boolean ignored -> ValueType.BOOLEAN;
                        default -> ValueType.UNKNOWN;
                    };
                }
                case Node.Reference reference -> {
                    ValueType type = bindingTypes.apply(reference.path());
                    yield type == null ? ValueType.UNKNOWN : type;
                }
                case Node.ListLiteral list -> {
                    list.items().forEach(this::infer);
                    yield ValueType.LIST;
                }
                case Node.Unary unary -> unary(unary);
                case Node.Binary binary -> binary(binary);
                case Node.Call call -> call(call);
                case Node.Method method -> method(method);
            };
        }

        private ValueType unary(Node.Unary unary) {
            ValueType operand = infer(unary.operand());
            if (unary.op().equals("!")) {
                requireBoolean(operand, "logical operators require boolean predicates");
                return ValueType.BOOLEAN;
            }
            // unary -
            if (operand != ValueType.UNKNOWN && operand != ValueType.NUMERIC) {
                throw new ExpressionException("unary - requires a numeric operand"
                        + describe(operand));
            }
            return ValueType.NUMERIC;
        }

        private ValueType binary(Node.Binary binary) {
            ValueType left = infer(binary.left());
            ValueType right = infer(binary.right());
            return switch (binary.op()) {
                case "&&", "||" -> {
                    requireBoolean(left, "logical operators require boolean predicates");
                    requireBoolean(right, "logical operators require boolean predicates");
                    yield ValueType.BOOLEAN;
                }
                case "==", "!=", "<", "<=", ">", ">=", "in" -> ValueType.BOOLEAN;
                case "+", "-" -> additive(binary.op(), left, right);
                case "*", "/" -> {
                    requireNumeric(left, "multiplication/division requires numeric operands");
                    requireNumeric(right, "multiplication/division requires numeric operands");
                    yield ValueType.NUMERIC;
                }
                default -> throw new ExpressionException("unknown binary operator " + binary.op());
            };
        }

        /**
         * Annex A arithmetic, mirroring the evaluator exactly: numeric ± numeric;
         * date ± integer (the date on the left — the runtime's own shape); date − date
         * → days. Everything statically known outside that lattice rejects; UNKNOWN
         * operands stay fail-open (the evaluator remains their authority).
         */
        private ValueType additive(String op, ValueType left, ValueType right) {
            if (left == ValueType.DATE && right == ValueType.DATE) {
                if (op.equals("+")) {
                    throw new ExpressionException("date + date is not defined (Annex A)");
                }
                return ValueType.NUMERIC;   // date − date is the day count
            }
            if (left == ValueType.DATE && right == ValueType.NUMERIC) {
                return ValueType.DATE;
            }
            if (left == ValueType.NUMERIC && right == ValueType.NUMERIC) {
                return ValueType.NUMERIC;
            }
            if (left == ValueType.NUMERIC && right == ValueType.DATE) {
                throw new ExpressionException("arithmetic requires numeric or date operands "
                        + "(date arithmetic takes the date on the left, Annex A)");
            }
            if (nonArithmetic(left)) {
                throw new ExpressionException("arithmetic requires numeric or date operands"
                        + describe(left));
            }
            if (nonArithmetic(right)) {
                throw new ExpressionException("arithmetic requires numeric or date operands"
                        + describe(right));
            }
            return ValueType.UNKNOWN;   // an untyped slot stays evaluation's business
        }

        /** Known shapes the arithmetic lattice never admits. */
        private static boolean nonArithmetic(ValueType type) {
            return type != ValueType.UNKNOWN && type != ValueType.NUMERIC && type != ValueType.DATE;
        }

        private ValueType call(Node.Call call) {
            return switch (call.name()) {
                case "today" -> arity(call, 0, ValueType.DATE);
                case "now" -> arity(call, 0, ValueType.DATETIME);
                case "date" -> arity(call, 1, ValueType.DATE);
                case "datetime" -> arity(call, 1, ValueType.DATETIME);
                case "size" -> arity(call, 1, ValueType.NUMERIC);
                case "abs" -> arity(call, 1, ValueType.NUMERIC);
                case "length" -> arity(call, 1, ValueType.NUMERIC);
                case "round" -> arity(call, 2, ValueType.NUMERIC);
                case "min" -> arity(call, 2, ValueType.NUMERIC);
                case "max" -> arity(call, 2, ValueType.NUMERIC);
                case "upper" -> arity(call, 1, ValueType.TEXT);
                case "lower" -> arity(call, 1, ValueType.TEXT);
                case "trim" -> arity(call, 1, ValueType.TEXT);
                case "contains" -> arity(call, 2, ValueType.BOOLEAN);
                case "startsWith" -> arity(call, 2, ValueType.BOOLEAN);
                default -> throw new ExpressionException("unknown function: " + call.name());
            };
        }

        private ValueType arity(Node.Call call, int args, ValueType returns) {
            if (call.args().size() != args) {
                throw new ExpressionException(call.name() + "() takes "
                        + (args == 1 ? "one" : String.valueOf(args)) + " argument"
                        + (args == 1 ? "" : "s"));
            }
            call.args().forEach(this::infer);
            return returns;
        }

        private ValueType method(Node.Method method) {
            infer(method.target());
            method.args().forEach(this::infer);
            if (method.name().equals("size") && method.args().isEmpty()) {
                return ValueType.NUMERIC;
            }
            throw new ExpressionException("unknown method: ." + method.name() + "()");
        }

        private void requireBoolean(ValueType type, String message) {
            if (type != ValueType.UNKNOWN && type != ValueType.BOOLEAN) {
                throw new ExpressionException(message + describe(type));
            }
        }

        private void requireNumeric(ValueType type, String message) {
            if (type != ValueType.UNKNOWN && type != ValueType.NUMERIC) {
                throw new ExpressionException(message + describe(type));
            }
        }

        private static String describe(ValueType type) {
            return " (statically " + type.name().toLowerCase(java.util.Locale.ROOT) + ")";
        }
    }

    private static final class Collector {
        private final Set<String> references = new java.util.LinkedHashSet<>();

        Set<String> collect(Node node) {
            walk(node);
            return references;
        }

        private void walk(Node node) {
            switch (node) {
                case Node.Reference ref -> references.add(ref.path());
                case Node.Unary unary -> walk(unary.operand());
                case Node.Binary binary -> {
                    walk(binary.left());
                    walk(binary.right());
                }
                case Node.Call call -> call.args().forEach(this::walk);
                case Node.ListLiteral list -> list.items().forEach(this::walk);
                case Node.Method method -> {
                    walk(method.target());
                    method.args().forEach(this::walk);
                }
                case Node.Literal ignored -> { }
            }
        }
    }

    private static final class ClockUse {
        private boolean uses;

        boolean scan(Node node) {
            walk(node);
            return uses;
        }

        private void walk(Node node) {
            switch (node) {
                case Node.Call call -> {
                    if (call.name().equals("today") || call.name().equals("now")) {
                        uses = true;
                    }
                    call.args().forEach(this::walk);
                }
                case Node.Unary unary -> walk(unary.operand());
                case Node.Binary binary -> {
                    walk(binary.left());
                    walk(binary.right());
                }
                case Node.ListLiteral list -> list.items().forEach(this::walk);
                case Node.Method method -> {
                    walk(method.target());
                    method.args().forEach(this::walk);
                }
                default -> { }
            }
        }
    }

    // --- evaluator ---

    private static final class Evaluator {
        private final Bindings bindings;
        private final Clock clock;

        Evaluator(Bindings bindings, Clock clock) {
            this.bindings = bindings;
            this.clock = clock;
        }

        Object eval(Node node) {
            return switch (node) {
                case Node.Literal literal -> literal.value();
                case Node.Reference reference -> bindings.resolve(reference.path());
                case Node.ListLiteral list -> list.items().stream().map(this::eval).toList();
                case Node.Unary unary -> unary(unary);
                case Node.Binary binary -> binary(binary);
                case Node.Call call -> call(call);
                case Node.Method method -> method(method);
            };
        }

        private Object unary(Node.Unary unary) {
            Object value = eval(unary.operand());
            return switch (unary.op()) {
                case "!" -> truth(value) ? Boolean.FALSE : Boolean.TRUE;
                case "-" -> {
                    if (value instanceof BigDecimal decimal) {
                        yield decimal.negate(MATH);
                    }
                    throw new ExpressionException("unary - requires a numeric operand");
                }
                default -> throw new ExpressionException("unknown unary operator " + unary.op());
            };
        }

        private Object binary(Node.Binary binary) {
            String op = binary.op();
            if (op.equals("&&") || op.equals("||")) {
                boolean left = truth(eval(binary.left()));
                if (op.equals("&&") && !left) {
                    return Boolean.FALSE;
                }
                if (op.equals("||") && left) {
                    return Boolean.TRUE;
                }
                return truth(eval(binary.right())) ? Boolean.TRUE : Boolean.FALSE;
            }
            Object left = eval(binary.left());
            Object right = eval(binary.right());
            return switch (op) {
                case "==" -> equal(left, right);
                case "!=" -> !equal(left, right);
                case "<", "<=", ">", ">=" -> compare(op, left, right);
                case "in" -> membership(left, right);
                case "+" -> additive(left, right, true);
                case "-" -> additive(left, right, false);
                case "*" -> pair(left, right)[0].multiply(pair(left, right)[1], MATH);
                case "/" -> {
                    BigDecimal[] operands = pair(left, right);
                    if (operands[1].signum() == 0) {
                        throw new ExpressionException("division by zero");
                    }
                    yield operands[0].divide(operands[1], MATH);
                }
                default -> throw new ExpressionException("unknown operator " + op);
            };
        }

        private Boolean membership(Object left, Object right) {
            if (!(right instanceof List<?> options)) {
                throw new ExpressionException("'in' requires a list operand");
            }
            for (Object option : options) {
                if (equal(left, option)) {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        }

        /** Null-aware equality (Annex A): ==/!= compare against null. */
        private boolean equal(Object left, Object right) {
            if (left == null || right == null) {
                return left == null && right == null;
            }
            if (left instanceof BigDecimal l && right instanceof BigDecimal r) {
                return l.compareTo(r) == 0;
            }
            return left.equals(right);
        }

        /** Ordered comparisons with a null operand are false (Annex A). */
        private Boolean compare(String op, Object left, Object right) {
            if (left == null || right == null) {
                return Boolean.FALSE;
            }
            int ordering;
            if (left instanceof BigDecimal l && right instanceof BigDecimal r) {
                ordering = l.compareTo(r);
            } else if (left instanceof String l && right instanceof String r) {
                ordering = l.compareTo(r);
            } else if (left instanceof LocalDate l && right instanceof LocalDate r) {
                ordering = l.compareTo(r);
            } else if (left instanceof Instant l && right instanceof Instant r) {
                ordering = l.compareTo(r);
            } else if (left instanceof Boolean l && right instanceof Boolean r) {
                ordering = Boolean.compare(l, r);
            } else {
                throw new ExpressionException("ordered comparison requires two operands of one comparable type");
            }
            return switch (op) {
                case "<" -> ordering < 0;
                case "<=" -> ordering <= 0;
                case ">" -> ordering > 0;
                default -> ordering >= 0;
            };
        }

        /**
         * Arithmetic: numerics in BigDecimal semantics; date arithmetic per Annex A —
         * {@code date - date} → integer days, {@code date ± integer} → date.
         */
        private Object additive(Object left, Object right, boolean plus) {
            if (left instanceof LocalDate date && right instanceof LocalDate other) {
                if (plus) {
                    throw new ExpressionException("date + date is not defined (Annex A)");
                }
                return BigDecimal.valueOf(java.time.temporal.ChronoUnit.DAYS.between(other, date));
            }
            if (left instanceof LocalDate date) {
                long days = requireDays(right);
                try {
                    return plus ? date.plusDays(days) : date.minusDays(days);
                } catch (ArithmeticException | java.time.DateTimeException outOfRange) {
                    // In-long-range but beyond the calendar (date + 9e18): plusDays'
                    // raw overflow exception would slip past ExpressionException's
                    // 400 rendering and 500 the write path evaluating the stored
                    // formula — the same authoring-feedback class as round(x, 1.5).
                    throw new ExpressionException("date " + (plus ? "+ " : "- ")
                            + decimal(days) + " is outside the supported calendar range");
                }
            }
            if (left instanceof BigDecimal l && right instanceof BigDecimal r) {
                return plus ? l.add(r, MATH) : l.subtract(r, MATH);
            }
            throw new ExpressionException("arithmetic requires numeric or date operands");
        }

        private long requireDays(Object value) {
            if (value instanceof BigDecimal decimal && decimal.stripTrailingZeros().scale() <= 0) {
                try {
                    return decimal.longValueExact();
                } catch (ArithmeticException outOfRange) {
                    // Integral but beyond long range (date + 1e30): the raw
                    // ArithmeticException would 500 the write path — reject as
                    // authoring feedback instead.
                    throw new ExpressionException("date arithmetic requires a day count "
                            + "within the supported calendar range: " + decimal.toPlainString());
                }
            }
            throw new ExpressionException("date arithmetic requires an integer day count");
        }

        private String decimal(long value) {
            return BigDecimal.valueOf(value).toPlainString();
        }

        private BigDecimal[] pair(Object left, Object right) {
            if (left instanceof BigDecimal l && right instanceof BigDecimal r) {
                return new BigDecimal[] {l, r};
            }
            throw new ExpressionException("arithmetic requires numeric operands");
        }

        /** Truthiness: a null predicate is false (Annex A). */
        private boolean truth(Object value) {
            if (value == null) {
                return false;
            }
            if (value instanceof Boolean b) {
                return b;
            }
            throw new ExpressionException("logical operators require boolean predicates");
        }

        private Object call(Node.Call call) {
            return switch (call.name()) {
                case "today" -> LocalDate.now(clock);
                case "now" -> Instant.now(clock);
                case "date" -> dateLiteral(call);
                case "datetime" -> datetimeLiteral(call);
                case "size" -> size(eval(single(call, "size")));
                case "abs" -> {
                    Object value = eval(single(call, "abs"));
                    yield value == null ? null : numeric(value).abs(MATH);
                }
                case "round" -> round(call);
                case "min" -> minMax(call, true);
                case "max" -> minMax(call, false);
                // Null-propagation for single-argument shaping functions: a stored
                // formula stays total when its input is absent.
                case "upper" -> {
                    Object value = eval(single(call, "upper"));
                    yield value == null ? null : string(value).toUpperCase();
                }
                case "lower" -> {
                    Object value = eval(single(call, "lower"));
                    yield value == null ? null : string(value).toLowerCase();
                }
                case "trim" -> {
                    Object value = eval(single(call, "trim"));
                    yield value == null ? null : string(value).trim();
                }
                case "length" -> {
                    Object value = eval(single(call, "length"));
                    yield value == null ? null : BigDecimal.valueOf(string(value).length());
                }
                case "contains" -> {
                    Object[] pair = two(call, "contains");
                    yield string(pair[0]).contains(string(pair[1]));
                }
                case "startsWith" -> {
                    Object[] pair = two(call, "startsWith");
                    yield string(pair[0]).startsWith(string(pair[1]));
                }
                default -> throw new ExpressionException("unknown function: " + call.name());
            };
        }

        private Object method(Node.Method method) {
            Object target = eval(method.target());
            if (method.name().equals("size") && method.args().isEmpty()) {
                return size(target);
            }
            throw new ExpressionException("unknown method: ." + method.name() + "()");
        }

        private Object size(Object value) {
            if (value instanceof List<?> list) {
                return BigDecimal.valueOf(list.size());
            }
            if (value instanceof String string) {
                return BigDecimal.valueOf(string.length());
            }
            throw new ExpressionException("size() requires a collection");
        }

        private Object round(Node.Call call) {
            if (call.args().size() != 2) {
                throw new ExpressionException("round(x, scale) takes two arguments");
            }
            BigDecimal value = numeric(eval(call.args().getFirst()));
            BigDecimal scale = numeric(eval(call.args().get(1)));
            // A fractional scale (round(x, 1.5)) is an authoring defect, and it used to
            // escape as BigDecimal.intValueExact()'s raw ArithmeticException — a bare
            // 500 on the first write that evaluated the stored formula (the failure
            // mode PHASE-3 §2's 400-not-500 rule pins). ExpressionException renders
            // 400 VALIDATION_FAILED at every door.
            if (scale.stripTrailingZeros().scale() > 0) {
                throw new ExpressionException("round(x, scale) takes an integer scale");
            }
            try {
                return value.setScale(scale.intValueExact(), RoundingMode.HALF_EVEN);
            } catch (ArithmeticException scaleOutOfRange) {
                // integral but beyond int range — the same authoring feedback
                throw new ExpressionException(
                        "round(x, scale) scale is out of range: " + scale.toPlainString(),
                        scaleOutOfRange);
            }
        }

        private Object minMax(Node.Call call, boolean min) {
            if (call.args().size() != 2) {
                throw new ExpressionException((min ? "min" : "max") + "(a, b) takes two arguments");
            }
            BigDecimal a = numeric(eval(call.args().getFirst()));
            BigDecimal b = numeric(eval(call.args().get(1)));
            return (min ? a.min(b) : a.max(b));
        }

        private LocalDate dateLiteral(Node.Call call) {
            return LocalDate.parse(string(eval(single(call, "date"))));
        }

        private Instant datetimeLiteral(Node.Call call) {
            return Instant.parse(string(eval(single(call, "datetime"))));
        }

        private Node single(Node.Call call, String name) {
            if (call.args().size() != 1) {
                throw new ExpressionException(name + "() takes one argument");
            }
            return call.args().getFirst();
        }

        private Object[] two(Node.Call call, String name) {
            if (call.args().size() != 2) {
                throw new ExpressionException(name + "() takes two arguments");
            }
            return new Object[] {eval(call.args().getFirst()), eval(call.args().get(1))};
        }

        private BigDecimal numeric(Object value) {
            if (value instanceof BigDecimal decimal) {
                return decimal;
            }
            throw new ExpressionException("expected a numeric value");
        }

        private String string(Object value) {
            if (value instanceof String s) {
                return s;
            }
            throw new ExpressionException("expected a string value");
        }
    }
}
