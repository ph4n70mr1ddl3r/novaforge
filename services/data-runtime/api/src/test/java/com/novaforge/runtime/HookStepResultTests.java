package com.novaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
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
 * The G-1 harvest's execution half (PHASE-7 §3.3, 2026-08-26): a {@code createRecord}
 * step's created record enters flow scope — later steps address it as
 * {@code ${record.<stepId>.<path…>}} ({@code id} included) — and {@code ${…}}
 * template resolution goes deep, so an inline children array inside a createRecord
 * template binds per row: the §5 posting shape (create journal lines from
 * templates) is expressible without the clerk-side workaround the dogfood logged.
 */
@SpringBootTest(properties = {"novaforge.events.relay-interval-ms=3600000"})
@AutoConfigureMockMvc
class HookStepResultTests extends PostgresTestBase {

    static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID ACTOR = UUID.fromString("33333333-3333-4333-8333-333333333333");
    static final UUID APP_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * The app under test: a Bill's afterSave posting flow creates a Journal with an
     * inline (deep-resolved) lines array, then a Voucher naming the created journal
     * through the step-result namespace — {@code ${record.j1.id}} for the lookup and
     * {@code ${record.j1.memo}} for a promoted field.
     */
    static final String APP_JSON = """
            { "apiName": "Books",
              "entities": [
                { "apiName": "Bill",
                  "displayField": "number",
                  "fields": [
                    { "apiName": "number", "type": "text", "required": true },
                    { "apiName": "total", "type": "money" } ],
                  "hooks": [
                    { "name": "post", "trigger": "afterSave", "flow":
                      { "id": "j1", "op": "createRecord",
                        "params": { "entity": "Journal",
                          "template": { "memo": "Bill ${number}",
                            "lines": [
                              { "memo": "AR ${number}", "debit": "${total}" },
                              { "memo": "Revenue", "credit": "${total}" } ] } },
                        "next": "v1",
                        "body": { "id": "v1", "op": "createRecord",
                          "params": { "entity": "Voucher",
                            "template": { "journal": "${record.j1.id}",
                                          "memo": "voucher ${record.j1.memo}" } } } } } ] },
                { "apiName": "Journal",
                  "displayField": "memo",
                  "fields": [
                    { "apiName": "memo", "type": "text" },
                    { "apiName": "totalDebit", "type": "money", "rollup": "SUM(lines.debit)" } ],
                  "relationships": [
                    { "apiName": "lines", "type": "child", "target": "JournalLine",
                      "cascadeDelete": true } ] },
                { "apiName": "JournalLine",
                  "fields": [
                    { "apiName": "entry", "type": "lookup", "target": "Journal",
                      "required": true },
                    { "apiName": "memo", "type": "text" },
                    { "apiName": "debit", "type": "money" },
                    { "apiName": "credit", "type": "money" } ] },
                { "apiName": "Voucher",
                  "displayField": "memo",
                  "fields": [
                    { "apiName": "journal", "type": "lookup", "target": "Journal",
                      "required": true },
                    { "apiName": "memo", "type": "text" } ] } ] }
            """;

    static AppDefinition app;

    @Autowired
    MockMvc mockMvc;

    @TestConfiguration
    static class StubMetadata {

        @Bean
        @Primary
        MetadataClient metadataClient() {
            MetadataClient client = Mockito.mock(MetadataClient.class);
            app = DefinitionParser.parseApp(APP_JSON);
            Mockito.when(client.publishedApps()).thenAnswer(inv ->
                    List.of(new MetadataClient.PublishedApp(APP_ID, "Books", 1)));
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

    @Test
    @DisplayName("createRecord captures its record into step scope; templates resolve deep (§3.3)")
    void createRecordStepResultsAndDeepTemplates() throws Exception {
        // the bill books and posts in one write: the afterSave flow creates the
        // journal (inline lines, deep-resolved) and the voucher (step-result refs)
        MvcResult created = mockMvc.perform(post("/api/v1/runtime/Bill").with(jwtFor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"B-1001\",\"total\":\"120.0000\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String page = java.net.URLEncoder.encode("{\"size\":50}",
                java.nio.charset.StandardCharsets.UTF_8);

        // the journal landed with its top-level template resolved…
        MvcResult journals = mockMvc.perform(get("/api/v1/runtime/Journal").with(jwtFor())
                        .param("page", page))
                .andExpect(status().isOk())
                .andReturn();
        var journal = MAPPER.readTree(journals.getResponse().getContentAsString()).get("rows").get(0);
        assertThat(journal.get("memo").asString()).isEqualTo("Bill B-1001");
        String journalId = journal.get("id").asString();
        // …and the roll-up over the deep-resolved inline lines nets the bill total —
        // the children were template-resolved, not passed through as literals
        // (decimal compare: the store's read rendering carries no scale promise)
        assertThat(new java.math.BigDecimal(journal.get("totalDebit").asString()))
                .isEqualByComparingTo("120.0000");

        // the voucher's lookup names the created journal — ${record.j1.id} — and its
        // memo walks a promoted field of the created view — ${record.j1.memo}
        MvcResult vouchers = mockMvc.perform(get("/api/v1/runtime/Voucher").with(jwtFor())
                        .param("page", page))
                .andExpect(status().isOk())
                .andReturn();
        var voucher = MAPPER.readTree(vouchers.getResponse().getContentAsString()).get("rows").get(0);
        assertThat(voucher.get("journal").asString()).isEqualTo(journalId);
        assertThat(voucher.get("memo").asString()).isEqualTo("voucher Bill B-1001");

        // the inline children resolved per row: the AR line carries the bill number
        // and the debit; the revenue line the credit — never the literal "${total}"
        MvcResult lines = mockMvc.perform(get("/api/v1/runtime/JournalLine").with(jwtFor())
                        .param("page", page))
                .andExpect(status().isOk())
                .andReturn();
        String rows = lines.getResponse().getContentAsString();
        assertThat(rows).contains("\"memo\":\"AR B-1001\"").contains("\"debit\":120");
        assertThat(rows).contains("\"memo\":\"Revenue\"").contains("\"credit\":120");
        assertThat(rows).doesNotContain("${total}").doesNotContain("${number}");
    }
}
