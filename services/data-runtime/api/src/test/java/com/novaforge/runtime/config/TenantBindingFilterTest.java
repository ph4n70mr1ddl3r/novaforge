package com.novaforge.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.novaforge.common.context.TenantContext;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Unit tests for claim→TenantContext binding (PHASE-0 §6.2/T7). */
class TenantBindingFilterTest {

    private final TenantBindingFilter filter = new TenantBindingFilter();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private JwtAuthenticationToken token(Map<String, Object> claims, String subject) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuedAt(java.time.Instant.now())
                .expiresAt(java.time.Instant.now().plusSeconds(60))
                .subject(subject)
                .claims(map -> map.putAll(claims))
                .build();
        return new JwtAuthenticationToken(jwt);
    }

    @Test
    @DisplayName("binds tenant/actor from verified claims and clears after the request")
    void bindsAndClears() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(token(Map.of("tenant_id", "tenant-1"), "user-1"));
        AtomicReference<Boolean> contextWasSet = new AtomicReference<>(false);
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> contextWasSet.set(TenantContext.current().isPresent()));
        assertThat(contextWasSet.get()).isTrue();
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    @DisplayName("missing tenant claim leaves the context unbound and does not clear unset context")
    void missingClaimDoesNotSetContext() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(token(Map.of(), "user-1"));
        AtomicReference<Boolean> contextWasSet = new AtomicReference<>(false);
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> contextWasSet.set(TenantContext.current().isPresent()));
        assertThat(contextWasSet.get()).isFalse();
        // Context should still be empty (nothing was set to clear)
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    @DisplayName("non-JWT authentication leaves the context unbound")
    void nonJwtAuthenticationUnbound() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(new org.springframework.security.authentication.TestingAuthenticationToken("u", "p"));
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> { });
        assertThat(TenantContext.current()).isEmpty();
    }
}
