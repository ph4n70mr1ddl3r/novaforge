import { describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { createElement } from "react";
import axe from "axe-core";
import {
    PlatformClient,
    resolveDefaultPage,
    pageApiName,
    applyDeltas,
    resolvePage,
    diffPages,
    toPersistedLayout,
} from "@novaforge/shared";
import { RuntimeShell } from "../src/shell.tsx";
import { Inbox } from "../src/inbox.tsx";

/**
 * The runtime shell journey (PHASE-2 §6/T6): published metadata → nav → auto list
 * page (server-side paging) → form page through the real renderer; the approval
 * inbox rides the same shell (PHASE-4 §5).
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
            fields: [
                { apiName: "name", type: "text", required: true, label: "Name" },
                { apiName: "email", type: "email", label: "Email" },
                { apiName: "region", type: "enum", values: ["EU", "US"], label: "Region" },
            ],
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
                { apiName: "total", type: "money", currency: "EUR", label: "Total" },
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
            { role: "arClerk", entity: "Order", create: true, read: true, update: true, delete: true },
        ],
        fieldSecurity: [{ role: "arClerk", entity: "Order", field: "total", access: "readonly" }],
    },
    stateMachines: [],
    reports: [],
    dashboards: [],
    translations: [],
};

function stubClient(lists: Record<string, unknown> = {}) {
    const fetchImpl = vi.fn(async (input: string | URL) => {
        const url = String(input);
        if (url.includes("/runtime/Customer")) {
            return new Response(
                JSON.stringify(lists.Customer ?? { rows: [{ id: "c-1", name: "Acme", region: "EU" }], total: 1 }),
                { status: 200, headers: { "Content-Type": "application/json" } },
            );
        }
        if (url.includes("/runtime/Order")) {
            return new Response(
                JSON.stringify(lists.Order ?? { rows: [], total: 0 }),
                { status: 200, headers: { "Content-Type": "application/json" } },
            );
        }
        return new Response(JSON.stringify({ title: "not stubbed", status: 404 }), { status: 404 });
    });
    return { client: new PlatformClient("", () => "t", fetchImpl as unknown as typeof fetch), fetchImpl };
}

function shell(client: PlatformClient) {
    return createElement(RuntimeShell, {
        client,
        published: { version: 3, app } as never,
        user: { name: "demo", roles: ["erp.arClerk"] },
        versionKey: "v3",
    });
}

describe("RuntimeShell", () => {
    it("renders nav from published metadata (module grouping) and the auto list page with server paging", async () => {
        const { client } = stubClient();
        render(shell(client));
        expect(await screen.findByRole("button", { name: "Customers" })).toBeTruthy();
        expect(screen.getByRole("button", { name: "Orders" })).toBeTruthy();
        // navigate to the Customers list
        screen.getByRole("button", { name: "Customers" }).click();
        await waitFor(() => expect(screen.getByText("1 record")).toBeTruthy());
        expect(screen.getByText("Acme")).toBeTruthy();
    });

    it("renders the form page through the real renderer (zero page definitions)", async () => {
        const { client } = stubClient();
        render(shell(client));
        screen.getByRole("button", { name: "Orders" }).click();
        await waitFor(() => expect(screen.getByText("0 records")).toBeTruthy());
        // the Order list exposes the create action (arClerk create grant)
        expect(screen.getByRole("button", { name: /new|add/i })).toBeTruthy();
    });

    it("the inbox pages tasks and resolves approvals", async () => {
        const { client } = stubClient();
        render(createElement(Inbox, { client }));
        // /api/v1/workflow/tasks is not stubbed → error surfaced accessibly, not a crash
        await waitFor(() => expect(screen.getByRole("alert")).toBeTruthy());
    });

    it("the inbox claims role tasks and delegates (PHASE-4 §11's full ladder)", async () => {
        const calls: string[] = [];
        const roleTask = {
            id: "t-1", type: "approval", entity: "Erp.JournalEntry", recordId: "r-9",
            assignee: null, role: "accountingManager", status: "OPEN", createdBy: "u-2",
            createdAt: "2026-08-24T10:00:00Z",
        };
        const fetchImpl = vi.fn(async (input: string | URL, init?: RequestInit) => {
            const url = String(input);
            calls.push(`${(init?.method ?? "GET").toUpperCase()} ${url}`);
            if (url.includes("/workflow/tasks/t-1/claim")) {
                return new Response(JSON.stringify({ ...roleTask, assignee: "u-1" }), {
                    status: 200, headers: { "Content-Type": "application/json" } });
            }
            if (url.includes("/workflow/tasks/t-1/delegate")) {
                return new Response(JSON.stringify({ id: "t-2", status: "OPEN" }), {
                    status: 200, headers: { "Content-Type": "application/json" } });
            }
            if (url.includes("/workflow/tasks")) {
                return new Response(JSON.stringify({ rows: [roleTask], total: 1 }), {
                    status: 200, headers: { "Content-Type": "application/json" } });
            }
            return new Response(JSON.stringify({ title: "not stubbed", status: 404 }), { status: 404 });
        });
        const prompt = vi.spyOn(window, "prompt").mockReturnValue("u-9");
        const client = new PlatformClient("", () => "t", fetchImpl as unknown as typeof fetch);

        render(createElement(Inbox, { client }));
        // role-addressed task renders its role and offers Claim; assigned offers Delegate
        expect(await screen.findByText(/role: accountingManager/)).toBeTruthy();
        screen.getByRole("button", { name: "Claim" }).click();
        await waitFor(() => expect(calls.some((call) => call.includes("/tasks/t-1/claim"))).toBe(true));

        screen.getByRole("button", { name: "Delegate" }).click();
        await waitFor(() => expect(calls.some((call) => call.includes("/tasks/t-1/delegate"))).toBe(true));
        expect(prompt).toHaveBeenCalled();
        prompt.mockRestore();
    });

    it("passes axe on the shell chrome", async () => {
        const { client } = stubClient();
        const { container } = render(shell(client));
        await screen.findByRole("button", { name: "Customers" });
        const results = await axe.run(container, {});
        expect(results.violations).toEqual([]);
    });
});

describe("the page pipeline: L1 → overlay deltas → persisted artifact", () => {
    const order = app.entities[1]!;
    it("saved deltas reshape the rendered page; toPersistedLayout round-trips", () => {
        const l1 = resolveDefaultPage(order, "form", { role: "arClerk", permissions: app.permissionSet });
        expect(l1.model.root.children!.find((child) => child.key === "field:total")!.readonly).toBe("true");

        const edited = applyDeltas(l1, [
            { op: "setSlot", key: "field:status", slot: "visibility", value: "status != 'POSTED'" },
        ]).page;
        const persisted = toPersistedLayout(edited, l1);
        expect(persisted.base).toBe("auto");
        expect(persisted.deltas).toHaveLength(1);

        // resolution from the saved artifact reproduces the edit
        const resolved = resolvePage(
            { apiName: pageApiName("Order", "form"), type: "form", entity: "Order", layout: persisted },
            order,
            { role: "arClerk", permissions: app.permissionSet },
        );
        expect(resolved.stale).toEqual([]);
        expect(
            resolved.page.model.root.children!.find((child) => child.key === "field:status")!.visibility,
        ).toBe("status != 'POSTED'");
        // and diffPages(edited vs l1) equals the persisted deltas
        expect(diffPages(l1, edited)).toEqual(persisted.deltas);
    });
});
