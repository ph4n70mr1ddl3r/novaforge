import { useEffect, useState, type ReactNode } from "react";
import { JsonTextField } from "./json-field.tsx";
import { mergeBranch } from "./branch-merge.ts";
import {
    type AppDefinition,
    type EntityDefinition,
    type ScheduledJobDefinition,
    type ScheduledJobTarget,
    type SlaDefinition,
    type StateMachineDefinition,
} from "@novaforge/shared";
import type { PlatformClient } from "@novaforge/shared";

/**
 * The workflow authoring surface (PHASE-4 §11): the state-machine designer over the
 * §3 schema (stateField bound to an enum field, states with terminal flags,
 * transitions with guard expressions — compile-checked at save like every authored
 * expression), the SLA definitions of §6 (the governed overlay over
 * requestApproval's own timers), and scheduled-job authoring (§7 — cron, target,
 * params, enabled; the registry itself stays publish-driven) beside the read-only
 * scheduler status route. Structural editors, not a free-form canvas — the same v1
 * shape the page and logic editors take (§16 Q1's designer deferral).
 */

export interface AutomationProps {
    app: AppDefinition;
    client: PlatformClient;
    /**
     * Saves a metadata branch patch ({stateMachines} / {slas} / {jobs}) built from
     * a FRESH app fetch: the patch callback receives the just-fetched app so the
     * saved branch can merge around concurrent additions (the dashboards rule) —
     * a mount-time snapshot saved verbatim deleted another tab's work.
     */
    onSave: (patch: (fresh: AppDefinition) => Record<string, unknown>) => Promise<void>;
}

const JOB_TARGETS: ScheduledJobTarget[] = ["flow", "script", "processStart", "report"];

export function Automation({ app, client, onSave }: AutomationProps): ReactNode {
    const [machines, setMachines] = useState<StateMachineDefinition[]>(app.stateMachines ?? []);
    const [slas, setSlas] = useState<SlaDefinition[]>(app.slas ?? []);
    const [jobs, setJobs] = useState<ScheduledJobDefinition[]>(app.jobs ?? []);
    const [registry, setRegistry] = useState<Record<string, unknown>[] | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [flash, setFlash] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);

    useEffect(() => {
        let cancelled = false;
        client
            .schedulerJobs()
            .then((rows) => {
                if (!cancelled) setRegistry(rows);
            })
            .catch(() => {
                if (!cancelled) setRegistry([]);
            });
        return () => {
            cancelled = true;
        };
    }, [client]);

    const save = async (
        patch: (fresh: AppDefinition) => Record<string, unknown>,
        what: string,
    ): Promise<void> => {
        setError(null);
        setBusy(true);
        try {
            await onSave(patch);
            setFlash(`Saved ${what}`);
        } catch (caught) {
            setError(caught instanceof Error ? caught.message : String(caught));
        } finally {
            setBusy(false);
        }
    };

    return (
        <section className="nf-b-automation" aria-label="Workflow and automation">
            {error ? <p role="alert">{error}</p> : null}
            {flash ? <p role="status" aria-live="polite">{flash}</p> : null}
            <MachineDesigner entities={app.entities} machines={machines} onChange={setMachines}
                busy={busy} onSave={() => save((fresh) => ({
                    // the dashboards rule: this editor's intent stands for what its
                    // mount knew; a machine another tab added after mount survives
                    stateMachines: mergeBranch(machines, app.stateMachines, fresh.stateMachines, (m) => m.id),
                }), "state machines")} />
            <SlaEditor slas={slas} onChange={setSlas} busy={busy}
                onSave={() => save((fresh) => ({
                    slas: mergeBranch(slas, app.slas, fresh.slas, (s) => s.id),
                }), "SLAs")} />
            <JobsEditor jobs={jobs} onChange={setJobs} busy={busy}
                onSave={() => save((fresh) => ({
                    jobs: mergeBranch(jobs, app.jobs, fresh.jobs, (j) => j.name),
                }), "scheduled jobs")} />
            <SchedulerStatus rows={registry} />
        </section>
    );
}

// --- state machines (§3): one per entity, stateField an enum field, guards compiled ---

function MachineDesigner({
    entities,
    machines,
    onChange,
    busy,
    onSave,
}: {
    entities: EntityDefinition[];
    machines: StateMachineDefinition[];
    onChange: (machines: StateMachineDefinition[]) => void;
    busy?: boolean;
    onSave: () => Promise<void>;
}): ReactNode {
    const enumFields = (entity: EntityDefinition): string[] =>
        entity.fields.filter((field) => field.type === "enum").map((field) => field.apiName);
    const machinable = entities.filter(
        (entity) => enumFields(entity).length > 0 && !machines.some((m) => m.entity === entity.apiName),
    );
    const update = (id: string, changes: Partial<StateMachineDefinition>): void =>
        onChange(machines.map((machine) => (machine.id === id ? { ...machine, ...changes } : machine)));

    return (
        <fieldset>
            <legend>State machines</legend>
            {machines.map((machine) => {
                const entity = entities.find((candidate) => candidate.apiName === machine.entity);
                const states = machine.states.map((state) => state.name);
                return (
                    <div key={machine.id} className="nf-machine" data-machine={machine.id}>
                        <h3>{machine.entity}</h3>
                        <label>
                            Machine id
                            <input aria-label={`Machine id ${machine.id}`} value={machine.id}
                                onChange={(e) => update(machine.id, { id: e.target.value })} />
                        </label>
                        <label>
                            State field (enum)
                            <select aria-label={`State field ${machine.id}`} value={machine.stateField}
                                onChange={(e) => update(machine.id, { stateField: e.target.value })}>
                                {(entity ? enumFields(entity) : []).map((field) => (
                                    <option key={field} value={field}>{field}</option>
                                ))}
                            </select>
                        </label>
                        <label>
                            Initial state
                            <select aria-label={`Initial state ${machine.id}`} value={machine.initial}
                                onChange={(e) => update(machine.id, { initial: e.target.value })}>
                                {states.map((state) => (
                                    <option key={state} value={state}>{state}</option>
                                ))}
                            </select>
                        </label>
                        <h4>States</h4>
                        {machine.states.map((state, index) => (
                            <div key={index} className="nf-state-row">
                                <input aria-label={`State name ${machine.id} ${index}`}
                                    value={state.name}
                                    onChange={(e) => update(machine.id, {
                                        states: machine.states.map((s, i) =>
                                            i === index ? { ...s, name: e.target.value } : s),
                                    })} />
                                <label className="nf-inline">
                                    <input type="checkbox"
                                        aria-label={`State terminal ${machine.id} ${state.name}`}
                                        checked={state.terminal === true}
                                        onChange={(e) => update(machine.id, {
                                            states: machine.states.map((s, i) =>
                                                i === index ? { ...s, terminal: e.target.checked || undefined } : s),
                                        })} />
                                    terminal
                                </label>
                                <button type="button"
                                    aria-label={`Remove state ${machine.id} ${state.name}`}
                                    onClick={() => update(machine.id, {
                                        states: machine.states.filter((_, i) => i !== index),
                                    })}>×</button>
                            </div>
                        ))}
                        <button type="button"
                            onClick={() => update(machine.id, {
                                states: [...machine.states, { name: "" }],
                            })}>
                            Add state
                        </button>
                        <h4>Transitions</h4>
                        {machine.transitions.map((transition, index) => (
                            <div key={index} className="nf-transition-row">
                                <select aria-label={`Transition from ${machine.id} ${index}`}
                                    value={transition.from}
                                    onChange={(e) => update(machine.id, {
                                        transitions: machine.transitions.map((t, i) =>
                                            i === index ? { ...t, from: e.target.value } : t),
                                    })}>
                                    <option value="">from…</option>
                                    {states.map((state) => (
                                        <option key={state} value={state}>{state}</option>
                                    ))}
                                </select>
                                <select aria-label={`Transition to ${machine.id} ${index}`}
                                    value={transition.to}
                                    onChange={(e) => update(machine.id, {
                                        transitions: machine.transitions.map((t, i) =>
                                            i === index ? { ...t, to: e.target.value } : t),
                                    })}>
                                    <option value="">to…</option>
                                    {states.map((state) => (
                                        <option key={state} value={state}>{state}</option>
                                    ))}
                                </select>
                                <input aria-label={`Transition guard ${machine.id} ${index}`}
                                    placeholder="guard expression (optional)"
                                    value={transition.guard ?? ""}
                                    onChange={(e) => update(machine.id, {
                                        transitions: machine.transitions.map((t, i) =>
                                            i === index
                                                ? { ...t, guard: e.target.value || undefined }
                                                : t),
                                    })} />
                                <button type="button"
                                    aria-label={`Remove transition ${machine.id} ${index}`}
                                    onClick={() => update(machine.id, {
                                        transitions: machine.transitions.filter((_, i) => i !== index),
                                    })}>×</button>
                            </div>
                        ))}
                        <button type="button"
                            onClick={() => update(machine.id, {
                                transitions: [...machine.transitions, { from: "", to: "" }],
                            })}>
                            Add transition
                        </button>
                        <div>
                            <button type="button"
                                aria-label={`Remove machine ${machine.id}`}
                                onClick={() => onChange(machines.filter((m) => m.id !== machine.id))}>
                                Remove machine
                            </button>
                        </div>
                    </div>
                );
            })}
            {machinable.length > 0 ? (
                <form
                    onSubmit={(event) => {
                        event.preventDefault();
                        const entity = String(new FormData(event.currentTarget).get("entity") ?? "");
                        if (!entity) return;
                        const candidate = entities.find((e) => e.apiName === entity);
                        const stateField = candidate ? enumFields(candidate)[0] : "";
                        onChange([...machines, {
                            id: `sm_${entity.toLowerCase()}`,
                            entity,
                            stateField: stateField ?? "",
                            initial: "",
                            states: [{ name: "" }],
                            transitions: [],
                        }]);
                        event.currentTarget.reset();
                    }}
                >
                    <label>
                        Add machine for entity
                        <select name="entity" aria-label="Add machine for entity" required>
                            {machinable.map((entity) => (
                                <option key={entity.apiName} value={entity.apiName}>{entity.apiName}</option>
                            ))}
                        </select>
                    </label>
                    <button type="submit">Add state machine</button>
                </form>
            ) : (
                <p className="nf-b-meta">One machine per entity in v1; add an enum field to start one.</p>
            )}
            <div className="nf-b-actions">
                <button type="button" className="nf-action-primary" disabled={busy}
                    onClick={() => void onSave()}>Save state machines</button>
            </div>
        </fieldset>
    );
}

// --- SLAs (§6): scope + target + warn fraction + breach escalation ---

function SlaEditor({
    slas,
    onChange,
    busy,
    onSave,
}: {
    slas: SlaDefinition[];
    onChange: (slas: SlaDefinition[]) => void;
    busy?: boolean;
    onSave: () => Promise<void>;
}): ReactNode {
    const update = (index: number, changes: Partial<SlaDefinition>): void =>
        onChange(slas.map((sla, i) => (i === index ? { ...sla, ...changes } : sla)));
    return (
        <fieldset>
            <legend>SLAs</legend>
            <p className="nf-b-meta">
                A matching SLA governs over the requestApproval step's own timeout
                (§6); warnAt is a fraction of target (blank = 0.8 default).
            </p>
            {slas.map((sla, index) => (
                <div key={index} className="nf-sla-row" data-sla={sla.id}>
                    <input aria-label={`SLA id ${index}`} placeholder="id" value={sla.id}
                        onChange={(e) => update(index, { id: e.target.value })} />
                    <select aria-label={`SLA task type ${index}`}
                        value={sla.scope?.taskType ?? ""}
                        onChange={(e) => update(index, {
                            scope: { ...sla.scope, taskType: e.target.value || undefined },
                        })}>
                        <option value="">any task type</option>
                        <option value="approval">approval</option>
                        <option value="todo">todo</option>
                    </select>
                    <input aria-label={`SLA match ${index}`} placeholder="match, e.g. entity == 'Purch.PurchaseOrder'"
                        value={sla.scope?.match ?? ""}
                        onChange={(e) => update(index, {
                            scope: { ...sla.scope, match: e.target.value || undefined },
                        })} />
                    <input aria-label={`SLA target ${index}`} placeholder="target, e.g. PT24H" value={sla.target}
                        onChange={(e) => update(index, { target: e.target.value })} />
                    <input aria-label={`SLA warnAt ${index}`} placeholder="warnAt" type="number" min="0" max="1"
                        step="0.05" value={sla.warnAt ?? ""}
                        onChange={(e) => update(index, {
                            warnAt: e.target.value === "" ? undefined : Number(e.target.value),
                        })} />
                    <input aria-label={`SLA escalateTo ${index}`} placeholder="escalateTo, e.g. role:senior-manager"
                        value={sla.onBreach?.escalateTo ?? ""}
                        onChange={(e) => update(index, {
                            onBreach: { ...sla.onBreach, escalateTo: e.target.value || undefined },
                        })} />
                    <button type="button" aria-label={`Remove SLA ${sla.id}`}
                        onClick={() => onChange(slas.filter((_, i) => i !== index))}>×</button>
                </div>
            ))}
            <button type="button"
                onClick={() => onChange([...slas, { id: "", target: "", scope: {} }])}>
                Add SLA
            </button>
            <div className="nf-b-actions">
                <button type="button" className="nf-action-primary" disabled={busy}
                    onClick={() => void onSave()}>Save SLAs</button>
            </div>
        </fieldset>
    );
}

// --- scheduled jobs (§7): definitions are metadata; the registry is runtime state ---

function JobsEditor({
    jobs,
    onChange,
    busy,
    onSave,
}: {
    jobs: ScheduledJobDefinition[];
    onChange: (jobs: ScheduledJobDefinition[]) => void;
    busy?: boolean;
    onSave: () => Promise<void>;
}): ReactNode {
    const update = (index: number, changes: Partial<ScheduledJobDefinition>): void =>
        onChange(jobs.map((job, i) => (i === index ? { ...job, ...changes } : job)));
    return (
        <fieldset>
            <legend>Scheduled jobs</legend>
            <p className="nf-b-meta">
                Job definitions activate on publish — the registry (next fire, run
                history) is never written here (§7's split).
            </p>
            {jobs.map((job, index) => (
                <div key={index} className="nf-job-row" data-job={job.name}>
                    <input aria-label={`Job name ${index}`} placeholder="name" value={job.name}
                        onChange={(e) => update(index, { name: e.target.value })} />
                    <input aria-label={`Job cron ${index}`} placeholder="cron, e.g. 0 2 * * *" value={job.cron}
                        onChange={(e) => update(index, { cron: e.target.value })} />
                    <select aria-label={`Job target ${index}`} value={job.target}
                        onChange={(e) => update(index, { target: e.target.value as ScheduledJobTarget })}>
                        {JOB_TARGETS.map((target) => (
                            <option key={target} value={target}>{target}</option>
                        ))}
                    </select>
                    <JsonTextField aria-label={`Job params ${index}`} placeholder='params JSON, e.g. {"hook": "nightly"}'
                        value={job.params}
                        onParsed={(parsed) => update(index, { params: parsed ?? {} })} />
                    <label className="nf-inline">
                        <input type="checkbox" aria-label={`Job enabled ${job.name}`}
                            checked={job.enabled !== false}
                            onChange={(e) => update(index, { enabled: e.target.checked || undefined })} />
                        enabled
                    </label>
                    <button type="button" aria-label={`Remove job ${job.name}`}
                        onClick={() => onChange(jobs.filter((_, i) => i !== index))}>×</button>
                </div>
            ))}
            <button type="button"
                onClick={() => onChange([...jobs, { name: "", cron: "", target: "flow", params: {} }])}>
                Add job
            </button>
            <div className="nf-b-actions">
                <button type="button" className="nf-action-primary" disabled={busy}
                    onClick={() => void onSave()}>Save scheduled jobs</button>
            </div>
        </fieldset>
    );
}

// --- scheduler visibility (§2/§11): the read-only registry status route ---

function SchedulerStatus({ rows }: { rows: Record<string, unknown>[] | null }): ReactNode {
    return (
        <fieldset>
            <legend>Scheduler status (read-only)</legend>
            {rows === null ? (
                <p role="status">Loading job registry…</p>
            ) : rows.length === 0 ? (
                <p>No jobs registered yet — publish activates authored definitions.</p>
            ) : (
                <table className="nf-table nf-scheduler">
                    <thead>
                        <tr>
                            <th scope="col">App</th>
                            <th scope="col">Job</th>
                            <th scope="col">Cron</th>
                            <th scope="col">Next fire</th>
                            <th scope="col">Last run</th>
                        </tr>
                    </thead>
                    <tbody>
                        {rows.map((row, index) => (
                            <tr key={index}>
                                <td>{String(row.app ?? "")}</td>
                                <td>{String(row.name ?? "")}</td>
                                <td>{String(row.cron ?? "")}</td>
                                <td>{String(row.next_fire_at ?? "—")}</td>
                                <td>{String(row.last_status ?? "—")}</td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            )}
        </fieldset>
    );
}
