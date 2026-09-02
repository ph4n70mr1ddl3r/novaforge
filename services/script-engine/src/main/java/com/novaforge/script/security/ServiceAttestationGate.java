package com.novaforge.script.security;

import com.novaforge.security.ServiceClientGate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * The Data Runtime's service attestation (PHASE-3 §6's reconciled auth shape): the
 * runtime relays the calling user's token as the request's primary credential —
 * scripts run caller-context (§13 Q1) — and simultaneously attests with its own
 * service-client token in {@code X-NovaForge-Service-Attestation}, so the execute
 * surface stays closed to user tokens that reach pod-network without the runtime
 * (the fifteenth pass's gate) while the caller-context relay keeps working.
 *
 * <p>Fail-closed by construction: a missing, malformed, expired, or wrong-issuer
 * attestation is simply not an attestation — {@code false}, never an exception
 * surface of its own. The token is verified against the issuer's JWKS exactly like
 * the primary credential.</p>
 */
@Component
public class ServiceAttestationGate {

    /** The header the Data Runtime's hook relay signs with its service token. */
    public static final String HEADER = com.novaforge.security.ServiceClientGate.ATTESTATION_HEADER;

    private final JwtDecoder decoder;

    public ServiceAttestationGate(JwtDecoder decoder) {
        this.decoder = decoder;
    }

    /** Whether the bearer in the attestation header is the platform service client. */
    public boolean attested(String bearer) {
        if (bearer == null || !bearer.startsWith("Bearer ") || bearer.isBlank()) {
            return false;
        }
        try {
            Jwt jwt = decoder.decode(bearer.substring("Bearer ".length()));
            return ServiceClientGate.CLIENT_ID.equals(jwt.getClaimAsString("azp"))
                    || ServiceClientGate.CLIENT_ID.equals(jwt.getClaimAsString("client_id"));
        } catch (JwtException | IllegalArgumentException e) {
            return false;   // unverified is unattested — fail closed, no leak of why
        }
    }
}
