# Deep-offset probe results — 2026-09-04 (the PHASE-1 §12 Q2 keyset-paging trigger, finally evaluated)

Environment: compose stack in rootless Podman (Postgres 16.15, Redis 7.4.11,
Keycloak 26.7.2), gateway + metadata + data-runtime on host JVMs (Temurin 21),
requests through the gateway with full JWT validation, tenant demo. Driver:
`docs/loadtests/deep-offset-probe.py` (sequential, 25 iterations per leg).

## What this closes

The §12 Q2 decision — *"Keyset paging joins only if the §10 load test shows
deep-offset pain"* — carried a trigger that was never evaluated. The 2026-08-21
exit run drove a small dataset and its own notes deferred the question (*"the
dataset is small … Deep-paging pain, if it appears, triggers the keyset-paging
revisit"*); ADR-001's 1M-row spike measured projection strategy, not paging
depth; no later run measured it. This probe runs the measurement the spec
names.

## Fixture

`PerfHook.PerfDoc` published through the definition APIs (version 1); the
materializer projects `rec_perf_doc` (generated `name`/`status`/`due_date`
columns; partial indexes on each plus `updated_at DESC`, all `WHERE NOT
deleted`; forced RLS). 1,000,000 rows seeded via `generate_series` into
`rec_records` — the trigger maintains the projection — and `ANALYZE`d, the
2026-08-23 report-perf methodology (50/50 POSTED/DRAFT, `dueDate` spread over
−119…0 days). Machine context: shared 32 GB box with an unrelated background
workload; services at 512 MB heaps; numbers are steady-state after warm-up.
Run-to-run variance on this box is material at the deep tail (a second full run
answered 5087 ms worst-p95 where the table below shows 3841 ms — the shape and
verdict are invariant, the milliseconds are not load-bearing).

## Legs (bar: the §10 filtered-list target, p95 < 300 ms on promoted/indexed fields)

| Leg (§10 filtered shape: status=POSTED, sort dueDate desc) | OFFSET | size | p50 | p95 | verdict |
|---|---|---|---|---|---|
| filtered, indexed sort | 0 | 50 | 242.7 ms | 284.1 ms | PASS |
| filtered, indexed sort | 1,000 | 50 | 292.8 ms | 407.8 ms | **PAIN** |
| filtered, indexed sort | 10,000 | 50 | 379.6 ms | 465.6 ms | **PAIN** |
| filtered, indexed sort | 100,000 | 50 | 638.9 ms | 1058.1 ms | **PAIN** |
| filtered, indexed sort | 250,000 | 50 | 1312.2 ms | 1798.2 ms | **PAIN** |
| filtered, indexed sort | 400,000 | 50 | 1709.0 ms | 2329.4 ms | **PAIN** |
| filtered, size 200 | 0 | 200 | 268.8 ms | 338.0 ms | **PAIN** |
| filtered, size 200 | 100,000 | 200 | 564.3 ms | 758.9 ms | **PAIN** |
| filtered, size 200 | 400,000 | 200 | 1587.8 ms | 2622.0 ms | **PAIN** |
| unfiltered, default sort | 0 | 50 | 200.6 ms | 269.0 ms | PASS |
| unfiltered, default sort | 100,000 | 50 | 554.8 ms | 930.6 ms | **PAIN** |
| unfiltered, default sort | 500,000 | 50 | 1735.6 ms | 2353.3 ms | **PAIN** |
| unfiltered, default sort | 990,000 | 50 | 3084.8 ms | 3841.2 ms | **PAIN** |

## Why: two separable costs

1. **The OFFSET scan is linear in depth — the pain §12 Q2 asked about.** The
   deep page's plan walks `ix_rec_perf_doc_due_date` backward, applies
   `status = 'POSTED'` as a post-scan Filter (the sort index does not carry the
   filter), and discards everything above the offset before the LIMIT:

   ```
   Limit
     -> Index Scan Backward using ix_rec_perf_doc_due_date on rec_perf_doc
          Index Cond: (tenant_id = '…')
          Filter: (status = 'POSTED'::text)
   ```

   400k discarded rows ≈ 1.7 s of p50 — the textbook deep-offset curve,
   confirmed at every depth in both runs.
2. **A flat per-page total-count tax at 1M scale** (a finding the probe was not
   looking for): the §5 offset+total model computes the filtered `count(*)` on
   every page, and at 1M rows that alone costs the same order as the whole §10
   bar (standalone EXPLAIN ANALYZE: **364.9 ms** over the 500k POSTED subset).
   It is why OFFSET 0 sits at ~240–280 ms p50 — near the bar before any offset
   depth exists — and it will not go away with keyset cursors unless the count
   semantics change with them.

## Verdict

**The §12 Q2 trigger FIRES.** Deep-offset pain is real, measured, and
reproducible: the §10 bar breaks from OFFSET ≈ 1,000 on the §10 shape itself,
and the unfiltered tail exceeds 10× the bar. Keyset paging is due.

Per the PHASE-4 §1 SDD agreement, the landing is a spec-first versioned growth:
the §5 DSL's keyset form (cursor encoding, its interaction with
`offset`/`total`, and the count semantics of a keyset page — item 2 above makes
that decision part of the landing, not an afterthought) gets its spec text
before the engine work. Offset paging remains on the wire unchanged for every
existing client until that lands; nothing existing changes meaning (the same
growth rule every DSL addition has followed).
