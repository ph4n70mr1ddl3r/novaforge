# Phase 2 — Builder UI & Security: Implementation Specification

> Detailed spec for the metadata-driven UI builder, auto-generated pages, and the
> RBAC/field-security that shapes them. Product context: [PLAN.md](../../PLAN.md) §5 Phase 2.
> UI architecture decision: [ADR-009](../adr/ADR-009-declarative-ui.md); expression/logic
> foundation: [ADR-008](../adr/ADR-008-declarative-first-logic.md).
>
| | |
|---|---|
| Status | Decided (open questions resolved 2026-08-21) |
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
(owner/role-hierarchy/criteria rules land in Phase 4 as needed by ERP flows — §9), and
wizard/tab composite pages plus mobile-specific layout tuning (PLAN.md P2 features —
deferred until the v1 catalog and overlay model stabilize; backlog until ERP dogfood
demands them).

## 2. Frontend Stack (pin exact versions at T1)

| Concern | Choice | Note |
|---|---|---|
| Framework | React **19.2.x** + TypeScript | 19.2.8 current as of 2026-08; no React 20 exists |
| Build | Vite | |
| Renderer state | TanStack Query | server cache keyed by metadata version |
| Lists | TanStack Table + TanStack Virtual | server-side paging/sort/filter via query DSL |
| Forms | react-hook-form + thin JSON Schema binder | §13 Q1, resolved — widget mapping stays ours via L1 rules |
| Builder canvas | React-Flow + agnostic-dnd | per PLAN.md §4 |
| Styling/theming | design tokens (W3C DTCG JSON) → CSS variables | single source for light/dark/tenant branding |
| A11y | WCAG 2.2 AA; axe automated checks in CI | builder and generated UI |
| E2E | Playwright | per-component + golden journeys |

Repo placement (per ARCHITECTURE.md §7): `frontend/runtime-ui` (renderer + shell)
and `frontend/builder-ui` (design-time), sharing a `frontend/shared` package
(page-model types, expression runtime, component registry). pnpm workspace.
Browser apps reach APIs via the gateway; the hosting model is same-origin static
bundles behind the gateway (§13 Q5, resolved) — it keeps gateway CORS (deferred in PHASE-0
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
  At runtime the renderer's metadata (entity/page definitions) comes from the
  Metadata Service's published-definition read (PHASE-1 §4); the builder previews
  against drafts through the draft APIs.
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
      { "type": "FieldSelect", "props": { "field": "status" }, "bind": "status",
        "visibility": "status != 'POSTED'" },                 // platform expression DSL
      { "type": "RelatedList", "props": { "relationship": "lines", "pageSize": 50 },
        "bind": "lines" }
    ]
  },
  "actions": [                                            // declarative action ladder
    { "type": "save" },
    { "type": "openPage", "props": { "page": "pg_order_detail", "id": "${record.id}" } }
  ]
}
```

Rules: every node = `{type, version?, props, children?, bind?, visibility?, required?,
readonly?}`; `bind` is the node's data binding — the record field (or relationship
path, for collection widgets such as `RelatedList`) the component reads/writes;
whether a component takes a binding is declared in its catalog contract (§6 item 1),
and where the bound name repeats in widget config (`props.field`,
`props.relationship`), save/publish validation rejects a mismatch.
`visibility`/`required`/`readonly` carry expression-DSL bindings (§7) that
override the field-metadata defaults (§5 — `required`/`readonly` match the
field-metadata flags, ARCHITECTURE.md §3; `visibility` defaults to role-based
field security);
`props` must validate against the component's JSON Schema at save and publish time; a
node's `version` pins the catalog component it renders — the builder writes it
explicitly on save, and a missing `version` resolves to the catalog's current stable
but is rejected at publish. Unknown component/version = build error in builder, safe
fallback in runtime.
`actions` entries follow the same `{type, props?}` shape from a closed declarative set
(rung 2 of ADR-009's escape-hatch ladder) — no scripts. v1 action set: `save`,
`cancel`, `delete`, `openPage` (`runFlow` from ADR-009's action ladder joins when the
flow engine lands in Phase 3 — PHASE-3-BUSINESS-LOGIC.md §8); the set grows only
via versioned platform features
(same policy as ADR-008's primitives). String-valued action props may interpolate the
current record with `${path}` templates (`${record.id}` above) — the same `${…}`
convention as ADR-008's `createRecord`/`updateRecord` record templates, resolved when
the action dispatches. Per ADR-009 L2, the *persisted*
artifact stores deltas against the L1 default; the concrete delta encoding is custom
structural deltas, with JSON Patch kept as the export/interchange format (§13 Q2,
resolved) — the example above shows the overlay's logical content, not its storage
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
   data requirements declaration (fields/relationships it reads, and whether the
   component takes a `bind` slot — §4), and a version.
2. Lifecycle: `draft → stable → deprecated`; pages pin versions; deprecation emits
   migration guidance. Registry is metadata, deployable per app version, persisted
   through the Metadata Service's definition store (ARCHITECTURE.md §2.3 — no
   separate persistence path, §8).
3. v1 catalog (18 components): AppShell, NavList, FormLayout, ListLayout,
   RecordHeader, FieldInput, FieldNumber, FieldSelect, FieldSwitch, FieldDate,
   FieldLookup, FieldMultiLookup, FieldRichText, FieldJson, FileUpload (stub,
   disabled until the File Service lands — Phase 6, PLAN.md §5), RelatedList,
   RecordActions, EmptyState.
4. Custom component SDK (escape hatch): charter only — iframe-sandboxed, catalog
   entry with props schema, versioned like everything else.

## 7. Expression DSL Sharing (with ADR-008)

- One grammar for validations, formulas, flow guards, and UI bindings — page-model
  slots `visibility`/`required`/`readonly` (§4; the slot name matches the
  field-metadata `readonly` flag, ARCHITECTURE.md §3 — ADR-008 #3 and ADR-009 #3
  spell the concept `read-only`). The v1 grammar is pinned in Annex A (§14); T4
  implements exactly that.
- Phase 2 ships expression-DSL v1 as a shared asset: the TS evaluator for renderer
  bindings plus a JVM reference parser/evaluator in a shared platform lib
  (`platform/libs/expression-dsl`, ARCHITECTURE.md §7 — the same engine behind
  the Data Runtime's Phase 3 write-path evaluation), wired into the Metadata Service so
  expressions are compile-checked at save/publish (like props schemas). Server-side
  evaluation of expression semantics in the write path (expression defaults,
  validation rules, formulas)
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
  matrix; owner/role-hierarchy/criteria rules land by Phase 4
  (PHASE-4-WORKFLOW-APPROVALS.md §10; Phase 3 needed none). Cross-tenant
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
realm/client assignment (Keycloak per the Phase 0 compose stack; realm strategy per
the closed Phase 0 Q1 decision — PHASE-0 §12) → first admin user → create first app → land
in entity builder. Target: < 5 minutes platform-admin time, fully scripted in API
terms for E2E tests.

**Admin API — pinned:** tenant provisioning and user→role assignment are
platform-admin APIs on the Data Runtime — it owns the platform-DB authorization
data per the ADR-002 direction (ARCHITECTURE.md §2.2) and already reads it at
request time (§9): `POST /api/v1/admin/tenants` (tenant row + first-admin
assignment in one flow, orchestrating the Keycloak side — user creation and tenant
claim via Keycloak's Admin API, deployed configuration, not bespoke code,
ARCHITECTURE.md §7) and `POST /api/v1/admin/tenants/{tenantId}/role-assignments`
(gateway route `/api/v1/admin/**`, `admin`-gated). Both are audited per the §9
event shapes — the durable trail lands with the Phase 3 event spine. App creation
in the same journey rides the Phase 1 metadata APIs unchanged. Tenant offboarding
(deprovisioning, data export/retention, deletion) is deliberately unmodeled in v1 —
a backlog item, not an omission; nothing in the platform assumes it exists.

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
| T1 | FE workspace + stack pin | pnpm workspace, Vite, React 19.2.x, TS strict, CI lint/test, react-hook-form + thin schema binder (§13 Q1) | Scaffold builds in CI; form layer wired per the resolved decision |
| T2 | Page model + registry core | TS types for §4, JSON Schema validation, lazy component registry, version pinning, custom structural delta encoding (§13 Q2) | Invalid props rejected at save; unknown version → fallback + warning; delta apply/merge round-trips |
| T3 | v1 component catalog | §6 item 3 components with props schemas + Playwright stories | Storybook-style gallery green incl. axe |
| T4 | Expression runtime v1 | Parser/evaluator for pure expressions per Annex A in TS + JVM reference engine (compile-checks expressions at Metadata-Service save/publish); shared conformance fixtures across both | 100% shared-fixture parity; invalid expression rejected at save/publish |
| T5 | Default resolver (L1) | §5 rules incl. role parameterization | Golden-file suite green |
| T6 | Runtime renderer + shell | Interpreter, TanStack Query data layer against Phase 1 APIs, server-side paging/virtualization | 100k-row fixture list served via server-side paging only; virtualized scrolling responsive in Playwright smoke |
| T7 | Entity builder UI | §8 wizard/grid over Metadata APIs | Create/modify entities incl. relationships without API calls by hand |
| T8 | Page builder (L2 overlays) | Canvas, palette, property panel, preview, undo, optimistic locking | Customize order form per §4 example; 409 rebase prompt works |
| T9 | RBAC + field security | §9 editors + server projection enforcement | Cross-role leakage tests fail closed; permission-change audit event shapes defined (§9; durable audit trail lands with the Phase 3 event spine) |
| T10 | Tenant onboarding + exit demo | §10 flow + scripted golden journey | Phase 2 exit criteria demo'd on compose stack |

Dependency order: T1 → T2 → (T3, T4, T5) → T6 → T7 → T8 → T9 → T10.
Note: T5/T6 can start against Phase 1 APIs as soon as Phase 1 exit criteria pass;
T3 runs parallel throughout.

## 13. Resolved Questions (decided 2026-08-21, per the recommendations)

Q1–Q5 carried recommendations; all are decided. Q3's Phase 8 landing and Q4's
Phase 5 landing are scope decisions, unchanged.

- **Q1 — Form layer: DECIDED — react-hook-form + a thin JSON Schema binder**
  (control, size); Uniforms' opinionated themes are not worth ceding widget mapping
  already owned via the L1 rules.
- **Q2 — Overlay format: DECIDED — custom structural deltas** (readable diffs in
  change-set reviews); RFC 6902 JSON Patch is kept as the export/interchange
  format.
- **Q3 — i18n: DECIDED — translation-ready now, editor in Phase 8.** Metadata ships
  the optional `label_i18n` map on every labeled definition (ARCHITECTURE.md §3);
  the editor UI and runtime fallback chain land in Phase 8 (PHASE-8 §7).
- **Q4 — App shell scope: DECIDED — minimal v1** (nav + list + form + detail);
  dashboard composition lands in Phase 5 as versioned catalog components
  (ECharts — PHASE-5 §5).
- **Q5 — Frontend hosting: DECIDED — static bundles behind the gateway** (same
  origin; gateway CORS stays deferred per PHASE-0 §6.1); revisit only if
  custom-component iframes (§6 item 4) demand separate origins.

## 14. Annex A — Expression DSL v1 Grammar (pinned)

The single grammar behind every expression slot this plan names — §7's validations,
formulas, flow guards, and UI bindings — and every later consumer: state-machine
guards (PHASE-4 §3), SLA `match` expressions (PHASE-4 §6), sharing-rule criteria
(PHASE-4 §10), report bucket expressions (PHASE-5 §3), webhook event filters
(PHASE-6 §5), and suite assertions (PHASE-3 §7). T4 implements exactly this;
additions arrive as versioned platform features (the ADR-008 #2 policy) with
conformance fixtures first.

- **Literals:** single-quoted strings (`'POSTED'`); `true`/`false`; `null`; integers;
  **decimals as exact literals** (`50.00` — arbitrary precision, never binary float;
  the PLAN.md §1 money rule, which is why PHASE-3 §7 pins monetary step-template
  values as strings); dates via `date('2026-08-16')`, timestamps via
  `datetime('2026-08-16T12:00:00Z')`.
- **References:** bare identifiers are host-bound values — field apiNames in record
  contexts, plus slot-specific bindings (e.g. `entity`/`transition` in SLA match).
  Relationship paths resolve as dot-chains (`customer.region.code`). `${…}` is **not**
  part of the scalar grammar: it is the template/reference interpolation of ADR-008
  record templates, §4's action props, and suite result references — resolved by the
  host, independent of expression evaluation.
- **Operators:** comparisons `== != < <= > >=` (null-aware: `==`/`!=` compare against
  `null`; ordered comparisons with a `null` operand are `false`); logical
  `&& || !` (a `null` predicate is `false`); arithmetic `+ - * /` on numerics
  (BigDecimal semantics, banker's rounding context per ARCHITECTURE.md §4); date
  arithmetic: `date - date` → integer days, `date ± integer` → date (the aging-bucket
  forms, PHASE-5 §3); membership `x in ('A','B')`. No bitwise operators, no
  assignment, no function definition.
- **Functions v1 (closed set):** `today()`, `now()` — clock-governed: server clock in
  production, the run's frozen clock in suites (PHASE-3 §7), compile-rejected in
  stored formula fields (PHASE-3 §3); `size(collection)` and the method form
  `collection.size()`; `abs(x)`, `round(x, scale)`, `min(a,b)`, `max(a,b)`; strings:
  `upper(s)`, `lower(s)`, `trim(s)`, `length(s)`, `contains(s, sub)`,
  `startsWith(s, prefix)`.
- **Purity:** expressions are side-effect-free and deterministic given bindings +
  clock; each slot declares its bindings, and compile-time reference resolution is
  part of the save/publish compile-check (§7). Aggregate forms
  (`SUM/COUNT/MIN/MAX/AVG` over a child collection) exist only in the `rollup` slot
  (PHASE-3 §3) — the scalar grammar cannot traverse collections (`.size()`
  excepted).
- **Versioning:** the DSL carries its own version (`expr/v1`); definitions record the
  DSL version they compiled against — the same pinning discipline as component
  versions (ADR-009 #1). The grammar ships as JSON Schema in `expression-dsl`, and
  the conformance corpus (valid/invalid/evaluation fixtures) is shared by the TS and
  JVM engines and grows with every addition (§7).
