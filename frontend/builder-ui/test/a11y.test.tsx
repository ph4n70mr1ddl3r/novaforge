import { describe, expect, it, vi } from "vitest";
import axe from "axe-core";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { createElement } from "react";
import { PlatformClient, type AppDefinition } from "@novaforge/shared";
import { BuilderShell } from "../src/shell.tsx";

/**
 * The builder's axe scans (PHASE-2 §2's a11y row: "WCAG 2.2 AA; axe automated
 * checks in CI — builder and generated UI"). The generated-UI half landed with
 * the catalog gallery and the runtime shell; the builder half — where builders
 * spend their authoring hours — had none. This journey mounts the BuilderShell
 * with a representative app and axe-scans every screen it can render: any new
 * builder surface that fails the scans fails CI, exactly like the gallery's
 * exhaustive-mount rule on the runtime side.
 */

const app: AppDefinition = {
    id: "app-1",
    apiName: "Erp",
    label: "ERP",
    entities: [
        {
            apiName: "Invoice",
            label: "Invoice",
            displayField: "reference",
            module: "AR",
            fields: [
                { apiName: "reference", type: "text", required: true },
                { apiName: "status", type: "enum", values: ["DRAFT", "POSTED"] },
                { apiName: "amountOutstanding", type: "money", precision: 18, scale: 4 },
            ],
            relationships: [],
            validations: [{ name: "nonnegative", expression: "amountOutstanding >= 0", message: "negative" }],
            hooks: [],
            indexes: [],
        },
    ],
    pages: [{ apiName: "invoice_form", type: "form", entity: "Invoice", layout: { kind: "form", base: "auto", root: { type: "novaforge.form-layout", key: "form", props: {} }, actions: [] }, revision: 3 }],
    permissionSet: {
        roles: [{ name: "arClerk", description: "AR Clerk", level: 3 }],
        objectPermissions: [{ role: "arClerk", entity: "Invoice", create: true, read: true, update: true, delete: false }],
        fieldSecurity: [],
    },
    stateMachines: [{
        id: "sm_invoice",
        entity: "Invoice",
        stateField: "status",
        initial: "DRAFT",
        states: [{ name: "DRAFT" }, { name: "POSTED", terminal: true }],
        transitions: [{ from: "DRAFT", to: "POSTED" }],
    }],
    slas: [],
    jobs: [{ name: "nightlyAging", cron: "0 2 * * *", target: "report", params: {}, enabled: true }],
    reports: [{ id: "arAging", entity: "Invoice", label: "A/R Aging", filters: [], groupBy: [], aggregates: [] }],
    dashboards: [{ id: "exec", label: "Executive", widgets: [], roles: [] }],
    testSuites: [],
    translations: [{ locale: "de", entries: { "app.label": "ERP" } }],
    gapLog: [{ id: "G-1", area: "flows", blocker: "b", disposition: "closed", priority: "medium" }],
};

function stubShellClient(): PlatformClient {
    const json = (body: unknown, status = 200) =>
        new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
    const fetchImpl = vi.fn(async (input: string | URL, init?: RequestInit) => {
        const url = String(input);
        const method = (init?.method ?? "GET").toUpperCase();
        if (method === "GET" && /\/api\/v1\/metadata\/apps$/.test(url)) return json([{ id: "app-1" }]);
        if (method === "GET" && url.includes("/apps/app-1/changeset")) {
            return json({
                env: "staging",
                publishedVersion: 2,
                diff: { entities: { modified: ["Invoice"] }, reports: { added: ["arAging"] } },
                suiteResults: [{ suite: "reconciliation", green: true, runAt: "2026-09-03T00:00:00Z" }],
                scriptRatio: { draft: 0, published: 0, modules: { Invoice: { hooks: 1, scripts: 0, scriptShare: 0 } } },
                credentialRefs: ["cred_stripe"],
                resolvedGaps: [{ id: "G-1", area: "flows", disposition: "closed", resolvedIn: "§3.3" }],
                promotions: [{ env: "staging", toVersion: 2, kind: "promote" }],
            });
        }
        if (method === "GET" && url.includes("/apps/app-1/translations")) return json([]);
        if (method === "GET" && url.includes("/apps/app-1/suite-runs")) return json({ runs: [] });
        if (method === "GET" && url.includes("/api/v1/scheduler/jobs")) return json([]);
        if (method === "GET" && url.includes("/metadata/templates")) return json([]);
        if (method === "GET" && url.includes("/api/v1/integrations/")) return json([]);
        if (method === "GET" && url.includes("/apps/app-1")) return json(app);
        return json({});
    });
    return new PlatformClient("", () => "t", fetchImpl as unknown as typeof fetch);
}

/** The axe runner — raw axe-core, the gallery's overload. */
const axeCheck = (element: HTMLElement) => axe.run(element, {});

describe("builder a11y — axe scans of every screen (PHASE-2 §2)", () => {
    it("the BuilderShell screens are axe-clean", async () => {
        const client = stubShellClient();
        const { container, unmount } = render(createElement(BuilderShell, { client, role: "builder" }));

        // the entities screen renders once the app loads
        await screen.findByText("Invoice");

        const screens = [
            "entities", "pages", "logic", "suites", "automation", "rbac", "reports",
            "dashboards", "integrations", "i18n", "lifecycle", "templates", "onboarding",
        ];
        const scanned: string[] = [];
        for (const name of screens) {
            fireEvent.click(screen.getByRole("button", { name: name }));
            // let the screen's async mounts settle before scanning
            // eslint-disable-next-line no-await-in-loop
            await waitFor(() => {
                const current = container.querySelector('[aria-current="true"]');
                expect(current?.textContent).toBe(name);
            });
            // eslint-disable-next-line no-await-in-loop
            await new Promise((resolve) => setTimeout(resolve, 50));
            const results = await axe.run(container, {});
            expect(
                results.violations,
                `${name} screen: ` + results.violations.map((v) =>
                    `${v.id} (${v.nodes.length} nodes): ${v.help}`).join("; "),
            ).toHaveLength(0);
            scanned.push(name);
        }
        // exhaustive: every screen the shell renders was scanned
        expect(scanned).toEqual(screens);
        unmount();
    });
});
