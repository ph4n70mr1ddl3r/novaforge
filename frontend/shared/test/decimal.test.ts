import { describe, expect, it } from "vitest";
import { Decimal } from "../src/expression/decimal.ts";

/**
 * The money twin's arithmetic, pinned directly (re-audit): the 39-case
 * conformance corpus carries JSON-number bindings only, so the 34-digit
 * HALF_EVEN context — where exact ties, guard-digit cuts, and mixed-sign
 * alignment live — was never exercised. Every expectation below is the value
 * Java's BigDecimal with MathContext(34, HALF_EVEN) produces for the same
 * operands; a divergence here is wrong money in every formula and roll-up the
 * renderer answers client-side.
 */

/** Convenience: parse-or-throw with the literal's shape checked. */
function d(source: string): Decimal {
    return Decimal.parse(source);
}

describe("Decimal — the 34-digit HALF_EVEN context (Java BigDecimal parity)", () => {
    it("setScale rounds x.xx5 ties to even, never away from zero", () => {
        // 2.5 → 2, 3.5 → 4 (HALF_EVEN), 2.6 → 3 (strictly past half)
        expect(d("2.5").setScale(0).toString()).toBe("2");
        expect(d("3.5").setScale(0).toString()).toBe("4");
        expect(d("2.6").setScale(0).toString()).toBe("3");
        // negative ties round to even the same way (symmetric, not toward zero)
        expect(d("-2.5").setScale(0).toString()).toBe("-2");
        expect(d("-3.5").setScale(0).toString()).toBe("-4");
        // 1.25 → 1.2, 1.35 → 1.4 at scale 1; widening keeps the requested scale
        expect(d("1.25").setScale(1).toString()).toBe("1.2");
        expect(d("1.35").setScale(1).toString()).toBe("1.4");
        expect(d("1.2").setScale(4).toString()).toBe("1.2000");
    });

    it("divide rounds a 34-digit tie UP when the remainder proves the tail non-terminating", () => {
        // The guard-digit cut stops at 36 quotient digits with remainder 1; the
        // kept digits end …50 — an exact tie on their face. The uneaten
        // remainder (1/3 > 0) makes the true discarded fraction strictly more
        // than half: BigDecimal rounds up, the pre-fix twin rounded to even
        // (down) — the divergence this pin exists for.
        // A = 1e33 + 2 (34 digits, last kept digit 2 = even); Q = A·100 + 50;
        // dividend = 3Q + 1 → quotient digits A50 with 1/3 left over.
        const a = "1000000000000000000000000000000002";
        const dividend = String(3n * (BigInt(a) * 100n + 50n) + 1n);
        expect(dividend.length).toBe(36);
        const quotient = d(dividend).divide(d("3"));
        // the true quotient is A·100 + 50.0… (50 + 1/3, strictly past half at
        // the 34-digit cut): BigDecimal rounds UP to (A+1)·100
        expect(quotient.toString()).toBe((BigInt(a) + 1n).toString() + "00");
    });

    it("divide keeps a genuine exact tie at HALF_EVEN (no remainder to round up)", () => {
        // 1 / 2^34·5^… style exact halves terminate inside the guard digits —
        // remainder 0 — so the tie decision is honest: 1.000…05 exact at the
        // 35th digit rounds to even.
        // 0.5 → scale: 5·10^-1; (10^33+2)/10^33 · … — simplest honest tie:
        // "2.5000…0" (34 significant digits ending in 5, exact value) / "1"
        const tie = "2." + "0".repeat(32) + "5" + "0".repeat(0);
        // 34 significant digits: '2' + 32 zeros + '5' — the tie digit is last
        // kept …05 with nothing dropped; instead build the true tie: value =
        // (10^33 + 5) · 10^-33 → 34 digits …005 / exact, divide by 2:
        // (10^33 + 5)/2 = 5·10^32 + 2.5 — not a tie. Direct honest tie:
        // 1.5 / 1 at 34 digits is already ≤34 — no rounding. The corpus-scale
        // honest tie: (10^34 + 5·10^0) is 35 digits …05 /1 → exact tie → down
        // (kept digit 0 even).
        const t = d("1" + "0".repeat(33) + "5").divide(d("1"));
        // exact value 1.0…05e34 — 35 digits, dropped tail exactly '5' with
        // remainder 0: HALF_EVEN keeps …0 (even) → 1e34.
        expect(t.toString()).toBe("1" + "0".repeat(34));
        void tie;
    });

    it("divide produces the 34-digit repeating expansion HALF_EVEN-rounded", () => {
        // 1/3 → 0.333… : 34 threes (35th is 3 < 5 → down)
        expect(d("1").divide(d("3")).toString()).toBe("0." + "3".repeat(34));
        // 2/3 → 0.666… : the 35th digit is 6 > 5 → up → …67 tail
        const twoThirds = d("2").divide(d("3")).toString();
        expect(twoThirds).toBe("0.6" + "6".repeat(32) + "7");
        expect(twoThirds.length).toBe(2 + 34);
    });

    it("add aligns mixed signs exactly and never answers negative zero", () => {
        expect(d("-5").add(d("3")).toString()).toBe("-2");
        expect(d("3").add(d("-5")).toString()).toBe("-2");
        expect(d("5").add(d("-3")).toString()).toBe("2");
        expect(d("-5").add(d("5")).toString()).toBe("0"); // +0, never -0
        expect(d("-0.0001").add(d("0.0001")).toString()).toBe("0");
        // scale alignment: 1.5 + 2.25 = 3.75 (max scale wins)
        expect(d("1.5").add(d("2.25")).toString()).toBe("3.75");
        expect(d("1.50").add(d("2.25")).toString()).toBe("3.75");
    });

    it("multiply rounds overlong exact products HALF_EVEN and normalizes sign", () => {
        // 34 nines × 2 = 199…98 (35 digits) → rounds to 2e34? BigDecimal:
        // 9999999999999999999999999999999999 × 2 = 1.999…98e34 (35 digits) —
        // dropped digit '8' > 5 → up → 2e34.
        const nines = "9".repeat(34);
        expect(d(nines).multiply(d("2")).toString()).toBe("2" + "0".repeat(34));
        // signs: (−a)·b and a·(−b) are negative; (−a)·(−b) positive; 0 carries +
        expect(d("-3").multiply(d("2")).toString()).toBe("-6");
        expect(d("3").multiply(d("-2")).toString()).toBe("-6");
        expect(d("-3").multiply(d("-2")).toString()).toBe("6");
        expect(d("0").multiply(d("-5")).toString()).toBe("0");
    });

    it("parses total and never crashes the tree on half-typed input", () => {
        expect(d("0012.3400").toString()).toBe("12.3400"); // trailing zeros kept
        expect(Decimal.tryParse("12.")).toBeUndefined();
        expect(Decimal.tryParse("-")).toBeUndefined();
        expect(Decimal.tryParse(".5")).toBeUndefined(); // integer part required
        expect(Decimal.tryParse("abc")).toBeUndefined();
        expect(Decimal.parse(" 42 ").toString()).toBe("42");
    });

    it("compareTo and equals ignore scale; isIntegral strips trailing zeros", () => {
        expect(d("1.50").equals(d("1.5"))).toBe(true);
        expect(d("1.5").compareTo(d("1.50"))).toBe(0);
        expect(d("1.5001").compareTo(d("1.5"))).toBe(1);
        expect(d("-1.5").compareTo(d("-1.50"))).toBe(0);
        expect(d("120.00").isIntegral()).toBe(true);
        expect(d("120.50").isIntegral()).toBe(false);
    });

    it("setScale at negative scales rounds exactly like BigDecimal's compact arithmetic", () => {
        // below half → zero; above half → one unit at the target scale
        expect(d("1.234").setScale(-5).compareTo(Decimal.ZERO)).toBe(0);
        expect(d("60000000").setScale(-8).toString()).toBe("100000000");
        expect(d("-60000000").setScale(-8).toString()).toBe("-100000000");
        // exact half with an even quotient (0) rounds to even → zero
        expect(d("50000000").setScale(-8).compareTo(Decimal.ZERO)).toBe(0);
    });

    it("setScale at an absurd negative scale answers instantly — it never builds 10^drop", () => {
        // drop = 2147483647: building 10^drop (the pre-guard path) hung the
        // evaluating tab where Java's compact scale arithmetic answers instantly.
        // The whole value sits strictly below the half-way point → rounds to zero.
        const start = Date.now();
        expect(d("1.234").setScale(-2147483647).compareTo(Decimal.ZERO)).toBe(0);
        expect(d("9999999").setScale(-2147483647).compareTo(Decimal.ZERO)).toBe(0);
        expect(Date.now() - start).toBeLessThan(1000);
    });
});
