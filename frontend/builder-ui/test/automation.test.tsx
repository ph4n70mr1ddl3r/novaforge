import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { createElement } from "react";
import type { AppDefinition, PlatformClient } from "@novaforge/shared";
import { Automation } from "../src/automation.tsx";

/**
 * The PHASE-4 §11 authoring surfaces: the state-machine designer over the §3
 * schema (stateField on an enum field, terminal flags, guarded transitions), the
 * §6 SLA editor, §7 scheduled-job authoring, and the read-only scheduler status.
 */

const app: AppDefinition = {
    apiName: "Purch",
    entities: [
        {
            apiName: "PurchaseOrder",
            fields: [
                { apiName: "reference", type: "text" },
                { apiName: "status", type: "enum", values: ["DRAFT", "SUBMITTED", "APPROVED", "POSTED"] },
            ],
            relationships: [],
            validations: [],
            hooks: [],
            indexes: [],
        },
    ],
    stateMachines: [],
    permissionSet: { roles: [], objectPermissions: [], fieldSecurity: [] },
    pages: [],
    reports: [],
    dashboards: [],
    translations: [],
};

const stubClient = (jobs: Record<string, unknown>[]): PlatformClient =>
    ({ schedulerJobs: async () => jobs }) as unknown as PlatformClient;

describe("Automation (PHASE-4 §11)", () => {
    it("designs a state machine over the §3 schema and saves the branch", async () => {
        const onSave = vi.fn(async (_patch: Record<string, unknown>) => {});
        render(createElement(Automation, { app, client: stubClient([]), onSave }));

        // the entity (it has an enum field) joins the machine candidates
        fireEvent.submit(screen.getByLabelText("Add machine for entity").closest("form")!);
        expect(screen.getByLabelText("Machine id sm_purchaseorder")).toBeTruthy();

        // states: name them and mark the terminal one
        fireEvent.change(screen.getByLabelText("State name sm_purchaseorder 0"), { target: { value: "DRAFT" } });
        fireEvent.click(screen.getByText("Add state"));
        fireEvent.change(screen.getByLabelText("State name sm_purchaseorder 1"), { target: { value: "POSTED" } });
        fireEvent.click(screen.getByLabelText("State terminal sm_purchaseorder POSTED"));

        // initial state binds to the listed states
        fireEvent.change(screen.getByLabelText("Initial state sm_purchaseorder"), { target: { value: "DRAFT" } });

        // a guarded transition — the designer's compile-checked slot
        fireEvent.click(screen.getByText("Add transition"));
        fireEvent.change(screen.getByLabelText("Transition from sm_purchaseorder 0"), { target: { value: "DRAFT" } });
        fireEvent.change(screen.getByLabelText("Transition to sm_purchaseorder 0"), { target: { value: "POSTED" } });
        fireEvent.change(screen.getByLabelText("Transition guard sm_purchaseorder 0"), {
            target: { value: "lines.size() > 0" },
        });

        fireEvent.click(screen.getByText("Save state machines"));
        await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
        expect(onSave.mock.calls[0]![0]).toEqual({
            stateMachines: [{
                id: "sm_purchaseorder",
                entity: "PurchaseOrder",
                stateField: "status",
                initial: "DRAFT",
                states: [{ name: "DRAFT" }, { name: "POSTED", terminal: true }],
                transitions: [{ from: "DRAFT", to: "POSTED", guard: "lines.size() > 0" }],
            }],
        });
    });

    it("authors an SLA definition — the governed overlay of §6", async () => {
        const onSave = vi.fn(async (_patch: Record<string, unknown>) => {});
        render(createElement(Automation, { app, client: stubClient([]), onSave }));

        fireEvent.click(screen.getByText("Add SLA"));
        fireEvent.change(screen.getByLabelText("SLA id 0"), { target: { value: "sla_po_approval" } });
        fireEvent.change(screen.getByLabelText("SLA task type 0"), { target: { value: "approval" } });
        fireEvent.change(screen.getByLabelText("SLA match 0"), {
            target: { value: "entity == 'Purch.PurchaseOrder'" },
        });
        fireEvent.change(screen.getByLabelText("SLA target 0"), { target: { value: "PT24H" } });
        fireEvent.change(screen.getByLabelText("SLA warnAt 0"), { target: { value: "0.8" } });
        fireEvent.change(screen.getByLabelText("SLA escalateTo 0"), {
            target: { value: "role:Purch.seniorManager" },
        });

        fireEvent.click(screen.getByText("Save SLAs"));
        await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
        expect(onSave.mock.calls[0]![0]).toEqual({
            slas: [{
                id: "sla_po_approval",
                scope: { taskType: "approval", match: "entity == 'Purch.PurchaseOrder'" },
                target: "PT24H",
                warnAt: 0.8,
                onBreach: { escalateTo: "role:Purch.seniorManager" },
            }],
        });
    });

    it("authors a scheduled job — definitions are metadata, the registry is not (§7)", async () => {
        const onSave = vi.fn(async (_patch: Record<string, unknown>) => {});
        render(createElement(Automation, { app, client: stubClient([]), onSave }));

        fireEvent.click(screen.getByText("Add job"));
        fireEvent.change(screen.getByLabelText("Job name 0"), { target: { value: "nightlySweep" } });
        fireEvent.change(screen.getByLabelText("Job cron 0"), { target: { value: "0 2 * * *" } });
        fireEvent.change(screen.getByLabelText("Job target 0"), { target: { value: "flow" } });
        fireEvent.change(screen.getByLabelText("Job params 0"), {
            target: { value: '{"hook": "sweep"}' },
        });

        fireEvent.click(screen.getByText("Save scheduled jobs"));
        await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
        expect(onSave.mock.calls[0]![0]).toEqual({
            jobs: [{ name: "nightlySweep", cron: "0 2 * * *", target: "flow", params: { hook: "sweep" } }],
        });
    });

    it("renders the read-only scheduler registry from the status route", async () => {
        render(createElement(Automation, {
            app,
            client: stubClient([
                { app: "Purch", name: "nightlySweep", cron: "0 2 * * *", next_fire_at: "2026-08-25T02:00:00Z", last_status: "ok" },
            ]),
            onSave: async () => {},
        }));
        await waitFor(() => expect(screen.getByText("nightlySweep")).toBeTruthy());
        expect(screen.getByText("0 2 * * *")).toBeTruthy();
        expect(screen.getByText("ok")).toBeTruthy();
    });

    it("surfaces branch save rejection verbatim (the save-time compile checks)", async () => {
        const onSave = vi.fn(async () => {
            throw new Error("state machine sm_purchaseorder: stateField must be an enum field");
        });
        render(createElement(Automation, { app, client: stubClient([]), onSave }));
        fireEvent.submit(screen.getByLabelText("Add machine for entity").closest("form")!);
        fireEvent.click(screen.getByText("Save state machines"));
        await waitFor(() =>
            expect(screen.getByRole("alert").textContent).toContain("stateField must be an enum field"),
        );
    });
});
