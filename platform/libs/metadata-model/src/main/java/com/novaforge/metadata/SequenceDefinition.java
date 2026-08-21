package com.novaforge.metadata;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A sequence definition — Settings metadata owned by the Metadata Service; execution
 * lives in the Data Runtime (PLAN.md §3). Consumed exclusively through a field
 * {@code default} sequence reference (PHASE-1 §5 binding).
 *
 * @param start   initial counter value (default 1)
 * @param padding zero-pad width of the numeric part (0 = none)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SequenceDefinition(
        String apiName,
        SequenceMode mode,
        Long start,
        String prefix,
        String suffix,
        Integer padding) {

    public SequenceDefinition {
        if (apiName == null || apiName.isBlank()) {
            throw new IllegalArgumentException("sequence apiName must not be blank");
        }
    }

    @JsonIgnore
    public SequenceMode modeOrDefault() {
        return mode == null ? SequenceMode.CACHED : mode;
    }

    @JsonIgnore
    public long startOrOne() {
        return start == null ? 1L : start;
    }

    @JsonIgnore
    public int paddingOrZero() {
        return padding == null ? 0 : padding;
    }

    /** Formats a drawn counter value per prefix/padding/suffix. */
    public String format(long value) {
        String number = paddingOrZero() > 0
                ? String.format("%0" + paddingOrZero() + "d", value)
                : Long.toString(value);
        return (prefix == null ? "" : prefix) + number + (suffix == null ? "" : suffix);
    }
}
