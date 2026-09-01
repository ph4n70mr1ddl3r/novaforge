import { describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { createElement } from "react";
import { PlatformClient } from "@novaforge/shared";
import { RuntimeShell } from "../src/shell.tsx";

/**
 * The runtime shell's failure surfaces (re-audit): a failed detail load renders
 * the retry-affording alert instead of a silent empty form; and the list's
 * create action — an openPage to the LOWERCASE entity form name ("customerForm")
 * — resolves the entity case-insensitively and renders the form. That lowercase
 * openPage is the exact navigation that crashed EntityPage live at the golden
 * journey (a bare map lookup: "customer" ≠ "Customer" → undefined →
 * entity.fields threw); the pin drives it through the real renderer.
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
            { role: "arClerk", entity: "Customer", create: true, read: true, update: true, delete: true },
            { role: "arClerk", entity: "Order", create: true, read: true, update: true, delete: true },
        ],
        fieldSecurity: [],
    },
    stateMachines: [],
    reports: [],
    dashboards: [],
    translations: [],
};

function fetchImplFor(detailFailure?: boolean) {
    return vi.fn(async (input: string | URL, init?: RequestInit) => {
        const url = String(input);
        const method = (init?.method ?? "GET").toUpperCase();
        if (url.includes("/runtime/erp.Customer/c-1") && method === "GET") {
            if (detailFailure) {
                return new Response(JSON.stringify({ title: "boom", status: 500 }), { status: 500 });
            }
            return new Response(
                JSON.stringify({ id: "c-1", name: "Acme", region: "EU", version: 1 }),
                { status: 200, headers: { "Content-Type": "application/json" } },
            );
        }
        if (url.includes("/runtime/erp.Customer")) {
            return new Response(
                JSON.stringify({ rows: [{ id: "c-1", name: "Acme", region: "EU" }], total: 1 }),
                { status: 200, headers: { "Content-Type": "application/json" } },
            );
        }
        if (url.includes("/runtime/erp.Order")) {
            return new Response(JSON.stringify({ rows: [], total: 0 }),
                { status: 200, headers: { "Content-Type": "application/json" } });
        }
        return new Response(JSON.stringify({ title: "not stubbed", status: 404 }), { status: 404 });
    });
}

function shell(fetchImpl: ReturnType<typeof vi.fn>) {
    return createElement(RuntimeShell, {
        client: new PlatformClient("", () => "t", fetchImpl as unknown as typeof fetch),
        published: { version: 3, app } as never,
        user: { name: "demo", roles: ["erp.arClerk"] },
        versionKey: "v3",
    });
}

describe("RuntimeShell role mapping (PHASE-2 §9)", () => {
    it("maps app-scoped role assignments by suffix and ignores roles the app does not define", async () => {
        // held: a role the app does not define + the app-defined arClerk. The
        // suffix mapping must land on arClerk (the create grant applies — "New"
        // renders); a regression to full-string matching or non-filtered roles
        // either blanks the grant or admits the undefined role.
        const fetchImpl = fetchImplFor();
        const first = render(createElement(RuntimeShell, {
            client: new PlatformClient("", () => "t", fetchImpl as unknown as typeof fetch),
            published: { version: 3, app } as never,
            user: { name: "demo", roles: ["erp.typer", "erp.arClerk"] },
            versionKey: "v3",
        }));
        first.getByRole("button", { name: "Customers" }).click();
        await waitFor(() => expect(first.getByText("1 record")).toBeTruthy());
        expect(first.getByRole("button", { name: "New" })).toBeTruthy();
        first.unmount();

        // the same shell under a role the app never defines: the matrix grants
        // nothing — the create action must NOT render
        const second = render(createElement(RuntimeShell, {
            client: new PlatformClient("", () => "t", fetchImpl as unknown as typeof fetch),
            published: { version: 3, app } as never,
            user: { name: "demo2", roles: ["erp.typer"] },
            versionKey: "v3",
        }));
        // a role the app never defines maps to nothing: no entity nav, no grants
        expect(second.queryByRole("button", { name: "Customers" })).toBeNull();
        expect(second.queryByRole("button", { name: "New" })).toBeNull();
    });
});

describe("RuntimeShell failure surfaces", () => {
    it("the list's create action — openPage('customerForm'), the golden journey's crash — renders the form", async () => {
        render(shell(fetchImplFor()));
        screen.getByRole("button", { name: "Customers" }).click();
        await waitFor(() => expect(screen.getByText("1 record")).toBeTruthy());
        // the auto list page's only action is openPage to the lowercased form name;
        // the case-insensitive resolution must land on Customer and render the form
        screen.getByRole("button", { name: "New" }).click();
        // the form's action bar carries the default save surface — a resolved page
        const save = await screen.findByRole("button", { name: "Save" });
        expect(save).toBeTruthy();
        expect((await screen.findByLabelText(/^Name/)) as HTMLInputElement).toBeTruthy();
    });

    it("a failed detail load renders the Could-not-load alert, not a silent empty form", async () => {
        render(shell(fetchImplFor(true)));
        screen.getByRole("button", { name: "Customers" }).click();
        await waitFor(() => expect(screen.getByText("1 record")).toBeTruthy());
        screen.getByText("Acme").click();
        const alert = await screen.findByRole("alert");
        expect(alert.textContent).toContain("Could not load Customer/c-1");
    });
});
