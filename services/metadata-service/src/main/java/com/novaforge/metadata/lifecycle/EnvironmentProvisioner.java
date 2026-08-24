package com.novaforge.metadata.lifecycle;

import com.novaforge.metadata.AppDefinition;

/**
 * The environment provisioning port (PHASE-8 §2): a named environment is its own
 * tenant provisioned through the Phase 3 scratch mechanism (ADR-010's consequence —
 * one provisioning path, no second system), pinning a promoted published version.
 * The default HTTP implementation rides the platform's own admin + definition APIs;
 * tests substitute an in-memory twin. Provisioning is idempotent per
 * (app, environment): an existing environment tenant re-imports the new bundle.
 */
public interface EnvironmentProvisioner {

    /**
     * Provisions (or refreshes) the environment tenant for the bundle and returns
     * its coordinates — {@code tenantId} the isolated data plane,
     * {@code appId} the environment's copy of the app (published, version-pinned).
     */
    EnvironmentRef provision(AppDefinition bundle, String envName);

    record EnvironmentRef(java.util.UUID tenantId, java.util.UUID appId) {
    }
}
