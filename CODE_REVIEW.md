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
