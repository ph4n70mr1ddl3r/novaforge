import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { ReportTable, drillFilters } from "../src/catalog/ReportTable.tsx";
import { KpiTile } from "../src/catalog/KpiTile.tsx";
import type { ReportRun } from "../src/report.ts";

/** The live arAging run from the 2026-08-23 exit demo — the golden fixture. */
const run: ReportRun = {
  columns: ["customer_name", "due_date", "outstanding"],
  rows: [
    { customer_name: "acme", due_date: "0-30", outstanding: 200 },
    { customer_name: "acme", due_date: "current", outstanding: 100.5 },
    { customer_name: "globex", due_date: "31-60", outstanding: 50.25 },
    { customer_name: "initech", due_date: null, outstanding: 0 },
  ],
  totals: { outstanding: 365.75 },
  chart: { xAxis: { data: [] }, series: [] },
};

describe("ReportTable", () => {
  it("renders the grouped rows with the totals twin as the footer", () => {
    render(<ReportTable reportRef="arAging" run={run} title="A/R Aging" />);
    expect(screen.getByRole("table", { name: "A/R Aging" })).toBeDefined();
    expect(screen.getByRole("columnheader", { name: "customer_name" })).toBeDefined();
    // 4 body rows + header + footer
    expect(screen.getAllByRole("row")).toHaveLength(6);
    const footer = document.querySelector("tfoot tr")!;
    expect(footer.textContent).toBe("TOTAL365.75");
  });

  it("renders unmatched buckets as em-dashes, never blank cells", () => {
    render(<ReportTable reportRef="arAging" run={run} />);
    const nullCell = screen.getByRole("cell", { name: "—" });
    expect(nullCell).toBeDefined();
  });

  it("applies authored column label overrides", () => {
    render(
      <ReportTable
        reportRef="arAging"
        run={run}
        labels={{ customer_name: "Customer", outstanding: "Outstanding" }}
      />,
    );
    expect(screen.getByRole("columnheader", { name: "Customer" })).toBeDefined();
  });
});

describe("drill-through (PHASE-5 §5/§10 item 2)", () => {
  const binding = {
    entity: "Invoice",
    groupFields: ["customer"],   // dueDate is bucketed — it never filters
  };

  it("lowers a row to eq leaves over its non-bucket group-by values", () => {
    expect(drillFilters(binding, { customer: "acme", due_date: "0-30" }))
      .toEqual({ op: "and", children: [{ field: "customer", op: "eq", value: "acme" }] });
  });

  it("joins the report's saved filters when carryFilters is set", () => {
    const carried = {
      ...binding,
      carryFilters: true,
      filters: [{ field: "status", op: "eq" as const, value: "POSTED" }],
    };
    expect(drillFilters(carried, { customer: "globex" }))
      .toEqual({
        op: "and",
        children: [
          { field: "status", op: "eq", value: "POSTED" },
          { field: "customer", op: "eq", value: "globex" },
        ],
      });
  });

  it("hands the row's filter payload to the shell on click — the deep link's shape", () => {
    const onDrill = vi.fn();
    render(
      <ReportTable
        reportRef="arAging"
        run={run}
        drillThrough={{ ...binding, groupFields: ["customerName"] }}
        onDrill={onDrill}
      />,
    );
    const link = screen.getAllByRole("link", { name: /view \w+ records/i })[0]!;
    expect(link.getAttribute("href")).toBe("#/Invoice");
    link.click();   // onClick preventDefaults and hands the payload over
    expect(onDrill).toHaveBeenCalledWith(
      expect.anything(),
      { op: "and", children: [{ field: "customerName", op: "eq", value: "acme" }] },
    );
  });

  it("renders no drill column without a binding — the static default", () => {
    render(<ReportTable reportRef="arAging" run={run} />);
    expect(screen.queryByRole("link")).toBeNull();
  });
});

describe("KpiTile", () => {
  it("renders the named total with a group label", () => {
    render(<KpiTile reportRef="arAging" totals={run.totals} metric="outstanding" label="Outstanding" />);
    expect(screen.getByRole("group", { name: "Outstanding" })).toBeDefined();
    expect(screen.getByText("365.75")).toBeDefined();
  });

  it("renders a currency-prefixed figure without re-rounding the server's decimal", () => {
    render(<KpiTile reportRef="arAging" totals={run.totals} metric="outstanding" currency="USD" />);
    expect(screen.getByText("USD 365.75")).toBeDefined();
  });

  it("renders a placeholder for a metric the run does not carry", () => {
    render(<KpiTile reportRef="arAging" totals={run.totals} metric="count" />);
    expect(screen.getByText("—")).toBeDefined();
  });
});
