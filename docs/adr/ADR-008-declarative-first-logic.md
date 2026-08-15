# ADR-008: Declarative-first business logic (scripts as escape hatch)

- **Status:** Accepted
- **Date:** 2026-08-15
- **Affects:** PLAN.md P3 (Business Logic), ARCHITECTURE.md §2.4/§2.5, Phase 3 delivery

## Context

The original plan made sandboxed GraalJS scripts (`beforeSave`/`afterSave` hooks) the
primary business-logic mechanism. Analysis of the no-code industry (Salesforce Flow,
Power Fx, Power Automate, n8n) shows the mature pattern is a **typed step graph** —
imperative in semantics, declarative in representation — with scripts only as a
last-resort escape hatch. Two NovaForge-specific forces push the same way:

1. **Everything declarative is everything diff-able.** Step graphs stored as versioned
   JSON get impact analysis ("which flows reference field X?"), publish-time
   validation, change-set promotion/rollback, generated tests — and a smaller
   arbitrary-code attack surface for tenants.
2. **The ERP dogfood (Phase 7)** will demand ERP-grade primitives (append-only
   postings, period locking). Solving those as platform primitives with declarative
   flags beats every app re-implementing them in scripts.

Known limit: algorithms with intermediate state (weighted-average costing with
rounding chains, FIFO lot consumption, dunning schedules) and custom integration
transforms are genuinely procedural. A pure step-graph encoding exists but is
unreadable; these justify the escape hatch.

## Decision

1. **Hook execution model = flow IR**: a typed directed graph of steps, stored as
   versioned JSON in the Metadata Service, compiled (reference/type-checked) at
   publish time — never naively interpreted per request (p95 < 150 ms write target,
   ARCHITECTURE.md §9).
2. **Closed primitive set for v1**: `setField` (expression), `createRecord` /
   `updateRecord` (template), `publishEvent`, `callConnector`, `branch` (expression
   guard), `iterate` (over related records), `requestApproval`, `transitionState`.
   Primitives are added as versioned platform features, not per-app code.
3. **One expression language** shared by formulas, validation rules, UI visibility
   (ADR-009), and step bindings — compiled once, evaluated on server (authoritative)
   and optionally client (UX sugar).
4. **GraalJS sandbox is demoted to escape hatch.** Scripts remain supported, but are
   versioned artifacts with the same review/promotion path as definitions, and their
   use is tracked.
5. **Platform health KPI: script ratio** = share of hooks implemented without
   scripts. A script pattern appearing twice becomes a candidate primitive.
6. **State machines, validations, roll-ups stay declarative** as already planned
   (ARCHITECTURE.md §2.6/§3).

## Consequences

- Phase 3 sequencing inverts: build the primitive engine + flow IR first; script
  engine work moves after, sized to actual escape-hatch demand.
- The expression language and flow IR become critical-path platform assets — they
  need their own JSON Schema, versioning, and conformance tests (shared with the
  client, per ADR-009).
- The Metadata Service gains a compiler/validator role at publish time.
- AI-assisted building gets a clean target: LLMs emit flow IR + expressions
  (structured, validatable) rather than code.
- Script ratio telemetry is emitted per app version (feeds the primitive backlog).
