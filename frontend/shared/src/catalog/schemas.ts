/**
 * The versioned component catalog (ADR-009 L3): every component declares a JSON
 * Schema (draft 2020-12) for its props, and the catalog pins component versions —
 * pages reference `{id, version}` so page definitions never rot as the catalog
 * evolves. The builder validates authored props against these schemas; the runtime
 * renderer resolves by the same ids.
 */

export interface CatalogEntry {
  /** The stable catalog id (`novaforge.<component>`). */
  id: string;
  /** Schema version of the component's props — pages pin this. */
  version: "1.0.0";
  /** JSON Schema (draft 2020-12) for the component's props. */
  schema: Record<string, unknown>;
}

const chartWidget: CatalogEntry = {
  id: "novaforge.chart-widget",
  version: "1.0.0",
  schema: {
    $schema: "https://json-schema.org/draft/2020-12/schema",
    type: "object",
    required: ["reportRef", "chart"],
    properties: {
      reportRef: { type: "string", pattern: "^[a-zA-Z_][a-zA-Z0-9_]*$" },
      title: { type: "string" },
      kind: { enum: ["bar", "line"] },
      height: { type: "integer", minimum: 80, maximum: 1200 },
      chart: {
        type: "object",
        required: ["xAxis", "series"],
        properties: {
          xAxis: {
            type: "object",
            required: ["data"],
            properties: { data: { type: "array" } },
          },
          series: {
            type: "array",
            items: {
              type: "object",
              required: ["name", "data"],
              properties: {
                name: { type: "string" },
                data: { type: "array" },
              },
            },
          },
        },
      },
    },
  },
};

const kpiTile: CatalogEntry = {
  id: "novaforge.kpi-tile",
  version: "1.0.0",
  schema: {
    $schema: "https://json-schema.org/draft/2020-12/schema",
    type: "object",
    required: ["reportRef", "totals", "metric"],
    properties: {
      reportRef: { type: "string", pattern: "^[a-zA-Z_][a-zA-Z0-9_]*$" },
      totals: { type: "object", additionalProperties: { type: "number" } },
      metric: { type: "string", minLength: 1 },
      label: { type: "string" },
      currency: { type: "string", minLength: 1, maxLength: 3 },
    },
  },
};

const reportTable: CatalogEntry = {
  id: "novaforge.report-table",
  version: "1.0.0",
  schema: {
    $schema: "https://json-schema.org/draft/2020-12/schema",
    type: "object",
    required: ["reportRef", "run"],
    properties: {
      reportRef: { type: "string", pattern: "^[a-zA-Z_][a-zA-Z0-9_]*$" },
      title: { type: "string" },
      labels: { type: "object", additionalProperties: { type: "string" } },
      run: {
        type: "object",
        required: ["columns", "rows", "totals", "chart"],
        properties: {
          columns: { type: "array", items: { type: "string" } },
          rows: { type: "array", items: { type: "object" } },
          totals: { type: "object", additionalProperties: { type: "number" } },
          chart: { type: "object" },
        },
      },
    },
  },
};

const dashboardGrid: CatalogEntry = {
  id: "novaforge.dashboard-grid",
  version: "1.0.0",
  schema: {
    $schema: "https://json-schema.org/draft/2020-12/schema",
    type: "object",
    properties: {
      children: { type: "array", items: { type: "object" } },
    },
  },
};

/** The catalog manifest — the versioned contract the builder pins pages against. */
export const CATALOG: readonly CatalogEntry[] = Object.freeze([
  chartWidget,
  kpiTile,
  reportTable,
  dashboardGrid,
]);
