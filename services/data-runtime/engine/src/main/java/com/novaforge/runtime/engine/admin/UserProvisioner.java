package com.novaforge.runtime.engine.admin;

import java.util.UUID;

/**
 * Port: realm-side user provisioning (PHASE-2 §10 orchestrates Keycloak — deployed
 * configuration, not bespoke identity code). The api layer supplies the adapter.
 */
public interface UserProvisioner {

    /** Creates (idempotently) the realm user pinned to the tenant; returns its id. */
    UUID createUser(String username, String email, UUID tenantId);

    /** Password variant — synthetic actors and scratch admins (never returned to clients). */
    default UUID createUser(String username, String email, UUID tenantId, String password) {
        return createUser(username, email, tenantId);
    }

    /** Password + platform-roles variant (the roles ride the token claim). */
    default UUID createUser(String username, String email, UUID tenantId, String password,
                            java.util.List<String> platformRoles) {
        return createUser(username, email, tenantId, password);
    }
}
