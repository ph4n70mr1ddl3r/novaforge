package com.novaforge.script.engine;

import com.novaforge.common.context.TenantContext;

/**
 * Host-side query execution: the api layer binds this to the Data Runtime's query
 * API. Every call carries the <em>calling</em> user's context (ARCHITECTURE.md §5
 * item 4) — the binding is what keeps a script inside its authorizing user's grants.
 */
public interface QueryProxy {

    /**
     * Runs {@code queryJson} ({@code {filter, sort, page}} — the query DSL's list
     * shape) against {@code entity} as {@code caller}.
     *
     * @return the query result as wire-shaped data ({@code {rows, total}})
     */
    Object query(TenantContext.Context caller, String entity, String queryJson);
}
