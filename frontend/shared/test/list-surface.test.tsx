import { describe, expect, it } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { createElement } from "react";
import { PageRenderer } from "../src/renderer/renderer.ts";
import { resolveDefaultPage } from "../src/resolver.ts";
import type { EntityDefinition } from "../src/metadata.ts";

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

function mountList(list: (request: CapturedRequest) => Promise<unknown>, requests: CapturedRequest[]) {
    const ctx = {
        mode: "runtime" as const,
        clock: "2026-09-01T10:00:00Z",
        user: { name: "Demo", roles: ["user"], locale: "en" },
        record: null,
        errors: {},
        getValue: () => null,
        setValue: () => {},
        fields: {},
        entity: "Invoice",
        data: {
            list: async (request: CapturedRequest) => {
                requests.push(request);
                return list(request);
            },
            search: async () => [],
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
});
