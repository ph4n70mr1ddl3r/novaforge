# NovaForge — No-Code App Builder Platform

> **Mission:** A Spring-microservices-based, metadata-driven no-code platform capable of hosting real business applications — validated by building a working ERP (GL, AR/AP, Inventory) *on the platform itself*, with zero handwritten application code.

**North-star milestone:** A functional mini-ERP (chart of accounts, journal entries with posting rules, invoices, inventory with costing, period close, approvals, financial reports) built entirely through the platform's builder UI, promoted from dev → staging → prod as a versioned app artifact.

---

## 1. Product Pillars

| # | Pillar | What it delivers |
|---|--------|------------------|
| P1 | **Data Modeling** | Entities, 20+ field types, relationships (lookup, master-detail, many-to-many), validations, uniqueness, indexes, audit trail, soft delete |
| P2 | **UI Builder** | Drag-and-drop pages: forms, lists, dashboards, wizards, tabs; conditional visibility; mobile-responsive layouts |
| P3 | **Business Logic** | Validation rules, formula fields, roll-up summaries, declarative event hooks (flow-IR primitives per [ADR-008](./docs/adr/ADR-008-declarative-first-logic.md)), sandboxed scripts as escape hatch only |
| P4 | **Workflow & Automation** | BPMN processes, document state machines (Draft → Posted), approval chains, scheduled jobs, escalation, SLAs |
| P5 | **Reporting & Analytics** | Report builder (grouping, pivots, charts), dashboards, scheduled exports, drill-down to records |
| P6 | **Security & Tenancy** | Multi-tenancy, RBAC + record-level + field-level permissions, data segregation, audit log, OAuth2/OIDC SSO |
| P7 | **Integration** | Auto-exposed entity REST APIs (generic runtime — no per-entity codegen), webhooks, inbound/outbound connectors, message bus topics, import/export |
| P8 | **App Lifecycle** | Apps as versioned artifacts (JSON), sandboxes, change sets, promotion (test-gated per [ADR-010](./docs/adr/ADR-010-builder-test-harness.md)), rollback, app templates/marketplace |

> **Pillar deferrals** (so the table is not read as the v1 contract): wizards/tabs/mobile-responsive layouts — PHASE-2 §1 backlog until dogfood demand; tenant-facing message-bus topics — post-1.0 (PHASE-6 §1); MFA activation — a deployed-Keycloak capability whose realm configuration is deferred with demand (ADR-002 #1, the Identity row in §3); SMS/push/websocket notification channels — deferred with demand (PHASE-4 §8); sequential approval chains and multi-level escalation — v1 ships `any`/parallel-unanimous `all` modes with single-level escalation, the rest arrive as versioned modes (PHASE-4 §1); dedicated pivot mechanics — v1's pivot is multi-field groupBy (PHASE-5 §1); marketplace commerce — v1 ships app templates with a catalog-only marketplace, buying and selling do not (PHASE-8 §6).

**ERP-grade requirements that force platform quality (non-negotiables):**
- Decimal-precision money handling (never floats), multi-currency
- Immutable, auditable postings (append-only journal)
- Approval hierarchies and segregation of duties
- Document numbering sequences (gapless where required)
- Period locking / closing
- High-volume list performance (100k+ rows with server-side paging/sort/filter)

If the platform supports these, it supports almost any business app.

---

## 2. Core Abstractions (everything is metadata)

```
Tenant
 └── App (versioned artifact)
      ├── Entities ── Fields, Relationships, Validations, Formulas
      ├── Pages/Layouts ── Forms, Lists, Dashboards, Navigation
      ├── Business Rules ── Event hooks (flow-IR step graphs; scripts as escape hatch per ADR-008), scheduled jobs
      ├── Workflows ── BPMN definitions, state machines, approvals, SLAs
      ├── Reports & Dashboards
      ├── Permissions ── Roles, record rules, field security
      ├── Integrations ── Connectors, webhooks, credential references (the reference only — secrets never ride metadata, PHASE-6 spec §9), API clients (deferred with demand — PHASE-6 spec §1), import mappings
      ├── Tests ── Suites: fixtures, steps, assertions (ADR-010)
      └── Settings ── Sequences, currencies, localization, enums
```

- **Control plane** (builder/design-time) vs **data plane** (runtime) separation.
- Every definition is stored as versioned JSON; apps deploy by applying definition versions.
- All runtime data access flows through a single **Data Runtime Service** that enforces metadata (types, permissions, validations) — no service bypasses it.

---

## 3. Microservice Landscape

| Service | Responsibility |
|---------|----------------|
| **API Gateway** (Spring Cloud Gateway) | Routing, auth token relay, rate limiting (activates with Phase 6's first public route — ARCHITECTURE.md §2.1), CORS (deferred — same-origin static serving keeps it out of v1, PHASE-2 spec §2) |
| **Identity Service** | OIDC via deployed Keycloak — no bespoke service module (ARCHITECTURE.md §7); authentication, MFA, SSO federation (MFA/federation are deployed-Keycloak capabilities — realm activation deferred with demand); tenant/role administration data lives in the platform DB (ADR-002) |
| **Metadata Service** | CRUD of app definitions: entities, fields, pages, rules (full owns-list: ARCHITECTURE.md §2.3); validation of definitions; versioning & change-sets; builder test-suite runner (ADR-010) |
| **Data Runtime Service** | Generic record APIs driven by metadata; permission enforcement; query engine (filter/sort/page/aggregate); sequences; audit emission; in-process expression & field-validation engine (ADR-008 #3); platform-admin API — tenant provisioning + user→role assignments over the platform-DB authorization data (PHASE-2 spec §10) |
| **Script Engine Service** | Sandboxed execution of user scripts (escape hatch per ADR-008); resource limits, warm pools (deferred with demand — ADR-003 #4, PHASE-3 §6) — the expression DSL runs in-process in the Data Runtime, not here (ARCHITECTURE.md §2.5) |
| **Workflow Service** | Flowable (BPMN) runtime, approvals, timers/tasks; state machines are metadata enforced on the Data Runtime write path (PHASE-4 spec §3) — this service consumes state-change events, it never mutates records |
| **UI Builder Service** | Component catalog, builder sessions, preview/scaffolding (page/layout definitions persist as versioned metadata in the Metadata Service); no separate module in v1 — extracted on demand only (ARCHITECTURE.md §2.8, PHASE-2 spec §8) |
| **Reporting Service** | Report definitions, execution against Data Runtime query API, chart data shaping, scheduled delivery |
| **File Service** | Attachments, images, presigned storage (S3/MinIO), virus-scan hook |
| **Notification Service** | Email/SMS/push/websocket fan-out, templates, user preferences (v1: platform inbox + email via SMTP with built-in default templates — PHASE-4 spec §8; SMS/push/websocket stay deferred with demand) |
| **Integration Service** | Connectors (REST first — the SOAP/DB/file types join the same frame on demand, §5 Phase 6), webhook dispatch, retry/DLQ, mapping engine; tenant-facing bus topics post-1.0 (PHASE-6 §1) |
| **Scheduler Service** | Cron registry & orchestration of scheduled jobs (job definitions are versioned app metadata activated on publish — ARCHITECTURE.md §2.8), distributed locks |
| **Audit Service** | Append-only event log (Kafka → store), who/what/when, field diffs |

Shared libraries (no separate service, per ARCHITECTURE.md §7): `common-core`, `metadata-model`, `security-context`, `event-schemas`, `test-support`, `expression-dsl` (JVM parser/evaluator for the shared expression language — PHASE-2 spec §7).

---

## 4. Technology Stack

| Concern | Choice |
|---------|--------|
| Language/Runtime | Java 21 LTS, Spring Boot 4.1.x (Spring Framework 7.0.x), Spring Cloud 2025.1.x |
| Auth | Keycloak (OIDC) — offloads user mgmt, MFA, federation |
| Databases | PostgreSQL 16 (metadata + tenant data; JSONB hybrid) |
| Cache | Redis (metadata cache, sequences, distributed locks) |
| Messaging | Kafka (domain events, audit, webhooks, cache invalidation) |
| Workflow | Flowable 7 (embedded in Workflow Service) |
| Scripting | GraalVM JS sandbox — CPU/memory caps, no host I/O by default (escape hatch per ADR-008) |
| Resilience | Resilience4j (circuit breakers, retries, bulkheads) |
| API docs | OpenAPI 3 generated per service, aggregated at gateway |
| Frontend | React 19.2.x + TypeScript; metadata-driven renderer (layered per [ADR-009](./docs/adr/ADR-009-declarative-ui.md)); builder on React-Flow/agnostic-dnd |
| Observability | Micrometer + Prometheus + Grafana, OpenTelemetry traces (Grafana Tempo backend — decided PHASE-0 §12 Q2, lands with Phase 3), Loki logs (same expansion, PHASE-3 §9) |
| Build/CI | Maven multi-module, GitHub Actions, Testcontainers 2 |
| Containers | Podman + Buildah (rootless, daemonless), OCI images |
| Orchestration | Kubernetes + Helm; local clusters via Kind-on-Podman; Skaffold (podman runner) for inner-loop dev |
| Testing | JUnit 6, Testcontainers 2 (Podman socket), ArchUnit (module rules), Playwright (E2E) |

---

## 5. Delivery Roadmap

### Phase 0 — Foundations (2–3 weeks)
- Monorepo scaffolding, Maven multi-module, shared libs — detailed spec: [docs/specs/PHASE-0-FOUNDATIONS.md](./docs/specs/PHASE-0-FOUNDATIONS.md); stack decision: [ADR-007](./docs/adr/ADR-007-adopt-spring-boot-4.md)
- Local dev: Podman compose as the primary path; Kind-on-Podman cluster + Helm as a stretch goal (decided: slips to Phase 1 — PHASE-0 §12 Q3, resolved); CI on GitHub Actions `ubuntu-latest` first, `quay.io/podman/stable` runners wired when Testcontainers jobs land in Phase 1
- Keycloak, Postgres, Redis, Kafka provisioning; gateway skeleton; observability baseline
- **Exit:** "hello world" service behind gateway with JWT auth + traces + dashboards

### Phase 1 — Metadata Core & Data Runtime (4–6 weeks) ← *highest risk, do first*
- Metadata model: entity/field/relationship definition schemas
- K8s dev environment (Kind-on-Podman + Helm) if not landed as the Phase 0 stretch goal
- Data Runtime: generic record CRUD + query DSL + permission checks
- Storage strategy implementation (see ARCHITECTURE.md §4)
- REST API per entity — auto-exposed by the generic runtime, no per-entity codegen (ARCHITECTURE.md §2.4); sequences; soft delete; optimistic locking
- Detailed spec: [docs/specs/PHASE-1-METADATA-CORE.md](./docs/specs/PHASE-1-METADATA-CORE.md) — it pins the metadata cache-invalidation transport used until the Kafka event spine lands in Phase 3 (Redis pub/sub carrying the future Kafka envelope — ARCHITECTURE.md §2.3, spec §4)
- **Exit:** create entity via API → CRUD records via generic API with field validations (required/type/uniqueness) enforced

### Phase 2 — Builder UI & Security (4–6 weeks)
- Entity builder UI; form/list page auto-generation; basic customization — detailed spec: [docs/specs/PHASE-2-UI-BUILDER.md](./docs/specs/PHASE-2-UI-BUILDER.md); UI layering decision: [ADR-009](./docs/adr/ADR-009-declarative-ui.md)
- Expression DSL v1 (ADR-008 #3): TS evaluator + JVM reference engine; expressions compile-checked at save/publish via the Metadata Service; cross-engine conformance suite from day one (PHASE-2 spec §7)
- RBAC: roles, object permissions, field-level security
- Tenant onboarding flow
- **Exit:** build a 3-entity app (e.g., customers/orders/lines) purely via UI

### Phase 3 — Business Logic Engine (4–5 weeks)
- Declarative-first per [ADR-008](./docs/adr/ADR-008-declarative-first-logic.md): flow IR + closed primitive set (setField, createRecord, updateRecord, publishEvent, callConnector, branch, iterate, requestApproval, transitionState); scripts demoted to escape hatch, script-ratio tracked (primitives backed by later-phase services — `requestApproval`/`transitionState` → Workflow in Phase 4, `callConnector` → Integration in Phase 6 — are fixed in the v1 grammar and activate as those services land)
- Expression defaults, validation rules (extending Phase 1's field constraints), formula fields, roll-up summaries
- Event hooks: flow-IR step graphs built from the primitive set (before/after save, on delete — v1 hooks run on the write path only, ARCHITECTURE.md §2.4; query-path hooks are deferred until a concrete need); sandboxed scripts only where primitives cannot express the logic
- Kafka domain events emitted from Data Runtime
- Builder test harness v1 per [ADR-010](./docs/adr/ADR-010-builder-test-harness.md): suites (fixtures → steps → assertions) over validations, formula/roll-up fields, and hook outcomes, run against a scratch tenant through the single write path — the concrete installment of ADR-008's "generated tests"
- Detailed spec: [docs/specs/PHASE-3-BUSINESS-LOGIC.md](./docs/specs/PHASE-3-BUSINESS-LOGIC.md) — it pins the two landings the roadmap left implicit (Audit Service v1 rides the spine, the durable trail PHASE-2 §9 promised; Script Engine v0 arrives demand-sized at phase tail per ADR-008's after-the-flow-engine sequencing) plus ADR-010's deferred details (TestSuiteDefinition encoding, frozen per-run test clock)
- **Exit:** order totals computed, inventory reserved via hook, no code — verified by a builder-authored suite

### Phase 4 — Workflow & Approvals (4–5 weeks)
- Flowable integration; state-machine designer; approval chains (parallel `any`/unanimous-`all` modes + delegation; sequential chains arrive later as a versioned mode — PHASE-4 spec §1)
- Test-harness vocabulary grows with the Workflow Service: `requestApproval`/`transitionState` assertions (ADR-010)
- Human task inbox; email notifications; timers, escalation, SLAs (P4)
- Scheduler Service (cron registry + distributed locks — ARCHITECTURE.md §2.8) lands here; scheduled jobs (this phase) and Phase 5's scheduled report delivery build on it
- Detailed spec (spec-driven): [docs/specs/PHASE-4-WORKFLOW-APPROVALS.md](./docs/specs/PHASE-4-WORKFLOW-APPROVALS.md) — pins SLA semantics, segregation-of-duties enforcement (fail closed), state machines as write-path-enforced metadata (no engine bypass), durable flow suspension for `requestApproval`, Scheduler/Notification v1 with the job-definitions-vs-registry split, and the record-sharing remainder of PHASE-2 §9; acceptance criteria are ADR-010 suites wherever expressible
- **Exit:** purchase order requires manager approval above threshold, with escalation

### Phase 5 — Reporting & Dashboards (3–4 weeks)
- Report builder (filters, groups, aggregates, charts), dashboard composer
- Scheduled report delivery; CSV/XLSX export (direct downloads in Phase 5; async large-export streaming via the File Service — ARCHITECTURE.md §2.7 — activates when it lands in Phase 6); drill-down
- Detailed spec (spec-driven): [docs/specs/PHASE-5-REPORTING.md](./docs/specs/PHASE-5-REPORTING.md) — reports compile to the query DSL over promoted fields (actor-scoped: sharing-rule row filters apply; the ARCHITECTURE.md §9 "materialized path" = projection columns), dashboards ship as versioned catalog components (PHASE-2 Q4's deferral), sync export capped at 10k rows with the async handoff designed for Phase 6, and the Scheduler's dormant `report` target activates
- **Exit:** A/R aging report + executive dashboard

### Phase 6 — Integration Layer (3–4 weeks)
- Webhook dispatch (signed, retried); inbound webhook endpoint per entity
- REST connector framework + mapping engine — REST connectors first; the SOAP/DB/file connector types (§3) join the same frame only when dogfood demand appears (§6 scope rule); bulk import/export (async, resumable)
- File Service: attachments + presigned uploads (S3/MinIO) — required by bulk import/export; unblocks the Phase 2 `FileUpload` stub before ERP dogfood
- Detailed spec (spec-driven): [docs/specs/PHASE-6-INTEGRATION.md](./docs/specs/PHASE-6-INTEGRATION.md) — one HMAC-SHA256+timestamp scheme in both directions (inbound webhooks ride the gateway's only anonymous route), `callConnector` activates with a 10 s timeout under the PHASE-3 failure policy, credentials live only as references (AES-GCM at rest), imports checkpoint per row for exactly-once resume, and the file field type gains its upload path
- **Exit:** sync bank feed or Stripe transactions via connector into Payments entity

### Phase 7 — ERP Dogfood (6–8 weeks) ← *the proving ground*
Build on the platform itself:
- GL: chart of accounts, journal engine (must be append-only; may require *platform enhancements*: posting/immutability primitives, period locking — log every gap encountered)
- AR/AP: invoices, credit notes, payments, dunning
- Inventory: items, receipts, issues, weighted-average costing, stock ledger
- Period close checklist driven by workflows
- Detailed spec (spec-driven): [docs/specs/PHASE-7-ERP-DOGFOOD.md](./docs/specs/PHASE-7-ERP-DOGFOOD.md) — binding dogfood rules (zero app code, ≤ 20% script ratio, gap log before any workaround), pinned multi-currency scope, and the two anticipated platform harvests (`freezeOnTerminal` posting immutability, `PeriodLock`) landing as versioned features once the gaps confirm; the reconciliation suite is the acceptance contract
- **Exit:** book invoice → auto journal → post → financial reports reconcile. Every missing platform capability becomes a prioritized backlog item.

### Phase 8 — App Lifecycle & Hardening (4–6 weeks)
- App packaging/versioning, change-set promotion dev→staging→prod, rollback — promotion gated by recorded green suite runs, suite results shown in change-set review, headless runs for CI (ADR-010)
- Templates & marketplace concept; performance & load testing (target: p95 < 300 ms list queries at 1M rows/tenant)
- i18n/localization editor for translation-ready metadata (deferred from Phase 2 — PHASE-2 spec Q3)
- Security review, pen test, DR/backup strategy
- Detailed spec (spec-driven): [docs/specs/PHASE-8-LIFECYCLE.md](./docs/specs/PHASE-8-LIFECYCLE.md) — the scratch-tenant mechanism grows into dev/staging/prod environments (one provisioning path, per ADR-010), promotion-gate mechanics pinned (green run against exactly V, visible audited overrides, compatibility-scoped rollback), headless API + CI wiring, templates-as-artifacts with a catalog-only marketplace, the i18n editor with its fallback chain, and the pinned pen-test/DR scope

> **Total: ~8–10 months with a small senior team** (see §7). Phases 1–3 are the make-or-break core. Phases 2–6 can partially overlap once the core stabilizes.

---

## 6. Key Risks & Mitigations

| Risk | Mitigation |
|------|-----------|
| Metadata storage becomes unmaintainable | Decide DDL-vs-JSONB explicitly (ARCHITECTURE.md §4); abstract behind storage SPI so strategy can evolve |
| Generic runtime too slow for ERP lists | Query DSL → tuned SQL with generated columns + indexes; load-test in Phase 1, not Phase 7 |
| User scripts crash or hang the platform | GraalVM isolates with CPU/memory caps, no I/O by default, kill-switch timeouts |
| Workflow vs script event semantics confusing | Single event spine (Kafka) with documented delivery semantics (at-least-once, idempotency keys) |
| Scope explosion ("build everything") | ERP dogfood is the acceptance test; every feature must trace to it |
| Microservice sprawl slows early delivery | Start with gateway + Metadata + Data Runtime (Identity is deployed Keycloak, not built — §3); extract other services only when boundaries prove stable (ADR-008 already sequences Script Engine after the flow engine) |

---

## 7. Team & Ways of Working

- **Core team:** 4–6 engineers (2 backend/platform, 1 data, 2 frontend, 1 QA/SRE) + product owner
- Monorepo, trunk-based development, PR previews via Skaffold
- Definition of Done includes: tests, OpenAPI updated, dashboards updated, runbook entry
- Every phase ends with a demo building a real scenario on the platform

## 8. Immediate Next Steps

1. Approve stack & phase plan; stand up Phase 0 repo skeleton
2. Run the storage spike: hybrid JSONB + projections (ARCHITECTURE.md §4) against a 1M-row dataset — 3-day timebox; confirm or adjust, and record the final call in ADR-001
3. Review the Phase 1 spec ([docs/specs/PHASE-1-METADATA-CORE.md](./docs/specs/PHASE-1-METADATA-CORE.md)); its T1 (Metadata JSON Schema v0 — app/entity/field/relationship/page) starts immediately — only the storage-dependent tasks (T5+) wait for the spike (item 2) to close ADR-001 (PHASE-1 §2)
4. Stand up Keycloak + Gateway + one service end-to-end with CI
5. Recruit/select team; set up project tracker with the phase backlog
6. All phase-spec open questions are resolved (2026-08-21) — each spec's Resolved Questions section records the decision adopted from its written recommendation; ADR-002–ADR-006 now join ADR-007–ADR-010 as accepted with files, leaving ADR-001's spike closure (item 2) as the sole remaining decision gate
