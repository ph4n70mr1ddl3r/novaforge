package com.novaforge.runtime.events;

import com.novaforge.common.context.TenantContext;
import com.novaforge.runtime.engine.RecordEngine;
import com.novaforge.runtime.engine.RecordEngine.RetryOutcome;
import com.novaforge.runtime.storage.retry.HookRetryStore;
import com.novaforge.runtime.storage.retry.HookRetryStore.PendingRetry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives due after-hook retries (PHASE-3 §2 failure policy): each pass claims pending
 * rows whose backoff has elapsed and re-runs the hook through
 * {@link RecordEngine#retryAfterHook} — the record's current state, the per-app system
 * principal, the same context the original execution had. Attempts run under
 * exponential backoff until the hook runs clean; exhausted or non-convergent retries
 * park durably (never lost) and surface on {@code novaforge.hook.retry.outcome}.
 */
@Component
public class HookRetryScanner {

    private static final Logger LOG = LoggerFactory.getLogger(HookRetryScanner.class);

    /** Backoff ceiling — attempts spread to at most this far apart. */
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(10);

    private final HookRetryStore retries;
    private final RecordEngine engine;
    private final int maxAttempts;
    private final long backoffBaseMs;
    private final io.micrometer.core.instrument.Counter ok;
    private final io.micrometer.core.instrument.Counter failed;
    private final io.micrometer.core.instrument.Counter parked;

    public HookRetryScanner(HookRetryStore retries, RecordEngine engine, MeterRegistry meters,
                            @Value("${novaforge.hooks.retry.max-attempts:8}") int maxAttempts,
                            @Value("${novaforge.hooks.retry.backoff-base-ms:5000}") long backoffBaseMs) {
        this.retries = retries;
        this.engine = engine;
        this.maxAttempts = maxAttempts;
        this.backoffBaseMs = backoffBaseMs;
        this.ok = meters.counter("novaforge.hook.retry.outcome", "result", "ok");
        this.failed = meters.counter("novaforge.hook.retry.outcome", "result", "failed");
        this.parked = meters.counter("novaforge.hook.retry.outcome", "result", "parked");
        meters.gauge("novaforge.hook.retry.pending", retries, HookRetryStore::pendingCount);
    }

    @Scheduled(fixedDelayString = "${novaforge.hooks.retry.scan-interval-ms:2000}")
    public void scan() {
        scanOnce();
    }

    /** One pass — public so tests drive it deterministically instead of waiting. */
    public void scanOnce() {
        for (PendingRetry retry : retries.due(100)) {
            try {
                TenantContext.with(retryContext(retry), () -> execute(retry));
            } catch (RuntimeException e) {
                // Bookkeeping failures (resolve/park errors) still count as attempts —
                // the retry stays observable rather than vanishing mid-flight.
                attemptFailed(retry, e.getMessage());
            }
        }
    }

    private void execute(PendingRetry retry) {
        RetryOutcome outcome;
        try {
            outcome = engine.retryAfterHook(retry.tenantId(), entityApiName(retry.entityId()),
                    retry.recordId(), retry.triggerName(), retry.hookName());
        } catch (RuntimeException e) {
            LOG.warn("hook retry {} on {} failed (attempt {}): {}",
                    retry.hookName(), retry.entityId(), retry.attempt(), e.getMessage());
            attemptFailed(retry, e.getMessage());
            return;
        }
        switch (outcome) {
            case OK -> {
                retries.markOk(retry.eventId());
                ok.increment();
            }
            case HOOK_GONE -> {
                retries.park(retry.eventId(), retry.attempt(),
                        "hook no longer exists in the published definition");
                parked.increment();
            }
            case RECORD_GONE -> {
                retries.park(retry.eventId(), retry.attempt(),
                        "record is gone — nothing to re-drive the hook against");
                parked.increment();
            }
        }
    }

    private void attemptFailed(PendingRetry retry, String error) {
        failed.increment();
        int next = retry.attempt() + 1;
        if (next > maxAttempts) {
            LOG.error("hook retry {} on {} exhausted after {} attempts: {} — parked, "
                    + "never retried again (manual re-trigger required)",
                    retry.hookName(), retry.entityId(), retry.attempt(), error);
            retries.park(retry.eventId(), retry.attempt(),
                    "attempts exhausted: " + error);
            parked.increment();
            return;
        }
        retries.reschedule(retry.eventId(), next, Instant.now().plus(backoff(next)), error);
    }

    /** Exponential backoff: base × 2^(attempt-1), capped. */
    private Duration backoff(int attempt) {
        long multiplier = 1L << Math.min(attempt - 1, 20);
        long delay = Math.min(backoffBaseMs * multiplier, MAX_BACKOFF.toMillis());
        return Duration.ofMillis(delay);
    }

    /** The entity key is "<App>.<Entity>" — the runtime API names the entity alone. */
    private static String entityApiName(String entityId) {
        return entityId.substring(entityId.indexOf('.') + 1);
    }

    /** RLS binding: the retry runs as the app's system principal (§13 Q1 — audited). */
    private static TenantContext.Context retryContext(PendingRetry retry) {
        String appApiName = retry.entityId().substring(0, retry.entityId().indexOf('.'));
        return new TenantContext.Context(retry.tenantId().toString(),
                UUID.nameUUIDFromBytes(("system:" + appApiName).getBytes()).toString());
    }
}
