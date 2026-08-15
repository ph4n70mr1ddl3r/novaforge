# Phase 0 — Foundations: Implementation Specification

> Detailed, task-level spec for standing up the NovaForge monorepo skeleton on the
> latest Spring generation. Product context: [PLAN.md](../../PLAN.md) §5 Phase 0.
> Stack decision & verified Boot-4 gotchas: [ADR-007](../adr/ADR-007-adopt-spring-boot-4.md).
>
| | |
|---|---|
| Status | Draft for review |
| Date | 2026-08-15 |
| Owner | Platform team |
| Estimate | 2–3 weeks (per PLAN.md §5) |

## 1. Objective

Deliver the Phase 0 exit criteria: *a "hello world" service behind the gateway with JWT
auth, traces, and dashboards* — plus the repo/build/CI foundations every later phase
builds on. No product features. No database schema work (that is Phase 1).

In scope: monorepo + parent build, shared-lib skeleton, gateway + one backing service,
local infrastructure (Podman compose), JWT auth at the edge, observability baseline, CI.

Out of scope: metadata model, Data Runtime, React frontends, Helm/K8s (Kind cluster is a
stretch goal — Open Question Q3 recommends slipping it to Phase 1).

## 2. Locked Version Matrix

All versions verified against Maven Central on 2026-08-15 (see ADR-007 for evidence).

| Component | Version | Note |
|---|---|---|
| Java | 21 (Temurin) | Local: 21.0.12; LTS per ADR-005 |
| Maven | 3.9.x + wrapper | Wrapper added so contributors need no local Maven |
| Spring Boot | **4.1.0** | Via `spring-boot-starter-parent` |
| Spring Framework | **7.0.8** | Pinned by the Boot BOM — do not override |
| Spring Cloud | **2025.1.2** | Imported BOM in root POM |
| Jackson | 3.1.4 (default) | `tools.jackson` namespace |
| JUnit / Testcontainers | 6.0.3 / 2.0.5 | New coordinates, see §10 |
| Keycloak | 26.x (container) | Pin exact tag at T5 |
| PostgreSQL / Redis / Kafka | 16 / 7.x / 4.x (containers) | Pin exact tags at T5 |

Patch upgrades (4.1.x, 2025.1.x) are allowed any time; minors are decision points.

## 3. Target Repository Structure (end of Phase 0)

```
spring_erp/
├── PLAN.md, ARCHITECTURE.md
├── pom.xml                        # root: parent + aggregator (§4.1)
├── platform/
│   ├── pom.xml                    # aggregator for shared libs
│   └── libs/
│       └── common-core/           # §5 — context + error model
├── services/
│   ├── pom.xml                    # aggregator
│   ├── gateway/                   # §6.1
│   └── metadata-service/          # §6.2 (skeleton only)
├── deploy/
│   └── compose/
│       ├── novaforge.yaml         # §7 local infra (podman compose)
│       └── observability/         # prometheus scrape config + grafana dashboard (T8)
├── .github/workflows/build.yaml   # §9 CI
└── docs/{adr,specs}/
```

`platform/libs/metadata-model`, `security-context`, `event-schemas`, `test-support`
(ARCHITECTURE.md §7) are chartered but intentionally **not created** until Phase 1 —
empty modules rot. Charters are recorded in §5.4 so intent is not lost.

## 4. Build Specification

### 4.1 Root parent POM (verified in spike)

- Parent: `org.springframework.boot:spring-boot-starter-parent:4.1.0`.
- Coordinates: `com.novaforge:novaforge-parent:0.1.0-SNAPSHOT`, packaging `pom`.
- Properties: `java.version=21`, `spring-cloud.version=2025.1.2`.
- `dependencyManagement`: import `spring-cloud-dependencies:${spring-cloud.version}`;
  declare each internal lib (`novaforge-common-core`, …) at `${project.version}` so
  service POMs never carry versions.
- Modules: `platform`, `services`.

### 4.2 Module conventions

| Module kind | Parent | Packaging | Boot plugin? | Notes |
|---|---|---|---|---|
| Aggregator (`platform`, `services`) | root | `pom` | no | Only `<modules>` |
| Shared lib (`platform/libs/*`) | root (`relativePath`) | `jar` | no | Plain jar; test deps allowed |
| Service (`services/*`) | root (`relativePath`) | `jar` | **yes** | Executable fat jar |

Rules:
1. Starters with versions are a build failure — everything resolves via parent/BOM.
2. Services declare the internal libs they use by groupId:artifactId only.
3. One `spring-boot-maven-plugin` declaration per service, no version, no custom
   goals beyond defaults (+ `build-info` in T8 for actuator version endpoints).
4. Adding a service = new dir under `services/`, register in `services/pom.xml`,
   follow §6 templates. No root POM edits needed unless a new internal lib appears.

### 4.3 Verified Boot-4 dependency names (use exactly these)

| Purpose | Artifact |
|---|---|
| REST/servlet web | `spring-boot-starter-webmvc` (not `…-web`) |
| Validation | `spring-boot-starter-validation` |
| Actuator | `spring-boot-starter-actuator` |
| JWT resource server | `spring-boot-starter-security-oauth2-resource-server` |
| Gateway (servlet) | `spring-cloud-starter-gateway-server-webmvc` |
| Service tests | `spring-boot-starter-webmvc-test` (supersedes `starter-test`) |
| Lib unit tests | `spring-boot-starter-test` |

## 5. Shared Library: `novaforge-common-core`

### 5.1 Charter

Cross-service vocabulary with **zero Spring web dependencies** (keeps it usable from
CLI/tools later): request context, error model, small value types.

### 5.2 Contents at end of Phase 0

1. `com.novaforge.common.context.TenantContext` — ThreadLocal holder for
   `record Context(String tenantId, String actorId)`; `set/current/clear`; `current()`
   returns `Optional`. (Implemented in spike — reuse.)
2. `com.novaforge.common.error.ErrorCode` — sealed interface + enum
   `PlatformErrorCode(String code, int httpStatus)`; `code` is a stable numeric
   string. Seed set: `VALIDATION_FAILED("4000",400)`, `NOT_FOUND("4004",404)`,
   `CONFLICT_VERSION("4090",409)`, `FORBIDDEN("4003",403)`,
   `TENANT_MISSING("4001",400)`, `INTERNAL("5000",500)`.
3. `com.novaforge.common.error.ProblemErrors` — record carrying
   `List<FieldError>`/`List<GlobalError>` for RFC 7807 extension fields; the
   `@RestControllerAdvice` that renders it lives per-service (Phase 1), not in the lib.

### 5.3 Tests

Plain JUnit 6 unit tests (no Spring context): TenantContext thread semantics,
ErrorCode uniqueness of `code`. AC: `mvn -pl platform/libs/common-core verify` green.

### 5.4 Deferred-lib charters (record only)

- `metadata-model` (Phase 1): definition POJOs + JSON Schema for entity/field/relationship/page.
- `security-context` (Phase 1): tenant/actor propagation helpers on top of TenantContext
  (reactor context, Kafka headers, mock-test fixtures).
- `event-schemas` (Phase 3): Kafka domain-event contracts.
- `test-support` (Phase 1): Testcontainers bases (Postgres + RLS fixtures).

## 6. Service Specifications

### 6.1 `novaforge-gateway` (port 8080)

Responsibilities in Phase 0: route `/api/v1/metadata/**` → metadata-service (versioning
rule: ARCHITECTURE.md §6); JWT validation; pass-through tenant header (informational —
services derive tenant from the token claim themselves, see T7); health.

Dependencies: `spring-cloud-starter-gateway-server-webmvc`,
`spring-boot-starter-security-oauth2-resource-server`,
`spring-boot-starter-actuator`, test: `spring-boot-starter-webmvc-test`.

Routes (YAML only — ADR-007 #5; verified syntax for Gateway 5.0.x):

```yaml
spring:
  cloud:
    gateway:
      server:
        webmvc:
          routes:
            - id: metadata-service
              uri: ${novaforge.upstreams.metadata-service}
              predicates:
                - Path=/api/v1/metadata/**
```

The `novaforge.upstreams.metadata-service` property defaults to
`http://localhost:8081` in the local profile.

Security config: stateless resource server, issuer-uri from
`novaforge.auth.issuer-uri` property (defaults to local Keycloak
`http://localhost:8082/realms/novaforge`); actuator endpoints permitted anonymously;
everything else requires scope `novaforge.api`. On validation failure return RFC 7807
`application/problem+json`.

Out of scope (later phases): rate limiting (Redis), CORS, request logging, route
discovery (K8s service name URI).

### 6.2 `novaforge-metadata-service` (port 8081)

Phase 0 skeleton proving the stack end-to-end; the real definition APIs arrive in
later phases.

Endpoints:
- `GET /api/v1/metadata/ping` → `{"service":"metadata-service","status":"ok","springFrameworkVersion":"7.0.8"}`
  (version assertions in tests make silent framework downgrades visible).
- `GET /actuator/health` (liveness/readiness probes enabled).

Also resource-server secured (same issuer property and the same `novaforge.api`
scope requirement as the gateway) — services must not trust the
gateway alone (defense in depth, ARCHITECTURE.md §5).

Dependencies: `novaforge-common-core`, `spring-boot-starter-webmvc`,
`spring-boot-starter-validation`, `spring-boot-starter-security-oauth2-resource-server`,
`spring-boot-starter-actuator`, test: `spring-boot-starter-webmvc-test`.

### 6.3 Test specifications (both services)

- Context loads; `/actuator/health` → 200 `{"status":"UP"}`.
- Ping: assert service/status and **exact framework version `7.0.8`**.
- Gateway route proof: request `/api/v1/metadata/ping` with no downstream running →
  ServletException caused by `ResourceAccessException` (connection refused proves the
  route matched and proxy was attempted, not a 404).
- Gateway JWT: unsigned/invalid token → 401 problem+json; valid token (issued from
  compose Keycloak in integration profile) → proxied 200.
- Imports note: `@AutoConfigureMockMvc` = `org.springframework.boot.webmvc.test.autoconfigure`.

## 7. Local Infrastructure (Podman compose)

`deploy/compose/novaforge.yaml` run with `podman compose up -d` (Podman 4.9 present):

| Service | Image (pin tag at T5) | Port | Purpose |
|---|---|---|---|
| keycloak | `quay.io/keycloak/keycloak:26.x` | 8082 | realm `novaforge`, `start-dev`, pre-configured realm export |
| postgres | `docker.io/library/postgres:16` | 5432 | shared instance — Keycloak persistence in Phase 0; per-service databases arrive in Phase 1 |
| redis | `docker.io/library/redis:7` | 6379 | cache/locks (unused until Phase 1) |
| kafka | `apache/kafka:4.x` (KRaft, single node) | 9092 | event spine (unused until Phase 3) |
| prometheus | `prom/prometheus` | 9090 | scrapes actuator metrics |
| grafana | `grafana/grafana` | 3000 | seeded with a "NovaForge / Phase 0" dashboard |

Requirements: named volumes for keycloak/postgres data; healthchecks on all;
`depends_on: condition: service_healthy`; a mounted Keycloak realm JSON creating realm
`novaforge`, client `novaforge-api`, and a `demo` user. Compose must work rootless.
Prometheus scrape config and Grafana provisioning/dashboard JSON live in
`deploy/compose/observability/` (§3; built in T8) and are bind-mounted into their
containers.

## 8. Observability Baseline

- Every service: actuator + `micrometer-registry-prometheus` endpoint exposure
  (`health,info,prometheus`); build-info goal enabled.
- Tracing: Micrometer tracing (bridge) with W3C traceparent propagated gateway →
  services; acceptance is the same trace id in both services' logs for one proxied
  request. Phase 0 ships no tracing backend (the §7 stack has none) — OTLP export
  activates when a backend lands (Q2; expected alongside Phase 3 Kafka tracing).
  Full OTel collector deferred (Q2).
- Grafana dashboard v0: one row per service — availability (up), HTTP p95, JVM heap.

## 9. CI (GitHub Actions)

`build.yaml`: on PR + push to main.
1. Job `build` (ubuntu-latest, Temurin 21, Maven cache): `./mvnw -B -ntp verify`.
2. Testcontainers-based jobs deferred to Phase 1, together with wiring the
   `quay.io/podman/stable` Podman-socket runner label (PLAN.md §5); a native-image
   check job is added only if a service adopts native builds (none is chartered in
   any phase).
3. Concurrency cancel on superseded PRs; artifacts: surefire reports on failure.

## 10. Testing Standards (Boot 4 specifics)

- JUnit 6; AssertJ; no JUnit 4 vintage anywhere.
- Web slice tests use `spring-boot-starter-webmvc-test`; remember the relocated
  `@AutoConfigureMockMvc` package (ADR-007 #3).
- Testcontainers 2 coordinates `org.testcontainers:testcontainers-junit-jupiter`;
  Podman socket env (`TESTCONTAINERS_*`) documented in README when first used (Phase 1).
- Rule: a test asserting an exact managed version (e.g. Framework 7.0.8) must fail
  loudly on any dependency drift — intentional upgrades update the assertion.
- ArchUnit module rules (PLAN.md §4) start in Phase 1, when there are real module
  boundaries to guard; until then T3's `dependency:tree` check enforces the one rule
  in effect (no Spring web deps in common-core).

## 11. Task Breakdown

Each task is independently mergeable; tasks T1–T3 unblock everything else.

| # | Task | Content | Acceptance criteria |
|---|---|---|---|
| T1 | Restore spike scaffold | Recreate structure from `spike/boot-4.1-scaffold` (POMs, TenantContext, gateway+metadata skeletons incl. YAML route + tests) | `./mvnw verify` green on Temurin 21 |
| T2 | Maven wrapper + README | `mvn wrapper:wrapper`; README quickstart (prereqs, verify, run compose) | Fresh clone builds with no local Maven |
| T3 | common-core error model | `ErrorCode`, `PlatformErrorCode`, `ProblemErrors` + tests (§5.2) | Lib tests green; no Spring web deps (`mvn dependency:tree` check) |
| T4 | Keycloak realm export | Realm `novaforge`, client `novaforge-api` with client scope `novaforge.api`, user `demo`; mounted into compose | `demo` login via CLI yields JWT carrying scope `novaforge.api` |
| T5 | Compose stack | §7 services, volumes, healthchecks, pinned tags | `podman compose up -d` → all healthy |
| T6 | Gateway JWT + tenant header | Resource-server config, issuer property, problem+json errors, `X-Tenant-Id` derivation from claim | Invalid token → 401; valid → proxied ping 200 carrying tenant header |
| T7 | Service JWT verification | Same resource-server config on metadata-service; tenant derived from the token claim into `TenantContext` via filter (services do not trust the gateway header) | Direct call w/o token → 401; with token → 200 |
| T8 | Observability | Prometheus scrape configs, Grafana dashboard, build-info, log correlation check | Dashboard shows both services; one proxied request yields shared trace id |
| T9 | CI pipeline | §9 build job | PR triggers green run incl. tests |
| T10 | Exit review | Walk PLAN.md §5 Phase 0 exit criteria on compose stack | Demo: browser/curl → gateway → JWT → ping, visible in Grafana |

Dependency order: T1 → T2 → (T3, T4) → T5 → T6 → T7 → T8 → T9; T10 last.

## 12. Open Questions

Q1 gates T4–T5; Q2 gates T8; Q3–Q4 are scheduling calls to close by the end of Phase 0.

- **Q1 — Keycloak realm strategy:** single realm + tenant claim vs realm-per-tenant
  (ARCHITECTURE.md §2.2 flags this as a Phase 0 decision). *Recommendation: single
  realm — simpler at our scale; revisit at true tenant isolation requirements.*
- **Q2 — Tracing backend:** Grafana Tempo (OTLP) in the compose stack now vs full OTel
  collector later — either way Phase 0 verifies tracing via log trace-id correlation
  only (§8). *Recommendation: bridge + log correlation now; choose the backend when
  Kafka tracing (Phase 3) demands header propagation.*
- **Q3 — Kind-on-Podman cluster in Phase 0 or Phase 1:** compose covers the exit
  criteria; cluster work could slip if time-pressed. *Recommendation: slip to Phase 1
  start; keep compose as the lean path.*
- **Q4 — Java 25 toolchain:** adopt now (supported by Boot 4) or after CI has
  multi-JDK matrix. *Recommendation: after; not on the Phase 0 critical path.*
