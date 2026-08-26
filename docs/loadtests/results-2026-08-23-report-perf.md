# Phase 5 report load-test results — 2026-08-23 (§12 exit record, PHASE-5-REPORTING.md)

Environment: compose stack in rootless Podman (Postgres 16.15, Redis 7.4.11,
Keycloak 26.7.2, Mailpit), services on host JVMs (Temurin 21, `-Xmx512m`), requests
through the gateway with full JWT validation, tenant demo. Driver:
`docs/loadtests/report-perf.py` (sequential, 100 iterations per leg). The §9 row under
test: **report — 1M rows, grouped aggregate over promoted fields — p95 < 2 s**.

Fixture: 1,000,000 `PerfInvoice` rows (500 customers, POSTED/DRAFT alternating,
`dueDate` spread over −119…0 days, random 2-dp amounts), seeded via
`generate_series` into the trigger-maintained projection table (`rec_perf_invoice`,
promoted columns `customer_name/status/due_date/amount_outstanding`) and `ANALYZE`d —
the ADR-001 spike methodology. The report: `perfAging` — saved filter
`status = POSTED`, group-by customer, bucketed due-date aging (current/0-30/31-60/60+
as in-pipeline CASE branches), `count` + `sum(amountOutstanding)`; 750 grouped rows
per run, totals `{count: 500000, outstanding: 25056773968.80}`.

| Leg | p50 | p95 | Verdict |
|---|---|---|---|
| Cache cold (result keys deleted before every run) | 807.4 ms | **1379.9 ms** | **PASS** (< 2000 ms) |
| Cache warm (60 s TTL, actor-keyed) | 21.0 ms | 132.6 ms | **PASS** (< 2000 ms) |

Notes:
- Cold runs execute the full journey twice per run (the grouped query plus the
  un-grouped totals twin) — 2× a parallel HashAggregate/GroupAggregate over 1M rows
  (EXPLAIN: Gather Merge → Partial GroupAggregate, 2 workers). The warm leg proves
  the cache is a latency tool, not a load-bearing dependency (§4): both legs pass
  independently.
- The result cache is keyed (tenant, app, report, version, actor, evaluation date,
  params) — the cold leg deletes `novaforge:reporting:results:*` between runs, which
  is exactly the miss path every first-time viewer takes.
- Machine context: shared 32 GB box also running an unrelated ~10 GB workload;
  services capped at 512 MB heaps. Numbers are steady-state after one warm-up run.

## §7/§10-item-4 delivery verification (same stack, same day)

The A/R demo app (`ArDesk`: `Invoice` entity, `arAging` report with the §3 aging
buckets, `exec` dashboard, `nightlyAging` report job) authored and published through
the definition APIs; 6 invoices seeded through the record API (one per bucket edge +
a DRAFT that must stay out). Verified live:

- `POST /api/v1/reports/arAging/run` — decimal-exact buckets (acme 0-30 = 200.00 at
  exactly the 30-day edge, globex 31-60 = 50.25, initech 31-60 = 10.00 at exactly 60,
  initech 60+ = 5.00, DRAFT excluded), totals `outstanding: 365.75`, plus the
  ECharts-shaped chart projection.
- `GET /arAging/export?format=csv` — RFC 4180 stream with the totals row;
  `format=xlsx` — a valid XLSX workbook (`xl/worksheets/sheet1.xml` present).
- The job fired (`cron 0 * * * * *` for the demo, republished to the authored
  `0 0 6 * * *` afterwards — the registry upsert moved `next_fire_at` to
  2026-08-24T06:00Z on the next sync): scheduler run `ok` → Reporting Service's
  internal delivery surface under `runAsRole: reporting` → Notification inbox row +
  email leg → **Mailpit shows `Report arAging (ArDesk)` to demo@localhost with
  `arAging.xlsx` (3657 bytes, the XLSX content type) attached**, and
  `notification.delivered` audit rows for both channels land in the outbox.

Two integration defects surfaced by the live run and fixed with regression tests:
the `published-apps` service-caller index omitted `apiName` (every service-consumer
sync — Scheduler jobs, workflow processes/SLAs, Reporting definitions — synced from a
null/empty app key), and the scheduler's registry never pruned vanished definitions
(the resulting orphan row fired and failed forever). See IMPLEMENTATION.md Phase 5.

## Dashboard initial load (§12's second line — measured 2026-08-26, same stack)

§12: "Dashboard initial load = N report runs; measured and reported, not gated." The
`ArDesk` `exec` dashboard composes three widgets over `arAging` (kpi/chart/table —
one report run each, PHASE-5 §5): 100 sequential initial loads through the gateway
with full JWT validation, warm result cache, as `demo`:

| Leg | p50 | p95 |
|---|---|---|
| Dashboard initial load (3 widget runs) | 63.9 ms | 179.6 ms |

Context: the ArDesk fixture is small (6 invoices); the per-run cost at scale is
bounded by the 1M-row legs above (warm p95 132.6 ms per run — a 3-widget dashboard
over that fixture composes to roughly 3× the warm per-run p95, still far under any
perceived-latency bar). Re-measured after the app-qualified report leg landed: the
same run now executes with a second published app defining `Invoice` in the tenant
(the ERP dogfood) — the disambiguation adds no measurable cost.
