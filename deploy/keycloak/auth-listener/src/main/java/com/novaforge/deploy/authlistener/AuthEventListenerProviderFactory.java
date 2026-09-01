package com.novaforge.deploy.authlistener;

import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/**
 * Registers {@code novaforge-auth} as a realm event listener (PHASE-3 §5): enabled
 * per realm by the realm export's {@code eventsListeners} — deployed configuration,
 * nothing bespoke in the services. The producer is created once per factory
 * (Keycloak's own lifecycle) against the compose in-network Kafka listener
 * ({@code kafka:29092}); override with the {@code NOVAFORGE_KAFKA_BOOTSTRAP} env.
 */
public class AuthEventListenerProviderFactory implements EventListenerProviderFactory {

    static final String ID = "novaforge-auth";

    private KafkaProducer<String, String> producer;

    @Override
    public void init(Config.Scope config) {
        this.producer = new KafkaProducer<>(producerProperties(bootstrap()));
    }

    static String bootstrap() {
        return System.getenv().getOrDefault("NOVAFORGE_KAFKA_BOOTSTRAP", "kafka:29092");
    }

    /**
     * The producer config. Kafka rejects a producer whose
     * {@code delivery.timeout.ms < linger.ms + request.timeout.ms} at
     * CONSTRUCTION time — and a constructor throwing here fails Keycloak's
     * whole boot, not just the audit trail. The first live deployment
     * (2026-09-02, twenty-ninth pass) died exactly this way: linger 1s + the
     * default request timeout 30s exceeded the 30s delivery bound.
     * request.timeout.ms is pinned explicitly so the invariant holds
     * arithmetically.
     */
    static Properties producerProperties(String bootstrap) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringSerializer");
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringSerializer");
        props.put(ProducerConfig.LINGER_MS_CONFIG, String.valueOf(TimeUnit.SECONDS.toMillis(1)));
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, String.valueOf(TimeUnit.SECONDS.toMillis(10)));
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, String.valueOf(TimeUnit.SECONDS.toMillis(30)));
        return props;
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // no realm-side wiring — the realm export opts in per realm
    }

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new AuthEventListenerProvider(session, producer);
    }

    @Override
    public void close() {
        if (producer != null) {
            producer.flush();
            producer.close();
        }
    }

    @Override
    public String getId() {
        return ID;
    }
}
