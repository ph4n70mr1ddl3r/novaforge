package com.novaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.runtime.engine.metadata.EntityResolver;
import com.novaforge.runtime.engine.metadata.MetadataClient;
import com.novaforge.runtime.engine.hook.ScriptClient;
import com.novaforge.runtime.storage.materializer.Materializer;
import com.novaforge.testsupport.PostgresTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * The Scheduler's {@code script} target (PHASE-4 §7 — "the Script Engine the same
 * way"): a recordless firing of a script hook through the same internal
 * scheduled-hook surface the {@code flow} target rides. The script executes as the
 * per-app system principal on the engine's service-gated scheduled leg — never by
 * relaying the scheduler's service token as a caller — and the script's
 * {@code $data.query} reaches the runtime's internal system-principal query surface
 * (also pinned here: raw stored rows, service-client gated).
 */
@SpringBootTest(properties = {"novaforge.events.relay-interval-ms=3600000"})
@AutoConfigureMockMvc
class ScheduledScriptTests extends PostgresTestBase {

    static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID ACTOR = UUID.fromString("33333333-3333-4333-8333-333333333333");
    static final UUID APP_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");

    static final String APP_JSON = """
            { "apiName": "Ledger",
              "entities": [
                { "apiName": "Payment",
                  "displayField": "reference",
                  "fields": [
                    { "apiName": "reference", "type": "text", "required": true },
                    { "apiName": "amount", "type": "decimal", "precision": 18, "scale": 4 } ],
                  "hooks": [
                    { "name": "nightlySweep", "trigger": "afterSave",
                      "script": { "language": "js", "source": "1" } },
                    { "name": "stampRef", "trigger": "afterSave",
                      "flow": { "id": "s1", "op": "setField",
                        "params": { "field": "reference",
                                    "value": "'swept-' + reference" } } } ] } ] }
            """;

    /** Scheduled script firings the stub Script Engine observed. */
    static final List<String> SCHEDULED = new CopyOnWriteArrayList<>();

    static AppDefinition app;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    EntityResolver resolver;

    @TestConfiguration
    static class Stubs {

        @Bean
        @Primary
        MetadataClient metadataClient() {
            MetadataClient client = Mockito.mock(MetadataClient.class);
            app = DefinitionParser.parseApp(APP_JSON);
            Mockito.when(client.publishedApps()).thenAnswer(inv ->
                    List.of(new MetadataClient.PublishedApp(APP_ID, "Ledger", 1)));
            Mockito.when(client.publishedBundle(Mockito.any(UUID.class))).thenAnswer(inv ->
                    new MetadataClient.PublishedBundle(1, app));
            return client;
        }

        /** The script-engine port, stubbed: the scheduled leg records its context. */
        @Bean
        @Primary
        ScriptClient scriptClient() {
            return new ScriptClient() {
                @Override
                public ScriptOutcome execute(String appApiName, int appVersion, String hook,
                                             String trigger,
                                             com.novaforge.metadata.ScriptDefinition script,
                                             Map<String, Object> record) {
                    // benign: the public writes this suite seeds fire afterSave hooks
                    // through the caller-context leg — not this suite's subject
                    return new ScriptOutcome(Map.of(), List.of());
                }

                @Override
                public ScriptOutcome executeScheduled(UUID tenantId, String appApiName,
                                                      int appVersion, String hook,
                                                      com.novaforge.metadata.ScriptDefinition script) {
                    SCHEDULED.add(tenantId + "|" + appApiName + "|" + appVersion + "|" + hook
                            + "|" + script.language());
                    return new ScriptOutcome("swept", List.of("stub scheduled"));
                }
            };
        }
    }

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("docker.io/library/redis:7.4.11")
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    private static final org.testcontainers.kafka.KafkaContainer KAFKA =
            new org.testcontainers.kafka.KafkaContainer("apache/kafka:4.3.1");

    static {
        REDIS.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestBase::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestBase::jdbcUsername);
        registry.add("spring.datasource.password", PostgresTestBase::jdbcPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @BeforeAll
    static void materialize(@Autowired Materializer materializer) {
        materializer.apply(DefinitionParser.parseApp(APP_JSON));
    }

    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor() {
        return jwt()
                .jwt(token -> token.claim("tenant_id", TENANT.toString())
                        .subject(ACTOR.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
    }

    /** The platform service client (azp) — the internal surfaces' gate. */
    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor serviceJwt() {
        return jwt()
                .jwt(token -> token.claim("azp", com.novaforge.security.ServiceClientGate.CLIENT_ID)
                        .subject("service-account-novaforge-runtime"))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
    }

    @Test
    @DisplayName("a scheduled script hook fires recordless as the system principal (§7)")
    void scheduledScriptHookFires() throws Exception {
        SCHEDULED.clear();
        mockMvc.perform(post("/api/v1/hooks/scheduled").with(serviceJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"" + TENANT + "\",\"app\":\"Ledger\","
                                + "\"entityApiName\":\"Payment\",\"hook\":\"nightlySweep\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("fired"));
        // the engine leg received the per-app context: tenant, app, version, hook
        assertThat(SCHEDULED).containsExactly(
                TENANT + "|Ledger|1|nightlySweep|js");
    }

    @Test
    @DisplayName("scheduled flow hooks still ride the same surface; unknown hooks 404 (§7)")
    void flowHookStillFires() throws Exception {
        // the script-hook leg is additive: a flow hook fired by name still executes
        // through the compiled-graph engine (the flow target's regression — one
        // surface serves both kinds)
        SCHEDULED.clear();
        mockMvc.perform(post("/api/v1/hooks/scheduled").with(serviceJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"" + TENANT + "\",\"app\":\"Ledger\","
                                + "\"entityApiName\":\"Payment\",\"hook\":\"stampRef\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("fired"));
        assertThat(SCHEDULED).isEmpty();   // a flow hook never touches the script leg

        // unknown hook names answer 404 — never a silent no-op
        mockMvc.perform(post("/api/v1/hooks/scheduled").with(serviceJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"" + TENANT + "\",\"app\":\"Ledger\","
                                + "\"entityApiName\":\"Payment\",\"hook\":\"ghost\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the internal system query serves raw rows, service-client only (§7)")
    void internalSystemQuery() throws Exception {
        // seed two payments through the public write path
        mockMvc.perform(post("/api/v1/runtime/Payment").with(jwtFor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"TX-1\",\"amount\":\"70.0000\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/runtime/Payment").with(jwtFor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"TX-2\",\"amount\":\"30.0000\"}"))
                .andExpect(status().isOk());

        String query = "{\"filter\":{\"field\":\"reference\",\"op\":\"eq\",\"value\":\"TX-1\"},"
                + "\"page\":{\"size\":50}}";
        mockMvc.perform(post("/api/v1/hooks/records/query").with(serviceJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"" + TENANT + "\",\"app\":\"Ledger\","
                                + "\"entityApiName\":\"Payment\",\"query\":"
                                + "\"" + query.replace("\"", "\\\"") + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.total").value(1))
                .andExpect(jsonPath("$.result.rows[0].reference").value("TX-1"))
                .andExpect(jsonPath("$.result.rows[0].amount").value(70.0));

        // user tokens never reach the surface
        mockMvc.perform(post("/api/v1/hooks/records/query").with(jwtFor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"" + TENANT + "\",\"app\":\"Ledger\","
                                + "\"entityApiName\":\"Payment\"}"))
                .andExpect(status().isForbidden());
    }
}
