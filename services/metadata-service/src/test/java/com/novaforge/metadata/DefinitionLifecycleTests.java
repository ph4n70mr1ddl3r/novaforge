package com.novaforge.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novaforge.testsupport.PostgresTestBase;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.json.JsonMapper;

/**
 * Definition lifecycle over the real stores (PHASE-1 §9 item 4): save-validation matrix
 * through the API, publish immutability, version list/export round-trip, breaking-change
 * acknowledgment, and the {@code metadata.published} envelope on Redis pub/sub (T4).
 */
@SpringBootTest
@AutoConfigureMockMvc
class DefinitionLifecycleTests extends PostgresTestBase {

    private static final GenericContainer<?> REDIS = new GenericContainer<>("docker.io/library/redis:7.4.11")
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    static {
        // Class-load order: subscription (@BeforeAll) and property registration
        // (DynamicPropertySource) both need the container up first.
        REDIS.start();
    }

    private static final String TENANT = "11111111-1111-4111-8111-111111111111";
    private static final String ACTOR = "33333333-3333-4333-8333-333333333333";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final BlockingQueue<String> PUBLISHED_EVENTS = new LinkedBlockingQueue<>();

    @Autowired
    MockMvc mockMvc;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestBase::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestBase::jdbcUsername);
        registry.add("spring.datasource.password", PostgresTestBase::jdbcPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @BeforeAll
    static void subscribeToPublishChannel() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(
                (message, pattern) -> PUBLISHED_EVENTS.add(new String(message.getBody())),
                new ChannelTopic(MetadataPublishEventPublisherFixtures.CHANNEL));
        container.afterPropertiesSet();
        container.start();
    }

    @Test
    @DisplayName("suite vocabulary (§12): grown ops + named error outcomes save; malformed op params reject")
    void suiteVocabularyGrowth() throws Exception {
        // an app to hang the suite on
        MvcResult app = mockMvc.perform(post("/api/v1/metadata/apps").with(builderJwt())
                        .contentType("application/json")
                        .content("""
                                { "apiName": "VocabApp", "entities": [ { "apiName": "Thing",
                                  "fields": [ { "apiName": "name", "type": "text" } ] } ] }
                                """))
                .andExpect(status().isOk()).andReturn();
        String appId = MAPPER.readTree(app.getResponse().getContentAsString())
                .get("id").asString();

        // Phase 4's grown vocabulary saves cleanly — including the §12 named outcome
        // error(SOD_VIOLATION) as an expectation (the runner maps it to registry 4011)
        mockMvc.perform(put("/api/v1/metadata/apps/" + appId + "/test-suites/vocab")
                        .with(builderJwt()).contentType("application/json")
                        .content("""
                                { "apiName": "vocab", "cases": [ { "name": "journey",
                                  "steps": [
                                    { "op": "queryRecord", "entity": "Task", "asRole": "manager",
                                      "template": { "filter": { "status": "OPEN" } },
                                      "expect": "ok" },
                                    { "op": "resolveTask", "entity": "Task", "asRole": "manager",
                                      "recordId": "${Task[0].id}",
                                      "template": { "action": "approve", "comment": "ok" },
                                      "expect": "ok" },
                                    { "op": "queryRecord", "entity": "Thing", "asRole": "manager",
                                      "template": { "filter": { "field": "name", "op": "eq",
                                                               "value": "x" } },
                                      "expect": "ok" },
                                    { "op": "resolveTask", "entity": "Task", "asRole": "manager",
                                      "recordId": "${Task[0].id}",
                                      "template": { "action": "reject" },
                                      "expect": "error(SOD_VIOLATION)" } ],
                                  "assertExpressions": [ "${Task[0].status} != 'OPEN'" ] } ] }
                                """))
                .andExpect(status().isOk());

        // unknown ops still reject
        mockMvc.perform(put("/api/v1/metadata/apps/" + appId + "/test-suites/bad")
                        .with(builderJwt()).contentType("application/json")
                        .content("""
                                { "apiName": "bad", "cases": [ { "name": "c",
                                  "steps": [ { "op": "teleport", "expect": "ok" } ] } ] }
                                """))
                .andExpect(status().isBadRequest());

        // record-addressed ops require recordId at save time (§12 — authoring errors
        // surface here, not as an aborted case at run time)
        mockMvc.perform(put("/api/v1/metadata/apps/" + appId + "/test-suites/no-id")
                        .with(builderJwt()).contentType("application/json")
                        .content("""
                                { "apiName": "no-id", "cases": [ { "name": "c",
                                  "steps": [ { "op": "resolveTask", "entity": "Task",
                                  "expect": "ok" } ] } ] }
                                """))
                .andExpect(status().isBadRequest());
        // deleteRecord carries template.version for optimistic locking
        mockMvc.perform(put("/api/v1/metadata/apps/" + appId + "/test-suites/no-version")
                        .with(builderJwt()).contentType("application/json")
                        .content("""
                                { "apiName": "no-version", "cases": [ { "name": "c",
                                  "steps": [ { "op": "deleteRecord", "entity": "Thing",
                                  "recordId": "${Thing[0].id}", "expect": "ok" } ] } ] }
                                """))
                .andExpect(status().isBadRequest());
        // resolveTask actions are the closed approve|reject set
        mockMvc.perform(put("/api/v1/metadata/apps/" + appId + "/test-suites/bad-action")
                        .with(builderJwt()).contentType("application/json")
                        .content("""
                                { "apiName": "bad-action", "cases": [ { "name": "c",
                                  "steps": [ { "op": "resolveTask", "entity": "Task",
                                  "recordId": "${Task[0].id}",
                                  "template": { "action": "delegate" }, "expect": "ok" } ] } ] }
                                """))
                .andExpect(status().isBadRequest());
        // the inbox query's v1 filter is {status} — the record DSL leaf rejects on save
        mockMvc.perform(put("/api/v1/metadata/apps/" + appId + "/test-suites/bad-filter")
                        .with(builderJwt()).contentType("application/json")
                        .content("""
                                { "apiName": "bad-filter", "cases": [ { "name": "c",
                                  "steps": [ { "op": "queryRecord", "entity": "Task",
                                  "template": { "filter": { "field": "assignee", "op": "eq",
                                                            "value": "x" } }, "expect": "ok" } ] } ] }
                                """))
                .andExpect(status().isBadRequest());
    }

    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor builderJwt() {
        return jwt()
                .jwt(token -> token.claim("tenant_id", TENANT).subject(ACTOR))
                .authorities(new SimpleGrantedAuthority("ROLE_builder"));
    }

    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor userJwt() {
        return jwt()
                .jwt(token -> token.claim("tenant_id", TENANT).subject(ACTOR))
                .authorities(new SimpleGrantedAuthority("ROLE_user"));
    }

    @Test
    @DisplayName("full definition lifecycle: create → invalid save rejected → publish → versions → export → immutability")
    void definitionLifecycle() throws Exception {
        // 1. create the ERP draft app (ARCHITECTURE §3 example)
        MvcResult created = mockMvc.perform(post("/api/v1/metadata/apps")
                        .with(builderJwt())
                        .contentType("application/json")
                        .content(JournalAppFixtures.JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiName").value("Erp"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn();
        String appId = MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();

        // 2. invalid entity save rejected as problem+json 4000 with field errors
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/entities")
                        .with(builderJwt())
                        .contentType("application/json")
                        .content("""
                                { "apiName": "bad_name", "fields": [ { "apiName": "f", "type": "text" } ] }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4000"))
                .andExpect(jsonPath("$.errors[0].field").isNotEmpty());

        // 3. publish v1 (no prior version → no breaking changes)
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/publish")
                        .with(builderJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.breakingChanges").isEmpty());

        // 4. published read carries the version for cache keys; user role suffices
        mockMvc.perform(get("/api/v1/metadata/apps/" + appId + "/published")
                        .with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.app.entities[0].apiName").value("AccountingPeriod"));

        // 5. breaking change without acknowledgment → 400 with the change listed
        mockMvc.perform(patch("/api/v1/metadata/apps/" + appId + "/entities/JournalEntry")
                        .with(builderJwt())
                        .contentType("application/json")
                        .content("""
                                { "fields": [
                                  { "apiName": "reference", "type": "text", "length": 32,
                                    "default": { "sequence": "entryNumber" } },
                                  { "apiName": "entryDate", "type": "date", "required": true },
                                  { "apiName": "status", "type": "enum", "values": ["DRAFT", "POSTED"] },
                                  { "apiName": "periodId", "type": "lookup", "target": "AccountingPeriod" }
                                ] }
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/publish").with(builderJwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.globalErrors[0].message").value(
                        org.hamcrest.Matchers.containsString("totalDebit")));

        // 6. publish with acknowledgeDataImpact → v2 acknowledged
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/publish")
                        .with(builderJwt())
                        .contentType("application/json")
                        .content("{ \"acknowledgeDataImpact\": true }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.acknowledged").value(true));

        // 7. versions listed newest-first; export round-trips v1 immutably
        mockMvc.perform(get("/api/v1/metadata/apps/" + appId + "/versions").with(builderJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[1].version").value(1));
        mockMvc.perform(get("/api/v1/metadata/apps/" + appId + "/versions/1/export")
                        .with(builderJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entities[?(@.apiName=='JournalEntry')].fields[?(@.apiName=='totalDebit')]")
                        .isNotEmpty());

        // 8. drafts stay mutable without touching the published bundle
        mockMvc.perform(patch("/api/v1/metadata/apps/" + appId)
                        .with(builderJwt())
                        .contentType("application/json")
                        .content("{ \"label\": \"ERP draft edit\" }"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/metadata/apps/" + appId + "/published").with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.app.label").value("ERP"));

        // 9. both publish envelopes rode the Redis channel (T4)
        await().atMost(Duration.ofSeconds(5)).until(() -> PUBLISHED_EVENTS.size() >= 2);
        String first = PUBLISHED_EVENTS.poll();
        String second = PUBLISHED_EVENTS.poll();
        assertThat(MAPPER.readTree(first).get("event").asString()).isEqualTo("metadata.published");
        assertThat(MAPPER.readTree(first).get("appId").asString()).isEqualTo(appId);
        assertThat(MAPPER.readTree(first).get("version").asInt()).isEqualTo(1);
        assertThat(MAPPER.readTree(second).get("version").asInt()).isEqualTo(2);
        assertThat(MAPPER.readTree(first).get("tenantId").asString()).isEqualTo(TENANT);
    }

    @Test
    @DisplayName("draft CRUD is builder-scoped; user role is denied (PHASE-1 §4)")
    void draftCrudIsBuilderScoped() throws Exception {
        mockMvc.perform(get("/api/v1/metadata/apps").with(userJwt()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/metadata/apps").with(builderJwt()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("expression slots compile-check at save: unresolved references and clock-in-formulas reject")
    void expressionCompileCheck() throws Exception {
        // validation rule referencing an unknown field → 4000 with the expression error
        mockMvc.perform(post("/api/v1/metadata/apps")
                        .with(builderJwt())
                        .contentType("application/json")
                        .content("""
                                { "apiName": "ExprApp", "entities": [ { "apiName": "Thing",
                                  "fields": [ { "apiName": "amount", "type": "decimal", "precision": 18, "scale": 4 } ],
                                  "validations": [
                                    { "name": "cap", "expression": "amount < ceilling",
                                      "message": "too much" } ] } ] }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4000"))
                .andExpect(jsonPath("$.errors[0].field").value(
                        org.hamcrest.Matchers.containsString("expressions")));

        // formula with a clock function → rejected (PHASE-3 §3 determinism)
        mockMvc.perform(post("/api/v1/metadata/apps")
                        .with(builderJwt())
                        .contentType("application/json")
                        .content("""
                                { "apiName": "ExprApp2", "entities": [ { "apiName": "Thing",
                                  "fields": [
                                    { "apiName": "amount", "type": "decimal", "precision": 18, "scale": 4 },
                                    { "apiName": "stale", "type": "boolean",
                                      "formula": "today() - opened > 365" },
                                    { "apiName": "opened", "type": "date" } ] } ] }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value(
                        org.hamcrest.Matchers.containsString("clock")));

        // clean validation rule (clock allowed in the validations slot) saves
        mockMvc.perform(post("/api/v1/metadata/apps")
                        .with(builderJwt())
                        .contentType("application/json")
                        .content("""
                                { "apiName": "ExprApp3", "entities": [ { "apiName": "Thing",
                                  "fields": [
                                    { "apiName": "amount", "type": "decimal", "precision": 18, "scale": 4 },
                                    { "apiName": "opened", "type": "date" } ],
                                  "validations": [
                                    { "name": "stale", "expression": "today() - opened > 365",
                                      "message": "stale entry" } ] } ] }
                                """))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("flow compiler (§2): cycles, unknown ops, bad fields, dangling chains reject")
    void flowCompilerRejections() throws Exception {
        // cycle: s1 → s2 → s1
        mockMvc.perform(post("/api/v1/metadata/apps").with(builderJwt())
                        .contentType("application/json").content("""
                                { "apiName": "CycleApp", "entities": [ { "apiName": "Thing",
                                  "fields": [ { "apiName": "name", "type": "text" } ],
                                  "hooks": [ { "name": "loop", "trigger": "beforeSave",
                                    "flow": { "id": "s1", "op": "setField",
                                      "params": { "field": "name", "expression": "upper(name)" },
                                      "next": "s2" } } ] } ] }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value(
                        org.hamcrest.Matchers.containsString("known step id")))
                .andExpect(jsonPath("$.errors[0].message").value(
                        org.hamcrest.Matchers.containsString("s2")));

        // unknown op
        mockMvc.perform(post("/api/v1/metadata/apps").with(builderJwt())
                        .contentType("application/json").content("""
                                { "apiName": "OpApp", "entities": [ { "apiName": "Thing",
                                  "fields": [ { "apiName": "name", "type": "text" } ],
                                  "hooks": [ { "name": "x", "trigger": "afterSave",
                                    "flow": { "id": "s1", "op": "runSql", "params": {} } } ] } ] }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value(
                        org.hamcrest.Matchers.containsString("unknown op")));

        // setField to a field that does not exist
        mockMvc.perform(post("/api/v1/metadata/apps").with(builderJwt())
                        .contentType("application/json").content("""
                                { "apiName": "FieldApp", "entities": [ { "apiName": "Thing",
                                  "fields": [ { "apiName": "name", "type": "text" } ],
                                  "hooks": [ { "name": "x", "trigger": "beforeSave",
                                    "flow": { "id": "s1", "op": "setField",
                                      "params": { "field": "ghost", "expression": "name" } } } ] } ] }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value(
                        org.hamcrest.Matchers.containsString("existing field")));

        // transitionState compiles against a bound machine (Phase 4 activation, §3)
        mockMvc.perform(post("/api/v1/metadata/apps").with(builderJwt())
                        .contentType("application/json").content("""
                                { "apiName": "FixedApp", "entities": [ { "apiName": "Thing",
                                  "fields": [ { "apiName": "name", "type": "text" },
                                               { "apiName": "status", "type": "enum",
                                                 "values": ["DRAFT", "POSTED"] } ],
                                  "hooks": [ { "name": "x", "trigger": "afterSave",
                                    "flow": { "id": "s1", "op": "transitionState",
                                      "params": { "to": "POSTED" } } } ] } ],
                                  "stateMachines": [
                                    { "id": "sm_thing", "entity": "Thing", "stateField": "status",
                                      "initial": "DRAFT",
                                      "states": [ { "name": "DRAFT" },
                                                  { "name": "POSTED", "terminal": true } ],
                                      "transitions": [ { "from": "DRAFT", "to": "POSTED" } ] } ] }
                                """))
                .andExpect(status().isOk());

        // …and rejects on an entity with no machine bound (compile-checked)
        mockMvc.perform(post("/api/v1/metadata/apps").with(builderJwt())
                        .contentType("application/json").content("""
                                { "apiName": "NoMachine", "entities": [ { "apiName": "Thing",
                                  "fields": [ { "apiName": "name", "type": "text" } ],
                                  "hooks": [ { "name": "x", "trigger": "afterSave",
                                    "flow": { "id": "s1", "op": "transitionState",
                                      "params": { "to": "POSTED" } } } ] } ] }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value(
                        org.hamcrest.Matchers.containsString("requires a state machine")));
    }

    @Test
    @DisplayName("script hooks (§6): publish accepts a valid artifact; shape, language, and size reject")
    void scriptHookPublishChecks() throws Exception {
        // valid script hook rides the same review path as flows
        mockMvc.perform(post("/api/v1/metadata/apps").with(builderJwt())
                        .contentType("application/json").content("""
                                { "apiName": "ScriptOk", "entities": [ { "apiName": "Thing",
                                  "fields": [ { "apiName": "name", "type": "text" } ],
                                  "hooks": [ { "name": "enrich", "trigger": "beforeSave",
                                    "script": { "language": "js",
                                      "source": "({ name: $record.name.toUpperCase() })" } } ] } ] }
                                """))
                .andExpect(status().isOk());

        // flow and script are exclusive bodies
        mockMvc.perform(post("/api/v1/metadata/apps").with(builderJwt())
                        .contentType("application/json").content("""
                                { "apiName": "BothApp", "entities": [ { "apiName": "Thing",
                                  "fields": [ { "apiName": "name", "type": "text" } ],
                                  "hooks": [ { "name": "x", "trigger": "beforeSave",
                                    "flow": { "id": "s1", "op": "setField",
                                      "params": { "field": "name", "expression": "name" } },
                                    "script": { "language": "js", "source": "1" } } ] } ] }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value(
                        org.hamcrest.Matchers.containsString("not both")));

        // a hook needs one of the two bodies
        mockMvc.perform(post("/api/v1/metadata/apps").with(builderJwt())
                        .contentType("application/json").content("""
                                { "apiName": "NeitherApp", "entities": [ { "apiName": "Thing",
                                  "fields": [ { "apiName": "name", "type": "text" } ],
                                  "hooks": [ { "name": "x", "trigger": "beforeSave" } ] } ] }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value(
                        org.hamcrest.Matchers.containsString("flow entry step or a script")));

        // v0 ships GraalVM JS only
        mockMvc.perform(post("/api/v1/metadata/apps").with(builderJwt())
                        .contentType("application/json").content("""
                                { "apiName": "LangApp", "entities": [ { "apiName": "Thing",
                                  "fields": [ { "apiName": "name", "type": "text" } ],
                                  "hooks": [ { "name": "x", "trigger": "afterSave",
                                    "script": { "language": "python", "source": "1" } } ] } ] }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value(
                        org.hamcrest.Matchers.containsString("language")));

        // blank source is no artifact at all
        mockMvc.perform(post("/api/v1/metadata/apps").with(builderJwt())
                        .contentType("application/json").content("""
                                { "apiName": "BlankApp", "entities": [ { "apiName": "Thing",
                                  "fields": [ { "apiName": "name", "type": "text" } ],
                                  "hooks": [ { "name": "x", "trigger": "afterSave",
                                    "script": { "language": "js", "source": "  " } } ] } ] }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value(
                        org.hamcrest.Matchers.containsString("blank")));

        // source is bounded — 64 KiB is the reviewed-artifact ceiling
        mockMvc.perform(post("/api/v1/metadata/apps").with(builderJwt())
                        .contentType("application/json").content(
                                "{ \"apiName\": \"HugeApp\", \"entities\": [ { \"apiName\": \"Thing\","
                                + " \"fields\": [ { \"apiName\": \"name\", \"type\": \"text\" } ],"
                                + " \"hooks\": [ { \"name\": \"x\", \"trigger\": \"afterSave\","
                                + " \"script\": { \"language\": \"js\", \"source\": \""
                                + "x".repeat(64 * 1024 + 1) + "\" } } ] } ] }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value(
                        org.hamcrest.Matchers.containsString("exceeds")));
    }

    @Test
    @DisplayName("app delete cascades (draft workspace) — publish history stays queryable per app row removal")
    void deleteAppCascades() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/metadata/apps")
                        .with(builderJwt())
                        .contentType("application/json")
                        .content("""
                                { "apiName": "Throwaway", "entities": [
                                  { "apiName": "Thing",
                                    "fields": [ { "apiName": "name", "type": "text" } ] } ] }
                                """))
                .andExpect(status().isOk()).andReturn();
        String appId = MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();
        mockMvc.perform(delete("/api/v1/metadata/apps/" + appId).with(builderJwt()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/metadata/apps/" + appId).with(builderJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("4004"));
    }

    // Small fixtures kept local to this suite.
    static final class JournalAppFixtures {
        static final String JSON = """
                {
                  "apiName": "Erp", "label": "ERP",
                  "settings": { "sequences": [
                    { "apiName": "entryNumber", "mode": "gapless", "start": 1,
                      "prefix": "JE-", "padding": 6 } ] },
                  "entities": [
                    { "apiName": "JournalEntry", "label": "Journal Entry",
                      "displayField": "reference",
                      "fields": [
                        { "apiName": "reference", "type": "text", "length": 32, "required": true,
                          "default": { "sequence": "entryNumber" } },
                        { "apiName": "entryDate", "type": "date", "required": true },
                        { "apiName": "status", "type": "enum", "values": ["DRAFT", "POSTED"] },
                        { "apiName": "periodId", "type": "lookup", "target": "AccountingPeriod" },
                        { "apiName": "totalDebit", "type": "decimal", "precision": 18, "scale": 4,
                          "rollup": "SUM(lines.debit)" },
                        { "apiName": "totalCredit", "type": "decimal", "precision": 18, "scale": 4,
                          "rollup": "SUM(lines.credit)" } ],
                      "relationships": [
                        { "apiName": "lines", "type": "child", "target": "JournalLine",
                          "cascadeDelete": true } ],
                      "indexes": [ { "fields": ["entryDate"], "unique": false } ] },
                    { "apiName": "JournalLine",
                      "fields": [
                        { "apiName": "entryId", "type": "lookup", "target": "JournalEntry",
                          "required": true },
                        { "apiName": "debit", "type": "decimal", "precision": 18, "scale": 4 },
                        { "apiName": "credit", "type": "decimal", "precision": 18, "scale": 4 } ] },
                    { "apiName": "AccountingPeriod", "displayField": "name",
                      "fields": [ { "apiName": "name", "type": "text" } ] } ]
                }
                """;
    }

    static final class MetadataPublishEventPublisherFixtures {
        static final String CHANNEL = "novaforge.metadata.events";
    }
}
