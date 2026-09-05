package com.novaforge.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Round-trip and shape tests for definition POJOs (PHASE-1 T1 AC). */
class MetadataModelRoundTripTest {

    /** The ARCHITECTURE.md §3 JournalEntry example, as authored JSON. */
    static final String JOURNAL_APP = """
            {
              "apiName": "Erp",
              "label": "ERP",
              "settings": {
                "sequences": [
                  { "apiName": "entryNumber", "mode": "gapless", "start": 1,
                    "prefix": "JE-", "padding": 6 }
                ]
              },
              "entities": [
                {
                  "apiName": "JournalEntry",
                  "label": "Journal Entry",
                  "displayField": "reference",
                  "fields": [
                    { "apiName": "reference", "type": "text", "length": 32, "required": true,
                      "uniqueness": true,
                      "default": { "sequence": "entryNumber" } },
                    { "apiName": "entryDate", "type": "date", "required": true },
                    { "apiName": "status", "type": "enum", "values": ["DRAFT", "POSTED"] },
                    { "apiName": "periodId", "type": "lookup", "target": "AccountingPeriod" },
                    { "apiName": "totalDebit", "type": "decimal", "precision": 18, "scale": 4,
                      "rollup": "SUM(lines.debit)" },
                    { "apiName": "totalCredit", "type": "decimal", "precision": 18, "scale": 4,
                      "rollup": "SUM(lines.credit)" }
                  ],
                  "relationships": [
                    { "apiName": "lines", "type": "child", "target": "JournalLine",
                      "cascadeDelete": true }
                  ],
                  "validations": [
                    { "name": "balanced", "scope": "record",
                      "expression": "totalDebit == totalCredit",
                      "message": "Entry must balance" }
                  ],
                  "indexes": [ { "fields": ["entryDate"], "unique": false } ]
                },
                {
                  "apiName": "JournalLine",
                  "fields": [
                    { "apiName": "entryId", "type": "lookup", "target": "JournalEntry",
                      "required": true },
                    { "apiName": "debit", "type": "decimal", "precision": 18, "scale": 4 },
                    { "apiName": "credit", "type": "decimal", "precision": 18, "scale": 4 }
                  ]
                },
                {
                  "apiName": "AccountingPeriod",
                  "displayField": "name",
                  "fields": [ { "apiName": "name", "type": "text" } ]
                }
              ]
            }
            """;

    @Test
    @DisplayName("ARCHITECTURE §3 JournalEntry example parses and validates clean")
    void journalExampleValid() {
        AppDefinition app = DefinitionParser.parseApp(JOURNAL_APP);
        assertThat(app.apiName()).isEqualTo("Erp");
        assertThat(app.entities()).hasSize(3);
        assertThat(DefinitionValidator.validate(app).isEmpty())
                .as("definition is save-clean").isTrue();
    }

    @Test
    @DisplayName("parsed model round-trips through JSON with camelCase + label_i18n names")
    void roundTrip() {
        AppDefinition app = DefinitionParser.parseApp(JOURNAL_APP);
        String json = DefinitionParser.writeApp(app);
        AppDefinition reparsed = DefinitionParser.parseApp(json);

        assertThat(reparsed).isEqualTo(app);

        assertThat(json).contains("\"apiName\"").contains("\"displayField\"")
                .contains("\"cascadeDelete\"");
        EntityDefinition entry = reparsed.entity("JournalEntry").orElseThrow();
        FieldDefinition reference = entry.field("reference").orElseThrow();
        assertThat(reference.defaultValue())
                .isEqualTo(new DefaultValue.SequenceReference("entryNumber"));
        assertThat(reference.length()).isEqualTo(32);
        assertThat(entry.relationship("lines").orElseThrow().cascadeOn()).isTrue();
        assertThat(entry.validations()).hasSize(1);
        assertThat(entry.indexes()).hasSize(1);
    }

    @Test
    @DisplayName("tenant branding parses, round-trips, and stays absent when unbranded")
    void brandingRoundTrip() {
        // an unbranded app serializes with no branding key at all (NON_NULL) —
        // pre-branding documents stay byte-identical through the upgrade
        AppDefinition plain = DefinitionParser.parseApp(JOURNAL_APP);
        assertThat(plain.branding()).isNull();
        assertThat(DefinitionParser.writeApp(plain)).doesNotContain("branding");

        AppDefinition branded = DefinitionParser.parseApp("""
                {
                  "apiName": "Erp",
                  "branding": { "accent": "#7c3aed", "accentContrast": "#ffffff" },
                  "entities": []
                }
                """);
        assertThat(branded.branding())
                .isEqualTo(new BrandingDefinition("#7c3aed", "#ffffff"));

        String json = DefinitionParser.writeApp(branded);
        assertThat(json).contains("\"branding\"");
        assertThat(DefinitionParser.parseApp(json)).isEqualTo(branded);
    }

    @Test
    @DisplayName("sequence formatting honors prefix/padding/suffix")
    void sequenceFormatting() {
        SequenceDefinition seq = new SequenceDefinition("entryNumber", SequenceMode.GAPLESS,
                1L, "JE-", null, 6);
        assertThat(seq.format(42)).isEqualTo("JE-000042");
        assertThat(new SequenceDefinition("s", null, null, null, "-X", 0).format(7)).isEqualTo("7-X");
        assertThat(new SequenceDefinition("s", null, 100L, null, null, null).startOrOne()).isEqualTo(100);
        assertThat(new SequenceDefinition("s", null, null, null, null, null).modeOrDefault())
                .isEqualTo(SequenceMode.CACHED);
    }

    @Test
    @DisplayName("label_i18n maps survive round-trip")
    void i18nRoundTrip() {
        String json = """
                { "apiName": "Erp", "label_i18n": { "de": "Buchhaltung" },
                  "entities": [ { "apiName": "E", "label_i18n": { "fr": "Entité" },
                                  "fields": [ { "apiName": "f", "type": "text", "label_i18n": { "de": "Feld" } } ] } ] }
                """;
        AppDefinition app = DefinitionParser.parseApp(json);
        assertThat(app.labelI18n()).containsEntry("de", "Buchhaltung");
        EntityDefinition entity = app.entities().getFirst();
        assertThat(entity.labelI18n()).containsEntry("fr", "Entité");
        assertThat(entity.fields().getFirst().labelI18n()).containsEntry("de", "Feld");

        AppDefinition back = DefinitionParser.parseApp(DefinitionParser.writeApp(app));
        assertThat(back.labelI18n()).isEqualTo(app.labelI18n());
    }

    @Test
    @DisplayName("default parses both authored forms and rejects any other shape")
    void defaultForms() {
        var mapper = DefinitionParser.mapper();
        FieldDefinition staticDefault = mapper.readValue(
                "{\"apiName\":\"f\",\"type\":\"int\",\"default\":{\"value\":3}}", FieldDefinition.class);
        assertThat(staticDefault.defaultValue()).isEqualTo(new DefaultValue.Static(3));

        FieldDefinition seqDefault = mapper.readValue(
                "{\"apiName\":\"f\",\"type\":\"text\",\"default\":{\"sequence\":\"seqName\"}}",
                FieldDefinition.class);
        assertThat(seqDefault.defaultValue()).isEqualTo(new DefaultValue.SequenceReference("seqName"));

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> mapper.readValue(
                        "{\"apiName\":\"f\",\"type\":\"text\",\"default\":{\"bogus\":1}}",
                        FieldDefinition.class))
                .hasMessageContaining("default must be");
    }

    @Test
    @DisplayName("field-type registry covers the 21 v1 types and rejects unknowns")
    void fieldTypeRegistry() {
        assertThat(FieldType.values()).hasSize(21);
        assertThat(FieldType.fromWireName("money")).contains(FieldType.MONEY);
        assertThat(FieldType.fromWireName("blob")).isEmpty();
        assertThat(FieldType.LOOKUP.relationshipLike()).isTrue();
        assertThat(FieldType.TEXT.textual()).isTrue();
        assertThat(FieldType.DECIMAL.numeric()).isTrue();
        assertThat(FieldType.MONEY.numeric()).isTrue();
    }
}
