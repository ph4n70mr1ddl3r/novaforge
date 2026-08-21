package com.novaforge.runtime.api;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.runtime.engine.admin.AdminService;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The platform-admin API (PHASE-2 §10, pinned): tenant provisioning and user→role
 * assignment over the platform-DB authorization data the runtime already reads at
 * request time (ADR-002's direction, ARCHITECTURE.md §2.2/§2.4). Gateway route
 * {@code /api/v1/admin/**}, platform-{@code admin}-gated; audited once the Phase 3
 * event spine lands (§9 defines the shapes).
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminService admin;

    public AdminController(AdminService admin) {
        this.admin = admin;
    }

    /** Tenant row + first-admin assignment in one flow (§10), orchestrating Keycloak. */
    @PostMapping("/tenants")
    public Map<String, Object> createTenant(@RequestBody CreateTenantRequest request) {
        requirePlatformAdmin();
        return admin.createTenant(request.apiName(), request.displayName(),
                request.adminUsername(), request.adminEmail());
    }

    /** Assign a platform or app-scoped ({@code app.role}) role to a user in a tenant. */
    @PostMapping("/tenants/{tenantId}/role-assignments")
    public Map<String, Object> assignRole(@PathVariable UUID tenantId,
                                          @RequestBody RoleAssignmentRequest request) {
        requirePlatformAdmin();
        return admin.assignRole(tenantId, request.userId(), request.role());
    }

    public record CreateTenantRequest(String apiName, String displayName,
                                      String adminUsername, String adminEmail) {
    }

    public record RoleAssignmentRequest(String userId, String role) {
    }

    /** Platform-admin gate: the fixed {@code admin} platform role from the token. */
    private static void requirePlatformAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuth
                && jwtAuth.getToken() instanceof Jwt jwt) {
            Object roles = jwt.getClaim("platform_roles");
            if (roles instanceof java.util.Collection<?> collection
                    && collection.contains("admin")) {
                return;
            }
        }
        throw new PlatformException(PlatformErrorCode.FORBIDDEN, "platform admin role required");
    }

}
