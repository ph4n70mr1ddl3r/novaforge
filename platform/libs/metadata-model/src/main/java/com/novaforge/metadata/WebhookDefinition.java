package com.novaforge.metadata;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * A webhook definition (PHASE-6 §5/§6) — one schema, both directions, split by
 * the {@code direction} discriminator: {@code outbound} carries {@code url} +
 * {@code events} (a filter expression over spine events); {@code inbound}
 * carries {@code entity} + {@code mapping} in their place. Both carry
 * {@code secretRef} (the HMAC secret's reference in the secret store) and
 * {@code enabled}. The same HMAC-SHA256 scheme protects both directions (§5).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebhookDefinition(
        String id,
        String direction,
        String url,
        String events,
        String entity,
        Mapping mapping,
        String secretRef,
        Boolean enabled) {

    public static final String OUTBOUND = "outbound";
    public static final String INBOUND = "inbound";

    public static final java.util.Set<String> DIRECTIONS = java.util.Set.of(OUTBOUND, INBOUND);

    /** Bindings the outbound {@code events} filter compiles against (the spine envelope). */
    public static final java.util.Set<String> EVENT_BINDINGS = java.util.Set.of(
            "event", "entityId", "recordId", "actorId");

    /**
     * The inbound mapping (§6): produces create/upsert payloads for the Data Runtime
     * write path. {@code fields} templates interpolate from the provider payload
     * ({@code ${amount}}, {@code ${data.object.id}}); {@code idempotencyKey} is a
     * template over the provider event id (the body hash when absent); {@code mode}
     * and {@code keyFields} mirror {@link ImportDefinition}'s upsert contract.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Mapping(
            String mode,
            List<String> keyFields,
            String idempotencyKey,
            Map<String, Object> fields) {

        public static final String MODE_CREATE = "create";
        public static final String MODE_UPSERT = "upsert";

        public Mapping {
            keyFields = keyFields == null ? List.of() : List.copyOf(keyFields);
            fields = fields == null ? Map.of() : Map.copyOf(fields);
        }
    }
}
