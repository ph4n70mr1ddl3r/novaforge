package com.novaforge.metadata;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * A scheduled job (PHASE-4 §7): the scheduled-job half of the Business Rules branch
 * — versioned metadata activated on publish, never authored against the runtime
 * registry (the job-definitions-vs-registry split). {@code target} is the closed v1
 * set: {@code flow} (the compiled-graph engine, system principal, synthetic
 * {@code scheduled} trigger context — {@code $record} absent), {@code script}
 * (dormant until the Script Engine carries a service execution context),
 * {@code processStart} (the Workflow Service's BPMN engine, PHASE-4 §9 — params
 * {@code {process, recordId?, variables?}}), {@code report} (dormant until
 * Phase 5). Misfire policy: fire once, skip missed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScheduledJobDefinition(
        String name,
        String cron,
        String target,
        Map<String, Object> params,
        Boolean enabled) {

    public ScheduledJobDefinition {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    public boolean enabledOn() {
        return !Boolean.FALSE.equals(enabled);
    }

    /** The v1 target set (§7). */
    public static final java.util.Set<String> TARGETS =
            java.util.Set.of("flow", "script", "processStart", "report");

    public String param(String name) {
        Object value = params.get(name);
        return value == null ? null : String.valueOf(value);
    }
}
