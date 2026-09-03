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

| Module | Entities (state machines where noted — here or §4) | Key mechanics |
|---|---|---|
| GL | `Account` (hierarchical lookup), `JournalEntry`/`JournalLine` (child), `AccountingPeriod` | Balanced validation (Phase 3); `DRAFT → POSTED` terminal state machine; **append-only**: posted entries never edited — corrections are reversal entries (PLAN.md §1 non-negotiable); gapless sequences for entry numbers (field-`default` sequence references — PHASE-1 §5) |
| AR/AP | `Customer`, `Vendor`, `Invoice`+lines, `CreditNote`, `Payment`, `DunningLetter` | Invoice machine `DRAFT → SUBMITTED → POSTED` (POSTED terminal — the §5 posting flow's `transitionState` target; `freezeOnTerminal` binds the journal entry, not the invoice — §2 GL / §3.1 — so settlement decrements `amountOutstanding` on the POSTED invoice, and aging selects rows by `status == 'POSTED' && amountOutstanding > 0`, the Phase 5 definition's filters — PHASE-5 §3); invoice numbering gapless; allocation flows (payment → invoice/credit memo); dunning as scheduled reports + letters (§11 Q2) |
| Inventory | `Item`, `Receipt`, `Issue`, `StockLedger` (append-only movements — a terminal machine with `freezeOnTerminal`, §3.1) | **Weighted-average costing** (PLAN.md §5; §11 Q1); stock ledger = child movements with running cost roll-ups (Phase 3 roll-ups) |
| Period close | Checklist driven by Phase 4 workflows | Tasks per close step (reconciliations, accruals); `AccountingPeriod` locking (§4) |
| Financial reports | Trial balance, A/R aging (the Phase 5 exit artifact), P&L sketch, executive dashboard | All Phase 5 definitions |
| Settings | Currencies, sequences, chart-of-accounts structure (the FX rate table is an app entity per the multi-currency pin below) | Settings metadata (ARCHITECTURE.md §2.3) |

**Multi-currency (a PLAN.md §1 non-negotiable) — pinned scope:** one book currency
per tenant; documents may be issued in a document currency converted at the rate on
the document date (rate table entity); GL posts in book currency; realized
gain/loss posts on settlement. Revaluation/unrealized gain is a logged gap, not v1.

## 3. Platform Enhancements (the two anticipated harvests, plus the gap-log harvests)

§§3.1–3.2 are *expected harvests* — PLAN.md §5 says the GL "may require platform
enhancements"; the spec pre-proposes their shape so implementation can land them
as versioned platform features (ADR-008 #2's growth path) mid-phase once the gap
is confirmed in practice. §§3.3–3.6 are the gap log's own growth: entries triaged
*accept-as-platform-feature* at review (§8), each specified here (their own
sections, per the SDD rule) before implementation.

### 3.1 `freezeOnTerminal` (posting/immutability primitive)

An `EntityDefinition` attribute (requiring a bound state machine) — when a record's
state machine sits in a terminal state, *all* writes to the record are rejected with
`RECORD_FROZEN("4013", 400)`: field updates and deletes on the record, and
master-detail child writes into it — children are independently addressable records
(PHASE-1 §5), but the freeze covers the parent's whole document, so a direct child
create or delete naming the frozen parent and an inline child array on a PATCH reject
identically, and the check runs before roll-up evaluation so a child write never
recomputes a frozen parent (PHASE-3 §3). (Today's Phase 4 state machines guard only
the state field.) This is what makes the journal append-only in fact, not convention.

### 3.2 `PeriodLock` (period locking)

Activated when an `AccountingPeriod` record reaches `CLOSED` (§4's state machine — the
period is an app entity, §2, not a Settings row); the Data Runtime write path rejects
dated-into-closed-period writes with the new code `PERIOD_LOCKED("4014", 400)`. How a
write's period is resolved (date-range lookup vs `periodId` reference) is spec'd in the
feature's harvest section per §8 before implementation.

Both land behind the same publish/compile machinery as every other definition, with
harness vocabulary to assert them (§9).

### 3.3 `createRecord` Step Results + Deep Template Resolution (the G-1 harvest, 2026-08-26)

Logged at the dogfood (G-1) and triaged *accept-as-platform-feature* per §8 — this
section is the versioned feature's spec; it lands as flow-IR growth compatible with
already-published graphs (nothing existing changes meaning).

- **The created record enters step scope.** A `createRecord` step's created record —
  the canonical field view plus the generated `id` — is captured into flow scope,
  addressed by later steps of the same graph through the template reference form
  `${record.<stepId>.<path…>}`: the exact mirror of the existing
  `${connector.<stepId>.<path…>}` convention (PHASE-6 §3), so the two step-result
  namespaces read identically. `${record.<stepId>.id}` is the created id — G-1's
  logged `${step.id}` proposal, pinned here in the namespaced form for that
  symmetry. The publish compiler checks the step reference exactly as connector
  references are checked: the addressed step must be a `createRecord` step of the
  same graph; the path below it resolves (or resolves empty) against the created
  view at run time — a promoted field is addressable, a failed create never lands
  in scope (the step failed, the failure policy already governs), and references
  from a resumed suspension resolve empty like every other pre-suspension step
  result. `iterate` bodies inherit the scope (a body step may address a
  pre-iterate `createRecord` result, per row); `updateRecord` deliberately stays
  out — the created record is the version-stamped result of a create, and an
  updated view would be a stale lie one write later.
- **`${…}` template resolution is deep.** Record-template resolution recurses into
  nested maps and arrays: an inline children array inside a `createRecord`
  template — the §2 GL shape, *create journal lines from templates* (§5) — resolves
  `${…}` references per row against the same bindings as top-level values (record
  fields, `id`, connector results, step results). The write path is unchanged:
  inline children were already accepted (PHASE-1 §5); only template resolution was
  top-level. The compile checks deepen identically — connector/record references
  anywhere in a template or payload tree are step-checked at publish.

### 3.4 `$decimal` — Exact Decimals in the Script Sandbox (the G-4 harvest, 2026-08-26)

Logged at the dogfood (G-4) and triaged *accept-as-platform-feature* per §8. GraalVM
JS computes in float64; money through scripts was exact only by corpus coincidence
(the logged workaround). The sandbox's closed surface grows one member:

- `$decimal.of(x)` constructs an exact decimal: from a **string** (`'50.00'`) or an
  **integral JS number** (converted exactly); a non-integral number rejects with
  guidance — float64 input is precisely what this surface exists to avoid, so it is
  never silently coerced.
- The decimal value carries a closed method set mirroring the expression DSL's
  numeric vocabulary (PHASE-2 Annex A): `add`, `subtract`, `multiply`, `divide`
  (scale-required — an explicit scale argument, banker's rounding), `negate`,
  `abs`, `round(scale)` (banker's rounding, the ARCHITECTURE.md §4 context), `min`,
  `max`, `compareTo`, `isZero`, `scale`, `toPlainString`/`toString`.
- **Crossing back:** a decimal value returned by the script (directly or inside the
  result) canonicalizes to its exact plain string — the same wire form monetary
  step templates pin (PHASE-3 §7) and the write path's decimal coercion accepts
  exactly (PHASE-1 §5). The PLAN.md §1 money rule holds *by construction* on the
  script path: nothing a script computes crosses as a float.
- No host classes, no I/O — the binding is pure arithmetic like `$log` is pure
  capture; the ADR-003 surface stays closed.

### 3.5 Conditional Roll-Ups (the G-15 harvest, 2026-08-27)

Logged live running the ERP corpus (G-15): a roll-up cannot say *which* children
count — `SUM(movements.qty)` aggregates every child row from its create, so a
roll-up-maintained `qtyOnHand` counted DRAFT movements the machine had not posted
yet, and the costing script (running at the POSTED update) read an Item that
already included the issue's own quantity. Triaged *accept-as-platform-feature*
per §8; this section is the feature's spec before implementation.

- **The roll-up grammar grows an optional WHERE clause** — same string encoding,
  a versioned growth compatible with already-published definitions (ADR-008 #2's
  path): `OP(relationship.field WHERE <condition> [AND <condition>…])`, COUNT
  without a field allowed exactly as before. A condition reuses the query DSL's
  leaf vocabulary verbatim (`field op value`; `eq ne gt gte lt lte in isNull` —
  `contains`/`ne` excluded: conditions bind machine-typed state values), joined
  by AND only in v1; condition fields resolve against the roll-up's child entity
  at save.
- **Both aggregation paths filter identically.** The store path ANDs the
  condition leaves onto the binding leaf of the aggregate/count query it already
  builds — the same parser, lowering, and canonical-value semantics every list
  and aggregate rides (`'true'/'false'` text for booleans, exact decimals for
  numbers); the inline-create path filters the in-memory child rows by the same
  leaf evaluation before aggregating (strings verbatim, numbers as exact
  decimals — never float compare).
- **Save validation** joins the validator rule matrix: grammar shape,
  relationship resolution on the parent, aggregated-field existence + numeric
  requirement, condition-field existence on the child entity — authoring errors
  reject at save/publish with field-scoped guidance, never silently aggregate a
  full set.
- **Nothing existing changes meaning**: a roll-up without a WHERE clause behaves
  byte-for-byte as before (pinned by regression).

### 3.6 System-Field Query Leaves (the G-5 harvest, 2026-08-27)

Logged at the dogfood (G-5): `$data.query` could not filter by `id` — system
fields are not authored fields, so the query-DSL leaf rejected them and scripts
scanned pages client-side to match identity (the logged workaround). Triaged
*accept-as-platform-feature* per §8.

- **The DSL leaf accepts `id` and `version`.** Filters and sorts name them like
  any authored field; values parse to their canonical forms at the door (UUID
  for `id`, integer for `version` — a malformed value rejects VALIDATION_FAILED
  with field scope) and lower to their projection columns through the shared
  pipeline. The other reserved names stay rejected (authored-data fields remain
  the only beyond these two operational keys — queries by audit metadata ride
  the trail instead, PHASE-3 §5).
- **The roll-up store path inherits the leaves for free** (its queries already
  ride the same parser), so §3.5's conditions may address child identity and
  version too.

### 3.7 `bind` — Lookup Targets in Flow Expression Scope (the G-2 harvest, 2026-09-03)

Logged at the dogfood (G-2): flow expressions bind the record's own fields
only — the weighted-average costing needed the parent `Item`'s roll-up values
(`inventoryValue / (qtyOnHand − qty)`), which no primitive could read, so the
corpus carried the plan's one budgeted script and the ≤ 20% ratio ceiling was
exceeded at exit (1 of 4 hooks) under G-2's reviewed exception (§1 rule 3).
Triaged *accept-as-platform-feature* per §8; this section is the feature's spec
before implementation.

- **The grammar grows one step: `bind`.** Params: `{ lookup }` — a LOOKUP field
  of the hook's entity (compile-checked: the field exists, is a lookup, and its
  target resolves within the app). At execution the step resolves the lookup's
  target record through the store — the same in-transaction read a caller's
  query would serve — and binds the target's canonical field view (the
  generated `id` included) into the graph's expression scope under the lookup
  field's apiName; later steps address it as dot-paths (`item.qtyOnHand`),
  which the shared `Bindings` resolver walks through the nested view. An
  absent, malformed, or unresolvable target binds the empty view — guards see
  null, the no-op shape the script's `item == null` check returned.
- **Compile semantics:** the graph's bound names join every expression slot's
  binding set, and the static arithmetic guard types `<lookup>.<field>`
  against the *target* entity's fields — `part.sku * 2` rejects at save with
  the offending expression named (the typed win the harvest carries); a bare
  bound name types as the id (text); unknown sub-fields stay UNKNOWN —
  fail-open, the evaluator remains their authority. A reference on a path that
  skips its bind (or precedes it) compiles and resolves empty at run time —
  §3.3's pre-suspension fail-open, unchanged.
- **Nothing existing changes meaning**: graphs without `bind` behave
  byte-for-byte as before (pinned by regression); the primitive is a versioned
  grammar growth per ADR-008 #2.
- **Adopted by the corpus** (2026-09-03): the ERP's costing hook re-authored as
  the declarative `bind` flow — receipt stamps `value = qty × unitCost`; a
  posted issue binds the Item and prices `unitCost = inventoryValue /
  (qtyOnHand − qty)`, `value = qty × unitCost` — the exact numbers the script
  produced (the `inventoryCosting` suite pins them unchanged), the script is
  demoted out, and the script-ratio ceiling holds (0 of 4 hooks).

## 4. Period Close Mechanics

- Close checklist = a Phase 4 workflow whose tasks are the close steps; owners are
  roles (controller, AR clerk, AP clerk).
- `AccountingPeriod.status: OPEN → CLOSING → CLOSED` (state machine — `CLOSED`
  deliberately non-terminal: the reopen transition below is a listed transition,
  satisfying the write-path check of PHASE-4 §3); `CLOSING`
  blocks new postings except close journals — close journals are `JournalEntry`
  records carrying an app-defined `closeJournal: true` flag, and the posting guard
  rejects dated-into-`CLOSING` writes unless it is set (app metadata, no platform
  special-casing); `CLOSED` activates `PeriodLock` (§3.2).
- Reopen is an audited `CLOSED → OPEN` transition that resolves only through its
  own Phase 4 approval flow (no back door). `PeriodLock` deactivates as its
  activation condition ceases to hold — the status is no longer `CLOSED` (§3.2) —
  and nothing is ever un-frozen: corrections inside a reopened period are still
  reversal entries (append-only, §2 GL; `freezeOnTerminal` binds the journal
  entry, not the period — §3.1).

## 5. What Runs Where (no surprises)

- Posting = a flow on `Invoice`/`JournalEntry` submit: branch → approval (Phase 4,
  SoD: preparer, approver, and poster are pairwise distinct) → `createRecord` journal lines from templates →
  `transitionState` to POSTED. No scripts expected on this path.
- Weighted-average costing = the §3.7 declarative `bind` flow (adopted
  2026-09-03, the G-2 harvest): the issue binds the Item's roll-up view and
  prices at `inventoryValue / (qtyOnHand − qty)` — historically §5's canonical
  escape-hatch case (one budgeted script), now declarative; the script-ratio
  ceiling holds (§9 item 7).
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
2. Immutability: PATCH on a posted entry rejected (`error(RECORD_FROZEN)`); a
   direct `JournalLine` child create or delete naming the posted entry rejects
   identically (document scope, §3.1); reversal entry posts and nets the
   original to zero.
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
| T2 | GL entities + posting machinery | §2 GL row + posting flows | §9 item 1 (partial: balanced posting) green |
| T3 | `freezeOnTerminal` + `PeriodLock` harvests | §3 platform features + `RECORD_FROZEN`/`PERIOD_LOCKED` codes | §9 items 2–3 green; features flag-gated |
| T4 | AR/AP + allocation + dunning | §2 AR/AP row | §9 items 5–6 green |
| T5 | Inventory + weighted-average costing | §2 Inventory row + the one budgeted script | §9 item 4 green; script ratio within budget |
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
