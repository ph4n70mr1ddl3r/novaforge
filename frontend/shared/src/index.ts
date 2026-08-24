import "./catalog/pages.css";
export {
  ChartWidget,
} from "./catalog/ChartWidget.tsx";
export { KpiTile } from "./catalog/KpiTile.tsx";
export { ReportTable } from "./catalog/ReportTable.tsx";
export { DashboardGrid, DashboardCell } from "./catalog/DashboardGrid.tsx";
export { FileUpload } from "./catalog/FileUpload.tsx";
export {
  FieldInput,
  FieldNumber,
  FieldSelect,
  FieldSwitch,
  FieldDate,
  FieldLookup,
  FieldMultiLookup,
  FieldRichText,
  FieldJson,
} from "./catalog/fields.tsx";
export {
  AppShell,
  NavList,
  FormLayout,
  ListLayout,
  RecordHeader,
  RelatedList,
  RecordActions,
  EmptyState,
} from "./catalog/layouts.tsx";
export { CATALOG, type CatalogEntry } from "./catalog/schemas.ts";
export { resolveComponent, catalogEntry } from "./registry.ts";
export type {
  ReportRun,
  ChartProjection,
  ChartSeries,
  ReportBinding,
} from "./report.ts";

// --- expr/v1: the TS twin of the JVM engine (PHASE-2 §7, shared conformance corpus) ---
export {
  Expression,
  display,
  EXPRESSION_VERSION,
  recordContext,
  type Bindings,
  type Clock,
  type CompilePolicy,
} from "./expression/expression.ts";
export { Decimal } from "./expression/decimal.ts";
export {
  ExpressionError,
  isDate,
  isInstant,
  dateValue,
  instantValue,
} from "./expression/values.ts";

// --- metadata wire types + permission resolution ---
export {
  resolveFieldAccess,
  resolveLabel,
  resolveObjectAccess,
  FULL_ACCESS,
  NO_ACCESS,
  FLOW_OPS,
  HOOK_TRIGGERS,
  SUITE_OPS,
  type AppDefinition,
  type DashboardDefinition,
  type EntityDefinition,
  type FieldAccess,
  type FieldDefinition,
  type FieldType,
  type PageDefinition,
  type PageType,
  type ObjectPermission,
  type PermissionSet,
  type PublishedApp,
  type RelationshipDefinition,
  type ReportDefinition,
  type ResolvedFieldAccess,
  type ResolvedObjectAccess,
  type ScheduledJobDefinition,
  type ScheduledJobTarget,
  type SharingRuleDefinition,
  type SlaDefinition,
  type StateMachineDefinition,
  type SuiteCase,
  type SuiteFixture,
  type SuiteStep,
  type TestSuiteDefinition,
  type FlowStep,
  type HookRule,
  type ValidationRule,
  type TranslationsDefinition,
} from "./metadata.ts";

// --- page model v0 + structural deltas (§4 / §13 Q2) ---
export {
  applyDeltas,
  defaultWidgetFor,
  diffPages,
  type ActionDef,
  type PageDelta,
  type PageModel,
  type PageNode,
  type ResolvedPage,
  type StaleDelta,
} from "./pagemodel/model.ts";
export {
  describeDelta,
  nodesByKey,
  pageDocument,
  toPatch,
  type JsonPatchOp,
} from "./pagemodel/patch.ts";
export {
  resolvePage,
  toPersistedLayout,
  isAuthoredLayout,
  type AnyLayout,
  type AuthoredLayout,
  type PersistedLayout,
} from "./pagemodel/pages.ts";
export {
  takesBinding,
  validatePage,
  validateSchema,
  type SchemaIssue,
  type ValidationContext,
} from "./pagemodel/validate.ts";

// --- L1 default resolver (§5) ---
export { resolveDefaultPage, resolveNav, pageApiName, type NavGroup, type ResolveOptions } from "./resolver.ts";

// --- L3 renderer (§6) ---
export {
  PageRenderer,
  type PageRendererProps,
} from "./renderer/renderer.ts";
export {
  RendererContext,
  dispatchAction,
  interpolate,
  resolvePath,
  useBoundValue,
  useRenderer,
  type ListRequest,
  type ListResult,
  type QueryFilter,
  type RendererContextValue,
  type RendererDataService,
  type RendererUser,
} from "./renderer/context.ts";

// --- gateway client (§2: browser apps reach APIs via the gateway) ---
export { ApiError, PlatformClient, type Problem, type TokenProvider } from "./client/platform.ts";
