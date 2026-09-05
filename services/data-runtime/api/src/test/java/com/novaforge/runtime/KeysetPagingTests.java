package com.novaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novaforge.metadata.DefinitionParser;
import com.novaforge.runtime.engine.metadata.MetadataClient;
import com.novaforge.runtime.storage.materializer.Materializer;
import com.novaforge.testsupport.PostgresTestBase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * The keyset (seek) paging contract — PHASE-1 §5's pinned landing, the §12 Q2
 * closeout (the deep-offset measurement fired the trigger: the per-page count(*)
 * alone cost 364.9 ms at 1M rows):
 *
 * <ul>
 *   <li>{@code page.after} seeks past the previous full page's {@code nextAfter};
 *       a seek walk serves exactly the rows an offset walk serves, in the same
 *       effective order (declared sorts + the {@code id} tiebreaker), every row
 *       exactly once — null sort keys included (Postgres's nulls-largest default:
 *       last in ASC, first in DESC).</li>
 *   <li>{@code after} and {@code offset} are mutually exclusive (reject); a garbled
 *       cursor and a cursor minted for a different sort reject VALIDATION_FAILED at
 *       the door.</li>
 *   <li>A seek page skips the per-page count and OMITS {@code total}; offset pages
 *       carry it. {@code nextAfter} rides only a full page (the walk's end on a
 *       partial one).</li>
 *   <li>Field security holds at the seek door: seeking by a field hidden for the
 *       actor rejects FORBIDDEN, and {@code nextAfter} is never minted when a
 *       sort-key value the actor cannot see would have to ride inside the cursor
 *       (base64 is an encoding, not cryptography).</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class KeysetPagingTests extends PostgresTestBase {

    static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID CLERK = UUID.fromString("33333333-3333-4333-8333-333333331111");
    static final UUID VIEWER = UUID.fromString("33333333-3333-4333-8333-333333332222");
    static final UUID APP_ID = UUID.fromString("cccccccc-dddd-4eee-8fff-000000000070");

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    static final String APP_JSON = """
            { "apiName": "KeysetDesk",
              "permissionSet": {
                "roles": [
                  { "name": "clerk", "level": 2 },
                  { "name": "viewer", "level": 3 } ],
                "objectPermissions": [
                  { "role": "clerk", "entity": "Entry", "create": true, "read": true },
                  { "role": "viewer", "entity": "Entry", "read": true } ],
                "fieldSecurity": [
                  { "role": "viewer", "entity": "Entry", "field": "amount", "access": "hidden" } ] },
              "entities": [
                { "apiName": "Entry",
                  "displayField": "memo",
                  "fields": [
                    { "apiName": "memo", "type": "text", "required": true },
                    { "apiName": "status", "type": "enum", "values": ["POSTED","DRAFT"], "required": true },
                    { "apiName": "dueDate", "type": "date" },
                    { "apiName": "amount", "type": "decimal", "precision": 18, "scale": 4 } ],
                  "indexes": [ { "fields": ["memo"] }, { "fields": ["dueDate"] },
                               { "fields": ["amount"] } ] } ] }
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
                    List.of(new MetadataClient.PublishedApp(APP_ID, "KeysetDesk", 1)));
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
        seedActor(jdbc, CLERK, "KeysetDesk.clerk");
        seedActor(jdbc, VIEWER, "KeysetDesk.viewer");
        // seven rows: a three-way dueDate tie (the id tiebreaker's chain), a null
        // due (nulls-largest ordering across page edges), and a numeric pair whose
        // text forms differ ("90" vs "90.00") but whose numeric order is a tie
        seedEntry(jdbc, "m1", "POSTED", "2026-01-05", "10.5");
        seedEntry(jdbc, "m2", "POSTED", "2026-01-05", "90");
        seedEntry(jdbc, "m3", "DRAFT", "2026-03-01", "5");
        seedEntry(jdbc, "m4", "POSTED", null, "100");
        seedEntry(jdbc, "m5", "DRAFT", "2026-02-10", "90.00");
        seedEntry(jdbc, "m6", "POSTED", "2026-01-05", "42");
        seedEntry(jdbc, "m7", "DRAFT", "2026-04-20", "0.0001");
    }

    private static void seedActor(JdbcTemplate jdbc, UUID user, String role) {
        jdbc.update("INSERT INTO platform.users (id, username) VALUES (?, ?) "
                + "ON CONFLICT DO NOTHING", user, "u-" + user);
        jdbc.update("INSERT INTO platform.role_assignments (tenant_id, user_id, role) "
                + "VALUES (?, ?, ?) ON CONFLICT DO NOTHING", TENANT, user, role);
    }

    private static void seedEntry(JdbcTemplate jdbc, String memo, String status,
                                  String dueDate, String amount) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("memo", memo);
        data.put("status", status);
        if (dueDate != null) {
            data.put("dueDate", dueDate);
        }
        data.put("amount", new java.math.BigDecimal(amount));
        jdbc.update("""
                INSERT INTO rec_records (id, tenant_id, entity_id, version, created_by, updated_by, deleted, data)
                VALUES (?, ?, 'KeysetDesk.Entry', 1, ?, ?, false, ?::jsonb)""",
                UUID.randomUUID(), TENANT, CLERK, CLERK, MAPPER.writeValueAsString(data));
    }

    // --- the wire ---

    /** One GET list page as JSON: filter/sort/page as the §5 canonical encoded DSL nodes. */
    private JsonNode listPage(UUID actor, String sortJson, String pageJson, String filterJson,
                              int expectedStatus)
            throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/runtime/Entry")
                        .with(jwtFor(actor))
                        .param("sort", sortJson == null ? "" : sortJson)
                        .param("filter", filterJson == null ? "" : filterJson)
                        .param("page", pageJson))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return body.isBlank() ? null : MAPPER.readTree(body);
    }

    private JsonNode listPageOk(UUID actor, String sortJson, String pageJson) throws Exception {
        return listPage(actor, sortJson, pageJson, null, 200);
    }

    /** The offset walk: every row's id, page by page, the pre-keyset shape. */
    private List<String> offsetWalk(UUID actor, String sortJson, String filterJson, int size)
            throws Exception {
        List<String> ids = new ArrayList<>();
        for (int offset = 0;; offset += size) {
            String page = "{\"size\":" + size + ",\"offset\":" + offset + "}";
            MvcResult result = mockMvc.perform(get("/api/v1/runtime/Entry")
                            .with(jwtFor(actor))
                            .param("filter", filterJson == null ? "" : filterJson)
                            .param("sort", sortJson == null ? "" : sortJson)
                            .param("page", page))
                    .andExpect(status().isOk()).andReturn();
            JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
            body.path("rows").forEach(row -> ids.add(row.path("id").asString()));
            if (body.path("rows").size() < size) {
                return ids;
            }
        }
    }

    /** The seek walk: follow nextAfter to exhaustion, asserting each page's contract. */
    private List<String> seekWalk(UUID actor, String sortJson, String filterJson, int size)
            throws Exception {
        List<String> ids = new ArrayList<>();
        String after = null;
        int pages = 0;
        do {
            StringBuilder page = new StringBuilder("{\"size\":" + size);
            if (after != null) {
                page.append(",\"after\":\"").append(after).append("\"");
            }
            page.append("}");
            JsonNode body = listPage(actor, sortJson, page.toString(), filterJson, 200);
            // a seek page skips the per-page count and omits total (§5)
            if (after != null) {
                assertThat(body.has("total"))
                        .as("a seek page omits total").isFalse();
            }
            int rowCount = body.path("rows").size();
            body.path("rows").forEach(row -> ids.add(row.path("id").asString()));
            boolean full = rowCount == size;
            // nextAfter rides only a page that came back full
            assertThat(body.has("nextAfter")).as("nextAfter on full pages only")
                    .isEqualTo(full);
            after = body.has("nextAfter") ? body.path("nextAfter").asString() : null;
            pages++;
            assertThat(pages).as("the walk terminates").isLessThan(50);
        } while (after != null);
        return ids;
    }

    // --- §5: the seek walk serves the offset walk's rows, in the effective order ---

    @Test
    @DisplayName("seek walk == offset walk under a declared sort: the three-way due tie and the null due land once each")
    void seekWalkMatchesOffsetWalkUnderDeclaredSort() throws Exception {
        String sort = "[{\"field\":\"dueDate\",\"dir\":\"desc\"}]";
        List<String> offset = offsetWalk(CLERK, sort, null, 3);
        List<String> seek = seekWalk(CLERK, sort, null, 3);
        assertThat(offset).hasSize(7);
        assertThat(seek).containsExactlyElementsOf(offset);   // same order, each row once
        // the null-due row sorts FIRST under DESC (Postgres nulls largest). The row
        // renders the absent key (never written) — ordering treats it as SQL NULL.
        JsonNode firstPage = listPageOk(CLERK, sort, "{\"size\":3}");
        assertThat(firstPage.path("rows").get(0).has("dueDate"))
                .as("the null-due row leads a DESC page (nulls largest → first)")
                .isFalse();
    }

    @Test
    @DisplayName("seek walk == offset walk with a filter composed: the conjunct rides after the filter")
    void seekComposesWithFilters() throws Exception {
        String sort = "[{\"field\":\"dueDate\",\"dir\":\"desc\"}]";
        String filter = "{\"field\":\"status\",\"op\":\"eq\",\"value\":\"POSTED\"}";
        List<String> offset = offsetWalk(CLERK, sort, filter, 2);
        List<String> seek = seekWalk(CLERK, sort, filter, 2);
        assertThat(offset).hasSize(4);
        assertThat(seek).containsExactlyElementsOf(offset);
    }

    @Test
    @DisplayName("a sortless list is seekable: its effective order is the id tiebreaker alone")
    void sortlessListIsSeekableById() throws Exception {
        List<String> offset = offsetWalk(CLERK, null, null, 3);
        List<String> seek = seekWalk(CLERK, null, null, 3);
        assertThat(offset).hasSize(7);
        assertThat(seek).containsExactlyElementsOf(offset);
    }

    @Test
    @DisplayName("numeric sort keys seek decimal-exact: \"90\" and \"90.00\" tie through the chain")
    void numericKeysSeekExactly() throws Exception {
        String sort = "[{\"field\":\"amount\",\"dir\":\"asc\"}]";
        List<String> offset = offsetWalk(CLERK, sort, null, 2);
        List<String> seek = seekWalk(CLERK, sort, null, 2);
        assertThat(seek).containsExactlyElementsOf(offset);
    }

    @Test
    @DisplayName("a seek page omits total; an offset page carries it; the cursor keeps working after")
    void seekPagesOmitTotal() throws Exception {
        String sort = "[{\"field\":\"dueDate\",\"dir\":\"asc\"}]";
        JsonNode offsetPage = listPageOk(CLERK, sort, "{\"size\":3}");
        assertThat(offsetPage.path("total").asLong()).isEqualTo(7);
        assertThat(offsetPage.has("nextAfter")).isTrue();
        String after = offsetPage.path("nextAfter").asString();
        JsonNode seekPage = listPageOk(CLERK, sort,
                "{\"size\":3,\"after\":\"" + after + "\"}");
        assertThat(seekPage.has("total")).isFalse();
        assertThat(seekPage.path("rows").size()).isEqualTo(3);
        // the walk continues from a seek page's own cursor
        assertThat(seekPage.has("nextAfter")).isTrue();
        JsonNode tail = listPageOk(CLERK, sort,
                "{\"size\":3,\"after\":\"" + seekPage.path("nextAfter").asString() + "\"}");
        assertThat(tail.path("rows").size()).isEqualTo(1);
        assertThat(tail.has("nextAfter")).isFalse();   // partial page: the walk's end
    }

    // --- §5: the door rejects what it cannot honor ---

    @Test
    @DisplayName("after and offset are mutually exclusive")
    void afterAndOffsetReject() throws Exception {
        String sort = "[{\"field\":\"dueDate\",\"dir\":\"asc\"}]";
        String cursor = listPageOk(CLERK, sort, "{\"size\":3}")
                .path("nextAfter").asString();
        JsonNode problem = listPage(CLERK, sort,
                "{\"size\":3,\"offset\":3,\"after\":\"" + cursor + "\"}", null, 400);
        assertThat(problem.path("title").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(problem.path("errors").get(0).path("field").asString())
                .isEqualTo("page.after");
    }

    @Test
    @DisplayName("a garbled cursor rejects VALIDATION_FAILED at the door")
    void garbledCursorRejects() throws Exception {
        JsonNode problem = listPage(CLERK, null,
                "{\"size\":3,\"after\":\"@@@not-a-cursor@@@\"}", null, 400);
        assertThat(problem.path("title").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(problem.path("errors").get(0).path("field").asString())
                .isEqualTo("page.after");
    }

    @Test
    @DisplayName("a cursor minted for a different sort rejects")
    void wrongSortCursorRejects() throws Exception {
        String amountSort = "[{\"field\":\"amount\",\"dir\":\"asc\"}]";
        String cursor = listPageOk(CLERK, amountSort, "{\"size\":3}")
                .path("nextAfter").asString();
        // same cursor, different declared sort
        JsonNode mismatched = listPage(CLERK, "[{\"field\":\"memo\",\"dir\":\"asc\"}]",
                "{\"size\":3,\"after\":\"" + cursor + "\"}", null, 400);
        assertThat(mismatched.path("title").asString()).isEqualTo("VALIDATION_FAILED");
        // same cursor, sort dropped entirely (effective order would be id asc)
        JsonNode sortless = listPage(CLERK, null,
                "{\"size\":3,\"after\":\"" + cursor + "\"}", null, 400);
        assertThat(sortless.path("title").asString()).isEqualTo("VALIDATION_FAILED");
    }

    // --- §5: field security holds at the seek door ---

    @Test
    @DisplayName("seeking by a field hidden for the actor rejects FORBIDDEN at the seek door")
    void hiddenSortKeyRejectsTheSeek() throws Exception {
        // the clerk (amount visible) mints a cursor under an amount sort
        String sort = "[{\"field\":\"amount\",\"dir\":\"asc\"}]";
        String cursor = listPageOk(CLERK, sort, "{\"size\":3}")
                .path("nextAfter").asString();
        // the viewer (amount hidden) may not seek by it — the filter door's rejection
        JsonNode problem = listPage(VIEWER, sort,
                "{\"size\":3,\"after\":\"" + cursor + "\"}", null, 403);
        assertThat(problem.path("title").asString()).isEqualTo("FORBIDDEN");
    }

    @Test
    @DisplayName("nextAfter is never minted when a sort-key value would ride stripped")
    void noCursorCarriesAHiddenValue() throws Exception {
        // offset paging by a hidden sort key still orders (the pre-pin shape) — but
        // the cursor would carry the hidden amount, so none is minted
        String sort = "[{\"field\":\"amount\",\"dir\":\"asc\"}]";
        JsonNode viewerPage = listPageOk(VIEWER, sort, "{\"size\":3}");
        assertThat(viewerPage.path("rows").size()).isEqualTo(3);
        assertThat(viewerPage.path("rows").get(0).has("amount")).isFalse();
        assertThat(viewerPage.has("nextAfter")).isFalse();
    }

    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(UUID actor) {
        return jwt()
                .jwt(token -> token.claim("tenant_id", TENANT.toString()).subject(actor.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
    }
}
