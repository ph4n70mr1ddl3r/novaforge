import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { createElement } from "react";
import { PlatformClient, type AppDefinition } from "@novaforge/shared";
import { RbacEditor } from "../src/rbac-editor.tsx";
import { I18nEditor, parseCsv, toCsv, translatableUniverse } from "../src/i18n-editor.tsx";
import { Onboarding } from "../src/onboarding.tsx";

/** RBAC editors (T9), tenant onboarding (T10), and the i18n editor (PHASE-8 §7). */

const app: AppDefinition = {
    apiName: "Erp",
    id: "app-1",
    label: "ERP",
    entities: [
        {
            apiName: "Invoice",
            label: "Invoice",
            fields: [
                { apiName: "number", type: "text" },
                { apiName: "total", type: "money", currency: "EUR" },
            ],
            relationships: [],
            validations: [],
            hooks: [],
            indexes: [],
        },
    ],
    pages: [],
    permissionSet: {
        roles: [{ name: "arClerk", description: "AR clerk" }],
        objectPermissions: [{ role: "arClerk", entity: "Invoice", create: true, read: true }],
        fieldSecurity: [{ role: "arClerk", entity: "Invoice", field: "total", access: "hidden" }],
    },
    stateMachines: [],
    reports: [{ id: "trialBalance", entity: "JournalEntry" }],
    dashboards: [],
    translations: [{ locale: "de", entries: { "app.label": "ERP (DE)" } }],
};

describe("RbacEditor (T9)", () => {
    it("edits the object matrix and cycles field security, then saves the permission set", async () => {
        const onSave = vi.fn<(permissionSet: import("@novaforge/shared").PermissionSet) => Promise<void>>(async () => {});
        render(createElement(RbacEditor, { app, onSave }));
        // absent flags deny — the matrix starts from the authored state
        fireEvent.click(screen.getByLabelText("delete"));
        // field security cycles visible → readonly → hidden
        const totalCell = screen.getByRole("button", { name: "hidden" });
        expect(totalCell.getAttribute("data-access")).toBe("hidden");
        fireEvent.click(totalCell); // hidden → visible (cycle wraps)
        screen.getByRole("button", { name: "Save permissions" }).click();
        await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
        const saved = onSave.mock.calls[0]![0] as AppDefinition["permissionSet"];
        expect(saved.objectPermissions.find((p) => p.entity === "Invoice")?.delete).toBe(true);
        expect(saved.fieldSecurity).toEqual([]); // cycled back to visible = no override rows
    });
});

describe("Onboarding (T10)", () => {
    function clientWith(calls: { path: string; method: string; body?: unknown }[]) {
        const fetchImpl = vi.fn(async (input: string | URL, init?: RequestInit) => {
            const url = String(input);
            calls.push({ path: url, method: (init?.method ?? "GET").toUpperCase(), body: init?.body ? JSON.parse(String(init.body)) : undefined });
            if (url.includes("/api/v1/admin/tenants") && !url.includes("role-assignments")) {
                return new Response(JSON.stringify({ tenantId: "t-1" }), { status: 200 });
            }
            if (url.includes("role-assignments")) {
                return new Response(JSON.stringify({ ok: true }), { status: 200 });
            }
            if (url.endsWith("/api/v1/metadata/apps") && (init?.method ?? "").toUpperCase() === "POST") {
                return new Response(JSON.stringify({ id: "app-9" }), { status: 200 });
            }
            return new Response(JSON.stringify({ title: "unstubbed", status: 404 }), { status: 404 });
        });
        return new PlatformClient("", () => "t", fetchImpl as unknown as typeof fetch);
    }

    it("drives tenant → first admin → first app through the admin APIs", async () => {
        const calls: { path: string; method: string; body?: unknown }[] = [];
        const onAppCreated = vi.fn();
        render(createElement(Onboarding, { client: clientWith(calls), onAppCreated }));

        fireEvent.change(screen.getByLabelText(/Tenant label/), { target: { value: "Acme" } });
        fireEvent.change(screen.getByLabelText(/First admin username/), { target: { value: "admin" } });
        fireEvent.change(screen.getByLabelText(/First admin email/), { target: { value: "admin@acme.test" } });
        screen.getByRole("button", { name: "Create tenant + admin" }).click();
        await waitFor(() => expect(screen.getByText(/assign the first admin/i)).toBeTruthy());

        fireEvent.change(screen.getByLabelText("Username"), { target: { value: "admin" } });
        screen.getByRole("button", { name: "Assign role" }).click();
        await waitFor(() => expect(screen.getByLabelText(/App apiName/)).toBeTruthy());

        fireEvent.change(screen.getByLabelText(/App apiName/), { target: { value: "Erp" } });
        screen.getByRole("button", { name: "Create first app" }).click();
        await waitFor(() => expect(onAppCreated).toHaveBeenCalledWith("app-9"));

        const tenantPost = calls.find((call) => call.path.endsWith("/admin/tenants"));
        expect(tenantPost?.method).toBe("POST");
        expect((tenantPost?.body as Record<string, unknown>).adminEmail).toBe("admin@acme.test");
        const appPost = calls.find((call) => call.path.endsWith("/metadata/apps"));
        expect((appPost?.body as Record<string, unknown>).apiName).toBe("Erp");
    });
});

describe("I18nEditor (PHASE-8 §7)", () => {
    it("keys the translatable universe, reports missing, round-trips CSV", async () => {
        const universe = translatableUniverse(app);
        expect(universe.map((entry) => entry.key)).toEqual([
            "app.label",
            "Invoice.label",
            "Invoice.number.label",
            "Invoice.total.label",
            "report.trialBalance.label",
        ]);
        render(createElement(I18nEditor, {
            app,
            loadWorkspace: async (locale) => app.translations.find((t) => t.locale === locale),
            saveWorkspace: async () => {},
        }));
        await waitFor(() => expect(screen.getByLabelText("app.label translation")).toHaveProperty("value", "ERP (DE)"));
        // 5 keys, 1 translated → 4 missing
        expect(screen.getByText(/4 of 5 keys missing/)).toBeTruthy();
        // the fallback chain never blanks: the untranslated row shows empty source-side fill
        expect((screen.getByLabelText("Invoice.label translation") as HTMLInputElement).value).toBe("");
    });

    it("CSV export/import round-trips", () => {
        const entries = { "app.label": "ERP \" Deluxe\"", "Invoice.label": "Rechnung" };
        expect(parseCsv(toCsv(entries))).toEqual(entries);
    });
});
