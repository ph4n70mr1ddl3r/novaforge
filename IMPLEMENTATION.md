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

## Phase 2 — Builder UI & Security ✅ (spec: PHASE-2-UI-BUILDER.md)

**Backend implemented (landed in phases 1–6, unchanged):**
- `expression-dsl` (expr/v1, §7/Annex A): the JVM parser/evaluator with the pinned
  grammar — exact BigDecimal semantics, null-aware operators, date arithmetic,
  membership, the closed function set, injectable clock — plus the shared conformance
  corpus (39 cases) and compile-check wired into the Metadata Service save/publish
  (validations may read the clock; formula fields may not, PHASE-3 §3).
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

**The frontend implemented — the `frontend/` workspace (T1–T10):**
- **T4 TS twin — 100% shared-fixture parity:** `@novaforge/shared`'s expression
  engine (parser/evaluator mirroring the JVM semantics exactly — exact decimals on
  BigInt with a 34-digit banker's context, tagged dates/instants, null-aware
  operators) runs the *same* `expr-v1-corpus.json` the JVM test runs (39/39 in
  both engines; additions ship fixtures first). Browser evaluation is UX sugar —
  the write path evaluates server-side.
- **T2 page model + registry core:** `PageModel` types (§4 — nodes with
  `type/version/props/children/bind/visibility/required/readonly`), the closed
  action ladder, **custom structural deltas** (§13 Q2: insert/remove/move/setProps/
  setSlot/setVersion/setBase/action ops addressing the L1 default's stable node
  keys) with apply/diff round-trip, stale-delta reporting (entity changed → the
  overlay reports instead of corrupting — §11 item 4's regression suite), RFC 6902
  JSON Patch export for interchange, and save/publish validation (props vs the
  component's JSON Schema, bind↔`props.field`/`props.relationship` mismatch
  rejection, version pinning — missing pins resolve at save, reject at publish,
  unknown ids/versions are builder errors + runtime fallbacks).
- **T3 catalog:** the §6 item-3 v1 set — AppShell, NavList, FormLayout, ListLayout,
  RecordHeader, FieldInput, FieldNumber, FieldSelect, FieldSwitch, FieldDate,
  FieldLookup, FieldMultiLookup, FieldRichText, FieldJson (readonly viewer),
  FileUpload (the activated Phase 6 widget), RelatedList, RecordActions,
  EmptyState — alongside the Phase 5 report widgets, all in the lazily-loaded
  version-pinned registry; the gallery test mounts every catalog id with axe
  checks (exhaustive — a new component without an a11y mount fails CI).
- **T5 L1 resolver:** `resolveDefaultPage(entity, kind, role)` — the §5 mapping
  table (21 field types → widgets), list defaults (display field + next 4
  visible-by-role + role-gated RecordActions), detail defaults (group sections),
  role-parameterized re-resolution, formula/rollup/uuid readonly, module-grouped
  nav; golden snapshots per (entity, role) fixture.
- **T6 renderer + runtime shell:** the recursive interpreter (ADR-009 L3 — never
  branches on entity specifics), expression-slot evaluation through the shared
  engine, safe fallbacks for unknown components, server-side paging only (the
  list pages page through the Data Runtime DSL — `frontend/runtime-ui` with
  PKCE OIDC against the deployed realm, locale preference riding the pinned i18n
  fallback chain `label_i18n[locale] → label → apiName`, the Phase 4 approval
  inbox, and Phase 5 dashboards rendering through the catalog widgets).
- **T7/T8 builder shell** (`frontend/builder-ui`): entity builder (identity +
  fields grid + type-specific constraint forms + target pickers over the draft
  APIs), page builder (catalog palette, structural canvas tree, property panel
  auto-generated from props schemas, live preview = the real renderer in preview
  mode, full-snapshot undo/redo, saves persist delta-encoded version-pinned
  pages), RBAC + field-security editors (matrix + tri-state field access), tenant
  onboarding (§10's three-step journey), plus the Phase 5 T6 report builder (live
  bucket-expression compile-checks) and dashboard composer, and the Phase 8
  lifecycle screens (change-set review with suite results + override history,
  gated promote/rollback, suite runs) and i18n translation editor (§7 — the
  missing-translation report over the keyed universe + RFC 4180 CSV round-trip).
- **Page metadata API:** `PUT/DELETE /api/v1/metadata/apps/{id}/pages/{apiName}`
  (the reserved `pages` branch becomes authorable): schema v0 grows the `detail`
  type + typed `layout`; save validation covers identity/type/entity resolution;
  every expression slot in the layout compile-checks against the entity's fields
  at save and publish (encoding-agnostic — deltas or resolved trees);
  `md_pages.revision` (V8) gives pages optimistic locking — stale saves 409
  (`4090`) and the builder's rebase prompt reloads the current draft.
- **Same-origin hosting (§13 Q5):** the gateway serves the built bundles
  (`pnpm package` → `dist/runtime` + `dist/builder`, classpath or `/static`
  volume) with SPA deep-link fallback — asset paths anonymous, APIs still
  scope-gated; Vite dev proxies cover local development.

**Suites:** 108 frontend tests (`pnpm -r test`: shared 82 — conformance 39, deltas,
  goldens, validation, renderer, gallery-axe, client; runtime-ui 5 — nav/auto-list/
  form journeys, inbox, axe, the L1→delta→persist round-trip; builder-ui 21 —
  entity/page/RBAC/onboarding/i18n/reporting journeys incl. the 409 rebase, the
  PHASE-3 §8 logic + suites authoring journeys), plus
  `DefinitionLifecycleTests.pageDefinitionLifecycle` (9 tests, real Postgres) and
  `GatewayApplicationTests` SPA hosting.

**Deviations (honest):** Playwright per-component stories + the scripted E2E golden
  journey (§11 items 2–3) ride the vitest+axe pattern the Phase 5 catalog work
  established (jsdom journeys + axe; browser-runner wiring lands with the live
  Phase 2 exit demo against the compose stack); the page-builder canvas is a
  structural tree editor with palette/canvas/property-panel/preview rather than a
  free-form React-Flow graph (form trees are the v1 page shape; §2's React-Flow
  pin joins when flow-graph authoring — Phase 3's designer — needs it).

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
- spine contracts live in-producer (payload shapes beside each outbox; shared header
  constants in `security-context`) — the PHASE-0 §5.4 `event-schemas` lib charter was
  resolved in place at the Phase 3 review rather than landing a module (extraction
  awaits a second typed cross-service consumer; PHASE-0 §5.4 / PHASE-3 §4 amended,
  root-POM phantom entry removed at the Phase 4 review)
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
  Enterprise-only; trips on two consecutive over-budget samples so same-JVM
  allocation churn can't false-kill a light script — review fix), wall-clock
  backstop, bounded concurrency with a bounded queue —
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

**Spec-review closeout (2026-08-24) — three gaps found reviewing the code against
the spec, all closed:**
- **§4's spine rebind had never landed.** `metadata.published` still rode the
  Phase 1 Redis pub/sub channel (`novaforge.metadata.events`) — publisher and all
  three consumers (runtime cache/materializer, Reporting epoch, Integration epoch)
  — despite T1's acceptance pin ("Redis channel retired"). The publisher now emits
  synchronously on the family topic `novaforge.metadata` keyed `tenantId:appId`
  with the shared header conventions; a broker outage fails the publish audibly
  rather than leaving consumers stale. The runtime subscriber is a `@KafkaListener`
  (group `novaforge-runtime-metadata`); Reporting (`novaforge-reporting-definitions`)
  and Integration (`novaforge-integration-definitions`) swap their Redis listeners
  for spine listeners — same envelope, client-only change, exactly as pinned.
  `DefinitionLifecycleTests` consumes the topic from a throwaway group and pins
  both envelopes per publish; the metadata service no longer carries the Redis
  starter at all.
- **§5's mechanical append-only was convention, not mechanism.** Nothing denied
  UPDATE/DELETE on `audit_events`. Now: a dedicated runtime role
  `novaforge_audit_app` holds INSERT+SELECT and nothing else (V2 migration, granted
  by the database owner Flyway connects as; compose init creates it for fresh
  volumes); the service's pool connects as that role while migrations ride the
  owner credentials (`spring.flyway.user`). `AuditTrailTests.appendOnlyIsMechanical`
  pins it at the database — INSERT/SELECT succeed as the store role, UPDATE/DELETE
  deny with `permission denied` — and every consumer write/read in the suite now
  exercises the restricted role end to end.
- **§8's authoring UI (T8) existed only in the ledger's imagination.** The builder
  had no rule/hook/expression editors and no suite authoring or runner surface.
  Two new screens close it: **Logic** (validation rules as expression+message rows,
  hook rules as trigger + step-list forms over the flow IR with auto-linked
  `next`, formula/roll-up/default-expression slots beside their fields — save-time
  compile feedback surfaces verbatim through the draft APIs) and **Suites**
  (fixture/step/assertion editors over the §7 encoding with the closed op
  vocabulary, run button riding the scratch-tenant runner API, the artifact's
  verdicts rendered beside the editor). Five vitest journeys pin the round-trips;
  the shell grows `logic` and `suites` screens.

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
- Flowable entered with §9 (below) — as the **Flowable 8 line** (8.0.0): ADR-004's
  "Flowable 7" was written against the Boot 3 assumption, and Flowable 7.2 does not
  run on Boot 4; 8.0.0 is the same engine on the Spring Framework 7/Boot 4 line this
  stack pins (the ledger's open compatibility assessment, resolved; ADR-004 and
  PHASE-4 §2 amended in place)

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

**Implemented — §9 BPMN v1 (Flowable 8 embedded, event-starts, processStart):**
- `WorkflowDefinition` rides the app definition (`{id, bpmn, eventStarts[]}` — the
  `<process id>` must equal `id`, the process key): save validation is XXE-hardened
  (DOCTYPE/external entities rejected — authored input), single-`<process>`,
  size-capped; event-start subscriptions use the closed
  `record.created|record.updated` set and bind app entities; filters compile at
  publish through the same JVM engine (record context)
- the embedded engine (Flowable 8.0.0, `flowable-spring-boot-starter-process`)
  lives in the Workflow Service against its own `novaforge_workflow` database
  (ACT_* under `database-schema-update`, Flyway owning only `wf_*`), UUID engine
  ids, async executor on for in-process timers (ARCHITECTURE §2.8 — the Scheduler
  never fires them)
- deploy activation is publish-driven (§7's split): a sync pass deploys by content
  hash (unchanged re-publishes redeploy nothing; changed BPMN deploys a new engine
  version — running instances finish on their own), records failures audibly
  (status + error, retried next pass — the last-known-good deployment keeps
  serving), and removes deployments whose definitions left the published app
  (cascading instances, cancelling the tasks those instances own)
- **the v1 user-task gate** at deploy: every `userTask` carries a literal
  `flowable:assignee` (user UUID) or literal `flowable:candidateGroups` (role
  names — qualified with the owning app, the platform's app-scoped role identity);
  expressions are not evaluated in v1 and fail the gate loudly at deploy, never
  silently at runtime
- **event-starts over the real spine**: `record.created/updated` events evaluate
  subscriptions (entity match against the app-qualified spine key); spine events
  carry the envelope only, so filters evaluate against the record's current state
  fetched through a new internal system-principal read on the runtime
  (`GET /api/v1/hooks/records/{id}`, service-client gated — a read, never a
  mutation, ADR-004 #2; gone records skip); starts dedupe on the spine event id
  with the claim riding the same transaction as the engine start (at-least-once
  redelivery collapses; distinct events each start)
- **user tasks join the §5 inbox**: TASK_CREATED of a deployed workflow bridges
  into `wf_tasks` (type `todo`) through the same task service — SLA resolution and
  `task.*` events ride the existing path — linked by `wf_process_tasks`; inbox
  approve/reject completes the engine task (a `resolution` variable routes the
  process's own gateways), claim/reassign mirror the assignee into the engine,
  record-deletion completes the engine task with `CANCELLED` so the process never
  hangs, engine-side termination cancels still-open rows; delegation of
  process-managed tasks rejects explicitly in v1 (Flowable's single-task model
  does not map to §5's replacement-task chains — reassign instead)
- **the Scheduler's `processStart` target activates**: params
  `{process, recordId?, variables?}` fire through the Workflow Service's
  service-client-gated internal start surface
  (`POST /api/v1/workflow/internal/processes/start`); undeployed processes reject
  audibly and land as failed runs
- suites: `BpmnProcessTests` (12 cases, real engine + Kafka) — deploy idempotency
  and versioning, the user-task gate, event-start matching/filter-skip/redelivery
  collapse, the inbox bridge (approve ends the process through history, claim
  mirrors, delegation rejects), record-deletion cancellation, in-engine timers on
  the async executor, removal cascading, the internal-start gate, and
  deploy-failure recovery; `DefinitionValidatorTest` grew the §9 rule matrix
  (process-key id, XML well-formedness, DOCTYPE rejection, single-`<process>`,
  closed event set, entity binding); `DefinitionLifecycleTests` covers the API
  save/publish path; `ApprovalFlowTests` covers the internal record read
  (service-client gate, raw fields, gone-record 404); `SchedulerTests` covers the
  processStart target firing (ok + failed runs)

**Fixed with §9 — the Phase-4 branch persistence gap:** state machines, SLAs,
scheduled jobs (and now workflows) had no persistence path in the Metadata
Service — `assembleApp`/`mergeApp`/`withIds` dropped every branch, so drafts and
published bundles never carried them downstream (the runtime's state-machine
enforcement, the SLA/workflow sources, and the Scheduler's jobs all read empty
lists outside stub-fed tests). The fix: `md_definitions` (kind-discriminated
documents, the md_pages pattern) with insert/replace on app create/update, full
branch assembly on every read, and merge semantics that never wipe branches on a
scalar PATCH; `DefinitionLifecycleTests` pins the round-trip of all four branches
through create → publish → published read (the regression).

**Fixed with §9 — the review pass:** three defects found reviewing the landing.
(1) The deploy sync's skip condition now also requires a non-null engine
`deployment_id`: the scheduled path reaches `syncOnce()` unproxied, so the
registry row and the engine deploy are not one transaction, and a crash between
them used to leave a DEPLOYED row that every later sync skipped forever (the row
now redeploys). (2) A literal non-duration `flowable:dueDate` (an ISO datetime)
no longer throws out of the bridge's task-created listener and rolls back the
process transaction — it parses as a step timer when it is an ISO duration and
carries no timer otherwise. (3) The record-event consumer distinguishes envelope
shape from processing: malformed envelopes are ignored terminally, but a
processing failure (runtime down, lock timeout) propagates so Kafka redelivers —
the §9 dedupe claim collapses any double-start, so at-least-once redelivery is
safe where the old blanket catch silently lost event-starts. ARCHITECTURE.md
§2.6's "Flowable 7" pin amended to the 8 line with the same note as ADR-004.

**Fixed at review — one trusted-service gate:** the five identical
`requireServiceClient` copies across the runtime's internal surfaces and the
workflow's suspension/process-start controllers collapsed into a single shared
`ServiceClientGate` in `security-context` (one client id, one rule, one
implementation; per-surface rejection wording preserved verbatim), with unit
tests for the accept/reject/anonymous/no-auth cases.

**Phase 4 remainder:** the §1 exit-journey demo (T12 — needs the full stack live;
the §14 item 1 journey suite through the runner rides it too). T10's runtime UI
landed with the Phase 2 builder/runtime shells: the approval inbox
(`frontend/runtime-ui` — my tasks with server-side paging, approve/reject with
comment) rides the §5 inbox API, and state-machine transitions render as record
actions from published machine metadata. All backend machinery for the journey —
state machines, approvals with suspension and SoD, SLA escalation, notifications,
the scheduler, BPMN execution with event-starts — is implemented and covered by the
service-level suites above.

## Phase 5 — Reporting & Dashboards ◐ (spec: PHASE-5-REPORTING.md)

**Backend surfaces implemented (T1–T4, T7, T8); T5's catalog components landed with
Phase 5 itself, and T6's report builder + dashboard composer now ride the Phase 2
builder shell (`frontend/builder-ui`: the §3 guided form with live bucket-expression
compile-checks through the shared TS engine, and the §5 widget grid with
report-ref binding + role-visibility composition — both saving through the metadata
app patch). The §12 performance validation + §1 exit demo still need the live stack.**

**Implemented — the expression SQL lowering (§3's compile surface):**
- `ExpressionSql` (`expression-dsl`): the grammar's second execution surface — compiles
  an authored expression to a boolean SQL fragment over the record's columns with
  ordered bind params and a null-aware type lattice. Deliberately partial: every
  construct whose SQL and JVM semantics could diverge rejects loudly (`round` —
  half-even vs half-up; `min`/`max` — NULL handling; `size`/collections; method
  calls), and the parity that *is* lowered is pinned construct-by-construct
  (`== null` → `IS NULL`, `!=` value → `IS DISTINCT FROM`, date arithmetic per Annex A
  over `CAST(… AS date)`, booleans in the canonical `'true'/'false'` text form,
  division guarded by `NULLIF`)
- `ExpressionFields` (`metadata-model`): the promotion-aware field resolver shared by
  the publish-time compile gate and the Data Runtime's aggregate pipeline — promoted
  fields lower to their projection columns, everything else to the JSONB extract with
  numeric casts; json/file/collections never lower
- `PromotionPolicy`/`Snake` moved from `data-runtime/storage` into `metadata-model`:
  promotion is a pure function of the published definition with three consumers
  (materializer DDL, query lowering, save-time validation) that must never disagree
- suites: `ExpressionSqlTest` (15 — bind order, null semantics, date arithmetic,
  parity rejects, type mismatches)

**Implemented — T2 report/dashboard metadata (§3/§5):**
- `ReportDefinition` (`{id, entity, filters, groupBy (+buckets), aggregates,
  drillThrough}`) and `DashboardDefinition` (`{id, widgets[{widget, reportRef,
  params, span}], roles}`) as first-class app branches: `md_definitions` kind rows
  (V5 migration), full branch assembly on every read, PATCH-merge semantics that
  never wipe branches — the Phase 4 §9 branch-persistence pattern extended
- save validation (§3): bound entity resolves; filter ops from the query-DSL leaf
  set with per-op shape rules (`contains` textual, `isNull` valueless, `in` list);
  aggregate fields numeric; **group-by and aggregate fields projection-promoted or
  the definition rejects with guidance** (reporting rides the §4 materialized path —
  the fix reads "add the field to an index"); bucket labels non-empty and unique;
  aggregate aliases unique; drill-through binds to an app entity; dashboards: widget
  types from `{kpi, chart, table}`, reportRefs resolve, spans 1..12, visibility roles
  resolve (composition only — §8)
- publish compilation: every bucket expression compiles twice — the JVM engine
  (record context, clock admissible) **and** the SQL lowering (an authored `round()`
  or method call is a publish error, never a run-time surprise)
- `ObjectPermission.reportExecute` grows the matrix beyond CRUD: the object-level
  `report: execute` grant — default deny until an app grants it (§8)
- scheduled jobs gained their save-time rules (the Phase 4 gap): unique names, cron
  structurally shaped (5–6 fields — the Scheduler's parser stays authoritative),
  per-target params; report jobs pin `reportId`, `runAsRole` (default the app's
  `reporting` role — must resolve), `format ∈ {csv, xlsx}`, and at least one
  resolvable recipient role/user (a job that fires but reaches nobody is a save
  error, not a silent no-op)
- suites: `DefinitionValidatorTest` +the §3/§5/§7 rule matrix (valid A/R app saves
  clean; unpromoted/non-numeric/dup-label/ghost-ref cases reject with the guidance)

**Implemented — the aggregate pipeline growth (§3/§4, in the Data Runtime):**
- bucketed group-by lowers to ordered `CASE WHEN` branches **inside the pipeline** —
  first match wins, unmatched rows land in no bucket (NULL), labels bind as params,
  and `today()` binds the run's governing date (a query `asOf` — a suite's frozen
  clock — pins deterministic buckets)
- `GROUP BY` addresses select ordinals (a repeated CASE would rebind every
  parameter; the goldens re-pinned)
- **sharing-rule row filters apply to aggregates exactly as to lists (§4):** owner
  sets lower to `created_by IN (…)`, criteria expressions lower through the same
  `ExpressionSql` into one OR'd predicate; a criterion that cannot lower fails
  closed (FORBIDDEN) rather than widening the dataset
- hidden fields fail closed on aggregates — a grouped or aggregated hidden field
  rejects (aggregates leak values, not rows)
- suites: `ReportAggregateTests` (6 — golden aging corpus decimal-exact with the
  30/60 bucket edges and the null-due row unbucketed, filters on aggregates,
  owner/criteria sharing bounds, hidden-field fail-closed, and the role-scoped
  internal surface incl. its service-client gate), `BucketedAggregateSqlTests`
  (3 — the CASE goldens), `GoldenSqlTests` re-pinned

**Implemented — T1/T3/T4 the Reporting Service (§2/§4/§6):**
- `novaforge-reporting-service` (port 8089, gateway route `/api/v1/reports/**`,
  Prometheus scrape + the Grafana baseline row; stateless by design — no per-service
  DB): definitions through the Metadata Service's published read (in-process cache
  keyed to a per-tenant epoch bumped on every `metadata.published`), results in Redis
- `POST /api/v1/reports/{id}/run`: the object-level `report: execute` grant decides
  first (default deny; platform admin/builder stay unrestricted), then the compiled
  envelope executes **as the requesting actor** — the caller's token relays to the
  runtime's public query surface, so matrix, field security, and sharing apply per
  run with no reporting-side reimplementation; response `{columns, rows, totals,
  chart}` with the ECharts-shaped projection
- `ReportCompiler`: filters merge (params tighten — a param naming a filtered field
  replaces its value, a new field appends), buckets/aggregates compile to the
  envelope, the un-grouped totals twin stays a valid envelope for every legal shape;
  `asOf` rides through
- result cache: keyed (tenant, app, report, version, actor, **evaluation date**,
  params) — role set alone is never a key (owner-based sharing differs between
  same-role users), a day boundary never serves yesterday's buckets, a Redis hiccup
  degrades to the uncached path; 60 s TTL. A latency tool, never an authorization
  boundary
- `GET /api/v1/reports/{id}/export`: synchronous CSV (RFC 4180) and XLSX (POI)
  streams under the run's exact authorization, money columns locale-formatted with
  the configured currency, totals row closing the file, **10k-row sync cap**
  rejecting with async-export (File Service, Phase 6) guidance
- suites: `ReportCompilerTests` (5), `ReportExporterTests` (4 — quoting, money,
  cap-with-guidance, CSV/XLSX parity parsed cell-for-cell)

**Implemented — T7 scheduled delivery (§7):**
- the Scheduler's dormant `report` target activates: fires the Reporting Service's
  internal delivery surface with the platform service client; failures propagate so
  the run history records them audibly
- the run executes under the job's `runAsRole` through the runtime's internal
  role-scoped surface (`/api/v1/hooks/reports/query`, service-client gated):
  `asRole`'s entity READ, field security, and sharing rules bound the dataset —
  never system-principal-everything; an ungranted role fails closed exactly like an
  ungranted actor
- delivery through the Notification Service's new internal send surface
  (`/api/v1/notifications/internal/send`): platform roles resolve to holders,
  explicit users ride verbatim (deduped, order-stable), the same preference
  filtering + inbox row + `notification.delivered` audit per channel, the export
  streaming inline as an email attachment (no File Service dependency), the
  built-in `report-delivery` category (V2 migration widens the CHECK); synthetic
  actors skip; recipients resolving to nobody reject audibly
- suites: `NotificationTests` +2 (role-holders + explicit users with attachment,
  the service-client gate, no-recipient rejection)

**Implemented — T8 harness growth (§9):**
- `runReport {reportId (the step's entity), params, asRole}` rides the public run
  surface as the step's actor (the candidate app is published in the scratch tenant,
  so `report: execute` and sharing apply per run); the result lands in scope as the
  next `${Report[n]}` carrying `{rowCount, totals}` — the §7 A/R-vs-ledger
  reconciliation assertions read exactly those
- `TestRunnerJourneyTests` grew the leg: POST-only transport, the app bound from the
  candidate, params interpolated, `rowCount`/`totals.sum_amount` assertions

**Implemented — T5 catalog components (§5, the frontend slice):**
- the pnpm workspace scaffolded at `frontend/` (PHASE-2 §2's T1 stack pulled forward —
  catalog work is frontend-only per the §11 dependency note): React 19.2 + TS strict +
  vitest/jsdom, `frontend/shared` carrying the component registry
- the four versioned catalog components per ADR-009 L3: `ChartWidget` (binds the §4
  chart projection to ECharts through a lazily-loaded canvas wrapper — role=img +
  aria-label over the chart region), `KpiTile` (a totals aggregate as a labeled
  headline; money strings ride verbatim, the browser never re-rounds the server's
  BigDecimal), `ReportTable` (grouped rows with the totals twin as the tfoot row,
  unmatched buckets as em-dashes), `DashboardGrid`/`DashboardCell` (12-column grid,
  span-clamped, one-column reflow under 720 px)
- the catalog contract: draft-2020-12 props schemas per component, `CATALOG` manifest
  with pinned versions, and the lazy `resolveComponent(id, version)` registry —
  version mismatches and unknown ids reject loudly (ADR-009's "pages pin component
  versions" from day one)
- design tokens as DTCG-derived CSS variables (ADR-009 §5) — components consume
  variables only, no literals
- suites: 17 vitest tests — the gallery (every component axe-checked incl. the grid
  composite), registry pinning, ChartWidget's projection→option wiring (echarts
  mocked; real canvas is the Phase 2 Playwright layer), ReportTable/KpiTile shape
  over the live arAging fixture; CI gains the `frontend` job (pnpm + strict tsc +
  vitest) gating the Podman leg

**Implemented — §12 performance validation** (measured 2026-08-23, record:
`docs/loadtests/results-2026-08-23-report-perf.md`): 1M-row `PerfInvoice` fixture
(generate_series into the trigger-maintained projection, the ADR-001 methodology),
the bucketed aging report through the full gateway→Reporting→Runtime journey —
**cache cold p95 1379.9 ms / warm p95 132.6 ms, both PASS (< 2 s)**; the driver
(`docs/loadtests/report-perf.py`) deletes the Redis result keys per cold iteration,
so the cache is proven a latency tool, not a load-bearing dependency.

**Implemented — §10 item 4 + T1/§1 exit demo** (verified live 2026-08-23, same
record): compose infra + the six services; the A/R app (`Invoice` + `arAging` +
`exec` dashboard + `nightlyAging` job) authored and published through the definition
APIs; the report run decimal-exact (bucket edges 30/60, DRAFT excluded, totals
365.75) with the chart projection; CSV + XLSX exports verified; the scheduled
delivery fired `ok` → inbox row + email leg → **Mailpit shows the report email with
`arAging.xlsx` (3657 bytes) attached** and `notification.delivered` audits per
channel; the job republished to its authored nightly cron moved `next_fire_at` on
the next sync (the registry upsert, live).

**Fixed at the live review — two integration defects the demo surfaced:**
(1) the `published-apps` service-caller index omitted `apiName` — every
service-consumer sync (Scheduler jobs, workflow processes/SLAs, Reporting
definitions) keyed off a null/empty app: the workflow source silently skipped apps,
the scheduler synced `app=null` rows that fired into 404s; the index now always
carries `apiName` (store.apiNameOf). (2) the scheduler's registry never pruned
vanished definitions — an orphaned row (here the null-app one) fired and failed
forever; `syncOnce` now prunes to the published set, outage-safe (an empty listing
never wipes the registry — a metadata outage must not, worse, disarm every job).
Both pinned by `SchedulerTests` (prune + outage-safety, now 8 cases). Operational
note: pre-Phase-4 compose volumes predate the init script's growth — the
workflow/notification/scheduler databases are created for fresh volumes, existing
ones need the three `CREATE DATABASE`s once (documented in the results record).

**Fixed at the post-phase review — one service-token client, three smaller defects:**
the client-credentials grant had been hand-rolled twelve times across five services
(each its own cache, each its own RestClient) — eleven of the copies concatenated the
raw secret into the form body, so a secret carrying `&`/`=` corrupted the grant (only
the harness's TestRunner encoded). All twelve collapsed into one shared
`ServiceTokenClient` in `security-context` (the outbound twin of the Phase 4
`ServiceClientGate` consolidation): form-encoded credentials, one cached grant per
JVM, 30 s-early refresh, 2 s/10 s timeouts — bound by library auto-configuration so
no service wires it (the reporting service's local `ServiceToken` component went the
same way; the metadata-service harness keeps its own correctly-encoded uncached
fetch, and the runtime's `KeycloakUserProvisioner`/`RestMetadataClient` ride a
distinct MultiValueMap variant that was already encoding-correct — left alone).
`ServiceTokenClientTest` pins encoding, caching, and the loud no-token failure.
Also: a malformed `params` query value on the export rendered 500 — the advice now
maps `JacksonException` (malformed text, not just mapping) to 400 VALIDATION_FAILED
(`ProblemAdviceTests`); the XLSX export called `autoSizeColumn` while only the header
existed (a no-op in practice) and POI autosizing walks AWT fonts — sizing now runs
after the data and degrades to a fixed width on a headless font failure instead of
losing the export; and the `ReportTable`'s dead `typeof` ternary went.

**Phase 5 remainder:** T6's report builder/dashboard composer authoring UI (rides
the Phase 2 builder shell — the workspace now exists, the shell does not yet);
chart/export polish beyond the catalog contract is backlog until the dogfood asks.

## Phase 6 — Integration Layer ◐ (spec: PHASE-6-INTEGRATION.md)

**Status:** T1–T9 landed and suite-green; T10 (the live exit walkthrough — Stripe/bank feed → Payments visible in reports against the running stack) remains the exit-review leg, per the task table's own definition.

**Fixed at the 2026-08-24 spec review — the secrets package was never committed.**
`.gitignore`'s broad `secrets/` scratch pattern had silently excluded
`integration/secrets/` (`SecretStore`, `SecretCipher`) and `reports/
ReportExportClient` from every commit — fresh checkouts did not compile, while
trees that predated the refactor built fine and hid it. The patterns are now
anchored to the repo root (`/secrets/`, `/reports/`) and the sources are tracked;
`SecretStore` was rewritten to the versioned `it_secrets` schema its callers,
controller, and migration already coded against (each `put` lands a new active
version so a rotation window verifies old + new, `retireEarlierVersions` closes
it back to exactly one, `sha256` keys the inbound replay nonces) — the §9
rotation semantics the suites pin, restored to a compiling tree.

**Implemented — T1 the Integration Service (§2):**
- `novaforge-integration-service` (port 8090, gateway routes `/api/v1/integrations/**` and the one deliberately anonymous `/api/v1/webhooks/inbound/**`), its own `novaforge_integration` database on the shared Postgres (delivery log, DLQ, replay nonces, job ledger + per-row outcomes, spine outbox), Prometheus scrape + Helm chart + Skaffold artifact
- spine contracts in-producer (the PHASE-3 §4 charter): `connector.delivered`, `webhook.dispatched`, `import.progress` — `import.*` keyed `tenant_id:job_id` (the job is the family's record; per-record ordering holds and the progress UI rides it, §7), `connector.*`/`webhook.*` tenant-scoped; the audit service consumes all three families so deliveries land in the append-only trail (§3)
- `common-core`: `SIGNATURE_INVALID("4012", 401)` — the HMAC failure both directions render

**Implemented — T2 the secret store (§9):**
- AES-GCM at rest under a data key held in KMS/Vault in staging/prod (a compose-provided key locally); `CredentialDefinition`/`WebhookDefinition` carry references only — the schema cannot express the secret, so metadata exports never contain it (by construction, pinned by the validator's kind-shape rules)
- rotation keeps two active secrets per reference (§9): verification tries every active version, retirement flips back to exactly one — the §11 item-1 rotation leg pins it

**Implemented — T3 the connector framework (§3):**
- `ConnectorExecutor`: REST executor with the shared `${…}` mapping convention (ADR-008 — path/query/header/body templates interpolate from the call context, deep-resolved for body maps), the v1 auth set (API-key header, HTTP basic, OAuth2 client-credentials with per-credential token caching — §13 Q1's resolved scope), Resilience4j circuit breaker per (tenant, connector) + bounded retries with exponential backoff, and the §4-pinned 10 s synchronous timeout
- every delivery idempotent: a dedupe key (the provider event/call id) returns the recorded outcome, never a second call; terminal failure parks the request in the DLQ with the payload preserved; every outcome emits `connector.delivered` and lands in audit
- runs are builder-authored metadata (§3): `ConnectorDefinition`/`WebhookDefinition`/`CredentialDefinition`/`ImportDefinition` live on the app definition's Integrations branch (kind-discriminated `md_definitions` rows, V6) with the same save-time schema/reference checking as the Phase 3 editors — ops/credential kinds, operation methods/paths, webhook direction shapes, import keyFields — and publish compiles `callConnector` steps (connector + operation resolve, templates resolve against the step context) and outbound webhook filter expressions

**Implemented — T4 `callConnector` + the `$http` sandbox (§4):**
- the last dormant primitive activates in the flow engine: a synchronous, bounded call through the Integration Service's service-gated execution surface; failure rides the unchanged §2 policy (before-hooks abort — pinned by test; after-hook failures ride the spine's retry leg — pinned by test); the step's record-scoped dedupe key makes after-hook retries collapse onto the recorded delivery instead of double-calling the provider
- `$http` turns on only for scripts whose artifact declares the connector sandbox context (`ScriptDefinition.sandbox: "connector"`): the binding exists only when declared (a `ReferenceError` otherwise — pinned by test), and routes through the same executor — circuit breaker, credentials, timeout — never raw sockets (`IOAccess.NONE` holds)

**Implemented — T5/T6 webhooks, both directions, one HMAC scheme (§5/§6):**
- pinned scheme: hex HMAC-SHA256 over `timestamp + "." + raw body`, `X-NovaForge-Timestamp`/`X-NovaForge-Signature`, ±5-minute window; inbound replay nonces (a seen signature inside the window rejects) and provider-event-id idempotency (body hash when absent)
- outbound: a pure spine consumer over every family topic — each enabled webhook's filter expression evaluates against the envelope (`event`, `entityId`, `recordId`, `actorId` + payload bindings); matches deliver signed with bounded retries to a terminal DLQ; delivery log per attempt (status, latency, response code) surfaced beside the editors
- inbound: the anonymous route authenticates by the same scheme (wrong secret / stale timestamp / mangled or replayed signature → `SIGNATURE_INVALID`, indistinguishable); the mapping produces create/upsert payloads applied through the Data Runtime's single write path **as the per-app integration principal** (`integration:<app>` — a distinct principal from the engine's system principal, so audit provenance separates integration-sourced writes): validations, state machines, and hooks all fire (pinned by test — a webhook cannot smuggle a bad record); poison messages DLQ with the payload preserved and replay from the builder re-applies exactly-once
- the gateway lifts its default JWT requirement for exactly the webhook prefix and rate-limits it from its first day (Redis fixed-window per client, PHASE-0 §6.1's deferral activating) — failing open only on a limiter outage (HMAC still gates every call)

**Implemented — T7 the File Service (§8):**
- `novaforge-file-service` (port 8091, route `/api/v1/files/**`, own database, MinIO joins the compose stack with a persistent volume): presigned upload/download (pinned 15-minute expiry, recorded server-side per grant), the attachment metadata entity (`fileId, entity, recordId, fileName, contentType, size, checksum, virusScan: pending|clean|infected|skipped`), server-side checksum verification over the stored bytes (mismatch rejects and deletes — pinned by test)
- the config-gated ClamAV hook (§13 Q2): clamd INSTREAM binding when on; EICAR quarantines — download blocked, `file.quarantined` rides the spine to audit (pinned by test with the gate on; CI runs one config-on job so the scanning path stays tested); a skipped gate records `skipped`, never `clean`
- the `file` field type's upload path: values are attachment ids; the catalog's `novaforge.file-upload` component activates (the PHASE-2 §5 stub, riding the real grant → PUT → complete-with-checksum flow, axe-clean); attachment access for bound records rides the owning record's authorization (the caller's token relays to the runtime's read)

**Implemented — T8 async, resumable import/export (§7):**
- `ImportDefinition` is promoted metadata; import *runs* are tenant data: the file lands via presigned upload, the run chunk-processes through the runtime's internal batch surface as the integration principal (per-item outcomes, field-scoped verdicts riding each), checkpointed — a killed run restarts from its last checkpoint and the per-row ledger skips every settled row, so a row applies exactly once (pinned by test: kill at chunk two → resume → all rows applied exactly once, ledger complete)
- entity export jobs page the dataset under the job's pinned `runAsRole` (the scheduled-report scope, PHASE-5 §7 — an explicitly permissioned role, never system-principal-everything) and stream to the File Service; report exports ride the Reporting Service's new internal role-scoped render leg
- PHASE-5 §6's designed handoff activates: over-cap sync exports answer **202 + the async job link** (Location header) instead of the cap error — pinned by test; progress rides `import.progress`; completion notifies the initiating user through the built-in `job-completed` category (the Notification Service's V3 migration — PHASE-4 §8's growth path, `report-delivery` was the first); every job audited with per-item outcomes retained

**Implemented — T9 harness growth (§10):**
- `postWebhook { hookId, body, headers? }`: the runner provisions the scratch tenant's hook secret through the builder surface and signs the §5 scheme itself, so suites exercise the real HMAC path — header overrides cover the deliberately-mangled/stale legs (`expect: error(SIGNATURE_INVALID)`); the applied record lands in scope as `${Entity[n]}` for assertions
- the harness-provided mock connector: an in-process stub server binds every connector's baseUrl before the candidate publishes (hit counts + last path ride the run artifact) — the bank-feed journey runs offline; `runReport` joins `Step.OPS` (the Phase 5 gap this phase's growth surfaced — the runner knew the op, the save validator didn't)

**Suites (§11):** `IntegrationWebhookTests` (7 — the full HMAC matrix incl. rotation + replay, idempotent application, outbound sign/filter/retry-to-DLQ/replay-exactly-once, poison DLQ with the write path's own verdict), `ConnectorExecutorTests` (4 — mock journey w/ credential + template legs, dedupe-collapse, terminal-failure DLQ, unknown-operation rejection), `ImportResumeTests` (kill/resume exactly-once), `IntegrationFlowTests` (+4 in the runtime — before-hook abort, after-hook spine retry, mock connector journey, and the integration write path firing validations/state machines/hooks with per-item field-scoped verdicts), `FileServiceTests` (5 — checksum verify/mismatch+delete, pinned presign expiry, EICAR quarantine + blocked download + outboxed event, internal upload leg), `ScriptApiTests` +$http gating, `AsyncExportHandoffTests` (202 + job link), `WebhookRateLimitFilterTest` (window enforcement, scoping, fail-open), `TestRunnerJourneyTests` +webhook journey (provisioning shape + signature equality over the raw body), frontend gallery/registry/FileUpload (+2, 20 total)

## Phase 7 — ERP Dogfood ◐ (spec: PHASE-7-ERP-DOGFOOD.md)

> The platform harvests (§3 — the only platform work the spec expects) are complete
> and suite-green; the ERP app ships as authored metadata with its acceptance
> suites and the binding gap log. The T12 live-stack walkthrough (the §1 exit
> demo against the running compose stack — the same leg Phases 5/6 exercised) and
> the builder-UI authoring surface (G-7, the tracked Phase 2 remainder) are the
> remaining legs. The §9 suites are authored as the contract; their live
> execution rides the full stack (as the Phase 5/6 exits did).

**Implemented — T3 the two anticipated harvests (§3), confirmed by the dogfood:**
- **`freezeOnTerminal` (§3.1)**: an `EntityDefinition` attribute (requiring a bound
  machine with a terminal state — save-validated) — a record in a terminal state is
  an immutable document: field updates and deletes reject with `RECORD_FROZEN`
  (4013), master-detail child writes into it reject identically (direct child
  create/update/delete naming the frozen parent through any lookup field targeting
  a freeze-bound entity, and inline child arrays on a PATCH) — the freeze covers the
  parent's whole document, and the check runs before roll-up evaluation so a child
  write never recomputes a frozen parent (§3.1's ordering pin). Enforced on every
  write path: user, integration-principal, and nested engine (hook) writes
- **`PeriodLock` (§3.2)**: an `EntityDefinition.periodLock` binding
  `{entity, dateField, from/to/statusField, closedStatus}` — a dated write resolves
  its period by date-range lookup (the resolved §8 pin — documents carry dates, not
  period pointers; re-dating re-resolves under the same gate); a date inside a
  `closedStatus` period rejects with `PERIOD_LOCKED` (4014). The status value is
  app metadata (`CLOSED` is the `AccountingPeriod` machine's state, §4) — the
  platform reads configuration, never special-cases an app's enum. Runs after the
  beforeSave hooks (like the state-machine check) so hook-dated writes ride the
  gate; no period rows → no locks; reopen (§4's audited transition) deactivates the
  lock and nothing is ever un-frozen — corrections inside a reopened period are
  reversal entries
- error codes 4013/4014 joined the registry (the PHASE-0 §5.2 floor grows per phase)
- both features flag-gated per definition (attributes absent → zero behavior
  change; §10 T3's "features flag-gated" satisfied by authoring, not deployment
  flags — an unupgraded app never sees the codes)
- suites: `FreezePeriodTests` (4 — §9 items 2/3 over the real Postgres write path:
  posted-document immutability incl. inline arrays + delete, child writes naming
  the frozen parent (create/update/delete), reversal-entries-post, closed-period
  rejection + boundary dates + reopen deactivation) + six `DefinitionValidatorTest`
  rules (machine-bound freeze, terminal-state presence, period entity resolution,
  dateField typing, range-field typing, closedStatus ∈ enum)

**Implemented — the ERP app as metadata (§2, `apps/erp/`):**
- `erp-app.json`: GL (`Account` hierarchical, `JournalEntry`/`JournalLine` with the
  balanced validation + gapless `JE-` sequence defaults + `DRAFT→SUBMITTED→POSTED`
  with `freezeOnTerminal` + `PeriodLock`), AR (`Customer`, `Invoice` + formula-priced
  lines + `total` roll-up + gapless `INV-`, machine + freeze — the invoice, not its
  journal, so settlement decrements `amountOutstanding` per §2's pin; `Payment` as
  the bank-feed webhook target), Inventory (`Item` with roll-up-maintained
  `qtyOnHand`/`inventoryValue` — the running weighted average is exactly
  value/qty; `StockLedger` append-only with terminal `POSTED` + freeze), periods
  (`OPEN→CLOSING→CLOSED` with the audited `CLOSED→OPEN` reopen edge), reports
  (trial balance, bucketed A/R aging, inventory valuation), the `exec` dashboard,
  the `nightlyAging` scheduled delivery under the `reporting` role, roles + the
  object matrix (arClerk/accountingManager/controller/inventoryClerk/reporting),
  and the bankFeed connector + paymentsFeed inbound webhook (T8's wiring)
- posting = the §5 shape: `afterSave` flows — branch → `requestApproval` (role
  `accountingManager`, SoD fail-closed; rejection publishes `journal.rejected`/
  `invoice.rejected` on the spine) → `transitionState` to POSTED through the
  durable-suspension resume leg; zero scripts on the posting path
- the one budgeted script (`costMovement`, beforeSave on `StockLedger`) — §5's
  canonical escape-hatch case: issues cost at the running weighted average read
  from the item's roll-ups, receipts stamp their extended value; script ratio ≤ 20%
  holds (1 script of 3 hooks, the rest of the logic surface declarative — §9 item 7
  reported in the gap log)
- the acceptance-contract suites (`apps/erp/suites/`): `reconciliation` (§9 items
  1/5: book → approval → POSTED → trial balance nets zero → aging reconciles →
  TB debits == credits == 120.0000, aging outstanding == 120.0000; preparer cannot
  approve own — `error(SOD_VIOLATION)`), `controls` (§9 items 2/3: posted-entry
  PATCH/delete/child-create all `error(RECORD_FROZEN)`, closed-period create
  `error(PERIOD_LOCKED)`, reopen admits new dated writes), `inventoryCosting`
  (§9 item 4: receipt 10×5 → issue 4 at average — `unitCost == 5.0000`, qty 6,
  value 30.0000 decimal-exact)
- `apps/erp/GAP-LOG.md` — the binding rule-2 deliverable: 9 numbered gaps logged
  BEFORE their workarounds, each with proposed primitive/flag + priority +
  disposition (2 accept-as-platform-feature: created-record id capture + nested
  template resolution for auto-journals; a `$decimal` sandbox binding for script
  money fidelity), plus the two confirmed §3 harvests
- `ErpAppArtifactTests` (7) gates the artifact in CI through the exact save/compile
  checks the builder would run (save-clean, FlowCompiler, harvests bound, gapless
  sequences, script budget, suite save-validation, ${…} reference shape) — it
  caught two real authoring defects on landing (unpromoted report aggregate field;
  deleteRecord's dynamic-version rejection)

**Fixed while landing (found by the artifact gate + suites):**
- `TestRunner.publishCandidate` published a REDUCED candidate into scratch tenants
  (entities/settings/permissions/integrations only) — state machines, reports,
  dashboards, jobs, SLAs, and workflows silently dropped, so any suite pinning
  machine-enforced behavior ran against an unguarded write path; the candidate now
  carries every branch
- suite save-validation rejected `deleteRecord` templates whose `version` is a
  `${…}` reference (only literal numbers passed) though the runner resolves them
  exactly like every other slot — references now accepted
- `MetadataStore.insertApp` silently dropped inline `testSuites` (only the dedicated
  PUT endpoint persisted them) — inline suites now round-trip like entities

## Phase 8 — Lifecycle & Hardening ◐ (spec: PHASE-8-LIFECYCLE.md)

> T1–T7 implemented and suite-green (the code surface: environments, the gate,
  rollback, change sets, artifacts, headless runs, templates, i18n). T8–T10 are the
> operational exercises: the runbooks ship (`docs/runbooks/`), the live load pass,
  pen test, and restore drill execute against the running stack (the Phases 5/6
> live-exit pattern) — recorded when run.

**Implemented — T1 environments + the promotion artifact (§2):**
- the scratch mechanism grown up, one provisioning path: `md_environments` pins
  promoted versions per (tenant, app, `staging|prod`) — `dev` stays the implicit
  draft workspace; the first promotion provisions the environment's own tenant
  through the platform-admin API (`EnvironmentProvisioner`, default HTTP impl riding
  the same legs the harness does); later promotions re-import the bundle into that
  tenant's app and publish — the environment's data plane survives promotions
- the promotion artifact format (§2): `GET …/versions/{v}/artifact` — a versioned
  ZIP (`manifest.json` + `definitions.json` + `signature.txt`), sha256-content-
  hashed and HMAC-SHA256-signed (the signing key is deployment config — KMS/Vault in
  staging/prod per PHASE-6 §9's stance); import verifies hash + signature, rejects
  zip path escapes and unknown entries, and creates a new draft app (optional
  `apiName` rename for same-workspace imports)

**Implemented — T3/T4 the promotion gate, override, rollback (§4):**
- version identity is content: publish records the bundle's sha256 on the version
  row; every suite run (interactive or headless) records the candidate's hash — the
  gate ("a recorded green run of all app suites against exactly V") is a mechanical
  hash match, latest-first per suite; free when the app defines no suites
  (ADR-010 #4's opt-in rule)
- order: dev → staging → prod, each hop its own gated promotion; the prod hop
  requires staging pinned to the same artifact hash AND is the explicit
  platform-admin approval (§11 Q1 — no timed burn-in in v1); override is
  admin-only + reason-required, audited in `md_promotions`, and rendered in
  change-set review forever
- rollback (§4 item 4): redeploying a prior version through the same gate; the
  compatibility rule reuses the publish check — `breakingChanges(current, prior)`
  non-empty (projection/field removals, type changes) blocks one-click rollback;
  incompatible rollback requires admin override + explicit
  `dataMigrationAcknowledged` (JSONB retains removed fields until a tenant-scoped
  prune — nothing is destroyed at publish, §4 item 5)

**Implemented — T2 change sets (§3):** `GET …/changeset?env=` renders the per-
definition diff (entities/state machines/reports/suites/translations by apiName:
  added/modified/removed; permissionSet changed flag) plus the review attachments:
  suite results hash-bound to the exact draft under review, the script-ratio delta,
  the credential references the target environment must re-bind (material never
  rides the artifact), and the full promotion history with overrides visible

**Implemented — T5 headless runs + CI (§5):** `POST …/apps/{id}/suite-runs`
  (app-wide or subset) + `GET` artifacts, `POST/GET …/suites/{rowId}/runs` — all
  builder-gated; the `novaforge-pipeline` realm client (client-credentials, builder
  platform role, dev-workspace tenant attribute) joins the deployed realm; the
  reusable `app-suite-gate` workflow ships the green-run→promote pattern and
  `docs/ci-app-promotion.md` pins the wiring

**Implemented — T6 templates (§6):** `POST /templates` snapshots a published
  version (definitions + fixture suites; tenant data and secret material never
  ride the artifact), `GET /templates` lists the catalog (name/publisher/version/
  description — no commerce, §11 Q2), `POST /templates/{id}/install` creates a new
  draft app; the ERP app is the first template (registered from its published
  version — the README documents the one-liner)

**Implemented — T7 i18n (§7):** the Translations branch — per-locale workspaces as
  kind-discriminated metadata (`md_definitions`), versioned and promoted with the
  app; the pinned fallback chain `label_i18n[locale] → label → apiName` (never
  blank) lives as a pure function in `metadata-model` (`TranslationsDefinition`);
  the missing-translation report keys the translatable universe
  (`app.label`, `<Entity>.label`, `<Entity>.<field>.label`, `report.<id>.label`);
  CSV/JSON export carries the full universe (missing rows empty), import merges
  never wipes, unknown keys reject with guidance; **the editor ships in the builder**
  (`frontend/builder-ui` — the side-by-side per-locale workspace over the keyed
  universe, the missing-translation count, RFC 4180 CSV round-trip) and the
  runtime shell carries the user-locale preference that selects the chain

**Suites:** `LifecycleTests` (7 — the §9 promotion-gate suite: red gate blocks,
  exact-content-hash matching (a stale-draft run does not admit), prod order +
  admin hop + override-forever-rendered, rollback both branches with the ack gate,
  artifact hash/signature/tamper + template round-trip, headless recording, i18n
  workspace round-trip incl. publish promotion), `TranslationsDefinitionTest` (4),
  `ErpAppArtifactTests` (7, Phase 7's CI gate)

**T8–T10 (the operational exercises):** the runbooks ship —
  `docs/runbooks/dr-restore-drill.md` (PITR + MinIO versioning + Kafka replay +
  the quarterly drill's exit criteria + the secrets-rotation leg) and
  `docs/runbooks/security-review-scope.md` (the §8 pen-test matrix mapped to the
  owning suites); the live executions (load validation at full scale, the pen
  pass, the restore drill) run against the live stack — the Phases 5/6
  live-exit pattern — and record here when run.
