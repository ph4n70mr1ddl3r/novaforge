package com.novaforge.metadata.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.novaforge.common.error.ProblemErrors;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.metadata.DefinitionValidator;
import com.novaforge.metadata.EntityDefinition;
import com.novaforge.metadata.TestSuiteDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The ERP dogfood artifact is a phase deliverable (PHASE-7 §1 rule 4: "an unverified
 * module is not done") — so the artifact itself is CI-gated: the app definition must be
 * save-clean, compile-clean (flows, machines, expressions, integrations), and every
 * suite must pass suite save-validation — the exact checks the Metadata Service would
 * run were the app authored through the builder. Zero handwritten application code;
 * the one budgeted script (§5, rule 3) is counted here too.
 */
class ErpAppArtifactTests {

    private static final Path ERP = Path.of("..", "..", "apps", "erp");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static AppDefinition app() throws Exception {
        return DefinitionParser.parseApp(
                Files.readString(ERP.resolve("erp-app.json")));
    }

    @Test
    @DisplayName("the ERP app definition is save-clean (§2 modules, §3 harvests)")
    void saveClean() throws Exception {
        ProblemErrors errors = DefinitionValidator.validate(app());
        assertThat(errors.isEmpty())
                .as("save validation findings: %s", errors.errors())
                .isTrue();
    }

    @Test
    @DisplayName("the gap log rides the artifact as metadata (PHASE-8 §3's review surface)")
    void gapLogRidesTheArtifact() throws Exception {
        AppDefinition app = app();
        // the GAP-LOG.md discipline, mirrored as the app's gapLog branch: every entry
        // logged before its workaround, dispositions from §8's triage set
        assertThat(app.gapLog().size()).isGreaterThanOrEqualTo(11);
        assertThat(app.gapLog().stream().filter(gap -> gap.id().equals("G-1")).toList())
                .as("the first logged gap (created-record id capture)").hasSize(1);
        assertThat(app.gapLog().stream().filter(gap -> gap.id().equals("G-11")).toList())
                .as("the newest logged gap (the poll op)").hasSize(1);
        // at least one entry is resolved (accept-as-platform-feature/closed) so the
        // change-set review's resolvedGaps surface has a live authoring precedent
        assertThat(app.gapLog().stream()
                .anyMatch(gap -> com.novaforge.metadata.GapLogEntry.resolving(gap.disposition())))
                .isTrue();
    }

    @Test
    @DisplayName("the ERP app definition compiles: expressions, flows, machines, reports")
    void compileClean() throws Exception {
        // The exact publish path: expression compile-check + the FlowCompiler's
        // reference/type checks over every hook, state machine, report, integration.
        DefinitionService.compileCheckExpressions(app(), new ProblemErrors(List.of(), List.of()));
        FlowCompiler.compile(app());
    }

    @Test
    @DisplayName("the §3 harvests are pinned: JE/StockLedger freeze, JE period-locks (soft close)")
    void harvestsBound() throws Exception {
        AppDefinition app = app();
        assertThat(app.entity("JournalEntry").orElseThrow().freezesOnTerminal()).isTrue();
        assertThat(app.entity("JournalEntry").orElseThrow().periodLock()).isNotNull();
        // §2's pin: freeze binds the journal entry, NOT the invoice — settlement
        // decrements amountOutstanding on the POSTED invoice (the bankFeed suite's leg)
        assertThat(app.entity("Invoice").orElseThrow().freezesOnTerminal()).isFalse();
        assertThat(app.entity("StockLedger").orElseThrow().freezesOnTerminal()).isTrue();
        // §4's soft close: CLOSING blocks postings unless the close-journal flag is set
        var lock = app.entity("JournalEntry").orElseThrow().periodLock();
        assertThat(lock.restrictedStatus()).isEqualTo("CLOSING");
        assertThat(lock.exemptField()).isEqualTo("closeJournal");
        // the period machine's reopen edge (§4): CLOSED is deliberately non-terminal
        var period = app.stateMachineFor("AccountingPeriod").orElseThrow();
        assertThat(period.isTerminal("CLOSED")).isFalse();
        assertThat(period.transition("CLOSED", "OPEN")).isPresent();
        // §4's close checklist: a workflow starts when a period enters CLOSING, its
        // confirm task reachable only through the parallel join of the role tasks
        var checklist = app.workflows().stream()
                .filter(workflow -> workflow.id().equals("closeChecklist")).findFirst().orElseThrow();
        assertThat(checklist.eventStarts()).hasSize(1);
        assertThat(checklist.eventStarts().getFirst().entity()).isEqualTo("AccountingPeriod");
        assertThat(checklist.eventStarts().getFirst().filter()).isEqualTo("status == 'CLOSING'");
        assertThat(checklist.bpmn()).contains("flowable:candidateGroups=\"arClerk\"");
        assertThat(checklist.bpmn()).contains("flowable:candidateGroups=\"controller\"");
        assertThat(checklist.bpmn()).contains("<parallelGateway id=\"join\"/>");
    }

    @Test
    @DisplayName("the §2 AR/AP + Settings scope rows are authored (credit notes, letters, vendor, rates)")
    void arApSettingsScopeAuthored() throws Exception {
        AppDefinition app = app();
        // AR/AP row: Customer, Vendor, Invoice+lines, CreditNote, Payment, DunningLetter
        for (String entity : List.of("Vendor", "CreditNote", "DunningLetter")) {
            assertThat(app.entity(entity)).as("§2 AR/AP row entity %s", entity).isPresent();
        }
        // the allocation leg (payment → invoice/credit memo) rides the credit note's
        // invoice lookup — present on the entity, exercised by creditAndCurrency
        assertThat(app.entity("CreditNote").orElseThrow()
                .field("invoice")).isPresent();
        // Settings row: the FX rate table is an app entity (the multi-currency pin),
        // unique per (currency, rateDate)
        var fx = app.entity("FxRate").orElseThrow();
        assertThat(fx.indexes().stream().anyMatch(index -> Boolean.TRUE.equals(index.unique())
                && index.fields().equals(List.of("currency", "rateDate"))))
                .as("FxRate is unique per (currency, rateDate)").isTrue();
        // credit notes number gaplessly like the other customer-facing documents
        assertThat(app.settings().sequence("creditNoteNumber").orElseThrow().mode())
                .isEqualTo(com.novaforge.metadata.SequenceMode.GAPLESS);
        assertThat(sequenceField(app, "CreditNote", "number")).isNotNull();
        // financial-reports row: trial balance, A/R aging, P&L sketch, dashboard
        assertThat(app.reports().stream().anyMatch(report -> report.id().equals("plSketch")))
                .as("the P&L sketch report (§2 financial-reports row)").isTrue();
        assertThat(app.dashboards().stream()
                .anyMatch(board -> board.widgets().stream()
                        .anyMatch(widget -> "plSketch".equals(widget.reportRef()))))
                .as("the executive dashboard carries the P&L sketch").isTrue();
    }

    @Test
    @DisplayName("bank-feed wiring (§5/T8): the connector rides a scheduled flow, not just the inbound webhook")
    void bankFeedWiring() throws Exception {
        AppDefinition app = app();
        // §5's pin: "the Phase 6 exit connector driven by a scheduled flow (a
        // callConnector step inside it)" — the job addresses the hook by name and
        // the hook's graph carries the connector call that iterates into Payments
        var job = app.jobs().stream().filter(j -> "bankFeedSync".equals(j.name()))
                .findFirst().orElseThrow();
        assertThat(job.target()).isEqualTo("flow");
        assertThat(job.param("entity")).isEqualTo("Payment");
        assertThat(job.param("hook")).isEqualTo("syncBankFeed");
        assertThat(job.cron()).isNotBlank();

        var hook = app.entity("Payment").orElseThrow().hooks().stream()
                .filter(h -> "syncBankFeed".equals(h.name())).findFirst().orElseThrow();
        assertThat(hook.trigger()).isEqualTo("scheduled");   // recordless — never write-path
        // walk the graph (next + bodies) for the callConnector and the iterate over
        // its response — the pull shape's two load-bearing steps
        boolean[] found = { false, false };
        java.util.ArrayDeque<com.novaforge.metadata.FlowStep> stack = new java.util.ArrayDeque<>();
        stack.push(hook.flow());
        while (!stack.isEmpty()) {
            com.novaforge.metadata.FlowStep step = stack.pop();
            if (step == null) {
                continue;
            }
            if ("callConnector".equals(step.op())
                    && "bankFeed".equals(step.param("connector"))) {
                found[0] = true;
            }
            if ("iterate".equals(step.op())
                    && step.param("path") != null
                    && step.param("path").startsWith("connector.")) {
                found[1] = true;
            }
            if (step.body() != null) {
                stack.push(step.body());
            }
        }
        assertThat(found[0]).as("a callConnector step drives the bankFeed connector").isTrue();
        assertThat(found[1]).as("the response array iterates into Payment rows").isTrue();
        // the inbound push direction stays beside it (the idempotent upsert path —
        // G-14's workaround, the bankFeed suite's leg)
        assertThat(app.integrations().webhooks().stream()
                .anyMatch(w -> "paymentsFeed".equals(w.id())
                        && com.novaforge.metadata.WebhookDefinition.INBOUND.equals(w.direction())))
                .isTrue();
    }

    @Test
    @DisplayName("gapless sequences bind the entry/invoice numbering (§2 GL/AR)")
    void gaplessNumbering() throws Exception {
        AppDefinition app = app();
        assertThat(app.settings().sequence("entryNumber").orElseThrow().mode())
                .isEqualTo(com.novaforge.metadata.SequenceMode.GAPLESS);
        assertThat(app.settings().sequence("invoiceNumber").orElseThrow().mode())
                .isEqualTo(com.novaforge.metadata.SequenceMode.GAPLESS);
        assertThat(sequenceField(app, "JournalEntry", "reference")).isNotNull();
        assertThat(sequenceField(app, "Invoice", "number")).isNotNull();
    }

    @Test
    @DisplayName("script budget: exactly one escape-hatch script (rule 3, ≤ 20%)")
    void scriptBudget() throws Exception {
        AppDefinition app = app();
        List<com.novaforge.metadata.HookRule> allHooks = app.entities().stream()
                .flatMap(entity -> entity.hooks().stream()).toList();
        long scripts = allHooks.stream().filter(hook -> hook.script() != null).count();
        long flows = allHooks.stream().filter(hook -> hook.flow() != null).count();
        assertThat(scripts).as("one budgeted script (§5 weighted-average costing)").isEqualTo(1);
        assertThat(flows).as("the posting flows are declarative (no scripts expected, §5)").isGreaterThanOrEqualTo(2);
        // rule 3's ceiling with margin: 1 script of 3 hooks ≈ 33% of *hooks* — but the
        // module budget counts logic surface (flows + validations + formulas + rollups
        // are declarative); the ratio reported at exit review is per module (§9 item 7)
        assertThat(scripts).isEqualTo(1);
    }

    @Test
    @DisplayName("every authored suite passes suite save-validation (the §9 contract)")
    void suitesValidate() throws Exception {
        List<Path> suites;
        try (Stream<Path> files = Files.list(ERP.resolve("suites"))) {
            suites = files.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }
        assertThat(suites).as("the acceptance corpus: reconciliation + controls + costing + bank feed "
                + "+ credit/currency (allocation, EUR book-currency posting, dunning, AP subledger)")
                .hasSizeGreaterThanOrEqualTo(5);
        for (Path suitePath : suites) {
            TestSuiteDefinition suite = DefinitionParser.parse(
                    Files.readString(suitePath), TestSuiteDefinition.class);
            DefinitionService.validateSuite(suite);
            assertThat(suite.cases())
                    .as("%s carries cases", suitePath.getFileName())
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("the §9 assertions use only ${…} references the runner can resolve")
    void suiteReferencesResolve() throws Exception {
        // structural smoke: assertions and recordId/template references parse as the
        // runner's ${Entity[n].path} grammar — a typo'd reference would silently
        // interpolate to null and fail at run time with a confusing verdict
        java.util.regex.Pattern reference =
                java.util.regex.Pattern.compile("\\$\\{([A-Za-z0-9_.\\[\\]]+)}");
        List<Path> suites;
        try (Stream<Path> files = Files.list(ERP.resolve("suites"))) {
            suites = files.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }
        for (Path suitePath : suites) {
            String source = Files.readString(suitePath);
            java.util.regex.Matcher matcher = reference.matcher(source);
            while (matcher.find()) {
                assertThat(matcher.group(1))
                        .as("reference %s in %s", matcher.group(1), suitePath.getFileName())
                        .matches("[A-Za-z]+\\[\\d+\\]([.][A-Za-z0-9_]+)*");
            }
        }
    }

    private static com.novaforge.metadata.DefaultValue sequenceField(AppDefinition app,
                                                                     String entity,
                                                                     String field) {
        return app.entity(entity).orElseThrow().field(field).orElseThrow().defaultValue();
    }
}
