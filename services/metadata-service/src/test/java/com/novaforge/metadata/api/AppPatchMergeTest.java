package com.novaforge.metadata.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.BrandingDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The branding leg of the presence-preserving PATCH (ADR-009 §5): null keeps the
 * current branch, a present branding replaces whole — an empty BrandingDefinition
 * (both fields null) is the "drop back to the platform palette" write, the same
 * replace semantics every other whole-branch patch has.
 */
class AppPatchMergeTest {

    /** Positional components: label, labelI18n, description, settings, permissionSet,
     *  testSuites, stateMachines, slas, jobs, workflows, reports, dashboards,
     *  integrations, translations, gapLog, branding. */
    private static AppPatch patch(BrandingDefinition branding) {
        return new AppPatch(null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null, List.of(), List.of(), branding);
    }

    private static AppDefinition appWith(BrandingDefinition branding) {
        return new AppDefinition("app-1", "Erp", "ERP", Map.of(), null,
                List.of(), List.of(), null, null, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), null, List.of(),
                List.of(), branding);
    }

    @Test
    @DisplayName("a null branding patch keeps the current branding")
    void nullKeepsCurrent() {
        AppDefinition current = appWith(new BrandingDefinition("#7c3aed", null));
        AppDefinition merged = patch(null).mergeOver(current);
        assertThat(merged.branding()).isEqualTo(current.branding());
    }

    @Test
    @DisplayName("a present branding replaces whole — an empty one clears back to the platform palette")
    void presentReplacesWhole() {
        AppDefinition current = appWith(new BrandingDefinition("#7c3aed", "#ffffff"));

        AppDefinition rebranded = patch(new BrandingDefinition("#0ea5e9", "#0b1a38"))
                .mergeOver(current);
        assertThat(rebranded.branding())
                .isEqualTo(new BrandingDefinition("#0ea5e9", "#0b1a38"));

        AppDefinition cleared = patch(new BrandingDefinition(null, null)).mergeOver(current);
        assertThat(cleared.branding()).isEqualTo(new BrandingDefinition(null, null));
    }

    @Test
    @DisplayName("an unbranded app and a null patch stay unbranded")
    void unbrandedStaysUnbranded() {
        AppDefinition merged = patch(null).mergeOver(appWith(null));
        assertThat(merged.branding()).isNull();
    }
}
