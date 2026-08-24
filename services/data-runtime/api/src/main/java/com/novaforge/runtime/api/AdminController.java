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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The platform-admin API (PHASE-2 §10, pinned): tenant provisioning and user→role
 * assignment over the platform-DB authorization data the runtime already reads at
 * request time (ADR-002's direction, ARCHITECTURE.md §2.2/§2.4). Gateway route
 * {@code /api/v1/admin/**}, platform-{@code admin}-gated; every permission-changing
 * write emits a {@code permission.*} event on the spine (ARCHITECTURE.md §5 item 5).
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
                request.adminUsername(), request.adminEmail(), request.adminPassword());
    }

    /** Synthetic-actor provisioning in a tenant (scratch tenants, ADR-010). */
    @PostMapping("/tenants/{tenantId}/users")
    public Map<String, Object> createUser(@PathVariable UUID tenantId,
                                          @RequestBody CreateUserRequest request) {
        requirePlatformAdmin();
        return admin.createUser(tenantId, request.username(), request.password());
    }

    /** The tenant row — the Workflow service's scratch gate (PHASE-4 §12). */
    @GetMapping("/tenants/{tenantId}")
    public Map<String, Object> tenant(@PathVariable UUID tenantId) {
        requirePlatformAdmin();
        return admin.tenant(tenantId);
    }

    /** The user's username — the Notification fan-out's synthetic-actor check. */
    @GetMapping("/users/{userId}")
    public Map<String, Object> user(@PathVariable UUID userId) {
        requirePlatformAdmin();
        return Map.of("userId", userId.toString(), "username", admin.usernameOf(userId));
    }

    /** Holders of a role in a tenant — the Notification fan-out (PHASE-4 §8). */
    @GetMapping("/tenants/{tenantId}/roles/{role}/users")
    public java.util.List<String> usersOfRole(@PathVariable UUID tenantId,
                                              @PathVariable String role) {
        requirePlatformAdmin();
        return admin.usersOfRole(tenantId, role).stream().map(UUID::toString).toList();
    }

    /** The user's roles in a tenant (platform + app-scoped) — the Workflow inbox's
     *  "my tasks" resolution and access checks read here (PHASE-4 §5/§13). */
    @GetMapping("/tenants/{tenantId}/users/{userId}/roles")
    public java.util.List<String> rolesOf(@PathVariable UUID tenantId, @PathVariable UUID userId) {
        requirePlatformAdmin();
        return admin.rolesOf(tenantId, userId);
    }

    /** Assign a platform or app-scoped ({@code app.role}) role to a user in a tenant. */
    @PostMapping("/tenants/{tenantId}/role-assignments")
    public Map<String, Object> assignRole(@PathVariable UUID tenantId,
                                          @RequestBody RoleAssignmentRequest request) {
        requirePlatformAdmin();
        return admin.assignRole(tenantId, request.userId(), request.role());
    }

    public record CreateTenantRequest(String apiName, String displayName,
                                      String adminUsername, String adminEmail,
                                      String adminPassword) {
    }

    public record CreateUserRequest(String username, String password) {
    }

    public record RoleAssignmentRequest(String userId, String role) {
    }

    /**
     * Platform-admin gate: the fixed {@code admin} platform role from the token, or the
     * trusted platform service client (client-credentials callers have no platform_roles
     * claim — the metadata test runner and other platform services act here, ADR-010).
     */
    private static void requirePlatformAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuth
                && jwtAuth.getToken() instanceof Jwt jwt) {
            Object roles = jwt.getClaim("platform_roles");
            if (roles instanceof java.util.Collection<?> collection
                    && collection.contains("admin")) {
                return;
            }
            String azp = jwt.getClaimAsString("azp");
            String clientId = jwt.getClaimAsString("client_id");
            if ("novaforge-runtime".equals(azp) || "novaforge-runtime".equals(clientId)) {
                return;
            }
            org.slf4j.LoggerFactory.getLogger(AdminController.class)
                    .warn("admin gate rejected token: azp={}, client_id={}", azp, clientId);
        }
        throw new PlatformException(PlatformErrorCode.FORBIDDEN, "platform admin role required");
    }

}
