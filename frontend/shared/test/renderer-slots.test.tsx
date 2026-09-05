import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { createElement } from "react";
import { PageRenderer } from "../src/renderer/renderer.ts";
import { resolveDefaultPage } from "../src/resolver.ts";
import type { EntityDefinition } from "../src/metadata.ts";
import type { PageNode } from "../src/pagemodel/model.ts";

/**
 * The renderer's expression slots (re-audit): readonly/required ride the same
 * expression evaluation visibility does, and a THROWING binding renders
 * conservatively visible — never blank pages, never frozen fields on fallback.
 */

const entity: EntityDefinition = {
    apiName: "Invoice",
    label: "Invoice",
    displayField: "number",
    fields: [
        { apiName: "number", type: "text", required: true, label: "Invoice No" },
        { apiName: "status", type: "enum", values: ["DRAFT", "POSTED"], label: "Status" },
    ],
    relationships: [],
    validations: [],
    hooks: [],
    indexes: [],
};

function context(record: Record<string, unknown>) {
    return {
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
}

function pageWith(modify: (node: PageNode) => void) {
    const page = resolveDefaultPage(entity, "form");
    const number = page.model.root.children!.find(
        (child: { key?: string }) => child.key === "field:number",
    )!;
    modify(number);
    return page;
}

describe("renderer expression slots", () => {
    it("an expression readonly locks the field exactly while the expression holds", async () => {
        const draft: Record<string, unknown> = { id: "1", number: "INV-1", status: "DRAFT" };
        render(createElement(PageRenderer, {
            page: pageWith((node) => { node.readonly = "status == 'POSTED'"; }),
            entity,
            context: context(draft),
        }));
        expect(((await screen.findByLabelText(/Invoice No/i)) as HTMLInputElement).readOnly)
            .toBe(false);

        const posted: Record<string, unknown> = { id: "1", number: "INV-1", status: "POSTED" };
        render(createElement(PageRenderer, {
            page: pageWith((node) => { node.readonly = "status == 'POSTED'"; }),
            entity,
            context: context(posted),
        }));
        expect(((await screen.findAllByLabelText(/Invoice No/i))[1] as HTMLInputElement).readOnly)
            .toBe(true);
    });

    it("a throwing visibility binding renders conservatively visible — never blank", async () => {
        const record: Record<string, unknown> = { id: "1", number: "INV-1", status: "DRAFT" };
        render(createElement(PageRenderer, {
            page: pageWith((node) => { node.visibility = "status ==="; }),
            entity,
            context: context(record),
        }));
        // the parse failure must NOT hide the field (a blank page reads as data loss)
        expect((await screen.findByLabelText(/Invoice No/i))).toBeTruthy();
    });

    it("a legal decimal the wire carries in exponent notation must not freeze the form", async () => {
        // A DECIMAL field legally holds 1e21 (precision 38, scale 0 ≤ field scale):
        // the record JSON carries the number token, JSON.parse makes it a JS number,
        // and String() renders exponent notation ("1e+21") — which the bindings
        // bridge's plain-decimal grammar rejected. The raw Error aborted the WHOLE
        // bindings map, so every slot rule fell back — and the fallback FROZE the
        // field (readonly === true on throw) whose rule reads perfectly healthy
        // bindings. One exotic-but-legal decimal locked the entire form.
        const record: Record<string, unknown> = {
            id: "1", number: "INV-1", status: "DRAFT",
            amount: 1e21,
        };
        render(createElement(PageRenderer, {
            page: pageWith((node) => { node.readonly = "status == 'POSTED'"; }),
            entity,
            context: context(record),
        }));
        expect(((await screen.findByLabelText(/Invoice No/i)) as HTMLInputElement).readOnly)
            .toBe(false);
    });
});
