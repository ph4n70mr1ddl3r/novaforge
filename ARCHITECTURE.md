# NovaForge — Technical Architecture

Companion to [PLAN.md](./PLAN.md). Covers service architecture, data strategy, security model, and the hardest technical problems.

---

## 1. System Overview

```
                                ┌──────────────────────────────────────────┐
                                │                 Kubernetes              │
   Browser ── HTTPS ──►  API Gateway (Spring Cloud Gateway)               │
   (Builder & Runtime UI)       │  routing • JWT verify • rate limit      │
                                └─────┬────────────────────────┬─────────┘
                                      │                        │
                    ┌─────────────────┼──────────────┐         │
                    ▼                 ▼              ▼         ▼
              ┌──────────┐    ┌──────────────┐  ┌────────┐ ┌─────────┐
              │Identity  │    │Metadata Svc  │  │UI      │ │Reporting│
              │(Keycloak)│    │(design-time) │  │Builder │ │Service  │
              └──────────┘    └──────┬───────┘  └────────┘ └─────────┘
                                      │ applies versions
                                      ▼
                              ┌───────────────┐     ┌──────────────┐
                              │Data Runtime   │◄───►│Script Engine │
                              │Service (CRUD, │    │(GraalVM JS)  │
                              │query DSL,     │    └──────────────┘
                              │permissions)   │
                              └───┬───────┬───┘
                    ┌─────────────┘       └─────────────┐
                    ▼                                     ▼
             ┌────────────┐                        ┌───────────┐
             │ PostgreSQL │                        │   Kafka   │
             │ (JSONB     │                        │ domain    │
             │  hybrid)   │                        │ events,   │
             └────────────┘                        │ audit     │
             ┌────────────┐                        └─────┬─────┘
             │Redis (meta│                              │
             │cache,seq) │        ┌───────────┐   ┌──────┴──────┐
             └────────────┘        │Workflow   │◄──┤Audit/Notify/│
                                   │(Flowable) │   │Integration  │
                                   └───────────┘   └─────────────┘
```

*Sketch — the File and Scheduler services (§2.8) are omitted for readability.*

**Principles**
1. **Single write path:** all record writes go through Data Runtime — it enforces metadata, permissions, validations, and emits events. Nothing writes to tenant tables directly.
2. **Metadata is cached aggressively** (Redis + in-memory, version-keyed) — every request consults hot metadata.
3. **Event spine:** Data Runtime publishes `record.created/updated/deleted` on Kafka; workflow, audit, notifications, integrations are pure consumers.
4. **Design-time/runtime split:** Metadata Service mutates definitions; Data Runtime serves traffic. Promotions are versioned definition deployments.

---

## 2. Service Details

### 2.1 API Gateway
- Spring Cloud Gateway; routes `/api/v1/runtime/**`, `/api/v1/metadata/**`, `/api/v1/workflow/**`, etc. (versioning rule: §6)
- JWT validation (Keycloak JWKS), tenant header derivation (`X-Tenant-Id` from token claim), rate limiting via Redis — the limiter itself activates with Phase 6's first public route (PHASE-0 §6.1's deferral; PHASE-6 §6), not at Phase 0. The default JWT requirement has exactly one API-route exception — the anonymous inbound-webhook prefix that arrives with Phase 6 (PHASE-6 spec §2/§6), rate-limited from its first day.

### 2.2 Identity Service
- Keycloak realms: one realm per tenant (or single realm + tenant claim — decide at Phase 0; single realm scales simpler, realm-per-tenant isolates better).
- Roles: platform roles (`admin`, `builder`, `user`) + app-defined roles. Two mechanisms were considered — syncing app-defined roles into Keycloak via its Admin API, or an Authz module inside Data Runtime backed by the platform DB; the recommendation below (and ADR-002's direction) picks the latter: authorization resolves in the platform, Keycloak stays authentication-only.
- Recommendation: Keycloak handles *authentication only*; Data Runtime handles *authorization* (roles stored in platform DB) — simpler than syncing dynamic roles into Keycloak.

### 2.3 Metadata Service (design-time)
- Owns: `AppDefinition`, `EntityDefinition`, `FieldDefinition`, `RelationshipDefinition`, `PageDefinition`, `RuleDefinition` (event-hook and scheduled-job rules — the Business Rules branch of PLAN.md §2; the cron *registry* stays with the Scheduler, §2.8), `WorkflowDefinition`, `StateMachineDefinition`, `SLADefinition`, `SharingRuleDefinition` (the PermissionSet branch — all four land in Phase 4, PHASE-4 spec §2; WorkflowDefinition has been listed since v0, the rest join it then), `ReportDefinition`, `DashboardDefinition`, `PermissionSet` (app role definitions + object/field/record-rule security — the Permissions branch of PLAN.md §2; user→role assignments are tenant data, not promoted metadata — PHASE-2 spec §9), `TestSuiteDefinition` (builder test suites — [ADR-010](./docs/adr/ADR-010-builder-test-harness.md)), plus connector, webhook, import, and API-client definitions (the Integrations branch of PLAN.md §2; API clients themselves stay deferred with demand — PHASE-6 spec §1), sandboxed-script artifacts (versioned with the same review/promotion path as definitions — ADR-008 #4), and app-scoped settings definitions (sequences, currencies, localization, shared enums — the Settings branch of PLAN.md §2; sequence *execution* stays with the Data Runtime per PLAN.md §3).
- Validates definitions on save (schema validation + referential integrity, e.g., formula references exist).
- Hosts the **test runner** per [ADR-010](./docs/adr/ADR-010-builder-test-harness.md): executes builder test suites against a scratch tenant pinned to a published draft version — steps run as synthetic actors through the Data Runtime's generic APIs (no test mode in the write path), and run artifacts are bound to the exact definition version; green runs gate change-set promotion (PLAN.md P8).
- On publish: bumps version, writes to `metadata_versions`, emits `metadata.published` on the Kafka spine (cache invalidation and the §4 storage materializer both react to this one event; until Kafka lands in Phase 3, Redis pub/sub carries the same envelope — pinned in the Phase 1 spec, PHASE-1-METADATA-CORE.md §4).
- API: REST + async import/export of app ZIP (JSON definitions) for promotion.

### 2.4 Data Runtime Service (the heart)
- Generic REST API:
  - `POST /api/v1/runtime/{entity}` create, `GET .../{id}`, `PATCH`, `DELETE`
  - `GET /api/v1/runtime/{entity}?filter=...&sort=...&page=...` (structured query DSL, not raw SQL)
  - `POST /api/v1/runtime/{entity}/query` for complex queries (aggregations)
  - `POST /api/v1/runtime/batch` for bulk ops
- Responsibilities per request: resolve metadata → authorize (object/field/record) → apply field defaults → evaluate formula/roll-up fields (§3) → run validation rules (state-machine transition guards join here in Phase 4 — PHASE-4 spec §3) → apply hooks (flow-IR primitives first per [ADR-008](./docs/adr/ADR-008-declarative-first-logic.md); sandboxed scripts only as escape hatch via Script Engine) → persist with optimistic locking → emit Kafka event (via transactional outbox — PHASE-3 spec §4) → return shaped projection (respecting field-level security).
- Record locking: `version` int, HTTP 409 on conflict; ERP posting documents get their immutability from Phase 7's `freezeOnTerminal` terminal-state write freeze (PHASE-7 spec §3) rather than bespoke lock machinery.
- Platform-admin API: tenant provisioning and user→role assignment over the platform-DB authorization data — the same store the `authorization/` module reads at request time (ADR-002's direction, §2.2; gateway route `/api/v1/admin/**`, `admin`-gated, audited — PHASE-2 spec §10).

### 2.5 Script Engine
- **Role per [ADR-008](./docs/adr/ADR-008-declarative-first-logic.md): escape hatch only** — sync hooks run flow-IR primitives (compiled at publish); scripts are written when primitives cannot express the logic, and their usage is tracked (script-ratio KPI).
- GraalVM polyglot (JS), `Context` per execution with:
  - CPU-time and heap caps, statement/loop watchdog
  - No host I/O; an explicit whitelisted API surface (`$record`, `$data.query` (the Data Runtime query API under the caller's authorization, §5 item 4 — scripts cannot bypass the single data path), `$http` only inside connector sandbox, `$log`)
  - Warm context pool per tenant app version
- The **expression DSL** (formulas, validation rules, flow guards per ADR-008; UI bindings per ADR-009) is **not evaluated here**: it is a pure, deterministic language served by the shared `expression-dsl` library (§7), used in-process by the Metadata Service (compile-checks, Phase 2) and the Data Runtime (write-path evaluation, Phase 3) — no sandbox needed. This service exists solely for GraalJS escape-hatch scripts.
- Hook failure policy (flow-IR graphs and escape-hatch scripts alike): `beforeSave`/`beforeDelete` failure = abort transaction; `afterSave`/`afterDelete` failure = retry via Kafka (idempotency required).

### 2.6 Workflow Service
- Flowable 7 embedded; process definitions authored as BPMN XML (v1 is editor-agnostic XML metadata — the visual designer defers with demand, PHASE-4 spec §9/§16).
- Subscriptions to domain events can start processes (`on record.updated where status='submitted'`).
- **State machines** as first-class metadata (states, allowed transitions, guards in the platform expression DSL per [ADR-008](./docs/adr/ADR-008-declarative-first-logic.md)) — most ERP flows are state machines, not full BPMN. Enforcement sits on the Data Runtime write path, not here (PHASE-4 spec §3): this service consumes state-change events and never mutates records.
- Human tasks exposed via task inbox API; approvals support parallel modes (`any`, unanimous `all`) — sequential chains arrive as a versioned mode on demand (PHASE-4 spec §1) — plus delegation, reassignment, escalation timers.

### 2.7 Reporting Service
- Compiles report definitions into Query DSL calls (never raw SQL), supports: filters, group-by, aggregates, pivot (v1: multi-field group-by — PHASE-5 spec §3), drill-through links.
- Large exports run async (scheduler) streaming to file service (File Service lands in Phase 6 — direct downloads capped at 10k rows until then: PHASE-5 spec §6, async job: PHASE-6 spec §7).
- Chart payloads shaped for the frontend chart lib (ECharts).

### 2.8 Other services
- **UI Builder Service:** component catalog hosting, builder sessions, preview/scaffolding. Page/layout definitions persist as versioned metadata in the Metadata Service (§2.3) — no separate persistence path, so promotion stays uniform. No separate module exists in v1: the catalog ships as versioned metadata and preview runs client-side; the service is extracted only if builder sessions/scaffolding later need server-side state (PHASE-2 spec §8).
- **File Service:** MinIO/S3, presigned uploads, attachment metadata entity, checksum, optional ClamAV hook.
- **Notification Service:** templates (entity/field tokens), channel preferences, inbox + email via SMTP/SES.
- **Integration Service:** connector runtime (outbound REST first — the SOAP/DB/file connector types of PLAN.md §3 join the same frame on demand — with mapping/retry/circuit-breaker, inbound webhook endpoints with HMAC validation), all deliveries idempotent with DLQ.
- **Audit Service:** Kafka consumer → append-only store (Postgres partitioned by month; option to offload cold data to S3/Parquet later).
- **Scheduler Service:** DB-backed cron registry + ShedLock-style distributed locks; scheduled-job definitions are versioned app metadata (the Business Rules branch of PLAN.md §2) — the registry is the runtime schedule state activated on publish. Triggers scheduled flows and scripts (ADR-008), scheduled reports, and scheduled workflow process starts; in-process BPMN timers (escalations and the like) stay with embedded Flowable (§2.6).

---

## 3. Metadata Model (v0 sketch)

```jsonc
// EntityDefinition
{
  "id": "ent_journal_entry", "apiName": "JournalEntry",
  "label": "Journal Entry", "displayField": "reference",
  "fields": [
    { "apiName": "reference",  "type": "text", "length": 32, "required": true },
    { "apiName": "entryDate",  "type": "date", "required": true },
    { "apiName": "status",     "type": "enum", "values": ["DRAFT","POSTED","REVERSED"] },
    { "apiName": "periodId",   "type": "lookup", "target": "AccountingPeriod" },
    { "apiName": "totalDebit",  "type": "decimal", "precision": 18, "scale": 4,
      "formula": "SUM(lines.debit)" },  // roll-up/formula evaluated at write time
    { "apiName": "totalCredit", "type": "decimal", "precision": 18, "scale": 4,
      "formula": "SUM(lines.credit)" }
  ],
  "relationships": [
    { "apiName": "lines", "type": "child", "target": "JournalLine",
      "cascadeDelete": true }   // child = master-detail
  ],
  "validations": [
    { "name": "balanced", "scope": "record",
      "expression": "totalDebit == totalCredit",
      "message": "Entry must balance" }
  ],
  "indexes": [{ "fields": ["entryDate"], "unique": false }]
}
```

Field types (v1): text, longText, richText, enum, boolean, int, long, **decimal(p,s)**, date, datetime, time, uuid, email, phone, url, json, lookup, child, m2m, file, money(currency-aware).

Fields may carry an optional `group` label; default detail pages section on it (no group → a single default section — see the Phase 2 spec's default-resolver rules). Common field attributes: `required`, `readonly`, `default` (static values from Phase 1; expression defaults arrive with Phase 3 write-path evaluation — PHASE-1 spec §5), `length`, `precision`/`scale`, `group`, `formula` (evaluated at write time, §2.4). Entities may likewise carry an optional `module` label; app navigation groups entities by it (no module → a default group, mirroring field groups).

---

## 4. The Storage Decision (critical)

Three options for storing *tenant record data* under dynamic schemas:

| | A: Dynamic DDL (table per entity) | B: Pure JSONB | **C: Hybrid (recommended)** |
|---|---|---|---|
| Schema change | ALTER TABLE (fast on PG, but risky at runtime) | None | None for most changes; add generated column on demand |
| Query perf | Native | GIN + expression indexes only | Native for hot fields via generated columns |
| Integrity | Full FK/NOT NULL/CHECK | App-enforced only | App-enforced + DB CHECKs on generated columns |
| Ops complexity | DDL migrations per tenant | Low | Low-moderate |
| Multi-tenant scale | Table explosion risk (entities × tenants) | Clean | Clean |

**Chosen: Hybrid JSONB** — to be load-validated by the 1M-row spike (PLAN.md §8) before Phase 1 implementation; ADR-001 records the final call.
```sql
CREATE TABLE rec_records (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  entity_id     text NOT NULL,
  version       int NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL,
  created_by    uuid NOT NULL,
  updated_by    uuid NOT NULL,
  deleted       boolean NOT NULL DEFAULT false,
  data          jsonb NOT NULL
);
-- Per-entity materialized hot columns:
CREATE TABLE rec_journal_entry (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  data jsonb NOT NULL,
  -- generated columns promoted from JSONB for filter/sort/index:
  reference text GENERATED ALWAYS AS (data->>'reference') STORED,
  entry_date date GENERATED ALWAYS AS ((data->>'entryDate')::date) STORED
);
CREATE INDEX ON rec_journal_entry (tenant_id, entry_date DESC);
```
- Base table (`rec_records`) is the source of truth for generic ops; per-entity tables are **projection views** (or generated tables) for query performance on indexed fields.
- Materializer listens to `metadata.published` and creates/refreshes projections — no DDL on the hot path, DDL happens at publish time only.
- The sketch's `CREATE TABLE` depicts the generated-table variant, where `data` is duplicated and must be kept current (trigger-maintained or dual-written on the Data Runtime write path); a pure view over `rec_records` needs no sync but forgoes stored generated columns and their indexes. Which variant — and its sync mechanics — is a central question for the 1M-row spike; ADR-001 records the call (PLAN.md §8).
- Postgres **RLS** (`tenant_id = current_setting('app.tenant')`) as defense-in-depth against tenant leakage.
- App-layer type enforcement: decimal precision/scale is validated in the Data Runtime (BigDecimal always).
- The whole strategy sits behind the Data Runtime's `storage` module boundary (§7) — the storage SPI that lets the strategy evolve without touching the engine or API layers (PLAN.md §6).

**Money rule:** `decimal(18,4)` minimum storage; all arithmetic via `BigDecimal` with banker's rounding config per currency. Never doubles, anywhere.

---

## 5. Security Model

1. **Authentication:** OIDC JWT (Keycloak); gateway validates signature/expiry; services re-derive tenant from claims.
2. **Authorization layers (Data Runtime):**
   - *Object-level:* role × entity → CRUD allow matrix
   - *Field-level:* visible/read-only/hidden per role; projections strip hidden fields server-side
   - *Record-level:* rule-based sharing (owner, role hierarchy, criteria sharing) evaluated into row filters appended to every query
3. **Tenant isolation:** JWT claim → request context → RLS session var → every query filtered. Integration tests assert cross-tenant access fails.
4. **Script sandbox:** see §2.5; scripts run with the *calling user's* authorization context.
5. **Audit:** every write emits audit event (field-level diffs); auth events, permission changes, definition publishes audited too.
6. **Secrets:** connector credentials encrypted at rest (AES-GCM, keys in KMS/Vault).

## 6. Cross-Cutting Concerns

- **Tracing:** OpenTelemetry (W3C traceparent propagated; Kafka headers carry trace context).
- **Idempotency:** all mutating APIs accept `Idempotency-Key`; event consumers dedupe on `(event_id, consumer)`.
- **Versioning:** REST APIs versioned `/api/v1/...`; app definitions versioned independently; runtime executes the *published* version, builder edits drafts.
- **Errors:** RFC 7807 problem+json with platform error codes.
- **Config:** Spring Cloud Config / Kubernetes ConfigMaps; per-env Helm values.
- **Container toolchain (Podman-first):**
  - Build/run locally with **Podman + Buildah** — rootless and daemonless; `podman machine` on macOS/Windows.
  - Local Kubernetes via **Kind with the Podman provider** (`KIND_EXPERIMENTAL_PROVIDER=podman`) or Minikube `--driver=podman`.
  - **Skaffold** `--platform=podman` (or `container-structure` config) for inner-loop rebuild/deploy against the local Kind cluster.
  - **Testcontainers** works with Podman: expose the podman socket and set `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` (rootless) — CI included.
  - Single-service debugging without a cluster: `podman kube play` runs the same K8s manifests from `deploy/k8s-base/`.
  - Production nodes run containerd/CRI-O; Podman-built OCI images are drop-in compatible. CI builds with `quay.io/podman/stable` and pushes to any registry (GHCR/Quay).

## 7. Suggested Repo Layout (monorepo)

```
spring_erp/
├── PLAN.md, ARCHITECTURE.md
├── platform/                     # shared libs (versions managed by root pom.xml)
│   └── libs/                     # shared libraries
│       ├── common-core/          # result types, error codes, context
│       ├── metadata-model/       # definition POJOs + JSON schema
│       ├── security-context/     # tenant/actor propagation
│       ├── event-schemas/        # Kafka event contracts
│       ├── expression-dsl/       # expression DSL: JVM parser/evaluator +
│       │                          #   conformance fixtures (TS twin in frontend/shared)
│       └── test-support/         # Testcontainers bases
├── services/
│   ├── gateway/
│   ├── metadata-service/
│   ├── data-runtime/             # largest service — split modules:
│   │   ├── api/  engine/  storage/  authorization/
│   ├── script-engine/
│   ├── workflow-service/
│   ├── ui-builder-service/      # on-demand extraction only (§2.8) — not a v1 module
│   ├── reporting-service/
│   ├── file-service/
│   ├── notification-service/
│   ├── integration-service/
│   ├── audit-service/
│   └── scheduler-service/
├── frontend/
│   ├── builder-ui/               # React design-time
│   ├── runtime-ui/               # metadata renderer + shell
│   └── shared/                   # page-model types, expression runtime, registry
├── deploy/
│   ├── compose/                  # podman compose: lean local stack (PG, Redis,
│   │                             #   Kafka, Keycloak, Prometheus/Grafana —
│   │                             #   infra only, per the Phase 0 spec §7;
│   │                             #   gateway + backing service run on the host)
│   ├── kind/                     # Kind-on-Podman cluster config (full stack)
│   ├── helm/                     # per-service charts + umbrella
│   └── k8s-base/                 # shared manifests (also `podman kube play`-able)
└── docs/{adr,specs}/              # architecture decision records, phase specs
```

No `identity/` module exists: Identity is a *deployed* Keycloak (realm/client configuration under `deploy/`, per PLAN.md §3), not bespoke service code; tenant/role administration data lives in the platform DB (§2.2, ADR-002).

## 8. ADR Log (decide early, record why)

| ADR | Topic | Status |
|-----|-------|--------|
| 001 | Storage strategy: hybrid JSONB + projections | Proposed |
| 002 | AuthN in Keycloak, AuthZ in platform DB | Proposed |
| 003 | Scripting: GraalVM JS sandbox (escape hatch per ADR-008) | Proposed |
| 004 | Workflow: Flowable embedded + native state machines | Proposed |
| 005 | Monorepo, Maven, Java 21 (Boot/Cloud versions: ADR-007) | Proposed |
| 006 | Multi-tenancy: shared schema + RLS | Proposed |
| 007 | Adopt latest: Spring Boot 4.1 / Spring Framework 7 / Cloud 2025.1 | Accepted — [ADR-007](./docs/adr/ADR-007-adopt-spring-boot-4.md) |
| 008 | Declarative-first business logic; scripts as escape hatch | Accepted — [ADR-008](./docs/adr/ADR-008-declarative-first-logic.md) |
| 009 | Declarative UI: layered generation + component catalog, no codegen | Accepted — [ADR-009](./docs/adr/ADR-009-declarative-ui.md) |
| 010 | Builder test harness: tests as versioned metadata, gating promotion | Accepted — [ADR-010](./docs/adr/ADR-010-builder-test-harness.md) |

Entries marked *Proposed* live in this log only — an ADR file is written when the decision is accepted (e.g. ADR-001's file will record the storage-spike outcome, §4). Expected acceptance points: ADR-005 with the Phase 0 repo skeleton (PHASE-0 §4), ADR-001 at storage-spike closure (PHASE-1 §2), ADR-002/ADR-006 with the Phase 1 authorization gate and RLS implementation (PHASE-1 §6–§7), ADR-003 at the Phase 3 Script Engine landing (PHASE-3 §6), ADR-004 at Phase 4 start (PHASE-4 §2).

## 9. Performance Targets (storage/query targets: approach validated by the pre-Phase-1 storage spike — §4 / PLAN.md §8 — implementation by the Phase 1 load test (PHASE-1-METADATA-CORE.md §10); report and script targets validated as those services land in Phases 3–5)

| Operation | Target |
|-----------|--------|
| Simple record read (cache warm) | p95 < 50 ms |
| Filtered list query, 1M rows/tenant, indexed | p95 < 300 ms |
| Record write with 1 sync hook | p95 < 150 ms |
| Report (1M rows aggregate, materialized path) | p95 < 2 s |
| Script hook execution (warm) | p95 < 20 ms |
