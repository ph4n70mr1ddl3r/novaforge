package com.novaforge.gateway.security;

import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The edge body cap (2026-08-31, sixteenth pass): the platform had no request-size
 * limit anywhere — no route filter, no servlet max-* setting — and the one anonymous
 * route lands on a controller that materializes the whole body into a {@code byte[]}
 * before HMAC verification runs. An unauthenticated multi-GB POST (chunked, so no
 * Content-Length to distrust either) allocated per request: an OOM vector the
 * request-count limiter never sees (it counts requests, not bytes).
 *
 * <p>Every request is capped: a declared Content-Length over the limit rejects 413
 * before a byte is read, and a chunked (or lying) stream is truncated at the limit —
 * the truncated read surfaces to the caller as a connection error, never a silent
 * partial payload the service might process.</p>
 */
@Component
@Order(-9)   // inside the rate limiter (-10), ahead of everything else
public class RequestSizeCapFilter extends OncePerRequestFilter {

    private final long maxBytes;

    public RequestSizeCapFilter(@Value("${novaforge.request-cap-bytes:10485760}") long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("{\"title\":\"Payload Too Large\",\"status\":413,"
                    + "\"detail\":\"request body exceeds " + maxBytes + " bytes\"}");
            return;
        }
        filterChain.doFilter(new CappedRequest(request, maxBytes), response);
    }

    /** Reads at most {@code remaining} bytes, then reports EOF — a chunked bomb dies
     *  at the cap instead of draining into the service's buffer. */
    static final class CappedRequest extends HttpServletRequestWrapper {

        private final long remaining;

        CappedRequest(HttpServletRequest delegate, long maxBytes) {
            super(delegate);
            this.remaining = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws java.io.IOException {
            ServletInputStream delegate = super.getInputStream();
            return new ServletInputStream() {
                long left = remaining;

                @Override
                public boolean isFinished() {
                    return delegate.isFinished() || left <= 0;
                }

                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    delegate.setReadListener(listener);
                }

                @Override
                public int read() throws IOException {
                    if (left <= 0) {
                        return -1;
                    }
                    left--;
                    return delegate.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    if (left <= 0) {
                        return -1;
                    }
                    int chunk = delegate.read(buffer, offset, (int) Math.min(length, left));
                    if (chunk > 0) {
                        left -= chunk;
                    }
                    return chunk;
                }
            };
        }
    }
}
