package com.novaforge.runtime.engine.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * App-qualified entity resolution: a tenant's published apps may collide on an entity
 * apiName — the unqualified name rejects as ambiguous, and the {@code App.Entity}
 * form resolves to exactly the named app's entity (found live driving the Phase 8
 * exit leg: the ERP and the A/R demo app both define {@code Invoice} in the dev
 * workspace, and every unqualified runtime path — reports included — rejected).
 */
class EntityResolverQualifiedTests {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ERP_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID DESK_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");

    private static final String ERP_JSON = """
            { "apiName": "Erp",
              "entities": [ { "apiName": "Invoice", "displayField": "number",
                "fields": [ { "apiName": "number", "type": "text" },
                            { "apiName": "amount", "type": "decimal", "precision": 18, "scale": 4 } ] } ] }
            """;

    /** Same entity apiName, different field shape — the assertable difference. */
    private static final String DESK_JSON = """
            { "apiName": "ArDesk",
              "entities": [ { "apiName": "Invoice", "displayField": "customer",
                "fields": [ { "apiName": "customer", "type": "text" } ] } ] }
            """;

    private EntityResolver resolver() {
        AppDefinition erp = DefinitionParser.parseApp(ERP_JSON);
        AppDefinition desk = DefinitionParser.parseApp(DESK_JSON);
        MetadataClient client = new MetadataClient() {
            @Override
            public List<PublishedApp> publishedApps() {
                return List.of(
                        new PublishedApp(TENANT, ERP_ID, "Erp", 3),
                        new PublishedApp(TENANT, DESK_ID, "ArDesk", 1));
            }

            @Override
            public PublishedBundle publishedBundle(UUID appId) {
                return appId.equals(ERP_ID)
                        ? new PublishedBundle(3, erp)
                        : new PublishedBundle(1, desk);
            }
        };
        return new EntityResolver(client, 30_000);
    }

    @Test
    @DisplayName("an apiName two published apps share rejects unqualified as ambiguous")
    void ambiguousBareNameRejects() {
        assertThatThrownBy(() -> resolver().resolve(TENANT, "Invoice"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("defined by multiple published apps")
                .extracting(e -> ((PlatformException) e).errorCode())
                .isEqualTo(PlatformErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("App.Entity resolves to exactly the named app's entity")
    void qualifiedNameResolves() {
        EntityResolver resolver = resolver();
        EntityResolver.EntityHandle erp = resolver.resolve(TENANT, "Erp.Invoice");
        assertThat(erp.appId()).isEqualTo(ERP_ID);
        assertThat(erp.entityKey()).isEqualTo("Erp.Invoice");
        assertThat(erp.entity().field("number")).isPresent();
        assertThat(erp.entity().field("customer")).isEmpty();

        EntityResolver.EntityHandle desk = resolver.resolve(TENANT, "ArDesk.Invoice");
        assertThat(desk.appId()).isEqualTo(DESK_ID);
        assertThat(desk.entityKey()).isEqualTo("ArDesk.Invoice");
        assertThat(desk.entity().field("customer")).isPresent();
    }

    @Test
    @DisplayName("a qualified name for an unpublished app or unknown entity is NOT_FOUND")
    void unknownQualifiedNamesReject() {
        EntityResolver resolver = resolver();
        assertThatThrownBy(() -> resolver.resolve(TENANT, "Nope.Invoice"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("no published app named Nope")
                .extracting(e -> ((PlatformException) e).errorCode())
                .isEqualTo(PlatformErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> resolver.resolve(TENANT, "Erp.Ledger"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("no published entity named Erp.Ledger")
                .extracting(e -> ((PlatformException) e).errorCode())
                .isEqualTo(PlatformErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("an unshared apiName keeps resolving bare — the common path is unchanged")
    void unsharedBareNameStillResolves() {
        EntityResolver.EntityHandle handle = resolverWithLedger().resolve(TENANT, "StockLedger");
        assertThat(handle.appApiName()).isEqualTo("Erp");
        assertThat(handle.entityKey()).isEqualTo("Erp.StockLedger");
    }

    @Test
    @DisplayName("a missed eviction self-heals: the version bump reloads the cached bundle on the next TTL refresh")
    void missedEvictionSelfHeals() throws Exception {
        // Anti-regression (eighteenth pass): the cache was a plain computeIfAbsent —
        // nothing version-checked a present entry, and the ONLY eviction was the
        // Kafka subscriber. A dropped metadata.published delivery (outbox crash,
        // consumer rebalance gap) left the stale bundle serving forever: writes
        // validated against superseded metadata, and the bare-name path — whose
        // search skips version-mismatched entries — 404ed the entity outright,
        // permanently. The TTL refresh must reload, not just re-cache.
        String v1 = """
                { "apiName": "Erp",
                  "entities": [ { "apiName": "Invoice", "displayField": "number",
                    "fields": [ { "apiName": "number", "type": "text" } ] } ] }
                """;
        String v2 = """
                { "apiName": "Erp",
                  "entities": [ { "apiName": "Invoice", "displayField": "number",
                    "fields": [ { "apiName": "number", "type": "text" },
                                { "apiName": "amount", "type": "decimal", "precision": 18, "scale": 4 } ] },
                                { "apiName": "Voucher", "displayField": "ref",
                    "fields": [ { "apiName": "ref", "type": "text" } ] } ] }
                """;
        AppDefinition first = DefinitionParser.parseApp(v1);
        AppDefinition second = DefinitionParser.parseApp(v2);
        int[] version = { 1 };
        MetadataClient client = new MetadataClient() {
            @Override
            public List<PublishedApp> publishedApps() {
                return List.of(new PublishedApp(TENANT, ERP_ID, "Erp", version[0]));
            }

            @Override
            public PublishedBundle publishedBundle(UUID appId) {
                return new PublishedBundle(version[0],
                        version[0] == 1 ? first : second);
            }
        };
        EntityResolver resolver = new EntityResolver(client, 50);

        // v1 loads and caches; then the publish to v2 happens with NO eviction
        EntityResolver.EntityHandle before = resolver.resolve(TENANT, "Invoice");
        assertThat(before.entity().field("amount")).isEmpty();
        version[0] = 2;

        // past the index TTL the refresh must self-heal: the new field resolves, and
        // v2's new entity is reachable bare (the 404 trap) — not still v1's bundle
        Thread.sleep(120);
        EntityResolver.EntityHandle after = resolver.resolve(TENANT, "Invoice");
        assertThat(after.version()).isEqualTo(2);
        assertThat(after.entity().field("amount")).isPresent();
        EntityResolver.EntityHandle voucher = resolver.resolve(TENANT, "Voucher");
        assertThat(voucher.entityKey()).isEqualTo("Erp.Voucher");
        assertThat(resolver.cacheSize()).isEqualTo(1);
    }

    private EntityResolver resolverWithLedger() {
        AppDefinition erp = DefinitionParser.parseApp("""
                { "apiName": "Erp",
                  "entities": [ { "apiName": "StockLedger", "displayField": "sku",
                    "fields": [ { "apiName": "sku", "type": "text" } ] } ] }
                """);
        MetadataClient client = new MetadataClient() {
            @Override
            public List<PublishedApp> publishedApps() {
                return List.of(new PublishedApp(TENANT, ERP_ID, "Erp", 1));
            }

            @Override
            public PublishedBundle publishedBundle(UUID appId) {
                return new PublishedBundle(1, erp);
            }
        };
        return new EntityResolver(client, 30_000);
    }
}
