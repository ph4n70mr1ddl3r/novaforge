package com.novaforge.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import com.novaforge.common.error.ProblemErrors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The PHASE-1 §3 save-validation rule matrix — every rule, one negative test each. */
class DefinitionValidatorTest {

    private static AppDefinition baseApp() {
        return DefinitionParser.parseApp(MetadataModelRoundTripTest.JOURNAL_APP);
    }

    private static ProblemErrors validate(AppDefinition app) {
        return DefinitionValidator.validate(app);
    }

    private static boolean mentions(ProblemErrors errors, String fragment) {
        String all = errors.errors().stream().map(ProblemErrors.FieldError::message)
                .reduce("", (a, b) -> a + b);
        return all.contains(fragment);
    }

    @Test
    @DisplayName("baseline app is valid")
    void baselineValid() {
        assertThat(validate(baseApp()).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("rule: entity apiName PascalCase")
    void entityPascalCase() {
        AppDefinition app = baseApp();
        AppDefinition broken = new AppDefinition(app.id(), "Erp", app.label(), app.labelI18n(),
                app.description(),
                java.util.List.of(EntityDefinition.copyWithApiName(app.entities().getFirst(), "journal_entry")),
                app.pages(), app.settings());
        assertThat(mentions(validate(broken), "PascalCase")).isTrue();
    }

    @Test
    @DisplayName("rule: entity apiName unique per app")
    void entityUnique() {
        AppDefinition app = baseApp();
        AppDefinition broken = new AppDefinition(app.id(), app.apiName(), app.label(), app.labelI18n(),
                app.description(),
                java.util.List.of(app.entities().getFirst(),
                        EntityDefinition.copyWithApiName(app.entities().get(1), "JournalEntry")),
                app.pages(), app.settings());
        assertThat(mentions(validate(broken), "unique per app")).isTrue();
    }

    @Test
    @DisplayName("rule: field apiName camelCase")
    void fieldCamelCase() {
        AppDefinition app = baseApp();
        EntityDefinition entry = app.entities().getFirst();
        EntityDefinition broken = EntityDefinition.copyWithField(entry, 0,
                new FieldDefinition("EntryDate", null, null, FieldType.DATE, null, null, null,
                        null, null, null, null, null, null, null, null, null, null));
        assertThat(mentions(validate(withEntity(app, broken)), "camelCase")).isTrue();
    }

    @Test
    @DisplayName("rule: field apiName unique per entity")
    void fieldUnique() {
        AppDefinition app = baseApp();
        EntityDefinition entry = app.entities().getFirst();
        EntityDefinition broken = EntityDefinition.copyWithField(entry, 1,
                entry.fields().getFirst());
        assertThat(mentions(validate(withEntity(app, broken)), "unique per entity")).isTrue();
    }

    @Test
    @DisplayName("rule: reserved system field names rejected")
    void reservedNames() {
        AppDefinition app = baseApp();
        EntityDefinition entry = app.entities().getFirst();
        EntityDefinition broken = EntityDefinition.copyWithField(entry, 0,
                new FieldDefinition("version", null, null, FieldType.INT, null, null, null,
                        null, null, null, null, null, null, null, null, null, null));
        assertThat(mentions(validate(withEntity(app, broken)), "reserved")).isTrue();
    }

    @Test
    @DisplayName("rule: relationship target resolves within the app")
    void relationshipTargetResolves() {
        AppDefinition app = baseApp();
        EntityDefinition entry = app.entities().getFirst();
        EntityDefinition broken = EntityDefinition.copyWithRelationship(entry, 0,
                new RelationshipDefinition("lines", RelationshipType.CHILD, "NoSuchEntity", true));
        assertThat(mentions(validate(withEntity(app, broken)), "relationship target must resolve")).isTrue();
    }

    @Test
    @DisplayName("rule: child relationship requires the target to declare a lookup back")
    void childRequiresLookupBack() {
        AppDefinition app = baseApp();
        EntityDefinition line = app.entities().get(1); // JournalLine
        EntityDefinition broken = EntityDefinition.copyWithField(line, 0,
                FieldDefinition.of("somethingElse", FieldType.TEXT));
        assertThat(mentions(validate(withEntity(app, broken)), "declare a lookup field")).isTrue();
    }

    @Test
    @DisplayName("rule: displayField exists")
    void displayFieldExists() {
        AppDefinition app = baseApp();
        EntityDefinition entry = app.entities().getFirst();
        EntityDefinition broken = EntityDefinition.copyWith(entry, e -> new EntityDefinition(
                e.id(), e.apiName(), e.label(), e.labelI18n(), "nope", e.module(),
                e.fields(), e.relationships(), e.validations(), e.indexes()));
        assertThat(mentions(validate(withEntity(app, broken)), "displayField must name an existing field")).isTrue();
    }

    @Test
    @DisplayName("rule: enum values non-empty")
    void enumValuesNonEmpty() {
        AppDefinition app = baseApp();
        EntityDefinition entry = app.entities().getFirst();
        FieldDefinition status = entry.fields().get(2);
        EntityDefinition broken = EntityDefinition.copyWithField(entry, 2,
                new FieldDefinition(status.apiName(), null, null, FieldType.ENUM, null, null, null,
                        null, null, null, null, null, null, java.util.List.of(), null, null, null));
        assertThat(mentions(validate(withEntity(app, broken)), "non-empty")).isTrue();
    }

    @Test
    @DisplayName("rule: decimal precision/scale invalid pairs rejected")
    void precisionScale() {
        AppDefinition app = baseApp();
        EntityDefinition entry = app.entities().getFirst();
        FieldDefinition date = entry.fields().get(1);
        EntityDefinition broken = EntityDefinition.copyWithField(entry, 1,
                new FieldDefinition(date.apiName(), null, null, FieldType.DECIMAL, null, null, null,
                        null, 4, 6, null, null, null, null, null, null, null));
        assertThat(mentions(validate(withEntity(app, broken)), "scale must be")).isTrue();

        FieldDefinition moneyBroken = new FieldDefinition("amount", null, null, FieldType.MONEY,
                null, null, null, null, 9, 2, null, null, null, null, null, null, null);
        EntityDefinition withMoney = EntityDefinition.copyWithField(entry, 1, moneyBroken);
        assertThat(mentions(validate(withEntity(app, withMoney)), "money requires decimal(18,4)")).isTrue();
    }

    @Test
    @DisplayName("rule: index fields exist")
    void indexFieldsExist() {
        AppDefinition app = baseApp();
        EntityDefinition entry = app.entities().getFirst();
        EntityDefinition broken = EntityDefinition.copyWith(entry, e -> new EntityDefinition(
                e.id(), e.apiName(), e.label(), e.labelI18n(), e.displayField(), e.module(),
                e.fields(), e.relationships(), e.validations(),
                java.util.List.of(new EntityDefinition.IndexDefinition(java.util.List.of("nope"), false))));
        assertThat(mentions(validate(withEntity(app, broken)), "index field must exist")).isTrue();
    }

    @Test
    @DisplayName("rule: default sequence references resolve within the app")
    void sequenceReferenceResolves() {
        AppDefinition app = baseApp();
        EntityDefinition entry = app.entities().getFirst();
        FieldDefinition reference = entry.fields().getFirst();
        EntityDefinition broken = EntityDefinition.copyWithField(entry, 0,
                new FieldDefinition(reference.apiName(), null, null, FieldType.TEXT, null, null, null,
                        null, null, null, null, new DefaultValue.SequenceReference("missing"), null,
                        null, null, null, null));
        assertThat(mentions(validate(withEntity(app, broken)), "sequence reference must resolve")).isTrue();
    }

    @Test
    @DisplayName("rule: sequence defaults only on text/uuid fields")
    void sequenceRequiresTextField() {
        AppDefinition app = baseApp();
        EntityDefinition entry = app.entities().getFirst();
        EntityDefinition broken = EntityDefinition.copyWithField(entry, 0,
                new FieldDefinition("number", null, null, FieldType.INT, null, null, null,
                        null, null, null, null, new DefaultValue.SequenceReference("entryNumber"), null,
                        null, null, null, null));
        assertThat(mentions(validate(withEntity(app, broken)), "text or uuid field")).isTrue();
    }

    @Test
    @DisplayName("rule: lookup targets resolve within the app")
    void lookupTargetResolves() {
        AppDefinition app = baseApp();
        EntityDefinition entry = app.entities().getFirst();
        EntityDefinition broken = EntityDefinition.copyWithField(entry, 3,
                FieldDefinition.of("periodId", FieldType.LOOKUP));
        assertThat(mentions(validate(withEntity(app, broken)), "lookup target must resolve")).isTrue();
    }

    private static AppDefinition withEntity(AppDefinition app, EntityDefinition entity) {
        return new AppDefinition(app.id(), app.apiName(), app.label(), app.labelI18n(),
                app.description(),
                app.entities().stream().map(e -> e.apiName().equals(entity.apiName()) ? entity : e)
                        .toList(),
                app.pages(), app.settings());
    }

    // --- state machines (PHASE-4 §3) ---

    private static AppDefinition withMachine(AppDefinition app, String machineJson) {
        return new AppDefinition(app.id(), app.apiName(), app.label(), app.labelI18n(),
                app.description(), app.entities(), app.pages(), app.settings(),
                app.permissionSet(), app.testSuites(),
                java.util.List.of(DefinitionParser.parse(machineJson, StateMachineDefinition.class)));
    }

    private static final String VALID_MACHINE = """
            { "id": "sm_journal", "entity": "JournalEntry", "stateField": "status",
              "initial": "DRAFT",
              "states": [ { "name": "DRAFT" }, { "name": "POSTED", "terminal": true } ],
              "transitions": [ { "from": "DRAFT", "to": "POSTED" } ] }
            """;

    @Test
    @DisplayName("rule: a well-formed state machine validates (and binds an enum field)")
    void machineValid() {
        assertThat(validate(withMachine(baseApp(), VALID_MACHINE)).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("rule: stateField must be an enum field on the bound entity")
    void machineStateFieldMustBeEnum() {
        AppDefinition broken = withMachine(baseApp(), """
                { "id": "sm_x", "entity": "JournalEntry", "stateField": "memo",
                  "initial": "DRAFT", "states": [ { "name": "DRAFT" } ], "transitions": [] }
                """);
        assertThat(mentions(validate(broken), "stateField must be an enum field")).isTrue();
    }

    @Test
    @DisplayName("rule: initial must be one of the machine's states")
    void machineInitialKnown() {
        AppDefinition broken = withMachine(baseApp(), """
                { "id": "sm_x", "entity": "JournalEntry", "stateField": "status",
                  "initial": "NOWHERE",
                  "states": [ { "name": "DRAFT" } ], "transitions": [] }
                """);
        assertThat(mentions(validate(broken), "initial must be one of")).isTrue();
    }

    @Test
    @DisplayName("rule: transitions must reference known states")
    void machineTransitionsKnown() {
        AppDefinition broken = withMachine(baseApp(), """
                { "id": "sm_x", "entity": "JournalEntry", "stateField": "status",
                  "initial": "DRAFT", "states": [ { "name": "DRAFT" } ],
                  "transitions": [ { "from": "DRAFT", "to": "GHOST" } ] }
                """);
        assertThat(mentions(validate(broken), "reference known states")).isTrue();
    }

    @Test
    @DisplayName("rule: terminal states admit no outgoing transitions")
    void machineTerminalNoOutgoing() {
        AppDefinition broken = withMachine(baseApp(), """
                { "id": "sm_x", "entity": "JournalEntry", "stateField": "status",
                  "initial": "DRAFT",
                  "states": [ { "name": "DRAFT" }, { "name": "POSTED", "terminal": true } ],
                  "transitions": [ { "from": "DRAFT", "to": "POSTED" },
                                   { "from": "POSTED", "to": "DRAFT" } ] }
                """);
        assertThat(mentions(validate(broken), "admits no outgoing")).isTrue();
    }

    @Test
    @DisplayName("rule: one state machine per entity in v1")
    void machineOnePerEntity() {
        AppDefinition app = withMachine(baseApp(), VALID_MACHINE);
        AppDefinition twice = new AppDefinition(app.id(), app.apiName(), app.label(),
                app.labelI18n(), app.description(), app.entities(), app.pages(), app.settings(),
                app.permissionSet(), app.testSuites(),
                java.util.List.of(DefinitionParser.parse(VALID_MACHINE, StateMachineDefinition.class),
                        DefinitionParser.parse(VALID_MACHINE, StateMachineDefinition.class)));
        assertThat(mentions(validate(twice), "one state machine per entity")).isTrue();
    }

    @Test
    @DisplayName("rule: states must be values of the enum field")
    void machineStatesWithinEnum() {
        AppDefinition broken = withMachine(baseApp(), """
                { "id": "sm_x", "entity": "JournalEntry", "stateField": "status",
                  "initial": "DRAFT", "states": [ { "name": "DRAFT" }, { "name": "FLYING" } ],
                  "transitions": [] }
                """);
        assertThat(mentions(validate(broken), "value of the enum field")).isTrue();
    }
}
