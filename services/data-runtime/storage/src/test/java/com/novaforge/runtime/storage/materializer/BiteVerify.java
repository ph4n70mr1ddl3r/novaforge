// Test-only probe: the merge() collision scenario against a fresh, empty
// database, driving the Materializer directly (no pre-existing index) so the
// pre-fix convergence is observable regardless of any leftover live index.
// Lives in test scope only; CI runs it beside MaterializerTests (testcontainers).
package com.novaforge.runtime.storage.materializer;

import static org.assertj.core.api.Assertions.assertThat;

import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class BiteVerify {

    private static JdbcTemplate jdbc;
    private static Materializer materializer;

    @BeforeAll
    static void setup() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:postgresql://127.0.0.1:5433/novaforge_test", "novaforge", "novaforge");
        jdbc = new JdbcTemplate(dataSource);
        try (Connection admin = java.sql.DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5433/postgres", "novaforge", "novaforge");
             Statement statement = admin.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS novaforge_test");
            statement.executeUpdate("CREATE DATABASE novaforge_test");
        }
        org.flywaydb.core.Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        materializer = new Materializer(jdbc);
    }

    private static final String APP_A = """
            { "apiName": "Erp",
              "entities": [ { "apiName": "JournalEntry",
                "displayField": "reference",
                "fields": [
                  { "apiName": "reference", "type": "text", "uniqueness": true },
                  { "apiName": "dueDate", "type": "date" },
                  { "apiName": "status", "type": "enum", "values": ["DRAFT", "POSTED"] } ],
                "indexes": [ { "fields": ["dueDate"] } ] } ] }
            """;

    private static final String APP_B = """
            { "apiName": "Purch", "entities": [ { "apiName": "JournalEntry",
              "displayField": "reference",
              "fields": [
                { "apiName": "reference", "type": "text" },
                { "apiName": "dueDate", "type": "date" },
                { "apiName": "status", "type": "enum", "values": ["OPEN", "CLOSED"] } ],
              "indexes": [ { "fields": ["due", "Date"], "unique": false } ] } ] }
            """;

    @Test
    void collidingDeclarationsConvergeOntoOneIndexName() {
        AppDefinition a = DefinitionParser.parseApp(APP_A);
        AppDefinition b = DefinitionParser.parseApp(APP_B);
        // B publishes first against a FRESH database (setup dropped it): its JSONB
        // index claims the base name. Then A publishes: its promoted-column
        // declaration [dueDate] lowers to the same managed name — before the fix
        // the merge guard converged A onto B's name, so A's CREATE INDEX IF NOT
        // EXISTS silently kept B's index and exactly ONE managed index existed.
        materializer.applyAll(List.of(b));
        materializer.applyAll(List.of(b, a));
        Integer managed = jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                 WHERE tablename = 'rec_journal_entry'
                   AND indexname LIKE 'ix_rec_journal_entry_due_date%'""",
                Integer.class);
        // the collision must land two indexes — one per declaration's target.
        // (Pre-fix this asserted 2 against a live 1 and failed.)
        assertThat(managed).isEqualTo(2);
    }
}
