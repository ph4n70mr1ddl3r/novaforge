# Phase 7 Gap Log — the ERP Dogfood

> A phase deliverable (PHASE-7 §1 rule 2): **every gap becomes a log entry before any
> workaround**. Triage at the weekly review assigns `accept-as-platform-feature |
> backlog | wontfix-with-workaround`; accepted items become versioned platform features
> with a spec section here before implementation (§8). The two §3 candidates were
> pre-accepted, confirmed by the dogfood, and shipped as platform features this phase.

| # | Area | Blocker | Workaround in the ERP v1 | Proposed primitive / flag | Priority | Disposition |
|---|------|---------|--------------------------|---------------------------|----------|-------------|
| G-1 | P3 Business Logic | **Auto-journal from an invoice:** `createRecord` cannot bind lines to the entry it just created — the created record's id is not captured into flow scope, and `${…}` template resolution is top-level only, so an inline `lines` array inside a `createRecord` template passes through unresolved (§5's "createRecord journal lines from templates" is not expressible) | The ar clerk books the invoice's journal as its own `JournalEntry` with inline lines (suite templates interpolate deeply, so the harness/API path works); the invoice flow posts the invoice itself | `createRecord` captures the created id into step scope (`${step.id}`) + nested template resolution for children arrays | **high** | accept-as-platform-feature (versioned, next platform increment) |
| G-2 | P3 Business Logic | **Cross-record arithmetic in flows:** `updateRecord` templates interpolate (`${field}`) but cannot compute over multiple records — the receipt-side weighted-average re-costing of `Item.unitCost` (`(qtyOld×costOld + qtyNew×costNew) / (qtyOld + qtyNew)`) is inexpressible declaratively | Roll-up maintained `inventoryValue`/`qtyOnHand` keep the running average *derivable*; the one budgeted script costs issues from those roll-ups (§5's canonical escape-hatch case) | expression-valued template slots (or a `computeField` primitive evaluated over queried bindings) | high | backlog (the script covers v1 within budget) |
| G-3 | P1 Data Modeling | **Rollup fields on `amountOutstanding`:** posting should stamp `amountOutstanding = total` once, but defaults evaluate before children persist and the resume write only carries the state field | The suite/clerk sets `amountOutstanding` at invoice create; settlement decrements it on the POSTED invoice (per §2's pin — the invoice is not frozen, only its journal entry is; the bankFeed suite pins the decrement) | rollup-derived initial values, or a `stampOnTransition` hook context | medium | backlog |
| G-4 | Script sandbox | **Decimal fidelity in scripts:** GraalVM JS computes in float64; money through `$data.query` reads and script arithmetic is not BigDecimal-exact by construction (PLAN.md §1 non-negotiable) | The costing script's arithmetic stays exactly representable in the dogfood corpus (÷ by whole quantities); assertions pin the decimal-exact roll-ups the *engine* computes, not script floats | a `$decimal` BigDecimal binding in the sandbox surface | **high** | accept-as-platform-feature |
| G-5 | P1/P3 | **`$data.query` cannot filter by `id`** (system fields are not authored fields, so the query-DSL leaf rejects them) — the costing script lists Items and matches `id` in JS | Script scans the first page (≤200 rows) and matches client-side | allow `id` (and `version`) as filterable system fields in the DSL leaf | medium | backlog |
| G-6 | P3 hooks | **Once-only hook triggers:** the `afterSave` posting flow re-suspends on every save of a `SUBMITTED` record (a memo edit re-requests approval) | Guards read the state field, and the journeys save `SUBMITTED` exactly once; rejection leaves the record `SUBMITTED` (a resubmit re-suspends — acceptable, but noisy) | idempotency flag on `requestApproval` (suspend once per record+step) | medium | backlog |
| G-7 | P2 UI Builder | **No builder/runtime UI** (the Phase 2 remainder): the ERP is authored as JSON through the definition APIs; runtime users have no pages yet | `apps/erp/*.json` + suites are the authored artifact; `ErpAppArtifactTests` gates them through the exact save/compile checks the builder would run | (the Phase 2 builder shell — already the tracked remainder) | **high** | closed — the Phase 2 shells landed after the dogfood (builder + runtime), and the 2026-08-24 review closed Phase 4 §11's remaining surfaces |
| G-8 | P5 Reporting | **Weighted-average unit cost as a report column:** `avg(unitCost)` is not the weighted average; the true average is `inventoryValue / qtyOnHand`, an aggregate-of-aggregates the v1 report vocabulary cannot express | `inventoryValuation` reports the value/qty totals; the average is derivable (and asserted in the suite by dividing the literals) | computed (ratio) aggregate columns in `ReportDefinition` | low | wontfix-with-workaround |
| G-9 | Multi-currency | **Realized gain/loss on settlement** requires an allocation flow over payments × invoices with FX revaluation at payment-date rates — expressible with today's primitives only as per-case scripts, blowing the script budget | v1 scope pins book currency USD, document-currency conversion at the document rate (`fxRate` carried, GL posts in book currency); settlement gain/loss is logged, not automated (§2's pin: revaluation/unrealized is a logged gap, and realized joins it) | an `allocate` primitive (payment → invoices/credit memos, FIFO or specific) | high | backlog |
| — | FIFO costing | Deferred by §11 Q1 (resolved): weighted-average only; lot machinery half-present in `StockLedger` | — | `costingMethod` flag on Item + lot allocation | low | backlog (versioned feature) |
| — | Full dunning automation | Deferred by §11 Q2 (resolved): letters + scheduler only | `nightlyAging` scheduled delivery is the v1 dunning surface | escalation chains (Phase 4 reuse) | low | backlog |
| G-10 | P4 Workflow | **Reopen resolves only through its own approval flow (§4):** transition guards and hook guards evaluate the record's *current* (post-merge) state — a `status == 'OPEN'` guard cannot distinguish the audited `CLOSED → OPEN` reopen from the initial create, so gating reopen with `requestApproval` would suspend every period creation (and SoD would block the creating controller from approving it) | The reopen edge is a listed, audited machine transition an `accountingManager`/`controller` write drives — audited, but not approval-gated | transition-scoped hooks (`trigger: onTransition`, carrying prior state) or prior-state bindings in guards | medium | backlog |
| G-11 | ADR-010 harness | **The close-checklist suite leg:** §9 item 3's "close checklist completes only with all tasks resolved" needs the suite to observe the event-started process's tasks, but event-starts ride the spine asynchronously and the harness has no await/poll step — a `queryRecord Task` right after the CLOSING update races | The checklist's all-tasks-then-confirm shape is structural (the BPMN parallel join gates `confirmClose`) and pinned service-level by `BpmnProcessTests`; the authored suite covers the CLOSING write gate instead | an `awaitTasks`/poll step op (bounded retries over the inbox query) | medium | backlog |

## Confirmed harvests (§3 — shipped as platform features this phase)

- **`freezeOnTerminal` (§3.1)** — confirmed by the GL module: `JournalEntry` and
  `StockLedger` freeze their whole documents in terminal states
  (`RECORD_FROZEN` 4013 on updates, deletes, and child writes naming the frozen
  parent); the `Invoice` deliberately does **not** freeze — §2's pin: freeze binds
  the journal entry, not the invoice, so settlement decrements `amountOutstanding`
  on the POSTED invoice (the `bankFeed` suite's leg). Verified by
  `FreezePeriodTests` + the `controls` suite.
- **`PeriodLock` (§3.2)** — confirmed by period close: dated writes resolve their
  period by date-range lookup; `CLOSED` periods reject with `PERIOD_LOCKED` 4014;
  **`CLOSING` blocks postings unless `closeJournal` is set** (§4's soft close — the
  `restrictedStatus`/`exemptField` growth, authored app metadata, no platform
  special-casing); reopening (§4's audited transition) deactivates the lock;
  nothing is ever un-frozen. Verified by `FreezePeriodTests` + the `controls` suite.

## Script ratio at exit review (§9 item 7)

3 hooks authored: 2 declarative posting flows + 1 budgeted script (weighted-average
issue costing). Logic surface otherwise declarative: 5 validations, 1 formula, 4
roll-ups, 3 machines. **Script ratio ≤ 20% holds** (rule 3; the one script is §5's
canonical escape-hatch case).
