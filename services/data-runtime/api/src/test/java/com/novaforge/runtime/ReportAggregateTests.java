package com.novaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novaforge.metadata.DefinitionParser;
import com.novaforge.runtime.engine.metadata.MetadataClient;
import com.novaforge.runtime.storage.materializer.Materializer;
import com.novaforge.testsupport.PostgresTestBase;
import java.time.LocalDate;
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
import org.springframework.http.MediaType;
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
 * The aggregate pipeline's Phase 5 contract (PHASE-5 §3/§4, §10 items 1–2 and 5):
 * golden datasets with decimal-exact groupings and aging buckets over the 30/60
 * boundaries; sharing-rule row filters apply to aggregates exactly as to lists
 * (owner sets in SQL, criteria lowered into the same predicate); hidden fields fail
 * closed — aggregates leak values, not rows; and the scheduled path's internal
 * surface executes as the per-app system principal over an explicitly permissioned
 * role, never a bypass.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReportAggregateTests extends PostgresTestBase {

    static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID ADMIN = UUID.fromString("33333333-3333-4333-8333-333333333333");
    static final UUID AR_CLERK1 = UUID.randomUUID();
    static final UUID AR_CLERK2 = UUID.randomUUID();
    static final UUID AR_MANAGER = UUID.randomUUID();
    static final UUID AR_AUDITOR = UUID.randomUUID();
    static final UUID AR_VIEWER = UUID.randomUUID();
    static final UUID APP_ID = UUID.fromString("cccccccc-dddd-4eee-8fff-000000000041");

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Value-normalized decimal (jsonb re-normalizes trailing zeros). */
    static java.math.BigDecimal bd(String literal) {
        return new java.math.BigDecimal(literal).stripTrailingZeros();
    }

    static final String APP_JSON = """
            { "apiName": "ArDesk",
              "permissionSet": {
                "roles": [
                  { "name": "arClerk", "level": 2 },
                  { "name": "arManager", "level": 1 },
                  { "name": "arAuditor" },
                  { "name": "arViewer" },
                  { "name": "arNoRead" } ],
                "objectPermissions": [
                  { "role": "arClerk", "entity": "AgingInvoice", "create": true, "read": true },
                  { "role": "arManager", "entity": "AgingInvoice", "create": true, "read": true },
                  { "role": "arAuditor", "entity": "AgingInvoice", "create": true, "read": true },
                  { "role": "arViewer", "entity": "AgingInvoice", "read": true },
                  { "role": "arNoRead", "entity": "AgingInvoice", "create": true } ],
                "fieldSecurity": [
                  { "role": "arViewer", "entity": "AgingInvoice", "field": "amountOutstanding", "access": "hidden" },
                  { "role": "arViewer", "entity": "AgingInvoice", "field": "customerName", "access": "hidden" } ],
                "sharingRules": [
                  { "entity": "AgingInvoice", "type": "owner", "roles": ["arManager"] },
                  { "entity": "AgingInvoice", "type": "criteria", "roles": ["arAuditor"],
                    "criteria": "amountOutstanding > 100" } ] },
              "entities": [
                { "apiName": "AgingInvoice",
                  "displayField": "customerName",
                  "fields": [
                    { "apiName": "customerName", "type": "text", "required": true },
                    { "apiName": "status", "type": "enum", "values": ["DRAFT","POSTED"], "required": true },
                    { "apiName": "dueDate", "type": "date" },
                    { "apiName": "amountOutstanding", "type": "decimal", "precision": 18, "scale": 4 } ],
                  "indexes": [ { "fields": ["customerName", "dueDate", "amountOutstanding"] } ] } ] }
            """;

    /** The §3 aging shape: buckets over dueDate, grouped by customer. */
    static final String AGING_QUERY = """
            { "groupBy": [
                { "field": "customerName" },
                { "field": "dueDate", "buckets": [
                  { "label": "current", "expression": "today() - dueDate < 0" },
                  { "label": "0-30", "expression": "today() - dueDate >= 0 && today() - dueDate <= 30" },
                  { "label": "31-60", "expression": "today() - dueDate > 30 && today() - dueDate <= 60" },
                  { "label": "60+", "expression": "today() - dueDate > 60" } ] } ],
              "aggregates": [ { "op": "sum", "field": "amountOutstanding" } ] }
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
                    List.of(new MetadataClient.PublishedApp(APP_ID, "ArDesk", 1)));
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

    static LocalDate asOf;

    @BeforeAll
    static void seed(@Autowired Materializer materializer, @Autowired JdbcTemplate jdbc) throws Exception {
        materializer.apply(DefinitionParser.parseApp(APP_JSON));
        seedActor(jdbc, AR_CLERK1, "ArDesk.arClerk");
        seedActor(jdbc, AR_CLERK2, "ArDesk.arClerk");
        seedActor(jdbc, AR_MANAGER, "ArDesk.arManager");
        seedActor(jdbc, AR_AUDITOR, "ArDesk.arAuditor");
        seedActor(jdbc, AR_VIEWER, "ArDesk.arViewer");

        // the golden corpus: every bucket, both boundary edges (30 and 60), two
        // customers sharing a bucket, an unbucketed (null dueDate) row
        asOf = LocalDate.now(java.time.ZoneOffset.UTC);
        seedInvoice(jdbc, AR_CLERK1, "acme", "POSTED", asOf.plusDays(5), "100.5000");
        seedInvoice(jdbc, AR_CLERK1, "acme", "POSTED", asOf.minusDays(30), "200.0000");   // edge: exactly 30
        seedInvoice(jdbc, AR_CLERK2, "globex", "POSTED", asOf.minusDays(31), "50.2500");
        seedInvoice(jdbc, AR_CLERK2, "initech", "POSTED", asOf.minusDays(60), "10.0000");  // edge: exactly 60
        seedInvoice(jdbc, AR_CLERK2, "initech", "POSTED", asOf.minusDays(61), "5.0000");
        seedInvoice(jdbc, AR_CLERK2, "initech", "DRAFT", asOf.minusDays(100), "1.0000");
        seedInvoice(jdbc, AR_CLERK2, "unassigned", "POSTED", null, "7.0000");              // no bucket
    }

    private static void seedActor(JdbcTemplate jdbc, UUID user, String role) {
        jdbc.update("INSERT INTO platform.users (id, username) VALUES (?, ?) "
                + "ON CONFLICT DO NOTHING", user, "u-" + user);
        jdbc.update("INSERT INTO platform.role_assignments (tenant_id, user_id, role) "
                + "VALUES (?, ?, ?) ON CONFLICT DO NOTHING", TENANT, user, role);
    }

    private static void seedInvoice(JdbcTemplate jdbc, UUID actor, String customer,
                                    String status, LocalDate due, String amount) {
        UUID id = UUID.randomUUID();
        // a null due date rides as an absent key — CAST over an empty string raises
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("customerName", customer);
        data.put("status", status);
        if (due != null) {
            data.put("dueDate", due.toString());
        }
        data.put("amountOutstanding", amount);
        jdbc.update("""
                INSERT INTO rec_records (id, tenant_id, entity_id, version, created_by, updated_by, deleted, data)
                VALUES (?, ?, 'ArDesk.AgingInvoice', 1, ?, ?, false, ?::jsonb)""",
                id, TENANT, actor, actor, MAPPER.writeValueAsString(data));
    }

    // --- §10 item 1/2: golden groupings and bucket boundaries, decimal-exact ---

    @Test
    @DisplayName("golden aging: buckets group decimal-exact, the 30/60 edges land per §3, nulls bucket nowhere")
    void goldenAgingBuckets() throws Exception {
        JsonNode rows = aggregateAs(ADMIN, AGING_QUERY).get("rows");
        var sums = new java.util.LinkedHashMap<String, java.math.BigDecimal>();
        String unassignedBucket = "sentinel";
        for (JsonNode row : rows) {
            String bucket = row.path("due_date").isNull() ? null : row.path("due_date").asString();
            if ("unassigned".equals(row.path("customer_name").asString())) {
                unassignedBucket = bucket;
                continue;
            }
            sums.put(row.path("customer_name").asString() + "|" + bucket,
                    row.path("sum_amount_outstanding").decimalValue().stripTrailingZeros());
        }
        // decimal-exact by value (jsonb numerics re-normalize trailing zeros)
        assertThat(sums).containsEntry("acme|current", bd("100.5"));
        assertThat(sums).containsEntry("acme|0-30", bd("200"));
        assertThat(sums).containsEntry("globex|31-60", bd("50.25")); // 31 → 31-60
        assertThat(sums).containsEntry("initech|31-60", bd("10"));   // exactly 60 → 31-60
        assertThat(sums).containsEntry("initech|60+", bd("6"));      // 61 and 100 sum
        // the null-due row lands in no bucket — CASE ELSE NULL
        assertThat(unassignedBucket).isNull();
    }

    @Test
    @DisplayName("saved filters lower: status = POSTED keeps the draft rows out of every bucket")
    void filtersApplyToAggregates() throws Exception {
        String query = AGING_QUERY.replace("{ \"groupBy\"",
                "{ \"filter\": { \"field\": \"status\", \"op\": \"eq\", \"value\": \"POSTED\" }, \"groupBy\"");
        JsonNode rows = aggregateAs(ADMIN, query).get("rows");
        var sums = new java.util.LinkedHashMap<String, java.math.BigDecimal>();
        for (JsonNode row : rows) {
            String bucket = row.path("due_date").isNull() ? null : row.path("due_date").asString();
            sums.put(row.path("customer_name").asString() + "|" + bucket,
                    row.path("sum_amount_outstanding").decimalValue().stripTrailingZeros());
        }
        assertThat(sums).containsEntry("initech|60+", bd("5"));  // the DRAFT 1.00 stays out
        assertThat(sums).containsEntry("unassigned|null", bd("7"));
        assertThat(sums).hasSize(6);
    }

    // --- §4/§10 item 5: sharing applies to aggregates exactly as to lists ---

    @Test
    @DisplayName("an owner-restricted actor aggregates only their rows; the named role sees all")
    void sharingBoundsAggregates() throws Exception {
        // a clerk (no owner rule names arClerk) sees only their own rows: acme only
        JsonNode clerk = aggregateAs(AR_CLERK1, AGING_QUERY).get("rows");
        var clerkCustomers = new java.util.HashSet<String>();
        clerk.forEach(r -> clerkCustomers.add(r.path("customer_name").asString()));
        assertThat(clerkCustomers).containsExactlyInAnyOrder("acme");

        // the manager (owner rule's named role) sees the union
        JsonNode manager = aggregateAs(AR_MANAGER, AGING_QUERY).get("rows");
        var managerSums = new java.util.LinkedHashMap<String, java.math.BigDecimal>();
        manager.forEach(r -> managerSums.merge(r.path("customer_name").asString(),
                r.path("sum_amount_outstanding").decimalValue().stripTrailingZeros(), java.math.BigDecimal::add));
        assertThat(managerSums).containsEntry("acme", bd("300.5"));
        assertThat(managerSums).containsEntry("globex", bd("50.25"));
        assertThat(managerSums).containsEntry("initech", bd("16"));
    }

    @Test
    @DisplayName("criteria sharing lowers into the aggregate predicate — the auditor sums only matches")
    void criteriaSharingLowers() throws Exception {
        JsonNode auditor = aggregateAs(AR_AUDITOR, AGING_QUERY).get("rows");
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        for (JsonNode row : auditor) {
            total = total.add(row.path("sum_amount_outstanding").decimalValue().stripTrailingZeros());
        }
        // criteria per row: amountOutstanding > 100 → 100.5 and 200 qualify (50.25,
        // 10, 5, 1, 7 do not) → the auditor's dataset sums to 300.5 exactly
        assertThat(total).isEqualByComparingTo("300.5");
    }

    @Test
    @DisplayName("hidden fields fail closed on aggregates — a grouped or summed hidden field rejects")
    void hiddenFieldsFailClosed() throws Exception {
        mockMvc.perform(post("/api/v1/runtime/AgingInvoice/query").with(jwtFor(AR_VIEWER))
                        .contentType(MediaType.APPLICATION_JSON).content(AGING_QUERY))
                .andExpect(status().isForbidden());
        String groupHidden = AGING_QUERY.replace("\"field\": \"customerName\"", "\"field\": \"status\"");
        mockMvc.perform(post("/api/v1/runtime/AgingInvoice/query").with(jwtFor(AR_VIEWER))
                        .contentType(MediaType.APPLICATION_JSON).content(groupHidden))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("hidden fields fail closed in FILTERS — a filter leaf is a value oracle over row counts, on every door")
    void hiddenFilterFieldsFailClosed() throws Exception {
        String filtered = AGING_QUERY.replace("{ \"groupBy\": [",
                "{ \"filter\": { \"field\": \"amountOutstanding\", \"op\": \"gt\", \"value\": 100 }, "
                        + "\"groupBy\": [");
        // the aggregate door: the viewer (amountOutstanding hidden) cannot filter on it
        mockMvc.perform(post("/api/v1/runtime/AgingInvoice/query").with(jwtFor(AR_VIEWER))
                        .contentType(MediaType.APPLICATION_JSON).content(filtered))
                .andExpect(status().isForbidden());
        // the list door: the same leaf through the GET filter param — once it rode
        // verbatim and row counts answered binary-search questions about hidden values
        String listFilter = "{\"field\":\"amountOutstanding\",\"op\":\"gt\",\"value\":100}";
        String page = "{\"size\":10}";
        mockMvc.perform(get("/api/v1/runtime/AgingInvoice").with(jwtFor(AR_VIEWER))
                        .param("filter", listFilter).param("page", page))
                .andExpect(status().isForbidden());
        // a filter on a field the viewer CAN read still answers
        String visibleFilter = "{\"field\":\"status\",\"op\":\"eq\",\"value\":\"POSTED\"}";
        mockMvc.perform(get("/api/v1/runtime/AgingInvoice").with(jwtFor(AR_VIEWER))
                        .param("filter", visibleFilter).param("page", page))
                .andExpect(status().isOk());
        // and the role-scoped internal door carries the same stance
        mockMvc.perform(post("/api/v1/hooks/reports/query").with(serviceJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(java.util.Map.of(
                                "tenantId", TENANT.toString(), "app", "ArDesk",
                                "entityApiName", "AgingInvoice", "asRole", "arViewer",
                                "query", MAPPER.readTree(filtered)))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the aggregate door honors a SQL-level limit — the reporting doors' caps ride it (§6)")
    void aggregateLimitLowers() throws Exception {
        String limited = AGING_QUERY.replace("{ \"groupBy\": [",
                "{ \"limit\": 1, \"groupBy\": [");
        // the manager sees all customers grouped; the limit bounds the grouped rows
        // in SQL — over-cap detection never drains the dataset to count it
        JsonNode rows = aggregateAs(AR_MANAGER, limited);
        assertThat(rows.get("rows").size()).isEqualTo(1);
        // a non-positive limit rejects at the parse door
        String bad = AGING_QUERY.replace("{ \"groupBy\": [",
                "{ \"limit\": 0, \"groupBy\": [");
        mockMvc.perform(post("/api/v1/runtime/AgingInvoice/query").with(jwtFor(AR_MANAGER))
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest());
    }

    // --- §7: the scheduled path's role-scoped internal surface ---

    @Test
    @DisplayName("role-scoped internal query: system principal bounded by asRole — matrix, sharing, gate")
    void roleScopedInternalSurface() throws Exception {
        // the auditor scope: only criteria-matching rows, exactly as the actor path
        JsonNode auditor = internalAggregate("arAuditor", AGING_QUERY);
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        for (JsonNode row : auditor.get("rows")) {
            total = total.add(row.path("sum_amount_outstanding").decimalValue().stripTrailingZeros());
        }
        assertThat(total).isEqualByComparingTo("300.5");

        // the manager scope: everything
        JsonNode manager = internalAggregate("arManager", AGING_QUERY);
        java.math.BigDecimal all = java.math.BigDecimal.ZERO;
        for (JsonNode row : manager.get("rows")) {
            all = all.add(row.path("sum_amount_outstanding").decimalValue().stripTrailingZeros());
        }
        assertThat(all).isEqualByComparingTo("373.75");

        // a role without read on the entity fails closed
        internalAggregateRejected("arNoRead", AGING_QUERY);
        // an unknown role fails closed
        internalAggregateRejected("ghost", AGING_QUERY);
        // hidden fields for the role fail closed
        internalAggregateRejected("arViewer", AGING_QUERY);

        // the surface is service-client only
        mockMvc.perform(post("/api/v1/hooks/reports/query").with(jwtFor(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(internalBody("arManager")))
                .andExpect(status().isForbidden());
    }

    // --- helpers ---

    private JsonNode aggregateAs(UUID actor, String query) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/runtime/AgingInvoice/query")
                        .with(jwtFor(actor))
                        .contentType(MediaType.APPLICATION_JSON).content(query))
                .andExpect(status().isOk()).andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private String internalBody(String asRole) {
        return MAPPER.writeValueAsString(java.util.Map.of(
                "tenantId", TENANT.toString(), "app", "ArDesk",
                "entityApiName", "AgingInvoice", "asRole", asRole,
                "query", MAPPER.readTree(AGING_QUERY)));
    }

    private JsonNode internalAggregate(String asRole, String query) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/hooks/reports/query")
                        .with(serviceJwt())
                        .contentType(MediaType.APPLICATION_JSON).content(internalBody(asRole)))
                .andExpect(status().isOk()).andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString()).path("result");
    }

    private void internalAggregateRejected(String asRole, String query) throws Exception {
        mockMvc.perform(post("/api/v1/hooks/reports/query").with(serviceJwt())
                        .contentType(MediaType.APPLICATION_JSON).content(internalBody(asRole)))
                .andExpect(status().isForbidden());
    }

    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(UUID actor) {
        return jwt()
                .jwt(token -> token.claim("tenant_id", TENANT.toString()).subject(actor.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
    }

    /** The platform service client (azp) — the internal report surface's gate. */
    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor serviceJwt() {
        return jwt()
                .jwt(token -> token.claim("azp", "novaforge-runtime")
                        .subject("service-account-novaforge-runtime"))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
    }
}
