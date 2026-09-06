import { describe, expect, it } from "vitest";
import { Decimal } from "../src/expression/decimal.ts";
import { daysBetween, dateValue } from "../src/expression/values.ts";

/**
 * The signed day-count seam (PHASE-2 §7 Annex A date arithmetic): `dateA - dateB`
 * is a SIGNED day count, and a backwards span (dateB after dateA) is a negative
 * one — overdue days, countdowns. The Decimal carrier's contract is absolute
 * digits + a sign; daysBetween once built the raw negative difference as digits,
 * and the hidden magnitude composed with the sign logic of every later
 * multiply/divide: a product its own compareTo read as +182.5 while toString
 * rendered "--182.5", and add/subtract on it silently answered wrong values.
 *
 * Pinned here directly (the engine-level verdicts ride the shared corpus's
 * negative-span cases) because the defect lives below evaluation — in the value
 * object's construction contract, where a regression would again be invisible to
 * any test that only reads the rendered string of the FIRST operation.
 */

describe("daysBetween — the sign rides the carrier, the digits stay absolute", () => {
    it("a backwards span is a negative value with absolute digits", () => {
        const span = daysBetween(dateValue("2026-03-15"), dateValue("2026-01-01"));
        expect(span.digits).toBe(73n); // absolute — never the raw −73n
        expect(span.sign).toBe(-1);
        expect(span.toString()).toBe("-73");
        expect(span.compareTo(Decimal.parse("-73"))).toBe(0);
    });

    it("a forward span is unchanged (regression guard on the positive leg)", () => {
        const span = daysBetween(dateValue("2026-01-01"), dateValue("2026-03-15"));
        expect(span.sign).toBe(1);
        expect(span.toString()).toBe("73");
    });

    it("composes through multiply — two negatives multiply to the exact positive", () => {
        const span = daysBetween(dateValue("2026-03-15"), dateValue("2026-01-01"));
        const product = span.multiply(Decimal.parse("-2.5"));
        expect(product.toString()).toBe("182.5");
        expect(product.compareTo(Decimal.parse("182.5"))).toBe(0);
    });

    it("composes through divide — a negative span under a negative divisor is positive", () => {
        const span = daysBetween(dateValue("2026-03-15"), dateValue("2026-01-01"));
        const quotient = span.divide(Decimal.parse("-2.5"));
        expect(quotient.toString()).toBe("29.2");
    });

    it("composes through negate — negating the signed span flips only the sign", () => {
        const span = daysBetween(dateValue("2026-03-15"), dateValue("2026-01-01"));
        const flipped = span.negate();
        expect(flipped.sign).toBe(1);
        expect(flipped.digits).toBe(73n);
        expect(flipped.toString()).toBe("73");
    });
});
