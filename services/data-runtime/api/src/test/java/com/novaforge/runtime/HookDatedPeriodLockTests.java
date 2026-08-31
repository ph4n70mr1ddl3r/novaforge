package com.novaforge.runtime;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.runtime.engine.metadata.MetadataClient;
import com.novaforge.runtime.storage.materializer.Materializer;
import com.novaforge.testsupport.PostgresTestBase;
import java.util.List;
import java.util.UUID;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The doom-guards' post-hook leg (eighteenth pass, re-audit finding 1): a
 * beforeSave hook (or its formula re-evaluation) that re-dates the record into a
 * CLOSED period must meet the same PERIOD_LOCKED rejection as a dated write from
 * the caller — the guards run before the hooks (external side effects of a doomed
 * write) AND after them (the landing state, like the state-machine check). The
 * reorder that only moved them earlier let hook-dated writes into closed periods
 * commit.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HookDatedPeriodLockTests extends PostgresTestBase {

    static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID ACTOR = UUID.fromString("33333333-3333-4333-8333-333333333333");
    static final UUID APP_ID = UUID.fromString("88888888-8888-4888-8888-888888888888");

    /** One period (August), one dated entity whose beforeSave hook re-dates to the 15th. */
    static final String APP_JSON = """
            { "apiName": "Ledger2",
              "entities": [
                { "apiName": "AccountingPeriod",
                  "displayField": "name",
                  "fields": [
                    { "apiName": "name", "type": "text", "required": true },
                    { "apiName": "startDate", "type": "date", "required": true },
                    { "apiName": "endDate", "type": "date", "required": true },
                    { "apiName": "status", "type": "enum",
                      "values": ["OPEN", "CLOSED"] } ] },
                { "apiName": "Memo",
                  "displayField": "label",
                  "periodLock": { "entity": "AccountingPeriod", "dateField": "entryDate" },
                  "fields": [
                    { "apiName": "label", "type": "text", "required": true },
                    { "apiName": "entryDate", "type": "date", "required": true },
                    { "apiName": "note", "type": "text" } ],
                  "hooks": [
                    { "name": "redate", "trigger": "beforeSave",
                      "flow": { "id": "r1", "op": "setField",
                                "params": { "field": "entryDate", "expression": "'2026-08-15'" } } } ] } ],
              "stateMachines": [
                { "id": "sm_period", "entity": "AccountingPeriod", "stateField": "status",
                  "initial": "OPEN",
                  "states": [ { "name": "OPEN" }, { "name": "CLOSED", "terminal": true } ],
                  "transitions": [ { "from": "OPEN", "to": "CLOSED" } ] } ] }
            """;

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    static AppDefinition app;

    @Autowired
    MockMvc mockMvc;

    @TestConfiguration
    static class StubMetadata {

        @Bean
        @Primary
        MetadataClient metadataClient() {
            MetadataClient client = Mockito.mock(MetadataClient.class);
            app = DefinitionParser.parseApp(APP_JSON);
            Mockito.when(client.publishedApps()).thenAnswer(inv ->
                    List.of(new MetadataClient.PublishedApp(APP_ID, "Ledger2", 1)));
            Mockito.when(client.publishedBundle(Mockito.any(UUID.class))).thenAnswer(inv ->
                    new MetadataClient.PublishedBundle(1, app));
            return client;
        }
    }

    private static final org.testcontainers.containers.GenericContainer<?> REDIS =
            new org.testcontainers.containers.GenericContainer<>("docker.io/library/redis:7.4.11")
                    .withExposedPorts(6379)
                    .waitingFor(org.testcontainers.containers.wait.strategy.Wait
                            .forLogMessage(".*Ready to accept connections.*", 1));

    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

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
        materializer.apply(app);
    }

    @Test
    @DisplayName("a beforeSave hook re-dating into a CLOSED period rejects PERIOD_LOCKED (the post-hook guard)")
    void hookDatedWriteIntoClosedPeriodRejects() throws Exception {
        // the period starts OPEN; a memo lands (its hook re-dates to the 15th — open)
        MvcResult period = mockMvc.perform(post("/api/v1/runtime/AccountingPeriod").with(jwtFor())
                        .contentType("application/json")
                        .content("{\"name\":\"aug\",\"startDate\":\"2026-08-01\","
                                + "\"endDate\":\"2026-08-31\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String periodId = MAPPER.readTree(period.getResponse().getContentAsString())
                .get("id").asString();
        MvcResult created = mockMvc.perform(post("/api/v1/runtime/Memo").with(jwtFor())
                        .contentType("application/json")
                        .content("{\"label\":\"m1\",\"entryDate\":\"2026-08-02\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String id = MAPPER.readTree(created.getResponse().getContentAsString())
                .get("id").asString();

        // close the period: the next memo write is doomed — the caller's own edit
        // carries NO date, the HOOK re-dates it to the 15th, and the landing state
        // must meet the same PERIOD_LOCKED gate (the pre-hook check alone saw an
        // open-dated record and would have let it through)
        mockMvc.perform(patch("/api/v1/runtime/AccountingPeriod/" + periodId)
                        .with(jwtFor()).contentType("application/json")
                        .content("{\"version\":1,\"status\":\"CLOSED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/runtime/Memo/" + id).with(jwtFor())
                        .contentType("application/json")
                        .content("{\"version\":1,\"note\":\"an edit\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4014"));

        // control: the same closed period admits nothing dated by the caller either
        mockMvc.perform(post("/api/v1/runtime/Memo").with(jwtFor())
                        .contentType("application/json")
                        .content("{\"label\":\"m2\",\"entryDate\":\"2026-08-20\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4014"));

        // the CREATE leg (nineteenth pass — the eighteenth fixed the update door
        // only): the caller dates September, no period covers it, the pre-hook
        // guard passes — the HOOK re-dates to the 15th of the CLOSED August period,
        // and the landing state must meet the same gate. (This pin lives inside
        // this method on purpose: period lookup is by date range, so a second
        // method closing its own August period would couple the two through every
        // August date either one writes.)
        mockMvc.perform(post("/api/v1/runtime/Memo").with(jwtFor())
                        .contentType("application/json")
                        .content("{\"label\":\"m-create\",\"entryDate\":\"2026-09-10\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4014"));
    }

    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor() {
        return jwt()
                .jwt(token -> token.claim("tenant_id", TENANT.toString()).subject(ACTOR.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
    }
}
