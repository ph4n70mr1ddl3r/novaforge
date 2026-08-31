package com.novaforge.audit.api;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * The spine consumers' error handling — the Workflow Service's ConsumerErrorConfig
 * mechanism (2026-08-31, sixteenth pass) mirrored for the trail: the three audit
 * listeners deliberately PROPAGATE a failed {@code store.append} ("must redeliver" —
 * committing the offset over a dropped append leaves a permanent, silent hole in
 * the append-only trail), but Boot's default handler answers a propagated failure
 * with nine ZERO-backoff retries and a log-and-skip: a database outage burned its
 * budget in under a second and then dropped every in-flight event for good.
 *
 * <p>Every listener container now carries a {@link DefaultErrorHandler} with real
 * exponential backoff (1 s doubling to a 60 s ceiling, ten attempts) and a dead-letter
 * publisher: a store outage pauses the partition and redelivers until the database
 * returns, and after the budget the record lands on {@code <topic>.DLT.novaforge-audit}
 * (keyed to its original partition) where it is durable, replayable, and visible —
 * never silently skipped. The listeners' own terminal-parse branches
 * (IllegalArgumentException/DateTimeParseException) are caught in-listener and never
 * reach the handler; the not-retryable registration keeps that contract if one ever
 * escapes.</p>
 */
@Configuration
@EnableKafka
public class ConsumerErrorConfig {

    /**
     * Overrides Boot's default {@code kafkaListenerContainerFactory} (the bean name
     * every listener resolves when none is named) — the same wiring the workflow
     * service rides, so all three audit groups inherit the handler by default.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        ExponentialBackOff backoff = new ExponentialBackOff(1_000L, 2.0);
        backoff.setMaxInterval(60_000L);
        backoff.setMaxAttempts(10L);

        DeadLetterPublishingRecoverer deadLetters = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (ConsumerRecord<?, ?> record, Exception exception) -> new TopicPartition(
                        record.topic() + ".DLT." + "novaforge-audit", record.partition()));

        DefaultErrorHandler handler = new DefaultErrorHandler(deadLetters, backoff);
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        factory.setCommonErrorHandler(handler);
        return factory;
    }
}
