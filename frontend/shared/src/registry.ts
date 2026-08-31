import { lazy, type ComponentType, type LazyExoticComponent } from "react";
import { CATALOG, type CatalogEntry } from "./catalog/schemas.ts";

/**
 * The lazily-loaded React registry (ADR-009 §2): the runtime renderer resolves
 * components by `{id, version}` — versions pin, unknown ids or versions reject
 * loudly (never silently render a newer contract against an older page), and the
 * implementation imports are code-split so a dashboard pays only for what it shows.
 */
type AnyComponent = LazyExoticComponent<ComponentType<Record<string, unknown>>>;

const IMPLEMENTATIONS: Readonly<Record<string, () => Promise<{ default: AnyComponent }>>> = {
  // named-only exports must wrap in { default: … } — React lazy rejects a module
  // namespace without a default, and the bare-import form resolved to undefined:
  // these five widgets never rendered through the runtime renderer at all (found
  // live when the fourteenth pass's upload-wiring pin first drove one)
  "novaforge.chart-widget": async () => ({ default: (await import("./catalog/ChartWidget.tsx")).ChartWidget }) as never,
  "novaforge.kpi-tile": async () => ({ default: (await import("./catalog/KpiTile.tsx")).KpiTile }) as never,
  "novaforge.report-table": async () => ({ default: (await import("./catalog/ReportTable.tsx")).ReportTable }) as never,
  "novaforge.dashboard-grid": async () => ({ default: (await import("./catalog/DashboardGrid.tsx")).DashboardGrid }) as never,
  "novaforge.file-upload": async () => ({ default: (await import("./catalog/FileUpload.tsx")).FileUpload }) as never,
  "novaforge.field-input": async () => ({ default: (await import("./catalog/fields.tsx")).FieldInput }) as never,
  "novaforge.field-number": async () => ({ default: (await import("./catalog/fields.tsx")).FieldNumber }) as never,
  "novaforge.field-select": async () => ({ default: (await import("./catalog/fields.tsx")).FieldSelect }) as never,
  "novaforge.field-switch": async () => ({ default: (await import("./catalog/fields.tsx")).FieldSwitch }) as never,
  "novaforge.field-date": async () => ({ default: (await import("./catalog/fields.tsx")).FieldDate }) as never,
  "novaforge.field-lookup": async () => ({ default: (await import("./catalog/fields.tsx")).FieldLookup }) as never,
  "novaforge.field-multi-lookup": async () => ({ default: (await import("./catalog/fields.tsx")).FieldMultiLookup }) as never,
  "novaforge.field-rich-text": async () => ({ default: (await import("./catalog/fields.tsx")).FieldRichText }) as never,
  "novaforge.field-json": async () => ({ default: (await import("./catalog/fields.tsx")).FieldJson }) as never,
  "novaforge.app-shell": async () => ({ default: (await import("./catalog/layouts.tsx")).AppShell }) as never,
  "novaforge.nav-list": async () => ({ default: (await import("./catalog/layouts.tsx")).NavList }) as never,
  "novaforge.form-layout": async () => ({ default: (await import("./catalog/layouts.tsx")).FormLayout }) as never,
  "novaforge.list-layout": async () => ({ default: (await import("./catalog/layouts.tsx")).ListLayout }) as never,
  "novaforge.record-header": async () => ({ default: (await import("./catalog/layouts.tsx")).RecordHeader }) as never,
  "novaforge.related-list": async () => ({ default: (await import("./catalog/layouts.tsx")).RelatedList }) as never,
  "novaforge.record-actions": async () => ({ default: (await import("./catalog/layouts.tsx")).RecordActions }) as never,
  "novaforge.empty-state": async () => ({ default: (await import("./catalog/layouts.tsx")).EmptyState }) as never,
};

const RESOLVED = new Map<string, AnyComponent>();

/** Resolves a catalog id to its lazily-imported implementation. */
export function resolveComponent(id: string, version: string): AnyComponent {
  const entry = catalogEntry(id);
  if (entry.version !== version) {
    throw new Error(
      `catalog version mismatch for ${id}: page pins ${version}, catalog serves ` +
        `${entry.version} — republish the page against the served version`,
    );
  }
  const loader = IMPLEMENTATIONS[id];
  if (!loader) {
    throw new Error(`unknown catalog component: ${id}`);
  }
  let component = RESOLVED.get(id);
  if (!component) {
    component = lazy(loader);
    RESOLVED.set(id, component);
  }
  return component;
}

/** The catalog entry (props schema + version) for builder-side validation. */
export function catalogEntry(id: string): CatalogEntry {
  const entry = CATALOG.find((item) => item.id === id);
  if (!entry) {
    throw new Error(`unknown catalog component: ${id}`);
  }
  return entry;
}

export { CATALOG };
