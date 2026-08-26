package com.novaforge.runtime.engine.metadata;

import com.novaforge.metadata.AppDefinition;
import java.util.List;
import java.util.UUID;

/**
 * Read-only access to the Metadata Service's published surface (PHASE-1 §4). The
 * published read serves any authenticated tenant user; calls carry the caller's bearer
 * token (principal relay — no service account in Phase 1).
 */
public interface MetadataClient {

    /**
     * One published app in the index. {@code tenantId} rides the service-caller
     * (cross-tenant) view — the resolver filters the index to the requesting
     * tenant, so another tenant's published app can never shadow or collide with
     * same-named entities (found live: accumulated scratch-tenant publishes made
     * unqualified entity resolution ambiguous platform-wide).
     */
    record PublishedApp(UUID tenantId, UUID appId, String apiName, int version) {

        /** The user-caller view shape (tenant-scoped server-side, no tenant field). */
        public PublishedApp(UUID appId, String apiName, int version) {
            this(null, appId, apiName, version);
        }
    }

    record PublishedBundle(int version, AppDefinition app) {
    }

    List<PublishedApp> publishedApps();

    PublishedBundle publishedBundle(UUID appId);
}
