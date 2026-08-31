package com.novaforge.runtime.engine.metadata;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.EntityDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves entity names to published definitions through a version-keyed cache
 * (ARCHITECTURE.md §1/§2.3): bundles cached per (tenant, appId) under the published
 * version; the app index refreshes on a short window or on publish eviction (the Redis
 * {@code metadata.published} subscriber, T4). The runtime never serves mutable drafts.
 *
 * <p>Entity apiNames are unique per app; a collision across a tenant's published apps
 * rejects as ambiguous rather than silently resolving.</p>
 */
@Component
public class EntityResolver {

    /** A resolved entity bound to its app + published version. */
    public record EntityHandle(UUID appId, String appApiName, int version,
                               EntityDefinition entity, String entityKey) {
    }

    private record BundleEntry(int version, AppDefinition app) {
    }

    private record IndexEntry(long loadedAtMillis, List<MetadataClient.PublishedApp> apps) {
    }

    private final MetadataClient client;
    private final long indexTtlMillis;

    private final Map<String, BundleEntry> bundles = new ConcurrentHashMap<>();
    private final Map<UUID, IndexEntry> indexes = new ConcurrentHashMap<>();

    public EntityResolver(MetadataClient client,
                          @Value("${novaforge.metadata.cache-index-ttl-ms:30000}") long indexTtlMillis) {
        this.client = client;
        this.indexTtlMillis = indexTtlMillis;
    }

    public EntityHandle resolve(UUID tenantId, String entityApiName) {
        // The app-qualified form `App.Entity` — the runtime's disambiguation surface
        // when a tenant's published apps collide on an entity apiName (the ambiguity
        // error below names it). ApiNames carry no dots (save-validation: word
        // characters only), so a dotted name can only be the qualified form.
        int dot = entityApiName.indexOf('.');
        if (dot > 0 && dot < entityApiName.length() - 1) {
            return resolveQualified(tenantId, entityApiName.substring(0, dot),
                    entityApiName.substring(dot + 1));
        }
        EntityHandle handle = searchCached(tenantId, entityApiName);
        if (handle == null) {
            refreshTenant(tenantId);
            handle = searchCached(tenantId, entityApiName);
        }
        if (handle == null) {
            throw new PlatformException(PlatformErrorCode.NOT_FOUND,
                    "no published entity named " + entityApiName);
        }
        return handle;
    }

    /**
     * The qualified resolution: the app's apiName pins the bundle, the entity
     * resolves within it — a same-named entity in another published app of the
     * tenant can never shadow or be shadowed (the exact collision the unqualified
     * path rejects as ambiguous).
     */
    private EntityHandle resolveQualified(UUID tenantId, String appApiName, String entityApiName) {
        java.util.function.Predicate<MetadataClient.PublishedApp> isApp =
                app -> app.apiName().equals(appApiName);
        MetadataClient.PublishedApp indexed = index(tenantId).stream().filter(isApp).findFirst().orElse(null);
        if (indexed == null) {
            refreshTenant(tenantId);
            indexed = index(tenantId).stream().filter(isApp).findFirst().orElse(null);
        }
        if (indexed == null) {
            throw new PlatformException(PlatformErrorCode.NOT_FOUND,
                    "no published app named " + appApiName + " for this tenant");
        }
        AppDefinition bundle = bundle(tenantId, indexed.appId());
        EntityDefinition entity = bundle.entity(entityApiName).orElseThrow(() ->
                new PlatformException(PlatformErrorCode.NOT_FOUND,
                        "no published entity named " + appApiName + "." + entityApiName));
        return new EntityHandle(indexed.appId(), bundle.apiName(), indexed.version(), entity,
                bundle.apiName() + "." + entityApiName);
    }

    /** The published bundle for an app (cached, version-checked against the index). */
    public AppDefinition bundle(UUID tenantId, UUID appId) {
        MetadataClient.PublishedApp indexed = index(tenantId).stream()
                .filter(app -> app.appId().equals(appId)).findFirst()
                .orElseThrow(() -> new PlatformException(PlatformErrorCode.NOT_FOUND,
                        "app " + appId + " has no published version"));
        return bundleOf(tenantId, indexed);
    }

    /**
     * The version-checked bundle load: a cached entry whose version no longer
     * matches the index RELOADS. The Kafka eviction is best-effort — a dropped
     * delivery, a consumer rebalance gap, a publish whose outbox leg crashed — and
     * {@code computeIfAbsent} would serve the superseded bundle forever (writes
     * validated and authorized against dead metadata) while the unqualified path's
     * version-skipping search 404s the entity outright. The index TTL refresh is
     * the self-healing backstop: staleness now costs one TTL window, never a
     * restart.
     */
    private AppDefinition bundleOf(UUID tenantId, MetadataClient.PublishedApp indexed) {
        String key = tenantId + ":" + indexed.appId();
        BundleEntry entry = bundles.get(key);
        if (entry == null || entry.version() != indexed.version()) {
            MetadataClient.PublishedBundle fresh = client.publishedBundle(indexed.appId());
            entry = new BundleEntry(fresh.version(), fresh.app());
            bundles.put(key, entry);
        }
        return entry.app();
    }

    /** Evicts a tenant app's cached bundle + index — the publish subscriber calls this (T4). */
    public void evict(UUID tenantId, UUID appId) {
        bundles.remove(tenantId + ":" + appId);
        indexes.remove(tenantId);
    }

    /** Test/ops observation: cached bundle count. */
    public int cacheSize() {
        return bundles.size();
    }

    private EntityHandle searchCached(UUID tenantId, String entityApiName) {
        List<EntityHandle> matches = new ArrayList<>();
        for (MetadataClient.PublishedApp app : index(tenantId)) {
            BundleEntry entry = bundles.get(tenantId + ":" + app.appId());
            if (entry == null || entry.version() != app.version()) {
                continue;
            }
            Optional<EntityDefinition> entity = entry.app().entity(entityApiName);
            entity.ifPresent(e -> matches.add(new EntityHandle(app.appId(), entry.app().apiName(),
                    entry.version(), e, entry.app().apiName() + "." + e.apiName())));
        }
        if (matches.size() > 1) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "entity " + entityApiName + " is defined by multiple published apps — qualify the app");
        }
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private void refreshTenant(UUID tenantId) {
        // The service-caller index is cross-tenant (the materializer's union needs
        // every tenant — projections are shared DDL); the resolver's view is THIS
        // tenant's apps only, so scratch-tenant publishes can never collide with a
        // same-named entity elsewhere (found live: unqualified resolution turned
        // ambiguous platform-wide once a second tenant published the same apiName).
        List<MetadataClient.PublishedApp> apps = client.publishedApps().stream()
                .filter(app -> app.tenantId() == null || app.tenantId().equals(tenantId))
                .toList();
        indexes.put(tenantId, new IndexEntry(System.currentTimeMillis(), apps));
        for (MetadataClient.PublishedApp app : apps) {
            bundleOf(tenantId, app);
        }
    }

    private List<MetadataClient.PublishedApp> index(UUID tenantId) {
        IndexEntry entry = indexes.get(tenantId);
        if (entry == null || System.currentTimeMillis() - entry.loadedAtMillis() > indexTtlMillis) {
            refreshTenant(tenantId);
            entry = indexes.get(tenantId);
        }
        return entry.apps();
    }
}
