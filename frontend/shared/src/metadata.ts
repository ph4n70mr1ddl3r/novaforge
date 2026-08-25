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

/**
 * An event-hook rule (PHASE-3 §2): a trigger + a flow-IR step graph, record scope.
 * Triggers v1: beforeSave | afterSave | beforeDelete | afterDelete; the vocabulary's
 * first versioned growth adds `scheduled` (the recordless trigger only the
 * Scheduler's by-name firing executes — PHASE-7 §5's bank-feed shape). The body is
 * either a flow or a script artifact (PHASE-3 §6) — exactly one is present.
 */
export interface HookRule {
    name?: string;
    trigger: string;
    flow?: FlowStep;
    script?: { language: string; source: string; sandbox?: string };
}

/** One node of a flow-IR step graph (PHASE-3 §2): {id, op, params, next}. */
export interface FlowStep {
    id: string;
    op: string;
    params?: Record<string, unknown>;
    next?: string;
    onTrue?: string;
    onFalse?: string;
    body?: FlowStep;
}

/** The closed v1 primitive set (ADR-008 #2). */
export const FLOW_OPS = [
    "setField",
    "createRecord",
    "updateRecord",
    "publishEvent",
    "branch",
    "iterate",
    "transitionState",
    "requestApproval",
    "callConnector",
] as const;

/** The v1 hook triggers + the first versioned growth (PHASE-7 §5's scheduled pull). */
export const HOOK_TRIGGERS = [
    "beforeSave",
    "afterSave",
    "beforeDelete",
    "afterDelete",
    "scheduled",
] as const;

// --- builder test suites (PHASE-3 §7, ADR-010) ---

export interface SuiteFixture {
    entity: string;
    asRole?: string;
    template: Record<string, unknown>;
}

export interface SuiteStep {
    op: string;
    entity?: string;
    asRole?: string;
    recordId?: string;
    template?: Record<string, unknown>;
    expect?: string;
}

export interface SuiteCase {
    name: string;
    fixtures: SuiteFixture[];
    steps: SuiteStep[];
    assertExpressions: string[];
    /** PHASE-3 §7's per-case frozen-clock override (ISO-8601 instant; absent = run start). */
    clock?: string;
}

/** The Tests branch (ADR-010): fixtures → steps → assertions per PHASE-3 §7. */
export interface TestSuiteDefinition {
    apiName: string;
    label?: string;
    cases: SuiteCase[];
}

/** The suite step vocabulary v1 + the growth (§12/§9/§10). */
export const SUITE_OPS = [
    "createRecord",
    "updateRecord",
    "deleteRecord",
    "queryRecord",
    "resolveTask",
    "runReport",
    "postWebhook",
    "scanSla",
] as const;

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
    entity: string;
    type: "owner" | "roleHierarchy" | "criteria";
    /** The roles the rule shares with (owner: see-everything roles; criteria: recipients). */
    roles?: string[];
    /** owner only: an explicit owner field (default: the creator). */
    ownerField?: string;
    /** criteria only: a record-context expression compiled at publish (PHASE-4 §10). */
    criteria?: string;
}

export interface PermissionSet {
    roles: RoleDefinition[];
    objectPermissions: ObjectPermission[];
    fieldSecurity: FieldSecurity[];
    sharingRules?: SharingRuleDefinition[];
}

// --- state machines (PHASE-4 §3) — the write path's gate, rendered as record actions ---

export interface StateMachineDefinition {
    id: string;
    entity: string;
    /** The enum field whose value is the record's state. */
    stateField: string;
    initial: string;
    states: { name: string; terminal?: boolean }[];
    /** A listed edge; `guard` is a record-context expression compiled at publish. */
    transitions: { from: string; to: string; guard?: string }[];
}

// --- SLAs (PHASE-4 §6) — the governed overlay over requestApproval's own timers ---

export interface SlaDefinition {
    id: string;
    scope?: { taskType?: string; match?: string };
    /** ISO-8601 duration from task createdAt to breach. */
    target: string;
    /** Fraction of target (0.8 = warn at 80%); null disables the warn timer. */
    warnAt?: number | null;
    onBreach?: { escalateTo?: string; notify?: boolean };
}

// --- scheduled jobs (PHASE-4 §7) — versioned metadata activated on publish ---

export type ScheduledJobTarget = "flow" | "script" | "processStart" | "report";

export interface ScheduledJobDefinition {
    name: string;
    cron: string;
    target: ScheduledJobTarget;
    params?: Record<string, unknown>;
    enabled?: boolean;
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

/** The §5 drill-through binding: rows deep-link the bound entity's list. */
export interface DrillThrough {
    entity: string;
    carryFilters?: boolean;
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
    drillThrough?: DrillThrough;
    params?: unknown[];
}

export interface DashboardWidget {
    widget: "kpi" | "chart" | "table";
    reportRef: string;
    params?: Record<string, unknown>;
    span?: number;
    /** §5 auto-refresh: the client-timer interval in seconds — absent = static. */
    refreshSeconds?: number;
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

// --- the gap log (PHASE-7 §1 rule 2 / §8, PHASE-8 §3) ---

export interface GapLogEntry {
    id: string;
    area: string;
    blocker: string;
    workaround?: string;
    /** The proposed primitive / flag the gap harvests toward. */
    proposed?: string;
    priority: "high" | "medium" | "low";
    disposition: "open" | "accept-as-platform-feature" | "backlog" | "wontfix-with-workaround" | "closed";
    /** What resolved the entry — rides triaged dispositions only. */
    resolvedIn?: string;
}

// --- integrations (PHASE-6 §2): connectors, webhooks, credential references, imports ---

export interface ConnectorOperation {
    name: string;
    method: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
    path: string;
    query?: Record<string, unknown>;
    headers?: Record<string, unknown>;
    body?: unknown;
}

export interface ConnectorDefinition {
    id: string;
    type: "rest";
    baseUrl: string;
    credential?: string;
    operations?: ConnectorOperation[];
}

export interface WebhookMapping {
    mode: "create" | "upsert";
    keyFields?: string[];
    idempotencyKey?: string;
    fields?: Record<string, unknown>;
}

export interface WebhookDefinition {
    id: string;
    direction: "inbound" | "outbound";
    /** outbound: the target URL + spine-event filter expression. */
    url?: string;
    events?: string;
    /** inbound: the target entity + its mapping onto the write path. */
    entity?: string;
    mapping?: WebhookMapping;
    secretRef?: string;
    enabled?: boolean;
}

/** A credential reference — the secret material never rides metadata (§9). */
export interface CredentialDefinition {
    id: string;
    kind: "api_key" | "basic" | "oauth2_client_credentials";
    header?: string;
    username?: string;
    tokenUrl?: string;
    clientId?: string;
    scopes?: string[];
}

export interface ImportDefinition {
    apiName: string;
    entity: string;
    mapping?: Record<string, unknown>;
    mode: "create" | "upsert";
    keyFields?: string[];
}

export interface IntegrationsDefinition {
    connectors?: ConnectorDefinition[];
    webhooks?: WebhookDefinition[];
    credentials?: CredentialDefinition[];
    imports?: ImportDefinition[];
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
    slas?: SlaDefinition[];
    jobs?: ScheduledJobDefinition[];
    reports: ReportDefinition[];
    dashboards: DashboardDefinition[];
    integrations?: IntegrationsDefinition;
    testSuites?: TestSuiteDefinition[];
    translations: TranslationsDefinition[];
    gapLog?: GapLogEntry[];
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
