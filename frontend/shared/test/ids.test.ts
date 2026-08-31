import { afterEach, describe, expect, it } from "vitest";
import { randomKey } from "../src/ids.ts";

/**
 * The context-safe id mint (re-audit): crypto.randomUUID is a secure-context
 * API — on a plain-HTTP origin it is undefined OR throws, and the unguarded
 * palette insert / create-path calls bricked the whole screen. The twin must
 * hold with randomUUID gone and still hand back a usable key.
 */

/** Hides randomUUID from the crypto object exactly as a plain-HTTP origin does. */
function withoutRandomUUID<T>(run: () => T): T {
    const target = Object.getPrototypeOf(globalThis.crypto) as Crypto;
    const descriptor = Object.getOwnPropertyDescriptor(target, "randomUUID")!;
    Object.defineProperty(target, "randomUUID", { ...descriptor, value: undefined });
    try {
        return run();
    } finally {
        Object.defineProperty(target, "randomUUID", descriptor);
    }
}

describe("randomKey", () => {
    afterEach(() => {
        // a failed restore must never poison the other suites' crypto
        expect(typeof globalThis.crypto.randomUUID).toBe("function");
    });

    it("falls back to the Math.random twin when crypto.randomUUID is undefined", () => {
        const key = withoutRandomUUID(() => randomKey());
        expect(typeof key).toBe("string");
        expect(key.length).toBeGreaterThan(4);
    });

    it("uses the real randomUUID where the context provides it, and mints distinct keys", () => {
        expect(randomKey()).toMatch(/[0-9a-f-]{36}/);
        expect(randomKey()).not.toBe(randomKey());
    });
});
