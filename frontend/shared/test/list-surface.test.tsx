import { describe, expect, it } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { createElement } from "react";
import { PageRenderer } from "../src/renderer/renderer.ts";
import { resolveDefaultPage } from "../src/resolver.ts";
import type { EntityDefinition } from "../src/metadata.ts";
import type { QueryFilter } from "../src/renderer/context.ts";

/**
 * The list surface's request discipline (re-audit): header-sort clicks lower to
 * the server ({sort} on data.list, direction toggling, offset reset), and a
 * failed fetch renders the failure alert — never the "No records yet" empty
 * state that tells users their data is gone.
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

interface CapturedRequest {
    entity: string;
    size: number;
    offset: number;
    sort?: { field: string; dir: string }[];
}

function mountList(
    list: (request: CapturedRequest) => Promise<unknown>,
    requests: CapturedRequest[],
    options: {
        fields?: Record<string, unknown>;
        displayFieldOf?: (target: string) => string | undefined;
        listFilter?: QueryFilter;
    } = {},
) {
    const ctx = {
        mode: "runtime" as const,
        clock: "2026-09-01T10:00:00Z",
        user: { name: "Demo", roles: ["user"], locale: "en" },
        record: null,
        errors: {},
        getValue: () => null,
        setValue: () => {},
        fields: options.fields ?? {},
        entity: "Invoice",
        listFilter: options.listFilter,
        data: {
            list: async (request: CapturedRequest) => {
                requests.push(request);
                return list(request);
            },
            search: async () => [],
            displayFieldOf: options.displayFieldOf,
        },
        actions: {
            save: async () => {},
            cancel: async () => {},
            deleteRecord: async () => {},
            openPage: async () => {},
        },
        navigate: () => {},
    };
    render(createElement(PageRenderer, {
        page: resolveDefaultPage(entity, "list"),
        entity,
        context: ctx as never,
    }));
}

describe("ListLayout", () => {
    it("header clicks lower server-side sort: direction toggles, offset resets, aria-sort tracks", async () => {
        const requests: CapturedRequest[] = [];
        mountList(async () => ({ rows: [], total: 120000 }), requests);
        await screen.findByRole("table");

        // no field metadata in this harness: headers carry the field apiNames
        const header = screen.getByRole("button", { name: "number" });
        fireEvent.click(header); // first click: ascending
        await waitFor(() =>
            expect(requests[requests.length - 1]?.sort)
                .toEqual([{ field: "number", dir: "asc" }]));
        const th = header.closest("th")!;
        expect(th.getAttribute("aria-sort")).toBe("ascending");

        // page forward, then sort again: the offset must reset to 0 (sorting a
        // page-2 window re-sorts the whole set, not the visible slice)
        fireEvent.click(screen.getByRole("button", { name: /Next/i }));
        await waitFor(() =>
            expect(requests[requests.length - 1]?.offset).toBe(50));
        fireEvent.click(header); // second click: descending — and the offset reset
        await waitFor(() => {
            expect(requests[requests.length - 1]?.sort)
                .toEqual([{ field: "number", dir: "desc" }]);
            expect(requests[requests.length - 1]?.offset).toBe(0);
        });
        expect(th.getAttribute("aria-sort")).toBe("descending");
    });

    it("a failed fetch renders the failure alert — never the empty state", async () => {
        const requests: CapturedRequest[] = [];
        mountList(async () => {
            throw new Error("gateway 502");
        }, requests);
        const alert = await screen.findByRole("alert");
        expect(alert.textContent).toContain("gateway 502");
        // the lie the sixth pass hunted: an empty-looking table below a failed load
        expect(screen.queryByText(/No .* yet/i)).toBeNull();
    });

    it("a textual display field surfaces search; typing lowers a debounced contains filter and resets the offset", async () => {
        const requests: (CapturedRequest & { filter?: QueryFilter })[] = [];
        // the runtime shell's data service resolves the entity's display field
        mountList(async () => ({ rows: [], total: 120000 }), requests, {
            displayFieldOf: (target) => (target === "Invoice" ? "number" : undefined),
            fields: { number: { apiName: "number", type: "text", label: "Invoice No" } },
        });
        await screen.findByRole("table");

        const box = screen.getByRole("searchbox", { name: "Search Invoice" });
        expect(box).toBeTruthy();

        // type past page 1, then let the debounce commit: the request rides the
        // whole set (offset 0), not the page-2 window of the unsearched list
        fireEvent.click(screen.getByRole("button", { name: /Next/i }));
        await waitFor(() => expect(requests[requests.length - 1]?.offset).toBe(50));
        fireEvent.change(box, { target: { value: "SO-1" } });
        await waitFor(() => {
            expect(requests[requests.length - 1]?.filter).toEqual({
                field: "number",
                op: "contains",
                value: "SO-1",
            });
            expect(requests[requests.length - 1]?.offset).toBe(0);
        });
        // the count says the list is filtered
        expect(screen.getByText(/0 records \(filtered\)/)).toBeTruthy();

        // clearing drops the filter
        fireEvent.click(screen.getByRole("button", { name: "Clear search" }));
        await waitFor(() => expect(requests[requests.length - 1]?.filter).toBeUndefined());
    });

    it("the search composes with a deep-linked drill filter (both apply, never either)", async () => {
        const requests: (CapturedRequest & { filter?: QueryFilter })[] = [];
        mountList(async () => ({ rows: [], total: 0 }), requests, {
            displayFieldOf: () => "number",
            fields: { number: { apiName: "number", type: "text", label: "Invoice No" } },
            listFilter: { field: "amount", op: "gt", value: 100 },
        });
        await screen.findByRole("table");
        fireEvent.change(screen.getByRole("searchbox", { name: "Search Invoice" }), {
            target: { value: "SO" },
        });
        await waitFor(() =>
            expect(requests[requests.length - 1]?.filter).toEqual({
                op: "and",
                children: [
                    { field: "amount", op: "gt", value: 100 },
                    { field: "number", op: "contains", value: "SO" },
                ],
            }));
    });

    it("no textual display field — no search box (the server rejects contains off the textual family)", async () => {
        const requests: CapturedRequest[] = [];
        mountList(async () => ({ rows: [], total: 0 }), requests, {
            displayFieldOf: () => "amount",
            fields: { amount: { apiName: "amount", type: "money", label: "Amount" } },
        });
        await screen.findByRole("table");
        expect(screen.queryByRole("searchbox")).toBeNull();
    });
});
