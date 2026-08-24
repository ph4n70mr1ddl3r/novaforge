package com.novaforge.workflow.tenants;

import java.util.UUID;

/**
 * The tenant-name lookup the scratch SLA surface gates on (PHASE-4 §12): the
 * harness's runs live in fresh {@code scratch-*} tenants (ADR-010's provisioning),
 * and the as-of scan answers only there — production tenants keep wall-clock scans
 * exclusively, so the surface can never advance time on real data.
 */
public interface TenantLookup {

    /** The tenant's {@code apiName}, or null when unknown. */
    String apiNameOf(UUID tenantId);

    /** Scratch tenants are the harness's — named {@code scratch-<runId>} at provisioning. */
    default boolean isScratch(UUID tenantId) {
        String name = apiNameOf(tenantId);
        return name != null && name.startsWith("scratch-");
    }
}
