package com.novaforge.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.novaforge.testsupport.PostgresTestBase;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The dead-letter leg of ConsumerErrorConfig (the nineteenth pass's recorded gap): the
 * redelivery-with-backoff pin proved the budget runs, but exhausting the PRODUCTION
 * budget (1 s doubling to 60 s, ten attempts — ~5 minutes of cumulative backoff) was
 * never observed, so the {@code <topic>.DLT.novaforge-notification} publish was
 * verified only by mirror-construction. The budget is property-tunable now, and this
 * context shrinks it to 10 ms/10 ms/three attempts so the exhaustion — and the
 * recoverer's publish of the ORIGINAL payload — is watchable end to end against the
 * real broker.
 */
@SpringBootTest(properties = {"novaforge.kafka.consumer-retry.initial-ms=10",
        "novaforge.kafka.consumer-retry.max-interval-ms=10",
        "novaforge.kafka.consumer-retry.max-attempts=3",
        // quiet the outbox relay — nothing here rides it (NotificationTests' convention)
        "novaforge.events.relay-interval-ms=3600000"})
class NotificationDeadLetterTests extends PostgresTestBase {

    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    static {
        KAFKA.start();
    }

    @Autowired
    KafkaTemplate<String, String> kafka;

    /** The production factory the task/sla listener resolves — the recoverer under test
     *  rides THIS bean's containers, not a hand-built twin. */
    @Autowired
    ConcurrentKafkaListenerContainerFactory<String, String> listenerFactory;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestBase::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestBase::jdbcUsername);
        registry.add("spring.datasource.password", PostgresTestBase::jdbcPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Test
    @DisplayName("an exhausted budget dead-letters the ORIGINAL record — payload, key, partition, proven by the recoverer's own header")
    void exhaustedBudgetDeadLettersTheOriginalRecord() throws Exception {
        // An always-failing listener through the real factory on a private topic/group:
        // three 10 ms-spaced attempts, then the recoverer publishes and the redelivery
        // stops — exactly the production wiring's own recoverer, budget shrunk only.
        String topic = "novaforge.task.dlt-" + UUID.randomUUID();
        String payload = "dlt-pin-" + UUID.randomUUID();
        AtomicInteger deliveries = new AtomicInteger();
        List<Integer> partitions = new CopyOnWriteArrayList<>();
        var container = listenerFactory.createContainer(topic);
        container.getContainerProperties().setGroupId(
                "novaforge-notification-dlt-" + UUID.randomUUID());
        Properties consumerProps = new Properties();
        consumerProps.setProperty("auto.offset.reset", "earliest");
        container.getContainerProperties().setKafkaConsumerProperties(consumerProps);
        container.getContainerProperties().setMessageListener(
                (MessageListener<String, String>) record -> {
                    partitions.add(record.partition());
                    deliveries.incrementAndGet();
                    throw new org.springframework.mail.MailSendException("smtp down");
                });
        try {
            container.start();
            kafka.send(new ProducerRecord<>(topic, "dlt-key", payload)).get();
            // the three-attempt budget exhausts in tens of milliseconds, not minutes —
            // and it must STOP there: a handler without a recoverer would keep seeking
            await().atMost(Duration.ofSeconds(20)).until(() -> deliveries.get() >= 3);
            assertThat(deliveries.get())
                    .as("the shrunk budget is exactly three attempts, then recovery")
                    .isEqualTo(3);

            // The observation the nineteenth pass never made: consume the DLT topic with
            // a plain KafkaConsumer (subscribe + bounded poll). No recoverer, no record —
            // the bounded wait expires and this fails; a topic-existence check proves
            // nothing, the payload proves the publish.
            String deadLetterTopic = topic + ".DLT.novaforge-notification";
            Properties readerProps = new Properties();
            readerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
            readerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID());
            readerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            ConsumerRecord<String, String> dead = null;
            try (KafkaConsumer<String, String> reader = new KafkaConsumer<>(
                    readerProps, new StringDeserializer(), new StringDeserializer())) {
                reader.subscribe(List.of(deadLetterTopic));
                long deadline = System.currentTimeMillis() + 20_000;
                while (dead == null && System.currentTimeMillis() < deadline) {
                    for (ConsumerRecord<String, String> record : reader.poll(Duration.ofSeconds(1))) {
                        if (payload.equals(record.value())) {
                            dead = record;
                        }
                    }
                }
            }
            assertThat(dead).as("the exhausted record must land on " + deadLetterTopic)
                    .isNotNull();
            // the dead-lettered record IS the original: payload and key verbatim, the
            // destination expression's original partition, and the recoverer's own
            // provenance header — nothing but DeadLetterPublishingRecoverer writes it
            assertThat(dead.value()).isEqualTo(payload);
            assertThat(dead.key()).isEqualTo("dlt-key");
            assertThat(dead.partition()).isEqualTo(partitions.get(0));
            assertThat(new String(dead.headers().lastHeader("kafka_dlt-original-topic").value()))
                    .isEqualTo(topic);
        } finally {
            container.stop();
        }
    }
}
