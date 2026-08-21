# ADR-005: Monorepo, Maven multi-module, Java 21

- **Status:** Accepted (ahead of implementation — docs-only; confirmed by the Phase 0 repo skeleton, PHASE-0 §3–§4/T1)
- **Date:** 2026-08-21
- **Affects:** PLAN.md §4 (Build/CI, Testing rows); ARCHITECTURE.md §6–§7; PHASE-0 §3–§4; resolves PHASE-0 §12 Q4 (Java 25 timing)
- **Related:** ADR-007 holds the Spring Boot/Cloud version pins; this ADR holds the repo/build/toolchain decision

## Context

Thirteen-plus services (PLAN.md §3's landscape, extraction only on demand), shared
libs, two frontends, and deploy assets need one
reviewable, atomic-change home. Alternatives: per-service repos (independent
versioning, but cross-service contract changes split across PRs and the shared-lib
bootstrap problem) vs a monorepo (atomic cross-cutting changes, one CI). The team is
4–6 engineers, trunk-based — repo sprawl buys nothing at that size.

## Decision

1. **Single monorepo** (`novaforge/`): `platform/libs/*` shared libs,
   `services/*` Boot services, `frontend/*` pnpm workspace (PHASE-2 §2),
   `deploy/compose` + Helm, `docs/{adr,specs}`. Trunk-based development, PR
   previews via Skaffold.
2. **Maven multi-module**: root parent `com.novaforge:novaforge-parent`
   (`spring-boot-starter-parent` underneath, versions per ADR-007), aggregators
   `platform` + `services`; internal libs declared once in
   `dependencyManagement`; starters with versions are a build failure (PHASE-0 §4).
3. **Java 21 (Temurin) LTS** is the build/runtime toolchain. Java 25 is adopted
   only after a CI multi-JDK matrix exists (PHASE-0 §12 Q4's decision — not on the
   critical path; ADR-007 #2's revisit point).
4. **Modules are created on demand**, not pre-created: empty modules rot
   (PHASE-0 §3/§5.4 records charters instead). ArchUnit guards module boundaries
   from Phase 1.

## Consequences

- One `mvn verify` builds the world; CI stays simple (PHASE-0 §9); Testcontainers
  jobs share the Podman-socket runner label from Phase 1.
- Cross-service contract changes (e.g. `event-schemas`) are single PRs — the
  at-least-once spine contracts (PLAN.md §6) stay coherent.
- Repo-level operations (mass refactors, version bumps) are cheap; the cost is CI
  time, mitigated by incremental builds and per-module test selection.
- Frontend tooling is pnpm-workspace-based, not Maven — one repo, two build
  systems, bridged in CI.
