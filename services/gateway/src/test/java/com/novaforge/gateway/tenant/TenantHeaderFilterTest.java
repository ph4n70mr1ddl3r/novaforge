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
}
