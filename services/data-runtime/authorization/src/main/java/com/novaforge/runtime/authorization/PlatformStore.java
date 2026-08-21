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

    /** Roles held by {@code user} in {@code tenant}, read at request time (§7). */
    public List<String> roles(UUID tenantId, UUID userId) {
        return jdbc.queryForList(
                "SELECT role FROM platform.role_assignments WHERE tenant_id = ? AND user_id = ?",
                String.class, tenantId, userId);
    }

    public boolean tenantExists(UUID tenantId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM platform.tenants WHERE id = ?", Integer.class, tenantId);
        return count != null && count > 0;
    }
}
