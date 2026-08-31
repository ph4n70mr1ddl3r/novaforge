import { useRef, useState, type ReactNode } from "react";
import {
    SUITE_OPS,
    type SuiteCase,
    type SuiteFixture,
    type SuiteStep,
    type TestSuiteDefinition,
} from "@novaforge/shared";
import { JsonTextField } from "./json-field.tsx";

/**
 * The suite authoring + runner surface (PHASE-3 §7/§8, ADR-010): fixture/step/
 * assertion editors over the pinned §7 encoding, the run button riding the
 * Metadata Service's scratch-tenant runner, and the run artifact rendered beside
 * the editor — "the exit suite is authored without hand-written JSON" (T8).
 */

export interface SuitesEditorProps {
    app: { testSuites?: TestSuiteDefinition[]; entities: { apiName: string }[] };
    busy?: boolean;
    onSaveSuite: (suite: TestSuiteDefinition) => Promise<void>;
    onRunSuite: (apiName: string) => Promise<unknown>;
}

export function SuitesEditor({ app, busy, onSaveSuite, onRunSuite }: SuitesEditorProps): ReactNode {
    const suites = app.testSuites ?? [];
    const [selected, setSelected] = useState<string>(suites[0]?.apiName ?? "");
    const [draft, setDraft] = useState<TestSuiteDefinition | null>(
        suites.find((suite) => suite.apiName === selected) ?? null,
    );
    const [error, setError] = useState<string | null>(null);
    const [flash, setFlash] = useState<string | null>(null);
    const [runResult, setRunResult] = useState<RunView | null>(null);
    // double-submit fences (the runtime form's rule): the shell's busy prop only
    // arrives after the async re-render — a fast second click re-entered here
    // and double-fired the versioned putSuite / the scratch-tenant runSuite
    const savingRef = useRef(false);
    const runningRef = useRef(false);

    interface RunView {
        green?: boolean;
        results?: { case: string; green: boolean; detail?: string }[];
    }

    const pick = (apiName: string): void => {
        setSelected(apiName);
        setDraft(suites.find((suite) => suite.apiName === apiName) ?? null);
        setRunResult(null);
        setError(null);
    };

    const save = async (): Promise<void> => {
        if (!draft || savingRef.current) return;
        savingRef.current = true;
        setError(null);
        try {
            await onSaveSuite(draft);
            setFlash(`Saved suite ${draft.apiName}`);
        } catch (caught) {
            // Save validation rejects unknown ops, malformed expectations, ghost
            // entity refs — surface the compiler's guidance verbatim (§7).
            setError(caught instanceof Error ? caught.message : String(caught));
        } finally {
            savingRef.current = false;
        }
    };

    const run = async (): Promise<void> => {
        if (!draft || runningRef.current) return;
        runningRef.current = true;
        setError(null);
        setRunResult(null);
        try {
            const result = (await onRunSuite(draft.apiName)) as RunView;
            setRunResult(result);
        } catch (caught) {
            setError(caught instanceof Error ? caught.message : String(caught));
        } finally {
            runningRef.current = false;
        }
    };

    const patch = (changes: Partial<TestSuiteDefinition>): void =>
        setDraft((current) => (current ? { ...current, ...changes } : current));

    return (
        <section className="nf-b-suites" aria-label="Test suites">
            <div className="nf-b-pane">
                <h2>Suites</h2>
                <ul>
                    {suites.map((suite) => (
                        <li key={suite.apiName}>
                            <button type="button" aria-current={selected === suite.apiName}
                                onClick={() => pick(suite.apiName)}>
                                {suite.label ?? suite.apiName}
                            </button>
                        </li>
                    ))}
                </ul>
                <button type="button"
                    onClick={() => {
                        const fresh: TestSuiteDefinition = {
                            apiName: "",
                            label: "",
                            cases: [{ name: "", fixtures: [], steps: [], assertExpressions: [] }],
                        };
                        setDraft(fresh);
                        setSelected("");
                        setRunResult(null);
                    }}>
                    New suite
                </button>
            </div>
            <div className="nf-b-main">
                {error ? <p role="alert">{error}</p> : null}
                {flash ? <p role="status" aria-live="polite">{flash}</p> : null}
                {!draft ? (
                    <p>Select or create a suite.</p>
                ) : (
                    <form onSubmit={(event) => { event.preventDefault(); void save(); }}>
                        <fieldset>
                            <legend>Identity</legend>
                            <label>
                                Suite apiName
                                <input aria-label="Suite apiName" value={draft.apiName}
                                    onChange={(e) => patch({ apiName: e.target.value })} required />
                            </label>
                            <label>
                                Label
                                <input aria-label="Suite label" value={draft.label ?? ""}
                                    onChange={(e) => patch({ label: e.target.value })} />
                            </label>
                        </fieldset>
                        {draft.cases.map((suiteCase, caseIndex) => (
                            <CaseEditor
                                key={caseIndex}
                                entities={app.entities.map((entity) => entity.apiName)}
                                suiteCase={suiteCase}
                                onChange={(changed) =>
                                    patch({ cases: draft.cases.map((c, i) => (i === caseIndex ? changed : c)) })
                                }
                                onRemove={() => patch({ cases: draft.cases.filter((_, i) => i !== caseIndex) })}
                            />
                        ))}
                        <button type="button"
                            onClick={() =>
                                patch({
                                    cases: [...draft.cases, { name: "", fixtures: [], steps: [], assertExpressions: [] }],
                                })
                            }>
                            Add case
                        </button>
                        <div className="nf-b-actions">
                            <button type="submit" className="nf-action-primary" disabled={busy}>Save suite</button>
                            <button type="button" disabled={busy || !draft.apiName} onClick={() => void run()}>
                                Run suite
                            </button>
                        </div>
                        {runResult ? (
                            <fieldset>
                                <legend>Run artifact</legend>
                                <p data-green={runResult.green === true}>
                                    {runResult.green === true ? "GREEN" : "RED"}
                                </p>
                                <ul>
                                    {(runResult.results ?? []).map((result) => (
                                        <li key={result.case} data-green={result.green}>
                                            {result.case}: {result.green ? "green" : `red${result.detail ? ` — ${result.detail}` : ""}`}
                                        </li>
                                    ))}
                                </ul>
                            </fieldset>
                        ) : null}
                    </form>
                )}
            </div>
        </section>
    );
}

function CaseEditor({
    entities,
    suiteCase,
    onChange,
    onRemove,
}: {
    entities: string[];
    suiteCase: SuiteCase;
    onChange: (changed: SuiteCase) => void;
    onRemove: () => void;
}): ReactNode {
    const patch = (changes: Partial<SuiteCase>): void => onChange({ ...suiteCase, ...changes });
    return (
        <fieldset data-case={suiteCase.name || "case"}>
            <legend>Case: {suiteCase.name || "(unnamed)"}</legend>
            <label>
                Case name
                <input aria-label={`Case name ${suiteCase.name}`} value={suiteCase.name}
                    onChange={(e) => patch({ name: e.target.value })} />
            </label>
            <label>
                Frozen clock (ISO-8601 instant; empty = run start — PHASE-3 §7's per-case override)
                <input aria-label={`Case clock ${suiteCase.name}`}
                    placeholder="2026-09-30T00:00:00Z"
                    value={suiteCase.clock ?? ""}
                    onChange={(e) => patch({ clock: e.target.value.trim() || undefined })} />
            </label>

            <h3>Fixtures</h3>
            {suiteCase.fixtures.map((fixture, index) => (
                <div key={index} className="nf-rule-row">
                    <select aria-label={`Fixture entity ${index}`} value={fixture.entity}
                        onChange={(e) =>
                            patch({
                                fixtures: suiteCase.fixtures.map((f, i) =>
                                    i === index ? { ...f, entity: e.target.value } : f),
                            })
                        }>
                        <option value="">—</option>
                        {entities.map((entity) => (
                            <option key={entity} value={entity}>{entity}</option>
                        ))}
                    </select>
                    <input aria-label={`Fixture asRole ${index}`} placeholder="asRole"
                        value={fixture.asRole ?? ""}
                        onChange={(e) =>
                            patch({
                                fixtures: suiteCase.fixtures.map((f, i) =>
                                    i === index ? { ...f, asRole: e.target.value || undefined } : f),
                            })
                        } />
                    {/* JsonTextField: a raw controlled input re-serialized the model
                        on every keystroke, so an incomplete literal snapped back to
                        the last committed template — `{` could never be typed */}
                    <JsonTextField
                        aria-label={`Fixture template ${index}`}
                        placeholder='{"name": "Acme"}'
                        value={fixture.template}
                        onParsed={(template) =>
                            patch({
                                fixtures: suiteCase.fixtures.map((f, i) =>
                                    i === index ? { ...f, template: template ?? {} } : f),
                            })
                        } />
                    <button type="button" aria-label={`Remove fixture ${index}`}
                        onClick={() => patch({ fixtures: suiteCase.fixtures.filter((_, i) => i !== index) })}>×</button>
                </div>
            ))}
            <button type="button"
                onClick={() => patch({ fixtures: [...suiteCase.fixtures, { entity: entities[0] ?? "", template: {} }] })}>
                Add fixture
            </button>

            <h3>Steps</h3>
            <table className="nf-table">
                <thead>
                    <tr>
                        <th scope="col">Op</th>
                        <th scope="col">Entity</th>
                        <th scope="col">asRole</th>
                        <th scope="col">Record id</th>
                        <th scope="col">Template / params (JSON)</th>
                        <th scope="col">Expect</th>
                        <th scope="col"></th>
                    </tr>
                </thead>
                <tbody>
                    {suiteCase.steps.map((step, index) => (
                        <tr key={index}>
                            <td>
                                <select aria-label={`Step op row ${index}`} value={step.op}
                                    onChange={(e) => updateStep(index, { op: e.target.value })}>
                                    {SUITE_OPS.map((op) => (
                                        <option key={op} value={op}>{op}</option>
                                    ))}
                                </select>
                            </td>
                            <td>
                                <input aria-label={`Step entity row ${index}`} value={step.entity ?? ""}
                                    onChange={(e) => updateStep(index, { entity: e.target.value || undefined })} />
                            </td>
                            <td>
                                <input aria-label={`Step asRole row ${index}`} value={step.asRole ?? ""}
                                    onChange={(e) => updateStep(index, { asRole: e.target.value || undefined })} />
                            </td>
                            <td>
                                <input aria-label={`Step recordId row ${index}`} value={step.recordId ?? ""}
                                    onChange={(e) => updateStep(index, { recordId: e.target.value || undefined })} />
                            </td>
                            <td>
                                {/* JsonTextField (same keep-typing fence as the fixture template above) */}
                                <JsonTextField
                                    aria-label={`Step template row ${index}`}
                                    placeholder='{"field": "value"}'
                                    value={step.template}
                                    onParsed={(template) => updateStep(index, { template })}
                                />
                            </td>
                            <td>
                                <input aria-label={`Step expect row ${index}`} placeholder="ok | error(CODE)"
                                    value={step.expect ?? ""}
                                    onChange={(e) => updateStep(index, { expect: e.target.value || undefined })} />
                            </td>
                            <td>
                                <button type="button" aria-label={`Remove step row ${index}`}
                                    onClick={() => patch({ steps: suiteCase.steps.filter((_, i) => i !== index) })}>×</button>
                            </td>
                        </tr>
                    ))}
                </tbody>
            </table>
            <button type="button"
                onClick={() => patch({ steps: [...suiteCase.steps, { op: "createRecord", entity: entities[0] ?? "" }] })}>
                Add step
            </button>

            <h3>Assertions</h3>
            {suiteCase.assertExpressions.map((expression, index) => (
                <div key={index} className="nf-rule-row">
                    <input aria-label={`Assertion ${index}`} placeholder="${Order[0].total} == 50.00"
                        value={expression}
                        onChange={(e) =>
                            patch({
                                assertExpressions: suiteCase.assertExpressions.map((a, i) =>
                                    i === index ? e.target.value : a),
                            })
                        } />
                    <button type="button" aria-label={`Remove assertion ${index}`}
                        onClick={() =>
                            patch({ assertExpressions: suiteCase.assertExpressions.filter((_, i) => i !== index) })
                        }>×</button>
                </div>
            ))}
            <button type="button"
                onClick={() => patch({ assertExpressions: [...suiteCase.assertExpressions, ""] })}>
                Add assertion
            </button>

            <button type="button" className="nf-danger" onClick={onRemove}>Remove case</button>
        </fieldset>
    );

    function updateStep(index: number, changes: Partial<SuiteStep>): void {
        patch({ steps: suiteCase.steps.map((step, i) => (i === index ? { ...step, ...changes } : step)) });
    }
}

/** Re-exported for the fixture-template editor's typed shape. */
export type { SuiteFixture };
