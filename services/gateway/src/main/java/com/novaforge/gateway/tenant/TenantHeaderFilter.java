package com.novaforge.gateway.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Derives {@code X-Tenant-Id} from the verified token's {@code tenant_id} claim and
 * passes it downstream (PHASE-0 §6.1, ARCHITECTURE.md §2.1). Informational only —
 * downstream services derive the tenant from the claim themselves and never trust this
 * header (PHASE-0 §6.2/T7). Runs after the security filter chain.
 */
@Component
@Order(0)
public class TenantHeaderFilter extends OncePerRequestFilter {

    /** Header carried downstream; informational (services use the claim, not this). */
    public static final String TENANT_HEADER = "X-Tenant-Id";

    /** Single-realm tenant claim (PHASE-0 §12 Q1: single realm + tenant claim). */
    public static final String TENANT_CLAIM = "tenant_id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuth
                && jwtAuth.getToken() instanceof Jwt jwt) {
            String tenantId = jwt.getClaimAsString(TENANT_CLAIM);
            if (tenantId != null && !tenantId.isBlank()) {
                filterChain.doFilter(new TenantRequest(request, tenantId), response);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    /** Wraps the request with the derived tenant header overlaid on any client-supplied one. */
    static final class TenantRequest extends HttpServletRequestWrapper {

        private final String tenantId;

        TenantRequest(HttpServletRequest delegate, String tenantId) {
            super(delegate);
            this.tenantId = tenantId;
        }

        @Override
        public String getHeader(String name) {
            if (TENANT_HEADER.equalsIgnoreCase(name)) {
                return tenantId;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (TENANT_HEADER.equalsIgnoreCase(name)) {
                return Collections.enumeration(List.of(tenantId));
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>(Collections.list(super.getHeaderNames()));
            names.add(TENANT_HEADER);
            return Collections.enumeration(names);
        }
    }
}
