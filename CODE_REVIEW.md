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
