import { useEffect, useRef, useState, type ReactNode } from "react";
import { PlatformClient } from "@novaforge/shared";
import { formatWhen } from "./format.ts";

/**
 * The approval inbox (PHASE-4 §5's runtime UI — the Phase 2 surface it rides):
 * my tasks (assigned or role-addressed), server-side paged, approve/reject with
 * comment, claim for role-addressed tasks, delegate (§11: "approve/reject with
 * comment, delegate"). Access is enforced server-side; this only renders.
 */

/** The ask-for-a-value panel that replaced window.prompt: blocking prompts were
 * unstylable, dumped focus, and sat entirely outside the design system. */
interface AskDialog {
    title: string;
    label: string;
    submitLabel: string;
    multiline: boolean;
    onSubmit: (value: string) => void;
}

export function Inbox({ client }: { client: PlatformClient }): ReactNode {
    const [tasks, setTasks] = useState<Record<string, unknown>[]>([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(0);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [ask, setAsk] = useState<AskDialog | null>(null);
    const [askValue, setAskValue] = useState("");
    const size = 25;
    const reloadSeq = useRef(0);

    // Dialog modality: Escape cancels (document-level — the scrim's key handler
    // only worked while focus happened to be inside), Tab is TRAPPED (it used to
    // cycle into the table behind the modal), and closing restores focus to the
    // trigger so keyboard users aren't dumped back at <body>. The return target
    // is captured at OPEN time — an effect capture would run after the commit,
    // when the dialog's own autofocus already owns focus.
    const restoreFocusRef = useRef<HTMLElement | null>(null);
    const openAsk = (dialog: AskDialog): void => {
        restoreFocusRef.current =
            document.activeElement instanceof HTMLElement ? document.activeElement : null;
        setAskValue("");
        setAsk(dialog);
    };
    useEffect(() => {
        if (!ask) {
            return;
        }
        const onKey = (event: KeyboardEvent): void => {
            if (event.key === "Escape") {
                event.stopPropagation();
                setAsk(null);
                return;
            }
            if (event.key !== "Tab") {
                return;
            }
            const dialog = document.querySelector(".nf-dialog");
            if (!(dialog instanceof HTMLElement)) {
                return;
            }
            const focusable = Array.from(
                dialog.querySelectorAll<HTMLElement>("button, input, textarea, select, a[href]"),
            ).filter((element) => !element.hasAttribute("disabled"));
            if (focusable.length === 0) {
                return;
            }
            const first = focusable[0]!;
            const last = focusable[focusable.length - 1]!;
            const active = document.activeElement;
            if (event.shiftKey && (active === first || !(active instanceof Node) || !dialog.contains(active))) {
                event.preventDefault();
                last.focus();
            } else if (!event.shiftKey && active === last) {
                event.preventDefault();
                first.focus();
            }
        };
        document.addEventListener("keydown", onKey, true);
        return () => {
            document.removeEventListener("keydown", onKey, true);
            restoreFocusRef.current?.focus();
        };
    }, [ask]);

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

    const approve = (taskId: string): Promise<void> => run(() => client.resolveTask(taskId, true));

    const reject = (taskId: string): void => {
        openAsk({
            title: "Reject task",
            label: "Rejection comment",
            submitLabel: "Reject",
            multiline: true,
            // cancelling the dialog cancels the rejection — the old prompt's
            // `null ?? undefined` turned a cancel into a comment-less reject
            onSubmit: (comment) => {
                void run(() => client.resolveTask(taskId, false, comment || undefined));
            },
        });
    };

    const claim = (taskId: string): Promise<void> => run(() => client.claimTask(taskId));

    const delegate = (taskId: string): void => {
        openAsk({
            title: "Delegate task",
            label: "Delegate to (user id)",
            submitLabel: "Delegate",
            multiline: false,
            onSubmit: (toUser) => {
                if (!toUser) return;
                void run(() => client.delegateTask(taskId, toUser));
            },
        });
    };

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
                                <td>
                                    {/* the raw ISO stayed machine-readable in dateTime */}
                                    <time dateTime={String(task.createdAt ?? "")}>{formatWhen(task.createdAt)}</time>
                                </td>
                                <td>
                                    <span className="nf-row-actions">
                                        <button type="button" disabled={busy} onClick={() => void approve(String(task.id))}>
                                            Approve
                                        </button>
                                        <button type="button" disabled={busy} onClick={() => reject(String(task.id))}>
                                            Reject
                                        </button>
                                        {!task.assignee && task.role ? (
                                            <button type="button" disabled={busy} onClick={() => void claim(String(task.id))}>
                                                Claim
                                            </button>
                                        ) : null}
                                        <button type="button" disabled={busy} onClick={() => delegate(String(task.id))}>
                                            Delegate
                                        </button>
                                    </span>
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
            {ask ? (
                // scrim click and Escape both cancel — a prompt's cancel button was
                // the ONLY escape hatch, and the browser-owned dialog fought the app
                <div className="nf-dialog-scrim" onClick={() => setAsk(null)}>
                    <div
                        role="dialog"
                        aria-modal="true"
                        aria-label={ask.title}
                        className="nf-dialog"
                        onClick={(event) => event.stopPropagation()}
                    >
                        <h3>{ask.title}</h3>
                        <label>
                            {ask.label}
                            {ask.multiline ? (
                                <textarea
                                    autoFocus
                                    rows={3}
                                    value={askValue}
                                    onChange={(event) => setAskValue(event.target.value)}
                                />
                            ) : (
                                <input
                                    autoFocus
                                    type="text"
                                    value={askValue}
                                    onChange={(event) => setAskValue(event.target.value)}
                                    onKeyDown={(event) => {
                                        if (event.key === "Enter") {
                                            ask.onSubmit(askValue.trim());
                                            setAsk(null);
                                        }
                                    }}
                                />
                            )}
                        </label>
                        <div className="nf-dialog-actions">
                            <button type="button" onClick={() => setAsk(null)}>Cancel</button>
                            <button
                                type="button"
                                className="nf-action-primary"
                                disabled={ask.multiline ? false : askValue.trim() === ""}
                                onClick={() => {
                                    ask.onSubmit(askValue.trim());
                                    setAsk(null);
                                }}
                            >
                                {ask.submitLabel}
                            </button>
                        </div>
                    </div>
                </div>
            ) : null}
        </section>
    );
}
