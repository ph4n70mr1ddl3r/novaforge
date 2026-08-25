package com.novaforge.runtime.engine.admin;

import com.novaforge.common.context.TenantContext;
import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.runtime.authorization.PlatformStore;
import com.novaforge.runtime.storage.outbox.OutboxStore;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final OutboxStore outbox;
    private final io.micrometer.tracing.Tracer tracer;

    public AdminService(PlatformStore platform, UserProvisioner users, OutboxStore outbox,
                        io.micrometer.tracing.Tracer tracer) {
        this.platform = platform;
        this.users = users;
        this.outbox = outbox;
        this.tracer = tracer;
    }

    /** The user's username — synthetic-actor detection (PHASE-4 §8). */
    public String usernameOf(UUID userId) {
        return platform.usernameOf(userId);
    }

    /** Holders of a role in a tenant — the Notification fan-out (PHASE-4 §8). */
    public java.util.List<UUID> usersOfRole(UUID tenantId, String role) {
        return platform.usersOfRole(tenantId, role);
    }

    /** The user's roles in a tenant — platform set + app-scoped ({@code app.role}). */
    public java.util.List<String> rolesOf(UUID tenantId, UUID userId) {
        return platform.roles(tenantId, userId);
    }

    /** The tenant row — the workflow service's scratch gate reads the name (PHASE-4 §12). */
    public Map<String, Object> tenant(UUID tenantId) {
        String apiName = platform.apiNameOf(tenantId);
        if (apiName == null) {
            throw new PlatformException(PlatformErrorCode.NOT_FOUND, "tenant " + tenantId + " not found");
        }
        return Map.of("tenantId", tenantId.toString(), "apiName", apiName);
    }

    /** Tenant row + first-admin assignment in one flow (§10). */
    public Map<String, Object> createTenant(String apiName, String displayName,
                                            String adminUsername, String adminEmail) {
        return createTenant(apiName, displayName, adminUsername, adminEmail, null);
    }

    @Transactional
    public Map<String, Object> createTenant(String apiName, String displayName,
                                            String adminUsername, String adminEmail,
                                            String adminPassword) {
        if (apiName == null || apiName.isBlank()
                || adminUsername == null || adminUsername.isBlank()) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "apiName and adminUsername are required");
        }
        UUID tenantId = UUID.randomUUID();
        platform.createTenant(tenantId, apiName, displayName);
        UUID adminId = users.createUser(adminUsername, adminEmail, tenantId, adminPassword,
                java.util.List.of("admin", "builder", "user"));
        platform.createUser(adminId, adminUsername);
        platform.assignRole(tenantId, adminId, "admin");
        permissionEvent("permission.tenant.provisioned", tenantId, tenantId, adminId,
                Map.of("apiName", apiName, "adminUserId", adminId.toString()));
        return Map.of("tenantId", tenantId.toString(), "adminUserId", adminId.toString());
    }

    /** Scratch-tenant actor provisioning (ADR-010's synthetic actors). */
    @Transactional
    public Map<String, Object> createUser(UUID tenantId, String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "username and password are required");
        }
        if (!platform.tenantExists(tenantId)) {
            throw new PlatformException(PlatformErrorCode.NOT_FOUND, "tenant " + tenantId + " not found");
        }
        UUID userId = users.createUser(username, username + "@scratch.novaforge.local",
                tenantId, password, java.util.List.of());
        platform.createUser(userId, username);
        permissionEvent("permission.user.provisioned", tenantId, userId, actingActor(),
                Map.of("username", username));
        return Map.of("userId", userId.toString());
    }

    /** Assigns a platform or app-scoped ({@code app.role}) role. */
    @Transactional
    public Map<String, Object> assignRole(UUID tenantId, String userId, String role) {
        if (userId == null || role == null || role.isBlank()) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "userId and role are required");
        }
        if (!platform.tenantExists(tenantId)) {
            throw new PlatformException(PlatformErrorCode.NOT_FOUND, "tenant " + tenantId + " not found");
        }
        UUID parsed = UUID.fromString(userId);
        platform.assignRole(tenantId, parsed, role);
        permissionEvent("permission.role.assigned", tenantId, parsed, actingActor(),
                Map.of("role", role));
        return Map.of("status", "ok");
    }

    /**
     * The permission-family outbox append (PHASE-3 §4): same transaction as the
     * platform write, tenant-scoped key, the acting admin (or the trusted service
     * client for platform-internal provisioning) as the audited actor. The relay
     * derives the family topic from the event type ({@code permission.*} →
     * {@code novaforge.permission}).
     */
    private void permissionEvent(String event, UUID tenantId, UUID subjectUserId,
                                 UUID actorId, Map<String, Object> detail) {
        UUID actor = actorId == null
                ? UUID.nameUUIDFromBytes("system:platform".getBytes()) : actorId;
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("event", event);
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("tenantId", tenantId.toString());
        payload.put("userId", subjectUserId.toString());
        payload.put("actorId", actor.toString());
        payload.put("occurredAt", Instant.now().toString());
        payload.putAll(detail);
        String traceparent = com.novaforge.security.TracePropagation.capture(tracer);
        if (traceparent != null) {
            payload.put("traceparent", traceparent);
        }
        outbox.append(UUID.randomUUID(), tenantId, "platform.permission", subjectUserId,
                event, payload);
    }

    /**
     * The acting admin from the request context — platform-internal callers (the
     * trusted service client, ADR-010) carry no user identity and audit as the
     * platform system principal instead.
     */
    private static UUID actingActor() {
        return TenantContext.current().map(ctx -> {
            try {
                return UUID.fromString(ctx.actorId());
            } catch (IllegalArgumentException notAUserId) {
                return null;   // a client-credentials principal — the system fallback
            }
        }).orElse(null);
    }
}
