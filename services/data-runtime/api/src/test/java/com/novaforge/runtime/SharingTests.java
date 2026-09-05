package com.novaforge.runtime;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novaforge.metadata.DefinitionParser;
import com.novaforge.runtime.engine.metadata.MetadataClient;
import com.novaforge.runtime.storage.materializer.Materializer;
import com.novaforge.testsupport.PostgresTestBase;
import java.util.ArrayList;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Record-level sharing (PHASE-4 §10, §14 item 5): the visibility matrix per role —
 * owner rules (the creator plus the named roles), roleHierarchy rules (seniors see
 * juniors' records, never the reverse; unleveled roles widen nobody), criteria rules
 * (compiled expressions shared with the named roles), platform-admin breadth, the
 * no-rule default preserved (full visibility under the matrix — no silent
 * tightening), and the same evaluation governing writes: a record outside
 * visibility reads as absent.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SharingTests extends PostgresTestBase {

    static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID ADMIN = UUID.fromString("33333333-3333-4333-8333-333333333333");
    static final UUID CLERK1 = UUID.randomUUID();
    static final UUID CLERK2 = UUID.randomUUID();
    static final UUID MANAGER = UUID.randomUUID();
    static final UUID AUDITOR = UUID.randomUUID();
    static final UUID APP_ID = UUID.fromString("cccccccc-dddd-4eee-8fff-000000000001");

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    static final String APP_JSON = """
            { "apiName": "Desk",
              "permissionSet": {
                "roles": [
                  { "name": "clerk", "level": 2 },
                  { "name": "manager", "level": 1 },
                  { "name": "auditor" } ],
                "objectPermissions": [
                  { "role": "clerk", "entity": "Case", "create": true, "read": true, "update": true },
                  { "role": "manager", "entity": "Case", "create": true, "read": true, "update": true },
                  { "role": "auditor", "entity": "Case", "create": true, "read": true, "update": true },
                  { "role": "clerk", "entity": "Plain", "create": true, "read": true },
                  { "role": "auditor", "entity": "Plain", "create": true, "read": true },
                  { "role": "clerk", "entity": "Review", "create": true, "read": true },
                  { "role": "manager", "entity": "Review", "create": true, "read": true },
                  { "role": "auditor", "entity": "Review", "create": true, "read": true },
                  { "role": "auditor", "entity": "Dossier", "create": true, "read": true } ],
                "sharingRules": [
                  { "entity": "Case", "type": "owner", "roles": ["manager"] },
                  { "entity": "Case", "type": "criteria", "roles": ["auditor"],
                    "criteria": "amount > 100" },
                  { "entity": "Review", "type": "roleHierarchy", "roles": ["manager"] },
                  { "entity": "Dossier", "type": "criteria", "roles": ["auditor"],
                    "criteria": "nosuchfn(amount) > 100" } ] },
              "entities": [
                { "apiName": "Case",
                  "displayField": "subject",
                  "fields": [
                    { "apiName": "subject", "type": "text", "required": true },
                    { "apiName": "amount", "type": "decimal", "precision": 18, "scale": 4 } ] },
                { "apiName": "Plain",
                  "fields": [
                    { "apiName": "label", "type": "text", "required": true } ] },
                { "apiName": "Review",
                  "displayField": "subject",
                  "fields": [
                    { "apiName": "subject", "type": "text", "required": true } ] },
                { "apiName": "Dossier",
                  "displayField": "subject",
                  "fields": [
                    { "apiName": "subject", "type": "text", "required": true },
                    { "apiName": "amount", "type": "decimal", "precision": 18, "scale": 4 } ] } ] }
            """;

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
    static void seed(@Autowired Materializer materializer, @Autowired JdbcTemplate jdbc) {
        materializer.apply(DefinitionParser.parseApp(APP_JSON));
        seedActor(jdbc, CLERK1, "Desk.clerk");
        seedActor(jdbc, CLERK2, "Desk.clerk");
        seedActor(jdbc, MANAGER, "Desk.manager");
        seedActor(jdbc, AUDITOR, "Desk.auditor");
    }

    private static void seedActor(JdbcTemplate jdbc, UUID user, String role) {
        jdbc.update("INSERT INTO platform.users (id, username) VALUES (?, ?) "
                + "ON CONFLICT DO NOTHING", user, "u-" + user);
        jdbc.update("INSERT INTO platform.role_assignments (tenant_id, user_id, role) "
                + "VALUES (?, ?, ?) ON CONFLICT DO NOTHING", TENANT, user, role);
    }

    @Test
    @DisplayName("the §14 item 5 matrix: owner sees own, named role sees all, criteria shares, admin broad")
    void visibilityMatrix() throws Exception {
        String ownSmall = createCase(CLERK1, "clerk1-small", 10);
        String otherBig = createCase(CLERK2, "clerk2-big", 500);

        // a clerk sees their own record, not a peer's
        mockMvc.perform(get("/api/v1/runtime/Case/" + ownSmall).with(jwtFor(CLERK1)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/runtime/Case/" + otherBig).with(jwtFor(CLERK1)))
                .andExpect(status().isNotFound());

        // the owner rule's named role (manager) sees everything
        mockMvc.perform(get("/api/v1/runtime/Case/" + otherBig).with(jwtFor(MANAGER)))
                .andExpect(status().isOk());

        // the criteria rule shares matching records with the auditor — and only those
        mockMvc.perform(get("/api/v1/runtime/Case/" + otherBig).with(jwtFor(AUDITOR)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/runtime/Case/" + ownSmall).with(jwtFor(AUDITOR)))
                .andExpect(status().isNotFound());

        // platform admin: unrestricted
        mockMvc.perform(get("/api/v1/runtime/Case/" + ownSmall).with(jwtFor(ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("lists carry the same visibility: own rows only for peers, all for the named role")
    void listVisibility() throws Exception {
        createCase(CLERK1, "l-own", 20);
        createCase(CLERK2, "l-other", 20);
        createCase(CLERK2, "l-big", 999);

        // each peer's list reflects exactly their own rows (tests share the corpus)
        mockMvc.perform(get("/api/v1/runtime/Case").with(jwtFor(CLERK1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(ownedBy(CLERK1)));
        mockMvc.perform(get("/api/v1/runtime/Case").with(jwtFor(CLERK2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(ownedBy(CLERK2)));
        // the owner rule's named role sees the union — never less than either peer
        mockMvc.perform(get("/api/v1/runtime/Case").with(jwtFor(MANAGER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(
                        ownedBy(CLERK1) + ownedBy(CLERK2))));
    }

    @Test
    @DisplayName("writes ride the same evaluation — an invisible record reads as absent (§10)")
    void writesGovernedBySharing() throws Exception {
        String other = createCase(CLERK2, "w-target", 30);
        mockMvc.perform(patch("/api/v1/runtime/Case/" + other).with(jwtFor(CLERK1))
                        .contentType("application/json")
                        .content("{\"version\":1,\"subject\":\"hijacked\"}"))
                .andExpect(status().isNotFound());
        // the owner still can
        mockMvc.perform(patch("/api/v1/runtime/Case/" + other).with(jwtFor(CLERK2))
                        .contentType("application/json")
                        .content("{\"version\":1,\"subject\":\"renamed\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("no rules defined: the Phase 2 default holds — full visibility, no silent tightening")
    void noRulesDefault() throws Exception {
        String other = createPlain(CLERK1, "p1");
        mockMvc.perform(get("/api/v1/runtime/Plain/" + other).with(jwtFor(AUDITOR)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("roleHierarchy: seniors see juniors' records; juniors never see seniors'; unleveled widens nobody (§16 Q2)")
    void roleHierarchyVisibility() throws Exception {
        String clerks = createReview(CLERK1, "h-junior");
        String managers = createReview(MANAGER, "h-senior");
        String auditors = createReview(AUDITOR, "h-unleveled");

        // the manager (level 1) sees records owned by the strictly less senior clerk (2)
        mockMvc.perform(get("/api/v1/runtime/Review/" + clerks).with(jwtFor(MANAGER)))
                .andExpect(status().isOk());
        // the clerk (level 2) does not see the senior's record — only their own
        mockMvc.perform(get("/api/v1/runtime/Review/" + managers).with(jwtFor(CLERK1)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/runtime/Review/" + clerks).with(jwtFor(CLERK1)))
                .andExpect(status().isOk());
        // an unleveled role carries no seniority — its records stay invisible to
        // leveled actors (and it sees only its own)
        mockMvc.perform(get("/api/v1/runtime/Review/" + auditors).with(jwtFor(CLERK1)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/runtime/Review/" + auditors).with(jwtFor(MANAGER)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/runtime/Review/" + auditors).with(jwtFor(AUDITOR)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a sharing-restricted list sorted by an unpromoted numeric field rides the splice intact")
    void restrictedListSortedByUnpromotedNumeric() throws Exception {
        // Case.amount is unpromoted (only the display field promotes), so sorting by
        // it lowers the ORDER BY to the shape-gated numeric cast — whose regex
        // literal carries four question marks of its own. The restricted scope
        // splices its visibility clause before that tail; counting the literal ?s
        // as bind placeholders corrupted the parameter list — the splice threw
        // (IndexOutOfBoundsException) or, with enough filter binds in front,
        // reordered real binds into the wrong slots (a 500 from the driver).
        // Found hunting; pinned here at the wire through the real splice.
        createCase(CLERK1, "splice-small", 111.25);
        createCase(CLERK2, "splice-big", 7777.5);
        createCase(CLERK2, "splice-mid", 250.5);

        // the auditor's criteria rule (amount > 100) shares exactly those rows —
        // the auditor owns no Case — in numeric DESC order
        MvcResult listed = mockMvc.perform(
                        post("/api/v1/runtime/Case/query").with(jwtFor(AUDITOR))
                                .contentType("application/json")
                                .content("{\"sort\":[{\"field\":\"amount\",\"dir\":\"desc\"}]}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = MAPPER.readTree(listed.getResponse().getContentAsString()).path("rows");
        List<Double> mine = new ArrayList<>();
        for (JsonNode row : rows) {
            // every shared row satisfies the criterion — never a widened bind slot
            org.assertj.core.api.Assertions.assertThat(row.path("amount").asDouble())
                    .isGreaterThan(100.0);
            String subject = row.path("subject").asString();
            if (subject.startsWith("splice-")) {
                mine.add(row.path("amount").asDouble());
            }
        }
        org.assertj.core.api.Assertions.assertThat(mine)
                .containsExactly(7777.5, 250.5, 111.25);

        // the same splice with filter binds in front (the misalignment mode that
        // reordered real binds instead of throwing) — still the shared scope, still
        // numeric order
        mockMvc.perform(post("/api/v1/runtime/Case/query").with(jwtFor(AUDITOR))
                        .contentType("application/json")
                        .content("""
                                { "filter": { "and": [
                                    { "field": "subject", "op": "contains", "value": "splice" },
                                    { "field": "amount", "op": "gt", "value": 0 } ] },
                                  "sort": [ { "field": "amount", "dir": "asc" } ] }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].subject").value("splice-small"))
                .andExpect(jsonPath("$.rows[1].subject").value("splice-mid"))
                .andExpect(jsonPath("$.rows[2].subject").value("splice-big"));
    }

    private int ownedBy(UUID actor) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM rec_records WHERE entity_id = 'Desk.Case' "
                        + "AND NOT deleted AND created_by = ?", Integer.class, actor);
        return count == null ? 0 : count;
    }

    // --- helpers ---

    private String createCase(UUID actor, String subject, double amount) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/runtime/Case").with(jwtFor(actor))
                        .contentType("application/json")
                        .content("{\"subject\":\"" + subject + "\",\"amount\":" + amount + "}"))
                .andExpect(status().isOk()).andReturn();
        return MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();
    }

    private String createPlain(UUID actor, String label) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/runtime/Plain").with(jwtFor(actor))
                        .contentType("application/json")
                        .content("{\"label\":\"" + label + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();
    }

    private String createReview(UUID actor, String subject) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/runtime/Review").with(jwtFor(actor))
                        .contentType("application/json")
                        .content("{\"subject\":\"" + subject + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();
    }

    @Test
    @DisplayName("a criterion that cannot evaluate/lowers fails CLOSED: row hidden, lists 403 — never widened")
    void brokenCriteriaFailClosed() throws Exception {
        // the Dossier rule names auditor with a criterion no evaluator knows
        // (nosuchfn): the per-record predicate must treat the failure as
        // not-visible, and the SQL lowering must refuse to drop the clause.
        // The platform admin authors the row — the auditor owns nothing here, so
        // the owner leg can never masquerade as the broken criterion passing.
        String dossier = createDossierAsAdmin("b-1");

        // point read: the broken criterion hides the record (fail closed, NOT visible)
        mockMvc.perform(get("/api/v1/runtime/Dossier/" + dossier).with(jwtFor(AUDITOR)))
                .andExpect(status().isNotFound());

        // list: the criterion cannot lower — the restricted scope refuses the
        // whole query (403) instead of silently widening to every row
        mockMvc.perform(get("/api/v1/runtime/Dossier").with(jwtFor(AUDITOR)))
                .andExpect(status().isForbidden());

        // the platform admin (unrestricted) still sees the row intact
        mockMvc.perform(get("/api/v1/runtime/Dossier/" + dossier).with(jwtFor(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("b-1"));
    }

    private String createDossierAsAdmin(String subject) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/runtime/Dossier").with(jwtFor(ADMIN))
                        .contentType("application/json")
                        .content("{\"subject\":\"" + subject + "\",\"amount\":555}"))
                .andExpect(status().isOk()).andReturn();
        return MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();
    }

    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(UUID actor) {
        return jwt()
                .jwt(token -> token.claim("tenant_id", TENANT.toString()).subject(actor.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
    }
}
