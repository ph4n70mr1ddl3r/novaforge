import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { ReportTable } from "../src/catalog/ReportTable.tsx";
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
