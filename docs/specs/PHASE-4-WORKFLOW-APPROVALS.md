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
> | Status | Draft for review |
> | Date | 2026-08-16 |
> | Owner | Platform team |
> | Estimate | 4–5 weeks (per PLAN.md §5) |
> | Depends on | Phase 3 (flow engine + spine + harness, system-principal direction Q1) + Phase 2 (RBAC roles for approver/sharing semantics) |

## 1. Objective & Exit Criteria — and the SDD Working Agreement

Deliver the Phase 4 exit: *purchase order requires manager approval above threshold,
with escalation* (PLAN.md §5), decomposed as a builder-authored, suite-verified
journey:

1. A `PurchaseOrder` entity carries a state machine
   (`DRAFT → SUBMITTED → APPROVED | REJECTED → POSTED`) and a threshold field.
2. A submit flow branches on `total > threshold` → `requestApproval` (managers,
   all-must-approve, SLA + escalation).
3. The submitting clerk is excluded from approval (segregation of duties).
4. A manager resolves the task (inbox + email); approval transitions the record.
5. With no resolution inside the SLA target, escalation reassigns to the senior
   manager role.

**Spec-driven development agreement:** this document is the implementation
contract. Work proceeds task by task (§15); a task is done when its acceptance
criteria pass — and wherever the behavior is expressible as an ADR-010 suite, the
acceptance criteria *are* suites run through the Phase 3 harness. If implementation
discovers a design change, the spec changes first (its own commit), then the code.
No design decisions are deferred to implementation time; the only open items are
§16, both non-blocking.

Out of scope: BPMN *visual* designer (Q1 — execution and event-starts ship, the
canvas waits for demand); scheduled report delivery (Phase 5 registers consumers);
multi-level escalation chains (v1 is single-level, §6); sequential approval chains
(v1 modes are `any` and parallel-unanimous `all`, §4 — sequential arrives as a
versioned mode when a flow demands it, the same policy as every primitive growth);
period locking / posting immutability (Phase 7 platform enhancements); wizard/tab
pages (PHASE-2 deferral, unchanged).

## 2. Service & Infrastructure Additions

| Addition | Detail |
|---|---|
| `novaforge-workflow-service` | Port 8086; gateway route `/api/v1/workflow/**` (already anticipated by ARCHITECTURE.md §2.1). Flowable 7 embedded. **ADR-004 moves Proposed → Accepted with a written file at phase start** (the ARCHITECTURE.md §8 convention). |
| `novaforge-scheduler-service` | Port 8087; internal (no gateway route) — registry administration happens via publish, not REST. |
| `novaforge-notification-service` | Port 8088; gateway route `/api/v1/notifications/**` (inbox read + preferences). |
| Compose | **Mailpit** joins the stack (SMTP 1025, UI 8025) as the local email sink. No other new infra — Postgres/Kafka/Redis are reused. |
| `common-core` | Two error codes join the seed set: `STATE_TRANSITION("4010", 400)` and `SOD_VIOLATION("4011", 400)` (the PHASE-0 §5.2 set is a seed, not a ceiling). |
| `event-schemas` | New contracts: `task.created/assigned/completed/escalated`, `sla.warn/breach`, `notification.delivered`. |
| `metadata-model` | New schemas: `StateMachineDefinition`, `SLADefinition`, `SharingRuleDefinition` (PermissionSet branch). |

## 3. State Machines (first-class metadata, enforced on the write path)

**Where they execute — pinned:** state machines are *metadata enforced by the Data
Runtime write path*, like validations — not a Workflow-Service concern. This keeps
the single write path absolute (ARCHITECTURE.md §1): no service can transition a
record around the engine, and the `transitionState` primitive compiles to a guarded
write through the same check. The Workflow Service consumes state-change events; it
never mutates records itself.

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
escalations) run as the per-app **system principal** (PHASE-3 §13/Q1's direction);
human actions (approve, reject, delegate, reassign) run as the acting user. Both are
audited (ARCHITECTURE.md §5).

- **`requestApproval` params (the grammar-fixed primitive, now executable):**
  `{ approvers, mode, timeout, escalateTo }` — `approvers` is a role reference or an
  expression resolving to users; `mode`: `any` (parallel, first resolution wins) |
  `all` (sequential or parallel unanimity — v1: parallel unanimity); `timeout` is an
  ISO-8601 duration; `escalateTo` a role.
- **Durable suspension — pinned:** a flow containing `requestApproval` *suspends* at
  that step. The Workflow Service persists the suspended instance (step pointer,
  context snapshot) and resumes it — system principal — when the approval resolves.
  Pure write-path flows (no suspension steps) keep executing in-process in the Data
  Runtime; only suspension steps coordinate with the Workflow Service.
- **Segregation of duties — pinned, fail closed (PLAN.md §1 non-negotiable):** the
  initiating actor of the record write that triggered the flow is removed from the
  approver set at task creation. Delegation to that actor is rejected. If the
  approver set becomes empty → the flow fails with `SOD_VIOLATION`, audited.
- On resolution: `approve` resumes the flow after the step; `reject` routes the
  branch's `onReject` path if declared, else fails the flow audibly (never silent).

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
  otherwise the `requestApproval` step's own `timeout`/`escalateTo` apply (`warnAt`
  defaulting to 0.8). The dedicated definition wins — the governed overlay beats
  the inline default, and both paths emit the same `sla.*` events.
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
  runtime state (next-fire, run history), never authored directly (the thirteenth-
  pass job-definitions-vs-registry split).
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
- **Templates:** subject/body with `${record.field}` / `${task.field}` tokens — the
  same `${…}` convention as ADR-008 templates and PHASE-2 §4 action props.
- **Channels v1:** platform **inbox** (read via `/api/v1/notifications/**`) and
  **email** via SMTP — Mailpit locally (§2); an SES adapter is a config-gated later
  addition, not new architecture.
- **Preferences:** per-user channel toggles per category (`task-assignment`,
  `sla-warning`) — coarse v1, refined on demand.
- **Synthetic actors (test harness) have no channels** (ADR-010 #3): delivery is
  skipped, events remain — so suites can still assert on them.

## 9. BPMN v1 (Flowable) & Event-Start Subscriptions

- Flowable 7 embedded (ADR-004); process definitions are `WorkflowDefinition`
  metadata (versioned, promoted — ARCHITECTURE.md §2.3).
- **Event-start subscriptions** per ARCHITECTURE.md §2.6:
  `on record.updated where status='submitted'` — the subscription filter is a
  platform expression compiled at publish; matching spine events start the process
  (system principal).
- v1 authors BPMN as XML metadata (import/editor-agnostic); the *visual* designer is
  Q1 (§16). State machines + approvals (§3–§6) cover the ERP-standard flows; full
  BPMN is the long tail.
- In-process BPMN timers (escalation-style) stay inside Flowable (ARCHITECTURE
  §2.8) — the Scheduler never fires BPMN timers.

## 10. Record-Level Sharing Rules (the PHASE-2 §9 remainder lands)

- `SharingRuleDefinition` metadata (PermissionSet branch, versioned + promoted):
  `{ entity, type: owner | roleHierarchy | criteria, roles[], criteria? }`.
- **Role hierarchy — pinned:** roles carry an optional numeric `level` (lower =
  more senior); a user sees records owned by users holding *less* senior roles.
  Single numeric level in v1 (Q2, §16); an arbitrary hierarchy graph is backlog.
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
- **Task inbox in `runtime-ui`**: my-tasks list (server-side paged), approve/reject
  with comment, delegate; notification inbox + preferences.
- Scheduler visibility: job list + last-run status in the builder (read-only v1 —
  the registry is publish-driven, §7).

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

- AuthN/authZ as Phase 0–2: JWT at every service, tenant from claims, object-level
  gate on new APIs (`/api/v1/workflow/**` = `user`+; reassign = admin/builder).
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
   rejected; terminal states immutable; `transitionState` respects the same checks;
   create-with-noninitial rejected.
3. Suspension: flow resumes exactly once per resolution (idempotent replay of the
   completion event); cancel-on-record-delete cancels open tasks.
4. Scheduler: ShedLock single-fire under concurrent leaders; misfire skips (§7);
   `flow`/`script`/`processStart` targets fire with system principal and land in
   audit.
5. Sharing: visibility matrix per role (owner/hierarchy/criteria) via
   `queryRecord`-based suites; write/delete governed by the same evaluation; no
   rule → Phase 2 default preserved (regression).
6. Notification: template token resolution; preference filtering; synthetic actors
   skip delivery but emit events.

## 15. Task Breakdown

| # | Task | Content | Acceptance criteria |
|---|---|---|---|
| T1 | Workflow skeleton + ADR-004 | Service, Flowable embedded, spine wiring, ADR-004 file | Health behind gateway; consumes one spine topic |
| T2 | State-machine metadata | Schema in metadata-model, save/publish validation (§3) | Invalid machines rejected at save; compiled at publish |
| T3 | Write-path enforcement | Transition checks + guards + `STATE_TRANSITION`; `transitionState` activation (§3) | §14.2 suite green; primitive rides the same check |
| T4 | Tasks + inbox API | Task model, lifecycle, events, REST (§5) | CRUD per §5; `task.*` on spine; access rules enforced |
| T5 | `requestApproval` | Durable suspension/resume, SoD fail-closed (§4) | §14.1/§14.3 suites green; `SOD_VIOLATION` case covered |
| T6 | SLAs + escalation | Definitions, Flowable timers, breach flow, metrics (§6) | Clock-advanced warn/breach/escalation suite green |
| T7 | Scheduler | Registry, locks, targets, misfire policy (§7) | §14.4 green; publish activates a job end-to-end |
| T8 | Notification v1 | Consumer, templates, inbox + email, Mailpit (§8) | Email visible in Mailpit; preferences honored |
| T9 | Sharing rules | Definitions, row-filter evaluation, editors (§10) | §14.5 matrix green; default behavior regression green |
| T10 | UI | State-machine designer, approval/SLA config, sharing editor, task inbox (§11) | Exit journey operable purely via UI |
| T11 | Harness growth | `queryRecord`, `resolveTask`, task/SLA assertions (§12) | §1 exit suite authored and green through the runner |
| T12 | Exit review | Walk PLAN §5 exit + dashboards | Demo: PO above threshold → approve → POSTED, escalation shown |

Dependency order: T1 → (T2, T4) → (T3, T5) → T6 → T11 → T12. Parallel tracks:
T7 after T1; T8 after T4; T9 from Phase 2 substrate; T10 staged as its engines land.

## 16. Open Questions (both non-blocking)

- **Q1 — BPMN visual designer:** ship a minimal canvas now or wait for demand.
  *Recommendation: wait — state machines + approvals cover the ERP standard; the
  XML-metadata path keeps BPMN usable meanwhile.*
- **Q2 — Role-hierarchy model:** single numeric `level` (v1 pin, §10) vs an
  arbitrary hierarchy graph. *Recommendation: numeric level now; the graph is a
  metadata-only upgrade later if dogfooded reporting lines demand it.*
