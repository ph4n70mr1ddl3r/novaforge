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
  "novaforge.chart-widget": () => import("./catalog/ChartWidget.tsx") as never,
  "novaforge.kpi-tile": () => import("./catalog/KpiTile.tsx") as never,
  "novaforge.report-table": () => import("./catalog/ReportTable.tsx") as never,
  "novaforge.dashboard-grid": () => import("./catalog/DashboardGrid.tsx") as never,
  "novaforge.file-upload": () => import("./catalog/FileUpload.tsx") as never,
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
