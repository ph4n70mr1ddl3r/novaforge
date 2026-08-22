package com.novaforge.metadata;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A BPMN process definition (PHASE-4 §9, ADR-004): versioned, promoted metadata —
 * v1 authors BPMN as XML (import/editor-agnostic; the visual designer defers with
 * demand). The {@code <process id>} inside the XML must equal {@code id} — it is the
 * process definition key the engine starts.
 *
 * <p>Event-start subscriptions (ARCHITECTURE.md §2.6) are metadata on the
 * definition, not triggers inside the XML: a matching spine event starts the
 * process. The {@code filter} is a platform expression compiled at publish in the
 * record context of the bound {@code entity}; the Workflow Service evaluates it
 * against the record's current state (system principal, a read — never a
 * mutation).</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowDefinition(
        String id,
        String bpmn,
        List<EventStart> eventStarts) {

    public WorkflowDefinition {
        eventStarts = eventStarts == null ? List.of() : List.copyOf(eventStarts);
    }

    /** The spine event types a subscription may start on (§9). */
    public static final java.util.Set<String> EVENT_TYPES =
            java.util.Set.of("record.created", "record.updated");

    /** BPMN is bounded like scripts (ADR-003's source cap — the same order). */
    public static final int MAX_BPMN_CHARS = 256 * 1024;

    /** BPMN process keys are NCNames: a letter/underscore, then word characters. */
    public static final java.util.regex.Pattern PROCESS_KEY =
            java.util.regex.Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    /**
     * One event-start subscription: {@code on event where entity = entity and
     * filter}. A blank {@code filter} matches every event of the type for the
     * entity.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EventStart(String event, String entity, String filter) {
    }
}
