package com.novaforge.metadata;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * An application definition — the versioned artifact promoted through environments
 * (PLAN.md P8). Drafts are mutable in the Metadata Service; publish snapshots the full
 * bundle into an immutable version.
 *
 * @param pages   page/layout definitions — schema reserved from v0, authored from
 *                Phase 2 (PHASE-1 §3)
 * @param settings app-scoped settings: sequences, currencies, shared enums — the
 *                Settings branch of PLAN.md §2
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AppDefinition(
        String id,
        String apiName,
        String label,
        @JsonProperty("label_i18n") Map<String, String> labelI18n,
        String description,
        List<EntityDefinition> entities,
        List<PageDefinition> pages,
        SettingsDefinition settings) {

    public AppDefinition {
        entities = entities == null ? List.of() : List.copyOf(entities);
        pages = pages == null ? List.of() : List.copyOf(pages);
        labelI18n = labelI18n == null ? Map.of() : Map.copyOf(labelI18n);
        settings = settings == null ? new SettingsDefinition(null, null, null) : settings;
    }

    public java.util.Optional<EntityDefinition> entity(String apiName) {
        return entities.stream().filter(e -> e.apiName().equals(apiName)).findFirst();
    }

    /** Page definition (reserved slot; authored from Phase 2 per PHASE-2 §3). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PageDefinition(
            String id,
            String apiName,
            String label,
            @JsonProperty("label_i18n") Map<String, String> labelI18n,
            String type,
            String entity,
            Object layout) {
    }

    /** App-scoped settings (Settings branch, PLAN.md §2). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SettingsDefinition(
            List<SequenceDefinition> sequences,
            List<CurrencyDefinition> currencies,
            Map<String, List<String>> enums) {

        public SettingsDefinition {
            sequences = sequences == null ? List.of() : List.copyOf(sequences);
            currencies = currencies == null ? List.of() : List.copyOf(currencies);
            enums = enums == null ? Map.of() : Map.copyOf(enums);
        }

        public java.util.Optional<SequenceDefinition> sequence(String apiName) {
            return sequences.stream().filter(s -> s.apiName().equals(apiName)).findFirst();
        }
    }

    /** Minimal currency definition: ISO code + decimal scale for money formatting. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CurrencyDefinition(String code, Integer scale) {
    }
}
