package com.novaforge.gateway.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.novaforge.gateway.tenant.TenantHeaderFilter.TenantRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Unit tests for the derived-tenant request wrapper (PHASE-0 §6.1/T6). */
class TenantHeaderFilterTest {

    private HttpServletRequest requestWithClientSpoofedHeader() {
        MockHttpServletRequest delegate = new MockHttpServletRequest("GET", "/api/v1/metadata/ping");
        delegate.addHeader(TenantHeaderFilter.TENANT_HEADER, "spoofed-tenant");
        return delegate;
    }

    @Test
    @DisplayName("derived tenant overlays any client-supplied header")
    void overlaysSpoofedHeader() {
        TenantRequest request = new TenantRequest(requestWithClientSpoofedHeader(), "derived-tenant");
        assertThat(request.getHeader(TenantHeaderFilter.TENANT_HEADER)).isEqualTo("derived-tenant");
        assertThat(Collections.list(request.getHeaders(TenantHeaderFilter.TENANT_HEADER)))
                .containsExactly("derived-tenant");
    }

    @Test
    @DisplayName("other headers pass through untouched")
    void otherHeadersPassThrough() {
        MockHttpServletRequest delegate = new MockHttpServletRequest("GET", "/x");
        delegate.addHeader("X-Custom", "value");
        TenantRequest request = new TenantRequest(delegate, "derived-tenant");
        assertThat(request.getHeader("X-Custom")).isEqualTo("value");
        assertThat(Collections.list(request.getHeaderNames()))
                .containsAll(List.of("X-Custom", TenantHeaderFilter.TENANT_HEADER));
    }

    @Test
    @DisplayName("anonymous traffic: client-supplied identity headers never ride upstream")
    void stripsIdentityHeadersWhenNoTenantDerived() {
        // Anti-regression (2026-08-31): with no token-derived tenant (the anonymous
        // webhook route, claim-less tokens), a client-sent X-Tenant-Id/X-Actor-Id
        // passed through verbatim — no service reads them today, but the edge
        // contract "the header downstream equals the claim" must hold unconditionally.
        MockHttpServletRequest delegate = new MockHttpServletRequest("POST",
                "/api/v1/webhooks/inbound/t/E/h");
        delegate.addHeader(TenantHeaderFilter.TENANT_HEADER, "spoofed-tenant");
        delegate.addHeader("X-Actor-Id", "spoofed-actor");
        delegate.addHeader("X-Event-Id", "spoofed-event");
        delegate.addHeader("X-Custom", "kept");
        TenantHeaderFilter.StrippedRequest request =
                new TenantHeaderFilter.StrippedRequest(delegate);
        assertThat(request.getHeader(TenantHeaderFilter.TENANT_HEADER)).isNull();
        assertThat(request.getHeader("X-Actor-Id")).isNull();
        assertThat(request.getHeader("X-Event-Id")).isNull();
        assertThat(Collections.list(request.getHeaders(TenantHeaderFilter.TENANT_HEADER)))
                .isEmpty();
        assertThat(Collections.list(request.getHeaderNames()))
                .containsExactly("X-Custom");
    }

    @Test
    @DisplayName("authenticated traffic: the claim headers are owned by the filter, not the client")
    void claimHeadersAreOwnedEvenWhenAuthenticated() {
        MockHttpServletRequest delegate = new MockHttpServletRequest("GET", "/x");
        delegate.addHeader("X-Actor-Id", "spoofed-actor");
        delegate.addHeader("X-Event-Type", "spoofed-type");
        TenantRequest request = new TenantRequest(delegate, "derived-tenant");
        assertThat(request.getHeader("X-Actor-Id")).isNull();
        assertThat(request.getHeader("X-Event-Type")).isNull();
        assertThat(request.getHeader(TenantHeaderFilter.TENANT_HEADER)).isEqualTo("derived-tenant");
    }
}
