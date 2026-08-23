export {
  ChartWidget,
} from "./catalog/ChartWidget.tsx";
export { KpiTile } from "./catalog/KpiTile.tsx";
export { ReportTable } from "./catalog/ReportTable.tsx";
export { DashboardGrid, DashboardCell } from "./catalog/DashboardGrid.tsx";
export { CATALOG, type CatalogEntry } from "./catalog/schemas.ts";
export { resolveComponent, catalogEntry } from "./registry.ts";
export type {
  ReportRun,
  ChartProjection,
  ChartSeries,
  ReportBinding,
} from "./report.ts";
