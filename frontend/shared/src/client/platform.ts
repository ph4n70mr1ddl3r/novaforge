import type { ListRequest, ListResult, QueryFilter } from "../renderer/context.ts";

/**
 * The gateway client for the browser apps (PHASE-2 §2): one base URL (same-origin
 * static bundles behind the gateway — §13 Q5), bearer tokens from the OIDC flow,
 * problem+json error surfacing (the platform's error contract, PHASE-0 §5.2).
 */

export interface Problem {
    type: string;
    title: string;
    status: number;
    detail?: string;
    code?: string;
    errors?: { field?: string; message?: string }[];
}

export class ApiError extends Error {
    constructor(
        readonly status: number,
        readonly problem: Problem,
    ) {
        super(problem.detail ?? problem.title ?? `HTTP ${status}`);
    }

    get code(): string | undefined {
        return this.problem.code;
    }

    /** Field-level messages for form error display. */
    fieldErrors(): Record<string, string> {
        const out: Record<string, string> = {};
        for (const error of this.problem.errors ?? []) {
            if (error.field && error.message) {
                out[error.field] = error.message;
            }
        }
        return out;
    }
}

export interface TokenProvider {
    (): string | Promise<string>;
}

/**
 * The 401 recovery hook: called once per failed request with the platform's own
 * refresh machinery (single-flight on the caller's side); a returned token retries
 * the request exactly once, null gives up and surfaces the 401. Without it, an SPA's
 * every call fails once the access token ages out — the client had no notion of
 * expiry at all.
 */
export type UnauthorizedRefresh = () => Promise<string | null>;

export class PlatformClient {
    /** The same-origin base the client speaks to (file uploads ride it too). */
    readonly base: string;

    constructor(
        base: string,
        private readonly token: TokenProvider,
        private readonly fetchImpl: typeof fetch = fetch.bind(globalThis),
        private readonly onUnauthorized?: UnauthorizedRefresh,
    ) {
        this.base = base;
    }

    /** A live bearer token for legs outside this client (the file-upload widget
     *  addresses the File Service directly and needs the same token, refreshed). */
    async bearer(): Promise<string> {
        return this.token();
    }

    private async request(
        method: string,
        path: string,
        body?: unknown,
        headers: Record<string, string> = {},
    ): Promise<unknown> {
        const payload = body !== undefined ? JSON.stringify(body) : undefined;
        const send = async (bearer: string): Promise<Response> => this.fetchImpl(this.base + path, {
            method,
            headers: {
                ...(payload !== undefined ? { "Content-Type": "application/json" } : {}),
                ...(bearer ? { Authorization: `Bearer ${bearer}` } : {}),
                ...headers,
            },
            body: payload,
        });
        let token = await this.token();
        let response = await send(token);
        if (response.status === 401 && this.onUnauthorized) {
            // one refresh, one retry — a second 401 surfaces to the caller
            const refreshed = await this.onUnauthorized();
            if (refreshed) {
                token = refreshed;
                response = await send(token);
            }
        }
        if (response.status === 204) return null;
        const text = await response.text();
        const parsed = text ? (JSON.parse(text) as unknown) : null;
        if (!response.ok) {
            throw new ApiError(response.status, (parsed ?? {}) as Problem);
        }
        return parsed;
    }

    // --- metadata: apps & entities (PHASE-1 §4) ---

    listApps(): Promise<unknown[]> {
        return this.request("GET", "/api/v1/metadata/apps") as Promise<unknown[]>;
    }

    getApp(appId: string): Promise<unknown> {
        return this.request("GET", `/api/v1/metadata/apps/${appId}`);
    }

    createApp(draft: Record<string, unknown>): Promise<unknown> {
        return this.request("POST", "/api/v1/metadata/apps", draft);
    }

    patchApp(appId: string, patch: Record<string, unknown>): Promise<unknown> {
        return this.request("PATCH", `/api/v1/metadata/apps/${appId}`, patch);
    }

    putEntity(appId: string, entity: Record<string, unknown>): Promise<unknown> {
        return this.request("POST", `/api/v1/metadata/apps/${appId}/entities`, entity);
    }

    patchEntity(appId: string, entityApiName: string, patch: Record<string, unknown>): Promise<unknown> {
        return this.request("PATCH", `/api/v1/metadata/apps/${appId}/entities/${entityApiName}`, patch);
    }

    deleteEntity(appId: string, entityApiName: string): Promise<unknown> {
        return this.request("DELETE", `/api/v1/metadata/apps/${appId}/entities/${entityApiName}`);
    }

    putPage(appId: string, page: Record<string, unknown>): Promise<unknown> {
        return this.request("PUT", `/api/v1/metadata/apps/${appId}/pages/${page.apiName}`, page);
    }

    deletePage(appId: string, apiName: string): Promise<unknown> {
        return this.request("DELETE", `/api/v1/metadata/apps/${appId}/pages/${apiName}`);
    }

    publish(appId: string, acknowledgeDataImpact = false): Promise<unknown> {
        return this.request("POST", `/api/v1/metadata/apps/${appId}/publish`, { acknowledgeDataImpact });
    }

    versions(appId: string): Promise<unknown[]> {
        return this.request("GET", `/api/v1/metadata/apps/${appId}/versions`) as Promise<unknown[]>;
    }

    published(appId: string): Promise<{ version: number; app: Record<string, unknown> }> {
        return this.request("GET", `/api/v1/metadata/apps/${appId}/published`) as Promise<{
            version: number;
            app: Record<string, unknown>;
        }>;
    }

    // --- lifecycle (PHASE-8 §2–§5) ---

    environments(appId: string): Promise<unknown[]> {
        return this.request("GET", `/api/v1/metadata/apps/${appId}/environments`) as Promise<unknown[]>;
    }

    promote(appId: string, env: string, body: Record<string, unknown> = {}): Promise<unknown> {
        return this.request("POST", `/api/v1/metadata/apps/${appId}/environments/${env}/promote`, body);
    }

    rollback(appId: string, env: string, body: Record<string, unknown>): Promise<unknown> {
        return this.request("POST", `/api/v1/metadata/apps/${appId}/environments/${env}/rollback`, body);
    }

    changeset(appId: string, env: string): Promise<unknown> {
        return this.request("GET", `/api/v1/metadata/apps/${appId}/changeset?env=${env}`);
    }

    suiteRuns(appId: string): Promise<unknown> {
        return this.request("GET", `/api/v1/metadata/apps/${appId}/suite-runs`);
    }

    runSuites(appId: string, suites?: string[]): Promise<unknown> {
        return this.request("POST", `/api/v1/metadata/apps/${appId}/suite-runs`, { suites });
    }

    runSuite(appId: string, suite: string): Promise<unknown> {
        return this.request("POST", `/api/v1/metadata/apps/${appId}/test-suites/${suite}/run`);
    }

    // --- templates (PHASE-8 §6): the catalog, listed and installed in the builder ---

    templates(): Promise<Record<string, unknown>[]> {
        return this.request("GET", "/api/v1/metadata/templates") as Promise<Record<string, unknown>[]>;
    }

    registerTemplate(appId: string, version: number, name: string, publisher?: string, description?: string): Promise<Record<string, unknown>> {
        return this.request("POST", "/api/v1/metadata/templates", {
            appId, version, name, publisher, description,
        }) as Promise<Record<string, unknown>>;
    }

    installTemplate(templateId: string, apiName?: string): Promise<Record<string, unknown>> {
        return this.request("POST", `/api/v1/metadata/templates/${templateId}/install`, apiName ? { apiName } : {}) as Promise<Record<string, unknown>>;
    }

    putSuite(appId: string, apiName: string, suite: Record<string, unknown>): Promise<unknown> {
        return this.request("PUT", `/api/v1/metadata/apps/${appId}/test-suites/${apiName}`, suite);
    }

    translations(appId: string): Promise<unknown[]> {
        return this.request("GET", `/api/v1/metadata/apps/${appId}/translations`) as Promise<unknown[]>;
    }

    putTranslation(appId: string, locale: string, entries: Record<string, string>): Promise<unknown> {
        return this.request("PUT", `/api/v1/metadata/apps/${appId}/translations/${locale}`, entries);
    }

    // --- data runtime (PHASE-1 §5): the structured query DSL, one canonical encoding ---

    async list(request: ListRequest): Promise<ListResult> {
        const params = new URLSearchParams();
        if (request.filter) {
            params.set("filter", JSON.stringify(request.filter));
        }
        if (request.sort?.length) {
            params.set("sort", JSON.stringify(request.sort));
        }
        params.set("page", JSON.stringify({ size: request.size, offset: request.offset }));
        return this.request("GET", `/api/v1/runtime/${request.entity}?${params.toString()}`) as Promise<ListResult>;
    }

    async search(target: string, term: string, field: string, size = 10): Promise<Record<string, unknown>[]> {
        if (term.length < 2) return [];
        const filter: QueryFilter = { op: "contains", field, value: term };
        return this
            .list({ entity: target, filter, size, offset: 0 })
            .then((result) => result.rows) as Promise<Record<string, unknown>[]>;
    }

    getRecord(entity: string, id: string): Promise<Record<string, unknown>> {
        return this.request("GET", `/api/v1/runtime/${entity}/${id}`) as Promise<Record<string, unknown>>;
    }

    createRecord(entity: string, body: Record<string, unknown>, idempotencyKey?: string): Promise<Record<string, unknown>> {
        return this.request("POST", `/api/v1/runtime/${entity}`, body, idempotencyKey ? { "Idempotency-Key": idempotencyKey } : {}) as Promise<Record<string, unknown>>;
    }

    updateRecord(entity: string, id: string, version: number, body: Record<string, unknown>): Promise<Record<string, unknown>> {
        return this.request("PATCH", `/api/v1/runtime/${entity}/${id}`, { version, ...body }) as Promise<Record<string, unknown>>;
    }

    deleteRecord(entity: string, id: string, version: number): Promise<null> {
        return this.request("DELETE", `/api/v1/runtime/${entity}/${id}?version=${version}`) as Promise<null>;
    }

    /** The runFlow page action's leg (PHASE-3 §8): one named flow hook on demand. */
    runHook(entity: string, id: string, hook: string): Promise<Record<string, unknown>> {
        return this.request("POST", `/api/v1/runtime/${entity}/${id}/hooks/${encodeURIComponent(hook)}`, {}) as Promise<Record<string, unknown>>;
    }

    // --- workflow inbox (PHASE-4 §5) ---

    myTasks(status?: string, page = 0, size = 25): Promise<{ rows: Record<string, unknown>[]; total: number }> {
        const params = new URLSearchParams({ page: String(page), size: String(size) });
        if (status) params.set("status", status);
        return this.request("GET", `/api/v1/workflow/tasks?${params.toString()}`) as Promise<{
            rows: Record<string, unknown>[];
            total: number;
        }>;
    }

    async resolveTask(taskId: string, approve: boolean, comment?: string): Promise<Record<string, unknown>> {
        return (await this.request(
            "POST",
            `/api/v1/workflow/tasks/${taskId}/${approve ? "approve" : "reject"}`,
            comment ? { comment } : {},
        )) as Record<string, unknown>;
    }

    /** Claim a role-addressed task (PHASE-4 §5) — stays OPEN, assignee becomes mine. */
    claimTask(taskId: string): Promise<Record<string, unknown>> {
        return this.request("POST", `/api/v1/workflow/tasks/${taskId}/claim`, {}) as Promise<Record<string, unknown>>;
    }

    /** Delegate to another user (PHASE-4 §5): replacement task, original DELEGATED. */
    delegateTask(taskId: string, toUser: string): Promise<Record<string, unknown>> {
        return this.request("POST", `/api/v1/workflow/tasks/${taskId}/delegate`, { toUser }) as Promise<Record<string, unknown>>;
    }

    // --- scheduler visibility (PHASE-4 §2/§11): the one read-only registry route ---

    schedulerJobs(): Promise<Record<string, unknown>[]> {
        return this.request("GET", "/api/v1/scheduler/jobs") as Promise<Record<string, unknown>[]>;
    }

    // --- integrations (PHASE-6 §3/§9): delivery log, DLQ, secret provisioning ---

    integrationDeliveries(kind?: string, limit = 100): Promise<Record<string, unknown>[]> {
        const params = new URLSearchParams({ limit: String(limit) });
        if (kind) params.set("kind", kind);
        return this.request("GET", `/api/v1/integrations/deliveries?${params.toString()}`) as Promise<Record<string, unknown>[]>;
    }

    integrationDlq(kind?: string): Promise<Record<string, unknown>[]> {
        const params = kind ? `?kind=${encodeURIComponent(kind)}` : "";
        return this.request("GET", `/api/v1/integrations/dlq${params}`) as Promise<Record<string, unknown>[]>;
    }

    replayDlqEntry(id: string): Promise<Record<string, unknown>> {
        return this.request("POST", `/api/v1/integrations/dlq/${id}/replay`, {}) as Promise<Record<string, unknown>>;
    }

    /** The secret material goes to the encrypted store — never into metadata (§9). */
    putSecret(ref: string, material: string, retireEarlier = false): Promise<Record<string, unknown>> {
        return this.request("POST", `/api/v1/integrations/secrets/${encodeURIComponent(ref)}`, {
            material,
            retireEarlier,
        }) as Promise<Record<string, unknown>>;
    }

    // --- import/export jobs (PHASE-6 §7/§9): the builder progress surface ---

    /** Lists job runs (imports + exports) with their progress counters. */
    integrationJobs(): Promise<Record<string, unknown>[]> {
        return this.request("GET", "/api/v1/integrations/jobs") as Promise<Record<string, unknown>[]>;
    }

    /** The per-item outcome ledger of one job (the retained audit trail, §7). */
    integrationJobRows(jobId: string): Promise<Record<string, unknown>[]> {
        return this.request("GET", `/api/v1/integrations/jobs/${jobId}/rows`) as Promise<
            Record<string, unknown>[]
        >;
    }

    /** Resumes a paused/failed import from its checkpoint (§11 item 4). */
    resumeIntegrationJob(jobId: string): Promise<Record<string, unknown>> {
        return this.request("POST", `/api/v1/integrations/jobs/${jobId}/resume`, {}) as Promise<
            Record<string, unknown>
        >;
    }

    // --- notifications (PHASE-4 §8): own inbox + channel preferences ---

    notifications(page = 0, size = 25): Promise<{ rows: Record<string, unknown>[]; total: number }> {
        const params = new URLSearchParams({ page: String(page), size: String(size) });
        return this.request("GET", `/api/v1/notifications?${params.toString()}`) as Promise<{
            rows: Record<string, unknown>[];
            total: number;
        }>;
    }

    markNotificationRead(id: string): Promise<Record<string, unknown>> {
        return this.request("POST", `/api/v1/notifications/${id}/read`, {}) as Promise<Record<string, unknown>>;
    }

    notificationPreferences(): Promise<Record<string, unknown>[]> {
        return this.request("GET", "/api/v1/notifications/preferences") as Promise<Record<string, unknown>[]>;
    }

    setNotificationPreference(category: string, inbox: boolean, email: boolean): Promise<Record<string, unknown>> {
        return this.request("POST", "/api/v1/notifications/preferences", { category, inbox, email }) as Promise<Record<string, unknown>>;
    }

    // --- reporting (PHASE-5 §4) ---

    async runReport(appApiName: string, reportId: string, params: Record<string, unknown> = {}): Promise<Record<string, unknown>> {
        return (await this.request("POST", `/api/v1/reports/${reportId}/run`, { app: appApiName, params })) as Record<string, unknown>;
    }

    // --- platform admin (PHASE-2 §10) ---

    createTenant(body: Record<string, unknown>): Promise<Record<string, unknown>> {
        return this.request("POST", "/api/v1/admin/tenants", body) as Promise<Record<string, unknown>>;
    }

    assignRole(tenantId: string, body: Record<string, unknown>): Promise<Record<string, unknown>> {
        return this.request("POST", `/api/v1/admin/tenants/${tenantId}/role-assignments`, body) as Promise<Record<string, unknown>>;
    }
}
