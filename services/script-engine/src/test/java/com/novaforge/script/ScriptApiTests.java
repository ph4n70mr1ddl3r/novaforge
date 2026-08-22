package com.novaforge.script;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novaforge.common.context.TenantContext;
import com.novaforge.script.engine.QueryProxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The internal execution surface (PHASE-3 §6): problem+json 401s without a token,
 * executions run under the calling tenant, and a capped script — the T6 acceptance —
 * dies at its budget while the service stays on its feet.
 */
@SpringBootTest(properties = "novaforge.script.heap-limit-mb=512")
@AutoConfigureMockMvc
class ScriptApiTests {

    static final String TENANT = "11111111-1111-4111-8111-111111111111";
    static final String ACTOR = "33333333-3333-4333-8333-333333333333";

    static final List<String> CALLERS = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class StubQuery {
        @Bean
        @Primary
        QueryProxy queryProxy() {
            return (caller, entity, queryJson) -> {
                CALLERS.add(caller.tenantId() + "/" + caller.actorId());
                return Map.of("rows", List.of(Map.of("sku", "WIDGET")), "total", 1L);
            };
        }
    }

    @Autowired
    MockMvc mockMvc;

    private static RequestPostProcessor engineJwt() {
        return jwt()
                .jwt(token -> token.claim("tenant_id", TENANT).subject(ACTOR))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
    }

    @Test
    @DisplayName("a valid token executes: value + logs return, $data rides the caller")
    void executesUnderCallerContext() throws Exception {
        CALLERS.clear();
        mockMvc.perform(post("/api/v1/scripts/execute").with(engineJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "app": "Erp", "hook": "enrich", "trigger": "beforeSave",
                                  "language": "js",
                                  "script": "const found = $data.query('InventoryItem', '{}'); $log.info('rows=' + found.total); ({ sku: found.rows[0].sku })",
                                  "record": { "label": "widget" } }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value.sku").value("WIDGET"))
                .andExpect(jsonPath("$.logs[0]").value("INFO rows=1"));
        org.assertj.core.api.Assertions.assertThat(CALLERS).containsExactly(TENANT + "/" + ACTOR);
    }

    @Test
    @DisplayName("no token renders 401 problem+json")
    void unauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/scripts/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"script\":\"1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    @DisplayName("a capped script dies at its budget and renders a problem (T6 acceptance)")
    void cappedScriptRendersProblem() throws Exception {
        mockMvc.perform(post("/api/v1/scripts/execute").with(engineJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "app": "Erp", "hook": "runaway", "trigger": "afterSave",
                                  "language": "js", "script": "while (true) { }" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4000"))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("budget")));
    }

    @Test
    @DisplayName("malformed requests reject: blank script, unknown language")
    void malformedRequests() throws Exception {
        mockMvc.perform(post("/api/v1/scripts/execute").with(engineJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"script\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("blank")));
        mockMvc.perform(post("/api/v1/scripts/execute").with(engineJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"python\",\"script\":\"1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("language")));
    }
}
