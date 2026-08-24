import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { createElement } from "react";
import type { AppDefinition, DashboardDefinition, ReportDefinition } from "@novaforge/shared";
import { ReportBuilder } from "../src/report-builder.tsx";
import { DashboardComposer } from "../src/dashboard-composer.tsx";

/**
 * The report builder + dashboard composer (PHASE-5 T6): authoring surfaces over
 * the metadata APIs, riding the Phase 2 builder shell — live bucket-expression
 * compile-checks through the shared TS engine; widget grids bound to report refs.
 */

const app: AppDefinition = {
    apiName: "Erp",
    id: "app-1",
    entities: [
        {
            apiName: "Invoice",
            label: "Invoice",
            fields: [
                { apiName: "dueDate", type: "date" },
                { apiName: "customer", type: "lookup", target: "Customer" },
                { apiName: "amountOutstanding", type: "money", currency: "EUR" },
            ],
            relationships: [],
            validations: [],
            hooks: [],
            indexes: [],
        },
    ],
    pages: [],
    permissionSet: {
        roles: [
            { name: "controller", description: "" },
            { name: "reporting", description: "" },
        ],
        objectPermissions: [],
        fieldSecurity: [],
    },
    stateMachines: [],
    reports: [
        {
            id: "arAging",
            entity: "Invoice",
            label: "A/R Aging",
            aggregates: [{ op: "sum", field: "amountOutstanding", alias: "outstanding" }],
            groupBy: [
                {
                    field: "dueDate",
                    buckets: [
                        { label: "0-30", expression: "today() - dueDate <= 30" },
                        { label: "31-60", expression: "today() - dueDate > 30 && today() - dueDate <= 60" },
                    ],
                },
            ],
            filters: [],
        },
    ],
    dashboards: [{ id: "exec", label: "Executive", widgets: [{ widget: "table", reportRef: "arAging", span: 12 }], roles: [] }],
    translations: [],
};

describe("ReportBuilder (PHASE-5 T6)", () => {
    it("shows the live compile-check green for the authored aging buckets", () => {
        render(createElement(ReportBuilder, { app, saveReports: async () => {} }));
        expect(screen.getByText("Compile-check green")).toBeTruthy();
    });

    it("rejects a bucket expression that does not compile — live, before save", async () => {
        const saveReports = vi.fn<(reports: ReportDefinition[]) => Promise<void>>(async () => {});
        render(createElement(ReportBuilder, { app, saveReports }));
        fireEvent.change(screen.getByLabelText("bucket expression 0.0"), {
            target: { value: "ghostField > 1" },
        });
        await waitFor(() => expect(screen.getByRole("alert")).toBeTruthy());
        fireEvent.click(screen.getByRole("button", { name: "Save report" }));
        expect(saveReports).not.toHaveBeenCalled();
    });

    it("saves the edited report through the metadata app patch", async () => {
        const saveReports = vi.fn<(reports: ReportDefinition[]) => Promise<void>>(async () => {});
        render(createElement(ReportBuilder, { app, saveReports }));
        fireEvent.change(screen.getByLabelText("aggregate alias 0"), { target: { value: "outstandingEur" } });
        fireEvent.click(screen.getByRole("button", { name: "Save report" }));
        await waitFor(() => expect(saveReports).toHaveBeenCalledTimes(1));
        const saved = saveReports.mock.calls[0]![0][0] as ReportDefinition;
        expect(saved.aggregates![0]!.alias).toBe("outstandingEur");
    });
});

describe("DashboardComposer (PHASE-5 T6)", () => {
    it("composes widgets bound to report refs and saves", async () => {
        const saveDashboards = vi.fn<(dashboards: DashboardDefinition[]) => Promise<void>>(async () => {});
        render(createElement(DashboardComposer, { app, saveDashboards }));
        fireEvent.click(screen.getByRole("button", { name: "Add widget" }));
        await waitFor(() => expect(saveDashboards).toHaveBeenCalledTimes(1));
        const saved = saveDashboards.mock.calls[0]![0][0] as DashboardDefinition;
        expect(saved.widgets).toHaveLength(2); // existing + the added kpi
        expect(saved.widgets[1]).toMatchObject({ widget: "kpi", reportRef: "arAging", span: 4 });
    });

    it("edits role visibility composition", async () => {
        const saveDashboards = vi.fn<(dashboards: DashboardDefinition[]) => Promise<void>>(async () => {});
        render(createElement(DashboardComposer, { app, saveDashboards }));
        fireEvent.click(screen.getByRole("checkbox", { name: "reporting" }));
        await waitFor(() => expect(saveDashboards).toHaveBeenCalled());
        const saved = saveDashboards.mock.calls[0]![0][0] as DashboardDefinition;
        expect(saved.roles).toEqual(["controller"]);
    });
});
