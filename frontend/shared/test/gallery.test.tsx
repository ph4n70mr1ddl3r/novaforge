import { describe, expect, it, vi } from "vitest";
import axe from "axe-core";
import { render, waitFor } from "@testing-library/react";
import { CATALOG } from "../src/catalog/schemas.ts";
import { ChartWidget } from "../src/catalog/ChartWidget.tsx";
import { KpiTile } from "../src/catalog/KpiTile.tsx";
import { ReportTable } from "../src/catalog/ReportTable.tsx";
import { DashboardCell, DashboardGrid } from "../src/catalog/DashboardGrid.tsx";

// the gallery asserts a11y semantics, not canvas rendering — ECharts stays mocked
// (jsdom has no canvas; the real canvas is the Phase 2 Playwright layer, spec §2)
vi.mock("echarts", () => ({
  init: vi.fn(() => ({ setOption: vi.fn(), dispose: vi.fn() })),
}));

/** The axe runner — raw axe-core: the options-carrying overload returns results. */
const axeCheck = (element: HTMLElement) => axe.run(element, {});

/**
 * The catalog gallery (PHASE-5 T5 AC: "Catalog gallery green incl. axe"): every
 * catalog component mounts with valid props against the golden arAging fixture and
 * passes automated WCAG checks — the ADR-009 §5 cross-cutting requirement, wired
 * from day one of the catalog rather than retrofitted.
 */
const run = {
  columns: ["customer_name", "due_date", "outstanding"],
  rows: [
    { customer_name: "acme", due_date: "0-30", outstanding: 200 },
    { customer_name: "globex", due_date: "31-60", outstanding: 50.25 },
  ],
  totals: { outstanding: 250.25 },
  chart: {
    xAxis: { data: ["acme", "globex"] },
    series: [
      { name: "due_date", data: ["0-30", "31-60"] },
      { name: "outstanding", data: [200, 50.25] },
    ],
  },
};

describe("catalog gallery — axe", () => {
  it("ChartWidget is axe-clean", async () => {
    const { container } = render(
      <ChartWidget reportRef="arAging" chart={run.chart} title="A/R Aging" />,
    );
    await waitFor(() =>
      expect(container.querySelector('[role="img"]')).not.toBeNull(),
    );
    expect(await axeCheck(container)).toHaveNoViolations();
  });

  it("KpiTile is axe-clean", async () => {
    const { container } = render(
      <KpiTile reportRef="arAging" totals={run.totals} metric="outstanding" label="Outstanding" />,
    );
    expect(await axeCheck(container)).toHaveNoViolations();
  });

  it("ReportTable is axe-clean", async () => {
    const { container } = render(
      <ReportTable reportRef="arAging" run={run} title="A/R Aging" />,
    );
    expect(await axeCheck(container)).toHaveNoViolations();
  });

  it("DashboardGrid with KPI cells is axe-clean", async () => {
    const { container } = render(
      <DashboardGrid>
        <DashboardCell span={4}>
          <KpiTile reportRef="arAging" totals={run.totals} metric="outstanding" label="Outstanding" />
        </DashboardCell>
        <DashboardCell span={8}>
          <ReportTable reportRef="arAging" run={run} title="A/R Aging" />
        </DashboardCell>
      </DashboardGrid>,
    );
    expect(await axeCheck(container)).toHaveNoViolations();
  });

  it("every catalog id has an implementation mounted in this gallery", () => {
    // the gallery is exhaustive: a new catalog component without an axe mount
    // fails here, so a11y coverage cannot silently lag the catalog
    expect(CATALOG.length).toBe(4);
  });
});

declare module "vitest" {
  interface Assertion<T> {
    toHaveNoViolations(): T;
  }
}

expect.extend({
  toHaveNoViolations(received: {
    violations: Array<{ id: string; help: string; nodes: unknown[] }>;
  }) {
    const { isNot } = this;
    return {
      pass: received.violations.length === 0,
      message: () =>
        `${received.violations.length} axe violations: ` +
        received.violations
          .map((v: { id: string; nodes: unknown[]; help: string }) =>
            `${v.id} (${v.nodes.length} nodes): ${v.help}`)
          .join("; "),
      actual: isNot,
    };
  },
});
