package com.novaforge.reporting.source;

import com.novaforge.metadata.AppDefinition;
import java.util.UUID;

/**
 * The published-definition source (§2): reports and dashboards are versioned metadata
 * fetched through the Metadata Service's published read with the platform service
 * client — never authored here. Implementations cache in-process and invalidate on
 * {@code metadata.published}.
 */
public interface PublishedApps {

    record PublishedApp(UUID tenantId, String appId, String apiName, int version,
                        AppDefinition definition) {
    }

    /** The app's current published bundle; empty when the app has no published version. */
    java.util.Optional<PublishedApp> byApiName(UUID tenantId, String appApiName);
}
