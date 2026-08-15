# ADR-007: Adopt Spring Boot 4.1 / Spring Framework 7 at latest

- **Status:** Accepted
- **Date:** 2026-08-15
- **Supersedes:** the "Spring Boot 3.x / Spring Cloud 2024.x" row of the PLAN.md §4 stack table (ADR-005 remains the Maven/Java-21 decision holder)

## Context

The platform is pre-implementation (docs only), so there is no migration cost: picking the
current generation now avoids a major-version jump mid-project. Research performed
2026-08-15, verified against `repo1.maven.org` and `start.spring.io` metadata:

| Component | Version | Verified via |
|---|---|---|
| Spring Boot | **4.1.0** (2026-06-10; current stable, 4.1.1 still SNAPSHOT) | Initializr default, Central |
| Spring Framework | **7.0.8** (2026-06-08, incl. security fixes) — pinned by Boot 4.1.0 BOM | `spring-boot-dependencies-4.1.0.pom` |
| Spring Cloud | **2025.1.2** — the release train Initializr pairs with Boot 4.1 | Initializr BOM metadata, Central |
| Spring Cloud Gateway (servlet) | 5.0.2 (from Cloud 2025.1.2 BOM) | Local build |
| Jackson | 3.1.4 (`tools.jackson`, default) + 2.21.4 managed for interop | Boot BOM |
| JUnit Jupiter | 6.0.3 | Boot BOM |
| Testcontainers | 2.0.5 — new coordinates `org.testcontainers:testcontainers-junit-jupiter` | Boot BOM |
| Jakarta EE baseline | EE 11 (Tomcat 11.0.22, Servlet 6.1) | Boot BOM |
| Also managed | Micrometer 1.17.0, Kafka clients 4.2.1, Spring Data 2026.0.0, Netty 4.2 | Boot BOM |

Support windows: Spring Boot 4.1.x and Spring Framework 7.0.x open-source support run to
**July 2027** — long enough to carry NovaForge through Phases 0–3 without a forced upgrade.

A throwaway spike (`spike/boot-4.1-scaffold` branch) compiled and tested this stack on the
local toolchain (Temurin 21.0.12, Maven 3.9.9); its findings are folded into the
"Breaking changes" section below and the Phase 0 spec.

## Decision

1. Build all services on **`spring-boot-starter-parent:4.1.0`** (which delivers Spring
   Framework 7.0.8) with **Spring Cloud 2025.1.2** for gateway/resilience concerns.
2. Stay on **Java 21** (ADR-005) even though Boot 4 supports up to Java 26: 21 is the
   installed team LTS. Revisit Java 25 once CI toolchains exist (non-blocking).
3. Upgrade cadence: patch releases (4.1.x) are taken opportunistically; the next minor
   (4.2, ~Nov 2026) is a scheduled decision point, not an automatic jump.

## Consequences — verified Boot 4 / Framework 7 changes that affect us

These were hit and confirmed during the spike; they are the ones the team will trip on:

1. **Modular starters.** `spring-boot-starter-web` → **`spring-boot-starter-webmvc`**;
   security starters renamed (e.g. **`spring-boot-starter-security-oauth2-resource-server`**);
   `docker-compose` is now the plain artifact `spring-boot-docker-compose`.
2. **Jackson 3 is the default JSON codec** (`tools.jackson` packages). Jackson 2 remains
   managed for libraries that still need it — isolate any Jackson 2 usage to interop code.
3. **Test starter split.** `spring-boot-starter-test` no longer carries web test
   autoconfiguration. Use **`spring-boot-starter-webmvc-test`**; `@AutoConfigureMockMvc`
   moved to **`org.springframework.boot.webmvc.test.autoconfigure`** (old package is gone).
4. **JUnit 6** (6.0.3) and **Testcontainers 2** with relocated coordinates
   (`org.testcontainers:testcontainers-junit-jupiter`).
5. **Spring Cloud Gateway 5.0.2** splits servlet/reactive artifacts:
   - Servlet stack starter: `spring-cloud-starter-gateway-server-webmvc`.
   - YAML routes live under **`spring.cloud.gateway.server.webmvc.routes[N]`**
     (id/uri/predicates; shortcut form `Path=/x/**` works).
   - The Java DSL changed: `GatewayRouterFunctions` now sits in
     `…gateway.server.mvc.handler` and `HandlerFunctions.http()` takes no URI argument.
     Prefer YAML route definitions; they are stable and reviewed more easily.
6. **Jakarta EE 11 / Tomcat 11** baseline; framework JSpecify null-safety annotations now
   appear on APIs — treat nullness warnings as build-relevant.

## Sources

- [Spring Boot releases / versions](https://spring.io/projects/spring-boot) ·
  [endoflife.date/spring-boot](https://endoflife.date/spring-boot)
- [Spring Framework project page](https://spring.io/projects/spring-framework) ·
  [7.0.8 release blog](https://spring.io/blog/2026/06/08/spring-framework-7-0-8-and-6-2-19-available-now)
- [Spring Boot 4.0 Migration Guide (GitHub wiki)](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- [Introducing Jackson 3 support in Spring](https://spring.io/blog/2025/10/07/introducing-jackson-3-support-in-spring)
- Version pins cross-checked in `spring-boot-dependencies:4.1.0` POM and
  `start.spring.io` metadata (2026-08-15)
