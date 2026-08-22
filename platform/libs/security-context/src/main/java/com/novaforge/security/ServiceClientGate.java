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

    private ServiceClientGate() {
    }

    /**
     * Returns normally when the current authentication is the platform service
     * client (matched on {@code azp} or {@code client_id}); otherwise throws
     * 403 {@code FORBIDDEN} naming the surface.
     */
    public static void require(String surface) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuth
                && jwtAuth.getToken() instanceof Jwt jwt) {
            String azp = jwt.getClaimAsString("azp");
            String clientId = jwt.getClaimAsString("client_id");
            if (CLIENT_ID.equals(azp) || CLIENT_ID.equals(clientId)) {
                return;
            }
        }
        throw new PlatformException(PlatformErrorCode.FORBIDDEN,
                "the " + surface + " surface is service-client only");
    }
}
