package com.novaforge.metadata;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Permissions branch of PLAN.md §2 (versioned, promoted with the app —
 * PHASE-2 §9): app-defined roles, the object permission matrix (role × entity →
 * CRUD), and per-role field security (visible / readonly / hidden). User→role
 * assignments are tenant data in the platform DB, never promoted metadata.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PermissionSet(
        List<RoleDefinition> roles,
        List<ObjectPermission> objectPermissions,
        List<FieldSecurity> fieldSecurity) {

    public PermissionSet {
        roles = roles == null ? List.of() : List.copyOf(roles);
        objectPermissions = objectPermissions == null ? List.of() : List.copyOf(objectPermissions);
        fieldSecurity = fieldSecurity == null ? List.of() : List.copyOf(fieldSecurity);
    }

    public Optional<RoleDefinition> role(String name) {
        return roles.stream().filter(r -> r.name().equals(name)).findFirst();
    }

    /** App-defined role; assignments scope it as {@code app.role}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RoleDefinition(String name, String description) {
    }

    /** role × entity → CRUD flags (absent flags deny). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ObjectPermission(String role, String entity,
                                   Boolean create, Boolean read, Boolean update, Boolean delete) {

        public boolean allows(String action) {
            return switch (action) {
                case "create" -> Boolean.TRUE.equals(create);
                case "read" -> Boolean.TRUE.equals(read);
                case "update" -> Boolean.TRUE.equals(update);
                case "delete" -> Boolean.TRUE.equals(delete);
                default -> false;
            };
        }
    }

    /** Field visibility per role: visible (default) / readonly / hidden. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldSecurity(String role, String entity, String field, String access) {

        public static final String READONLY = "readonly";
        public static final String HIDDEN = "hidden";
    }
}
