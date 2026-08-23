import { type ReactNode } from "react";

/**
 * DashboardGrid (PHASE-5 §5) — catalog version 1.0.0. A 12-column responsive grid
 * placing widgets by their authored span; collapse to one column under 720 px
 * (WCAG reflow — content must not scroll in two dimensions). Layout only: the grid
 * carries no semantics, the widgets inside it do.
 */
export function DashboardGrid(props: {
  children: ReactNode;
}): ReactNode {
  return (
    <div className="nf-dashboard-grid" role="presentation">
      {props.children}
    </div>
  );
}

/** A grid cell: the widget's authored span, 1..12 (validated at save, §5). */
export function DashboardCell(props: { span: number; children: ReactNode }): ReactNode {
  const span = Math.min(12, Math.max(1, props.span));
  return (
    <div className="nf-dashboard-cell" style={{ gridColumn: `span ${span}` }}>
      {props.children}
    </div>
  );
}
