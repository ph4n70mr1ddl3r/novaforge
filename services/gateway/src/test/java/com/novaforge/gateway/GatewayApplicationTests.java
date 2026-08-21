package com.novaforge.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

/** PHASE-0 §6.3 gateway test slice (default, hermetic — Keycloak-backed tests are the
 * {@code integration} profile/tag suite). */
@SpringBootTest
@AutoConfigureMockMvc
class GatewayApplicationTests {

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("context loads and health is UP, anonymously")
    void healthIsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("no token → 401 problem+json")
    void unauthenticatedIsProblemJson() throws Exception {
        mockMvc.perform(get("/api/v1/metadata/ping"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("token without novaforge.api scope → 403")
    void wrongScopeIsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/metadata/ping").with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("route proof: valid token, no downstream running → proxy attempted (connection refused), not 404")
    void routeMatchAttemptsProxy() {
        // The route matched and the proxy call was attempted: connection refused to the
        // (not-running) upstream, never a 404 from the edge (PHASE-0 §6.3). MockMvc
        // rethrows the unhandled proxy failure out of perform().
        ServletException thrown = catchThrowableOfType(() -> mockMvc.perform(get("/api/v1/metadata/ping")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api")))),
                ServletException.class);
        assertThat(thrown).isNotNull();
        boolean proxyAttempt = false;
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof ResourceAccessException) {
                proxyAttempt = true;
                break;
            }
        }
        assertThat(proxyAttempt)
                .as("expected ResourceAccessException in the cause chain of %s", thrown)
                .isTrue();
    }
}
