package com.novaforge.metadata;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The Integrations branch of an app definition (PHASE-6 §2): connectors, webhooks
 * (both directions), credential references, and import mappings — builder-authored
 * metadata on the same draft/publish/promotion path as every other branch
 * (PHASE-7 §1 rule 1: nothing here is API-only).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IntegrationsDefinition(
        List<ConnectorDefinition> connectors,
        List<WebhookDefinition> webhooks,
        List<CredentialDefinition> credentials,
        List<ImportDefinition> imports) {

    public IntegrationsDefinition {
        connectors = connectors == null ? List.of() : List.copyOf(connectors);
        webhooks = webhooks == null ? List.of() : List.copyOf(webhooks);
        credentials = credentials == null ? List.of() : List.copyOf(credentials);
        imports = imports == null ? List.of() : List.copyOf(imports);
    }

    public IntegrationsDefinition() {
        this(List.of(), List.of(), List.of(), List.of());
    }

    public java.util.Optional<ConnectorDefinition> connector(String id) {
        return connectors.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    public java.util.Optional<WebhookDefinition> webhook(String id) {
        return webhooks.stream().filter(w -> w.id().equals(id)).findFirst();
    }

    public java.util.Optional<CredentialDefinition> credential(String id) {
        return credentials.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    public java.util.Optional<ImportDefinition> importMapping(String apiName) {
        return imports.stream().filter(i -> i.apiName().equals(apiName)).findFirst();
    }

    /** The enabled outbound webhooks (the dispatch scan's working set). */
    public List<WebhookDefinition> enabledOutbound() {
        return webhooks.stream()
                .filter(w -> WebhookDefinition.OUTBOUND.equals(w.direction()))
                .filter(w -> !Boolean.FALSE.equals(w.enabled()))
                .toList();
    }
}
