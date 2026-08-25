# ADR-003: Scripting — GraalVM JS sandbox as the escape hatch

- **Status:** Accepted (ahead of implementation — docs-only; confirmed by the Phase 3 Script Engine v0 landing, PHASE-3 §6/T6)
- **Date:** 2026-08-21
- **Affects:** PLAN.md §3 (Script Engine row), §4 (Scripting row), §6 (risk: user scripts); ARCHITECTURE.md §2.5, §9; ADR-008 (scripts demoted to escape hatch); PHASE-3 §6

## Context

ADR-008 made declarative flow-IR the primary business-logic mechanism and demoted
scripts to an escape hatch — but the hatch must exist before Phase 4/5 builders
accumulate logic, or the script-ratio KPI has no denominator and the Phase 7
dogfood's procedural cases (weighted-average costing, FIFO lots, dunning) have no
outlet. The danger PLAN.md §6 names — user scripts crashing or hanging the platform —
must be made structurally impossible, not merely discouraged.

## Decision

1. **GraalVM JavaScript**, one `Context` per execution, with **CPU-time and heap
   caps plus a loop watchdog** — an infinite loop dies at its cap, never takes a
   thread pool hostage.
2. **No host I/O by default.** The whitelisted v0 surface is exactly `$record`,
   `$data.query` (the Data Runtime query API under the *calling user's*
   authorization — ARCHITECTURE.md §5 item 4), and `$log`. `$http` exists only
   inside the Phase 6 connector sandbox.
3. **Scripts are versioned artifacts** in the Metadata Service, on the same
   review/promotion path as definitions (ADR-008 #4); the Script Engine keeps no
   database of its own — executions are stateless.
4. **Sizing:** a thin v0 at Phase 3 tail (`novaforge-script-engine`, port 8084,
   internal — no gateway routes); warm context pools and tuning are deferred with
   demand (the p95 < 20 ms *warm* target of ARCHITECTURE.md §9 applies only once
   pools land).
5. **Script-ratio telemetry** per app version (ADR-008 #5); a script pattern
   appearing twice becomes a candidate primitive.

## Consequences

- The failure policy is uniform with flows (PHASE-3 §2): before-hook failure aborts
  the transaction; after-hook failures retry via the spine.
- The engine runs caller-context (unlike declarative flows' system principal —
  PHASE-3 §13 Q1), so a script can never exceed its authorizing user's grants.
  *(Amended 2026-08-26 at the seventh-pass review, for one surface PHASE-4 §7 pins:
  the Scheduler's recordless `script` target executes as the per-app system
  principal through the engine's service-gated scheduled leg — no user initiated
  the firing, so there is no authorizing user to bound; the write-path hook leg
  stays caller-context with no service-account fallback, exactly this ADR's rule.)*
- Escape-hatch discipline is measurable: the ratio surfaces in change-set review
  (Phase 8) and gates nothing directly — social pressure plus primitive harvesting.
