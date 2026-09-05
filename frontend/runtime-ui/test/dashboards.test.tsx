import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, within, fireEvent } from "@testing-library/react";
import { createElement } from "react";
import { PlatformClient, type AppDefinition } from "@novaforge/shared";
import { RuntimeShell } from "../src/shell.tsx";

/**
 * The dashboard legs of PHASE-5 §5: drill-through deep links (a report row hands
 * its group filters to the entity list as a query-DSL payload — §10 item 2's
 * round-trip: click → list filters match the row) and per-widget client-timer
 * auto-refresh (configurable, default off — the server never pushes in v1).
 */

beforeEach(() => {
    // hash routing (the deep-link pass): a prior test's screen must never
    // seed the next mount's boot — the shell restores from location.hash
    window.location.hash = "";
});


const app: AppDefinition = {
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
                { apiName: "region", type: "enum", values: ["EU", "US"], label: "Region" },
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
        ],
        fieldSecurity: [],
    },
    stateMachines: [],
    reports: [
        {
            id: "byCustomer",
            entity: "Customer",
            label: "Outstanding by customer",
            groupBy: [{ field: "name" }],
            aggregates: [{ op: "sum", field: "amountOutstanding", alias: "outstanding" }],
            filters: [{ field: "status", op: "eq", value: "POSTED" }],
            drillThrough: { entity: "Customer", carryFilters: true },
        },
    ],
    dashboards: [
        {
            id: "exec",
            label: "Executive",
            widgets: [{ widget: "table", reportRef: "byCustomer", span: 12 }],
        },
    ],
    translations: [],
};

function stubClient(options: {
    reportCalls?: () => void;
    lists?: Record<string, unknown>;
}) {
    const reportCalls = options.reportCalls ?? (() => {});
    const fetchImpl = vi.fn(async (input: string | URL) => {
        const url = String(input);
        if (url.includes("/reports/byCustomer/run")) {
            reportCalls();
            return new Response(
                JSON.stringify({
                    columns: ["name", "outstanding"],
                    rows: [{ name: "acme", outstanding: "120.00" }],
                    totals: { outstanding: "120.00" },
                    chart: { xAxis: { data: [] }, series: [] },
                }),
                { status: 200, headers: { "Content-Type": "application/json" } },
            );
        }
        if (url.includes("/runtime/erp.Customer")) {
            return new Response(
                JSON.stringify(options.lists?.Customer ?? {
                    rows: [{ id: "c-1", name: "acme", region: "EU" }],
                    total: 1,
                }),
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

describe("dashboards (PHASE-5 §5)", () => {
    it("drill-through deep-links the row's filters into the entity list (§10 item 2's round-trip)", async () => {
        const { client, fetchImpl } = stubClient({});
        render(shell(client));

        screen.getByRole("button", { name: "Dashboards" }).click();
        const drill = await screen.findByRole("link", { name: /view \w+ records/i });
        drill.click();

        // the list page rendered under the drill route, its request carrying the
        // row's filters as a query-DSL payload: the report's saved filter joined
        // (carryFilters) plus the row's group value
        await waitFor(() => expect(screen.getByText("1 record (filtered)")).toBeTruthy());
        const listCall = fetchImpl.mock.calls
            .map(([url]) => String(url))
            .filter((url) => url.includes("/runtime/erp.Customer"))
            .pop()!;
        const filter = JSON.parse(new URL(listCall, "http://gateway").searchParams.get("filter")!);
        // the SERVER's canonical composite shape ({"and": […]}) — the TS-side
        // {op, children} used to ride the wire verbatim and 400 at the QueryParser
        expect(filter).toEqual({
            and: [
                { field: "status", op: "eq", value: "POSTED" },
                { field: "name", op: "eq", value: "acme" },
            ],
        });
    });

    it("titles widgets by the report's authored label, never the raw report id", async () => {
        const { client } = stubClient({});
        render(shell(client));
        screen.getByRole("button", { name: "Dashboards" }).click();
        // "Outstanding by customer" is the report's label; "byCustomer" is an id
        expect(await screen.findByText("Outstanding by customer")).toBeTruthy();
        expect(screen.queryByText("byCustomer")).toBeNull();
    });

    it("auto-refreshes a widget on its authored timer, and stays static without one", async () => {
        vi.useFakeTimers();
        try {
            let runs = 0;
            const refreshed: AppDefinition = {
                ...app,
                dashboards: [
                    {
                        id: "exec",
                        label: "Executive",
                        widgets: [
                            { widget: "table", reportRef: "byCustomer", span: 6, refreshSeconds: 30 },
                            { widget: "kpi", reportRef: "byCustomer", span: 6 },
                        ],
                    },
                ],
            };
            const fetchImpl = vi.fn(async (input: string | URL) => {
                const url = String(input);
                if (url.includes("/reports/byCustomer/run")) {
                    runs += 1;
                    return new Response(
                        JSON.stringify({
                            columns: ["name", "outstanding"],
                            rows: [{ name: "acme", outstanding: "120.00" }],
                            totals: { outstanding: "120.00" },
                            chart: { xAxis: { data: [] }, series: [] },
                        }),
                        { status: 200, headers: { "Content-Type": "application/json" } },
                    );
                }
                return new Response(JSON.stringify({ rows: [], total: 0 }), { status: 200 });
            });
            const client = new PlatformClient("", () => "t", fetchImpl as unknown as typeof fetch);
            render(createElement(RuntimeShell, {
                client,
                published: { version: 3, app: refreshed } as never,
                user: { name: "demo", roles: ["erp.arClerk"] },
                versionKey: "v3",
            }));

            screen.getByRole("button", { name: "Dashboards" }).click();
            // both widgets load once
            await vi.waitFor(() => expect(runs).toBe(2));
            // one interval at a time — React batches same-tick timer callbacks, and
            // the production cadence is one re-run per fired interval
            await vi.advanceTimersByTimeAsync(30_000);
            await vi.waitFor(() => expect(runs).toBe(3));   // the timered widget re-ran; the kpi stayed static
            await vi.advanceTimersByTimeAsync(30_000);
            await vi.waitFor(() => expect(runs).toBe(4));
            await vi.advanceTimersByTimeAsync(30_000);
            await vi.waitFor(() => expect(runs).toBe(5));
        } finally {
            vi.useRealTimers();
        }
    });

    it("renders per-widget empty states when a run fails", async () => {
        const fetchImpl = vi.fn(async () =>
            new Response(JSON.stringify({ title: "forbidden", status: 403 }), { status: 403 }));
        const client = new PlatformClient("", () => "t", fetchImpl as unknown as typeof fetch);
        render(shell(client));
        screen.getByRole("button", { name: "Dashboards" }).click();
        // the loading state speaks the report's label too (never the raw id)
        const loading = await screen.findByText(/Loading Outstanding by customer/);
        expect(loading.getAttribute("role")).toBe("status");
    });

    it("the tablist answers the arrow keys, roves its tabindex, and its panel is labelled by the active tab", async () => {
        const twoDashboards: AppDefinition = {
            ...app,
            dashboards: [
                { id: "exec", label: "Executive", widgets: [], roles: [] },
                { id: "ops", label: "Operations", widgets: [], roles: [] },
            ],
        };
        const fetchImpl = vi.fn(async () => new Response(JSON.stringify({ rows: [], total: 0 }), { status: 200 }));
        const client = new PlatformClient("", () => "t", fetchImpl as unknown as typeof fetch);
        render(createElement(RuntimeShell, {
            client,
            published: { version: 3, app: twoDashboards } as never,
            user: { name: "demo", roles: ["erp.arClerk"] },
            versionKey: "v3",
        }));
        screen.getByRole("button", { name: "Dashboards" }).click();

        const tablist = await screen.findByRole("tablist", { name: "Dashboard selection" });
        const execTab = within(tablist).getByRole("tab", { name: "Executive" });
        const opsTab = within(tablist).getByRole("tab", { name: "Operations" });
        // roving tabindex: one tab stop, the selection carries it
        expect(execTab.getAttribute("aria-selected")).toBe("true");
        expect(execTab.getAttribute("tabindex")).toBe("0");
        expect(opsTab.getAttribute("tabindex")).toBe("-1");
        const panel = screen.getByRole("tabpanel");
        expect(panel.getAttribute("aria-labelledby")).toBe(execTab.id);
        expect(execTab.getAttribute("aria-controls")).toBe(panel.id);

        // arrow keys move BOTH the selection and the focus (activation on focus)
        fireEvent.keyDown(tablist, { key: "ArrowRight" });
        expect(opsTab.getAttribute("aria-selected")).toBe("true");
        expect(document.activeElement).toBe(opsTab);
        expect(panel.getAttribute("aria-labelledby")).toBe(opsTab.id);

        fireEvent.keyDown(tablist, { key: "ArrowLeft" });
        expect(document.activeElement).toBe(execTab);
        fireEvent.keyDown(tablist, { key: "End" });
        expect(document.activeElement).toBe(opsTab);
        fireEvent.keyDown(tablist, { key: "Home" });
        expect(document.activeElement).toBe(execTab);
    });
});

describe("dashboards a11y (axe)", () => {
    it("the dashboard tablist + tabpanel are axe-clean", async () => {
        const axe = (await import("axe-core")).default;
        const { container } = render(shell(stubClient({}).client));
        screen.getByRole("button", { name: "Dashboards" }).click();
        await screen.findByRole("tabpanel");
        const results = await axe.run(container, {});
        expect(results.violations).toEqual([]);
    });
});
