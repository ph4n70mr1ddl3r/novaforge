package com.novaforge.runtime.engine.admin;

import java.util.UUID;

/**
 * Port: realm-side user provisioning (PHASE-2 §10 orchestrates Keycloak — deployed
 * configuration, not bespoke identity code). The api layer supplies the adapter.
 */
public interface UserProvisioner {

    /** Creates (idempotently) the realm user pinned to the tenant; returns its id. */
    UUID createUser(String username, String email, UUID tenantId);
}
