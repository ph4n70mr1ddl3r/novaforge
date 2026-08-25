package com.novaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.runtime.engine.metadata.EntityResolver;
import com.novaforge.runtime.engine.metadata.MetadataClient;
import com.novaforge.runtime.engine.hook.ConnectorPort;
import com.novaforge.runtime.storage.materializer.Materializer;
import com.novaforge.testsupport.PostgresTestBase;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.json.JsonMapper;

/**
 * The scheduled connector pull (PHASE-7 §5 — "the Phase 6 exit connector driven by a
 * scheduled flow", T8): a {@code scheduled}-trigger hook fires recordless through the
 * internal scheduled surface, its {@code callConnector} step lands the provider's
 * response in flow scope, and {@code iterate} over the response's array creates a
 * record per row — the bank-feed sync shape. Pins the whole leg end to end:
 * <ul>
 * <li>rows land through the real write path (nested engine writes, validations and
 * uniqueness included — a webhook-shaped pull cannot smuggle a bad record either);</li>
 * <li>every fire is a fresh connector delivery — the delivery dedupe is permanent, so
 * a fire-keyed dedupe scope is what keeps the second cron tick from answering the
 * first tick's recorded response forever (the stale-pull defect this pass closed);</li>
 * <li>the {@code scheduled} trigger never fires on the write path — only the
 * Scheduler's by-name firing executes it;</li>
 * <li>a duplicate re-pull rejects audibly (G-14's workaround: never a silent
 * double-apply), and the flow's publishEvent tail rides the outbox recordless.</li>
 * </ul>
 */
@SpringBootTest(properties = {"novaforge.events.relay-interval-ms=3600000"})
@AutoConfigureMockMvc
class ScheduledConnectorFlowTests extends PostgresTestBase {

    static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID ACTOR = UUID.fromString("33333333-3333-4333-8333-333333333333");
    static final UUID APP_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * The app under test mirrors the ERP's bank-feed wiring: Payment carries the
     * scheduled sync hook — callConnector → iterate over the response's
     * transactions → createRecord per row → the recordless publishEvent tail.
     */
    static final String APP_JSON = """
            { "apiName": "Ledger",
              "entities": [
                { "apiName": "Payment",
                  "displayField": "number",
                  "fields": [
                    { "apiName": "number", "type": "text", "required": true,
                      "uniqueness": true },
                    { "apiName": "amount", "type": "decimal", "precision": 18, "scale": 4 },
                    { "apiName": "paymentDate", "type": "date", "required": true } ],
                  "hooks": [
                    { "name": "syncBankFeed", "trigger": "scheduled",
                      "flow": {
                        "id": "c1", "op": "callConnector",
                        "params": { "connector": "bankFeed",
                                    "operation": "listTransactions",
                                    "template": { "since": "2000-01-01" } },
                        "next": "i1",
                        "body": {
                          "id": "i1", "op": "iterate",
                          "params": { "path": "connector.c1.transactions" },
                          "next": "e1",
                          "body": {
                            "id": "p1", "op": "createRecord",
                            "params": { "entity": "Payment",
                                        "template": { "number": "${txn_id}",
                                                      "amount": "${amount}",
                                                      "paymentDate": "${posted_date}" } },
                            "body": {
                              "id": "e1", "op": "publishEvent",
                              "params": { "name": "ledger.bankfeed.synced",
                                          "payload": { "since": "2000-01-01" } } } } } } } ] } ],
              "integrations": {
                "connectors": [
                  { "id": "bankFeed", "type": "rest", "baseUrl": "http://127.0.0.1:1",
                    "operations": [
                      { "name": "listTransactions", "method": "GET",
                        "path": "/v1/transactions", "query": { "since": "${since}" } } ] } ] } }
            """;

    /** Every stubbed connector call: the dedupe key it was served under. */
    static final List<String> DEDUPE_KEYS = new CopyOnWriteArrayList<>();

    /** The canned responses, served in order (one per fire). */
    static final List<String> RESPONSES = new CopyOnWriteArrayList<>();

    static AppDefinition app;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityResolver resolver;

    @TestConfiguration
    static class Stubs {

        @Bean
        @Primary
        MetadataClient metadataClient() {
            MetadataClient client = Mockito.mock(MetadataClient.class);
            app = DefinitionParser.parseApp(APP_JSON);
            Mockito.when(client.publishedApps()).thenAnswer(inv ->
                    List.of(new MetadataClient.PublishedApp(APP_ID, "Ledger", 1)));
            Mockito.when(client.publishedBundle(Mockito.any(UUID.class))).thenAnswer(inv ->
                    new MetadataClient.PublishedBundle(1, app));
            return client;
        }

        /** The connector port, stubbed: canned provider bodies per call. */
        @Bean
        @Primary
        ConnectorPort connectorPort() {
            return (tenantId, appApiName, connector, operation, template, dedupeKey) -> {
                DEDUPE_KEYS.add(dedupeKey);
                String body = RESPONSES.isEmpty()
                        ? "{\"transactions\":[]}"
                        : RESPONSES.remove(0);
                return new ConnectorPort.ConnectorResult(200, MAPPER.readTree(body));
            };
        }
    }

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("docker.io/library/redis:7.4.11")
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
                .jwt(token -> token.claim("tenant_id", TENANT.toString())
                        .subject(ACTOR.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
    }

    /** The platform service client (azp) — the internal surface's gate. */
    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor serviceJwt() {
        return jwt()
                .jwt(token -> token.claim("azp", com.novaforge.security.ServiceClientGate.CLIENT_ID)
                        .subject("service-account-novaforge-runtime"))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
    }

    private void fire() throws Exception {
        mockMvc.perform(post("/api/v1/hooks/scheduled").with(serviceJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"" + TENANT + "\",\"app\":\"Ledger\","
                                + "\"entityApiName\":\"Payment\",\"hook\":\"syncBankFeed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("fired"));
    }

    private int paymentsOf(String number) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM rec_payment WHERE data->>'number' = ?", Integer.class, number);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("the scheduled pull creates a Payment per response row (§5/T8)")
    void scheduledPullCreatesPaymentsPerRow() throws Exception {
        DEDUPE_KEYS.clear();
        RESPONSES.add("""
                {"transactions":[
                  {"txn_id":"TX-9001","amount":"70.0000","posted_date":"2026-09-20"},
                  {"txn_id":"TX-9002","amount":"30.0000","posted_date":"2026-09-21"}]}""");
        fire();

        // both rows landed through the real write path — decimal-exact, typed dates
        assertThat(paymentsOf("TX-9001")).isEqualTo(1);
        assertThat(paymentsOf("TX-9002")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT data->>'amount' FROM rec_payment WHERE data->>'number' = 'TX-9001'",
                String.class)).isEqualTo("70.0000");
        assertThat(jdbc.queryForObject(
                "SELECT data->>'paymentDate' FROM rec_payment WHERE data->>'number' = 'TX-9001'",
                String.class)).isEqualTo("2026-09-20");

        // the flow's publishEvent tail rides the outbox recordless (V5: record_id
        // nullable — the completion event has no record)
        Integer synced = jdbc.queryForObject(
                "SELECT count(*) FROM event_outbox WHERE event_type = 'ledger.bankfeed.synced'"
                        + " AND record_id IS NULL", Integer.class);
        assertThat(synced).isEqualTo(1);
    }

    @Test
    @DisplayName("every fire is a fresh connector delivery — no stale first response (the dedupe defect)")
    void eachFireIsAFreshDelivery() throws Exception {
        DEDUPE_KEYS.clear();
        RESPONSES.add("{\"transactions\":[{\"txn_id\":\"TX-A\",\"amount\":\"1.0000\","
                + "\"posted_date\":\"2026-09-01\"}]}");
        fire();
        RESPONSES.add("{\"transactions\":[{\"txn_id\":\"TX-B\",\"amount\":\"2.0000\","
                + "\"posted_date\":\"2026-09-02\"}]}");
        fire();

        // the second fire's provider body was served and applied — the permanent
        // delivery dedupe would otherwise have answered fire one's recorded outcome
        assertThat(paymentsOf("TX-A")).isEqualTo(1);
        assertThat(paymentsOf("TX-B")).isEqualTo(1);

        // and the two fires carried distinct dedupe keys (fire-scoped, not
        // hook+step-scoped) while each fire's own retries would still collapse
        assertThat(DEDUPE_KEYS).hasSize(2);
        assertThat(DEDUPE_KEYS.get(0)).isNotEqualTo(DEDUPE_KEYS.get(1));
    }

    @Test
    @DisplayName("the scheduled trigger never fires on the write path (only by name)")
    void scheduledTriggerNeverFiresOnWrites() throws Exception {
        int before = DEDUPE_KEYS.size();
        mockMvc.perform(post("/api/v1/runtime/Payment").with(jwtFor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"TX-MANUAL\",\"amount\":\"5.0000\","
                                + "\"paymentDate\":\"2026-09-05\"}"))
                .andExpect(status().isOk());
        assertThat(DEDUPE_KEYS.size()).as("no connector call from a plain write").isEqualTo(before);
        assertThat(paymentsOf("TX-MANUAL")).isEqualTo(1);
    }

    @Test
    @DisplayName("a duplicate re-pull rejects audibly — never a silent double-apply (G-14)")
    void duplicateRePullRejectsAudibly() throws Exception {
        RESPONSES.add("{\"transactions\":[{\"txn_id\":\"TX-DUP\",\"amount\":\"9.0000\","
                + "\"posted_date\":\"2026-09-03\"}]}");
        fire();
        assertThat(paymentsOf("TX-DUP")).isEqualTo(1);

        // the same transaction again: the unique index answers through the write
        // path's own verdict — the scheduled surface renders it, never swallows it
        RESPONSES.add("{\"transactions\":[{\"txn_id\":\"TX-DUP\",\"amount\":\"9.0000\","
                + "\"posted_date\":\"2026-09-03\"}]}");
        mockMvc.perform(post("/api/v1/hooks/scheduled").with(serviceJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"" + TENANT + "\",\"app\":\"Ledger\","
                                + "\"entityApiName\":\"Payment\",\"hook\":\"syncBankFeed\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4000"));   // VALIDATION_FAILED
        assertThat(paymentsOf("TX-DUP")).isEqualTo(1);
    }
}
