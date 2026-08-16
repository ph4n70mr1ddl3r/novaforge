# Phase 5 — Reporting & Dashboards: Implementation Specification

> Complete, implementation-driving spec for the Reporting Service, report/dashboard
> definitions, exports, and scheduled delivery. Product context:
> [PLAN.md](../../PLAN.md) §5 Phase 5. Service design: ARCHITECTURE.md §2.7;
> dashboard composition is PHASE-2 §13/Q4's deferred scope. The spec-driven
> development agreement of PHASE-4 §1 applies (spec is the contract; ACs are
> ADR-010 suites wherever expressible).
>
> | | |
> |---|---|
> | Status | Draft for review |
> | Date | 2026-08-16 |
> | Owner | Platform team |
> | Estimate | 3–4 weeks (per PLAN.md §5) |
> | Depends on | Phase 1 (query DSL + aggregate endpoint) + Phase 4 (Scheduler `report` target, Notification) |

## 1. Objective & Exit Criteria

Deliver the Phase 5 exit: *A/R aging report + executive dashboard* (PLAN.md §5) —
built entirely in the report/dashboard builders, scheduled delivery working, and the
journey verifiable by a builder-authored suite.

Out of scope: async large-export streaming via the File Service (activates when it
lands in Phase 6 — PLAN.md §5; direct downloads only here, §6); ad-hoc free-form
query UI (Q2); end-user self-serve subscriptions (Q1); pixel-perfect/BI-grade
reporting and dedicated pivot mechanics (never a v1 goal); cross-app federation.

## 2. Service & Infrastructure Additions

| Addition | Detail |
|---|---|
| `novaforge-reporting-service` | Port 8089; gateway routes `/api/v1/reports/**` and `/api/v1/dashboards/**` — the dashboard prefix is *reserved*: v1 dashboard loading is a definition fetch via the Metadata Service's published read (PHASE-1 §4) plus client-issued report runs (§5), and any dashboard-scoped API arrives on demand (versioned growth, as everywhere). |
| Compose | Nothing new — Postgres/Kafka/Redis reused; Mailpit (Phase 4) serves scheduled-delivery email. The service is stateless and takes no per-service database (definitions via the Metadata Service, result cache in Redis, scheduled delivery via Scheduler + Notification) — the PHASE-4 §2 per-service-DB pattern applies only to services with their own state. |
| `metadata-model` | `ReportDefinition` and `DashboardDefinition` schemas (both already in the ARCHITECTURE.md §2.3 owns-list). |
| Frontend | ECharts joins `frontend/` dependencies (ARCHITECTURE.md §2.7); chart rendering ships as versioned catalog components (§5), not bespoke report UI. |
| `common-core` | No new codes — report errors reuse `VALIDATION_FAILED` / `NOT_FOUND` / `FORBIDDEN`. |

## 3. Report Definitions (metadata, compiled to the query DSL)

```json
{ "id": "rep_ar_aging",
  "entity": "Invoice", "label": "A/R Aging",
  "filters": [ { "field": "status", "op": "eq", "value": "OPEN" } ],
  "groupBy": [ { "field": "customerName" }, { "field": "dueDate",
                 "buckets": [ { "label": "current", "expression": "today() - dueDate < 0" },
                              { "label": "0-30", "expression": "today() - dueDate >= 0 && today() - dueDate <= 30" },
                              { "label": "31-60", "expression": "today() - dueDate > 30 && today() - dueDate <= 60" },
                              { "label": "60+", "expression": "today() - dueDate > 60" } ] } ],
  "aggregates": [ { "op": "sum", "field": "amountOutstanding" } ],
  "drillThrough": { "entity": "Invoice", "carryFilters": true } }
```

- **Save/publish validation:** fields exist on the entity; aggregate fields are
  numeric (decimal sums as BigDecimal — PLAN.md §1 money rule); bucket expressions
  compile (Phase 2 JVM engine); groupBy and aggregate fields are projection-promoted
  or the definition is rejected with guidance (reporting rides the §4 materialized
  path — sums execute on promoted columns; a bucketed groupBy rides its source
  field's promotion, the bucket itself computing in-pipeline).
- Reports compile to Data Runtime query/aggregate calls — **never raw SQL**
  (ARCHITECTURE.md §2.7). Buckets lower to `branch`-style case expressions in the
  aggregate pipeline, not client-side shaping.
- `daysOverdue`-style aging inputs compute at **run time** inside bucket
  expressions over stored dates — they must *not* be stored formula fields: Phase 3
  formulas evaluate at write time (PHASE-3 §3) and a stored age would go stale
  between writes, which is exactly why time functions are compile-rejected there.
  Bucket expressions may reference `today()` (date arithmetic yields days),
  resolved against the governing clock — the run's frozen clock in suites
  (PHASE-3 §7), so bucket results are deterministic. Reports still never recompute
  row *business* logic (validations, stored formulas); lowering bucket expressions
  into the aggregate pipeline is report logic.
- Multi-field groupBy is v1's pivot: the aging example renders rows × bucket columns
  from its two-level groupBy — the coverage ARCHITECTURE.md §2.7's "pivot" names;
  dedicated pivot mechanics (asymmetric layouts, custom cross-tabs) stay out of v1
  (§1).

## 4. Execution Semantics

- `POST /api/v1/reports/{id}/run` with param overrides (saved filters are the
  defaults; callers may tighten, never loosen beyond their own row filters — §8);
  response: `{ columns, rows, totals }` plus a chart-shaped projection (series/axes)
  for direct ECharts binding.
- **Authorization — pinned:** report runs execute as the *requesting actor* through
  the same Data Runtime query path — sharing-rule row filters (Phase 4 §10) apply to
  reports exactly as to lists. No system-principal reporting on the interactive
  path (the scheduled path is separately scoped by `runAsRole`, §7); a manager's
  dashboard shows what that manager may see.
- **The "materialized path" (ARCHITECTURE.md §9) — pinned:** aggregates execute
  against projection-promoted/indexed columns via the Phase 1 aggregate endpoint; no
  separate materialized-view machinery in v1. Definitions referencing non-promoted
  group-by fields are rejected at save (§3).
- Result caching: keyed (report, params, definition version, the requesting actor —
  identity plus effective sharing-rule row filter) with a 60 s TTL, invalidated on
  `metadata.published`; for time-relative reports the evaluation date joins the key,
  so a day boundary never serves yesterday's buckets (§3). Cache is a latency tool,
  never an authorization boundary (row filters still apply per actor). Role set
  alone is not a safe key: owner-based sharing differs between users holding
  identical roles.

## 5. Dashboards & Catalog Components

- `DashboardDefinition` = a grid of widgets `{ widget: kpi | chart | table,
  reportRef, params, span }` + role visibility; rendered by the runtime renderer
  from four new versioned catalog components — `ChartWidget` (ECharts props
  schema), `KpiTile`, `ReportTable` (the `table` widget type), `DashboardGrid` —
  following the ADR-009 catalog contract (props JSON Schema, lazy, versioned). This
  is the composition PHASE-2 §13/Q4 deferred here.
- Dashboard load issues its report runs server-paged; auto-refresh is client-timer
  driven (configurable per widget, default off) — no server push in v1.
- Drill-through links deep-link to record lists carrying the row's filters as a
  query-DSL payload (the runtime list page consumes it natively).

## 6. Export (direct downloads only)

- Formats: CSV and XLSX. `GET /api/v1/reports/{id}/export?format=…` streams
  synchronously with the same authorization as a run (§4).
- **Sync cap — pinned:** 10,000 rows. Larger exports return a problem+json pointing
  at the async export job that activates with the File Service in Phase 6
  (PHASE-6 spec §7) — the handoff is designed now, wired then.
- Formatting: money columns as decimal strings with currency symbols per locale;
  bucket labels verbatim; totals row included.

## 7. Scheduled Delivery

- The Scheduler's `report` target activates (registered dormant in Phase 4 §7):
  job params `{ reportId, params, runAsRole, recipients: roles|users, format }`.
- Execution: run as a **system principal over an explicitly permissioned scope** —
  scheduled reports declare a `runAsRole` (default: the app's `reporting` role; a
  role that does not resolve against the app's definitions is a save-time
  validation error), so row filters still bound the dataset; pinning this avoids
  both leaks and system-principal-everything.
- Delivery via the Notification Service (template + attachment — the built-in
  `report-delivery` template category joining Notification v1's defaults per
  PHASE-4 §8's growth path), Mailpit locally; delivery audited; failures visible in
  the scheduler job history.

## 8. Security & Audit

- Runs/export/schedules enforce object-level (`report: execute`) plus the underlying
  entity access of the effective actor (§4, §7). The grant's authoring home —
  pinned: `report: execute` rides app role definitions (`PermissionSet`,
  ARCHITECTURE.md §2.3) — the Phase 2 role editor grows the matrix beyond entity
  CRUD in this phase — default deny until an app grants it; dashboard widgets run
  under the same grant, their §5 role visibility governing composition only.
- Dashboards are metadata: versioned, promoted, role-visible per §5.
- Audited: scheduled deliveries, subscription changes; interactive runs are not
  audited (reads; consistent with ARCHITECTURE.md §5, which audits writes and
  permission changes).

## 9. Test-Harness Growth

- New step op: `runReport { reportId, params, asRole }` → `{ rowCount, totals }` for
  assertions — the A/R aging suite asserts aging totals equal the ledger sums (the
  Phase 7 reconciliation seed).
- Visibility suites: a low-privilege role's `runReport` respects sharing-rule row
  filters (regression against §4); cached results never leak across roles — nor
  between same-role users under owner-based sharing (the cache key carries the
  actor, §4).

## 10. Testing Standards

1. Golden datasets → expected groupings/buckets/totals (decimal-exact).
2. Bucket boundary tests (30/60 edges); drill-through round-trip (click → list
   filters match the row).
3. Export: cap enforcement, money formatting, XLSX/CSV parity.
4. Scheduled delivery end-to-end with the frozen clock (Phase 3) — fire → run →
   email with attachment visible in Mailpit.
5. Authorization matrix: role × report × sharing-rule state (fail closed).

## 11. Task Breakdown

| # | Task | Content | Acceptance criteria |
|---|---|---|---|
| T1 | Service skeleton | Port 8089, routes, health, dashboards row in Grafana | Behind gateway with JWT |
| T2 | Report metadata | Schema, save/publish validation, compile to query DSL (§3) | Invalid definitions rejected; compile artifacts versioned |
| T3 | Execution engine | Run API, actor-scoped queries, caching, chart shaping (§4) | p95 < 2 s on the 1M-row fixture (§12) |
| T4 | Export | CSV/XLSX sync stream, cap, formatting (§6) | §10.3 green |
| T5 | Catalog components | `ChartWidget`, `KpiTile`, `ReportTable`, `DashboardGrid` + props schemas + stories (§5) | Catalog gallery green incl. axe |
| T6 | Dashboard composer + report builder UI | Builders over metadata APIs (§5, §11) | A/R aging + dashboard authored without hand-written JSON |
| T7 | Scheduled delivery | Scheduler `report` activation + Notification attachments (§7) | §10.4 green |
| T8 | Harness | `runReport` op + suites (§9) | Aging-vs-ledger suite green |
| T9 | Exit review | Walk PLAN §5 exit | Demo: A/R aging + executive dashboard |

Dependency order: T1 → T2 → T3 → (T4, T5) → (T6, T7) → T8 → T9; T5 can start at
phase start (catalog work is frontend-only).

## 12. Performance Validation

The ARCHITECTURE.md §9 row becomes due: **report (1M rows, grouped aggregate over
promoted fields) p95 < 2 s** — measured on the Phase 1 load fixture with the
reporting cache cold and warm (both must pass; the cache may not be load-bearing).
Dashboard initial load = N report runs; measured and reported, not gated.

## 13. Open Questions (both non-blocking)

- **Q1 — End-user subscriptions:** self-serve "schedule this report" UI vs admin
  -authored schedules only. *Recommendation: admin-authored v1; self-serve when the
  dogfood asks for it.*
- **Q2 — Ad-hoc query UI:** saved-report building only, or a free-form explorer.
  *Recommendation: saved reports only — the explorer is a UX project that doesn't
  block the ERP dogfood.*
