package com.novaforge.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * RLS fixtures for cross-tenant assertions (PHASE-1 §9 item 3): creates the canonical
 * RLS-guarded probe table and binds {@code app.tenant} exactly the way
 * {@code TenantRlsDataSource} does at runtime. Mandatory assertions per
 * ARCHITECTURE.md §5.3 live here so every suite tests the same invariants.
 */
public final class RlsFixtures {

    public static final String TENANT_VARIABLE = "app.tenant";

    private RlsFixtures() {
    }

    /** Creates the probe table + fail-closed RLS policy (idempotent). */
    public static void install(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS rls_probe (
                      id uuid PRIMARY KEY,
                      tenant_id uuid NOT NULL,
                      payload text NOT NULL)""");
            statement.execute("ALTER TABLE rls_probe ENABLE ROW LEVEL SECURITY");
            statement.execute("ALTER TABLE rls_probe FORCE ROW LEVEL SECURITY");
            // Fail closed: unset/empty app.tenant sees nothing (ADR-006 / PHASE-1 §6).
            statement.execute("""
                    DROP POLICY IF EXISTS tenant_isolation ON rls_probe""");
            statement.execute("""
                    CREATE POLICY tenant_isolation ON rls_probe USING (
                      current_setting('app.tenant', true) <> ''
                      AND tenant_id::text = current_setting('app.tenant', true))""");
            // The container's default user is the instance superuser, and RLS never
            // applies to superusers — even with FORCE. Assertions run as this
            // non-privileged role so the policy actually binds.
            statement.execute("DROP ROLE IF EXISTS novaforge_rls_probe");
            statement.execute("CREATE ROLE novaforge_rls_probe NOLOGIN NOBYPASSRLS");
            statement.execute("GRANT USAGE ON SCHEMA public TO novaforge_rls_probe");
            statement.execute("GRANT SELECT, INSERT ON rls_probe TO novaforge_rls_probe");
        }
    }

    public static void seedRow(Connection connection, UUID tenantId, String payload)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO rls_probe (id, tenant_id, payload) VALUES (?, ?, ?)")) {
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, tenantId);
            insert.setString(3, payload);
            insert.executeUpdate();
        }
    }

    public static void bindTenant(Connection connection, UUID tenantId) throws SQLException {
        try (Statement role = connection.createStatement()) {
            role.execute("SET ROLE novaforge_rls_probe");
        }
        try (PreparedStatement set = connection.prepareStatement(
                "SELECT set_config(?, ?, false)")) {
            set.setString(1, TENANT_VARIABLE);
            set.setString(2, tenantId == null ? "" : tenantId.toString());
            set.executeQuery();
        }
    }

    public static List<String> visiblePayloads(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT payload FROM rls_probe")) {
            List<String> payloads = new ArrayList<>();
            while (rs.next()) {
                payloads.add(rs.getString(1));
            }
            return payloads;
        }
    }
}
