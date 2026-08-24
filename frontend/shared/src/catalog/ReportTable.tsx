import { type ReactNode } from "react";
import type { ReportRun } from "../report.ts";

/**
 * ReportTable (PHASE-5 §5, the `table` widget) — catalog version 1.0.0. A report
 * run's grouped rows with the totals twin as the closing footer row (the §6 export
 * shape, mirrored on screen). Server-side paging belongs to the list pages
 * (PHASE-2); report result sets are already grouped aggregates.
 *
 * Drill-through (§5): when the report declares a `drillThrough` binding, every row
 * renders an anchor that hands the shell the row's filters as a query-DSL payload —
 * non-bucket group-by columns lower to `eq` leaves (a bucket label is a derived
 * value, not a field value, so bucket columns never filter), the report's saved
 * filters join when `carryFilters` is set. The shell deep-links the record list,
 * which consumes the payload natively (the runtime list page splices it into every
 * paged request — §10 item 2's round-trip).
 */
/** A query-DSL leaf — the runtime list's filter vocabulary (PHASE-1 §5). */
export type DrillLeafOp = "eq" | "ne" | "in" | "gt" | "gte" | "lt" | "lte" | "contains" | "isNull";

export interface DrillThroughBinding {
    entity: string;
    carryFilters?: boolean;
    /** The report's saved filters (joined when carryFilters is set). */
    filters?: { field: string; op: DrillLeafOp; value?: unknown }[];
    /** The group-by fields behind the columns, in column order (buckets excluded). */
    groupFields?: string[];
}

export type DrillFilter = { op: "and"; children: { field: string; op: DrillLeafOp; value?: unknown }[] };

export function drillFilters(binding: DrillThroughBinding, row: Record<string, unknown>): DrillFilter | undefined {
    // non-bucket group-by columns lower to eq leaves over the row's own values
    // (a bucket label is a derived value, not a field value — bucket columns never
    // filter); the report's saved filters join when carryFilters is set (§5)
    const leaves: { field: string; op: DrillLeafOp; value?: unknown }[] =
        binding.carryFilters ? (binding.filters ?? []).map((f) => ({ ...f })) : [];
    for (const field of binding.groupFields ?? []) {
        const value = row[field] ?? row[toSnake(field)];
        if (value !== null && value !== undefined && value !== "—") {
            leaves.push({ field, op: "eq", value });
        }
    }
    if (leaves.length === 0) {
        return undefined;
    }
    return { op: "and", children: leaves };
}

/** The runtime labels group-by columns in snake_case (the compiler's column naming). */
function toSnake(field: string): string {
    return field.replace(/([a-z0-9])([A-Z])/g, "$1_$2").toLowerCase();
}

export function ReportTable(props: {
    reportRef: string;
    run: ReportRun;
    title?: string;
    /** Column overrides: {column: label} — absent columns render under their key. */
    labels?: Record<string, string>;
    /** The report's drill-through binding (§5); rows become links when present. */
    drillThrough?: DrillThroughBinding;
    /** The shell's drill handler — receives the row's query-DSL filter payload. */
    onDrill?: (row: Record<string, unknown>, filter: DrillFilter) => void;
}): ReactNode {
    const { columns, rows, totals } = props.run;
    const label = (column: string): string => props.labels?.[column] ?? column;
    return (
        <table className="nf-report-table">
            {props.title ? <caption>{props.title}</caption> : null}
            <thead>
                <tr>
                    {columns.map((column) => (
                        <th key={column} scope="col">{label(column)}</th>
                    ))}
                    {props.drillThrough ? <th scope="col"><span className="nf-visually-hidden">Drill through</span></th> : null}
                </tr>
            </thead>
            <tbody>
                {rows.map((row, index) => {
                    const filter = props.drillThrough ? drillFilters(props.drillThrough, row) : undefined;
                    const drill = filter && props.onDrill
                        ? () => props.onDrill?.(row, filter)
                        : undefined;
                    return (
                        <tr key={index}>
                            {columns.map((column) => (
                                <td key={column}>{cell(row[column])}</td>
                            ))}
                            <td>
                                {drill ? (
                                    <a
                                        href={`#/${props.drillThrough?.entity ?? ""}`}
                                        className="nf-drill-link"
                                        aria-label={`View ${props.drillThrough?.entity} records for row ${index + 1}`}
                                        onClick={(event) => {
                                            event.preventDefault();
                                            drill();
                                        }}
                                    >
                                        view records
                                    </a>
                                ) : null}
                            </td>
                        </tr>
                    );
                })}
            </tbody>
            <tfoot>
                <tr>
                    {columns.map((column, index) => (
                        <td key={column} className="nf-total-cell">
                            {index === 0
                                ? "TOTAL"
                                : column in totals
                                  ? cell(totals[column])
                                  : ""}
                        </td>
                    ))}
                    {props.drillThrough ? <td /> : null}
                </tr>
            </tfoot>
        </table>
    );
}

function cell(value: unknown): string {
    if (value === null || value === undefined) {
        return "—";   // unmatched buckets render as null rows, not blanks
    }
    return String(value);
}
