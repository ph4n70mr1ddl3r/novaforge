package com.novaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.runtime.engine.metadata.MetadataClient;
import com.novaforge.runtime.storage.materializer.Materializer;
import com.novaforge.security.ServiceClientGate;
import com.novaforge.testsupport.PostgresTestBase;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.http.HttpHeaders;
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
 * §11 item 3's engine half: {@code callConnector} before-hooks abort the write on
 * connector failure (§4's pinned policy), after-hook failures ride the spine's
 * retry leg instead of blocking the write, and the mock connector journey runs
 * end-to-end (a stub executor answers, the write commits, the connector was
 * called). Plus the integration principal's write path (§6): validations, state
 * machines, and hooks all fire through the internal surface — a webhook cannot
 * smuggle a bad record past the write path.
 */
@SpringBootTest
@AutoConfigureMockMvc
class IntegrationFlowTests extends PostgresTestBase {

    static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID ACTOR = UUID.fromString("33333333-3333-4333-8333-333333333333");
    static final UUID APP_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * The app under test: Payment carries an afterSave enrichment hook (callConnector
     * → setField), a validation, and a state machine; the integration branch holds
     * the connector the flow calls plus an inbound-webhook mapping for §6.
     */
    static final String APP_JSON = """
            { "apiName": "Erp",
              "entities": [
                { "apiName": "Payment",
                  "displayField": "reference",
                  "fields": [
                    { "apiName": "reference", "type": "text", "required": true },
                    { "apiName": "amount", "type": "decimal", "precision": 18, "scale": 4 },
                    { "apiName": "source", "type": "text" },
                    { "apiName": "status", "type": "enum", "values": ["DRAFT", "POSTED"] } ],
                  "validations": [
                    { "name": "positive", "scope": "record",
                      "expression": "amount > 0", "message": "amount must be positive" } ],
                  "hooks": [
                    { "name": "enrich", "trigger": "afterSave", "flow":
                      { "id": "c1", "op": "callConnector",
                        "params": { "connector": "con_bank", "operation": "listTransactions",
                                    "template": { "limit": "${amount}" } },
                        "next": "s1" } } ] },
                { "apiName": "Charge",
                  "displayField": "label",
                  "fields": [
                    { "apiName": "label", "type": "text", "required": true } ],
                  "hooks": [
                    { "name": "verify", "trigger": "beforeSave", "flow":
                      { "id": "v1", "op": "callConnector",
                        "params": { "connector": "con_bank", "operation": "listTransactions",
                                    "template": { "limit": "1" } } } } ] } ],
              "stateMachines": [
                { "id": "sm_pay", "entity": "Payment", "stateField": "status",
                  "initial": "DRAFT",
                  "states": [ { "name": "DRAFT" }, { "name": "POSTED", "terminal": true } ],
                  "transitions": [ { "from": "DRAFT", "to": "POSTED" } ] } ],
              "integrations": {
                "connectors": [
                  { "id": "con_bank", "type": "rest", "baseUrl": "http://127.0.0.1:1",
                    "operations": [
                      { "name": "listTransactions", "method": "GET",
                        "path": "/transactions", "query": { "limit": "${limit}" } } ] } ],
                "webhooks": [
                  { "id": "wh_feed", "direction": "inbound", "entity": "Payment",
                    "secretRef": "hook_wh_feed",
                    "mapping": { "mode": "create",
                                 "fields": { "reference": "${data.ref}", "amount": "${data.amount}" } } } ] } }
            """;

    static AppDefinition app;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    /** The stub integration executor: canned verdicts per connector call. */
    static HttpServer executor;

    /** Connector calls the stub observed: path + body. */
    static final AtomicInteger EXECUTOR_HITS = new AtomicInteger();
    static final AtomicReference<String> LAST_PATH = new AtomicReference<>();
    static volatile boolean failExecutor = false;

    @TestConfiguration
    static class StubMetadata {

        @Bean
        @Primary
        com.novaforge.runtime.engine.hook.ConnectorPort connectorPort() {
            return new com.novaforge.runtime.engine.hook.ConnectorPort() {

                private final org.springframework.web.client.RestClient client =
                        org.springframework.web.client.RestClient.builder()
                                .baseUrl("http://127.0.0.1:" + executor.getAddress().getPort())
                                .build();

                @Override
                public ConnectorResult execute(String tenantId, String appApiName,
                                               String connector, String operation,
                                               java.util.Map<String, Object> template,
                                               String dedupeKey) {
                    try {
                        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
                        body.put("tenantId", tenantId);
                        body.put("app", appApiName);
                        body.put("connector", connector);
                        body.put("operation", operation);
                        body.put("template", template == null ? java.util.Map.of() : template);
                        if (dedupeKey != null) {
                            body.put("dedupeKey", dedupeKey);
                        }
                        java.util.Map<String, Object> response = client.post()
                                .uri("/api/v1/integrations/internal/execute")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .body(MAPPER.writeValueAsString(body))
                                .retrieve()
                                .body(java.util.Map.class);
                        return new ConnectorResult(200, MAPPER.valueToTree(
                                response == null ? java.util.Map.of() : response.get("body")));
                    } catch (org.springframework.web.client.RestClientResponseException e) {
                        throw new com.novaforge.common.error.PlatformException(
                                com.novaforge.common.error.PlatformErrorCode.INTERNAL,
                                "connector " + connector + "." + operation + ": "
                                        + e.getResponseBodyAsString());
                    }
                }
            };
        }

        @Bean
        @Primary
        MetadataClient metadataClient() {
            app = DefinitionParser.parseApp(APP_JSON);
            MetadataClient client = Mockito.mock(MetadataClient.class);
            Mockito.when(client.publishedApps()).thenAnswer(inv ->
                    List.of(new MetadataClient.PublishedApp(APP_ID, "Erp", 1)));
            Mockito.when(client.publishedBundle(Mockito.any(UUID.class))).thenAnswer(inv ->
                    new MetadataClient.PublishedBundle(1, app));
            return client;
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
        try {
            // the stub executor stands in for the Integration Service's internal
            // surface — started before the context so the connector port binds to it
            executor = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            executor.createContext("/api/v1/integrations/internal/execute", exchange -> {
                EXECUTOR_HITS.incrementAndGet();
                LAST_PATH.set(exchange.getRequestURI().getPath());
                if (failExecutor) {
                    respond(exchange, 500, "{\"code\":\"5000\",\"detail\":\"connector down\"}");
                    return;
                }
                respond(exchange, 200, "{\"status\":200,\"body\":{\"object\":\"list\",\"data\":[]}}");
            });
            executor.start();
        } catch (Exception e) {
            throw new IllegalStateException("stub executor failed to start", e);
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestBase::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestBase::jdbcUsername);
        registry.add("spring.datasource.password", PostgresTestBase::jdbcPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("novaforge.integration.url",
                () -> "http://127.0.0.1:" + executor.getAddress().getPort());
    }

    @BeforeAll
    static void materialize(@Autowired Materializer materializer) {
        materializer.apply(app);
    }

    @AfterAll
    static void stopExecutor() {
        if (executor != null) {
            executor.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status, String json) {
        try {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (Exception ignored) {
            // test stub
        }
    }

    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor userJwt() {
        return jwt().jwt(token -> token.claim("tenant_id", TENANT.toString())
                        .claim("actor_id", ACTOR.toString()).subject(ACTOR.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
    }

    /** The platform service client (azp) — the internal surfaces' gate. */
    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor serviceJwt() {
        return jwt().jwt(token -> token.claim("azp", ServiceClientGate.CLIENT_ID)
                        .subject("service-account-novaforge-runtime"))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
    }

    @Test
    @DisplayName("mock connector journey: afterSave callConnector fires, the write commits (§11 item 3)")
    void mockConnectorJourney() throws Exception {
        failExecutor = false;
        int before = EXECUTOR_HITS.get();
        MvcResult result = mockMvc.perform(post("/api/v1/runtime/Payment").with(userJwt())
                        .contentType("application/json")
                        .content("{\"reference\":\"pay-1\",\"amount\":\"42.50\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(EXECUTOR_HITS.get()).isGreaterThan(before);   // the connector ran
        assertThat(result.getResponse().getContentAsString()).contains("pay-1");
        // record.created landed with the integration-visible shape
        Integer created = jdbc.queryForObject(
                "SELECT count(*) FROM " + table("Payment") + " WHERE data->>'reference' = 'pay-1'",
                Integer.class);
        assertThat(created).isEqualTo(1);
    }

    @Test
    @DisplayName("after-hook connector failure retries via the spine — the write still commits (§4)")
    void afterHookFailureRetries() throws Exception {
        failExecutor = true;
        try {
            mockMvc.perform(post("/api/v1/runtime/Payment").with(userJwt())
                            .contentType("application/json")
                            .content("{\"reference\":\"pay-retry\",\"amount\":\"10\"}"))
                    .andExpect(status().isOk());   // committed — the failure never blocks
        } finally {
            failExecutor = false;
        }
        // the §2 failure policy queued hook.retry on the outbox (the spine's retry leg)
        awaitRetry("enrich");
    }

    @Test
    @DisplayName("before-hook connector failure aborts the transaction (§4's pinned policy)")
    void beforeHookFailureAborts() throws Exception {
        failExecutor = true;
        try {
            mockMvc.perform(post("/api/v1/runtime/Charge").with(userJwt())
                            .contentType("application/json")
                            .content("{\"label\":\"chg-1\"}"))
                    .andExpect(status().isInternalServerError());
            // nothing persisted — the transaction aborted
            Integer created = jdbc.queryForObject(
                    "SELECT count(*) FROM " + table("Charge") + " WHERE data->>'label' = 'chg-1'",
                    Integer.class);
            assertThat(created).isZero();
        } finally {
            failExecutor = false;
        }
        // healed: the same write now commits through the connector
        mockMvc.perform(post("/api/v1/runtime/Charge").with(userJwt())
                        .contentType("application/json")
                        .content("{\"label\":\"chg-1\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("integration write path: validations, state machines, and hooks all fire (§11 item 6)")
    void integrationWritePathEnforcesEverything() throws Exception {
        // a bad record rejects with the validation rule's verdict — no smuggling
        mockMvc.perform(post("/api/v1/hooks/integration/write").with(serviceJwt())
                        .contentType("application/json")
                        .content(MAPPER.writeValueAsString(Map.of("tenantId", TENANT.toString(),
                                "items", List.of(Map.of("op", "create", "entity", "Payment",
                                        "record", Map.of("reference", "wh-bad",
                                                "amount", "-1")))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomes[0].status").value("error"))
                .andExpect(jsonPath("$.outcomes[0].code").value("4000"))
                .andExpect(jsonPath("$.outcomes[0].errors[0].message").value(
                        org.hamcrest.Matchers.containsString("amount must be positive")));

        // a good record creates — the state machine pinned the initial state and the
        // afterSave hook (callConnector) fired as the integration's initiating actor
        int before = EXECUTOR_HITS.get();
        MvcResult created = mockMvc.perform(post("/api/v1/hooks/integration/write").with(serviceJwt())
                        .contentType("application/json")
                        .content(MAPPER.writeValueAsString(Map.of("tenantId", TENANT.toString(),
                                "items", List.of(Map.of("op", "create", "entity", "Payment",
                                        "record", Map.of("reference", "wh-good",
                                                "amount", "5")))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomes[0].status").value("ok"))
                .andExpect(jsonPath("$.outcomes[0].record.status").value("DRAFT"))
                .andExpect(jsonPath("$.outcomes[0].record.integration").value(true))
                .andReturn();
        assertThat(EXECUTOR_HITS.get()).isGreaterThan(before);   // hooks fired

        // a terminal-state write rejects through the state machine's check
        String recordId = MAPPER.readTree(created.getResponse().getContentAsString())
                .path("outcomes").get(0).path("record").path("id").asString();
        int version = MAPPER.readTree(created.getResponse().getContentAsString())
                .path("outcomes").get(0).path("record").path("version").asInt();
        // POST the DRAFT first (legal), then POST the POSTED (terminal) — the second rejects
        mockMvc.perform(post("/api/v1/hooks/integration/write").with(serviceJwt())
                        .contentType("application/json")
                        .content(MAPPER.writeValueAsString(Map.of("tenantId", TENANT.toString(),
                                "items", List.of(Map.of("op", "update", "entity", "Payment",
                                        "id", recordId, "version", version,
                                        "record", Map.of("status", "POSTED")))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomes[0].status").value("ok"));
        Integer version2 = jdbc.queryForObject(
                "SELECT version FROM " + table("Payment") + " WHERE id = ?",
                Integer.class, UUID.fromString(recordId));
        // the machine fires on the integration path too: no transition exists from a
        // terminal state, so a status write rejects STATE_TRANSITION (4010)
        mockMvc.perform(post("/api/v1/hooks/integration/write").with(serviceJwt())
                        .contentType("application/json")
                        .content(MAPPER.writeValueAsString(Map.of("tenantId", TENANT.toString(),
                                "items", List.of(Map.of("op", "update", "entity", "Payment",
                                        "id", recordId, "version", version2,
                                        "record", Map.of("status", "DRAFT")))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomes[0].status").value("error"))
                .andExpect(jsonPath("$.outcomes[0].code").value("4010"));
    }

    private static String table(String entity) {
        return "rec_" + com.novaforge.metadata.Snake.caseName(entity);
    }

    /** Awaits the retry row for the named hook (the §2 retry leg parks here). */
    private void awaitRetry(String hook) {
        try {
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                Integer queued = jdbc.queryForObject(
                        "SELECT count(*) FROM hook_retry_log WHERE hook_name = ?",
                        Integer.class, hook);
                if (queued != null && queued > 0) {
                    return;
                }
                Thread.sleep(200);
            }
            throw new AssertionError("retry row for " + hook + " never landed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted awaiting retry row");
        }
    }
}
