package com.novaforge.runtime.storage.materializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.testsupport.PostgresTestBase;
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
        assertThat(uniques).isEqualTo(1);   // uniqueness on reference (§6 partial unique)
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

    private void insertRaw(UUID id, String reference) {
        jdbc.update("""
                INSERT INTO rec_records (id, tenant_id, entity_id, created_by, updated_by, data)
                VALUES (?, ?, 'Erp.JournalEntry', ?, ?,
                 ?::jsonb)""",
                id, TENANT, ACTOR, ACTOR,
                "{\"reference\":\"" + reference + "\",\"entryDate\":\"2026-08-01\",\"status\":\"DRAFT\",\"amount\":1}");
    }
}
