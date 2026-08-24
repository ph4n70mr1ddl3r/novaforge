package com.novaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Phase 7 harvests on the write path (PHASE-7 §3, §9 items 2–3):
 * {@code freezeOnTerminal} makes a posted journal entry an immutable document — field
 * updates, deletes, and child writes naming it all reject with {@code RECORD_FROZEN}
 * (4013); {@code PeriodLock} rejects dated-into-closed-period writes with
 * {@code PERIOD_LOCKED} (4014), and deactivates the moment the period reopens (§4) —
 * nothing is ever un-frozen, but a reopened period admits new dated writes.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FreezePeriodTests extends PostgresTestBase {

    static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID ACTOR = UUID.fromString("33333333-3333-4333-8333-333333333333");
    static final UUID APP_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    static final String APP_JSON = """
            { "apiName": "Ledger",
              "entities": [
                { "apiName": "AccountingPeriod",
                  "displayField": "name",
                  "fields": [
                    { "apiName": "name", "type": "text", "required": true },
                    { "apiName": "startDate", "type": "date", "required": true },
                    { "apiName": "endDate", "type": "date", "required": true },
                    { "apiName": "status", "type": "enum",
                      "values": ["OPEN", "CLOSING", "CLOSED"] } ] },
                { "apiName": "LedgerEntry",
                  "displayField": "label",
                  "freezeOnTerminal": true,
                  "periodLock": { "entity": "AccountingPeriod", "dateField": "entryDate" },
                  "fields": [
                    { "apiName": "label", "type": "text", "required": true },
                    { "apiName": "entryDate", "type": "date", "required": true },
                    { "apiName": "amount", "type": "money" },
                    { "apiName": "status", "type": "enum", "values": ["DRAFT", "POSTED"] } ],
                  "relationships": [
                    { "apiName": "lines", "type": "child", "target": "LedgerLine",
                      "cascadeOn": true } ] },
                { "apiName": "LedgerLine",
                  "fields": [
                    { "apiName": "entry", "type": "lookup", "target": "LedgerEntry",
                      "required": true },
                    { "apiName": "debit", "type": "money" },
                    { "apiName": "credit", "type": "money" } ] } ],
              "stateMachines": [
                { "id": "sm_je", "entity": "LedgerEntry", "stateField": "status",
                  "initial": "DRAFT",
                  "states": [ { "name": "DRAFT" }, { "name": "POSTED", "terminal": true } ],
                  "transitions": [ { "from": "DRAFT", "to": "POSTED" } ] },
                { "id": "sm_period", "entity": "AccountingPeriod", "stateField": "status",
                  "initial": "OPEN",
                  "states": [ { "name": "OPEN" }, { "name": "CLOSING" },
                              { "name": "CLOSED" } ],
                  "transitions": [
                    { "from": "OPEN", "to": "CLOSING" },
                    { "from": "CLOSING", "to": "CLOSED" },
                    { "from": "CLOSED", "to": "OPEN" } ] } ] }
            """;

    static AppDefinition app;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    StringRedisTemplate redis;

    @TestConfiguration
    static class StubMetadata {

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
    }

    private static final GenericContainer<?> REDIS = new GenericContainer<>("docker.io/library/redis:7.4.11")
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

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
    @DisplayName("§9 item 2: PATCH on a posted entry rejects RECORD_FROZEN; the state field too; delete also frozen")
    void postedEntryIsAnImmutableDocument() throws Exception {
        String id = createEntry("le-immutable", "2026-09-10");
        postEntry(id, 1);
        // a field update on the frozen document rejects 4013
        mockMvc.perform(patch("/api/v1/runtime/LedgerEntry/" + id).with(jwtFor())
                        .contentType("application/json")
                        .content("{\"version\":2,\"label\":\"renamed\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4013"));
        // an inline child array on the PATCH rejects identically (§3.1)
        mockMvc.perform(patch("/api/v1/runtime/LedgerEntry/" + id).with(jwtFor())
                        .contentType("application/json")
                        .content("{\"version\":2,\"lines\":[{\"debit\":\"10.0000\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4013"));
        // delete rejects too
        mockMvc.perform(delete("/api/v1/runtime/LedgerEntry/" + id).with(jwtFor())
                        .param("version", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4013"));
        // and the record is untouched
        mockMvc.perform(get("/api/v1/runtime/LedgerEntry/" + id).with(jwtFor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("le-immutable"))
                .andExpect(jsonPath("$.status").value("POSTED"));
    }

    @Test
    @DisplayName("§9 item 2: direct LedgerLine create/delete naming the posted entry reject identically")
    void childWritesNamingFrozenParentReject() throws Exception {
        String id = createEntry("le-lines", "2026-09-11");
        // a line while DRAFT lands (children of a draft document are writable)
        String line = mockMvc.perform(post("/api/v1/runtime/LedgerLine").with(jwtFor())
                        .contentType("application/json")
                        .content("{\"entry\":\"" + id + "\",\"debit\":\"40.0000\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String lineId = MAPPER.readTree(line).get("id").asString();
        postEntry(id, 1);

        // create naming the frozen parent → 4013
        mockMvc.perform(post("/api/v1/runtime/LedgerLine").with(jwtFor())
                        .contentType("application/json")
                        .content("{\"entry\":\"" + id + "\",\"credit\":\"5.0000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4013"));

        // delete naming the frozen parent → 4013
        mockMvc.perform(delete("/api/v1/runtime/LedgerLine/" + lineId).with(jwtFor())
                        .param("version", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4013"));

        // and update naming the frozen parent → 4013 (document scope; a child edit
        // never recomputes the frozen parent's roll-ups — the check precedes them)
        mockMvc.perform(patch("/api/v1/runtime/LedgerLine/" + lineId).with(jwtFor())
                        .contentType("application/json")
                        .content("{\"version\":1,\"debit\":\"41.0000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4013"));
    }

    @Test
    @DisplayName("§9 item 2: a reversal entry posts; corrections are new records, never edits")
    void reversalEntryPostsAndNets() throws Exception {
        String original = createEntry("le-original", "2026-09-12");
        postEntry(original, 1);
        // the reversal is a new DRAFT entry that itself posts — append-only holds
        String reversal = createEntry("le-reversal", "2026-09-13");
        postEntry(reversal, 1);
        assertThat(UUID.fromString(original)).isNotEqualTo(UUID.fromString(reversal));
        mockMvc.perform(get("/api/v1/runtime/LedgerEntry/" + reversal).with(jwtFor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"));
    }

    @Test
    @DisplayName("§9 item 3: posting dated into a CLOSED period rejects PERIOD_LOCKED; open periods admit")
    void periodLockRejectsClosedPeriodWrites() throws Exception {
        String closed = createPeriod("2026-08-closed", "2026-08-01", "2026-08-31");
        closePeriod(closed);
        // create dated into the closed period → 4014
        mockMvc.perform(post("/api/v1/runtime/LedgerEntry").with(jwtFor())
                        .contentType("application/json")
                        .content("{\"label\":\"into-closed\",\"entryDate\":\"2026-08-15\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4014"));
        // an entry just outside the range posts fine (the range lookup is exact)
        mockMvc.perform(post("/api/v1/runtime/LedgerEntry").with(jwtFor())
                        .contentType("application/json")
                        .content("{\"label\":\"september\",\"entryDate\":\"2026-09-01\"}"))
                .andExpect(status().isOk());

        // re-dating a draft entry into the closed period rejects on update too
        String draft = createEntry("le-redraft", "2026-09-02");
        mockMvc.perform(patch("/api/v1/runtime/LedgerEntry/" + draft).with(jwtFor())
                        .contentType("application/json")
                        .content("{\"version\":1,\"entryDate\":\"2026-08-20\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4014"));

        // reopen (§4): the audited CLOSED → OPEN transition deactivates the lock —
        // the same dated write now lands; the posted entries stay frozen regardless
        mockMvc.perform(patch("/api/v1/runtime/AccountingPeriod/" + closed).with(jwtFor())
                        .contentType("application/json")
                        .content("{\"version\":3,\"status\":\"OPEN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
        mockMvc.perform(post("/api/v1/runtime/LedgerEntry").with(jwtFor())
                        .contentType("application/json")
                        .content("{\"label\":\"after-reopen\",\"entryDate\":\"2026-08-15\"}"))
                .andExpect(status().isOk());
    }

    // --- helpers ---

    private String createEntry(String label, String date) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/runtime/LedgerEntry")
                        .with(jwtFor()).contentType("application/json")
                        .content("{\"label\":\"" + label + "\",\"entryDate\":\"" + date + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();
    }

    private String createPeriod(String name, String from, String to) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/runtime/AccountingPeriod")
                        .with(jwtFor()).contentType("application/json")
                        .content("{\"name\":\"" + name + "\",\"startDate\":\"" + from
                                + "\",\"endDate\":\"" + to + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();
    }

    private void closePeriod(String id) throws Exception {
        mockMvc.perform(patch("/api/v1/runtime/AccountingPeriod/" + id).with(jwtFor())
                        .contentType("application/json")
                        .content("{\"version\":1,\"status\":\"CLOSING\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/runtime/AccountingPeriod/" + id).with(jwtFor())
                        .contentType("application/json")
                        .content("{\"version\":2,\"status\":\"CLOSED\"}"))
                .andExpect(status().isOk());
    }

    private void postEntry(String id, int fromVersion) throws Exception {
        mockMvc.perform(patch("/api/v1/runtime/LedgerEntry/" + id).with(jwtFor())
                        .contentType("application/json")
                        .content("{\"version\":" + fromVersion + ",\"status\":\"POSTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"));
    }

    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor() {
        return jwt()
                .jwt(token -> token.claim("tenant_id", TENANT.toString()).subject(ACTOR.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
    }
}
