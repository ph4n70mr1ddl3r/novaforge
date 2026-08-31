import { useEffect, useMemo, useState, type ReactNode } from "react";
import {
    ApiError,
    type EntityDefinition,
    type FieldDefinition,
    type FieldType,
    type AppDefinition,
} from "@novaforge/shared";
import { FIELD_TYPES } from "./fieldTypes.ts";

/**
 * The entity builder (PHASE-2 §8): a guided wizard (identity + display field)
 * and the table/grid editor for fields and relationships over the Metadata
 * draft APIs — creating entities incl. relationships without hand-written API
 * calls (T7's acceptance). Field-type-specific constraint forms generate from
 * field metadata (dogfooding the catalog approach — §8).
 */

const CONSTRAINT_FORMS: Partial<Record<FieldType, { prop: keyof FieldDefinition; kind: "number" | "text" | "enum"; label: string }[]>> = {
    text: [{ prop: "length", kind: "number", label: "Length" }],
    longText: [{ prop: "length", kind: "number", label: "Length" }],
    int: [{ prop: "precision", kind: "number", label: "Precision" }],
    long: [{ prop: "precision", kind: "number", label: "Precision" }],
    decimal: [
        { prop: "precision", kind: "number", label: "Precision" },
        { prop: "scale", kind: "number", label: "Scale" },
    ],
    money: [{ prop: "currency", kind: "text", label: "Currency (ISO)" }],
    enum: [{ prop: "values", kind: "enum", label: "Values (comma-separated)" }],
    lookup: [{ prop: "target", kind: "text", label: "Target entity" }],
    m2m: [{ prop: "target", kind: "text", label: "Target entity" }],
};

export interface EntityBuilderProps {
    app: AppDefinition;
    appId: string;
    onSave: (entity: EntityDefinition) => Promise<void>;
    onDelete: (apiName: string) => Promise<void>;
}

export function EntityBuilder({ app, appId, onSave, onDelete }: EntityBuilderProps): ReactNode {
    const [selected, setSelected] = useState<EntityDefinition | null>(app.entities[0] ?? null);
    const [error, setError] = useState<string | null>(null);
    const [flash, setFlash] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);

    const pick = (apiName: string): void => {
        setSelected(app.entities.find((entity) => entity.apiName === apiName) ?? null);
        setError(null);
    };

    const save = async (entity: EntityDefinition): Promise<void> => {
        setBusy(true);
        setError(null);
        try {
            await onSave(entity);
            setSelected(entity);
            setFlash(`Saved ${entity.apiName}`);
        } catch (caught) {
            setError(caught instanceof ApiError ? (caught.problem.detail ?? caught.message) : String(caught));
        } finally {
            setBusy(false);
        }
    };

    return (
        <section className="nf-b-entity" aria-label="Entity builder">
            <div className="nf-b-pane">
                <h2>Entities</h2>
                <ul>
                    {app.entities.map((entity) => (
                        <li key={entity.apiName}>
                            <button
                                type="button"
                                aria-current={selected?.apiName === entity.apiName}
                                onClick={() => pick(entity.apiName)}
                            >
                                {/* blank labels fall back to the apiName — `??` alone
                                    keeps "" and the button loses its accessible name
                                    (found live at the golden journey) */}
                                {entity.label?.trim() ? entity.label : entity.apiName}
                            </button>
                        </li>
                    ))}
                </ul>
                <button
                    type="button"
                    className="nf-action-primary"
                    disabled={busy}
                    onClick={() =>
                        // a fresh object identity every click — EntityEditor resets its
                        // draft when the selection prop changes (found live at the
                        // golden-journey run: a stale draft from the app the shell
                        // first loaded, hooks included, rode into every "new" entity
                        // and failed the save compile-check)
                        setSelected({
                            apiName: "",
                            label: "",
                            // the seeded field is the display field — an empty
                            // displayField fails save-validation ("must name an
                            // existing field"), found live at the golden journey
                            displayField: "name",
                            module: "",
                            fields: [{ apiName: "name", type: "text", required: true }],
                            relationships: [],
                            validations: [],
                            hooks: [],
                            indexes: [],
                        })
                    }
                >
                    New entity
                </button>
            </div>
            <div className="nf-b-main">
                {error ? <p role="alert">{error}</p> : null}
                {flash ? <p role="status" aria-live="polite">{flash}</p> : null}
                {selected ? (
                    <EntityEditor entity={selected} app={app} busy={busy} onSave={save} onDelete={onDelete} />
                ) : (
                    <p>Select or create an entity.</p>
                )}
            </div>
            <p className="nf-b-meta">app {appId}</p>
        </section>
    );
}

function EntityEditor({
    entity,
    app,
    busy,
    onSave,
    onDelete,
}: {
    entity: EntityDefinition;
    app: AppDefinition;
    busy: boolean;
    onSave: (entity: EntityDefinition) => Promise<void>;
    onDelete: (apiName: string) => Promise<void>;
}): ReactNode {
    const [draft, setDraft] = useState<EntityDefinition>(entity);
    const [deleteError, setDeleteError] = useState<string | null>(null);
    // selection resets the draft: a fresh "New entity" selection or picking another
    // entity must never carry the previous draft's fields/hooks — found live at the
    // golden-journey run (a stale draft from the shell's first-loaded app rode into
    // every "new" entity and failed the save compile-check)
    useEffect(() => {
        setDraft(entity);
    }, [entity]);
    const update = (patch: Partial<EntityDefinition>): void => {
        setDraft((current) => ({ ...current, ...patch }));
    };
    const updateField = (index: number, patch: Partial<FieldDefinition>): void => {
        setDraft((current) => ({
            ...current,
            fields: current.fields.map((field, i) => (i === index ? { ...field, ...patch } : field)),
        }));
    };
    const removeField = (index: number): void => {
        setDraft((current) => ({ ...current, fields: current.fields.filter((_, i) => i !== index) }));
    };
    const addField = (): void => {
        setDraft((current) => ({
            ...current,
            fields: [...current.fields, { apiName: "", type: "text" }],
        }));
    };
    const targetOptions = app.entities.map((candidate) => candidate.apiName);

    return (
        <form
            onSubmit={(event) => {
                event.preventDefault();
                void onSave(draft);
            }}
        >
            <fieldset>
                <legend>Identity</legend>
                <label>
                    Entity apiName
                    <input aria-label="Entity apiName" value={draft.apiName} onChange={(e) => update({ apiName: e.target.value })} required
                        pattern="[A-Z][A-Za-z0-9]*" title="PascalCase per ARCHITECTURE.md §3" />
                </label>
                <label>
                    Label
                    <input value={draft.label ?? ""} onChange={(e) => update({ label: e.target.value })} />
                </label>
                <label>
                    Module (nav group)
                    <input value={draft.module ?? ""} onChange={(e) => update({ module: e.target.value })} />
                </label>
                <label>
                    Display field
                    <select value={draft.displayField ?? ""} onChange={(e) => update({ displayField: e.target.value })}>
                        <option value="">—</option>
                        {draft.fields.map((field) => (
                            <option key={field.apiName} value={field.apiName}>{field.apiName}</option>
                        ))}
                    </select>
                </label>
            </fieldset>
            <fieldset>
                <legend>Fields</legend>
                <table className="nf-table">
                    <thead>
                        <tr>
                            <th scope="col">apiName</th>
                            <th scope="col">Label</th>
                            <th scope="col">Type</th>
                            <th scope="col">Constraints</th>
                            <th scope="col">Flags</th>
                            <th scope="col">Remove</th>
                        </tr>
                    </thead>
                    <tbody>
                        {draft.fields.map((field, index) => (
                            <tr key={index}>
                                <td>
                                    <input value={field.apiName} aria-label={`apiName row ${index}`}
                                        onChange={(e) => updateField(index, { apiName: e.target.value })} />
                                </td>
                                <td>
                                    <input value={field.label ?? ""} aria-label={`label row ${index}`}
                                        onChange={(e) => updateField(index, { label: e.target.value })} />
                                </td>
                                <td>
                                    <select value={field.type} aria-label={`type row ${index}`}
                                        onChange={(e) => updateField(index, { type: e.target.value as FieldType })}>
                                        {FIELD_TYPES.map((type) => (
                                            <option key={type} value={type}>{type}</option>
                                        ))}
                                    </select>
                                </td>
                                <td>
                                    <ConstraintForm field={field} index={index} onChange={updateField} targets={targetOptions} />
                                </td>
                                <td>
                                    <label className="nf-inline">
                                        <input type="checkbox" checked={field.required === true}
                                            onChange={(e) => updateField(index, { required: e.target.checked || undefined })} /> required
                                    </label>
                                    <label className="nf-inline">
                                        <input type="checkbox" checked={field.uniqueness === true}
                                            onChange={(e) => updateField(index, { uniqueness: e.target.checked || undefined })} /> unique
                                    </label>
                                </td>
                                <td>
                                    <button type="button" onClick={() => removeField(index)} aria-label={`Remove field ${field.apiName}`}>
                                        ×
                                    </button>
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
                <button type="button" onClick={addField}>Add field</button>
            </fieldset>
            <div className="nf-b-actions">
                <button type="submit" className="nf-action-primary" disabled={busy}>Save entity</button>
                {app.entities.some((candidate) => candidate.apiName === draft.apiName) ? (
                    <button type="button" className="nf-danger"
                        disabled={busy}
                        onClick={() => {
                            // a rejected delete (409 referenced-by-pages, 403) was a
                            // silent unhandled rejection — surface it, never swallow
                            void onDelete(draft.apiName)
                                .catch((caught: unknown) =>
                                    setDeleteError(caught instanceof Error ? caught.message : String(caught)));
                        }}>Delete entity</button>
                ) : null}
                {deleteError ? <p role="alert">{deleteError}</p> : null}
            </div>
        </form>
    );
}

function ConstraintForm({
    field,
    index,
    onChange,
    targets,
}: {
    field: FieldDefinition;
    index: number;
    onChange: (index: number, patch: Partial<FieldDefinition>) => void;
    targets: string[];
}): ReactNode {
    const forms = CONSTRAINT_FORMS[field.type] ?? [];
    return (
        <>
            {forms.map((form) =>
                form.kind === "enum" ? (
                    <input key={String(form.prop)} aria-label={`${String(form.prop)} row ${index}`}
                        value={Array.isArray(field.values) ? field.values.join(",") : ""}
                        onChange={(e) =>
                            onChange(index, { values: e.target.value.split(",").map((v) => v.trim()).filter(Boolean) } as Partial<FieldDefinition>)
                        }
                        placeholder={form.label} />
                ) : form.prop === "target" ? (
                    <select key="target" aria-label={`target row ${index}`} value={field.target ?? ""}
                        onChange={(e) => onChange(index, { target: e.target.value || undefined })}>
                        <option value="">—</option>
                        {targets.map((target) => (
                            <option key={target} value={target}>{target}</option>
                        ))}
                    </select>
                ) : (
                    <input key={String(form.prop)} aria-label={`${String(form.prop)} row ${index}`}
                        type={form.kind === "number" ? "number" : "text"}
                        value={String(field[form.prop] ?? "")}
                        onChange={(e) =>
                            onChange(index, {
                                [form.prop]: form.kind === "number" ? Number(e.target.value) : e.target.value,
                            } as Partial<FieldDefinition>)
                        }
                        placeholder={form.label} />
                ),
            )}
        </>
    );
}
