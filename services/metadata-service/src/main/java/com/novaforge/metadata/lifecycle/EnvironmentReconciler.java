package com.novaforge.metadata.lifecycle;

import com.novaforge.metadata.store.MetadataStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The environment-pin reconciler (2026-08-31, the eleventh pass): a promote or
 * rollback that died between the environment tenant's publish and the control-plane
 * pin left the environment serving a version the control plane could not see — the
 * prod parity check then rejects the matching promote until someone re-promoted by
 * hand. On boot, every active environment's pin is compared against its tenant's
 * actual latest published version (a local read — the environment's app rows live in
 * this store) and realigned, with a recorded 'reconcile' promotion row so the drift
 * and its repair stay in the audited history. Dangling first-provision intents are
 * surfaced loudly for an operator or a retry to converge.
 */
@Component
public class EnvironmentReconciler {

    private static final Logger LOG = LoggerFactory.getLogger(EnvironmentReconciler.class);

    private final MetadataStore store;

    public EnvironmentReconciler(MetadataStore store) {
        this.store = store;
    }

    /** The reconcile row's actor: a named system identity, never a null (NOT NULL). */
    private static final java.util.UUID RECONCILER =
            java.util.UUID.nameUUIDFromBytes("environment-reconciler".getBytes());

    @EventListener(ApplicationReadyEvent.class)
    public void reconcile() {
        for (MetadataStore.EnvironmentRefRow row : store.allEnvironments()) {
            try {
                reconcileOne(row);
            } catch (Exception e) {
                // one drifted environment must never block the boot or the others
                LOG.error("environment reconcile failed for {}@{} (will retry next boot)",
                        row.appId(), row.env(), e);
            }
        }
    }

    private void reconcileOne(MetadataStore.EnvironmentRefRow row) {
        if (row.envTenantId() == null) {
            LOG.warn("environment {}@{} has a dangling provision intent (version {}) — "
                    + "a promotion crashed mid-provision; re-promote to converge",
                    row.appId(), row.env(), row.pinnedVersion());
            return;
        }
        var actual = store.latestPublished(row.envTenantId(), row.envAppId());
        if (actual.isEmpty()) {
            return;
        }
        if (actual.get().version() != row.pinnedVersion()) {
            LOG.info("environment {}@{} pin {} drifted from the environment tenant's "
                            + "published {} — realigning (a promote/rollback died before "
                            + "the pin landed)",
                    row.appId(), row.env(), row.pinnedVersion(), actual.get().version());
            store.pinVersion(row.tenantId(), row.appId(), row.env(), actual.get().version());
            store.recordPromotion(row.tenantId(), row.appId(), row.env(), "reconcile",
                    row.pinnedVersion(), actual.get().version(), false,
                    "boot reconcile: aligned the pin with the environment's published "
                            + "version", java.util.Map.of("reconciled", true), RECONCILER);
        }
    }
}
