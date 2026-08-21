# NovaForge

**A metadata-driven, no-code application platform — built on Spring microservices and validated by building a real ERP on the platform itself, with zero handwritten application code.**

> **North-star milestone:** a functional mini-ERP (chart of accounts, journal entries with posting rules, invoices, inventory with costing, period close, approvals, financial reports) built entirely through the platform's builder UI and promoted from dev → staging → prod as a versioned app artifact.

---

## Why NovaForge?

Most no-code tools handle forms and lists well but collapse under ERP-grade requirements: decimal-precise money, immutable auditable postings, approval hierarchies, gapless document numbering, period locking, and 100k+-row list performance. NovaForge is designed so that **if the platform supports these, it supports almost any business app** — and the ERP dogfood build ([Phase 7](docs/specs/PHASE-7-ERP-DOGFOOD.md)) is the acceptance test that keeps scope honest.

## How It Works

- **Everything is metadata.** Entities, fields, relationships, pages, rules, workflows, reports, permissions, and tests are all stored as versioned JSON — apps deploy by applying definition versions.
- **Single write path.** All runtime record access flows through one Data Runtime Service that enforces metadata (types, permissions, validations). No service bypasses it.
- **Declarative first, scripts as escape hatch.** Business logic is expressed as flow-IR step graphs over a closed primitive set; sandboxed scripts are the last resort (see [ADR-008](docs/adr/ADR-008-declarative-first-logic.md)).
- **Design-time / runtime split.** A control plane (builder) and a data plane (runtime), cleanly separated.
- **Multi-tenant by construction**, with RBAC plus record- and field-level security.

## Product Pillars

| # | Pillar | What it delivers |
|---|--------|------------------|
| P1 | Data Modeling | Entities, 20+ field types, relationships, validations, audit trail, soft delete |
| P2 | UI Builder | Drag-and-drop pages: forms, lists, dashboards; metadata-driven renderer |
| P3 | Business Logic | Validation rules, formulas, roll-ups, declarative event hooks |
| P4 | Workflow & Automation | BPMN processes, state machines, approval chains, scheduled jobs, SLAs |
| P5 | Reporting & Analytics | Report builder, dashboards, scheduled exports, drill-down |
| P6 | Security & Tenancy | Multi-tenancy, RBAC + record/field permissions, audit log, OIDC SSO |
| P7 | Integration | Auto-exposed entity REST APIs, webhooks, connectors, import/export |
| P8 | App Lifecycle | Apps as versioned artifacts, sandboxes, test-gated promotion, rollback |

> v1 scope deferrals against this table are pinned in [PLAN.md](PLAN.md) §1.

## Technology Stack

| Concern | Choice |
|---------|--------|
| Language/Runtime | Java 21 LTS, Spring Boot 4.1.x, Spring Cloud 2025.1.x |
| Auth | Keycloak (OIDC) |
| Data | PostgreSQL 16 (JSONB hybrid), Redis, Kafka |
| Workflow | Flowable 7 |
| Scripting | GraalVM JS sandbox (CPU/memory caps, no host I/O by default) |
| Frontend | React 19 + TypeScript, metadata-driven renderer |
| Observability | Micrometer, Prometheus, Grafana, OpenTelemetry, Loki |
| Build/CI | Maven multi-module monorepo, GitHub Actions, Testcontainers 2 |
| Containers | Podman + Buildah; Kubernetes + Helm (Kind-on-Podman for local dev) |

## Architecture at a Glance

```
Browser ──► API Gateway ──┬─► Identity (Keycloak)
                          ├─► Metadata Service (design-time)
                          │       └─ applies versions ─► Data Runtime Service
                          ├─► Data Runtime Service ──► PostgreSQL / Redis
                          │       └─ domain events ─► Kafka ──► Workflow / Audit /
                          │                                           Notification / Integration
                          └─► Reporting, UI Builder, File, Scheduler, Script Engine
```

Full service landscape, data strategy (JSONB hybrid + projections), and security model: [ARCHITECTURE.md](ARCHITECTURE.md).

## Roadmap

| Phase | Focus | Spec |
|-------|-------|------|
| 0 | Foundations (repo, CI, local dev, observability baseline) | [PHASE-0-FOUNDATIONS.md](docs/specs/PHASE-0-FOUNDATIONS.md) |
| 1 | Metadata core & data runtime ← *highest risk, first* | [PHASE-1-METADATA-CORE.md](docs/specs/PHASE-1-METADATA-CORE.md) |
| 2 | Builder UI & security | [PHASE-2-UI-BUILDER.md](docs/specs/PHASE-2-UI-BUILDER.md) |
| 3 | Business logic engine + builder test harness | [PHASE-3-BUSINESS-LOGIC.md](docs/specs/PHASE-3-BUSINESS-LOGIC.md) |
| 4 | Workflow & approvals | [PHASE-4-WORKFLOW-APPROVALS.md](docs/specs/PHASE-4-WORKFLOW-APPROVALS.md) |
| 5 | Reporting & dashboards | [PHASE-5-REPORTING.md](docs/specs/PHASE-5-REPORTING.md) |
| 6 | Integration layer | [PHASE-6-INTEGRATION.md](docs/specs/PHASE-6-INTEGRATION.md) |
| 7 | **ERP dogfood — the proving ground** | [PHASE-7-ERP-DOGFOOD.md](docs/specs/PHASE-7-ERP-DOGFOOD.md) |
| 8 | App lifecycle & hardening | [PHASE-8-LIFECYCLE.md](docs/specs/PHASE-8-LIFECYCLE.md) |

Timeline estimate: ~8–10 months with a small senior team; Phases 1–3 are the make-or-break core.

## Documentation

- [PLAN.md](PLAN.md) — product pillars, core abstractions, service landscape, roadmap, risks
- [ARCHITECTURE.md](ARCHITECTURE.md) — system design, data strategy, security model, hard problems
- [Phase specs](docs/specs/) — detailed, decision-resolved specifications per phase
- [Architecture Decision Records](docs/adr/):
  - ADR-001 storage spike *(pending closure — no file yet)* · [ADR-002](docs/adr/ADR-002-authn-keycloak-authz-platform.md) Keycloak authn / platform authz · [ADR-003](docs/adr/ADR-003-graalvm-script-sandbox.md) GraalVM script sandbox · [ADR-004](docs/adr/ADR-004-flowable-embedded-state-machines.md) Flowable + state machines · [ADR-005](docs/adr/ADR-005-monorepo-maven-java21.md) monorepo, Maven, Java 21 · [ADR-006](docs/adr/ADR-006-shared-schema-rls.md) shared schema + RLS · [ADR-007](docs/adr/ADR-007-adopt-spring-boot-4.md) Spring Boot 4 · [ADR-008](docs/adr/ADR-008-declarative-first-logic.md) declarative-first logic · [ADR-009](docs/adr/ADR-009-declarative-ui.md) declarative UI · [ADR-010](docs/adr/ADR-010-builder-test-harness.md) builder test harness

## Repository Layout

```
novaforge/
├── README.md         # This file — project entry point
├── PLAN.md            # Product & delivery plan
├── ARCHITECTURE.md    # Technical architecture
└── docs/
    ├── specs/         # Phase specifications (PHASE-0 … PHASE-8)
    └── adr/           # Architecture Decision Records (ADR-002 … ADR-010)
```

## Status

**Planning phase.** All phase-spec open questions are resolved; ADR-002–ADR-006 now join ADR-007–ADR-010 as accepted with files. The remaining decision gate is the storage spike (hybrid JSONB + projections against a 1M-row dataset) that will close ADR-001. Next up: stand up the Phase 0 repo skeleton, gateway, Keycloak, and CI. See [PLAN.md §8](PLAN.md) for the immediate next steps.
