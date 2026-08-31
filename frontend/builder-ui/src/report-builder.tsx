import { useMemo, useState, type ReactNode } from "react";
import { mergeBranch } from "./branch-merge.ts";
import {
    Expression,
    type AppDefinition,
    type EntityDefinition,
    type ReportDefinition,
} from "@novaforge/shared";

/**
 * The report builder (PHASE-5 §5/T6, riding the Phase 2 builder shell): a guided
 * form over the §3 report schema — filters, group-by (with bucketed expressions),
 * aggregates, drill-through — with live compile-check of bucket expressions
 * through the shared TS engine (the same grammar the JVM engine re-checks at
 * save/publish). Saves through the Metadata definition APIs like every other
 * definition (reports ride the app document).
 */

export interface ReportBuilderProps {
    app: AppDefinition;
    saveReports: (mutate: (fresh: ReportDefinition[]) => ReportDefinition[]) => Promise<void>;
}

export function ReportBuilder({ app, saveReports }: ReportBuilderProps): ReactNode {
    const [selectedId, setSelectedId] = useState<string | null>(app.reports[0]?.id ?? null);
    const [draft, setDraft] = useState<ReportDefinition | null>(app.reports[0] ?? null);
    const [flash, setFlash] = useState<string | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);

    const entity = useMemo(
        () => app.entities.find((candidate) => candidate.apiName === draft?.entity),
        [app.entities, draft?.entity],
    );

    const compileIssues = useMemo(() => {
        if (!entity || !draft) return [];
        const issues: string[] = [];
        const fields = entity.fields.map((field) => field.apiName);
        for (const group of draft.groupBy ?? []) {
            for (const bucket of group.buckets ?? []) {
                try {
                    Expression.parse(bucket.expression).compileCheck({
                        bindings: fields,
                        allowClock: true, // aging inputs compute at run time (§3)
                    });
                } catch (caught) {
                    issues.push(`${bucket.label}: ${caught instanceof Error ? caught.message : String(caught)}`);
                }
            }
            if (!fields.includes(group.field)) {
                issues.push(`group-by field '${group.field}' not on ${entity.apiName}`);
            }
        }
        for (const filter of draft.filters ?? []) {
            if (!fields.includes(filter.field)) {
                issues.push(`filter field '${filter.field}' not on ${entity.apiName}`);
            }
        }
        for (const aggregate of draft.aggregates ?? []) {
            if (aggregate.op !== "count" && !fields.includes(aggregate.field)) {
                issues.push(`aggregate field '${aggregate.field}' not on ${entity.apiName}`);
            }
        }
        return issues;
    }, [draft, entity]);

    const update = (patch: Partial<ReportDefinition>): void => {
        setDraft((current) => (current ? { ...current, ...patch } : current));
    };

    const save = async (): Promise<void> => {
        if (!draft) return;
        if (compileIssues.length > 0) {
            setError(`Live compile-check failed: ${compileIssues[0]}`);
            return;
        }
        setBusy(true);
        setError(null);
        try {
            const next = app.reports.some((report) => report.id === draft.id)
                ? app.reports.map((report) => (report.id === draft.id ? draft : report))
                : [...app.reports, draft];
            // merged over a FRESH fetch inside the shell: this list is built from
            // the mount-time snapshot, and saving it verbatim deleted a report
            // another tab added meanwhile (the dashboards rule)
            await saveReports((fresh) => mergeBranch(next, app.reports, fresh, (report) => report.id));
            setFlash(`Saved report ${draft.id}`);
        } catch (caught) {
            setError(caught instanceof Error ? caught.message : String(caught));
        } finally {
            setBusy(false);
        }
    };

    return (
        <section className="nf-b-report" aria-label="Report builder">
            <h2>Reports</h2>
            <div className="nf-b-toolbar">
                <label>
                    Report
                    <select
                        value={selectedId ?? ""}
                        onChange={(event) => {
                            const report = app.reports.find((candidate) => candidate.id === event.target.value);
                            setSelectedId(report?.id ?? null);
                            setDraft(report ?? null);
                        }}
                    >
                        <option value="">—</option>
                        {app.reports.map((report) => (
                            <option key={report.id} value={report.id}>{report.id}</option>
                        ))}
                    </select>
                </label>
                <button
                    type="button"
                    onClick={() => {
                        setSelectedId(null);
                        setDraft({ id: "", entity: app.entities[0]?.apiName ?? "", label: "", filters: [], groupBy: [], aggregates: [{ op: "count", field: "*", alias: "count" }] } as ReportDefinition);
                    }}
                >
                    New report
                </button>
            </div>
            {error ? <p role="alert">{error}</p> : null}
            {flash ? <p role="status" aria-live="polite">{flash}</p> : null}
            {draft ? (
                <form onSubmit={(event) => {
                    event.preventDefault();
                    void save();
                }}>
                    <label>
                        id (API name)
                        <input value={draft.id} onChange={(e) => update({ id: e.target.value })} required
                            pattern="[a-zA-Z_][a-zA-Z0-9_]*" aria-label="Report id" />
                    </label>
                    <label>
                        Label
                        <input value={draft.label ?? ""} onChange={(e) => update({ label: e.target.value })} />
                    </label>
                    <label>
                        Entity
                        <select value={draft.entity} onChange={(e) => update({ entity: e.target.value })}>
                            {app.entities.map((candidate) => (
                                <option key={candidate.apiName} value={candidate.apiName}>{candidate.apiName}</option>
                            ))}
                        </select>
                    </label>
                    <fieldset>
                        <legend>Aggregates</legend>
                        {(draft.aggregates ?? []).map((aggregate, index) => (
                            <div key={index} className="nf-b-toolbar">
                                <select
                                    aria-label={`aggregate op ${index}`}
                                    value={aggregate.op}
                                    onChange={(event) =>
                                        update({
                                            aggregates: draft.aggregates!.map((candidate, i) =>
                                                i === index ? { ...candidate, op: event.target.value as typeof aggregate.op } : candidate),
                                        })
                                    }
                                >
                                    {["count", "sum", "avg", "min", "max"].map((op) => (
                                        <option key={op} value={op}>{op}</option>
                                    ))}
                                </select>
                                <input
                                    aria-label={`aggregate field ${index}`}
                                    value={aggregate.field}
                                    placeholder={entity?.fields.map((field) => field.apiName).join(", ")}
                                    onChange={(event) =>
                                        update({
                                            aggregates: draft.aggregates!.map((candidate, i) =>
                                                i === index ? { ...candidate, field: event.target.value } : candidate),
                                        })
                                    }
                                />
                                <input
                                    aria-label={`aggregate alias ${index}`}
                                    value={aggregate.alias}
                                    onChange={(event) =>
                                        update({
                                            aggregates: draft.aggregates!.map((candidate, i) =>
                                                i === index ? { ...candidate, alias: event.target.value } : candidate),
                                        })
                                    }
                                />
                            </div>
                        ))}
                    </fieldset>
                    <fieldset>
                        <legend>Group-by (bucketed expressions compile live)</legend>
                        {(draft.groupBy ?? []).map((group, index) => (
                            <div key={index}>
                                <input
                                    aria-label={`group by field ${index}`}
                                    value={group.field}
                                    placeholder="field"
                                    onChange={(event) =>
                                        update({
                                            groupBy: draft.groupBy!.map((candidate, i) =>
                                                i === index ? { ...candidate, field: event.target.value } : candidate),
                                        })
                                    }
                                />
                                {(group.buckets ?? []).map((bucket, bucketIndex) => (
                                    <div key={bucketIndex} className="nf-b-toolbar">
                                        <input
                                            aria-label={`bucket label ${index}.${bucketIndex}`}
                                            value={bucket.label}
                                            placeholder="label"
                                            onChange={(event) =>
                                                update({
                                                    groupBy: draft.groupBy!.map((candidate, i) =>
                                                        i === index
                                                            ? {
                                                                ...candidate,
                                                                buckets: candidate.buckets!.map((b, bi) =>
                                                                    bi === bucketIndex ? { ...b, label: event.target.value } : b),
                                                            }
                                                            : candidate),
                                                })
                                            }
                                        />
                                        <input
                                            aria-label={`bucket expression ${index}.${bucketIndex}`}
                                            value={bucket.expression}
                                            placeholder="dueDate < today() - 30"
                                            onChange={(event) =>
                                                update({
                                                    groupBy: draft.groupBy!.map((candidate, i) =>
                                                        i === index
                                                            ? {
                                                                ...candidate,
                                                                buckets: candidate.buckets!.map((b, bi) =>
                                                                    bi === bucketIndex ? { ...b, expression: event.target.value } : b),
                                                            }
                                                            : candidate),
                                                })
                                            }
                                        />
                                    </div>
                                ))}
                                <button
                                    type="button"
                                    onClick={() =>
                                        update({
                                            groupBy: draft.groupBy!.map((candidate, i) =>
                                                i === index
                                                    ? { ...candidate, buckets: [...(candidate.buckets ?? []), { label: "", expression: "" }] }
                                                    : candidate),
                                        })
                                    }
                                >
                                    Add bucket
                                </button>
                            </div>
                        ))}
                        <button
                            type="button"
                            onClick={() => update({ groupBy: [...(draft.groupBy ?? []), { field: "", buckets: [] }] })}
                        >
                            Add group-by
                        </button>
                    </fieldset>
                    <fieldset>
                        <legend>Drill-through (rows deep-link the bound entity's list — §5)</legend>
                        <label>
                            Target entity
                            <select
                                aria-label="drill through entity"
                                value={draft.drillThrough?.entity ?? ""}
                                onChange={(event) =>
                                    update({
                                        drillThrough: event.target.value
                                            ? { entity: event.target.value,
                                                carryFilters: draft.drillThrough?.carryFilters }
                                            : undefined,
                                    })
                                }
                            >
                                <option value="">none</option>
                                {app.entities.map((candidate) => (
                                    <option key={candidate.apiName} value={candidate.apiName}>{candidate.apiName}</option>
                                ))}
                            </select>
                        </label>
                        <label className="nf-inline">
                            <input
                                type="checkbox"
                                aria-label="drill through carry filters"
                                checked={draft.drillThrough?.carryFilters === true}
                                disabled={!draft.drillThrough?.entity}
                                onChange={(event) =>
                                    update({
                                        drillThrough: draft.drillThrough?.entity
                                            ? { entity: draft.drillThrough.entity,
                                                carryFilters: event.target.checked || undefined }
                                            : undefined,
                                    })
                                }
                            />
                            carry the report's saved filters on the deep link
                        </label>
                    </fieldset>
                    {compileIssues.length > 0 ? (
                        <p role="alert">Live compile-check: {compileIssues.join("; ")}</p>
                    ) : (
                        <p role="status">Compile-check green</p>
                    )}
                    <button type="submit" className="nf-action-primary" disabled={busy}>Save report</button>
                </form>
            ) : (
                <p>Select or create a report.</p>
            )}
        </section>
    );
}

export type { EntityDefinition };
