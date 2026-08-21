# ADR-006: Multi-tenancy — shared schema + PostgreSQL RLS

- **Status:** Accepted (ahead of implementation — docs-only; confirmed by the Phase 1 storage/RLS implementation, PHASE-1 §6/T5 and §7/T9)
- **Date:** 2026-08-21
- **Affects:** PLAN.md P6 (Security & Tenancy), §6; ARCHITECTURE.md §4–§5, §9; PHASE-1 §6 (RLS, partial unique indexes), §7; every phase's cross-tenant tests

## Context

Tenant data isolation options: schema-per-tenant or database-per-tenant (strong
isolation, but DDL explosions across tenants × entities and painful cross-tenant
platform operations) vs shared schema with a `tenant_id` discriminator (one set of
tables, isolation enforced by every query carrying the predicate — an
application-discipline risk). The storage decision (ADR-001's hybrid JSONB,
`rec_records` base + projections) makes per-tenant schemas especially explosive:
every publish would fan out DDL per tenant.

## Decision

1. **Shared schema, `tenant_id` on every tenant-data row.**
2. **PostgreSQL Row-Level Security as defense-in-depth**, not as the primary gate:
   `tenant_id = current_setting('app.tenant')` on all tenant record tables; the
   `security-context` lib sets the session variable per request from
   `TenantContext` (derived from the token claim, never the gateway header —
   PHASE-0 §6.1/T7).
3. **Primary enforcement stays in the Data Runtime** — metadata resolution carries
   the tenant, the authorization matrix (ADR-002) gates access, and query filters
   are always tenant-scoped; RLS makes a missed predicate fail closed rather than
   leak.
4. **Uniqueness is tenant-scoped over live rows only** — partial unique indexes
   (`WHERE NOT deleted`) so soft-deleted tombstones never pin values (PHASE-1 §6).
5. **The platform authorization store is exempt by design** (cross-tenant:
   tenants/users/role assignments) — gated by the role matrix and admin API, never
   row filters (PHASE-1 §6).

## Consequences

- Cross-tenant read/write/delete assertions are mandatory test standards
  (ARCHITECTURE.md §5; PHASE-1 §9.3) — RLS failing closed is itself tested.
- Per-tenant extraction (a tenant demanding a silo) stays possible behind the
  storage SPI — the strategy can evolve without touching `api/`/`engine/`
  (ARCHITECTURE.md §4).
- Connection-level discipline: the session variable must be set/cleared with the
  request (pooling-safe fixtures in `test-support`).
- Compliance posture: logical isolation + append-only audit trail, not physical
  separation — stated up front as the v1 tenancy contract.
