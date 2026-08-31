import { useEffect, useRef, useState, type ReactNode } from "react";
import { PlatformClient } from "@novaforge/shared";

/**
 * The approval inbox (PHASE-4 §5's runtime UI — the Phase 2 surface it rides):
 * my tasks (assigned or role-addressed), server-side paged, approve/reject with
 * comment, claim for role-addressed tasks, delegate (§11: "approve/reject with
 * comment, delegate"). Access is enforced server-side; this only renders.
 */
export function Inbox({ client }: { client: PlatformClient }): ReactNode {
    const [tasks, setTasks] = useState<Record<string, unknown>[]>([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(0);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const size = 25;
    const reloadSeq = useRef(0);

    const reload = (target: number): void => {
        // only the LATEST load commits (notifications' own fence): a page change
        // racing a resolve-triggered reload used to let the older request's rows
        // land last and clobber the newer page
        const seq = ++reloadSeq.current;
        setBusy(true);
        setError(null);
        client
            .myTasks(undefined, target, size)
            .then((result) => {
                if (seq !== reloadSeq.current) return;
                setTasks(result.rows);
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
        reload(page);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [client, page]);

    const run = async (op: () => Promise<unknown>): Promise<void> => {
        setBusy(true);
        setError(null);
        try {
            await op();
        } catch (caught) {
            setError(caught instanceof Error ? caught.message : String(caught));
            setBusy(false);
            return;
        }
        // the fenced reload owns busy AND the stale-response guard from here —
        // the inline fetch this replaced committed unconditionally, so a resolve
        // response could clobber a newer page's rows
        reload(page);
    };

    const resolve = (taskId: string, approve: boolean): Promise<void> =>
        run(async () => {
            const comment = approve ? undefined : window.prompt("Rejection comment");
            // cancelling the comment prompt cancels the rejection — `null ?? undefined`
            // turned the cancel into a comment-less reject (delegate's own contract)
            if (!approve && comment === null) return;
            await client.resolveTask(taskId, approve, comment ?? undefined);
        });

    const claim = (taskId: string): Promise<void> => run(() => client.claimTask(taskId));

    const delegate = (taskId: string): Promise<void> =>
        run(async () => {
            const toUser = window.prompt("Delegate to (user id)") ?? undefined;
            if (!toUser) return;
            await client.delegateTask(taskId, toUser);
        });

    return (
        <section className="nf-inbox" aria-busy={busy} aria-label="My approvals">
            <h2>My approvals</h2>
            {error ? <p role="alert">{error}</p> : null}
            <table className="nf-table">
                <thead>
                    <tr>
                        <th scope="col">Record</th>
                        <th scope="col">Addressed to</th>
                        <th scope="col">Requested by</th>
                        <th scope="col">Created</th>
                        <th scope="col">Resolve</th>
                    </tr>
                </thead>
                <tbody>
                    {tasks.map((task) => {
                        const addressed = task.assignee
                            ? String(task.assignee)
                            : `role: ${String(task.role ?? "")}`;
                        return (
                            <tr key={String(task.id)}>
                                <td>{String(task.entity ?? "")}{task.recordId ? ` (${String(task.recordId)})` : ""}</td>
                                <td>{addressed}</td>
                                <td>{String(task.createdBy ?? "")}</td>
                                <td>{String(task.createdAt ?? "")}</td>
                                <td>
                                    <button type="button" disabled={busy} onClick={() => void resolve(String(task.id), true)}>
                                        Approve
                                    </button>
                                    <button type="button" disabled={busy} onClick={() => void resolve(String(task.id), false)}>
                                        Reject
                                    </button>
                                    {!task.assignee && task.role ? (
                                        <button type="button" disabled={busy} onClick={() => void claim(String(task.id))}>
                                            Claim
                                        </button>
                                    ) : null}
                                    <button type="button" disabled={busy} onClick={() => void delegate(String(task.id))}>
                                        Delegate
                                    </button>
                                </td>
                            </tr>
                        );
                    })}
                </tbody>
            </table>
            {tasks.length === 0 && !busy ? <p role="status">No pending approvals.</p> : null}
            <div className="nf-pager">
                {/* busy owns the pager too: a click mid-resolve raced the reload's
                    older response against the new page's load (see reload's fence) */}
                <button type="button" disabled={page === 0 || busy} onClick={() => setPage(page - 1)}>Previous</button>
                <span>{page * size + 1}–{Math.min((page + 1) * size, total)} / {total}</span>
                <button type="button" disabled={(page + 1) * size >= total || busy} onClick={() => setPage(page + 1)}>Next</button>
            </div>
        </section>
    );
}
