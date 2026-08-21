# Implementation Status

Tracks what is built versus the phase specs. Each entry names the spec sections the
code implements and the suites that verify it. The corpus is the contract; this file is
the ledger.

## Phase 0 — Foundations ✅ (spec: PHASE-0-FOUNDATIONS.md)

- Monorepo: root parent POM (Boot 4.1.0 / Framework 7.0.8 / Cloud 2025.1.2 per
  ADR-007), `platform/libs` + `services` aggregators, Maven wrapper.
- `novaforge-common-core`: TenantContext, error model (`4000/4001/4003/4004/4090/5000`
  seed registry), ProblemErrors, PlatformException.
- Gateway (8080): YAML routes, JWT validation (problem+json 401s), `X-Tenant-Id`
  derivation from the `tenant_id` claim.
- Metadata-service skeleton (8081): ping pins the exact framework version (drift rule).
- Compose stack (`deploy/compose`): Keycloak 26.7.2 (realm `novaforge`, client
  `novaforge-api`, `novaforge.api` scope with tenant/actor/platform-roles claim
  mappers, confidential service client `novaforge-runtime`, demo user), Postgres 16.15,
  Redis 7.4.11, Kafka 4.3.1, Prometheus + Grafana with the seeded Phase 0 dashboard.
- CI: GitHub Actions build (ubuntu) + the Podman-socket self-hosted job (§9's Phase 1
  wiring).

## Phase 1 — Metadata Core & Data Runtime ✅ (spec: PHASE-1-METADATA-CORE.md)

- **ADR-001 closed**: 1M-row spike, variant B chosen (generated projections,
  trigger-maintained); measured numbers + revisit triggers in the ADR. The
  cast-immutability constraint (no `::date` in index expressions) lowered to policy:
  promote text/numeric only, canonical ISO text for temporal ordering.
- Shared libs: `metadata-model` (definition POJOs + JSON Schema v0 + the §3 rule
  matrix), `security-context` (task decorator, RLS DataSource bridge, event headers),
  `test-support` (Postgres/Redis container bases, RLS fixtures, shared ArchUnit rules).
- Metadata Service (8081): draft CRUD, save validation, publish with compatibility
  check + `acknowledgeDataImpact`, immutable versions + export, published read,
  `published-apps` index, `metadata.published` on Redis pub/sub.
- Data Runtime (8083, module split `api/engine/storage/authorization`): the full §5/§6/§7
  surface — write path (defaults incl. sequence refs drawn once at create, field
  validations, inline children ≤100 with cascade delete, optimistic locking, soft
  delete, uniqueness via partial unique indexes), query path (DSL GET + `/query`
  aggregates, batch ≤500 with per-item outcomes), sequences (cached Redis blocks +
  gapless in-transaction), Idempotency-Key on create/batch, RLS everywhere (fail-closed
  policies; the system role carries BYPASSRLS for the materializer/catch-up —
  documented in `deploy/postgres-init`), platform authorization store + role matrix
  (fail closed), materializer reacting to publish + restart catch-up, ArchUnit layering.
- **Exit demo verified live** (compose stack + services + real Keycloak tokens):
  app → publish → CRUD with gapless `JE-00000x` numbering, inline lines, 409 on stale
  versions, idempotent replay, aggregates. Load test (§10): read p95 39.4 ms
  (< 50), list p95 37.2 ms (< 300), write p95 33.4 ms (≤ 100) — PASS
  (`docs/loadtests/results-2026-08-21.md`).
- Environment track: Kind-on-Podman config, per-service Helm charts + umbrella,
  Skaffold (jib) — written; live cluster bring-up is the remaining operational check
  (validated declaratively; not yet exercised against a running Kind cluster).

Suites: 162 tests, `./mvnw verify` green (Testcontainers: Postgres + Redis; rootless
Podman socket env documented in the README).

## Phase 2 — Builder UI & Security ◐ (spec: PHASE-2-UI-BUILDER.md)

**Backend implemented:**
- `expression-dsl` (expr/v1, §7/Annex A): the JVM parser/evaluator with the pinned
  grammar — exact BigDecimal semantics, null-aware operators, date arithmetic,
  membership, the closed function set, injectable clock — plus the shared conformance
  corpus (40 cases) and compile-check wired into the Metadata Service save/publish
  (validations may read the clock; formula fields may not, PHASE-3 §3). Slots stay
  inert until Phase 3 write-path evaluation.
- RBAC + field security (§9): PermissionSet as versioned, promoted metadata
  (roles, object CRUD matrix, field visible/readonly/hidden) with save-validation
  rules; server-side enforcement in the Data Runtime — the matrix decides
  create/read/update/delete per app-scoped role (`App.role` assignments in the
  platform DB), hidden fields strip from every projection, writes to
  hidden/readonly fields reject.
- Tenant onboarding + platform-admin API (§10): `POST /api/v1/admin/tenants`
  (tenant row + first admin, orchestrating Keycloak user creation via the service
  account with realm-management roles) and role assignments — admin-gated, riding
  the engine behind a UserProvisioner port (layering rules hold).
- Realm export: service account `service-account-novaforge-runtime` with
  manage-users; role-assignment check widened for app-scoped roles (V2 migration).

**Not implemented:** the React builder/runtime UIs (§3–§6, §8 — the largest remaining
Phase 2 surface: page model, component catalog, renderer, entity/page builders) and
the TS evaluator twin for the conformance corpus. Backend surfaces are ready for it:
published reads, expression bindings compile-checked, field security stripping.

## Phase 3 — Business Logic ◐ (spec: PHASE-3-BUSINESS-LOGIC.md)

**Implemented — §3 write-path evaluation** (the ARCHITECTURE §2.4 chain is now:
resolve → authorize → defaults → formula/roll-up evaluation → validation rules →
persist → event seam → shaped projection):
- expression defaults (`{"expression": "…"}` — clock-free, compile-checked, evaluated
  at the defaults step before validations)
- validation rules (record-scope expressions extending Phase 1 field constraints,
  failing with the authored message)
- formula fields (own-record expressions stored at write time, never computed on
  read; implicitly readonly; null-propagating string/numeric functions keep stored
  formulas total)
- roll-up summaries (`SUM/COUNT/MIN/MAX/AVG(relationship[.field])` — creates
  aggregate the in-memory child set; updates recompute in the child's write
  transaction and only rewrite the parent when a value moved)

**Not implemented (Phase 3 remainder):** the Kafka event spine + transactional
outbox (§4 — the no-op event recorder still stands), flow-IR hooks with the closed
primitive set (§5/ADR-008), the builder test harness (ADR-010), the Script Engine
v0, Audit/Telemetry/Loki expansions.

## Phases 4–8 ⬜

Not started. `requestApproval`/`transitionState` grammar notes live in the specs.
