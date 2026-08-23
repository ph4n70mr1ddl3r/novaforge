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
  const value = props.totals[props.metric];
  const has = typeof value === "number" && Number.isFinite(value);
  const text = has
    ? props.currency
      ? `${props.currency} ${formatNumber(value)}`
      : formatNumber(value)
    : "—";
  return (
    <div className="nf-kpi-tile" role="group" aria-label={props.label ?? props.metric}>
      <span className="nf-kpi-label">{props.label ?? props.metric}</span>
      <span className="nf-kpi-value">{text}</span>
    </div>
  );
}

/** Locale-grouped integer rendering; decimals ride verbatim to the tenth of a cent. */
function formatNumber(value: number): string {
  return Number.isInteger(value) ? value.toLocaleString() : value.toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 4,
  });
}
