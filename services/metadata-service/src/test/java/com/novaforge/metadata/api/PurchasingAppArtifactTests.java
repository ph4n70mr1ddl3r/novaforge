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
 * The Phase 4 exit journey as a CI-gated artifact (PHASE-4 §1/§14 item 1): PLAN §5's
 * exit — "purchase order requires manager approval above threshold, with escalation" —
 * authored as an app whose suite is the journey's runnable contract. The artifact must
 * clear the exact save/compile/suite checks the builder would run, and the §1
 * decomposition must be structurally present: the machine, the threshold branch into
 * requestApproval (managers, all-must-approve), the SoD case, and the governing SLA
 * whose breach escalates to the senior role.
 */
class PurchasingAppArtifactTests {

    private static final Path APP = Path.of("..", "..", "apps", "purchasing");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static AppDefinition app() throws Exception {
        return DefinitionParser.parseApp(
                Files.readString(APP.resolve("purchasing-app.json")));
    }

    @Test
    @DisplayName("the Purchasing app definition is save-clean (§1's authored shape)")
    void saveClean() throws Exception {
        ProblemErrors errors = DefinitionValidator.validate(app());
        assertThat(errors.isEmpty())
                .as("save validation findings: %s", errors.errors())
                .isTrue();
    }

    @Test
    @DisplayName("the app compiles: expressions, the submit flow, the machine, the SLA match")
    void compileClean() throws Exception {
        DefinitionService.compileCheckExpressions(app(), new ProblemErrors(List.of(), List.of()));
        FlowCompiler.compile(app());
    }

    @Test
    @DisplayName("the §1 decomposition: machine edges/terminals, threshold branch, approval params")
    void exitDecompositionAuthored() throws Exception {
        AppDefinition app = app();
        // item 1 — the machine: DRAFT → SUBMITTED → APPROVED | REJECTED; APPROVED → POSTED;
        // REJECTED and POSTED terminal; the submit edge carries a guard
        var machine = app.stateMachineFor("PurchaseOrder").orElseThrow();
        assertThat(machine.initial()).isEqualTo("DRAFT");
        assertThat(machine.isTerminal("REJECTED")).isTrue();
        assertThat(machine.isTerminal("POSTED")).isTrue();
        assertThat(machine.transition("DRAFT", "SUBMITTED")).isPresent();
        assertThat(machine.transition("SUBMITTED", "APPROVED")).isPresent();
        assertThat(machine.transition("SUBMITTED", "REJECTED")).isPresent();
        assertThat(machine.transition("APPROVED", "POSTED")).isPresent();
        assertThat(machine.transition("DRAFT", "SUBMITTED").orElseThrow().guard())
                .isEqualTo("total > 0");

        // item 2 — the submit flow branches on the threshold into requestApproval
        // (managers, all-must-approve), the rejection routes the onReject subgraph,
        // and the approve resume chains APPROVED → POSTED → the posted event
        EntityDefinition po = app.entity("PurchaseOrder").orElseThrow();
        var hook = po.hooks().stream()
                .filter(h -> "submitForApproval".equals(h.name())).findFirst().orElseThrow();
        assertThat(hook.trigger()).isEqualTo("afterSave");
        var branch = hook.flow();
        assertThat(branch.op()).isEqualTo("branch");
        assertThat(branch.onTrue()).isEqualTo("a1");
        assertThat(branch.onFalse()).isEqualTo("p2");   // below threshold: no approval
        var approval = step(hook.flow(), "a1");
        assertThat(approval.op()).isEqualTo("requestApproval");
        assertThat(approval.param("approvers")).isEqualTo("Purchasing.manager");
        assertThat(approval.param("mode")).isEqualTo("all");
        assertThat(approval.next()).isEqualTo("p2");
        assertThat(step(hook.flow(), "r1").op()).isEqualTo("transitionState");
        assertThat(step(hook.flow(), "r1").param("to")).isEqualTo("REJECTED");
        assertThat(step(hook.flow(), "p2").param("to")).isEqualTo("APPROVED");
        assertThat(step(hook.flow(), "p3").param("to")).isEqualTo("POSTED");
        assertThat(step(hook.flow(), "e1").op()).isEqualTo("publishEvent");

        // item 5 — the governing SLA: precedence over any step default, senior escalation
        var sla = app.slas().stream()
                .filter(s -> "poApprovalSla".equals(s.id())).findFirst().orElseThrow();
        assertThat(sla.scope().taskType()).isEqualTo("approval");
        assertThat(sla.scope().match()).isEqualTo("entity == 'Purchasing.PurchaseOrder'");
        assertThat(sla.target()).isEqualTo("PT1H");
        assertThat(sla.onBreach().escalateTo()).isEqualTo("Purchasing.seniorManager");
    }

    @Test
    @DisplayName("the exit-journey suite passes suite save-validation and carries §14 item 1")
    void suiteIsValid() throws Exception {
        TestSuiteDefinition suite = DefinitionParser.parse(
                Files.readString(APP.resolve("suites").resolve("exitJourney.json")),
                TestSuiteDefinition.class);
        DefinitionService.validateSuite(suite);
        assertThat(suite.cases()).hasSize(5);

        // the journey's legs, by name: approve→POSTED, reject, below-threshold auto,
        // warn→breach→escalation (scanSla — no sleeps), and the SoD rejection
        var names = suite.cases().stream().map(TestSuiteDefinition.TestCase::name).toList();
        assertThat(names).containsExactly("approve-to-posted", "reject-path",
                "below-threshold-auto-posts", "sla-warn-breach-escalation",
                "sod-requester-cannot-approve");

        var sod = suite.cases().stream()
                .filter(c -> c.name().startsWith("sod-")).findFirst().orElseThrow();
        assertThat(sod.steps().stream().anyMatch(step ->
                "resolveTask".equals(step.op()) && "error(SOD_VIOLATION)".equals(step.expect())))
                .as("§14 item 1's error(SOD_VIOLATION) leg").isTrue();

        var escalation = suite.cases().stream()
                .filter(c -> c.name().startsWith("sla-")).findFirst().orElseThrow();
        assertThat(escalation.steps().stream()
                .filter(step -> "scanSla".equals(step.op())).count())
                .as("the clock-advanced warn and breach legs (§12)").isEqualTo(2);
        assertThat(escalation.assertExpressions().stream()
                .anyMatch(a -> a.contains("Purchasing.seniorManager")))
                .as("the escalation replacement lands with the senior role (§1 item 5)")
                .isTrue();

        // the SoD case must run last: its task stays OPEN, and a later scanSla would
        // breach it and skew the escalation counts — the suite's own ordering rule
        assertThat(names.getLast()).isEqualTo("sod-requester-cannot-approve");
    }

    @Test
    @DisplayName("the suite's ${…} references parse as the runner's grammar")
    void suiteReferencesResolve() throws Exception {
        java.util.regex.Pattern reference =
                java.util.regex.Pattern.compile("\\$\\{([A-Za-z0-9_.\\[\\]]+)}");
        List<Path> suites;
        try (Stream<Path> files = Files.list(APP.resolve("suites"))) {
            suites = files.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }
        assertThat(suites).hasSize(1);
        for (Path suitePath : suites) {
            java.util.regex.Matcher matcher = reference.matcher(Files.readString(suitePath));
            while (matcher.find()) {
                assertThat(matcher.group(1))
                        .as("reference %s in %s", matcher.group(1), suitePath.getFileName())
                        .matches("[A-Za-z]+\\[\\d+\\]([.][A-Za-z0-9_]+)*");
            }
        }
    }

    /** Walks the nested body structure collecting every step by id (the encoding). */
    private static com.novaforge.metadata.FlowStep step(com.novaforge.metadata.FlowStep root,
                                                        String id) {
        java.util.ArrayDeque<com.novaforge.metadata.FlowStep> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            com.novaforge.metadata.FlowStep current = stack.pop();
            if (current == null) {
                continue;
            }
            if (id.equals(current.id())) {
                return current;
            }
            stack.push(current.body());
        }
        throw new AssertionError("no step " + id + " in the submit flow");
    }
}
