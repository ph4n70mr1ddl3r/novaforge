# Implementation Status

Tracks what is built versus the phase specs. Each entry names the spec sections the
code implements and the suites that verify it. The corpus is the contract; this file is
the ledger.

## Phase 0 — Foundations ✅ (spec: PHASE-0-FOUNDATIONS.md)

- Monorepo: root parent POM (Boot 4.1.0 / Framework 7.0.8 / Cloud 2025.1.2 per
  ADR-007), `platform/libs` + `services` aggregators, Maven wrapper.
- `novaforge-common-core`: TenantContext, error model (`4000/4001/4003/4004/4090/5000`
  seed registry), ProblemErrors, PlatformException.
- Gateway (8080): YAML routes, JWT validation (problem+json 401s), `X-Tenant-Id`
  derivation from the `tenant_id` claim.
- Metadata-service skeleton (8081): ping pins the exact framework version (drift rule).
- Compose stack (`deploy/compose`): Keycloak 26.7.2 (realm `novaforge`, client
  `novaforge-api`, `novaforge.api` scope with tenant/actor/platform-roles claim
  mappers, confidential service client `novaforge-runtime`, demo user), Postgres 16.15,
  Redis 7.4.11, Kafka 4.3.1, Prometheus + Grafana with the seeded Phase 0 dashboard.
- CI: GitHub Actions build (ubuntu) + the Podman-socket self-hosted job (§9's Phase 1
  wiring).

## Phase 1 — Metadata Core & Data Runtime ✅ (spec: PHASE-1-METADATA-CORE.md)

- **ADR-001 closed**: 1M-row spike, variant B chosen (generated projections,
  trigger-maintained); measured numbers + revisit triggers in the ADR. The
  cast-immutability constraint (no `::date` in index expressions) lowered to policy:
  promote text/numeric only, canonical ISO text for temporal ordering.
- Shared libs: `metadata-model` (definition POJOs + JSON Schema v0 + the §3 rule
  matrix), `security-context` (task decorator, RLS DataSource bridge, event headers),
  `test-support` (Postgres/Redis container bases, RLS fixtures, shared ArchUnit rules).
- Metadata Service (8081): draft CRUD, save validation, publish with compatibility
  check + `acknowledgeDataImpact`, immutable versions + export, published read,
  `published-apps` index, `metadata.published` on Redis pub/sub.
- Data Runtime (8083, module split `api/engine/storage/authorization`): the full §5/§6/§7
  surface — write path (defaults incl. sequence refs drawn once at create, field
  validations, inline children ≤100 with cascade delete, optimistic locking, soft
  delete, uniqueness via partial unique indexes), query path (DSL GET + `/query`
  aggregates, batch ≤500 with per-item outcomes), sequences (cached Redis blocks +
  gapless in-transaction), Idempotency-Key on create/batch, RLS everywhere (fail-closed
  policies; the system role carries BYPASSRLS for the materializer/catch-up —
  documented in `deploy/postgres-init`), platform authorization store + role matrix
  (fail closed), materializer reacting to publish + restart catch-up, ArchUnit layering.
- **Exit demo verified live** (compose stack + services + real Keycloak tokens):
  app → publish → CRUD with gapless `JE-00000x` numbering, inline lines, 409 on stale
  versions, idempotent replay, aggregates. Load test (§10): read p95 39.4 ms
  (< 50), list p95 37.2 ms (< 300), write p95 33.4 ms (≤ 100) — PASS
  (`docs/loadtests/results-2026-08-21.md`).
- Environment track: Kind-on-Podman config, per-service Helm charts + umbrella,
  Skaffold (jib) — written; live cluster bring-up is the remaining operational check
  (validated declaratively; not yet exercised against a running Kind cluster).

Suites: 176 tests, `./mvnw verify` green (Testcontainers: Postgres + Redis + Kafka;
rootless Podman socket env documented in the README).

**Live verification:** the full spine runs on the compose stack — Keycloak → gateway →
metadata → runtime with the outbox → Kafka → audit trail all observed live (create with
inline children → 4 outbox rows published → `novaforge.record` carries the events →
`GET /api/v1/audit/records/{id}` serves the trail through the gateway).

## Phase 2 — Builder UI & Security ◐ (spec: PHASE-2-UI-BUILDER.md)

**Backend implemented:**
- `expression-dsl` (expr/v1, §7/Annex A): the JVM parser/evaluator with the pinned
  grammar — exact BigDecimal semantics, null-aware operators, date arithmetic,
  membership, the closed function set, injectable clock — plus the shared conformance
  corpus (40 cases) and compile-check wired into the Metadata Service save/publish
  (validations may read the clock; formula fields may not, PHASE-3 §3). Slots stay
  inert until Phase 3 write-path evaluation.
- RBAC + field security (§9): PermissionSet as versioned, promoted metadata
  (roles, object CRUD matrix, field visible/readonly/hidden) with save-validation
  rules; server-side enforcement in the Data Runtime — the matrix decides
  create/read/update/delete per app-scoped role (`App.role` assignments in the
  platform DB), hidden fields strip from every projection, writes to
  hidden/readonly fields reject.
- Tenant onboarding + platform-admin API (§10): `POST /api/v1/admin/tenants`
  (tenant row + first admin, orchestrating Keycloak user creation via the service
  account with realm-management roles) and role assignments — admin-gated, riding
  the engine behind a UserProvisioner port (layering rules hold).
- Realm export: service account `service-account-novaforge-runtime` with
  manage-users; role-assignment check widened for app-scoped roles (V2 migration).

**Not implemented:** the React builder/runtime UIs (§3–§6, §8 — the largest remaining
Phase 2 surface: page model, component catalog, renderer, entity/page builders) and
the TS evaluator twin for the conformance corpus. Backend surfaces are ready for it:
published reads, expression bindings compile-checked, field security stripping.

## Phase 3 — Business Logic ◐ (spec: PHASE-3-BUSINESS-LOGIC.md)

**Implemented — §3 write-path evaluation** (the ARCHITECTURE §2.4 chain is now:
resolve → authorize → defaults → formula/roll-up evaluation → validation rules →
persist → event seam → shaped projection):
- expression defaults (`{"expression": "…"}` — clock-free, compile-checked, evaluated
  at the defaults step before validations)
- validation rules (record-scope expressions extending Phase 1 field constraints,
  failing with the authored message)
- formula fields (own-record expressions stored at write time, never computed on
  read; implicitly readonly; null-propagating string/numeric functions keep stored
  formulas total)
- roll-up summaries (`SUM/COUNT/MIN/MAX/AVG(relationship[.field])` — creates
  aggregate the in-memory child set; updates recompute in the child's write
  transaction and only rewrite the parent when a value moved)

**Implemented — §4 event spine + §5 audit:**
- transactional outbox (`event_outbox`): record events ride the creating transaction;
  the KafkaOutboxRelay publishes committed rows at-least-once to family topics
  (`novaforge.record`), keyed `tenantId:recordId` for per-record ordering, event id +
  type + tenant in headers, then marks rows published (stop-on-failure preserves order)
- Audit Service v1 (`novaforge-audit-service`, port 8085): consumes `novaforge.record`
  into an append-only, monthly-partitioned Postgres trail (`audit_events`, PK
  `(event_id, occurred_at)` — identical redeliveries collapse); tenant-scoped reads
  through the gateway (`/api/v1/audit/records/{id}`, `/api/v1/audit/entities/{id}`);
  Prometheus scrape + Helm chart + umbrella entry; the durable trail PHASE-2 §9
  promised

**Implemented — §2 flow-IR hooks (ADR-008 #1–2):**
- metadata: HookRule (`{trigger, flow}`) + FlowStep graphs on entities (schema v0
  extended); the publish-time FlowCompiler reference/type-checks every step —
  ops from the closed v1 set, DAG enforcement (cycles and dangling chains
  reject), setField fields exist, guard/setField expressions compile, record
  templates address existing fields on their target entity, iterate paths are
  relationships; grammar-fixed ops (requestApproval/transitionState/callConnector)
  compile and fail loudly only if executed before Phases 4/6
- runtime: HookExecutor on the write path — setField (expression), createRecord/
  updateRecord (${…} templates, nested engine writes as the per-app system
  principal with a depth budget), publishEvent (rides the outbox spine),
  branch (guard), iterate (relationship body over live children); failure policy
  per ARCHITECTURE §2.5 (before-hooks abort the transaction, after-hook failures
  are recorded, never lost, never block the write)
- **exit scenario green**: order → beforeSave stamps the label, afterSave iterates
  lines and reserves inventory stock via updateRecord — totals via roll-ups,
  reservation via a hook, zero app code

**Implemented — §7 builder test harness (ADR-010):**
- `TestSuiteDefinition` (Tests branch): fixtures → steps → assertions, the pinned
  encoding — step vocabulary createRecord/updateRecord/deleteRecord with `${…}`
  templates (monetary values as strings), `expect: ok | error(code) | validation(rule)`,
  assertions as DSL predicates over `${Entity[n].path}` references resolved to exact
  decimal literals
- suite APIs on the Metadata Service: PUT `/apps/{id}/test-suites/{name}` (ops and
  expectations validated on save) and POST `.../run`
- the runner: fresh scratch tenant per run via the platform-admin API (tenant
  offboarding is unmodeled in v1 — PHASE-2 §10), synthetic actors provisioned with
  role impersonation through Keycloak (firstName/lastName + email set — Keycloak 26's
  Verify Profile blocks first login otherwise; tenant_id + platform_roles ride
  declared managed attributes the provisioner ensures on boot), the candidate bundle
  published into the scratch tenant (never a mutable draft), steps replayed through
  the generic runtime APIs as the actors (4xx bodies are results, matched against
  expectations), assertions evaluated under the run's frozen clock
**Implemented — §6 Script Engine v0 (ADR-003, T6):**
- `novaforge-script-engine` (port 8084, internal — hooks invoke it, no gateway route):
  GraalVM JS on the community jars, one `Context` per execution (no warm pools —
  deferred with demand), no host I/O or classes, and the ADR's caps made real —
  statement watchdog (`ResourceLimits`), CPU-time cap (ThreadMXBean-sampled force
  close), heap tripwire (process-growth attribution; per-context metering is
  Enterprise-only), wall-clock backstop, bounded concurrency with a bounded queue —
  the watchdog stays armed through result conversion (getters/proxies run guest code)
- the closed surface: `$record` (read-only view, `id` included), `$data.query` (the
  Data Runtime list API under the calling user's relayed token — no service-account
  fallback, a script can never exceed its authorizing user's grants), `$log` (bounded
  capture); the program's completion value is the result (integral numbers stay
  integral; conversion bounded)
- metadata: `ScriptDefinition` artifact riding the app definition (versioned on the
  same review/promotion path); `HookRule` is flow XOR script, publish-enforced
  (language set, source size, blank rejects)
- runtime: HookExecutor script branch — caller-context through RestScriptEngineClient
  (the write request's token relayed verbatim), beforeSave return-merge into declared
  fields (reserved names pass through), failure policy uniform with flows; script
  hooks die at the same caps through the engine and render problem+json on the write
  path
- script-ratio telemetry per app version: engine-side
  `novaforge.script.executions{app,version,trigger,outcome}` + duration; runtime-side
  `novaforge.hook.executions{app,version,trigger,kind}` — the ratio joins on the
  dashboards (§9)
- deploy: helm chart + umbrella dependency + prometheus scrape; graalvm pins in the
  parent dependencyManagement (the `js-community` coordinates are pom-packaging
  aggregators — the concrete community jars are polyglot/js-language/regex)
- **T6 acceptance pinned**: a capped script (infinite loop, getter bomb, heap hog)
  dies at its budget and the service stays on its feet; host access closed;
  authorization verdicts (FORBIDDEN et al.) survive the sandbox boundary

**Implemented — §2 failure policy, the retry leg (phase close):**
- after-hook failures ride the spine out of the write's own transaction: the engine
  emits `hook.retry` outbox rows (trigger, hook, kind, attempt, error in the payload
  envelope) — never lost, never blocking the write; the relay publishes them to
  `novaforge.hook` (family topic)
- the retry consumer on the spine (`HookRetryConsumer`, group `novaforge-hook-retry`)
  claims events idempotently — the spine's event id is the `hook_retry_log` PK, so
  at-least-once redelivery collapses
- the scanner re-drives due rows through the real write path
  (`RecordEngine.retryAfterHook`): the record's **current** state, the per-app system
  principal — the identical context to the original execution (§13 Q1) — under
  exponential backoff (base 5 s, ×2 per attempt, capped 10 min, 8 attempts default),
  then parks durably (never silently dropped; `novaforge.hook.retry.outcome{result}`
  + a pending gauge)
- script-kind failures park at consume time by design: scripts are caller-context
  only (ADR-003 #2) and the spine has no user token to relay — parking keeps the
  failure visible instead of silently escalating to a service account
- non-convergent retries park immediately: hook gone from the republished definition,
  record since deleted

**Implemented — §9 observability expansion (phase close):**
- Tempo 2.10 (OTLP gRPC 4317 + HTTP 4318) and Loki 3.6 + promtail join the compose
  stack; every service exports spans OTLP-direct to Tempo (no collector — §13 Q4).
  Boot 4's modular tracing wiring: `spring-boot-micrometer-tracing-opentelemetry`
  (the autoconfiguration) + `micrometer-tracing-bridge-otel` + the pinned
  `io.opentelemetry:opentelemetry-exporter-otlp` (the OTLP autoconfig's
  `@ConditionalOnClass` — Boot's BOM doesn't manage it), configured under
  `management.opentelemetry.tracing.*` with the sampler switchable
  (`NOVAFORGE_TRACE_SAMPLER`, default always-on for local)
- logs ship to Loki: services file-log to a shared dir
  (`NOVAFORGE_LOG_DIR`, default `/tmp/novaforge/logs`) that promtail tails with a
  per-service label; the Loki datasource derives trace-id deep links into Tempo from
  the shared `LEVEL [service,traceId,spanId]` pattern
- kafka-exporter joins (via a new in-network Kafka `INTERNAL` listener — advertised
  listeners must match where each client lives) and Prometheus scrapes it
- new dashboards: the "NovaForge / Phase 3" board — Kafka consumer lag, hook-duration
  histograms (`novaforge.hook.duration{trigger,kind}` — new Timer in the executor),
  script ratio per app version, suite pass rates (`novaforge.suite.runs{app,outcome}`
  — new counter in the harness runner), after-hook retry outcomes + backlog

**Phase 3 closed:** all §1–§9 surfaces implemented. The environment track's live
Kind-cluster bring-up remains the one outstanding operational check (declaratively
validated; carried from Phase 1).

## Phase 4 — Workflow & Approvals ◐ (spec: PHASE-4-WORKFLOW-APPROVALS.md)

**Implemented — T2+T3 state machines (§3):**
- `StateMachineDefinition` (`{id, entity, stateField, initial, states, transitions}`
  with terminal flags and optional transition guards) rides the app definition as a
  first-class branch — one machine per entity in v1
- save validation: bound entity resolves, `stateField` is an enum field whose values
  contain every state, `initial` ∈ states, transitions reference known states,
  terminal states admit no outgoing edges, UPPER_SNAKE state names
- publish compilation: transition guards compile through the FlowCompiler's
  expression check (the same JVM engine and record-context policy as every other
  slot); `transitionState` steps compile-check their bound machine + target state
- write-path enforcement (the §3 pin — metadata enforced by the Data Runtime, like
  validations): create pins the initial state (a differing explicit value — or a
  hook-driven one — rejects with `STATE_TRANSITION` 4010); an update changing the
  state field requires a listed transition with a passing guard; terminal states
  freeze the state field only (whole-record freezing is Phase 7's
  `freezeOnTerminal`); nested engine writes (`updateRecord` from hooks) pass the
  same check
- `transitionState` primitive activated: a guarded field write into the record
  before persist — the write path's enforcement validates it, so flows, scripts,
  and humans share one check with no bypass
- error codes 4010 `STATE_TRANSITION` and 4011 `SOD_VIOLATION` joined the registry
  (§2; the seed set is a floor, not a ceiling)
- suites: `StateMachineTests` (§14 item 2 — create-pins-initial, hook-driven
  transitions ride the check, guard failures reject, terminal semantics, nested
  writes) + seven validator rules in `DefinitionValidatorTest`

**Implemented — T1 skeleton + T4 tasks & inbox (§5):**
- `novaforge-workflow-service` (port 8086, gateway route `/api/v1/workflow/**`, its own
  `novaforge_workflow` database, Prometheus scrape): the human-task plane of §5 —
  a pure spine participant that never mutates records
- task model per §5: `{type: approval|todo, entity, recordId, assignee|role, status,
  dueAt/warnAt, createdBy, contextRef}`; statuses OPEN → APPROVED | REJECTED |
  DELEGATED | ESCALATED | CANCELLED
- inbox API: `GET /tasks` (mine = assigned to me or one of my roles, open by default,
  server-side paged), `GET /tasks/{id}`, approve/reject with comment (re-resolution
  409s), claim (role task → assignee, stays OPEN), delegate (replacement task, the
  original DELEGATED, chain rooted via `contextRef`, delegation to the initiating
  actor rejected `SOD_VIOLATION`), reassign (admin/builder-only, audited)
- access enforced server-side (§13): assignee, the task's role holders, or platform
  admin; role resolution rides a new read endpoint on the runtime's admin surface
  (`GET /api/v1/admin/tenants/{t}/users/{u}/roles`) through the trusted service
  client — no cross-service database reads
- `task.*` events (`created/assigned/approved/rejected/delegated/escalated/cancelled`)
  ride a transactional outbox relayed to `novaforge.task` keyed `tenantId:taskId`
  (per-task ordering; delegation chains ride each delegate's own key)
- spine consumption (T1's leg): `record.deleted` on `novaforge.record` cancels the
  record's open tasks — verified through the real Kafka path
- suites: `TaskApiTests` — inbox resolution, resolution + events, claim, delegation
  chains + SoD, reassign gate, access checks, deletion-cancel
- Flowable itself is not yet a dependency: T5's suspension is a durable instance table
  and T6's timers a scanner, so ADR-004's embedded engine now enters with §9 (BPMN
  execution + event-starts — Phase 4 remainder); the Boot 4 compatibility of the
  Flowable starter gets assessed at that boundary (deviation from T1's letter, noted)

**Implemented — T5 `requestApproval` + durable suspension + SoD (§4):**
- the primitive activates: a flow reaching `requestApproval` hands the approval to
  the Workflow Service's internal surface (`/api/v1/workflow/internal/approvals`,
  service-client gated) — step id, after-step pointer, and the optional inline
  `onReject` subgraph (the step's `body`) travel in the payload; execution of that
  flow ends there, so a suspension inside a write-path hook never holds the
  enclosing transaction — the triggering write commits
- durable instances (`wf_suspended_flows`): where the flow resumes, the onReject
  graph, and unanimity bookkeeping — `any` resumes on first resolution, `all` on
  unanimity; siblings after the first win are no-ops (exactly-once resume)
- resolution re-enters the compiled-graph engine through the runtime's internal
  `/api/v1/hooks/resume` (service-client gated, no gateway route): approve
  continues after the step; reject runs the step's own `onReject` subgraph, or the
  instance fails audibly (status FAILED + last_error, never silent); resume
  executes as the per-app system principal against the record's current state,
  with `transitionState` persisting through the standard guarded write on this
  path (no enclosing write carries the field)
- segregation of duties, fail closed (§4): the initiating actor is removed from
  explicit approver sets at suspension (empty remainder → `SOD_VIOLATION` renders
  onto the write path and aborts a beforeSave flow); role-targeted approvals
  enforce the same rule at resolution (the initiator cannot resolve their own
  request); delegation to the initiator rejects
- publish compilation: `requestApproval` params (approvers role/user-list, mode
  any|all) and the onReject subgraph are compile-checked like any graph fragment
- suites: `ApprovalFlowTests` (submit → suspends, write commits SUBMITTED; approve
  resumes to APPROVED; reject routes onReject to REJECTED; SoD aborts the write
  4011; the resume surface is service-client only) + three workflow-side cases
  (suspension→resume exactly once, SoD at resolution and at creation, onReject
  routing)

**Implemented — T6 SLAs & escalation (§6):**
- `SlaDefinition` rides the app definition (`{id, scope{taskType, match}, target,
  warnAt, onBreach{escalateTo, notify}}`); structural save validation (ISO-8601
  target, warnAt ∈ (0,1] or null, task types, role-shaped escalation refs) and
  publish-time compile checks for match expressions over the scope bindings
  (`entity`, `type`)
- resolution at task creation with §6's precedence pinned: a matching definition
  governs (its `onBreach.escalateTo` wins), otherwise the `requestApproval` step's
  own `timeout`/`escalateTo` apply (both now travel the suspension payload); with
  neither, the task carries no `dueAt` — no timer, no escalation, open until
  resolved; `warnAt` defaults to 0.8 on both paths, null disables the warn timer
- the SlaScanner (in-process with the Workflow Service — ARCHITECTURE §2.8 keeps
  escalation timers out of the Scheduler; a scanner pass rather than Flowable
  jobs, the §6 mechanism deviation already logged) warns once per task
  (`sla.warn`), and at breach: the open task goes ESCALATED, a replacement task
  is created for the escalation role (single-level, §6), `task.escalated` +
  `sla.breach` ride the outbox (family topics: `sla.*` → `novaforge.sla`, keyed
  `tenantId:taskId`), and `novaforge.sla.breach` increments
- the workflow relay now derives family topics like the runtime's — `task.*` and
  `sla.*` publish to their own topics
- published SLAs reach the Workflow Service through a read-only metadata consumer
  (service token, 30s TTL cache) — definitions are versioned artifacts, never
  authored at the service
- suites: SLA precedence over the step timeout (PT2H requested, PT1H governs),
  warn-once → breach → escalation replacement with events + counter, second pass
  idempotent; the no-timer default (§6's "no dueAt" case)

**Implemented — T8 Notification v1 (§8):**
- `novaforge-notification-service` (port 8088, gateway route
  `/api/v1/notifications/**`, own database, Prometheus scrape): a pure spine
  consumer — `task.assigned` fans out as `task-assignment`, `sla.warn`/`sla.breach`
  as `sla-warning`
- channels: the platform inbox (paged read, mark-read, own-data only) + SMTP email
  (Mailpit joins compose on 1025/8025); per-user per-category channel toggles
  (both on by default — v1's coarse shape)
- built-in platform templates per category (no authoring surface, §8's pin) with
  `${task.field}` tokens resolved from the event payload; `${record.field}`
  resolution waits for a record-fetching surface (noted — the workflow events carry
  entity/record ids, not record fields)
- recipients: the task's assignee, or the holders of its role through new runtime
  admin reads (`GET .../roles/{role}/users`, `GET .../users/{id}` for the
  synthetic-actor username check)
- synthetic actors have no channels (ADR-010 #3): recognized by their provisioned
  username shape — no inbox row, no email, no `notification.delivered`; the
  triggering `task.*`/`sla.*` events remain the assertable surface
- `notification.delivered` rides its own outbox → `novaforge.notification`
  (tenant-scoped keys)
- suites: fan-out with both channels + token resolution, role-holder resolution +
  preference filtering, synthetic skip, sla.warn delivery through real Kafka

**Implemented — T7 Scheduler (§7):**
- `novaforge-scheduler-service` (port 8087, own database, Prometheus scrape): the
  cron registry — job definitions are `ScheduledJobDefinition` metadata
  (`{name, cron, target, params, enabled}`) riding the app definition, activated on
  publish; the registry is runtime state only (next-fire, leases, run history — the
  job-definitions-vs-registry split), synced from the Metadata Service's published
  surface on an interval (restart-safe upserts)
- exactly one gateway route: read-only `GET /api/v1/scheduler/jobs` (builder
  visibility); no write or admin route exists — administration is publish-driven
- firing: due jobs acquire a lease by atomic conditional update — the distributed
  lock, single-fire under concurrent replicas (§14 item 4); misfire policy fire
  once/skip missed (next-fire advances past `now`, a missed window waits for the
  next tick); every fire records a run row and emits `scheduler.job.run`
  (success/failure) on the outbox → `novaforge.scheduler`, tenant-scoped keys
- targets: `flow` fires the compiled-graph engine through the runtime's new
  internal `/api/v1/hooks/scheduled` surface (service-client gated) — hooks
  addressed by name in the synthetic `scheduled` context, no record, per-app
  system principal; `script`/`processStart`/`report` register but fire as
  `skipped` (dormant) — script awaits the Script Engine's service execution
  context, processStart awaits Flowable, report awaits Phase 5 (ledger note)
- suites: publish-driven sync with upsert republish, fire-exactly-once with run +
  event, misfire skip advancing to the next tick, dormant targets skip with
  reason, read-only tenant-scoped status route

**Implemented — T9 record-level sharing rules (§10, the PHASE-2 §9 remainder):**
- `SharingRuleDefinition` on the PermissionSet branch (versioned, promoted):
  `{entity, type: owner | roleHierarchy | criteria, roles[], ownerField?, criteria?}`;
  roles carry an optional numeric `level` (lower = more senior, §16 Q2's pin)
- save validation: bound entity resolves, type ∈ the closed v1 set, named roles
  exist, hierarchy rules sit on leveled roles, criteria rules require an
  expression; criteria compile at publish (record context, same engine)
- enforcement (a `SharingGate` in the runtime): rules evaluate into the visibility
  governing reads, writes, and deletes alike — `owner` (the creator plus the named
  roles see everything), `roleHierarchy` (a user sees records owned by holders of
  strictly less senior roles, resolved through the platform role store),
  `criteria` (matching records shared with the named roles); lists lower
  owner-sets to `created_by IN (…)` row filters spliced before the page tail,
  criteria post-filter the page (exact for single records; page-level criteria
  filtering may underfill a page — noted); invisible records read as NOT_FOUND on
  get/update/delete (no existence leak); platform admin/builder unrestricted;
  **no rules defined → Phase 2's default holds** — full visibility under the
  matrix, no silent tightening (regression-tested)
- service-level tests (`SharingTests`): the §14 item 5 matrix (own/named-role/
  criteria/admin), list visibility per actor, writes governed by the same
  evaluation, the no-rule default — the matrix's queryRecord-suite form rides the
  harness with T12's live-stack journey (§14 item 5's "via queryRecord-based
  suites" wording)

**Implemented — T11 harness growth (§12):**
- new step ops: `queryRecord {entity, filter, asRole}` → `{count, ids}` in scope as
  `${Query[n]}` (entity `Task` queries the workflow inbox with a v1 `{status}`
  filter, remembering rows as `${Task[n]}` for status/assignee assertions) and
  `resolveTask {recordId | Task reference, action: approve|reject, comment?}` —
  resolution rides the same inbox API synthetic actors use, no back door
  (ADR-010 #3). Both legs share the runtime transport's error-as-result contract,
  so `expect: error(...)` matches problem payloads from either engine; pages cap
  at 200 — `count` is the full total, `ids`/rows the first page
- expected outcomes accept the ADR-010 named forms — any registry name
  (`error(SOD_VIOLATION)`, `error(STATE_TRANSITION)`, …) resolves through
  `PlatformErrorCode` onto its code; query results count as `ok`
- scope semantics: re-observing a record id (an update, a resolved task)
  overwrites the `${Entity[n]}` entry in place — post-step assertions read
  current state, so a resolved `${Task[0]}` shows its post-resolution status
- save validation covers the grown vocabulary: unknown ops reject; record-addressed
  ops (`updateRecord`/`deleteRecord`/`resolveTask`) require `recordId`;
  `deleteRecord` requires `template.version`; `resolveTask` actions are the closed
  approve|reject set; the Task query's filter must be `{status}` (the record DSL
  leaf rejects on save)
- CI coverage: `DefinitionLifecycleTests` (save validation, named-outcome shapes)
  + `TestRunnerJourneyTests` — the runner's real HTTP legs against stub servers:
  the inbox query rides **GET** with `status`/`size=200`, resolutions POST,
  `error(SOD_VIOLATION)` matches a 4011 problem body, `${…}` references in
  filters interpolate before the query is sent, and the id-match scope holds.
  The live-stack §1 journey itself rides T12
- clock-driven SLA suites deferred — an **open deviation from §12's
  clock-advanced leg**: the SlaScanner runs on wall clock in the Workflow
  Service, and the harness (a metadata-service HTTP client) has no as-of scan
  surface to drive; PHASE-3 §7's controlled clock pins assertion-time
  determinism, not cross-service timer control. A scratch-scoped as-of scan
  endpoint would satisfy the spec (scan path, not write path — no ADR-010 #3
  conflict); until it exists, SLA behavior is covered by the workflow-side
  scanner suites

**Phase 4 remainder:** §9 BPMN execution (Flowable embedded, `WorkflowDefinition`
metadata, event-start subscriptions, a live `processStart` target — §1 pins
"execution and event-starts ship"; the scheduler's `processStart` registers but
fires `skipped`), the builder/runtime UI (T10 — rides the unstarted Phase 2 React
surface), and the §1 exit-journey demo (T12 — needs the full stack live; T11's
acceptance form, the §14 item 1 journey suite green through the runner, and §14
item 5's queryRecord-form visibility suites ride it too). All other backend
machinery for the journey — state machines, approvals with suspension and SoD,
SLA escalation, notifications, the scheduler — is implemented and covered by the
service-level suites above.

## Phases 5–8 ⬜

Not started. `requestApproval`/`callConnector` grammar notes live in the specs.
