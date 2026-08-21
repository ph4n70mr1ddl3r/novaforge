package com.novaforge.runtime.config;

import com.novaforge.security.TenantRlsDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Data Runtime edge wiring: the RLS DataSource bridge. The event seam binds to the
 * transactional outbox (Phase 3 — the relay publishes to Kafka); the metadata.published
 * subscriber lives in the engine; the REST metadata client lives here at the api layer.
 */
@Configuration
public class DataRuntimeConfig {

    /**
     * Wraps every non-wrapped DataSource in the RLS bridge (ADR-006/PHASE-1 §6): the
     * {@code app.tenant} session variable is set per checkout from TenantContext and
     * reset on close, so pooled connections never leak a tenant.
     */
    @Bean
    static BeanPostProcessor rlsDataSourceWrapper() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource
                        && !(bean instanceof TenantRlsDataSource)
                        && !(bean instanceof DecoratingDataSourceMarker)) {
                    return new TenantRlsDataSource(dataSource);
                }
                return bean;
            }
        };
    }

    /** Marker to avoid double-wrapping test delegates. */
    public interface DecoratingDataSourceMarker {
    }

    /** String-keyed template for the outbox relay (serializers pinned in yaml). */
    @Bean
    @SuppressWarnings("unchecked")
    public org.springframework.kafka.core.KafkaTemplate<String, String> stringKafkaTemplate(
            org.springframework.kafka.core.ProducerFactory<Object, Object> producerFactory) {
        return new org.springframework.kafka.core.KafkaTemplate<>(
                (org.springframework.kafka.core.ProducerFactory<String, String>) (Object) producerFactory);
    }

}
