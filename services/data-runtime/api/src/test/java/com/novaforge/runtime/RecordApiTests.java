package com.novaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.runtime.engine.hook.ScriptClient;
import com.novaforge.runtime.engine.metadata.EntityResolver;
import com.novaforge.runtime.engine.metadata.MetadataClient;
import com.novaforge.runtime.engine.metadata.MetadataPublishedSubscriber;
import com.novaforge.runtime.storage.materializer.Materializer;
import com.novaforge.testsupport.PostgresTestBase;
import java.time.Duration;
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
 * The Phase 1 exit demo through the real stack (PHASE-1 §1/§5/§9): entity resolved from
 * a published bundle → CRUD via the generic record API with field validations enforced —
 * plus optimistic-locking races, uniqueness, sequences, inline children, query DSL,
 * batch, idempotency, RLS cross-tenant fail-closed, the event seam, and T4's
 * publish-driven cache invalidation + re-materialization.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RecordApiTests extends PostgresTestBase {

    static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID SECOND_TENANT = UUID.fromString("99999999-9999-4999-8999-999999999999");
    static final UUID ACTOR = UUID.fromString("33333333-3333-4333-8333-333333333333");
    static final UUID APP_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    static final String APP_JSON = """
            { "apiName": "Erp",
              "permissionSet": {
                "roles": [ { "name": "clerk", "description": "Front-desk clerk" } ],
                "objectPermissions": [
                  { "role": "clerk", "entity": "Ticket",
                    "create": true, "read": true, "update": false, "delete": false } ],
                "fieldSecurity": [
                  { "role": "clerk", "entity": "Ticket", "field": "title", "access": "hidden" },
                  { "role": "clerk", "entity": "Ticket", "field": "number", "access": "readonly" } ] },
              "settings": { "sequences": [
                { "apiName": "entryNumber", "mode": "gapless", "start": 1,
                  "prefix": "JE-", "padding": 6 },
                { "apiName": "cachedNumber", "mode": "cached", "start": 500,
                  "prefix": "CN-", "padding": 5 } ] },
              "entities": [
                { "apiName": "JournalEntry", "label": "Journal Entry",
                  "displayField": "reference",
                  "fields": [
                    { "apiName": "reference", "type": "text", "length": 32, "required": true,
                      "default": { "sequence": "entryNumber" } },
                    { "apiName": "entryDate", "type": "date", "required": true },
                    { "apiName": "status", "type": "enum", "values": ["DRAFT", "POSTED"] },
                    { "apiName": "memo", "type": "text", "length": 32 },
                    { "apiName": "amount", "type": "decimal", "precision": 18, "scale": 4 },
                    { "apiName": "totalLines", "type": "decimal", "precision": 18, "scale": 4,
                      "rollup": "SUM(lines.amount)" },
                    { "apiName": "lineCount", "type": "int",
                      "rollup": "COUNT(lines)" },
                    { "apiName": "memoUpper", "type": "text",
                      "formula": "upper(memo)" } ],
                  "relationships": [
                    { "apiName": "lines", "type": "child", "target": "JournalLine",
                      "cascadeDelete": true } ],
                  "validations": [
                    { "name": "memoBalanced", "scope": "record",
                      "expression": "memo == null || contains(memo, 'ok')",
                      "message": "memo must contain 'ok'" } ],
                  "indexes": [ { "fields": ["entryDate"] } ] },
                { "apiName": "JournalLine",
                  "fields": [
                    { "apiName": "entryId", "type": "lookup", "target": "JournalEntry",
                      "required": true },
                    { "apiName": "debit", "type": "decimal", "precision": 18, "scale": 4 },
                    { "apiName": "credit", "type": "decimal", "precision": 18, "scale": 4 },
                    { "apiName": "amount", "type": "decimal", "precision": 18, "scale": 4 } ] },
                { "apiName": "InventoryItem",
                  "displayField": "sku",
                  "fields": [
                    { "apiName": "sku", "type": "text", "required": true, "uniqueness": true },
                    { "apiName": "onHand", "type": "decimal", "precision": 18, "scale": 4 },
                    { "apiName": "reserved", "type": "decimal", "precision": 18, "scale": 4 } ] },
                { "apiName": "Order",
                  "displayField": "label",
                  "fields": [
                    { "apiName": "label", "type": "text", "required": true } ],
                  "relationships": [
                    { "apiName": "items", "type": "child", "target": "OrderLine",
                      "cascadeDelete": true } ],
                  "hooks": [
                    { "name": "reserveStock", "trigger": "afterSave", "flow":
                      { "id": "s1", "op": "iterate", "params": { "path": "items" },
                        "body": { "id": "s2", "op": "updateRecord",
                                  "params": { "entity": "InventoryItem",
                                              "recordId": "${itemId}",
                                              "template": { "reserved": "${qty}" } } },
                        "next": null } },
                    { "name": "stampTotal", "trigger": "beforeSave", "flow":
                      { "id": "b1", "op": "setField",
                        "params": { "field": "label", "expression": "upper(label)" } } } ] },
                { "apiName": "OrderLine",
                  "fields": [
                    { "apiName": "orderId", "type": "lookup", "target": "Order", "required": true },
                    { "apiName": "itemId", "type": "lookup", "target": "InventoryItem", "required": true },
                    { "apiName": "qty", "type": "decimal", "precision": 18, "scale": 4 } ] },
                { "apiName": "LedgerNote",
                  "fields": [
                    { "apiName": "note", "type": "text" },
                    { "apiName": "shouty", "type": "text",
                      "default": { "expression": "upper(note)" } },
                    { "apiName": "noteLength", "type": "int",
                      "default": { "expression": "length(note)" } } ] },
                { "apiName": "Scripty",
                  "fields": [
                    { "apiName": "label", "type": "text", "required": true } ],
                  "hooks": [
                    { "name": "enrich", "trigger": "beforeSave",
                      "script": { "language": "js",
                        "source": "({ label: 'ENRICHED-' + $record.label })" } },
                    { "name": "notify", "trigger": "afterSave",
                      "script": { "language": "js",
                        "source": "$log.info('saved ' + $record.id)" } } ] },
                { "apiName": "Ticket",
                  "fields": [
                    { "apiName": "number", "type": "text",
                      "default": { "sequence": "cachedNumber" } },
                    { "apiName": "title", "type": "text", "required": true } ] } ] }
            """;

    static AppDefinition appV1;
    static volatile AppDefinition appV2;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    org.springframework.kafka.core.KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    EntityResolver resolver;

    static final List<String> PUBLISHED_APP_INDEX = new CopyOnWriteArrayList<>();

    /** Script-hook calls observed by the stub (app|hook|trigger|language|recordId). */
    static final List<String> SCRIPT_CALLS = new CopyOnWriteArrayList<>();
    static volatile boolean failBeforeScripts;
    static volatile boolean failAfterScripts;

    @TestConfiguration
    static class StubMetadata {
        @Bean
        @Primary
        MetadataClient metadataClient() {
            MetadataClient client = Mockito.mock(MetadataClient.class);
            appV1 = DefinitionParser.parseApp(APP_JSON);
            PUBLISHED_APP_INDEX.add("v1");
            Mockito.when(client.publishedApps()).thenAnswer(inv ->
                    List.of(new MetadataClient.PublishedApp(APP_ID, "Erp", currentVersion())));
            Mockito.when(client.publishedBundle(Mockito.any(UUID.class))).thenAnswer(inv ->
                    new MetadataClient.PublishedBundle(currentVersion(), currentApp()));
            return client;
        }

        /** The script-engine port, stubbed: canned outcomes, recorded invocations. */
        @Bean
        @Primary
        ScriptClient scriptClient() {
            return new ScriptClient() {
                @Override
                public ScriptOutcome execute(String app, int appVersion, String hook,
                                             String trigger, com.novaforge.metadata.ScriptDefinition script,
                                             Map<String, Object> record) {
                    SCRIPT_CALLS.add(app + "|" + hook + "|" + trigger + "|" + script.language()
                            + "|" + record.get("id"));
                    if (failBeforeScripts && trigger.startsWith("before")) {
                        throw new com.novaforge.common.error.PlatformException(
                                com.novaforge.common.error.PlatformErrorCode.VALIDATION_FAILED,
                                "script hook " + hook + " exploded");
                    }
                    if (failAfterScripts && trigger.startsWith("after")) {
                        throw new com.novaforge.common.error.PlatformException(
                                com.novaforge.common.error.PlatformErrorCode.VALIDATION_FAILED,
                                "script hook " + hook + " exploded");
                    }
                    if ("beforeSave".equals(trigger)) {
                        return new ScriptOutcome(
                                Map.of("label", "ENRICHED-" + record.get("label")),
                                List.of("stub enrich"));
                    }
                    return new ScriptOutcome(Map.of(), List.of("stub notify"));
                }

                @Override
                public ScriptOutcome executeScheduled(java.util.UUID tenantId, String app,
                                                      int appVersion, String hook,
                                                      com.novaforge.metadata.ScriptDefinition script) {
                    SCRIPT_CALLS.add(app + "|" + hook + "|scheduled|" + script.language()
                            + "|null");
                    return new ScriptOutcome(Map.of(), List.of("stub scheduled"));
                }
            };
        }

        static int currentVersion() {
            return appV2 == null ? 1 : 2;
        }

        static AppDefinition currentApp() {
            return appV2 == null ? appV1 : appV2;
        }
    }

    private static final GenericContainer<?> REDIS = new GenericContainer<>("docker.io/library/redis:7.4.11")
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    private static final org.testcontainers.kafka.KafkaContainer KAFKA =
            new org.testcontainers.kafka.KafkaContainer("apache/kafka:4.3.1");

    static String kafkaBootstrap;

    static {
        REDIS.start();
        KAFKA.start();
        kafkaBootstrap = KAFKA.getBootstrapServers();
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestBase::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestBase::jdbcUsername);
        registry.add("spring.datasource.password", PostgresTestBase::jdbcPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", () -> kafkaBootstrap);
        registry.add("novaforge.events.relay-interval-ms", () -> "200");
    }

    @BeforeAll
    static void seedSecondTenantAndMaterialize(@Autowired Materializer materializer,
                                               @Autowired JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO platform.tenants (id, api_name) VALUES (?, ?) ON CONFLICT DO NOTHING",
                SECOND_TENANT, "second");
        jdbc.update("INSERT INTO platform.users (id, username) VALUES (?, ?) ON CONFLICT DO NOTHING",
                ACTOR, "demo");
        jdbc.update("INSERT INTO platform.role_assignments (tenant_id, user_id, role) VALUES (?, ?, 'admin') "
                + "ON CONFLICT DO NOTHING", SECOND_TENANT, ACTOR);
        materializer.apply(DefinitionParser.parseApp(APP_JSON));
    }

    @Test
    @DisplayName("script hooks (§6): beforeSave write-back merges, caller artifact travels, failure policy is uniform")
    void scriptHooks() throws Exception {
        failBeforeScripts = false;
        failAfterScripts = false;
        SCRIPT_CALLS.clear();

        // beforeSave: the returned object is the write-back channel — merged into the
        // record before persist, exactly like setField
        MvcResult created = mockMvc.perform(post("/api/v1/runtime/Scripty").with(jwtFor(TENANT))
                        .contentType("application/json").content("{\"label\":\"widget\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("ENRICHED-widget"))
                .andReturn();
        String id = MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();

        // the caller's artifact arrived with app, hook, trigger, language, and the id view
        assertThat(SCRIPT_CALLS).anySatisfy(call -> {
            org.assertj.core.api.Assertions.assertThat(call)
                    .contains("Erp|enrich|beforeSave|js|" + id);
        });

        // afterSave fired on the same write; its failure must not block the write
        assertThat(SCRIPT_CALLS).anySatisfy(call -> {
            org.assertj.core.api.Assertions.assertThat(call)
                    .contains("Erp|notify|afterSave|js|" + id);
        });
        failAfterScripts = true;
        mockMvc.perform(post("/api/v1/runtime/Scripty").with(jwtFor(TENANT))
                        .contentType("application/json").content("{\"label\":\"second\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("ENRICHED-second"));
        failAfterScripts = false;

        // beforeSave failure aborts the transaction — uniform with flows (§2.5)
        failBeforeScripts = true;
        mockMvc.perform(post("/api/v1/runtime/Scripty").with(jwtFor(TENANT))
                        .contentType("application/json").content("{\"label\":\"doomed\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("exploded")));
        Integer doomed = jdbc.queryForObject(
                "SELECT count(*) FROM rec_records WHERE entity_id = 'Erp.Scripty' "
                        + "AND data->>'label' = 'doomed' AND NOT deleted", Integer.class);
        assertThat(doomed).isZero();
        failBeforeScripts = false;
    }

    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(UUID tenant) {
        return jwt()
                .jwt(token -> token.claim("tenant_id", tenant.toString()).subject(ACTOR.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
    }

    // --- Phase 1 exit demo (§1): create → CRUD with field validations enforced ---

    @Test
    @DisplayName("create enforces required/type/enum/precision and draws the gapless sequence once")
    void createValidatesAndDrawsSequence() throws Exception {
        // missing required entryDate → 4000 with field errors
        mockMvc.perform(post("/api/v1/runtime/JournalEntry").with(jwtFor(TENANT))
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4000"))
                .andExpect(jsonPath("$.errors[?(@.field=='entryDate')]").isNotEmpty());

        // bad enum + bad scale + overlength text all reported at once
        mockMvc.perform(post("/api/v1/runtime/JournalEntry").with(jwtFor(TENANT))
                        .contentType("application/json").content("""
                                { "entryDate": "2026-08-21", "status": "VOID",
                                  "amount": 12.34567, "memo": "%s" }
                                """.formatted("x".repeat(40))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field=='status')]").isNotEmpty())
                .andExpect(jsonPath("$.errors[?(@.field=='amount')]").isNotEmpty())
                .andExpect(jsonPath("$.errors[?(@.field=='memo')]").isNotEmpty());

        // unknown field rejected
        mockMvc.perform(post("/api/v1/runtime/JournalEntry").with(jwtFor(TENANT))
                        .contentType("application/json")
                        .content("{\"entryDate\":\"2026-08-21\",\"nope\":1}"))
                .andExpect(status().isBadRequest());

        // success: sequence default drawn once (gapless JE-000001 first draw), version 1
        MvcResult created = mockMvc.perform(post("/api/v1/runtime/JournalEntry").with(jwtFor(TENANT))
                        .contentType("application/json")
                        .content("{\"entryDate\":\"2026-08-21\",\"status\":\"DRAFT\",\"amount\":100.5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value(
                        org.hamcrest.Matchers.matchesPattern("JE-\\d{6}")))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn();
        String id = MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();
        String reference = MAPPER.readTree(created.getResponse().getContentAsString()).get("reference").asString();

        // GET round-trip, sparse fields strip server-side (Phase 2 field-security base)
        mockMvc.perform(get("/api/v1/runtime/JournalEntry/" + id).with(jwtFor(TENANT))
                        .param("fields", "reference,status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value(reference))
                .andExpect(jsonPath("$.entryDate").doesNotExist());

        // PATCH bumps version; unknown version → 409/4090
        mockMvc.perform(patch("/api/v1/runtime/JournalEntry/" + id).with(jwtFor(TENANT))
                        .contentType("application/json")
                        .content("{\"version\":1,\"status\":\"POSTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(patch("/api/v1/runtime/JournalEntry/" + id).with(jwtFor(TENANT))
                        .contentType("application/json")
                        .content("{\"version\":1,\"status\":\"DRAFT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("4090"));

        // the event seam wrote the transactional outbox (§4): created + updated for this record
        Integer outboxCount = jdbc.queryForObject(
                "SELECT count(*) FROM event_outbox WHERE record_id = ?::uuid AND event_type LIKE 'record.%'",
                Integer.class, id);
        assertThat(outboxCount).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("write-path evaluation (PHASE-3 §3): expression defaults, validation rules, formulas, roll-ups")
    void writePathEvaluation() throws Exception {
        // expression defaults evaluated before validations
        mockMvc.perform(post("/api/v1/runtime/LedgerNote").with(jwtFor(TENANT))
                        .contentType("application/json").content("{\"note\":\"quiet\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shouty").value("QUIET"))
                .andExpect(jsonPath("$.noteLength").value(5));

        // validation rule (record-scope expression) rejects with its message
        mockMvc.perform(post("/api/v1/runtime/JournalEntry").with(jwtFor(TENANT))
                        .contentType("application/json")
                        .content("{\"entryDate\":\"2026-08-21\",\"memo\":\"bad memo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("memo must contain 'ok'"));

        // formula field computed at write + roll-ups recomputed in the same transaction
        MvcResult created = mockMvc.perform(post("/api/v1/runtime/JournalEntry").with(jwtFor(TENANT))
                        .contentType("application/json").content("""
                                { "entryDate": "2026-08-21", "memo": "all ok",
                                  "lines": [ { "amount": 10.5 }, { "amount": 20 } ] }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memoUpper").value("ALL OK"))
                .andExpect(jsonPath("$.totalLines").value(30.5))
                .andExpect(jsonPath("$.lineCount").value(2))
                .andReturn();
        String id = MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();

        // roll-ups recompute on update (children replaced) and formula recomputes with memo
        mockMvc.perform(patch("/api/v1/runtime/JournalEntry/" + id).with(jwtFor(TENANT))
                        .contentType("application/json")
                        .content("{\"version\":1,\"memo\":\"still ok\",\"lines\":[{\"amount\":5}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memoUpper").value("STILL OK"))
                .andExpect(jsonPath("$.totalLines").value(5))
                .andExpect(jsonPath("$.lineCount").value(1));
    }

    @Test
    @DisplayName("hooks (§2): beforeSave setField, afterSave iterate + updateRecord reserve stock — no code")
    void flowHooks() throws Exception {
        // two inventory items
        String widget = createInventory("WIDGET-1", 100);
        String gadget = createInventory("GADGET-1", 50);

        // order with lines → afterSave iterates and reserves stock; beforeSave stamps the label
        MvcResult order = mockMvc.perform(post("/api/v1/runtime/Order").with(jwtFor(TENANT))
                        .contentType("application/json").content("""
                                { "label": "web order",
                                  "items": [
                                    { "itemId": "%s", "qty": 3 },
                                    { "itemId": "%s", "qty": 2 } ] }
                                """.formatted(widget, gadget)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("WEB ORDER"))   // beforeSave setField
                .andReturn();

        // stock reserved via the hook — declaratively, zero app code (the §1 exit)
        mockMvc.perform(get("/api/v1/runtime/InventoryItem/" + widget).with(jwtFor(TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reserved").value(3));
        mockMvc.perform(get("/api/v1/runtime/InventoryItem/" + gadget).with(jwtFor(TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reserved").value(2));

        // app event published? hook write events landed in the outbox (system principal)
        Integer hookEvents = jdbc.queryForObject("""
                SELECT count(*) FROM event_outbox WHERE entity_id = 'Erp.InventoryItem'
                 AND event_type = 'record.updated'""", Integer.class);
        assertThat(hookEvents).isGreaterThanOrEqualTo(2);
    }

    private String createInventory(String sku, double onHand) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/runtime/InventoryItem").with(jwtFor(TENANT))
                        .contentType("application/json")
                        .content("{\"sku\":\"" + sku + "\",\"onHand\":" + onHand + ",\"reserved\":0}"))
                .andExpect(status().isOk()).andReturn();
        return MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();
    }

    @Test
    @DisplayName("cached-mode sequence also draws from its Redis block")
    void cachedSequenceDraws() throws Exception {
        mockMvc.perform(post("/api/v1/runtime/Ticket").with(jwtFor(TENANT))
                        .contentType("application/json")
                        .content("{\"title\":\"first ticket\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value("CN-00500"));
    }

    @Test
    @DisplayName("optimistic-lock race: concurrent PATCHes — exactly one wins (§9 item 2)")
    void optimisticLockRace() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/runtime/Ticket").with(jwtFor(TENANT))
                        .contentType("application/json").content("{\"title\":\"race\"}"))
                .andExpect(status().isOk()).andReturn();
        String id = MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();

        List<Integer> statuses = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread a = new Thread(() -> statuses.add(patchStatus(id, 1, "title", "A")));
        Thread b = new Thread(() -> statuses.add(patchStatus(id, 1, "title", "B")));
        a.start();
        b.start();
        a.join();
        b.join();
        assertThat(statuss(statuses)).containsExactlyInAnyOrder(200, 409);
    }

    private int patchStatus(String id, int version, String field, String value) {
        try {
            return mockMvc.perform(patch("/api/v1/runtime/Ticket/" + id).with(jwtFor(TENANT))
                            .contentType("application/json")
                            .content("{\"version\":" + version + ",\"" + field + "\":\"" + value + "\"}"))
                    .andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static List<Integer> statuss(List<Integer> statuses) {
        return statuses;
    }

    @Test
    @DisplayName("soft delete excludes from reads; tombstone never pins the unique value; cascade removes children")
    void softDeleteAndCascade() throws Exception {
        MvcResult parent = mockMvc.perform(post("/api/v1/runtime/JournalEntry").with(jwtFor(TENANT))
                        .contentType("application/json").content("""
                                { "entryDate": "2026-08-21", "status": "DRAFT",
                                  "lines": [
                                    { "debit": 10.5, "credit": 0 },
                                    { "debit": 0, "credit": 10.5 } ] }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").isNotEmpty())
                .andReturn();
        String parentId = MAPPER.readTree(parent.getResponse().getContentAsString()).get("id").asString();
        String reference = MAPPER.readTree(parent.getResponse().getContentAsString()).get("reference").asString();

        // children created with the binding lookup, visible via child entity list
        Integer childCount = jdbc.queryForObject(
                "SELECT count(*) FROM rec_records WHERE entity_id = 'Erp.JournalLine' AND NOT deleted",
                Integer.class);
        assertThat(childCount).isGreaterThanOrEqualTo(2);

        int version = MAPPER.readTree(parent.getResponse().getContentAsString()).get("version").asInt();
        mockMvc.perform(delete("/api/v1/runtime/JournalEntry/" + parentId).with(jwtFor(TENANT))
                        .param("version", String.valueOf(version)))
                .andExpect(status().isNoContent());

        // parent gone from reads; children cascaded (cascadeDelete: true)
        mockMvc.perform(get("/api/v1/runtime/JournalEntry/" + parentId).with(jwtFor(TENANT)))
                .andExpect(status().isNotFound());
        Integer cascaded = jdbc.queryForObject(
                "SELECT count(*) FROM rec_records r WHERE entity_id = 'Erp.JournalLine' "
                        + "AND data->>'entryId' = ? AND NOT deleted", Integer.class, parentId);
        assertThat(cascaded).isZero();

        // recreate with the same unique-drawn reference works — tombstones never pin (§6)
        mockMvc.perform(post("/api/v1/runtime/JournalEntry").with(jwtFor(TENANT))
                        .contentType("application/json").content("{\"entryDate\":\"2026-08-21\"}"))
                .andExpect(status().isOk());
        assertThat(reference).startsWith("JE-");
    }

    @Test
    @DisplayName("inline children cap at 100 per request; larger sets rejected")
    void inlineChildrenCapped() throws Exception {
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 101; i++) {
            if (!lines.isEmpty()) {
                lines.append(',');
            }
            lines.append("{\"debit\":1}");
        }
        mockMvc.perform(post("/api/v1/runtime/JournalEntry").with(jwtFor(TENANT))
                        .contentType("application/json").content(
                                "{\"entryDate\":\"2026-08-21\",\"lines\":[" + lines + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field=='lines')]").isNotEmpty());
    }

    // --- query path ---

    @Test
    @DisplayName("GET list: percent-encoded DSL nodes filter/sort/page; over-limit page size rejects")
    void listQueryDsl() throws Exception {
        for (double amount : new double[] {50, 150, 250}) {
            mockMvc.perform(post("/api/v1/runtime/Ticket").with(jwtFor(TENANT))
                            .contentType("application/json")
                            .content("{\"title\":\"t" + amount + "\"}"))
                    .andExpect(status().isOk());
        }
        String filter = java.net.URLEncoder.encode(
                "{\"field\":\"title\",\"op\":\"contains\",\"value\":\"t\"}",
                java.nio.charset.StandardCharsets.UTF_8);
        String sort = java.net.URLEncoder.encode(
                "[{\"field\":\"title\",\"dir\":\"desc\"}]", java.nio.charset.StandardCharsets.UTF_8);
        String page = java.net.URLEncoder.encode(
                "{\"size\":2,\"offset\":0}", java.nio.charset.StandardCharsets.UTF_8);

        MvcResult listed = mockMvc.perform(get("/api/v1/runtime/Ticket").with(jwtFor(TENANT))
                        .param("filter", filter).param("sort", sort).param("page", page))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").isNumber())
                .andReturn();
        assertThat(MAPPER.readTree(listed.getResponse().getContentAsString()).get("rows").size())
                .isLessThanOrEqualTo(2);

        String overLimit = java.net.URLEncoder.encode("{\"size\":201}",
                java.nio.charset.StandardCharsets.UTF_8);
        mockMvc.perform(get("/api/v1/runtime/Ticket").with(jwtFor(TENANT)).param("page", overLimit))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4000"));
    }

    @Test
    @DisplayName("GET list: a malformed DSL node rejects at the door as 400, not a downstream 500")
    void malformedDslNodeRejects() throws Exception {
        String malformed = java.net.URLEncoder.encode("{not json",
                java.nio.charset.StandardCharsets.UTF_8);
        mockMvc.perform(get("/api/v1/runtime/Ticket").with(jwtFor(TENANT))
                        .param("filter", malformed))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4000"))
                .andExpect(jsonPath("$.errors[?(@.field=='filter')]").isNotEmpty());
    }

    @Test
    @DisplayName("POST query: aggregate groupBy + sum; plain filtered list")
    void aggregateQuery() throws Exception {
        mockMvc.perform(post("/api/v1/runtime/JournalEntry").with(jwtFor(TENANT))
                        .contentType("application/json")
                        .content("{\"entryDate\":\"2026-08-21\",\"status\":\"POSTED\",\"amount\":10}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/runtime/JournalEntry").with(jwtFor(TENANT))
                        .contentType("application/json")
                        .content("{\"entryDate\":\"2026-08-21\",\"status\":\"POSTED\",\"amount\":32}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/runtime/JournalEntry/query").with(jwtFor(TENANT))
                        .contentType("application/json").content("""
                                { "filter": { "field": "status", "op": "eq", "value": "POSTED" },
                                  "groupBy": ["status"],
                                  "aggregates": [ { "op": "count" }, { "op": "sum", "field": "amount", "alias": "total" } ] }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].count").isNumber())
                .andExpect(jsonPath("$.rows[0].total").isNumber());
    }

    @Test
    @DisplayName("batch: per-item outcomes, mixed ops; cap 500 rejects")
    void batchPerItemOutcomes() throws Exception {
        mockMvc.perform(post("/api/v1/runtime/batch").with(jwtFor(TENANT))
                        .contentType("application/json").content("""
                                { "items": [
                                  { "op": "create", "entity": "Ticket",
                                    "record": { "title": "batch-1" } },
                                  { "op": "create", "entity": "NoSuchEntity",
                                    "record": {} },
                                  { "op": "delete", "entity": "Ticket",
                                    "id": "00000000-0000-4000-8000-000000000000", "version": 1 } ] }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomes[0].status").value("ok"))
                .andExpect(jsonPath("$.outcomes[1].status").value("error"))
                .andExpect(jsonPath("$.outcomes[2].status").value("error"));
    }

    @Test
    @DisplayName("Idempotency-Key: replay returns the original outcome and never re-draws the sequence (§5)")
    void idempotentCreate() throws Exception {
        String body = "{\"title\":\"idem\"}";
        MvcResult first = mockMvc.perform(post("/api/v1/runtime/Ticket").with(jwtFor(TENANT))
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/v1/runtime/Ticket").with(jwtFor(TENANT))
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // exactly one Ticket titled idem exists — the replay did not create a second
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM rec_records WHERE entity_id='Erp.Ticket' "
                        + "AND data->>'title' = 'idem' AND NOT deleted", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("RLS + scoping: a second tenant's reads fail closed to its own rows (§9 item 3)")
    void crossTenantFailsClosed() throws Exception {
        // second tenant sees none of the first tenant's rows
        MvcResult listed = mockMvc.perform(get("/api/v1/runtime/Ticket").with(jwtFor(SECOND_TENANT))
                        .param("page", java.net.URLEncoder.encode("{\"size\":200}",
                                java.nio.charset.StandardCharsets.UTF_8)))
                .andExpect(status().isOk()).andReturn();
        int visible = MAPPER.readTree(listed.getResponse().getContentAsString()).get("rows").size();
        assertThat(visible).isZero();

        // cross-tenant point read 404s
        MvcResult anyId = mockMvc.perform(get("/api/v1/runtime/Ticket").with(jwtFor(TENANT))
                        .param("page", java.net.URLEncoder.encode("{\"size\":1}",
                                java.nio.charset.StandardCharsets.UTF_8)))
                .andExpect(status().isOk()).andReturn();
        String victim = MAPPER.readTree(anyId.getResponse().getContentAsString())
                .get("rows").get(0).get("id").asString();
        mockMvc.perform(get("/api/v1/runtime/Ticket/" + victim).with(jwtFor(SECOND_TENANT)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("T4: metadata.published evicts the warm cache and re-materializes (new entity serves without redeploy)")
    void publishInvalidatesCacheAndMaterializes() throws Exception {
        // warm the cache with v1
        mockMvc.perform(get("/api/v1/runtime/Ticket").with(jwtFor(TENANT)).param("page",
                        java.net.URLEncoder.encode("{\"size\":1}", java.nio.charset.StandardCharsets.UTF_8)))
                .andExpect(status().isOk());
        assertThat(resolver.cacheSize()).isGreaterThanOrEqualTo(1);

        // publish v2: adds a new entity to the bundle
        appV2 = DefinitionParser.parseApp(APP_JSON.replace(
                "{ \"apiName\": \"Ticket\",",
                "{ \"apiName\": \"Flash\", \"fields\": [ { \"apiName\": \"name\", \"type\": \"text\" } ] },\n  { \"apiName\": \"Ticket\","));
        Map<String, Object> envelope = Map.of(
                "event", "metadata.published",
                "tenantId", TENANT.toString(),
                "appId", APP_ID.toString(),
                "version", 2,
                "publishedAt", "2026-08-21T00:00:00.000Z",
                "actorId", ACTOR.toString());
        // PHASE-3 §4 rebind: the envelope rides the spine topic novaforge.metadata
        kafkaTemplate.send(MetadataPublishedSubscriber.TOPIC,
                APP_ID + ":" + TENANT, MAPPER.writeValueAsString(envelope)).get(10, java.util.concurrent.TimeUnit.SECONDS);

        await().atMost(Duration.ofSeconds(10)).until(() -> {
            try {
                int status = mockMvc.perform(post("/api/v1/runtime/Flash").with(jwtFor(TENANT))
                                .contentType("application/json").content("{\"name\":\"new\"}"))
                        .andReturn().getResponse().getStatus();
                return status == 200;
            } catch (Exception e) {
                return false;
            }
        });
        appV2 = null;   // restore v1 for other suites
    }

    @Test
    @DisplayName("PermissionSet matrix grants app roles server-side; hidden fields strip; readonly rejects (PHASE-2 §9)")
    void permissionSetEnforcement() throws Exception {
        // the demo actor already holds platform admin — grant a second, clerk-only actor
        UUID clerkUser = UUID.randomUUID();
        jdbc.update("INSERT INTO platform.users (id, username) VALUES (?, ?) ON CONFLICT DO NOTHING",
                clerkUser, "clerk-" + clerkUser);
        jdbc.update("INSERT INTO platform.role_assignments (tenant_id, user_id, role) VALUES (?, ?, 'Erp.clerk') "
                + "ON CONFLICT DO NOTHING", TENANT, clerkUser);

        var clerkJwt = jwt()
                .jwt(token -> token.claim("tenant_id", TENANT.toString()).subject(clerkUser.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));

        // create allowed by the matrix; the shaped response strips the hidden title
        MvcResult created = mockMvc.perform(post("/api/v1/runtime/Ticket").with(clerkJwt)
                        .contentType("application/json").content("{\"title\":\"clerk ticket\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").doesNotExist())
                .andExpect(jsonPath("$.number").isNotEmpty())
                .andReturn();
        String id = MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();

        // list strips the hidden field server-side for the clerk too
        MvcResult listed = mockMvc.perform(get("/api/v1/runtime/Ticket").with(clerkJwt)
                        .param("page", java.net.URLEncoder.encode(
                                "{\"size\":50}", java.nio.charset.StandardCharsets.UTF_8)))
                .andExpect(status().isOk()).andReturn();
        assertThat(MAPPER.readTree(listed.getResponse().getContentAsString()).get("rows").toString())
                .doesNotContain("\"title\"");

        // update denied by the matrix; the platform admin still writes freely
        mockMvc.perform(patch("/api/v1/runtime/Ticket/" + id).with(clerkJwt)
                        .contentType("application/json").content("{\"version\":1,\"title\":\"x\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/runtime/Ticket/" + id).with(clerkJwt).param("version", "1"))
                .andExpect(status().isForbidden());

        // a clerk cannot see records of an entity the matrix denies (JournalEntry has no grant)
        mockMvc.perform(get("/api/v1/runtime/JournalEntry").with(clerkJwt).param("page",
                        java.net.URLEncoder.encode("{\"size\":1}", java.nio.charset.StandardCharsets.UTF_8)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("platform-admin API: admin-gated tenant provisioning path and role assignment (PHASE-2 §10)")
    void adminApiGating() throws Exception {
        // non-admin (plain authenticated) → 403
        mockMvc.perform(post("/api/v1/admin/tenants/" + TENANT + "/role-assignments")
                        .with(jwtFor(TENANT))
                        .contentType("application/json")
                        .content("{\"userId\":\"" + ACTOR + "\",\"role\":\"Erp.clerk\"}"))
                .andExpect(status().isForbidden());

        // platform admin (demo carries platform_roles via the claim) assigns an app role
        var adminJwt = jwt()
                .jwt(token -> token.claim("tenant_id", TENANT.toString()).subject(ACTOR.toString())
                        .claim("platform_roles", List.of("admin")))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
        mockMvc.perform(post("/api/v1/admin/tenants/" + TENANT + "/role-assignments")
                        .with(adminJwt)
                        .contentType("application/json")
                        .content("{\"userId\":\"" + ACTOR + "\",\"role\":\"Erp.clerk\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        // the permission change rides the outbox → novaforge.permission (ARCHITECTURE §5.5)
        await().atMost(java.time.Duration.ofSeconds(10))
                .until(() -> jdbc.queryForObject(
                        "SELECT count(*) FROM event_outbox WHERE event_type = 'permission.role.assigned'",
                        Integer.class) >= 1);
        try (org.apache.kafka.clients.consumer.KafkaConsumer<String, String> consumer =
                     new org.apache.kafka.clients.consumer.KafkaConsumer<>(java.util.Map.of(
                             "bootstrap.servers", kafkaBootstrap,
                             "group.id", "test-perm-" + System.nanoTime(),
                             "auto.offset.reset", "earliest",
                             "enable.auto.commit", "true",
                             "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer",
                             "value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer"))) {
            consumer.subscribe(java.util.List.of("novaforge.permission"));
            long deadline = System.currentTimeMillis() + 30_000;
            boolean seen = false;
            while (System.currentTimeMillis() < deadline && !seen) {
                for (var record : consumer.poll(java.time.Duration.ofSeconds(1))) {
                    if (record.value().contains("\"permission.role.assigned\"")
                            && record.value().contains("\"Erp.clerk\"")) {
                        seen = true;
                    }
                }
            }
            assertThat(seen).as("permission.role.assigned relayed to novaforge.permission").isTrue();
        }
    }

    @Test
    @DisplayName("Kafka spine (§4): outbox rows relay to novaforge.record keyed tenant:entity:record")
    void kafkaSpineRelay() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/runtime/Ticket").with(jwtFor(TENANT))
                        .contentType("application/json").content("{\"title\":\"spine\"}"))
                .andExpect(status().isOk()).andReturn();
        String recordId = MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();

        try (org.apache.kafka.clients.consumer.KafkaConsumer<String, String> consumer =
                     new org.apache.kafka.clients.consumer.KafkaConsumer<>(java.util.Map.of(
                             "bootstrap.servers", kafkaBootstrap,
                             "group.id", "test-" + System.nanoTime(),
                             "auto.offset.reset", "earliest",
                             "enable.auto.commit", "true",
                             "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer",
                             "value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer"))) {
            consumer.subscribe(java.util.List.of("novaforge.record"));
            long deadline = System.currentTimeMillis() + 30_000;
            boolean seen = false;
            while (System.currentTimeMillis() < deadline && !seen) {
                for (var record : consumer.poll(java.time.Duration.ofSeconds(1))) {
                    if (record.key().equals(TENANT + ":Erp.Ticket:" + recordId)
                            && record.value().contains("\"record.created\"")
                            && record.value().contains(recordId)) {
                        seen = true;
                    }
                }
            }
            assertThat(seen).as("record.created relayed to novaforge.record").isTrue();
        }
        // the relay marked the published rows
        await().atMost(java.time.Duration.ofSeconds(10))
                .until(() -> jdbc.queryForObject(
                        "SELECT count(*) FROM event_outbox WHERE record_id = ?::uuid AND published_at IS NOT NULL",
                        Integer.class, recordId) >= 1);
    }

    @Test
    @DisplayName("gapless sequence survives a rolled-back transaction without losing a number (§9 item 2)")
    void gaplessRollbackKeepsNumbersContiguous() throws Exception {
        // A create that fails validation AFTER the sequence was drawn (defaults run before
        // validations) rolls the draw back with the transaction.
        mockMvc.perform(post("/api/v1/runtime/JournalEntry").with(jwtFor(TENANT))
                        .contentType("application/json")
                        .content("{\"entryDate\":\"2026-08-21\",\"status\":\"BOGUS\"}"))
                .andExpect(status().isBadRequest());
        List<Long> state = jdbc.queryForList(
                "SELECT next_value - 1 FROM seq_state WHERE sequence_name = 'entryNumber'", Long.class);
        long next = state.isEmpty() ? 0L : state.getFirst();
        assertThat(next).isGreaterThanOrEqualTo(0);
        // The next successful create draws the immediate successor — no gap.
        MvcResult ok = mockMvc.perform(post("/api/v1/runtime/JournalEntry").with(jwtFor(TENANT))
                        .contentType("application/json").content("{\"entryDate\":\"2026-08-21\"}"))
                .andExpect(status().isOk()).andReturn();
        String reference = MAPPER.readTree(ok.getResponse().getContentAsString()).get("reference").asString();
        assertThat(reference).isEqualTo("JE-" + String.format("%06d", next + 1));
    }
}
