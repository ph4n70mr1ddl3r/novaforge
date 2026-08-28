# Phase 3 §11 / Phase 8 §8 write-path load-test results — 2026-08-28

(the at-scale record PHASE-3-BUSINESS-LOGIC.md §11 pinned and PHASE-8 §8 owed; also
re-validates the ARCHITECTURE.md §9 read/list rows at the true 1M-row dataset —
Phase 1's 2026-08-21 run measured a small dataset, leaning on the ADR-001 spike for
the at-scale numbers)

Environment: compose stack in rootless Podman (Postgres 16.15, Redis 7.4.11, Kafka
4.3.1, Keycloak 26.7.2), services on host JVMs (Temurin 21, `-Xmx512m`), requests
through the gateway with full JWT validation, tenant demo. Machine context: shared
32 GB/32-core WSL2 box also running an unrelated ~72%-of-memory workload during the
run — numbers are conservative. Driver: `docs/loadtests/hook-perf.py` (sequential,
200 iterations per leg, one warm-up pass).

Fixture: the `PerfHook` app (`apps/perf/perfhook-app.json`, published v1 into the
demo tenant) — one entity `PerfDoc` (text/enum/date/money/text fields, two promoted
indexes), exactly **one synchronous beforeSave hook** (`setField stamp =
upper(name)`, a real parse+eval over the record) and one record-scope validation
rule (`amount >= 0`): the §11 "1 sync hook" write chain is
required-fields → validation rule → beforeSave setField → optimistic persist →
projection trigger → transactional outbox append. 1,000,000 rows seeded via
`generate_series` into `rec_records` (trigger-maintained `rec_perf_doc`, 400 MB)
with `ANALYZE` — the ADR-001 spike methodology, the same shape as the 2026-08-23
report fixture. The write leg draws no gapless sequence (Phase 1's small-dataset
run already pins that path at 33.4 ms p95).

| Leg (§9 row) | p50 | p95 | Target | Verdict |
|---|---|---|---|---|
| Point read, cache warm | 12.8 ms | **37.3 ms** | < 50 ms | **PASS** |
| Filtered list (`status = POSTED`, 500k matches, promoted+indexed) | 28.7 ms | **68.9 ms** | < 300 ms | **PASS** |
| Record write with 1 sync hook | 26.8 ms | **112.0 ms** | < 150 ms | **PASS** |

Remaining §9 rows: report @1M p95 < 2 s — PASS (measured 2026-08-23, cold 1379.9 ms /
warm 132.6 ms); script warm p95 < 20 ms — N/A (warm pools deferred with demand,
ADR-003 #4).

## The defect the at-scale run caught (and the fix)

First measurement FAILED the list row at p95 750.7 ms (and the write row marginally
at 150.6 ms). Profiling (postgres statement log + jstack sampling) showed the list
path re-queried `platform.role_assignments` **once per field per row** — the
hidden-field predicate in `RecordEngine.strip()` evaluated
`RoleMatrix.fieldAccess` lazily inside `stripHidden`, and each call is a
platform-store role lookup wrapped in the RLS `set_config` dance: a 50-row page
over a 5-field entity issued ~250 role queries (~450 ms on this box). Point reads
stripped one row (invisible at small scale); writes don't strip at all — exactly
why Phase 1's small-dataset run never saw it. PLAN.md §6's "load-test in Phase 1,
not Phase 7" risk, materializing two phases late.

Fixed in `RecordEngine.strip()`: field access is row-independent, so the hidden set
now resolves **once per request** over the entity's fields (and apps with no
`fieldSecurity` entries skip the lookups entirely — the measured fixture's path).
Regression-pinned by `FieldStripCostTests` (engine module: at most one
`fieldAccess` call per entity field over a 50-row page, hidden fields still strip
from every row). Post-fix list p95: **68.9 ms** (7.3× better than the target, ~10×
better than the pre-fix p95); point reads and writes unaffected.

Second finding (authoring-time, both gap-logged to the ledger, not platform fixes
this pass): publish's compile-check accepted a `setField` expression using `+` on
text operands (`name + '-' + status`) — the DSL's `+` is numeric-only, so the
error surfaced at runtime as 500 INTERNAL instead of a save-time authoring
rejection (type-checking at compile time is the missing leg); and an app PATCH
replacing an entity's nested hook graph did not replace the `flow` (published v2
carried v1's expression) — delete + re-import worked around it live.

## Suites verified live the same day (the same stack, same session)

- **Phase 4 T12 exit journey** (`apps/purchasing` + `exitJourney`): GREEN in 7.7 s —
  approve→POSTED, reject-path, below-threshold auto-post, SLA warn→breach→escalation
  (scanSla, no sleeps), `error(SOD_VIOLATION)` — PLAN §5's Phase 4 exit demonstrable
  on demand.
- **Phase 7 walkthrough** (`apps/erp`, all five suites): reconciliation, controls,
  inventoryCosting, bankFeed, creditAndCurrency — **all GREEN** (12 cases), which
  carries PHASE-6 T10's acceptance leg (bank-feed webhook through the real HMAC path
  → Payment lands → settlement decrements the POSTED invoice → aging reconciles
  decimal-exact).
