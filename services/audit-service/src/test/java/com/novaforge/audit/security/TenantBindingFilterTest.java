package com.novaforge.audit.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.novaforge.common.context.TenantContext;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Unit tests for claim→TenantContext binding (PHASE-0 §6.2/T7). */
/** Ported per-module from the metadata service's pin (twenty-ninth pass
 *  coverage audit): every service carries its own copy of the claim-to-
 *  TenantContext binding, and only metadata's copy had ever been tested.
 *  A drift in ANY copy — a dropped clear(), a trusted header — must fail
 *  THIS service's build, not wait for a cross-service integration run. */
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
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
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
        AtomicReference<TenantContext.Context> during = new AtomicReference<>();
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> during.set(TenantContext.current().orElse(null)));
        assertThat(during.get()).isEqualTo(new TenantContext.Context("tenant-1", "user-1"));
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    @DisplayName("missing tenant claim leaves the context unbound (fail closed)")
    void missingClaimLeavesUnbound() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(token(Map.of(), "user-1"));
        AtomicReference<TenantContext.Context> during = new AtomicReference<>();
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> during.set(TenantContext.current().orElse(null)));
        assertThat(during.get()).isNull();
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    @DisplayName("non-JWT authentication leaves the context unbound")
    void nonJwtAuthenticationUnbound() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("u", "p"));
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> { });
        assertThat(TenantContext.current()).isEmpty();
    }
}
