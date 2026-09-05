import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { createElement } from "react";
import { PlatformClient, type AppDefinition } from "@novaforge/shared";
import { BuilderShell } from "../src/shell.tsx";
/**
 * The BuilderShell harness (twentieth pass): the shell had no test mount of its
 * own — the nineteenth pass's pages-screen fix ("every screen-saver reloads;
 * the pages leg skipping it left the shell's app at the pre-save snapshot, and
 * the NEXT save 409'd against the user's own revision") shipped unpinned for
 * exactly that reason. This harness stubs the metadata fetches behind the real
 * PlatformClient and drives the shell end to end: mount → load app v1 → pages
 * screen → dirty a customization → save → PUT observed → the app MUST be
 * re-fetched (the reload), so the builder's next save rides the server's own
 * revision instead of the pre-save snapshot.
 */

beforeEach(() => {
    // hash routing (the deep-link pass): a prior test's screen must never
    // seed the next mount's boot — the shell restores from location.hash
    window.location.hash = "";
});


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

/** v1 serves the mount load; every later getApp serves the post-save app (revision 4). */
function stubShellClient() {
    let getAppCalls = 0;
    let putPageCalls = 0;
    const fetchImpl = vi.fn(async (input: string | URL, init?: RequestInit) => {
        const url = String(input);
        const method = (init?.method ?? "GET").toUpperCase();
        const json = (body: unknown) =>
            new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
        if (method === "GET" && /\/api\/v1\/metadata\/apps$/.test(url)) {
            return json([{ id: "app-1" }]);
        }
        if (method === "GET" && url.includes("/api/v1/metadata/apps/app-1")) {
            getAppCalls++;
            return json(getAppCalls === 1 ? baseApp : { ...baseApp, version: 4 });
        }
        if (method === "PUT" && url.includes("/pages/")) {
            putPageCalls++;
            return json({ ...baseApp, pages: [{ apiName: "order_form", revision: 4 }] });
        }
        return json({});
    });
    return {
        client: new PlatformClient("", () => "t", fetchImpl as unknown as typeof fetch),
        getAppCalls: () => getAppCalls,
        putPageCalls: () => putPageCalls,
    };
}

describe("BuilderShell (twentieth-pass harness)", () => {
    it("the pages screen reloads the app after a successful save — the next save rides the server's revision", async () => {
        const stub = stubShellClient();
        render(createElement(BuilderShell, { client: stub.client, role: "builder" }));

        // navigate to the pages screen once the app has loaded
        fireEvent.click(await screen.findByRole("button", { name: "Pages" }));

        // dirty a customization (the §4 visibility overlay the page-builder pins ride)
        fireEvent.click(await screen.findByRole("treeitem", { name: /status/ }));
        const visibility = screen.getByLabelText(/visibility/) as HTMLInputElement;
        fireEvent.change(visibility, { target: { value: "status != 'POSTED'" } });
        await waitFor(() => expect(screen.getByTestId("save-page").textContent).toContain("•"));

        const getAppBefore = stub.getAppCalls();
        fireEvent.click(screen.getByTestId("save-page"));
        await waitFor(() => expect(stub.putPageCalls()).toBe(1));

        // THE PIN: exactly one mount-time getApp served before the save — and a
        // fresh one lands after it. Without the reload the count stays at one and
        // the builder's seed (revision included) stays on the pre-save snapshot.
        expect(getAppBefore).toBe(1);
        await waitFor(() => expect(stub.getAppCalls()).toBe(2), { timeout: 3000 });
    });
});
