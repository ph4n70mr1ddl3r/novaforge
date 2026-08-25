package com.novaforge.metadata.api;

import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.EntityDefinition;
import com.novaforge.metadata.HookRule;
import java.util.List;

/**
 * The rendering view of a published bundle (ARCHITECTURE.md §2.3): the published read
 * serves any authenticated tenant user, so it carries rendering-relevant definitions
 * only — escape-hatch script artifacts (they execute server-side; the source is not
 * user surface) and credential references (secrets never ride metadata — PHASE-6 §9)
 * are excluded. The trusted service client reads the full bundle: the Data Runtime's
 * write path resolves hooks (scripts included) through it, and the definition-consuming
 * services (workflow, reporting, integration, scheduler) bind connectors and webhooks
 * the same way. Everything else passes through verbatim.
 */
final class RenderingView {

    private RenderingView() {
    }

    static AppDefinition of(AppDefinition bundle) {
        if (bundle.entities().stream().noneMatch(RenderingView::carriesScript)
                && bundle.integrations().credentials().isEmpty()) {
            return bundle;   // nothing to strip — the common case stays allocation-free
        }
        return new AppDefinition(bundle.id(), bundle.apiName(), bundle.label(), bundle.labelI18n(),
                bundle.description(),
                bundle.entities().stream().map(RenderingView::withoutScriptSource).toList(),
                bundle.pages(), bundle.settings(), bundle.permissionSet(), bundle.testSuites(),
                bundle.stateMachines(), bundle.slas(), bundle.jobs(), bundle.workflows(),
                bundle.reports(), bundle.dashboards(),
                new com.novaforge.metadata.IntegrationsDefinition(
                        bundle.integrations().connectors(), bundle.integrations().webhooks(),
                        List.of(), bundle.integrations().imports()),
                bundle.translations(), bundle.gapLog());
    }

    private static boolean carriesScript(EntityDefinition entity) {
        return entity.hooks().stream().anyMatch(hook -> hook.script() != null);
    }

    /** The hook stays (name, trigger, flow) — only the script artifact leaves. */
    private static EntityDefinition withoutScriptSource(EntityDefinition entity) {
        if (!carriesScript(entity)) {
            return entity;
        }
        List<HookRule> hooks = entity.hooks().stream()
                .map(hook -> hook.script() == null ? hook
                        : new HookRule(hook.name(), hook.trigger(), hook.flow(), null))
                .toList();
        return new EntityDefinition(entity.id(), entity.apiName(), entity.label(),
                entity.labelI18n(), entity.displayField(), entity.module(),
                entity.freezeOnTerminal(), entity.periodLock(), entity.fields(),
                entity.relationships(), entity.validations(), hooks, entity.indexes());
    }
}
