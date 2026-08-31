import { describe, expect, it } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { createElement } from "react";
import { PageRenderer } from "../src/renderer/renderer.ts";
import { resolveDefaultPage } from "../src/resolver.ts";
import { RendererContext, dispatchAction, interpolate, resolvePath, type ListRequest } from "../src/renderer/context.ts";
import { FieldLookup } from "../src/catalog/fields.tsx";
import type { EntityDefinition } from "../src/metadata.ts";

/**
 * The renderer interpreter (PHASE-2 §6): pages render through the real component
 * registry; expression slots evaluate through the shared engine; unknown
 * components fall back safely (runtime), while validation flags them (builder).
 */

const entity: EntityDefinition = {
    apiName: "Invoice",
    label: "Invoice",
    displayField: "number",
    fields: [
        { apiName: "number", type: "text", required: true, label: "Invoice No" },
        { apiName: "status", type: "enum", values: ["DRAFT", "POSTED"], label: "Status" },
        { apiName: "amount", type: "money", currency: "EUR", label: "Amount" },
        { apiName: "postedAt", type: "date", label: "Posted" },
    ],
    relationships: [],
    validations: [],
    hooks: [],
    indexes: [],
};

function context(overrides: Record<string, unknown> = {}) {
    const record: Record<string, unknown> = {
        id: "7f2c4e10-9a91-4b1e-8d3a-5d6a2f001234",
        number: "INV-000042",
        status: "DRAFT",
        amount: "120.00",
        postedAt: null,
    };
    return {
        mode: "preview" as const,
        clock: "2026-08-24T10:00:00Z",
        user: { name: "Demo User", roles: ["user"], locale: "en" },
        record,
        errors: {},
        getValue: (path: string) => resolvePath(record, path),
        setValue: (path: string, value: unknown) => {
            record[path] = value;
        },
        fields: {},
        actions: {
            save: async () => {},
            cancel: async () => {},
            deleteRecord: async () => {},
            openPage: async () => {},
        },
        navigate: () => {},
        ...overrides,
    };
}

describe("PageRenderer", () => {
    it("renders the resolved form through the real registry with labels + values", async () => {
        const page = resolveDefaultPage(entity, "form");
        const element = createElement(PageRenderer, { page, entity, context: context() });
        render(element);
        const number = (await screen.findByLabelText(/Invoice No/i)) as HTMLInputElement;
        expect(number.value).toBe("INV-000042");
        expect((screen.getByLabelText(/Amount/i) as HTMLInputElement).value).toBe("120.00");
        expect((screen.getByLabelText("Status") as HTMLSelectElement).value).toBe("DRAFT");
    });

    it("evaluates visibility bindings through the shared expression engine", async () => {
        const page = resolveDefaultPage(entity, "form");
        const postedAt = page.model.root.children!.find((child: { key?: string }) => child.key === "field:postedAt")!;
        postedAt.visibility =
            "status == 'POSTED'";
        render(createElement(PageRenderer, { page, entity, context: context() }));
        await screen.findByLabelText(/Invoice No/i);
        expect(screen.queryByLabelText(/Posted/i)).toBeNull(); // status DRAFT hides it
    });

    it("unknown components render the safe fallback, never crash the page", async () => {
        const page = resolveDefaultPage(entity, "form");
        page.model.root.children!.push({ type: "novaforge.warp-field", key: "n:x", props: {} });
        render(createElement(PageRenderer, { page, entity, context: context() }));
        await screen.findByLabelText(/Invoice No/i);
        expect(screen.getByTestId("nf-fallback")).toBeTruthy();
    });

    it("list layouts page server-side through the data service", async () => {
        const page = resolveDefaultPage(entity, "list");
        const requests: string[] = [];
        const data = {
            list: async (request: { entity: string; size: number; offset: number }) => {
                requests.push(`${request.entity}:${request.size}:${request.offset}`);
                return {
                    rows: [{ id: "1", number: "INV-000001", status: "POSTED" }],
                    total: 120000,
                };
            },
            search: async () => [],
        };
        render(
            createElement(PageRenderer, {
                page,
                entity,
                context: context({ mode: "runtime", data, record: null }),
            }),
        );
        await waitFor(() => expect(screen.getByText("120000 records")).toBeTruthy());
        expect(requests).toEqual(["Invoice:50:0"]);
    });
});

describe("actions and templates (§4)", () => {
    it("interpolates ${record.path} templates when actions dispatch", async () => {
        let opened: { page: string; id?: string } | undefined;
        const ctx = context({
            actions: {
                save: async () => {},
                cancel: async () => {},
                deleteRecord: async () => {},
                openPage: async (page: string, id?: string) => {
                    opened = { page, id };
                },
            },
        });
        await dispatchAction(ctx, { type: "openPage", props: { page: "Invoice_detail", id: "${record.id}" } });
        expect(opened).toEqual({
            page: "Invoice_detail",
            id: "7f2c4e10-9a91-4b1e-8d3a-5d6a2f001234",
        });
        expect(interpolate("No ${record.number} → ${record.status}", ctx.record)).toBe("No INV-000042 → DRAFT");
    });

    it("runFlow dispatches through the context's leg — or rejects where unavailable", async () => {
        let ran: string | undefined;
        const ctx = context({
            actions: {
                save: async () => {},
                cancel: async () => {},
                deleteRecord: async () => {},
                openPage: async () => {},
                runFlow: async (hook: string) => {
                    ran = hook;
                },
            },
        });
        await dispatchAction(ctx, { type: "runFlow", props: { hook: "stampCredit" } });
        expect(ran).toBe("stampCredit");

        const bare = context({
            actions: {
                save: async () => {},
                cancel: async () => {},
                deleteRecord: async () => {},
                openPage: async () => {},
            },
        });
        await expect(dispatchAction(bare, { type: "runFlow", props: { hook: "x" } }))
            .rejects.toThrow("runFlow is not available");
    });
});

describe("FieldLookup's closed state (re-audit)", () => {
    const fields = {
        customer: { apiName: "customer", label: "Customer", type: "lookup" as const, target: "Customer" },
    };

    function lookupMount(record: Record<string, unknown>, rows: Record<string, unknown>[]) {
        const requests: ListRequest[] = [];
        const value = {
            mode: "runtime" as const,
            clock: "2026-08-24T10:00:00Z",
            user: { name: "Demo", roles: ["user"], locale: "en" },
            fields,
            record,
            errors: {},
            getValue: (path: string) => record[path.split(".")[0]!],
            setValue: () => {},
            actions: {
                save: async () => {},
                cancel: async () => {},
                deleteRecord: async () => {},
                openPage: async () => {},
            },
            navigate: () => {},
            data: {
                list: async (request: ListRequest) => {
                    requests.push(request);
                    return { rows, total: rows.length };
                },
                search: async () => [],
                displayFieldOf: () => "name",
            },
        };
        const mounted = render(createElement(
            RendererContext.Provider,
            { value },
            createElement(FieldLookup, { field: "customer", label: "Customer", target: "Customer" }),
        ));
        return { mounted, requests };
    }

    it("shows the target's display label, not the opaque FK id", async () => {
        // Anti-regression: the closed input rendered the stored uuid verbatim —
        // the dropdown labeled options by displayFieldOf, the closed box didn't
        const { requests } = lookupMount({ customer: "cu-7" }, [{ id: "cu-7", name: "Acme Corp" }]);
        await waitFor(() =>
            expect((screen.getByLabelText(/Customer/i) as HTMLInputElement).value).toBe("Acme Corp"));
        // resolved through the data service's by-id query leg, sequenced like the search
        expect(requests).toEqual([
            { entity: "Customer", filter: { op: "eq", field: "id", value: "cu-7" }, size: 1, offset: 0 },
        ]);
    });

    it("falls back to the raw id when the target row cannot be resolved", async () => {
        const { mounted } = lookupMount({ customer: "cu-404" }, []);
        await waitFor(() =>
            expect((screen.getByLabelText(/Customer/i) as HTMLInputElement).value).toBe("cu-404"));
        mounted.unmount();
    });
});
