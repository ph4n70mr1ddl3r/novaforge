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
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Definition lifecycle over the real stores (PHASE-1 §9 item 4): save-validation matrix
 * through the API, publish immutability, version list/export round-trip, breaking-change
 * acknowledgment, and the {@code metadata.published} envelope on the spine topic
 * {@code novaforge.metadata} (PHASE-3 §4 — the Phase 1 Redis pub/sub channel is retired).
 */
@SpringBootTest
@AutoConfigureMockMvc
class DefinitionLifecycleTests extends PostgresTestBase {

    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    static {
        // Class-load order: subscription (@BeforeAll) and property registration
        // (DynamicPropertySource) both need the container up first.
        KAFKA.start();
    }

    private static final String TENANT = "11111111-1111-4111-8111-111111111111";
    private static final String ACTOR = "33333333-3333-4333-8333-333333333333";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final BlockingQueue<String> PUBLISHED_EVENTS = new LinkedBlockingQueue<>();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestBase::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestBase::jdbcUsername);
        registry.add("spring.datasource.password", PostgresTestBase::jdbcPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @BeforeAll
    static void subscribeToPublishTopic() {
        // A raw consumer from earliest on a throwaway group — every envelope the
        // publisher emits during this class lands in the assertion queue.
        Thread reader = new Thread(() -> {
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(java.util.Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                    ConsumerConfig.GROUP_ID_CONFIG, "metadata-lifecycle-test-" + UUID.randomUUID(),
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                    org.apache.kafka.common.serialization.StringDeserializer.class.getName(),
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                    org.apache.kafka.common.serialization.StringDeserializer.class.getName()))) {
                consumer.subscribe(java.util.List.of(
                        com.novaforge.metadata.events.MetadataPublishEventPublisher.TOPIC));
                while (!Thread.currentThread().isInterrupted()) {
                    consumer.poll(Duration.ofMillis(200))
                            .forEach(record -> PUBLISHED_EVENTS.add(record.value()));
                }
            } catch (Exception e) {
                // container gone at JVM shutdown — nothing left to assert against
            }
        }, "metadata-published-reader");
        reader.setDaemon(true);
        reader.start();
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
                                      "expect": "error(SOD_VIOLATION)" },
                                    { "op": "scanSla",
                                      "template": { "advance": "PT26H" }, "expect": "ok" } ],
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
        // scanSla carries exactly one governing instant — neither rejects…
        mockMvc.perform(put("/api/v1/metadata/apps/" + appId + "/test-suites/no-clock")
                        .with(builderJwt()).contentType("application/json")
                        .content("""
                                { "apiName": "no-clock", "cases": [ { "name": "c",
                                  "steps": [ { "op": "scanSla", "expect": "ok" } ] } ] }
                                """))
                .andExpect(status().isBadRequest());
        // …both reject…
        mockMvc.perform(put("/api/v1/metadata/apps/" + appId + "/test-suites/two-clocks")
                        .with(builderJwt()).contentType("application/json")
                        .content("""
                                { "apiName": "two-clocks", "cases": [ { "name": "c",
                                  "steps": [ { "op": "scanSla",
                                  "template": { "advance": "PT26H", "asOf": "2026-08-24T00:00:00Z" },
                                  "expect": "ok" } ] } ] }
                                """))
                .andExpect(status().isBadRequest());
        // …and a malformed duration rejects at save, not as an aborted case
        mockMvc.perform(put("/api/v1/metadata/apps/" + appId + "/test-suites/bad-clock")
                        .with(builderJwt()).contentType("application/json")
                        .content("""
                                { "apiName": "bad-clock", "cases": [ { "name": "c",
                                  "steps": [ { "op": "scanSla",
                                  "template": { "advance": "in 26 hours" },
                                  "expect": "ok" } ] } ] }
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

    /** The trusted service client (ServiceClientGate's azp match) — the full-bundle reader. */
    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor serviceJwt() {
        return jwt()
                .jwt(token -> token.claim("azp", "novaforge-runtime")
                        .claim("client_id", "novaforge-runtime"))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
    }

    /** One page save carrying a revision token; the HTTP status for race assertions. */
    private int savePageStatus(String appId, int revision) {
        try {
            return mockMvc.perform(put("/api/v1/metadata/apps/" + appId + "/pages/orderForm")
                            .with(builderJwt()).contentType("application/json")
                            .content("""
                                    { "apiName": "orderForm", "type": "form", "entity": "Order",
                                      "layout": { "base": "auto", "kind": "form", "deltas": [] },
                                      "revision": %d }
                                    """.formatted(revision)))
                    .andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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

        // 9. both publish envelopes rode the spine topic (the PHASE-3 §4 rebind)
        java.util.List<String> mine = new java.util.ArrayList<>();
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            PUBLISHED_EVENTS.drainTo(mine);
            return mine.stream().filter(envelope -> appId.equals(appIdOf(envelope))).count() >= 2;
        });
        java.util.List<String> own = mine.stream()
                .filter(envelope -> appId.equals(appIdOf(envelope))).toList();
        String first = own.get(0);
        String second = own.get(1);
        assertThat(MAPPER.readTree(first).get("event").asString()).isEqualTo("metadata.published");
        assertThat(MAPPER.readTree(first).get("appId").asString()).isEqualTo(appId);
        assertThat(MAPPER.readTree(first).get("version").asInt()).isEqualTo(1);
        assertThat(MAPPER.readTree(second).get("version").asInt()).isEqualTo(2);
        assertThat(MAPPER.readTree(first).get("tenantId").asString()).isEqualTo(TENANT);
    }

    private String appIdOf(String envelope) {
        try {
            return MAPPER.readTree(envelope).get("appId").asString();
        } catch (Exception e) {
            return "";
        }
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
    @DisplayName("app PATCH-merge never wipes the permission set (roles/matrix/sharing survive a label edit)")
    void appPatchKeepsPermissionSet() throws Exception {
        // the ERP shape: roles + a report job pinning runAsRole + a role-visible
        // dashboard — found live driving the Phase 8 exit leg (a description PATCH
        // rejected with must-resolve role errors; on apps without role references
        // the same PATCH silently dropped the whole permission set)
        MvcResult created = mockMvc.perform(post("/api/v1/metadata/apps")
                        .with(builderJwt())
                        .contentType("application/json")
                        .content("""
                                {
                                  "apiName": "PermissionMerge", "label": "Permission Merge",
                                  "entities": [
                                    { "apiName": "Invoice", "displayField": "name",
                                      "fields": [ { "apiName": "name", "type": "text" },
                                        { "apiName": "status", "type": "enum",
                                          "values": ["DRAFT", "POSTED"] } ] } ],
                                  "reports": [ { "id": "rep_ar", "entity": "Invoice",
                                    "aggregates": [ { "op": "count" } ] } ],
                                  "dashboards": [ { "id": "exec", "roles": ["controller"],
                                    "widgets": [ { "widget": "kpi", "reportRef": "rep_ar", "span": 6 } ] } ],
                                  "jobs": [ { "name": "nightlyAging", "cron": "0 0 2 * * *",
                                    "target": "report",
                                    "params": { "reportId": "rep_ar", "runAsRole": "reporting",
                                      "recipients": { "roles": ["controller"] }, "format": "csv" } } ],
                                  "permissionSet": {
                                    "roles": [ { "name": "controller" },
                                               { "name": "reporting" } ],
                                    "objectPermissions": [
                                      { "role": "controller", "entity": "Invoice",
                                        "read": true } ] }
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        String appId = MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();

        // a patch that touches nothing but the description must not adopt the patch's
        // (empty-by-default) permission set — the merge treats absent as absent
        mockMvc.perform(patch("/api/v1/metadata/apps/" + appId)
                        .with(builderJwt())
                        .contentType("application/json")
                        .content("{ \"description\": \"patched live\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("patched live"))
                .andExpect(jsonPath("$.permissionSet.roles[?(@.name=='reporting')]").isNotEmpty())
                .andExpect(jsonPath("$.permissionSet.roles[?(@.name=='controller')]").isNotEmpty())
                .andExpect(jsonPath("$.permissionSet.objectPermissions[0].entity").value("Invoice"));

        // an explicit permissionSet patch still lands (the RBAC editor's save path)
        mockMvc.perform(patch("/api/v1/metadata/apps/" + appId)
                        .with(builderJwt())
                        .contentType("application/json")
                        .content("""
                                { "permissionSet": {
                                    "roles": [ { "name": "controller" },
                                               { "name": "reporting" },
                                               { "name": "clerk" } ] } }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissionSet.roles[?(@.name=='clerk')]").isNotEmpty());
    }

    @Test
    @DisplayName("PATCH list branches: an explicit empty list clears — the last item is removable")
    void appPatchEmptyListClears() throws Exception {
        // Anti-regression (2026-08-31): AppDefinition's canonical constructor
        // normalizes absent branches to empty lists, so the PATCH merge could never
        // distinguish "omitted" (keep) from "emptied" (clear) — {"dashboards": []}
        // silently kept the branch and the last item of every list branch was
        // unremovable through the API. AppPatch binds presence verbatim.
        MvcResult created = mockMvc.perform(post("/api/v1/metadata/apps")
                        .with(builderJwt())
                        .contentType("application/json")
                        .content("""
                                {
                                  "apiName": "LastDashboard", "label": "Last Dashboard",
                                  "entities": [
                                    { "apiName": "Invoice", "displayField": "name",
                                      "fields": [ { "apiName": "name", "type": "text" } ] } ],
                                  "reports": [ { "id": "rep_ar", "entity": "Invoice",
                                    "aggregates": [ { "op": "count" } ] } ],
                                  "dashboards": [ { "id": "exec", "roles": ["controller"],
                                    "widgets": [ { "widget": "kpi", "reportRef": "rep_ar", "span": 6 } ] } ],
                                  "permissionSet": { "roles": [ { "name": "controller" } ] }
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        String appId = MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();

        // an absent branch keeps — a label-only patch never touches dashboards
        mockMvc.perform(patch("/api/v1/metadata/apps/" + appId)
                        .with(builderJwt())
                        .contentType("application/json")
                        .content("{ \"label\": \"Renamed\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dashboards.length()").value(1))
                .andExpect(jsonPath("$.label").value("Renamed"));

        // an explicit empty list clears — the last dashboard finally leaves
        mockMvc.perform(patch("/api/v1/metadata/apps/" + appId)
                        .with(builderJwt())
                        .contentType("application/json")
                        .content("{ \"dashboards\": [] }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dashboards.length()").value(0))
                .andExpect(jsonPath("$.reports.length()").value(1));   // untouched branches stay
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
    @DisplayName("BPMN workflows (§9): valid definitions save and publish; broken ones reject at save, bad filters at publish")
    void workflowDefinitions() throws Exception {
        // a clean workflow rides the app definition and publishes
        String bpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="novaforge">
                  <process id="po_review" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="review"/>
                    <userTask id="review" name="Review" flowable:candidateGroups="manager"/>
                    <sequenceFlow id="f2" sourceRef="review" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.lines().map(line -> line.replace("\\", "\\\\").replace("\"", "\\\""))
                .reduce((a, b) -> a + "\\n" + b).orElseThrow();
        String app = """
                { "apiName": "FlowApp", "entities": [ { "apiName": "PurchaseOrder",
                  "fields": [ { "apiName": "total", "type": "money" },
                              { "apiName": "status", "type": "enum",
                                "values": [ "DRAFT", "SUBMITTED", "APPROVED" ] } ] } ],
                  "workflows": [ { "id": "po_review", "bpmn": "%s",
                    "eventStarts": [ { "event": "record.updated",
                                       "entity": "PurchaseOrder",
                                       "filter": "status == 'SUBMITTED'" } ] } ] }
                """.formatted(bpmn);
        MvcResult created = mockMvc.perform(post("/api/v1/metadata/apps")
                        .with(builderJwt()).contentType("application/json").content(app))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflows[0].id").value("po_review"))
                .andReturn();
        String appId = MAPPER.readTree(created.getResponse().getContentAsString())
                .get("id").asString();
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/publish")
                        .with(builderJwt()))
                .andExpect(status().isOk());

        // regression (the branch-persistence fix): drafts AND published bundles
        // round-trip every Phase-4 branch — state machines, SLAs, scheduled jobs,
        // workflows — so downstream consumers read what authors authored
        MvcResult published = mockMvc.perform(get("/api/v1/metadata/apps/" + appId + "/published")
                        .with(userJwt())).andExpect(status().isOk()).andReturn();
        var tree = MAPPER.readTree(published.getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(
                tree.at("/app/workflows").toString()).contains("po_review");
        // the full-branch app carries the other kinds too when authored — verified
        // through the draft read (the same assembly path)
        MvcResult draft = mockMvc.perform(get("/api/v1/metadata/apps/" + appId)
                        .with(builderJwt())).andExpect(status().isOk()).andReturn();
        org.assertj.core.api.Assertions.assertThat(
                MAPPER.readTree(draft.getResponse().getContentAsString())
                        .at("/workflows").toString()).contains("po_review");

        // a branch-bearing app: state machine + SLA + job ride the same round trip
        String machineApp = """
                { "apiName": "BranchApp", "entities": [ { "apiName": "Order",
                  "fields": [ { "apiName": "status", "type": "enum",
                                "values": [ "DRAFT", "POSTED" ] } ] } ],
                  "stateMachines": [ { "id": "sm_order", "entity": "Order",
                    "stateField": "status", "initial": "DRAFT",
                    "states": [ { "name": "DRAFT" }, { "name": "POSTED", "terminal": true } ],
                    "transitions": [ { "from": "DRAFT", "to": "POSTED" } ] } ],
                  "slas": [ { "id": "sla_x", "scope": { "taskType": "approval" },
                    "target": "PT2H" } ],
                  "jobs": [ { "name": "sweep", "cron": "0 0 3 * * *", "target": "flow",
                    "params": { "entity": "Order", "hook": "sweep" } } ] }
                """;
        MvcResult branchApp = mockMvc.perform(post("/api/v1/metadata/apps")
                        .with(builderJwt()).contentType("application/json").content(machineApp))
                .andExpect(status().isOk()).andReturn();
        String branchId = MAPPER.readTree(branchApp.getResponse().getContentAsString())
                .get("id").asString();
        mockMvc.perform(post("/api/v1/metadata/apps/" + branchId + "/publish")
                        .with(builderJwt())).andExpect(status().isOk());
        MvcResult branchPublished = mockMvc.perform(
                        get("/api/v1/metadata/apps/" + branchId + "/published").with(userJwt()))
                .andExpect(status().isOk()).andReturn();
        String publishedJson = MAPPER.readTree(
                branchPublished.getResponse().getContentAsString()).at("/app").toString();
        org.assertj.core.api.Assertions.assertThat(publishedJson)
                .contains("sm_order").contains("sla_x").contains("sweep");

        // a non-compiling event-start filter rejects — the compile rides save and
        // publish like every expression slot (§9)
        String brokenFilter = app.replace("status == 'SUBMITTED'", "bogusField == 'x'");
        MvcResult broken = mockMvc.perform(post("/api/v1/metadata/apps")
                        .with(builderJwt()).contentType("application/json")
                        .content(brokenFilter.replace("FlowApp", "FlowApp2")))
                .andExpect(status().isBadRequest())
                .andReturn();
        // the unknown binding reports through the workflow scope
        org.assertj.core.api.Assertions.assertThat(
                        MAPPER.readTree(broken.getResponse().getContentAsString()).toString())
                .contains("eventStarts");

        // process id mismatch rejects at save: the workflow id no longer equals the
        // BPMN <process id> (po_review stays inside the XML)
        String mismatch = app.replace("FlowApp", "FlowApp3")
                .replace("\"id\": \"po_review\", \"bpmn\"", "\"id\": \"other_key\", \"bpmn\"");
        mockMvc.perform(post("/api/v1/metadata/apps")
                        .with(builderJwt()).contentType("application/json").content(mismatch))
                .andExpect(status().isBadRequest());
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

        // SLA match carries the Annex A slot bindings — the spec's own example
        // (entity + transition, PHASE-4 §6) compiles clean…
        mockMvc.perform(post("/api/v1/metadata/apps").with(builderJwt())
                        .contentType("application/json").content("""
                                { "apiName": "SlaApp", "entities": [ { "apiName": "PurchaseOrder",
                                  "fields": [ { "apiName": "name", "type": "text" } ] } ],
                                  "slas": [ { "id": "sla_po",
                                    "scope": { "taskType": "approval",
                                               "match": "entity == 'Purch.PurchaseOrder' && transition == 'DRAFT->SUBMITTED'" },
                                    "target": "PT24H", "warnAt": 0.8,
                                    "onBreach": { "escalateTo": "role:senior-manager",
                                                   "notify": true } } ] }
                                """))
                .andExpect(status().isOk());

        // …while a match referencing a binding no slot provides still rejects
        mockMvc.perform(post("/api/v1/metadata/apps").with(builderJwt())
                        .contentType("application/json").content("""
                                { "apiName": "SlaApp2", "entities": [ { "apiName": "PurchaseOrder",
                                  "fields": [ { "apiName": "name", "type": "text" } ] } ],
                                  "slas": [ { "id": "sla_bad",
                                    "scope": { "match": "record == 'x'" },
                                    "target": "PT24H" } ] }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value(
                        org.hamcrest.Matchers.containsString("sla[sla_bad].scope.match")))
                .andExpect(jsonPath("$.errors[0].message").value(
                        org.hamcrest.Matchers.containsString("unresolved reference 'record'")));

        // §4's approvers-expression form: a root identifier naming a field compiles
        // against the record context…
        mockMvc.perform(post("/api/v1/metadata/apps").with(builderJwt())
                        .contentType("application/json").content("""
                                { "apiName": "ApprApp", "entities": [ { "apiName": "Claim",
                                  "fields": [ { "apiName": "name", "type": "text" },
                                               { "apiName": "manager", "type": "uuid" } ],
                                  "hooks": [ { "name": "route", "trigger": "beforeSave",
                                    "flow": { "id": "a1", "op": "requestApproval",
                                      "params": { "approvers": "manager", "mode": "any" } } } ] } ] }
                                """))
                .andExpect(status().isOk());

        // …and a malformed expression in that form rejects with the parse error
        mockMvc.perform(post("/api/v1/metadata/apps").with(builderJwt())
                        .contentType("application/json").content("""
                                { "apiName": "ApprApp2", "entities": [ { "apiName": "Claim",
                                  "fields": [ { "apiName": "name", "type": "text" },
                                               { "apiName": "manager", "type": "uuid" } ],
                                  "hooks": [ { "name": "route", "trigger": "beforeSave",
                                    "flow": { "id": "a1", "op": "requestApproval",
                                      "params": { "approvers": "manager +" } } } ] } ] }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value(
                        org.hamcrest.Matchers.containsString("requestApproval approvers expression")));
    }

    @Test
    @DisplayName("connector-response flows (§5's scheduled pull): the vocabulary compiles; bad step references reject")
    void connectorResponseFlowCompilerChecks() throws Exception {
        // the bank-feed shape (PHASE-7 §5/T8): a scheduled hook — callConnector →
        // iterate over the response's array → createRecord per row → the
        // recordless publishEvent tail — saves clean, `scheduled` trigger included
        mockMvc.perform(post("/api/v1/metadata/apps").with(builderJwt())
                        .contentType("application/json").content("""
                                { "apiName": "ConnApp", "entities": [ { "apiName": "Payment",
                                  "fields": [ { "apiName": "number", "type": "text" },
                                               { "apiName": "amount", "type": "decimal",
                                                 "precision": 18, "scale": 4 } ],
                                  "hooks": [ { "name": "syncBankFeed", "trigger": "scheduled",
                                    "flow": { "id": "c1", "op": "callConnector",
                                      "params": { "connector": "bankFeed",
                                                  "operation": "listTransactions",
                                                  "template": { "since": "2000-01-01" } },
                                      "next": "i1",
                                      "body": { "id": "i1", "op": "iterate",
                                        "params": { "path": "connector.c1.transactions" },
                                        "next": "e1",
                                        "body": { "id": "p1", "op": "createRecord",
                                          "params": { "entity": "Payment",
                                            "template": { "number": "${txn_id}",
                                              "amount": "${amount}" } },
                                          "body": { "id": "e1", "op": "publishEvent",
                                            "params": { "name": "ledger.bankfeed.synced",
                                              "payload": { "ref": "${connector.c1.cursor}" } } } } } } } ] } ],
                                  "integrations": { "connectors": [ { "id": "bankFeed",
                                    "type": "rest", "baseUrl": "https://bank.example.local",
                                    "operations": [ { "name": "listTransactions", "method": "GET",
                                      "path": "/v1/transactions" } ] } ] } }
                                """))
                .andExpect(status().isOk());

        // an iterate connector path addressing a non-callConnector step rejects:
        // the path names p1 (a createRecord), not the callConnector that ran
        mockMvc.perform(post("/api/v1/metadata/apps").with(builderJwt())
                        .contentType("application/json").content("""
                                { "apiName": "ConnApp3", "entities": [ { "apiName": "Payment",
                                  "fields": [ { "apiName": "number", "type": "text" } ],
                                  "hooks": [ { "name": "sync", "trigger": "scheduled",
                                    "flow": { "id": "c1", "op": "callConnector",
                                      "params": { "connector": "bankFeed",
                                                  "operation": "listTransactions",
                                                  "template": { "since": "2000-01-01" } },
                                      "next": "i1",
                                      "body": { "id": "i1", "op": "iterate",
                                        "params": { "path": "connector.p1.transactions" },
                                        "next": "e1",
                                        "body": { "id": "p1", "op": "createRecord",
                                          "params": { "entity": "Payment",
                                            "template": { "number": "x" } },
                                          "body": { "id": "e1", "op": "publishEvent",
                                            "params": { "name": "ledger.synced" } } } } } } ] } ],
                                  "integrations": { "connectors": [ { "id": "bankFeed",
                                    "type": "rest", "baseUrl": "https://bank.example.local",
                                    "operations": [ { "name": "listTransactions", "method": "GET",
                                      "path": "/v1/transactions" } ] } ] } }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value(
                        org.hamcrest.Matchers.containsString(
                                "connector reference must address a callConnector step")));

        // a record template referencing a connector step that does not exist rejects
        mockMvc.perform(post("/api/v1/metadata/apps").with(builderJwt())
                        .contentType("application/json").content("""
                                { "apiName": "ConnApp4", "entities": [ { "apiName": "Payment",
                                  "fields": [ { "apiName": "number", "type": "text" } ],
                                  "hooks": [ { "name": "sync", "trigger": "scheduled",
                                    "flow": { "id": "c1", "op": "callConnector",
                                      "params": { "connector": "bankFeed",
                                                  "operation": "listTransactions",
                                                  "template": { "since": "2000-01-01" } },
                                      "next": "p1",
                                      "body": { "id": "p1", "op": "createRecord",
                                        "params": { "entity": "Payment",
                                          "template": { "number": "${connector.ghost.ref}" } } } } } ] } ],
                                  "integrations": { "connectors": [ { "id": "bankFeed",
                                    "type": "rest", "baseUrl": "https://bank.example.local",
                                    "operations": [ { "name": "listTransactions", "method": "GET",
                                      "path": "/v1/transactions" } ] } ] } }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value(
                        org.hamcrest.Matchers.containsString(
                                "connector reference must address a callConnector step")));
    }

    @Test
    @DisplayName("createRecord step results + deep templates (§3.3): the vocabulary compiles; bad references and child rows reject")
    void recordResultFlowCompilerChecks() throws Exception {
        // the posting shape (PHASE-7 §5, the G-1 harvest): create the parent with an
        // inline children array, then address the created record — id and a promoted
        // field — through the record.<stepId> namespace. Saves clean.
        mockMvc.perform(post("/api/v1/metadata/apps").with(builderJwt())
                        .contentType("application/json").content("""
                                { "apiName": "PostApp", "entities": [
                                  { "apiName": "Bill",
                                    "fields": [ { "apiName": "number", "type": "text" },
                                                 { "apiName": "total", "type": "money" } ],
                                    "hooks": [ { "name": "post", "trigger": "afterSave",
                                      "flow": { "id": "j1", "op": "createRecord",
                                        "params": { "entity": "Journal",
                                          "template": { "memo": "Bill ${number}",
                                            "lines": [
                                              { "memo": "AR", "debit": "${total}" },
                                              { "memo": "Revenue", "credit": "${total}" } ] } },
                                        "next": "v1",
                                        "body": { "id": "v1", "op": "createRecord",
                                          "params": { "entity": "Voucher",
                                            "template": { "journal": "${record.j1.id}",
                                              "memo": "${record.j1.memo}" } } } } } ] },
                                  { "apiName": "Journal",
                                    "fields": [ { "apiName": "memo", "type": "text" } ],
                                    "relationships": [
                                      { "apiName": "lines", "type": "child",
                                        "target": "JournalLine", "cascadeDelete": true } ] },
                                  { "apiName": "JournalLine",
                                    "fields": [ { "apiName": "entry", "type": "lookup",
                                                  "target": "Journal", "required": true },
                                                 { "apiName": "memo", "type": "text" },
                                                 { "apiName": "debit", "type": "money" },
                                                 { "apiName": "credit", "type": "money" } ] },
                                  { "apiName": "Voucher",
                                    "fields": [ { "apiName": "journal", "type": "lookup",
                                                  "target": "Journal" },
                                                 { "apiName": "memo", "type": "text" } ] } ] }
                                """))
                .andExpect(status().isOk());

        // a record reference addressing a non-createRecord step rejects (v1 names a
        // setField, not the createRecord that ran)
        mockMvc.perform(post("/api/v1/metadata/apps").with(builderJwt())
                        .contentType("application/json").content("""
                                { "apiName": "PostApp2", "entities": [
                                  { "apiName": "Bill",
                                    "fields": [ { "apiName": "number", "type": "text" } ],
                                    "hooks": [ { "name": "post", "trigger": "afterSave",
                                      "flow": { "id": "s1", "op": "setField",
                                          "params": { "field": "number",
                                            "expression": "'x' + number" },
                                        "next": "v1",
                                        "body": { "id": "v1", "op": "createRecord",
                                          "params": { "entity": "Voucher",
                                            "template": { "memo": "${record.s1.id}" } } } } } ] },
                                  { "apiName": "Voucher",
                                    "fields": [ { "apiName": "memo", "type": "text" } ] } ] }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value(
                        org.hamcrest.Matchers.containsString(
                                "record reference must address a createRecord step")));

        // an inline children row addressing a field the child does not carry rejects
        // with the child entity named — deep-checked, not just the top-level map
        mockMvc.perform(post("/api/v1/metadata/apps").with(builderJwt())
                        .contentType("application/json").content("""
                                { "apiName": "PostApp3", "entities": [
                                  { "apiName": "Bill",
                                    "fields": [ { "apiName": "number", "type": "text" } ],
                                    "hooks": [ { "name": "post", "trigger": "afterSave",
                                      "flow": { "id": "j1", "op": "createRecord",
                                        "params": { "entity": "Journal",
                                          "template": { "lines": [
                                            { "ghost": "x" } ] } } } } ] },
                                  { "apiName": "Journal",
                                    "fields": [ { "apiName": "memo", "type": "text" } ],
                                    "relationships": [
                                      { "apiName": "lines", "type": "child",
                                        "target": "JournalLine", "cascadeDelete": true } ] },
                                  { "apiName": "JournalLine",
                                    "fields": [ { "apiName": "entry", "type": "lookup",
                                                  "target": "Journal", "required": true },
                                                 { "apiName": "memo", "type": "text" } ] } ] }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value(
                        org.hamcrest.Matchers.containsString(
                                "inline child field must exist on JournalLine: ghost")));
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
    @DisplayName("published read is caller-shaped (ARCHITECTURE §2.3): user tokens get the rendering view, the service client the full bundle")
    void publishedReadStripsScriptsAndCredentialRefsForUsers() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/metadata/apps").with(builderJwt())
                        .contentType("application/json").content("""
                                { "apiName": "ViewApp", "entities": [ { "apiName": "Thing",
                                  "fields": [ { "apiName": "name", "type": "text" } ],
                                  "hooks": [ { "name": "enrich", "trigger": "beforeSave",
                                    "script": { "language": "js",
                                      "source": "({ name: $record.name.toUpperCase() })" } } ] } ],
                                  "integrations": { "credentials": [
                                    { "id": "cred_view", "kind": "api_key", "header": "X-Key" } ] } }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        String appId = MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/publish").with(builderJwt()))
                .andExpect(status().isOk());

        // a user caller reads the rendering view: no script artifact, no credential refs
        String userView = mockMvc.perform(get("/api/v1/metadata/apps/" + appId + "/published")
                        .with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.app.entities[0].apiName").value("Thing"))
                .andReturn().getResponse().getContentAsString();
        assertThat(userView).doesNotContain("toUpperCase");
        assertThat(userView).doesNotContain("cred_view");

        // the trusted service client reads the full bundle its write path needs
        String serviceView = mockMvc.perform(get("/api/v1/metadata/apps/" + appId + "/published")
                        .with(serviceJwt()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(serviceView).contains("toUpperCase");
        assertThat(serviceView).contains("cred_view");

        // this test's publish envelope must not leak into the lifecycle test's queue
        await().atMost(Duration.ofSeconds(10)).until(() -> !PUBLISHED_EVENTS.isEmpty());
        PUBLISHED_EVENTS.clear();
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
                                    "fields": [ { "apiName": "status", "type": "enum",
                                                  "values": [ "NEW", "DONE" ] } ] } ],
                                  "stateMachines": [ { "id": "sm_thing", "entity": "Thing",
                                    "stateField": "status", "initial": "NEW",
                                    "states": [ { "name": "NEW" }, { "name": "DONE", "terminal": true } ],
                                    "transitions": [ { "from": "NEW", "to": "DONE" } ] } ] }
                                """))
                .andExpect(status().isOk()).andReturn();
        String appId = MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();
        String appUuid = java.util.UUID.fromString(appId).toString();
        // the kind-discriminated branch landed in md_definitions before the delete
        Integer definitions = jdbc.queryForObject(
                "SELECT count(*) FROM md_definitions WHERE app_id = ?::uuid", Integer.class, appUuid);
        assertThat(definitions).isEqualTo(1);
        mockMvc.perform(delete("/api/v1/metadata/apps/" + appId).with(builderJwt()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/metadata/apps/" + appId).with(builderJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("4004"));
        // Anti-regression (2026-08-31): md_definitions never carried the app FK —
        // the delete cascaded every sibling table but leaked state machines, SLAs,
        // jobs, and workflows (credential references included) forever.
        Integer leaked = jdbc.queryForObject(
                "SELECT count(*) FROM md_definitions WHERE app_id = ?::uuid", Integer.class, appUuid);
        assertThat(leaked).isZero();
    }

    @Test
    @DisplayName("deleting a referenced entity rejects at the door — the draft never wedges")
    void deleteReferencedEntityRejects() throws Exception {
        // Anti-regression (2026-08-31): the delete path skipped the save-validation
        // pass every other writer runs — removing an entity referenced by a page,
        // state machine, or permission branch left the draft failing validation with
        // publish and every re-save blocked until each referencing definition was
        // hand-repaired.
        MvcResult created = mockMvc.perform(post("/api/v1/metadata/apps")
                        .with(builderJwt())
                        .contentType("application/json")
                        .content("""
                                { "apiName": "RefGuard", "entities": [
                                  { "apiName": "Order",
                                    "fields": [ { "apiName": "status", "type": "enum",
                                                  "values": [ "NEW", "DONE" ] } ] },
                                  { "apiName": "Loose",
                                    "fields": [ { "apiName": "name", "type": "text" } ] } ],
                                  "stateMachines": [ { "id": "sm_order", "entity": "Order",
                                    "stateField": "status", "initial": "NEW",
                                    "states": [ { "name": "NEW" }, { "name": "DONE", "terminal": true } ],
                                    "transitions": [ { "from": "NEW", "to": "DONE" } ] } ] }
                                """))
                .andExpect(status().isOk()).andReturn();
        String appId = MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();

        // the referenced entity cannot leave the draft — the validator names the holder
        mockMvc.perform(delete("/api/v1/metadata/apps/" + appId + "/entities/Order")
                        .with(builderJwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value(
                        org.hamcrest.Matchers.containsString("state machine")))
                .andExpect(jsonPath("$.errors[0].message").value(
                        org.hamcrest.Matchers.containsString("Order")));
        // the draft is still publishable — nothing wedged
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/publish").with(builderJwt()))
                .andExpect(status().isOk());
        // an unreferenced entity deletes freely
        mockMvc.perform(delete("/api/v1/metadata/apps/" + appId + "/entities/Loose")
                        .with(builderJwt()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("page definitions (PHASE-2 §4/§8): save-validate, round-trip, publish with the bundle, delete")
    void pageDefinitionLifecycle() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/metadata/apps")
                        .with(builderJwt())
                        .contentType("application/json")
                        .content("""
                                { "apiName": "Pages", "entities": [
                                  { "apiName": "Order", "displayField": "reference",
                                    "fields": [
                                      { "apiName": "reference", "type": "text", "required": true },
                                      { "apiName": "status", "type": "enum",
                                        "values": ["DRAFT", "POSTED"] } ] } ] }
                                """))
                .andExpect(status().isOk()).andReturn();
        String appId = MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();

        // the §4 example shape: an L2 overlay page with expression slots
        mockMvc.perform(put("/api/v1/metadata/apps/" + appId + "/pages/orderForm")
                        .with(builderJwt()).contentType("application/json")
                        .content("""
                                { "apiName": "orderForm", "label": "Order Form", "type": "form",
                                  "entity": "Order",
                                  "layout": {
                                    "base": "auto", "kind": "form",
                                    "deltas": [
                                      { "op": "setProps", "key": "form", "props": { "columns": 2 } },
                                      { "op": "setSlot", "key": "field:status",
                                        "slot": "visibility", "value": "status != 'POSTED'" } ] } }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pages[0].apiName").value("orderForm"))
                .andExpect(jsonPath("$.pages[0].layout.deltas[1].value").value("status != 'POSTED'"));

        // bad type rejects; unresolved entity rejects; bad expression slot rejects
        mockMvc.perform(put("/api/v1/metadata/apps/" + appId + "/pages/bad_type")
                        .with(builderJwt()).contentType("application/json")
                        .content("{ \"apiName\": \"bad_type\", \"type\": \"wizard\" }"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/metadata/apps/" + appId + "/pages/bad_entity")
                        .with(builderJwt()).contentType("application/json")
                        .content("{ \"apiName\": \"bad_entity\", \"type\": \"form\", \"entity\": \"Ghost\" }"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/metadata/apps/" + appId + "/pages/badExpr")
                        .with(builderJwt()).contentType("application/json")
                        .content("""
                                { "apiName": "badExpr", "type": "form", "entity": "Order",
                                  "layout": { "root": { "type": "novaforge.field-input",
                                    "props": { "field": "reference" }, "bind": "reference",
                                    "visibility": "ghostField > 1" } } }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("badExpr.layout"));

        // the closed action ladder, server-side (PHASE-2 §4 + PHASE-3 §8's runFlow):
        // a runFlow action with its hook reference saves; a hookless one and an
        // unknown type reject at save
        mockMvc.perform(put("/api/v1/metadata/apps/" + appId + "/pages/withFlow")
                        .with(builderJwt()).contentType("application/json")
                        .content("""
                                { "apiName": "withFlow", "type": "form", "entity": "Order",
                                  "layout": { "base": "auto", "kind": "form", "deltas": [
                                    { "op": "addAction",
                                      "action": { "type": "runFlow", "props": { "hook": "stampCredit" } } } ] } }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pages[1].apiName").value("withFlow"));
        mockMvc.perform(put("/api/v1/metadata/apps/" + appId + "/pages/hooklessFlow")
                        .with(builderJwt()).contentType("application/json")
                        .content("""
                                { "apiName": "hooklessFlow", "type": "form", "entity": "Order",
                                  "layout": { "base": "auto", "kind": "form", "deltas": [
                                    { "op": "addAction",
                                      "action": { "type": "runFlow", "props": { "hook": "" } } } ] } }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("hooklessFlow.actions"));
        mockMvc.perform(put("/api/v1/metadata/apps/" + appId + "/pages/rogueAction")
                        .with(builderJwt()).contentType("application/json")
                        .content("""
                                { "apiName": "rogueAction", "type": "form", "entity": "Order",
                                  "layout": { "base": "auto", "kind": "form", "deltas": [
                                    { "op": "addAction",
                                      "action": { "type": "runScript" } } ] } }
                                """))
                .andExpect(status().isBadRequest());
        // drop the runFlow page again — the rest of the lifecycle walk below counts pages
        mockMvc.perform(delete("/api/v1/metadata/apps/" + appId + "/pages/withFlow")
                        .with(builderJwt()))
                .andExpect(status().isOk());

        // concurrent edit protection (§8): a stale revision 409s; the rebased save succeeds
        MvcResult saved = mockMvc.perform(get("/api/v1/metadata/apps/" + appId).with(builderJwt()))
                .andExpect(status().isOk()).andReturn();
        int revision = MAPPER.readTree(saved.getResponse().getContentAsString())
                .get("pages").get(0).get("revision").asInt();
        mockMvc.perform(put("/api/v1/metadata/apps/" + appId + "/pages/orderForm")
                        .with(builderJwt()).contentType("application/json")
                        .content("""
                                { "apiName": "orderForm", "type": "form", "entity": "Order",
                                  "layout": { "base": "auto", "kind": "form", "deltas": [] },
                                  "revision": %d }
                                """.formatted(revision - 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("4090"));
        mockMvc.perform(put("/api/v1/metadata/apps/" + appId + "/pages/orderForm")
                        .with(builderJwt()).contentType("application/json")
                        .content("""
                                { "apiName": "orderForm", "type": "form", "entity": "Order",
                                  "layout": { "base": "auto", "kind": "form", "deltas": [] },
                                  "revision": %d }
                                """.formatted(revision)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pages[0].revision").value(revision + 1));

        // Anti-regression (2026-08-31): a save omitting the revision used to clobber
        // an existing page unconditionally — the token is now required to update it.
        mockMvc.perform(put("/api/v1/metadata/apps/" + appId + "/pages/orderForm")
                        .with(builderJwt()).contentType("application/json")
                        .content("""
                                { "apiName": "orderForm", "type": "form", "entity": "Order",
                                  "layout": { "base": "auto", "kind": "form", "deltas": [] } }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("4090"));

        // Anti-regression (2026-08-31): the check was SELECT-then-blind-write — two
        // editors holding the same revision both passed and the second silently won.
        // The conditional update now guarantees exactly one winner even when both
        // pre-checks race past.
        int raced = revision + 1;
        java.util.List<Integer> outcomes = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread editorA = new Thread(() -> outcomes.add(savePageStatus(appId, raced)));
        Thread editorB = new Thread(() -> outcomes.add(savePageStatus(appId, raced)));
        editorA.start();
        editorB.start();
        editorA.join();
        editorB.join();
        assertThat(outcomes).containsExactlyInAnyOrder(200, 409);

        // the page rides the published bundle (versioned metadata like everything else)
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/publish").with(builderJwt()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/metadata/apps/" + appId + "/published").with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.app.pages[0].apiName").value("orderForm"));

        // delete removes the page from the draft
        mockMvc.perform(delete("/api/v1/metadata/apps/" + appId + "/pages/orderForm")
                        .with(builderJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pages.length()").value(0));
    }

    @Test
    @DisplayName("publish events ride the transactional outbox: enqueued with the version, relayed")
    void publishEventsRideTheOutbox() throws Exception {
        // Anti-regression (2026-08-31): metadata.published was sent inside the publish
        // transaction — a broker outage held the connection for the send timeout and a
        // send-then-rollback emitted a phantom event. The outbox row commits with the
        // version; the relay delivers at-least-once.
        MvcResult created = mockMvc.perform(post("/api/v1/metadata/apps")
                        .with(builderJwt())
                        .contentType("application/json")
                        .content("{ \"apiName\": \"OutboxApp\", \"entities\": [ "
                                + "{ \"apiName\": \"Thing\", "
                                + "\"fields\": [ { \"apiName\": \"name\", \"type\": \"text\" } ] } ] }"))
                .andExpect(status().isOk()).andReturn();
        String appId = MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/publish").with(builderJwt()))
                .andExpect(status().isOk());
        String appUuid = java.util.UUID.fromString(appId).toString();
        // the row committed atomically with the version
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            Integer enqueued = jdbc.queryForObject(
                    "SELECT count(*) FROM md_event_outbox WHERE app_id = ?::uuid "
                            + "AND event_type = 'metadata.published'", Integer.class, appUuid);
            return enqueued != null && enqueued >= 1;
        });
        // the relay delivered it to the spine and marked it published
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            Integer relayed = jdbc.queryForObject(
                    "SELECT count(*) FROM md_event_outbox WHERE app_id = ?::uuid "
                            + "AND published_at IS NOT NULL", Integer.class, appUuid);
            return relayed != null && relayed >= 1;
        });
        // the spine subscriber saw the same envelope
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            java.util.List<String> mine = new java.util.ArrayList<>();
            PUBLISHED_EVENTS.drainTo(mine);
            return mine.stream().anyMatch(envelope -> envelope.contains(appId)
                    && envelope.contains("metadata.published"));
        });
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
        /** The spine family topic (PHASE-3 §4) — the retired Redis channel is gone. */
        static final String TOPIC =
                com.novaforge.metadata.events.MetadataPublishEventPublisher.TOPIC;
    }
}
