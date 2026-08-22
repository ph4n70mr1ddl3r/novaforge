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
| Frontend | React 19.2.x + TypeScript, metadata-driven renderer |
| Observability | Micrometer, Prometheus, Grafana, Tempo (traces, from Phase 3), OpenTelemetry, Loki (logs — same Phase 3 expansion) |
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
                          └─► Workflow, Reporting, File, Integration, Audit,
                              Notification, Scheduler (read-only job status)
```

*Two landscape services are deliberately absent from the gateway line: the Script Engine is internal (hooks invoke it — no gateway route, ARCHITECTURE.md §2.5), and the UI Builder ships as versioned metadata with no separate v1 service module (client-side preview — ARCHITECTURE.md §2.8).*

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
  - [ADR-001](docs/adr/ADR-001-hybrid-jsonb-projections.md) hybrid JSONB + generated projections (spike closed) · [ADR-002](docs/adr/ADR-002-authn-keycloak-authz-platform.md) Keycloak authn / platform authz · [ADR-003](docs/adr/ADR-003-graalvm-script-sandbox.md) GraalVM script sandbox · [ADR-004](docs/adr/ADR-004-flowable-embedded-state-machines.md) Flowable + state machines · [ADR-005](docs/adr/ADR-005-monorepo-maven-java21.md) monorepo, Maven, Java 21 · [ADR-006](docs/adr/ADR-006-shared-schema-rls.md) shared schema + RLS · [ADR-007](docs/adr/ADR-007-adopt-spring-boot-4.md) Spring Boot 4 · [ADR-008](docs/adr/ADR-008-declarative-first-logic.md) declarative-first logic · [ADR-009](docs/adr/ADR-009-declarative-ui.md) declarative UI · [ADR-010](docs/adr/ADR-010-builder-test-harness.md) builder test harness

## Repository Layout

```
novaforge/
├── README.md            # This file — project entry point
├── PLAN.md              # Product & delivery plan
├── ARCHITECTURE.md      # Technical architecture
├── IMPLEMENTATION.md    # Phase-by-phase implementation ledger
├── platform/libs/       # shared libraries (common-core, metadata-model,
│                        #   security-context, expression-dsl*, test-support)
├── services/            # gateway, metadata-service,
│                        #   data-runtime (api/engine/storage/authorization)
├── deploy/              # compose (Keycloak/PG/Redis/Kafka/Prometheus/Grafana),
│                        #   postgres-init, kind/, helm/
├── docs/
│   ├── specs/           # Phase specifications (PHASE-0 … PHASE-8)
│   ├── adr/             # ADR-001 … ADR-010
│   ├── spikes/          # storage spike (ADR-001's evidence)
│   └── loadtests/       # Phase 1 §10 measurements
└── .github/workflows/   # CI (build + Podman-socket integration)
```

*expression-dsl lands with Phase 2 (PHASE-0 §5.4 — empty modules rot).*

## Development

**Prerequisites:** Temurin 21, Podman ≥ 4.9 (rootless), and `podman-compose` (or another
`podman compose` provider). The Maven wrapper (`./mvnw`) needs no local Maven install.

**Testcontainers (rootless Podman):** expose the API socket and point the suite at it —

```bash
podman system service --time=0 unix:///run/user/$UID/podman/podman.sock &
export DOCKER_HOST=unix:///run/user/$UID/podman/podman.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/run/user/$UID/podman/podman.sock
./mvnw -B -ntp verify
```

**Full local stack:**

```bash
# 1. Infrastructure (Keycloak, Postgres, Redis, Kafka, Prometheus, Grafana,
#    + the Phase 3 observability expansion: Tempo, Loki, promtail, kafka-exporter)
cd deploy/compose
PODMAN_COMPOSE_PROVIDER=podman-compose \
NOVAFORGE_POSTGRES_PORT=5433 podman compose -f novaforge.yaml up -d
podman ps                                       # wait until all report (healthy)

# 2. Services (host JVMs). File logging defaults to /tmp/novaforge/logs — promtail
#    tails that dir into Loki; spans ship OTLP-direct to Tempo (localhost:4318).
cd ../..
NOVAFORGE_POSTGRES_PORT=5433 java -jar services/metadata-service/target/novaforge-metadata-service-*.jar &
NOVAFORGE_POSTGRES_PORT=5433 java -jar services/data-runtime/api/target/novaforge-data-runtime-*.jar &
java -jar services/gateway/target/novaforge-gateway-*.jar &

# 3. Token (scope novaforge.api + tenant/actor/platform-roles claims)
TOKEN=$(curl -s -X POST http://localhost:8082/realms/novaforge/protocol/openid-connect/token \
  -d 'grant_type=password&client_id=novaforge-api&username=demo&password=demo' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')
```

Grafana (admin/admin) at http://localhost:3000 ships the seeded "NovaForge" dashboards
(the Phase 0 service baseline plus the Phase 3 board: Kafka consumer lag, hook-duration
histograms, script ratio per app version, suite pass rates, after-hook retry outcomes);
Prometheus scrapes every service's `/actuator/prometheus` plus the kafka-exporter.
Tempo (http://localhost:3200) is the OTLP trace backend — services export direct, no
collector; Loki (http://localhost:3100) holds the shipped service logs with trace-id
deep links into Tempo.

The **Phase 1 exit demo** (create an app via the API, publish, then CRUD records with
validations enforced) runs against that stack — see IMPLEMENTATION.md for the verified
transcript and `docs/loadtests/` for the measured read/list/write targets.

## Status

**Phases 0–1 implemented and verified live; ADR-001 closed with measured numbers.**
The platform core exists: gateway, Metadata Service (definition lifecycle, publish,
versions, export, the Redis `metadata.published` spine), and the Data Runtime (generic
record/query APIs, field validations, sequences, inline master-detail children,
optimistic locking, soft delete, idempotency, hybrid-JSONB storage with ADR-001
generated projections + RLS, the fail-closed role matrix) — 162 tests green, exit demo
and load targets verified on the compose stack. Next: Phase 2 (builder UI, expression
DSL, RBAC editors) per [PLAN.md §5](PLAN.md). Progress ledger: [IMPLEMENTATION.md](IMPLEMENTATION.md).
