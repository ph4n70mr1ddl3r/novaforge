# ADR-004: Workflow — Flowable 7 embedded + native state machines on the write path

- **Status:** Accepted (ahead of implementation — docs-only; confirmed at Phase 4, PHASE-4 §2)
- **Date:** 2026-08-21
- **Affects:** PLAN.md §3 (Workflow row), §4 (Flowable 7 row), P4; ARCHITECTURE.md §2.6, §2.8; PHASE-4 (whole spec); resolves PHASE-4 §16 Q1

## Context

The ERP requires approvals with escalation/timers (BPMN-shaped work) *and* document
state machines (`DRAFT → POSTED`, append-only postings — PLAN.md §1
non-negotiables). Two failure modes were weighed: routing record writes through a
BPMN engine (the engine becomes a second write path that bypasses validations,
permissions, and RLS), or enforcing document lifecycle as metadata on the single
write path and using the engine only for coordination.

## Decision

1. **Flowable 7 embedded** in the Workflow Service (`novaforge-workflow-service`,
   port 8086) — approvals, timers/tasks, event-started processes; in-process BPMN
   timers stay inside Flowable, the Scheduler never fires them.
2. **State machines are metadata enforced on the Data Runtime write path** — the
   Workflow Service consumes state-change events (a `record.updated` whose diffs
   include the bound `stateField`); it never mutates records. There is no engine
   bypass to a record write.
3. **v1 authors BPMN as XML metadata** (import/editor-agnostic); the *visual*
   designer is deferred until demand (PHASE-4 §16 Q1's decision) — state machines +
   approvals cover the ERP-standard flows.
4. Engine-driven actions (flows, transitions, escalations) run as the per-app
   **system principal**; human actions run as the acting user — both audited
   (PHASE-3 §13 Q1, PHASE-4 §4).

## Consequences

- One write path, one set of validations/permissions everywhere: state-machine
  enforcement cannot be skipped by writing through the engine.
- `requestApproval` suspends its flow durably (the triggering write commits; the
  instance resumes on resolution) — no long-lived transaction across human latency.
- Approval modes grow as versioned features: v1 ships `any` + parallel-unanimous
  `all`; sequential chains arrive as a versioned mode (PHASE-4 §4).
- Flowable's schema lives in the Workflow Service's own database (per-service-DB
  pattern, PHASE-1 §6); its version upgrades are service-scoped.
