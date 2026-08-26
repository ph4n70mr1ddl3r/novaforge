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

    /** The published bundle for an app (cached, version-checked against the index). */
    public AppDefinition bundle(UUID tenantId, UUID appId) {
        MetadataClient.PublishedApp indexed = index(tenantId).stream()
                .filter(app -> app.appId().equals(appId)).findFirst()
                .orElseThrow(() -> new PlatformException(PlatformErrorCode.NOT_FOUND,
                        "app " + appId + " has no published version"));
        return bundles.computeIfAbsent(tenantId + ":" + appId, k -> {
            MetadataClient.PublishedBundle fresh = client.publishedBundle(appId);
            return new BundleEntry(fresh.version(), fresh.app());
        }).app();
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
            bundles.computeIfAbsent(tenantId + ":" + app.appId(), k -> {
                MetadataClient.PublishedBundle fresh = client.publishedBundle(app.appId());
                return new BundleEntry(fresh.version(), fresh.app());
            });
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
