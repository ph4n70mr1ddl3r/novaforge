import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { createElement } from "react";
import { PlatformClient } from "@novaforge/shared";
import { RuntimeShell } from "../src/shell.tsx";

/**
 * Hash routing (router.ts): the route lives in the URL fragment, so a refresh
 * or a shared link boots ON the named route, in-app navigations become history
 * entries the Back button can walk, the drill-through payload survives the
 * round-trip, and the unsaved-changes gate rewinds a committed hash jump while
 * a draft is dirty instead of destroying it.
 */

const app: import("@novaforge/shared").AppDefinition = {
    apiName: "erp",
    label: "ERP",
    entities: [
        {
            apiName: "Customer",
            label: "Customers",
            module: "Sales",
            displayField: "name",
            fields: [{ apiName: "name", type: "text", required: true, label: "Name" }],
            relationships: [],
            validations: [],
            hooks: [],
            indexes: [],
        },
        {
            apiName: "Order",
            label: "Orders",
            module: "Sales",
            displayField: "reference",
            fields: [
                { apiName: "reference", type: "text", required: true, label: "Reference" },
                { apiName: "status", type: "enum", values: ["DRAFT", "POSTED"], label: "Status" },
            ],
            relationships: [],
            validations: [],
            hooks: [],
            indexes: [],
        },
    ],
    pages: [],
    permissionSet: {
        roles: [{ name: "arClerk", description: "" }],
        objectPermissions: [
            { role: "arClerk", entity: "Customer", create: true, read: true, update: true },
            { role: "arClerk", entity: "Order", create: true, read: true, update: true },
        ],
        fieldSecurity: [],
    },
    stateMachines: [],
    reports: [],
    dashboards: [],
    translations: [],
};

function stubClient(overrides: {
    lists?: Record<string, { rows: Record<string, unknown>[]; total: number }>;
    record?: Record<string, unknown>;
} = {}): { client: PlatformClient; calls: string[] } {
    const calls: string[] = [];
    const fetchImpl = vi.fn(async (input: string | URL) => {
        const url = String(input);
        calls.push(url);
        if (url.includes("/runtime/erp.Order/o-9")) {
            return new Response(
                JSON.stringify(overrides.record ?? { id: "o-9", reference: "ORD-9", status: "DRAFT" }),
                { status: 200, headers: { "Content-Type": "application/json" } },
            );
        }
        if (url.includes("/runtime/erp.Order")) {
            return new Response(
                JSON.stringify(
                    overrides.lists?.Order ?? { rows: [{ id: "o-1", reference: "ORD-1" }], total: 1 },
                ),
                { status: 200, headers: { "Content-Type": "application/json" } },
            );
        }
        if (url.includes("/runtime/erp.Customer")) {
            return new Response(
                JSON.stringify(
                    overrides.lists?.Customer ?? { rows: [{ id: "c-1", name: "Acme" }], total: 1 },
                ),
                { status: 200, headers: { "Content-Type": "application/json" } },
            );
        }
        return new Response(JSON.stringify({ title: "not stubbed", status: 404 }), { status: 404 });
    });
    return { client: new PlatformClient("", () => "t", fetchImpl as unknown as typeof fetch), calls };
}

function shell(client: PlatformClient) {
    return createElement(RuntimeShell, {
        client,
        published: { version: 3, app } as never,
        user: { name: "demo", roles: ["erp.arClerk"] },
        versionKey: "v3",
    });
}

const settleHashEcho = () =>
    act(async () => {
        // the hashchange echo of our own write is async — give it the tick it
        // needs so an unsuppressed echo would visibly re-render before asserts
        await new Promise((resolve) => setTimeout(resolve, 20));
    });

describe("runtime hash routing", () => {
    beforeEach(() => {
        window.location.hash = "";
    });

    it("boots ON the route the hash names — a refresh or shared link keeps your place", async () => {
        const { client } = stubClient();
        window.location.hash = "#/e/Order/list";
        render(shell(client));
        // the Order list loaded with NO click — the mount consumed the URL
        await waitFor(() => expect(screen.getByText("1 record")).toBeTruthy());
        expect(screen.getByText("ORD-1")).toBeTruthy();
        expect(window.location.hash).toBe("#/e/Order/list");
    });

    it("a nav click pushes its route into the hash (bookmarkable addresses)", async () => {
        const { client } = stubClient();
        render(shell(client));
        await screen.findByRole("button", { name: "Customers" });
        await act(async () => {
            screen.getByRole("button", { name: "Customers" }).click();
        });
        await waitFor(() => expect(window.location.hash).toBe("#/e/Customer/list"));
        await settleHashEcho();
        expect(window.location.hash).toBe("#/e/Customer/list");
    });

    it("a detail deep link loads its record by id", async () => {
        const { client } = stubClient();
        window.location.hash = "#/e/Order/detail/o-9";
        render(shell(client));
        await waitFor(() => expect(screen.getByDisplayValue("ORD-9")).toBeTruthy());
        expect(window.location.hash).toBe("#/e/Order/detail/o-9");
    });

    it("the drill-through payload rides the hash and reaches the list request", async () => {
        const { client, calls } = stubClient();
        const filter = { op: "and", children: [{ field: "status", op: "eq", value: "POSTED" }] };
        window.location.hash = `#/e/Order/list?f=${encodeURIComponent(JSON.stringify(filter))}`;
        render(shell(client));
        await waitFor(() => expect(screen.getByText("1 record (filtered)")).toBeTruthy());
        const listCall = calls.find((url) => url.includes("/runtime/erp.Order"))!;
        const wire = JSON.parse(new URL(listCall, "http://gateway").searchParams.get("filter")!);
        // the SERVER's canonical composite shape, re-lowered from the URL payload
        expect(wire).toEqual({ and: [{ field: "status", op: "eq", value: "POSTED" }] });
    });

    it("a junk hash falls home and the URL snaps to the canonical form", async () => {
        const { client } = stubClient();
        window.location.hash = "#/definitely/not/a/route";
        render(shell(client));
        // home: the load-bearing intro line, and no entity list request
        await screen.findByText("Select a record type to begin.");
        await waitFor(() => expect(window.location.hash).toBe("#/home"));
    });

    it("a deep link to an entity the app does not define falls home, not crash", async () => {
        const { client } = stubClient();
        window.location.hash = "#/e/Nope/list";
        render(shell(client));
        await screen.findByText("Select a record type to begin.");
        expect(window.location.hash).toBe("#/home");
    });

    it("the browser's Back button walks in-app navigations", async () => {
        const { client } = stubClient();
        render(shell(client));
        await screen.findByRole("button", { name: "Customers" });
        await act(async () => {
            screen.getByRole("button", { name: "Customers" }).click();
        });
        await waitFor(() => expect(window.location.hash).toBe("#/e/Customer/list"));
        await act(async () => {
            screen.getByRole("button", { name: "Approvals" }).click();
        });
        await waitFor(() =>
            expect(screen.getByRole("heading", { name: "My approvals" })).toBeTruthy());
        expect(window.location.hash).toBe("#/inbox");
        // Back: the app returns to the Customer list — the URL's history entry
        await act(async () => {
            window.history.back();
        });
        await waitFor(() => expect(screen.getByText("1 record")).toBeTruthy());
        expect(window.location.hash).toBe("#/e/Customer/list");
    });

    it("a committed hash jump while dirty rewinds the URL and asks — Back cannot destroy a draft", async () => {
        const { client } = stubClient();
        render(shell(client));
        screen.getByRole("button", { name: "Customers" }).click();
        await waitFor(() => expect(screen.getByText("1 record")).toBeTruthy());
        screen.getByRole("button", { name: /new|add/i }).click();
        await screen.findByRole("button", { name: "Save" });
        const name = screen.getByLabelText(/^Name/) as HTMLInputElement;
        await act(async () => {
            fireEvent.change(name, { target: { value: "Acme" } });
        });
        expect(window.location.hash).toBe("#/e/Customer/form");

        // the Back-equivalent jump — the browser already committed the hash.
        // The await rides inside act: jsdom delivers hashchange as a task, so
        // the gate's updates stay wrapped.
        await act(async () => {
            window.location.hash = "#/inbox";
            await new Promise((resolve) => setTimeout(resolve, 20));
        });
        const dialog = await screen.findByRole("dialog", { name: "Unsaved changes" });
        // the draft is intact AND the URL was rewound to the page we're on —
        // Keep editing must leave URL and route agreeing
        expect((screen.getByLabelText(/^Name/) as HTMLInputElement).value).toBe("Acme");
        expect(window.location.hash).toBe("#/e/Customer/form");

        await act(async () => {
            within(dialog).getByRole("button", { name: "Keep editing" }).click();
        });
        expect(screen.queryByRole("dialog", { name: "Unsaved changes" })).toBeNull();
        expect(window.location.hash).toBe("#/e/Customer/form");
        expect(screen.getByLabelText(/^Name/)).toBeTruthy();

        // Discard completes the interrupted jump — route AND URL land on it
        await act(async () => {
            window.location.hash = "#/inbox";
            await new Promise((resolve) => setTimeout(resolve, 20));
        });
        await act(async () => {
            within(await screen.findByRole("dialog", { name: "Unsaved changes" }))
                .getByRole("button", { name: "Discard changes" })
                .click();
        });
        await waitFor(() =>
            expect(screen.getByRole("heading", { name: "My approvals" })).toBeTruthy());
        expect(window.location.hash).toBe("#/inbox");
    });
});
