package com.novaforge.workflow.roles;

import java.util.List;
import java.util.UUID;

/**
 * Role resolution for the inbox (PHASE-4 §5/§13): the Workflow Service reads the
 * platform authorization data through the Data Runtime's admin surface — the platform
 * DB is the runtime's to own (ADR-002), no cross-service database reads.
 */
public interface RoleLookup {

    /** The actor's roles in the tenant — platform set + app-scoped ({@code app.role}). */
    List<String> of(UUID tenantId, UUID actor);
}
