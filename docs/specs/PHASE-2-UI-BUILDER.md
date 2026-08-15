# Phase 2 — Builder UI & Security: Implementation Specification

> Detailed spec for the metadata-driven UI builder, auto-generated pages, and the
> RBAC/field-security that shapes them. Product context: [PLAN.md](../../PLAN.md) §5 Phase 2.
> UI architecture decision: [ADR-009](../adr/ADR-009-declarative-ui.md); expression/logic
> foundation: [ADR-008](../adr/ADR-008-declarative-first-logic.md).
>
| | |
|---|---|
| Status | Draft for review |
| Date | 2026-08-15 |
| Owner | Platform team |
| Estimate | 4–6 weeks (per PLAN.md §5) |
| Depends on | Phase 1 (Metadata read APIs + Data Runtime record/query APIs) |

## 1. Objective & Exit Criteria

Deliver the Phase 2 exit: *build a 3-entity app (customers/orders/lines) purely via
the builder UI*, including:

1. Entity builder UI (create/modify entities, fields, relationships against Phase 1
   Metadata APIs).
2. Auto-generated form/list/detail pages for every entity with zero page definitions.
3. Basic customization via declarative overlays (reorder, sections, visibility).
4. RBAC: roles, object permissions (CRUD matrix), field-level security.
5. Tenant onboarding flow (create tenant → first admin → first app).

Out of scope: dashboards/charts (Phase 5), script/flow designer (Phase 3), custom
component SDK (chartered in §6.4, built on demand), full record-level sharing rules
(criteria sharing lands Phase 3–4 as needed by ERP flows).

## 2. Frontend Stack (pin exact versions at T1)

| Concern | Choice | Note |
|---|---|---|
| Framework | React **19.2.x** + TypeScript | 19.2.8 current as of 2026-08; no React 20 exists |
| Build | Vite | |
| Renderer state | TanStack Query | server cache keyed by metadata version |
| Lists | TanStack Table + TanStack Virtual | server-side paging/sort/filter via query DSL |
| Forms | **Q1** — react-hook-form + JSON Schema mapping vs Uniforms | decide at T1 (§13) |
| Builder canvas | React-Flow + agnostic-dnd | per PLAN.md §4 |
| Styling/theming | design tokens (W3C DTCG JSON) → CSS variables | single source for light/dark/tenant branding |
| A11y | WCAG 2.2 AA; axe automated checks in CI | builder and generated UI |
| E2E | Playwright | per-component + golden journeys |

Repo placement (per ARCHITECTURE.md §7): `frontend/runtime-ui` (renderer + shell)
and `frontend/builder-ui` (design-time), sharing a `frontend/shared` package
(page-model types, expression runtime, component registry). pnpm workspace.

## 3. UI Architecture Summary (binding: ADR-009)

```
entity/field metadata ──► resolveDefaultPage(entity, role)   [L1, pure]
                                   │ default PageDefinition
                                   ▼
              saved PageDefinition (overlay deltas)          [L2, versioned JSON]
                                   │
                                   ▼
        renderer: recursive interpreter over page JSON        [L3]
        components resolved from versioned, lazy registry
```

- The resolver runs **client-side** (pure TS function) so the builder can preview
  defaults instantly; the same output is snapshot-tested server-side in CI.
- The renderer never branches on entity specifics — only on component types.

## 4. Page Model v0

```jsonc
// PageDefinition (overlay against the generated default)
{
  "id": "pg_order_form", "entity": "Order", "type": "form",
  "base": "auto",                      // L1 default this overlays
  "root": {                            // component tree (L3)
    "type": "FormLayout", "props": { "columns": 2 },
    "children": [
      { "type": "FieldInput", "props": { "field": "reference" }, "bind": "reference" },
      { "type": "FieldSelect", "props": { "field": "status" },
        "visibility": "status != 'POSTED'" },                 // platform expression DSL
      { "type": "RelatedList", "props": { "relationship": "lines", "pageSize": 50 } }
    ]
  },
  "actions": [                                            // declarative action ladder
    { "type": "save" },
    { "type": "openPage", "props": { "page": "pg_order_form", "id": "${record.id}" } }
  ]
}
```

Rules: every node = `{type, version?, props, children?, bind?, visibility?}`; `props`
must validate against the component's JSON Schema at save and publish time; a node's
`version` pins the catalog component it renders — the builder writes it explicitly on
save, and a missing `version` resolves to the catalog's current stable but is rejected
at publish. Unknown component/version = build error in builder, safe fallback in runtime.

## 5. Default Resolver Rules (L1)

Field type → widget mapping (v1):

| Field type (ARCHITECTURE.md §3) | Default widget |
|---|---|
| text / email / phone / url | typed `FieldInput` (input type + client hint) |
| longText / richText | textarea / rich editor |
| enum | `FieldSelect` |
| boolean | `FieldSwitch` |
| int / long / decimal / money | `FieldNumber` (locale-aware, money shows currency) |
| date / datetime / time | `FieldDate` family |
| uuid | readonly on form, shown on detail |
| lookup | `FieldLookup` (search-as-you-type via query DSL, min 2 chars) |
| child | `RelatedList` (inline-editable grid, cascade rules honored) |
| m2m | `FieldMultiLookup` |
| json | code viewer (Phase 2: readonly) |
| file | `FileUpload` stub (File Service arrives later — disabled gracefully) |

List view defaults: display field + next 4 visible-by-role fields, `RecordActions`
column; detail view: sections grouped by field group metadata; navigation: entities
grouped by module in app nav. Required/readonly flags flow from field metadata into
form defaults. Role changes re-resolve defaults (L1 is role-parameterized).

## 6. Component Catalog Contract (L3)

1. Each component ships: implementation (lazy React chunk), **props JSON Schema**,
   data requirements declaration (fields/relationships it reads), and a version.
2. Lifecycle: `draft → stable → deprecated`; pages pin versions; deprecation emits
   migration guidance. Registry is metadata, deployable per app version.
3. v1 catalog (~12 components): AppShell, NavList, FormLayout, ListLayout,
   RecordHeader, FieldInput/Number/Select/Switch/Date/Lookup, RelatedList,
   RecordActions, EmptyState.
4. Custom component SDK (escape hatch): charter only — iframe-sandboxed, catalog
   entry with props schema, versioned like everything else.

## 7. Expression DSL Sharing (with ADR-008)

- One grammar for validations, formulas, flow guards, UI `visibility`/`required`/
  `disabled` bindings. Compiled: server (authoritative, JVM) + browser (sugar).
- Phase 2 needs only the pure-expression subset (no step graphs) but the conformance
  test suite is shared from day one to prevent dialect drift.
- Security: browser evaluation is never trusted; server re-evaluates on write/query.

## 8. Builder UX

- **Entity builder**: guided wizard + table/grid editor over Metadata APIs;
  field-type picker with type-specific constraint forms (generated from field
  metadata schemas — dogfood the catalog approach).
- **Page builder**: React-Flow canvas, agnostic-dnd palette from the catalog,
  property panel auto-generated from the component's props JSON Schema, live preview
  = the real runtime renderer in preview mode (no separate preview implementation).
- Undo/redo: command pattern producing overlay diffs; page definitions are small so
  full-document snapshots with structural sharing are acceptable v1.
- Simultaneous edit protection: optimistic locking on page definitions (409 →
  rebase prompt), consistent with Data Runtime record locking.

## 9. Security Integration (RBAC + field-level)

- Role editor: platform roles + app-defined roles; object permission matrix
  (role × entity → CRUD flags); field security (visible/read-only/hidden per role).
- Enforcement is **server-side only** (Data Runtime projections strip hidden fields;
  ARCHITECTURE.md §5) — the builder/resolver only *renders* the role-appropriate UI.
- Admin/builder/user capabilities: `builder` role gates design-time UI routes;
  permission changes emit audit events (Phase 0 audit spine).
- Field-level and object permissions are themselves metadata (versioned, promoted).

## 10. Tenant Onboarding Flow

Platform-admin driven (no self-serve billing in Phase 2): create tenant → bootstrap
realm/client assignment (Keycloak per the Phase 0 compose stack; realm strategy =
Open Question Q1 in the PHASE-0 spec) → first admin user → create first app → land
in entity builder. Target: < 5 minutes platform-admin time, fully scripted in API
terms for E2E tests.

## 11. Testing Standards

1. **Golden files:** `resolveDefaultPage` snapshots per (entity, role) fixture —
   fail loudly on behavior change; intentional changes update goldens in the same PR.
2. Per-catalog-component Playwright stories incl. keyboard-only runs + axe scans.
3. E2E golden journey = Phase 2 exit criteria scripted: create the 3-entity
   customers/orders/lines app purely through UI, run in CI against the compose stack.
4. Overlay merge unit tests: entity change + overlay → expected resolved page
   (regression suite for the "un-overridden parts follow the entity" rule).

## 12. Task Breakdown

| # | Task | Content | Acceptance criteria |
|---|---|---|---|
| T1 | FE workspace + stack pin | pnpm workspace, Vite, React 19.2.x, TS strict, CI lint/test, form-library decision (Q1) | Scaffold builds in CI; decision recorded |
| T2 | Page model + registry core | TS types for §4, JSON Schema validation, lazy component registry, version pinning | Invalid props rejected at save; unknown version → fallback + warning |
| T3 | v1 component catalog | §6.3 components with props schemas + Playwright stories | Storybook-style gallery green incl. axe |
| T4 | Expression runtime (subset) | Parser/evaluator for pure expressions in TS; shared conformance fixtures with JVM suite | 100% shared-fixture parity |
| T5 | Default resolver (L1) | §5 rules incl. role parameterization | Golden-file suite green |
| T6 | Runtime renderer + shell | Interpreter, TanStack Query data layer against Phase 1 APIs, server-side paging/virtualization | 100k-row fixture list: p95 render smooth, paging server-side only |
| T7 | Entity builder UI | §8 wizard/grid over Metadata APIs | Create/modify entities incl. relationships without API calls by hand |
| T8 | Page builder (L2 overlays) | Canvas, palette, property panel, preview, undo, optimistic locking | Customize order form per §4 example; 409 rebase prompt works |
| T9 | RBAC + field security | §9 editors + server projection enforcement | Cross-role leakage tests fail closed; audit events emitted |
| T10 | Tenant onboarding + exit demo | §10 flow + scripted golden journey | Phase 2 exit criteria demo'd on compose stack |

Dependency order: T1 → T2 → (T3, T4, T5) → T6 → T7 → T8 → T9 → T10.
Note: T5/T6 can start against Phase 1 APIs as soon as Phase 1 exit criteria pass;
T3 runs parallel throughout.

## 13. Open Questions

- **Q1 — Form layer:** react-hook-form + own JSON Schema mapping (control, size) vs
  Uniforms (batteries included, opinionated themes). *Recommendation: react-hook-form
  core with a thin schema binder — we already own widget mapping via L1 rules.*
- **Q2 — Overlay format:** custom delta JSON vs RFC 6902 JSON Patch. *Recommendation:
  custom structural deltas (readable diffs in change-set reviews); JSON Patch kept as
  export/interchange format.*
- **Q3 — i18n now or Phase 8:** label translations touch every metadata type.
  *Recommendation: ship metadata fields as translation-ready (`label_i18n` optional)
  but defer editor UI to Phase 8.*
- **Q4 — Nav/dashboard shell scope:** keep v1 shell minimal (nav + list + form +
  detail) and defer dashboard composition to Phase 5 (ECharts via catalog components).
