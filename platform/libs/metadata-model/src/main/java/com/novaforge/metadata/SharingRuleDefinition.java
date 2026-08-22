package com.novaforge.metadata;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A record-level sharing rule (PHASE-4 §10, the PHASE-2 §9 remainder): versioned,
 * promoted metadata on the PermissionSet branch. {@code owner} shares the creator's
 * (or an explicit owner field's) records plus the named roles; {@code roleHierarchy}
 * lets a user see records owned by holders of less senior roles (numeric
 * {@code level}, lower = more senior — §16 Q2's single-level pin);
 * {@code criteria} shares records matching a compiled expression with the named
 * roles. With no rules for an entity, Phase 2's default holds — full visibility
 * under the object CRUD matrix, no silent tightening.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SharingRuleDefinition(
        String entity,
        String type,
        List<String> roles,
        String ownerField,
        String criteria) {

    public SharingRuleDefinition {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    public static final String OWNER = "owner";
    public static final String ROLE_HIERARCHY = "roleHierarchy";
    public static final String CRITERIA = "criteria";

    public static final java.util.Set<String> TYPES =
            java.util.Set.of(OWNER, ROLE_HIERARCHY, CRITERIA);
}
