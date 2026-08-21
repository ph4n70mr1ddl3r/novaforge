package com.novaforge.runtime.config;

import com.novaforge.runtime.engine.event.DomainEventPublisher;
import com.novaforge.security.TenantRlsDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Data Runtime edge wiring: the RLS DataSource bridge and the Phase 1 event-seam
 * binding. The metadata.published subscriber lives in the engine (it bridges the
 * resolver cache and the storage materializer — both engine-reachable); the REST
 * metadata client lives here at the api layer.
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

    /** Phase 1 event-seam binding: the no-op recorder (Kafka producer arrives Phase 3). */
    @Bean
    public DomainEventPublisher domainEventPublisher() {
        return new DomainEventPublisher.Recording();
    }
}
