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
        SettingsDefinition settings,
        PermissionSet permissionSet,
        List<TestSuiteDefinition> testSuites,
        List<StateMachineDefinition> stateMachines) {

    public AppDefinition {
        entities = entities == null ? List.of() : List.copyOf(entities);
        pages = pages == null ? List.of() : List.copyOf(pages);
        labelI18n = labelI18n == null ? Map.of() : Map.copyOf(labelI18n);
        settings = settings == null ? new SettingsDefinition(null, null, null) : settings;
        permissionSet = permissionSet == null ? new PermissionSet(null, null, null) : permissionSet;
        testSuites = testSuites == null ? List.of() : List.copyOf(testSuites);
        stateMachines = stateMachines == null ? List.of() : List.copyOf(stateMachines);
    }

    public java.util.Optional<TestSuiteDefinition> testSuite(String apiName) {
        return testSuites.stream().filter(t -> t.apiName().equals(apiName)).findFirst();
    }

    public java.util.Optional<EntityDefinition> entity(String apiName) {
        return entities.stream().filter(e -> e.apiName().equals(apiName)).findFirst();
    }

    /** The machine bound to an entity, if one exists (one per entity in v1). */
    public java.util.Optional<StateMachineDefinition> stateMachineFor(String entityApiName) {
        return stateMachines.stream()
                .filter(m -> m.entity().equals(entityApiName))
                .findFirst();
    }

    /** Constructor without the Permissions branch (pre-PermissionSet drafts). */
    public AppDefinition(String id, String apiName, String label, Map<String, String> labelI18n,
                         String description, List<EntityDefinition> entities,
                         List<PageDefinition> pages, SettingsDefinition settings) {
        this(id, apiName, label, labelI18n, description, entities, pages, settings,
                new PermissionSet(null, null, null));
    }

    /** Constructor without the Tests branch (pre-harness drafts). */
    public AppDefinition(String id, String apiName, String label, Map<String, String> labelI18n,
                         String description, List<EntityDefinition> entities,
                         List<PageDefinition> pages, SettingsDefinition settings,
                         PermissionSet permissionSet) {
        this(id, apiName, label, labelI18n, description, entities, pages, settings,
                permissionSet, List.of());
    }

    /** Constructor without the state-machines branch (pre-Phase-4 drafts). */
    public AppDefinition(String id, String apiName, String label, Map<String, String> labelI18n,
                         String description, List<EntityDefinition> entities,
                         List<PageDefinition> pages, SettingsDefinition settings,
                         PermissionSet permissionSet, List<TestSuiteDefinition> testSuites) {
        this(id, apiName, label, labelI18n, description, entities, pages, settings,
                permissionSet, testSuites, List.of());
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
