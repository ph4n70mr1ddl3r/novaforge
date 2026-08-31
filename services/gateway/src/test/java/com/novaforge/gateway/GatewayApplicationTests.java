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
 * {@code integration} profile/tag suite). The route proof pins a dead upstream port so
 * the proxy attempt is observable even when a real service happens to run locally. */
@SpringBootTest(properties = {"novaforge.upstreams.metadata-service=http://localhost:8099",
        // Hermetic slice (found live, twenty-fourth pass): the context boots the
        // rate limiter's StringRedisTemplate, whose health contributor pings
        // spring.data.redis — silently localhost:6379. Standalone runs against a
        // quiet port answered 503 on /actuator/health; the suite had passed only
        // when some ambient redis happened to listen. The limiter's own behavior
        // is pinned in WebhookRateLimitFilterTest (mocked template, the atomic
        // Lua window included); this slice asserts the platform's own health.
        "management.health.redis.enabled=false"})
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
    @DisplayName("prometheus is authenticated at the edge — anonymous scrape 401s, scoped token passes")
    void prometheusIsNotAnonymous() throws Exception {
        // Anti-regression (2026-08-31): /actuator/** was permitAll — the L-TP7
        // behind-the-cluster posture never held for the gateway itself, the one
        // internet-facing component; per-route volumes and infra detail were
        // anonymously scrapeable. Health/info stay anonymous; the exposition
        // surface now carries the platform's own token.
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mockMvc.perform(get("/actuator/prometheus")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"))))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("jvm_")));
        // health stays anonymous for probes and humans
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("SPA hosting (PHASE-2 §13 Q5): shells + deep links serve anonymously from the bundle tree")
    void spaShellsServeAnonymously() throws Exception {
        // the test classpath ships a marker bundle so the fallback path is observable
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<!doctype html>")));
        mockMvc.perform(get("/runtime/orders/123"))   // deep link → runtime shell
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<!doctype html>")));
        mockMvc.perform(get("/builder/entities"))     // builder shell by prefix
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<!doctype html>")));
    }

    @Test
    @DisplayName("each prefix serves its OWN shell, and bundle files serve as files (found live at the 2026-08-28 golden-journey wiring)")
    void spaPrefixServesItsOwnShellAndAssetsServeAsFiles() throws Exception {
        // /builder must answer the builder shell — the old controller read an
        // unbound request param, so every deep link served the runtime shell.
        mockMvc.perform(get("/builder"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers
                        .containsString("<title>NovaForge Builder</title>")));
        mockMvc.perform(get("/runtime"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers
                        .containsString("<title>NovaForge</title>")));
        // a real bundle file serves as the file — with the filename's media type,
        // never the content-negotiated shell (browsers reject JSON-typed modules)
        mockMvc.perform(get("/runtime/assets/marker.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/javascript"))
                .andExpect(content().string(org.hamcrest.Matchers
                        .containsString("runtime-asset")));
        // an unknown asset-looking path is still the shell (SPA routing decides)
        mockMvc.perform(get("/runtime/assets/nope.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers
                        .containsString("<!doctype html>")));
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

    @Test
    @DisplayName("aggregated OpenAPI (PLAN.md §4): scope-gated, serves even with every upstream down")
    void aggregatedOpenApiServesAndDegrades() throws Exception {
        // every upstream is dead in this slice — the merged document still serves,
        // listing the missing services instead of failing the edge (the aggregator's
        // audible-degradation pin)
        mockMvc.perform(get("/api/v1/openapi.json")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("NovaForge Platform API"))
                .andExpect(jsonPath("$.info['x-novaforge-services']").isArray())
                .andExpect(jsonPath("$.info['x-novaforge-unavailable']").isArray());
        // and the route gates like every other API path: anonymous → 401
        mockMvc.perform(get("/api/v1/openapi.json"))
                .andExpect(status().isUnauthorized());
    }
}
