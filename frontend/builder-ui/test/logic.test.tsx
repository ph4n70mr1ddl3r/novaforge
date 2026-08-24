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
});
