import { describe, expect, it, vi } from "vitest";
import axe from "axe-core";
import { render, waitFor } from "@testing-library/react";
import { createElement } from "react";
import { CATALOG } from "../src/catalog/schemas.ts";
import { ChartWidget } from "../src/catalog/ChartWidget.tsx";
import { KpiTile } from "../src/catalog/KpiTile.tsx";
import { ReportTable } from "../src/catalog/ReportTable.tsx";
import { DashboardCell, DashboardGrid } from "../src/catalog/DashboardGrid.tsx";
import { FileUpload } from "../src/catalog/FileUpload.tsx";
import {
  FieldDate,
  FieldInput,
  FieldJson,
  FieldMultiLookup,
  FieldNumber,
  FieldRichText,
  FieldSelect,
  FieldSwitch,
  FieldLookup,
} from "../src/catalog/fields.tsx";
import {
  AppShell,
  EmptyState,
  FormLayout,
  ListLayout,
  NavList,
  RecordActions,
  RecordHeader,
  RelatedList,
} from "../src/catalog/layouts.tsx";
import { RendererContext, type RendererContextValue } from "../src/renderer/context.ts";

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

  it("FileUpload is axe-clean (the PHASE-2 §5 stub, activated by PHASE-6 §8)", async () => {
    const { container } = render(
      <FileUpload filesBase="http://files" bearerToken="token" />,
    );
    expect(await axeCheck(container)).toHaveNoViolations();
  });

  it("every catalog id has an implementation mounted in this gallery", async () => {
    // the gallery is exhaustive: a new catalog component without an axe mount
    // fails here, so a11y coverage cannot silently lag the catalog
    const mounted = new Set<string>();
    const axeAll = async (element: ReturnType<typeof render>, ids: string[]) => {
      expect(await axeCheck(element.container)).toHaveNoViolations();
      ids.forEach((id) => mounted.add(id));
    };

    // --- the v1 page catalog (PHASE-2 §6 item 3) — mounted inside the renderer context ---
    const fields: Record<string, import("../src/metadata.ts").FieldDefinition> = {
      reference: { apiName: "reference", label: "Reference", type: "text" },
      status: { apiName: "status", label: "Status", type: "enum", values: ["DRAFT", "POSTED"] },
      amount: { apiName: "amount", label: "Amount", type: "money" },
      orderedAt: { apiName: "orderedAt", label: "Ordered", type: "date" },
      active: { apiName: "active", label: "Active", type: "boolean" },
      meta: { apiName: "meta", label: "Meta", type: "json" },
      customer: { apiName: "customer", label: "Customer", type: "lookup" },
      tags: { apiName: "tags", label: "Tags", type: "m2m" },
      notes: { apiName: "notes", label: "Notes", type: "longText" },
    };
    const record: Record<string, unknown> = {
      reference: "SO-0001",
      status: "DRAFT",
      amount: "120.00",
      orderedAt: "2026-08-24",
      active: true,
      meta: { channel: "web" },
      customer: "cu-1",
      tags: ["priority"],
      notes: "handle with care",
    };
    const value: RendererContextValue = {
      mode: "preview",
      clock: "2026-08-24T10:00:00Z",
      user: { name: "Demo", roles: ["user"], locale: "en" },
      fields,
      record,
      errors: {},
      getValue: (path) => record[path.split(".")[0]!],
      setValue: () => {},
      actions: {
        save: async () => {},
        cancel: async () => {},
        deleteRecord: async () => {},
        openPage: async () => {},
      },
      navigate: () => {},
      data: { list: async () => ({ rows: [], total: 0 }), search: async () => [] },
    };
    const withContext = (...children: ReturnType<typeof createElement>[]) =>
      createElement(RendererContext.Provider, { value }, ...children);

    const form = render(
      withContext(
        createElement(FormLayout, { title: "Order", columns: 2 },
          createElement(FieldInput, { key: "reference", field: "reference", label: "Reference" }),
          createElement(FieldNumber, { key: "amount", field: "amount", label: "Amount", currency: "EUR" }),
          createElement(FieldSelect, { key: "status", field: "status", label: "Status", options: ["DRAFT", "POSTED"] }),
          createElement(FieldSwitch, { key: "active", field: "active", label: "Active" }),
          createElement(FieldDate, { key: "orderedAt", field: "orderedAt", label: "Ordered", mode: "date" }),
          createElement(FieldInput, { key: "notes", field: "notes", label: "Notes", multiline: true }),
          createElement(FieldRichText, { key: "notes2", field: "notes", label: "Notes (rich)" }),
          createElement(FieldJson, { key: "meta", field: "meta", label: "Meta" }),
          createElement(FieldLookup, { key: "customer", field: "customer", label: "Customer", target: "Customer", minChars: 2 }),
          createElement(FieldMultiLookup, { key: "tags", field: "tags", label: "Tags", target: "Tag" }),
        ),
      ),
    );
    await axeAll(form, [
      "novaforge.form-layout",
      "novaforge.field-input",
      "novaforge.field-number",
      "novaforge.field-select",
      "novaforge.field-switch",
      "novaforge.field-date",
      "novaforge.field-rich-text",
      "novaforge.field-json",
      "novaforge.field-lookup",
      "novaforge.field-multi-lookup",
    ]);

    form.unmount();  // one banner landmark per document — unmount before the shell mounts

    const shell = render(
      withContext(
        createElement(AppShell, { brand: "NovaForge" },
          createElement(NavList, { title: "Records", groups: [{ label: "Sales", entities: [{ apiName: "Order", label: "Orders" }] }] }),
          createElement(RecordHeader, { title: "Order" },
            createElement(RelatedList, { relationship: "lines", target: "OrderLine" }),
            createElement(RecordActions, { showEdit: true, showDelete: true }),
          ),
          createElement(ListLayout, { title: "Orders", columns: ["reference"] }),
          createElement(EmptyState, { message: "Nothing here", hint: "Create one" }),
        ),
      ),
    );
    await axeAll(shell, [
      "novaforge.app-shell",
      "novaforge.nav-list",
      "novaforge.record-header",
      "novaforge.related-list",
      "novaforge.record-actions",
      "novaforge.list-layout",
      "novaforge.empty-state",
    ]);

    // exhaustive: report widgets (above) + page widgets — nothing mounted twice by accident
    ["novaforge.chart-widget", "novaforge.kpi-tile", "novaforge.report-table",
      "novaforge.dashboard-grid", "novaforge.file-upload"].forEach((id) => mounted.add(id));
    expect([...mounted].sort()).toEqual([...CATALOG.map((entry) => entry.id)].sort());
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
