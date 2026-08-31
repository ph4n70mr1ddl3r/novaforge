package com.novaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.runtime.engine.metadata.EntityResolver;
import com.novaforge.runtime.engine.metadata.MetadataClient;
import com.novaforge.runtime.storage.materializer.Materializer;
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
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.json.JsonMapper;

/**
 * The page-model {@code runFlow} action's runtime leg (PHASE-2 §4 / PHASE-3 §8): a
 * named flow hook runs on demand against the record's current state — flow hooks
 * only (script hooks stay write-path caller-context, ADR-003 #2), the per-app
 * system principal executing, the initiating actor recorded, and the caller's READ
 * grant + sharing visibility gating the surface (the button renders on a page the
 * user can see; the endpoint refuses otherwise).
 */
@SpringBootTest(properties = {"novaforge.events.relay-interval-ms=3600000"})
@AutoConfigureMockMvc
class ManualHookTests extends PostgresTestBase {

    static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID ACTOR = UUID.fromString("33333333-3333-4333-8333-333333333333");
    static final UUID OTHER_ACTOR = UUID.fromString("44444444-4444-4444-8444-444444444444");
    static final UUID APP_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    static final String APP_JSON = """
            { "apiName": "Desk",
              "entities": [
                { "apiName": "DeskTicket",
                  "displayField": "subject",
                  "fields": [
                    { "apiName": "subject", "type": "text", "required": true },
                    { "apiName": "status", "type": "enum", "values": ["NEW", "OPEN"] } ],
                  "hooks": [
                    { "name": "fileNote", "trigger": "afterSave", "flow":
                      { "id": "n1", "op": "createRecord",
                        "params": { "entity": "DeskNote",
                                    "template": { "about": "${id}", "severity": "${status}" } } } },
                    { "name": "greet", "trigger": "beforeSave", "flow":
                      { "id": "g1", "op": "setField",
                        "params": { "field": "subject", "expression": "upper(subject)" } } },
                    { "name": "shouty", "trigger": "afterSave",
                      "script": { "language": "js", "source": "$log.info('saved')" } } ] },
                { "apiName": "DeskNote",
                  "fields": [
                    { "apiName": "about", "type": "text" },
                    { "apiName": "severity", "type": "text" } ] } ] }
            """;

    static AppDefinition app;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    EntityResolver resolver;

    @TestConfiguration
    static class StubMetadata {

        @Bean
        @Primary
        MetadataClient metadataClient() {
            MetadataClient client = Mockito.mock(MetadataClient.class);
            app = DefinitionParser.parseApp(APP_JSON);
            Mockito.when(client.publishedApps()).thenAnswer(inv ->
                    List.of(new MetadataClient.PublishedApp(APP_ID, "Desk", 1)));
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
        materializer.apply(DefinitionParser.parseApp(APP_JSON));
    }

    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor() {
        return jwt()
                .jwt(token -> token.claim("tenant_id", TENANT.toString()).subject(ACTOR.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
    }

    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor strangerJwt() {
        return jwt()
                .jwt(token -> token.claim("tenant_id", TENANT.toString()).subject(OTHER_ACTOR.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
    }

    @Test
    @DisplayName("runFlow executes a named flow hook on demand — authored trigger is irrelevant")
    void runsNamedFlowHookOnDemand() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/runtime/DeskTicket").with(jwtFor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"svc-1\",\"status\":\"NEW\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String id = MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();

        // the afterSave-authored hook fires manually: its createRecord lands a Note
        mockMvc.perform(post("/api/v1/runtime/DeskTicket/{id}/hooks/fileNote", id).with(jwtFor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));

        String page = java.net.URLEncoder.encode("{\"size\":50}", java.nio.charset.StandardCharsets.UTF_8);
        MvcResult notes = mockMvc.perform(get("/api/v1/runtime/DeskNote").with(jwtFor()).param("page", page))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(notes.getResponse().getContentAsString())
                .contains("\"about\":\"" + id + "\"")
                .contains("\"severity\":\"NEW\"");
    }

    @Test
    @DisplayName("script hooks reject with guidance — runFlow targets flow hooks (ADR-003 #2)")
    void scriptHooksRejectOnTheManualSurface() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/runtime/DeskTicket").with(jwtFor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"svc-2\",\"status\":\"NEW\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String id = MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();

        mockMvc.perform(post("/api/v1/runtime/DeskTicket/{id}/hooks/shouty", id).with(jwtFor()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4000"))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("caller-context")));
    }

    @Test
    @DisplayName("unknown hooks 404; the caller's READ grant gates the surface (matrix fail-closed)")
    void unknownHookAndAuthorization() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/runtime/DeskTicket").with(jwtFor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"svc-3\",\"status\":\"NEW\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String id = MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();

        // unknown hook name → NOT_FOUND, never a silent no-op
        mockMvc.perform(post("/api/v1/runtime/DeskTicket/{id}/hooks/ghost", id).with(jwtFor()))
                .andExpect(status().isNotFound());

        // an actor with no grant on the entity cannot run flows against its records
        mockMvc.perform(get("/api/v1/runtime/DeskTicket/" + id).with(strangerJwt()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/runtime/DeskTicket/{id}/hooks/fileNote", id).with(strangerJwt()))
                .andExpect(status().isForbidden());
    }
}
