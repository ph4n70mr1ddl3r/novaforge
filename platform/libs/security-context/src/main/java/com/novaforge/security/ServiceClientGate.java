package com.novaforge.security;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The trusted-service gate shared by every internal cross-service surface
 * (the admin API, the hook resume/scheduled/record reads, the workflow
 * suspension and process-start surfaces): only the platform's confidential
 * service client may call — never user traffic through the gateway. One rule,
 * one client id, one implementation; the caller names its surface so a
 * rejection stays specific.
 */
public final class ServiceClientGate {

    /** The Keycloak confidential service client (realm export under deploy/). */
    public static final String CLIENT_ID = "novaforge-runtime";

    /**
     * The header a relaying service signs with its service-client token beside a
     * relayed user credential (PHASE-3 §6's reconciled execute-surface shape): the
     * user token stays the primary (caller-context binds from it) while the
     * attestation proves the platform's own relay put the call on the wire.
     */
    public static final String ATTESTATION_HEADER = "X-NovaForge-Service-Attestation";

    /** The committed development secret for that client — local bring-up only. It
     *  is never accepted where novaforge.auth.service-client.allow-default-secret
     *  is false (the auto-configuration fails boot on it there), because a bearer
     *  of the service-client token passes every internal gate (the 2025-08-27
     *  review closed the committed-default hole for staged environments). */
    public static final String DEFAULT_DEV_SECRET = "novaforge-runtime-secret";

    private ServiceClientGate() {
    }

    /**
     * Returns normally when the current authentication is the platform service
     * client (matched on {@code azp} or {@code client_id}); otherwise throws
     * 403 {@code FORBIDDEN} naming the surface.
     */
    public static void require(String surface) {
        if (!isServiceClient()) {
            throw new PlatformException(PlatformErrorCode.FORBIDDEN,
                    "the " + surface + " surface is service-client only");
        }
    }

    /**
     * Whether the current authentication is the platform service client (matched on
     * {@code azp} or {@code client_id}) — the non-throwing twin {@link #require}
     * leaves to callers that branch on the caller's identity instead of gating
     * (the published read's rendering view, ARCHITECTURE.md §2.3).
     */
    public static boolean isServiceClient() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuth
                && jwtAuth.getToken() instanceof Jwt jwt) {
            String azp = jwt.getClaimAsString("azp");
            String clientId = jwt.getClaimAsString("client_id");
            return CLIENT_ID.equals(azp) || CLIENT_ID.equals(clientId);
        }
        return false;
    }
}
