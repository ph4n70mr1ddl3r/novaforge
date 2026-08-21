package com.novaforge.testsupport;

import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Shared Redis Testcontainers base (PHASE-1 §3/T2): one container per JVM, pinned to the
 * compose stack image. Used by the publish-event (Redis pub/sub) integration suites.
 */
public abstract class RedisTestBase {

    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            "docker.io/library/redis:7.4.11")
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    @BeforeAll
    static void startRedis() {
        if (!REDIS.isRunning()) {
            REDIS.start();
        }
    }

    protected static String redisHost() {
        return REDIS.getHost();
    }

    protected static int redisPort() {
        return REDIS.getMappedPort(6379);
    }
}
