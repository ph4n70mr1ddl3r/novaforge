package com.novaforge.runtime.engine.metadata;

import com.novaforge.runtime.storage.materializer.Materializer;
import com.novaforge.security.EventHeaders;
import com.novaforge.security.TracePropagation;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code metadata.published} subscriber (PHASE-1 §4/T4): the version-keyed bundle
 * cache evicts, then the storage materializer applies the fresh bundle's DDL on a
 * single-threaded executor (DDL serialization) — publish time only, never the hot
 * path (§6). Lives in the engine: it bridges the resolver cache (engine) and the
 * materializer (storage) without the api layer knowing either.
 *
 * <p>PHASE-3 §4 rebind: the envelope rides the spine topic {@code novaforge.metadata}
 * — same payload, Kafka client; the Phase 1 Redis pub/sub channel is retired. The
 * spine's at-least-once redelivery is safe here (idempotent DDL + a cache evict), and
 * the boot catch-up covers publishes missed while this instance was down.</p>
 */
@Component
public class MetadataPublishedSubscriber {

    /** The metadata family topic (PHASE-3 §4 topology: {@code novaforge.<family>}). */
    public static final String TOPIC = "novaforge.metadata";

    private static final Logger LOG = LoggerFactory.getLogger(MetadataPublishedSubscriber.class);

    private final EntityResolver resolver;
    private final MetadataClient client;
    private final Materializer materializer;
    private final Tracer tracer;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ExecutorService materializerExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "metadata-materializer");
        thread.setDaemon(true);
        return thread;
    });

    public MetadataPublishedSubscriber(EntityResolver resolver,
                                       MetadataClient client,
                                       Materializer materializer,
                                       Tracer tracer) {
        this.resolver = resolver;
        this.client = client;
        this.materializer = materializer;
        this.tracer = tracer;
    }

    @KafkaListener(topics = TOPIC, groupId = "novaforge-runtime-metadata")
    public void onMetadataPublished(ConsumerRecord<String, String> message) {
        var header = message.headers().lastHeader(EventHeaders.TRACEPARENT);
        String traceparent = header == null ? null
                : new String(header.value(), StandardCharsets.UTF_8);
        TracePropagation.inConsumerSpan(tracer, traceparent,
                "novaforge.metadata consume", () -> handle(message.value()));
    }

    void handle(String message) {
        try {
            Map<String, Object> envelope = mapper.readValue(message, Map.class);
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
    }

    @jakarta.annotation.PostConstruct
    void catchUpOnRestart() {
        // Publishes that fired while this instance was down are covered by the boot
        // catch-up (the consumer group's offsets only advance while it is live, and a
        // fresh group starts at earliest — but an offset reset to latest or a manual
        // purge must never leave a projection missing). Idempotent DDL makes re-running
        // every currently published app safe.
        try {
            for (MetadataClient.PublishedApp app : client.publishedApps()) {
                try {
                    MetadataClient.PublishedBundle bundle = client.publishedBundle(app.appId());
                    materializerExecutor.execute(() -> {
                        try {
                            materializer.apply(bundle.app());
                        } catch (Exception e) {
                            LOG.error("startup catch-up failed for app {}", app.appId(), e);
                        }
                    });
                } catch (Exception e) {
                    LOG.error("startup catch-up could not fetch app {}", app.appId(), e);
                }
            }
        } catch (Exception e) {
            LOG.error("startup catch-up could not read the published-apps index", e);
        }
    }
}
