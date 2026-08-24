package com.novaforge.script.engine;

import com.novaforge.common.context.TenantContext;
import java.util.Map;

/**
 * Host-side connector calls (PHASE-6 §4): the api layer binds this to the
 * Integration Service's internal execution surface — the same circuit-breaker,
 * credential, and timeout machinery {@code callConnector} rides. Scripts never
 * touch raw sockets ({@code IOAccess.NONE} holds); inside the connector sandbox
 * {@code $http} is the only egress, and it is this proxy.
 */
public interface HttpProxy {

    /**
     * Executes one connector operation as the tenant/app of the executing script.
     *
     * @return the provider response as wire-shaped data ({@code {status, body}})
     */
    Object call(TenantContext.Context caller, String app, String connector,
                String operation, Map<String, Object> template);
}
