package com.novaforge.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.novaforge.common.context.TenantContext;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The async tenant fence (re-audit pin): the decorator must propagate the
 * submit-time binding into the worker AND — the branch that keeps pooled
 * threads honest — CLEAR the worker thread when the task was submitted with no
 * tenant. If the clear regresses to a no-op, the next task a pool thread runs
 * executes under the previous task's tenant: RLS queries leak across tenants
 * on exactly the async surfaces (retry scanners, scheduler executors) this
 * decorator wires. Both branches run on a real pool thread, the way executors
 * actually use them.
 */
class TenantTaskDecoratorTest {

    private final TenantTaskDecorator decorator = new TenantTaskDecorator();

    private ExecutorService pool;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a bound submit rebinds the captured tenant on the worker thread")
    void boundSubmitRebindsOnWorker() throws Exception {
        pool = Executors.newSingleThreadExecutor();
        var tenant = UUID.randomUUID().toString();
        var actor = UUID.randomUUID().toString();
        var seen = new AtomicReference<TenantContext.Context>();
        Runnable task;
        try {
            TenantContext.set(new TenantContext.Context(tenant, actor));
            task = decorator.decorate(() -> seen.set(TenantContext.current().orElse(null)));
        } finally {
            TenantContext.clear();
        }
        pool.submit(task).get(5, TimeUnit.SECONDS);
        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().tenantId()).isEqualTo(tenant);
        assertThat(seen.get().actorId()).isEqualTo(actor);
        // the worker's binding died with the task — the submitting thread stays clean
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    @DisplayName("an unbound submit clears the pool thread's stale tenant — no leak to the next task")
    void unboundSubmitClearsStaleWorkerTenant() throws Exception {
        pool = Executors.newSingleThreadExecutor();
        var tenant = UUID.randomUUID().toString();
        var stale = new AtomicReference<TenantContext.Context>();

        // the pool thread's FIRST task runs under tenant A (a bound submit)
        Runnable first;
        try {
            TenantContext.set(new TenantContext.Context(tenant, UUID.randomUUID().toString()));
            first = decorator.decorate(() -> { });
        } finally {
            TenantContext.clear();
        }
        pool.submit(first).get(5, TimeUnit.SECONDS);

        // the SECOND task on the same pooled thread was submitted unbound: the
        // decorator must clear A before it runs — not let it smear onto this task
        var second = decorator.decorate(() -> stale.set(TenantContext.current().orElse(null)));
        pool.submit(second).get(5, TimeUnit.SECONDS);
        assertThat(stale.get()).isNull();
    }
}
