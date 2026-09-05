package com.novaforge.e2e;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The <strong>record-to-report cycle</strong>, end to end against the live stack, in
 * two legs:
 *
 * <ol>
 *   <li>the {@code recordToReport} suite runs green through the headless harness —
 *       posting, the soft close that blocks ordinary journals while admitting
 *       {@code closeJournal} accruals, the hard close that locks dated writes, the
 *       reopen that admits them again, and the trial balance / P&amp;L reports pinning
 *       the ledger's arithmetic;</li>
 *   <li>the period-close <em>checklist</em> itself — the BPMN workflow
 *       ({@code closeChecklist}) that starts on the period's CLOSING event, fans out
 *       three parallel reconciliation tasks to their candidate roles, joins, and ends
 *       at the controller's confirmation. That leg is asynchronous by design (the
 *       event spine + the Flowable deploy sync), so it is driven directly through the
 *       runtime write path and the workflow inbox with Awaitility — the same APIs the
 *       runtime shell's inbox uses.</li>
 * </ol>
 */
class ErpRecordToReportE2ETest {

    @Test
    void recordToReportSuiteRunsGreen() {
        NovaForgeStack stack = NovaForgeStack.stack();
        NovaForgeStack.Tenant tenant = stack.createTenant("e2e-r2r-" + System.currentTimeMillis());
        UUID appId = stack.publishApp(tenant, stack.readAppJson("erp", "erp-app.json"));
        stack.putSuite(tenant, appId, "recordToReport",
                stack.readAppJson("erp", "suites/recordToReport.json"));
        stack.runSuiteGreen(tenant, appId, "recordToReport");
    }

    @Test
    void periodCloseChecklistRunsThroughTheBpmnWorkflow() throws InterruptedException {
        NovaForgeStack stack = NovaForgeStack.stack();
        NovaForgeStack.Tenant tenant = stack.createTenant("e2e-close-" + System.currentTimeMillis());

        // synthetic actors for every candidate role the checklist names
        String controller = actorToken(stack, tenant, "ctl", "Erp.controller");
        String manager = actorToken(stack, tenant, "mgr", "Erp.accountingManager");
        String arClerk = actorToken(stack, tenant, "arc", "Erp.arClerk");
        String invClerk = actorToken(stack, tenant, "ivc", "Erp.inventoryClerk");

        stack.publishApp(tenant, stack.readAppJson("erp", "erp-app.json"));

        // give the workflow's publish-driven deploy (event relay + the interval sweep)
        // time to land the closeChecklist definition before the CLOSING event fires —
        // an event consumed before its process deploys would match nothing
        Thread.sleep(6_000);

        // the ledger leg: a balanced journal, approved and posted inside September
        String ar = stack.runtimeCreate(tenant, manager, "Account",
                "{\"code\":\"1100\",\"name\":\"Accounts Receivable\",\"type\":\"ASSET\"}");
        String revenue = stack.runtimeCreate(tenant, manager, "Account",
                "{\"code\":\"4000\",\"name\":\"Sales Revenue\",\"type\":\"REVENUE\"}");
        String journal = stack.runtimeCreate(tenant, arClerk, "JournalEntry",
                "{\"entryDate\":\"2026-09-10\",\"memo\":\"september sale\","
                        + "\"lines\":[{\"account\":\"" + ar + "\",\"debit\":\"40.0000\"},"
                        + "{\"account\":\"" + revenue + "\",\"credit\":\"40.0000\"}]}");
        submitAndApproveJournal(stack, arClerk, manager, journal);
        assertThat(stack.runtimeGet(manager, "JournalEntry", journal).get("status").asString())
                .isEqualTo("POSTED");

        // the period: OPEN → CLOSING starts the closeChecklist BPMN (event start on
        // record.updated with filter status == 'CLOSING')
        String period = stack.runtimeCreate(tenant, manager, "AccountingPeriod",
                "{\"name\":\"Sep 2026\",\"startDate\":\"2026-09-01\",\"endDate\":\"2026-09-30\"}");
        stack.runtimeUpdateRaw(manager, "AccountingPeriod", period,
                "{\"version\":" + versionOf(stack, manager, "AccountingPeriod", period)
                        + ",\"status\":\"CLOSING\"}").assertOk("begin close");

        // the three parallel reconciliation tasks reach their candidate roles; each
        // role resolves its own step (A/R → arClerk, inventory → inventoryClerk,
        // accrual journals → accountingManager). Each role carries exactly one open
        // task at its resolution point — the sequencing below is the unambiguous one.
        resolveChecklistStep(stack, arClerk, "Erp.arClerk");
        resolveChecklistStep(stack, invClerk, "Erp.inventoryClerk");
        resolveChecklistStep(stack, manager, "Erp.accountingManager");

        // the join fires: the controller confirms, the checklist ends
        String confirm = Awaitility.await().atMost(Duration.ofSeconds(60))
                .alias("confirm period close reaches the controller")
                .until(() -> firstOpenTaskId(stack, controller, "Erp.controller"),
                        id -> id != null);
        stack.resolveTask(controller, confirm, "approve");

        // the controller closes the reconciled period, the lock bites, the reopen admits
        stack.runtimeUpdateRaw(controller, "AccountingPeriod", period,
                "{\"version\":" + versionOf(stack, controller, "AccountingPeriod", period)
                        + ",\"status\":\"CLOSED\"}").assertOk("close period");

        var locked = stack.runtimeCreateRaw(arClerk, "JournalEntry",
                "{\"entryDate\":\"2026-09-15\",\"memo\":\"into the closed period\","
                        + "\"lines\":[{\"account\":\"" + ar + "\",\"debit\":\"5.0000\"},"
                        + "{\"account\":\"" + revenue + "\",\"credit\":\"5.0000\"}]}");
        assertThat(locked.json().path("code").asString())
                .as("a dated write into a CLOSED period is period-locked: %s", locked.body())
                // PERIOD_LOCKED's registry code (PlatformErrorCode 4014) — the wire
                // carries codes, names are the harness's expect() sugar
                .isEqualTo("4014");

        stack.runtimeUpdateRaw(controller, "AccountingPeriod", period,
                "{\"version\":" + versionOf(stack, controller, "AccountingPeriod", period)
                        + ",\"status\":\"OPEN\"}").assertOk("reopen period");

        String admitted = stack.runtimeCreate(tenant, arClerk, "JournalEntry",
                "{\"entryDate\":\"2026-09-15\",\"memo\":\"reopened period admits writes\","
                        + "\"lines\":[{\"account\":\"" + ar + "\",\"debit\":\"5.0000\"},"
                        + "{\"account\":\"" + revenue + "\",\"credit\":\"5.0000\"}]}");
        submitAndApproveJournal(stack, arClerk, manager, admitted);
        assertThat(stack.runtimeGet(manager, "JournalEntry", admitted).get("status").asString())
                .isEqualTo("POSTED");

        // the report leg: the trial balance stays balanced across the whole cycle —
        // 40 (September sale) + 5 (reopened-period write), and nothing else
        JsonNode totals = stack.runReport(controller, "trialBalance", "Erp").path("totals");
        assertThat(totals.path("debitTotal").decimalValue())
                .as("trial balance totals %s", totals)
                .isEqualByComparingTo(totals.path("creditTotal").decimalValue());
        assertThat(totals.path("debitTotal").decimalValue())
                .isEqualByComparingTo(new BigDecimal("45.0000"));
    }

    // --- helpers ---

    /** Provisions the synthetic actor, then grants; returns the actor's bearer token. */
    private static String actorToken(NovaForgeStack stack, NovaForgeStack.Tenant tenant,
                                     String prefix, String role) {
        String username = prefix + "-" + tenant.tenantId().substring(0, 8);
        stack.provisionActor(tenant, username, role);
        return stack.passwordGrant(username, "e2e-" + username + "-secret");
    }

    /** The record's current optimistic-lock version (never hardcode one). */
    private static long versionOf(NovaForgeStack stack, String token, String entity, String id) {
        return stack.runtimeGet(token, entity, id).get("version").asLong();
    }

    /** preparer submits → the manager's approval task appears → approve → the entry posts. */
    private static void submitAndApproveJournal(NovaForgeStack stack, String preparer,
                                                String approver, String journalId) {
        stack.runtimeUpdateRaw(preparer, "JournalEntry", journalId,
                "{\"version\":" + versionOf(stack, preparer, "JournalEntry", journalId)
                        + ",\"status\":\"SUBMITTED\"}").assertOk("submit journal");
        String taskId = Awaitility.await().atMost(Duration.ofSeconds(30))
                .alias("journal approval task reaches the manager")
                .until(() -> firstOpenTaskId(stack, approver, "Erp.accountingManager"),
                        id -> id != null);
        stack.resolveTask(approver, taskId, "approve");
    }

    private static String firstOpenTaskId(NovaForgeStack stack, String actorToken, String role) {
        List<JsonNode> tasks = stack.openTasks(actorToken, role);
        return tasks.isEmpty() ? null : tasks.getFirst().get("id").asString();
    }

    /** Polls the inbox for the role's checklist step (the BPMN leg is async) and resolves it. */
    private static void resolveChecklistStep(NovaForgeStack stack, String actorToken, String role) {
        String taskId = Awaitility.await().atMost(Duration.ofSeconds(120))
                .alias("checklist step reaches " + role)
                .until(() -> firstOpenTaskId(stack, actorToken, role), id -> id != null);
        stack.resolveTask(actorToken, taskId, "approve");
    }
}
