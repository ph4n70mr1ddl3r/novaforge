/**
 * The Reporting Service's run-result wire shape (PHASE-5 §4): what every report
 * catalog component renders. The runtime renderer fetches runs (TanStack Query in
 * the Phase 2 shell — server cache keyed by metadata version) and hands the result
 * to the component as a data prop; catalog components are pure/presentational and
 * never fetch (ADR-009: behavior is declarative, the renderer interprets).
 */
export interface ReportRun {
  /** Column keys, in render order (the runtime's snake_case labels). */
  columns: string[];
  /** Grouped rows — objects keyed by column. */
  rows: Record<string, unknown>[];
  /** The un-grouped totals twin, keyed by aggregate alias. */
  totals: Record<string, number>;
  /** The ECharts-shaped projection for direct chart binding (§4). */
  chart: ChartProjection;
}

export interface ChartProjection {
  xAxis: { data: unknown[] };
  series: ChartSeries[];
}

export interface ChartSeries {
  name: string;
  data: unknown[];
}

/** A widget's binding to one report (PHASE-5 §5): the ref plus param overrides. */
export interface ReportBinding {
  reportRef: string;
  params?: Record<string, unknown>;
}
