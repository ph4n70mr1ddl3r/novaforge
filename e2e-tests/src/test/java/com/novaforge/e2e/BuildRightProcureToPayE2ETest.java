package com.novaforge.e2e;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The <strong>procure-to-pay cycle</strong> (the BuildRight portfolio's wave-1 product
 * app), end to end against the live stack: the P2P app publishes into a fresh tenant
 * and its whole suite corpus runs through the headless harness —
 *
 * <ul>
 *   <li>{@code p2pHappyPath}: PO → approval → goods receipt (costed) → vendor bill
 *       (auto-journal) → settlement by posted-payment roll-up, pinned end to end;</li>
 *   <li>{@code p2pApprovalEdges}: the threshold gate (auto-approve below, manager
 *       approval above, SoD against self-approval, the reject → resubmit → re-approve
 *       edge);</li>
 *   <li>{@code p2pReceivingBillingEdges}: receipt/bill validations, terminal freezes,
 *       the draft-payment-does-not-settle rule, and the BR-G-2 overpay pin.</li>
 * </ul>
 *
 * <p>This is the corpus's first live run — its gates were static (artifact tests)
 * until the e2e stack existed.
 */
class BuildRightProcureToPayE2ETest {

    private static final String[] P2P_SUITES = {
            "p2pHappyPath", "p2pApprovalEdges", "p2pReceivingBillingEdges"};

    @Test
    void procureToPayCorpusRunsGreen() {
        NovaForgeStack stack = NovaForgeStack.stack();
        NovaForgeStack.Tenant tenant = stack.createTenant("e2e-p2p-" + System.currentTimeMillis());
        UUID appId = stack.publishApp(tenant, stack.readAppJson("buildright", "buildright-app.json"));
        for (String suite : P2P_SUITES) {
            stack.putSuite(tenant, appId, suite, stack.readAppJson("buildright", "suites/" + suite + ".json"));
        }
        for (String suite : P2P_SUITES) {
            stack.runSuiteGreen(tenant, appId, suite);
        }
    }
}
