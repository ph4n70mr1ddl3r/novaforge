package com.novaforge.metadata;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One gap-log entry (PHASE-7 §1 rule 2 / §8, PHASE-8 §3): the dogfood discipline —
 * <em>every gap becomes a log entry before any workaround</em> — as versioned app
 * metadata, so change-set review can render "the gap-log entries the version
 * resolves (Phase 7 continuity)" without a second system. The entry shape is the
 * spec's own: {@code { area, blocker, workaround, proposedPrimitiveOrFlag, priority }}
 * plus the §8 triage disposition.
 *
 * <p>Entries ride the app definition (the {@code gapLog} branch, kind-discriminated
 * documents like every other branch), are promoted with the app, and ride the
 * promotion artifact — tenant data and secret material never do.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GapLogEntry(
        String id,
        String area,
        String blocker,
        String workaround,
        String proposed,
        String priority,
        String disposition,
        String resolvedIn) {

    /** Gap ids: {@code G-1}, {@code fifo-costing} — readable keys, stable in diffs. */
    public static final java.util.regex.Pattern GAP_ID =
            java.util.regex.Pattern.compile("^[A-Za-z][A-Za-z0-9]*(?:-[A-Za-z0-9]+)*$");

    /** The §8 triage outcomes plus {@code open} (logged, not yet triaged). */
    public static final java.util.Set<String> DISPOSITIONS = java.util.Set.of(
            "open", "accept-as-platform-feature", "backlog", "wontfix-with-workaround", "closed");

    public static final java.util.Set<String> PRIORITIES = java.util.Set.of(
            "high", "medium", "low");

    /**
     * Whether this disposition resolves the gap for change-set review (PHASE-8 §3):
     * accepted-as-feature and closed entries count as resolved — the version under
     * review shipped the fix or the platform feature; backlog and wontfix entries
     * stay open workarounds.
     */
    public static boolean resolving(String disposition) {
        return "accept-as-platform-feature".equals(disposition) || "closed".equals(disposition);
    }
}
