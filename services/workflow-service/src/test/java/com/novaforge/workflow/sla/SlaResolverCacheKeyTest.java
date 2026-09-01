package com.novaforge.workflow.sla;

import static org.assertj.core.api.Assertions.assertThat;

import com.novaforge.metadata.SlaDefinition;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The SLA definition cache's tenant scoping, pinned directly (re-audit): the
 * 30 s entry is keyed {@code tenantId + ":" + appApiName} — two tenants running
 * same-named apps must each resolve their own definitions inside the window.
 * An app-name-only key serves tenant A's warn/breach timers (and escalation
 * targets) to tenant B; every context suite sidesteps the cache with fresh app
 * names, so only this unit pins the key.
 */
class SlaResolverCacheKeyTest {

    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    @DisplayName("same app apiName, two tenants: each resolves its own SLA inside the 30 s window")
    void tenantScopedCacheKey() {
        Map<UUID, List<SlaDefinition>> perTenant = new HashMap<>();
        SlaResolver resolver = new SlaResolver(
                (tenantId, appApiName) -> perTenant.getOrDefault(tenantId, List.of()));

        var oneHour = new SlaDefinition("a-one-hour",
                new SlaDefinition.Scope("approval", "entity == 'T.PurchaseOrder'"),
                "PT1H", 0.5, null);
        var twoHour = new SlaDefinition("b-two-hour",
                new SlaDefinition.Scope("approval", "entity == 'T.PurchaseOrder'"),
                "PT2H", 0.5, null);
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        perTenant.put(tenantA, List.of(oneHour));
        perTenant.put(tenantB, List.of(twoHour));

        // tenant A warms the cache entry for app "T"; tenant B resolves the same
        // app name milliseconds later — inside any cache window
        var timersA = resolver.resolve(tenantA, "T", "T.PurchaseOrder", "approval",
                null, null, NOW);
        var timersB = resolver.resolve(tenantB, "T", "T.PurchaseOrder", "approval",
                null, null, NOW);

        assertThat(timersA.dueAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(timersB.dueAt()).isEqualTo(NOW.plusSeconds(7200));
        assertThat(timersB.matched()).isEqualTo(twoHour);
    }
}
