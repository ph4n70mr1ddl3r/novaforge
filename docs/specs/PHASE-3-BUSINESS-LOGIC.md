# Phase 3 — Business Logic Engine: Implementation Specification

> Detailed spec for the flow-IR primitive engine, write-path expressions, the Kafka
> event spine with its first consumers, and the builder test harness. Product context:
> [PLAN.md](../../PLAN.md) §5 Phase 3. Logic decision:
> [ADR-008](../adr/ADR-008-declarative-first-logic.md); test harness:
> [ADR-010](../adr/ADR-010-builder-test-harness.md); service design:
> ARCHITECTURE.md §2.3–§2.5, §2.8.
>
> | | |
> |---|---|
> | Status | Draft for review |
> | Date | 2026-08-16 |
> | Owner | Platform team |
> | Estimate | 4–5 weeks (per PLAN.md §5) |
> | Depends on | Phase 1 (write path, event seam, publish machinery) + Phase 2 (expression DSL v1 — both engines + conformance suite; RBAC roles for `asRole`; builder shell) |

## 1. Objective & Exit Criteria

Deliver the Phase 3 exit: *order totals computed, inventory reserved via a hook, no
code — verified by a builder-authored suite* (PLAN.md §5).

In scope: the flow-IR engine with the closed primitive set (ADR-008); expression
validation rules, formula fields, and roll-up summaries evaluated on the write path;
the Kafka event spine (record events, audit emission, `metadata.published` rebind);
the `event-schemas` lib; **Audit Service v1** and **Script Engine v0** — the two
landings the roadmap left implicit, pinned in §5/§6; the builder test harness v1
(ADR-010) with its scratch-tenant runner; declarative authoring editors in the
builder UI; and the tracing/log backend expansion of PHASE-0 §8.

Out of scope: approvals and state machines — `requestApproval`/`transitionState`
stay grammar-fixed and dormant until Workflow lands (Phase 4), `callConnector` until
Integration (Phase 6); scheduled jobs (Phase 4, Scheduler); query-path hooks
(deferred until a concrete need — PLAN.md §5); record-level sharing rules (they land
Phase 3–4 as ERP flows demand — PHASE-2 §9; this phase's exit needs none —
landing: PHASE-4 spec §10);
notification delivery (no Notification
Service yet; scratch actors have no channels anyway — ADR-010 #3); promotion gating
and headless CI runs (Phase 8 — ADR-010 #5); report targets (Phase 5).

## 2. Flow IR & the Primitive Engine (ADR-008 #1–2)

- Event-hook rules are `RuleDefinition`s (the event-hook branch,
  ARCHITECTURE.md §2.3): `{ trigger, flow }` with triggers v1 =
  `beforeSave | afterSave | beforeDelete | afterDelete` (record scope).
- Flow IR v1: a typed step graph as versioned JSON — `{ id, op, params, next }`
  nodes; `branch` adds `onTrue`/`onFalse`; `iterate` wraps a `body` over a
  relationship path. Entry point = the trigger; cycles are rejected at publish
  (the graph is a DAG).
- Primitive semantics — the closed set of ADR-008 #2; additions are versioned
  platform features, never per-app code:

| Primitive | Params | Phase 3 state |
|---|---|---|
| `setField` | field + expression | executable |
| `createRecord` / `updateRecord` | entity + `${…}` template | executable |
| `publishEvent` | name + payload template | executable |
| `branch` | expression guard | executable |
| `iterate` | relationship path + body | executable |
| `requestApproval` | — | grammar-fixed; activates Phase 4 |
| `transitionState` | — | grammar-fixed; activates Phase 4 |
| `callConnector` | — | grammar-fixed; activates Phase 6 |

- **Publish-time compiler** in the Metadata Service (ADR-008's new role,
  ARCHITECTURE.md §2.3): reference/type-check every step — fields exist, guard and
  `setField` expressions compile (Phase 2 JVM engine), templates resolve, iterate
  paths are relationships — then store the compiled artifact with the version. The
  runtime executes compiled graphs; it never re-parses per request (ADR-008 #1,
  p95 < 150 ms write target).
- **Failure policy** (ARCHITECTURE.md §2.5), uniform for flows and scripts:
  `beforeSave`/`beforeDelete` failure aborts the transaction; `afterSave`/
  `afterDelete` failures retry via the spine with idempotent consumers.
- Hooks v1 run on the write path only (PLAN.md §5). Execution context is Q1 (§13).

## 3. Expressions on the Write Path

The Data Runtime evaluates in-process via the shared `expression-dsl` JVM engine
(PLAN.md §3; not the Script Engine — ARCHITECTURE.md §2.5), activating the schema
slots Phase 1 left inert:

- **Validation rules:** record-scope expressions with a `message`, stored on the
  entity definition (ARCHITECTURE.md §3 sketch); they extend — do not replace —
  Phase 1 field constraints. API failure renders `VALIDATION_FAILED` problem+json;
  tests see it as `validation(rule)` (§7).
- **Formula fields:** evaluated at write time and stored, never computed on read;
  formula fields are implicitly `readonly`, so app writes are rejected (the Phase 1
  rule). Time-dependent functions (e.g. `today()`) are compile-rejected in formula
  fields — a stored value would go stale between writes; run-time evaluation
  contexts (report bucket expressions, PHASE-5 §3) may take them, resolved against
  the governing clock.
- **Roll-up summaries:** parent aggregates (`SUM/COUNT/MIN/MAX/AVG`) over child
  collections, recomputed in the child's write transaction — the sketch's
  `SUM(lines.debit)` is the canonical case. Strategy is Q2 (§13).

The write path becomes the full ARCHITECTURE.md §2.4 chain: resolve metadata →
authorize → defaults → formula/roll-up evaluation → validation rules → hooks →
persist with optimistic locking → events (§4) → shaped projection.

## 4. Event Spine (Kafka)

- **Producer:** the Phase 1 `DomainEventPublisher` no-op binds to Kafka. Events are
  appended to a transactional outbox in the *same* transaction and relayed
  post-commit — that is what makes the documented at-least-once semantics
  (PLAN.md §6) real: the relay retries, consumers dedupe on `(event_id, consumer)`
  (ARCHITECTURE.md §6).
- **Events:** `record.created/updated/deleted` (tenant, entity, id, actor, field
  diffs for audit) and audit events (§5). `metadata.published` rebinds from Redis
  pub/sub to the spine — same envelope, consumer-side swap only (PHASE-1 spec §4);
  the Redis channel is retired.
- **Topology (Q3, §13):** shared topics `novaforge.record.*` / `novaforge.metadata.*`,
  partition key `tenant_id:entity_id` (per-record ordering), consumer groups per
  service, tenant filtering at the consumer.
- **`event-schemas` lib lands** (the PHASE-0 §5.4 charter): contracts for all of the
  above, with round-trip tests.
- Trace context propagates in Kafka headers (ARCHITECTURE.md §6) via the
  `security-context` constants staged in Phase 1.

## 5. Audit Service v1 (landing pinned)

PHASE-2 §9 promised the durable audit trail "lands with the Phase 3 event spine" —
so the consumer lands with the producer:

- Pure consumer of the spine (ARCHITECTURE.md §1 principle 3); append-only Postgres
  store partitioned by month (ARCHITECTURE.md §2.8) in its own database on the
  compose instance — the PHASE-1 §6 per-service pattern; S3/Parquet cold offload
  stays deferred.
- Captures the shapes Phase 2 defined: write events with field-level diffs, auth
  events (published to the spine by a Keycloak event listener — deployed config
  under `deploy/`, not bespoke service code, per ARCHITECTURE.md §7's
  identity-is-deployed stance), permission changes, definition publishes
  (ARCHITECTURE.md §5 item 5).
- New service `novaforge-audit-service` (port 8085); read API for admins at
  `/api/v1/audit/**` via the gateway. Audit *UI* is a later phase. Append-only is
  enforced mechanically — the store's role has no UPDATE/DELETE grants.

## 6. Script Engine v0 (escape hatch — landing pinned)

ADR-008 sequences the script engine *after* the flow engine, sized to escape-hatch
demand. Pin: a thin **v0 at Phase 3 tail** — the escape hatch must exist before
Phase 4/5 builders accumulate logic, or the script-ratio KPI has no denominator and
the Phase 7 dogfood's procedural cases (weighted-average costing, FIFO lots,
dunning — ADR-008's known limit) have no outlet.

- New service `novaforge-script-engine` (port 8084; internal — hooks invoke it, no
  gateway routes in v0). ADR-003 moves Proposed → Accepted with a written file at
  landing (the ARCHITECTURE.md §8 convention).
- v0 scope: GraalVM JS, `Context` per execution, CPU-time and heap caps plus a loop
  watchdog, no host I/O, whitelisted surface `$record`, `$data.query` (the Data
  Runtime query API under the *calling user's* authorization — ARCHITECTURE.md §5
  item 4), `$log`. Scripts are versioned artifacts on the same review/promotion path
  as definitions (ADR-008 #4) and attach to the same triggers as flows.
- Deferred with demand: warm context pools (the p95 < 20 ms *warm* target of
  ARCHITECTURE.md §9 applies once they land), `$http` inside the connector sandbox
  (Phase 6), pool tuning.
- **Script ratio** telemetry per app version (ADR-008 #5): share of hooks implemented
  without scripts; a script pattern appearing twice becomes a candidate primitive.

## 7. Builder Test Harness v1 (ADR-010)

The concrete `TestSuiteDefinition` encoding ADR-010 left illustrative, pinned:

```json
{ "id": "ts_order_fulfillment",
  "cases": [
    { "name": "order_total_rolls_up_lines",
      "fixtures": [ { "entity": "Customer", "template": { "name": "Acme" } } ],
      "steps": [
        { "op": "createRecord", "entity": "Order", "asRole": "order-clerk",
          "template": { "customerId": "${Customer[0].id}",
                        "lines": [ { "item": "WIDGET", "qty": 2, "price": "25.00" } ] },
          "expect": "ok" } ],
      "assert": [ "${Order[0].total} == 50.00" ] },
    { "name": "unbalanced_entry_rejected",
      "steps": [
        { "op": "createRecord", "entity": "JournalEntry", "asRole": "accountant",
          "template": { "lines": [ { "debit": "100.00", "credit": "90.00" } ] },
          "expect": "validation(balanced)" } ],
      "assert": [] }
  ] }
```

- Step vocabulary v1: `createRecord`, `updateRecord`, `deleteRecord`, reusing
  ADR-008's template/`${…}` conventions (the same interpolation PHASE-2 §4 defines
  for action props). `expect`: `ok` | `error(code)` | `validation(rule)` — the
  common-core codes (PHASE-0 §5.2). Monetary values in step templates are strings,
  never JSON numbers (PLAN.md §1 money rule — JSON numbers are floats); assertion
  expressions use the DSL's exact decimal literals (`50.00` above).
- Assertions are platform-expression predicates over step results
  (`${Entity[n].path}` references).
- Runner in the Metadata Service (ARCHITECTURE.md §2.3): scratch tenant wiped per
  run, pinned to a published draft version; steps replay as synthetic actors with
  role impersonation (Phase 2 RBAC — permission-denial assertions ride it,
  ADR-010 #5) through the generic APIs — no test mode in the write path. Side
  effects (events, audit, sequences) land in the scratch tenant only; synthetic
  actors have no notification channels.
- **Controlled clock — pinned** (ADR-010's open question): a run freezes `now()` at
  an explicit clock (default: run start, overridable per case); period-lock-style
  tests advance it explicitly. Runs are deterministic by construction.
- Run artifacts (per-step traces, assertion results) retained last N per definition
  version, surfaced via API. Builder-UI surfacing is §8; CI/headless wiring is
  Phase 8 (ADR-010 #5).

## 8. Builder Authoring v1 (and `runFlow`)

The flow/script designer was explicitly deferred out of Phase 2 into Phase 3
(PHASE-2 §1):

- Guided editors, no free-form canvas in v1: rules as trigger + step-list forms;
  validation rules and formulas as expression fields with live compile-check
  feedback (the Metadata Service checks at save — the same treatment as page props,
  PHASE-2 §7); test suites as fixture/step/assertion editors over the §7 encoding.
- The suite runner surfaces in the builder: launch a suite, inspect the run artifact.
- The page-model `runFlow` action activates once flows exist (PHASE-2 §4's deferred
  action from ADR-009's ladder).

## 9. Observability Expansion (closes the PHASE-0 §8 deferral)

Kafka tracing demands the backend (PHASE-0 §8/Q2): **Grafana Tempo (OTLP)** joins
the compose stack, the OTLP exporter activates on all services, and **Loki** joins in
the same expansion (PLAN.md §4). The full OTel collector stays deferred (Q4, §13).
New dashboards: Kafka consumer lag, hook-duration histograms, script ratio per app
version, suite pass rates.

## 10. Testing Standards

1. Engine: per-primitive interpreter tests; compiler rejection matrix (dangling
   references, cycles, non-compiling expressions, unresolved templates);
   failure-policy tests — before-hook aborts, after-hook retries via the spine,
   idempotent replay.
2. Roll-ups: a child write recomputes parent totals in-transaction (the exit
   scenario); balanced-entry validation green/red paths.
3. Spine: relay restart loses nothing; duplicate delivery deduped; per-record
   ordering holds on the partition key.
4. Audit: append-only enforced (UPDATE/DELETE denied), field diffs present,
   permission-change and publish events captured (the Phase 2 shapes).
5. Harness dogfood: the exit suite itself (order totals + inventory reservation) is
   authored as a `TestSuiteDefinition` and run through the runner — the phase's own
   acceptance demo (T10).
6. Expression conformance continues against both engines (PHASE-2 §7), now covering
   the write-path evaluation points.

## 11. Performance Validation

The write target becomes measurable: **p95 < 150 ms with 1 sync hook** at the
1M-row tenant dataset (ARCHITECTURE.md §9) — Phase 1's ≤ 100 ms budget reserved
exactly this ~50 ms of hook headroom. Also recorded (not gated): script-hook
latency at v0 (cold) against the 20 ms warm target that arrives with pools, and
spine consumer lag under the load-test write rate.

## 12. Task Breakdown

| # | Task | Content | Acceptance criteria |
|---|---|---|---|
| T1 | Spine bootstrap + event-schemas | Outbox + relay + Kafka producer, `metadata.published` rebind, header propagation, contracts lib (§4) | Redis channel retired; relay-restart test loses nothing |
| T2 | Write-path expressions | Validation rules + formula fields via the JVM engine (§3) | Formula stored at write; rule failure renders problem+json and `validation(rule)` |
| T3 | Roll-up summaries | Child-write recompute (§3, Q2) | Exit-scenario totals correct in-transaction |
| T4 | Flow engine + compiler | IR schema, publish-time compiler, executable primitives, triggers, failure policy (§2) | Compiled-graph execution; rejection matrix green |
| T5 | Audit Service v1 | Consumer, partitioned store, read API (§5) | Field diffs queryable; append-only enforced |
| T6 | Script Engine v0 | Sandbox service, whitelisted surface, versioned artifacts, ratio telemetry (§6) | Capped script survives an infinite loop; ADR-003 file written |
| T7 | Test harness | `TestSuiteDefinition` encoding, runner, scratch tenant, frozen clock (§7) | Exit suite green through the runner |
| T8 | Authoring UI v1 | Rule/formula/suite editors with compile-check; `runFlow` activation (§8) | The exit suite is authored without hand-written JSON |
| T9 | Observability expansion | Tempo + Loki + dashboards (§9) | Cross-service trace renders in Tempo; logs in Loki |
| T10 | Perf + exit review | §11 run; walk the PLAN §5 exit criteria | 150 ms target met with 1 sync hook |

Dependency order: (T1, T2) → (T3, T4, T5) → (T6, T7) → T8 → T10; T9 runs parallel
from the moment T1 lands.

## 13. Open Questions

Closure points: Q3 before T1, Q2 before T3, Q1 before T4, Q4 before T9.

- **Q1 — Flow execution context:** initiating actor vs per-app system principal.
  *Recommendation: system principal for declarative flows — they are reviewed,
  promoted artifacts (ADR-008's trust gradient) and the inventory-reservation exit
  must not depend on the clerk's grants; scripts stay caller-context
  (ARCHITECTURE.md §5 item 4). Both audited.*
- **Q2 — Roll-up recompute:** synchronous in-transaction (consistent; serializes on
  the parent) vs async eventual. *Recommendation: synchronous v1 — ARCHITECTURE §3
  says evaluated at write time; revisit at dogfood scale.*
- **Q3 — Topic topology:** shared topics with `tenant_id:entity_id` partition key vs
  per-tenant topics. *Recommendation: shared — per-tenant topics explode (tenants ×
  event types).*
- **Q4 — Tracing backend:** Grafana Tempo in compose vs full OTel collector now.
  *Recommendation: Tempo (PHASE-0 Q2's leaning); the collector stays deferred.*
