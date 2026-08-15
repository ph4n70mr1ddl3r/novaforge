# Phase 8 — App Lifecycle & Hardening: Implementation Specification

> Complete, implementation-driving spec for app packaging, suite-gated promotion,
> rollback, templates, i18n, and hardening. Product context: [PLAN.md](../../PLAN.md)
> §5 Phase 8. The ADR-010 Phase 8 activations (promotion gating, change-set review
> integration, headless CI runs) land here. The PHASE-4 §1 spec-driven agreement
> applies.
>
> | | |
> |---|---|
> | Status | Draft for review |
> | Date | 2026-08-16 |
> | Owner | Platform team |
> | Estimate | 4–6 weeks (per PLAN.md §5) |
> | Depends on | Phase 3+ (harness, scratch tenants) and all landed services |

## 1. Objective & Exit Criteria

Deliver the Phase 8 exit — **pinned** (PLAN.md §5 lists scope, not an exit): *the
ERP app promotes dev → staging → prod through suite-gated change sets with a
demonstrated rollback; the full-scale load target passes; the i18n editor ships;
the security review and DR drill close.* Every missing capability found here still
becomes a backlog item (the §6 discipline never stops).

Out of scope: marketplace commerce (Q2 — templates import/export ship, buying and
selling do not); multi-region; self-serve tenant billing; environment auto-scaling
tuning.

## 2. Environment Model (the scratch tenant grows up)

Per ADR-010's consequence, the Phase 3 scratch-tenant provisioning **is** the
environment mechanism — one provisioning path, no second system:

| Environment | What it is | Who writes |
|---|---|---|
| `dev` | The builder's draft workspace; unpublished definitions live here | builders |
| `staging` | A promoted published version + its own isolated data | promotion only |
| `prod` | A promoted published version + its own isolated data | promotion only |

- An environment = tenant + pinned app version + data plane provisioning (the
  scratch mechanism, without the per-run wipe). Scratch/test environments remain
  available on demand alongside the three named ones.
- The Metadata Service's async app-ZIP import/export (ARCHITECTURE.md §2.3 —
  deliberately dormant until now) is the promotion artifact format: a versioned ZIP
  of JSON definitions, content-hashed and signed.
- Phase 1's implicit draft-vs-published change set is formalized (§3); nothing
  about the underlying versioning changes.

## 3. Change Sets & Review

- A **change set** = the diff between an environment's published version and the
  draft version being promoted: per-definition add/modify/remove, rendered in the
  builder's review UI (page overlays reuse the PHASE-2 Q2 delta format; flow/state
  machine diffs reuse the graph editors' structural diffs).
- Review surfaces: definition diffs, **attached suite results** for that exact
  version (ADR-010 #4), the script-ratio delta, and the gap-log entries the
  version resolves (Phase 7 continuity).
- Free when the app defines no suites; blocking when it does (opt-in is authoring
  tests — ADR-010 #4 unchanged).

## 4. Promotion Gate (mechanics pinned)

1. **Gate:** promoting version V to an environment requires a recorded **green run
   of all app suites against exactly V** (run artifacts are version-bound —
   PHASE-3 §7 — so the check is mechanical: latest recorded run for V is green).
2. **Order:** dev → staging → prod; each hop is its own gated promotion (prod
   requires the staging run against V, plus explicit platform-admin approval —
   Q1 settles whether anything more is required; recommendation: no timed burn-in
   in v1).
3. **Override:** platform-admin only, reason recorded, audited, and *shown in the
   change-set review forever* — an override is a visible artifact, not a secret.
4. **Rollback:** redeploying a prior version through the same gate machinery.
   **Compatibility rule — pinned:** automatic rollback is offered only when the
   prior version's metadata is storage-compatible with the current one
   (projection/field removals block one-click rollback); incompatible rollbacks
   require admin override with an explicit data-migration acknowledgment. The
   materializer handles version downgrades in the compatible case.

## 5. Headless Runs & CI (ADR-010's third activation)

- Public API: `POST /api/v1/metadata/suites/{id}/runs` (and app-wide) returning a
  run handle; artifacts consumable via API — the builder UI is a client of the same
  API, never a prerequisite.
- CI wiring: the platform repo's pipeline runs the platform's own suites on PRs
  (dogfooding the harness); a per-app promotion pipeline pattern (green run →
  promote) ships as documentation + a GitHub Actions reusable workflow, not as
  hosted CI (the platform does not run customer CI in v1).

## 6. Templates & Marketplace (concept scope)

- **App template** = an exported app ZIP with tenant data stripped and fixture
  bundles attached; import creates a new app in draft. The ERP app itself ships as
  the first template.
- **Marketplace** = a catalog listing of templates in the builder (metadata:
  name, publisher, versions, screenshots) — no commerce, no third-party publishing
  pipeline in v1 (Q2).

## 7. i18n / Localization Editor (the PHASE-2 Q3 deferral lands)

- Metadata is already translation-ready (`label_i18n`); this phase ships the
  editor: a translation workspace per app × locale, side-by-side with the source
  label, missing-translation report, and CSV/JSON import-export for translators.
- **Fallback chain — pinned:** `label_i18n[activeLocale]` → `label` → `apiName`
  (never a blank label). Runtime locale is a user preference; builder UI remains
  English-only v1.
- Localized money/date rendering follows the Phase 2 design tokens + locale
  settings; translations are versioned metadata like everything else (promoted
  with the app, gated by the same suites).

## 8. Hardening

- **Load validation at full scale:** re-run the Phase 1/3 load profiles with *all*
  services live — list p95 < 300 ms @ 1M rows/tenant stays the gate
  (ARCHITECTURE.md §9 / PLAN.md §8); dashboards for every §9 target row green.
- **Security review + pen test (scope pinned):** the authz matrix end-to-end
  (object/field/record), RLS under adversarial tenants, script sandbox escape
  attempts, HMAC webhook endpoints (PHASE-6 §5–6), gateway rate limits on public
  routes, promotion override abuse, secrets store access. Findings triaged with
  the Phase 7 gap-log discipline.
- **DR/backup:** Postgres PITR + nightly snapshots; MinIO bucket versioning +
  cross-store replication for the prod file set; Kafka topic retention sized for
  replay; **a quarterly restore drill runbook** — executed once in this phase as
  the acceptance.
- **Secrets rotation:** the PHASE-6 §9 dual-secret windows exercised for one real
  connector credential.

## 9. Harness & Suites

- No new step vocabulary; headless runs (§5) are the harness feature of this phase.
- The **promotion-gate suite**: an app with a deliberately failing suite cannot
  promote (negative test); override path audited; rollback suite exercises §4.4's
  compatible and incompatible branches.
- i18n suite: missing-translation report correctness; fallback chain behavior.

## 10. Task Breakdown

| # | Task | Content | Acceptance criteria |
|---|---|---|---|
| T1 | Environment provisioning | Named envs from the scratch mechanism; ZIP artifact import/export (§2) | Three envs provisioned; artifact hash+signature verified |
| T2 | Change-set formalization + review UI | Diffs, suite results, ratio/gap attach (§3) | Review renders a real change set end-to-end |
| T3 | Promotion gate + override + audit | §4 mechanics | Negative suite blocks promotion; override audited and visible |
| T4 | Rollback | Compatible/incompatible branches, materializer downgrade (§4.4) | Rollback suite green both branches |
| T5 | Headless API + CI wiring | §5 | PR pipeline runs suites via API only |
| T6 | Templates + catalog | §6 | ERP app exported/imported as a template |
| T7 | i18n editor + fallback | §7 | Translation round-trip; fallback suite green |
| T8 | Load validation at scale | §8 profiles with all services | All §9 targets green, dashboards populated |
| T9 | Security review + pen test | §8 scope | Findings triaged; criticals fixed |
| T10 | DR/backup + rotation drill | §8 | Restore drill executed; rotation exercised |

Dependency order: T1 → (T2, T5) → T3 → T4 → T6; T7, T8, T9, T10 parallel tracks;
exit review last (all green).

## 11. Open Questions (both non-blocking)

- **Q1 — Prod promotion bar:** green staging run + admin approval (v1 pin) vs an
  additional timed burn-in. *Recommendation: no burn-in v1 — suites + approval; add
  burn-in when operating data justifies it.*
- **Q2 — Marketplace scope:** catalog only (v1 pin) vs third-party publishing.
  *Recommendation: catalog only; third-party publishing is a post-1.0 program
  (review pipeline, signing, legal).*
