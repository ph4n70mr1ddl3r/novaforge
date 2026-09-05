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
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The BuildRight portfolio's wave-1 artifact (the P2P product dogfood) is gated by
 * the same discipline as the Phase 7 corpus ({@code ErpAppArtifactTests}): the app
 * definition must be save-clean, compile-clean (flows, machines, expressions), and
 * every suite must pass suite save-validation — the exact checks the Metadata
 * Service would run were the app authored through the builder. Zero handwritten
 * application code; the hook corpus is fully declarative. The portfolio's own
 * contract — requirements traceability (coverage/) and the gap-log discipline —
 * is pinned here too, so a wave cannot claim coverage it does not have.
 */
class BuildrightAppArtifactTests {

    private static final Path BUILDRIGHT = Path.of("..", "..", "apps", "buildright");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Set<String> KNOWN_APPS = Set.of("apps/erp", "apps/buildright");

    private static AppDefinition app() throws Exception {
        return DefinitionParser.parseApp(
                Files.readString(BUILDRIGHT.resolve("buildright-app.json")));
    }

    @Test
    @DisplayName("the P2P app definition is save-clean (the builder's exact checks)")
    void saveClean() throws Exception {
        ProblemErrors errors = DefinitionValidator.validate(app());
        assertThat(errors.isEmpty())
                .as("save validation findings: %s", errors.errors())
                .isTrue();
    }

    @Test
    @DisplayName("the P2P app definition compiles: expressions, flows, machines, reports")
    void compileClean() throws Exception {
        AppDefinition app = app();
        DefinitionService.compileCheckExpressions(app, new ProblemErrors(List.of(), List.of()), false);
        FlowCompiler.compile(app);
    }

    @Test
    @DisplayName("the portfolio gap log rides the artifact (the PHASE-7 §1 rule 2 discipline)")
    void gapLogRidesTheArtifact() throws Exception {
        AppDefinition app = app();
        assertThat(app.gapLog().size()).isGreaterThanOrEqualTo(4);
        assertThat(app.gapLog().stream().filter(gap -> gap.id().equals("BR-G-1")).toList())
                .as("the cross-app gap (apps cannot create records in other apps' entities)")
                .hasSize(1);
        assertThat(app.gapLog().stream().filter(gap -> gap.id().equals("BR-G-2")).toList())
                .as("the cross-document guard gap (the 3-way match blocker)").hasSize(1);
        // wave 1 opens honestly: every entry triaged into the §8 disposition set
        for (var gap : app.gapLog()) {
            assertThat(Set.of("backlog", "closed", "wontfix-with-workaround",
                            "accept-as-platform-feature"))
                    .as("gap %s disposition", gap.id()).contains(gap.disposition());
        }
    }

    @Test
    @DisplayName("P2P document chain: PO machine, receipt freeze, bill auto-journal, payment freeze")
    void p2pDocumentChain() throws Exception {
        AppDefinition app = app();
        // PO: the resubmission edge (REJECTED → SUBMITTED) and the terminal close
        var po = app.stateMachineFor("PurchaseOrder").orElseThrow();
        assertThat(po.transition("REJECTED", "SUBMITTED")).as("resubmit after rejection").isPresent();
        assertThat(po.transition("SUBMITTED", "APPROVED")).isPresent();
        assertThat(po.transition("SUBMITTED", "REJECTED")).isPresent();
        assertThat(po.isTerminal("CLOSED")).isTrue();
        // receipts are ledger-grade: terminal state freezes the document
        assertThat(app.entity("GoodsReceipt").orElseThrow().freezesOnTerminal()).isTrue();
        // payments freeze on posting (the settlement leg is immutable once posted)
        assertThat(app.entity("BillPayment").orElseThrow().freezesOnTerminal()).isTrue();
        // the bill is NOT frozen at POSTED — the settlement formula decrements
        // outstanding on the live bill (the Erp invoice pin, mirrored)
        assertThat(app.entity("VendorBill").orElseThrow().freezesOnTerminal()).isFalse();
        // the bill's auto-journal is a frozen ledger document with a typed link back
        var je = app.entity("JournalEntry").orElseThrow();
        assertThat(je.freezesOnTerminal()).isTrue();
        assertThat(je.field("sourceBill")).as("the auto-journal's link to its bill").isPresent();
    }

    @Test
    @DisplayName("settlement by roll-up: posted payments only, outstanding = total − paid")
    void settlementByRollup() throws Exception {
        AppDefinition app = app();
        var amountPaid = app.entity("VendorBill").orElseThrow().field("amountPaid").orElseThrow();
        assertThat(amountPaid.rollup())
                .as("amountPaid counts POSTED payments only (the G-15 WHERE grammar)")
                .isEqualTo("SUM(payments.amount WHERE status = 'POSTED')");
        assertThat(app.entity("VendorBill").orElseThrow().field("amountOutstanding").orElseThrow().formula())
                .as("outstanding derives from posted payments — no flow write needed")
                .isEqualTo("total - amountPaid");
        // the receipt-side twin: Item.qtyReceived counts POSTED receipts only
        assertThat(app.entity("Item").orElseThrow().field("qtyReceived").orElseThrow().rollup())
                .isEqualTo("SUM(receipts.qty WHERE status = 'POSTED')");
    }

    @Test
    @DisplayName("threshold approval: the branch gates on approvalThreshold, the reject leg ends at REJECTED")
    void thresholdApproval() throws Exception {
        AppDefinition app = app();
        var po = app.entity("PurchaseOrder").orElseThrow();
        assertThat(po.field("approvalThreshold")).as("the tiering threshold is authored").isPresent();
        var hook = po.hooks().stream()
                .filter(h -> "submitForApproval".equals(h.name())).findFirst().orElseThrow();
        Map<String, com.novaforge.metadata.FlowStep> byId = new java.util.LinkedHashMap<>();
        java.util.ArrayDeque<com.novaforge.metadata.FlowStep> stack = new java.util.ArrayDeque<>();
        stack.push(hook.flow());
        while (!stack.isEmpty()) {
            var step = stack.pop();
            if (step == null) {
                continue;
            }
            byId.put(step.id(), step);
            if (step.body() != null) {
                stack.push(step.body());
            }
        }
        var tier = byId.get("b2");
        assertThat(tier.op()).isEqualTo("branch");
        assertThat(tier.param("guard")).isEqualTo("total > approvalThreshold");
        // approve → APPROVED; reject → REJECTED (the reject leg ends at the transition);
        // below threshold → straight to APPROVED (the same p2 step, no approval hop)
        var approval = byId.get("a1");
        assertThat(approval.op()).isEqualTo("requestApproval");
        assertThat(String.valueOf(approval.param("approvers"))).isEqualTo("Buildright.procurementManager");
        assertThat(approval.next()).isEqualTo("p2");
        assertThat(byId.get("r1").op()).isEqualTo("transitionState");
        assertThat(byId.get("r1").param("to")).isEqualTo("REJECTED");
        assertThat(byId.get("p2").op()).isEqualTo("transitionState");
        assertThat(byId.get("p2").param("to")).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("the bill posting flow creates the journal from deep-resolved templates (the G-1 shape)")
    void billPostingCreatesJournal() throws Exception {
        AppDefinition app = app();
        var bill = app.entity("VendorBill").orElseThrow();
        assertThat(bill.field("apAccount")).isPresent();
        assertThat(bill.field("expenseAccount")).isPresent();
        assertThat(bill.field("totalBook").orElseThrow().formula()).isEqualTo("total * fxRate");
        var hook = bill.hooks().stream()
                .filter(h -> "submitForPosting".equals(h.name())).findFirst().orElseThrow();
        Map<String, com.novaforge.metadata.FlowStep> byId = new java.util.LinkedHashMap<>();
        java.util.ArrayDeque<com.novaforge.metadata.FlowStep> stack = new java.util.ArrayDeque<>();
        stack.push(hook.flow());
        while (!stack.isEmpty()) {
            var step = stack.pop();
            if (step == null) {
                continue;
            }
            byId.put(step.id(), step);
            if (step.body() != null) {
                stack.push(step.body());
            }
        }
        var approval = byId.get("a1");
        assertThat(approval.op()).isEqualTo("requestApproval");
        assertThat(approval.next()).isEqualTo("j1");
        assertThat(approval.body().op()).isEqualTo("publishEvent");
        assertThat(approval.body().next()).as("the reject leg ends at the event").isNull();
        var journal = byId.get("j1");
        assertThat(journal.op()).isEqualTo("createRecord");
        assertThat(journal.param("entity")).isEqualTo("JournalEntry");
        Map<?, ?> template = (Map<?, ?>) journal.params().get("template");
        assertThat(String.valueOf(template.get("memo"))).isEqualTo("Bill ${number}");
        assertThat(String.valueOf(template.get("sourceBill"))).isEqualTo("${id}");
        var lines = (List<?>) template.get("lines");
        assertThat(lines).as("the expense/AP-control pair, deep-resolved from the record").hasSize(2);
        assertThat(journal.next()).isEqualTo("p1");
        assertThat(byId.get("p1").op()).isEqualTo("transitionState");
        assertThat(byId.get("p1").param("to")).isEqualTo("POSTED");
    }

    @Test
    @DisplayName("gapless sequences bind PO/receipt/bill/journal numbering; payments are cached")
    void documentNumbering() throws Exception {
        AppDefinition app = app();
        for (String seq : List.of("poNumber", "receiptNumber", "billNumber", "entryNumber")) {
            assertThat(app.settings().sequence(seq).orElseThrow().mode())
                    .as("%s is gapless (financial documents)", seq)
                    .isEqualTo(com.novaforge.metadata.SequenceMode.GAPLESS);
        }
        assertThat(app.settings().sequence("paymentNumber").orElseThrow().mode())
                .isEqualTo(com.novaforge.metadata.SequenceMode.CACHED);
    }

    @Test
    @DisplayName("script budget: zero scripts of four hooks — the corpus stays declarative")
    void scriptBudget() throws Exception {
        AppDefinition app = app();
        List<com.novaforge.metadata.HookRule> allHooks = app.entities().stream()
                .flatMap(entity -> entity.hooks().stream()).toList();
        long scripts = allHooks.stream().filter(hook -> hook.script() != null).count();
        long flows = allHooks.stream().filter(hook -> hook.flow() != null).count();
        assertThat(scripts).as("no escape-hatch scripts in the wave-1 corpus").isZero();
        assertThat(flows).as("every hook is a declarative flow").isEqualTo(4);
        assertThat(allHooks).hasSize(4);
    }

    @Test
    @DisplayName("every authored suite passes suite save-validation (the ADR-010 contract)")
    void suitesValidate() throws Exception {
        List<Path> suites;
        try (Stream<Path> files = Files.list(BUILDRIGHT.resolve("suites"))) {
            suites = files.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }
        assertThat(suites)
                .as("the acceptance corpus: happy path + approval edges + receiving/billing edges")
                .hasSizeGreaterThanOrEqualTo(3);
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
    @DisplayName("suite assertions use only ${…} references the runner can resolve")
    void suiteReferencesResolve() throws Exception {
        java.util.regex.Pattern reference =
                java.util.regex.Pattern.compile("\\$\\{([A-Za-z0-9_.\\[\\]]+)}");
        List<Path> suites;
        try (Stream<Path> files = Files.list(BUILDRIGHT.resolve("suites"))) {
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

    @Test
    @DisplayName("the coverage matrix is committed, totals the full catalog, and claims only what exists")
    @SuppressWarnings("unchecked")
    void coverageMatrixIsHonest() throws Exception {
        Path coverageJson = BUILDRIGHT.resolve("requirements-coverage").resolve("coverage.json");
        assertThat(coverageJson).as("run scripts/generate-coverage.py; commit coverage/")
                .exists();
        Map<String, Object> coverage = MAPPER.readValue(coverageJson.toFile(), Map.class);
        Map<String, Object> totals = (Map<String, Object>) coverage.get("totals");
        Map<String, Object> all = (Map<String, Object>) totals.get("all");
        assertThat((Integer) all.get("total"))
                .as("the matrix totals the erpplans catalog (724 rows)")
                .isEqualTo(724);
        int summed = (Integer) all.get("covered")
                + (Integer) all.get("partial") + (Integer) all.get("uncovered");
        assertThat(summed).isEqualTo(724);
        // every claim names only apps that exist in this repository
        List<Map<String, Object>> requirements =
                (List<Map<String, Object>>) coverage.get("requirements");
        for (Map<String, Object> req : requirements) {
            String status = String.valueOf(req.get("status"));
            assertThat(Set.of("covered", "partial", "uncovered"))
                    .as("%s status", req.get("id")).contains(status);
            if (!"uncovered".equals(status)) {
                String appField = String.valueOf(req.get("app"));
                for (String named : appField.split("\\s*\\+\\s*")) {
                    assertThat(KNOWN_APPS).as("%s claims app %s", req.get("id"), named)
                            .contains(named.trim());
                    assertThat(BUILDRIGHT.resolve("../..").resolve(named.trim()))
                            .as("claimed app %s exists on disk", named.trim()).exists();
                }
            }
        }
        assertThat(BUILDRIGHT.resolve("requirements-coverage").resolve("matrix.md"))
                .as("the human-readable matrix rides the commit").exists();
    }
}
