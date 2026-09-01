# NovaForge Code Review Report

**Date:** 2025-08-26  
**Reviewer:** AI Code Review Assistant  
**Scope:** Full platform (platform libs, services, deploy modules)

---

## Executive Summary

The NovaForge codebase is a well-architected, metadata-driven no-code platform built on Spring Boot 4.x, Java 21, and a multi-module Maven structure. The code demonstrates strong architectural discipline (enforced via ArchUnit), thoughtful security design (multi-tenant isolation, service-to-service auth), and comprehensive test coverage using Testcontainers.

However, several **critical and high-priority issues** were identified that affect correctness, security, and maintainability.

---

## Critical Issues

### 1. ScriptSandbox - Resource Leak in Watchdog (CRITICAL)
**File:** `services/script-engine/src/main/java/com/novaforge/script/engine/ScriptSandbox.java`  
**Lines:** 160-200

The `watchdog.scheduleAtFixedRate()` creates a recurring task that holds references to the `Context`, `logs`, and `killReason`. If an exception occurs before `watch.cancel(false)` in the `finally` block, the task continues executing and prevents garbage collection of the GraalVM Context.

**Risk:** Memory leak under error conditions; Context not properly closed.

**Fix:** Wrap the watchdog task in a try-finally that ensures cancellation, or use a try-with-resources pattern for the scheduled future.

---

### 2. ScriptSandbox - Thread ID Reuse Race (CRITICAL)
**File:** `services/script-engine/src/main/java/com/novaforge/script/engine/ScriptSandbox.java`  
**Line:** 170

```java
Thread executor = Thread.currentThread();
long executorId = executor.getId();
```

Thread IDs are reused by the JVM thread pool. If a script execution completes and the thread is reused for another request, the watchdog will measure CPU time from the *new* execution, incorrectly attributing it to the previous one.

**Fix:** Use `ThreadMXBean.getThreadCpuTime(Thread.currentThread().getId())` inside the watchdog task itself, or use a dedicated executor thread pool with isolated threads.

---

### 3. ServiceTokenClient - Token Refresh Race Condition (CRITICAL)
**File:** `platform/libs/security-context/src/main/java/com/novaforge/security/ServiceTokenClient.java`  
**Lines:** 60-95

Multiple threads can simultaneously detect an expired `grant` and all execute the HTTP token request. While "benign" per comments, this:
- Wastes network/CPU resources
- Can trigger rate limiting on Keycloak
- Increases latency for all callers during refresh

**Fix:** Use double-checked locking with a `CompletableFuture` or `StampedLock` to ensure only one refresh occurs.

---

### 4. ConnectorExecutor - OAuth2 Token Cache Race (CRITICAL)
**File:** `services/integration-service/src/main/java/com/novaforge/integration/connector/ConnectorExecutor.java`  
**Lines:** 240-265

The `tokenCache` check-then-act pattern is not atomic:
```java
CachedToken cached = tokenCache.get(credential.id());
if (cached != null && Instant.now().isBefore(cached.refreshAt())) {
    return cached.token();
}
// ... fetch new token ...
tokenCache.put(credential.id(), new CachedToken(...));
```

Multiple threads can fetch tokens simultaneously.

**Fix:** Use `ConcurrentHashMap.computeIfAbsent` with a refresh-aware supplier, or `CompletableFuture` memoization.

---

### 5. TenantContext - ThreadLocal Leakage in Virtual Threads (CRITICAL)
**File:** `platform/libs/common-core/src/main/java/com/novaforge/common/context/TenantContext.java`

Java 21 virtual threads have different ThreadLocal semantics. The `ThreadLocal<Context>` holder:
- May not be inherited by virtual threads spawned from platform threads
- Can leak if virtual threads are pooled without proper cleanup
- `TenantTaskDecorator` only applies to `ThreadPoolTaskExecutor`, not all executors

**Fix:** Use `InheritableThreadLocal` for virtual thread compatibility, and enforce decorator on all executor beans via auto-configuration.

---

## High Priority Issues

### 6. ScriptSandbox - Heap Measurement False Positives (HIGH)
**File:** `services/script-engine/src/main/java/com/novaforge/script/engine/ScriptSandbox.java`  
**Lines:** 195-205

The two-sample heap check has a race: GC can run between samples, causing a transient spike to be missed or a real leak to be masked by GC.

**Fix:** Use a longer observation window (e.g., 3 samples over 100ms) or integrate with GC notifications via `MemoryPoolMXBean`.

---

### 7. ConnectorExecutor - RestClient Created Per Call (HIGH)
**File:** `services/integration-service/src/main/java/com/novaforge/integration/connector/ConnectorExecutor.java`  
**Line:** 180

```java
RestClient client = RestClient.builder()
    .requestFactory(timedFactory())
    .build();
```

Creates a new `RestClient` (and connection pool) for every connector call. This defeats connection pooling and HTTP/2 multiplexing.

**Fix:** Create a shared `RestClient` bean per connector (or use `RestClient.Builder` prototype scope).

---

### 8. ConnectorExecutor - Client Secret in Request Body (HIGH)
**File:** `services/integration-service/src/main/java/com/novaforge/integration/connector/ConnectorExecutor.java`  
**Line:** 260

OAuth2 client credentials grant sends `client_secret` in the form body, which may be logged by proxies, load balancers, or the auth server's access logs.

**Fix:** Use HTTP Basic Auth for client credentials (RFC 6749 §2.3.1) or ensure secrets are masked in logs.

---

### 9. Inconsistent Error Handling / Stack Trace Loss (HIGH)
**Files:** Multiple services

Patterns like:
```java
} catch (Exception e) {
    throw new PlatformException(PlatformErrorCode.INTERNAL, "failed: " + e.getMessage());
}
```
Lose the original stack trace. The `PlatformException(..., cause)` constructor exists but is inconsistently used.

**Fix:** Always pass the cause: `new PlatformException(code, msg, detail, cause)`.

---

### 10. Manual JSON String Construction (HIGH)
**Files:** Multiple (e.g., `RecordController.encodeQuery`, `RecordApiTests`)

JSON is manually constructed via `StringBuilder` and `URLEncoder`, which is brittle and doesn't handle escaping correctly in all cases.

**Fix:** Use `ObjectMapper`/`JsonMapper` for all JSON serialization.

---

## Medium Priority Issues

### 11. Duplicate Infrastructure Code Across Services (MEDIUM)
**Files:** Every service has nearly identical:
- `ProblemAdvice.java`
- `SecurityConfig.java`  
- `TenantBindingFilter.java`
- `ProblemAuthenticationEntryPoint.java`
- `OpenApiConfig.java`

**Fix:** Extract to `novaforge-common-web` module.

---

### 12. Magic Numbers / Hardcoded Limits (MEDIUM)
**Examples:**
- `ScriptSandbox.MAX_ENTRIES = 100` (hardcoded log limit)
- `ScriptSandbox.Converter.MAX_NODES = 8192` (arbitrary)
- `RecordEngine.MAX_INLINE_CHILDREN = 100`
- `RecordEngine.MAX_BATCH = 500`
- Various timeouts in `application.yml` not centralized

**Fix:** Centralize in configuration properties with `@ConfigurationProperties` validation.

---

### 13. Circuit Breaker Proliferation (MEDIUM)
**File:** `services/integration-service/src/main/java/com/novaforge/integration/connector/ConnectorExecutor.java`  
**Line:** 108

```java
CircuitBreaker breaker = breakers.circuitBreaker(tenantId + ":" + connectorId);
```

Creates a circuit breaker per tenant+connector combination. With many tenants, this creates thousands of circuit breaker instances.

**Fix:** Use a shared circuit breaker per connector (tenant isolation is at the credential level, not circuit level).

---

### 14. Missing Input Validation (MEDIUM)
**Files:** Multiple controllers

UUID parsing without validation:
```java
UUID.fromString(request.tenantId())  // throws IAE if invalid
```

**Fix:** Add `@Valid` + custom validators, or validate early with clear error messages.

---

## Low Priority Issues

### 15. Extensive PHASE/ADR References in Comments (LOW)
Comments reference internal documents (PHASE-0, ADR-003, etc.) that external contributors won't have access to. While useful for the core team, they reduce readability.

**Fix:** Keep architectural context but add inline explanatory comments for key decisions.

---

### 16. Long Methods (LOW)
`RecordEngine.create()`, `update()`, `delete()` exceed 100 lines each with multiple responsibilities.

**Fix:** Extract to private methods with clear names (already partially done, but could go further).

---

### 17. Inconsistent `var` Usage (LOW)
Some files use `var` extensively, others use explicit types. No project-wide convention.

**Fix:** Define a style rule (e.g., "use `var` when type is obvious from RHS").

---

## Recommendations Summary

| Priority | Count | Est. Effort |
|----------|-------|-------------|
| Critical | 5 | 2-3 days |
| High | 5 | 2-3 days |
| Medium | 4 | 1-2 days |
| Low | 3 | < 1 day |

**Total Estimated Effort:** 5-8 days for full remediation

---

## Immediate Action Items (Do First)

1. **Fix ScriptSandbox watchdog leak** - Memory leak in production
2. **Fix ServiceTokenClient refresh race** - Auth server stability
3. **Fix ConnectorExecutor token cache race** - Connector reliability
4. **Fix TenantContext for virtual threads** - Java 21 compatibility
5. **Always pass exception causes** - Debuggability

---

## Files to Create/Modify for Fixes

### New Files:
- `platform/libs/common-web` module (extract shared web infrastructure)
- `platform/libs/security-context/src/main/java/com/novaforge/security/ServiceTokenClient.java` (fixed)
- `services/script-engine/src/main/java/com/novaforge/script/engine/ScriptSandbox.java` (fixed)
- `services/integration-service/src/main/java/com/novaforge/integration/connector/ConnectorExecutor.java` (fixed)

### Modified Files:
- `platform/libs/common-core/src/main/java/com/novaforge/common/context/TenantContext.java`
- `platform/libs/security-context/src/main/java/com/novaforge/security/TenantTaskDecorator.java`
- Multiple service `SecurityConfig.java` to auto-apply `TenantTaskDecorator`

---

## Testing Impact

All fixes should be validated by:
1. Existing test suites (`./mvnw verify`)
2. New unit tests for the fixed race conditions
3. Load testing for ScriptSandbox concurrency
4. Chaos testing for token refresh under Keycloak latency
---

## Second-Pass Review — 2026-08-26 (verification of the first-pass fixes + remaining items)

**Scope:** verify the committed critical fixes, then close the high-priority remainder.

### Findings on the committed fixes

1. **ConnectorExecutor token cache — the fix regressed (CRITICAL, fixed now).** The
   `computeIfAbsent(CompletableFuture)` rewrite cached the *future*, not the value:
   `refreshAt` was never consulted (tokens served forever once fetched — exactly the
   expiry bug it replaced), and a failed grant cached a failed future permanently —
   one auth-server blip poisoned the credential for the JVM's lifetime. It also kept
   an unbounded cached-thread pool alive. Rewritten as a single-flight,
   expiry-aware `compute` over plain values: fresh entries serve uncontended,
   expired ones refetch under the map lock (concurrent callers coalesce), failed
   fetches leave nothing cached, and the pool is gone.
2. **ServiceTokenClient** — double-checked locking verified sound. Note: a failed
   grant correctly leaves the old (expired) token in place; next call retries.
3. **TenantTaskAutoConfiguration** — the BeanPostProcessor approach works on Spring
   Framework 7 (verified against 7.0.8 bytecode: the decorator is resolved per
   submitted task, not at pool creation), **but the javadoc claimed virtual threads
   "inherit ThreadLocal values through Continuation mechanics" — false.** Plain
   ThreadLocals are never inherited by any thread. The doc claims are corrected;
   the code was already right (the decorator is the carrier in both pool and
   virtual-thread models — Boot's virtual-thread executor is a
   `SimpleAsyncTaskExecutor`, which applies the decorator bean per task).
4. **ScriptSandbox watchdog** — cancellation and CPU metering fixes verified sound.

### Fixes implemented this pass

| # | Issue | Fix |
|---|-------|-----|
| H7 | `RestClient` rebuilt per connector call (and the token grant used bare `RestClient.create()` — no timeout at all) | one shared client per executor with the §4 read timeout |
| H8 | OAuth2/Keycloak client secrets in request bodies | RFC 6749 §2.3.1 Basic client authentication on both grant legs (`ServiceTokenClient`, `ConnectorExecutor.fetchToken`) |
| H9 | ~20 `PlatformException` throws dropped the cause | every wrap now passes `cause` (file, notification, reporting, workflow, metadata, integration, data-runtime) |
| H10 | `RecordController.encodeQuery` hand-built JSON; malformed DSL nodes surfaced as downstream 500s | mapper-composed query + each node parsed/validated at the door → VALIDATION_FAILED with a field-scoped error |
| — | OAuth2 credential leg had zero test coverage | 3 journey tests against a mock token endpoint (Basic auth shape, cache-until-expiry, no cache poisoning) — verified they fail against the old implementation |
| — | Malformed-DSL rejection untested | new `malformedDslNodeRejects` test in `RecordApiTests` |

### Deferred (unchanged from first pass, by scope decision)

- M11 duplicate web infra per service (extract `novaforge-common-web`) — worthwhile, but a structural refactor across 10+ services, not a review-commit item.
- M12 magic limits → `@ConfigurationProperties` — the `@Value` defaults are already per-bean and documented; batching this with M11.
- M13 per-tenant circuit breakers — deliberate isolation (a noisy tenant must not open *other* tenants' breakers); reopening would change failure semantics. Keeping, with this note as the record.
- L15–L17 style items — no functional impact.

### Verification

`security-context` (17), `integration-service` ConnectorExecutorTests (7),
`data-runtime` api+engine (73), `file-service` (5), `notification-service` (8),
`metadata-service` (45), `workflow-service` (27), `reporting-service` (16) — all green.

---

## Third Pass — 2025-08-27 (full-repo sweep: platform libs, all services, frontend, deploy)

Scope beyond the first two passes: query lowering, expression SQL, materializer DDL,
migrations, gateway, SPAs, helm/compose/keycloak. All first/second-pass fixes
re-verified in place. Two new findings, both HIGH, both closed this pass:

### H-TP1. SQL injection via aggregate `alias` (query DSL) — HIGH
`POST /api/v1/runtime/{entity}/query` accepted `aggregates[].alias` as an arbitrary
string; `QueryLowering` splices it into the SELECT list as a quoted identifier, so a
crafted alias (`x", (SELECT …) AS "leak`) broke out of the quotes and appended
caller-chosen SQL to the statement — same-tenant cross-entity reads bypassing sharing
rules and hidden-field security (injected select expressions never reach
`requireFieldVisible`). Metadata report aliases were grammar-bound
(`ReportDefinition.REPORT_KEY`); the runtime parse door was not.

**Fix:** `QueryParser.parseAggregate` now grammar-binds every authored alias with
`ReportDefinition.REPORT_KEY` (`^[a-zA-Z_][a-zA-Z0-9_]*$` — same rule as report keys,
so `debitTotal`/`sum_debit` ride) and rejects anything else as VALIDATION_FAILED
before SQL is built. Regression: `AggregateAliasValidationTests` (plain aliases
parse; quote/paren/space/digit breakouts reject). Bucket labels were already bind
params; group/filter/sort fields were already metadata-validated — the alias was the
one hole.

### H-TP2. Missing transaction boundaries on five RecordEngine entry points — HIGH
`@Transactional` existed only on `create/update/delete/integrationCreate/
integrationUpdate`; `HookExecutor` carries none. Consequences: (1) `batch()` —
unannotated *and* self-invoking `this.create/update/delete` past the Spring proxy —
auto-committed every statement independently: a failing inline child committed an
orphaned parent, outbox rows decoupled from the writes they describe, and `seq_state`
draws stopped being rollback-safe (the gapless-sequence guarantee, PHASE-1 §5).
(2) `runManualHook`, `runScheduledHook`, `resumeApproval`, `retryAfterHook` — flow
writes and outbox appends piecemeal, against the PHASE-3 §4 "events ride the record
transaction" design.

**Fix:** the four hook entry points are `@Transactional` (they are invoked through
the proxy from controllers/scanner, so the boundary applies; hook machinery inside
matches the write path, which already runs hooks in-transaction). `batch()` routes
each item through an injected `ObjectProvider<RecordEngine>` self-proxy — per-item
transactions restore item atomicity (parent + children + outbox + sequence draws)
while per-item outcomes survive: a SQL-level abort poisons only that item's
transaction, never the rest of the batch.

### Open items from this pass (recorded, not fixed here)

- M-TP1 idempotency-key TOCTOU (`replay → execute → record` not fenced; use `setIfAbsent`)
- M-TP2 `SecretCipher` silently falls back to an all-zeros key when the env var is unset
- M-TP3 default service-client secret is committed and admin-equivalent (`ServiceClientGate`/AdminController service-client branch)
- M-TP4 list-path criteria sharing post-filters a fetched page — criteria-only rows beyond the window unreachable; reuse the aggregate path's `applySharing` lowering
- M-TP5 `ExpressionSql` contains/startsWith lower the needle unescaped — `%`/`_` act as wildcards in SQL but literals in the evaluator (parity divergence)
- L-TP1 `RecordController.query` routes aggregate vs list via raw `body.contains("\"aggregates\"")` — misroutes when a filter value is that string
- L-TP2 SPA auth stores the session (access token) in `sessionStorage` contra its own doc; restored sessions are never expiry-checked; `refresh_token` unused
- L-TP7 actuator `prometheus` permitAll on every service — keep service ports unexposed

### Verification (this pass)

Full reactor `-DskipTests install` green on Java 21; engine module tests green
(15/15: GoldenSql 5, BucketedAggregateSql 3, EntityResolverQualified 4, the new
AggregateAliasValidation 3). Container-based suites not run this pass (no Docker in
the review environment) — the changed paths are covered by the unit tests above;
the transaction-boundary change is proxy wiring, exercised by the existing
api-module suites when run in a container-capable environment.

---

## Fourth Pass — 2025-08-27 (follow-up: the recorded-open mediums and lows)

Every open item from the third pass is closed, except L-TP7 (actuator prometheus
permitAll — unchanged by decision: scraping has no credential channel today; the
posture is "service ports never leave the cluster," now noted in the helm values).

### M-TP1 — Idempotency claim fence
`replay → execute → record` raced: two concurrent requests with one key both
executed. `IdempotencyRecorder.claim` now takes the key with a Redis
`SETNX` pending marker (10-min TTL, long enough for a 500-item batch) before
execution — `Acquired` / `Replay(settled)` / `InFlight` verdicts; create and batch
in `RecordController` release the fence on execution failure so a client may retry
immediately, and a duplicate in flight renders 409 with guidance instead of racing.

### M-TP2 — secrets data key fails closed
`SecretCipher` no longer silently mints the all-zeros key: the public dev key is
allowed only under `novaforge.integration.secrets.allow-dev-key` (default true,
loud multi-line WARN naming the risk); the integration helm chart sets
`NOVAFORGE_SECRETS_ALLOW_DEV_KEY=false` + sources `NOVAFORGE_SECRETS_DATA_KEY`
from a Kubernetes Secret — a misconfigured staged deployment fails at boot
instead of encrypting tenant secrets under a repo-published key.

### M-TP3 — service-client secret fails closed
`ServiceTokenAutoConfiguration` now refuses the committed
`novaforge-runtime-secret` when `novaforge.auth.service-client.allow-default-secret`
is false (all eight service charts set it + source the secret from a K8s Secret);
locally the default boots with a startup warning. Constant hoisted to
`ServiceClientGate.DEFAULT_DEV_SECRET`.

### M-TP4 — list-path sharing lowers into SQL
`RecordEngine.list`/`listAsRole` now reuse the aggregate path's `applySharing`
(owners `created_by IN (…)` OR lowered criteria), replacing the owner-only SQL +
JVM post-filter hybrid: criteria-only rows beyond the fetched page window are
reachable again, `total` matches the visible rows, and a non-lowerable criterion
fails closed exactly as reports do. Point reads keep the JVM predicate (no
windowing exists there).

### M-TP5 — LIKE-wildcard parity
`ExpressionSql` `contains`/`startsWith` escape the needle in SQL (backslash
first, then `%`/`_`, explicit `ESCAPE '\'`); the DSL's `contains` leaf escapes
the bound value at bind time. A literal `%`/`_` in a needle now matches itself on
both execution surfaces — the evaluator's substring semantics hold in SQL.
Golden expectations updated (plain values like `spike` are byte-identical).

### L-TP1 — query routing parses once
`RecordController.query` routes aggregate-vs-list on the parsed body's shape;
a list filter whose *value* is the string "aggregates" no longer misroutes, and a
malformed JSON body rejects VALIDATION_FAILED at the door.

### L-TP2 — SPA session honesty
Both `auth.ts` copies: the session (in `sessionStorage`, now acknowledged in the
doc — memory-only was never true) carries `expiresAt` + `refreshToken`; restore
expiry-checks before use, silently refreshes via the refresh-token grant while it
is valid, and clears unrecoverable sessions to the sign-in action.

### Verification (this pass)

Full reactor `-DskipTests install` green on Java 21. Module tests green:
expression-dsl 16/16 (incl. the updated parity test), security-context 17/17,
data-runtime engine 15/15. Frontend: builder-ui 40/40, runtime-ui 11/11.
Container suites (api module: RecordApi/Sharing/…, integration: webhooks)
not run here (no Docker) — the api-module suites exercise the new claim fence,
lowered sharing, and query routing end-to-end and must run in CI.

---

## Fifth Pass — 2026-08-28 (bug hunt: the child walk's silent page truncation)

### H-5P1 — `currentChildren` walked one default page (50 rows), orphaning every child past it

`RecordEngine.currentChildren` — the shared child walk behind the update path's
inline-array replace (`replaceChildren`), the delete path's cascade
(`cascadeChildren`), and the flow `iterate` step (`EngineHookSink.children`) —
issued its binding-filter list with no `page`, so the DSL lowered its default:
`LIMIT 50 OFFSET 0`. Inline children are legal to 100 per request, and
standalone/batch writes grow a parent's child set without bound, so the walk
silently stopped at 50:

- **replace-children** soft-deleted only page one, then inserted the new set —
  every old child past row 50 survived as an orphan *and duplicated* the new set;
- **cascade-delete** left up to N−50 live children under a deleted parent;
- both stayed counted by the roll-ups (`Rollup.aggregate` runs unwindowed SQL
  aggregates), so a replaced document summed old + new lines — corrupted totals,
  not just stray rows;
- the flow `iterate` step observed only the first 50 children.

The walk now pages to exhaustion at `MAX_PAGE_SIZE` (200) — stable under the
list lowering's deterministic `ORDER BY id`, and safe because every caller
mutates only after the walk completes.

Regression: `ChildReplacePagingTests` (engine module, no containers) — a mocked
`RecordStore` honoring the lowered `LIMIT ?/OFFSET ?` binds serves a 250-child
parent; against the bug the walk deleted 50/250 (replace) and 51/251
(cascade); the tests pin all-children deletion on both paths plus the
binding-correct insert of the new set.

### Verification (this pass)

Full reactor `-DskipTests install` green on Java 21. Module tests green:
expression-dsl 16/16, security-context 17/17, data-runtime engine 17/17
(incl. the new 2). Container suites unchanged and still owed to CI.

## Sixth Pass — 2026-08-30 (twin audits: workflow/approvals/SLA + reporting/exports, and the materializer's concurrent-DDL race)

The full-reactor baseline flaked first: `HookStepResultTests` NPE'd on an empty
Voucher list, reproduced root-cause — the boot catch-up's reconcile pass raced the
test's direct `materializer.apply()` on the same tables, and Postgres
`CREATE … IF NOT EXISTS` is not atomic against a concurrent creator (one pass died
on a `pg_class` duplicate key, its shape skipped until an unpromised "next
publish"; the after-hook failure is swallowed by design, so the write returned 200
and the voucher never landed).

### H-6P1 — materializer passes never serialized (cross-replica, same-JVM, and test-flake root)

`Materializer.applyAll` now holds a session-level Postgres advisory lock
(`PASS_LOCK_KEY`, 120 s `lock_timeout`) on a dedicated guard connection around the
whole reconcile — the subscriber's executor serialized passes only within one JVM,
while every replica's subscriber and boot catch-up runs its own pass; two passes
interleaving DDL is the production shape of the same race. Statements keep running
pooled and per-statement-isolated; mutual exclusion comes from every pass passing
the gate. Pinned deterministically in `MaterializerTests.passLockSerializesConcurrentReconciles`
(an external holder of the key blocks a pass; release completes it).

### H-6P2 — the scheduler lease suppressed every other window of every job

`JobRunner`'s lease ran until `next_fire + lease`, but the scan advances
`next_fire_at` *before* testing the lease — the next due window could never
acquire: any job with a cron period longer than the lease (default 60 s — i.e.
every real job) fired at half its intended rate, silently. V2 migration adds
`sched_leases.fired_window`; the lease now gates on the fired window (a lease for
window N never suppresses N+1; two replicas scanning the same window still
single-fire). Pinned by `SchedulerTests.consecutiveWindowsBothFire` (two forced
windows, no `DELETE FROM sched_leases` — the exact crutch the old tests used).

### H-6P3 — SLA resolution leaked across tenants

`RestPublishedSlaSource` filtered the cross-tenant published-apps index by
`apiName` only (first match in ANY tenant won the bundle) and `SlaResolver`'s 30 s
cache was keyed by `appApiName` alone — tenant B's approval could run tenant A's
timers and escalate to tenant A's role. Both now scope by tenant.

### H-6P4 — a delegated approval could never escalate; a failed resume wedged the record

Two wedges on the approval path: delegation's replacement task used the pre-SLA
constructor (escalateTo nulled — at breach the scanner flips ESCALATED with no
replacement, the flow stays suspended forever, `sla.warn` re-fires), and a failed
`runtime.resume` parked the instance FAILED — a status `resolved()` can never act
on — with the task already consumed as APPROVED in the same transaction. Delegation
now carries the full task shape; a failed resume rethrows so the whole approve
rolls back (task OPEN, instance SUSPENDED, the approver retries once the runtime
heals or the renamed hook is republished). Also: `any`-mode resolution now
supersedes its losing siblings (an OPEN loser used to "breach" and spawn a phantom
escalation for an approval already resolved), and `warnAt: null` (the §6 disable)
is presence-parsed — an absent `warnAt` authors the 0.8 default, an explicit null
disables, and the field always serializes so the authored disable round-trips.

### H-6P5 — the async report export re-scoped to the app's `reporting` role

An over-cap interactive export (correctly actor-scoped) handed off to a job that
re-rendered under the app's `reporting` role — wider data delivered to the
requester, and apps without that role failed every over-cap export. The chain is
actor-scoped end to end now: the runtime's internal report-query leg grew
`asActor` (exactly one of asRole/asActor; `engine.aggregate` re-evaluates the
actor's matrix, field security, and owner-based sharing), `ReportRunner.runAsActor`
re-checks the `report: execute` grant, and the integration job passes
`initiatedBy` as the scope. The scheduled leg stays role-scoped and now addresses
the app-qualified entity (same-named entities across apps rejected the nightly
delivery as ambiguous).

### H-6P6 — the reporting surface's long tail

- **Filters were a value oracle over hidden fields** — group-by/aggregate fields
  failed closed on HIDDEN but filter leaves rode verbatim on every door (report
  params, list `filter`), so row counts and totals answered binary-searchable
  questions about values the caller cannot read. Filter trees now fail closed on
  the actor and role doors (`RecordEngine` list/aggregate, listAsRole/aggregateAsRole).
- **Widget display config rode report run params** — the ERP `exec` dashboard's
  `{aggregate: outstanding}` / `{x,y}` compiled into filter leaves the runtime
  rejected, and the SPA swallowed the error ("Loading arAging…" forever). Widgets
  gained `options` (display config, never sent as run params; the KPI metric names
  its aggregate), run failures render an error state, and the corpus is re-authored.
- **Exports**: CSV formula injection neutralized in both exporters (leading
  `= + - @`/tab/CR prefixed); XLSX sheet names cap at 31 (a legal 40-char report id
  failed every render); aggregate-only reports carry totals (the un-grouped twin
  was skipped as identical — KPIs read `undefined` and the export's closing row
  printed TOTAL over the money value); money renders exact-scale symbol-prefixed
  decimals (no rounded, grouped currency format); `count` over a money field is no
  longer currency-formatted.
- **Doors bound materialization**: the aggregate DSL grew a SQL-level `limit`, and
  the reporting doors ride it one past their ceilings — the run door
  (`run-max-rows`, default 50k) and the async legs (`async-max-rows`, default 1M)
  fail closed audibly past their ceiling; the sync export door detects over-cap at
  cap+1 rows and answers the 202 handoff without draining the dataset (a
  high-cardinality group-by was an OOM vector via a single request).
- **Smaller**: the task read enforces §13's access rule (a task id from a
  notification is not a grant); the notification consumer rethrows transient
  failures (the inbox dedupe collapses redelivery — a swallowed SMTP outage used to
  ack a dropped fan-out); dashboard composition matches ANY held app role; the
  cache read keeps decimals decimal; one structurally-invalid cron skips its own
  job, never the rest of the sync pass; the BPMN bridge carries the matched SLA's
  escalation target; `sla.warn`'s flip is conditional (no double-warn under
  replicas); record-deletion cancellation only notifies tasks it actually
  cancelled (a resolved engine task used to roll the whole cancellation back and
  poison the `record.deleted` redelivery); the dead `ReportExportClient` (spoke a
  contract the server never answered) is deleted.

### The recorded lows — closed in the same pass (follow-up commit)

- **L-6P1** delegation now validates the target is a reachable assignee — a
  target holding no roles in the tenant (a typo'd ghost UUID, or a role-less
  user whose inbox can never match) rejects VALIDATION_FAILED, transaction
  rolled back (`delegationValidatesTarget`).
- **L-6P2** `onBreach.notify` is honored end to end: the switch resolves at task
  creation from the matching `SlaDefinition` (V5 migration `wf_tasks.notify_on`),
  rides delegation replacements and the BPMN bridge, and at breach the
  `sla.breach` event carries `notify: false` — the event still rides the spine
  (metrics, audit) but the Notification Service skips the sla-warning fan-out
  (`onBreachNotifyFalseEscalatesQuietly`, `quietBreachNeverFansOut`). The
  escalation itself still happens — "escalate, notify, or both" is now both
  switches, not one.
- **L-6P5** every outbox (the three recorded — `wf_/sched_/nf_` — plus
  `it_`, `fl_`, and the runtime's own `event_outbox`, the highest-volume one)
  gained a retention pass: published rows older than
  `novaforge.events.retention-days` (default 7) leave on a slow schedule
  (`retention-interval-ms`, default 1 h); unpublished rows never leave —
  delivery first. Pinned in each service's suite
  (`outboxRetentionDropsOldPublishedRows`).

### Verification (this pass)

Full `./mvnw verify` green end to end on Java 21 (all 23 reactor modules, with the
container suites on the podman socket), frontend 149 vitest green + `check`
(typecheck) clean. New pins this pass: `MaterializerTests` +1 (9), `SchedulerTests`
+2 (11), `TaskApiTests` +5 (20), `SlaWarnAtPresenceTest` (2), `ReportRunnerTests`
+4 (8), `ReportExporterTests` +3 (7), `ReportAggregateTests` +2 (7).

## Seventh Pass — 2026-08-31 (four-surface audit: data-runtime write path, file service, audit consumers + gateway edge, metadata lifecycle)

Four parallel deep audits over the surfaces the six prior passes had covered least.
Thirteen live defects closed this pass; the remainder are recorded below with their
mechanisms. Every fix carries a regression pin that fails against the prior code.

### H-7P1 — the cached sequence re-served its first number up to `start/blockSize` times

`SequenceService.drawCached` clamped a fresh block's `first` up to the authored
start — manufacturing a born-exhausted block whenever `start > blockSize` (the
production default block is 100). With `start: 500`: every allocation returned
`Block(500, 100)`, served 500, found itself exhausted, and re-allocated — the first
five draws were all `500`. Duplicate document numbers, the exact invariant a
sequence exists to guarantee; a unique-numbered field bricked creates 2..N with a
generic uniqueness error until the counter climbed past the start. The allocation
now pushes the counter to `start-1` and claims a fresh full block there (whole
window below start), or serves from `start` (window straddles it) — every served
window is the range of one atomic increment, so concurrent claimers never overlap
and the counter only moves forward. Pinned in `SequenceServiceTests` (4: strictly
increasing draws from 500, two claimers never overlap, small-start unchanged,
raised-start never serves below start) against a per-key Redis-faithful Mockito
counter; `RecordApiTests.cachedSequenceDraws` re-pinned order-independently — the
old assertion (`CN-00500` on any draw) only held *because* every draw was a
duplicate, masking the suite's shared-counter ordering.

### H-7P2 — flow-driven updates bypassed the whole validation pipeline; `"null"` poisoned numeric roll-up SQL

`RecordEngine.updateAsPrincipal` — the write path behind every flow
`updateRecord` step, approval resume, and hook field-write — merged template values
raw: no `FieldCoercer.canonicalize`, no `evaluateValidationRules`. Templates render
every `${…}` binding as a *string* (`HookExecutor.resolveTemplateText`), and an
unbound binding renders the literal `"null"` — so an OrderLine without `qty`
wrote the string `"null"` into `InventoryItem.reserved` (DECIMAL), and every later
`SUM`/roll-up over the entity threw Postgres `22P02` (the in-memory aggregator's
`!text.equals("null")` guard was the codebase acknowledging the leak the SQL side
never had). Enum membership, scale caps, and date shapes rode the same hole. The
path now canonicalizes and validates like every writer (string "3" coerces to
decimal 3; `"null"` on a typed field rejects as a field-scoped hook failure,
spine-retried, the stored value untouched); unknown fields stay ignored so a flow
outliving a metadata edit keeps running. Pinned in
`RecordApiTests.hookTemplateNullBindingDoesNotPoisonTypedField` (positive control:
bound template lands as a number; poison case: value unchanged and the aggregate
door still parses).

### H-7P3 — one malformed batch item 500'd the request after earlier items committed

`RecordEngine.batch` caught only `PlatformException`: an update item missing
`version` NPE'd, a non-uuid `id` threw `IllegalArgumentException`, and a unique-race
`DataIntegrityViolationException` escaped — a 500 *after* items 1..k-1 committed in
their own transactions, their verdicts lost (the design comment's own promise — "a
SQL-level abort poisons only that item's transaction" — was never implemented on
the catch side). Items are now shape-guarded into per-item `VALIDATION_FAILED`
verdicts (`requireBatchText/Uuid/Version`), non-Platform failures report as that
item's error outcome, and a null/empty item list is an empty response. The
integration chunk controller (`IntegrationAccessController`) got the same two legs
(uuid parse shaped per-item, non-Platform catch). Pinned in
`RecordApiTests.batchMalformedItemsArePerItemOutcomes` (five mixed items → five
verdicts, request 200).

### H-7P4 — the audit trail silently dropped events on any store failure

All three audit consumers wrapped `store.append` inside a catch-all — a Postgres
restart, pool exhaustion, or failover was classified "invalid event ignored", the
offset committed, and the event left a permanent silent hole in the compliance
trail (this session's own reactor flake — a missing `notification.delivered` row
under parallel container load — was this mechanism live). The consumers now follow
the notification consumer's convention: envelope-shape errors (unparseable JSON,
non-uuid fields, bad timestamps) are terminal; processing failures propagate so the
spine redelivers, with the append's `(event_id, occurred_at)` dedupe collapsing the
replay. `PlatformEventConsumer`'s missing-timestamp default was `Instant.now()` per
redelivery — defeating that dedupe and duplicating every replay of such an event; a
timestamp-less envelope is now malformed by contract. Pinned in
`AuditConsumerFailureTests` (2: store failure propagates out of all three
consumers; malformed envelopes stay terminal).

### H-7P5 — the webhook rate limiter keyed on the client-supplied X-Forwarded-For hop

`WebhookRateLimitFilter.clientOf` took XFF's *first* element — chosen by the client,
with no trusted proxy in the deployed topology (the gateway is the edge). Rotating
the header minted a fresh Redis key per request (the 60/min cap on the platform's
only anonymous route never bound — unlimited HMAC brute-force surface), pinning a
victim's address keyed *their* traffic into 429s, and CRLF in the header forged
multiline log entries. The key is now the socket peer, with the proxy-allowlist
path documented for whenever a trusted proxy actually exists. Pinned in
`WebhookRateLimitFilterTest.xffIsNotTrusted`.

### H-7P6 — a builder could roll prod back; and promote-an-old-version dodged the rollback gate

Two asymmetries in `LifecycleService`: (a) `promote`'s prod hop required the
platform admin, `rollback`'s did not — a tenant builder rolled a green,
storage-compatible prod pin anywhere with no admin, no reason, no acknowledgment;
(b) `promote` never compared the requested version to the current pin, so
promoting an *older* version deployed it through the plain suite gate — bypassing
the rollback door's storage-compatibility check and `dataMigrationAcknowledged`
requirement (dropping projection columns' queryable data without the mandated ack;
an admin could route the same move through staging-parity). Rollback now enforces
the admin hop symmetrically, and an older-version promote rejects
`CONFLICT_VERSION` naming the rollback door. Pinned in
`LifecycleTests.prodRollbackIsTheAdminHop` and `.promotingAnOlderVersionIsARollback`.

### H-7P7 — suite-run retention evicted promotion-gate evidence, permanently

`recordSuiteRun` trimmed to the newest 25 rows per suite *regardless of content
hash* — the gate needs the latest run matching a **published version's** hash, and
the draft (whose hash new runs record) can essentially never be brought back to an
old version's content. Twenty-six newer-hash runs evicted a published version's
green run forever; the only path forward was an audited admin override — the
override channel filling with noise that isn't an override decision. Retention is
now hash-exempt: a run whose hash appears in `md_versions` never leaves (per-suite
newest-25 still bounds draft churn). Pinned in
`LifecycleTests.retentionKeepsPublishedVersionGateEvidence` (30 newer-hash runs,
then promoting v1 still admits).

### M-7P1 — deleting an app leaked every state machine, SLA, job, and workflow row

Every child table carried `REFERENCES md_apps(id) ON DELETE CASCADE` except
`md_definitions` — no FK at all — so app deletes cascaded entities/pages/versions/
environments but leaked the kind-discriminated branch documents forever (outbound
webhook URLs and credential ids included), while the cascade simultaneously
destroyed the `md_environments` rows that named the still-running sandbox tenants.
V10 adds the FK (with an orphan sweep first, so the constraint lands on databases
that already hold pre-FK orphans). Pinned in `deleteAppCascades` (asserts the
branch row exists before, and is gone after, the delete).

### M-7P2 — page optimistic locking was bypassable (null revision) and racy (check-then-act)

`putPage` skipped the revision check entirely when the incoming save omitted it,
and even with a token the check was a SELECT followed by a blind upsert — two
builders holding revision N both passed and the second silently won (V8's trigger
maintained the counter but nothing made it a guard). An update now *requires* the
token, and the upsert is conditional (`DO UPDATE … WHERE md_pages.revision = ?`)
with a zero-row write surfacing as the 409 — never a silent no-op, even for two
racing first-saves. Pinned in `pageDefinitionLifecycle` (null-revision 409; two
same-token saves → exactly `{200, 409}`).

### M-7P3 — the file service's verdicts were TOCTOU: a replayed PUT swapped the bytes behind a clean scan

The presigned PUT (15-minute validity) and the download path addressed the *same*
object key, and `presignDownload`/`content` authorized purely off the DB row: after
`complete` recorded `clean` + checksum, re-PUTting EICAR (or any substitution) at
the still-valid upload URL changed what downloads served — AV and checksum
integrity both bypassed, silently. Completion now finalizes the verified bytes
under a content-addressed key (`<tenant>/<id>/v/<checksum>`, server-side `copy`)
that the upload URL can never address, and downloads serve only that key; rows
completed before the finalization existed are healed lazily — the staging bytes are
re-hashed, drift means tampering (denied audibly, `file.tampered` outboxed), a match
re-finalizes. Pinned in `FileServiceTests.replayedPutCannotSwapVerifiedBytes` and
`.tamperedStagingObjectRejects`.

### M-7P4 — the size cap was advisory; `complete()` buffered the object before checking

`beginUpload` trusted the declared size, the presigned PUT signed no
`Content-Length`, and the real check ran after `readAllBytes()` had materialized
the whole object — a single 3 GB PUT was an OOM vector against a 768 Mi pod, and
unlimited PUTs had no quota at all. `StoragePort` grew `size()` (statObject);
completion stats before it gets. Pinned in
`FileServiceTests.sizeCapRejectsOversizeObjects` (declared 10, stored 2048 →
rejected without a verdict).

### M-7P5 — attachments were completable/rebindable by any same-tenant user

`complete` checked only tenancy: anyone holding an id could complete another
user's upload — with a bogus checksum *deleting the victim's bytes* — and the
controller then bound the attachment to any entity/record the caller named,
rewriting a confidential record's binding onto a record the attacker can read (the
read gate then hands over the presigned URL). Completion is uploader-only, and
bindings are write-once (`entity IS NULL AND record_id IS NULL` guard). The
`GET /{id}` metadata read also skipped the §9 read gate the download enforced —
any same-tenant user learned which record carries which file — and now applies it
uniformly (bound → owning record's authorization; unbound → uploader only). The
internal `content()` leg now refuses quarantined rows like the user path. Pinned in
`FileServiceTests.completionIsUploadersOnly` and `.bindingsAreWriteOnce`.

### L-7P1 — the SMTP boundary carried no line discipline (CRLF header injection)

`SmtpEmailPort` performed no sanitization of header-bound values; the resolved
mail stack (jakarta.mail-api 2.1.5 + angus-mail 2.0.5) serializes CR/LF in a
subject or attachment filename into a real standalone `Bcc:` header line. Today's
callers feed validated values, but the documented `${record.*}` template growth
path puts user free text one template edit from the subject — the boundary is now
safe regardless (CR/LF/NUL folded to spaces on `from`/`to`/`subject`/filename).
Pinned in `SmtpEmailPortHeaderTest` (wire-serialized message contains no injected
header line).

### Recorded open (mechanisms verified, not fixed this pass)

- **Metadata lifecycle**: promote/rollback apply across provisioner + env-tenant
  stores + pins non-atomically — a failure between legs orphans a provisioned
  sandbox tenant or leaves the env serving a version the control plane can't see
  (needs an intent row + idempotent provision); `metadata.published` is sent
  synchronously inside the publish transaction (broker outage holds the DB
  connection 10 s per publish; send-succeeds-commit-fails emits a phantom event —
  wants the outbox pattern the other services already use);
  `deleteEntity`/`deletePage` skip the save-validation pass, so deleting a
  referenced entity wedges the draft's next publish (validation should run on the
  post-delete candidate, naming the referencing definitions); a non-empty list
  branch can never be emptied via PATCH (absent-or-empty keeps current — the last
  dashboard/state machine/etc. is unremovable); concurrent publishes race on the
  version number and the loser surfaces as a raw 500 (unique constraint holds —
  shape it as CONFLICT_VERSION).
- **Data runtime**: unique-field parity — the pre-check compares text while the
  index compares numerics (`10` vs `10.00`), constraint violations are shaped
  friendly only on the parent-create leg (updates/inline children 500), and the
  shared-projection unique index has no `entity_id` (two same-named entities in
  one tenant collide); the event payload carries no before/after and roll-up
  recomputes rewrite parents with no `record.updated` (subscribers never fire for
  them); roll-up change detection is scale-sensitive `Objects.equals` (AVG churns
  the parent version) and roll-up values bypass the field's authored scale; the
  webhook upsert is check-then-act (document that upsert keys must be unique).
- **File service**: stored Content-Type is client-controlled and presigned GETs
  pin no `response-content-disposition` (inline HTML/SVG on the storage origin —
  sign `response-content-disposition: attachment` + `response-content-type`);
  no deployment path enables ClamAV (chart env lacks `NOVAFORGE_CLAMAV_ENABLED`
  and the MinIO/Postgres endpoints — every deployed profile records
  `virusScan: skipped`); abandoned uploads are never reaped and `fl_grants` is
  write-only (a scheduled reaper keyed on the grant expiry).
- **Gateway/audit/notification**: `X-Tenant-Id`/`X-Actor-Id` pass through
  unstripped on anonymous traffic (no consumer today — strip at the edge before
  one exists); `/actuator/prometheus` is anonymous on the internet-facing
  component (gate it or move it to the scrape network); internal sends use a
  random `event_id` (no idempotency key — caller retries duplicate inbox rows and
  emails); the audit trail's monthly partition rotation is unimplemented (the
  DEFAULT partition grows forever).

### Verification (this pass)

Module suites green on the podman socket: data-runtime engine (21, incl. the new
`SequenceServiceTests` 4) and api (81, incl. the two new RecordApiTests pins and
the re-pinned cached-sequence draw), audit-service (6, incl.
`AuditConsumerFailureTests` 2), gateway (14, incl. the XFF pin), metadata-service
(49, incl. the three new lifecycle pins and the two extended definition-lifecycle
pins), file-service (10, incl. the five new pins), notification-service (11, incl.
`SmtpEmailPortHeaderTest`). Full serial `./mvnw verify` + frontend workspace runs
recorded in IMPLEMENTATION.md's closeout. The audit-service reactor flake that
motivated H-7P4 reproduced once under `-T 1C` parallel container load and never
again after the fix (and in isolation before it).

## Eighth Pass — 2026-08-31 (closing the seventh pass's recorded-open set: the tractable highs)

The seventh pass's recorded-open list, worked top-down; eight more defects closed,
each pinned. The structural items (non-atomic multi-system promotion, the
in-transaction Kafka publish wanting the outbox pattern, per-app unique-index
scoping, audit partition rotation) remain recorded open.

### H-8P1 — numeric unique fields: a text pre-check against a numeric index, and unshaped violations on every leg but the parent create

Two parity holes compounded. The uniqueness pre-check compared jsonb text
(`data->>? = ?`), so a create sending `10` passed against a live row storing
`10.00` — the projection's unique index compares cast numerics, where they collide —
and the enforcement shaping existed only on the parent-create leg: the same lost
race on the user PATCH, integration update, hook update, inline-child, or roll-up
legs surfaced as a raw `DataIntegrityViolationException` 500 (and aborted whole
batches, H-7P3's shape). Numeric fields now pre-check numerically
(`numericValueExists` — regex-gated cast, total on legacy poisoned rows) and every
insert/update leg shapes violations through the shared field-scoped error
(`insertShaped`/`updateShaped`). Pinned in
`RecordApiTests.numericUniqueScaleCollisionIsFieldScoped` (create and update legs).

### H-8P2 — AVG roll-ups: 34-digit scale churned the parent's version on every write, and the stored value bypassed the field's authored scale

The create path computed roll-ups in memory at `MathContext(34)`, the update path
in SQL at Postgres' aggregate scale, and the change detector was
scale-sensitive `Objects.equals` — `15.5 ≠ 15.50`, so every parent write that
touched nothing roll-up-related still rewrote the parent (version churn → CAS
conflicts for concurrent readers, `updated_by` attribution to the wrong actor, and
— with H-8P3 — double events). The stored value also rode past the coercer's
scale caps (a 34-digit AVG into a DECIMAL(18,4) field). Detection is now
`compareTo`-based (`rollupMoved`) and both aggregate paths normalize to the field's
authored scale (`normalizeRollupScale`, HALF_EVEN — the same rule every writer
passes). Pinned in `avgRollupRidesAuthoredScaleWithoutVersionChurn` (scale-shape
assertion + exactly-one-version-bump).

### H-8P3 — roll-up recomputes mutated parents with no event: subscriptions and audit never saw them move

`recomputeRollupsIfChanged` rewrote the parent's data/version/`updated_by` with no
`record.updated` on the spine — a workflow subscribed to the parent entity (the
documented event-start surface) never fired for roll-up-driven changes, and the
audit trail attributed nothing. The recompute now publishes `record.updated` for
every parent it actually rewrites, attributed to the initiating actor; the writer's
own update path suppresses the duplicate (its publish follows in the same
transaction). Pinned in `standaloneChildWritePublishesParentRollupEvent` (outbox
row for the parent + the roll-up value moved).

### M-8P1 — deleting a referenced entity wedged the draft

The delete path was the only writer that skipped the save-validation pass every
other path runs: removing an entity referenced by a page, state machine, report,
permission branch, or mapping left the draft failing validation — publish and every
re-save blocked until each referencing definition was hand-repaired. The
post-delete candidate now validates, naming the holder. Pinned in
`deleteReferencedEntityRejects` (referenced rejects naming the branch, the draft
still publishes, an unreferenced entity deletes freely). `deletePage` stays as-is:
pages are leaf documents — nothing references them, so their deletion cannot
dangle a reference.

### M-8P2 — a concurrent publish's loser was a raw 500

Two publishes of one app computed the same next version; the unique
`(tenant, app, version)` constraint correctly rejected the loser — as an unhandled
`DataIntegrityViolationException` rendered `500 INTERNAL`. The lost race now
shapes as `409 CONFLICT_VERSION` with a retry instruction. (Not race-pinnable
without interleaving hooks; the catch is the seventh pass's H-7P3 pattern applied
to the version allocation.)

### M-8P3 — file downloads: no forced disposition, and the stored Content-Type was fully client-chosen

Presigned GETs pinned nothing about the response: the storage origin served the
PUT-time Content-Type (client-controlled) with no `Content-Disposition` — an
uploaded HTML/SVG rendered inline on the storage origin, attacker-scripted.
Download presigns now carry signed `response-content-disposition: attachment` +
`response-content-type: application/octet-stream` overrides, and upload content
types normalize to a validated bare `type/subtype` pair (parameters dropped,
malformed shapes rejected). Pinned in
`downloadsForceDispositionAndContentTypesAreShaped`.

### M-8P4 — the gateway forwarded client-supplied identity headers when no tenant was derived

`TenantHeaderFilter` overlaid `X-Tenant-Id` for token-derived tenants but passed
the raw request through otherwise — on the anonymous webhook route a client-sent
`X-Tenant-Id`/`X-Actor-Id`/`X-Event-*` rode upstream verbatim. No service reads
them today (the tenant is always re-derived from the claim), but the edge contract
"identity headers downstream are the platform's own" now holds unconditionally:
the strip set is dropped from anonymous traffic and owned by the filter on
authenticated traffic. Pinned in `TenantHeaderFilterTest` (×2).

### L-8P1 — internal sends had no idempotency key: caller retries duplicated inbox rows and emails per recipient

`deliverDirect`'s inbox insert used a fresh random `event_id` per attempt — the
`(tenant, user, event)` dedupe could never collapse a replay, so a retried report
job or a 5xx-retried send duplicated every recipient's inbox row and email. The
surface accepts a `deliveryId` key: a keyed replay collapses both legs. The
scheduled-report chain threads the scheduler's fired window end-to-end (job
`fire(job, window)` → `RestReportTarget` → reporting `/deliver` → notification) —
a retried window collapses, the next cron window delivers fresh; the
job-completed leg keys on the job id. Unkeyed sends behave exactly as before.
Pinned in `NotificationTests.internalSendKeyedReplayCollapses` + the scheduler
stub's delivery-key assertion. (Known limit, recorded: email-only recipients of
unkeyed sends have no inbox row to dedupe on.)

### Verification (this pass)

Module suites green on the podman socket: data-runtime api (24 — three new pins,
the AVG/batchLot fixture additions) and engine (21), metadata-service (51 — the
new referenced-entity-delete pin), notification (12 — the keyed-replay pin),
gateway (16 — two edge-strip pins), scheduler (11), file-service (11 — the
disposition/content-type pin), reporting and integration unchanged-green (the
delivery-key pass-through). `ConditionalRollupTests` re-pinned numerically: its
text assertions held the *incidental* scale-0 rendering of summed roll-ups
("0"/"15") — H-8P2 stores the field's authored scale, which renders 0.0/15.0 with
the same value; the numeric compare is the contract. Full serial `./mvnw verify`
recorded in IMPLEMENTATION.md's closeout.

### Still recorded open (structural, next passes)

The promotion chain's multi-system atomicity (intent row + idempotent provision +
reconcile), `metadata.published` inside the publish transaction (the outbox
pattern), per-app scoping of shared-projection unique indexes (two same-named
entities in one tenant), the unremovable-last-list-branch PATCH semantics
(absent-vs-empty needs a presence-preserving patch shape), the webhook upsert
fence, event payloads' before/after depth, abandoned-upload reaping + MinIO
lifecycle, the deployed ClamAV/MinIO/Postgres env in the helm chart, edge
prometheus gating, and audit partition rotation.

## Ninth Pass — 2026-08-31 (the metadata outbox: the recorded-open set's highest-value item)

### H-9P1 — `metadata.published` rode inside the publish transaction

`DefinitionService.publish` is `@Transactional`, and the Kafka send happened before
commit (`kafka.send(record).get(10, TimeUnit.SECONDS)`): (a) a broker outage held
every publish's DB connection — and the `md_apps` row lock — for the full 10-second
timeout before rolling back, so a burst of publishes under a broker blip exhausted
the pool and took the metadata service's reads down with it; (b) a send that
succeeded just before a rollback (deadlock, connection drop) emitted
`metadata.published` vN for a version row that never existed — the Data Runtime
materializer, scheduler registry, and reporting caches consuming a phantom version,
with no outbox and no compensation. The publisher now enqueues the envelope on a
transactional outbox (`md_event_outbox`, V11) that commits atomically with the
version; `MetadataOutboxRelay` (the PHASE-4 §2 pattern every other eventing service
already rides) delivers at-least-once to `novaforge.metadata`, keyed
`tenantId:appId`, and retries until the broker returns — unpublished rows never
leave, so a broker outage now *delays* the announcement instead of taking the
service down or emitting phantoms. Retention matches the other outboxes (published
rows drop after `retention-days`). The delivery semantic change is deliberate and
recorded: the old "a broker outage fails the publish audibly" posture cost pool
stability for no durability gain. Pinned in
`DefinitionLifecycleTests.publishEventsRideTheOutbox` (row enqueued with the
version → relay marks it published → the spine subscriber sees the same envelope);
the suite's existing spine assertions now transitively exercise the relay.

### Verification (this pass)

metadata-service 52 green (the new outbox pin; the hermetic smoke slice stubs the
outbox's JdbcTemplate — it excludes the DataSource autoconfiguration); full serial
`./mvnw verify` green end to end (all 23 reactor modules, container suites on the
podman socket).

### Recorded open after this pass

The promotion chain's multi-system atomicity (intent row + idempotent provision +
reconcile), per-app scoping of shared-projection unique indexes, the
unremovable-last-list-branch PATCH semantics (needs a presence-preserving patch
shape — an API design decision), the webhook upsert fence, event payloads'
before/after depth, abandoned-upload reaping + MinIO lifecycle, the deployed
ClamAV/MinIO/Postgres env in the helm chart, edge prometheus gating, and audit
partition rotation.

## Tenth Pass — 2026-08-31 (the recorded-open set, take two: five more close)

### H-10P1 — no deployed profile enabled ClamAV, and the file service booted against localhost defaults

The helm chart carried only the two auth variables — no `NOVAFORGE_CLAMAV_*`, no
`NOVAFORGE_MINIO_*`, no `NOVAFORGE_POSTGRES_*`, and the datasource credentials were
not env-bound at all — so every deployed profile ran with scanning off (every upload
recorded `virusScan: skipped`, which the download door happily serves) against
unreachable localhost infra. The chart now wires the in-cluster endpoints (Postgres,
MinIO, clamd at `novaforge-clamav`), scanning **on** for deployed profiles
(fail-closed: an unreachable clamd fails uploads, never waves them through), and the
DB/MinIO credentials ride the fail-closed secret posture (documented create-secret
steps, mirroring the auth pair). `application.yaml` binds the datasource to
`NOVAFORGE_FILE_DB_USER/PASSWORD` with the compose defaults intact.

### H-10P2 — the audit trail's monthly partition rotation: promised by the schema, implemented by nobody

V1 declared "month partitions rotate forward" and a DEFAULT partition "before the
next month's partition is added" — nothing ever added one; every row landed in
`audit_events_default` forever. `AuditPartitionRotation` (twice daily, idempotent)
creates the current and next month's partitions — and for months whose range already
holds default-partition rows (always true for the live current month on a stack that
ran pre-rotation, since Postgres rejects `CREATE … PARTITION OF` over a default
partition whose contents violate the new bounds) it runs the standard move-and-attach
path in one owner transaction: standalone twin with the parent's key and indexes,
in-range rows moved across, the default emptied, ATTACH. It runs as the database
owner (`spring.flyway.user` — V2's design: the runtime role is INSERT/SELECT and
cannot DDL); new partitions inherit V2's default-privilege grants. Failures log and
retry; inserts stay total through the default either way. Pinned in
`AuditTrailTests.partitionRotationCreatesMonthsAhead` (both months exist, the
runtime role still writes the current month's partition, live traffic flows — the
pin found the default-partition-row conflict live, which is what forced the
move-and-attach path into the implementation).

### M-10P1 — `/actuator/prometheus` was anonymous on the internet-facing component

The L-TP7 note's mitigation ("these service ports NEVER leave the cluster") held for
the backend services but not for the gateway itself — the edge is by definition
exposed, and `/actuator/**` was permitAll: per-route volumes, upstream latencies,
and infra detail were anonymously scrapeable. Health/info stay anonymous (probes,
humans); the exposition surface now requires the platform's own token. Pinned in
`GatewayApplicationTests.prometheusIsNotAnonymous` (anonymous 401 problem+json,
scoped token 200, health still anonymous).

### M-10P2 — abandoned uploads were never reaped, and the grant ledger was write-only

A client walking away between the upload grant and completion left a `pending` row
plus an orphaned object forever (`remove` ran only on checksum mismatch/oversize) —
unbounded table and bucket growth on busy tenants. `AttachmentReaper` (hourly)
removes pending attachments past their grant window (creation + presign window +
slack — every grant on them is dead by then), drops their rows and grant-ledger
entries, and outboxes `file.upload.expired` so the cleanup is auditable; the grant
ledger prunes past the retention window. Completed rows and in-window uploads never
touch it. Pinned in `FileServiceTests.abandonedUploadsReap`.

### M-10P3 — record events carried no notion of what changed

The payload held only ids: a `record.updated` consumer could not know what changed,
a `record.deleted` consumer could not know what left — the trail recorded the
movement of a black box. Every update leg (user, integration, hook/principal,
roll-up recompute) now carries `changed` — each changed field's `[before, after]`
pair, null-tolerant (a field moving from null is content, not an NPE — the pin run
caught `List.of` rejecting it) — and every delete leg (user, replace-children,
cascade) carries `before`, the deleted record's data. Pinned in
`RecordApiTests.recordEventsCarryChangeMetadata`.

### Verification (this pass)

audit-service 7 (5+2, incl. the rotation pin), gateway 19 (incl. the prometheus
pin), file-service 12 (incl. the reaper pin), data-runtime api 26 (incl. the
change-metadata pin), engine 21; the chart values parse with all twelve env
entries (no cluster deploy available here — the chart's posture is
documented-for-operator, like the existing fail-closed secret steps). Full serial
`./mvnw verify` recorded in IMPLEMENTATION.md's closeout.

### Recorded open after this pass

The promotion chain's multi-system atomicity (intent row + idempotent provision +
reconcile) and per-app scoping of shared-projection unique indexes. Two of this
pass's four recorded items closed in the same session's addendum below.

### Addendum — the last two recorded items close too

**The webhook/import upsert fence becomes validation.** Upsert keys resolved by
lookup-then-write, and only the key field's unique index turns a concurrent
same-key delivery into a shaped retry instead of a silent duplicate — but nothing
required the key to be unique. Save-validation now rejects an upsert `keyFields`
entry that is not declared `uniqueness: true` (webhook mappings and import
mappings alike), naming the fence in the message. The ERP corpus already keyed on
a unique field; the fixtures that did not now declare it. Pinned in
`DefinitionValidatorTest.integrationsRuleMatrix` (a non-unique key field rejects
with "upsert key fields must be unique").

**The last list item is removable through the API.** `AppDefinition`'s canonical
constructor normalizes absent branches to empty lists, so the PATCH merge could
never distinguish "omitted" (keep) from "emptied" (clear) — `{"dashboards": []}`
silently kept the branch, and the SPA's delete-last-dashboard never worked. The
PATCH surface now binds a presence-preserving `AppPatch` (null keeps, an explicit
empty list clears, non-empty replaces; sub-branch presence inside
`integrations`/`permissionSet` is whole-branch replace — the RBAC editor
round-trips the full set, so its save shape is unaffected, and an all-empty
permissionSet that would wipe every role fails save validation loudly). The first
draft of the DTO missed `gapLog` — `changeSetRendersResolvedGaps` caught the
dropped branch live, which is the coverage the pin set now guards. Pinned in
`appPatchEmptyListClears` (absent keeps; `[]` clears the last dashboard;
untouched branches stay) plus the existing `appPatchKeepsPermissionSet` legs.

Verified: metadata-model (incl. the validator pin), metadata-service (53, incl.
the AppPatch pin and the gapLog coverage), integration-service, data-runtime —
all green; full serial `./mvnw verify` recorded in IMPLEMENTATION.md.

## Eleventh Pass — 2026-08-31 (the two deep-structural items close)

### H-11P1 — per-app unique-index scoping: two same-named entities in one tenant cross-collided

Projection tables are shared per bare entity apiName across apps and tenants, and
the partial unique index was `(tenant_id, target) WHERE NOT deleted` — no entity
discriminator. Two published apps in one tenant defining the same entity apiName
with a unique field (the ERP and the A/R demo both defining `Invoice`, exactly the
shape the tenth-spec review's `App.Entity` disambiguation enabled) cross-collided:
the app-qualified pre-check passed while the index rejected app B's legitimate
value on app A's row — an unexplained uniqueness failure blocking legitimate
writes. Projections now carry the owning `App.Entity` key (`entity_id`, a system
column: new tables declare it, the reconcile backfills it onto pre-existing tables
from the base rows and drops orphaned projection rows before asserting NOT NULL),
and every unique index is `(tenant_id, entity_id, target)` — the `_app`-named twin
retiring its tenant-wide predecessor through the managed-index sweep. Non-unique
indexes and the trigger's sync set are unchanged (multi-app sharing of one table is
the design, only uniqueness needed the scope). Pinned in
`MaterializerTests.perAppUniqueScoping` (same tenant, same value, two apps — both
land; within one app still exclusive; the pre-entity_id migration leg restores
nulled keys from the base rows) and the definition assertion in `createsProjection`.

### H-11P2 — the promotion chain's multi-system atomicity: intent journal, keyed provisioning, boot reconcile

Three legs, closing both recorded failure windows:

- **The intent row lands first (V12).** The first promotion of an environment wrote
  nothing before the remote calls — a crash between provision and pin was invisible
  and every retry provisioned a *second* sandbox tenant (random names, random
  credentials — unreachable except by platform admin). `md_environments` gains
  `status`/`provision_key`; `recordProvisionIntent` writes the row (version pinned,
  identity blank, status 'provisioning') *before* the provisioner runs, and
  `completeProvision` fills the identity. A dangling intent is a visible stuck
  state the reconciler logs loudly.
- **Provisioning is keyed on (tenant, app, env).** The provisioner's names derive
  deterministically from the key (the source workspace's tenant joins the interface),
  so a retry adopts instead of leaking: the runtime's admin API grows a by-name
  tenant lookup (`GET /api/v1/admin/tenants?apiName=…`, platform-admin) used
  adopt-before-create; the admin credential regenerates onto the same deterministic
  username (Keycloak user provisioning was already idempotent with password reset);
  and an app left by a partial attempt is retired and re-imported (`md_apps` pins
  one apiName per tenant). Every leg of a retried promotion converges on the same
  environment identity.
- **The boot reconcile.** A promote/rollback dying between the environment tenant's
  publish and the pin left the environment serving a version the control plane
  could not see — the prod parity check then rejected the matching promote. On
  ready, `EnvironmentReconciler` compares every environment's pin against its
  tenant's actual latest publish (a local read — the environment's app rows live in
  this store), realigns, and records a `reconcile` promotion row (V12 extends the
  kind CHECK) under a named system actor; one environment's failure never blocks
  the others.

Pinned in `LifecycleTests.crashedProvisionRetriesConverge` (a promotion that dies
*after* the remote provision lands retries to the same deterministic environment
tenant — one row, active, identified) and `.bootReconcileAlignsDriftedPins` (an
environment-tenant publish the pin never saw realigns at boot with an audited
reconcile row). The suite's provisioner stub is keyed like the real one.

### Verification (this pass)

data-runtime storage (11 — the scoping pin; engine 21, api 26 unchanged-green),
metadata-service 29 green across both suites (the two atomicity pins; the suite
provisioner stub rewritten to keyed semantics). Full serial `./mvnw verify`
recorded in IMPLEMENTATION.md's closeout.

### Recorded open after this pass

None. The audit trail's recorded-open list — every finding from passes 7–11 — is
closed and pinned. The helm chart's env posture remains operator-verified by
design (documented create-secret steps; no cluster deploy exists in this
environment to render against).

## Twelfth Pass — 2026-08-31 (adversarial re-audit: charts rendered, newest code reviewed, frontend swept)

Three fresh adversarial audits (this session's newest code; the record engine/query
path re-swept; the SPAs — previously the least-audited surface). Fourteen more live
defects closed; the deferred M11/M12 recorded as an explicit decision.

### H-12P1 — the helm charts had never rendered: 22 templates were invalid YAML

`helm template` (via a containerized helm — no cluster needed) failed on the first
chart: every template used `key: {{ include … | indent N }}` / `key: {{ toYaml … |
nindent N }}` — the expansion's first line lands on the key's line, which is not
YAML. All 22 templates across 12 charts fixed to the canonical `key:` +
`{{- … | nindent N }}` form; every service chart and the umbrella (after
`helm dependency build` — the standard deploy step, now documented by this record)
render clean, and the rendered file-service Deployment carries the tenth pass's
ClamAV/MinIO/Postgres env and secretKeyRefs verbatim — the "operator-verified"
posture is now render-verified.

### H-12P2 — the entity_id migration would have broken live inserts mid-pass

`ensureEntityKey` set `entity_id NOT NULL` while the previous code version's sync
trigger (which inserts without the column) was still installed — every
`rec_records` insert between the `SET NOT NULL` and the function swap would abort
with a NOT NULL violation, a window spanning the full non-concurrent index builds.
The migration is staged now: the column lands nullable + backfills first, the
trigger swap happens in `applySyncMachinery`, and `SET NOT NULL` runs last with a
final stamp and a fail-soft retry (unresolved rows defer to the next pass —
inserts keep working; the per-app unique index treats NULLs as distinct until it
converges).

### H-12P3 — the cached sequence's last-slot race served a number outside the window

`drawCached` checked exhaustion BEFORE the increment: at `current == max` two
threads both pass, the loser's `getAndIncrement` returns `max+1` — outside the
window — and the next allocation serves `max+1` again: a deterministic duplicate.
The check now happens after the increment (overflow → fresh window, which is an
atomic INCRBY range exclusively the claimer's → retry). Pinned with a true
8-thread race over tiny blocks (`lastSlotRaceNeverDuplicates` — 512 draws, all
distinct; gap-aware bound, since wasted overflow draws are cached mode's contract).

### H-12P4 — event payloads exfiltrated HIDDEN field values to outbound webhooks

The eleventh pass's `changed`/`before` legs shipped every changed field's values
with no field-security shaping — and the OutboundDispatcher posts raw payloads to
external endpoints. A field the writing actor cannot read (HIDDEN) never rides the
event it causes now: the change/delete metadata is shaped through the same
`strip` predicate the read doors use, on every publish leg (the hook/principal leg
shapes through the initiating actor from the bound context).

### H-12P5 — integration and flow-driven writes never recomputed parent roll-ups

The StockLedger/Item fix (standalone child writes recompute parent roll-ups) was
wired to the user doors only: webhook/import writes and flow `createRecord`/
`updateRecord` steps fed child rows all day while the parents' aggregates sat
stale (silently wrong money). `recomputeParentRollups` now runs on the integration
doors (with prior data, so re-parenting refreshes both sides) and both principal
paths.

### H-12P6 — beforeSave hook writes bypassed coercion entirely

Validation ran, then hooks mutated the record, then the write persisted verbatim —
a `setField`/script value of the wrong shape (the unbound-template class the
principal paths fixed in pass 7) poisoned typed fields on the human doors too; the
advisory run proved it live: the ManualHook fixture's mis-parametrized hook had
been silently nulling `subject` into the database. All four doors now re-canonicalize
hook writes (`reCanonicalizeHookWrites`: coerce typed fields, re-run formulas and
record rules, reject shaped violations), and the create doors moved their
freeze/period guards BEFORE the hooks (a doomed write no longer fires connector
calls or approval tasks first) while the initial-state guard stays after them (it
validates the landing state — a transitionState hook cannot smuggle a non-initial
state; the StateMachine pin caught exactly that when the reorder first went too far).

### H-12P7 — the create door enforced neither readonly nor field-security writes

Update rejected writes to hidden/readonly fields; create accepted them — a
restricted role could launder values into fields it could never read or correct.
Both guards now run on create (the baked-in test inverted: the clerk's
hidden-`title` create rejects with "field is hidden"; the admin feeds the later
legs).

### M-12P1 — mediums closed in the same pass

- **Numeric ORDER BY on unpromoted fields** sorted lexicographically (9, 100, 10);
  the lowering now uses the numeric expression for numeric fields on the JSONB
  path (promoted columns were already typed).
- **Aggregate GROUP BY + LIMIT** truncated arbitrary hash-aggregate order — which
  groups survive differed run to run; the group keys are ordered before the limit.
- **An empty `in` filter** lowered to `()` — a raw SQL 500; the parser rejects it
  at the door.
- **Batch non-Platform verdicts** leaked raw exception messages (constraint names,
  value excerpts) into API responses; the verdict names the class, the log keeps
  the message.
- **Projection index-name collisions** ([totalAmount] vs [total, amount] both
  snake to `total_amount`; a field named `display`/`updated` collided with the
  fixed indexes) silently dropped declarations via `CREATE INDEX IF NOT EXISTS`;
  colliding names disambiguate with a suffix.
- **Roll-up parent CAS** killed concurrent child writes to one parent (a legitimate
  insert died as 409 with no conflicting edit of its own); the recompute re-reads
  the parent and retries once before surfacing.
- **`rollupMoved`** now compares any numeric carriers (BigDecimal vs Integer/Long
  across SQL/jsonb/coercer) — the pass's own pins caught the phantom-drift version
  churn live.
- **The provisioner's adopt path** now resets the env-admin credential through the
  idempotent user-provisioning leg — a crashed promotion's retry previously died at
  the password grant (fresh password, lost original) and the intent never cleared.

### Frontend (the first dedicated pass): 3 closed, the rest recorded

- **CRITICAL — typing "12." into any number field crashed the whole SPA**:
  `Decimal.parse` throws (the validity check treated it as returning undefined)
  and no error boundary existed anywhere. `Decimal.tryParse` makes the check
  total, and a render `ErrorBoundary` wraps both mounts (contained blast radius +
  retry).
- **EntityPage cross-entity bleed**: no `key` on navigation meant a previously
  loaded record rendered in another entity's "New form" — whose save PATCHed the
  wrong record with foreign data. Keyed by route identity; failed detail loads now
  render an error instead of a silent empty form.
- **ListLayout swallowed fetch failures as "No records yet"**: a backend blip (or
  the expired-token case below) presented every list as empty — failures render an
  alert now.

**Recorded open (frontend, each with the audit's mechanism)**: no token refresh
after page load (5-minute access tokens → every call 401s until a manual reload
discards unsaved state); PageBuilder never resolves `app.pages` (a second edit
session silently wipes prior customizations; entity-switch edits the wrong tree);
DashboardComposer PATCHes the whole branch per keystroke over a stale snapshot
(lost updates, cross-tab clobber); FileUpload is unwirable through the renderer (no
token reaches it, its result never binds back to the record); the renderer feeds
raw JSON to the expression twin (numeric/date slot rules always take the fallback
— readonly/required wrongly true); FieldLookup's blur writes raw typed text and
eats option clicks; KpiTile renders money through binary floats; builder saves
without catch on four screens (rbac/composer/lifecycle/i18n). Backend recorded
open: keyed-notification replays still re-email inbox-opted-out recipients (the
dedupe rides the inbox row); sharing criteria using `now()` disagree between the
Java doors (live instant) and the SQL lowering (start-of-day); period-lock and
parent-freeze checks are check-then-write without row locks.

### M11/M12 — the explicit decision

**Deferred indefinitely as accepted maintainability debt, not defects.** M11
(extract `novaforge-common-web` from the per-service web infra) and M12 (magic
limits → `@ConfigurationProperties`) have no behavioral delta — the second pass
already scoped them as "not a review-commit item", and a 10+ service structural
refactor mid-hardening trades zero fixed bugs for real regression risk. M13
(per-tenant circuit breakers) stays as-is by design (isolation). Revisit M11/M12
as a dedicated refactor pass after the defect trail goes quiet.

### Verification (this pass)

data-runtime engine (22, incl. the race pin), api (25 — `permissionSetEnforcement`
re-pinned for the create-door guard; ManualHook's fixture corrected to a legal
`expression` param, which is itself the H-12P6 proof), storage (12); frontend 149
vitest + typecheck green with the boundary/key/failure-surface changes; all 12
charts render via containerized helm. Full serial `./mvnw verify` recorded in
IMPLEMENTATION.md's closeout.

## Thirteenth Pass — 2026-08-31 (the frontend HIGHs close: tokens that live, pages that survive)

### H-13P1 — no token refresh past page load: every SPA call 401'd after five minutes

Refreshes ran only inside `restoreSession` — one per page load — while the realm
defaults issue five-minute access tokens: an SPA in normal use failed every list,
save, and report run with a 401 until a manual reload (discarding unsaved state).
Three legs: `PlatformClient` grew an optional `onUnauthorized` hook (one refresh,
one retry per request — a second 401 surfaces); `auth.ts` (both SPA twins) grew a
`sessionManager` — `token()` proactively refreshes inside the expiry margin,
`refreshOnUnauthorized()` is the client's hook, and all refreshes are single-flight
(N concurrent expired callers share one grant — a rotating refresh token replayed
N times would invalidate the session itself); both `main.tsx` mount sites wire the
manager as the token provider. Pinned in `client.test.ts` (401 → refresh → retried
request with the fresh token, two calls total; refresh-gives-up surfaces the 401)
and the new `runtime-ui/test/auth.test.ts` (single-flight across concurrent
callers, live tokens never touch the grant, unrecoverable refresh clears the
session and reports null).

### H-13P2 — a second page-builder session silently wiped saved customizations

The editor seeded from the L1 default and never read `app.pages`: reopening showed
no customizations, the revision counter was local fiction (first save always
revision 1), and one small edit + save persisted deltas-vs-default over the saved
page — destroying the prior session's work. The seed now resolves the SAVED page
(deltas or authored layout) through `resolvePage` with the server's revision
(`PageDefinition.revision` typed); editing state is keyed by page identity (an
entity/kind switch can no longer edit the old tree under a new name); dirty diffs
against the loaded baseline rather than the default (a customized page no longer
opens as "unsaved"); and the 409 rebase offers the server's actual saved page
(the shell refetches into props) instead of resetting to the default — rebasing no
longer discards the other editor's work. Pinned in `page-builder.test.tsx` (a
saved visibility overlay opens visible in the property panel; an edit saves
carrying the server's revision 3).

### M-13P1 — the frontend long tail

- **Four builder saves ran `try/finally` without a catch** (RBAC, dashboards,
  suite runs, i18n): a failed save un-busied the button and rendered nothing —
  indistinguishable from success. All four catch and render `role="alert"` errors.
- **FieldLookup's blur wrote the raw typed text as the field's value** ("Acme"
  became the foreign key) and closed the listbox before an option's click could
  land (blur fires between mousedown and click). Blur only closes now; options
  select on prevented mousedown; only ids are ever written.
- **KpiTile pushed money through binary floats** (`toLocaleString` over `Number`)
  against the file's own exact-decimal rule — values now render the exact text,
  grouping integers only.
- **The renderer fed raw JSON to the expression twin**: numeric and date slot
  rules always threw in the evaluator and fell back — visibility stayed visible,
  but readonly and required wrongly evaluated TRUE (fields frozen or forced that
  the server would accept). Record values are tagged before evaluation (numbers →
  exact Decimals, ISO strings on date-typed fields → tagged date/instants), the
  same decode the conformance corpus applies to its bindings.

### Recorded open after this pass

Frontend: DashboardComposer still PATCHes the whole branch per keystroke over a
stale `app` snapshot (failures now surface via the new catch; the local-draft +
explicit-save redesign remains); FileUpload still has no renderer wiring (no token
reaches it through `renderNode`, and its uploaded attachment id never binds back
to the record). Backend (from the twelfth pass): keyed-notification replays still
re-email inbox-opted-out recipients; sharing criteria using `now()` disagree
between the Java doors (live instant) and the SQL lowering (start-of-day);
period-lock and parent-freeze checks are check-then-write without row locks.
M11/M12 remain deferred by decision.

### Verification (this pass)

Frontend 154 vitest green (+5: the client retry pin, the three manager pins, the
saved-page pin) with typecheck clean; backend untouched this pass — full serial
`./mvnw verify` re-run green end to end as the turn's gate.

## Fourteenth Pass — 2026-08-31 (the recorded-open mediums close: notification, sharing parity, row locks, composer, uploads)

### H-14P1 — five catalog widgets never rendered through the runtime renderer at all

The fourteenth pass's upload-wiring pin refused to render and exposed the real
defect: five registry loaders used the bare-import lazy form
(`() => import("…") as never`), which resolves to the module namespace — React
`lazy` requires `{ default: Component }` and crashed with "Element type is
invalid … resolves to: undefined". `chart-widget`, `kpi-tile`, `report-table`,
`dashboard-grid`, and `file-upload` — every dashboard widget and the upload
control — could never render in the runtime SPA (dashboard grids fell to the
error boundary once that existed; before it, a white screen). All five loaders
now wrap their named exports. Pinned transitively: the upload-wiring pin renders
a real `novaforge.file-upload` node through the registry.

### H-14P2 — FileUpload is wired through the renderer: token leg + bind-back

The widget's upload path was unwirable in production — no token ever reached it
through `renderNode` (its schema props were optional and nothing supplied them),
and the uploaded attachment id stayed in component state, never binding to the
record (the save wrote no file reference). The renderer context grows a `files`
leg (`{ base, token }`); `renderNode` threads it to `file-upload` nodes along
with an `onUploaded` that binds the id back through `context.setValue(bind, …)`;
the widget accepts a live `bearerTokenProvider` (a frozen string would expire
mid-upload — the provider rides the thirteenth pass's refresh machinery);
`PlatformClient` exposes `bearer()` + its base; the runtime shell supplies the
leg. Pinned end-to-end: a rendered upload node carries the context's live token
on both authorized legs and `record.invoice` receives `att-1` on completion.

### M-14P1 — the keyed-notification email leg dedupes on its own marker

A keyed send to an inbox-opted-out recipient had no inbox row to collide on, so
nothing recorded the email — every keyed replay (a retried scheduler window)
re-emailed them. V4 adds `nf_email_deliveries (tenant, user, event_id)`; the
email leg claims it with `ON CONFLICT DO NOTHING` and skips on collision —
the same key semantics the inbox row always had. Pinned in
`keyedReplayNeverReEmailsInboxOptedOut` (replay delivers 0, exactly one email
and one marker; an unkeyed send still delivers).

### M-14P2 — sharing `now()` parity

The Java gates evaluate `now()` at the live instant while both SQL lowering
surfaces bound it truncated to start-of-day UTC — an actor could see a record by
id but not in any list for the rest of the UTC day when a criterion compared
`now()` against a time-of-day boundary. Both lowerings (`applySharing`,
`QueryLowering`) now bind `Instant.now(clock)`; `asOf` still shapes `today()`
for bucketed group-bys. (The roll-up aggregate path binds live already.)

### M-14P3 — period-lock and parent-freeze hold their rows

Both checks were plain reads: a period flipping to closed (or a parent
transitioning to terminal) between the check and the commit let the dated write
land inside the lock. `RecordStore.findForShare` locks the parent row and
`countValueForShare` wraps the count in a locking subselect (aggregates cannot
carry `FOR SHARE` directly) — the check and the write it guards now serialize
against the closing transaction. Both legs are exercised by every
`FreezePeriodTests` run (the SQL validity is what needed pinning; the
serialization is the mechanism).

### M-14P4 — the composer edits locally and saves against a fresh fetch

Every widget edit and keystroke PATCHed the whole dashboards branch immediately
over the mount-time `app` snapshot: two rapid edits raced (the second payload
built before the first save's reload reverted it), out-of-order HTTP applied the
older list last, and another tab's dashboard was wiped by the stale whole-branch
replace. Edits are local now (an edits map overlaid on the app prop, a dirty
marker, an explicit Save button); the save applies the edits through a mutate
callback the shell runs against a freshly fetched app, so only the edited
dashboard's slot is replaced. Pinned in `reporting.test.tsx` (an edit leaves the
browser; Save applies it; role-composition saves with the dashboard).

### Recorded open after this pass

None from the thirteenth pass's list. Standing, by decision or mechanism: M11/M12
deferred (maintainability, no behavioral delta); the outbox `md_event_outbox`
retention follows the shared pattern (verified in the ninth pass); the SPA
logout action remains unwired in the UI (session clears on tab close — the
storage contract). No known-open defect items remain on the books.

### Verification (this pass)

notification-service 13 green (+the marker pin), data-runtime api 26 / storage 12
/ engine 22 green with the locking reads, frontend 155 vitest (+2: the
upload-wiring pin, the composer local-edit pin) + typecheck clean. Full serial
`./mvnw verify` green end to end.

## Fifteenth Pass — 2026-08-31 (the adversarial re-audit: newest code first, then the unaudited services)

Three fresh audits: the fourteenth pass's own code, the workflow-service task/SLA
internals (not re-audited since the sixth pass), and reporting/script-engine/gateway.
Fourteen findings; the ten concretely-bounded ones closed and pinned this pass, the
rest recorded with mechanisms.

### H-15P1 — the fourteenth pass's own locks deadlocked the exact case they guarded

`findForShare` took FOR SHARE on the parent row and the same transaction then
UPDATED it through the roll-up recompute — two concurrent child writes to one
parent both acquired SHARE (compatible), then both requested exclusive for the
UPDATE: the textbook share→upgrade deadlock, turning a race the bounded CAS retry
handled cleanly into a raw 40P01. The lock is `FOR NO KEY UPDATE` now (writers
serialize at the check point; the roll-up UPDATE follows in the same mode without a
cycle), and the locking count's string surgery gained a shape guard (a LIMIT/OFFSET
tail would silently truncate the wrapped count — an undercount here would let a
locked period pass; it rejects loudly instead).

### H-15P2 — the script engine's execute surface was a connector-egress primitive

`/api/v1/scripts/execute` authenticated at user scope with no service gate, and the
connector sandbox opt-in rode the request body: any user token with pod-network
reach (no gateway route, but no NetworkPolicy either) could execute any connector
operation of their tenant under tenant credentials with a fully
attacker-controlled template — external writes the builder-authored model exists
to gate. The surface now requires the trusted service client (mirroring
`/scheduled`); the runtime's relay leg is the only legitimate caller.

### H-15P3 — a notify-only SLA breach permanently wedged the approval

`ESCALATED` is terminal and `wf_tasks.resolve` CASes on `status = 'OPEN'` — a
breach with no escalation target (§6's notify-only branch, or a step timeout
without `escalateTo`) terminalized the task, so the suspended instance could never
resume and no admin surface existed to unwedge it: the "parked with nothing
re-drivable" class, entering through the SLA path. A no-target breach now rides
the spine (`sla.breach`, metrics) and leaves the task OPEN and resolvable — the
flip happens only when a replacement will exist. Pinned end-to-end (breach →
still OPEN → the approval resolves → the record unwedges). The breach block also
became one transaction per task (the flip, events, and replacement previously
committed in 4+ separate transactions — a crash between them lost the replacement
with no retry).

### H-15P4 — task claim was last-writer-wins, and delegation narrowed nothing

The claim UPDATE matched any OPEN role task: concurrent claims both succeeded (the
loser never told), and any later role holder could silently steal an assigned or
delegated task. The claim is a CAS on `assignee IS NULL` now (the loser gets the
existing conflict path). Pinned. (Delegation's role-vs-assignee visibility question
is recorded below — the CAS closes the steal, the read-scope decision stands.)

### M-15P1 — the notifier's email marker rolled back with the batch

The fourteenth pass's marker inserted inside the `@Transactional` batch loop — a
later recipient's SMTP failure rolled back earlier recipients' markers while their
emails had physically gone, so the retry re-emailed exactly the people already
sent to (the guarantee the marker existed to provide). The claim now commits in
its own `REQUIRES_NEW` transaction before the send: it survives the rollback, and
a send-then-fail leaves the claim harmlessly held (one attempt per key —
at-least-once with a ceiling of one). The marker also became the SOLE gate on the
email leg (the old inbox-collision shortcut suppressed emails that had never gone
out — the original send may have had email off), and `nf_email_deliveries` gained
the outbox's retention window (one row per recipient per keyed send was otherwise
permanent growth).

### M-15P2 — the composer still wiped edits two ways

The save cleared the ENTIRE local edit map: edits made while the save was in
flight (after the click, before the reload) were deleted locally and never sent,
and New-dashboard's mutate ignored the edits entirely — creating a dashboard
silently discarded every unsaved widget/role edit. The save now clears only the
keys it sent; New-dashboard is disabled while dirty. Pinned (edit → New disabled →
save → New enabled → append lands with the edited dashboard intact).

### M-15P3 — the medium tail

- **Subprocess-nested user tasks never bridged**: the deploy gate validates user
  tasks recursively (findFlowElementsOfType) while the bridge resolved
  non-recursively (getFlowElement scans direct children only) — a subprocess task
  deployed cleanly, then sat unbridged forever with no inbox row and no log. The
  lookup is recursive and a failed resolution logs at warn, never silently.
- **One workflow's event-start failure poisoned the delivery**: `onRecordEvent`
  looped all matching workflows with no isolation — a throwing start aborted the
  subscriptions after it on the same event. Per-workflow try/catch (the claim row
  rolls back with the failed start, so redelivery retries exactly that one).
- **The webhook rate limiter missed the slash-less path**: the prefix check
  required the trailing slash while the route/security patterns match the exact
  form — `POST /api/v1/webhooks/inbound` was anonymous, proxied, and unthrottled.
  The guard matches the patterns' semantics now. Pinned.
- **Connector SSRF at the publish door**: `baseUrl` validated only `^https?://` —
  a loopback/link-local/private target (cloud metadata, actuator ports) dressed as
  a connector returned its body to the caller. The validator rejects internal
  targets: literal IPv4/IPv6 addresses resolve against the RFC ranges, and the
  internal-suffix hostnames (`.svc`, `.cluster.local`, `.internal`, `.localhost`)
  reject outright. The first cut also resolved real DNS names and failed closed on
  NXDOMAIN — the full verify caught it rejecting the ERP corpus's
  `bank.example.local` (an unresolvable-at-CI provider is normal at publish);
  rebinding through real DNS is the execution-time check's job, so the door checks
  literals only. Pinned (169.254.169.254 and localhost both reject).
- **Four east-west RestClients had no timeouts** (reporting→runtime, reporting→
  notifications, integration→reporting, the async-export leg): a hung upstream
  held the calling thread forever — and the job scanner runs jobs serially on one
  scheduler thread, so one black-holed export stalled every tenant's pipeline.
  All four are bounded (2s connect / 60s read).
- **The aggregated OpenAPI disabled its cache when degraded**: an upstream down
  made every request re-fetch all nine upstreams serially during exactly the
  incidents when they are slowest. A degraded document serves until a 10 s floor,
  then one single-flight refetch runs.
- **Quarantined uploads bound to the record**: the file-upload wiring wrote the
  attachment id on every completion — including `virusScan: "infected"`,
  persisting a reference to a file that can never be downloaded. The bind skips
  the infected case.

### Recorded open (mechanisms verified, deliberate scope)

The sandbox heap cap is a sampled process-wide tripwire (a single-statement
allocation bomb completes before any sample; per-context metering is Enterprise —
containment wants ADR-003's process-isolation pools, a scoped pass). The gateway
still has no request-body size cap on the anonymous webhook route (bounded body +
edge cap is the fix; compose/kind deployments front it today). The BPMN bridge's
single `resolution` variable cross-contaminates parallel-gateway/multi-instance
outcomes (per-task variable scoping is a contract change for authored
gateway conditions). The resume re-entry carries no idempotency key (a
remote-succeeds-local-commit-fails retry re-runs the approval subgraph once). The
escalation replacement's role is unvalidated at creation (a typo'd role is a
silent wedge — needs a role-exists lookup). Record-event consumers have no
configured error handler/DLT (Boot defaults: 9 zero-backoff retries then skip) —
the per-workflow isolation added this pass bounds the blast radius. Delegation
replacements keep the original role for visibility while `requireAccess` accepts
any holder of it (the CAS stops the steal; whether delegation should narrow the
read scope is a product decision). M11/M12 remain deferred by decision.

### Verification (this pass)

workflow 25 (+2 pins: notify-only breach, claim CAS), script-engine 12 (+1: the
user-token rejection), gateway 17 (+1: the slash-less path), metadata-model (+2
SSRF pins), data-runtime storage/api/engine green with the lock-mode change,
frontend 157 vitest (+2: the every-catalog-id render pin — which now guards the
five-loader regression class — and the composer New-dashboard pin) + typecheck
clean. Full serial `./mvnw verify` green end to end.

## Sixteenth Pass — 2026-08-31 (the bounded recorded-open set closes)

### H-16P1 — no request-size cap anywhere: the anonymous route was an unauthenticated OOM vector

The platform had no body limit — no route filter, no servlet max-* — and the one
anonymous route buffers the entire payload into a `byte[]` before HMAC
verification. `RequestSizeCapFilter` (edge, inside the rate limiter) rejects a
declared Content-Length over the cap with 413 before a byte is read, and truncates
chunked/lying streams at the cap (EOF at the limit — never a silent partial
payload). Default 10 MB (`novaforge.request-cap-bytes`). Pinned ×3: the 413
before-read, the chunked truncation reading exactly the cap (never the 4096 behind
it), and normal bodies passing untouched.

### H-16P2 — spine events were silently dropped: no error handler, no dead letters

Boot's listener defaults retry a failing record nine times with zero backoff, then
log-and-skip — for the workflow record consumer that is silent data loss (a
metadata blip while an event-start matched meant the process never started and the
offset committed anyway). `ConsumerErrorConfig` gives every workflow listener a
`DefaultErrorHandler` with real exponential backoff (1 s doubling to a 60 s
ceiling, ten attempts) and a dead-letter publisher: after the budget the record
lands on `<topic>.DLT.novaforge-workflow`, durable and replayable — never skipped.
Envelope-shaped failures (IllegalArgumentException) skip the retry budget and go
straight to the DLT — no backoff can fix a malformed payload.

### H-16P3 — the escalation target's role is now validated at breach

A replacement addressed to a role nobody holds (a typo'd ghost, or a role emptied
since authoring) was an OPEN task no inbox would ever match, with null timers so
it could never breach again — the approval wedged permanently, precisely when SLAs
were already being ignored. `RoleLookup` grew a `holdersOf` leg (the runtime's
existing by-role admin listing); at breach, an unheld target keeps the task OPEN
and resolvable while the breach rides the spine (the misconfiguration is visible).
An unreachable runtime answers "held" — the breach path's availability beats the
fence. Pinned (a ghost-role escalation stays OPEN, breaches audibly, and the
approval still resolves).

### H-16P4 — the resume re-entry is idempotent on the suspension's instanceId

The workflow side's remote resume and its local commit are one dual-write: when
the runtime leg succeeds and the workflow transaction fails to commit, the
approver's retry re-entered the engine and re-ran the approval subgraph —
duplicate state transitions, duplicate created records, duplicated notification
side effects. The `Resume` record and the `/api/v1/hooks/resume` request carry the
suspension's `instanceId`; the runtime records a `resume_claims` row (V6) inside
the resume transaction — the first execution inserts it, a retried delivery of the
same key answers `already-resumed` without re-entering (layering preserved: the
claim lives in the storage SPI behind an engine facade). Pinned end-to-end (the
retry answers already-resumed; the record's version does not move again).

### Recorded open after this pass

The sandbox heap cap remains the sampled tripwire (single-statement allocation
bombs; per-context metering is GraalVM Enterprise — containment wants ADR-003's
process-isolation pools, a scoped pass of its own). The BPMN bridge's single
`resolution` variable cross-contaminates parallel-gateway/multi-instance outcomes
(per-task variable naming is a contract change for authored gateway conditions —
a design decision, not a mechanical fix). Delegation replacements keep the
original role for visibility while `requireAccess` accepts any holder of it (the
claim CAS closed the steal; whether delegation should narrow the read scope is a
product decision). M11/M12 remain deferred by decision.

### Verification (this pass)

gateway 20 (+3: the cap pins), workflow 27 (+2: the unheld-target and
notify-only-breach pins now coexisting), data-runtime api 27 (+1: the resume
idempotency pin) with the layering rule green (the claim reached storage through
the engine facade), notification/script/model/engine/storage suites green. Full
serial `./mvnw verify` green end to end.

## Seventeenth Pass — 2026-08-31 (the two remaining recorded defect items close)

### H-17P1 — the BPMN bridge's per-task resolution variable

Every bridged task of an instance wrote ONE process-level `resolution` variable —
a parallel gateway's second completion overwrote the first, so the join's routing
saw only the last writer and one approver's outcome silently vanished (worse under
multi-instance: N completions left only the last). `wf_process_tasks` carries the
engine task's definition key (V6); each completion now also writes
`resolution_<definitionKey>` while the bare `resolution` stays for single-task
instances — authored gateway conditions keep working, and per-task conditions have
a stable name to address. Pinned with a real parallel-gateway journey: fork →
legal + finance → one approved, one rejected → both variables survive in the
process history with their own values (legal=APPROVED, finance=REJECTED) while the
instance completes.

### H-17P2 — the sandbox single-statement allocation bomb is bounded

The heap cap is a sampled, process-wide tripwire (two consecutive 25 ms samples) —
`'x'.repeat(2**28)` completes inside one statement before any sample, and 8
concurrent lanes out-allocate the shared heap before the watchdog fires
(empirically demonstrated in the fifteenth-pass audit). Two legs, per ADR-003's
containment direction: the script-engine chart pins `-Xmx512m
-XX:+ExitOnOutOfMemoryError` (against the 768Mi pod limit) and halves
`max-concurrent` to 4 — a guest OOM kills exactly this pod, never a
tenant-facing service, and the process-isolation pools remain the recorded growth
path for full per-execution isolation. And the guest OOM that reaches the host
now maps to the budget verdict (`ScriptBudgetExceededException`) instead of a
generic 500 — the caller sees "too big." Pinned with a real single-statement bomb
(256 MB in one builtin call) asserting the budget verdict.

### Also this pass: the registry render pin hardened

The every-catalog-id loop pin (fifteenth pass) was passing while its unmounted
widgets threw unhandled errors after their tests completed — jsdom's missing
canvas, a KpiTile without totals, a ReportTable without a run. The pin now gives
each data widget a minimal survivable props shape, KpiTile treats a missing
`totals` as "—" (a defensive fix in the component itself), and a suite-wide
canvas stub silences jsdom's noise (guarded for the node-env suites). The loop pin
is now genuinely asserting what it exists to assert: every catalog id renders
through the real registry with zero unhandled errors.

### Recorded open after this pass

Delegation replacements keep the original role for visibility while
`requireAccess` accepts any holder of it — the claim CAS closed the steal; whether
delegation should narrow the read scope is a product decision (the sixth pass's
delegation design deliberately kept the role for inbox visibility). M11/M12 remain
deferred by decision. The process-isolation pools for per-execution sandbox
isolation remain the recorded growth path beyond the -Xmx containment.

### Verification (this pass)

workflow 28 (+1: the parallel-gateway pin), script-engine 26 (+1: the allocation
bomb), shared 99 with the hardened loop pin, full frontend workspace green
(157 vitest + typecheck). Full serial `./mvnw verify` green end to end (23
modules).

## Eighteenth Pass — 2026-08-31 (the fan-out audit: three parallel adversarial sweeps + the deploy-posture leg)

The recorded-open defect list was empty, so this pass ran three concurrent
adversarial audits (data plane, frontend, remaining services) plus a deploy
hardening sweep. Thirty-plus candidates reported; each was re-verified against
the mechanism before fixing. Confirmed and closed: 6 highs, 8 mediums, 6 lows
across the backend, 13 frontend defects, and one production-posture gap.

### H-18P1 — the resume claim fence was broken three ways

The sixteenth pass's `resume_claims` fence had (a) the claim auto-committing
before the resume transaction ran — a failed resume left the claim standing and
every retry answered HTTP 200 `already-resumed` for a subgraph that never ran
(the permanent wedge the fence existed to prevent); (b) the INSERT's affected-row
count discarded behind a precheck SELECT — two concurrent deliveries both passed
the precheck and both ran; (c) the precheck filtering on
`(instance_id, approved)` while the conflict target is `instance_id` alone — a
replayed opposite verdict ran BOTH the approve continuation and the reject
subgraph. The insert count IS the claim now, and it rides
`resumeApprovalOnce`'s own transaction (a failed resume rolls the claim back).
Pinned ×2: the opposite-verdict replay collapses; the failed resume releases the
claim and the retry re-enters.

### H-18P2 — a missed `metadata.published` wedged a tenant's cached bundles forever

The resolver cached bundles with `computeIfAbsent` — no version check, eviction
by Kafka only. A dropped `metadata.published` delivery (outbox crash, consumer
rebalance gap) left the stale bundle serving forever on the qualified path while
the unqualified path's version-skipping search 404ed the entity outright,
permanently. The lifecycle promotion tail made the drop concrete:
`deployToEnvironment` ran publish, outbox insert, and pin as three separate
auto-commits — a crash between the first two skipped the env tenant's event
entirely. Two legs: the bundle load is version-checked against the index (the
30 s TTL refresh self-heals; staleness costs one window, never a restart), and
the promotion tail is one `TransactionTemplate` transaction (the manager arrives
as an ObjectProvider — the no-datasource smoke context has none). Pinned: a
version bump with NO eviction reloads on the next refresh — the new field
resolves, v2's new entity is reachable bare.

### H-18P3 — the SLA breach re-fired every scanner pass, forever

The two stay-OPEN breach branches (notify-only SLAs; escalation targets whose
role has no holders) emitted `sla.breach` and returned without any marker — the
still-open, still-overdue row was re-selected by every 5 s pass, each emission
with a fresh event id no consumer dedupe could collapse: a new inbox row and a
new email per recipient per pass, an unbounded spine/audit stream, and a
`novaforge.sla.breach` counter that climbed 0.2 Hz per wedged task. V7 adds
`sla_breached`, the warn path's conditional-flip pattern applied to the branches
that cannot terminalize. Pinned ×2: a second `scanOnce()` emits nothing for the
same task; both stay-OPEN branches one-shot.

### H-18P4 — the connector OAuth token cache leaked across tenants

`tokenCache` keyed by the credential's authored id alone — credential ids are
per-app strings with no cross-tenant uniqueness, so two tenants sharing a name
served each other's tokens: tenant B's provider call rode tenant A's OAuth grant
(a leak into A's provider account and a wrong-tenant side effect). The key is
`tenantId:credentialId` now. Pinned: a same-named credential in a second tenant
fetches its own grant (its own Basic client id, its own token) — never the
first tenant's cached entry.

### H-18P5 — one hung webhook receiver wedged every tenant's dispatch; one unprovisioned secret skipped every webhook after it

The outbound dispatcher built `RestClient.create()` per attempt with no
timeouts, inside the single-threaded Kafka consumer group — one slow receiver
(a builder-authored external host) stalled all dispatch platform-wide. The
client is bounded once (2 s connect / 10 s read, `novaforge.webhook.read-timeout-ms`).
Separately, the missing-secret throw unwound the per-app/per-webhook loops and
the consumer acked it as a malformed event: every webhook ordered after one
unprovisioned secret silently never dispatched, with no redelivery. The loops
isolate per webhook now (the broken one is already parked in the DLQ by its own
fail path). Pinned: a broken subscription ordered first no longer suppresses the
healthy one behind it.

### H-18P6 — two replicas double-ran every integration job

The job runner claimed work with an in-process set only — two replicas (a
rolling-deploy overlap, a scale-out) both selected the same pending row and both
ran it, applying every import row twice through the batch API. The pending→running
transition is a CAS (`WHERE status = 'pending'`, affected-rows checked); only
the pass that flips it owns the job. Pinned: the claim is true once and false
for the second claimer, whose scan applies nothing.

### M-18P7 through M-18P10 — the mediums

- **The spine-event email leg had no idempotency claim**: `Notifier.onEvent`
  deduped the inbox row on event_id but ran SMTP unconditionally — a Kafka
  redelivery re-emailed every email-preferred recipient. The leg claims its key
  in REQUIRES_NEW exactly like `deliverDirect`. Pinned: a replayed event
  re-emails nobody.
- **The update path fired hooks before the doom-guards**: `beforeSave` connector
  deliveries and approval tasks went out before the freeze/period checks
  rejected the write — remote effects persisted uncompensated, and the connector
  dedupe key then swallowed the retried write's call (the real integration
  effect never happened). The guards precede the hooks now, the create path's
  own documented invariant.
- **Two unbounded remote clients sat on hot paths**: `RestMetadataClient`
  (called inside @Transactional writes on the resolver's TTL refresh — a hung
  metadata service held the write's DB connection toward pool exhaustion) and
  `HttpEnvironmentProvisioner` (synchronous inside promote/rollback). Both
  bounded 2 s/10 s like every sibling.
- **The JSONB numeric cast had no shape gate**: one legacy non-numeric string
  under a re-typed numeric field aborted every filter/sort/rollup/sharing
  predicate over the field (`invalid input syntax for type numeric`). The cast
  rides the same regex CASE gate as `RecordStore.numericValueExists` — malformed
  rows evaluate NULL and never match. Goldens updated.

### L-18P11 through L-18P15 — the lows

ClamAV's connect is bounded (5 s — the scan runs inside the completing
transaction); the audit partition move checks moved-vs-deleted counts and rolls
back on divergence (under READ COMMITTED the two statements take separate
snapshots — a concurrently-ingested row was deleted without being copied,
silently, from an append-only trail; ATTACH already fenced the later window);
every service pins `spring.task.scheduling.pool.size` (Boot's default ONE
scheduler thread starved the outbox relays behind any long leg); the entity
export honors `novaforge.jobs.export-max-rows` (200k default — the in-memory
CSV assembly OOMed the pod on the shared scheduler pool);
`audit/records/{id}` is LIMIT-bounded like the entity path (1..200).

### The frontend harvest — thirteen confirmed defects

The runtime form save had no in-flight fence and the create no idempotency key
(a double-click minted two records; both fences added, pinned); the record fetch
fired from the render body unguarded (a per-keystroke fetch storm, typed edits
clobbered by each response, a retry storm on failure — now a guarded effect,
pinned shape); four authoring JSON inputs parsed on every keystroke (a new
`JsonTextField` keeps typing and commits parseable edits, pinned ×3); the page
builder's 409 rebase read the server page from the stale prop captured at click
time (the shell now pins the fresh saved page onto the rethrown error; the save
and rebase revisions come from the server's own response, not local fiction);
record/entity delete failures were silent unhandled rejections (both surface
now); non-ApiError save failures threw into `void` dispatches leaving the
previous "Saved" flash as misinformation; the lifecycle ack leaked across
environment switches and doubled as the override switch (staging promotions were
recorded as admin overrides forever — the red-gate override is now its own
reasoned action, and the ack resets on switch); reports/rbac/integrations saved
whole branches from mount-time snapshots (a shared `mergeBranch` applies the
dashboards' fresh-fetch rule — concurrent additions survive, deletions stand);
the i18n workspace race (a failed load left locale A's strings editable under
locale B's header — Save wrote German into French; loading state fences it);
ListLayout dropped its authored children (the record-actions node and builder
inserts never rendered); the palette insert defaulted its parent to "form" (a
silent stale no-op on list/detail pages, now root-keyed with stale surfaced);
cancelling the rejection-comment prompt still rejected the approval; the lookup
widget raced out-of-order responses and labeled options with raw ids (sequenced
search + the target's display field via the shell); one malformed chart run
unmounted the whole SPA (ChartWidget guards the projection's shape — KpiTile's
own rule; a failed dashboard auto-refresh now says it is showing the last
successful run); the client parses error bodies defensively (a gateway 502 HTML
page threw SyntaxError instead of the problem contract).

### The deploy-posture leg — the pods no longer run as root

None of the eleven jib images set a user and none of the eleven charts set a
securityContext — every pod ran as root. The poms pin `<user>1000</user>` and
every deployment template carries the locked-down container context
(runAsNonRoot, no privilege escalation, ALL capabilities dropped, RuntimeDefault
seccomp, readOnlyRootFilesystem) with an emptyDir at /tmp (every service logs to
/tmp/novaforge/logs). Templates validated by parse; the chart set stays
ClusterIP-only per the L-TP7 posture.

### The process defect this pass found in itself

The per-module verification commands piped Maven through `tail`, and a pipeline's
exit code is the last command's — several "green" intermediate results were
`tail`'s exit code, not Maven's. The completion audit caught it: surefire
reports showed a context-load failure the "green" runs masked (a yaml insertion
this pass made between `spring.application` and its `name:` child — the
`${spring.application.name}` log placeholder became unresolvable in eight
services). Both repaired; every verification gate in this pass's record ran with
an honest exit code.

### The closing addendum — the recorded-open set empties

- **`attachments.bind` consults the §9 record gate now**: both binding doors —
  the upload's stored target tag (record-governed from the first moment) and the
  completion's bind — verify the caller can READ the target record first, the
  same gate every read of a bound attachment rides. An unreadable target
  rejects FORBIDDEN with nothing planted; an unreachable runtime fails closed.
  Pinned ×2 (the unreadable bind leaves the attachment unbound; the upload-tag
  door rejects identically).
- **The rate limiter's window is one atomic call**: INCR + first-hit PEXPIRE
  ride a single Lua script — the two-call form left the minute key immortal
  whenever anything failed between them (never over-blocking, but residue
  forever). Pinned: one script call, zero separate expiry calls, PEXPIRE in the
  script with the 60 s arg.
- **`warn` reports the flipped count**: the scratch surface's `warned` answer
  now counts what the pass emitted, not what it selected — the same semantics
  the breach path reports.

### The re-audit of the pass's own code — seven more close

The prior passes' pattern held: the newest code was again the richest vein. A
full adversarial sweep of everything `ba96b02` landed found seven defects, all
fixed:

1. **The guard reorder dropped the post-hooks leg** (MED-HIGH): moving the
   freeze/period guards before the hooks — without keeping the after-hooks
   re-check — let a beforeSave hook (or its formula re-evaluation) re-date a
   record into a CLOSED period or re-point a frozen parent and commit. The
   guards run TWICE now: pre-hook (no external side effects for a doomed write)
   and post-hook (the landing state meets the same gate as the arrival state,
   like the state-machine check). Pinned with a real hook-dated write into a
   closed period rejecting PERIOD_LOCKED (4014) — the pin the original reorder
   never had. The stale "runs after the hooks" javadoc corrected.
2. **`crypto.randomUUID` bricked creates on plain-HTTP origins** (MED): the new
   create idempotency key threw on any non-secure context (the platform's own
   LAN demos) — caught by the flash, creation never succeeded. The key
   generator falls back to a time+random twin (no security rides on it).
3. **`markRead` bypassed the pass's own stale-response fence**: its inline
   reload neither bumped nor checked the sequence — a markRead response could
   clobber a newer page's rows. It routes through the fenced reload now.
4. **A failed i18n load left Save armed over an emptied table**: one click
   overwrote the locale's whole workspace with empty. Save fences on the
   load-failure state (a save failure does not — that fence would stick).
5. **The dashboard stale-note latched**: a single failed auto-refresh showed
   "last successful run" forever, including after recovery. A successful
   refresh retires it.
6. **`JsonTextField` committed non-object JSON**: `5`, `[1,2]`, `"x"` parsed
   fine and rode into job params and mapping fields as record shapes. Objects
   only; anything else is keep-typing. Pinned.
7. **The sharing-rule merge key wasn't unique**: same entity+type+roles with
   different criteria collided — a concurrent tab's rule silently dropped on
   save. The key carries the criteria/ownerField.

### Recorded open after this pass

None on the defect ledger. The deliberate decisions stand: delegation
read-scope (product), M11/M12 (maintainability), the sandbox process-isolation
pools (the recorded growth path), and the stuck-`running` job row after a pod
death (pre-existing, bounded by the CAS, next operator action documented).

### Verification (the closing addendum)

Full serial `./mvnw verify` **BUILD SUCCESS, honest exit 0** — 23 modules, 486
backend tests, zero failures (file-service 14 with the two bind-gate pins,
gateway 23 with the atomic-window pin, data-runtime api with the hook-dated
period-lock pin, workflow 39 with the flipped-count semantics, the whole of the
eighteenth pass's suites re-run underneath). Frontend: typecheck clean + 163
vitest (the JsonTextField scalar-guard pin added).

### Verification (this pass)

Full serial `./mvnw verify` **BUILD SUCCESS with an honest exit code** — 23
modules, 484 backend tests, zero failures (data-runtime api 88 / engine 25 /
storage 16 with the resume-claim, self-heal, and numeric-gate pins; metadata 54
with the transactional tail; workflow 39 with the one-shot breach pins;
integration 21 with the cross-tenant-token, cross-replica-claim, and
webhook-isolation pins; notification 14 with the replay pin; audit, file,
scheduler, gateway, reporting, script-engine green under the rotation check,
bounded clamd connect, pools, and caps). Frontend: typecheck clean + 162 vitest
(shared 100 incl. the chart-guard pin, builder-ui 47 incl. the JSON-field pins,
runtime-ui 15 incl. the double-submit pin). The eleven chart templates
parse-validated with the securityContext and /tmp volume; the eleven poms
well-formed with UID 1000.

## Nineteenth Pass — 2026-08-31 (the ledger-empty audit: four concurrent sweeps, the newest code first)

The eighteenth pass emptied the ledger, so this pass ran the established
pattern at full width: a hand audit of the newest code (the closeout commit's
own landings), three parallel adversarial sweeps (backend services, frontend,
deploy/CI), then every confirmed defect fixed with an anti-regression pin.
Thirty-two confirmed defects closed: 4 engine/file, 8 backend, 12 frontend, 8
deploy/posture.

### The newest code, re-audited — the closeout's own fixes were the richest vein again

- **H-19P1 — the double-guard landed on ONE door of four.** The eighteenth
  pass's post-hook doom-guard re-check (a beforeSave hook re-dating a record
  into a CLOSED period, or re-pointing a frozen parent, must meet the same
  rejection on landing as on arrival) was added to the user UPDATE path only.
  The user CREATE door and the integration CREATE door had no post-hook leg at
  all — a hook-dated create into a closed period committed (the caller dates a
  period-free September; the hook re-dates to the closed August inside); the
  integration UPDATE door re-ran the period lock but never the parent-freeze
  guard, so a hook re-pointing a lookup at a terminal parent committed there.
  All three doors run the guards pre- AND post-hook now. Pinned ×3 — the user
  create leg inside HookDatedPeriodLockTests (folded into the method that owns
  the period row: period lookup is by date range, so a sibling method closing
  its own August period couples the two through every August date), and a new
  IntegrationGuardLegTests carrying the integration create (4014, nothing
  landed) and the integration update (4013, the line still points at the open
  doc). Bite-proven: with the engine reverted, the create leg commits 200 and
  the pin fails.
- **L-19P1 — the bind gate paid the scan for a doomed write.** The closeout's
  §9 gate on the completion door ran AFTER `complete()` — the ClamAV scan is an
  external side effect, and a rejected bind left a completed attachment that
  could never accept its target. The gate runs BEFORE completion now (the
  doom-first invariant the engine doors obey); the pin strengthened to assert
  the checksum stays NULL (completion never fired for the doomed bind). The
  orphaned requireAccess javadoc the insertion left dangling is back on its
  own method.

### The backend sweep — three HIGHs, all in the at-least-once contract

- **H-19P2 — the one unbounded RestClient left in the audited set**:
  reporting's `RestPublishedApps` (the published-bundle source behind every
  run/export/delivery) built its metadata client with no requestFactory —
  infinite connect/read. Every sibling is bounded 2s/60s with a stated
  rationale; a hung metadata TCP hold pinned a Tomcat thread per fire until
  the pool exhausted. Bounded like its siblings; the factory's actual
  2000/60000ms pinned.
- **H-19P3 / H-19P4 — the "must redeliver" contract was a comment, not a
  mechanism, in audit and notification.** Both services' listeners propagate
  append/send failures deliberately — but with no container error handler,
  Boot's default retries nine times at ZERO backoff then log-and-skips,
  committing the offset: a DB outage longer than nothing punched permanent
  silent holes in the append-only trail, and an SMTP outage silently dropped
  every task/sla fan-out (no inbox row, no email). The sixteenth pass built
  the answer for workflow (`ConsumerErrorConfig`: exponential backoff, DLT,
  never log-and-skip) but wired it into one service. Audit and notification
  carry the same config now; pinned with real-broker behavioral
  discriminators (the failing delivery redelivers with observable spread, not
  the sub-second zero-backoff burst) plus wiring assertions. The existing
  listener-throws pins held untouched.
- **M-19P1 — a Redis outage hard-failed every report run.** The epoch read
  rode the resolve path unguarded — one connection failure 500'd interactive
  runs, exports, and scheduled deliveries even for bundles already cached
  in-process, contradicting the service's own documented degrade posture.
  Guarded: warm cache serves, cold miss fetches fresh; the invalidate bump is
  guarded too. Pinned ×2 (cold-miss-through-outage, warm-serve-does-no-fetch).
- **M-19P2 — the published-bundle cache served stale forever on one lost
  Kafka delivery** (H-18P2's mechanism, alive in reporting's own cache): no
  version check, no TTL — a missed `metadata.published` bump wedged a tenant
  on a stale bundle until restart. The cache carries a TTL self-heal window
  now (30s default, env-tunable) exactly like the resolver's H-18P2 close;
  pinned: version moves with no observed bump and the next TTL-expired read
  serves v2.
- **M-19P3 — cross-tenant explicit recipients.** `recipients.users` ids are
  caller-named untrusted input, but the admin user lookup is global
  (tenant-unscoped, no tenant attribute — membership lives in role rows), so
  a recipient list naming a foreign user delivered the sending tenant's data
  to them: inbox row plus the emailed export. Every explicit id must now
  prove membership through the tenant-scoped roles read (empty = not a
  member), dropping non-members with a warn and failing CLOSED on lookup
  errors — a lost send to one id beats a cross-tenant leak. Pinned: a foreign
  id named alongside a member delivers exactly the member's row/email and
  nothing for the foreign user; an all-foreign list rejects.
- **L-19P2 — `page * size` overflow**: the inbox pager left `page` unbounded;
  `page=2000000000&size=200` overflowed int to a negative OFFSET and rendered
  a 500 from a raw Postgres error. Bounded (reject, never clamp) with long
  math; pinned 400.
- **L-19P3 — upstream response bodies leaked to end users**: the async-export
  handoff embedded the integration service's rejection body verbatim in the
  user-facing problem detail. Server-side WARN carries the body; the user
  sees the status only. Pinned.

### The frontend sweep — twelve confirmed, twelve closed (163 → 182 vitest)

- **HIGH: the JSON authoring inputs wiped typed text** — the logic editor's
  step-params and the suites editor's fixture/step-template inputs were
  controlled components re-derived from the parsed model every keystroke,
  committing `{}` on failed parse: React snapped the DOM back, so JSON could
  only be entered by pasting a complete literal in one action (typing
  `{"x": 1` left the box empty), and a partial silently saved empty params.
  All three author through the `JsonTextField` fence the eighteenth pass
  built (objects only, keep-typing). Pinned ×2.
- **MEDs**: the palette's raw `crypto.randomUUID` bricked every palette click
  on plain-HTTP origins (the guarded `randomKey` moved to shared and both
  callers use it — pinned by hiding the API like a non-secure context does);
  the pages screen never reloaded after save (the one screen-saver without
  it — stale-revision 409s against the user's own save); the page builder's
  save had no double-submit fence (savingRef + disabled, pinned one-PUT);
  the runtime's state-machine transition buttons were local-only theater
  (a real versioned PATCH now, server response applied, pinned one-PATCH
  with `{version, stateField}`); the approval inbox's reload lacked the
  stale-response sequence fence the notifications inbox got (reloadSeq +
  pager-fence, pinned out-of-order refusal); automation and gap-log saved
  mount-time branch snapshots verbatim (the dashboards' fresh-fetch +
  mergeBranch rule applied — a concurrent tab's machine/SLA/job/gap rows
  survive, pinned).
- **LOWs**: the integrations ops panels faked empty on failure (error alerts,
  last-good rows kept — pinned); secret provisioning swallowed every error
  (surfaced, pinned); `length + 1` id schemes collided after out-of-band
  deletions (first-free loop, pinned); closed lookup inputs displayed the raw
  FK uuid (resolved to the target's display field through a sequenced by-id
  read, raw-id fallback — pinned ×2); Logic/Suites editors never received the
  shell's busy state (threaded + re-entry fences, pinned single-fire).

### The deploy/posture leg — the H-10P1 class closes for every chart

- **H-19P4 — ten of eleven charts deployed services wired to localhost, and
  six application yamls still hardcoded DB credentials.** The ninth pass
  (H-10P1) recorded and fixed exactly this for file-service only; the ten
  siblings shipped `env: []` or the auth pair alone — inside a pod every
  default endpoint is the pod itself: Flyway CrashLooped the seven DB
  services and the gateway 502'd on every route, and the literal
  `username: novaforge` blocks had no env binding at all. All eleven charts
  now carry the full infra wiring (in-cluster service DNS for Postgres,
  Kafka, Redis, Keycloak issuer, Tempo, and every inter-service URL; DB
  credentials ride the fail-closed `novaforge-db` secret posture like the
  auth pair), the six yamls env-bind their credentials (compose/dev defaults
  unchanged), and the gateway's dashed upstream properties gained explicit
  env-fallback twins (`${novaforge.upstreams.x:${NOVAFORGE_X_URL:…}}` —
  dashed names have no clean relaxed-binding form). All eleven charts lint
  AND render under helm 3.16.4.
- **M-19P4 — no startupProbe anywhere**: the liveness probe's default budget
  kills a booting JVM (~30s) that shares a kind node with ten siblings — the
  classic kill-before-ready loop. Every deployment carries a startup probe
  now (5s × 60 = five minutes of grace); helm-render verified.
- **M-19P5 — the CI bearer token rode GITHUB_ENV unmasked**: a runtime-derived
  token is not auto-masked by GitHub (only `secrets.*` inputs are) — any
  later env-printing step leaked a full-scope platform token into logs.
  `::add-mask::` precedes the export.
- **L-19P4 — no job timeouts** (GitHub's 360-minute default vs this repo's
  own surefire-hang history): every job in both workflows is bounded.
- **L-19P5 — the chart-render gate**: H-12P1 found 22 never-rendered invalid
  templates, fixed by hand with nothing stopping the class from returning. A
  `charts` CI job lints and renders every chart on every PR (and this pass
  ran the same helm 3.16.4 check locally — clean).
- **L-19P6 — version drift**: `poi-ooxml` was the one third-party version in
  a module pom, and jib's version was repeated verbatim in eleven poms. Both
  centralized in the root pom (`poi.version`, `jib.version` +
  pluginManagement); module poms keep only per-image configuration.

### Recorded open after this pass (decisions, not defects)

- **In-cluster infra has no chart**: the eleven service charts point at
  `novaforge-postgres/kafka/redis/keycloak/tempo/minio/clamav`, which no chart
  or manifest in this repo creates — local dev rides the compose stack,
  staged environments bring their own infra (the values comments state the
  assumption; a stateful infra chart is a scoped project, not a fix).
- **Cluster hardening beyond the v1 posture**, all deliberate alongside the
  recorded L-TP7 ClusterIP-only stance: no NetworkPolicy/dedicated
  ServiceAccount (the pods ride the namespace default SA), no PDB/HPA (v1
  ships replicaCount 1), mutable `0.1.0-SNAPSHOT` image tags with no CI image
  publish (skaffold's gitCommit override is the working path), and actions
  pinned to major-version tags rather than SHAs.
- The prior deliberate set stands: delegation read-scope, M11/M12,
  sandbox process-isolation pools, the stuck-`running` job row.

### Verification (this pass)

Full serial `./mvnw verify` **BUILD SUCCESS, honest exit 0** — 23 modules,
496 backend tests, zero failures, zero errors, zero skipped (data-runtime api
91 with the create-leg and IntegrationGuardLegTests pins; reporting 30 with
the bounded-client/degrade/TTL pins; notification 17 with the backoff,
cross-tenant, and page-overflow pins; audit 8 with the redelivery-backoff
pin; file 14 with the strengthened bind-gate pin; gateway 24 under the nested
upstream placeholders; the whole of the prior passes' suites re-run
underneath). Frontend: `pnpm check` exit 0 and 182 vitest green across 25
files (shared 104, builder-ui 60, runtime-ui 18) — 19 new pins, zero
pre-existing tests deleted. The eleven charts lint + render under helm 3.16.4
(the same check the new CI charts job runs). Every fix carries its pin; the
engine guard legs were bite-proven by reverting the fix and watching the new
pins fail.

## Twentieth Pass — 2026-08-31 (the closeout re-audit: the nineteenth's own landings + its recorded verification gaps)

The nineteenth pass closed thirty-two defects and recorded two verification
gaps of its own; this pass re-audited the newest landings first (the richest
vein, nineteen passes running) and closed both gaps. The re-audit found three
real defects — all in the nineteenth pass's own chart work.

### The re-audit of 1ddecae — what held and what didn't

- **Held**: the RecordEngine guard legs (committed exactly as pinned; the
  doubled FOR-SHARE parent check is re-entrant per transaction and locks in a
  stable field order — no self-deadlock, no new lock-order inversion; the
  cascade/inline-child paths insert without hooks, so they have no post-hook
  leg to miss); the two ConsumerErrorConfig wirings (no listener names a
  custom factory, so every listener resolves the overridden default bean, and
  neither service sets `spring.kafka.listener.*` props the Boot configurer
  would have applied); the cross-tenant recipient gate (the roles-of surface
  answers membership, 404-on-unknown fails closed through the catch, and a
  role-less "member" is dropped by the documented definition — the platform
  DB's tenant binding IS the role row).
- **Didn't hold — the chart env rewrite had three gaps of its own**, found by
  a two-way consistency check (every chart env name must be consumed by a yaml
  placeholder, every non-knob placeholder must be chart-fed):
  1. the integration chart never received its DB wiring at all — its yaml
     wants `NOVAFORGE_INTEGRATION_DB_USER/_PASSWORD` (and the Postgres
     host/port pair) and the chart set none of them;
  2. the integration yaml also binds `NOVAFORGE_NOTIFICATION_URL`, unfeed;
  3. the audit chart bound the Flyway owner PASSWORD but not the owner
     USERNAME (`NOVAFORGE_AUDIT_DB_OWNER`). All three repaired on the same
     secret posture; both touched charts re-verified under helm 3.16.4
     (lint + template); the checker now reports zero real gaps (the remainder
     is documented tuning knobs with sane defaults — scan intervals, relay
     cadences, log dir — each individually env-tunable).

### The two recorded verification gaps close

- **The BuilderShell harness exists** (frontend/builder-ui/test/shell.test.tsx):
  the nineteenth pass's pages-screen reload fix shipped unpinned because no
  test mounted the shell. The harness stubs the metadata fetches behind the
  real PlatformClient (v1 serves the mount load, every later getApp serves
  the post-save app), drives mount → pages screen → dirty a customization →
  save → PUT, and pins that a FRESH getApp lands after the putPage (exactly
  one call served the mount; the second is the reload). Bite-proven: with the
  `await reload()` removed the pin times out waiting for the second fetch.
- **The DLT publish leg is observed end to end** — the real production
  factory bean, a real broker, a real dead letter. The retry budget became
  property-tunable in all three ConsumerErrorConfigs
  (`novaforge.kafka.consumer-retry.{initial-ms,max-interval-ms,max-attempts}`,
  defaults bit-identical to the hardcoded 1000/60000/10 they replaced —
  unified across audit/notification/workflow in this pass). The new
  AuditDeadLetterTests and NotificationDeadLetterTests shrink the budget to
  10ms/10ms/3 in the test context, run an always-failing listener through the
  autowired `kafkaListenerContainerFactory`, assert exactly three deliveries
  (the budget exhausted, not skipped), then subscribe a plain KafkaConsumer
  to `<topic>.DLT.<group>` and assert the dead-lettered payload, key,
  original partition, and the recoverer-only `kafka_dlt-original-topic`
  header. Both bite-proven: with the recoverer removed from the handler both
  tests fail at exactly the dead-letter assertion. What stays honestly
  unobserved: the production budget's own ~5-minute exhaustion — the DLT leg
  is observed at the shrunk budget through the identical wiring (same factory
  bean, same recoverer, same destination expression).

### Verification (this pass)

Full serial `./mvnw verify` **BUILD SUCCESS, honest exit 0** — 498 backend
tests (audit 9 and notification 18 with the new dead-letter observations;
workflow 39 re-run green on the unified tunable config; the whole of the
prior passes' suites underneath), zero failures, zero errors, zero skipped.
Frontend: `pnpm check` exit 0 and 183 vitest green across 26 files (the
BuilderShell harness is the twenty-sixth). Both touched charts re-verified
under helm 3.16.4 (lint + template). Both new pins bite-proven; the shell
pin bite-proven against the reload's removal, the dead-letter pins against
the recoverer's removal.

### Recorded open after this pass

Unchanged from the nineteenth (deliberate decisions, not defects): the
in-cluster infra chart set, the NetworkPolicy/SA/PDB/immutable-tag posture
alongside L-TP7, and the prior set. Plus this pass's own honest limit: the
production retry budget's five-minute exhaustion is verified at the shrunk
budget through identical wiring, not at full duration.

## Twenty-First Pass — 2026-08-31 (the largest deferral closes: in-cluster infra, plus the DNS defect the task itself surfaced)

The twentieth pass's closeout named the infra chart set as "the most natural next
candidate" toward literal production readiness; this pass built it — and the
build itself surfaced a real defect the twentieth pass's checker could not see.

### H-21P1 — every inter-service URL was DNS-unresolvable under any real release

Designing the infra chart forced the question the twentieth pass's
name↔placeholder consistency check never asked: DOES the referenced DNS name
exist? The eleven charts created Services as `{{ .Release.Name }}-novaforge-x`
— release-prefixed — while every env value (the ninth pass's and the
nineteenth's alike) references FLAT `novaforge-metadata-service`-style hosts.
Under skaffold's `novaforge` release every URL pointed at
`novaforge-novaforge-metadata-service`-shaped names nothing owned… no — the
reverse: the services existed only under prefixed names, so every flat
reference was unresolvable. The eleven Service templates create flat names now
(deployments keep release-prefixed names — nothing uses them as DNS), and the
new chart gate (below) makes the whole class unrepeatable.

### The novaforge-infra chart — the compose stack's in-cluster twin

Postgres 16.15 (the initdb script creating every service database/role,
byte-mirrored from `deploy/postgres-init/01-databases.sh`), single-node KRaft
Kafka 4.3.1 (advertised as the flat service name), Redis 7.4.11, Keycloak
26.7.2 (the realm export byte-mirrored from the compose stack; the
auth-listener provider jar documented as bring-your-own), Tempo 2.10.0 (config
byte-mirrored; distroless uid 10001 — verified empirically, not guessed), MinIO
(+ the versioned-bucket init Job, a real mc release tag — the invented one
did not exist), and ClamAV 1.4.3 (signature PVC so freshclam's ~300 MB
database survives restarts; uid/gid 100/101 verified from the image). The
dev-instance secret set the eleven charts reference (`novaforge-db` with all
four keys including the owner pair, `novaforge-service-client`,
`novaforge-secrets`, `novaforge-minio`) is created from the compose stack's
own committed values, loudly scoped: `secrets.create: false` for staged
environments, which bring their own. Every component carries the eighteenth
pass's locked-down container posture with per-image uids, a startupProbe
sized to its real first-boot cost (keycloak's realm import, clamav's signature
download, kafka's storage format), and PVCs where state must survive.

### deploy/helm/check-charts.sh — the chart gate, wired into CI

Three checks, each bite-proven: (1) every chart lints and renders (12 charts);
(2) the infra chart's three embedded files stay byte-identical to their
compose-stack sources — a one-byte drift fails (proven); (3) DNS consistency
across the rendered output — every URL host and every `*_HOST`-named env value
must be a Service the same render owns (16 hosts, all resolving; a ghost host
fails the gate — proven). The CI charts job calls the script; the same run
this pass recorded executed it green end to end.

### Dead-letter coverage completes — workflow's own e2e pin

The original ConsumerErrorConfig (the sixteenth-pass original the two
nineteenth-pass mirrors copy) was the only spine service without an observed
DLT publish. `WorkflowDeadLetterTests` mirrors the audit/notification pins
exactly: the production factory bean, a real broker, the budget shrunk to
10ms×3, an always-failing listener (DataAccessResourceFailureException —
RecordEventConsumer's own transient mode), exactly three deliveries, then the
dead letter itself consumed and verified (payload, key, original partition,
`kafka_dlt-original-topic`). Bite-proven against the recoverer's removal.
All three spine services now observe their dead letters end to end.

### Re-audit of f95491c (this pass's first act)

The tunable retry budgets (property names and defaults bit-identical across
the three configs), the two dead-letter tests (production-factory-driven, not
hand-built twins), and the repaired integration/audit chart blocks all held.
The fresh-eyes sweep of the chart surface is what found H-21P1.

### Verification (this pass)

Full serial `./mvnw verify` **BUILD SUCCESS, honest exit 0** — 499 backend
tests (workflow 40 with the new dead-letter pin), zero failures. The chart
gate green across all 12 charts (lint, render, drift, DNS consistency), with
both failure modes bite-proven and the gate restored-green afterwards.

### Recorded open after this pass

Shrunk, and now purely posture: no NetworkPolicy/dedicated ServiceAccount, no
PDB/HPA (v1 ships replicaCount 1), mutable `0.1.0-SNAPSHOT` image tags with no
CI image publish (skaffold's gitCommit override is the working path), and
actions on major-version tags rather than SHAs. The prior deliberate set
stands (delegation read-scope, M11/M12, sandbox pools, stuck-running job row,
the production-budget-duration observation limit).

## Twenty-Second Pass — 2026-08-31 (the isolation posture lands; the closeout re-audit finds two first-boot defects in the infra chart)

### The 2e95534 re-audit — two real defects, both in the infra chart's first boot

- **The bucket-init Job rendered `$$MINIO_ROOT_USER`** — `$$` is
  docker-compose interpolation escaping, meaningless in helm: the rendered
  command carried it literally, the shell expanded `$$` as its PID, and the
  Job would have authenticated to MinIO with garbage forever (backoffLimit 6,
  then a never-ready bucket). Single `$` is what `sh -c` needs.
- **The Postgres bootstrap collided with its own initdb script**: the chart fed
  `POSTGRES_USER` from the secret's owner-user key (`novaforge`), but the init
  script's very first statement is `CREATE USER novaforge` — with
  ON_ERROR_STOP the "role already exists" abort aborted the whole init, and no
  service database would ever have been created. The bootstrap superuser is
  the image default `postgres` (the compose stack's own shape — it sets only
  the password); only the password rides the secret.
- Everything else held: the flattened Service names (nothing references the
  release-prefixed workload names as DNS), the kafka advertised-listener
  wiring (bootstrap via the Service DNS, controller on loopback), the
  readOnly-rootfs choices, and the gate script's three original checks.

### The isolation posture — dedicated ServiceAccounts + default-deny NetworkPolicies, all twelve charts

Every chart now ships `serviceAccount.create` + `networkPolicy.enabled`
(both default true): a dedicated, token-automount-disabled ServiceAccount per
workload — pod-level `automountServiceAccountToken: false` as well (pod-level
wins even if someone flips the SA) — and a default-deny NetworkPolicy
(Ingress+Egress) whose explicit allows are exactly the chart's own env wiring:
DNS (kube-dns UDP+TCP 53), Keycloak:8080 (the JWKS fetch), Tempo:4318 (OTLP),
plus each chart's actual dependencies; infra components get their own
(keycloak→postgres, minio-init→minio, everything else DNS-only); clamav's
egress is `0.0.0.0/0` on 443 only — freshclam's signature downloads, which a
plain default-deny would have silently broken. The gateway additionally
allows ingress from anywhere on 8080 (the dev entry point; a staged
environment narrows it) AND egress to all nine proxied backends — the one
matrix gap the landing itself flagged (the gateway IS the front door; the
prescribed matrix had omitted its own route table) — fixed before verify.

### The chart gate learns the posture contract

check-charts.sh now also verifies, from the rendered output alone: every
workload pod (Deployment/StatefulSet/Job) names a ServiceAccount that the
same render owns, with pod-level token automount off; every chart renders a
default-deny (Ingress+Egress) NetworkPolicy. Bite-proven before the charts
landed: the pre-agent tree failed the gate on all 19 pods and all 12 charts,
exactly as the contract demands; the landed tree passes clean.

### Verification (this pass)

Full serial `./mvnw verify` **BUILD SUCCESS, honest exit 0** — 499 backend
tests, zero failures (the chart work touches no Java; the suites re-ran as
the honest current-tree check). The chart gate green across all 12 charts:
lint, render, embedded-file drift, DNS consistency (16 hosts), and the new
isolation posture (19 pods on named SAs, token automount off, 12 charts
default-denying both ways).

### Recorded open after this pass

Pure posture remains: PDB/HPA (v1 ships replicaCount 1), immutable image tags
with no CI image publish (skaffold's gitCommit override is the working path),
actions on major-version tags rather than SHAs — plus the prior deliberate
set (delegation read-scope, M11/M12, sandbox pools, the stuck-running job
row, the production-budget-duration observation limit).

## Twenty-Third Pass — 2026-08-31 (the remaining posture deferrals close: PDB/HPA, immutable images + CI publish, SHA-pinned actions)

### The e221937 re-audit

The twelve charts' ServiceAccount/NetworkPolicy templates held under fresh
review — the policy semantics are right (selection for both policyTypes is
default-deny; the allow lists are the env wiring), the DNS/kube-dns peer
shape is correct (namespace+pod selectors AND within one `to` entry), and the
gateway's nine-backend egress block renders every peer. The extended gate's
own new checks were re-audited too and one real bug in them was fixed on the
spot: the final chart-name derivation read the render DIRECTORY instead of
the file basename (flagging `tmp.xxxxx` as a chart-less render).

### Disruption budgets and autoscaling, all twelve charts

Every workload chart registers a PodDisruptionBudget — deliberately
`maxUnavailable: 1`, not `minAvailable: 1`: v1 ships replicaCount 1, where
minAvailable blocks EVERY voluntary eviction and hangs node drains;
maxUnavailable registers the budget (visibility, future-proofing) while the
single replica still drains, and the template documents the switch point.
The infra chart budgets each component (the minio-init Job is excluded — a
one-shot bootstrap pod has no maintained replica to budget). HPA templates
ship enabled-by-default-OFF with the why in values (no metrics-server on the
kind cluster; an HPA without one reads <unknown> and scales nothing), and
enabling one removes the Deployment's `replicas` field — a hard-coded count
fights the autoscaler.

### Immutable images + the CI publish leg

A new `images` job (push-to-main only, after build/frontend/charts/clamav
gates; packages: write) builds all eleven service images through jib to
GHCR under the IMMUTABLE commit SHA — the tag pattern the chart values now
document (`helm upgrade --set image.tag=<sha>`); `0.1.0-SNAPSHOT` remains
the local dev default and skaffold's per-build override stays the dev path.
The publish loop itself was audited before landing: the first draft's
`echo | while` pipe would have swallowed a failed middle image (pipeline
status is the last command's — the exact masked-failure class the eighteenth
pass recorded); it is a here-string loop now, where set -e governs every
invocation.

### SHA-pinned actions

All fourteen `uses` references across the workflows are pinned to the exact
commit each major tag named when this pass landed (resolved via
`git ls-remote`, not guessed), with the intended major version kept as a
trailing comment. A tag is a moving target; the pin makes a compromised or
breaking tag update unable to rewrite the pipeline in place.

### The gate's contract grows again

check-charts.sh now also requires every chart to render a PodDisruptionBudget
(defaults render them on) — bite-proven: removing one PDB fails the gate with
the chart named; restored, the gate is green across all twelve charts:
lint, render, file drift, DNS consistency (16 hosts), isolation posture (19
pods, 12 default-denies), and 18 registered disruption budgets.

### Verification (this pass)

Full serial `./mvnw verify` **BUILD SUCCESS, honest exit 0** — 499 backend
tests, zero failures (the pass touches charts and CI only; the suites re-ran
as the honest current-tree check). The chart gate CLEAN as above.

### Recorded open after this pass

The posture ledger is empty: every named deferral (infra charts, flat DNS,
NetworkPolicy/SA, PDB/HPA, immutable tags + CI publish, SHA-pinned actions)
is now a landed artifact under the gate. What remains recorded is the prior
deliberate set — delegation read-scope, M11/M12, sandbox pools, the
stuck-running job row, the production-budget-duration observation limit —
plus this pass's honest boundary: the infra chart's runtime behavior is
render-verified and empirically uid-checked, not yet live-cluster-observed
(the kind-on-podman flow is the recorded environment for that).

## Twenty-Fourth Pass — 2026-08-31 (the live-cluster leg: the kind-on-Podman stack boots end to end, and ten runtime-only defects flush out)

The recorded boundary — "the infra chart is render-verified and uid-empiric but
not yet live-cluster-observed" — is closed: a cold kind-on-Podman cluster was
created, novaforge-infra installed, all eleven service images built (jib
`buildTar`) and loaded, all eleven charts installed, and the stack observed to
steady state: **every pod 1/1 Running** — eight infra components (postgres,
kafka, redis, keycloak, tempo, minio, clamav) plus all eleven services, with
the bucket-init Job Complete. Verified live: the initdb script's three roles
and nine databases in Postgres, the imported Keycloak realm (Ready probe on
/realms/novaforge), the versioned novaforge-attachments bucket, Kafka consumed
by every spine service (readiness requires the consumer), and the full OIDC
chain — `curl` through the gateway answers 200 on health and 401 on a JWKS-gated
route, bogus token or none.

The pass's premise held: the runtime found ten defects no render check could.

1. **The minio-init Job's `restartPolicy` was gone** — a twenty-second-pass
   edit added the isolation fields and dropped the line; the live API server
   refused the Job. Restored; the gate now demands a valid restartPolicy on
   every Job render.
2. **My own fix mis-landed in the StatefulSet** (an edit anchored on
   `image:`+`args:` matched the wrong block): invalid `restartPolicy:
   OnFailure` on the StatefulSet, still nothing on the Job. Both corrected.
3. **Kafka's probes used `sh` with `/dev/tcp`** — a bash-ism; the image's sh
   can't. `nc -z` (the image ships nc).
4. **ClamAV's entrypoint `chown`s its data dir** — illegal at uid 100; a root
   initContainer pre-owns the volume, the main container keeps the non-root
   posture.
5. **ClamAV also writes `/run/clamav` and `/var/lock`** — root-owned paths;
   emptyDirs carry them.
6. **The initdb ConfigMap mounted `defaultMode: 0500`** — root-owned files
   unreadable to postgres's uid 999: initdb created the cluster, the script
   step could not even read itself, and every later boot skipped
   initialization over a half-born cluster. `0444` — scripts are not secrets.
7. **The bucket-init command carried a stray trailing quote** (a string-edit
   artifact): `unexpected EOF while looking for matching '"'`. Fixed.
8. **`mc` writes its config under root-owned `$HOME`** — `MC_CONFIG_DIR=/tmp/mc`
   plus a tmp emptyDir.
9. **THE BIG ONE — Kubernetes EnableServiceLinks collides with the env
   placeholders.** For every Service named `novaforge-X`, k8s injects
   `NOVAFORGE_X_PORT=tcp://10.96.x.x:PORT` — so `NOVAFORGE_REDIS_PORT` and
   `NOVAFORGE_POSTGRES_PORT` resolved to tcp:// URLs in-cluster: the gateway
   died binding `spring.data.redis.port` to int, scheduler/workflow died on
   mangled JDBC URLs, while byte-identical boots outside a cluster (podman,
   same image, same env) ran clean — reproduced and isolated before the
   mechanism was named. Every pod spec now carries `enableServiceLinks:
   false` (DNS owns resolution), and the gate demands it on every workload
   render.
10. **The NetworkPolicy peer selectors matched the instance label** — correct
    in a single-release world, wrong in the stack's one-release-per-chart
    layout: services (instance=novaforge-metadata-service) never matched
    infra pods (instance=infra), and kindnet (which DOES enforce policy)
    silently dropped every service→infra flow — DB connects timed out.
    Peers now match by name/component labels only; kindnet enforcing them is
    itself runtime proof the policies load.

Plus one chart-mechanics defect found mid-pass and fixed: **helm does not
template values.yaml** — a `{{ .Values.* }}` expression in the env list
rendered literally into the pod (`Invalid boolean value [{{ ... }}]`). The
fail-closed `auth.allowDefaultSecret` posture is plain data in values,
consumed by the deployment template; the dev cluster overrides it with
`--set auth.allowDefaultSecret=true` (the compose stack's own posture — the
realm's dev client is the only credential that exists there), keeping the
fail-closed default for staged environments.

Two environment facts recorded for honesty: kind node containers that restart
under memory pressure can come back with a changed bridge IP (the kubelet then
dials the old one — recreate the cluster rather than restart it), and the
cold-cluster run that produced this record is the reproducible path.

### Verification (this pass)

The chart gate CLEAN across all 12 charts with two new contracts (Job
restartPolicy; enableServiceLinks false on every workload) — each new check
bite-proven by the very defects that motivated it. The live observation:
every pod 1/1 Running, the smoke evidence above, and the full serial
`./mvnw verify` (below). Cluster evidence retained in this record; the
environment's kind cluster was deleted after capture.

### Recorded open after this pass

The prior deliberate set stands. The live-cluster boundary is closed for the
dev stack; what remains honestly unobserved is a policy-enforcing CNI under
load, and multi-node behavior — both outside the recorded environment.

### Addendum — the live leg flushed an eleventh defect, in the test suite itself

The post-record full `./mvnw verify` failed: `GatewayApplicationTests` answered
503 on `/actuator/health` — at clean HEAD, worktree stashed, so nothing this
pass touched. The slice boots the rate limiter's `StringRedisTemplate`, whose
redis health contributor pings `spring.data.redis` — silently `localhost:6379`.
Nothing listening → DOWN → 503. The suite had passed for twenty-three passes
only when some ambient redis happened to be listening during the run — a
test-isolation defect (a hermetic slice with an ambient dependency), exposed
the moment the live-cluster work ran it against a quiet port. The slice
excludes the redis health contributor now (with the rationale in-code); the
limiter's own behavior stays pinned in `WebhookRateLimitFilterTest` (mocked
template, the atomic Lua window included), and the live leg itself proved the
real redis path in-cluster. Gateway 24/24 standalone; the full verify re-ran
green with the fix.

## Twenty-Fifth Pass — 2026-08-31 (the closeout re-audit: the landings hold, the gate's own teeth did not)

### The 9fef138 re-audit, area by area

- **enableServiceLinks placement** (render-audited, not template-audited — raw
  helm templates are not YAML and parsing them unrendered is noise): zero
  misplaced (the flag sits only on pod specs), zero missing across every
  rendered workload. The restartPolicy mis-landing class did not recur.
- **The NetworkPolicy peer-selector rewrite**: no instance-label matching
  survived in any peer; the deliberate exceptions are intact (the gateway's
  0.0.0.0/0:8080 dev ingress; clamav's ipBlock 0.0.0.0/0 on 443 ONLY — the
  freshclam rule, verified port-scoped on the render); every ingress rule
  carries from+ports and every egress peer a selector or namespaceSelector.
- **The values restructure**: all eleven service charts carry the plain-data
  `auth.allowDefaultSecret: false` block, all eleven deployment templates
  consume it, and no values env list retains the stale entry — the literal
  `{{ ... }}` class is gone.
- **The hermetic gateway slice**: gateway was the ONLY ambient-redis slice —
  data-runtime's fifteen context suites each start their own containers, and
  reporting's thirty are unit-level. No sibling defect.

### The finding — the gate's two newest contracts were toothless

Bite-proofing the Job-restartPolicy check (motivated by the live install
failure but never explicitly proven at the GATE level) exposed it: the check
printed `CHART-GATE FAIL: … Job lacks a valid restartPolicy` and **still
exited 0**. `ok = True` was initialized AFTER the render-walk, wiping every
failure the walk-phase checks recorded — both new contracts (Job
restartPolicy, enableServiceLinks) printed their FAILs without ever failing
the gate. The initialization moved before the walk (with the mechanism in a
comment), and both checks were re-bite-proven: removing the Job's
restartPolicy fails the gate (exit 1), removing a deployment's
enableServiceLinks fails it (exit 1), and the restored tree is CLEAN. The
older contracts (pods/SA/PDB/DNS/drift, which validate in the post-loop
phase) were never affected — the twenty-third pass's PDB bite-proof ran
against the validation phase and was genuine; this is also why the live
cluster caught the restartPolicy defect the gate should have: the gate's
check did not yet bite.

The pass's own lesson, recorded: a gate check without a bite-proof is a
print statement. Every contract in check-charts.sh has now been bite-proven
at least once against a deliberate violation.

### Verification (this pass)

Chart gate CLEAN across all 12 charts with all contracts genuinely armed;
full serial `./mvnw verify` (below) as the honest current-tree check. The
landings from the live-cluster pass hold everywhere the re-audit probed.

## Twenty-Sixth Pass — 2026-09-01 (the coverage sweep becomes the live leg: four defects the golden journey flushed, forty-three pins land)

### The method — three parallel coverage sweeps, then the live leg

The recorded-open defect list has been empty since the eighteenth pass, so this
pass attacked the anti-regression net itself: three parallel audits (core
services; the data-runtime + platform libs; the frontend workspace) asked one
question per production class — *which behavior branch does NO test pin anywhere
in the repo?* Thirty-four unpinned behaviors came back, ranked by risk. The pass
pinned the high-risk set (43 new tests) and re-ran the golden journey live —
which flushed four more real defects, all fixed with their own pins.

### The defects

1. **The builder SPA died at boot — `Root()` invoked at module scope**
   (`frontend/builder-ui/src/main.tsx`). The twelfth pass's ErrorBoundary wrap
   changed `createElement(Root)` to `createElement(ErrorBoundary, …, Root())` —
   *calling* the component function at module scope, where React's hook
   dispatcher is null: "Cannot read properties of null (reading 'useState')", no
   sign-in screen, no builder at all. Masked for four days because the built
   bundle is gitignored and was never rebuilt; no test executed the entry. Fixed
   (`createElement(Root)`), and pinned by `builder-ui/test/boot.test.tsx`, which
   imports the REAL entry module against a jsdom `#root` and asserts the boot UI
   renders — bite-proven (reverting the fix fails the test).

2. **The runtime shell addressed entities bare — same-named apps collided**
   (`frontend/runtime-ui/src/shell.tsx`). A tenant running two published apps
   that define the same entity apiName (the ERP corpus defines `Customer`; the
   journey's app too) 400s every bare-name write with "entity Customer is
   defined by multiple published apps — qualify the app". The engine's
   disambiguation surface is the app-qualified `App.Entity` form; reporting's
   run path adopted it live months ago, the shell never did. Every runtime
   client call now rides `qualified()` (list, search, get, create, update,
   delete, runHook); the pin (`shell.test.tsx`) asserts the create URL carries
   `erp.Customer`.

3. **The engine's list/aggregate lowering had NO entity scope**
   (`services/data-runtime/engine/.../query/QueryLowering.java`). The projection
   table is per entity apiName and SHARED by every of the tenant's apps defining
   it, but the lowered list/count/aggregate SQL filtered only `tenant_id` — a
   list answered with the sibling apps' rows (the journey's fresh app listed 9
   Customers from every app that ever defined one; aggregates summed across
   apps; roll-ups and period counts counted them). Writes carry the qualified
   `entity_id`; the lowering now scopes by it (table from the bare name,
   predicate from the key), all 14 call sites pass `handle.entityKey()`, and the
   SQL goldens pin the new canonical shape (`GoldenSqlTests`,
   `BucketedAggregateSqlTests` updated with the qualified key in the parameter
   vectors).

4. **The audit service had no problem+json advice at all**
   (`services/audit-service/.../api/ProblemAdvice.java`, new). Every
   `PlatformException` from the audit read API rendered as a raw 500
   ServletException — the limit bound (the surface's only thrower, "limit must
   be 1..200") rejected a hostile `?limit=0` as a whitelabel 500 instead of the
   platform's 400 problem+json (PHASE-0 §5.2). The advice is byte-shaped like
   the notification service's.

Plus two smaller landings: **the money twin's HALF_EVEN divide mis-rounded a cut
tail** (`shared/src/expression/decimal.ts`) — the guard-digit loop stops at 36
quotient digits with the remainder non-zero and `rounded()` then treated a `…50`
tail as an exact tie; the non-zero remainder makes the true discarded fraction
strictly more than half, so BigDecimal rounds UP where the twin rounded to even
(verified against the JVM directly on an adversarial vector, then fixed by
passing the inexact-tail fact into the rounding). And **the chart gate's own
newest contract validated a leaked variable** (`deploy/helm/check-charts.sh`):
the post-walk isolation loop read `ps.get("enableServiceLinks")` from the walk
phase's leftover loop variable — the LAST walked pod, checked N times — the
toothless-check class the twenty-fifth pass hunted, one generation later. The
flags now ride the pods tuple; the walk learned the CronJob shape
(`spec.jobTemplate.spec.template`) with the symmetric jobTemplate restartPolicy
contract; all bite-proven both ways (stripped flag → gate fails; synthetic
CronJob lacking four contracts → four failures; restored tree CLEAN).

### The pins (43 new tests; backend 523, frontend 203)

Backend — 24 (each pinned against the exact failure the sweep named):

- **Security**: `TenantTaskDecoratorTest` (the async tenant fence's
  clear-when-unbound branch on a real pool thread — a no-op regression leaks the
  previous task's tenant through pooled RLS queries); `includeDeletedIsAdminOnly`
  (a READ-granted clerk 403s on `?includeDeleted=true`; the admin reads the
  tombstone); `brokenCriteriaFailClosed` (a criterion no evaluator knows hides
  the row and 403s the list instead of widening); `SecretCipherTest` (dev-key
  fail-closed boot, malformed keys, wrong-key GCM failure, fresh IV per write);
  `membershipLookupFailureDropsRecipient` (fail-closed on lookup throw).
- **Financial controls**: `allModeRequiresEveryApproval` ("all"-mode unanimity —
  first vote suspends with siblings OPEN, the last resumes exactly once);
  `cachedRunKeepsMoneyDecimal` (the cache read re-types money BigDecimal, never
  Double); the decimal context pins in `shared/test/decimal.test.ts` (8 cases,
  JVM parity).
- **Delivery guarantees**: `outboxRetentionSparesUnpublished` on BOTH outboxes
  (retention never deletes an undelivered row); `relayPublishesDeliveredEvents
  WithHeaders` (topic derivation, tenant key, X-Event-* headers, published_at —
  consumed off real Kafka).
- **Fences and gates**: `idempotencyInFlightAndRelease` (in-flight duplicate
  409s; a failed create frees the key); `approversExpressionResolvingToNothing
  Rejects` (no approval nobody can act on); `upsertSecondEventUpdatesExisting
  Record` (the upsert UPDATE leg carries the existing id+version — which also
  exposed the webhook fake reading a dead filter shape, so the lookup leg had
  never once matched); the boot pin above.
- **Contracts**: `datetimeCanonicalization` (offset → fixed-width UTC, range
  filters on the canonical form); `deliveryIdShapeEnforced`;
  `readLimitBoundsReject`; `rotationMovesDefaultPartitionRowsIntoTheMonth` (the
  move-and-attach path — the fresh-CREATE path was the only one pinned);
  `SlaResolverCacheKeyTest` (the tenant-scoped 30 s cache key);
  `EntityExportCsvCellTest` (the integration csvCell copy — only the reporting
  twin was pinned); `publishOutboxRetentionSparesUnpublished`.

Frontend — 19: the decimal pins; `client-error.test.ts` (gateway 502 HTML and
empty proxy bodies keep the problem contract); `field-number.test.tsx` (blur
canonicalization; a half-typed "12." never crashes the tree); `list-surface
.test.tsx` (header clicks lower server-side sort with toggle/offset-reset/
aria-sort; a failed fetch renders the alert, never the empty state);
`renderer-slots.test.tsx` (expression readonly locks exactly while it holds; a
throwing visibility binding renders conservatively visible); `shell-guards
.test.tsx` (the lowercase-openPage navigation — the golden journey's historical
crash — drives the real renderer; a failed detail load renders the
Could-not-load alert).

### Verification (this pass)

Full `./mvnw verify` green across all 23 modules — 523 backend tests — against
the Podman socket; frontend `pnpm check` + `pnpm -r test` green (203 tests, up
from 183); chart gate CLEAN across all 12 charts with the repaired contracts
bite-proven; and the golden journey GREEN at final HEAD against the live stack
(real PKCE sign-in → onboarding → three entities → RBAC → dev publish → runtime
shell → record created through the real renderer, verified in the server-paged
list) — with the journey's own plural-count regex (`records?`) fixed on the way.

### Recorded open after this pass

The sweep's deliberate remainder (materializer per-shape failure isolation;
runaway-hook MAX_STEPS/MAX_DEPTH bounds; the integration export row ceiling;
the integration batch's SQL-failure per-item verdict; the builder auth module's
byte-twin of the runtime client; the runtime role-suffix mapping). Plus one
pre-existing wart observed, not introduced: `ApprovalFlowTests.resumeClaimFirst
VerdictWins` fails under `-Dtest` isolation (passes in the full-module and CI
mode) — an execution-context sensitivity worth its own look. Nothing else new
recorded open.

## Twenty-Seventh Pass — 2026-09-01 (the recorded remainder empties: every pass-26 leftover pinned, the "wart" was a stale jar)

### The remainder pins (7 new tests; backend 530, frontend 205)

Every behavior the twenty-sixth pass recorded as deliberately unpinned now has a
test:

- **Materializer per-shape failure isolation**
  (`MaterializerTests.brokenShapeIsolatesAndRetries`): a foreign table occupying
  a projection name stands in for "one app's bad DDL" — `applyAll` over a broken
  and a healthy app throws nothing, the sibling's projection materializes fully
  (table + trigger + RLS), the broken shape stays skipped, and once the
  obstruction is gone the next pass lands the projection (the idempotent-retry
  contract).
- **Runaway-hook bounds** (`HookStepResultTests`): an afterSave flow that
  `createRecord`s its own entity stabilizes at exactly the seed plus one nested
  echo — the hook dispatch's depth bound ends the self-creation cascade (a
  regression to an unbounded recursion explodes the count; a lost echo zeroes
  it) — and a beforeSave flow whose step's `next` points back at itself trips
  the 256-step budget: VALIDATION_FAILED aborts the write, zero rows, never an
  infinite loop.
- **Integration entity-export row ceiling**
  (`ImportResumeTests.entityExportRowCeilingFailsTheJob`): the first export-job
  test in the repo — a 100-row entity behind a 2-row ceiling fails the job on
  the FIRST page (the total is known immediately) with "exceeds the ceiling",
  one lookup, zero uploads: the in-memory assembly never scans or uploads
  unbounded.
- **Integration batch SQL-failure per-item verdict**
  (`IntegrationGuardLegTests.batchRawFailureIsPerItemVerdict`): a batch whose
  second item aborts on a raw RuntimeException (null entity — the unique-race/
  deadlock leg's stand-in) answers 200 with per-item verdicts — item one `ok`
  and committed, item two `error`/5000 "item failed: …" — a rethrow regression
  would convert the partially-committed batch into a request-level 500 with the
  committed verdicts lost.
- **Builder auth byte-twin** (`builder-ui/test/auth-twin.test.ts`): the OIDC
  client ships as byte-identical copies in both shells (only the runtime copy
  had behavioral tests); the canary pins the byte equality so token handling
  cannot silently drift — divergence forces a conscious split into `shared`.
- **Runtime role-suffix mapping** (`shell-guards.test.ts`): held roles map by
  suffix and filter against the app's own permission set — `erp.arClerk`
  (alongside a role the app never defined) drives the arClerk create grant,
  while a role the app does not define maps to nothing (no nav, no grants).

### The "wart" was a stale jar, not a test bug

`resumeClaimFirstVerdictWins` "fails under -Dtest isolation" is corrected: the
failure was an artifact of running module tests WITHOUT `-am`, which resolves
the engine from the last `install`ed jar in `~/.m2` — a snapshot predating
recent engine work. With the engine in the reactor (`-am`, or the full `verify`
CI runs), the test passes in every mode (verified: single-method, whole-class,
and full-module). The lesson stands for module-scoped loops: module tests need
`-am`, or a fresh `install`, never the last-installed snapshot.

### Verification (this pass)

Full `./mvnw verify` green across all 23 modules (530 backend tests); frontend
`pnpm check` + `pnpm -r test` green (205 tests). The chart gate and the golden
journey's green from the twenty-sixth pass are unaffected (no production code
changed beyond the pass-26 landings; this pass added tests only).

### Recorded open after this pass

Empty. Every behavior the coverage sweeps flagged as risky is now pinned, and
the remainder list is gone.

## Twenty-Eighth Pass — 2026-09-02 (the frontend boots the fresh bundle: three live-leg defects, one ungated artifact, all pinned)

### The method

The recorded-open set has been empty since the twenty-seventh pass, so this pass
re-ran the full baseline at HEAD and then hunted where the passes had never
looked: the golden-journey spec itself, the sample-app artifacts (`apps/perf`
had never been touched by any pass), the ops scripts (pitr-restore), the
runbooks, and the loadtest drivers. The baseline caught the first defect before
any hunt: **the frontend unit gate is red at HEAD** — `pnpm -r test` exits 1
with all 205 tests passing.

### The defects

1. **Both SPA entries boot through `hydrateRoot` — with nothing to hydrate**
   (`frontend/builder-ui/src/main.tsx`, `frontend/runtime-ui/src/main.tsx`).
   The gateway-served `index.html` ships an EMPTY `#root` (no SSR anywhere —
   the SPAs are client-only), so React 19 throws a hydration mismatch on every
   boot: the tree regenerates client-side, the error is logged in every
   browser console, and the thrown error escapes ASYNCHRONOUSLY — under load it
   lands after the boot test's window, which failed the whole vitest run with
   every test passing (the baseline's red, deterministic under CPU load;
   masked on an idle machine by timing). Both entries now mount through
   `createRoot`. The builder's boot pin gained a guard that captures
   `console.error` during boot and fails on any hydration/mismatch signal —
   the createRoot contract, deterministic instead of load-timing-lucky
   (bite-proven: reverting one entry to `hydrateRoot` fails the test).

2. **The runtime entry had ZERO test execution** — the twenty-sixth pass pinned
   the builder's boot; the runtime's `main.tsx` was never imported by any test,
   so the same class of boot death would have shipped silently again. New
   `runtime-ui/test/boot.test.tsx` mounts the REAL entry module against a jsdom
   `#root` and asserts the boot UI renders, with the same hydration guard.

3. **`pnpm package` — the documented volume-mount path — emitted bundles that
   cannot boot behind the gateway** (the live leg's blocker). Vite's default
   `base: "/"` writes bare `/assets/...` URLs into the shell document, but the
   SPAs deploy same-origin under `/builder/**` and `/runtime/**`
   (PHASE-2 §13 Q5): the shell loads and every module request 401s — a blank
   page. The gateway-embedded builds always rode the IMPLEMENTATION.md's
   `vite --base=/builder/` + `/runtime/` flags; the package script never did.
   Both build scripts now carry their prefix, and the package script runs a new
   gate (`frontend/scripts/check-bundle-base.mjs`) that fails the package when
   any shell asset URL escapes its hosting prefix — bite-proven both ways
   (stripped `--base` → package fails naming the offending URLs; restored →
   green).

4. **The perf fixture was the only ungated app artifact** (`apps/perf/
   perfhook-app.json` — never touched by any pass; the ERP and Purchasing
   artifacts have been CI-gated since their phases). The recorded
   ARCHITECTURE.md §9 numbers (write p95 < 150 ms with exactly one synchronous
   beforeSave hook + one record validation; filtered list < 300 ms over the
   status/dueDate indexes) are measured against exactly that shape — a silent
   fixture edit re-baselines every recorded number while the load scripts keep
   "passing". New `PerfAppArtifactTests` pins the save/compile-clean checks the
   builder would run plus the measured shape itself (one beforeSave setField
   hook, one record validation, the money field, the two single-column
   indexes).

### Verification (this pass)

Full `./mvnw verify` green across the reactor — 530 backend tests, 0 failures
(module-footed total, the two new pins included). Frontend `pnpm check` green;
`pnpm -r test` green — 206 tests, up from 205 (shared 121, builder 63, runtime
22). Chart gate CLEAN across all 12 charts. And the golden journey GREEN at
final HEAD against the live stack — real PKCE sign-in → onboarding → three
entities → RBAC → dev publish → runtime shell → record created through the real
renderer, verified in the server-paged list — with the journey's builder and
runtime legs riding the freshly built bundles this pass's fixes produced (the
gateway jar rebuilt around them, asset requests verified prefixed and 200).

### Recorded open after this pass

Empty.
