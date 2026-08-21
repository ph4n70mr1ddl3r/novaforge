package com.novaforge.security;

import com.novaforge.common.context.TenantContext;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * DataSource bridge for Postgres RLS (PHASE-1 §6, ADR-006): on every connection
 * checkout it sets {@code app.tenant} to the tenant bound on the calling thread, and on
 * close it resets the variable so pooled connections never leak a tenant across
 * checkouts. RLS policies ({@code tenant_id = current_setting('app.tenant')::uuid}) are
 * defense-in-depth behind the app-layer tenant scoping — ARCHITECTURE.md §5.3.
 *
 * <p>The session var is set with {@code set_config('app.tenant', ?, false)} — session
 * scope, not transaction-local — because reads may run outside explicit transactions;
 * the reset-on-close keeps pooling safe either way. When no tenant is bound the variable
 * is reset to the empty string, and RLS policies must treat an empty value as
 * no-rows-visible (fail closed): policies are created with
 * {@code current_setting('app.tenant', true) <> '' AND tenant_id = current_setting('app.tenant')::uuid}.
 */
public final class TenantRlsDataSource implements DataSource {

    public static final String TENANT_VARIABLE = "app.tenant";

    private final DataSource delegate;

    public TenantRlsDataSource(DataSource delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    public static String tenantVariable() {
        return TENANT_VARIABLE;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return decorate(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return decorate(delegate.getConnection(username, password));
    }

    private Connection decorate(Connection connection) throws SQLException {
        String tenantId = TenantContext.current().map(TenantContext.Context::tenantId).orElse("");
        try (PreparedStatement set = connection.prepareStatement(
                "SELECT set_config(?, ?, false)")) {
            set.setString(1, TENANT_VARIABLE);
            set.setString(2, tenantId);
            set.executeQuery();
        }
        // Reset right before the real close so the pool never hands out a tenant-tagged
        // connection to an unbound thread.
        return (Connection) Proxy.newProxyInstance(
                TenantRlsDataSource.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        try (PreparedStatement reset = connection.prepareStatement(
                                "SELECT set_config(?, '', false)")) {
                            reset.setString(1, TENANT_VARIABLE);
                            reset.executeQuery();
                        } catch (SQLException ignored) {
                            // Connection may already be broken; close still proceeds.
                        }
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    // --- plain delegation below ---

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }

    @Override
    public java.io.PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(java.io.PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }
}
