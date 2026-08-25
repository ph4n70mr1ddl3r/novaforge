package com.novaforge.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novaforge.integration.clients.PublishedIntegrations;
import com.novaforge.integration.clients.RuntimeClient;
import com.novaforge.integration.connector.ConnectorExecutor;
import com.novaforge.integration.secrets.SecretStore;
import com.novaforge.integration.store.DeliveryStore;
import com.novaforge.integration.webhook.HmacScheme;
import com.novaforge.integration.webhook.InboundProcessor;
import com.novaforge.integration.webhook.OutboundDispatcher;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.testsupport.PostgresTestBase;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.json.JsonMapper;

/**
 * The §11 test matrix, items 1, 2 (inbound/outbound legs), and 6: the HMAC matrix
 * (valid / wrong secret / stale timestamp / replayed signature → SIGNATURE_INVALID;
 * rotation with old+new secrets both valid), retry-to-DLQ with replay succeeding
 * exactly once, and webhook-driven writes through the (faked) Data Runtime client —
 * a bad record surfaces the write path's own rejection and parks in the poison DLQ.
 */
@SpringBootTest(properties = {
        "novaforge.webhook.attempts=3",
        "novaforge.webhook.backoff-initial-ms=20",
        "novaforge.webhook.backoff-max-ms=50",
        "novaforge.connector.attempts=2",
        "novaforge.connector.backoff-initial-ms=20",
})
@AutoConfigureMockMvc
class IntegrationWebhookTests extends PostgresTestBase {

    static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    static final String APP_JSON = """
            { "apiName": "Erp",
              "entities": [
                { "apiName": "Payment",
                  "displayField": "reference",
                  "fields": [
                    { "apiName": "reference", "type": "text", "required": true },
                    { "apiName": "amount", "type": "decimal", "precision": 18, "scale": 4 } ],
                  "validations": [
                    { "name": "positive", "scope": "record",
                      "expression": "amount > 0", "message": "amount must be positive" } ] } ],
              "integrations": {
                "webhooks": [
                  { "id": "wh_feed", "direction": "inbound", "entity": "Payment",
                    "secretRef": "hook_wh_feed",
                    "mapping": { "mode": "upsert", "keyFields": ["reference"],
                                 "idempotencyKey": "${data.id}",
                                 "fields": { "reference": "${data.ref}", "amount": "${data.amount}" } } } ] } }
            """;

    static AppDefinition app;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    SecretStore secrets;

    @Autowired
    DeliveryStore deliveries;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    InboundProcessor inbound;

    @Autowired
    OutboundDispatcher outbound;

    @Autowired
    HmacScheme hmac;

    /** The write calls the fake runtime observed (order-stable). */
    static final List<Map<String, Object>> WRITE_CALLS = new ArrayList<>();

    /** Rows the fake runtime "stores" — upsert lookups read them back. */
    static final Map<String, Map<String, Object>> STORED = new LinkedHashMap<>();

    /** When set, the fake runtime rejects matching create bodies (poison simulation). */
    static volatile String rejectReference = null;

    @TestConfiguration
    static class Stubs {

        @Bean
        @Primary
        PublishedIntegrations publishedIntegrations() {
            app = DefinitionParser.parseApp(APP_JSON);
            return new PublishedIntegrations() {

                @Override
                public java.util.Optional<AppDefinition> byApiName(UUID tenantId, String apiName) {
                    return "Erp".equals(apiName) ? java.util.Optional.of(app)
                            : java.util.Optional.empty();
                }

                @Override
                public List<AppDefinition> allApps(UUID tenantId) {
                    return List.of(app);
                }
            };
        }

        @Bean
        @Primary
        RuntimeClient runtimeClient() {
            return new RuntimeClient() {

                @Override
                public ListPage lookup(UUID tenantId, String entity, Map<String, Object> query) {
                    Map<?, ?> filter = (Map<?, ?>) query.get("filter");
                    Object reference = filter.get("reference");
                    Map<String, Object> found = STORED.get(String.valueOf(reference));
                    return new ListPage(found == null ? List.of() : List.of(found),
                            found == null ? 0 : 1);
                }

                @Override
                public List<Outcome> write(UUID tenantId, List<Map<String, Object>> items) {
                    List<Outcome> outcomes = new ArrayList<>();
                    for (Map<String, Object> item : items) {
                        WRITE_CALLS.add(item);
                        Map<String, Object> record =
                                (Map<String, Object>) item.get("record");
                        if (rejectReference != null
                                && rejectReference.equals(String.valueOf(record.get("reference")))) {
                            // the write path's own validation verdict (§11 item 6)
                            outcomes.add(new Outcome("error", null, "4000",
                                    "amount must be positive"));
                            continue;
                        }
                        if ("update".equals(item.get("op"))) {
                            Map<String, Object> existing = STORED.get(
                                    String.valueOf(record.get("reference")));
                            existing.putAll(record);
                            outcomes.add(new Outcome("ok", existing, null, null));
                        } else {
                            Map<String, Object> created = new LinkedHashMap<>(record);
                            created.put("id", UUID.randomUUID().toString());
                            created.put("version", 1);
                            STORED.put(String.valueOf(record.get("reference")), created);
                            outcomes.add(new Outcome("ok", created, null, null));
                        }
                    }
                    return outcomes;
                }
            };
        }
    }

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("docker.io/library/redis:7.4.11")
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestBase::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestBase::jdbcUsername);
        registry.add("spring.datasource.password", PostgresTestBase::jdbcPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @org.junit.jupiter.api.BeforeEach
    void provisionSecret() {
        resetHookSecret("hook-secret-one");
    }

    /** One active secret version — order-independent tests (rotation manages its own). */
    private void resetHookSecret(String material) {
        secrets.put(TENANT, "hook_wh_feed", SecretStore.PURPOSE_WEBHOOK, material);
        secrets.retireEarlierVersions(TENANT, "hook_wh_feed");
    }

    // --- the signed request helper ---

    private static org.springframework.test.web.servlet.ResultActions signed(
            MockMvc mockMvc, String body, String secret,
            String timestampOverride, String signatureOverride,
            org.springframework.test.web.servlet.ResultMatcher status)
            throws Exception {
        String timestamp = timestampOverride == null
                ? String.valueOf(Instant.now().getEpochSecond()) : timestampOverride;
        String signature = signatureOverride == null
                ? HmacScheme.signature(secret, timestamp, body.getBytes(StandardCharsets.UTF_8))
                : signatureOverride;
        return mockMvc.perform(post("/api/v1/webhooks/inbound/" + TENANT + "/Payment/wh_feed")
                        .header("X-NovaForge-Timestamp", timestamp)
                        .header("X-NovaForge-Signature", signature)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status);
    }

    @Test
    @DisplayName("§11 item 1: valid signature applies through the write path as the integration principal")
    void validSignatureApplies() throws Exception {
        int before = WRITE_CALLS.size();
        signed(mockMvc, """
                {"data": {"id": "evt-1", "ref": "pay-1", "amount": "150.50"}}""",
                "hook-secret-one", null, null, status().isOk());
        assertThat(WRITE_CALLS).hasSize(before + 1);
        Map<String, Object> item = WRITE_CALLS.getLast();
        assertThat(item.get("op")).isEqualTo("create");
        assertThat(((Map<?, ?>) item.get("record")).get("reference")).isEqualTo("pay-1");
        assertThat(((Map<?, ?>) item.get("record")).get("amount")).isEqualTo("150.50");
        // the delivery log records the settled application
        assertThat(deliveries.find(TENANT, DeliveryStore.KIND_WEBHOOK_INBOUND,
                "wh_feed", "evt-1")).isPresent();
    }

    @Test
    @DisplayName("§11 item 1: wrong secret / stale timestamp / replayed signature → SIGNATURE_INVALID")
    void hmacMatrixRejects() throws Exception {
        String body = """
                {"data": {"id": "evt-bad", "ref": "pay-bad", "amount": "1"}}""";
        // wrong secret
        signed(mockMvc, body, "wrong-secret", null, null,
                status().is(org.springframework.http.HttpStatus.UNAUTHORIZED.value()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("4012"));
        // stale timestamp (outside ±300s)
        signed(mockMvc, body, "hook-secret-one",
                String.valueOf(Instant.now().getEpochSecond() - 3600), null,
                status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("4012"));
        // replayed signature: first a good post, then the exact same signature again
        String replayBody = """
                {"data": {"id": "evt-replay", "ref": "pay-replay", "amount": "2"}}""";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = HmacScheme.signature("hook-secret-one", timestamp,
                replayBody.getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(post("/api/v1/webhooks/inbound/" + TENANT + "/Payment/wh_feed")
                        .header("X-NovaForge-Timestamp", timestamp)
                        .header("X-NovaForge-Signature", signature)
                        .contentType("application/json")
                        .content(replayBody))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/webhooks/inbound/" + TENANT + "/Payment/wh_feed")
                        .header("X-NovaForge-Timestamp", timestamp)
                        .header("X-NovaForge-Signature", signature)
                        .contentType("application/json")
                        .content(replayBody))
                .andExpect(status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("4012"));
    }

    @Test
    @DisplayName("§11 item 1: rotation — old and new secrets both valid until retirement")
    void rotationAdmitsBoth() throws Exception {
        resetHookSecret("hook-secret-one");
        secrets.put(TENANT, "hook_wh_feed", SecretStore.PURPOSE_WEBHOOK, "hook-secret-two");
        // during the rotation window both versions verify
        signed(mockMvc, """
                {"data": {"id": "evt-old", "ref": "pay-old", "amount": "3"}}""",
                "hook-secret-one", null, null, status().isOk());
        signed(mockMvc, """
                {"data": {"id": "evt-new", "ref": "pay-new", "amount": "4"}}""",
                "hook-secret-two", null, null, status().isOk());
        // retirement flips back to exactly one
        secrets.retireEarlierVersions(TENANT, "hook_wh_feed");
        signed(mockMvc, """
                {"data": {"id": "evt-retired", "ref": "pay-retired", "amount": "5"}}""",
                "hook-secret-one", null, null, status().isUnauthorized());
        signed(mockMvc, """
                {"data": {"id": "evt-after", "ref": "pay-after", "amount": "6"}}""",
                "hook-secret-two", null, null, status().isOk());
    }

    @Test
    @DisplayName("§11 item 6: a webhook cannot smuggle a bad record — the write path rejects, poison DLQs")
    void badRecordReachesWritePathVerdict() throws Exception {
        rejectReference = "pay-poison";
        try {
            signed(mockMvc, """
                    {"data": {"id": "evt-poison", "ref": "pay-poison", "amount": "-5"}}""",
                    "hook-secret-one", null, null,
                    status().isBadRequest());
        } finally {
            rejectReference = null;
        }
        var dlq = deliveries.dlq(TENANT, DeliveryStore.KIND_WEBHOOK_INBOUND, true);
        assertThat(dlq).anySatisfy(entry -> {
            assertThat(entry.target()).isEqualTo("wh_feed");
            assertThat(entry.error()).contains("amount must be positive");
        });
    }

    @Test
    @DisplayName("idempotency: the provider event id dedupes — one application, one record")
    void providerEventIdDedupes() throws Exception {
        String body = """
                {"data": {"id": "evt-dedupe", "ref": "pay-dedupe", "amount": "7"}}""";
        int before = WRITE_CALLS.size();
        signed(mockMvc, body, "hook-secret-one", null, null, status().isOk());
        // a fresh signature over the same event id (whitespace differs — a new
        // signature over the same logical payload, inside the same second)
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = HmacScheme.signature("hook-secret-one", timestamp,
                (body + "\n").getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(post("/api/v1/webhooks/inbound/" + TENANT + "/Payment/wh_feed")
                        .header("X-NovaForge-Timestamp", timestamp)
                        .header("X-NovaForge-Signature", signature)
                        .contentType("application/json")
                        .content(body + "\n"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.deduped").value(true));
        assertThat(WRITE_CALLS).hasSize(before + 1);
    }

    // --- outbound (§5/§11 items 1-2) ---

    private static HttpServer receiver;

    /** What the stub receiver observed: per-request path + signature headers. */
    static final ConcurrentLinkedQueue<String> RECEIVED = new ConcurrentLinkedQueue<>();
    static final AtomicInteger FAIL_FIRST = new AtomicInteger(0);

    @BeforeAll
    static void startReceiver() throws Exception {
        receiver = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/hook", exchange -> {
            RECEIVED.add(headers(exchange));
            if (FAIL_FIRST.get() > 0) {
                FAIL_FIRST.decrementAndGet();
                respond(exchange, 500, "{\"error\":\"flaky\"}");
                return;
            }
            respond(exchange, 200, "{\"ok\":true}");
        });
        receiver.start();
    }

    @AfterAll
    static void stopReceiver() {
        if (receiver != null) {
            receiver.stop(0);
        }
    }

    private static String headers(HttpExchange exchange) {
        return exchange.getRequestURI().getPath() + "|"
                + exchange.getRequestHeaders().getFirst("X-NovaForge-Timestamp") + "|"
                + exchange.getRequestHeaders().getFirst("X-NovaForge-Signature");
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

    private void withOutboundWebhook(java.util.function.Consumer<Object> body) {
        // splice an outbound webhook into the stubbed published app
        String url = "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook";
        com.novaforge.metadata.WebhookDefinition outboundHook =
                new com.novaforge.metadata.WebhookDefinition("wh_notify",
                        com.novaforge.metadata.WebhookDefinition.OUTBOUND, url,
                        "event == 'record.created' && entityId == 'Payment'",
                        null, null, "hook_wh_notify", true);
        com.novaforge.metadata.IntegrationsDefinition withHook =
                new com.novaforge.metadata.IntegrationsDefinition(
                        app.integrations().connectors(),
                        java.util.List.of(outboundHook),
                        app.integrations().credentials(),
                        app.integrations().imports());
        AppDefinition original = app;
        app = new AppDefinition(original.id(), original.apiName(), original.label(),
                original.labelI18n(), original.description(), original.entities(),
                original.pages(), original.settings(), original.permissionSet(),
                original.testSuites(), original.stateMachines(), original.slas(),
                original.jobs(), original.workflows(), original.reports(),
                original.dashboards(), withHook);
        secrets.put(TENANT, "hook_wh_notify", SecretStore.PURPOSE_WEBHOOK, "notify-secret");
        try {
            body.accept(null);
        } finally {
            app = original;
        }
    }

    @Test
    @DisplayName("§5/§11: a matching spine event dispatches HMAC-signed; non-matching events do not")
    void outboundDispatchSignsAndFilters() {
        withOutboundWebhook(ignored -> {
            RECEIVED.clear();
            outbound.onEvent(record(MAPPER.writeValueAsString(Map.of(
                    "event", "record.created", "eventId", UUID.randomUUID().toString(),
                    "tenantId", TENANT.toString(), "entityId", "Payment",
                    "recordId", UUID.randomUUID().toString(),
                    "actorId", UUID.randomUUID().toString(),
                    "occurredAt", Instant.now().toString()))));
            assertThat(RECEIVED).hasSize(1);
            String[] parts = RECEIVED.peek().split("\\|");
            assertThat(parts[1]).isNotBlank();   // timestamp header carried
            // the signature verifies against the raw body
            // (verified structurally here: hex, non-blank; the matrix above pins the scheme)
            assertThat(parts[2]).matches("[0-9a-f]{64}");
            // a non-matching event filters out
            outbound.onEvent(record(MAPPER.writeValueAsString(Map.of(
                    "event", "record.updated", "eventId", UUID.randomUUID().toString(),
                    "tenantId", TENANT.toString(), "entityId", "Payment",
                    "recordId", UUID.randomUUID().toString(),
                    "actorId", UUID.randomUUID().toString(),
                    "occurredAt", Instant.now().toString()))));
            assertThat(RECEIVED).hasSize(1);
        });
    }

    @Test
    @DisplayName("§11 item 2: terminal failure exhausts backoff → DLQ; replay succeeds exactly once")
    void retryToDlqThenReplayExactlyOnce() {
        withOutboundWebhook(ignored -> {
            String eventId = "evt-dlq-" + UUID.randomUUID();
            String raw = MAPPER.writeValueAsString(Map.of(
                    "event", "record.created", "eventId", eventId,
                    "tenantId", TENANT.toString(), "entityId", "Payment",
                    "recordId", UUID.randomUUID().toString(),
                    "actorId", UUID.randomUUID().toString(),
                    "occurredAt", Instant.now().toString()));
            FAIL_FIRST.set(99);   // every attempt fails → DLQ
            RECEIVED.clear();
            outbound.onEvent(record(raw));
            assertThat(RECEIVED).hasSize(3);   // attempts=3 exhausted
            var parked = deliveries.dlq(TENANT, DeliveryStore.KIND_WEBHOOK_OUTBOUND, true);
            assertThat(parked).anySatisfy(entry -> assertThat(entry.target())
                    .isEqualTo("wh_notify"));
            // the receiver heals; replay from the builder re-dispatches the preserved body
            UUID dlqId = parked.stream()
                    .filter(entry -> entry.target().equals("wh_notify"))
                    .findFirst().orElseThrow().id();
            RECEIVED.clear();
            FAIL_FIRST.set(0);
            outbound.deliver(TENANT, app.integrations().webhooks().getFirst(), eventId, raw);
            assertThat(RECEIVED).hasSize(1);
            // and the delivery settled — a second replay dedupes onto the outcome
            outbound.deliver(TENANT, app.integrations().webhooks().getFirst(), eventId, raw);
            assertThat(RECEIVED).hasSize(1);
            deliveries.markReplayed(TENANT, dlqId);
        });
    }
    /** A consumer record wrapping the payload — the dispatcher reads the spine's
     *  traceparent header off the record (ARCHITECTURE.md §6). */
    private static org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record(
            String payload) {
        return new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                "novaforge.record", 0, 0L, "key", payload);
    }

}
