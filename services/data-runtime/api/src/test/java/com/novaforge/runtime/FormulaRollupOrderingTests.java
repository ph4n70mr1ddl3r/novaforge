package com.novaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * The formula × roll-up ordering pins (found live 2026-09-03, the re-authored ERP
 * corpus): PHASE-3 §3's chain is one evaluation family — inline children land their
 * roll-ups (and their own formula fields) BEFORE the parent's formulas and
 * validations read them. The ERP's exact shape — parent formula over a roll-up of a
 * child formula — is the pin: {@code Invoice.totalBook = total * fxRate} where
 * {@code total = SUM(lines.amount)} and {@code lines.amount = quantity * unitPrice}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FormulaRollupOrderingTests extends PostgresTestBase {

    static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID ACTOR = UUID.fromString("33333333-3333-4333-8333-333333333333");
    static final UUID APP_ID = UUID.fromString("99999999-9999-4999-8999-999999999999");

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    static final String APP_JSON = """
            { "apiName": "Ar",
              "entities": [
                { "apiName": "Invoice",
                  "displayField": "number",
                  "fields": [
                    { "apiName": "number", "type": "text", "required": true },
                    { "apiName": "fxRate", "type": "decimal", "precision": 18, "scale": 6,
                      "default": { "value": 1 } },
                    { "apiName": "total", "type": "money", "rollup": "SUM(lines.amount)" },
                    { "apiName": "totalBook", "type": "money", "formula": "total * fxRate" } ],
                  "relationships": [
                    { "apiName": "lines", "type": "child", "target": "Line",
                      "cascadeDelete": true } ] },
                { "apiName": "Line",
                  "fields": [
                    { "apiName": "invoice", "type": "lookup", "target": "Invoice",
                      "required": true },
                    { "apiName": "quantity", "type": "decimal", "precision": 18, "scale": 6 },
                    { "apiName": "unitPrice", "type": "money" },
                    { "apiName": "amount", "type": "money", "formula": "quantity * unitPrice" } ] } ] }
            """;

    static AppDefinition app;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @TestConfiguration
    static class StubMetadata {

        @Bean
        @Primary
        MetadataClient metadataClient() {
            MetadataClient client = Mockito.mock(MetadataClient.class);
            app = DefinitionParser.parseApp(APP_JSON);
            Mockito.when(client.publishedApps()).thenAnswer(inv ->
                    List.of(new MetadataClient.PublishedApp(APP_ID, "Ar", 1)));
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

    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor userJwt() {
        return jwt()
                .jwt(token -> token.claim("tenant_id", TENANT.toString()).subject(ACTOR.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
    }

    @Test
    @DisplayName("create with inline children: the child's formula lands before the parent's roll-up reads it, and the roll-up before the parent's formula — the ERP's exact chain, decimal-exact")
    void createEvaluatesChildFormulaThenRollupThenParentFormula() throws Exception {
        // the suite authoring shape verbatim: decimal/money values as exact strings
        MvcResult result = mockMvc.perform(post("/api/v1/runtime/Invoice").with(userJwt())
                        .contentType("application/json")
                        .content("""
                                { "number": "INV-100",
                                  "lines": [ { "quantity": "1", "unitPrice": "120.0000" } ] }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        var invoice = MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(invoice.path("total").decimalValue()).isEqualByComparingTo("120.0000");
        assertThat(invoice.path("totalBook").decimalValue()).isEqualByComparingTo("120.0000");

        // the stored line carries its computed formula (the inline child's own
        // formula field is real data, not an absent column)
        java.math.BigDecimal amount = jdbc.queryForObject(
                "SELECT (data->>'amount')::numeric FROM rec_line LIMIT 1",
                java.math.BigDecimal.class);
        assertThat(amount).isEqualByComparingTo("120");
    }
}
