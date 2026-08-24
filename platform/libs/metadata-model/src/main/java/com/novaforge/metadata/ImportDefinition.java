package com.novaforge.metadata;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * An import mapping (PHASE-6 §2/§7): versioned metadata promoted with the app
 * like connectors — {@code {entity, mapping, mode, keyFields}}. Import
 * <em>runs</em> ({@code ImportJob}s) are tenant data living in the Integration
 * Service, checkpointed for resume; this definition is the promoted shape they
 * execute. The mapping's keys are target entity fields; values name source
 * columns (CSV headers) or {@code ${…}} templates over the row.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImportDefinition(
        String apiName,
        String entity,
        Map<String, Object> mapping,
        String mode,
        List<String> keyFields) {

    public static final String MODE_CREATE = "create";
    public static final String MODE_UPSERT = "upsert";

    public static final java.util.Set<String> MODES =
            java.util.Set.of(MODE_CREATE, MODE_UPSERT);

    public ImportDefinition {
        keyFields = keyFields == null ? List.of() : List.copyOf(keyFields);
        mapping = mapping == null ? Map.of() : Map.copyOf(mapping);
    }
}
