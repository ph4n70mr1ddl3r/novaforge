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
  const axis = props.chart.xAxis.data.map((value) => String(value));
  const option = {
    animation: false,
    tooltip: { trigger: "axis" },
    xAxis: { type: "category" as const, data: axis },
    yAxis: { type: "value" as const },
    series: props.chart.series.map((series) => ({
      name: series.name,
      type: kind,
      data: series.data,
    })),
  };
  const ariaLabel =
    `${props.title ?? props.reportRef}: ${props.chart.series.length} series over ` +
    `${axis.length} categories (${axis.slice(0, 3).join(", ")}…)`;
  return (
    <figure className="nf-chart-widget" style={{ margin: 0 }}>
      {props.title ? <figcaption className="nf-widget-title">{props.title}</figcaption> : null}
      <Suspense fallback={<div role="status">Loading chart…</div>}>
        <EChartCanvas option={option} height={props.height} ariaLabel={ariaLabel} />
      </Suspense>
    </figure>
  );
}
