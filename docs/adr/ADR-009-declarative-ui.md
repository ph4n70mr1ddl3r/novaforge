# ADR-009: Declarative UI — layered generation, component catalog, no codegen

- **Status:** Accepted
- **Date:** 2026-08-15
- **Affects:** PLAN.md P2 (UI Builder), §4 Frontend row, Phase 2 delivery

## Context

Survey of the metadata-driven UI landscape: Salesforce (auto layouts + page-layout
overlays + one expression language client/server), ServiceNow (dictionary → forms),
Power Apps model-driven (schema → forms/views, Power Fx reactive behavior), Oracle APEX
(25 years of schema → pages), Retool/Budibase (JSON component tree + generic runtime),
RJSF/Uniforms/SurveyJS (JSON Schema → forms with ui-schema overlays), and
server-driven UI at Airbnb/Shopify/Yelp. 2025–2026 adds a converging trend: AI +
SDUI, where LLMs generate the page schema rather than code.

The one recurring failure mode: **generating source code instead of interpreting
metadata** — it breaks diffing, versioned promotion/rollback (P8), multi-tenancy, and
AI generation. PLAN.md's "metadata-driven renderer" already avoids this; this ADR
makes the layering explicit and binding.

## Decision

1. **Three-layer UI model, one direction of dependence:**
   - **L1 — Automatic:** `resolveDefaultPage(entityDefinition, role)` is a pure
     function producing a fully usable default page (form, list, detail, navigation).
     An app with zero page definitions is usable — the Phase 2 exit criterion.
   - **L2 — Overlays:** saved page definitions store only deltas against the
     generated default (reorder, sections, visibility rules, custom components).
     Entity evolution flows into un-overridden parts automatically.
   - **L3 — Component catalog:** every builder component declares a JSON Schema for
     its props and is versioned; custom components enter the same catalog and remain
     reviewable/promotable artifacts.
2. **Runtime renderer = recursive interpreter** over page JSON resolving components
   from a lazily-loaded React registry. No source-code generation, ever.
3. **Behavior is declarative:** visibility/required/read-only use the platform
   expression DSL (shared with ADR-008) as reactive bindings, not event handlers.
   Client evaluation is UX sugar only; the Data Runtime remains authoritative.
4. **Escape-hatch ladder:** expressions → declarative actions (open page, run flow) →
   cataloged custom components. Each rung is versioned data.
5. **Cross-cutting requirements:** theming via design tokens (W3C DTCG format);
   WCAG 2.2 AA for builder and generated UI; server-side paging over the query DSL
   with virtualized tables for large lists (100k+ rows — PLAN.md §1 non-negotiables).
6. **Stack:** React 19.2.x + TypeScript (19.2.8 current as of 2026-08; no React 20
   exists). Builder canvas: React-Flow + agnostic-dnd per PLAN.md §4.

## Consequences

- `resolveDefaultPage` being pure gives golden-file testing and deterministic
  role-dependent defaults for free; it is a first-class tested platform asset.
- Component prop schemas must be versioned from day one, and pages pin component
  versions — otherwise page definitions rot as the catalog evolves (Salesforce's
  API-versioning lesson).
- The same expression engine must run in the browser and the JVM (compile target,
  conformance suite shared with ADR-008).
- Builder UX (drag-drop, undo, property panels, preview) remains substantial
  engineering regardless of declarativeness — budgeted as such in Phase 2.
- AI-assisted page/entity authoring becomes a schema-generation problem — feasible
  later without architectural change.
