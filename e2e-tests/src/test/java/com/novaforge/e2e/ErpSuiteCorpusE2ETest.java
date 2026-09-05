package com.novaforge.e2e;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The Erp dogfood's <strong>existing acceptance corpus</strong>, re-run live against
 * the e2e stack as an automated regression (the 2026-09-03 exit re-run proved these
 * once by hand; this pins them in CI): the posting-immutability and period-lock
 * controls, the weighted-average inventory costing, the credit-note / EUR / dunning
 * A-R edges, the bank-feed webhook journey, and the book-to-post reconciliation exit.
 */
class ErpSuiteCorpusE2ETest {

    private static final String[] CORPUS = {
            "controls", "inventoryCosting", "creditAndCurrency", "bankFeed", "reconciliation"};

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
