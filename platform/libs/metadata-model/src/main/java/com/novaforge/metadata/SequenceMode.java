package com.novaforge.metadata;

/** Sequence execution modes (PHASE-1 §5). */
public enum SequenceMode {
    /** Redis block allocation, gaps allowed (default). */
    @com.fasterxml.jackson.annotation.JsonProperty("cached")
    CACHED("cached"),
    /** Allocated inside the record transaction via a locked counter row; gapless. */
    @com.fasterxml.jackson.annotation.JsonProperty("gapless")
    GAPLESS("gapless");

    private final String wireName;

    SequenceMode(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
