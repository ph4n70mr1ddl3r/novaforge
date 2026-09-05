import { useEffect, useRef, useState, type ReactNode } from "react";
import { EmptyState, PlatformClient } from "@novaforge/shared";
import { formatWhen } from "./format.ts";

/**
 * The notification inbox + preferences (PHASE-4 §8's runtime UI): my platform
 * inbox rows (paged, mark-read) and the per-category channel toggles — coarse v1,
 * refined on demand. Own data only, enforced server-side; this only renders.
 */

/** The built-in categories v1 ships (§8) — later phases append as features land. */
const CATEGORIES = ["task-assignment", "sla-warning", "report-delivery", "job-completed"];

export function Notifications({ client }: { client: PlatformClient }): ReactNode {
    const [rows, setRows] = useState<Record<string, unknown>[] | null>(null);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(0);
    const [preferences, setPreferences] = useState<Record<string, { inbox: boolean; email: boolean }>>({});
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [flash, setFlash] = useState<string | null>(null);
    const size = 25;
    const reloadSeq = useRef(0);

    const reload = (target: number): void => {
        // only the LATEST load commits: a page change racing a markRead reload used
        // to let the older request's rows land last
        const seq = ++reloadSeq.current;
        setBusy(true);
        setError(null);
        client
            .notifications(target, size)
            .then((result) => {
                if (seq !== reloadSeq.current) return;
                setRows(result.rows);
                setTotal(result.total);
            })
            .catch((caught: unknown) => {
                if (seq !== reloadSeq.current) return;
                setError(caught instanceof Error ? caught.message : String(caught));
            })
            .finally(() => {
                if (seq === reloadSeq.current) {
                    setBusy(false);
                }
            });
    };

    useEffect(() => {
        let cancelled = false;
        client
            .notificationPreferences()
            .then((saved) => {
                if (cancelled) return;
                const map: Record<string, { inbox: boolean; email: boolean }> = {};
                for (const row of saved) {
                    map[String(row.category)] = {
                        inbox: row.inbox !== false,
                        email: row.email !== false,
                    };
                }
                setPreferences(map);
            })
            .catch(() => {
                // both channels default on when nothing is saved yet (§8)
            });
        return () => {
            cancelled = true;
        };
    }, [client]);

    useEffect(() => {
        reload(page);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [client, page]);

    const markRead = async (id: string): Promise<void> => {
        setBusy(true);
        try {
            await client.markNotificationRead(id);
        } catch (caught) {
            setError(caught instanceof Error ? caught.message : String(caught));
            setBusy(false);
            return;
        }
        // the fenced reload owns busy AND the stale-response guard from here —
        // the inline fetch this replaced neither bumped nor checked the sequence,
        // so a markRead response could clobber a newer page's rows
        reload(page);
    };

    const toggle = async (category: string, channel: "inbox" | "email"): Promise<void> => {
        const current = preferences[category] ?? { inbox: true, email: true };
        const next = { ...current, [channel]: !current[channel] };
        setPreferences((all) => ({ ...all, [category]: next }));
        try {
            await client.setNotificationPreference(category, next.inbox, next.email);
            setFlash(`Preferences saved for ${category}`);
        } catch (caught) {
            setPreferences((all) => ({ ...all, [category]: current }));
            setError(caught instanceof Error ? caught.message : String(caught));
        }
    };

    return (
        <section className="nf-notifications" aria-busy={busy} aria-label="My notifications">
            <h2>My notifications</h2>
            {error ? <p role="alert">{error}</p> : null}
            {flash ? <p role="status" aria-live="polite">{flash}</p> : null}
            <table className="nf-table">
                <thead>
                    <tr>
                        <th scope="col">Category</th>
                        <th scope="col">Title</th>
                        <th scope="col">Body</th>
                        <th scope="col">Received</th>
                        <th scope="col">Read</th>
                    </tr>
                </thead>
                <tbody>
                    {(rows ?? []).map((row) => (
                        <tr key={String(row.id)} data-read={row.read_at != null ? "true" : "false"}>
                            <td>{String(row.category ?? "")}</td>
                            <td className={row.read_at == null ? "nf-unread-title" : undefined}>{String(row.title ?? "")}</td>
                            <td>{String(row.body ?? "")}</td>
                            <td>
                                <time dateTime={String(row.created_at ?? "")}>{formatWhen(row.created_at)}</time>
                            </td>
                            <td>
                                {row.read_at != null ? (
                                    <time dateTime={String(row.read_at)}>{formatWhen(row.read_at)}</time>
                                ) : (
                                    <button type="button" disabled={busy}
                                        aria-label={`Mark read ${String(row.title ?? row.id)}`}
                                        onClick={() => void markRead(String(row.id))}>
                                        Mark read
                                    </button>
                                )}
                            </td>
                        </tr>
                    ))}
                </tbody>
            </table>
            {rows !== null && rows.length === 0 && !busy ? (
                <EmptyState
                    message="No notifications yet."
                    hint="Task assignments, SLA warnings, report deliveries, and job completions land here."
                />
            ) : null}
            {/* the inbox's pager rule: hidden at zero rows, where it only ever
                rendered the degenerate range "1–0 / 0" */}
            {total > 0 ? (
                <div className="nf-pager">
                    {/* busy owns the pager (the inbox's rule): a page flip mid-markRead
                        raced the reload's older response against the new page's load */}
                    <button type="button" disabled={page === 0 || busy} onClick={() => setPage(page - 1)}>Previous</button>
                    <span>{page * size + 1}–{Math.min((page + 1) * size, total)} / {total}</span>
                    <button type="button" disabled={(page + 1) * size >= total || busy} onClick={() => setPage(page + 1)}>Next</button>
                </div>
            ) : null}
            <fieldset>
                <legend>Channel preferences</legend>
                <table className="nf-table nf-preferences">
                    <thead>
                        <tr>
                            <th scope="col">Category</th>
                            <th scope="col">Inbox</th>
                            <th scope="col">Email</th>
                        </tr>
                    </thead>
                    <tbody>
                        {CATEGORIES.map((category) => {
                            const preference = preferences[category] ?? { inbox: true, email: true };
                            return (
                                <tr key={category} data-category={category}>
                                    <th scope="row">{category}</th>
                                    <td>
                                        <label className="nf-inline">
                                            <input type="checkbox"
                                                aria-label={`${category} inbox channel`}
                                                checked={preference.inbox}
                                                onChange={() => void toggle(category, "inbox")} />
                                        </label>
                                    </td>
                                    <td>
                                        <label className="nf-inline">
                                            <input type="checkbox"
                                                aria-label={`${category} email channel`}
                                                checked={preference.email}
                                                onChange={() => void toggle(category, "email")} />
                                        </label>
                                    </td>
                                </tr>
                            );
                        })}
                    </tbody>
                </table>
                <p className="nf-meta">Both channels default on (§8's coarse v1 shape).</p>
            </fieldset>
        </section>
    );
}
