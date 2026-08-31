import { lazy, Suspense, type ReactNode } from "react";
import type { ChartProjection } from "../report.ts";

const EChartCanvas = lazy(() => import("./EChartCanvas.tsx"));

/**
 * ChartWidget (PHASE-5 §5) — catalog version 1.0.0. Renders a report run's §4
 * chart projection as an ECharts bar/line chart, resolving ECharts lazily per
 * ADR-009 §2 (the runtime renderer resolves components from a lazily-loaded
 * registry; the chart library follows the same rule). The first column is the
 * category axis, every series after it binds a line — the projection's shape.
 */
export function ChartWidget(props: {
  reportRef: string;
  chart: ChartProjection;
  title?: string;
  kind?: "bar" | "line";
  height?: number;
}): ReactNode {
  const kind = props.kind ?? "bar";
  // A malformed/failed run feeds this straight from the API response: an
  // unguarded deref threw during render, and the only boundary is the app root —
  // one bad widget replaced the whole shell (KpiTile guards its totals; this
  // guards the projection's own shape).
  const axisData = props.chart?.xAxis?.data ?? [];
  const series = props.chart?.series ?? [];
  const axis = axisData.map((value) => String(value));
  const option = {
    animation: false,
    tooltip: { trigger: "axis" },
    xAxis: { type: "category" as const, data: axis },
    yAxis: { type: "value" as const },
    series: series.map((series) => ({
      name: series.name,
      type: kind,
      data: series.data,
    })),
  };
  const ariaLabel =
    `${props.title ?? props.reportRef}: ${series.length} series over ` +
    `${axis.length} categories (${axis.slice(0, 3).join(", ")}…)`;
  return (
    <figure className="nf-chart-widget" style={{ margin: 0 }}>
      {props.title ? <figcaption className="nf-widget-title">{props.title}</figcaption> : null}
      {series.length === 0 && axis.length === 0 ? (
        <div role="status" className="nf-empty">No chart data for this run</div>
      ) : (
        <Suspense fallback={<div role="status">Loading chart…</div>}>
          <EChartCanvas option={option} height={props.height} ariaLabel={ariaLabel} />
        </Suspense>
      )}
    </figure>
  );
}
