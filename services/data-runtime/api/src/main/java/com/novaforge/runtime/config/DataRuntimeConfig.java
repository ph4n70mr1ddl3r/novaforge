package com.novaforge.runtime.config;

import com.novaforge.runtime.engine.event.DomainEventPublisher;
import com.novaforge.runtime.engine.metadata.EntityResolver;
import com.novaforge.runtime.engine.metadata.MetadataClient;
import com.novaforge.runtime.storage.materializer.Materializer;
import com.novaforge.security.TenantRlsDataSource;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Data Runtime wiring: the RLS DataSource bridge, the Phase 1 event-seam binding, and
 * the Redis subscriber for {@code metadata.published} (PHASE-1 §4/T4): the version-keyed
 * bundle cache evicts, then the storage materializer applies the fresh bundle's DDL —
 * publish time only, never the hot path (§6).
 */
@Configuration
public class DataRuntimeConfig {

    public static final String METADATA_EVENTS_CHANNEL = "novaforge.metadata.events";

    private static final Logger LOG = LoggerFactory.getLogger(DataRuntimeConfig.class);

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

    /**
     * Subscribes to {@code metadata.published}: evict the cached bundle, then
     * re-materialize on a single-threaded executor (DDL serialization).
     */
    @Bean
    public RedisMessageListenerContainer metadataPublishedSubscriber(
            RedisConnectionFactory connectionFactory,
            EntityResolver resolver,
            MetadataClient client,
            Materializer materializer) {
        Executor materializerExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "metadata-materializer");
            thread.setDaemon(true);
            return thread;
        });
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            try {
                Map<String, Object> envelope = JsonMapper.builder().build()
                        .readValue(new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8),
                                Map.class);
                UUID tenantId = UUID.fromString(String.valueOf(envelope.get("tenantId")));
                UUID appId = UUID.fromString(String.valueOf(envelope.get("appId")));
                resolver.evict(tenantId, appId);
                var bundle = client.publishedBundle(appId);
                materializerExecutor.execute(() -> {
                    try {
                        materializer.apply(bundle.app());
                    } catch (Exception e) {
                        LOG.error("materialization failed for app {}", appId, e);
                    }
                });
            } catch (Exception e) {
                LOG.error("invalid metadata.published envelope ignored", e);
            }
        }, new ChannelTopic(METADATA_EVENTS_CHANNEL));
        return container;
    }
}
