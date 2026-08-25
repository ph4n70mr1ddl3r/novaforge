package com.novaforge.script.engine;

import com.novaforge.common.context.TenantContext;

/**
 * Host-side query execution: the api layer binds this to the Data Runtime's query
 * API. Every caller-context call carries the <em>calling</em> user's context
 * (ARCHITECTURE.md §5 item 4) — the binding is what keeps a script inside its
 * authorizing user's grants. The scheduled leg
 * ({@link #systemQuery(Context, String, String, String)}) is the engine-driven
 * twin: a recordless firing runs as the per-app system principal through the
 * runtime's internal surface (PHASE-4 §7 — the same execution-context rule the
 * Scheduler's {@code flow} target rides).
 */
public interface QueryProxy {

    /**
     * Runs {@code queryJson} ({@code {filter, sort, page}} — the query DSL's list
     * shape) against {@code entity} as {@code caller}.
     *
     * @return the query result as wire-shaped data ({@code {rows, total}})
     */
    Object query(TenantContext.Context caller, String entity, String queryJson);

    /**
     * The scheduled-context read: the same list DSL executed by the Data Runtime's
     * internal surface under the per-app system principal bound in
     * {@code principal} — never a user's relaid token, because a recordless firing
     * has none (PHASE-4 §7). The app apiName rides along: the internal surface
     * addresses entities within it.
     *
     * @return the query result as wire-shaped data ({@code {rows, total}})
     */
    Object systemQuery(TenantContext.Context principal, String app, String entity,
                       String queryJson);
}
