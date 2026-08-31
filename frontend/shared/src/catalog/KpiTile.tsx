import { type ReactNode } from "react";
import type { ReportRun } from "../report.ts";

/**
 * KpiTile (PHASE-5 §5) — catalog version 1.0.0. One aggregate from a report run's
 * totals twin, rendered as a labeled headline figure. Money formatting keeps the
 * decimal string verbatim (PLAN.md §1's money rule — the server's BigDecimal is the
 * truth; the browser never re-rounds it), optionally prefixed with a currency
 * symbol when the column is money-typed.
 */
export function KpiTile(props: {
  reportRef: string;
  totals: ReportRun["totals"];
  metric: string;
  label?: string;
  currency?: string;
}): ReactNode {
  // Money never rides a binary float: the backend answers exact decimals (as
  // strings or numbers within double range) — render the exact text, grouping
  // integers only, decimals verbatim (the file's own rule; the old path pushed
  // every value through Number and toLocaleString, re-rounding money in the UI)
  const raw = props.totals?.[props.metric];
  const text = renderMetric(raw, props.currency);
  return (
    <div className="nf-kpi-tile" role="group" aria-label={props.label ?? props.metric}>
      <span className="nf-kpi-label">{props.label ?? props.metric}</span>
      <span className="nf-kpi-value">{text}</span>
    </div>
  );
}

/** Locale-grouped integer rendering; decimals ride verbatim to the tenth of a cent. */
function renderMetric(raw: unknown, currency?: string): string {
  if (typeof raw === "number" && Number.isInteger(raw)) {
    return currency ? `${currency} ${raw.toLocaleString()}` : raw.toLocaleString();
  }
  const text = typeof raw === "number" ? String(raw) : typeof raw === "string" ? raw : null;
  if (text === null || text === "") {
    return "—";
  }
  return currency ? `${currency} ${text}` : text;
}
