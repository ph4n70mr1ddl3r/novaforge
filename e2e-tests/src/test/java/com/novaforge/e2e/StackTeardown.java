package com.novaforge.e2e;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * The stack's teardown rides the launcher session's close, not the JVM's shutdown
 * hooks: the surefire fork halts past its hooks — two green verify runs leaked the
 * integration-service jar (the one service that survives infrastructure loss, its
 * Kafka clients retrying forever while Postgres/Kafka die with the containers), and
 * the next run's port preflight bricked all five cycle tests on the held 8090. The
 * session's close runs while this JVM is still alive and owns its children, so the
 * destroy → 15 s grace → SIGKILL ladder actually executes (and stays idempotent
 * against the belt-and-braces JVM hook in {@link NovaForgeStack#start()}).
 *
 * <p>Registered by name in {@code META-INF/services/org.junit.platform.launcher.LauncherSessionListener}
 * — the JUnit Platform picks it up from the test classpath through the ServiceLoader
 * when surefire launches the session.
 */
public class StackTeardown implements LauncherSessionListener {

    @Override
    public void launcherSessionClosed(LauncherSession session) {
        NovaForgeStack.shutdownSpawnedServices();
    }
}
