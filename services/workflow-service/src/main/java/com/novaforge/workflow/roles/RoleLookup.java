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

    /** Whether any user holds the role in the tenant — the escalation-target fence
     *  (a role nobody holds produces an OPEN task no inbox ever matches). */
    default boolean roleHeld(UUID tenantId, String role) {
        return !holdersOf(tenantId, role).isEmpty();
    }

    /** The role's holders (the admin surface's by-role listing). */
    default List<UUID> holdersOf(UUID tenantId, String role) {
        return List.of();
    }
}
