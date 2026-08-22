package com.novaforge.script.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Script-ratio telemetry (ADR-003 #5, ADR-008 #5, PHASE-3 §6): executions counted per
 * app version and trigger with their outcome, plus duration. The ratio itself — the
 * share of hooks implemented without scripts per app version — joins these counters
 * with the runtime's hook-kind counters on the dashboards (§9): a rising script share
 * is change-set-review signal, and a script pattern appearing twice is a candidate
 * primitive.
 */
@Component
public class ScriptMetrics {

    /** outcome tag values: {@code ok | capped | error}. */
    public static final String OK = "ok";
    public static final String CAPPED = "capped";
    public static final String ERROR = "error";

    private final MeterRegistry registry;

    public ScriptMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Counts one finished execution (any outcome — the counter is the ratio base). */
    public void executed(String app, Integer appVersion, String trigger, String outcome) {
        registry.counter("novaforge.script.executions",
                        "app", safe(app),
                        "version", appVersion == null ? "unknown" : String.valueOf(appVersion),
                        "trigger", safe(trigger), "outcome", outcome)
                .increment();
    }

    /** Records wall-clock duration; caps bound it, so the tail stays honest. */
    public void duration(String trigger, long elapsedMillis) {
        Timer.builder("novaforge.script.duration")
                .tag("trigger", safe(trigger))
                .description("Script execution wall-clock duration")
                .register(registry)
                .record(java.time.Duration.ofMillis(elapsedMillis));
    }

    private static String safe(String tag) {
        return tag == null || tag.isBlank() ? "unknown" : tag;
    }
}
