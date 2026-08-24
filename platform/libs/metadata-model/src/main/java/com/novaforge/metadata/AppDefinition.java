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
        List<StateMachineDefinition> stateMachines,
        List<SlaDefinition> slas,
        List<ScheduledJobDefinition> jobs,
        List<WorkflowDefinition> workflows,
        List<ReportDefinition> reports,
        List<DashboardDefinition> dashboards,
        IntegrationsDefinition integrations,
        List<TranslationsDefinition> translations) {

    public AppDefinition {
        entities = entities == null ? List.of() : List.copyOf(entities);
        pages = pages == null ? List.of() : List.copyOf(pages);
        labelI18n = labelI18n == null ? Map.of() : Map.copyOf(labelI18n);
        settings = settings == null ? new SettingsDefinition(null, null, null) : settings;
        permissionSet = permissionSet == null ? new PermissionSet(null, null, null) : permissionSet;
        testSuites = testSuites == null ? List.of() : List.copyOf(testSuites);
        stateMachines = stateMachines == null ? List.of() : List.copyOf(stateMachines);
        slas = slas == null ? List.of() : List.copyOf(slas);
        jobs = jobs == null ? List.of() : List.copyOf(jobs);
        workflows = workflows == null ? List.of() : List.copyOf(workflows);
        reports = reports == null ? List.of() : List.copyOf(reports);
        dashboards = dashboards == null ? List.of() : List.copyOf(dashboards);
        integrations = integrations == null ? new IntegrationsDefinition() : integrations;
        translations = translations == null ? List.of() : List.copyOf(translations);
    }

    /** The Integrations branch (PHASE-6 §2) — connectors, webhooks, credentials, imports. */
    @Override
    public IntegrationsDefinition integrations() {
        return integrations;
    }

    /** The Translations branch (PHASE-8 §7) — one workspace per locale. */
    public List<TranslationsDefinition> translations() {
        return translations;
    }

    public java.util.Optional<TranslationsDefinition> translations(String locale) {
        return translations.stream().filter(t -> t.locale().equals(locale)).findFirst();
    }

    public java.util.Optional<TestSuiteDefinition> testSuite(String apiName) {
        return testSuites.stream().filter(t -> t.apiName().equals(apiName)).findFirst();
    }

    public java.util.Optional<EntityDefinition> entity(String apiName) {
        return entities.stream().filter(e -> e.apiName().equals(apiName)).findFirst();
    }

    /** The scheduled jobs of the app (PHASE-4 §7), activated on publish. */
    public List<ScheduledJobDefinition> jobs() {
        return jobs;
    }

    /** The machine bound to an entity, if one exists (one per entity in v1). */
    public java.util.Optional<StateMachineDefinition> stateMachineFor(String entityApiName) {
        return stateMachines.stream()
                .filter(m -> m.entity().equals(entityApiName))
                .findFirst();
    }

    /** A workflow by process key, if one exists (PHASE-4 §9). */
    public java.util.Optional<WorkflowDefinition> workflow(String id) {
        return workflows.stream().filter(w -> w.id().equals(id)).findFirst();
    }

    /** A report by id, if one exists (PHASE-5 §3). */
    public java.util.Optional<ReportDefinition> report(String id) {
        return reports.stream().filter(r -> r.id().equals(id)).findFirst();
    }

    /** A connector by id, if one exists (PHASE-6 §3). */
    public java.util.Optional<ConnectorDefinition> connector(String id) {
        return integrations.connector(id);
    }

    /** An inbound webhook bound to an entity, if one exists (PHASE-6 §6). */
    public java.util.Optional<WebhookDefinition> inboundWebhook(String entity, String hookId) {
        return integrations.webhooks().stream()
                .filter(w -> WebhookDefinition.INBOUND.equals(w.direction()))
                .filter(w -> hookId == null ? entity.equals(w.entity())
                        : hookId.equals(w.id()) && entity.equals(w.entity()))
                .findFirst();
    }

    /** A dashboard by id, if one exists (PHASE-5 §5). */
    public java.util.Optional<DashboardDefinition> dashboard(String id) {
        return dashboards.stream().filter(d -> d.id().equals(id)).findFirst();
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

    /** Constructor without the state-machines/sla branches (pre-Phase-4 drafts). */
    public AppDefinition(String id, String apiName, String label, Map<String, String> labelI18n,
                         String description, List<EntityDefinition> entities,
                         List<PageDefinition> pages, SettingsDefinition settings,
                         PermissionSet permissionSet, List<TestSuiteDefinition> testSuites) {
        this(id, apiName, label, labelI18n, description, entities, pages, settings,
                permissionSet, testSuites, List.of(), List.of());
    }

    /** Constructor without the SLA/jobs branches (pre-Phase-4 drafts). */
    public AppDefinition(String id, String apiName, String label, Map<String, String> labelI18n,
                         String description, List<EntityDefinition> entities,
                         List<PageDefinition> pages, SettingsDefinition settings,
                         PermissionSet permissionSet, List<TestSuiteDefinition> testSuites,
                         List<StateMachineDefinition> stateMachines) {
        this(id, apiName, label, labelI18n, description, entities, pages, settings,
                permissionSet, testSuites, stateMachines, List.of(), List.of());
    }

    /** Constructor without the jobs branch (pre-T7 drafts). */
    public AppDefinition(String id, String apiName, String label, Map<String, String> labelI18n,
                         String description, List<EntityDefinition> entities,
                         List<PageDefinition> pages, SettingsDefinition settings,
                         PermissionSet permissionSet, List<TestSuiteDefinition> testSuites,
                         List<StateMachineDefinition> stateMachines,
                         List<SlaDefinition> slas) {
        this(id, apiName, label, labelI18n, description, entities, pages, settings,
                permissionSet, testSuites, stateMachines, slas, List.of(), List.of());
    }

    /** Constructor without the workflows branch (pre-§9 drafts). */
    public AppDefinition(String id, String apiName, String label, Map<String, String> labelI18n,
                         String description, List<EntityDefinition> entities,
                         List<PageDefinition> pages, SettingsDefinition settings,
                         PermissionSet permissionSet, List<TestSuiteDefinition> testSuites,
                         List<StateMachineDefinition> stateMachines,
                         List<SlaDefinition> slas, List<ScheduledJobDefinition> jobs) {
        this(id, apiName, label, labelI18n, description, entities, pages, settings,
                permissionSet, testSuites, stateMachines, slas, jobs, List.of(),
                List.of(), List.of());
    }

    /** Constructor without the reports/dashboards branches (pre-Phase-5 drafts). */
    public AppDefinition(String id, String apiName, String label, Map<String, String> labelI18n,
                         String description, List<EntityDefinition> entities,
                         List<PageDefinition> pages, SettingsDefinition settings,
                         PermissionSet permissionSet, List<TestSuiteDefinition> testSuites,
                         List<StateMachineDefinition> stateMachines,
                         List<SlaDefinition> slas, List<ScheduledJobDefinition> jobs,
                         List<WorkflowDefinition> workflows) {
        this(id, apiName, label, labelI18n, description, entities, pages, settings,
                permissionSet, testSuites, stateMachines, slas, jobs, workflows,
                List.of(), List.of());
    }

    /** Constructor without the Integrations branch (pre-Phase-6 drafts). */
    public AppDefinition(String id, String apiName, String label, Map<String, String> labelI18n,
                         String description, List<EntityDefinition> entities,
                         List<PageDefinition> pages, SettingsDefinition settings,
                         PermissionSet permissionSet, List<TestSuiteDefinition> testSuites,
                         List<StateMachineDefinition> stateMachines,
                         List<SlaDefinition> slas, List<ScheduledJobDefinition> jobs,
                         List<WorkflowDefinition> workflows, List<ReportDefinition> reports,
                         List<DashboardDefinition> dashboards) {
        this(id, apiName, label, labelI18n, description, entities, pages, settings,
                permissionSet, testSuites, stateMachines, slas, jobs, workflows,
                reports, dashboards, new IntegrationsDefinition(), List.of());
    }

    /** Constructor without the Translations branch (pre-Phase-8 drafts). */
    public AppDefinition(String id, String apiName, String label, Map<String, String> labelI18n,
                         String description, List<EntityDefinition> entities,
                         List<PageDefinition> pages, SettingsDefinition settings,
                         PermissionSet permissionSet, List<TestSuiteDefinition> testSuites,
                         List<StateMachineDefinition> stateMachines,
                         List<SlaDefinition> slas, List<ScheduledJobDefinition> jobs,
                         List<WorkflowDefinition> workflows, List<ReportDefinition> reports,
                         List<DashboardDefinition> dashboards, IntegrationsDefinition integrations) {
        this(id, apiName, label, labelI18n, description, entities, pages, settings,
                permissionSet, testSuites, stateMachines, slas, jobs, workflows,
                reports, dashboards, integrations, List.of());
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
