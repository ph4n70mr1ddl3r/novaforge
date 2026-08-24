package com.novaforge.runtime.authorization;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The platform authorization store reader (PHASE-1 §6/§7): tenants, users, role
 * assignments in the platform schema of {@code novaforge-data} — cross-tenant by
 * design, gated by this matrix, never by row filters. PHASE-2 §10's platform-admin API
 * writes the same tables.
 */
@Component
public class PlatformStore {

    private final JdbcTemplate jdbc;

    public PlatformStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Roles held by {@code user} in {@code tenant}, read at request time (§7/PHASE-2 §9):
     * platform roles plus app-scoped roles ({@code app.role}) from the same table. */
    public List<String> roles(UUID tenantId, UUID userId) {
        return jdbc.queryForList(
                "SELECT role FROM platform.role_assignments WHERE tenant_id = ? AND user_id = ?",
                String.class, tenantId, userId);
    }

    /** The user's username — synthetic-actor detection (PHASE-4 §8, ADR-010 #3). */
    public String usernameOf(UUID userId) {
        return jdbc.queryForObject(
                "SELECT username FROM platform.users WHERE id = ?", String.class, userId);
    }

    /** Holders of {@code role} in {@code tenant} — the notification fan-out (PHASE-4 §8). */
    public List<UUID> usersOfRole(UUID tenantId, String role) {
        return jdbc.queryForList(
                "SELECT user_id FROM platform.role_assignments WHERE tenant_id = ? AND role = ?",
                UUID.class, tenantId, role);
    }

    public UUID createUser(UUID userId, String username) {
        jdbc.update("INSERT INTO platform.users (id, username) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                userId, username);
        return userId;
    }

    public UUID createTenant(UUID tenantId, String apiName, String displayName) {
        jdbc.update("INSERT INTO platform.tenants (id, api_name, display_name) VALUES (?, ?, ?)",
                tenantId, apiName, displayName);
        return tenantId;
    }

    public void assignRole(UUID tenantId, UUID userId, String role) {
        jdbc.update("""
                INSERT INTO platform.role_assignments (tenant_id, user_id, role) VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING""", tenantId, userId, role);
    }

    /** The tenant's {@code apiName} — the scratch gate's lookup (PHASE-4 §12), or null. */
    public String apiNameOf(UUID tenantId) {
        return jdbc.queryForList(
                "SELECT api_name FROM platform.tenants WHERE id = ?", String.class, tenantId)
                .stream().findFirst().orElse(null);
    }

    public boolean tenantExists(UUID tenantId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM platform.tenants WHERE id = ?", Integer.class, tenantId);
        return count != null && count > 0;
    }
}
