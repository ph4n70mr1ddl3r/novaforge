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

const fileUpload: CatalogEntry = {
  id: "novaforge.file-upload",
  version: "1.0.0",
  schema: {
    $schema: "https://json-schema.org/draft/2020-12/schema",
    type: "object",
    properties: {
      entity: { type: "string", pattern: "^[A-Z][A-Za-z0-9]*$" },
      recordId: { type: "string", format: "uuid" },
      filesBase: { type: "string" },
    },
  },
};

const fieldPattern = { type: "string", pattern: "^[a-zA-Z_][a-zA-Z0-9_]*$" };

function fieldWidget(id: string, properties: Record<string, unknown>, required: string[] = ["field"]): CatalogEntry {
  return {
    id,
    version: "1.0.0",
    schema: {
      $schema: "https://json-schema.org/draft/2020-12/schema",
      type: "object",
      required,
      properties: { field: fieldPattern, label: { type: "string" }, ...properties },
    },
  };
}

const PAGE_CATALOG: readonly CatalogEntry[] = Object.freeze([
  {
    id: "novaforge.app-shell",
    version: "1.0.0",
    schema: {
      $schema: "https://json-schema.org/draft/2020-12/schema",
      type: "object",
      properties: {
        brand: { type: "string" },
        variant: { enum: ["runtime", "builder"] },
      },
    },
  },
  {
    id: "novaforge.nav-list",
    version: "1.0.0",
    schema: {
      $schema: "https://json-schema.org/draft/2020-12/schema",
      type: "object",
      properties: { title: { type: "string" } },
    },
  },
  {
    id: "novaforge.form-layout",
    version: "1.0.0",
    schema: {
      $schema: "https://json-schema.org/draft/2020-12/schema",
      type: "object",
      properties: {
        title: { type: "string" },
        columns: { type: "integer", minimum: 1, maximum: 4 },
        section: { type: ["string", "null"] },
      },
    },
  },
  {
    id: "novaforge.list-layout",
    version: "1.0.0",
    schema: {
      $schema: "https://json-schema.org/draft/2020-12/schema",
      type: "object",
      properties: {
        title: { type: "string" },
        pageSize: { type: "integer", minimum: 1, maximum: 200 },
        sortable: { type: "boolean" },
        columns: { type: "array", items: { type: "string" } },
      },
    },
  },
  {
    id: "novaforge.record-header",
    version: "1.0.0",
    schema: {
      $schema: "https://json-schema.org/draft/2020-12/schema",
      type: "object",
      properties: {
        title: { type: "string" },
        displayField: { type: "string" },
      },
    },
  },
  fieldWidget("novaforge.field-input", {
    inputType: { enum: ["text", "email", "tel", "url"] },
    multiline: { type: "boolean" },
    placeholder: { type: "string", maxLength: 200 },
  }),
  fieldWidget("novaforge.field-number", {
    currency: { type: "string", minLength: 1, maxLength: 3 },
    scale: { type: "integer", minimum: 0, maximum: 12 },
  }),
  fieldWidget("novaforge.field-select", {
    options: { type: "array", items: { type: "string" }, minItems: 1 },
  }),
  fieldWidget("novaforge.field-switch", {}),
  fieldWidget("novaforge.field-date", {
    mode: { enum: ["date", "datetime", "time"] },
  }),
  fieldWidget("novaforge.field-lookup", {
    target: { type: "string", pattern: "^[A-Z][A-Za-z0-9]*$" },
    minChars: { type: "integer", minimum: 1, maximum: 10 },
  }, ["field", "target"]),
  fieldWidget("novaforge.field-multi-lookup", {
    target: { type: "string", pattern: "^[A-Z][A-Za-z0-9]*$" },
  }, ["field", "target"]),
  fieldWidget("novaforge.field-rich-text", {}),
  fieldWidget("novaforge.field-json", {}),
  {
    id: "novaforge.related-list",
    version: "1.0.0",
    schema: {
      $schema: "https://json-schema.org/draft/2020-12/schema",
      type: "object",
      required: ["relationship"],
      properties: {
        relationship: fieldPattern,
        target: { type: "string", pattern: "^[A-Z][A-Za-z0-9]*$" },
        pageSize: { type: "integer", minimum: 1, maximum: 200 },
        columns: { type: "array", items: { type: "string" } },
      },
    },
  },
  {
    id: "novaforge.record-actions",
    version: "1.0.0",
    schema: {
      $schema: "https://json-schema.org/draft/2020-12/schema",
      type: "object",
      properties: {
        showEdit: { type: "boolean" },
        showDelete: { type: "boolean" },
      },
    },
  },
  {
    id: "novaforge.empty-state",
    version: "1.0.0",
    schema: {
      $schema: "https://json-schema.org/draft/2020-12/schema",
      type: "object",
      properties: {
        message: { type: "string" },
        hint: { type: "string" },
      },
    },
  },
]);

/** The catalog manifest — the versioned contract the builder pins pages against. */
export const CATALOG: readonly CatalogEntry[] = Object.freeze([
  chartWidget,
  kpiTile,
  reportTable,
  dashboardGrid,
  fileUpload,
  ...PAGE_CATALOG,
]);
