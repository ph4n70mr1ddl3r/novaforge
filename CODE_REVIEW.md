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
reconcile), per-app scoping of shared-projection unique indexes, the
unremovable-last-list-branch PATCH semantics (needs a presence-preserving patch
shape — an API design decision), and the webhook upsert fence (document that upsert
keys must be `unique` fields, or add an engine keyed primitive).
