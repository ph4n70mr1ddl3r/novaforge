package com.novaforge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.novaforge.common.error.PlatformException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Unit tests for the shared trusted-service gate (the internal cross-service
 * surfaces — PHASE-4 §4/§7/§9). Controller wiring is covered by the services'
 * MockMvc suites (service-client gate, 403 for user tokens). */
class ServiceClientGateTest {

    private static Jwt jwt(String clientId, String azp) {
        return new Jwt("token", Instant.EPOCH, Instant.EPOCH.plusSeconds(60),
                Map.of("alg", "none"),
                Map.of("client_id", clientId, "azp", azp));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(Jwt token) {
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(token, AuthorityUtils.createAuthorityList(
                        "SCOPE_novaforge.api")));
    }

    @Test
    @DisplayName("the platform service client passes on client_id")
    void serviceClientByClientId() {
        authenticate(jwt(ServiceClientGate.CLIENT_ID, ServiceClientGate.CLIENT_ID));
        assertThatCode(() -> ServiceClientGate.require("resume")).doesNotThrowAnyException();
        assertThat(ServiceClientGate.isServiceClient()).isTrue();
    }

    @Test
    @DisplayName("the non-throwing twin reads the same verdict for user traffic")
    void isServiceClientForUsers() {
        authenticate(jwt("novaforge-api", "novaforge-api"));
        assertThat(ServiceClientGate.isServiceClient()).isFalse();
    }

    @Test
    @DisplayName("a user token relayed through the gateway is rejected 403 with the surface named")
    void userTokenRejected() {
        authenticate(jwt("novaforge-api", "novaforge-api"));
        assertThatThrownBy(() -> ServiceClientGate.require("resume"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("resume")
                .hasMessageContaining("service-client only");
    }

    @Test
    @DisplayName("an anonymous call is rejected")
    void anonymousRejected() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("anonymous", "n/a"));
        assertThatThrownBy(() -> ServiceClientGate.require("process-start"))
                .isInstanceOf(PlatformException.class);
    }

    @Test
    @DisplayName("no authentication at all is rejected")
    void noAuthenticationRejected() {
        assertThatThrownBy(() -> ServiceClientGate.require("record read"))
                .isInstanceOf(PlatformException.class);
    }
}
