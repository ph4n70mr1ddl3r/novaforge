package com.novaforge.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * PHASE-6 §6 (activating PHASE-0 §6.1): the anonymous inbound-webhook prefix is
 * rate-limited from its first day — Redis fixed-window per client; other routes
 * pass untouched, and a Redis outage fails open (availability of the public route;
 * the HMAC verification behind it still gates every call).
 */
class WebhookRateLimitFilterTest {

    @Test
    @DisplayName("within the window requests pass; over the limit renders 429 problem+json")
    void limitsThePublicPrefix() throws Exception {
        CountingRedis redis = new CountingRedis();
        WebhookRateLimitFilter filter = new WebhookRateLimitFilter(redis, 2);

        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                WebhookRateLimitFilter.PUBLIC_PREFIX + "/tenant/Payment/wh_feed");
        request.setRemoteAddr("203.0.113.9");

        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(request, first, passing());
        assertThat(first.getStatus()).isEqualTo(200);

        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(request, second, passing());
        assertThat(second.getStatus()).isEqualTo(200);

        MockHttpServletResponse third = new MockHttpServletResponse();
        filter.doFilter(request, third, passing());
        assertThat(third.getStatus()).isEqualTo(429);
        assertThat(third.getContentAsString()).contains("rate limit exceeded");
        assertThat(redis.increments).isEqualTo(3);
    }

    @Test
    @DisplayName("non-public routes never touch the limiter; a Redis outage fails open")
    void scopingAndFailOpen() throws Exception {
        CountingRedis redis = new CountingRedis();
        redis.failing = true;
        WebhookRateLimitFilter filter = new WebhookRateLimitFilter(redis, 1);

        // an authenticated route passes without a Redis call at all
        MockHttpServletRequest record = new MockHttpServletRequest("GET", "/api/v1/runtime/Payment");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(record, response, passing());
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(redis.increments).isZero();

        // the public route under a Redis outage fails open — 200, not an outage page
        MockHttpServletRequest webhook = new MockHttpServletRequest("POST",
                WebhookRateLimitFilter.PUBLIC_PREFIX + "t/E/h");
        MockHttpServletResponse degraded = new MockHttpServletResponse();
        filter.doFilter(webhook, degraded, passing());
        assertThat(degraded.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("a spoofed X-Forwarded-For never mints a fresh key — the socket peer is the client")
    void xffIsNotTrusted() throws Exception {
        // Anti-regression (2026-08-31): the limiter keyed on the client-supplied first
        // XFF hop — rotating the header minted a fresh window per request (bypass),
        // and pinning a victim's address keyed their traffic (cross-victim denial).
        CountingRedis redis = new CountingRedis();
        WebhookRateLimitFilter filter = new WebhookRateLimitFilter(redis, 2);

        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                WebhookRateLimitFilter.PUBLIC_PREFIX + "/tenant/Payment/wh_feed");
        request.setRemoteAddr("203.0.113.9");
        request.addHeader("X-Forwarded-For", "198.51.100.1");

        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, passing());
            assertThat(response.getStatus()).isEqualTo(200);
        }
        // third call from the same socket, a brand-new spoofed hop — still over limit
        MockHttpServletRequest rotated = new MockHttpServletRequest("POST",
                WebhookRateLimitFilter.PUBLIC_PREFIX + "/tenant/Payment/wh_feed");
        rotated.setRemoteAddr("203.0.113.9");
        rotated.addHeader("X-Forwarded-For", "198.51.100.77");
        MockHttpServletResponse third = new MockHttpServletResponse();
        filter.doFilter(rotated, third, passing());
        assertThat(third.getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("the slash-less public path is throttled too (the route pattern matches it)")
    void slashLessPublicPathIsLimited() throws Exception {
        // Anti-regression (2026-08-31, fifteenth pass): the limiter keyed on the
        // trailing-slash prefix while the route and security patterns match the exact
        // slash-less form — POST /api/v1/webhooks/inbound was anonymous, proxied,
        // and unthrottled.
        CountingRedis redis = new CountingRedis();
        WebhookRateLimitFilter filter = new WebhookRateLimitFilter(redis, 2);

        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                WebhookRateLimitFilter.PUBLIC_PREFIX);   // no trailing slash
        request.setRemoteAddr("203.0.113.9");
        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, passing());
            assertThat(response.getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse third = new MockHttpServletResponse();
        filter.doFilter(request, third, passing());
        assertThat(third.getStatus()).isEqualTo(429);
        assertThat(redis.increments).isEqualTo(3);
    }

    private static FilterChain passing() {
        return (request, response) -> {
            // downstream reached (the filter chain continued)
        };
    }

    /** A counting Redis template — no broker needed for the window mechanics. */
    static class CountingRedis extends StringRedisTemplate {

        final ValueOperations<String, String> values =
                org.mockito.Mockito.mock(ValueOperations.class);
        int increments;
        boolean failing;

        CountingRedis() {
            org.mockito.Mockito.when(values.increment(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(inv -> {
                        if (failing) {
                            throw new IllegalStateException("redis down");
                        }
                        return (long) ++increments;
                    });
        }

        @Override
        public ValueOperations<String, String> opsForValue() {
            return values;
        }
    }
}
