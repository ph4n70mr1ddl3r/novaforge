package com.novaforge.gateway.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Renders 401s as RFC 7807 {@code application/problem+json} (PHASE-0 §6.1). The platform
 * error-code registry carries no 401 code in the Phase 0/1 slice — the problem's status
 * and title carry the semantics; the first 401-family code (SIGNATURE_INVALID, "4012")
 * joins with Phase 6's inbound webhooks.
 */
@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"title":"Unauthorized","status":401,"detail":"A valid bearer token \\
                with scope novaforge.api is required"}""");
    }
}
