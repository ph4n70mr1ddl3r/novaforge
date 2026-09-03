import { useEffect, useState, type ReactNode } from "react";
import { dispatchAction, useBoundValue, useRenderer, type ListResult } from "../renderer/context.ts";
import { resolveLabel } from "../metadata.ts";

/**
 * The v1 layout & container components (PHASE-2 §6 item 3): AppShell, NavList,
 * FormLayout, ListLayout, RecordHeader, RelatedList, RecordActions, EmptyState.
 * Layouts are structural — data flows through the renderer context (ADR-009 L3:
 * behavior is declarative; the renderer interprets, components present).
 */

export interface LayoutProps {
    title?: string;
    children?: ReactNode;
    [key: string]: unknown;
}

export function AppShell(props: LayoutProps & { brand?: string }): ReactNode {
    const renderer = useRenderer();
    return (
        <div className="nf-shell">
            <header className="nf-shell-header">
                <span className="nf-brand">{props.brand ?? "NovaForge"}</span>
                {renderer.user ? (
                    <span className="nf-user" aria-label="Signed in user">
                        {renderer.user.name}
                        {renderer.user.locale ? ` · ${renderer.user.locale}` : ""}
                    </span>
                ) : null}
            </header>
            <div className="nf-shell-body">{props.children}</div>
        </div>
    );
}

export function NavList(props: LayoutProps): ReactNode {
    const renderer = useRenderer();
    const [nav, setNav] = useState<{ label: string; entities: string[] }[]>([]);
    useEffect(() => {
        // The shell supplies nav through the data service in preview; the runtime
        // app resolves nav from the published bundle (module-grouped, §5).
        const configured = props.groups as { label: string; entities: { apiName: string; label: string }[] }[] | undefined;
        if (configured) {
            setNav(configured.map((group) => ({
                label: group.label,
                entities: group.entities.map((entity) => entity.label),
            })));
        }
    }, [props.groups]);
    return (
        <nav className="nf-nav" aria-label={props.title ?? "Navigation"}>
            <h2>{props.title ?? "Records"}</h2>
            {nav.map((group) => (
                <section key={group.label}>
                    <h3>{group.label}</h3>
                    <ul>
                        {group.entities.map((label) => (
                            <li key={label}>{label}</li>
                        ))}
                    </ul>
                </section>
            ))}
            {nav.length === 0 ? <EmptyState message="No entities" /> : null}
            <span className="nf-nav-role">{renderer.role ?? "—"}</span>
        </nav>
    );
}

export function FormLayout(props: LayoutProps & { columns?: number; section?: string | null }): ReactNode {
    const renderer = useRenderer();
    const columns = Number(props.columns ?? 2);
    return (
        <form
            className="nf-form"
            style={{ gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))` }}
            onSubmit={(event) => event.preventDefault()}
            aria-label={props.section ?? props.title ?? undefined}
        >
            {props.section ? <h3 className="nf-section-title">{props.section}</h3> : null}
            {props.children}
            {/* The form's action bar: L1 form defaults carry save/cancel as page
                actions — without a rendering surface here the auto-generated form
                had no submit path at all (found live at the golden journey) */}
            {(renderer.pageActions ?? []).length > 0 ? (
                <div className="nf-form-actions nf-full" role="group" aria-label="Form actions">
                    {(renderer.pageActions ?? []).map((action, index) => (
                        <button
                            key={index}
                            type="button"
                            // the in-flight write fences the button too: a fast
                            // double-click landed two POSTs before the re-render
                            disabled={renderer.busy}
                            className={action.type === "save" ? "nf-action nf-action-primary" : "nf-action"}
                            onClick={() => void dispatchAction(renderer, action)}
                        >
                            {actionLabel(action.type)}
                        </button>
                    ))}
                </div>
            ) : null}
        </form>
    );
}

export function RecordHeader(props: LayoutProps & { displayField?: string }): ReactNode {
    const renderer = useRenderer();
    // A <section>, not a <header>: AppShell owns the page's single banner landmark
    // (axe landmark-unique) — the record header is a titled region.
    return (
        <section className="nf-record-header" aria-label={props.title ?? "Record"}>
            <h2>
                {props.title ?? ""}
                {renderer.record?.id ? (
                    <span className="nf-record-id"> {String(renderer.record.id)}</span>
                ) : null}
            </h2>
            <div className="nf-record-body">{props.children}</div>
        </section>
    );
}

/** Server-side paging only — never client slicing (§6/T6's 100k-row rule). */
export function ListLayout(props: LayoutProps & { pageSize?: number; sortable?: boolean; columns?: string[] }): ReactNode {
    const renderer = useRenderer();
    const pageSize = Number(props.pageSize ?? 50);
    const [state, setState] = useState<{ rows: Record<string, unknown>[]; total: number }>({ rows: [], total: 0 });
    // a failed fetch must never render as "No records yet" — the user would conclude
    // their data is gone (the sixth pass's stuck-surface class, on the primary
    // data surface)
    const [failure, setFailure] = useState<string | null>(null);
    const [offset, setOffset] = useState(0);
    const [sortField, setSortField] = useState<string | null>(null);
    const [sortDir, setSortDir] = useState<"asc" | "desc">("asc");
    const [loading, setLoading] = useState(false);

    const { data, entity, mode, listFilter } = renderer;
    useEffect(() => {
        let cancelled = false;
        if (!entity || !data || mode === "preview") {
            return;
        }
        setLoading(true);
        data.list({
            entity,
            filter: listFilter,
            size: pageSize,
            offset,
            sort: sortField ? [{ field: sortField, dir: sortDir }] : undefined,
        })
            .then((result: ListResult) => {
                if (!cancelled) {
                    setState({ rows: result.rows, total: result.total });
                    setFailure(null);
                }
            })
            .catch((caught: unknown) => {
                if (!cancelled) {
                    setFailure(caught instanceof Error ? caught.message : String(caught));
                }
            })
            .finally(() => {
                if (!cancelled) setLoading(false);
            });
        return () => {
            cancelled = true;
        };
    }, [data, entity, mode, listFilter, offset, pageSize, sortDir, sortField]);

    const columns = (props.columns as string[] | undefined) ?? [];
    const fieldLabels = columns.filter((field) => renderer.fields[field] !== undefined || field !== "actions");

    return (
        <section className="nf-list" aria-busy={loading}>
            <div className="nf-list-toolbar">
                <h2>{props.title}</h2>
                <span className="nf-list-count" aria-live="polite">
                    {state.total} record{state.total === 1 ? "" : "s"}
                    {listFilter ? " (filtered)" : ""}
                </span>
                {(renderer.pageActions ?? []).map((action, index) => (
                    <button
                        key={index}
                        type="button"
                        className="nf-action nf-action-primary"
                        onClick={() => void dispatchAction(renderer, action)}
                    >
                        {action.type === "openPage" ? "New" : actionLabel(action.type)}
                    </button>
                ))}
            </div>
            <table className="nf-table">
                <thead>
                    <tr>
                        {fieldLabels.length > 0 ? <th className="nf-open-col" scope="col">
                            <span className="nf-visually-hidden">Open</span>
                        </th> : null}
                        {fieldLabels.map((field) => {
                            const fieldMeta = renderer.fields[field];
                            return (
                                <th key={field} scope="col" aria-sort={sortField === field ? (sortDir === "asc" ? "ascending" : "descending") : "none"}>
                                    {props.sortable === false ? (
                                        resolveLabel(fieldMeta, renderer.user?.locale, field)
                                    ) : (
                                        <button
                                            type="button"
                                            onClick={() => {
                                                setSortField(field);
                                                setSortDir(sortField === field && sortDir === "asc" ? "desc" : "asc");
                                                setOffset(0);
                                            }}
                                        >
                                            {resolveLabel(fieldMeta, renderer.user?.locale, field)}
                                        </button>
                                    )}
                                </th>
                            );
                        })}
                    </tr>
                </thead>
                    <tbody>
                    {state.rows.map((row) => (
                        <tr
                            key={String(row.id)}
                            onClick={() => renderer.navigate(renderer.entity!, "detail", String(row.id ?? ""))}
                        >
                            {/* The record-open control is a real button in the first
                                cell (§11 item 2's keyboard-only run caught it): the
                                row's click handler is pointer-convenience — a keyboard
                                user had no way to open a record from a generated list
                                at all (WCAG 2.1.1). The accessible name carries the
                                row's display value; the visible "Open" keeps
                                Label-in-Name. */}
                            <td className="nf-open-col">
                                {row.id != null ? (
                                    <button
                                        type="button"
                                        className="nf-row-open"
                                        aria-label={`Open ${fieldLabels.length > 0 && row[fieldLabels[0]!] != null
                                            ? row[fieldLabels[0]!]
                                            : String(row.id)}`}
                                        onClick={(event) => {
                                            // the row's click would double-fire on pointer
                                            event.stopPropagation();
                                            renderer.navigate(renderer.entity!, "detail", String(row.id));
                                        }}
                                    >
                                        Open
                                    </button>
                                ) : null}
                            </td>
                            {fieldLabels.map((field) => (
                                <td key={field}>{row[field] == null ? "" : String(row[field])}</td>
                            ))}
                        </tr>
                    ))}
                    </tbody>
            </table>
            {failure ? (
                <p role="alert" className="nf-error">
                    Could not load {props.title ?? "records"}: {failure}
                </p>
            ) : state.rows.length === 0 && !loading ? (
                <EmptyState message={`No ${props.title ?? "records"} yet`} />
            ) : null}
            <div className="nf-pager">
                <button type="button" disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - pageSize))}>
                    Previous
                </button>
                <span>
                  {state.total === 0 ? 0 : offset + 1}–{Math.min(offset + pageSize, state.total)} / {state.total}
                </span>
                <button
                    type="button"
                    disabled={offset + pageSize >= state.total}
                    onClick={() => setOffset(offset + pageSize)}
                >
                    Next
                </button>
            </div>
            {/* Authored children render: the L1 list default (and any builder insert)
                places column widgets and the record-actions node here — a list page
                that dropped its children silently swallowed every node it was given. */}
            {props.children}
        </section>
    );
}

/** Inline-editable child grid — the `child` field type's default widget (§5). */
export function RelatedList(props: LayoutProps & { relationship?: string; target?: string; pageSize?: number }): ReactNode {
    const renderer = useRenderer();
    const rows = (useBoundValue(props.relationship) as Record<string, unknown>[] | undefined) ?? [];
    const columns = (props.columns as string[] | undefined) ?? Object.keys(rows[0] ?? { id: "" });
    return (
        <section className="nf-related-list" aria-label={props.relationship}>
            <h3>{props.relationship}</h3>
            {rows.length === 0 ? (
                <EmptyState message={`No ${props.relationship} rows`} />
            ) : (
                <table className="nf-table">
                    <thead>
                        <tr>
                            {columns.map((column) => (
                                <th key={column} scope="col">{column}</th>
                            ))}
                        </tr>
                    </thead>
                    <tbody>
                        {rows.slice(0, Number(props.pageSize ?? 50)).map((row, index) => (
                            <tr key={String(row.id ?? index)}>
                                {columns.map((column) => (
                                    <td key={column}>{row[column] == null ? "" : String(row[column])}</td>
                                ))}
                            </tr>
                        ))}
                    </tbody>
                </table>
            )}
        </section>
    );
}

export function RecordActions(props: { showEdit?: boolean; showDelete?: boolean }): ReactNode {
    const renderer = useRenderer();
    // Record-context only: the L1 list default carries this node too (list children
    // render now), but a list has no selected record — dead Edit/Delete buttons
    // there navigated to a bare "new" form and no-opped respectively
    if (!renderer.record?.id) {
        return null;
    }
    return (
        <div className="nf-record-actions" role="group" aria-label="Record actions">
            {props.showEdit !== false ? (
                <button
                    type="button"
                    onClick={() =>
                        renderer.navigate(renderer.entity!, "form", renderer.record?.id ? String(renderer.record.id) : undefined)
                    }
                >
                    Edit
                </button>
            ) : null}
            {props.showDelete !== false ? (
                <button type="button" className="nf-danger" onClick={() => void renderer.actions.deleteRecord()}>
                    Delete
                </button>
            ) : null}
        </div>
    );
}

function actionLabel(type: string): string {
    switch (type) {
        case "save":
            return "Save";
        case "cancel":
            return "Cancel";
        case "delete":
            return "Delete";
        default:
            return type;
    }
}

export function EmptyState(props: { message?: string; hint?: string }): ReactNode {
    return (
        <div className="nf-empty" role="status">
            <p>{props.message ?? "Nothing here"}</p>
            {props.hint ? <p className="nf-empty-hint">{props.hint}</p> : null}
        </div>
    );
}
