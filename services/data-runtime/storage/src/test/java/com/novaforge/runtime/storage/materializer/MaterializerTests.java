package com.novaforge.runtime.storage.materializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.testsupport.PostgresTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Definition→runtime storage integration (PHASE-1 §9 item 6): publish-side materializer
 * creates the ADR-001 projection (generated columns, partial unique index, trigger sync,
 * RLS), and CRUD against the base table keeps the projection current without redeploy.
 */
class MaterializerTests extends PostgresTestBase {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ACTOR = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private static JdbcTemplate jdbc;
    private static Materializer materializer;

    private static final String APP_JSON = """
            { "apiName": "Erp",
              "entities": [ { "apiName": "JournalEntry",
                "displayField": "reference",
                "fields": [
                  { "apiName": "reference", "type": "text", "uniqueness": true },
                  { "apiName": "entryDate", "type": "date" },
                  { "apiName": "status", "type": "enum", "values": ["DRAFT", "POSTED"] },
                  { "apiName": "amount", "type": "decimal", "precision": 18, "scale": 4 } ],
                "indexes": [ { "fields": ["entryDate"] } ] } ] }
            """;

    /** v2: memo joins the index declaration — a promoted field added to an existing table. */
    private static final String APP_JSON_V2_INDEXED_FIELD = """
            { "apiName": "Erp",
              "entities": [ { "apiName": "JournalEntry",
                "displayField": "reference",
                "fields": [
                  { "apiName": "reference", "type": "text", "uniqueness": true },
                  { "apiName": "entryDate", "type": "date" },
                  { "apiName": "status", "type": "enum", "values": ["DRAFT", "POSTED"] },
                  { "apiName": "amount", "type": "decimal", "precision": 18, "scale": 4 },
                  { "apiName": "memo", "type": "text" } ],
                "indexes": [ { "fields": ["entryDate"] }, { "fields": ["memo"] } ] } ] }
            """;

    /** v3: the unique reference field is removed (breaking, acknowledged). */
    private static final String APP_JSON_V3_FIELD_REMOVED = """
            { "apiName": "Erp",
              "entities": [ { "apiName": "JournalEntry",
                "displayField": "status",
                "fields": [
                  { "apiName": "entryDate", "type": "date" },
                  { "apiName": "status", "type": "enum", "values": ["DRAFT", "POSTED"] },
                  { "apiName": "amount", "type": "decimal", "precision": 18, "scale": 4 } ],
                "indexes": [ { "fields": ["entryDate"] } ] } ] }
            """;

    /** v4: amount re-typed decimal → text (breaking, acknowledged) — still indexed, still promoted. */
    private static final String APP_JSON_V4_AMOUNT_TEXT = """
            { "apiName": "Erp",
              "entities": [ { "apiName": "JournalEntry",
                "displayField": "reference",
                "fields": [
                  { "apiName": "reference", "type": "text", "uniqueness": true },
                  { "apiName": "entryDate", "type": "date" },
                  { "apiName": "status", "type": "enum", "values": ["DRAFT", "POSTED"] },
                  { "apiName": "amount", "type": "text" } ],
                "indexes": [ { "fields": ["entryDate"] }, { "fields": ["amount"] } ] } ] }
            """;

    /** The isolation test's broken shape: its projection name is pre-occupied. */
    private static final String BROKEN_APP_JSON = """
            { "apiName": "Broken",
              "entities": [ { "apiName": "Broken",
                "displayField": "label",
                "fields": [ { "apiName": "label", "type": "text" } ] } ] }
            """;

    /** The type-change test's starting point: amount indexed (promoted) as decimal. */
    private static final String APP_JSON_AMOUNT_INDEXED_DECIMAL = """
            { "apiName": "Erp",
              "entities": [ { "apiName": "JournalEntry",
                "displayField": "reference",
                "fields": [
                  { "apiName": "reference", "type": "text", "uniqueness": true },
                  { "apiName": "entryDate", "type": "date" },
                  { "apiName": "status", "type": "enum", "values": ["DRAFT", "POSTED"] },
                  { "apiName": "amount", "type": "decimal", "precision": 18, "scale": 4 } ],
                "indexes": [ { "fields": ["entryDate"] }, { "fields": ["amount"] } ] } ] }
            """;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                jdbcUrl(), jdbcUsername(), jdbcPassword());
        jdbc = new JdbcTemplate(dataSource);
        org.flywaydb.core.Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        materializer = new Materializer(jdbc);
    }

    @Test
    @DisplayName("materializer creates the projection with generated columns + indexes + trigger + RLS")
    void createsProjection() {
        AppDefinition app = DefinitionParser.parseApp(APP_JSON);
        materializer.apply(app);

        Map<String, Object> columns = jdbc.queryForMap("""
                SELECT data_type FROM information_schema.columns
                 WHERE table_name = 'rec_journal_entry' AND column_name = 'entry_date'""");
        assertThat(columns.get("data_type")).isEqualTo("text");
        Integer uniques = jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                 WHERE tablename = 'rec_journal_entry' AND indexname LIKE 'ux_%'""",
                Integer.class);
        assertThat(uniques).isEqualTo(1);   // uniqueness on reference, scoped per app (§6)
        // the per-app scope: the unique index discriminates by the App.Entity key
        Map<String, Object> uniqueDefinition = jdbc.queryForMap("""
                SELECT indexdef FROM pg_indexes
                 WHERE tablename = 'rec_journal_entry' AND indexname LIKE 'ux_%'""");
        assertThat(String.valueOf(uniqueDefinition.get("indexdef")))
                .contains("tenant_id, entity_id")
                .contains("WHERE (NOT deleted)");
        Integer triggers = jdbc.queryForObject("""
                SELECT count(DISTINCT trigger_name) FROM information_schema.triggers
                 WHERE event_object_table = 'rec_records' AND trigger_name = 'trg_rec_journal_entry'""",
                Integer.class);
        assertThat(triggers).isEqualTo(1);
        Integer policies = jdbc.queryForObject(
                "SELECT count(*) FROM pg_policies WHERE tablename = 'rec_journal_entry'",
                Integer.class);
        assertThat(policies).isEqualTo(1);
    }

    @Test
    @DisplayName("per-shape failure isolation: one app's broken shape never blocks a sibling's; the retry lands it")
    void brokenShapeIsolatesAndRetries() {
        // A foreign table occupying a projection name makes applyShape's CREATE
        // fail — the "one app's bad DDL" stand-in. The doc'd contract (applyAll):
        // the failure is logged and skipped, the sibling app's projection still
        // materializes, and the next pass retries the broken shape idempotently.
        jdbc.execute("CREATE TABLE rec_broken(id uuid primary key)");
        AppDefinition healthy = DefinitionParser.parseApp(APP_JSON);
        AppDefinition broken = DefinitionParser.parseApp(BROKEN_APP_JSON);

        materializer.applyAll(java.util.List.of(healthy, broken));   // never throws

        // the sibling's projection materialized fully (table + trigger + RLS)
        Integer healthyTable = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'rec_journal_entry'",
                Integer.class);
        assertThat(healthyTable).isEqualTo(1);
        Integer triggers = jdbc.queryForObject(
                "SELECT count(DISTINCT trigger_name) FROM information_schema.triggers "
                        + "WHERE event_object_table = 'rec_records' "
                        + "AND trigger_name = 'trg_rec_journal_entry'", Integer.class);
        assertThat(triggers).isEqualTo(1);
        Integer policies = jdbc.queryForObject(
                "SELECT count(*) FROM pg_policies WHERE tablename = 'rec_journal_entry'",
                Integer.class);
        assertThat(policies).isEqualTo(1);
        // the broken shape stayed skipped: rec_broken is still the foreign table
        // (its name was never replaced by a managed projection)
        Integer foreignLeft = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'rec_broken'",
                Integer.class);
        assertThat(foreignLeft).isEqualTo(1);

        // the next pass retries the broken shape: once the obstruction is gone the
        // projection lands (idempotent reconcile, never a wedged pipeline)
        jdbc.execute("DROP TABLE rec_broken");
        materializer.applyAll(java.util.List.of(healthy, broken));
        Integer brokenNow = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'rec_broken' "
                        + "AND column_name = 'tenant_id'", Integer.class);
        assertThat(brokenNow).isEqualTo(1);   // the projection's own columns exist now
    }

    @Test
    @DisplayName("reconcile (PHASE-8 §4 item 5): a newly promoted field adds its column and index to the existing table")
    void promotedFieldAddedCreatesColumnAndIndex() {
        materializer.apply(DefinitionParser.parseApp(APP_JSON));
        // v2 adds a field to the entity's index declaration — promoted on the existing
        // projection. Before the reconcile this failed DDL forever: CREATE TABLE IF NOT
        // EXISTS was a no-op, then CREATE INDEX referenced a column that never existed.
        materializer.apply(DefinitionParser.parseApp(APP_JSON_V2_INDEXED_FIELD));

        Map<String, Object> added = jdbc.queryForMap("""
                SELECT data_type FROM information_schema.columns
                 WHERE table_name = 'rec_journal_entry' AND column_name = 'memo'""");
        assertThat(added.get("data_type")).isEqualTo("text");
        Integer indexed = jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                 WHERE tablename = 'rec_journal_entry' AND indexname = 'ix_rec_journal_entry_memo'""",
                Integer.class);
        assertThat(indexed).isEqualTo(1);
        // and the column computes over rows that predate it (stored generated backfill)
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rec_records (id, tenant_id, entity_id, created_by, updated_by, data)
                VALUES (?, ?, 'Erp.JournalEntry', ?, ?,
                 ?::jsonb)""",
                id, TENANT, ACTOR, ACTOR,
                "{\"reference\":\"JE-ADD\",\"entryDate\":\"2026-08-01\",\"status\":\"DRAFT\",\"amount\":10,\"memo\":\"hello\"}");
        assertThat(jdbc.queryForObject(
                "SELECT memo FROM rec_journal_entry WHERE id = ?", String.class, id))
                .isEqualTo("hello");
    }

    @Test
    @DisplayName("reconcile: a removed field drops its column and unique index lazily — data stays in JSONB")
    void removedFieldDropsColumnAndIndex() {
        materializer.apply(DefinitionParser.parseApp(APP_JSON));
        UUID id = UUID.randomUUID();
        insertRaw(id, "JE-GONE");

        // v3 removes the unique reference field (an acknowledged breaking change)
        materializer.apply(DefinitionParser.parseApp(APP_JSON_V3_FIELD_REMOVED));

        Integer columns = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_name = 'rec_journal_entry' AND column_name = 'reference'""",
                Integer.class);
        assertThat(columns).isZero();
        Integer uniques = jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                 WHERE tablename = 'rec_journal_entry'
                   AND indexname = 'ux_rec_journal_entry_reference_app'""",
                Integer.class);
        assertThat(uniques).isZero();
        // nothing is destroyed at publish: the value survives in the base table's JSONB
        String survived = jdbc.queryForObject(
                "SELECT data->>'reference' FROM rec_records WHERE id = ?", String.class, id);
        assertThat(survived).isEqualTo("JE-GONE");
    }

    @Test
    @DisplayName("reconcile: an acknowledged type change re-creates the column under the new type class")
    void typeChangeRecreatesColumn() {
        // amount indexed (promoted) as decimal first — the column exists as numeric
        materializer.apply(DefinitionParser.parseApp(APP_JSON_AMOUNT_INDEXED_DECIMAL));
        Map<String, Object> numericColumn = jdbc.queryForMap("""
                SELECT data_type FROM information_schema.columns
                 WHERE table_name = 'rec_journal_entry' AND column_name = 'amount'""");
        assertThat(numericColumn.get("data_type")).isEqualTo("numeric");

        // then re-typed text (breaking, acknowledged): the stale numeric generated
        // column would otherwise reject the text-class query path
        materializer.apply(DefinitionParser.parseApp(APP_JSON_V4_AMOUNT_TEXT));

        Map<String, Object> retyped = jdbc.queryForMap("""
                SELECT data_type FROM information_schema.columns
                 WHERE table_name = 'rec_journal_entry' AND column_name = 'amount'""");
        assertThat(retyped.get("data_type")).isEqualTo("text");
        // the re-added stored column recomputes from data for rows that predate it
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rec_records (id, tenant_id, entity_id, created_by, updated_by, data)
                VALUES (?, ?, 'Erp.JournalEntry', ?, ?,
                 ?::jsonb)""",
                id, TENANT, ACTOR, ACTOR,
                "{\"reference\":\"JE-T4\",\"entryDate\":\"2026-08-01\",\"status\":\"DRAFT\",\"amount\":\"12.50\"}");
        assertThat(jdbc.queryForObject(
                "SELECT amount FROM rec_journal_entry WHERE id = ?", String.class, id))
                .isEqualTo("12.50");
    }

    @Test
    @DisplayName("reconcile: an entity no published app carries retires its projection; republishing backfills it")
    void entityRemovalRetiresAndRepublishBackfills() {
        materializer.apply(DefinitionParser.parseApp(APP_JSON));
        UUID id = UUID.randomUUID();
        insertRaw(id, "JE-RETIRE");

        // an app with a different entity retires the JournalEntry projection entirely
        materializer.apply(DefinitionParser.parseApp("""
                { "apiName": "Other", "entities": [ { "apiName": "Note",
                  "displayField": "body", "fields": [ { "apiName": "body", "type": "text" } ] } ] }
                """));
        assertThat(jdbc.queryForObject("SELECT to_regclass('rec_journal_entry') IS NOT NULL",
                Boolean.class)).isFalse();
        Integer triggers = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.triggers
                 WHERE event_object_table = 'rec_records' AND trigger_name = 'trg_rec_journal_entry'""",
                Integer.class);
        assertThat(triggers).isZero();
        // the record itself is untouched — the projection was derived, rec_records is truth
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM rec_records WHERE id = ?", Integer.class, id)).isEqualTo(1);

        // rollback/re-publish (PHASE-8 §4 item 4's compatible downgrade): the projection
        // recreates and backfills from the base table
        materializer.apply(DefinitionParser.parseApp(APP_JSON));
        assertThat(jdbc.queryForObject(
                "SELECT reference FROM rec_journal_entry WHERE id = ?", String.class, id))
                .isEqualTo("JE-RETIRE");
    }

    @Test
    @DisplayName("reconcile: a shared projection survives while any published app needs it — and every app's rows sync")
    void sharedProjectionRidesTheUnion() {
        // two apps publishing the same entity apiName share one projection table
        AppDefinition erp = DefinitionParser.parseApp(APP_JSON);
        AppDefinition purch = DefinitionParser.parseApp("""
                { "apiName": "Purch", "entities": [ { "apiName": "JournalEntry",
                  "displayField": "reference",
                  "fields": [ { "apiName": "reference", "type": "text" },
                              { "apiName": "amount", "type": "decimal", "precision": 18, "scale": 4 } ] } ] }
                """);
        materializer.applyAll(List.of(erp, purch));

        UUID erpRow = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rec_records (id, tenant_id, entity_id, created_by, updated_by, data)
                VALUES (?, ?, 'Erp.JournalEntry', ?, ?,
                 ?::jsonb)""",
                erpRow, TENANT, ACTOR, ACTOR,
                "{\"reference\":\"ERP-1\",\"amount\":5}");
        UUID purchRow = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rec_records (id, tenant_id, entity_id, created_by, updated_by, data)
                VALUES (?, ?, 'Purch.JournalEntry', ?, ?,
                 ?::jsonb)""",
                purchRow, TENANT, ACTOR, ACTOR,
                "{\"reference\":\"PUR-1\",\"amount\":7}");
        assertThat(jdbc.queryForList(
                "SELECT reference FROM rec_journal_entry WHERE id IN (?, ?) ORDER BY reference",
                String.class, erpRow, purchRow)).containsExactly("ERP-1", "PUR-1");

        // the union decides, not the last publisher: entry_date is promoted only by
        // Erp, yet the union reconcile keeps it while Erp stays published — one app's
        // shape can never retire a column another published app promotes
        materializer.applyAll(List.of(erp, purch));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_name = 'rec_journal_entry' AND column_name = 'entry_date'""",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("base-table writes propagate to the projection through the trigger")
    void triggerSyncsProjection() {
        materializer.apply(DefinitionParser.parseApp(APP_JSON));
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rec_records (id, tenant_id, entity_id, created_by, updated_by, data)
                VALUES (?, ?, 'Erp.JournalEntry', ?, ?,
                 ?::jsonb)""",
                id, TENANT, ACTOR, ACTOR,
                "{\"reference\":\"JE-T1\",\"entryDate\":\"2026-08-01\",\"status\":\"DRAFT\",\"amount\":10}");

        Map<String, Object> projected = jdbc.queryForMap(
                "SELECT reference, entry_date FROM rec_journal_entry WHERE id = ?", id);
        assertThat(projected.get("reference")).isEqualTo("JE-T1");
        assertThat(projected.get("entry_date")).isEqualTo("2026-08-01");

        jdbc.update("UPDATE rec_records SET data = ?::jsonb WHERE id = ?",
                "{\"reference\":\"JE-T1b\",\"entryDate\":\"2026-08-01\",\"status\":\"DRAFT\",\"amount\":10}", id);
        assertThat(jdbc.queryForObject(
                "SELECT reference FROM rec_journal_entry WHERE id = ?", String.class, id))
                .isEqualTo("JE-T1b");
    }

    @Test
    @DisplayName("the partial unique index enforces uniqueness over live rows (soft delete frees the value)")
    void partialUniqueIndex() {
        materializer.apply(DefinitionParser.parseApp(APP_JSON));
        UUID first = UUID.randomUUID();
        insertRaw(first, "JE-DUP");
        // Same value again while the first row is live → the index rejects it.
        assertThatThrownBy(() -> insertRaw(UUID.randomUUID(), "JE-DUP"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        // Soft-delete frees the value: delete → recreate with the same value works (§6).
        jdbc.update("UPDATE rec_records SET deleted = true WHERE id = ?", first);
        insertRaw(UUID.randomUUID(), "JE-DUP");
    }

    @Test
    @DisplayName("per-app unique scoping: the same value in two same-named entities of one tenant coexists")
    void perAppUniqueScoping() {
        // Anti-regression (2026-08-31): the projection's unique index was tenant-wide
        // with no entity discriminator — two published apps defining the same entity
        // apiName with a unique field cross-collided: app B's legitimate value
        // rejected on app A's row (the app-qualified pre-check passed; the index
        // enforced across apps). The index now scopes by the App.Entity key.
        AppDefinition erp = DefinitionParser.parseApp(APP_JSON);
        AppDefinition billing = DefinitionParser.parseApp("""
                { "apiName": "Billing", "entities": [ { "apiName": "JournalEntry",
                  "displayField": "reference",
                  "fields": [ { "apiName": "reference", "type": "text", "uniqueness": true },
                              { "apiName": "amount", "type": "decimal", "precision": 18, "scale": 4 } ] } ] }
                """);
        materializer.applyAll(List.of(erp, billing));

        // the same tenant, the same unique value, two apps — both rows land
        UUID erpRow = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rec_records (id, tenant_id, entity_id, created_by, updated_by, data)
                VALUES (?, ?, 'Erp.JournalEntry', ?, ?, ?::jsonb)""",
                erpRow, TENANT, ACTOR, ACTOR, "{\"reference\":\"SHARED-1\",\"amount\":5}");
        UUID billingRow = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rec_records (id, tenant_id, entity_id, created_by, updated_by, data)
                VALUES (?, ?, 'Billing.JournalEntry', ?, ?, ?::jsonb)""",
                billingRow, TENANT, ACTOR, ACTOR, "{\"reference\":\"SHARED-1\",\"amount\":9}");
        assertThat(jdbc.queryForList("""
                        SELECT entity_id FROM rec_journal_entry WHERE id IN (?, ?) ORDER BY entity_id""",
                String.class, erpRow, billingRow))
                .containsExactly("Billing.JournalEntry", "Erp.JournalEntry");

        // within one app the value stays exclusive — the scope narrowed, not removed
        assertThatThrownBy(() -> insertRaw(UUID.randomUUID(), "SHARED-1"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        // the pre-entity_id migration leg: a projection row that lost its key
        // backfills from the base row on the next pass, then goes NOT NULL again
        jdbc.execute("ALTER TABLE rec_journal_entry ALTER COLUMN entity_id DROP NOT NULL");
        jdbc.update("UPDATE rec_journal_entry SET entity_id = NULL");
        materializer.apply(erp);
        Integer nullKeys = jdbc.queryForObject(
                "SELECT count(*) FROM rec_journal_entry WHERE entity_id IS NULL", Integer.class);
        assertThat(nullKeys).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT entity_id FROM rec_journal_entry WHERE id = ?",
                String.class, erpRow)).isEqualTo("Erp.JournalEntry");
    }

    @Test
    @DisplayName("a reconcile pass holds the cluster-wide advisory lock — a concurrent pass waits, never interleaves")
    void passLockSerializesConcurrentReconciles() throws Exception {
        materializer.apply(DefinitionParser.parseApp(APP_JSON));

        // An external holder of the pass lock (another replica's pass, mid-flight)
        // must block this pass: Postgres's CREATE … IF NOT EXISTS is not atomic
        // against a concurrent creator, so interleaved passes abort on a pg_class
        // duplicate key and skip the shape until an unpromised next publish.
        try (java.sql.Connection holder = jdbc.getDataSource().getConnection();
             java.sql.Statement statement = holder.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(" + Materializer.PASS_LOCK_KEY + ")");

            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                java.util.concurrent.Future<?> pass = pool.submit(() ->
                        materializer.applyAll(List.of(DefinitionParser.parseApp(APP_JSON))));
                // blocked behind the holder — it must not finish while the lock is held
                assertThatThrownBy(() -> pass.get(750, java.util.concurrent.TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                statement.execute("SELECT pg_advisory_unlock(" + Materializer.PASS_LOCK_KEY + ")");
                pass.get(30, java.util.concurrent.TimeUnit.SECONDS);

                // the released pass ran to completion: the projection is whole
                Integer triggers = jdbc.queryForObject("""
                        SELECT count(DISTINCT trigger_name) FROM information_schema.triggers
                         WHERE event_object_table = 'rec_records'
                           AND trigger_name = 'trg_rec_journal_entry'""",
                        Integer.class);
                assertThat(triggers).isEqualTo(1);
                Integer updatedIndex = jdbc.queryForObject("""
                        SELECT count(*) FROM pg_indexes
                         WHERE tablename = 'rec_journal_entry' AND indexname = 'ix_rec_journal_entry_updated'""",
                        Integer.class);
                assertThat(updatedIndex).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private void insertRaw(UUID id, String reference) {
        jdbc.update("""
                INSERT INTO rec_records (id, tenant_id, entity_id, created_by, updated_by, data)
                VALUES (?, ?, 'Erp.JournalEntry', ?, ?,
                 ?::jsonb)""",
                id, TENANT, ACTOR, ACTOR,
                "{\"reference\":\"" + reference + "\",\"entryDate\":\"2026-08-01\",\"status\":\"DRAFT\",\"amount\":1}");
    }
}
