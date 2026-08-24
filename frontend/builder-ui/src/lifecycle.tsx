import { useEffect, useState, type ReactNode } from "react";
import type { PlatformClient } from "@novaforge/shared";

/**
 * The lifecycle screens (PHASE-8 §2–§5, builder side): change-set review (per-
 * definition diffs, suite results hash-bound to the draft, script-ratio delta,
 * credential re-binding list, override history rendered forever), gated promotion
 * dev → staging → prod, rollback, and headless suite runs.
 */

interface ChangeSet {
    env?: string;
    fromVersion?: number;
    toVersion?: number;
    definitions?: { kind: string; apiName: string; change: "added" | "modified" | "removed" }[];
    suiteResults?: { suite: string; green: boolean; contentHash?: string }[];
    scriptRatioDelta?: number;
    credentialReferences?: string[];
    promotions?: { env: string; fromVersion?: number; toVersion?: number; overridden?: boolean; reason?: string; promotedBy?: string }[];
}

export function Lifecycle({ client, appId }: { client: PlatformClient; appId: string }): ReactNode {
    const [env, setEnv] = useState<"staging" | "prod">("staging");
    const [changeset, setChangeset] = useState<ChangeSet | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [flash, setFlash] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    const [ack, setAck] = useState(false);

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

    const gateGreen = (changeset?.suiteResults ?? []).every((result) => result.green);

    return (
        <section className="nf-b-lifecycle" aria-label="Promotion and change sets">
            <h2>Promotion</h2>
            {error ? <p role="alert">{error}</p> : null}
            {flash ? <p role="status" aria-live="polite">{flash}</p> : null}
            <label>
                Environment
                <select value={env} onChange={(event) => setEnv(event.target.value as typeof env)}>
                    <option value="staging">staging</option>
                    <option value="prod">prod</option>
                </select>
            </label>
            {changeset ? (
                <>
                    <h3>Change set</h3>
                    <table className="nf-table">
                        <thead>
                            <tr>
                                <th scope="col">Kind</th>
                                <th scope="col">Definition</th>
                                <th scope="col">Change</th>
                            </tr>
                        </thead>
                        <tbody>
                            {(changeset.definitions ?? []).map((definition, index) => (
                                <tr key={index}>
                                    <td>{definition.kind}</td>
                                    <td>{definition.apiName}</td>
                                    <td data-change={definition.change}>{definition.change}</td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                    <h3>Suite results (version-bound)</h3>
                    <ul>
                        {(changeset.suiteResults ?? []).map((result) => (
                            <li key={result.suite} data-green={result.green}>
                                {result.suite}: {result.green ? "green" : "red"}
                                {result.contentHash ? <code> {result.contentHash.slice(0, 12)}…</code> : null}
                            </li>
                        ))}
                        {(changeset.suiteResults ?? []).length === 0 ? <li>No suites (gate free)</li> : null}
                    </ul>
                    {changeset.credentialReferences?.length ? (
                        <>
                            <h3>Credentials to re-bind in {env}</h3>
                            <ul>
                                {changeset.credentialReferences.map((reference) => (
                                    <li key={reference}><code>{reference}</code></li>
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
                                        {promotion.env}: v{promotion.fromVersion} → v{promotion.toVersion}
                                        {promotion.overridden ? <strong> — OVERRIDE: {promotion.reason}</strong> : null}
                                    </li>
                                ))}
                            </ul>
                        </details>
                    ) : null}
                    <div className="nf-b-actions">
                        <button
                            type="button"
                            className="nf-action-primary"
                            disabled={busy || !gateGreen || (env === "prod" && !ack)}
                            data-testid="promote"
                            onClick={async () => {
                                setBusy(true);
                                try {
                                    await client.promote(appId, env, ack ? { override: true, reason: "admin override" } : {});
                                    setFlash(`Promoted to ${env}`);
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
                            Platform-admin approval / data-migration acknowledgment
                        </label>
                    ) : null}
                </>
            ) : (
                <p role="status">Loading change set…</p>
            )}
        </section>
    );
}

export function SuiteRuns({ client, appId }: { client: PlatformClient; appId: string }): ReactNode {
    const [runs, setRuns] = useState<Record<string, unknown> | null>(null);
    const [busy, setBusy] = useState(false);
    const [flash, setFlash] = useState<string | null>(null);
    const run = async (): Promise<void> => {
        setBusy(true);
        try {
            const result = (await client.runSuites(appId)) as Record<string, unknown>;
            setRuns(result);
            setFlash(result.green === true ? "All suites green" : "Suites red — see results");
        } finally {
            setBusy(false);
        }
    };
    return (
        <section aria-label="Suite runs">
            <h2>Suites</h2>
            <button type="button" className="nf-action-primary" disabled={busy} onClick={() => void run()}>
                Run all suites
            </button>
            {flash ? <p role="status" aria-live="polite">{flash}</p> : null}
            {runs ? <pre>{JSON.stringify(runs, null, 2)}</pre> : null}
        </section>
    );
}
