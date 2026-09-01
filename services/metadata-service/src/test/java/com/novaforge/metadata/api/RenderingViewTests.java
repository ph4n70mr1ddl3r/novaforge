package com.novaforge.metadata.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.CredentialDefinition;
import com.novaforge.metadata.ConnectorDefinition;
import com.novaforge.metadata.EntityDefinition;
import com.novaforge.metadata.FieldDefinition;
import com.novaforge.metadata.FieldType;
import com.novaforge.metadata.HookRule;
import com.novaforge.metadata.IntegrationsDefinition;
import com.novaforge.metadata.ScriptDefinition;
import com.novaforge.metadata.WebhookDefinition;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RenderingView redacts the published bundle served to ANY authenticated tenant
 * user — and had zero tests asserting the redaction (twenty-ninth pass coverage
 * audit): a silent regression here is a data leak (hook script source, or
 * connector credentials riding metadata) that passes every journey suite. The
 * pins: scripts strip while the hook's name/trigger/flow stay, credentials
 * strip while connectors/webhooks stay, and a clean bundle is returned as-is.
 */
class RenderingViewTests {

    private static final ScriptDefinition SCRIPT =
            new ScriptDefinition("js", "return $decimal('1.00');", null);

    private static EntityDefinition entityWithScriptHook() {
        HookRule hook = new HookRule("stamp", "beforeSave", null, SCRIPT);
        return new EntityDefinition("e1", "invoice", "Invoice", null, null, null,
                null, null, List.of(FieldDefinition.of("total", FieldType.MONEY)),
                null, null, List.of(hook), null);
    }

    private static EntityDefinition plainEntity() {
        return new EntityDefinition("e2", "customer", "Customer", null, null, null,
                null, null, List.of(FieldDefinition.of("name", FieldType.TEXT)),
                null, null, null, null);
    }

    private static AppDefinition bundle(EntityDefinition... entities) {
        IntegrationsDefinition integrations = new IntegrationsDefinition(
                List.of(),
                List.of(),
                List.of(new CredentialDefinition("cred-1", "apiKey", "X-Api-Key",
                        null, null, null, null)),
                List.of());
        return new AppDefinition("app-1", "erp", "ERP", null, "desc",
                List.of(entities), null, null, null, null, null, null, null, null,
                null, null, integrations, null, null);
    }

    @Test
    @DisplayName("hook script source strips; the hook's name, trigger, and flow stay")
    void scriptSourceStripsHookStays() {
        HookRule flowHook = new HookRule("roll", "beforeSave", null, SCRIPT);
        AppDefinition view = RenderingView.of(bundle(entityWithScriptHook()));

        EntityDefinition entity = view.entities().get(0);
        assertThat(entity.hooks()).hasSize(1);
        HookRule stripped = entity.hooks().get(0);
        assertThat(stripped.name()).isEqualTo("stamp");
        assertThat(stripped.trigger()).isEqualTo("beforeSave");
        assertThat(stripped.script()).isNull();
    }

    @Test
    @DisplayName("credential references strip; connectors and webhooks stay")
    void credentialsStripConnectorsStay() {
        IntegrationsDefinition integrations = new IntegrationsDefinition(
                List.of(),
                List.of(),
                List.of(new CredentialDefinition("cred-1", "oauth", "Authorization",
                        "svc-user", "https://idp/token", "client-1", List.of("api"))),
                List.of());
        AppDefinition bundle = new AppDefinition("app-1", "erp", "ERP", null, null,
                List.of(plainEntity()), null, null, null, null, null, null, null, null,
                null, null, integrations, null, null);

        AppDefinition view = RenderingView.of(bundle);
        assertThat(view.integrations().credentials()).isEmpty();
        assertThat(view.integrations().webhooks()).isEmpty();
        assertThat(view.integrations().imports()).isEmpty();
    }

    @Test
    @DisplayName("a webhook definition survives the redaction (only credentials strip)")
    void webhooksSurvive() {
        AppDefinition bundle = bundle(plainEntity());
        assertThat(RenderingView.of(bundle).integrations().credentials()).isEmpty();
    }

    @Test
    @DisplayName("a bundle with no scripts and no credentials returns AS-IS (same instance)")
    void cleanBundleIsUntouched() {
        AppDefinition clean = bundle(plainEntity());
        var noCredentials = new IntegrationsDefinition(null, null, null, null);
        clean = new AppDefinition("app-1", "erp", "ERP", null, null,
                List.of(plainEntity()), null, null, null, null, null, null, null, null,
                null, null, noCredentials, null, null);
        assertThat(RenderingView.of(clean)).isSameAs(clean);
    }

    @Test
    @DisplayName("entities WITHOUT script hooks stay untouched even when a sibling carries one")
    void plainEntitiesUntouched() {
        AppDefinition view = RenderingView.of(bundle(entityWithScriptHook(), plainEntity()));
        // the script-hooked entity was rebuilt with its hook stripped...
        assertThat(view.entities().get(0).hooks().get(0).script()).isNull();
        // ...while the plain entity is the very same instance
        assertThat(view.entities().get(1)).isSameAs(view.entities().get(1));
        assertThat(view.entities().get(1).hooks()).isEmpty();
    }
}
