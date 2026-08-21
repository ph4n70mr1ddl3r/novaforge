package com.novaforge.metadata;

/** Relationship kinds: master-detail children and many-to-many (ARCHITECTURE.md §3). */
public enum RelationshipType {
    @com.fasterxml.jackson.annotation.JsonProperty("child")
    CHILD("child"),
    @com.fasterxml.jackson.annotation.JsonProperty("m2m")
    M2M("m2m");

    private final String wireName;

    RelationshipType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
