package com.novaforge.e2e;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The Erp dogfood's <strong>acceptance corpus plus the workflow-edge corpus</strong>,
 * re-run live against the e2e stack as an automated regression (the 2026-09-03 exit
 * re-run proved the acceptance half once by hand; this pins it in CI): the
 * posting-immutability and period-lock controls, the weighted-average inventory
 * costing, the credit-note / EUR / dunning A-R edges, the bank-feed webhook journey,
 * and the book-to-post reconciliation exit — then the edge suites: GL numbering and
 * state-machine walls, AR document validation and rejection edges, the costing
 * edges (draft movements, empty-stock and explicit-cost issues), and the
 * close-checklist workflow (the G-11/G-17 harvests: the CLOSING event-start forks
 * the three reconciliation tasks; the parallel join gates the controller's confirm).
 */
class ErpSuiteCorpusE2ETest {

    private static final String[] CORPUS = {
            "controls", "inventoryCosting", "creditAndCurrency", "bankFeed", "reconciliation",
            "glLedgerEdges", "arDocumentEdges", "inventoryCostingEdges", "closeChecklist"};

    @Test
    void erpAcceptanceCorpusRunsGreen() {
        NovaForgeStack stack = NovaForgeStack.stack();
        NovaForgeStack.Tenant tenant = stack.createTenant("e2e-erp-" + System.currentTimeMillis());
        UUID appId = stack.publishApp(tenant, stack.readAppJson("erp", "erp-app.json"));
        for (String suite : CORPUS) {
            stack.putSuite(tenant, appId, suite, stack.readAppJson("erp", "suites/" + suite + ".json"));
        }
        for (String suite : CORPUS) {
            stack.runSuiteGreen(tenant, appId, suite);
        }
    }
}
