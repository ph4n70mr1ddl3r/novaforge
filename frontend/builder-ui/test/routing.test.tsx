import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { createElement } from "react";
import { PlatformClient, type AppDefinition } from "@novaforge/shared";
import { BuilderShell } from "../src/shell.tsx";

/**
 * Builder hash routing (the runtime router's twin): the screen lives in the
 * fragment, so a refresh reopens the editor you were in, a nav click pushes a
 * history entry Back can walk, and a committed hash jump while the page builder
 * holds edits rewinds the URL and asks — the same gate a topbar click answers
 * to, arriving this time with no click of its own.
 */

const baseApp: AppDefinition = {
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

function stubClient() {
    const fetchImpl = vi.fn(async (input: string | URL) => {
        const url = String(input);
        const json = (body: unknown) =>
            new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
        if (/\/api\/v1\/metadata\/apps$/.test(url)) return json([{ id: "app-1" }]);
        if (url.includes("/api/v1/metadata/apps/app-1")) return json(baseApp);
        return json({});
    });
    return new PlatformClient("", () => "t", fetchImpl as unknown as typeof fetch);
}

const shell = (client: PlatformClient) =>
    createElement(BuilderShell, { client, role: "builder" });

describe("builder hash routing", () => {
    beforeEach(() => {
        window.location.hash = "";
    });

    it("boots ON the screen the hash names — a refresh reopens the editor you were in", async () => {
        window.location.hash = "#pages";
        render(shell(stubClient()));
        // the pages screen: the page builder's Entity select is its marker —
        // the entity grid (default screen) never renders one
        expect(await screen.findByLabelText("Entity")).toBeTruthy();
        expect(window.location.hash).toBe("#pages");
    });

    it("a topbar click pushes its screen into the hash, and Back walks it", async () => {
        const client = stubClient();
        render(shell(client));
        fireEvent.click(await screen.findByRole("button", { name: "Reports" }));
        await waitFor(() => expect(window.location.hash).toBe("#reports"));
        expect(screen.getByRole("heading", { name: "Reports" })).toBeTruthy();

        fireEvent.click(screen.getByRole("button", { name: "RBAC" }));
        await waitFor(() => expect(window.location.hash).toBe("#rbac"));

        await act(async () => {
            window.history.back();
        });
        await waitFor(() => expect(window.location.hash).toBe("#reports"));
        expect(screen.getByRole("heading", { name: "Reports" })).toBeTruthy();
    });

    it("a committed hash jump while the page builder is dirty rewinds and asks — no click needed to lose work", async () => {
        const client = stubClient();
        render(shell(client));
        fireEvent.click(await screen.findByRole("button", { name: "Pages" }));
        fireEvent.click(await screen.findByRole("treeitem", { name: /status/ }));
        const visibility = screen.getByLabelText(/visibility/) as HTMLInputElement;
        await act(async () => {
            fireEvent.change(visibility, { target: { value: "status != 'POSTED'" } });
        });
        await waitFor(() => expect(screen.getByTestId("save-page").textContent).toContain("•"));
        expect(window.location.hash).toBe("#pages");

        // the Back-equivalent jump — the browser already committed the hash
        await act(async () => {
            window.location.hash = "#rbac";
            await new Promise((resolve) => setTimeout(resolve, 20));
        });
        const dialog = await screen.findByRole("dialog", { name: "Unsaved changes" });
        // the working page is intact and the URL was rewound to where we are
        expect(screen.getByTestId("save-page")).toBeTruthy();
        expect(window.location.hash).toBe("#pages");

        await act(async () => {
            fireEvent.click(within(dialog).getByRole("button", { name: "Keep editing" }));
        });
        expect(screen.queryByRole("dialog", { name: "Unsaved changes" })).toBeNull();
        expect(window.location.hash).toBe("#pages");
        expect(screen.getByTestId("save-page")).toBeTruthy();

        // Discard completes the interrupted jump — screen AND URL land on it
        await act(async () => {
            window.location.hash = "#rbac";
            await new Promise((resolve) => setTimeout(resolve, 20));
        });
        await act(async () => {
            fireEvent.click(within(
                await screen.findByRole("dialog", { name: "Unsaved changes" }),
            ).getByRole("button", { name: "Discard changes" }));
        });
        await waitFor(() =>
            expect(screen.getByRole("heading", { name: "Roles & permissions" })).toBeTruthy());
        expect(window.location.hash).toBe("#rbac");
    });
});
