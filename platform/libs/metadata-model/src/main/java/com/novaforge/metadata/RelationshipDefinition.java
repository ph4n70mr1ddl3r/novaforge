package com.novaforge.metadata;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A collection relationship declared on the owning entity (ARCHITECTURE.md §3).
 * {@code child} = master-detail: children apply atomically in the parent's transaction
 * and deletes cascade per {@code cascadeDelete} (PHASE-1 §5). The target entity must
 * declare a lookup field pointing back at the owner (the binding column) — enforced by
 * {@code DefinitionValidator}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RelationshipDefinition(
        String apiName,
        RelationshipType type,
        String target,
        Boolean cascadeDelete) {

    @JsonIgnore
    public boolean cascadesDelete() {
        return Boolean.TRUE.equals(cascadeDelete);
    }
}
