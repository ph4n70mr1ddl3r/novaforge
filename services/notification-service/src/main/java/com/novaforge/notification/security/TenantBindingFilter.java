package com.novaforge.notification.security;

import com.novaforge.common.context.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds {@link TenantContext} from the verified token's claims (PHASE-0 §6.2/T7): tenant
 * from the {@code tenant_id} claim, actor from {@code sub}. Never trusts the gateway's
 * {@code X-Tenant-Id} header — services derive identity from the claim themselves.
 * Requests without a tenant claim leave the context unbound; downstream {@code require()}
 * calls then fail closed.
 */
@Component
@Order(0)
public class TenantBindingFilter extends OncePerRequestFilter {

    public static final String TENANT_CLAIM = "tenant_id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication instanceof JwtAuthenticationToken jwtAuth
                    && jwtAuth.getToken() instanceof Jwt jwt) {
                String tenantId = jwt.getClaimAsString(TENANT_CLAIM);
                // Keycloak 26 lightweight access tokens may omit `sub`; the novaforge.api
                // scope maps the user id explicitly as actor_id.
                String actorId = jwt.getClaimAsString("actor_id");
                if (actorId == null || actorId.isBlank()) {
                    actorId = jwt.getSubject();
                }
                if (tenantId != null && !tenantId.isBlank() && actorId != null && !actorId.isBlank()) {
                    TenantContext.set(new TenantContext.Context(tenantId, actorId));
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
