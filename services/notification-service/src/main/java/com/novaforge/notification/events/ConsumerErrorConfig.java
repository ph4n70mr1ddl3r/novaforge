package com.novaforge.notification.events;

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
 * mechanism (2026-08-31, sixteenth pass) mirrored for the fan-out: TaskEventConsumer
 * deliberately PROPAGATES non-envelope failures so Notifier's @Transactional inbox
 * rows roll back with a failed send, but with no container error handler Boot's
 * default answered that propagation with nine ZERO-backoff retries and a
 * log-and-skip: every task.assigned/sla.warn/sla.breach arriving during an SMTP
 * outage was silently lost — no inbox row, no email, offset committed.
 *
 * <p>Every listener container now carries a {@link DefaultErrorHandler} with real
 * exponential backoff (1 s doubling to a 60 s ceiling, ten attempts) and a dead-letter
 * publisher: an SMTP outage pauses the partition and redelivers until the mail host
 * returns, and after the budget the record lands on
 * {@code <topic>.DLT.novaforge-notification} (keyed to its original partition) where
 * it is durable, replayable, and visible — never silently skipped. The listener's
 * own terminal-parse branch (IllegalArgumentException) is caught in-listener and
 * never reaches the handler; the not-retryable registration keeps that contract if
 * one ever escapes. Redeliveries collapse on the inbox's (tenant, user, event)
 * dedupe and the email leg's claim marker — at-least-once stays idempotent.</p>
 */
@Configuration
@EnableKafka
public class ConsumerErrorConfig {

    /**
     * Overrides Boot's default {@code kafkaListenerContainerFactory} (the bean name
     * every listener resolves when none is named) — the same wiring the workflow
     * service rides, so the task/sla listener inherits the handler by default.
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
                        record.topic() + ".DLT." + "novaforge-notification",
                        record.partition()));

        DefaultErrorHandler handler = new DefaultErrorHandler(deadLetters, backoff);
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        factory.setCommonErrorHandler(handler);
        return factory;
    }
}
