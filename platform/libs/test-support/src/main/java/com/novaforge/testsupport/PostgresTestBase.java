package com.novaforge.testsupport;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Postgres Testcontainers base (PHASE-1 §3/T2): one container per JVM (singleton
 * container pattern), pinned to the same image as the compose stack.
 *
 * <p>Podman rootless: export {@code DOCKER_HOST=unix:///run/user/<uid>/podman/podman.sock}
 * and {@code TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/run/user/<uid>/podman/podman.sock}
 * (documented in the README per PHASE-0 §10).
 */
public abstract class PostgresTestBase {

    private static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse("docker.io/library/postgres:16.15")
                    .asCompatibleSubstituteFor("postgres");

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("novaforge_test")
            .withUsername("novaforge")
            .withPassword("novaforge");

    @BeforeAll
    static void startPostgres() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
    }

    @AfterAll
    static void keepContainer() {
        // Singleton container: reused across test classes in this JVM; Ryuk reaps it on JVM exit.
    }

    protected static PostgreSQLContainer<?> postgres() {
        return POSTGRES;
    }

    protected static String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    protected static String jdbcUsername() {
        return POSTGRES.getUsername();
    }

    protected static String jdbcPassword() {
        return POSTGRES.getPassword();
    }
}
