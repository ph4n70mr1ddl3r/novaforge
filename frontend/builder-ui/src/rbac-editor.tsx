import { useState, type ReactNode } from "react";
import type { AppDefinition, FieldAccess, ObjectPermission } from "@novaforge/shared";

/**
 * The RBAC + field-security editors (PHASE-2 §9): the app-defined role list, the
 * role × entity CRUD matrix (absent flags deny — the editor shows what the Data
 * Runtime enforces server-side), and per-role field security
 * (visible/read-only/hidden). Rendering only; enforcement never moves client-side.
 */

export interface RbacEditorProps {
    app: AppDefinition;
    onSave: (permissionSet: AppDefinition["permissionSet"]) => Promise<void>;
}

const CRU = ["create", "read", "update", "delete", "reportExecute"] as const;

export function RbacEditor({ app, onSave }: RbacEditorProps): ReactNode {
    const [draft, setDraft] = useState(app.permissionSet);
    const [flash, setFlash] = useState<string | null>(null);
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
                            {role.level !== undefined ? <span> (level {role.level})</span> : null}
                        </li>
                    ))}
                </ul>
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
            <button
                type="button"
                className="nf-action-primary"
                disabled={busy}
                onClick={async () => {
                    setBusy(true);
                    try {
                        await onSave(draft);
                        setFlash("Permissions saved");
                    } finally {
                        setBusy(false);
                    }
                }}
            >
                Save permissions
            </button>
            {flash ? <p role="status" aria-live="polite">{flash}</p> : null}
        </section>
    );
}
