package com.novaforge.runtime.authorization;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
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

    /** Fails closed with FORBIDDEN unless the actor holds a granting role. */
    public void require(UUID tenantId, UUID actorId, Action action, String entityApiName) {
        List<String> roles = platform.roles(tenantId, actorId);
        boolean granted = roles.stream().anyMatch(FULL_CRUD::contains);
        if (!granted) {
            throw new PlatformException(PlatformErrorCode.FORBIDDEN,
                    "actor " + actorId + " is not granted " + action + " on " + entityApiName
                            + " (roles: " + roles + ")");
        }
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
