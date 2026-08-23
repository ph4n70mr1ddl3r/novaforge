import { type ReactNode } from "react";
import type { ReportRun } from "../report.ts";

/**
 * ReportTable (PHASE-5 §5, the `table` widget) — catalog version 1.0.0. A report
 * run's grouped rows with the totals twin as the closing footer row (the §6 export
 * shape, mirrored on screen). Server-side paging belongs to the list pages
 * (PHASE-2); report result sets are already grouped aggregates.
 */
export function ReportTable(props: {
  reportRef: string;
  run: ReportRun;
  title?: string;
  /** Column overrides: {column: label} — absent columns render under their key. */
  labels?: Record<string, string>;
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
        </tr>
      </thead>
      <tbody>
        {rows.map((row, index) => (
          <tr key={index}>
            {columns.map((column) => (
              <td key={column}>{cell(row[column])}</td>
            ))}
          </tr>
        ))}
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
        </tr>
      </tfoot>
    </table>
  );
}

function cell(value: unknown): string {
  if (value === null || value === undefined) {
    return "—";   // unmatched buckets render as null rows, not blanks
  }
  return typeof value === "number" ? String(value) : String(value);
}
