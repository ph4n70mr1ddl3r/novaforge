import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { createElement } from "react";
import type { EntityDefinition, TestSuiteDefinition } from "@novaforge/shared";
import { LogicEditor } from "../src/logic-editor.tsx";
import { SuitesEditor } from "../src/suites-editor.tsx";

/**
 * The PHASE-3 §8 authoring surfaces: validation/hook/expression editors (T8's
 * "rules as trigger + step-list forms") and the suite editor + runner (§7/§8 —
 * the exit suite authored without hand-written JSON).
 */

const entity: EntityDefinition = {
    apiName: "JournalEntry",
    label: "Journal Entry",
    fields: [
        { apiName: "reference", type: "text" },
        { apiName: "totalDebit", type: "money", currency: "EUR" },
        { apiName: "totalCredit", type: "money", currency: "EUR" },
    ],
    relationships: [],
    validations: [],
    hooks: [
        {
            name: "stampLabel",
            trigger: "beforeSave",
            flow: {
                id: "stamp",
                op: "setField",
                params: { field: "reference", expression: "'JE'" },
            },
        },
    ],
    indexes: [],
};

const app = { entities: [entity] };

describe("LogicEditor (PHASE-3 §8)", () => {
    it("round-trips a validation rule and the hook step list through save", async () => {
        const onSaveEntity = vi.fn(async (_entity: EntityDefinition) => {});
        render(createElement(LogicEditor, { app, onSaveEntity }));

        // add a validation rule
        fireEvent.click(screen.getByText("Add validation rule"));
        fireEvent.change(screen.getByLabelText("Rule name 0"), { target: { value: "balanced" } });
        fireEvent.change(screen.getByLabelText("Rule expression 0"), {
            target: { value: "totalDebit == totalCredit" },
        });
        fireEvent.change(screen.getByLabelText("Rule message 0"), {
            target: { value: "entry must balance" },
        });

        // the existing hook flattens into the step-list form
        expect((screen.getByLabelText("Step id 0") as HTMLInputElement).value).toBe("stamp");
        expect((screen.getByLabelText("Step op 0") as HTMLInputElement).value).toBe("setField");

        // extend the chain: a second step auto-links via next
        fireEvent.click(screen.getByText("Add hook"));
        fireEvent.change(screen.getByLabelText("Hook name 1"), { target: { value: "notify" } });

        fireEvent.click(screen.getByText("Save logic"));
        await waitFor(() => expect(onSaveEntity).toHaveBeenCalledTimes(1));
        const saved = onSaveEntity.mock.calls[0]![0] as EntityDefinition;
        expect(saved.validations).toEqual([
            { name: "balanced", scope: "record", expression: "totalDebit == totalCredit", message: "entry must balance" },
        ]);
        expect(saved.hooks[0]!.flow).toEqual({
            id: "stamp",
            op: "setField",
            params: { field: "reference", expression: "'JE'" },
            next: undefined,
        });
        expect(saved.hooks[1]).toMatchObject({ name: "notify", trigger: "afterSave" });
    });

    it("edits formula and roll-up slots beside their fields", async () => {
        const onSaveEntity = vi.fn(async (_entity: EntityDefinition) => {});
        render(createElement(LogicEditor, { app, onSaveEntity }));
        fireEvent.change(screen.getByLabelText("Rollup for totalDebit"), {
            target: { value: "SUM(lines.debit)" },
        });
        fireEvent.click(screen.getByText("Save logic"));
        await waitFor(() => expect(onSaveEntity).toHaveBeenCalledTimes(1));
        const saved = onSaveEntity.mock.calls[0]![0] as EntityDefinition;
        expect(saved.fields.find((field) => field.apiName === "totalDebit")?.rollup).toBe("SUM(lines.debit)");
    });

    it("configures requestApproval steps through the guided §4 param set (PHASE-4 §11)", async () => {
        const onSaveEntity = vi.fn(async (_entity: EntityDefinition) => {});
        render(createElement(LogicEditor, { app, onSaveEntity }));

        // the existing hook's step becomes an approval request — the op swap turns
        // the raw params JSON into the guided fields (the stale setField params ride
        // the "other params" slot, cleared here)
        fireEvent.change(screen.getByLabelText("Step id 0"), { target: { value: "approve" } });
        fireEvent.change(screen.getByLabelText("Step op 0"), { target: { value: "requestApproval" } });
        fireEvent.change(screen.getByLabelText("Step other params 0"), { target: { value: "" } });
        fireEvent.blur(screen.getByLabelText("Step other params 0"));

        // the guided fields replace the raw params JSON for the op (text slots commit on blur)
        const fill = (label: string, value: string): void => {
            fireEvent.change(screen.getByLabelText(label), { target: { value } });
            fireEvent.blur(screen.getByLabelText(label));
        };
        fill("Approvers role 0", "Purch.manager");
        fireEvent.change(screen.getByLabelText("Approval mode 0"), { target: { value: "all" } });
        fill("Approval timeout 0", "PT24H");
        fill("Approval escalateTo 0", "role:Purch.seniorManager");

        fireEvent.click(screen.getByText("Save logic"));
        await waitFor(() => expect(onSaveEntity).toHaveBeenCalledTimes(1));
        const saved = onSaveEntity.mock.calls[0]![0] as EntityDefinition;
        expect(saved.hooks[0]!.flow).toEqual({
            id: "approve",
            op: "requestApproval",
            params: {
                approvers: "Purch.manager",
                mode: "all",
                timeout: "PT24H",
                escalateTo: "role:Purch.seniorManager",
            },
            next: undefined,
        });
    });

    it("writes the user-list form of approvers when UUIDs are given", async () => {
        const onSaveEntity = vi.fn(async (_entity: EntityDefinition) => {});
        render(createElement(LogicEditor, { app, onSaveEntity }));

        fireEvent.change(screen.getByLabelText("Step id 0"), { target: { value: "approve" } });
        fireEvent.change(screen.getByLabelText("Step op 0"), { target: { value: "requestApproval" } });
        fireEvent.change(screen.getByLabelText("Step other params 0"), { target: { value: "" } });
        fireEvent.blur(screen.getByLabelText("Step other params 0"));

        const fill = (label: string, value: string): void => {
            fireEvent.change(screen.getByLabelText(label), { target: { value } });
            fireEvent.blur(screen.getByLabelText(label));
        };
        fill("Approvers role 0", "Purch.manager");
        fill("Approver users 0", "11111111-1111-4111-8111-111111111111, 22222222-2222-4222-8222-222222222222");

        fireEvent.click(screen.getByText("Save logic"));
        await waitFor(() => expect(onSaveEntity).toHaveBeenCalledTimes(1));
        const saved = onSaveEntity.mock.calls[0]![0] as EntityDefinition;
        // the user list wins over the role text — one `approvers` param, the shape
        // the engine reads (§4)
        expect((saved.hooks[0]!.flow!.params as Record<string, unknown>).approvers).toEqual([
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
        ]);
    });

    it("keeps typing through an incomplete params literal — the text stays, the model lags (re-audit)", async () => {
        // The step rows are re-derived from the flow graph on every keystroke; the
        // old controlled input re-serialized the FAILED parse as {} and the box
        // snapped back to empty — `{"x": 1` could never be typed character by character
        const onSaveEntity = vi.fn(async (_entity: EntityDefinition) => {});
        render(createElement(LogicEditor, { app, onSaveEntity }));

        const input = screen.getByLabelText("Step params 0") as HTMLInputElement;
        expect(input.value).toBe(`{"field":"reference","expression":"'JE'"}`);
        fireEvent.change(input, { target: { value: '{"x": 1' } });
        // the keystroke SURVIVES; nothing commits
        expect(input.value).toBe('{"x": 1');
        fireEvent.click(screen.getByText("Save logic"));
        await waitFor(() => expect(onSaveEntity).toHaveBeenCalledTimes(1));
        expect(onSaveEntity.mock.calls[0]![0].hooks[0]!.flow!.params)
            .toEqual({ field: "reference", expression: "'JE'" });

        // completing the literal commits it
        fireEvent.change(input, { target: { value: '{"x": 1}' } });
        fireEvent.click(screen.getByText("Save logic"));
        await waitFor(() => expect(onSaveEntity).toHaveBeenCalledTimes(2));
        expect(onSaveEntity.mock.calls[1]![0].hooks[0]!.flow!.params).toEqual({ x: 1 });
    });

    it("a double-clicked Save fires exactly one putEntity (re-entry fence, re-audit)", async () => {
        // Anti-regression: the shell never threaded busy here and save had no
        // in-flight ref — a fast double-click double-fired the versioned PUT
        let release!: (value: unknown) => void;
        const gate = new Promise((resolve) => {
            release = resolve;
        });
        const onSaveEntity = vi.fn(async (_entity: EntityDefinition): Promise<void> => {
            await gate;
        });
        const first = render(createElement(LogicEditor, { app, onSaveEntity }));

        const button = first.getByText("Save logic") as HTMLButtonElement;
        button.click();
        button.click(); // the double-click — must not re-enter
        expect(onSaveEntity).toHaveBeenCalledTimes(1);
        release({});
        await waitFor(() => expect(onSaveEntity).toHaveBeenCalledTimes(1));
        first.unmount();

        // and the shell-threaded busy disables the buttons once a save is flighted
        const busyRender = render(createElement(LogicEditor, {
            app,
            busy: true,
            onSaveEntity: async () => {},
        }));
        expect((busyRender.getByText("Save logic") as HTMLButtonElement).disabled).toBe(true);
        busyRender.unmount();
    });
});

describe("SuitesEditor (PHASE-3 §7/§8)", () => {
    const suiteApp = {
        entities: [{ apiName: "Order" }],
        testSuites: [
            {
                apiName: "order_total",
                label: "order totals roll up",
                cases: [
                    {
                        name: "rolls_up_lines",
                        fixtures: [{ entity: "Order", template: { name: "Acme" } }],
                        steps: [
                            { op: "createRecord", entity: "Order", asRole: "clerk", expect: "ok" },
                        ],
                        assertExpressions: ["${Order[0].total} == 50.00"],
                    },
                ],
            } satisfies TestSuiteDefinition,
        ],
    };

    it("authors a suite through the forms and saves over the PUT API shape", async () => {
        const onSaveSuite = vi.fn(async (suite: TestSuiteDefinition) => {});
        render(createElement(SuitesEditor, {
            app: suiteApp,
            onSaveSuite,
            onRunSuite: async () => ({}),
        }));
        fireEvent.click(screen.getByRole("button", { name: "order totals roll up" }));

        // edit the assertion
        fireEvent.change(screen.getByLabelText("Assertion 0"), {
            target: { value: "${Order[0].total} == 60.00" },
        });
        fireEvent.click(screen.getByText("Save suite"));
        await waitFor(() => expect(onSaveSuite).toHaveBeenCalledTimes(1));
        const saved = onSaveSuite.mock.calls[0]![0];
        expect(saved.apiName).toBe("order_total");
        expect(saved.cases[0]!.assertExpressions).toEqual(["${Order[0].total} == 60.00"]);
        expect(saved.cases[0]!.fixtures[0]!.template).toEqual({ name: "Acme" });
    });

    it("runs a suite through the runner and renders the artifact verdicts", async () => {
        const runResult = {
            green: true,
            results: [{ case: "rolls_up_lines", green: true }],
        };
        const onRunSuite = vi.fn(async (_apiName: string) => runResult);
        render(createElement(SuitesEditor, { app: suiteApp, onSaveSuite: async () => {}, onRunSuite }));
        fireEvent.click(screen.getByRole("button", { name: "order totals roll up" }));
        fireEvent.click(screen.getByText("Run suite"));
        await waitFor(() => expect(onRunSuite).toHaveBeenCalledWith("order_total"));
        expect(screen.getByText("GREEN").getAttribute("data-green")).toBe("true");
    });

    it("surfaces save-validation rejection verbatim (the compile-check guidance)", async () => {
        const onSaveSuite = vi.fn(async () => {
            throw new Error('unknown op "explodeRecord" — the v1 vocabulary is createRecord…');
        });
        render(createElement(SuitesEditor, {
            app: suiteApp,
            onSaveSuite,
            onRunSuite: async () => ({}),
        }));
        fireEvent.click(screen.getByRole("button", { name: "order totals roll up" }));
        fireEvent.click(screen.getByText("Save suite"));
        await waitFor(() =>
            expect(screen.getByRole("alert").textContent).toContain("unknown op"),
        );
    });

    it("keeps typing through an incomplete fixture/step template — the text stays, the model lags (re-audit)", async () => {
        // The old controlled inputs bound JSON.stringify(template) and silently
        // dropped unparseable edits — the re-render snapped the box back to the
        // last committed template, so `{` could never be typed character by character
        const onSaveSuite = vi.fn(async (suite: TestSuiteDefinition) => {});
        render(createElement(SuitesEditor, {
            app: suiteApp,
            onSaveSuite,
            onRunSuite: async () => ({}),
        }));
        fireEvent.click(screen.getByRole("button", { name: "order totals roll up" }));

        const fixtureBox = screen.getByLabelText("Fixture template 0") as HTMLInputElement;
        expect(fixtureBox.value).toBe('{"name":"Acme"}');
        fireEvent.change(fixtureBox, { target: { value: '{"name": "A' } });
        expect(fixtureBox.value).toBe('{"name": "A'); // the keystroke SURVIVES

        const stepBox = screen.getByLabelText("Step template row 0") as HTMLInputElement;
        expect(stepBox.value).toBe(""); // the step authors no template yet
        fireEvent.change(stepBox, { target: { value: "{" } });
        expect(stepBox.value).toBe("{");

        // saving mid-lag commits the LAST parseable templates — never {} and never a wipe
        fireEvent.click(screen.getByText("Save suite"));
        await waitFor(() => expect(onSaveSuite).toHaveBeenCalledTimes(1));
        const saved = onSaveSuite.mock.calls[0]![0];
        expect(saved.cases[0]!.fixtures[0]!.template).toEqual({ name: "Acme" });
        expect(saved.cases[0]!.steps[0]!.template).toBeUndefined();

        // completing each literal commits it
        fireEvent.change(fixtureBox, { target: { value: '{"name":"Acme Corp"}' } });
        fireEvent.change(stepBox, { target: { value: '{"total": 60}' } });
        fireEvent.click(screen.getByText("Save suite"));
        await waitFor(() => expect(onSaveSuite).toHaveBeenCalledTimes(2));
        const committed = onSaveSuite.mock.calls[1]![0];
        expect(committed.cases[0]!.fixtures[0]!.template).toEqual({ name: "Acme Corp" });
        expect(committed.cases[0]!.steps[0]!.template).toEqual({ total: 60 });
    });

    it("double-clicked Save/Run fire exactly one putSuite and one runSuite (re-entry fences, re-audit)", async () => {
        // Anti-regression: the shell never threaded busy here and save/run had no
        // in-flight refs — fast double-clicks double-fired both legs
        const onSaveSuite = vi.fn(async (_suite: TestSuiteDefinition) => {});
        const onRunSuite = vi.fn(async (_apiName: string) => ({ green: true, results: [] }));
        render(createElement(SuitesEditor, {
            app: suiteApp,
            onSaveSuite,
            onRunSuite,
        }));
        fireEvent.click(screen.getByRole("button", { name: "order totals roll up" }));

        const save = screen.getByText("Save suite") as HTMLButtonElement;
        save.click();
        save.click(); // the double-click — must not re-enter
        await waitFor(() => expect(onSaveSuite).toHaveBeenCalledTimes(1));

        const run = screen.getByText("Run suite") as HTMLButtonElement;
        run.click();
        run.click(); // the double-click — must not re-enter
        await waitFor(() => expect(onRunSuite).toHaveBeenCalledTimes(1));
        expect(onRunSuite).toHaveBeenCalledWith("order_total");
    });
});
