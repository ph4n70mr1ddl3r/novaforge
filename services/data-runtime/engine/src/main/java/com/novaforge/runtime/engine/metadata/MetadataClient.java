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

    record PublishedApp(UUID appId, String apiName, int version) {
    }

    record PublishedBundle(int version, AppDefinition app) {
    }

    List<PublishedApp> publishedApps();

    PublishedBundle publishedBundle(UUID appId);
}
