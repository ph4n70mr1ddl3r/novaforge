# ADR-001: Storage strategy — hybrid JSONB + generated projection tables

- **Status:** Accepted (spike closed 2026-08-21)
- **Date:** 2026-08-21
- **Closes:** the pre-Phase-1 storage decision gate (PLAN.md §8 item 2, PHASE-1 §2)

## Context

Tenant record data lives under dynamic, per-app schemas. ARCHITECTURE.md §4 chose the
hybrid JSONB strategy (option C) in principle — a shared `rec_records` base table as
source of truth plus per-entity projections for query performance — but left the
projection variant and sync mechanics to a 1M-row spike before the Phase 1 storage work:

- **Variant A — pure view** over `rec_records`, expression indexes on the base table.
  No data duplication, no sync; promoted fields live only inside index expressions.
- **Variant B — generated projection table**: per-entity table duplicating `data` with
  `STORED` generated columns promoted from JSONB, regular indexes on those columns,
  kept current by an AFTER INSERT/UPDATE/DELETE trigger on `rec_records`.

## Decision

**Variant B — generated projection tables, trigger-maintained.** `rec_records` stays the
single source of truth and the only table the write path touches; each publish creates
or refreshes the entity's projection (DDL at publish time only — never on the hot path),
and a row-level trigger propagates base-table writes transactionally, so projections
cannot diverge.

**Immutability constraint discovered by the spike:** Postgres rejects non-IMMUTABLE
expressions in index expressions and generated columns — `(data->>'entryDate')::date` is
STABLE (DateStyle-dependent) and therefore cannot be promoted. The storage layer instead
canonicalizes promoted scalars to sortable text forms and promotes **text and numeric
columns only**:

- text-family fields promote as `text` (`data->>'f'`);
- numeric fields promote as `numeric` (`(data->>'f')::numeric` — that cast is immutable);
- date/datetime/time values are stored in JSONB in canonical ISO-8601 (UTC, fixed width)
  and promoted/compared as **text** — lexicographic order equals chronological order for
  the canonical form; the engine canonicalizes on write so the invariant holds.

Uniqueness lowers to a partial unique index scoped `(tenant_id, …)` over live rows only
(`WHERE NOT deleted`) on the promoted column — soft-deleted tombstones never pin a value.

## Measured numbers (the acceptance vehicle)

1M `JournalEntry` rows in one tenant, Postgres 16.15 (container, local disk), warm cache,
200 in-transaction iterations per operation; script and raw output:
`docs/spikes/storage-spike.sh`, `docs/spikes/storage-spike-results.md`.

| Operation (target, ARCHITECTURE.md §9) | Variant A view+expr-index | **Variant B generated table** |
|---|---|---|
| Point read by id (p95 < 50 ms) | 0.037 avg / 6.95 max ms | **0.024 avg / 4.24 max ms** |
| Filtered+sorted list, page 50 (p95 < 300 ms) | 3.64 avg / 349.5 max ms | **2.18 avg / 122.5 max ms** |
| Unique-value lookup | 0.021 avg ms | **0.012 avg ms** |
| Insert (variant B incl. trigger + projection write) | 0.44 avg ms | **0.24 avg ms** |
| Storage (table + indexes) | 446 MB + 139 MB indexes | 450 MB + 132 MB indexes (≈2× total) |

Both variants meet the read targets by two orders of magnitude; the max-column outliers
are first-iteration planning/cold-buffer effects, not steady-state (steady-state avg is
what the p95 target bounds here). Variant B is uniformly faster on native columns with a
narrower unique index and keeps the planner on simple index scans; its costs are ~2×
storage at 1M rows (~582 MB vs 585 MB+projection duplication — 894 MB total) and one
trigger function per entity, both acceptable at the ERP scale the dogfood pins.

## Consequences

1. The Data Runtime `storage/` module owns: base-table SQL, per-entity projection DDL
   (the **materializer**, reacting to `metadata.published`), trigger management, and
   the partial-unique-index lowering for field uniqueness and entity-level unique
   indexes (PHASE-1 §6).
2. Promoted-field policy (PHASE-1 §12 Q3): fields named in entity-level `indexes`
   declarations and unique constraints promote, plus automatic promotion of display and
   lookup fields.
3. Writes go to `rec_records` only; projections are trigger-synced inside the same
   transaction — no dual-write logic in Java, no divergence window.
4. RLS (`tenant_id = current_setting('app.tenant')::uuid`) applies to `rec_records` and
   every projection table; policies fail closed on an unset variable (ADR-006).
5. Date/datetime canonicalization (UTC ISO-8601) becomes a write-path responsibility —
   `metadata-model` field types carry the contract and the engine enforces it.
6. The strategy sits behind the storage SPI (ARCHITECTURE.md §4/§7): if a later load
   test or a tenant-scale requirement overturns it, only `storage/` changes.

## Revisit triggers

- A projection-heavy entity count where per-entity tables×indexes exceed operational
  comfort (the materializer must then batch/queue DDL).
- The Phase 1 load test (PHASE-1 §10) against the full write path (validations +
  sequences + RLS) missing write p95 ≤ 100 ms — then re-measure with the projection
  writes in the loop and consider skipping projection triggers on cold entities.
