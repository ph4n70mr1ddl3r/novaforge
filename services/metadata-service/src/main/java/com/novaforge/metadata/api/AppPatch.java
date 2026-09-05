package com.novaforge.metadata.api;

import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.BrandingDefinition;
import com.novaforge.metadata.GapLogEntry;
import com.novaforge.metadata.IntegrationsDefinition;
import com.novaforge.metadata.PermissionSet;
import com.novaforge.metadata.ReportDefinition;
import com.novaforge.metadata.ScheduledJobDefinition;
import com.novaforge.metadata.SlaDefinition;
import com.novaforge.metadata.StateMachineDefinition;
import com.novaforge.metadata.TestSuiteDefinition;
import com.novaforge.metadata.TranslationsDefinition;
import com.novaforge.metadata.WorkflowDefinition;
import java.util.List;
import java.util.Map;

/**
 * The presence-preserving PATCH shape (2026-08-31, the tenth pass's recorded-open
 * close): {@link AppDefinition}'s canonical constructor normalizes absent branches to
 * empty lists, so the merge could never tell "the client omitted the branch" (keep
 * current) from "the client sent an empty branch" (clear it) — the last item of every
 * list branch was silently unremovable. This record keeps Jackson's binding verbatim:
 * {@code null} keeps the current branch, an explicit empty list clears it, a
 * non-empty list replaces it. {@code AppDefinition} itself stays normalized — the
 * distinction lives only at this boundary.
 *
 * <p>Sub-branch presence (a single list inside {@code integrations} or a single leg
 * of {@code permissionSet}) is not distinguished: those records normalize internally,
 * so a present branch replaces whole — a client clearing the last webhook sends the
 * full integrations branch with the rest round-tripped, the same replace semantics a
 * non-empty patch always had. An all-empty permissionSet that clears every role will
 * fail save validation loudly rather than wiping silently.</p>
 */
public record AppPatch(
        String label,
        Map<String, String> labelI18n,
        String description,
        AppDefinition.SettingsDefinition settings,
        PermissionSet permissionSet,
        List<TestSuiteDefinition> testSuites,
        List<StateMachineDefinition> stateMachines,
        List<SlaDefinition> slas,
        List<ScheduledJobDefinition> jobs,
        List<WorkflowDefinition> workflows,
        List<ReportDefinition> reports,
        List<com.novaforge.metadata.DashboardDefinition> dashboards,
        IntegrationsDefinition integrations,
        List<TranslationsDefinition> translations,
        List<GapLogEntry> gapLog,
        BrandingDefinition branding) {

    /** The patch applied over a current draft: null keeps, empty clears, else replaces. */
    AppDefinition mergeOver(AppDefinition current) {
        return new AppDefinition(
                current.id(),
                current.apiName(),
                label != null ? label : current.label(),
                labelI18n != null ? labelI18n : current.labelI18n(),
                description != null ? description : current.description(),
                current.entities(),
                current.pages(),
                settings != null ? settings : current.settings(),
                permissionSet != null ? permissionSet : current.permissionSet(),
                testSuites != null ? testSuites : current.testSuites(),
                stateMachines != null ? stateMachines : current.stateMachines(),
                slas != null ? slas : current.slas(),
                jobs != null ? jobs : current.jobs(),
                workflows != null ? workflows : current.workflows(),
                reports != null ? reports : current.reports(),
                dashboards != null ? dashboards : current.dashboards(),
                integrations != null ? integrations : current.integrations(),
                translations != null ? translations : current.translations(),
                gapLog != null ? gapLog : current.gapLog(),
                branding != null ? branding : current.branding());
    }
}
