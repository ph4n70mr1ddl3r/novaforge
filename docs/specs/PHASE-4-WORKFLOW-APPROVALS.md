# Phase 4 — Workflow & Approvals: Implementation Specification

> Complete, implementation-driving spec for state machines, approvals, human tasks,
> SLAs/escalation, the Scheduler, notifications, and record-level sharing. Product
> context: [PLAN.md](../../PLAN.md) §5 Phase 4. Engine substrate: the Phase 3 flow
> engine, event spine, and test harness ([ADR-008](../adr/ADR-008-declarative-first-logic.md),
> [ADR-010](../adr/ADR-010-builder-test-harness.md)); service design:
> ARCHITECTURE.md §2.6/§2.8, §5 item 2.
>
> | | |
> |---|---|
> | Status | Decided (open questions resolved 2026-08-21) |
> | Date | 2026-08-16 |
> | Owner | Platform team |
> | Estimate | 4–5 weeks (per PLAN.md §5) |
> | Depends on | Phase 3 (flow engine + spine + harness, system-principal decision — PHASE-3 §13 Q1, resolved) + Phase 2 (RBAC roles for approver/sharing semantics) |

## 1. Objective & Exit Criteria — and the SDD Working Agreement

Deliver the Phase 4 exit: *purchase order requires manager approval above threshold,
with escalation* (PLAN.md §5), decomposed as a builder-authored, suite-verified
journey:

1. A `PurchaseOrder` entity carries a state machine (`DRAFT → SUBMITTED →
   APPROVED | REJECTED`; `APPROVED → POSTED`; REJECTED and POSTED terminal — the
   §3 machine) and a threshold field.
2. A submit flow branches on `total > threshold` → `requestApproval` (managers,
   all-must-approve, SLA + escalation).
3. The submitting clerk is excluded from approval (segregation of duties).
4. A manager resolves the task (inbox + email); approval transitions the record.
5. With no resolution inside the SLA target, escalation creates a replacement task
   for the senior manager role (§6 — not the §5 `reassign` operation; the original
   task goes `ESCALATED`).

**Spec-driven development agreement:** this document is the implementation
contract. Work proceeds task by task (§15); a task is done when its acceptance
criteria pass — and wherever the behavior is expressible as an ADR-010 suite, the
acceptance criteria *are* suites run through the Phase 3 harness. If implementation
discovers a design change, the spec changes first (its own commit), then the code.
No design decisions are deferred to implementation time; the former §16 open
items are resolved scope pins (both non-blocking).

Out of scope: BPMN *visual* designer (deferred until demand — §16 Q1, resolved;
execution and event-starts ship); scheduled report delivery (the Scheduler's
`report` target is registered dormant in §7; Phase 5's Reporting Service is its
consumer, PHASE-5 §7);
multi-level escalation chains (v1 is single-level, §6); sequential approval chains
(v1 modes are `any` and parallel-unanimous `all`, §4 — sequential arrives as a
versioned mode when a flow demands it, the same policy as every primitive growth);
period locking / posting immutability (Phase 7 platform enhancements); wizard/tab
pages (PHASE-2 deferral, unchanged).

## 2. Service & Infrastructure Additions

| Addition | Detail |
|---|---|
| `novaforge-workflow-service` | Port 8086; gateway route `/api/v1/workflow/**` (already anticipated by ARCHITECTURE.md §2.1). Flowable 7 embedded. **ADR-004 is accepted ahead of implementation (file written, ARCHITECTURE.md §8); this phase's landing confirms its pins.** |
| `novaforge-scheduler-service` | Port 8087; no gateway route for administration — the registry is publish-driven, never written over REST (§7). The gateway routes exactly one Scheduler path, the read-only status route serving §11's builder visibility: `GET /api/v1/scheduler/jobs` (builder role; browser apps reach APIs via the gateway — PHASE-2 §2) — no write or admin route exists. |
| `novaforge-notification-service` | Port 8088; gateway route `/api/v1/notifications/**` (inbox read + preferences). |
| Compose | **Mailpit** joins the stack (SMTP 1025, UI 8025) as the local email sink. No other new infrastructure — the Postgres/Kafka/Redis instances are reused; each new service adds its own database on the shared Postgres (the PHASE-1 §6 pattern). |
| `common-core` | Two error codes join the seed set: `STATE_TRANSITION("4010", 400)` and `SOD_VIOLATION("4011", 400)` (the PHASE-0 §5.2 set is a seed, not a ceiling). |
| `event-schemas` | New contracts: the `task.*` lifecycle — `task.created/assigned` plus one event per §5 terminal status (`task.approved/rejected/delegated/escalated/cancelled`; there is no `completed` status to event), so delegation chains and record-delete cancellation are observable — plus `sla.warn/breach`, `notification.delivered`, `scheduler.job.run` (§7) — the first families joining the spine's shared-topic convention (`novaforge.<family>.*`, PHASE-3 §4), their partition keys pinned at landing per that section: `task.*`/`sla.*` keyed `tenant_id:task_id` (per-task ordering — a task's assigned → warn/breach → terminal transitions serialize, and a delegation chain rides each delegate task's own key, `contextRef`-linked), `notification.*`/`scheduler.*` tenant-scoped (`tenant_id`). |
| `metadata-model` | New schemas: `StateMachineDefinition`, `SLADefinition`, `SharingRuleDefinition` (PermissionSet branch), `WorkflowDefinition` (BPMN process definitions, §9) — all four in ARCHITECTURE.md §2.3's owns-list (WorkflowDefinition since v0; the rest join it this phase). |

## 3. State Machines (first-class metadata, enforced on the write path)

**Where they execute — pinned:** state machines are *metadata enforced by the Data
Runtime write path*, like validations — not a Workflow-Service concern. This keeps
the single write path absolute (ARCHITECTURE.md §1): no service can transition a
record around the engine, and the `transitionState` primitive compiles to a guarded
write through the same check. The Workflow Service consumes state-change events —
a state change is a `record.updated` event whose field diffs include the bound
`stateField` (the PHASE-3 §4 record family carries the diffs; no separate event
family exists) — and never mutates records itself.

```json
{ "id": "sm_purchase_order",
  "entity": "PurchaseOrder", "stateField": "status",
  "initial": "DRAFT",
  "states": [
    { "name": "DRAFT" }, { "name": "SUBMITTED" },
    { "name": "APPROVED" }, { "name": "REJECTED", "terminal": true },
    { "name": "POSTED", "terminal": true } ],
  "transitions": [
    { "from": "DRAFT",     "to": "SUBMITTED", "guard": "lines.size() > 0" },
    { "from": "SUBMITTED", "to": "APPROVED" },
    { "from": "SUBMITTED", "to": "REJECTED" },
    { "from": "APPROVED",  "to": "POSTED" } ] }
```

- **Schema rules (validated at save, compiled at publish):** `stateField` must be an
  enum field on the bound entity; `initial` ∈ states; every transition references
  known states; guards compile (Phase 2 JVM engine); terminal states have no
  outgoing transitions. One state machine per entity (v1).
- **Write-path enforcement:** on create, the engine sets `initial` (a differing
  explicit value is rejected); on update, a changed `stateField` requires a listed
  transition *and* a passing guard, else 400 `STATE_TRANSITION` problem+json.
  Guards evaluate in-process (same engine as validations — ADR-008 #3).
- **`transitionState` activates:** the Phase 3 grammar-fixed primitive compiles to a
  guarded field write; target state must be reachable from the record's current
  state. No bypass exists — flows, scripts, and humans all go through the same check.
- Transition hooks (flows fired *on* a transition) are deferred; v1 flows subscribe
  to `record.updated` or run before the transition (the exit scenario models it as a
  submit hook that requests approval, then transitions).

## 4. Approvals & Durable Flow Suspension (`requestApproval` activates)

**Execution context — pinned:** engine-driven actions (flows, transitions,
escalations) run as the per-app **system principal** (the PHASE-3 §13 Q1 decision);
human actions (approve, reject, delegate, reassign) run as the acting user. Both are
audited (ARCHITECTURE.md §5).

- **`requestApproval` params (the grammar-fixed primitive, now executable):**
  `{ approvers, mode, timeout?, escalateTo?, onReject? }` — `approvers` is a role reference or an
  expression resolving to users; `mode`: `any` (parallel, first resolution wins) |
  `all` (sequential or parallel unanimity — v1: parallel unanimity); `timeout`
  (optional) is an ISO-8601 duration; `escalateTo` (optional) a role; `onReject?`
  an optional inline flow-IR subgraph (same shape as `iterate`'s `body`) declared
  **on the step itself** — there is no coupling to any enclosing `branch` node.
- **Durable suspension — pinned:** a flow containing `requestApproval` *suspends* at
  that step. The Workflow Service persists the suspended instance (step pointer,
  context snapshot) and resumes it — system principal — when the approval resolves.
  Pure write-path flows (no suspension steps) keep executing in-process in the Data
  Runtime; only suspension steps coordinate with the Workflow Service. A suspension
  step inside a write-path hook never holds the enclosing transaction: the
  triggering write commits, the instance stays suspended, and resolution re-enters
  the compiled-graph engine in the Data Runtime — the same internal,
  system-principal path the Scheduler's `flow` target uses (§7) — afterward (the
  exit scenario's submit hook, §3, and PHASE-7 §5's posting flow are exactly this
  shape).
- **Segregation of duties — pinned, fail closed (PLAN.md §1 non-negotiable):** the
  initiating actor of the record write that triggered the flow is removed from the
  approver set at task creation. Delegation to that actor is rejected. If the
  approver set becomes empty → the flow fails with `SOD_VIOLATION`, audited.
- On resolution: `approve` resumes the flow after the step; `reject` routes the
  step's own `onReject` subgraph (§4 params) if declared, else fails the flow audibly (never silent).

## 5. Human Tasks & Inbox API

Task model: `{ id, tenant, type: approval|todo, entity, recordId, assignee | role,
status, createdAt, dueAt, sla { warnAt, breachAt }, createdBy, contextRef }`.
Statuses v1: `OPEN → APPROVED | REJECTED | DELEGATED | ESCALATED | CANCELLED`.

- `GET /api/v1/workflow/tasks` — my tasks (assigned to me or my roles), open by
  default; filter/sort/page per the Phase 1 query conventions.
- `POST /api/v1/workflow/tasks/{id}/approve` and `/reject` — body: optional comment;
  audited with actor + comment.
- `POST .../claim` (role-assigned tasks), `.../delegate` `{ toUser }`, and
  `.../reassign` `{ toUserOrRole }` (admin/builder only, audited).
- Delegation creates a new task; the original is `DELEGATED` (chain preserved via
  `contextRef`). Record deletion cancels open tasks for that record.
- Every task event lands on the spine (`task.*`, §2) — notifications (§8) and audit
  are pure consumers; the harness observes tasks through the same events.

## 6. SLAs & Escalation (semantics pinned)

`SLADefinition` — the "SLAs (P4)" of PLAN.md §1, made concrete:

```json
{ "id": "sla_po_approval",
  "scope": { "taskType": "approval",
             "match": "entity == 'PurchaseOrder' && transition == 'DRAFT->SUBMITTED'" },
  "target": "PT24H", "warnAt": 0.8,
  "onBreach": { "escalateTo": "role:senior-manager", "notify": true } }
```

- **Semantics:** wall-clock duration from task `createdAt`; `warnAt` is a fraction of
  `target` (0.8 = warn at 80%). Timers are in-process Flowable jobs (ARCHITECTURE
  §2.8 keeps escalation timers with embedded Flowable, not the Scheduler).
- **Precedence over the primitive's own `timeout` — pinned:** a task's
  `sla`/`dueAt` come from a matching `SLADefinition` when one matches the scope;
  otherwise the `requestApproval` step's own `timeout`/`escalateTo` apply. `warnAt`
  is optional on both paths and defaults to 0.8 — a matching `SLADefinition`
  overrides it, and `warnAt: null` disables the warn timer outright. The
  dedicated definition wins — the governed overlay beats
  the inline default, and both paths emit the same `sla.*` events. With neither an
  SLA nor the step's `timeout` present, the task carries no `dueAt` — no timer, no
  escalation — and stays open until resolved or cancelled (§5).
- **Breach:** the open task becomes `ESCALATED`; a replacement task is created for
  `escalateTo`; `sla.breach` is emitted; both actions audited; a counter metric
  (`novaforge.sla.breach`) increments. Single-level escalation in v1 (a chain is a
  backlog item if the dogfood demands it).
- SLA metrics (warn/breach counts per app) feed the Grafana baseline; targets are
  *measured*, not gated, in this phase.

## 7. Scheduler Service

- **Job definitions are the scheduled-job half of `RuleDefinition`**
  (ARCHITECTURE.md §2.3): `{ cron, target: flow | script | processStart | report,
  params, enabled }`, versioned metadata **activated on publish** — the registry is
  runtime state (next-fire, run history), never authored directly (the
  job-definitions-vs-registry split).
- DB-backed registry + ShedLock-style distributed locks (ARCHITECTURE.md §2.8);
  executions audited; a `scheduler.job.run` event per fire (success/failure).
- **Targets:** `flow` → the compiled-graph engine in the Data Runtime via an internal
  endpoint, system principal, synthetic `scheduled` trigger context (`$record`
  absent); `script` → the Script Engine the same way; `processStart` → the Workflow
  Service starts a BPMN process; `report` → registration only, no consumer until
  Phase 5.
- **Misfire policy — pinned:** fire once, skip missed (a missed window waits for the
  next cron tick). Gapless *execution* is not promised — gapless semantics belong to
  sequences (Phase 1), not schedules.

## 8. Notification Service v1

- Pure spine consumer (ARCHITECTURE.md §1): `task.*`, `sla.warn/breach` in v1.
- **Templates — pinned:** v1 ships built-in platform default templates per category
  (no authoring surface — which is why §2 lists no template schema and §11 no
  editor); later phases add built-in categories as their features land — Phase 5's
  `report-delivery` is the first (PHASE-5 §7) — while app-authored templates arrive
  as versioned metadata via the gap-harvest path when demanded (Phase 7 dunning
  letters are the expected first case).
  Subject/body use `${record.field}` / `${task.field}` tokens — the same `${…}`
  convention as ADR-008 templates and PHASE-2 §4 action props.
- **Channels v1:** platform **inbox** (read via `/api/v1/notifications/**`) and
  **email** via SMTP — Mailpit locally (§2); an SES adapter is a config-gated later
  addition, not new architecture, and SMS/push/websocket (PLAN.md §3's fan-out
  list) stay deferred until demand — the same versioned-growth policy.
- **Preferences:** per-user channel toggles per category (`task-assignment`,
  `sla-warning`) — coarse v1, refined on demand.
- **Synthetic actors (test harness) have no channels** (ADR-010 #3): both
  channels are skipped — no inbox entry, no email, hence no
  `notification.delivered`. What stays observable is the triggering
  `task.*`/`sla.*` events on the spine, exactly the surface suites assert on
  (§14.6).

## 9. BPMN v1 (Flowable) & Event-Start Subscriptions

- Flowable 7 embedded (ADR-004); process definitions are `WorkflowDefinition`
  metadata (versioned, promoted — ARCHITECTURE.md §2.3).
- **Event-start subscriptions** per ARCHITECTURE.md §2.6:
  `on record.updated where status='SUBMITTED'` — the subscription filter is a
  platform expression compiled at publish; matching spine events start the process
  (system principal).
- v1 authors BPMN as XML metadata (import/editor-agnostic); the *visual* designer is
  deferred until demand (§16 Q1, resolved). State machines + approvals (§3–§6) cover the ERP-standard flows; full
  BPMN is the long tail.
- In-process BPMN timers (escalation-style) stay inside Flowable (ARCHITECTURE
  §2.8) — the Scheduler never fires BPMN timers.

## 10. Record-Level Sharing Rules (the PHASE-2 §9 remainder lands)

- `SharingRuleDefinition` metadata (PermissionSet branch, versioned + promoted):
  `{ entity, type: owner | roleHierarchy | criteria, roles[], criteria? }`.
- **Role hierarchy — pinned:** roles carry an optional numeric `level` (lower =
  more senior); a user sees records owned by users holding *less* senior roles.
  Single numeric level in v1 (§16 Q2, resolved); an arbitrary hierarchy graph is backlog.
- `owner`: creator (or an explicit owner field) plus named roles; `criteria`: records
  matching a compiled expression shared with the named roles.
- **Enforcement:** rules are evaluated into row filters appended to every query, and
  the same evaluation governs record-level write/delete checks — exactly
  ARCHITECTURE.md §5 item 2. Phase 2's default (full visibility under the object
  CRUD matrix) remains the behavior until rules are defined — no silent tightening.
- Builder UI: a sharing-rule editor joins the Phase 2 permission editors.

## 11. Builder & Runtime UI Additions

- **State-machine designer** (the PLAN §5 named deliverable): states/transitions
  canvas over the §3 schema with live guard compile-check; terminal states marked.
- **Approval & SLA configuration** in the flow editor: `requestApproval` step
  properties (approvers, mode, timeout, escalation) + SLA binding, compile-checked.
- **Sharing-rule editor** (§10).
- **Scheduled-job authoring:** the scheduled-job half of `RuleDefinition` (§7)
  gets its builder form — cron, target, params, `enabled` — alongside the
  Phase 3 rule editors; authoring is definition work like any other, and the
  §2 status route stays read-only (administration is publish-driven, §7).
- **Task inbox in `runtime-ui`**: my-tasks list (server-side paged), approve/reject
  with comment, delegate; notification inbox + preferences.
- Scheduler visibility: job list + last-run status in the builder via the
  read-only `GET /api/v1/scheduler/jobs` (§2; administration stays publish-driven —
  the registry is never written over REST, §7).

## 12. Test-Harness Growth (ADR-010 #5's Phase 4 vocabulary)

- **New step ops:** `queryRecord` `{ entity, filter, asRole }` → `{ count, ids }`
  (needed for sharing-rule visibility assertions); `resolveTask`
  `{ match | taskId, action: approve | reject, asRole, comment? }`.
- **New assertion surface:** task references `${Task[n].status}`,
  `${Task[n].assignee}`; `requestApproval` inside a flow under test creates real
  tasks in the scratch tenant; resolution goes through the same inbox API synthetic
  actors use — the harness never gets a back door (ADR-010 #3's no-test-mode rule).
- **Clock-driven SLA tests:** Phase 3's frozen clock advances past `warnAt`/`target`
  deterministically — escalation and `sla.breach` assertions need no sleeps.
- Expected outcomes reuse the ADR-010 vocabulary (`ok`, `error(code)`,
  `validation(rule)`) — plus `error(SOD_VIOLATION)` for §4's fail-closed case.

## 13. Security & Audit

- AuthN/authZ as Phase 0–2: JWT at every service, tenant from claims, route
  gate on new APIs (`/api/v1/workflow/**` = `user`+; reassign = admin/builder;
  `/api/v1/notifications/**` = `user`+, own inbox/preferences only;
  `GET /api/v1/scheduler/jobs` = `builder`+).
- Task access: assignee, the task's role holders, or admin — enforced server-side.
- Audited: task lifecycle, approve/reject/delegate/reassign with actors and
  comments, SLA warn/breach, escalations, scheduler fires, sharing-rule publishes
  (a permission change — ARCHITECTURE.md §5 item 5).
- System-principal actions are audited as the app's system identity with the
  initiating human (where one exists) recorded as context.

## 14. Testing Standards

1. Exit journey as a builder suite (the §1 decomposition): submit → SoD-filtered
   approval task → approve → `APPROVED`; reject path; clock-advanced SLA warn →
   breach → escalation to senior role; `error(SOD_VIOLATION)` when only the
   requester qualifies as approver.
2. State machine: invalid transition rejected (`STATE_TRANSITION`); guard failure
   rejected; terminal states admit no transitions (the *state field* is what is
   frozen — rejecting *all* writes to a terminal record is Phase 7's
   `freezeOnTerminal` harvest, PHASE-7 §3.1); `transitionState` respects the same
   checks; create-with-noninitial rejected.
3. Suspension: flow resumes exactly once per resolution (idempotent replay of the
   completion event); cancel-on-record-delete cancels open tasks.
4. Scheduler: ShedLock single-fire under concurrent leaders; misfire skips (§7);
   `flow`/`script`/`processStart` targets fire with system principal and land in
   audit.
5. Sharing: visibility matrix per role (owner/hierarchy/criteria) via
   `queryRecord`-based suites; write/delete governed by the same evaluation; no
   rule → Phase 2 default preserved (regression).
6. Notification: template token resolution; preference filtering (real actors,
   Mailpit); synthetic-actor runs skip both channels with no
   `notification.delivered` — the triggering `task.*`/`sla.*` events remain the
   assertable surface (§8).

## 15. Task Breakdown

| # | Task | Content | Acceptance criteria |
|---|---|---|---|
| T1 | Workflow skeleton + ADR-004 | Service, Flowable embedded, spine wiring, ADR-004 file | Health behind gateway; consumes one spine topic |
| T2 | State-machine metadata | Schema in metadata-model, save/publish validation (§3) | Invalid machines rejected at save; compiled at publish |
| T3 | Write-path enforcement | Transition checks + guards + `STATE_TRANSITION`; `transitionState` activation (§3) | §14.2 suite green; primitive rides the same check |
| T4 | Tasks + inbox API | Task model, lifecycle, events, REST (§5) | CRUD per §5; `task.*` on spine; access rules enforced |
| T5 | `requestApproval` | Durable suspension/resume, SoD fail-closed (§4) | §14.3 suite green; `SOD_VIOLATION` case covered (§14.1's journey form rides T11's `resolveTask` vocabulary) |
| T6 | SLAs + escalation | Definitions, Flowable timers, breach flow, metrics (§6) | Clock-advanced warn/breach/escalation suite green |
| T7 | Scheduler | Registry, locks, targets, misfire policy (§7) | §14.4 green; publish activates a job end-to-end |
| T8 | Notification v1 | Consumer, templates, inbox + email, Mailpit (§8) | Email visible in Mailpit; preferences honored |
| T9 | Sharing rules | Definitions, row-filter evaluation, editors (§10) | §14.5 matrix green; default behavior regression green |
| T10 | UI | State-machine designer, approval/SLA config, sharing editor, scheduled-job authoring + scheduler visibility, task & notification inboxes (§11) | Exit journey operable purely via UI |
| T11 | Harness growth | `queryRecord`, `resolveTask`, task/SLA assertions (§12) | §1 exit suite authored and green through the runner |
| T12 | Exit review | Walk PLAN §5 exit + dashboards | Demo: PO above threshold → approve → POSTED, escalation shown |

Dependency order: T1 → (T2, T4) → (T3, T5) → T6 → T11 → T12. Parallel tracks:
T7 after T1; T8 after T4; T9 from Phase 2 substrate (its §14.5 visibility suites
ride T11's `queryRecord`); T10 staged as its engines land.

## 16. Resolved Questions (decided 2026-08-21, per the recommendations; both were non-blocking scope pins)

- **Q1 — BPMN visual designer: DECIDED — wait for demand.** State machines +
  approvals cover the ERP standard; the XML-metadata path keeps BPMN usable
  meanwhile. A canvas is a backlog item pulled when a dogfooded flow demands it.
- **Q2 — Role-hierarchy model: DECIDED — single numeric `level`** (the §10 pin).
  An arbitrary hierarchy graph is a metadata-only upgrade later if dogfooded
  reporting lines demand it.
