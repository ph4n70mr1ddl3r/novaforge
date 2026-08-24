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

    // --- BPMN workflows (PHASE-4 §9) ---

    private static AppDefinition withWorkflow(AppDefinition app, WorkflowDefinition workflow) {
        return new AppDefinition(app.id(), app.apiName(), app.label(), app.labelI18n(),
                app.description(), app.entities(), app.pages(), app.settings(),
                app.permissionSet(), app.testSuites(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(workflow));
    }

    private static final String VALID_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:flowable="http://flowable.org/bpmn"
                         targetNamespace="novaforge">
              <process id="journal_review" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="f1" sourceRef="start" targetRef="review"/>
                <userTask id="review" name="Review" flowable:candidateGroups="accountant"/>
                <sequenceFlow id="f2" sourceRef="review" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """;

    private static WorkflowDefinition workflow(String id, String bpmn,
                                               WorkflowDefinition.EventStart... starts) {
        return new WorkflowDefinition(id, bpmn, java.util.List.of(starts));
    }

    @Test
    @DisplayName("rule: a well-formed BPMN workflow with an event-start validates")
    void workflowValid() {
        WorkflowDefinition valid = workflow("journal_review", VALID_BPMN,
                new WorkflowDefinition.EventStart("record.updated", "JournalEntry",
                        "status = 'DRAFT'"));
        assertThat(validate(withWorkflow(baseApp(), valid)).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("rule: the BPMN <process id> must equal the workflow id")
    void workflowProcessIdMatches() {
        WorkflowDefinition mismatch = workflow("other_key", VALID_BPMN);
        assertThat(mentions(validate(withWorkflow(baseApp(), mismatch)),
                "must equal the workflow id")).isTrue();
    }

    @Test
    @DisplayName("rule: malformed BPMN XML rejects")
    void workflowMalformedXml() {
        WorkflowDefinition broken = workflow("journal_review",
                "<definitions><process id=\"journal_review\">");
        assertThat(mentions(validate(withWorkflow(baseApp(), broken)), "well-formed XML")).isTrue();
    }

    @Test
    @DisplayName("rule: DOCTYPE in BPMN rejects — XXE-hardened parse (authored input)")
    void workflowDoctypeRejected() {
        WorkflowDefinition doctype = workflow("journal_review", VALID_BPMN.replace(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<!DOCTYPE definitions [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"));
        assertThat(mentions(validate(withWorkflow(baseApp(), doctype)), "well-formed XML")).isTrue();
    }

    @Test
    @DisplayName("rule: blank BPMN source rejects")
    void workflowBlankBpmn() {
        WorkflowDefinition blank = workflow("journal_review", "   ");
        assertThat(mentions(validate(withWorkflow(baseApp(), blank)), "requires BPMN XML")).isTrue();
    }

    @Test
    @DisplayName("rule: exactly one <process> per document")
    void workflowSingleProcess() {
        WorkflowDefinition two = workflow("journal_review",
                VALID_BPMN.replace("</process>", "</process>\n<process id=\"spare\"/>"));
        assertThat(mentions(validate(withWorkflow(baseApp(), two)),
                "exactly one <process>")).isTrue();
    }

    @Test
    @DisplayName("rule: workflow ids unique per app and shaped as process keys")
    void workflowIdRules() {
        WorkflowDefinition valid = workflow("journal_review", VALID_BPMN);
        AppDefinition app = withWorkflow(baseApp(), valid);
        AppDefinition duplicate = new AppDefinition(app.id(), app.apiName(), app.label(),
                app.labelI18n(), app.description(), app.entities(), app.pages(), app.settings(),
                app.permissionSet(), app.testSuites(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(valid, valid));
        assertThat(mentions(validate(duplicate), "unique per app")).isTrue();

        assertThat(mentions(validate(withWorkflow(baseApp(), workflow("9bad key!", "<x/>"))),
                "process key")).isTrue();
    }

    @Test
    @DisplayName("rule: event-start events come from the closed set and bind app entities")
    void workflowEventStartRules() {
        WorkflowDefinition unknownEvent = workflow("journal_review", VALID_BPMN,
                new WorkflowDefinition.EventStart("record.archived", "JournalEntry", null));
        assertThat(mentions(validate(withWorkflow(baseApp(), unknownEvent)),
                "event-start event must be one of")).isTrue();

        WorkflowDefinition unknownEntity = workflow("journal_review", VALID_BPMN,
                new WorkflowDefinition.EventStart("record.updated", "Ghost", null));
        assertThat(mentions(validate(withWorkflow(baseApp(), unknownEntity)),
                "event-start must bind to an entity of the app")).isTrue();
    }

    // --- PHASE-5 §3/§5/§7: reports, dashboards, report jobs ---

    /** An A/R-shaped app: every report surface promoted (display + indexed fields). */
    private static final String REPORTING_APP = """
            {
              "apiName": "ArDesk",
              __BRANCHES__
              "permissionSet": {
                "roles": [ { "name": "reporting" }, { "name": "clerk" } ],
                "objectPermissions": [
                  { "role": "reporting", "entity": "Invoice", "read": true },
                  { "role": "clerk", "entity": "Invoice", "read": true } ]
              },
              "entities": [
                { "apiName": "Invoice",
                  "displayField": "customer",
                  "fields": [
                    { "apiName": "customer", "type": "text" },
                    { "apiName": "status", "type": "enum", "values": ["DRAFT","POSTED"] },
                    { "apiName": "dueDate", "type": "date" },
                    { "apiName": "amount", "type": "decimal", "precision": 18, "scale": 4 },
                    { "apiName": "memo", "type": "text" } ],
                  "indexes": [ { "fields": ["status", "dueDate", "amount"] } ] } ]
            }
            """;

    /**
     * Splices report/dashboard/job branches into the A/R app at the marker. Branch
     * fragments are authored as {@code ", \"reports\": […]"} continuations; the splice
     * normalizes them to plain properties so the template stays one valid document
     * for the empty and non-empty cases alike.
     */
    private static AppDefinition reportingApp(String branches) {
        return reportingApp(branches, REPORTING_APP);
    }

    /** Negative cases rewrite the app template itself (e.g. strip a declared role). */
    private static AppDefinition reportingApp(String branches, String appTemplate) {
        String spliced = branches == null ? "" : branches.strip();
        if (spliced.startsWith(",")) {
            spliced = spliced.substring(1).strip();
        }
        if (!spliced.isEmpty()) {
            spliced = spliced + ",";
        }
        return DefinitionParser.parseApp(appTemplate.replace("__BRANCHES__", spliced));
    }

    private static final String VALID_REPORT = """
            , "reports": [
                { "id": "arAging", "entity": "Invoice",
                  "filters": [ { "field": "status", "op": "eq", "value": "POSTED" } ],
                  "groupBy": [
                    { "field": "customer" },
                    { "field": "dueDate", "buckets": [
                      { "label": "current", "expression": "today() - dueDate < 0" },
                      { "label": "60+", "expression": "today() - dueDate > 60" } ] } ],
                  "aggregates": [ { "op": "sum", "field": "amount", "alias": "outstanding" } ] } ]
            """;

    @Test
    @DisplayName("rule: a promoted-field report with buckets validates save-clean")
    void validReportPasses() {
        assertThat(validate(reportingApp(VALID_REPORT)).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("rule: unpromoted group-by/aggregate fields reject with guidance")
    void unpromotedFieldsReject() {
        // memo exists on Invoice but rides no index/display/lookup — the unpromoted case
        String unpromoted = VALID_REPORT.replace("\"field\": \"customer\"", "\"field\": \"memo\"");
        assertThat(mentions(validate(reportingApp(unpromoted)),
                "group-by fields must be projection-promoted")).isTrue();
        String unpromotedSum = VALID_REPORT.replace("\"op\": \"sum\", \"field\": \"amount\"",
                "\"op\": \"sum\", \"field\": \"status\"");
        assertThat(mentions(validate(reportingApp(unpromotedSum)),
                "aggregate fields must be numeric")).isTrue();
    }

    @Test
    @DisplayName("rule: bucket labels unique and non-empty; dashboards resolve report refs")
    void bucketAndDashboardRules() {
        String dupLabels = VALID_REPORT.replace("\"60+\"", "\"current\"");
        assertThat(mentions(validate(reportingApp(dupLabels)),
                "bucket labels must be unique")).isTrue();

        String dashboard = VALID_REPORT + """
            , "dashboards": [
                    { "id": "exec", "widgets": [
                      { "widget": "kpi", "reportRef": "ghost", "span": 4 } ] } ]
            """;
        assertThat(mentions(validate(reportingApp(dashboard)),
                "reportRef must resolve")).isTrue();
    }

    @Test
    @DisplayName("rule: report jobs pin their report, default runAsRole, and recipients")
    void reportJobRules() {
        String job = VALID_REPORT + """
            , "jobs": [
                    { "name": "nightlyAging", "cron": "0 0 6 * * *", "target": "report",
                      "params": { "reportId": "arAging",
                        "recipients": { "roles": ["reporting"] } } } ]
            """;
        assertThat(validate(reportingApp(job)).isEmpty()).isTrue();

        assertThat(mentions(validate(reportingApp(
                job.replace("\"reportId\": \"arAging\"", "\"reportId\": \"ghost\""))),
                "must reference a report")).isTrue();
        assertThat(mentions(validate(reportingApp(
                job.replace("{ \"roles\": [\"reporting\"] }", "{}"))),
                "at least one recipient")).isTrue();
        assertThat(mentions(validate(reportingApp(job
                .replace("\"roles\": [\"reporting\"]", "\"roles\": [\"ghost\"]"))),
                "recipient role must resolve")).isTrue();
        assertThat(mentions(validate(reportingApp(
                job.replace("{ \"roles\": [\"reporting\"] }", "{ \"users\": [\"not-a-uuid\"] }"))),
                "recipient users must be uuids")).isTrue();
        // the default runAsRole must resolve: strip the reporting role from the app and
        // the job rejects
        assertThat(mentions(validate(reportingApp(job,
                REPORTING_APP.replace("{ \"name\": \"reporting\" }, ", ""))),
                "default runAsRole")).isTrue();
    }

    // --- integrations (PHASE-6 §3/§5/§6/§7/§9) ---

    private static AppDefinition withIntegrations(AppDefinition app, String json) {
        IntegrationsDefinition integrations =
                DefinitionParser.parse(json, IntegrationsDefinition.class);
        return new AppDefinition(app.id(), app.apiName(), app.label(), app.labelI18n(),
                app.description(), app.entities(), app.pages(), app.settings(),
                app.permissionSet(), app.testSuites(), app.stateMachines(), app.slas(),
                app.jobs(), app.workflows(), app.reports(), app.dashboards(), integrations);
    }

    private static final String INTEGRATIONS_APP = """
            { "connectors": [
                { "id": "con_stripe", "type": "rest", "baseUrl": "https://api.stripe.com/v1",
                  "credential": "cred_stripe",
                  "operations": [
                    { "name": "listTransactions", "method": "GET", "path": "/balance_transactions",
                      "query": { "limit": "${limit}" } } ] } ],
              "credentials": [
                { "id": "cred_stripe", "kind": "api_key", "header": "Authorization" } ],
              "webhooks": [
                { "id": "wh_feed", "direction": "inbound", "entity": "JournalEntry",
                  "secretRef": "hook_wh_feed",
                  "mapping": { "mode": "upsert", "keyFields": ["reference"],
                               "idempotencyKey": "${data.id}",
                               "fields": { "reference": "${data.ref}" } } },
                { "id": "wh_notify", "direction": "outbound", "url": "https://ops.example/hook",
                  "events": "event == 'record.created' && entityId == 'JournalEntry'",
                  "secretRef": "hook_wh_notify" } ],
              "imports": [
                { "apiName": "journalFeed", "entity": "JournalEntry", "mode": "upsert",
                  "keyFields": ["reference"], "mapping": { "reference": "Ref" } } ] }
            """;

    @Test
    @DisplayName("integrations: the §3 spec shape saves clean")
    void integrationsBaselineValid() {
        assertThat(validate(withIntegrations(baseApp(), INTEGRATIONS_APP)).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("integrations: connector/credential/webhook/import rule matrix")
    void integrationsRuleMatrix() {
        assertThat(mentions(validate(withIntegrations(baseApp(),
                INTEGRATIONS_APP.replace("\"type\": \"rest\"", "\"type\": \"soap\""))),
                "connector type")).isTrue();
        assertThat(mentions(validate(withIntegrations(baseApp(),
                INTEGRATIONS_APP.replace("\"baseUrl\": \"https://api.stripe.com/v1\"",
                        "\"baseUrl\": \"ftp://x\""))),
                "baseUrl")).isTrue();
        assertThat(mentions(validate(withIntegrations(baseApp(),
                INTEGRATIONS_APP.replace("\"credential\": \"cred_stripe\"",
                        "\"credential\": \"cred_ghost\""))),
                "credential must reference")).isTrue();
        assertThat(mentions(validate(withIntegrations(baseApp(),
                INTEGRATIONS_APP.replace("\"method\": \"GET\"", "\"method\": \"TRACE\""))),
                "operation method")).isTrue();
        // credentials never carry the secret: the kind shapes pin the non-secret half
        assertThat(mentions(validate(withIntegrations(baseApp(),
                INTEGRATIONS_APP.replace("\"kind\": \"api_key\", \"header\": \"Authorization\"",
                        "\"kind\": \"basic\""))),
                "basic credentials")).isTrue();
        // webhook direction shape: outbound needs url+events, inbound entity+mapping
        assertThat(mentions(validate(withIntegrations(baseApp(),
                INTEGRATIONS_APP.replace("\"events\": \"event == 'record.created' "
                        + "&& entityId == 'JournalEntry'\"", "\"events\": \"\""))),
                "filter expression")).isTrue();
        assertThat(mentions(validate(withIntegrations(baseApp(),
                INTEGRATIONS_APP.replace("\"entity\": \"JournalEntry\"",
                        "\"entity\": \"Ghost\""))),
                "bind to an entity")).isTrue();
        assertThat(mentions(validate(withIntegrations(baseApp(),
                INTEGRATIONS_APP.replace("\"reference\": \"${data.ref}\"",
                        "\"ghostField\": \"${data.ref}\""))),
                "mapped field must exist")).isTrue();
        // imports: upsert requires keys; mappings address real fields
        assertThat(mentions(validate(withIntegrations(baseApp(),
                INTEGRATIONS_APP.replace("\"keyFields\": [\"reference\"]",
                        "\"keyFields\": []"))),
                "per-row idempotency")).isTrue();
        assertThat(mentions(validate(withIntegrations(baseApp(),
                INTEGRATIONS_APP.replace("\"mapping\": { \"reference\": \"Ref\" }",
                        "\"mapping\": { \"ghost\": \"Ref\" }"))),
                "mapped field must exist")).isTrue();
    }

    // --- Phase 7 harvests (PHASE-7 §3): freezeOnTerminal + periodLock ---

    /** The GL-shaped harvest: a posted journal freezes, dated writes lock to periods. */
    private static final String HARVEST_APP = """
            { "apiName": "Erp",
              "entities": [
                { "apiName": "AccountingPeriod",
                  "fields": [
                    { "apiName": "name", "type": "text", "required": true },
                    { "apiName": "startDate", "type": "date", "required": true },
                    { "apiName": "endDate", "type": "date", "required": true },
                    { "apiName": "status", "type": "enum",
                      "values": ["OPEN", "CLOSING", "CLOSED"] } ] },
                { "apiName": "JournalEntry",
                  "freezeOnTerminal": true,
                  "periodLock": { "entity": "AccountingPeriod", "dateField": "entryDate" },
                  "fields": [
                    { "apiName": "label", "type": "text", "required": true },
                    { "apiName": "entryDate", "type": "date", "required": true },
                    { "apiName": "status", "type": "enum", "values": ["DRAFT", "POSTED"] } ] } ],
              "stateMachines": [
                { "id": "sm_je", "entity": "JournalEntry", "stateField": "status",
                  "initial": "DRAFT",
                  "states": [ { "name": "DRAFT" }, { "name": "POSTED", "terminal": true } ],
                  "transitions": [ { "from": "DRAFT", "to": "POSTED" } ] },
                { "id": "sm_period", "entity": "AccountingPeriod", "stateField": "status",
                  "initial": "OPEN",
                  "states": [ { "name": "OPEN" }, { "name": "CLOSING" }, { "name": "CLOSED" } ],
                  "transitions": [
                    { "from": "OPEN", "to": "CLOSING" },
                    { "from": "CLOSING", "to": "CLOSED" },
                    { "from": "CLOSED", "to": "OPEN" } ] } ] }
            """;

    private static AppDefinition harvestApp(String mutation) {
        return DefinitionParser.parseApp(HARVEST_APP.replace("\"freezeOnTerminal\": true",
                "\"freezeOnTerminal\": " + mutation));
    }

    @Test
    @DisplayName("rule: the §3 harvest shape saves clean (machine-bound freeze + period lock)")
    void harvestShapeValid() {
        assertThat(validate(DefinitionParser.parseApp(HARVEST_APP)).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("rule: freezeOnTerminal requires a bound state machine")
    void freezeRequiresMachine() {
        AppDefinition broken = DefinitionParser.parseApp(HARVEST_APP.replace(
                "\"stateMachines\": [", "\"ghostMachines\": ["));
        assertThat(mentions(validate(broken), "freezeOnTerminal requires a bound state machine")).isTrue();
    }

    @Test
    @DisplayName("rule: freezeOnTerminal requires at least one terminal state")
    void freezeRequiresTerminalState() {
        AppDefinition broken = DefinitionParser.parseApp(HARVEST_APP.replace(
                "{ \"name\": \"POSTED\", \"terminal\": true }", "{ \"name\": \"POSTED\" }"));
        assertThat(mentions(validate(broken), "at least one terminal state")).isTrue();
    }

    @Test
    @DisplayName("rule: periodLock must bind to a period entity of the app")
    void periodLockEntityResolves() {
        AppDefinition broken = DefinitionParser.parseApp(HARVEST_APP.replace(
                "\"entity\": \"AccountingPeriod\"", "\"entity\": \"GhostPeriod\""));
        assertThat(mentions(validate(broken), "periodLock must bind to a period entity")).isTrue();
    }

    @Test
    @DisplayName("rule: periodLock's dateField must be a date/datetime field on the entity")
    void periodLockDateFieldTyped() {
        AppDefinition broken = DefinitionParser.parseApp(HARVEST_APP.replace(
                "\"dateField\": \"entryDate\"", "\"dateField\": \"label\""));
        assertThat(mentions(validate(broken), "date/datetime field")).isTrue();
    }

    @Test
    @DisplayName("rule: the period's range fields must be dates; closedStatus an enum value")
    void periodLockRangeAndStatusShaped() {
        AppDefinition rangeBroken = DefinitionParser.parseApp(HARVEST_APP.replace(
                "{ \"apiName\": \"startDate\", \"type\": \"date\", \"required\": true },",
                "{ \"apiName\": \"startDate\", \"type\": \"text\" },"));
        assertThat(mentions(validate(rangeBroken), "range field must be a date/datetime")).isTrue();

        AppDefinition statusBroken = DefinitionParser.parseApp(HARVEST_APP.replace(
                "\"values\": [\"OPEN\", \"CLOSING\", \"CLOSED\"]",
                "\"values\": [\"OPEN\", \"CLOSING\", \"LOCKED\"]"));
        assertThat(mentions(validate(statusBroken), "closedStatus must be a value of")).isTrue();
    }

    // --- PHASE-7 §4: the soft-close restriction (CLOSING unless exempt) ---

    /** The harvest app with §4's soft close bound: CLOSING unless closeJournal. */
    private static final String SOFT_CLOSE_APP = HARVEST_APP.replace(
            "\"periodLock\": { \"entity\": \"AccountingPeriod\", \"dateField\": \"entryDate\" },",
            "\"periodLock\": { \"entity\": \"AccountingPeriod\", \"dateField\": \"entryDate\",\n"
                    + "                \"restrictedStatus\": \"CLOSING\", \"exemptField\": \"closeJournal\" },")
            .replace("{ \"apiName\": \"entryDate\", \"type\": \"date\", \"required\": true },",
                    "{ \"apiName\": \"entryDate\", \"type\": \"date\", \"required\": true },\n"
                            + "                    { \"apiName\": \"closeJournal\", \"type\": \"boolean\" },");

    @Test
    @DisplayName("rule: the §4 soft close saves clean — restrictedStatus an enum value, exemptField boolean")
    void softCloseShapeValid() {
        assertThat(validate(DefinitionParser.parseApp(SOFT_CLOSE_APP)).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("rule: restrictedStatus must be an enum value and differ from closedStatus")
    void softCloseStatusShaped() {
        // a value the period's enum never declares
        assertThat(mentions(validate(DefinitionParser.parseApp(SOFT_CLOSE_APP.replace(
                        "\"restrictedStatus\": \"CLOSING\"", "\"restrictedStatus\": \"HALFWAY\""))),
                "restrictedStatus must be a value of")).isTrue();
        // the closed leg is absolute — the restriction may not collapse onto CLOSED
        assertThat(mentions(validate(DefinitionParser.parseApp(SOFT_CLOSE_APP.replace(
                        "\"restrictedStatus\": \"CLOSING\"", "\"restrictedStatus\": \"CLOSED\""))),
                "restrictedStatus must differ from closedStatus")).isTrue();
    }

    @Test
    @DisplayName("rule: restrictedStatus requires a boolean exemptField on the locked entity")
    void softCloseExemptFieldTyped() {
        AppDefinition missing = DefinitionParser.parseApp(SOFT_CLOSE_APP.replace(
                "\"restrictedStatus\": \"CLOSING\", \"exemptField\": \"closeJournal\" },",
                "\"restrictedStatus\": \"CLOSING\" },"));
        assertThat(mentions(validate(missing), "restrictedStatus requires a boolean exempt field")).isTrue();

        AppDefinition mistyped = DefinitionParser.parseApp(SOFT_CLOSE_APP.replace(
                "{ \"apiName\": \"closeJournal\", \"type\": \"boolean\" },",
                "{ \"apiName\": \"closeJournal\", \"type\": \"text\" },"));
        assertThat(mentions(validate(mistyped), "restrictedStatus requires a boolean exempt field")).isTrue();

        // the exemption never applies beside a closed-only lock
        AppDefinition orphan = DefinitionParser.parseApp(HARVEST_APP.replace(
                "\"periodLock\": { \"entity\": \"AccountingPeriod\", \"dateField\": \"entryDate\" },",
                "\"periodLock\": { \"entity\": \"AccountingPeriod\", \"dateField\": \"entryDate\", \"exemptField\": \"closeJournal\" },"));
        assertThat(mentions(validate(orphan), "exemptField applies only beside a restrictedStatus")).isTrue();
    }

    // --- PHASE-5 §5: widget refreshSeconds bounds ---

    @Test
    @DisplayName("rule: widget refreshSeconds is bounded; null stays the static default")
    void widgetRefreshSecondsBounded() {
        String dashboards = ", \"dashboards\": [ { \"id\": \"dash\", \"widgets\": [ "
                + "{ \"widget\": \"kpi\", \"reportRef\": \"arAging\", \"span\": 6__EXTRA__ } ] } ]";
        String branches = VALID_REPORT + dashboards;
        AppDefinition valid = reportingApp(branches.replace("__EXTRA__", ", \"refreshSeconds\": 30"));
        assertThat(validate(valid).isEmpty()).isTrue();
        assertThat(mentions(validate(reportingApp(branches.replace("__EXTRA__",
                ", \"refreshSeconds\": 1"))), "refreshSeconds is")).isTrue();
        assertThat(mentions(validate(reportingApp(branches.replace("__EXTRA__",
                ", \"refreshSeconds\": 7200"))), "refreshSeconds is")).isTrue();
    }
}
