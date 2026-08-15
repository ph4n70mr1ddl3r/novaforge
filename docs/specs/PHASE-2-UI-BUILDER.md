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
component SDK (chartered in §6 item 4, built on demand), full record-level sharing rules
(owner/role-hierarchy/criteria rules land Phase 3–4 as needed by ERP flows — §9).

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
Browser apps reach APIs via the gateway; the hosting model is Q5 (§13) — the
recommended same-origin static serving keeps gateway CORS (deferred in PHASE-0
§6.1) out of v1, with a Vite dev proxy covering local development.

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
  defaults instantly; the same output is snapshot-tested in CI (golden files, §11 item 1).
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

Rules: every node = `{type, version?, props, children?, bind?, visibility?, required?,
readonly?}`; `visibility`/`required`/`readonly` carry expression-DSL bindings (§7) that
override the field-metadata defaults (§5 — names match the ARCHITECTURE.md §3 flags);
`props` must validate against the component's JSON Schema at save and publish time; a
node's
`version` pins the catalog component it renders — the builder writes it explicitly on
save, and a missing `version` resolves to the catalog's current stable but is rejected
at publish. Unknown component/version = build error in builder, safe fallback in runtime.
`actions` entries follow the same `{type, props?}` shape from a closed declarative set
(rung 2 of ADR-009's escape-hatch ladder) — no scripts. v1 action set: `save`,
`cancel`, `delete`, `openPage` (`runFlow` from ADR-009's action ladder joins when the
flow engine lands in Phase 3); the set grows only via versioned platform features
(same policy as ADR-008's primitives). String-valued action props may interpolate the
current record with `${path}` templates (`${record.id}` above) — the same `${…}`
convention as ADR-008's `createRecord`/`updateRecord` record templates, resolved when
the action dispatches. Per ADR-009 L2, the *persisted*
artifact stores deltas against the L1 default; the concrete delta encoding is Q2 (§13),
decided at T2 — the example above shows the overlay's logical content, not its storage
format.

## 5. Default Resolver Rules (L1)

Field type → widget mapping (v1):

| Field type (ARCHITECTURE.md §3) | Default widget |
|---|---|
| text / email / phone / url | typed `FieldInput` (input type + client hint) |
| longText / richText | `FieldInput` multiline / `FieldRichText` |
| enum | `FieldSelect` |
| boolean | `FieldSwitch` |
| int / long / decimal / money | `FieldNumber` (locale-aware, money shows currency) |
| date / datetime / time | `FieldDate` (one component; date/datetime/time as prop modes) |
| uuid | typed `FieldInput` (readonly) on form; shown on detail |
| lookup | `FieldLookup` (search-as-you-type via query DSL, min 2 chars) |
| child | `RelatedList` (inline-editable grid, cascade rules honored) |
| m2m | `FieldMultiLookup` |
| json | `FieldJson` code viewer (Phase 2: readonly) |
| file | `FileUpload` stub (File Service lands in Phase 6 — PLAN.md §5; stub disabled gracefully until then) |

List view defaults: display field + next 4 visible-by-role fields, `RecordActions`
column; detail view: sections grouped by field `group` metadata (no group → a single
default section); navigation: entities grouped by `module` metadata (no module → a
default group, mirroring field groups — ARCHITECTURE.md §3). Required/readonly flags
flow from field metadata into form defaults. Role changes re-resolve defaults
(L1 is role-parameterized).

## 6. Component Catalog Contract (L3)

1. Each component ships: implementation (lazy React chunk), **props JSON Schema**,
   data requirements declaration (fields/relationships it reads), and a version.
2. Lifecycle: `draft → stable → deprecated`; pages pin versions; deprecation emits
   migration guidance. Registry is metadata, deployable per app version.
3. v1 catalog (18 components): AppShell, NavList, FormLayout, ListLayout,
   RecordHeader, FieldInput, FieldNumber, FieldSelect, FieldSwitch, FieldDate,
   FieldLookup, FieldMultiLookup, FieldRichText, FieldJson, FileUpload (stub,
   disabled until the File Service lands — Phase 6, PLAN.md §5), RelatedList,
   RecordActions, EmptyState.
4. Custom component SDK (escape hatch): charter only — iframe-sandboxed, catalog
   entry with props schema, versioned like everything else.

## 7. Expression DSL Sharing (with ADR-008)

- One grammar for validations, formulas, flow guards, UI `visibility`/`required`/
  `read-only` bindings (ADR-008 #3 — `read-only` per ADR-009 #3 and the field
  metadata `readonly` flag, ARCHITECTURE.md §3).
- Phase 2 ships expression-DSL v1 as a shared asset: the TS evaluator for renderer
  bindings plus a JVM reference parser/evaluator in a shared platform lib
  (`platform/libs/expression-dsl`, ARCHITECTURE.md §7 — the same engine behind
  the Data Runtime's Phase 3 write-path evaluation), wired into the Metadata Service so
  expressions are compile-checked at save/publish (like props schemas). Server-side
  evaluation of expression semantics in the write path (validation rules, formulas)
  arrives in Phase 3 — until then the write path is enforced by the Phase 1 field
  validations (required/type/uniqueness — PLAN.md §5 Phase 1 exit) plus field-level
  security (§9); client-side expression bindings remain UX sugar (security note below).
- Conformance fixtures run against both engines from day one (T4) to prevent dialect
  drift.
- Security: browser evaluation is UX sugar, never trusted as enforcement.

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
- Saves and publishes go through the Metadata Service definition APIs — page
  definitions are versioned metadata; the UI Builder Service (ARCHITECTURE.md §2.8)
  adds catalog/preview concerns, not a separate persistence path. Phase 2 ships no
  separate `ui-builder-service` module: the catalog deploys as versioned metadata
  (components are lazy static chunks), and preview runs client-side — the service is
  extracted only if builder sessions/scaffolding later need server-side state
  (PLAN.md §6 extract-when-stable rule).

## 9. Security Integration (RBAC + field-level)

- Role editor: platform roles + app-defined roles; object permission matrix
  (role × entity → CRUD flags); field security (visible/read-only/hidden per role).
- Enforcement is **server-side only** (Data Runtime projections strip hidden fields;
  ARCHITECTURE.md §5) — the builder/resolver only *renders* the role-appropriate UI.
- Record-level sharing: Phase 2 default is full visibility under the object CRUD
  matrix; owner/role-hierarchy/criteria rules land Phase 3–4 (§1). Cross-tenant
  isolation is already enforced (RLS + query filters, ARCHITECTURE.md §4–5).
- Admin/builder/user capabilities: `builder` role gates design-time UI routes;
  permission changes emit audit events once the Phase 3 event spine lands
  (ARCHITECTURE.md §5); Phase 2 defines the audit event shapes so nothing is
  re-modeled later.
- Field-level and object permissions are themselves metadata (versioned, promoted);
  user→role *assignments* are tenant data in the platform DB — the authorization home
  per ARCHITECTURE.md §2.2 — read by the Data Runtime at request time and not promoted
  with the app.

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
| T2 | Page model + registry core | TS types for §4, JSON Schema validation, lazy component registry, version pinning, overlay-format decision (Q2, §13) | Invalid props rejected at save; unknown version → fallback + warning; Q2 decision recorded |
| T3 | v1 component catalog | §6 item 3 components with props schemas + Playwright stories | Storybook-style gallery green incl. axe |
| T4 | Expression runtime v1 | Parser/evaluator for pure expressions in TS + JVM reference engine (compile-checks expressions at Metadata-Service save/publish); shared conformance fixtures across both | 100% shared-fixture parity; invalid expression rejected at save/publish |
| T5 | Default resolver (L1) | §5 rules incl. role parameterization | Golden-file suite green |
| T6 | Runtime renderer + shell | Interpreter, TanStack Query data layer against Phase 1 APIs, server-side paging/virtualization | 100k-row fixture list served via server-side paging only; virtualized scrolling responsive in Playwright smoke |
| T7 | Entity builder UI | §8 wizard/grid over Metadata APIs | Create/modify entities incl. relationships without API calls by hand |
| T8 | Page builder (L2 overlays) | Canvas, palette, property panel, preview, undo, optimistic locking | Customize order form per §4 example; 409 rebase prompt works |
| T9 | RBAC + field security | §9 editors + server projection enforcement | Cross-role leakage tests fail closed; permission-change audit event shapes defined (§9; durable audit trail lands with the Phase 3 event spine) |
| T10 | Tenant onboarding + exit demo | §10 flow + scripted golden journey | Phase 2 exit criteria demo'd on compose stack |

Dependency order: T1 → T2 → (T3, T4, T5) → T6 → T7 → T8 → T9 → T10.
Note: T5/T6 can start against Phase 1 APIs as soon as Phase 1 exit criteria pass;
T3 runs parallel throughout.

## 13. Open Questions

Closure points: Q1 at T1 (§2) and Q2 at T2 (§4) as noted above; Q3 before T5 — the
golden files pin label handling — and Q4–Q5 by T6, when the app shell and its
serving model land.

- **Q1 — Form layer:** react-hook-form + own JSON Schema mapping (control, size) vs
  Uniforms (batteries included, opinionated themes). *Recommendation: react-hook-form
  core with a thin schema binder — we already own widget mapping via L1 rules.*
- **Q2 — Overlay format:** custom delta JSON vs RFC 6902 JSON Patch. *Recommendation:
  custom structural deltas (readable diffs in change-set reviews); JSON Patch kept as
  export/interchange format.*
- **Q3 — i18n now or Phase 8:** label translations touch every metadata type.
  *Recommendation: ship metadata fields as translation-ready (`label_i18n` optional)
  but defer editor UI to Phase 8.*
- **Q4 — Nav/dashboard shell scope:** how minimal should the v1 app shell be?
  *Recommendation: keep v1 minimal (nav + list + form + detail); defer dashboard
  composition to Phase 5 (ECharts via catalog components).*
- **Q5 — Frontend hosting:** serve `runtime-ui`/`builder-ui` as static bundles
  behind the gateway (same origin) vs separate frontend hosting/ingress.
  *Recommendation: static bundles routed through the gateway — same origin keeps
  gateway CORS (deferred in PHASE-0 §6.1) out of v1; revisit for custom-component
  iframes (§6 item 4).*
