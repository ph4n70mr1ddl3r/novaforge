package com.novaforge.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.novaforge.common.context.TenantContext;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for propagation helpers (PHASE-1 §3). RLS semantics are covered by the
 * data-runtime integration suite on real Postgres. */
class SecurityContextTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("TenantTaskDecorator carries the submit-time binding into the worker thread")
    void taskDecoratorPropagates() throws Exception {
        TenantContext.set(new TenantContext.Context("tenant-1", "actor-1"));
        TenantTaskDecorator decorator = new TenantTaskDecorator();
        AtomicReference<String> seenInWorker = new AtomicReference<>();
        // decorate() while the context is bound — that is the submit-time capture
        Runnable decorated = decorator.decorate(() ->
                seenInWorker.set(TenantContext.require().tenantId()));
        TenantContext.clear();

        Thread worker = new Thread(decorated);
        worker.start();
        worker.join();

        assertThat(seenInWorker.get()).isEqualTo("tenant-1");
        assertThat(TenantContext.current()).isEmpty(); // worker thread ended unbound
    }

    @Test
    @DisplayName("TenantRlsDataSource exposes the pinned session variable name")
    void rlsVariableName() {
        assertThat(TenantRlsDataSource.tenantVariable()).isEqualTo("app.tenant");
        assertThat(EventHeaders.TENANT_ID).isEqualTo("X-Tenant-Id");
    }

    @Test
    @DisplayName("checkout sets app.tenant from the bound context; close delegates through")
    void proxyBindsAndCloses() throws Exception {
        List<String> executedSql = new ArrayList<>();
        int[] closes = {0};
        Connection stub = (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { Connection.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> recordingStatement((String) args[0], executedSql);
                    case "close" -> voidReturn(closes[0]++);
                    case "isClosed" -> Boolean.FALSE;
                    default -> defaultValueFor(method.getReturnType());
                });
        DataSource delegate = stubDataSource(stub);

        TenantContext.set(new TenantContext.Context("tenant-9", "actor-9"));
        try (Connection proxied = new TenantRlsDataSource(delegate).getConnection()) {
            assertThat(proxied.isClosed()).isFalse();
        } finally {
            TenantContext.clear();
        }

        assertThat(executedSql).anyMatch(sql ->
                sql.contains("set_config") && !sql.contains("''"));
        assertThat(closes[0]).isEqualTo(1);
    }

    @Test
    @DisplayName("unbound thread resets the variable to empty (fail closed)")
    void unboundResets() throws Exception {
        List<String> executedSql = new ArrayList<>();
        Connection stub = (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { Connection.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> recordingStatement((String) args[0], executedSql);
                    case "close" -> null;
                    default -> defaultValueFor(method.getReturnType());
                });

        try (Connection ignored = new TenantRlsDataSource(stubDataSource(stub)).getConnection()) {
            // checkout alone is the contract under test
        }
        assertThat(executedSql.getFirst()).contains("set_config");
    }

    private static java.sql.PreparedStatement recordingStatement(String sql, List<String> sink) {
        sink.add(sql);
        return (java.sql.PreparedStatement) Proxy.newProxyInstance(
                SecurityContextTest.class.getClassLoader(),
                new Class<?>[] { java.sql.PreparedStatement.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "executeQuery" -> emptyResultSet();
                    case "executeUpdate" -> 0;
                    case "setString", "setObject", "setNull", "setInt", "setLong" -> null;
                    case "close" -> null;
                    default -> defaultValueFor(method.getReturnType());
                });
    }

    private static java.sql.ResultSet emptyResultSet() {
        return (java.sql.ResultSet) Proxy.newProxyInstance(
                SecurityContextTest.class.getClassLoader(),
                new Class<?>[] { java.sql.ResultSet.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> Boolean.FALSE;
                    case "close" -> null;
                    default -> defaultValueFor(method.getReturnType());
                });
    }

    private static DataSource stubDataSource(Connection connection) {
        return (DataSource) Proxy.newProxyInstance(
                SecurityContextTest.class.getClassLoader(), new Class<?>[] { DataSource.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getConnection" -> connection;
                    case "getParentLogger" -> Logger.getGlobal();
                    default -> defaultValueFor(method.getReturnType());
                });
    }

    private static Object voidReturn(int ignored) {
        return null;
    }

    private static Object defaultValueFor(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        throw new AssertionError("unreachable");
    }
}
