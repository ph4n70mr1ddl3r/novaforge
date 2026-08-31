import { useState, type ReactNode } from "react";
import type { AppDefinition, DashboardDefinition } from "@novaforge/shared";

/**
 * The dashboard composer (PHASE-5 §5/T6): a widget grid binding report refs with
 * per-widget run params and grid span, plus role-visibility composition. Saves
 * through the Metadata definition APIs (dashboards ride the app document); the
 * runtime renders the composed grid — a dashboard never widens what its viewer
 * may see (§8).
 *
 * Edits are LOCAL until the explicit Save (2026-08-31, fourteenth pass): every
 * keystroke used to PATCH the whole dashboards branch immediately over the
 * mount-time `app` snapshot — two rapid edits raced (the second payload built
 * before the first save's reload reverted it), out-of-order HTTP applied the
 * older list last, and another tab's dashboard was wiped by the stale
 * whole-branch replace. The whole list is rebuilt from a FRESH fetch at save
 * time, so only the edited dashboard's slot is replaced.
 */

export function DashboardComposer({
    app,
    saveDashboards,
}: {
    app: AppDefinition;
    saveDashboards: (mutate: (current: DashboardDefinition[]) => DashboardDefinition[]) => Promise<void>;
}): ReactNode {
    const [selectedId, setSelectedId] = useState<string | null>(app.dashboards[0]?.id ?? null);
    const [flash, setFlash] = useState<string | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    // the working copy: seeded from the app prop, mutated locally, saved explicitly
    const [edits, setEdits] = useState<Record<string, DashboardDefinition>>({});
    const dashboards = app.dashboards.map((dashboard) => edits[dashboard.id] ?? dashboard);
    const draft = dashboards.find((dashboard) => dashboard.id === selectedId) ?? null;
    const dirty = Object.keys(edits).length > 0;

    const update = (patch: Partial<DashboardDefinition>): void => {
        if (!draft) return;
        setEdits((current) => ({ ...current, [draft.id]: { ...draft, ...patch } }));
    };

    const persist = async (
        mutate: (current: DashboardDefinition[]) => DashboardDefinition[],
        thenSelect?: string,
    ): Promise<void> => {
        setBusy(true);
        // the snapshot of local edits this save carries: on success ONLY these keys
        // clear — wiping the whole map discarded edits made after the click (lost
        // mid-flight) and edits to other dashboards when New-dashboard saved an
        // unrelated append
        const sent = { ...edits };
        try {
            await saveDashboards(mutate);
            setEdits((current) => {
                const next = { ...current };
                for (const key of Object.keys(sent)) {
                    delete next[key];
                }
                return next;
            });
            if (thenSelect) {
                setSelectedId(thenSelect);
            }
            setFlash("Dashboard saved");
        } catch (caught) {
            setError(caught instanceof Error ? caught.message : String(caught));
        } finally {
            setBusy(false);
        }
    };

    const save = (): void => {
        // apply the local edits onto whatever the server holds NOW: the mutate
        // callback runs against the freshly fetched list inside saveDashboards
        void persist((current) => current.map((dashboard) => edits[dashboard.id] ?? dashboard));
    };

    return (
        <section className="nf-b-dashboards" aria-label="Dashboard composer">
            <h2>Dashboards</h2>
            <div className="nf-b-toolbar">
                <label>
                    Dashboard
                    <select value={selectedId ?? ""} onChange={(event) => setSelectedId(event.target.value || null)}>
                        <option value="">—</option>
                        {app.dashboards.map((dashboard) => (
                            <option key={dashboard.id} value={dashboard.id}>{dashboard.label ?? dashboard.id}</option>
                        ))}
                    </select>
                </label>
                <button
                    type="button"
                    disabled={dirty || busy}
                    onClick={() => {
                        // first dashN not already present (re-audit): length+1
                        // collided with a surviving dashN after out-of-band
                        // deletions left gaps in the id sequence
                        const taken = new Set(app.dashboards.map((dashboard) => dashboard.id));
                        let n = 1;
                        while (taken.has(`dash${n}`)) n += 1;
                        const id = `dash${n}`;
                        void persist(
                            (current) => [...current, { id, label: id, widgets: [], roles: [] }],
                            id,
                        );
                    }}
                >
                    New dashboard
                </button>
            </div>
            {flash ? <p role="status" aria-live="polite">{flash}</p> : null}
            {error ? <p role="alert">{error}</p> : null}
            <button
                type="button"
                className="nf-action-primary"
                disabled={!dirty || busy}
                onClick={save}
                data-testid="save-dashboards"
            >
                Save{dirty ? " •" : ""}
            </button>
            {draft ? (
                <>
                    <table className="nf-table">
                        <caption>{draft.label ?? draft.id}</caption>
                        <thead>
                            <tr>
                                <th scope="col">Widget</th>
                                <th scope="col">Report</th>
                                <th scope="col">Span (1–12)</th>
                                <th scope="col">Refresh (s)</th>
                                <th scope="col">Remove</th>
                            </tr>
                        </thead>
                        <tbody>
                            {draft.widgets.map((widget, index) => (
                                <tr key={index}>
                                    <td>
                                        <select
                                            aria-label={`widget kind ${index}`}
                                            value={widget.widget}
                                            onChange={(event) =>
                                                update({
                                                    widgets: draft.widgets.map((candidate, i) =>
                                                        i === index ? { ...candidate, widget: event.target.value as typeof candidate.widget } : candidate),
                                                })
                                            }
                                        >
                                            {["kpi", "chart", "table"].map((kind) => (
                                                <option key={kind} value={kind}>{kind}</option>
                                            ))}
                                        </select>
                                    </td>
                                    <td>
                                        <select
                                            aria-label={`widget report ${index}`}
                                            value={widget.reportRef}
                                            onChange={(event) =>
                                                update({
                                                    widgets: draft.widgets.map((candidate, i) =>
                                                        i === index ? { ...candidate, reportRef: event.target.value } : candidate),
                                                })
                                            }
                                        >
                                            {app.reports.map((report) => (
                                                <option key={report.id} value={report.id}>{report.id}</option>
                                            ))}
                                        </select>
                                    </td>
                                    <td>
                                        <input
                                            type="number"
                                            min={1}
                                            max={12}
                                            aria-label={`widget span ${index}`}
                                            value={widget.span ?? 6}
                                            onChange={(event) =>
                                                update({
                                                    widgets: draft.widgets.map((candidate, i) =>
                                                        i === index ? { ...candidate, span: Number(event.target.value) } : candidate),
                                                })
                                            }
                                        />
                                    </td>
                                    <td>
                                        <input
                                            type="number"
                                            min={5}
                                            max={3600}
                                            aria-label={`widget refresh seconds ${index}`}
                                            title="client-timer auto-refresh — blank keeps the widget static (§5)"
                                            value={widget.refreshSeconds ?? ""}
                                            onChange={(event) =>
                                                update({
                                                    widgets: draft.widgets.map((candidate, i) =>
                                                        i === index
                                                            ? { ...candidate,
                                                                refreshSeconds: event.target.value
                                                                    ? Number(event.target.value)
                                                                    : undefined }
                                                            : candidate),
                                                })
                                            }
                                        />
                                    </td>
                                    <td>
                                        <button
                                            type="button"
                                            aria-label={`remove widget ${index}`}
                                            onClick={() =>
                                                update({ widgets: draft.widgets.filter((_, i) => i !== index) })
                                            }
                                        >
                                            ×
                                        </button>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                    <button
                        type="button"
                        disabled={app.reports.length === 0 || busy}
                        onClick={() =>
                            update({
                                widgets: [...draft.widgets, { widget: "kpi", reportRef: app.reports[0]!.id, span: 4 }],
                            })
                        }
                    >
                        Add widget
                    </button>
                    <fieldset>
                        <legend>Role visibility (composition only — §8)</legend>
                        {app.permissionSet.roles.map((role) => (
                            <label key={role.name} className="nf-inline">
                                <input
                                    type="checkbox"
                                    checked={!draft.roles?.length || draft.roles.includes(role.name)}
                                    onChange={(event) => {
                                        // empty roles = implicitly every role — an uncheck makes the
                                        // remaining selection explicit
                                        const current = new Set(
                                            draft.roles?.length
                                                ? draft.roles
                                                : app.permissionSet.roles.map((role) => role.name),
                                        );
                                        if (event.target.checked) {
                                            current.add(role.name);
                                        } else {
                                            current.delete(role.name);
                                        }
                                        update({ roles: [...current] });
                                    }}
                                />
                                {role.name}
                            </label>
                        ))}
                        <p className="nf-hint">No roles selected = visible to every role of the app.</p>
                    </fieldset>
                </>
            ) : (
                <p>Select or create a dashboard.</p>
            )}
        </section>
    );
}
