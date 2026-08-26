package com.novaforge.common.context;

import java.util.Optional;

/**
 * ThreadLocal holder for the active tenant/actor request context (PHASE-0 §5.2).
 *
 * <p>Set by each service's auth filter from the verified JWT claims — services never
 * trust the gateway's {@code X-Tenant-Id} header (ARCHITECTURE.md §5.1). Clearing on
 * request end is the caller's responsibility; {@link #with(Context, Runnable)} and
 * {@link #wrap(Context, Runnable)} are provided for scoped use.
 *
 * <p>Uses {@link ThreadLocal} (not InheritableThreadLocal) deliberately: plain
 * ThreadLocals are never inherited by spawned threads (platform or virtual), so a
 * pooled worker cannot smear one request's identity onto unrelated work.
 * Propagation to executor threads is explicit — {@link
 * com.novaforge.security.TenantTaskDecorator} captures the binding at submit time and
 * rebinds it per run — and the request thread itself binds and clears inside the
 * auth filter.</p>
 */
public final class TenantContext {

    private static final ThreadLocal<Context> HOLDER = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Context context) {
        HOLDER.set(context);
    }

    /** Current context, or empty when no authenticated tenant is bound to this thread. */
    public static Optional<Context> current() {
        return Optional.ofNullable(HOLDER.get());
    }

    /** Convenience accessor that throws when no tenant is bound (fail closed). */
    public static Context require() {
        Context ctx = HOLDER.get();
        if (ctx == null) {
            throw new IllegalStateException("No tenant context bound to this thread");
        }
        return ctx;
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** Runs {@code action} with {@code context} bound, restoring the previous binding after. */
    public static void with(Context context, Runnable action) {
        Context previous = HOLDER.get();
        try {
            HOLDER.set(context);
            action.run();
        } finally {
            if (previous == null) {
                HOLDER.remove();
            } else {
                HOLDER.set(previous);
            }
        }
    }

    /** Returns a runnable that binds {@code context} for the duration of each run. */
    public static Runnable wrap(Context context, Runnable action) {
        return () -> with(context, action);
    }

    /** Runs {@code action} with {@code context} bound, returning its value. */
    public static <T> T call(Context context, java.util.function.Supplier<T> action) {
        Context previous = HOLDER.get();
        try {
            HOLDER.set(context);
            return action.get();
        } finally {
            if (previous == null) {
                HOLDER.remove();
            } else {
                HOLDER.set(previous);
            }
        }
    }

    /** Tenant + actor identity propagated through the request pipeline. */
    public record Context(String tenantId, String actorId) {
        public Context {
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException("tenantId must not be blank");
            }
            if (actorId == null || actorId.isBlank()) {
                throw new IllegalArgumentException("actorId must not be blank");
            }
        }
    }
}
