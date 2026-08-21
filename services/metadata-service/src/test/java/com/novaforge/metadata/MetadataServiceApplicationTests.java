package com.novaforge.metadata;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

/** PHASE-0 §6.3 metadata-service test slice. */
@SpringBootTest
@AutoConfigureMockMvc
class MetadataServiceApplicationTests {

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
    @DisplayName("ping without token → 401")
    void pingRequiresToken() throws Exception {
        mockMvc.perform(get("/api/v1/metadata/ping"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("ping with token lacking scope → 403")
    void pingRequiresScope() throws Exception {
        mockMvc.perform(get("/api/v1/metadata/ping").with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ping with novaforge.api scope → ok, exact framework version 7.0.8")
    void pingReportsExactFrameworkVersion() throws Exception {
        mockMvc.perform(get("/api/v1/metadata/ping")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("metadata-service"))
                .andExpect(jsonPath("$.status").value("ok"))
                // Version-drift rule (PHASE-0 §10): intentional upgrades update this assertion.
                .andExpect(jsonPath("$.springFrameworkVersion").value("7.0.8"));
    }
}
