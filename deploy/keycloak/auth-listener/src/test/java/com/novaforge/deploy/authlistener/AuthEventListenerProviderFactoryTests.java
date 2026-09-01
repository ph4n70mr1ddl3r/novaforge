package com.novaforge.deploy.authlistener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Properties;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The factory's producer config was never live-testable until the first real
 * deployment (2026-09-02, twenty-ninth pass) — and that deployment failed
 * Keycloak's whole BOOT: Kafka rejects a producer whose delivery timeout is
 * smaller than linger + request timeout, at construction time, inside
 * {@code init(...)}. The config is now built by a testable function and pinned
 * here twice: the arithmetic invariant, and a real {@code KafkaProducer}
 * construction (no broker needed — the constructor validates config only).
 */
class AuthEventListenerProviderFactoryTests {

    @Test
    @DisplayName("the producer config satisfies delivery.timeout >= linger + request.timeout")
    void timeoutArithmeticHolds() {
        Properties props = AuthEventListenerProviderFactory.producerProperties("kafka:29092");
        long linger = Long.parseLong(props.getProperty(ProducerConfig.LINGER_MS_CONFIG));
        long request = Long.parseLong(props.getProperty(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG));
        long delivery = Long.parseLong(props.getProperty(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG));
        assertThat(delivery).isGreaterThanOrEqualTo(linger + request);
        // the bounded-delivery contract stands: a spine hiccup never wedges login
        assertThat(delivery).isLessThanOrEqualTo(60_000);
    }

    @Test
    @DisplayName("the configured producer actually CONSTRUCTS (the boot-killer regression, pinned)")
    void producerConstructs() {
        Properties props = AuthEventListenerProviderFactory.producerProperties("127.0.0.1:1");
        KafkaProducer<String, String> producer = new KafkaProducer<>(props);
        producer.close(java.time.Duration.ZERO);
    }

    @Test
    @DisplayName("bite-proof: the pre-fix shape (linger 1s, default request timeout, 30s delivery) is INVALID")
    void preFixShapeIsInvalid() {
        Properties broken = new Properties();
        broken.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1");
        broken.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringSerializer");
        broken.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringSerializer");
        broken.put(ProducerConfig.LINGER_MS_CONFIG, "1000");
        // request.timeout.ms left at its 30s default — delivery 30s < linger 1s + request 30s
        broken.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "30000");
        assertThatThrownBy(() -> new KafkaProducer<String, String>(broken))
                .isInstanceOf(org.apache.kafka.common.KafkaException.class)
                .hasMessageContaining("Failed to construct kafka producer")
                .cause()
                .hasMessageContaining("delivery.timeout.ms");
    }
}
