package com.novaforge.workflow.events;

import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
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
 * The spine consumers' error handling (2026-08-31, sixteenth pass): Boot's defaults
 * retry a failing record nine times with ZERO backoff and then log-and-skip — no
 * dead letter, no retention of the dropped event. For the workflow service's record
 * consumer that is silent data loss: a metadata blip while an event-start matches
 * meant the process never started and the offset committed anyway.
 *
 * <p>Every listener container now carries a {@link DefaultErrorHandler} with real
 * exponential backoff (1 s doubling to a 60 s ceiling, ten attempts) and a dead-letter
 * publisher: after the budget the record lands on {@code <topic>.DLT.<group>} (keyed
 * to its original partition) where it is durable, replayable, and visible — never
 * silently skipped. Envelope-shaped failures (the consumer's own terminal-parse
 * branch) still skip immediately: no backoff can fix them, and the DLT would only
 * accumulate noise for a malformed payload the producer must fix.</p>
 *
 * <p>The budget itself is property-tunable ({@code novaforge.kafka.consumer-retry.*}):
 * the defaults below ARE the production budget — 1 s initial doubling to a 60 s
 * ceiling, ten attempts — and only the test context shrinks them (10 ms/10 ms/three
 * attempts), because exhausting the production budget takes ~5 minutes of cumulative
 * backoff and no honest test waits that out. Runtime behavior with the properties unset
 * is bit-identical to the hardcoded values this replaced. The twentieth pass unified
 * the three services' configs on these same names and defaults — audit and
 * notification mirror this class, and a tunable original with hardcoded twins (or the
 * reverse) was an inconsistency waiting to bite.</p>
 */
@Configuration
@EnableKafka
public class ConsumerErrorConfig {

    /** The terminal-envelope classifier the consumers already use for their own
     *  skip-legs — routed straight to the DLT without burning the retry budget. */
    static final java.util.function.BiPredicate<org.apache.kafka.clients.consumer.ConsumerRecord<?, ?>,
            java.lang.Exception> ENVELOPE_SHAPED =
            (record, exception) -> exception.getCause() instanceof IllegalArgumentException
                    || exception.getMessage() != null
                            && exception.getMessage().contains("ignored");

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${novaforge.kafka.consumer-retry.initial-ms:1000}") long retryInitialMs,
            @Value("${novaforge.kafka.consumer-retry.max-interval-ms:60000}") long retryMaxIntervalMs,
            @Value("${novaforge.kafka.consumer-retry.max-attempts:10}") long retryMaxAttempts) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        ExponentialBackOff backoff = new ExponentialBackOff(retryInitialMs, 2.0);
        backoff.setMaxInterval(retryMaxIntervalMs);
        backoff.setMaxAttempts(retryMaxAttempts);

        DeadLetterPublishingRecoverer deadLetters = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (ConsumerRecord<?, ?> record, Exception exception) -> new TopicPartition(
                        record.topic() + ".DLT." + "novaforge-workflow", record.partition()));

        DefaultErrorHandler handler = new DefaultErrorHandler(deadLetters, backoff);
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        factory.setCommonErrorHandler(handler);
        return factory;
    }
}
