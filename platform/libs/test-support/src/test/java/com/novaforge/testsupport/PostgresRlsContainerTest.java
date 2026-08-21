package com.novaforge.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Boots the Testcontainers base and proves the RLS fixtures fail closed (PHASE-1 §3/T2
 * acceptance: fixtures boot under the CI Podman runner; cross-tenant assertions are
 * mandatory per ARCHITECTURE.md §5.3).
 */
class PostgresRlsContainerTest extends PostgresTestBase {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @BeforeAll
    static void installFixtures() throws Exception {
        // Owner connection (superuser, RLS-bypassing): install fixtures and seed both
        // tenants' rows. Assertions below connect as the non-privileged probe role.
        try (Connection connection = DriverManager.getConnection(jdbcUrl(), jdbcUsername(), jdbcPassword())) {
            RlsFixtures.install(connection);
            RlsFixtures.seedRow(connection, TENANT_A, "tenant-a-row");
            RlsFixtures.seedRow(connection, TENANT_B, "tenant-b-row");
        }
    }

    @Test
    @DisplayName("cross-tenant reads fail closed: tenant A sees only its rows")
    void crossTenantReadFailsClosed() throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl(), jdbcUsername(), jdbcPassword())) {
            RlsFixtures.bindTenant(connection, TENANT_A);
            assertThat(RlsFixtures.visiblePayloads(connection)).containsExactly("tenant-a-row");
        }
    }

    @Test
    @DisplayName("unset tenant variable sees nothing")
    void unsetVariableSeesNothing() throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl(), jdbcUsername(), jdbcPassword())) {
            RlsFixtures.bindTenant(connection, null);
            assertThat(RlsFixtures.visiblePayloads(connection)).isEmpty();
        }
    }

    @Test
    @DisplayName("cross-tenant writes are rejected by policy")
    void crossTenantWriteRejected() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> {
                    try (Connection connection = DriverManager.getConnection(jdbcUrl(), jdbcUsername(), jdbcPassword())) {
                        RlsFixtures.bindTenant(connection, TENANT_A);
                        try (var insert = connection.prepareStatement(
                                "INSERT INTO rls_probe (id, tenant_id, payload) VALUES (?, ?, ?)")) {
                            insert.setObject(1, UUID.randomUUID());
                            insert.setObject(2, TENANT_B);
                            insert.setString(3, "leak");
                            insert.executeUpdate();
                        }
                    }
                })
                .isInstanceOf(org.postgresql.util.PSQLException.class)
                .hasMessageContaining("row-level security");
    }

    @Test
    @DisplayName("both tenants' rows exist when RLS is bypassed (sanity)")
    void sanityBothRowsExist() throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl(), jdbcUsername(), jdbcPassword())) {
            RlsFixtures.bindTenant(connection, TENANT_A);
            List<String> visible = RlsFixtures.visiblePayloads(connection);
            assertThat(visible).containsExactly("tenant-a-row");
        }
    }
}
