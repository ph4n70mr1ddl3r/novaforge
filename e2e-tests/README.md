# NovaForge E2E Tests

Whole-platform end-to-end cycles for the ERP dogfood — the O2C / P2P / R2R journeys
driven against the **real service topology**, not mocks:

- **Infrastructure** (Testcontainers, the compose stack's image pins): Postgres 16
  (per-service databases + the audit role, `deploy/postgres-init`'s shape), Redis 7,
  Kafka 4 (KRaft), Keycloak 26 with the realm import (the compose realm minus the
  `novaforge-auth` event listener — that provider only feeds the auth.* spine).
- **Services**: metadata, data-runtime, script-engine, audit, workflow, notification,
  reporting, integration — each booted from its **packaged Spring Boot jar** with the
  same defaults and ports the README's host-JVM bring-up uses. One stack per test JVM;
  `ErpSuiteCorpusE2ETest` (alphabetically first) carries the boot.
- **The cycles** ride public APIs only: the admin surface (tenants/users/roles), the
  runtime write path, the workflow inbox, the report run surface, and the builder's
  headless suite-run API (`TestRunner`'s scratch-tenant machinery is the platform's
  own — this module is the customer promotion pipeline's point of view).

| Test | Cycle | What runs |
|------|-------|-----------|
| `ErpOrderToCashE2ETest` | **O2C** | the `orderToCash` suite: invoice → approval → auto-journal → journal approval → payment application → trial balance / A-R aging |
| `ErpRecordToReportE2ETest` | **R2R** | the `recordToReport` suite (soft close, close-journal exemption, hard close, reopen, P&L/trial-balance pins) **plus** the BPMN `closeChecklist` journey: CLOSING event → three parallel reconciliation tasks resolved by their candidate roles → the controller's confirmation → lock → reopen, with Awaitility polling the async legs |
| `BuildRightProcureToPayE2ETest` | **P2P** | the BuildRight wave-1 corpus: happy path, threshold/approval edges, receiving/billing/settlement edges |
| `ErpSuiteCorpusE2ETest` | regression | the five Phase-7 acceptance suites re-run live (controls, inventoryCosting, creditAndCurrency, bankFeed, reconciliation) |

## Run

```bash
# podman rootless (the same contract the per-module Testcontainers suites document)
export DOCKER_HOST=unix:///run/user/$UID/podman/podman.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/run/user/$UID/podman/podman.sock

./mvnw -DskipTests package          # the stack boots the services' packaged jars
./mvnw -pl e2e-tests test           # the whole cycle corpus (stack boots once)

./mvnw -pl e2e-tests test -Dtest=ErpOrderToCashE2ETest   # one cycle
./mvnw verify -De2e.skip=true       # skip the e2e module for a fast inner loop
```

CI's `build` job runs the module as part of `./mvnw verify` (the reactor builds it
last — the stack needs the services' packaged jars).

## Failure diagnostics

- a RED suite prints its run artifact: every case, every failed step, every failed
  assertion (the harness's fixture creates fail loudly since this module landed —
  a silently failed GIVEN used to poison later `${Entity[n].id}` references);
- a service that never turns healthy dumps its log tail into the failure message;
- service stdout lands in `target/e2e-logs/<service>.log` (and each service's own
  file log beside it).

## What the first live runs flushed out (and this module now pins)

1. **P2P report permissions** — `runReport openBills/poSpend as procurementManager`
   could never pass: no role held `reportExecute` on the reports' entities. Fixed in
   `buildright-app.json` (static artifact gates cannot see permission-driven runs).
2. **Roll-up recompute skipped dependent formulas** — posting a payment moved
   `VendorBill.amountPaid` while `amountOutstanding` (a formula reading the rollup)
   stayed stale. Fixed in the engine: `recomputeRollupsIfChanged` now re-evaluates
   the parent's formulas after a roll-up moves (the create path's
   roll-ups-before-formulas ordering, extended to the child-write leg).
3. **Resolver race on fresh tenants** — `EntityResolver.index()` re-read the map
   after `refreshTenant`; the publish subscriber's concurrent evict NPE'd the first
   writes of a freshly published tenant. Fixed: the built entry is used directly.
4. **Validation errors were unaddressable** — record-validation failures rendered
   `field: "<Entity>.validations"`, so `expect: validation(<rule>)` could never
   match. The rule name is the field now.
5. **Suite authoring bugs only a live run catches** — a resolve of an already-
   consumed task index (`Task[0]` vs the resubmission's `Task[1]`), a missing
   re-observation before settlement assertions, and an unresolvable `SOD_VIOLATION`
   pin (the requester is denied at §13's task-access gate — `FORBIDDEN` — before any
   SoD check can fire). Fixed in the suites; docs synced.
