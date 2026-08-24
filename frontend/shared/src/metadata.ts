/**
 * Wire types for the published/draft metadata the renderer and builder consume —
 * the TS twins of `platform/libs/metadata-model` (schema v0). Field types and
 * relationships per ARCHITECTURE.md §3; labels carry the optional `label_i18n`
 * map (PHASE-2 §13 Q3, resolved — the Phase 8 editor writes them).
 */

export type FieldType =
    | "text"
    | "longText"
    | "richText"
    | "enum"
    | "boolean"
    | "int"
    | "long"
    | "decimal"
    | "date"
    | "datetime"
    | "time"
    | "uuid"
    | "email"
    | "phone"
    | "url"
    | "json"
    | "lookup"
    | "child"
    | "m2m"
    | "file"
    | "money";

export interface DefaultValue {
    value?: unknown;
    expression?: string;
}

export interface FieldDefinition {
    apiName: string;
    label?: string;
    label_i18n?: Record<string, string>;
    type: FieldType;
    required?: boolean;
    uniqueness?: boolean;
    readonly?: boolean;
    length?: number;
    precision?: number;
    scale?: number;
    group?: string;
    default?: DefaultValue;
    target?: string;
    values?: string[];
    currency?: string;
    formula?: string;
    rollup?: string;
}

export type RelationshipType = "child" | "m2m";

export interface RelationshipDefinition {
    apiName: string;
    type: RelationshipType;
    target: string;
    cascadeDelete?: boolean;
}

export interface ValidationRule {
    name: string;
    scope?: string;
    expression: string;
    message: string;
}

export interface HookRule {
    trigger: string;
    flow: unknown;
}

export interface IndexDefinition {
    fields: string[];
    unique?: boolean;
}

export interface PeriodLock {
    entity: string;
    dateField: string;
    fromField?: string;
    toField?: string;
    statusField?: string;
    closedStatus?: string;
}

export interface EntityDefinition {
    id?: string;
    apiName: string;
    label?: string;
    label_i18n?: Record<string, string>;
    displayField?: string;
    module?: string;
    freezeOnTerminal?: boolean;
    periodLock?: PeriodLock;
    fields: FieldDefinition[];
    relationships: RelationshipDefinition[];
    validations: ValidationRule[];
    hooks: HookRule[];
    indexes: IndexDefinition[];
}

// --- permissions (PHASE-2 §9) ---

export interface RoleDefinition {
    name: string;
    description?: string;
    level?: number;
}

export interface ObjectPermission {
    role: string;
    entity: string;
    create?: boolean;
    read?: boolean;
    update?: boolean;
    delete?: boolean;
    reportExecute?: boolean;
}

export type FieldAccess = "visible" | "readonly" | "hidden";

export interface FieldSecurity {
    role: string;
    entity: string;
    field: string;
    access: FieldAccess;
}

export interface SharingRuleDefinition {
    name: string;
    entity: string;
    /** Sharing-rule criteria compile through the same expression grammar (PHASE-4 §10). */
    criteria?: string;
    withRole?: string;
    access?: "read" | "readWrite";
}

export interface PermissionSet {
    roles: RoleDefinition[];
    objectPermissions: ObjectPermission[];
    fieldSecurity: FieldSecurity[];
    sharingRules?: SharingRuleDefinition[];
}

// --- state machines (PHASE-4 §3) — read for render (transitions shown as actions) ---

export interface StateMachineDefinition {
    id: string;
    entity: string;
    field: string;
    states: { name: string; terminal?: boolean }[];
    transitions: { from: string; to: string; label?: string }[];
    initial: string;
}

// --- reports & dashboards (PHASE-5 §3/§5) ---

export interface ReportFilter {
    field: string;
    op: string;
    value?: unknown;
}

export interface ReportBucket {
    label: string;
    expression: string;
}

export interface ReportGroupBy {
    field: string;
    buckets?: ReportBucket[];
}

export interface ReportAggregate {
    op: "count" | "sum" | "avg" | "min" | "max";
    field: string;
    alias: string;
}

export interface ReportDefinition {
    id: string;
    label?: string;
    label_i18n?: Record<string, string>;
    entity: string;
    description?: string;
    columns?: unknown[];
    aggregates?: ReportAggregate[];
    groupBy?: ReportGroupBy[];
    filters?: ReportFilter[];
    params?: unknown[];
}

export interface DashboardWidget {
    widget: "kpi" | "chart" | "table";
    reportRef: string;
    params?: Record<string, unknown>;
    span?: number;
}

export interface DashboardDefinition {
    id: string;
    label?: string;
    label_i18n?: Record<string, string>;
    widgets: DashboardWidget[];
    roles?: string[];
}

// --- translations (PHASE-8 §7) ---

export interface TranslationsDefinition {
    locale: string;
    entries: Record<string, string>;
}

// --- the app bundle ---

export type PageType = "form" | "list" | "detail" | "dashboard" | "custom";

/**
 * A page definition as stored metadata (the reserved `pages` branch, authored from
 * Phase 2): identity + entity + the Phase 2 page model in `layout` (§4).
 */
export interface PageDefinition {
    id?: string;
    apiName: string;
    label?: string;
    label_i18n?: Record<string, string>;
    type: PageType;
    entity?: string;
    layout?: unknown;
}

export interface AppDefinition {
    id?: string;
    apiName: string;
    label?: string;
    label_i18n?: Record<string, string>;
    description?: string;
    entities: EntityDefinition[];
    pages: PageDefinition[];
    permissionSet: PermissionSet;
    stateMachines: StateMachineDefinition[];
    reports: ReportDefinition[];
    dashboards: DashboardDefinition[];
    translations: TranslationsDefinition[];
}

/** The published read (PHASE-1 §4): bundle + version for cache keys. */
export interface PublishedApp {
    version: number;
    app: AppDefinition;
}

/** Field-level security resolved for one role × entity (§9). */
export interface ResolvedFieldAccess {
    visible: boolean;
    readonly: boolean;
}

export const FULL_ACCESS: ResolvedFieldAccess = { visible: true, readonly: false };

/** Object CRUD resolved for one role × entity (absent flags deny). */
export interface ResolvedObjectAccess {
    create: boolean;
    read: boolean;
    update: boolean;
    delete: boolean;
}

export const NO_ACCESS: ResolvedObjectAccess = {
    create: false,
    read: false,
    update: false,
    delete: false,
};

export function resolveObjectAccess(
    permissions: PermissionSet,
    role: string | undefined,
    entity: string,
): ResolvedObjectAccess {
    const grants = permissions.objectPermissions.filter(
        (permission) => permission.role === role && permission.entity === entity,
    );
    return {
        create: grants.some((g) => g.create === true),
        read: grants.some((g) => g.read === true),
        update: grants.some((g) => g.update === true),
        delete: grants.some((g) => g.delete === true),
    };
}

export function resolveFieldAccess(
    permissions: PermissionSet,
    role: string | undefined,
    entity: string,
    field: string,
): ResolvedFieldAccess {
    const rules = permissions.fieldSecurity.filter(
        (security) => security.role === role && security.entity === entity && security.field === field,
    );
    for (const rule of rules) {
        if (rule.access === "hidden") return { visible: false, readonly: false };
        if (rule.access === "readonly") return { visible: true, readonly: true };
    }
    return FULL_ACCESS;
}

/**
 * The i18n fallback chain — pinned `label_i18n[locale] → label → apiName`, never
 * blank (PHASE-8 §7; the JVM twin lives in metadata-model's TranslationsDefinition).
 */
export function resolveLabel(
    labeled: { label?: string; label_i18n?: Record<string, string> } | undefined,
    locale: string | undefined,
    apiName: string,
): string {
    const localized = locale ? labeled?.label_i18n?.[locale] : undefined;
    return localized ?? labeled?.label ?? apiName;
}
