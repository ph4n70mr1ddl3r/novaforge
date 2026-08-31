package com.novaforge.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The edge body cap (2026-08-31, sixteenth pass): the platform had no request-size
 * limit anywhere, and the one anonymous route buffers the whole body into a byte[]
 * before HMAC verification — an unauthenticated multi-GB chunked POST was an OOM
 * vector the request-count limiter never sees.
 */
class RequestSizeCapFilterTest {

    @Test
    @DisplayName("a declared Content-Length over the cap rejects 413 before any byte is read")
    void declaredLengthRejects() throws Exception {
        RequestSizeCapFilter filter = new RequestSizeCapFilter(1024);
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                WebhookRateLimitFilter.PUBLIC_PREFIX + "/t/E/h") {
            @Override
            public long getContentLengthLong() {
                return 104_857_600L;   // the declared length is what the edge trusts
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        final boolean[] chained = {false};
        filter.doFilter(request, response, (req, res) -> chained[0] = true);
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("Payload Too Large");
        assertThat(chained[0]).isFalse();
    }

    @Test
    @DisplayName("a chunked stream truncates at the cap — the service reads EOF, not the bomb")
    void chunkedStreamTruncates() throws Exception {
        RequestSizeCapFilter filter = new RequestSizeCapFilter(16);
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                WebhookRateLimitFilter.PUBLIC_PREFIX + "/t/E/h");
        // no Content-Length (chunked shape); a body far past the cap — set via a
        // servlet-input stub the wrapper must honor
        java.io.ByteArrayInputStream bomb = new java.io.ByteArrayInputStream(new byte[4096]);
        request = new MockHttpServletRequest("POST",
                WebhookRateLimitFilter.PUBLIC_PREFIX + "/t/E/h") {
            @Override
            public ServletInputStream getInputStream() {
                return new org.springframework.mock.web.DelegatingServletInputStream(bomb) {
                    @Override
                    public boolean isFinished() {
                        return bomb.available() == 0;
                    }
                };
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        final int[] read = {0};
        filter.doFilter(request, response, (req, res) -> {
            // obtain the stream once (containers cache it; a fresh wrapper per call
            // would reset the cap)
            jakarta.servlet.ServletInputStream stream = req.getInputStream();
            byte[] buffer = new byte[256];
            int chunk;
            while ((chunk = stream.read(buffer)) != -1) {
                read[0] += chunk;
            }
        });
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(read[0]).isEqualTo(16);   // exactly the cap — never the 4096
    }

    @Test
    @DisplayName("normal bodies pass through untouched")
    void normalBodiesPass() throws Exception {
        RequestSizeCapFilter filter = new RequestSizeCapFilter(1024);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/runtime/Order");
        request.setContent(new byte[64]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        final int[] read = {0};
        filter.doFilter(request, response, (req, res) -> {
            byte[] buffer = new byte[256];
            int chunk;
            while ((chunk = req.getInputStream().read(buffer)) != -1) {
                read[0] += chunk;
            }
        });
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(read[0]).isEqualTo(64);
    }
}
