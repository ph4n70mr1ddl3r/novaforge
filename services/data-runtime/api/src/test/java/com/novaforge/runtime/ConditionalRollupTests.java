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
 * The PHASE-7 §3.5/§3.6 harvests end to end (the G-15/G-5 gap-log closes):
 * <ul>
 *   <li>{@code SUM(movements.qty WHERE status = 'POSTED')} counts only posted
 *       movements — a DRAFT row never moves the parent roll-up, its own transition
 *       does, and a delete recomputes again (both aggregation paths: the store-side
 *       recompute on standalone child writes, the in-memory filter over inline
 *       children at create);</li>
 *   <li>the query DSL's {@code id}/{@code version} leaves filter and sort like
 *       authored fields — canonical values parse at the door, malformed values
 *       reject VALIDATION_FAILED, other reserved names stay rejected.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConditionalRollupTests extends PostgresTestBase {

    static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID ACTOR = UUID.fromString("33333333-3333-4333-8333-333333333333");
    static final UUID APP_ID = UUID.fromString("88888888-8888-4888-8888-888888888888");

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    static final String APP_JSON = """
            { "apiName": "Stock",
              "entities": [
                { "apiName": "Item",
                  "displayField": "name",
                  "fields": [
                    { "apiName": "name", "type": "text", "required": true },
                    { "apiName": "qtyOnHand", "type": "decimal", "precision": 18, "scale": 4,
                      "rollup": "SUM(movements.qty WHERE status = 'POSTED')" },
                    { "apiName": "receivedQty", "type": "decimal", "precision": 18, "scale": 4,
                      "rollup": "SUM(movements.qty WHERE kind = 'receipt')" },
                    { "apiName": "postedIssueQty", "type": "decimal", "precision": 18, "scale": 4,
                      "rollup": "SUM(movements.qty WHERE kind = 'issue' AND status = 'POSTED')" } ],
                  "relationships": [
                    { "apiName": "movements", "type": "child", "target": "Movement",
                      "cascadeDelete": true } ] },
                { "apiName": "Movement",
                  "fields": [
                    { "apiName": "item", "type": "lookup", "target": "Item", "required": true },
                    { "apiName": "kind", "type": "enum",
                      "values": ["receipt", "issue"], "required": true },
                    { "apiName": "qty", "type": "decimal", "precision": 18, "scale": 4,
                      "required": true },
                    { "apiName": "status", "type": "enum",
                      "values": ["DRAFT", "POSTED"] } ] } ],
              "stateMachines": [
                { "id": "sm_movement", "entity": "Movement", "stateField": "status",
                  "initial": "DRAFT",
                  "states": [ { "name": "DRAFT" }, { "name": "POSTED" } ],
                  "transitions": [ { "from": "DRAFT", "to": "POSTED" } ] } ] }
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
                    List.of(new MetadataClient.PublishedApp(APP_ID, "Stock", 1)));
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
    @DisplayName("§3.5: only POSTED movements move qtyOnHand; the transition and the delete each recompute")
    void conditionalRollupRecomputesOnChildWrites() throws Exception {
        String item = createItem("item-store-path");
        // A DRAFT receipt lands but never counts as on-hand...
        String first = createMovement(item, "receipt", "10");
        assertItemRollups(item, "0", "10", "0");
        // ...its own DRAFT->POSTED transition is what moves it
        patchMovement(first, 1, "POSTED");
        assertItemRollups(item, "10", "10", "0");

        // A second movement stays split by kind AND status (multi-leaf AND)
        String second = createMovement(item, "issue", "5");
        assertItemRollups(item, "10", "10", "0");
        patchMovement(second, 1, "POSTED");
        assertItemRollups(item, "15", "10", "5");

        // Delete recomputes: removing the posted receipt drops it back out
        deleteMovement(first, 2);
        assertItemRollups(item, "5", "0", "5");
    }

    @Test
    @DisplayName("§3.5: the inline-create path filters the in-memory child set by the same leaves")
    void inlineChildrenFilterInMemory() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/runtime/Item")
                        .with(jwtFor()).contentType("application/json")
                        .content("""
                                { "name": "item-inline",
                                  "movements": [
                                    { "kind": "receipt", "qty": 7 },
                                    { "kind": "issue", "qty": 3 } ] }
                                """))
                .andExpect(status().isOk()).andReturn();
        var item = MAPPER.readTree(created.getResponse().getContentAsString());
        // both rows ride in pinned at DRAFT — nothing posts yet...
        assertThat(item.get("qtyOnHand").asText()).isEqualTo("0");
        assertThat(item.get("postedIssueQty").asText()).isEqualTo("0");
        // ...but the kind-only condition already sees the raw receipt row
        assertThat(item.get("receivedQty").asText()).isEqualTo("7");
    }

    @Test
    @DisplayName("§3.6: id/version leaves filter and sort; malformed values reject; reserved names stay closed")
    void systemFieldLeaves() throws Exception {
        String item = createItem("item-leaves");
        String kept = createMovement(item, "receipt", "2");
        String moved = createMovement(item, "issue", "1");
        patchMovement(moved, 1, "POSTED");   // now at version 2

        // identity leaf: exact row by uuid
        var found = mockMvc.perform(get("/api/v1/runtime/Movement")
                        .with(jwtFor())
                        .param("filter", "{\"field\":\"id\",\"op\":\"eq\",\"value\":\"" + moved + "\"}"))
                .andExpect(status().isOk());
        var rows = MAPPER.readTree(found.andReturn().getResponse().getContentAsString());
        assertThat(rows.get("rows").size()).isEqualTo(1);
        assertThat(rows.get("rows").get(0).get("id").asString()).isEqualTo(moved);

        // version leaf, scoped to this item (the class shares one database): the
        // patched row sits above v1
        var versions = mockMvc.perform(get("/api/v1/runtime/Movement")
                        .with(jwtFor())
                        .param("filter", "{\"and\":[{\"field\":\"item\",\"op\":\"eq\","
                                + "\"value\":\"" + item + "\"},"
                                + "{\"field\":\"version\",\"op\":\"gt\",\"value\":1}]}")
                        .param("sort", "[{\"field\":\"version\",\"dir\":\"desc\"}]"))
                .andExpect(status().isOk()).andReturn();
        var sorted = MAPPER.readTree(versions.getResponse().getContentAsString());
        assertThat(sorted.get("rows").size()).isEqualTo(1);
        assertThat(sorted.get("rows").get(0).get("id").asString()).isEqualTo(moved);

        // malformed canonical values reject with field scope, never a downstream 500
        mockMvc.perform(get("/api/v1/runtime/Movement")
                        .with(jwtFor())
                        .param("filter", "{\"field\":\"id\",\"op\":\"eq\",\"value\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4000"));
        // every other reserved name stays rejected
        mockMvc.perform(get("/api/v1/runtime/Movement")
                        .with(jwtFor())
                        .param("filter", "{\"field\":\"updatedAt\",\"op\":\"eq\",\"value\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4000"));
    }

    // --- helpers ---

    private String createItem(String name) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/runtime/Item").with(jwtFor())
                        .contentType("application/json").content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();
    }

    private String createMovement(String item, String kind, String qty) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/runtime/Movement").with(jwtFor())
                        .contentType("application/json")
                        .content("{\"item\":\"" + item + "\",\"kind\":\"" + kind
                                + "\",\"qty\":" + qty + "}"))
                .andExpect(status().isOk()).andReturn();
        return MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();
    }

    private void patchMovement(String id, int version, String status) throws Exception {
        mockMvc.perform(patch("/api/v1/runtime/Movement/" + id).with(jwtFor())
                        .contentType("application/json")
                        .content("{\"version\":" + version + ",\"status\":\"" + status + "\"}"))
                .andExpect(status().isOk());
    }

    private void deleteMovement(String id, int version) throws Exception {
        mockMvc.perform(delete("/api/v1/runtime/Movement/" + id).with(jwtFor())
                        .param("version", String.valueOf(version)))
                .andExpect(status().isNoContent());
    }

    private void assertItemRollups(String item, String onHand, String received, String issued)
            throws Exception {
        var record = MAPPER.readTree(mockMvc.perform(
                        get("/api/v1/runtime/Item/" + item).with(jwtFor()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(record.get("qtyOnHand").asText()).isEqualTo(onHand);
        assertThat(record.get("receivedQty").asText()).isEqualTo(received);
        assertThat(record.get("postedIssueQty").asText()).isEqualTo(issued);
    }

    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor() {
        return jwt()
                .jwt(token -> token.claim("tenant_id", TENANT.toString()).subject(ACTOR.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
    }
}
