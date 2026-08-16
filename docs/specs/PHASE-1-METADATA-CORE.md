# Phase 1 — Metadata Core & Data Runtime: Implementation Specification

> Detailed spec for the metadata model, definition APIs, the generic record/query
> runtime, and the hybrid-JSONB storage layer. Product context:
> [PLAN.md](../../PLAN.md) §5 Phase 1. Storage strategy: [ARCHITECTURE.md](../../ARCHITECTURE.md)
> §4 / ADR-001; service design: ARCHITECTURE.md §2.3–§2.4.
>
> | | |
> |---|---|
> | Status | Draft for review |
> | Date | 2026-08-16 |
> | Owner | Platform team |
> | Estimate | 4–6 weeks (per PLAN.md §5) |
> | Depends on | Phase 0 (repo skeleton, compose infra, gateway + JWT, CI — PHASE-0 spec) |

## 1. Objective & Exit Criteria

Deliver the Phase 1 exit: *create an entity via API, then CRUD records through the
generic record API with field validations (required/type/uniqueness) enforced* — plus
the definition/publish machinery and storage foundation every later phase builds on.

In scope: the `metadata-model`, `security-context`, and `test-support` libs (charters
deferred from Phase 0), Metadata Service definition APIs with draft→publish versioning,
Data Runtime generic record/query/batch APIs, hybrid-JSONB storage behind the `storage`
SPI (ARCHITECTURE.md §4), sequences, soft delete, optimistic locking, the interim
metadata-publish transport, the K8s dev environment (if slipped from Phase 0), and the
1M-row load test (PLAN.md §6: load-test now, not in Phase 7).

Out of scope: expression validation rules, formula/roll-up fields, hooks, and the Kafka
event spine (Phase 3 — ADR-008; the schema *slots* for expressions are accepted but
inert until then, mirroring ADR-008's grammar-fixed-activates-later pattern); role
editors and field-level security (Phase 2), record sharing (Phase 4 — PHASE-2 spec §9);
any UI (Phase 2); the builder test harness (Phase 3 — ADR-010); File Service (Phase 6).

## 2. Prerequisite: Storage Spike Closure (ADR-001)

The 3-day, 1M-row spike (PLAN.md §8 item 2) runs **before** implementation (T5): it
decides the projection variant — pure view vs generated table with `data` duplication —
and the sync mechanics (trigger-maintained vs dual-write), per ARCHITECTURE.md §4.
ADR-001 then moves Proposed → Accepted with the measured numbers.

This spec assumes hybrid JSONB wins. If the spike overturns it, only the `storage/`
module changes: `api/` and `engine/` code against the SPI, not the tables — which is
the SPI's purpose (ARCHITECTURE.md §4/§7).

## 3. Shared Libraries Landing in Phase 1

Charters were recorded in PHASE-0 §5.4 (empty modules rot, so they are created now):

| Lib | Contents | Notes |
|---|---|---|
| `metadata-model` | definition POJOs + JSON Schema v0: app, entity, field, relationship, **page** | Page schema is reserved now (PLAN.md §8 item 3) but authored only from Phase 2. `formula` and expression `validations` slots are schema-accepted, inert until Phase 3. |
| `security-context` | tenant/actor propagation: async-executor wrappers, Kafka header constants (producer/consumer arrive with the Phase 3 spine), mock-test fixtures | Builds on `common-core`'s `TenantContext` (PHASE-0 §5.2). |
| `test-support` | Testcontainers 2 bases: Postgres with RLS fixtures, compose-stack clients | Podman-socket env documented in README on first use (PHASE-0 §10). |

Schema v0 validation rules (enforced on save, §4): entity `apiName` PascalCase and
unique per app; field `apiName` camelCase and unique per entity; relationship targets
resolve within the app; `displayField` exists; enum `values` non-empty;
`precision`/`scale` valid for decimal; index fields exist; field types restricted to
the v1 set of ARCHITECTURE.md §3. The `file` type is schema-valid from v0 but has no
upload path until the File Service lands (Phase 6 — PLAN.md §5); PHASE-2 §5's
disabled stub is the matching UI state.

## 4. Metadata Service: Definitions, Validation, Publish

Port 8081 (carried from Phase 0; the gateway route already exists). The per-service
database `novaforge-metadata` lands now — Phase 0's shared Postgres carried only
Keycloak (PHASE-0 §7).

APIs (draft workspace plus the runtime's published read below; OpenAPI generated
per service, PLAN.md §4):

- `POST/GET/PATCH/DELETE /api/v1/metadata/apps` — app definitions
- `POST/GET/PATCH/DELETE /api/v1/metadata/apps/{appId}/entities` — entity definitions
  as one document (fields, relationships, indexes per ARCHITECTURE.md §3)
- `POST /api/v1/metadata/apps/{appId}/publish` — snapshot drafts → new immutable version
- `GET /api/v1/metadata/apps/{appId}/versions` and `.../versions/{v}/export` — version
  list plus synchronous JSON bundle export (async ZIP import/export and change-set
  review are P8 machinery, ARCHITECTURE.md §2.3)
- `GET /api/v1/metadata/apps/{appId}/published` — the currently published version's
  definition bundle, response carrying its version number so clients cache by version
  (the PHASE-2 §2 renderer-state key): the **runtime read path for rendering**. First
  consumer is the Phase 2 renderer (entity/page definitions — PHASE-2 §3); Phase 5's
  dashboard loading reuses it (PHASE-5 §2). Draft CRUD above stays `builder`-scoped
  (PHASE-2 §9's design-time stance); the published read serves any authenticated
  tenant user (`user`+) and carries rendering-relevant definitions only — escape-hatch
  script artifacts and credential references are excluded (scripts execute server-side;
  secrets never ride metadata — PHASE-6 §9).

Save validation = JSON Schema (metadata-model) + referential integrity (§3 rules).
Publish = validate all drafts, bump version, write `metadata_versions`, emit
`metadata.published`. Drafts stay mutable; the runtime serves published versions only
(design-time/runtime split, ARCHITECTURE.md §6). Phase 1 has a single implicit change
set per app (draft vs published); formal change sets arrive with P8.

**Publish event — envelope pinned now, transport swapped in Phase 3:**

```json
{ "event": "metadata.published", "tenantId": "…", "appId": "…",
  "version": 12, "publishedAt": "…", "actorId": "…" }
```

Until the Kafka spine lands (Phase 3), the envelope rides Redis pub/sub channel
`novaforge.metadata.events` — this is the interim transport ARCHITECTURE.md §2.3
assigns this spec to pin. Metadata caches (Metadata + Data Runtime, version-keyed per
ARCHITECTURE.md §1) and the storage materializer (§6) subscribe; Phase 3 rebinds the
same envelope to Kafka with no consumer change beyond the client.

## 5. Data Runtime: Record & Query APIs

New service `novaforge-data-runtime`, port 8083; the gateway gains the
`/api/v1/runtime/**` route. Module split per ARCHITECTURE.md §7: `api/`, `engine/`,
`storage/` (§6), `authorization/` (§7).

Surface (ARCHITECTURE.md §2.4):

- `POST /api/v1/runtime/{entity}` create; `GET .../{id}`; `PATCH .../{id}`;
  `DELETE .../{id}` (soft)
- `GET /api/v1/runtime/{entity}?filter=...&sort=...&page=...` — structured query DSL,
  never raw SQL
- `POST /api/v1/runtime/{entity}/query` — aggregations
- `POST /api/v1/runtime/batch` — bulk ops with per-item outcomes, max 500 items

Query DSL v1 (JSON; golden-tested against generated SQL, §9):

```json
{ "filter": { "and": [ { "field": "status", "op": "eq", "value": "POSTED" },
                       { "field": "entryDate", "op": "gte", "value": "2026-01-01" } ] },
  "sort": [ { "field": "entryDate", "dir": "desc" } ],
  "page": { "size": 50, "offset": 0 } }
```

Operators v1: `and`/`or` nesting, `eq ne in gt gte lt lte contains isNull`
(`contains` on text fields only). Aggregates: `count sum avg min max` with optional
`groupBy`. Paging model is Q2 (§12). The GET list endpoint carries the same DSL
URL-encoded in its `filter`/`sort`/`page` params; anything richer — deep nesting,
aggregates — goes to `POST /{entity}/query`.

Write path (the Phase 1 slice of the ARCHITECTURE.md §2.4 pipeline): resolve metadata →
authorize (§7) → apply static field `default`s (expression defaults arrive with
Phase 3 write-path evaluation — PHASE-2 §7) → field validations → persist with optimistic locking → event seam
(below) → shaped projection. Field validations v1: `required`, type, `length`,
`precision`/`scale` (BigDecimal, never doubles — ARCHITECTURE.md §4 money rule), enum
membership, `uniqueness`, lookup-target existence, writes to `readonly` fields
rejected. `GET` supports a sparse `fields` parameter — server-side stripping is the
mechanism Phase 2 field security builds on (PHASE-2 spec §9).

- **Optimistic locking:** `version` int; conflict → 409 with `CONFLICT_VERSION`
  ("4090", PHASE-0 §5.2).
- **Soft delete:** `deleted` flag; reads and lists exclude deleted by default;
  `includeDeleted=true` is admin-only.
- **Idempotency:** `Idempotency-Key` honored on create and batch in Phase 1 — the
  first installment of ARCHITECTURE.md §6's "all mutating APIs" bar (update/delete
  replay keys reuse the identical mechanism when a client needs them). The response
  is recorded in Redis keyed (tenant, actor, key) with a 24 h TTL; replay returns the
  original outcome.
- **Sequences:** definitions are Settings metadata (owned by the Metadata Service,
  ARCHITECTURE.md §2.3); execution lives here (PLAN.md §3). Two modes: `cached`
  (default — Redis block allocation, gaps allowed) and `gapless` (allocated inside the
  record transaction via a locked counter row; serializes writes on that sequence —
  acceptable for document numbering, and required by PLAN.md §1 non-negotiables).
- **Event seam:** an internal `DomainEventPublisher` port; the Phase 1 binding is a
  no-op recorder (asserted in tests, §9). Phase 3 binds the Kafka producer for
  `record.created/updated/deleted` (ARCHITECTURE.md §1) and audit emission
  (ARCHITECTURE.md §5) with no write-path rework.

## 6. Storage Implementation (behind the SPI)

The `storage/` module owns all tenant-data SQL and hosts the **materializer** — it owns
the data-plane tables, so it owns their DDL, reacting to `metadata.published`
(ARCHITECTURE.md §2.3/§4). DDL happens at publish time only, never on the hot path.

- `rec_records` base table plus per-entity projections exactly per ADR-001's recorded
  variant (§2); the projection promotion policy is Q3 (§12).
- Field `uniqueness` (§5) lowers to a DB unique index on the promoted column — or a
  JSONB expression unique index on the base table, per ADR-001's variant — which is
  what makes the §9.2 uniqueness race pass; the write-path check exists to shape the
  friendly `VALIDATION_FAILED` error, not to be the enforcement.
- Postgres **RLS** everywhere: `tenant_id = current_setting('app.tenant')` as
  defense-in-depth; `security-context` sets the session var per request from
  `TenantContext`. Cross-tenant access assertions are mandatory (ARCHITECTURE.md §5).
- Per-service databases land now: `novaforge-metadata` and `novaforge-data` in the
  compose stack (PHASE-0 §7's shared instance remains for Keycloak only).
- Money: `decimal(18,4)` minimum storage, BigDecimal arithmetic, banker's rounding
  config per currency (ARCHITECTURE.md §4).

## 7. Security Slice & Cross-Cutting Rules

- AuthN as proven in Phase 0 (T7 pattern): resource-server JWT; tenant derived from
  the claim into `TenantContext` — services never trust the gateway header.
- Authorization: the `authorization/` module enforces the object-level
  role × entity → CRUD matrix from day one, read at request time from the platform DB
  (the ADR-002 direction, ARCHITECTURE.md §2.2). Phase 1 seeds the platform roles
  (`admin`, `builder`, `user`) as a bootstrap matrix in the platform DB — not yet
  metadata; Phase 2's app-defined roles arrive as versioned `PermissionSet` metadata
  (ARCHITECTURE.md §2.3) with user→role assignments as tenant data (PHASE-2 spec §9).
  The default policy is Q1 (§12); the gate exists so Phase 2 tightens policy rather
  than reworking the write path.
- Errors: per-service `@RestControllerAdvice` rendering `ProblemErrors` (RFC 7807 with
  common-core codes — the PHASE-0 §5.2 deferral lands here).
- Tracing and dashboards continue the Phase 0 baseline (PHASE-0 §8); Grafana gains
  Data Runtime rows.

## 8. Environments & CI

- **Kind-on-Podman + Helm** lands now if not landed as the Phase 0 stretch goal
  (PHASE-0 Q3 recommendation; PLAN.md §5): the full stack (gateway, both services,
  compose infra equivalents) deploys to the cluster; Skaffold's Podman runner covers
  the inner loop (ARCHITECTURE.md §6). Timing is Q4 (§12).
- **CI:** Testcontainers jobs land now, wiring the `quay.io/podman/stable`
  Podman-socket runner label (the PHASE-0 §9 deferral); the README documents the
  Podman socket env on first use (PHASE-0 §10).
- **ArchUnit rules start** (PHASE-0 §10): data-runtime layering
  (`api → engine → storage/authorization`, no skips), no Spring web in platform libs,
  services touch tenant data only through the storage SPI.

## 9. Testing Standards

1. **Golden SQL:** query-DSL fixtures → expected SQL/plan shape (catches dialect drift
   and filter-lowering regressions).
2. Concurrency: optimistic-lock race (one writer wins, the other gets 409), uniqueness
   race, sequence allocation under parallel writers in both modes — `gapless` must
   survive a rolled-back transaction without losing a number.
3. RLS: cross-tenant read/write/delete attempts fail closed (test-support fixtures) —
   ARCHITECTURE.md §5 makes these assertions mandatory.
4. Definition lifecycle: save-validation matrix (every §3 rule), publish immutability,
   version list/export round-trip.
5. Event seam: the no-op recorder's captured events assert correct emission points
   (created/updated/deleted with tenant/entity/id) before Kafka exists.
6. Definition→runtime integration: publish an entity → materializer creates the
   projection → CRUD works without redeploy.

## 10. Performance Validation

The 1M-row load test (PLAN.md §6: "load-test in Phase 1, not Phase 7") validates the
ARCHITECTURE.md §9 storage/query targets: simple read p95 < 50 ms (cache warm),
filtered list p95 < 300 ms on promoted/indexed fields. The write target (150 ms with
1 sync hook) is a Phase 3 measurement; Phase 1's bar is write p95 ≤ 100 ms, reserving
~50 ms of hook headroom so Phase 3 lands inside the existing target. Runs on demand
(T11) and at exit review; results are recorded with the exit review — the strategy
itself stays in ADR-001.

## 11. Task Breakdown

| # | Task | Content | Acceptance criteria |
|---|---|---|---|
| T1 | metadata-model v0 | POJOs + JSON Schema v0 (§3) + round-trip/validation tests | Lib green; invalid definitions rejected by schema |
| T2 | security-context + test-support | Propagation helpers; Postgres/RLS Testcontainers bases | Fixtures boot under the CI Podman runner |
| T3 | Metadata APIs + publish | Draft CRUD, save validation, versioning, export (§4) | create → publish → versions listed; invalid save rejected problem+json |
| T4 | Publish event + caches | `metadata.published` envelope on Redis pub/sub; version-keyed caches subscribe (§4) | Publishing invalidates a warm Data Runtime cache |
| T5 | Storage SPI + materializer | `rec_records`, RLS, projections per the ADR-001 variant (§6) | Publish creates/refreshes projections; cross-tenant tests fail closed |
| T6 | Write path | CRUD + validations + defaults + optimistic lock + soft delete (§5) | Phase 1 exit demo passes |
| T7 | Query path | List + aggregate + batch (§5) | Golden-SQL suite green; 100k fixture served via server-side paging only |
| T8 | Sequences + idempotency | `cached`/`gapless` modes; `Idempotency-Key` (§5) | Concurrent allocation correct; key replay returns the original outcome |
| T9 | Authorization gate | Object-level matrix + seeded roles + default policy (§7, Q1) | Denied role → 403 `FORBIDDEN`; matrix read from the platform DB |
| T10 | K8s + CI expansion | Kind/Helm/Skaffold; Testcontainers CI job; ArchUnit rules (§8) | Full stack on Kind; PR integration green on the Podman runner |
| T11 | Load test + exit review | 1M-row run vs §10; walk the PLAN §5 exit criteria | Targets met, or ADR-001 adjusted with a re-run plan |

Dependency order: T1 → (T2, T3) → T4 → T5 → T6 → (T7, T8, T9) → T11; T10 runs as a
parallel track from week 1 (environment work blocks nothing above).

## 12. Open Questions

Closure points: Q3 before T5 (post-spike), Q2 before T7, Q1 before T9; Q4 is a
week-1 scheduling call.

- **Q1 — Default authorization policy** until Phase 2 role editors exist.
  *Recommendation: fail closed — `admin`/`builder` full CRUD on app entities, `user`
  denied until grants are authorable (consistent with PHASE-2 §9's fail-closed
  testing).*
- **Q2 — Paging model:** offset + total count (builder lists need totals) vs cursor.
  *Recommendation: offset, max page size 200; add keyset if the load test shows
  deep-offset pain.*
- **Q3 — Projection promotion policy:** which fields become generated columns.
  *Recommendation: fields named in explicit index declarations (the entity-level
  `indexes` of ARCHITECTURE.md §3) and unique constraints, plus automatic promotion
  of display and lookup fields; spike numbers confirm the cutoff.*
- **Q4 — Kind cluster timing:** week-1 parallel track vs after exit criteria.
  *Recommendation: week 1 — it is also the Phase 2 preview path (Skaffold).*
