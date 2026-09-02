import { useEffect, useState, type ReactNode } from "react";
import { mergeBranch } from "./branch-merge.ts";
import type { AppDefinition, GapLogEntry, PlatformClient } from "@novaforge/shared";

/**
 * The lifecycle screens (PHASE-8 §2–§5, builder side): change-set review (per-
 * definition diffs, suite results hash-bound to the draft, script-ratio delta,
 * credential re-binding list, the gap-log entries the version resolves, override
 * history rendered forever), gated promotion dev → staging → prod, rollback, and
 * headless suite runs. The gap-log editor beside them is the Phase 7 dogfood
 * discipline's authoring surface (PHASE-7 §1 rule 2): every gap becomes a log
 * entry before any workaround.
 */

/** The change-set review payload exactly as the Metadata Service shapes it (§3). */
interface ChangeSet {
    env?: string;
    publishedVersion?: number | null;
    diff?: Record<string, { added?: string[]; modified?: string[]; removed?: string[] }>;
    suiteResults?: { suite: string; green: boolean | null; runAt?: string }[];
    scriptRatio?: {
        draft: number;
        published: number;
        modules?: Record<string, { hooks: number; scripts: number; scriptShare: number }>;
    };
    credentialRefs?: string[];
    resolvedGaps?: { id: string; area: string; disposition: string; resolvedIn?: string; proposed?: string }[];
    promotions?: { env: string; kind?: string; toVersion: number; overridden?: boolean; reason?: string; at?: string }[];
}

/** Flattens the per-branch diff into review rows (kind / definition / change). */
function diffRows(diff: ChangeSet["diff"]): { kind: string; apiName: string; change: "added" | "modified" | "removed" }[] {
    const rows: { kind: string; apiName: string; change: "added" | "modified" | "removed" }[] = [];
    for (const [kind, branch] of Object.entries(diff ?? {})) {
        if (kind === "permissionSetChanged") continue;
        for (const apiName of branch.added ?? []) rows.push({ kind, apiName, change: "added" });
        for (const apiName of branch.modified ?? []) rows.push({ kind, apiName, change: "modified" });
        for (const apiName of branch.removed ?? []) rows.push({ kind, apiName, change: "removed" });
    }
    return rows;
}

export function Lifecycle({ client, appId }: { client: PlatformClient; appId: string }): ReactNode {
    const [env, setEnv] = useState<"staging" | "prod">("staging");
    const [changeset, setChangeset] = useState<ChangeSet | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [flash, setFlash] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    // The prod hop's approval acknowledgment — NEVER the override switch: a checked
    // ack used to ride into a later staging promotion as {override: true} and land
    // in the permanent override history with a fabricated reason.
    const [ack, setAck] = useState(false);
    // The red-gate bypass is its own explicit, reasoned action (§4 item 3) — the
    // old disabled-when-red button made overrides unreachable while still sending
    // override:true for every acked prod promote.
    const [overrideGate, setOverrideGate] = useState(false);
    const [overrideReason, setOverrideReason] = useState("");

    useEffect(() => {
        let cancelled = false;
        client
            .changeset(appId, env)
            .then((result) => {
                if (!cancelled) {
                    setChangeset(result as ChangeSet);
                }
            })
            .catch((caught: unknown) => {
                if (!cancelled) setError(caught instanceof Error ? caught.message : String(caught));
            });
        return () => {
            cancelled = true;
        };
    }, [client, appId, env]);

    const suiteResults = changeset?.suiteResults ?? [];
    const gateGreen = suiteResults.length === 0
        || suiteResults.every((result) => result.green === true);
    const rows = diffRows(changeset?.diff);

    return (
        <section className="nf-b-lifecycle" aria-label="Promotion and change sets">
            <h2>Promotion</h2>
            {error ? <p role="alert">{error}</p> : null}
            {flash ? <p role="status" aria-live="polite">{flash}</p> : null}
            <div className="nf-b-publish">
                <button
                    type="button"
                    data-testid="publish"
                    disabled={busy}
                    onClick={async () => {
                        setBusy(true);
                        try {
                            // The dev publish (PHASE-1 §4): validation + compatibility
                            // check; a published version is what the gate promotes —
                            // without this control the builder could author but never
                            // ship (found wiring the PHASE-2 §11 golden journey).
                            const version = await client.publish(appId) as { version?: number };
                            setFlash(`Published v${version?.version ?? "?"}`);
                            const fresh = await client.changeset(appId, env) as ChangeSet;
                            setChangeset(fresh);
                        } catch (caught) {
                            setError(caught instanceof Error ? caught.message : String(caught));
                        } finally {
                            setBusy(false);
                        }
                    }}
                >
                    Publish dev version
                </button>
                <p className="nf-hint">Publishes the draft (validation + compatibility check). Promotion below is gated on suite runs against the published version.</p>
            </div>
            <label>
                Environment
                <select
                    value={env}
                    onChange={(event) => {
                        setEnv(event.target.value as typeof env);
                        // the acknowledgment belongs to ONE environment's hop —
                        // carrying it across the switch promoted staging with a
                        // phantom admin override
                        setAck(false);
                        setOverrideGate(false);
                    }}
                >
                    <option value="staging">staging</option>
                    <option value="prod">prod</option>
                </select>
            </label>
            {changeset ? (
                <>
                    <h3>Change set{changeset.publishedVersion ? ` (from v${changeset.publishedVersion})` : ""}</h3>
                    {rows.length === 0 ? (
                        <p>No definition changes against the published version.</p>
                    ) : (
                        <table className="nf-table">
                            <thead>
                                <tr>
                                    <th scope="col">Kind</th>
                                    <th scope="col">Definition</th>
                                    <th scope="col">Change</th>
                                </tr>
                            </thead>
                            <tbody>
                                {rows.map((definition, index) => (
                                    <tr key={`${definition.kind}-${definition.apiName}-${index}`}>
                                        <td>{definition.kind}</td>
                                        <td>{definition.apiName}</td>
                                        <td data-change={definition.change}>{definition.change}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    )}
                    <h3>Suite results (version-bound)</h3>
                    <ul>
                        {suiteResults.map((result) => (
                            <li key={result.suite} data-green={result.green}>
                                {result.suite}: {result.green === null ? "not run against this draft" : result.green ? "green" : "red"}
                                {result.runAt ? <code> {result.runAt}</code> : null}
                            </li>
                        ))}
                        {suiteResults.length === 0 ? <li>No suites (gate free)</li> : null}
                    </ul>
                    {changeset.scriptRatio ? (
                        <p data-testid="script-ratio">
                            Script ratio: draft {(changeset.scriptRatio.draft * 100).toFixed(0)}% —
                            published {(changeset.scriptRatio.published * 100).toFixed(0)}%
                            {changeset.scriptRatio.modules
                                ? ` — per module: ${Object.entries(changeset.scriptRatio.modules)
                                      .map(([module, row]) => `${module} ${(row.scriptShare * 100).toFixed(0)}% (${row.scripts}/${row.hooks})`)
                                      .join(" · ")}`
                                : ""}
                        </p>
                    ) : null}
                    {changeset.credentialRefs?.length ? (
                        <>
                            <h3>Credentials to re-bind in {env}</h3>
                            <ul>
                                {changeset.credentialRefs.map((reference) => (
                                    <li key={reference}><code>{reference}</code></li>
                                ))}
                            </ul>
                        </>
                    ) : null}
                    {changeset.resolvedGaps?.length ? (
                        <>
                            <h3>Gap-log entries this version resolves</h3>
                            <ul className="nf-resolved-gaps">
                                {changeset.resolvedGaps.map((gap) => (
                                    <li key={gap.id} data-gap={gap.id}>
                                        <code>{gap.id}</code> ({gap.area}) — {gap.disposition}
                                        {gap.resolvedIn ? `: ${gap.resolvedIn}` : ""}
                                    </li>
                                ))}
                            </ul>
                        </>
                    ) : null}
                    {(changeset.promotions ?? []).length ? (
                        <details>
                            <summary>Promotion history (overrides visible forever)</summary>
                            <ul>
                                {changeset.promotions!.map((promotion, index) => (
                                    <li key={index}>
                                        {promotion.env}: → v{promotion.toVersion}
                                        {promotion.overridden ? <strong> — OVERRIDE: {promotion.reason}</strong> : null}
                                    </li>
                                ))}
                            </ul>
                        </details>
                    ) : null}
                    {!gateGreen ? (
                        <div className="nf-b-override" role="group" aria-label="Gate override">
                            <p role="alert">The promotion gate is red — a platform admin may override with a recorded reason (visible in the history forever).</p>
                            <label className="nf-inline">
                                <input
                                    type="checkbox"
                                    checked={overrideGate}
                                    onChange={(event) => setOverrideGate(event.target.checked)}
                                />
                                Override the red gate (platform admin)
                            </label>
                            <input
                                aria-label="Override reason"
                                placeholder="recorded reason (required)"
                                value={overrideReason}
                                onChange={(event) => setOverrideReason(event.target.value)}
                            />
                        </div>
                    ) : null}
                    <div className="nf-b-actions">
                        <button
                            type="button"
                            className="nf-action-primary"
                            disabled={busy
                                || (!gateGreen && !(overrideGate && overrideReason.trim().length > 0))
                                || (env === "prod" && !ack)}
                            data-testid="promote"
                            onClick={async () => {
                                setBusy(true);
                                try {
                                    await client.promote(
                                        appId,
                                        env,
                                        !gateGreen && overrideGate && overrideReason.trim()
                                            ? { override: true, reason: overrideReason.trim() }
                                            : {},
                                    );
                                    setFlash(`Promoted to ${env}`);
                                    setOverrideGate(false);
                                    setOverrideReason("");
                                } catch (caught) {
                                    setError(caught instanceof Error ? caught.message : String(caught));
                                } finally {
                                    setBusy(false);
                                }
                            }}
                        >
                            Promote to {env}
                        </button>
                        <button
                            type="button"
                            disabled={busy}
                            onClick={async () => {
                                setBusy(true);
                                try {
                                    const previous = Number(
                                        window.prompt("Roll back to version") ?? 0,
                                    );
                                    if (previous > 0) {
                                        await client.rollback(appId, env, { toVersion: previous, dataMigrationAcknowledged: ack });
                                        setFlash(`Rolled back to v${previous}`);
                                    }
                                } catch (caught) {
                                    setError(caught instanceof Error ? caught.message : String(caught));
                                } finally {
                                    setBusy(false);
                                }
                            }}
                        >
                            Roll back…
                        </button>
                    </div>
                    {env === "prod" ? (
                        <label className="nf-inline">
                            <input type="checkbox" checked={ack} onChange={(event) => setAck(event.target.checked)} />
                            Platform-admin approval for the prod hop (§4)
                        </label>
                    ) : null}
                </>
            ) : (
                <p role="status">Loading change set…</p>
            )}
        </section>
    );
}

const DISPOSITIONS: GapLogEntry["disposition"][] = [
    "open", "accept-as-platform-feature", "backlog", "wontfix-with-workaround", "closed",
];
const PRIORITIES: GapLogEntry["priority"][] = ["high", "medium", "low"];

/**
 * The gap-log editor (PHASE-7 §1 rule 2): the dogfood discipline's authoring
 * surface — log every gap before working around it; triage disposition arrives
 * with the weekly review (§8). Saves ride the app PATCH's gapLog branch like
 * every other definition.
 */
export function GapLogEditor({
    app,
    onSave,
}: {
    app: AppDefinition;
    /** The gapLog patch builds from a FRESH app fetch (the dashboards rule) — a mount-time snapshot saved verbatim deleted another tab's concurrent entries. */
    onSave: (patch: (fresh: AppDefinition) => Record<string, unknown>) => Promise<void>;
}): ReactNode {
    const [entries, setEntries] = useState<GapLogEntry[]>(app.gapLog ?? []);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [flash, setFlash] = useState<string | null>(null);

    const update = (id: string, changes: Partial<GapLogEntry>): void =>
        setEntries((current) => current.map((entry) => (entry.id === id ? { ...entry, ...changes } : entry)));

    const save = async (): Promise<void> => {
        setBusy(true);
        setError(null);
        try {
            await onSave((fresh) => ({
                // the dashboards rule: this editor's triage stands for what its
                // mount knew; an entry another tab logged after mount survives
                gapLog: mergeBranch(entries, app.gapLog, fresh.gapLog, (entry) => entry.id),
            }));
            setFlash("Saved gap log");
        } catch (caught) {
            setError(caught instanceof Error ? caught.message : String(caught));
        } finally {
            setBusy(false);
        }
    };

    return (
        <section className="nf-b-gaplog" aria-label="Gap log">
            <h2>Gap log</h2>
            <p className="nf-hint">
                Every gap becomes a log entry before any workaround (PHASE-7 §1 rule 2);
                change-set review renders the entries a promoting version resolves.
            </p>
            {error ? <p role="alert">{error}</p> : null}
            {flash ? <p role="status" aria-live="polite">{flash}</p> : null}
            <table className="nf-table nf-gaplog">
                <thead>
                    <tr>
                        <th scope="col">Id</th>
                        <th scope="col">Area</th>
                        <th scope="col">Blocker</th>
                        <th scope="col">Priority</th>
                        <th scope="col">Disposition</th>
                        <th scope="col">Resolved in</th>
                    </tr>
                </thead>
                <tbody>
                    {entries.map((entry, index) => (
                        <tr key={entry.id ?? index} data-gap={entry.id}>
                            <td><code>{entry.id}</code></td>
                            <td>{entry.area}</td>
                            <td>{entry.blocker}</td>
                            <td>
                                <select aria-label={`Priority of ${entry.id}`} value={entry.priority}
                                    onChange={(event) => update(entry.id, { priority: event.target.value as GapLogEntry["priority"] })}>
                                    {PRIORITIES.map((priority) => (
                                        <option key={priority} value={priority}>{priority}</option>
                                    ))}
                                </select>
                            </td>
                            <td>
                                <select aria-label={`Disposition of ${entry.id}`} value={entry.disposition}
                                    onChange={(event) => update(entry.id, { disposition: event.target.value as GapLogEntry["disposition"] })}>
                                    {DISPOSITIONS.map((disposition) => (
                                        <option key={disposition} value={disposition}>{disposition}</option>
                                    ))}
                                </select>
                            </td>
                            <td>
                                <input aria-label={`Resolved in (${entry.id})`} value={entry.resolvedIn ?? ""}
                                    onChange={(event) => update(entry.id, { resolvedIn: event.target.value || undefined })} />
                            </td>
                        </tr>
                    ))}
                    {entries.length === 0 ? <tr><td colSpan={6}>No gaps logged.</td></tr> : null}
                </tbody>
            </table>
            <div className="nf-b-actions">
                <button
                    type="button"
                    disabled={busy}
                    onClick={() =>
                        setEntries((current) => {
                            // first G-N not already present (re-audit): length+1
                            // collided with a surviving G-N after out-of-band
                            // deletions left gaps in the id sequence
                            const taken = new Set(current.map((entry) => entry.id));
                            let n = 1;
                            while (taken.has(`G-${n}`)) n += 1;
                            return [
                                ...current,
                                {
                                    id: `G-${n}`,
                                    area: "",
                                    blocker: "",
                                    priority: "medium",
                                    disposition: "open",
                                },
                            ];
                        })
                    }
                >
                    Add gap
                </button>
                <button type="button" className="nf-action-primary" disabled={busy} onClick={() => void save()}>
                    Save gap log
                </button>
            </div>
        </section>
    );
}

export function SuiteRuns({ client, appId }: { client: PlatformClient; appId: string }): ReactNode {
    const [runs, setRuns] = useState<Record<string, unknown> | null>(null);
    const [busy, setBusy] = useState(false);
    const [flash, setFlash] = useState<string | null>(null);
    const [error, setError] = useState<string | null>(null);

    const run = async (): Promise<void> => {
        setBusy(true);
        try {
            const result = (await client.runSuites(appId)) as Record<string, unknown>;
            setRuns(result);
            setFlash(result.green === true ? "All suites green" : "Suites red — see results");
        } catch (caught) {
            setError(caught instanceof Error ? caught.message : String(caught));
        } finally {
            setBusy(false);
        }
    };
    return (
        <section aria-label="Suite runs">
            <h2>Suites</h2>
            {error ? <p role="alert">{error}</p> : null}
            <button type="button" className="nf-action-primary" disabled={busy} onClick={() => void run()}>
                Run all suites
            </button>
            {flash ? <p role="status" aria-live="polite">{flash}</p> : null}
            {runs ? <pre>{JSON.stringify(runs, null, 2)}</pre> : null}
        </section>
    );
}
