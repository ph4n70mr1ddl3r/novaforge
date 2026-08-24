package com.novaforge.metadata;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * The Tests branch of PLAN.md §2 (ADR-010): builder test suites as versioned app
 * metadata — fixtures → steps → assertions, run against a scratch tenant pinned to a
 * candidate version through the generic runtime APIs. Pinned encoding per PHASE-3 §7:
 * step vocabulary v1 is createRecord/updateRecord/deleteRecord reusing ADR-008's
 * {@code ${…}} templates (monetary values as strings, never JSON numbers — PLAN §1);
 * {@code expect} is {@code ok | error(code) | validation(rule)}; assertions are
 * platform-expression predicates over step results.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TestSuiteDefinition(
        String apiName,
        String label,
        List<TestCase> cases) {

    public TestSuiteDefinition {
        apiName = apiName == null ? "suite" : apiName;
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public record TestCase(
            String name,
            List<Fixture> fixtures,
            List<Step> steps,
            List<String> assertExpressions) {

        public TestCase {
            fixtures = fixtures == null ? List.of() : List.copyOf(fixtures);
            steps = steps == null ? List.of() : List.copyOf(steps);
            assertExpressions = assertExpressions == null ? List.of() : List.copyOf(assertExpressions);
        }
    }

    /** A record created before the steps, addressable as {@code ${Entity[n].path}}. */
    public record Fixture(String entity, String asRole, Map<String, Object> template) {
    }

    /** One synthetic-actor operation through the generic write path. */
    public record Step(String op, String entity, String asRole, String recordId,
                       Map<String, Object> template, String expect) {

        /**
         * The v1 vocabulary: the Phase 3 record ops plus the growth — Phase 4's
         * {@code queryRecord}/{@code resolveTask} (§12) and {@code scanSla} (the
         * clock-advanced leg: the harness drives the scratch tenant's SLA scan as
         * of a governing instant — {@code advance} an ISO-8601 duration past now,
         * or an absolute {@code asOf} — so warn/breach/escalation assertions need
         * no sleeps), Phase 5's {@code runReport} (§9), and Phase 6's
         * {@code postWebhook} (§10: the harness signs with the scratch tenant's
         * hook secret, so suites exercise the real HMAC path — expect {@code ok}
         * or {@code error(SIGNATURE_INVALID)} for deliberately mangled signatures).
         */
        public static final java.util.Set<String> OPS =
                java.util.Set.of("createRecord", "updateRecord", "deleteRecord",
                        "queryRecord", "resolveTask", "runReport", "postWebhook", "scanSla");
    }
}
