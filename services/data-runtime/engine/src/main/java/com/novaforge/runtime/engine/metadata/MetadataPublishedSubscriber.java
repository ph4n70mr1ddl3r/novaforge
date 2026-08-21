package com.novaforge.runtime.engine.metadata;

import com.novaforge.runtime.storage.materializer.Materializer;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code metadata.published} subscriber (PHASE-1 §4/T4): the version-keyed bundle
 * cache evicts, then the storage materializer applies the fresh bundle's DDL on a
 * single-threaded executor (DDL serialization) — publish time only, never the hot
 * path (§6). Lives in the engine: it bridges the resolver cache (engine) and the
 * materializer (storage) without the api layer knowing either.
 */
@Component
public class MetadataPublishedSubscriber {

    public static final String CHANNEL = "novaforge.metadata.events";

    private static final Logger LOG = LoggerFactory.getLogger(MetadataPublishedSubscriber.class);

    private final RedisConnectionFactory connectionFactory;
    private final EntityResolver resolver;
    private final MetadataClient client;
    private final Materializer materializer;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ExecutorService materializerExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "metadata-materializer");
        thread.setDaemon(true);
        return thread;
    });

    public MetadataPublishedSubscriber(RedisConnectionFactory connectionFactory,
                                       EntityResolver resolver,
                                       MetadataClient client,
                                       Materializer materializer) {
        this.connectionFactory = connectionFactory;
        this.resolver = resolver;
        this.client = client;
        this.materializer = materializer;
    }

    @PostConstruct
    void subscribe() {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            try {
                Map<String, Object> envelope = mapper.readValue(
                        new String(message.getBody(), StandardCharsets.UTF_8), Map.class);
                UUID tenantId = UUID.fromString(String.valueOf(envelope.get("tenantId")));
                UUID appId = UUID.fromString(String.valueOf(envelope.get("appId")));
                resolver.evict(tenantId, appId);
                MetadataClient.PublishedBundle bundle = client.publishedBundle(appId);
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
        }, new ChannelTopic(CHANNEL));
        container.afterPropertiesSet();
        container.start();
        LOG.info("subscribed to {} for cache invalidation + re-materialization", CHANNEL);
        catchUpOnRestart();
    }

    /**
     * Publishes that fired while this instance was down are gone (pub/sub has no
     * replay) — on boot, materialize every currently published app so restarts never
     * serve an entity whose projection is missing. Idempotent DDL makes this safe.
     */
    private void catchUpOnRestart() {
        try {
            for (MetadataClient.PublishedApp app : client.publishedApps()) {
                try {
                    MetadataClient.PublishedBundle bundle = client.publishedBundle(app.appId());
                    materializer.apply(bundle.app());
                } catch (Exception e) {
                    LOG.error("startup catch-up failed for app {}", app.appId(), e);
                }
            }
        } catch (Exception e) {
            LOG.error("startup catch-up could not read the published-apps index", e);
        }
    }
}
