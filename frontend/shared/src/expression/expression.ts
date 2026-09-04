import { Decimal } from "./decimal.ts";
import { parse, type Node } from "./parser.ts";
import {
    addDays,
    daysBetween,
    type DateValue,
    ExpressionError,
    type ExpressionValue,
    instantParts,
    type InstantValue,
    isDate,
    isInstant,
    parseDate,
    parseInstant,
    utcDateOfInstant,
} from "./values.ts";

/**
 * expr/v1 — the TS twin of the JVM engine (PHASE-2 §7 / Annex A, ADR-008 #3).
 * One grammar for validations, formulas, flow guards, and UI bindings; the shared
 * conformance corpus (`platform/libs/expression-dsl/.../expr-v1-corpus.json`) runs
 * against both engines so the dialects never drift.
 *
 * Browser evaluation is UX sugar, never trusted as enforcement — the Data Runtime
 * evaluates server-side on the write path (PHASE-3 §3).
 */

export const EXPRESSION_VERSION = "expr/v1";

export type Bindings = Record<string, unknown>;

/** Injectable clock: ISO instant string (server clock in production, frozen in suites). */
export type Clock = string;

/** Slot compile policy: declared bindings + whether the clock is admissible. */
export interface CompilePolicy {
    bindings: ReadonlySet<string> | readonly string[];
    allowClock: boolean;
}

/** The statically inferable value shapes of the Annex A lattice. */
export type ValueType = "numeric" | "text" | "date" | "datetime" | "boolean" | "list" | "unknown";

/** Binding shapes for {@link Expression.arithmeticCheck}: field/path → static shape. */
export type BindingTypes = Readonly<Record<string, ValueType>>;

export function recordContext(fieldNames: readonly string[], allowClock: boolean): CompilePolicy {
    return { bindings: fieldNames, allowClock };
}

export class Expression {
    private constructor(
        readonly source: string,
        private readonly root: Node,
    ) {}

    static parse(source: string): Expression {
        return new Expression(source, parse(source));
    }

    /** Evaluates against bindings + clock (exact decimal semantics). */
    evaluate(bindings: Bindings, clock: Clock): ExpressionValue {
        return new Evaluator(bindings, clock).eval(this.root);
    }

    /** All reference paths the expression reads (compile-check input). */
    references(): string[] {
        const refs = new Set<string>();
        const walk = (node: Node): void => {
            switch (node.kind) {
                case "reference":
                    refs.add(node.path);
                    break;
                case "unary":
                    walk(node.operand);
                    break;
                case "binary":
                    walk(node.left);
                    walk(node.right);
                    break;
                case "call":
                    node.args.forEach(walk);
                    break;
                case "list":
                    node.items.forEach(walk);
                    break;
                case "method":
                    walk(node.target);
                    node.args.forEach(walk);
                    break;
                case "literal":
                    break;
            }
        };
        walk(this.root);
        return [...refs];
    }

    /** True when the expression calls a clock function (today/now). */
    usesClock(): boolean {
        let uses = false;
        const walk = (node: Node): void => {
            switch (node.kind) {
                case "call":
                    if (node.name === "today" || node.name === "now") uses = true;
                    node.args.forEach(walk);
                    break;
                case "unary":
                    walk(node.operand);
                    break;
                case "binary":
                    walk(node.left);
                    walk(node.right);
                    break;
                case "list":
                    node.items.forEach(walk);
                    break;
                case "method":
                    walk(node.target);
                    node.args.forEach(walk);
                    break;
                default:
                    break;
            }
        };
        walk(this.root);
        return uses;
    }

    /**
     * Compile check: every reference must resolve against the slot's declared
     * bindings, and clock functions reject where determinism demands it.
     */
    compileCheck(policy: CompilePolicy): void {
        if (!policy.allowClock && this.usesClock()) {
            throw new ExpressionError("clock functions (today/now) are not allowed in this slot");
        }
        const allowed = new Set(policy.bindings);
        for (const reference of this.references()) {
            const head = reference.split(".")[0]!;
            if (!allowed.has(head)) {
                throw new ExpressionError(
                    `unresolved reference '${reference}' (slot bindings: ${[...allowed].join(", ")})`,
                );
            }
        }
    }

    /**
     * Static arithmetic/logical guard (PHASE-3 §2, the compile-check's type-aware
     * leg — the JVM engine's twin): rejects the shapes Annex A forbids — `+`/`-`
     * over statically text/boolean/list operands, `date + date`, `*`//` over any
     * statically non-numeric operand, logical operators over statically non-boolean
     * predicates, unknown methods, and wrong function arities — at authoring time.
     * Deliberately fail-open: bindings whose shape the host cannot state statically
     * ("unknown") pass; the server-side evaluator stays the authority.
     */
    arithmeticCheck(bindingTypes: BindingTypes): void {
        new ArithmeticGuard(bindingTypes).infer(this.root);
    }
}

// --- static shape inference (the arithmeticCheck twin) ---

class ArithmeticGuard {
    constructor(private readonly bindingTypes: BindingTypes) {}

    infer(node: Node): ValueType {
        switch (node.kind) {
            case "literal": {
                const literal = node.value;
                if (literal instanceof Decimal) return "numeric";
                if (typeof literal === "string") return "text";
                if (typeof literal === "boolean") return "boolean";
                return "unknown";   // null propagates (functions stay total)
            }
            case "reference":
                return this.bindingTypes[node.path] ?? "unknown";
            case "list":
                node.items.forEach((item) => this.infer(item));
                return "list";
            case "unary":
                return this.unary(node);
            case "binary":
                return this.binary(node);
            case "call":
                return this.call(node);
            case "method":
                return this.method(node);
        }
    }

    private unary(node: Extract<Node, { kind: "unary" }>): ValueType {
        const operand = this.infer(node.operand);
        if (node.op === "!") {
            this.requireBoolean(operand, "logical operators require boolean predicates");
            return "boolean";
        }
        if (operand !== "unknown" && operand !== "numeric") {
            throw new ExpressionError(`unary - requires a numeric operand${this.describe(operand)}`);
        }
        return "numeric";
    }

    private binary(node: Extract<Node, { kind: "binary" }>): ValueType {
        const left = this.infer(node.left);
        const right = this.infer(node.right);
        switch (node.op) {
            case "&&":
            case "||": {
                this.requireBoolean(left, "logical operators require boolean predicates");
                this.requireBoolean(right, "logical operators require boolean predicates");
                return "boolean";
            }
            case "==":
            case "!=":
            case "<":
            case "<=":
            case ">":
            case ">=":
            case "in":
                return "boolean";
            case "+":
            case "-":
                return this.additive(node.op, left, right);
            case "*":
            case "/": {
                this.requireNumeric(left, "multiplication/division requires numeric operands");
                this.requireNumeric(right, "multiplication/division requires numeric operands");
                return "numeric";
            }
            default:
                throw new ExpressionError(`unknown binary operator ${node.op}`);
        }
    }

    /** Annex A arithmetic, mirroring the evaluator: numeric ± numeric; date ± integer
     *  (date on the left); date − date → days; everything statically known outside
     *  that lattice rejects; unknown shapes stay fail-open. */
    private additive(op: "+" | "-", left: ValueType, right: ValueType): ValueType {
        if (left === "date" && right === "date") {
            if (op === "+") throw new ExpressionError("date + date is not defined (Annex A)");
            return "numeric";   // date − date is the day count
        }
        if (left === "date" && right === "numeric") return "date";
        if (left === "numeric" && right === "numeric") return "numeric";
        if (left === "numeric" && right === "date") {
            throw new ExpressionError(
                "arithmetic requires numeric or date operands (date arithmetic takes the date on the left, Annex A)",
            );
        }
        if (this.nonArithmetic(left)) {
            throw new ExpressionError(
                `arithmetic requires numeric or date operands${this.describe(left)}`,
            );
        }
        if (this.nonArithmetic(right)) {
            throw new ExpressionError(
                `arithmetic requires numeric or date operands${this.describe(right)}`,
            );
        }
        return "unknown";   // an untyped slot stays evaluation's business
    }

    private call(node: Extract<Node, { kind: "call" }>): ValueType {
        const shapes: Record<string, { args: number; returns: ValueType }> = {
            today: { args: 0, returns: "date" },
            now: { args: 0, returns: "datetime" },
            date: { args: 1, returns: "date" },
            datetime: { args: 1, returns: "datetime" },
            size: { args: 1, returns: "numeric" },
            abs: { args: 1, returns: "numeric" },
            length: { args: 1, returns: "numeric" },
            round: { args: 2, returns: "numeric" },
            min: { args: 2, returns: "numeric" },
            max: { args: 2, returns: "numeric" },
            upper: { args: 1, returns: "text" },
            lower: { args: 1, returns: "text" },
            trim: { args: 1, returns: "text" },
            contains: { args: 2, returns: "boolean" },
            startsWith: { args: 2, returns: "boolean" },
        };
        const shape = shapes[node.name];
        if (!shape) throw new ExpressionError(`unknown function: ${node.name}`);
        if (node.args.length !== shape.args) {
            const count = shape.args === 1 ? "one" : String(shape.args);
            const plural = shape.args === 1 ? "" : "s";
            throw new ExpressionError(`${node.name}() takes ${count} argument${plural}`);
        }
        node.args.forEach((arg) => this.infer(arg));
        return shape.returns;
    }

    private method(node: Extract<Node, { kind: "method" }>): ValueType {
        this.infer(node.target);
        node.args.forEach((arg) => this.infer(arg));
        if (node.name === "size" && node.args.length === 0) return "numeric";
        throw new ExpressionError(`unknown method: .${node.name}()`);
    }

    private requireBoolean(type: ValueType, message: string): void {
        if (type !== "unknown" && type !== "boolean") {
            throw new ExpressionError(`${message}${this.describe(type)}`);
        }
    }

    private requireNumeric(type: ValueType, message: string): void {
        if (type !== "unknown" && type !== "numeric") {
            throw new ExpressionError(`${message}${this.describe(type)}`);
        }
    }

    private nonArithmetic(type: ValueType): boolean {
        return type !== "unknown" && type !== "numeric" && type !== "date";
    }

    private describe(type: ValueType): string {
        return ` (statically ${type})`;
    }
}

// --- evaluation ---

class Evaluator {
    constructor(
        private readonly bindings: Bindings,
        private readonly clock: Clock,
    ) {}

    eval(node: Node): ExpressionValue {
        switch (node.kind) {
            case "literal": {
                const literal = node.value;
                if (literal == null) return null;
                if (typeof literal === "string" || typeof literal === "boolean" || literal instanceof Decimal) {
                    return literal;
                }
                return null;
            }
            case "reference":
                return this.resolve(node.path);
            case "list":
                return node.items.map((item) => this.eval(item));
            case "unary":
                return this.unary(node);
            case "binary":
                return this.binary(node);
            case "call":
                return this.call(node);
            case "method":
                return this.method(node);
        }
    }

    private resolve(path: string): ExpressionValue {
        const value = this.resolveRaw(path);
        if (value === undefined) return null;
        return value as ExpressionValue;
    }

    private resolveRaw(path: string): unknown {
        let current: unknown = this.bindings;
        for (const segment of path.split(".")) {
            if (typeof current === "object" && current !== null && !Array.isArray(current) && segment in current) {
                current = (current as Record<string, unknown>)[segment] as ExpressionValue;
            } else {
                return null;
            }
        }
        return current;
    }

    private unary(node: Extract<Node, { kind: "unary" }>): ExpressionValue {
        const value = this.eval(node.operand);
        if (node.op === "!") {
            return !this.truth(value);
        }
        if (value instanceof Decimal) {
            return value.negate();
        }
        throw new ExpressionError("unary - requires a numeric operand");
    }

    private binary(node: Extract<Node, { kind: "binary" }>): ExpressionValue {
        const op = node.op;
        if (op === "&&" || op === "||") {
            const left = this.truth(this.eval(node.left));
            if (op === "&&" && !left) return false;
            if (op === "||" && left) return true;
            return this.truth(this.eval(node.right));
        }
        const left = this.eval(node.left);
        const right = this.eval(node.right);
        switch (op) {
            case "==":
                return this.equal(left, right);
            case "!=":
                return !this.equal(left, right);
            case "<":
            case "<=":
            case ">":
            case ">=":
                return this.compare(op, left, right);
            case "in":
                return this.membership(left, right);
            case "+":
                return this.additive(left, right, true);
            case "-":
                return this.additive(left, right, false);
            case "*": {
                const [a, b] = this.numericPair(left, right);
                return a.multiply(b);
            }
            case "/": {
                const [a, b] = this.numericPair(left, right);
                try {
                    return a.divide(b);
                } catch (error) {
                    if (error instanceof Error && error.message === "division by zero") {
                        throw new ExpressionError("division by zero");
                    }
                    throw error;
                }
            }
            default:
                throw new ExpressionError(`unknown operator ${op}`);
        }
    }

    private membership(left: ExpressionValue, right: ExpressionValue): boolean {
        if (!Array.isArray(right)) {
            throw new ExpressionError("'in' requires a list operand");
        }
        return right.some((option) => this.equal(left, option));
    }

    /** Null-aware equality (Annex A): ==/!= compare against null. */
    private equal(left: unknown, right: unknown): boolean {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        if (left instanceof Decimal || right instanceof Decimal) {
            if (!(left instanceof Decimal) || !(right instanceof Decimal)) {
                return false;
            }
            return left.equals(right);
        }
        if (isDate(left) && isDate(right)) return left.$date === right.$date;
        if (isInstant(left) && isInstant(right)) {
            const a = instantParts(left.$instant);
            const b = instantParts(right.$instant);
            return a.seconds === b.seconds && a.nanos === b.nanos;
        }
        return left === right;
    }

    /** Ordered comparisons with a null operand are false (Annex A). */
    private compare(op: string, left: unknown, right: unknown): boolean {
        if (left == null || right == null) {
            return false;
        }
        let ordering: number;
        if (left instanceof Decimal && right instanceof Decimal) {
            ordering = left.compareTo(right);
        } else if (typeof left === "string" && typeof right === "string") {
            ordering = left < right ? -1 : left > right ? 1 : 0;
        } else if (isDate(left) && isDate(right)) {
            ordering = left.$date < right.$date ? -1 : left.$date > right.$date ? 1 : 0;
        } else if (isInstant(left) && isInstant(right)) {
            const a = instantParts(left.$instant);
            const b = instantParts(right.$instant);
            ordering = a.seconds - b.seconds || Math.sign(a.nanos - b.nanos);
        } else if (typeof left === "boolean" && typeof right === "boolean") {
            ordering = Number(left) - Number(right);
        } else {
            throw new ExpressionError(
                "ordered comparison requires two operands of one comparable type",
            );
        }
        if (op === "<") return ordering < 0;
        if (op === "<=") return ordering <= 0;
        if (op === ">") return ordering > 0;
        return ordering >= 0;
    }

    /**
     * Arithmetic: numerics in exact decimals; date arithmetic per Annex A —
     * `date - date` → integer days, `date ± integer` → date.
     */
    private additive(left: ExpressionValue, right: ExpressionValue, plus: boolean): ExpressionValue {
        if (isDate(left) && isDate(right)) {
            if (plus) throw new ExpressionError("date + date is not defined (Annex A)");
            return daysBetween(right, left);
        }
        if (isDate(left)) {
            const days = this.requireDays(right);
            return addDays(left, plus ? days : -days);
        }
        if (left instanceof Decimal && right instanceof Decimal) {
            return plus ? left.add(right) : left.subtract(right);
        }
        throw new ExpressionError("arithmetic requires numeric or date operands");
    }

    private requireDays(value: ExpressionValue): number {
        if (value instanceof Decimal && value.isIntegral()) {
            const stripped = value.stripTrailingZeros();
            // The true magnitude: unscaled digits × 10^−scale. After the integral
            // gate the scale is ≤ 0, so this EXPANDS — reading Number(digits) alone
            // ignored negative scale, and division quotients carry it (1000000 / 0.5
            // → Decimal(200000n, −1) read as 200,000 where the value is 2,000,000:
            // a wrong date even in-calendar, where the JVM answers 7502-06-25).
            const magnitude = stripped.digits * 10n ** BigInt(-stripped.scale);
            // Exact bigint comparison against the safe bound — Number(magnitude)
            // would silently round past 2^53 and let a lossy count through.
            if (magnitude > BigInt(Number.MAX_SAFE_INTEGER)) {
                throw new ExpressionError(
                    "date arithmetic requires a day count within the supported calendar range",
                );
            }
            return Number(magnitude) * stripped.sign;
        }
        throw new ExpressionError("date arithmetic requires an integer day count");
    }

    private numericPair(left: ExpressionValue, right: ExpressionValue): [Decimal, Decimal] {
        if (left instanceof Decimal && right instanceof Decimal) {
            return [left, right];
        }
        throw new ExpressionError("arithmetic requires numeric operands");
    }

    /** Truthiness: a null predicate is false (Annex A). */
    private truth(value: unknown): boolean {
        if (value == null) return false;
        if (typeof value === "boolean") return value;
        throw new ExpressionError("logical operators require boolean predicates");
    }

    private single(node: Extract<Node, { kind: "call" }>, name: string): Node {
        if (node.args.length !== 1) {
            throw new ExpressionError(`${name}() takes one argument`);
        }
        return node.args[0]!;
    }

    private two(node: Extract<Node, { kind: "call" }>, name: string): [ExpressionValue, ExpressionValue] {
        if (node.args.length !== 2) {
            throw new ExpressionError(`${name}() takes two arguments`);
        }
        return [this.eval(node.args[0]!), this.eval(node.args[1]!)];
    }

    private numeric(value: ExpressionValue): Decimal {
        if (value instanceof Decimal) return value;
        throw new ExpressionError("expected a numeric value");
    }

    private string(value: ExpressionValue): string {
        if (typeof value === "string") return value;
        throw new ExpressionError("expected a string value");
    }

    private size(value: unknown): Decimal {
        if (Array.isArray(value)) return new Decimal(BigInt(value.length), 0);
        if (typeof value === "string") return new Decimal(BigInt(value.length), 0);
        throw new ExpressionError("size() requires a collection");
    }

    private call(node: Extract<Node, { kind: "call" }>): ExpressionValue {
        switch (node.name) {
            case "today":
                return utcDateOfInstant(this.clock);
            case "now":
                return { $instant: this.clock };
            case "date":
                return parseDate(this.string(this.eval(this.single(node, "date"))));
            case "datetime":
                return parseInstant(this.string(this.eval(this.single(node, "datetime"))));
            case "size":
                return this.size(this.eval(this.single(node, "size")));
            case "abs": {
                const value = this.eval(this.single(node, "abs"));
                return value == null ? null : this.numeric(value).abs();
            }
            case "round": {
                if (node.args.length !== 2) {
                    throw new ExpressionError("round(x, scale) takes two arguments");
                }
                const value = this.numeric(this.eval(node.args[0]!));
                const scale = this.numeric(this.eval(node.args[1]!));
                // Mirrors the JVM engine: a fractional scale (round(x, 1.5)) is an
                // authoring defect that rejects — the old Number(digits) read turned
                // 1.5 into scale 15 and silently returned a wrong value.
                if (!scale.isIntegral()) {
                    throw new ExpressionError("round(x, scale) takes an integer scale");
                }
                const stripped = scale.stripTrailingZeros();
                // The true magnitude — unscaled digits × 10^−scale (the integral gate
                // leaves scale ≤ 0, so this expands). The old Number(digits) read
                // ignored negative scale: a division quotient (1000000 / 0.000001 →
                // 1e12) read as its unscaled digits and silently rounded at scale
                // 1,000,000 where the JVM rejects.
                const magnitude = stripped.digits * 10n ** BigInt(-stripped.scale);
                // Mirrors the JVM engine's out-of-range arm: its read is
                // intValueExact(), so anything beyond the 32-bit int range rejects —
                // the old safe-integer gate admitted scales 1000× past it (and an
                // absurd integral scale drove setScale into building a 10^21-digit
                // bigint, hanging the evaluating tab).
                if (magnitude > 2147483647n) {
                    throw new ExpressionError("round(x, scale) scale is out of range");
                }
                const places = Number(magnitude) * stripped.sign;
                return value.setScale(places);
            }
            case "min":
            case "max": {
                if (node.args.length !== 2) {
                    throw new ExpressionError(`${node.name}(a, b) takes two arguments`);
                }
                const a = this.numeric(this.eval(node.args[0]!));
                const b = this.numeric(this.eval(node.args[1]!));
                return node.name === "min"
                    ? (a.compareTo(b) <= 0 ? a : b)
                    : (a.compareTo(b) >= 0 ? a : b);
            }
            // Null-propagation for single-argument shaping functions: a stored
            // formula stays total when its input is absent.
            case "upper": {
                const value = this.eval(this.single(node, "upper"));
                return value == null ? null : this.string(value).toUpperCase();
            }
            case "lower": {
                const value = this.eval(this.single(node, "lower"));
                return value == null ? null : this.string(value).toLowerCase();
            }
            case "trim": {
                const value = this.eval(this.single(node, "trim"));
                return value == null ? null : this.string(value).trim();
            }
            case "length": {
                const value = this.eval(this.single(node, "length"));
                return value == null ? null : new Decimal(BigInt(this.string(value).length), 0);
            }
            case "contains": {
                const [s, sub] = this.two(node, "contains");
                return this.string(s).includes(this.string(sub));
            }
            case "startsWith": {
                const [s, prefix] = this.two(node, "startsWith");
                return this.string(s).startsWith(this.string(prefix));
            }
            default:
                throw new ExpressionError(`unknown function: ${node.name}`);
        }
    }

    private method(node: Extract<Node, { kind: "method" }>): ExpressionValue {
        const target = this.eval(node.target);
        if (node.name === "size" && node.args.length === 0) {
            return this.size(target);
        }
        throw new ExpressionError(`unknown method: .${node.name}()`);
    }
}

// --- tagged-value helpers for hosts rendering results ---

export { Decimal, isDate, isInstant };
export type { DateValue, InstantValue };

/** Renders an evaluation result for display (decimals as canonical strings). */
export function display(value: ExpressionValue): string {
    if (value == null) return "";
    if (value instanceof Decimal) return value.toString();
    if (isDate(value)) return value.$date;
    if (isInstant(value)) return value.$instant;
    if (Array.isArray(value)) return `[${value.map(display).join(", ")}]`;
    return String(value);
}
