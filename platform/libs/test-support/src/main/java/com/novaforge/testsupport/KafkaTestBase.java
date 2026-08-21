package com.novaforge.testsupport;

import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Shared Kafka Testcontainers base (PHASE-3 §4): one broker per JVM, pinned to the
 * compose stack's distribution (KRaft, apache/kafka image).
 */
public abstract class KafkaTestBase {

    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    @BeforeAll
    static void startKafka() {
        if (!KAFKA.isRunning()) {
            KAFKA.start();
        }
    }

    protected static String bootstrapServers() {
        return KAFKA.getBootstrapServers();
    }
}
