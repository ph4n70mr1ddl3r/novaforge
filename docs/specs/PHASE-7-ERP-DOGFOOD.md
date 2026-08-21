# Phase 7 — ERP Dogfood: Implementation Specification

> Complete, implementation-driving spec for building the mini-ERP **on the platform
> itself** — the proving ground (PLAN.md §5). Unlike Phases 0–6, almost nothing here
> is platform code: the deliverable is an app authored as metadata, the discipline
> is gap harvesting, and the two anticipated platform enhancements are the only
> expected platform work. The PHASE-4 §1 spec-driven agreement applies, with one
> addition: the ERP app's ADR-010 suites are its acceptance contract (§9).
>
> | | |
> |---|---|
> | Status | Decided (open questions resolved 2026-08-21) |
> | Date | 2026-08-16 |
> | Owner | Platform team + product owner |
> | Estimate | 6–8 weeks (per PLAN.md §5) |
> | Depends on | Phases 1–6 complete (the whole platform) |

## 1. Objective & Exit Criteria

Deliver the Phase 7 exit: *book invoice → auto journal → post → financial reports
reconcile* (PLAN.md §5) — with every missing platform capability captured as a
prioritized backlog item, not worked around silently.

**Dogfood rules (binding for the whole phase):**
1. **Zero handwritten application code** (the mission, PLAN.md preamble). Entities,
   pages, flows, state machines, reports, roles, integrations — all metadata through
   the builder. The only code permitted: escape-hatch scripts per ADR-008, budgeted
   (rule 3).
2. **Every gap becomes a log entry** before any workaround: `{ area, blocker,
   workaround, proposedPrimitiveOrFlag, priority }` — triaged weekly; the gap log is
   a phase deliverable, reviewed at exit.
3. **Script-ratio budget:** ERP hooks keep script ratio ≤ 20% (ADR-008 #5's KPI with
   a concrete ceiling); exceeding it on a module triggers a primitive-candidate
   review, not quiet script growth.
4. Every module ships with builder-authored suites (§9) — an unverified module is
   not done.

Out of scope: platform features not demanded by the ERP (PLAN.md §6's scope rule
runs in reverse here — the dogfood *is* the filter); FIFO costing (deferred — §11 Q1,
resolved; a logged gap); full dunning
automation (deferred — §11 Q2, resolved); the wizard/tab/mobile UI backlog (pulled only if this phase
demands it, per PHASE-2 §1).

## 2. ERP App Scope (the metadata to author)

| Module | Entities (all with state machines where noted) | Key mechanics |
|---|---|---|
| GL | `Account` (hierarchical lookup), `JournalEntry`/`JournalLine` (child), `AccountingPeriod` | Balanced validation (Phase 3); `DRAFT → POSTED` terminal state machine; **append-only**: posted entries never edited — corrections are reversal entries (PLAN.md §1 non-negotiable); gapless sequences for entry numbers |
| AR/AP | `Customer`, `Vendor`, `Invoice`+lines, `CreditNote`, `Payment`, `DunningLetter` | Invoice numbering gapless; allocation flows (payment → invoice/credit memo); dunning as scheduled reports + letters (§11 Q2) |
| Inventory | `Item`, `Receipt`, `Issue`, `StockLedger` (append-only movements) | **Weighted-average costing** (PLAN.md §5; §11 Q1); stock ledger = child movements with running cost roll-ups (Phase 3 roll-ups) |
| Period close | Checklist driven by Phase 4 workflows | Tasks per close step (reconciliations, accruals); `AccountingPeriod` locking (§4) |
| Financial reports | Trial balance, A/R aging (the Phase 5 exit artifact), P&L sketch, executive dashboard | All Phase 5 definitions |
| Settings | Currencies, sequences, chart-of-accounts structure (the FX rate table is an app entity per the multi-currency pin below) | Settings metadata (ARCHITECTURE.md §2.3) |

**Multi-currency (a PLAN.md §1 non-negotiable) — pinned scope:** one book currency
per tenant; documents may be issued in a document currency converted at the rate on
the document date (rate table entity); GL posts in book currency; realized
gain/loss posts on settlement. Revaluation/unrealized gain is a logged gap, not v1.

## 3. The Two Anticipated Platform Enhancements

Both are *expected harvests* — PLAN.md §5 says the GL "may require platform
enhancements"; the spec pre-proposes their shape so implementation can land them
as versioned platform features (ADR-008 #2's growth path) mid-phase once the gap
is confirmed in practice:

1. **`freezeOnTerminal` (posting/immutability primitive):** an `EntityDefinition`
   attribute (requiring a bound state machine) — when a record's state machine sits
   in a terminal state, *all* writes to the record are rejected with
   `RECORD_FROZEN("4013", 400)` (today's Phase 4 state machines guard only the
   state field). This is what makes the journal append-only in fact, not
   convention.
2. **`PeriodLock` (period locking):** activated when an `AccountingPeriod` record
   reaches `CLOSED` (§4's state machine — the period is an app entity, §2, not a
   Settings row); the Data Runtime write path rejects dated-into-closed-period
   writes with the new code `PERIOD_LOCKED("4014", 400)`. How a write's period is
   resolved (date-range lookup vs `periodId` reference) is spec'd in the feature's
   harvest section per §8 before implementation.

Both land behind the same publish/compile machinery as every other definition, with
harness vocabulary to assert them (§9).

## 4. Period Close Mechanics

- Close checklist = a Phase 4 workflow whose tasks are the close steps; owners are
  roles (controller, AR clerk, AP clerk).
- `AccountingPeriod.status: OPEN → CLOSING → CLOSED` (state machine); `CLOSING`
  blocks new postings except close journals — close journals are `JournalEntry`
  records carrying an app-defined `closeJournal: true` flag, and the posting guard
  rejects dated-into-`CLOSING` writes unless it is set (app metadata, no platform
  special-casing); `CLOSED` activates `PeriodLock` (§3.2).
- Reopen is an audited admin action that requires un-freezing reversals — modeled
  as its own approval flow (Phase 4), not a back door.

## 5. What Runs Where (no surprises)

- Posting = a flow on `Invoice`/`JournalEntry` submit: branch → approval (Phase 4,
  SoD: preparer, approver, and poster are pairwise distinct) → `createRecord` journal lines from templates →
  `transitionState` to POSTED. No scripts expected on this path.
- Weighted-average costing = an `iterate` flow over receipt lots with rounding
  chains — the canonical ADR-008 escape-hatch case; one script, counted, reviewed
  against the budget (rule 3).
- Dunning = scheduled reports (Phase 5 scheduler target) + letter generation; the
  schedule logic is expressions over aging buckets.
- Bank feed = the Phase 6 exit connector driven by a scheduled flow (a
  `callConnector` step inside it — the Scheduler's target set is
  `flow | script | processStart | report`, PHASE-4 §7; there is no `connector`
  target, so the connector always rides a flow).

## 6. UI Scope

ERP users live in the runtime UI: auto-generated pages + L2 overlays only (ADR-009's
layering is the point — if the ERP needs a bespoke page, that's a gap log entry).
Builders use the Phase 2–5 editors. Any wizard/tab/mobile need discovered here
enters the PHASE-2 §1 backlog with evidence.

## 7. Data & Suites Baseline

- Seed data as fixtures: chart of accounts, rate table, customers/vendors/items,
  opening balances — all fixture metadata (ADR-010), loadable into scratch and dev.
- The ERP app's suite corpus is the acceptance contract: per-module suites + the
  end-to-end reconciliation suite (§9).

## 8. Gap-Harvest Protocol

Weekly triage (platform team + product owner): each logged gap gets
`accept-as-platform-feature | backlog | wontfix-with-workaround`; accepted items
become versioned platform features with their own spec section here before
implementation (the SDD rule applies to enhancements too). The two §3 candidates
are pre-accepted pending confirmation; everything else earns its place.

## 9. Test-Harness Verification (the acceptance contract)

1. **Reconciliation suite (the exit):** book invoice → journal auto-created and
   balanced → approval → POSTED → trial balance nets to zero; A/R aging totals
   equal the GL control account (via `runReport` assertions, PHASE-5 §9).
2. Immutability: PATCH on a posted entry rejected (`error(RECORD_FROZEN)`);
   reversal entry posts and nets the original to zero.
3. `PeriodLock`: posting into a closed period rejected (`error(PERIOD_LOCKED)`);
   close checklist completes only with all tasks resolved.
4. Costing: receipt → issue at weighted average, decimal-exact through the rounding
   chain; stock ledger roll-ups match item valuation.
5. SoD: preparer cannot approve own invoice (`error(SOD_VIOLATION)`).
6. Multi-currency: EUR invoice at date-rate posts in USD book currency; settlement
   gain/loss posts automatically.
7. Script ratio reported per module at exit review (≤ 20%, rule 3).

## 10. Task Breakdown

| # | Task | Content | Acceptance criteria |
|---|---|---|---|
| T1 | Tenant/app bootstrap + seed fixtures | App, roles, COA, rates, parties, opening balances | Fixtures load into a scratch tenant |
| T2 | GL entities + posting machinery | §2 GL row + posting flows | §9.1 (partial: balanced posting) green |
| T3 | `freezeOnTerminal` + `PeriodLock` harvests | §3 platform features + `RECORD_FROZEN`/`PERIOD_LOCKED` codes | §9.2–.3 green; features flag-gated |
| T4 | AR/AP + allocation + dunning | §2 AR/AP row | §9.5–.6 green |
| T5 | Inventory + weighted-average costing | §2 Inventory row + the one budgeted script | §9.4 green; script ratio within budget |
| T6 | Period close workflows | §4 checklist + reopen approval | Close suite green with frozen clock |
| T7 | Financial reports + dashboard | Trial balance, aging, P&L sketch, dashboard | Reconciliation assertions green |
| T8 | Bank-feed connector wiring | Reuse Phase 6 exit connector as scheduled job | Payments sync visible in aging |
| T9 | Exit review + gap-log triage | Walk PLAN §5 exit; present the gap log | Demo reconciles; gap log dispositioned |

Dependency order: T1 → T2 → (T3, T4) → T5 → T6 → T7 → (T8, T9); T3 can start as
soon as T2 confirms the gaps.

## 11. Resolved Questions (decided 2026-08-21, per the recommendations; both were non-blocking scope pins)

- **Q1 — Costing method first: DECIDED — weighted-average only.** FIFO is a
  logged gap — the lot machinery is already half-present (StockLedger) — and joins
  as a versioned feature when the gap log demands it.
- **Q2 — Dunning scope: DECIDED — letters + scheduler.** Full escalation chains
  are a Phase 4 escalation reuse if the dogfood demands them.
