import { useState, type ReactNode } from "react";
import { mergeBranch } from "./branch-merge.ts";
import type {
    AppDefinition,
    FieldAccess,
    ObjectPermission,
    SharingRuleDefinition,
} from "@novaforge/shared";

/**
 * The RBAC + field-security editors (PHASE-2 §9) plus the sharing-rule editor of
 * PHASE-4 §10 (the §9 remainder): the app-defined role list (with the numeric
 * levels roleHierarchy rules read), the role × entity CRUD matrix (absent flags
 * deny — the editor shows what the Data Runtime enforces server-side), per-role
 * field security (visible/read-only/hidden), and record-level sharing rules
 * (owner / roleHierarchy / criteria — versioned, promoted, compiled at publish).
 * Rendering only; enforcement never moves client-side.
 */

export interface RbacEditorProps {
    app: AppDefinition;
    onSave: (mutate: (fresh: AppDefinition["permissionSet"]) => AppDefinition["permissionSet"]) => Promise<void>;
}

const CRU = ["create", "read", "update", "delete", "reportExecute"] as const;

export function RbacEditor({ app, onSave }: RbacEditorProps): ReactNode {
    const [draft, setDraft] = useState(app.permissionSet);
    const [flash, setFlash] = useState<string | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    const entities = app.entities;

    const grantOf = (role: string, entity: string): ObjectPermission =>
        draft.objectPermissions.find((permission) => permission.role === role && permission.entity === entity)
        ?? { role, entity };

    const toggle = (role: string, entity: string, action: (typeof CRU)[number]): void => {
        setDraft((current) => {
            const existing = current.objectPermissions.filter(
                (permission) => !(permission.role === role && permission.entity === entity),
            );
            const grant = grantOf(role, entity);
            existing.push({ ...grant, [action]: !grant[action] || undefined });
            return { ...current, objectPermissions: existing };
        });
    };

    const fieldAccessOf = (role: string, entity: string, field: string): FieldAccess =>
        draft.fieldSecurity.find(
            (security) => security.role === role && security.entity === entity && security.field === field,
        )?.access ?? "visible";

    const cycleFieldAccess = (role: string, entity: string, field: string): void => {
        const order: FieldAccess[] = ["visible", "readonly", "hidden"];
        const next = order[(order.indexOf(fieldAccessOf(role, entity, field)) + 1) % order.length]!;
        setDraft((current) => ({
            ...current,
            fieldSecurity: [
                ...current.fieldSecurity.filter(
                    (security) => !(security.role === role && security.entity === entity && security.field === field),
                ),
                ...(next === "visible" ? [] : [{ role, entity, field, access: next }]),
            ],
        }));
    };

    return (
        <section className="nf-b-rbac" aria-label="Roles and field security">
            <h2>Roles &amp; permissions</h2>
            <fieldset>
                <legend>Roles</legend>
                <ul>
                    {draft.roles.map((role) => (
                        <li key={role.name}>
                            <code>{role.name}</code> {role.description ? <span>— {role.description}</span> : null}
                            <label className="nf-inline">
                                <span className="nf-visually-hidden">level for {role.name}</span>
                                <input aria-label={`Level for ${role.name}`} type="number" placeholder="level"
                                    value={role.level ?? ""}
                                    onChange={(event) => setDraft((current) => ({
                                        ...current,
                                        roles: current.roles.map((candidate) =>
                                            candidate.name === role.name
                                                ? {
                                                    ...candidate,
                                                    level: event.target.value === ""
                                                        ? undefined
                                                        : Number(event.target.value),
                                                }
                                                : candidate),
                                    }))} />
                            </label>
                        </li>
                    ))}
                </ul>
                <p className="nf-b-meta">
                    Levels feed roleHierarchy sharing: lower = more senior (§10).
                </p>
                <form
                    onSubmit={(event) => {
                        event.preventDefault();
                        const form = new FormData(event.currentTarget);
                        const name = String(form.get("role") ?? "").trim();
                        if (!name) return;
                        setDraft((current) => ({
                            ...current,
                            roles: [...current.roles, { name, description: String(form.get("description") ?? "") }],
                        }));
                        event.currentTarget.reset();
                    }}
                >
                    <label>
                        New role name
                        <input name="role" pattern="[a-zA-Z][A-Za-z0-9_]*" required />
                    </label>
                    <label>
                        Description
                        <input name="description" />
                    </label>
                    <button type="submit">Add role</button>
                </form>
            </fieldset>
            <h3>Object permissions (role × entity → CRUD + report:execute)</h3>
            <table className="nf-table">
                <thead>
                    <tr>
                        <th scope="col">Role</th>
                        {entities.map((entity) => (
                            <th key={entity.apiName} scope="col">{entity.apiName}</th>
                        ))}
                    </tr>
                </thead>
                <tbody>
                    {draft.roles.map((role) => (
                        <tr key={role.name}>
                            <th scope="row">{role.name}</th>
                            {entities.map((entity) => (
                                <td key={entity.apiName}>
                                    {CRU.map((action) => (
                                        <label key={action} className="nf-inline">
                                            <input
                                                type="checkbox"
                                                checked={grantOf(role.name, entity.apiName)[action] === true}
                                                onChange={() => toggle(role.name, entity.apiName, action)}
                                            />
                                            {action === "reportExecute" ? "report" : action}
                                        </label>
                                    ))}
                                </td>
                            ))}
                        </tr>
                    ))}
                </tbody>
            </table>
            <h3>Field security</h3>
            {draft.roles.map((role) => (
                <details key={role.name}>
                    <summary>{role.name}</summary>
                    {entities.map((entity) => (
                        <table key={entity.apiName} className="nf-table">
                            <caption>{entity.apiName}</caption>
                            <thead>
                                <tr>
                                    {entity.fields.map((field) => (
                                        <th key={field.apiName} scope="col">{field.apiName}</th>
                                    ))}
                                </tr>
                            </thead>
                            <tbody>
                                <tr>
                                    {entity.fields.map((field) => {
                                        const access = fieldAccessOf(role.name, entity.apiName, field.apiName);
                                        return (
                                            <td key={field.apiName}>
                                                <button
                                                    type="button"
                                                    data-access={access}
                                                    onClick={() => cycleFieldAccess(role.name, entity.apiName, field.apiName)}
                                                >
                                                    {access}
                                                </button>
                                            </td>
                                        );
                                    })}
                                </tr>
                            </tbody>
                        </table>
                    ))}
                </details>
            ))}
            <SharingRulesEditor
                entities={entities.map((entity) => entity.apiName)}
                roles={draft.roles.map((role) => role.name)}
                rules={draft.sharingRules ?? []}
                onChange={(sharingRules) => setDraft((current) => ({ ...current, sharingRules }))}
            />
            <button
                type="button"
                className="nf-action-primary"
                disabled={busy}
                onClick={async () => {
                    setBusy(true);
                    try {
                        // merged over a FRESH fetch inside the shell: this draft is
                        // seeded from the mount-time permission set, and saving it
                        // verbatim deleted a permission another tab added meanwhile
                        // (the dashboards rule)
                        await onSave((fresh) => ({
                            ...draft,
                            roles: mergeBranch(draft.roles, app.permissionSet.roles, fresh.roles, (role) => role.name),
                            objectPermissions: mergeBranch(
                                draft.objectPermissions,
                                app.permissionSet.objectPermissions,
                                fresh.objectPermissions,
                                (permission) => `${permission.role}:${permission.entity}`,
                            ),
                            fieldSecurity: mergeBranch(
                                draft.fieldSecurity,
                                app.permissionSet.fieldSecurity,
                                fresh.fieldSecurity,
                                (security) => `${security.role}:${security.entity}:${security.field}`,
                            ),
                            sharingRules: mergeBranch(
                                draft.sharingRules,
                                app.permissionSet.sharingRules,
                                fresh.sharingRules,
                                (rule) => `${rule.entity}:${rule.type}:${rule.roles?.join("+") ?? ""}`,
                            ),
                        }));
                        setFlash("Permissions saved");
                                        } catch (caught) {
                        // a failed save must never look like a success — the draft
                        // stays local and the user sees why
                        setError(caught instanceof Error ? caught.message : String(caught));
                    } finally {
                        setBusy(false);
                    }
                }}
            >
                Save permissions
            </button>
            {flash ? <p role="status" aria-live="polite">{flash}</p> : null}
            {error ? <p role="alert">{error}</p> : null}
        </section>
    );
}

// --- sharing rules (PHASE-4 §10): the record-level remainder of §9 ---

function SharingRulesEditor({
    entities,
    roles,
    rules,
    onChange,
}: {
    entities: string[];
    roles: string[];
    rules: SharingRuleDefinition[];
    onChange: (rules: SharingRuleDefinition[]) => void;
}): ReactNode {
    const update = (index: number, changes: Partial<SharingRuleDefinition>): void =>
        onChange(rules.map((rule, i) => (i === index ? { ...rule, ...changes } : rule)));
    const csv = (values: string[] | undefined): string => (values ?? []).join(", ");
    const parseCsv = (text: string): string[] =>
        text.split(",").map((part) => part.trim()).filter((part) => part.length > 0);
    return (
        <fieldset>
            <legend>Sharing rules (record-level)</legend>
            <p className="nf-b-meta">
                No rules → full visibility under the CRUD matrix (no silent
                tightening). Criteria compile at publish; roleHierarchy rules need
                leveled roles.
            </p>
            {rules.map((rule, index) => (
                <div key={index} className="nf-sharing-row" data-sharing={rule.entity}>
                    <select aria-label={`Sharing entity ${index}`} value={rule.entity}
                        onChange={(e) => update(index, { entity: e.target.value })}>
                        <option value="">entity…</option>
                        {entities.map((entity) => (
                            <option key={entity} value={entity}>{entity}</option>
                        ))}
                    </select>
                    <select aria-label={`Sharing type ${index}`} value={rule.type}
                        onChange={(e) => update(index, { type: e.target.value as SharingRuleDefinition["type"] })}>
                        <option value="owner">owner</option>
                        <option value="roleHierarchy">roleHierarchy</option>
                        <option value="criteria">criteria</option>
                    </select>
                    <input aria-label={`Sharing roles ${index}`} placeholder="roles (comma-separated)"
                        value={csv(rule.roles)}
                        onChange={(e) => update(index, { roles: parseCsv(e.target.value) })} />
                    {rule.type === "owner" ? (
                        <input aria-label={`Sharing ownerField ${index}`} placeholder="ownerField (default: creator)"
                            value={rule.ownerField ?? ""}
                            onChange={(e) => update(index, { ownerField: e.target.value || undefined })} />
                    ) : null}
                    {rule.type === "criteria" ? (
                        <input aria-label={`Sharing criteria ${index}`} placeholder="criteria expression"
                            value={rule.criteria ?? ""}
                            onChange={(e) => update(index, { criteria: e.target.value || undefined })} />
                    ) : null}
                    <button type="button" aria-label={`Remove sharing rule ${index}`}
                        onClick={() => onChange(rules.filter((_, i) => i !== index))}>×</button>
                </div>
            ))}
            <button type="button"
                onClick={() => onChange([...rules, { entity: "", type: "owner", roles: [] }])}>
                Add sharing rule
            </button>
            {roles.length === 0 ? <p className="nf-b-meta">Add roles first — rules name them.</p> : null}
        </fieldset>
    );
}
