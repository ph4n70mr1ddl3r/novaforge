package com.novaforge.runtime.authorization;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.PermissionSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Object-level authorization (PHASE-1 §7): role × entity → CRUD allow matrix read from
 * the platform DB at request time. The bootstrap policy fails closed (§12 Q1, decided):
 * {@code admin}/{@code builder} hold full CRUD on app entities; {@code user} is denied
 * until Phase 2's role editors make grants authorable (PermissionSet metadata). Phase 2
 * tightens policy here — the write path never changes.
 */
@Component
public class RoleMatrix {

    public enum Action { CREATE, READ, UPDATE, DELETE }

    private static final Set<String> FULL_CRUD = Set.of("admin", "builder");

    private final PlatformStore platform;

    public RoleMatrix(PlatformStore platform) {
        this.platform = platform;
    }

    /**
     * Fails closed with FORBIDDEN unless the actor holds a granting role. Platform
     * {@code admin}/{@code builder} keep full CRUD (the Phase 1 bootstrap); everyone
     * else is decided by the app's PermissionSet matrix (PHASE-2 §9 tightens the
     * fail-closed default: {@code user} earns grants only through app roles).
     */
    public void require(UUID tenantId, UUID actorId, Action action, String entityApiName,
                        String appApiName, PermissionSet permissionSet) {
        List<String> roles = platform.roles(tenantId, actorId);
        if (roles.stream().anyMatch(FULL_CRUD::contains)) {
            return;
        }
        boolean granted = permissionSet.objectPermissions().stream()
                .filter(p -> p.entity().equals(entityApiName))
                .filter(p -> heldInApp(roles, appApiName, p.role()))
                .anyMatch(p -> p.allows(action.name().toLowerCase()));
        if (!granted) {
            throw new PlatformException(PlatformErrorCode.FORBIDDEN,
                    "actor " + actorId + " is not granted " + action + " on " + entityApiName
                            + " (roles: " + roles + ")");
        }
    }

    /** An app role applies when the actor holds it scoped to this app ({@code app.role}). */
    private static boolean heldInApp(List<String> roles, String appApiName, String role) {
        return roles.contains(appApiName + "." + role);
    }

    /**
     * Effective field access under the actor's app roles (PHASE-2 §9): hidden wins over
     * readonly, readonly over visible; fields without an entry are visible.
     */
    public String fieldAccess(UUID tenantId, UUID actorId, String appApiName,
                              PermissionSet permissionSet, String entityApiName, String field) {
        List<String> roles = platform.roles(tenantId, actorId);
        if (roles.stream().anyMatch(FULL_CRUD::contains)) {
            return "visible";
        }
        String access = "visible";
        for (PermissionSet.FieldSecurity security : permissionSet.fieldSecurity()) {
            if (!security.entity().equals(entityApiName) || !security.field().equals(field)) {
                continue;
            }
            if (!heldInApp(roles, appApiName, security.role())) {
                continue;
            }
            if (PermissionSet.FieldSecurity.HIDDEN.equals(security.access())) {
                return PermissionSet.FieldSecurity.HIDDEN;
            }
            access = PermissionSet.FieldSecurity.READONLY;
        }
        return access;
    }

    /** Admin-only surfaces — e.g. {@code includeDeleted} reads (PHASE-1 §5). */
    public void requireAdmin(UUID tenantId, UUID actorId) {
        List<String> roles = platform.roles(tenantId, actorId);
        if (!roles.contains("admin")) {
            throw new PlatformException(PlatformErrorCode.FORBIDDEN,
                    "actor " + actorId + " is not an admin (roles: " + roles + ")");
        }
    }
}
