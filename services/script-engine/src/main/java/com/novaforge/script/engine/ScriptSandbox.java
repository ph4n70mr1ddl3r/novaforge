package com.novaforge.script.engine;

import com.novaforge.common.context.TenantContext;
import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import jakarta.annotation.PreDestroy;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.springframework.stereotype.Component;

/**
 * The GraalVM JS sandbox (ADR-003, PHASE-3 §6): one {@link Context} per execution —
 * v0 has no warm pools (deferred with demand, ADR-003 #4) — with the ADR's three caps
 * made real on the community runtime:
 *
 * <ul>
 *   <li><b>CPU-time cap</b> — the watchdog thread samples the executing thread's CPU
 *       time ({@link ThreadMXBean}) and force-closes the context at the budget; a
 *       wall-clock backstop covers a script wedged inside a host call.</li>
 *   <li><b>Heap cap</b> — the watchdog trips on process-heap growth attributable to
 *       the execution. Per-context heap metering is a GraalVM Enterprise capability;
 *       on community this attribution is process-level (conservative under
 *       concurrency — a hostile script dies, a well-behaved one never approaches
 *       the cap), and the statement watchdog bounds allocation loops independently.</li>
 *   <li><b>Loop watchdog</b> — a statement limit via {@link ResourceLimits}; an
 *       infinite loop dies at its cap, never takes a thread hostage.</li>
 * </ul>
 *
 * <p>No host I/O and no host classes; the closed surface is exactly {@code $record}
 * (read-only view of the triggering record), {@code $data.query} (the Data Runtime
 * query API under the <em>calling</em> user's authorization — ARCHITECTURE.md §5
 * item 4), and {@code $log} (bounded capture). Executions are stateless (ADR-003 #3);
 * concurrency is bounded so a script flood cannot take the JVM hostage.</p>
 */
@Component
public class ScriptSandbox {

    /**
     * Explicit member access for the exported surface, plus container access so
     * host maps/lists ($record, query results) read naturally from JS — and nothing
     * else: no public reflection, no method scoping surprises.
     */
    private static final HostAccess HOST_ACCESS = HostAccess.newBuilder(HostAccess.EXPLICIT)
            .allowMapAccess(true)
            .allowListAccess(true)
            .allowArrayAccess(true)
            .build();

    /** Result of one execution: the returned value + captured logs (bounded). */
    public record ScriptResult(Object value, List<String> logs, long elapsedMillis) {
    }

    /** The trigger label of a recordless scheduled firing (PHASE-4 §7). */
    public static final String SCHEDULED_TRIGGER = "scheduled";

    private final QueryProxy queryProxy;
    private final HttpProxy httpProxy;
    private final long cpuBudgetNanos;
    private final long wallBudgetNanos;
    private final long heapLimitBytes;
    private final long statementLimit;
    private final long queueWaitMillis;
    private final Semaphore lanes;
    private final ScheduledExecutorService watchdog;
    private final ThreadMXBean threads;
    private final boolean cpuMetering;

    public ScriptSandbox(QueryProxy queryProxy,
                         HttpProxy httpProxy,
                         @org.springframework.beans.factory.annotation.Value("${novaforge.scripts.cpu-budget-ms:1000}") long cpuBudgetMillis,
                         @org.springframework.beans.factory.annotation.Value("${novaforge.scripts.wall-budget-ms:30000}") long wallBudgetMillis,
                         @org.springframework.beans.factory.annotation.Value("${novaforge.scripts.heap-limit-mb:64}") long heapLimitMb,
                         @org.springframework.beans.factory.annotation.Value("${novaforge.scripts.statement-limit:100000}") long statementLimit,
                         @org.springframework.beans.factory.annotation.Value("${novaforge.scripts.max-concurrent:8}") int maxConcurrent,
                         @org.springframework.beans.factory.annotation.Value("${novaforge.scripts.queue-wait-ms:5000}") long queueWaitMillis) {
        if (cpuBudgetMillis <= 0 || wallBudgetMillis <= 0 || heapLimitMb <= 0
                || statementLimit <= 0 || maxConcurrent <= 0 || queueWaitMillis < 0) {
            throw new IllegalArgumentException("script sandbox limits must be positive");
        }
        this.queryProxy = queryProxy;
        this.httpProxy = httpProxy;
        this.cpuBudgetNanos = TimeUnit.MILLISECONDS.toNanos(cpuBudgetMillis);
        this.wallBudgetNanos = TimeUnit.MILLISECONDS.toNanos(wallBudgetMillis);
        this.heapLimitBytes = heapLimitMb * 1024 * 1024;
        this.statementLimit = statementLimit;
        this.queueWaitMillis = queueWaitMillis;
        this.lanes = new Semaphore(maxConcurrent);
        this.watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "script-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        this.cpuMetering = bean.isCurrentThreadCpuTimeSupported();
        if (cpuMetering) {
            bean.setThreadCpuTimeEnabled(true);
        }
        this.threads = bean;
    }

    /**
     * Boot warmup: the first {@code Context.eval} in a JVM pays GraalVM's one-time
     * truffle-JS bootstrap (class loading + compilation) — easily a second or more of
     * CPU, which lands inside whichever guest script runs first and trips its CPU
     * budget for work the script never did (found live: the ERP costing script died
     * on the first fire after a cold start). A no-op eval at startup moves that cost
     * outside every guest budget; ADR-003's deferred warm pools stay deferred — this
     * is initialization, not pooling. Failures are swallowed: a warmup miss only
     * means the first real execution pays bootstrap, exactly the old behavior.
     */
    @jakarta.annotation.PostConstruct
    void warmEngine() {
        try {
            runCapped("0", (bindings, logs) -> { });
        } catch (RuntimeException ignored) {
            // never a boot failure — see above
        }
    }

    /**
     * Executes {@code script} against the read-only {@code record} view as
     * {@code caller}. Throws {@link PlatformException} VALIDATION_FAILED for every
     * author-side outcome (script error, cap kill, malformed result); host errors
     * raised by {@code $data.query} rethrow unchanged so the caller's authorization
     * verdicts (e.g. FORBIDDEN) survive the sandbox boundary.
     */
    public ScriptResult execute(String script, Map<String, Object> record,
                                TenantContext.Context caller) {
        return execute(script, record, caller, null);
    }

    /**
     * The connector-sandbox execution (PHASE-6 §4): when the artifact declares the
     * connector sandbox context, {@code $http} joins the surface — routed through
     * the Integration Service's circuit-breaker/credential machinery, never raw
     * sockets (the PHASE-3 §6 deferral activating per its terms).
     */
    public ScriptResult execute(String script, Map<String, Object> record,
                                TenantContext.Context caller, String connectorApp) {
        try {
            if (!lanes.tryAcquire(queueWaitMillis, TimeUnit.MILLISECONDS)) {
                throw new PlatformException(PlatformErrorCode.INTERNAL,
                        "script execution capacity exceeded — retry");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "script execution interrupted while awaiting a lane");
        }
        try {
            return runCapped(script, (bindings, logs) -> {
                bindings.putMember("$record", Collections.unmodifiableMap(
                        record == null ? Map.of() : record));
                bindings.putMember("$data", new DataSurface(queryProxy, caller));
                bindings.putMember("$log", new LogSurface(logs));
                // $http exists only inside the connector sandbox (§4): a script
                // outside the declared context never sees the egress at all.
                if (connectorApp != null) {
                    bindings.putMember("$http", new HttpSurface(httpProxy, caller, connectorApp));
                }
            });
        } finally {
            lanes.release();
        }
    }

    /**
     * The Scheduler's {@code script} target (PHASE-4 §7): a recordless firing in the
     * synthetic {@code scheduled} context — {@code $record} is absent (a script that
     * reaches for it dies loudly, not on empty data), {@code $data.query} rides the
     * internal system-principal leg, and the per-app system principal bound in
     * {@code principal} is the executing identity (engine-driven actions run as it,
     * PHASE-4 §4 — this is the write-path script leg's caller-context rule, not a
     * service-account fallback: no user ever initiated this execution).
     */
    public ScriptResult executeScheduled(String script, String app,
                                         TenantContext.Context principal, String connectorApp) {
        try {
            if (!lanes.tryAcquire(queueWaitMillis, TimeUnit.MILLISECONDS)) {
                throw new PlatformException(PlatformErrorCode.INTERNAL,
                        "script execution capacity exceeded — retry");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "script execution interrupted while awaiting a lane");
        }
        try {
            return runCapped(script, (context, logs) -> {
                // $record stays absent in the scheduled context (§7) — a reach for
                // it is a ReferenceError, never a silent empty view
                context.putMember("$data", new DataSurface(queryProxy, principal, app, true));
                context.putMember("$log", new LogSurface(logs));
                if (connectorApp != null) {
                    context.putMember("$http", new HttpSurface(httpProxy, principal, connectorApp));
                }
            });
        } finally {
            lanes.release();
        }
    }

    /** Installs one execution's guest bindings — the capped runner's only variable. */
    private interface Binder {

        void bind(org.graalvm.polyglot.Value bindings, List<String> logs);
    }

    private ScriptResult runCapped(String script, Binder binder) {
        List<String> logs = new CopyOnWriteArrayList<>();
        long startNanos = System.nanoTime();
        Thread executor = Thread.currentThread();
        long executorId = executor.getId();
        long startCpu = cpuMetering ? Math.max(0L, threads.getThreadCpuTime(executorId)) : 0L;
        long startHeap = usedHeap();
        AtomicReference<String> killReason = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean finished =
                new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean heapOverBudget =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Converter converter = new Converter();
        try (Context context = Context.newBuilder("js")
                .allowHostAccess(HOST_ACCESS)
                .allowHostClassLookup(className -> false)     // Java.type & friends: closed
                .allowIO(IOAccess.NONE)                       // no filesystem, no sockets
                .allowCreateProcess(false)
                .allowNativeAccess(false)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .option("engine.WarnInterpreterOnly", "false")
                .resourceLimits(ResourceLimits.newBuilder()
                        .statementLimit(statementLimit, null)
                        .build())
                .build()) {
            ScheduledFuture<?> watch = watchdog.scheduleAtFixedRate(() -> {
                if (killReason.get() != null || finished.get()) {
                    return;   // killed already, or result materialized host-side
                }
                if (cpuMetering
                        && threads.getThreadCpuTime(executorId) - startCpu >= cpuBudgetNanos) {
                    if (killReason.compareAndSet(null, "CPU")) {
                        context.close(true);
                    }
                } else if (System.nanoTime() - startNanos >= wallBudgetNanos) {
                    if (killReason.compareAndSet(null, "wall-clock")) {
                        context.close(true);
                    }
                } else if (usedHeap() - startHeap >= heapLimitBytes) {
                    // The heap meter is process-wide (per-context metering is
                    // Enterprise-only), so unrelated same-JVM allocation churn can
                    // spike past the cap for one sample. Trip only on two consecutive
                    // over-budget readings — a real hog holds the growth; a transient
                    // burst (GC lag, parallel test load) recovers between samples.
                    if (heapOverBudget.get()
                            && killReason.compareAndSet(null, "heap")) {
                        context.close(true);
                    }
                    heapOverBudget.set(true);
                } else {
                    heapOverBudget.set(false);
                }
            }, 50, 25, TimeUnit.MILLISECONDS);
            try {
                binder.bind(context.getBindings("js"), logs);
                Value outcome = context.eval("js", script);
                // conversion re-enters guest code (getters, Proxy traps run on member
                // access) — the watchdog stays armed until the result is host-side
                Object converted = converter.convert(outcome);
                List<String> captured = List.copyOf(logs);
                finished.set(true);
                return new ScriptResult(converted, captured,
                        (System.nanoTime() - startNanos) / 1_000_000);
            } finally {
                watch.cancel(false);
            }
        } catch (PolyglotException e) {
            String killed = killReason.get();
            if (killed != null) {
                throw new ScriptBudgetExceededException(
                        "script exceeded its " + killed + " budget and was terminated");
            }
            if (e.isResourceExhausted()) {
                throw new ScriptBudgetExceededException(
                        "script exceeded its statement budget (" + statementLimit
                                + ") and was terminated");
            }
            if (e.isHostException()) {
                Throwable host = e.asHostException();
                if (host instanceof PlatformException platform) {
                    throw platform;   // the caller's authorization verdict, unchanged
                }
                throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                        "script failed: " + host.getMessage(), null, host);
            }
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "script failed: " + e.getMessage());
        }
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    @PreDestroy
    void shutdown() {
        watchdog.shutdownNow();
    }

    /**
     * $data — the query surface under the calling user's authorization (§5 item 4):
     * every call rides the caller's tenant and actor; the proxy forwards to the Data
     * Runtime's query API with the caller's token, so a script can never exceed its
     * authorizing user's grants. The system mode is the scheduled context's leg
     * (PHASE-4 §7): the internal surface, the per-app system principal.
     */
    public static final class DataSurface {

        private static final java.util.regex.Pattern ENTITY =
                java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

        private final QueryProxy proxy;
        private final TenantContext.Context caller;
        private final String app;
        private final boolean system;

        DataSurface(QueryProxy proxy, TenantContext.Context caller) {
            this(proxy, caller, null, false);
        }

        DataSurface(QueryProxy proxy, TenantContext.Context caller, String app, boolean system) {
            this.proxy = proxy;
            this.caller = caller;
            this.app = app;
            this.system = system;
        }

        @HostAccess.Export
        public Object query(String entity, String queryJson) {
            if (entity == null || !ENTITY.matcher(entity).matches()) {
                throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                        "$data.query entity must be an api name: " + entity);
            }
            String query = queryJson == null || queryJson.isBlank() ? "{}" : queryJson;
            return system
                    ? proxy.systemQuery(caller, app, entity, query)
                    : proxy.query(caller, entity, query);
        }
    }

    /**
     * $http — the connector-sandbox egress (PHASE-6 §4): one named operation of one
     * published connector, through the same circuit-breaker/credential machinery
     * {@code callConnector} rides. Never a raw URL — the §4 pins apply to scripts
     * exactly as to flows.
     */
    public static final class HttpSurface {

        private static final java.util.regex.Pattern NAME =
                java.util.regex.Pattern.compile("[a-zA-Z_][a-zA-Z0-9_-]*");

        private final HttpProxy proxy;
        private final TenantContext.Context caller;
        private final String app;

        HttpSurface(HttpProxy proxy, TenantContext.Context caller, String app) {
            this.proxy = proxy;
            this.caller = caller;
            this.app = app;
        }

        @HostAccess.Export
        public Object call(String connector, String operation, Object template) {
            if (connector == null || !NAME.matcher(connector).matches()
                    || operation == null || operation.isBlank()) {
                throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                        "$http.call requires a connector id and an operation name");
            }
            Map<String, Object> params = template instanceof Map<?, ?> map
                    ? new java.util.LinkedHashMap<>() {{
                        map.forEach((key, value) -> put(String.valueOf(key), value));
                    }} : Map.of();
            return proxy.call(caller, app, connector, operation, params);
        }
    }

    /** $log — bounded capture (scripts stay observable without host stdout). */
    public static final class LogSurface {

        private final List<String> logs;
        private static final int MAX_ENTRIES = 100;

        LogSurface(List<String> logs) {
            this.logs = logs;
        }

        @HostAccess.Export
        public void info(String message) {
            capture("INFO " + message);
        }

        @HostAccess.Export
        public void warn(String message) {
            capture("WARN " + message);
        }

        @HostAccess.Export
        public void error(String message) {
            capture("ERROR " + message);
        }

        private void capture(String entry) {
            if (logs.size() < MAX_ENTRIES) {
                logs.add(entry);
            }
        }
    }

    /**
     * Guest-to-host value conversion: JS values become plain Java types the wire can
     * carry (objects → maps, arrays → lists, integral numbers → long, else double).
     * Functions and symbols map to null — the record write-back channel only accepts
     * data. Bounded: a conversion over {@link Converter#MAX_NODES} nodes rejects, so a
     * statement-heavy script cannot smuggle a huge structure out in one value.
     */
    private static final class Converter {

        static final int MAX_NODES = 8192;

        private int nodes;

        Object convert(Value value) {
            if (++nodes > MAX_NODES) {
                throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                        "script result exceeds the conversion budget (" + MAX_NODES + " nodes)");
            }
            if (value.isNull()) {
                return null;
            }
            if (value.isHostObject()) {
                Object host = value.asHostObject();
                return host instanceof Map<?, ?> || host instanceof List<?> ? host : null;
            }
            if (value.isString()) {
                return value.asString();
            }
            if (value.isBoolean()) {
                return value.asBoolean();
            }
            if (value.isNumber()) {
                double number = value.asDouble();
                if (number == Math.rint(number) && !Double.isInfinite(number)
                        && Math.abs(number) <= 9_007_199_254_740_992.0) {
                    return (long) number;
                }
                return number;
            }
            if (value.hasArrayElements()) {
                java.util.List<Object> list = new java.util.ArrayList<>();
                for (long i = 0; i < value.getArraySize(); i++) {
                    list.add(convert(value.getArrayElement(i)));
                }
                return list;
            }
            if (value.hasMembers()) {
                Map<String, Object> map = new java.util.LinkedHashMap<>();
                for (String key : value.getMemberKeys()) {
                    map.put(key, convert(value.getMember(key)));
                }
                return map;
            }
            return null;   // functions, symbols, undefined — not data
        }
    }
}
