import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { createElement } from "react";
import { ApiError, type AppDefinition } from "@novaforge/shared";
import { PageBuilder } from "../src/page-builder.tsx";

/**
 * The page builder (PHASE-2 T8): palette → canvas → property panel → live preview
 * through the real renderer; undo/redo; save persists pinned, delta-encoded pages;
 * a 409 from a concurrent editor prompts the rebase flow.
 */

const app: AppDefinition = {
    apiName: "Erp",
    id: "app-1",
    entities: [
        {
            apiName: "Order",
            label: "Order",
            displayField: "reference",
            fields: [
                { apiName: "reference", type: "text", required: true },
                { apiName: "status", type: "enum", values: ["DRAFT", "POSTED"] },
            ],
            relationships: [],
            validations: [],
            hooks: [],
            indexes: [],
        },
    ],
    pages: [],
    permissionSet: { roles: [], objectPermissions: [], fieldSecurity: [] },
    stateMachines: [],
    reports: [],
    dashboards: [],
    translations: [],
};

function builder(
    savePage: (page: Record<string, unknown>) => Promise<unknown>,
    pages: AppDefinition["pages"] = [],
) {
    return createElement(PageBuilder, { app: { ...app, pages }, savePage });
}

describe("PageBuilder (T8)", () => {
    it("customizes the form per §4's example: visibility overlay + reorder, undo restores", async () => {
        render(builder(async () => {}));
        // select the status field in the canvas, then author the visibility binding
        fireEvent.click(await screen.findByRole("treeitem", { name: /status/ }));
        const visibility = screen.getByLabelText(/visibility/) as HTMLInputElement;
        fireEvent.change(visibility, { target: { value: "status != 'POSTED'" } });
        await waitFor(() =>
            expect(screen.getByTestId("save-page").textContent).toContain("•"),
        );
        // undo the overlay — the dirty marker clears
        screen.getByRole("button", { name: "Undo" }).click();
        await waitFor(() => expect(screen.getByTestId("save-page").textContent).not.toContain("•"));
    });

    it("saves a delta-encoded, version-pinned page against the L1 default", async () => {
        const savePage = vi.fn<(page: Record<string, unknown>) => Promise<unknown>>(async () => {});
        render(builder(savePage));
        fireEvent.click(await screen.findByRole("treeitem", { name: /status/ }));
        fireEvent.change(screen.getByLabelText(/visibility/), { target: { value: "status != 'POSTED'" } });
        screen.getByTestId("save-page").click();
        await waitFor(() => expect(savePage).toHaveBeenCalledTimes(1));
        const page = savePage.mock.calls[0]![0] as {
            apiName: string;
            type: string;
            layout: { base: string; kind: string; deltas: { op: string; slot?: string; value?: string }[] };
        };
        expect(page.apiName).toBe("orderForm");
        expect(page.layout.base).toBe("auto");
        const slot = page.layout.deltas.find((delta) => delta.op === "setSlot");
        expect(slot?.slot).toBe("visibility");
        expect(slot?.value).toBe("status != 'POSTED'");
    });

    it("opens a saved page's customizations and carries its server revision (no silent wipe)", async () => {
        // Anti-regression (2026-08-31, thirteenth pass): the editor seeded from the
        // L1 default and never read app.pages — a second edit session showed no
        // customizations, its revision counter was local fiction, and the first save
        // overwrote the saved page with deltas-vs-default (a silent wipe of the
        // prior session's work).
        const savePage = vi.fn<(page: Record<string, unknown>) => Promise<unknown>>(async () => {});
        // the first session's saved work: hide status, pinned by the server at revision 3
        const savedPages: AppDefinition["pages"] = [{
            apiName: "orderForm",
            label: "Order form",
            type: "form",
            entity: "Order",
            revision: 3,
            layout: {
                base: "auto",
                kind: "form",
                deltas: [
                    { op: "setSlot", key: "field:status", slot: "visibility", value: "status != 'POSTED'" },
                ],
            },
        }];
        render(builder(savePage, savedPages));

        // the saved customization is visible: selecting the status field shows its
        // visibility slot carrying the saved expression (the default page has none)
        fireEvent.click(await screen.findByRole("treeitem", { name: /status/ }));
        const visibility = screen.getByLabelText(/visibility/) as HTMLInputElement;
        expect(visibility.value).toBe("status != 'POSTED'");

        // a fresh edit + save carries the server's revision — the optimistic-lock
        // token, not a local fiction
        fireEvent.change(visibility, { target: { value: "status == 'DRAFT'" } });
        screen.getByTestId("save-page").click();
        await waitFor(() => expect(savePage).toHaveBeenCalledTimes(1));
        const page = savePage.mock.calls[0]![0] as { revision?: number };
        expect(page.revision).toBe(3);
    });

    it("a concurrent-edit 409 prompts the rebase (T8's acceptance)", async () => {
        let calls = 0;
        const savePage = vi.fn<(page: Record<string, unknown>) => Promise<unknown>>(async () => {
            calls += 1;
            if (calls === 1) {
                throw new ApiError(409, {
                    type: "x", title: "Conflict", status: 409, code: "4090",
                    detail: "page orderForm was modified by another editor — rebase and retry",
                });
            }
            return {};
        });
        render(builder(savePage));
        fireEvent.click(await screen.findByRole("treeitem", { name: /status/ }));
        fireEvent.change(screen.getByLabelText(/visibility/), { target: { value: "status != 'POSTED'" } });
        screen.getByTestId("save-page").click();
        const prompt = await screen.findByTestId("rebase-prompt");
        expect(prompt.textContent).toContain("another editor");
        // rebase resets onto the current draft (fresh revision) — visible in flash
        prompt.querySelector("button")!.click();
        expect(await screen.findByText(/Rebased onto the server's page/)).toBeTruthy();
    });

    it("rejects saves failing publish validation (unpinned or invalid)", async () => {
        const savePage = vi.fn<(page: Record<string, unknown>) => Promise<unknown>>(async () => {});
        render(builder(savePage));
        fireEvent.click(await screen.findByRole("treeitem", { name: /status/ }));
        fireEvent.change(screen.getByLabelText(/visibility/), { target: { value: "ghost > 1" } });
        screen.getByTestId("save-page").click();
        await waitFor(() => expect(screen.getByRole("alert")).toBeTruthy());
        expect(screen.getByRole("alert").textContent).toContain("unresolved reference");
        expect(savePage).not.toHaveBeenCalled();
    });

    it("authors the runFlow action (PHASE-3 §8): the ladder grows the entity's flow hooks", async () => {
        const hookedApp: AppDefinition = {
            ...app,
            entities: app.entities.map((entity): import("@novaforge/shared").EntityDefinition =>
                entity.apiName === "Order"
                    ? {
                          ...entity,
                          hooks: [
                              {
                                  name: "stampCredit",
                                  trigger: "beforeSave",
                                  flow: { id: "s1", op: "setField" },
                              },
                          ] as import("@novaforge/shared").HookRule[],
                      }
                    : entity,
            ),
        };
        const savePage = vi.fn<(page: Record<string, unknown>) => Promise<unknown>>(async () => {});
        render(createElement(PageBuilder, { app: hookedApp, savePage }));

        const add = await screen.findByLabelText(/add action/i) as HTMLSelectElement;
        fireEvent.change(add, { target: { value: "runFlow" } });
        // the hook picker offers the entity's named flow hooks
        const hookPicker = screen.getByLabelText(/runflow hook/i) as HTMLSelectElement;
        expect(Array.from(hookPicker.options).map((option) => option.value)).toContain("stampCredit");
        fireEvent.change(hookPicker, { target: { value: "stampCredit" } });

        screen.getByTestId("save-page").click();
        await waitFor(() => expect(savePage).toHaveBeenCalledTimes(1));
        const page = savePage.mock.calls[0]![0] as {
            layout: { deltas: { op: string; action?: { type: string; props: { hook?: string } } }[] };
        };
        // the diff's v1 granularity replaces the ladder wholesale — the runFlow
        // addition rides as addAction deltas with the L1 defaults re-added
        const added = page.layout.deltas.filter((delta) => delta.op === "addAction");
        expect(added.at(-1)!.action).toEqual({ type: "runFlow", props: { hook: "stampCredit" } });
        expect(added.length).toBeGreaterThan(1);
    });
});
