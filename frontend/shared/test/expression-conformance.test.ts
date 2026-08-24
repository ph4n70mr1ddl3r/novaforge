import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { Decimal } from "../src/expression/decimal.ts";
import { Expression } from "../src/expression/expression.ts";
import { ExpressionError, isDate, isInstant } from "../src/expression/values.ts";

/**
 * The shared conformance corpus (PHASE-2 §7): the exact JSON the JVM engine's
 * `ExpressionConformanceTest` runs — one file, two engines, zero dialect drift.
 * Additions ship fixtures first (Annex A versioning rule).
 */
// @vitest-environment node

interface CorpusCase {
    name: string;
    expr: string;
    bindings?: Record<string, unknown>;
    clock?: string;
    expect?: unknown;
    invalid?: boolean;
    policy?: { bindings: string[]; allowClock?: boolean };
}

interface Corpus {
    version: string;
    description: string;
    cases: CorpusCase[];
}

const CORPUS_URL = new URL(
    "../../../platform/libs/expression-dsl/src/main/resources/conformance/expr-v1-corpus.json",
    import.meta.url,
);
const corpus: Corpus = JSON.parse(readFileSync(fileURLToPath(CORPUS_URL), "utf8"));

/** Corpus values are JSON: numbers → Decimals, tagged dates/instants stay tagged. */
function decode(value: unknown): unknown {
    if (typeof value === "number") return Decimal.fromNumber(value);
    return value;
}

function comparable(value: unknown): string {
    if (isDate(value)) return `date:${value.$date}`;
    if (isInstant(value)) return `instant:${value.$instant}`;
    return `${typeof value}:${String(value)}`;
}

/** Numbers compare as exact decimals (corpus rule) — scale never leaks into equality. */
function assertSame(actual: unknown, expected: unknown): void {
    if (actual instanceof Decimal && expected instanceof Decimal) {
        expect(actual.equals(expected), `${actual.toString()} == ${expected.toString()}`).toBe(true);
        return;
    }
    expect(comparable(actual)).toBe(comparable(expected));
}

describe(`expr/v1 conformance corpus (${corpus.version}, ${corpus.cases.length} cases, shared with the JVM engine)`, () => {
    for (const testCase of corpus.cases) {
        it(testCase.name, () => {
            const bindings: Record<string, unknown> = {};
            for (const [key, value] of Object.entries(testCase.bindings ?? {})) {
                bindings[key] = decode(value);
            }

            let expression: Expression;
            try {
                expression = Expression.parse(testCase.expr);
            } catch (error) {
                if (!testCase.invalid) throw error;
                expect(error).toBeInstanceOf(ExpressionError);
                return;
            }
            if (testCase.invalid) {
                // Invalid-after-parse: compile or evaluation must reject.
                expect(() => {
                    if (testCase.policy) {
                        expression.compileCheck({
                            bindings: testCase.policy.bindings,
                            allowClock: testCase.policy.allowClock ?? true,
                        });
                    }
                    expression.evaluate(bindings, testCase.clock ?? "2026-01-01T00:00:00Z");
                }).toThrow();
                return;
            }

            if (testCase.policy) {
                expression.compileCheck({
                    bindings: testCase.policy.bindings,
                    allowClock: testCase.policy.allowClock ?? true,
                });
            }

            const result = expression.evaluate(bindings, testCase.clock ?? "2026-01-01T00:00:00Z");
            assertSame(result, decode(testCase.expect));
        });
    }
});
