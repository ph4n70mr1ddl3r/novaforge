package com.novaforge.runtime;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novaforge.metadata.DefinitionParser;
import com.novaforge.runtime.engine.hook.ApprovalClient;
import com.novaforge.runtime.engine.metadata.MetadataClient;
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
 * The approval journey (PHASE-4 §4, the exit scenario's core): a submit flow
 * transitions the record, suspends at {@code requestApproval} — the triggering write
 * commits, never held — and a resolution re-enters the engine through the internal
 * resume surface: approve continues after the step, reject runs the step's own
 * onReject subgraph. SoD failures render onto the write path (4011) and abort a
 * beforeSave flow.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApprovalFlowTests extends PostgresTestBase {

    static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID ACTOR = UUID.fromString("33333333-3333-4333-8333-333333333333");
    static final UUID APP_ID = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    static final String APP_JSON = """
            { "apiName": "Purch",
              "entities": [
                { "apiName": "PurchaseOrder",
                  "displayField": "label",
                  "fields": [
                    { "apiName": "label", "type": "text", "required": true },
                    { "apiName": "total", "type": "decimal", "precision": 18, "scale": 4 },
                    { "apiName": "status", "type": "enum",
                      "values": ["DRAFT", "SUBMITTED", "APPROVED", "REJECTED"] } ],
                  "hooks": [
                    { "name": "submit", "trigger": "beforeSave", "flow":
                      { "id": "b1", "op": "branch", "params": { "guard": "label == 'submit'" },
                        "onTrue": "t1",
                        "body": { "id": "t1", "op": "transitionState",
                                   "params": { "to": "SUBMITTED" }, "next": "a1",
                          "body": { "id": "a1", "op": "requestApproval",
                                     "params": { "approvers": "Purch.manager", "mode": "any" },
                                     "next": "s2",
                            "body": { "id": "r1", "op": "transitionState",
                                       "params": { "to": "REJECTED" },
                              "body": { "id": "s2", "op": "transitionState",
                                         "params": { "to": "APPROVED" } } } } } } } ] } ],
              "stateMachines": [
                { "id": "sm_po", "entity": "PurchaseOrder", "stateField": "status",
                  "initial": "DRAFT",
                  "states": [ { "name": "DRAFT" }, { "name": "SUBMITTED" },
                              { "name": "APPROVED" }, { "name": "REJECTED", "terminal": true } ],
                  "transitions": [
                    { "from": "DRAFT", "to": "SUBMITTED", "guard": "total > 0" },
                    { "from": "SUBMITTED", "to": "APPROVED" },
                    { "from": "SUBMITTED", "to": "REJECTED" } ] } ] }
            """;

    /** Suspensions the stub observed (the Workflow Service's stand-in). */
    static final List<ApprovalClient.Suspension> SUSPENSIONS = new CopyOnWriteArrayList<>();

    /** When set, the stub answers with the Workflow Service's SoD rejection. */
    static volatile boolean sodOnNext;

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
            var app = DefinitionParser.parseApp(APP_JSON);
            Mockito.when(client.publishedApps()).thenAnswer(inv ->
                    List.of(new MetadataClient.PublishedApp(APP_ID, "Purch", 1)));
            Mockito.when(client.publishedBundle(Mockito.any(UUID.class))).thenAnswer(inv ->
                    new MetadataClient.PublishedBundle(1, app));
            return client;
        }

        @Bean
        @Primary
        ApprovalClient approvalClient() {
            return suspension -> {
                if (sodOnNext) {
                    sodOnNext = false;
                    throw new com.novaforge.common.error.PlatformException(
                            com.novaforge.common.error.PlatformErrorCode.SOD_VIOLATION,
                            "the initiating actor is the only candidate approver (§4)");
                }
                SUSPENSIONS.add(suspension);
            };
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
        materializer.apply(DefinitionParser.parseApp(APP_JSON));
    }

    @Test
    @DisplayName("submit suspends at requestApproval — write commits; approve resumes to APPROVED (§4)")
    void approvalJourneyApprove() throws Exception {
        String id = createOrder(100);
        SUSPENSIONS.clear();

        // the submit update: the flow transitions to SUBMITTED then suspends —
        // the write itself commits with the transition applied
        mockMvc.perform(patch("/api/v1/runtime/PurchaseOrder/" + id).with(jwtFor())
                        .contentType("application/json")
                        .content("{\"version\":1,\"label\":\"submit\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        assertThatOneSuspensionFor(id);
        ApprovalClient.Suspension suspension = SUSPENSIONS.getFirst();
        org.assertj.core.api.Assertions.assertThat(suspension.approversRole())
                .isEqualTo("Purch.manager");
        org.assertj.core.api.Assertions.assertThat(suspension.afterStep()).isEqualTo("s2");
        org.assertj.core.api.Assertions.assertThat(suspension.initiatingActor()).isEqualTo(ACTOR);

        // the manager's approval re-enters the engine through the internal surface
        mockMvc.perform(post("/api/v1/hooks/resume").with(serviceJwt())
                        .contentType("application/json")
                        .content(resumeBody(suspension, true)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/runtime/PurchaseOrder/" + id).with(jwtFor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    @DisplayName("reject routes the step's own onReject subgraph (§4)")
    void approvalJourneyReject() throws Exception {
        String id = createOrder(100);
        SUSPENSIONS.clear();
        mockMvc.perform(patch("/api/v1/runtime/PurchaseOrder/" + id).with(jwtFor())
                        .contentType("application/json")
                        .content("{\"version\":1,\"label\":\"submit\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
        ApprovalClient.Suspension suspension = SUSPENSIONS.getFirst();

        mockMvc.perform(post("/api/v1/hooks/resume").with(serviceJwt())
                        .contentType("application/json")
                        .content(resumeBody(suspension, false)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/runtime/PurchaseOrder/" + id).with(jwtFor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    @DisplayName("SoD failure renders onto the write path and aborts the beforeSave flow (§4)")
    void sodViolationAbortsTheWrite() throws Exception {
        String id = createOrder(100);
        sodOnNext = true;
        // the submit flow aborts before persist: the record stays as it was
        mockMvc.perform(patch("/api/v1/runtime/PurchaseOrder/" + id).with(jwtFor())
                        .contentType("application/json")
                        .content("{\"version\":1,\"label\":\"submit\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4011"));
        mockMvc.perform(get("/api/v1/runtime/PurchaseOrder/" + id).with(jwtFor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("po"));
    }

    @Test
    @DisplayName("the resume surface is service-client only (§13)")
    void resumeGate() throws Exception {
        String id = createOrder(100);
        mockMvc.perform(post("/api/v1/hooks/resume").with(jwtFor())
                        .contentType("application/json")
                        .content(resumeBody(stubSuspension(id), true)))
                .andExpect(status().isForbidden());
    }

    // --- helpers ---

    private String createOrder(double total) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/runtime/PurchaseOrder")
                        .with(jwtFor()).contentType("application/json")
                        .content("{\"label\":\"po\",\"total\":" + total + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        return MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();
    }

    private static String resumeBody(ApprovalClient.Suspension s, boolean approved) {
        return MAPPER.writeValueAsString(Map.of(
                "tenantId", s.tenantId().toString(),
                "app", s.appApiName(),
                "entityApiName", s.entityApiName(),
                "recordId", s.recordId().toString(),
                "hook", String.valueOf(s.hookName()),
                "afterStep", s.afterStep() == null ? "" : s.afterStep(),
                "onReject", s.onReject() == null ? "" : MAPPER.writeValueAsString(s.onReject()),
                "approved", approved));
    }

    private static ApprovalClient.Suspension stubSuspension(String recordId) {
        return new ApprovalClient.Suspension(TENANT, "Purch", "PurchaseOrder",
                "Purch.PurchaseOrder", UUID.fromString(recordId), "submit", "a1", "s2",
                null, "Purch.manager", null, "any", null, null, ACTOR);
    }

    private void assertThatOneSuspensionFor(String recordId) {
        org.assertj.core.api.Assertions
                .assertThat(SUSPENSIONS.stream()
                        .filter(s -> s.recordId().toString().equals(recordId)).count())
                .isEqualTo(1);
    }

    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor() {
        return jwt()
                .jwt(token -> token.claim("tenant_id", TENANT.toString()).subject(ACTOR.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
    }

    /** The platform service client (azp) — the resume surface's gate. */
    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor serviceJwt() {
        return jwt()
                .jwt(token -> token.claim("azp", "novaforge-runtime")
                        .subject("service-account-novaforge-runtime"))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
    }
}
