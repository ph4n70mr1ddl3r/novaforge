# ADR-010: Builder test harness — tests as versioned metadata, gating promotion

- **Status:** Accepted
- **Date:** 2026-08-16
- **Affects:** PLAN.md P8 (App Lifecycle), §2 Core Abstractions, §3 (Metadata Service), §5 Phases 3/4/8; ARCHITECTURE.md §2.3; Phase 3 spec (PHASE-3-BUSINESS-LOGIC.md)

## Context

ADR-008 lists "generated tests" among the benefits of declarative logic, but today
nothing lets a builder verify *behavior* before promoting. Publish-time checks are
static — schema validity, referential integrity, expression compile-checks
(ARCHITECTURE.md §2.3, PHASE-2 spec §7) — so the first behavioral signal for a change
set is it running against real data. For ERP-grade confidence (PLAN.md §1
non-negotiables) and for P8's promise that change sets are reviewable artifacts,
promotion needs a green light stronger than "it compiles."

Salesforce gates deploys on test runs; the declarative analog is stronger here — if
logic, pages, and permissions are all versioned JSON, the tests can be too.

## Decision

1. **Test suites are metadata.** A `TestSuiteDefinition` — fixtures, steps,
   assertions — is a versioned definition owned by the Metadata Service
   (ARCHITECTURE.md §2.3), imported/exported and promoted with the app like every
   other artifact.
2. **Closed vocabulary, existing conventions.** Steps reuse ADR-008's
   primitive/template conventions (`createRecord`/`updateRecord` with `${…}`
   templates — the same interpolation the PHASE-2 spec §4 defines for UI action
   props); each step
   declares a role (`asRole`) and an expected outcome (`ok`, `error(code)`,
   `validation(rule)` — the platform error codes of `common-core`,
   PHASE-0 spec §5.2). Assertions are platform-expression predicates over step
   results (ADR-008 #3's one-language rule). The vocabulary grows only via versioned
   platform features, same policy as ADR-008 #2.

   ```jsonc
   // TestSuiteDefinition (illustrative — concrete encoding pinned by the
   // Phase 3 spec, PHASE-3-BUSINESS-LOGIC.md §7)
   {
     "id": "ts_order_rules",
     "cases": [
       {
         "name": "large_order_needs_approval",
         "fixtures": [ { "entity": "Customer", "template": { "name": "Acme" } } ],
         "steps": [
           { "op": "createRecord", "entity": "Order", "asRole": "order-clerk",
             "template": { "customerId": "${Customer[0].id}", "amount": 5000 },
             "expect": "ok" }
         ],
         "assert": [ "${Order[0].status} == 'PENDING_APPROVAL'" ]
       }
     ]
   }
   ```

3. **Execution goes through the single write path.** The Metadata Service hosts the
   runner; there is no test mode in the Data Runtime and no re-implemented engine. A
   run targets a scratch tenant pinned to a *published* draft version (the
   design-time/runtime split is preserved — the runtime never serves unpublished
   definitions), replays fixtures and steps as synthetic actors with role
   impersonation, and records a run artifact bound to that exact definition version.
   The scratch tenant is wiped per run: side effects (events, audit, sequences) land
   there and nowhere else, and synthetic actors have no notification channels, so
   nothing real is delivered.
4. **Promotion gate.** Promoting a change set requires a recorded green run of the
   app's suites against that definition version — blocking when the app defines
   suites, free otherwise (opt-in is authoring tests). Override is platform-admin
   only and audited.
5. **Phasing.** v1 lands with Phase 3 (validations, formula/roll-up fields, hook
   outcomes; permission-denial assertions ride on Phase 2 RBAC); approval and
   state-machine assertions with Phase 4 (`requestApproval`/`transitionState` are
   already in the v1 grammar, ADR-008 #2 — vocabulary: PHASE-4 spec §12); promotion gating, change-set review
   integration, and headless API runs with Phase 8 (mechanics:
   PHASE-8-LIFECYCLE.md §3–5).

## Consequences

- The harness is the behavioral complement to ADR-008's publish-time compiler:
  static checks answer "is this well-formed," suites answer "does it behave."
- Run artifacts (per-step traces, assertion results) are tenant data with retention
  (last N per definition version), surfaced in the builder UI and via API — the
  headless form is the hook for CI-driven promotion later.
- Determinism: period-lock and dunning-style tests need a controlled clock; the
  Phase 3 spec pins a per-run frozen clock (PHASE-3-BUSINESS-LOGIC.md §7).
- Suite coverage joins script ratio (ADR-008 #5) as an app-health signal in the
  builder.
- The Phase 3 scratch-tenant mechanism is the seed of P8's sandboxes — the same
  provisioning grows into full environment promotion rather than a second mechanism.
