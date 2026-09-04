import { describe, expect, it } from "vitest";
import { Decimal } from "../src/expression/decimal.ts";
import { Expression } from "../src/expression/expression.ts";

/**
 * The context-precision seam (PHASE-2 §7 Annex A): the JVM reference engine carries
 * its MathContext(34, HALF_EVEN) into `negate(MATH)` and `abs(MATH)` — both ROUND —
 * so the twin's unary minus and abs() must too. The unguarded twin kept every digit
 * of a literal past 34 significant digits and evaluated to a DIFFERENT VALUE than
 * the server answers (abs(…7890): the JVM rounds to …1235×10^6, the twin kept all
 * 40 digits — a silent divergence on every formula the browser previews).
 *
 * Pinned here rather than in the shared corpus: the verdict values exceed 2^53, and
 * JSON numbers don't survive the twin's exact-decimal decode (Decimal.fromNumber
 * goes through a binary float) — the corpus stays compareTo-exact for values its
 * schema can carry; the side-specific pin is the precedent for the rest.
 *
 * Every verdict below was read off the JVM engine's BigDecimal calls
 * (MathContext(34, HALF_EVEN)) before being pinned.
 */

const CLOCK = "2026-01-01T00:00:00Z";

/** 40 significant digits — six past the context. */
const FORTY_DIGITS = "1234567890123456789012345678901234567890";
/** abs(MATH)/negate(MATH) verdict: 34 significant digits, scale −6. */
const CONTEXT_ANSWER = "1234567890123456789012345678901235000000";

function evalWith(expr: string, bindings: Record<string, unknown>): unknown {
    return Expression.parse(expr).evaluate(bindings, CLOCK);
}

describe("expr/v1 context precision — abs() and unary minus honor the JVM engine's MathContext", () => {
    it("abs() of a >34-digit literal rounds to the 34-digit context like abs(MATH)", () => {
        const result = evalWith("abs(x)", { x: Decimal.parse(FORTY_DIGITS) });
        expect((result as Decimal).toString()).toBe(CONTEXT_ANSWER);
    });

    it("unary minus rounds like negate(MATH)", () => {
        const result = evalWith("-x", { x: Decimal.parse(FORTY_DIGITS) });
        expect((result as Decimal).toString()).toBe(`-${CONTEXT_ANSWER}`);
    });

    it("abs() at the context edge rounds HALF_EVEN: a tie rounds to the even kept digit", () => {
        // 35 digits, exact half tail ("…1234|5", kept last digit 4 — even) rounds DOWN.
        const even = evalWith("abs(x)", { x: Decimal.parse("12345678901234567890123456789012345") });
        expect((even as Decimal).toString()).toBe("12345678901234567890123456789012340");
        // Same tie with an odd kept last digit ("…1233|5") rounds UP.
        const odd = evalWith("abs(x)", { x: Decimal.parse("12345678901234567890123456789012335") });
        expect((odd as Decimal).toString()).toBe("12345678901234567890123456789012340");
    });

    it("values within the context pass through untouched (scale kept)", () => {
        const result = evalWith("abs(x)", { x: Decimal.parse("1.2300") });
        expect((result as Decimal).toString()).toBe("1.2300");
    });

    it("subtraction still rounds ONCE from the exact difference (subtract(MATH)) — the unary fix must not leak into it", () => {
        // The internal negate stays exact: if the context crept into subtract's
        // negate, both 40-digit operands would round to the SAME context value and
        // the difference would collapse to 0 where the JVM answers 1.
        const x = Decimal.parse(FORTY_DIGITS);
        const y = Decimal.parse("1234567890123456789012345678901234567889");
        const result = evalWith("x - y", { x, y });
        expect((result as Decimal).toString()).toBe("1");
    });

    it("min/max stay un-rounded (the JVM's min/max carry no MathContext)", () => {
        const x = Decimal.parse(FORTY_DIGITS);
        const y = Decimal.parse("1234567890123456789012345678901234567889");
        expect((evalWith("min(x, y)", { x, y }) as Decimal).toString()).toBe(y.toString());
        expect((evalWith("max(x, y)", { x, y }) as Decimal).toString()).toBe(x.toString());
    });
});
