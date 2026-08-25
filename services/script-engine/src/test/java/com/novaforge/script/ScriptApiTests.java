package com.novaforge.script;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novaforge.common.context.TenantContext;
import com.novaforge.script.engine.QueryProxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
@SpringBootTest(properties = {"novaforge.scripts.heap-limit-mb=512",
        // the API relay is under test here, not the cap — GraalJS warm-up on a loaded
        // CI box can exceed the 1 s prod budget; the watchdog itself is pinned by
        // ScriptSandboxTests.cpuWatchdog with a tight sandbox
        "novaforge.scripts.cpu-budget-ms=10000"})
@AutoConfigureMockMvc
class ScriptApiTests {

    static final String TENANT = "11111111-1111-4111-8111-111111111111";
    static final String ACTOR = "33333333-3333-4333-8333-333333333333";

    static final List<String> CALLERS = new CopyOnWriteArrayList<>();

    /** The connector-sandbox egress stub (PHASE-6 §4): records calls, returns a body. */
    static final List<String> HTTP_CALLS = new CopyOnWriteArrayList<>();

    /** The scheduled leg's system queries the stub observed (PHASE-4 §7). */
    static final List<String> SYSTEM_QUERIES = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class StubQuery {

        @Bean
        @Primary
        QueryProxy queryProxy() {
            return new QueryProxy() {
                @Override
                public Object query(com.novaforge.common.context.TenantContext.Context caller,
                                    String entity, String queryJson) {
                    CALLERS.add(caller.tenantId() + "/" + caller.actorId());
                    return Map.of("rows", List.of(Map.of("sku", "WIDGET")), "total", 1L);
                }

                @Override
                public Object systemQuery(com.novaforge.common.context.TenantContext.Context principal,
                                         String app, String entity, String queryJson) {
                    SYSTEM_QUERIES.add(principal.tenantId() + "/" + principal.actorId()
                            + "/" + app + "/" + entity);
                    return Map.of("rows", List.of(Map.of("sku", "SCHEDULED")), "total", 1L);
                }
            };
        }

        @Bean
        @Primary
        com.novaforge.script.engine.HttpProxy httpProxy() {
            return (caller, app, connector, operation, template) -> {
                HTTP_CALLS.add(caller.tenantId() + "/" + app + "/" + connector + "."
                        + operation + "/" + template);
                return Map.of("status", 200, "body", Map.of("object", "list", "data",
                        List.of(Map.of("id", "txn_1"))));
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

    /** The platform service client (azp) — the scheduled surface's gate. */
    private static RequestPostProcessor serviceJwt() {
        return jwt()
                .jwt(token -> token.claim("azp", com.novaforge.security.ServiceClientGate.CLIENT_ID)
                        .subject("service-account-novaforge-runtime"))
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
    @DisplayName("$http exists only in the connector sandbox: declared → egress; absent → undefined (§4)")
    void httpGatedBySandboxContext() throws Exception {
        HTTP_CALLS.clear();
        // declared connector sandbox: $http routes through the proxy (the platform's
        // circuit-breaker/credential machinery, never raw sockets)
        mockMvc.perform(post("/api/v1/scripts/execute").with(engineJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "app": "Erp", "hook": "pull", "trigger": "beforeSave",
                                  "language": "js", "sandbox": "connector",
                                  "script": "const feed = $http.call('con_stripe', 'listTransactions', { limit: 5 }); ({ first: feed.body.data[0].id })",
                                  "record": {} }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value.first").value("txn_1"));
        org.assertj.core.api.Assertions.assertThat(HTTP_CALLS).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(HTTP_CALLS.getFirst())
                .contains(TENANT + "/Erp/con_stripe.listTransactions");

        // default sandbox: the egress does not exist at all — ReferenceError, a problem
        mockMvc.perform(post("/api/v1/scripts/execute").with(engineJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "app": "Erp", "hook": "pull", "trigger": "beforeSave",
                                  "language": "js",
                                  "script": "$http.call('con_stripe', 'listTransactions', {})",
                                  "record": {} }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4000"));
        org.assertj.core.api.Assertions.assertThat(HTTP_CALLS).hasSize(1);
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

    @Test
    @DisplayName("the scheduled leg is service-client only — a user token answers 403 (§7)")
    void scheduledSurfaceIsServiceGated() throws Exception {
        mockMvc.perform(post("/api/v1/scripts/scheduled").with(engineJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"" + TENANT + "\", \"app\":\"Erp\","
                                + " \"hook\":\"sweep\", \"script\":\"1\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("service-client")));
    }

    @Test
    @DisplayName("scheduled execution: $record absent, $data rides the system leg (§7)")
    void scheduledExecutionBindsSystemPrincipal() throws Exception {
        SYSTEM_QUERIES.clear();
        // the per-app system principal's UUID, derived exactly as the runtime does
        String systemActor = UUID.nameUUIDFromBytes("system:Erp".getBytes()).toString();
        mockMvc.perform(post("/api/v1/scripts/scheduled").with(serviceJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "tenantId": "%s", "app": "Erp", "appVersion": 3,
                                  "hook": "nightlySweep", "language": "js",
                                  "script": "const rows = $data.query('Payment', '{}'); ({ found: rows.rows[0].sku })" }
                                """.formatted(TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value.found").value("SCHEDULED"));
        org.assertj.core.api.Assertions.assertThat(SYSTEM_QUERIES).containsExactly(
                TENANT + "/" + systemActor + "/Erp/Payment");
        org.assertj.core.api.Assertions.assertThat(CALLERS).isEmpty();

        // $record is absent in the recordless context — a reach for it fails loudly
        mockMvc.perform(post("/api/v1/scripts/scheduled").with(serviceJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "tenantId": "%s", "app": "Erp", "hook": "nightlySweep",
                                  "script": "$record.id" }
                                """.formatted(TENANT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("$record")));
    }

    @Test
    @DisplayName("scheduled authoring rejects: blank script, missing tenant (§7)")
    void scheduledValidation() throws Exception {
        mockMvc.perform(post("/api/v1/scripts/scheduled").with(serviceJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"" + TENANT + "\", \"app\":\"Erp\","
                                + " \"hook\":\"sweep\", \"script\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("blank")));
        mockMvc.perform(post("/api/v1/scripts/scheduled").with(serviceJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"app\":\"Erp\", \"hook\":\"sweep\", \"script\":\"1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("tenantId")));
    }
}
