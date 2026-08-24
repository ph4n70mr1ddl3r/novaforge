import { useEffect, useState, type ReactNode } from "react";
import { PlatformClient } from "@novaforge/shared";

/**
 * The approval inbox (PHASE-4 §5's runtime UI — the Phase 2 surface it rides):
 * my tasks (assigned or role-addressed), server-side paged, approve/reject with
 * comment. Access is enforced server-side; this only renders.
 */
export function Inbox({ client }: { client: PlatformClient }): ReactNode {
    const [tasks, setTasks] = useState<Record<string, unknown>[]>([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(0);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const size = 25;

    useEffect(() => {
        let cancelled = false;
        setBusy(true);
        client
            .myTasks(undefined, page, size)
            .then((result) => {
                if (!cancelled) {
                    setTasks(result.rows);
                    setTotal(result.total);
                }
            })
            .catch((caught: unknown) => {
                if (!cancelled) setError(caught instanceof Error ? caught.message : String(caught));
            })
            .finally(() => {
                if (!cancelled) setBusy(false);
            });
        return () => {
            cancelled = true;
        };
    }, [client, page]);

    const resolve = async (taskId: string, approve: boolean): Promise<void> => {
        const comment = approve ? undefined : window.prompt("Rejection comment") ?? undefined;
        setBusy(true);
        try {
            await client.resolveTask(taskId, approve, comment);
            const result = await client.myTasks(undefined, page, size);
            setTasks(result.rows);
            setTotal(result.total);
        } catch (caught) {
            setError(caught instanceof Error ? caught.message : String(caught));
        } finally {
            setBusy(false);
        }
    };

    return (
        <section className="nf-inbox" aria-busy={busy} aria-label="My approvals">
            <h2>My approvals</h2>
            {error ? <p role="alert">{error}</p> : null}
            <table className="nf-table">
                <thead>
                    <tr>
                        <th scope="col">Record</th>
                        <th scope="col">Action</th>
                        <th scope="col">Requested by</th>
                        <th scope="col">Created</th>
                        <th scope="col">Resolve</th>
                    </tr>
                </thead>
                <tbody>
                    {tasks.map((task) => (
                        <tr key={String(task.id)}>
                            <td>{String(task.entityLabel ?? task.entity ?? "")}</td>
                            <td>{String(task.action ?? "approve")}</td>
                            <td>{String(task.createdBy ?? "")}</td>
                            <td>{String(task.createdAt ?? "")}</td>
                            <td>
                                <button type="button" disabled={busy} onClick={() => void resolve(String(task.id), true)}>
                                    Approve
                                </button>
                                <button type="button" disabled={busy} onClick={() => void resolve(String(task.id), false)}>
                                    Reject
                                </button>
                            </td>
                        </tr>
                    ))}
                </tbody>
            </table>
            {tasks.length === 0 && !busy ? <p role="status">No pending approvals.</p> : null}
            <div className="nf-pager">
                <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
                <span>{page * size + 1}–{Math.min((page + 1) * size, total)} / {total}</span>
                <button type="button" disabled={(page + 1) * size >= total} onClick={() => setPage(page + 1)}>Next</button>
            </div>
        </section>
    );
}
