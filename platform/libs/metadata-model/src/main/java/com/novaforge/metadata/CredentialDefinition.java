package com.novaforge.metadata;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * A credential definition (PHASE-6 §2/§9) — the reference only. The secret
 * material (API-key value, basic password, OAuth2 client secret, webhook HMAC
 * secret) lives in the Integration Service's secret store, AES-GCM-encrypted at
 * rest, keyed by the credential's id within the tenant; metadata never carries
 * it, and exports/redactions strip references by construction (§9's "credentials
 * never live in metadata" — the schema cannot express the secret).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CredentialDefinition(
        String id,
        String kind,
        String header,
        String username,
        String tokenUrl,
        String clientId,
        List<String> scopes) {

    /** v1 auth set (§3): API-key header, HTTP basic, OAuth2 client-credentials. */
    public static final String KIND_API_KEY = "api_key";
    public static final String KIND_BASIC = "basic";
    public static final String KIND_OAUTH2_CC = "oauth2_client_credentials";

    public static final java.util.Set<String> KINDS =
            java.util.Set.of(KIND_API_KEY, KIND_BASIC, KIND_OAUTH2_CC);

    public CredentialDefinition {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }
}
