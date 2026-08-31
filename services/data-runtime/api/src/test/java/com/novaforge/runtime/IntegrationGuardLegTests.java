package com.novaforge.runtime;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.runtime.engine.metadata.MetadataClient;
import com.novaforge.runtime.storage.materializer.Materializer;
import com.novaforge.security.ServiceClientGate;
import com.novaforge.testsupport.PostgresTestBase;
import java.util.List;
import java.util.Map;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The doom-guards' post-hook leg on the INTEGRATION doors (nineteenth pass): the
 * eighteenth pass added the after-hooks re-check to the user update door only —
 * the integration create door had no post-hook leg at all (a webhook/import hook
 * re-dating a record into a CLOSED period committed), and the integration update
 * door re-ran the period lock but never the parent-freeze guard (a hook re-pointing
 * a lookup at a frozen parent committed). Both doors now enforce the landing state
 * exactly as the user doors do; these pins hold them there.
 */
@SpringBootTest
@AutoConfigureMockMvc
class IntegrationGuardLegTests extends PostgresTestBase {

    static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID APP_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");

    /**
     * One period-locked entity whose beforeSave hook re-dates every write to the
     * 15th, and one freeze-bound parent with a child whose beforeSave hook re-points
     * its lookup at whatever {@code candidateDoc} carries — the runtime-controlled
     * mutation each pin needs AFTER the pre-hook guards have already passed.
     */
    static final String APP_JSON = """
            { "apiName": "Ledger3",
              "entities": [
                { "apiName": "FiscalPeriod",
                  "displayField": "name",
                  "fields": [
                    { "apiName": "name", "type": "text", "required": true },
                    { "apiName": "startDate", "type": "date", "required": true },
                    { "apiName": "endDate", "type": "date", "required": true },
                    { "apiName": "status", "type": "enum",
                      "values": ["OPEN", "CLOSED"] } ] },
                { "apiName": "Txn",
                  "displayField": "label",
                  "periodLock": { "entity": "FiscalPeriod", "dateField": "postedAt" },
                  "fields": [
                    { "apiName": "label", "type": "text", "required": true },
                    { "apiName": "postedAt", "type": "date", "required": true } ],
                  "hooks": [
                    { "name": "redate", "trigger": "beforeSave",
                      "flow": { "id": "r1", "op": "setField",
                                "params": { "field": "postedAt", "expression": "'2026-08-15'" } } } ] },
                { "apiName": "Doc",
                  "displayField": "title",
                  "freezeOnTerminal": true,
                  "fields": [
                    { "apiName": "title", "type": "text", "required": true },
                    { "apiName": "status", "type": "enum", "values": ["DRAFT", "POSTED"] } ] },
                { "apiName": "DocLine",
                  "displayField": "note",
                  "fields": [
                    { "apiName": "note", "type": "text", "required": true },
                    { "apiName": "doc", "type": "lookup", "target": "Doc", "required": true },
                    { "apiName": "candidateDoc", "type": "text" } ],
                  "hooks": [
                    { "name": "repoint", "trigger": "beforeSave",
                      "flow": { "id": "p1", "op": "setField",
                                "params": { "field": "doc", "expression": "candidateDoc" } } } ] } ],
              "stateMachines": [
                { "id": "sm_period", "entity": "FiscalPeriod", "stateField": "status",
                  "initial": "OPEN",
                  "states": [ { "name": "OPEN" }, { "name": "CLOSED", "terminal": true } ],
                  "transitions": [ { "from": "OPEN", "to": "CLOSED" } ] },
                { "id": "sm_doc", "entity": "Doc", "stateField": "status",
                  "initial": "DRAFT",
                  "states": [ { "name": "DRAFT" }, { "name": "POSTED", "terminal": true } ],
                  "transitions": [ { "from": "DRAFT", "to": "POSTED" } ] } ] }
            """;

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    static AppDefinition app;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @TestConfiguration
    static class StubMetadata {

        @Bean
        @Primary
        MetadataClient metadataClient() {
            MetadataClient client = Mockito.mock(MetadataClient.class);
            app = DefinitionParser.parseApp(APP_JSON);
            Mockito.when(client.publishedApps()).thenAnswer(inv ->
                    List.of(new MetadataClient.PublishedApp(APP_ID, "Ledger3", 1)));
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
    @DisplayName("an integration create hook-dated into a CLOSED period rejects PERIOD_LOCKED")
    void integrationCreateHookDatedIntoClosedPeriodRejects() throws Exception {
        // no period rows exist yet: a September-dated txn rides the hook's re-date to
        // the 15th of a month with no period — nothing locks, the write commits
        writeOk(Map.of("op", "create", "entity", "Txn",
                "record", Map.of("label", "t-open", "postedAt", "2026-09-10")));

        // an August period, then closed: the SAME September-dated create is now
        // doomed only through its hook — the pre-hook guard sees September (no
        // covering period) and passes; the re-date lands in CLOSED August
        String periodId = writeOkForId(Map.of("op", "create", "entity", "FiscalPeriod",
                "record", Map.of("name", "aug", "startDate", "2026-08-01",
                        "endDate", "2026-08-31")));
        writeOk(Map.of("op", "update", "entity", "FiscalPeriod", "id", periodId,
                "version", 1, "record", Map.of("status", "CLOSED")));

        mockMvc.perform(post("/api/v1/hooks/integration/write").with(serviceJwt())
                        .contentType("application/json")
                        .content(MAPPER.writeValueAsString(Map.of("tenantId", TENANT.toString(),
                                "items", List.of(Map.of("op", "create", "entity", "Txn",
                                        "record", Map.of("label", "t-doomed",
                                                "postedAt", "2026-09-10")))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomes[0].status").value("error"))
                .andExpect(jsonPath("$.outcomes[0].code").value("4014"));
        // nothing landed: the doomed create left no row behind
        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("SELECT count(*) FROM rec_txn WHERE "
                        + "data->>'label' = 't-doomed'", Integer.class)).isZero();
    }

    @Test
    @DisplayName("an integration update whose hook re-points a frozen parent rejects RECORD_FROZEN")
    void integrationUpdateHookRepointedFrozenParentRejects() throws Exception {
        String openDoc = writeOkForId(Map.of("op", "create", "entity", "Doc",
                "record", Map.of("title", "d-open")));
        String frozenDoc = writeOkForId(Map.of("op", "create", "entity", "Doc",
                "record", Map.of("title", "d-frozen")));
        // POST the second doc: terminal state + freezeOnTerminal — an immutable document
        writeOk(Map.of("op", "update", "entity", "Doc", "id", frozenDoc,
                "version", 1, "record", Map.of("status", "POSTED")));

        // the line points at the open doc (its hook re-points to the same target —
        // a no-op re-point, the write commits)
        String lineId = writeOkForId(Map.of("op", "create", "entity", "DocLine",
                "record", Map.of("note", "l1", "doc", openDoc,
                        "candidateDoc", openDoc)));

        // the doomed update: the caller's body touches neither the lookup nor
        // anything frozen — the HOOK re-points doc at the frozen parent after the
        // pre-hook guard already passed on the open parent
        mockMvc.perform(post("/api/v1/hooks/integration/write").with(serviceJwt())
                        .contentType("application/json")
                        .content(MAPPER.writeValueAsString(Map.of("tenantId", TENANT.toString(),
                                "items", List.of(Map.of("op", "update", "entity", "DocLine",
                                        "id", lineId, "version", 1,
                                        "record", Map.of("note", "l1-edited",
                                                "candidateDoc", frozenDoc)))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomes[0].status").value("error"))
                .andExpect(jsonPath("$.outcomes[0].code").value("4013"));
        // the landing state never moved: the line still points at the open doc
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT data->>'doc' FROM rec_doc_line WHERE id = ?",
                String.class, UUID.fromString(lineId))).isEqualTo(openDoc);
    }

    /** One integration write item, asserted ok — returns the created record's id. */
    private String writeOkForId(Map<?, ?> item) throws Exception {
        MvcResult result = writeOk(item);
        return MAPPER.readTree(result.getResponse().getContentAsString())
                .path("outcomes").get(0).path("record").path("id").asString();
    }

    /** One integration write item, asserted ok — returns the raw outcome envelope. */
    private MvcResult writeOk(Map<?, ?> item) throws Exception {
        return mockMvc.perform(post("/api/v1/hooks/integration/write").with(serviceJwt())
                        .contentType("application/json")
                        .content(MAPPER.writeValueAsString(Map.of("tenantId", TENANT.toString(),
                                "items", List.of(item)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomes[0].status").value("ok"))
                .andReturn();
    }

    /** The platform service client (azp) — the internal surfaces' gate. */
    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor serviceJwt() {
        return jwt().jwt(token -> token.claim("azp", ServiceClientGate.CLIENT_ID)
                        .subject("service-account-novaforge-runtime"))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
    }
}
