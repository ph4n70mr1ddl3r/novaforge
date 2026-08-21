package com.novaforge.runtime.engine.admin;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.runtime.authorization.PlatformStore;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Tenant provisioning + user→role assignment (PHASE-2 §10, pinned): the Data Runtime
 * owns the platform-DB authorization data (ADR-002) and already reads it at request
 * time — the admin API writes the same rows, orchestrating the realm through the
 * {@link UserProvisioner} port.
 */
@Service
public class AdminService {

    private final PlatformStore platform;
    private final UserProvisioner users;

    public AdminService(PlatformStore platform, UserProvisioner users) {
        this.platform = platform;
        this.users = users;
    }

    /** Tenant row + first-admin assignment in one flow (§10). */
    public Map<String, Object> createTenant(String apiName, String displayName,
                                            String adminUsername, String adminEmail) {
        if (apiName == null || apiName.isBlank()
                || adminUsername == null || adminUsername.isBlank()) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "apiName and adminUsername are required");
        }
        UUID tenantId = UUID.randomUUID();
        platform.createTenant(tenantId, apiName, displayName);
        UUID adminId = users.createUser(adminUsername, adminEmail, tenantId);
        platform.createUser(adminId, adminUsername);
        platform.assignRole(tenantId, adminId, "admin");
        return Map.of("tenantId", tenantId.toString(), "adminUserId", adminId.toString());
    }

    /** Assigns a platform or app-scoped ({@code app.role}) role. */
    public Map<String, Object> assignRole(UUID tenantId, String userId, String role) {
        if (userId == null || role == null || role.isBlank()) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "userId and role are required");
        }
        if (!platform.tenantExists(tenantId)) {
            throw new PlatformException(PlatformErrorCode.NOT_FOUND, "tenant " + tenantId + " not found");
        }
        platform.assignRole(tenantId, UUID.fromString(userId), role);
        return Map.of("status", "ok");
    }
}
