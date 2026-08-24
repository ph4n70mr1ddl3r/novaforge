import { useState, type ReactNode } from "react";
import {
    FLOW_OPS,
    HOOK_TRIGGERS,
    type EntityDefinition,
    type FieldDefinition,
    type FlowStep,
    type HookRule,
    type ValidationRule,
} from "@novaforge/shared";

/**
 * The business-logic authoring surface (PHASE-3 §8, T8): validation rules and
 * hook rules as guided forms with live compile feedback — the Metadata Service
 * checks expressions at save, so a rejected draft surfaces the compiler's message
 * verbatim; formula/rollup/default-expression slots edit beside their fields.
 * No free-form canvas in v1: rules are trigger + step-list forms per the spec.
 */

export interface LogicEditorProps {
    app: AppDefinitionLike;
    busy?: boolean;
    onSaveEntity: (entity: EntityDefinition) => Promise<void>;
}

/** Structural subset the editor needs (keeps tests light). */
export interface AppDefinitionLike {
    entities: EntityDefinition[];
}

export function LogicEditor({ app, busy, onSaveEntity }: LogicEditorProps): ReactNode {
    const [selected, setSelected] = useState<EntityDefinition>(app.entities[0] ?? {
        apiName: "",
        fields: [],
        relationships: [],
        validations: [],
        hooks: [],
        indexes: [],
    });
    const [error, setError] = useState<string | null>(null);
    const [flash, setFlash] = useState<string | null>(null);

    const save = async (entity: EntityDefinition): Promise<void> => {
        setError(null);
        try {
            await onSaveEntity(entity);
            setFlash(`Saved logic for ${entity.apiName}`);
        } catch (caught) {
            setError(caught instanceof Error ? caught.message : String(caught));
        }
    };

    return (
        <section className="nf-b-logic" aria-label="Business logic">
            <div className="nf-b-pane">
                <h2>Entities</h2>
                <ul>
                    {app.entities.map((entity) => (
                        <li key={entity.apiName}>
                            <button
                                type="button"
                                aria-current={selected.apiName === entity.apiName}
                                onClick={() => setSelected(entity)}
                            >
                                {entity.label ?? entity.apiName}
                            </button>
                        </li>
                    ))}
                </ul>
            </div>
            <div className="nf-b-main">
                {error ? <p role="alert">{error}</p> : null}
                {flash ? <p role="status" aria-live="polite">{flash}</p> : null}
                {app.entities.length === 0 ? (
                    <p>Create an entity first.</p>
                ) : (
                    <LogicForms key={selected.apiName} entity={selected} busy={busy} onSave={save} />
                )}
            </div>
        </section>
    );
}

function LogicForms({
    entity,
    busy,
    onSave,
}: {
    entity: EntityDefinition;
    busy?: boolean;
    onSave: (entity: EntityDefinition) => Promise<void>;
}): ReactNode {
    const [draft, setDraft] = useState<EntityDefinition>(structuredClone(entity));
    const patch = (changes: Partial<EntityDefinition>): void =>
        setDraft((current) => ({ ...current, ...changes }));

    return (
        <form
            onSubmit={(event) => {
                event.preventDefault();
                void onSave(draft);
            }}
        >
            <ValidationRulesEditor rules={draft.validations ?? []} onChange={(rules) => patch({ validations: rules })} />
            <HooksEditor hooks={draft.hooks ?? []} onChange={(hooks) => patch({ hooks })} />
            <FieldExpressions fields={draft.fields} onChange={(fields) => patch({ fields })} />
            <div className="nf-b-actions">
                <button type="submit" className="nf-action-primary" disabled={busy}>Save logic</button>
            </div>
        </form>
    );
}

// --- validation rules (PHASE-3 §3): record-scope expression + authored message ---

function ValidationRulesEditor({
    rules,
    onChange,
}: {
    rules: ValidationRule[];
    onChange: (rules: ValidationRule[]) => void;
}): ReactNode {
    const update = (index: number, changes: Partial<ValidationRule>): void =>
        onChange(rules.map((rule, i) => (i === index ? { ...rule, ...changes } : rule)));
    return (
        <fieldset>
            <legend>Validation rules</legend>
            {rules.map((rule, index) => (
                <div key={index} className="nf-rule-row" data-rule={rule.name || `rule-${index}`}>
                    <input aria-label={`Rule name ${index}`} placeholder="name" value={rule.name}
                        onChange={(e) => update(index, { name: e.target.value })} />
                    <input aria-label={`Rule expression ${index}`} placeholder="expression, e.g. totalDebit == totalCredit"
                        value={rule.expression} onChange={(e) => update(index, { expression: e.target.value })} />
                    <input aria-label={`Rule message ${index}`} placeholder="authored failure message" value={rule.message}
                        onChange={(e) => update(index, { message: e.target.value })} />
                    <button type="button" aria-label={`Remove rule ${rule.name}`}
                        onClick={() => onChange(rules.filter((_, i) => i !== index))}>×</button>
                </div>
            ))}
            <button type="button"
                onClick={() => onChange([...rules, { name: "", scope: "record", expression: "", message: "" }])}>
                Add validation rule
            </button>
        </fieldset>
    );
}

// --- hooks (PHASE-3 §2): trigger + step-list form over the flow IR ---

function HooksEditor({
    hooks,
    onChange,
}: {
    hooks: HookRule[];
    onChange: (hooks: HookRule[]) => void;
}): ReactNode {
    const update = (index: number, changes: Partial<HookRule>): void =>
        onChange(hooks.map((hook, i) => (i === index ? { ...hook, ...changes } : hook)));
    return (
        <fieldset>
            <legend>Hooks (flow steps)</legend>
            {hooks.map((hook, index) => (
                <div key={index} className="nf-hook-row">
                    <label>
                        Name
                        <input aria-label={`Hook name ${index}`} value={hook.name ?? ""}
                            onChange={(e) => update(index, { name: e.target.value })} />
                    </label>
                    <label>
                        Trigger
                        <select aria-label={`Hook trigger ${index}`} value={hook.trigger}
                            onChange={(e) => update(index, { trigger: e.target.value })}>
                            {HOOK_TRIGGERS.map((trigger) => (
                                <option key={trigger} value={trigger}>{trigger}</option>
                            ))}
                        </select>
                    </label>
                    <StepListEditor
                        steps={hook.flow ? flattenChain(hook.flow) : []}
                        onChange={(steps) => update(index, { flow: chain(steps) })}
                    />
                    <button type="button" aria-label={`Remove hook ${hook.name ?? index}`}
                        onClick={() => onChange(hooks.filter((_, i) => i !== index))}>×</button>
                </div>
            ))}
            <button type="button"
                onClick={() => onChange([...hooks, { name: "", trigger: "afterSave", flow: undefined }])}>
                Add hook
            </button>
        </fieldset>
    );
}

interface StepRow {
    id: string;
    op: string;
    paramsText: string;
    next: string;
}

/**
 * Flattens the graph for the linear step-list editor: walks the {@code next}
 * chain from the entry node, indexing every node so a later-listed node can be
 * the target of an earlier explicit `next`. Branch/iterate bodies stay
 * round-tripped verbatim in their params' JSON — v1 authors linear chains.
 */
function flattenChain(flow: FlowStep): StepRow[] {
    const byId = new Map<string, FlowStep>();
    const collect = (step?: FlowStep): void => {
        if (!step) return;
        byId.set(step.id, step);
        collect(step.body);
    };
    collect(flow);
    const rows: StepRow[] = [];
    let current: FlowStep | undefined = flow;
    const seen = new Set<string>();
    while (current && !seen.has(current.id)) {
        seen.add(current.id);
        rows.push({
            id: current.id,
            op: current.op,
            paramsText: current.params && Object.keys(current.params).length > 0
                ? JSON.stringify(current.params)
                : "",
            next: current.next ?? "",
        });
        current = current.next ? byId.get(current.next) : undefined;
    }
    return rows;
}

/**
 * Builds the step graph from the row list: each row's `next` is the following
 * row's id when left blank. A single-step graph needs no next.
 */
function chain(rows: StepRow[]): FlowStep | undefined {
    if (rows.length === 0) return undefined;
    const parseParams = (text: string): Record<string, unknown> => {
        if (!text.trim()) return {};
        return JSON.parse(text) as Record<string, unknown>;
    };
    const nodes = new Map<string, FlowStep>();
    for (const row of rows) {
        nodes.set(row.id, {
            id: row.id,
            op: row.op,
            params: parseParams(row.paramsText),
            next: undefined,
        });
    }
    for (let i = 0; i < rows.length; i++) {
        const explicitNext = rows[i]!.next.trim();
        const node = nodes.get(rows[i]!.id)!;
        node.next = explicitNext || (i + 1 < rows.length ? rows[i + 1]!.id : undefined);
    }
    return nodes.get(rows[0]!.id);
}

function StepListEditor({
    steps,
    onChange,
}: {
    steps: StepRow[];
    onChange: (steps: StepRow[]) => void;
}): ReactNode {
    const update = (index: number, changes: Partial<StepRow>): void =>
        onChange(steps.map((step, i) => (i === index ? { ...step, ...changes } : step)));
    return (
        <table className="nf-table nf-steps">
            <thead>
                <tr>
                    <th scope="col">Step id</th>
                    <th scope="col">Op</th>
                    <th scope="col">Params (JSON)</th>
                    <th scope="col">Next</th>
                    <th scope="col"></th>
                </tr>
            </thead>
            <tbody>
                {steps.map((step, index) => (
                    <tr key={index}>
                        <td>
                            <input aria-label={`Step id ${index}`} value={step.id}
                                onChange={(e) => update(index, { id: e.target.value })} />
                        </td>
                        <td>
                            <select aria-label={`Step op ${index}`} value={step.op}
                                onChange={(e) => update(index, { op: e.target.value })}>
                                <option value="">—</option>
                                {FLOW_OPS.map((op) => (
                                    <option key={op} value={op}>{op}</option>
                                ))}
                            </select>
                        </td>
                        <td>
                            {step.op === "requestApproval" ? (
                                <RequestApprovalParams
                                    index={index}
                                    paramsText={step.paramsText}
                                    onChange={(paramsText) => update(index, { paramsText })}
                                />
                            ) : (
                                <input aria-label={`Step params ${index}`} value={step.paramsText}
                                    onChange={(e) => update(index, { paramsText: e.target.value })}
                                    placeholder='{"field": "status", "expression": "..."}' />
                            )}
                        </td>
                        <td>
                            <input aria-label={`Step next ${index}`} value={step.next}
                                onChange={(e) => update(index, { next: e.target.value })} />
                        </td>
                        <td>
                            <button type="button" aria-label={`Remove step ${step.id}`}
                                onClick={() => onChange(steps.filter((_, i) => i !== index))}>×</button>
                        </td>
                    </tr>
                ))}
            </tbody>
        </table>
    );
}

/**
 * requestApproval step properties (PHASE-4 §11): the §4 param set as guided fields —
 * approvers (role or user list), mode, timeout, escalation — with the remaining
 * params (the inline onReject subgraph) as JSON. The Metadata Service
 * compile-checks approvers/mode/timeout and the onReject graph at publish.
 */
function RequestApprovalParams({
    index,
    paramsText,
    onChange,
}: {
    index: number;
    paramsText: string;
    onChange: (paramsText: string) => void;
}): ReactNode {
    let params: Record<string, unknown> = {};
    try {
        params = paramsText.trim() ? (JSON.parse(paramsText) as Record<string, unknown>) : {};
    } catch {
        params = {};
    }
    const patch = (changes: Record<string, unknown>): void => {
        const merged = { ...params, ...changes };
        for (const key of Object.keys(merged)) {
            const value = merged[key];
            if (value === "" || value === undefined
                    || (Array.isArray(value) && value.length === 0)) {
                delete merged[key];
            }
        }
        onChange(Object.keys(merged).length > 0 ? JSON.stringify(merged) : "");
    };
    const users = Array.isArray(params.approverUsers) ? (params.approverUsers as string[]).join(", ") : "";
    return (
        <div className="nf-approval-params">
            <input aria-label={`Approvers role ${index}`} placeholder="approvers role, e.g. Purch.manager"
                defaultValue={typeof params.approversRole === "string" ? params.approversRole : ""}
                onBlur={(e) => patch({ approversRole: e.target.value })} />
            <input aria-label={`Approver users ${index}`} placeholder="approver users (UUIDs, comma-separated)"
                defaultValue={users}
                onBlur={(e) => patch({
                    approverUsers: e.target.value.split(",").map((part) => part.trim())
                        .filter((part) => part.length > 0),
                })} />
            <select aria-label={`Approval mode ${index}`}
                defaultValue={typeof params.mode === "string" ? params.mode : ""}
                onChange={(e) => patch({ mode: e.target.value })}>
                <option value="">mode…</option>
                <option value="any">any (first resolution wins)</option>
                <option value="all">all (parallel unanimity)</option>
            </select>
            <input aria-label={`Approval timeout ${index}`} placeholder="timeout, e.g. PT24H"
                defaultValue={typeof params.timeout === "string" ? params.timeout : ""}
                onBlur={(e) => patch({ timeout: e.target.value })} />
            <input aria-label={`Approval escalateTo ${index}`} placeholder="escalateTo, e.g. role:Purch.seniorManager"
                defaultValue={typeof params.escalateTo === "string" ? params.escalateTo : ""}
                onBlur={(e) => patch({ escalateTo: e.target.value })} />
            <input aria-label={`Step other params ${index}`} placeholder='other params JSON (onReject subgraph)'
                defaultValue={otherParams(params)}
                onBlur={(e) => {
                    const text = e.target.value.trim();
                    let rest: Record<string, unknown> = {};
                    try {
                        rest = text ? (JSON.parse(text) as Record<string, unknown>) : {};
                    } catch {
                        return;   // malformed JSON stays uncommitted — save surfaces it
                    }
                    const guided: Record<string, unknown> = {};
                    for (const key of ["approversRole", "approverUsers", "mode", "timeout", "escalateTo"]) {
                        if (params[key] !== undefined) guided[key] = params[key];
                    }
                    const merged = { ...guided, ...rest };
                    onChange(Object.keys(merged).length > 0 ? JSON.stringify(merged) : "");
                }} />
        </div>
    );
}

/** The params outside the guided set, as editable JSON. */
function otherParams(params: Record<string, unknown>): string {
    const rest: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(params)) {
        if (!["approversRole", "approverUsers", "mode", "timeout", "escalateTo"].includes(key)) {
            rest[key] = value;
        }
    }
    return Object.keys(rest).length > 0 ? JSON.stringify(rest) : "";
}

// --- field expressions (PHASE-3 §3): formula, roll-up, expression default ---

function FieldExpressions({
    fields,
    onChange,
}: {
    fields: FieldDefinition[];
    onChange: (fields: FieldDefinition[]) => void;
}): ReactNode {
    const update = (apiName: string, changes: Partial<FieldDefinition>): void =>
        onChange(fields.map((field) => (field.apiName === apiName ? { ...field, ...changes } : field)));
    const expressible = fields.filter(
        (field) => ["decimal", "money", "int", "long"].includes(field.type) || field.formula || field.rollup,
    );
    return (
        <fieldset>
            <legend>Field expressions</legend>
            {expressible.length === 0 ? <p>No numeric fields to compute yet.</p> : null}
            <table className="nf-table">
                <thead>
                    <tr>
                        <th scope="col">Field</th>
                        <th scope="col">Formula (own record)</th>
                        <th scope="col">Roll-up (child aggregate)</th>
                        <th scope="col">Default expression</th>
                    </tr>
                </thead>
                <tbody>
                    {expressible.map((field) => (
                        <tr key={field.apiName}>
                            <td>{field.apiName}</td>
                            <td>
                                <input aria-label={`Formula for ${field.apiName}`} value={field.formula ?? ""}
                                    onChange={(e) => update(field.apiName, { formula: e.target.value || undefined })} />
                            </td>
                            <td>
                                <input aria-label={`Rollup for ${field.apiName}`}
                                    placeholder="SUM(lines.amount)" value={field.rollup ?? ""}
                                    onChange={(e) => update(field.apiName, { rollup: e.target.value || undefined })} />
                            </td>
                            <td>
                                <input aria-label={`Default expression for ${field.apiName}`}
                                    value={field.default?.expression ?? ""}
                                    onChange={(e) => update(field.apiName, {
                                        default: e.target.value.trim()
                                            ? { expression: e.target.value }
                                            : field.default?.value !== undefined
                                                ? field.default
                                                : undefined,
                                    })} />
                            </td>
                        </tr>
                    ))}
                </tbody>
            </table>
            <p className="nf-b-meta">
                Formulas may not read the clock (stored values go stale); defaults may.
                The Metadata Service compile-checks every slot at save and publish.
            </p>
        </fieldset>
    );
}
