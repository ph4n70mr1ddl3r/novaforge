package com.novaforge.e2e;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The <strong>order-to-cash cycle</strong>, end to end against the live stack: the Erp
 * dogfood app publishes into a fresh tenant, and its {@code orderToCash} suite runs
 * through the headless harness — invoice authored by the preparer (arClerk), approved
 * by the manager (SoD-safe), posted with its auto-journal, the journal approved and
 * posted, a payment applied against the invoice, and the A/R aging plus trial balance
 * pinning the settlement in the financial reports.
 *
 * <p>The scratch-tenant machinery (synthetic actors per app role, candidate publish,
 * frozen clock, DSL assertions) is the platform's own — this test exercises it the way
 * a customer's promotion pipeline would.
 */
class ErpOrderToCashE2ETest {

    @Test
    void orderToCashCycleRunsGreen() {
        NovaForgeStack stack = NovaForgeStack.stack();
        NovaForgeStack.Tenant tenant = stack.createTenant("e2e-o2c-" + System.currentTimeMillis());
        UUID appId = stack.publishApp(tenant, stack.readAppJson("erp", "erp-app.json"));
        stack.putSuite(tenant, appId, "orderToCash",
                stack.readAppJson("erp", "suites/orderToCash.json"));
        stack.runSuiteGreen(tenant, appId, "orderToCash");
        assertThat(appId).isNotNull();
    }
}
