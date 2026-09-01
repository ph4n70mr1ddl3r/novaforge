import { describe, expect, it } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { createElement } from "react";
import { PageRenderer } from "../src/renderer/renderer.ts";
import { resolveDefaultPage } from "../src/resolver.ts";
import type { EntityDefinition } from "../src/metadata.ts";

/**
 * FieldNumber's commit discipline (re-audit): the money input canonicalizes what
 * was typed through the exact decimal on blur — but a half-typed intermediate
 * ("12.", ".") must never crash the tree (the historical parse bug) and must
 * keep the raw text so the user can finish typing. The blur event carries the
 * DOM value the way the browser delivers it (jsdom resets controlled values,
 * so the target value rides the event explicitly).
 */

const entity: EntityDefinition = {
    apiName: "Invoice",
    label: "Invoice",
    displayField: "number",
    fields: [
        { apiName: "number", type: "text", required: true, label: "Invoice No" },
        { apiName: "amount", type: "money", currency: "EUR", label: "Amount" },
    ],
    relationships: [],
    validations: [],
    hooks: [],
    indexes: [],
};

function mount(record: Record<string, unknown>) {
    const ctx = {
        mode: "runtime" as const,
        clock: "2026-09-01T10:00:00Z",
        user: { name: "Demo", roles: ["user"], locale: "en" },
        record,
        errors: {},
        getValue: (path: string) => record[path.split(".").pop()!],
        setValue: (path: string, value: unknown) => {
            record[path.split(".").pop()!] = value;
        },
        fields: {},
        actions: {
            save: async () => {},
            cancel: async () => {},
            deleteRecord: async () => {},
            openPage: async () => {},
        },
        navigate: () => {},
    };
    render(createElement(PageRenderer, {
        page: resolveDefaultPage(entity, "form"),
        entity,
        context: ctx,
    }));
    return screen.findByLabelText(/Amount/i) as Promise<HTMLInputElement>;
}

describe("FieldNumber", () => {
    it("canonicalizes typed amounts on blur — 0012.3400 commits as 12.3400 (trailing zeros kept)", async () => {
        const record: Record<string, unknown> = { id: "1", number: "INV-1", amount: "" };
        const amount = await mount(record);
        fireEvent.blur(amount, { target: { value: "0012.3400" } });
        // exact decimal: leading zeros drop, trailing zeros are significant
        expect(record.amount).toBe("12.3400");
    });

    it("a half-typed amount never crashes the tree and keeps the raw text on blur", async () => {
        const record: Record<string, unknown> = { id: "1", number: "INV-1", amount: "" };
        const amount = await mount(record);
        // "12." is an intermediate typing state: parse fails, the catch keeps raw
        expect(() => fireEvent.blur(amount, { target: { value: "12." } })).not.toThrow();
        expect(record.amount).toBe("12.");
    });
});
