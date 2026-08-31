package com.novaforge.gateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The public-route rate limiter (PHASE-6 §6, activating PHASE-0 §6.1's deferral):
 * the anonymous inbound-webhook prefix — the gateway's only public API path — is
 * rate-limited from its first day, Redis-backed, fixed-window per remote address.
 * Authenticated routes carry no per-user limits in v1 (ARCHITECTURE.md §2.1: a
 * stated decision, not an omission).
 */
@Component
@Order(-10)
public class WebhookRateLimitFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookRateLimitFilter.class);

    /** The one prefix the limiter guards (§2: exactly one anonymous route). */
    public static final String PUBLIC_PREFIX = "/api/v1/webhooks/inbound";

    /**
     * One atomic round-trip: INCR, and PEXPIRE only on the window's first hit.
     * Separate calls left the window key immortal whenever anything failed between
     * them — each minute mints a fresh key, so it never over-blocks, but the
     * residue accumulates in Redis forever (a slow, unbounded memory leak).
     */
    private static final org.springframework.data.redis.core.script.RedisScript<Long>
            WINDOW_INCREMENT = org.springframework.data.redis.core.script.RedisScript.of(
            "local count = redis.call('INCR', KEYS[1]) "
                    + "if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
                    + "return count",
            Long.class);

    private final StringRedisTemplate redis;
    private final int requestsPerMinute;

    public WebhookRateLimitFilter(StringRedisTemplate redis,
                                  @Value("${novaforge.webhook.rate-limit-per-minute:60}")
                                  int requestsPerMinute) {
        this.redis = redis;
        this.requestsPerMinute = requestsPerMinute;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // match the route/security patterns' semantics: both the exact prefix
        // (Path=/api/v1/webhooks/inbound/** matches the slash-less form too) and
        // everything under it — the trailing-slash-only check left the exact path
        // anonymous, routed, and unthrottled
        String uri = request.getRequestURI();
        boolean publicRoute = uri.equals(PUBLIC_PREFIX) || uri.startsWith(PUBLIC_PREFIX + "/");
        if (!publicRoute || uri.startsWith("/actuator")) {
            filterChain.doFilter(request, response);
            return;
        }
        String key = "novaforge:rl:" + clientOf(request) + ":" + window();
        Long count;
        try {
            count = redis.execute(WINDOW_INCREMENT, java.util.List.of(key),
                    String.valueOf(Duration.ofMinutes(1).toMillis()));
        } catch (Exception e) {
            // Redis unavailable: fail open — availability of the public route beats a
            // limiter outage, and the HMAC verification behind it still gates every call.
            LOG.warn("rate limiter backend unavailable — failing open: {}", e.getMessage());
            count = null;
        }
        if (count != null && count > requestsPerMinute) {
            LOG.warn("rate limit exceeded for {} ({} > {}/min)", request.getRemoteAddr(), count,
                    requestsPerMinute);
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("{\"title\":\"Too Many Requests\",\"status\":429,"
                    + "\"detail\":\"inbound webhook rate limit exceeded — retry after the "
                    + "window\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * The remote address — the socket peer, never a client-supplied hop. The gateway is
     * the exposed edge (no trusted proxy rewrites {@code X-Forwarded-For} in the
     * deployed topology), so honoring XFF here would let a caller mint a fresh limit
     * key per request (bypass), pin a victim's address into the key (cross-victim
     * denial), and forge multiline log entries (CRLF). If a trusted proxy is ever
     * introduced, derive the key from the last untrusted hop behind a proxy allowlist.
     */
    private static String clientOf(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    private static String window() {
        return String.valueOf(System.currentTimeMillis() / 60_000);
    }
}
