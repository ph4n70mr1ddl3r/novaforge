# NovaForge — No-Code App Builder Platform

> **Mission:** A Spring-microservices-based, metadata-driven no-code platform capable of hosting real business applications — validated by building a working ERP (GL, AR/AP, Inventory) *on the platform itself*, with zero handwritten application code.

**North-star milestone:** A functional mini-ERP (chart of accounts, journal entries with posting rules, invoices, inventory with costing, period close, approvals, financial reports) built entirely through the platform's builder UI, promoted from dev → staging → prod as a versioned app artifact.

---

## 1. Product Pillars

| # | Pillar | What it delivers |
|---|--------|------------------|
| P1 | **Data Modeling** | Entities, 20+ field types, relationships (lookup, master-detail, many-to-many), validations, uniqueness, indexes, audit trail, soft delete |
| P2 | **UI Builder** | Drag-and-drop pages: forms, lists, dashboards, wizards, tabs; conditional visibility; mobile-responsive layouts |
| P3 | **Business Logic** | Validation rules, formula fields, roll-up summaries, sandboxed server-side scripts, before/after event hooks |
| P4 | **Workflow & Automation** | BPMN processes, document state machines (Draft → Posted), approval chains, scheduled jobs, escalation, SLAs |
| P5 | **Reporting & Analytics** | Report builder (grouping, pivots, charts), dashboards, scheduled exports, drill-down to records |
| P6 | **Security & Tenancy** | Multi-tenancy, RBAC + record-level + field-level permissions, data segregation, audit log, OAuth2/OIDC SSO |
| P7 | **Integration** | Generated REST APIs, webhooks, inbound/outbound connectors, message bus topics, import/export |
| P8 | **App Lifecycle** | Apps as versioned artifacts (JSON), sandboxes, change sets, promotion, rollback, app templates/marketplace |

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
      ├── Business Rules ── Script hooks (beforeSave/afterSave/...), scheduled scripts
      ├── Workflows ── BPMN definitions, state machines, approvals
      ├── Reports & Dashboards
      ├── Permissions ── Roles, profiles, record rules, field security
      ├── Integrations ── Connectors, webhooks, API clients
      └── Settings ── Sequences, currencies, localization, enums
```

- **Control plane** (builder/design-time) vs **data plane** (runtime) separation.
- Every definition is stored as versioned JSON; apps deploy by applying definition versions.
- All runtime data access flows through a single **Data Runtime Service** that enforces metadata (types, permissions, validations) — no service bypasses it.

---

## 3. Microservice Landscape

| Service | Responsibility |
|---------|----------------|
| **API Gateway** (Spring Cloud Gateway) | Routing, auth token relay, rate limiting, CORS |
| **Identity Service** | OIDC (Keycloak), users, tenants, roles, SSO federation |
| **Metadata Service** | CRUD of app definitions: entities, fields, pages, rules; validation of definitions; versioning & change-sets |
| **Data Runtime Service** | Generic record APIs driven by metadata; permission enforcement; query engine (filter/sort/page/aggregate); sequences; audit emission |
| **Script Engine Service** | Sandboxed execution of user scripts/formulas (GraalJS), resource limits, warm pools |
| **Workflow Service** | Flowable (BPMN) runtime, approvals, state machines, timers/tasks |
| **UI Builder Service** | Page/layout persistence, component catalog, preview/scaffolding |
| **Reporting Service** | Report definitions, execution against Data Runtime query API, chart data shaping, scheduled delivery |
| **File Service** | Attachments, images, presigned storage (S3/MinIO), virus-scan hook |
| **Notification Service** | Email/SMS/push/websocket fan-out, templates, user preferences |
| **Integration Service** | Connectors (REST/SOAP/DB/file), webhook dispatch, retry/DLQ, mapping engine |
| **Scheduler Service** | Cron-definitions, job orchestration, distributed locks |
| **Audit Service** | Append-only event log (Kafka → store), who/what/when, field diffs |

Shared libraries (no separate service): `metadata-model`, `security-context`, `kafka-events`, `error-handling`, `query-dsl`.

---

## 4. Technology Stack

| Concern | Choice |
|---------|--------|
| Language/Runtime | Java 21 LTS, Spring Boot 3.x, Spring Cloud 2024.x |
| Auth | Keycloak (OIDC) — offloads user mgmt, MFA, federation |
| Databases | PostgreSQL 16 (metadata + tenant data; JSONB hybrid) |
| Cache | Redis (metadata cache, sequences, distributed locks) |
| Messaging | Kafka (domain events, audit, webhooks, cache invalidation) |
| Workflow | Flowable 7 (embedded in Workflow Service) |
| Scripting | GraalVM JS in sandbox with CPU/memory/IO limits |
| Resilience | Resilience4j (circuit breakers, retries, bulkheads) |
| API docs | OpenAPI 3 generated per service, aggregated at gateway |
| Frontend | React 19 + TypeScript; metadata-driven renderer; builder on React-Flow/agnostic-dnd |
| Observability | Micrometer + Prometheus + Grafana, OpenTelemetry traces, Loki logs |
| Build/CI | Gradle multi-module (or Maven), GitHub Actions, Testcontainers |
| Containers | Podman + Buildah (rootless, daemonless), OCI images |
| Orchestration | Kubernetes + Helm; local clusters via Kind-on-Podman; Skaffold (podman runner) for inner-loop dev |
| Testing | JUnit 5, Testcontainers (Podman socket), ArchUnit (module rules), Playwright (E2E) |

---

## 5. Delivery Roadmap

### Phase 0 — Foundations (2–3 weeks)
- Monorepo scaffolding, Gradle multi-module, shared libs
- K8s dev environment: Kind cluster on Podman (full stack) + Helm; podman-compose for lean single-service runs; CI pipeline on `quay.io/podman/stable` runners
- Keycloak, Postgres, Redis, Kafka provisioning; gateway skeleton; observability baseline
- **Exit:** "hello world" service behind gateway with JWT auth + traces + dashboards

### Phase 1 — Metadata Core & Data Runtime (4–6 weeks) ← *highest risk, do first*
- Metadata model: entity/field/relationship definition schemas
- Data Runtime: generic record CRUD + query DSL + permission checks
- Storage strategy implementation (see ARCHITECTURE.md §4)
- Generated REST API per entity; sequences; soft delete; optimistic locking
- **Exit:** create entity via API → CRUD records via generic API with validations enforced

### Phase 2 — Builder UI & Security (4–6 weeks)
- Entity builder UI; form/list page auto-generation; basic customization
- RBAC: roles, object permissions, field-level security
- Tenant onboarding flow
- **Exit:** build a 3-entity app (e.g., customers/orders/lines) purely via UI

### Phase 3 — Business Logic Engine (4–5 weeks)
- Validation rules, formula fields, roll-up summaries
- Event hooks → sandboxed scripts (before/after save, on delete, on query)
- Kafka domain events emitted from Data Runtime
- **Exit:** order totals computed, inventory reserved via hook, no code

### Phase 4 — Workflow & Approvals (4–5 weeks)
- Flowable integration; state-machine designer; approval chains (parallel/sequential, delegation)
- Human task inbox; email notifications; timers/escalation
- **Exit:** purchase order requires manager approval above threshold, with escalation

### Phase 5 — Reporting & Dashboards (3–4 weeks)
- Report builder (filters, groups, aggregates, charts), dashboard composer
- Scheduled report delivery; CSV/XLSX export; drill-down
- **Exit:** A/R aging report + executive dashboard

### Phase 6 — Integration Layer (3–4 weeks)
- Webhook dispatch (signed, retried); inbound webhook endpoint per entity
- REST connector framework + mapping engine; bulk import/export (async, resumable)
- **Exit:** sync bank feed or Stripe transactions via connector into Payments entity

### Phase 7 — ERP Dogfood (6–8 weeks) ← *the proving ground*
Build on the platform itself:
- GL: chart of accounts, journal engine (must be append-only; may require *platform enhancements*: posting/immutability primitives, period locking — log every gap encountered)
- AR/AP: invoices, credit notes, payments, dunning
- Inventory: items, receipts, issues, weighted-average costing, stock ledger
- Period close checklist driven by workflows
- **Exit:** book invoice → auto journal → post → financial reports reconcile. Every missing platform capability becomes a prioritized backlog item.

### Phase 8 — App Lifecycle & Hardening (4–6 weeks)
- App packaging/versioning, change-set promotion dev→staging→prod, rollback
- Templates & marketplace concept; performance & load testing (target: p95 < 300 ms list queries at 1M rows/tenant)
- Security review, pen test, DR/backup strategy

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
| Microservice sprawl slows early delivery | Start with gateway + 3 core services; extract others only when boundaries prove stable |

---

## 7. Team & Ways of Working

- **Core team:** 4–6 engineers (2 backend/platform, 1 data, 2 frontend, 1 QA/SRE) + product owner
- Monorepo, trunk-based development, PR previews via Skaffold
- Definition of Done includes: tests, OpenAPI updated, dashboards updated, runbook entry
- Every phase ends with a demo building a real scenario on the platform

## 8. Immediate Next Steps

1. Approve stack & phase plan; stand up Phase 0 repo skeleton
2. Prototype the storage strategy spike (JSONB vs DDL) with 1M-row dataset — 3-day timebox, decide on data
3. Draft Metadata JSON Schema v0 (entity/field/relationship/page)
4. Stand up Keycloak + Gateway + one service end-to-end with CI
5. Recruit/select team; set up project tracker with the phase backlog
