import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { createElement } from "react";
import { PlatformClient, type AppDefinition } from "@novaforge/shared";
import { BuilderShell } from "../src/shell.tsx";

/**
 * The builder's unsaved-changes gate (fifth UX pass — the runtime shell's
 * contract, learned): the page builder holds edits LOCAL until "Save page", but
 * a topbar screen switch unmounted it silently and destroyed the work — and the
 * builder's own entity/kind selects re-seeded the editor the same way. A dirty
 * editor must ASK before its edits die; a clean one must never prompt.
 */

beforeEach(() => {
    // hash routing (the deep-link pass): a prior test's screen must never
    // seed the next mount's boot — the shell restores from location.hash
    window.location.hash = "";
});


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
        {
            apiName: "Customer",
            label: "Customer",
            displayField: "name",
            fields: [{ apiName: "name", type: "text", required: true }],
            relationships: [],
            validations: [],
            hooks: [],
            indexes: [],
        },
    ],
    pages: [],
    permissionSet: { roles: [], objectPermissions: [], fieldSecurity: [] },
    stateMachines: [],
    reports: [{ id: "arAging", entity: "Order", label: "A/R Aging", filters: [], groupBy: [], aggregates: [] }],
    dashboards: [{ id: "exec", label: "Executive", widgets: [], roles: [] }],
    translations: [],
};

function stubClient(): PlatformClient {
    const fetchImpl = vi.fn(async (input: string | URL) => {
        const url = String(input);
        if (/\/api\/v1\/metadata\/apps$/.test(url)) {
            return new Response(JSON.stringify([{ id: "app-1" }]), { status: 200 });
        }
        if (url.includes("/api/v1/metadata/apps/app-1")) {
            return new Response(JSON.stringify(app), { status: 200 });
        }
        return new Response(JSON.stringify({}), { status: 200 });
    });
    return new PlatformClient("", () => "t", fetchImpl as unknown as typeof fetch);
}

/** Mounts the shell, opens Pages, and dirties the working page (the §4
 *  visibility overlay — the same edit the shell harness drives). */
async function dirtyPageBuilder() {
    render(createElement(BuilderShell, { client: stubClient(), role: "builder" }));
    fireEvent.click(await screen.findByRole("button", { name: "Pages" }));
    fireEvent.click(await screen.findByRole("treeitem", { name: /status/ }));
    fireEvent.change(screen.getByLabelText(/visibility/) as HTMLInputElement, {
        target: { value: "status != 'POSTED'" },
    });
    await waitFor(() => expect(screen.getByTestId("save-page").textContent).toContain("•"));
}

describe("the builder's unsaved-changes gate", () => {
    it("a topbar screen switch while the page builder is dirty asks instead of destroying", async () => {
        await dirtyPageBuilder();

        fireEvent.click(screen.getByRole("button", { name: "Entities" }));
        const dialog = screen.getByRole("dialog", { name: "Unsaved changes" });
        // the interrupted navigation did NOT happen — the editor is still mounted
        expect(screen.getByTestId("save-page").textContent).toContain("•");

        // Keep editing dismisses the gate and keeps the work
        fireEvent.click(within(dialog).getByRole("button", { name: "Keep editing" }));
        await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
        expect(screen.getByTestId("save-page").textContent).toContain("•");

        // Escape cancels too — and focus returns to the trigger. (A real click
        // focuses the button before the guard captures it; jsdom's fireEvent does
        // not, so the test focuses the trigger explicitly to mirror the browser.)
        const entitiesButton = screen.getByRole("button", { name: "Entities" });
        entitiesButton.focus();
        fireEvent.click(entitiesButton);
        await act(async () => {
            fireEvent.keyDown(document, { key: "Escape" });
        });
        await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
        expect(document.activeElement).toBe(entitiesButton);

        // Discard completes the interrupted switch — deliberately
        fireEvent.click(screen.getByRole("button", { name: "Entities" }));
        fireEvent.click(within(screen.getByRole("dialog", { name: "Unsaved changes" }))
            .getByRole("button", { name: "Discard changes" }));
        await waitFor(() => expect(screen.queryByTestId("save-page")).toBeNull());
        expect(screen.queryByRole("dialog")).toBeNull();
    });

    it("the page builder's own entity switch routes through the same gate", async () => {
        await dirtyPageBuilder();

        // picking another entity re-seeds the editor from the saved page — with
        // unsaved edits that is a destruction, so it asks first
        fireEvent.change(screen.getByLabelText("Entity"), { target: { value: "Customer" } });
        const dialog = screen.getByRole("dialog", { name: "Unsaved changes" });

        // Keep editing reverts the select to the page being edited
        fireEvent.click(within(dialog).getByRole("button", { name: "Keep editing" }));
        await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
        expect((screen.getByLabelText("Entity") as HTMLSelectElement).value).toBe("Order");

        // Discard switches — the user chose to
        fireEvent.change(screen.getByLabelText("Entity"), { target: { value: "Customer" } });
        fireEvent.click(within(screen.getByRole("dialog", { name: "Unsaved changes" }))
            .getByRole("button", { name: "Discard changes" }));
        await waitFor(() =>
            expect((screen.getByLabelText("Entity") as HTMLSelectElement).value).toBe("Customer"));
    });

    it("a clean screen switch never prompts", async () => {
        render(createElement(BuilderShell, { client: stubClient(), role: "builder" }));
        await screen.findByRole("button", { name: "Pages" });
        fireEvent.click(screen.getByRole("button", { name: "Pages" }));
        await waitFor(() => expect(screen.getByTestId("save-page")).toBeTruthy());
        expect(screen.queryByRole("dialog")).toBeNull();
        // and back — still clean, still unguarded
        fireEvent.click(screen.getByRole("button", { name: "Entities" }));
        await waitFor(() => expect(screen.queryByTestId("save-page")).toBeNull());
        expect(screen.queryByRole("dialog")).toBeNull();
    });

    it("a dirty dashboard composer gates the topbar too", async () => {
        render(createElement(BuilderShell, { client: stubClient(), role: "builder" }));
        fireEvent.click(await screen.findByRole("button", { name: "Dashboards" }));
        // the app's one dashboard is selected; adding a widget dirties the copy
        fireEvent.click(await screen.findByRole("button", { name: "Add widget" }));
        await waitFor(() => expect(screen.getByTestId("save-dashboards").textContent).toContain("•"));

        fireEvent.click(screen.getByRole("button", { name: "Entities" }));
        expect(screen.getByRole("dialog", { name: "Unsaved changes" })).toBeTruthy();
        fireEvent.click(within(screen.getByRole("dialog", { name: "Unsaved changes" }))
            .getByRole("button", { name: "Discard changes" }));
        await waitFor(() => expect(screen.queryByTestId("save-dashboards")).toBeNull());
        expect(screen.queryByRole("dialog")).toBeNull();
    });
});
