package com.novaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.json.JsonMapper;

/**
 * State machines on the write path (PHASE-4 §3, §14 item 2): create pins the initial
 * state, updates require a listed transition with a passing guard (else 400
 * STATE_TRANSITION), terminal states freeze the state field but not the record, and
 * the {@code transitionState} primitive rides the same check — no bypass for flows.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StateMachineTests extends PostgresTestBase {

    static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID ACTOR = UUID.fromString("33333333-3333-4333-8333-333333333333");
    static final UUID APP_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    static final String APP_JSON = """
            { "apiName": "Purch",
              "entities": [
                { "apiName": "PurchaseOrder",
                  "displayField": "label",
                  "fields": [
                    { "apiName": "label", "type": "text", "required": true },
                    { "apiName": "total", "type": "decimal", "precision": 18, "scale": 4 },
                    { "apiName": "urgent", "type": "boolean" },
                    { "apiName": "status", "type": "enum",
                      "values": ["DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "POSTED"] } ],
                  "hooks": [
                    { "name": "submitWhenUrgent", "trigger": "beforeSave", "flow":
                      { "id": "b1", "op": "branch", "params": { "guard": "urgent == true" },
                        "onTrue": "s1", "onFalse": null } } ] },
                { "apiName": "Fulfill",
                  "fields": [
                    { "apiName": "po", "type": "lookup", "target": "PurchaseOrder",
                      "required": true } ],
                  "hooks": [
                    { "name": "approve", "trigger": "afterSave", "flow":
                      { "id": "a1", "op": "updateRecord",
                        "params": { "entity": "PurchaseOrder", "recordId": "${po}",
                                    "template": { "status": "APPROVED" } } } } ] } ],
              "stateMachines": [
                { "id": "sm_po", "entity": "PurchaseOrder", "stateField": "status",
                  "initial": "DRAFT",
                  "states": [
                    { "name": "DRAFT" }, { "name": "SUBMITTED" }, { "name": "APPROVED" },
                    { "name": "REJECTED", "terminal": true },
                    { "name": "POSTED", "terminal": true } ],
                  "transitions": [
                    { "from": "DRAFT", "to": "SUBMITTED", "guard": "total > 0" },
                    { "from": "SUBMITTED", "to": "APPROVED" },
                    { "from": "SUBMITTED", "to": "REJECTED" },
                    { "from": "APPROVED", "to": "POSTED" } ] } ] }
            """;

    // The submitWhenUrgent hook's branch body — spliced in at parse time so the JSON
    // above stays readable (a branch with a transitionState child).
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
            // splice the transitionState step into the hook's branch
            com.novaforge.metadata.EntityDefinition po = app.entity("PurchaseOrder")
                    .orElseThrow();
            com.novaforge.metadata.HookRule hook = po.hooks().getFirst();
            com.novaforge.metadata.FlowStep branch = new com.novaforge.metadata.FlowStep(
                    "b1", "branch", java.util.Map.of("guard", "urgent == true"),
                    null, "s1", null, new com.novaforge.metadata.FlowStep(
                    "s1", "transitionState", java.util.Map.of("to", "SUBMITTED"),
                    null, null, null, null));
            com.novaforge.metadata.HookRule wired = new com.novaforge.metadata.HookRule(
                    hook.name(), hook.trigger(), branch, null);
            app = new AppDefinition(app.id(), app.apiName(), app.label(), app.labelI18n(),
                    app.description(),
                    java.util.List.of(new com.novaforge.metadata.EntityDefinition(
                            po.id(), po.apiName(), po.label(), po.labelI18n(), po.displayField(),
                            po.module(), po.fields(), po.relationships(), po.validations(),
                            java.util.List.of(wired), po.indexes()),
                            app.entity("Fulfill").orElseThrow()),
                    app.pages(), app.settings(), app.permissionSet(), app.testSuites(),
                    app.stateMachines());
            Mockito.when(client.publishedApps()).thenAnswer(inv ->
                    List.of(new MetadataClient.PublishedApp(APP_ID, "Purch", 1)));
            Mockito.when(client.publishedBundle(Mockito.any(UUID.class))).thenAnswer(inv ->
                    new MetadataClient.PublishedBundle(1, app));
            return client;
        }
    }

    private static final GenericContainer<?> REDIS = new GenericContainer<>("docker.io/library/redis:7.4.11")
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
        materializer.apply(app);
    }

    @Test
    @DisplayName("create pins the initial state; an explicit non-initial value rejects (§3)")
    void createPinsInitial() throws Exception {
        mockMvc.perform(post("/api/v1/runtime/PurchaseOrder").with(jwtFor())
                        .contentType("application/json")
                        .content("{\"label\":\"po-1\",\"total\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));

        mockMvc.perform(post("/api/v1/runtime/PurchaseOrder").with(jwtFor())
                        .contentType("application/json")
                        .content("{\"label\":\"po-2\",\"total\":100,\"status\":\"SUBMITTED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4010"));
    }

    @Test
    @DisplayName("a hook-driven transitionState rides the same check — guard included (§3)")
    void transitionStatePrimitiveEnforced() throws Exception {
        // urgent on create → the hook sets SUBMITTED, but create must land in DRAFT → 4010
        mockMvc.perform(post("/api/v1/runtime/PurchaseOrder").with(jwtFor())
                        .contentType("application/json")
                        .content("{\"label\":\"urgent-create\",\"total\":100,\"urgent\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4010"));

        // urgent on update from DRAFT with total > 0 → the guard passes → SUBMITTED
        String id = createOrder("guard-pass", 100);
        mockMvc.perform(patch("/api/v1/runtime/PurchaseOrder/" + id).with(jwtFor())
                        .contentType("application/json")
                        .content("{\"version\":1,\"urgent\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        // urgent on update with total == 0 → the machine guard fails → 4010
        String zero = createOrder("guard-fail", 0);
        mockMvc.perform(patch("/api/v1/runtime/PurchaseOrder/" + zero).with(jwtFor())
                        .contentType("application/json")
                        .content("{\"version\":1,\"urgent\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4010"))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("guard")));
    }

    @Test
    @DisplayName("human transitions require a listed edge; unlisted rejects (§3)")
    void humanTransitionsChecked() throws Exception {
        String id = createOrder("human", 100);
        // DRAFT → APPROVED is not listed
        mockMvc.perform(patch("/api/v1/runtime/PurchaseOrder/" + id).with(jwtFor())
                        .contentType("application/json")
                        .content("{\"version\":1,\"status\":\"APPROVED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4010"));
        // DRAFT → SUBMITTED is listed, guard total > 0 passes
        mockMvc.perform(patch("/api/v1/runtime/PurchaseOrder/" + id).with(jwtFor())
                        .contentType("application/json")
                        .content("{\"version\":1,\"status\":\"SUBMITTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    @DisplayName("terminal states freeze the state field only — other writes continue (§3, PHASE-7 defers record freezing)")
    void terminalFreezesStateFieldOnly() throws Exception {
        String id = createOrder("terminal", 100);
        transition(id, 1, "SUBMITTED");
        transition(id, 2, "APPROVED");
        transition(id, 3, "POSTED");
        // POSTED is terminal: other fields still writable (freezeOnTerminal is Phase 7)
        mockMvc.perform(patch("/api/v1/runtime/PurchaseOrder/" + id).with(jwtFor())
                        .contentType("application/json")
                        .content("{\"version\":4,\"label\":\"renamed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("renamed"));
        // but the state field admits no transitions from POSTED
        mockMvc.perform(patch("/api/v1/runtime/PurchaseOrder/" + id).with(jwtFor())
                        .contentType("application/json")
                        .content("{\"version\":5,\"status\":\"DRAFT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4010"));
    }

    @Test
    @DisplayName("nested engine writes (hooks' updateRecord) pass the same check (§3)")
    void nestedWritesEnforced() throws Exception {
        String id = createOrder("nested", 100);
        transition(id, 1, "SUBMITTED");
        // Fulfill's afterSave hook updates the PO to APPROVED via updateRecord —
        // SUBMITTED → APPROVED is listed, so the nested write lands.
        mockMvc.perform(post("/api/v1/runtime/Fulfill").with(jwtFor())
                        .contentType("application/json")
                        .content("{\"po\":\"" + id + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/runtime/PurchaseOrder/" + id).with(jwtFor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // and the same nested path rejects an unlisted transition: a Fulfill against
        // an already-POSTED order leaves the PO untouched (the hook failure is
        // recorded for retry, the write itself succeeds).
        String fresh = createOrder("nested-posted", 100);
        transition(fresh, 1, "SUBMITTED");
        transition(fresh, 2, "APPROVED");
        transition(fresh, 3, "POSTED");
        mockMvc.perform(post("/api/v1/runtime/Fulfill").with(jwtFor())
                        .contentType("application/json")
                        .content("{\"po\":\"" + fresh + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/runtime/PurchaseOrder/" + fresh).with(jwtFor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"));
    }

    // --- helpers ---

    private String createOrder(String label, double total) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/runtime/PurchaseOrder")
                        .with(jwtFor()).contentType("application/json")
                        .content("{\"label\":\"" + label + "\",\"total\":" + total + "}"))
                .andExpect(status().isOk()).andReturn();
        return MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();
    }

    private void transition(String id, int fromVersion, String to) throws Exception {
        mockMvc.perform(patch("/api/v1/runtime/PurchaseOrder/" + id).with(jwtFor())
                        .contentType("application/json")
                        .content("{\"version\":" + fromVersion + ",\"status\":\"" + to + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(to));
    }

    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor() {
        return jwt()
                .jwt(token -> token.claim("tenant_id", TENANT.toString()).subject(ACTOR.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
    }
}
