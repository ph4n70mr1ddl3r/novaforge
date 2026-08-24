package com.novaforge.metadata;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A REST connector definition (PHASE-6 §3): the v1 connector frame — operations
 * named and addressed on a base URL, request maps templated with the shared
 * {@code ${…}} convention (ADR-008). Definitions are versioned app metadata,
 * promoted like every branch; the secret material never rides here —
 * {@link #credential()} references a {@link CredentialDefinition}, which itself
 * carries the reference only (the secret lives in the Integration Service's
 * encrypted store, §9).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConnectorDefinition(
        String id,
        String type,
        String baseUrl,
        String credential,
        List<Operation> operations) {

    public ConnectorDefinition {
        operations = operations == null ? List.of() : List.copyOf(operations);
    }

    /** v1 ships the REST type; SOAP/DB/file join the same frame on dogfood demand (§1). */
    public static final String TYPE_REST = "rest";

    public static final Set<String> TYPES = Set.of(TYPE_REST);

    public static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    /**
     * One named operation: method + path (+ query/header/body templates). Template
     * values interpolate from the call context at execute time — {@code ${limit}} in
     * a query map, {@code ${id}} in a path.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Operation(
            String name,
            String method,
            String path,
            Map<String, Object> query,
            Map<String, Object> headers,
            Object body) {

        public Operation {
            query = query == null ? Map.of() : Map.copyOf(query);
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    /** The operation of this name, if any (the publish check's lookup). */
    public java.util.Optional<Operation> operation(String name) {
        return operations.stream().filter(o -> o.name().equals(name)).findFirst();
    }
}
