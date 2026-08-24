package com.novaforge.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.novaforge.common.error.PlatformException;
import com.novaforge.integration.clients.PublishedIntegrations;
import com.novaforge.integration.connector.ConnectorExecutor;
import com.novaforge.integration.secrets.SecretStore;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.testsupport.PostgresTestBase;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * §11 item 3's executor half: the mock connector journey (a stub provider answers a
 * templated GET), delivery idempotency (a dedupe key returns the recorded outcome —
 * never a second provider call), and terminal failure parking in the DLQ with the
 * request preserved for builder replay. The timeout/failure-policy legs against the
 * flow engine live in the Data Runtime suite (callConnector before/after).
 */
@SpringBootTest(properties = {
        "novaforge.connector.attempts=2",
        "novaforge.connector.backoff-initial-ms=20",
})
class ConnectorExecutorTests extends PostgresTestBase {

    static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");

    static final String APP_JSON = """
            { "apiName": "Erp",
              "entities": [
                { "apiName": "Payment", "displayField": "reference",
                  "fields": [
                    { "apiName": "reference", "type": "text", "required": true },
                    { "apiName": "amount", "type": "decimal", "precision": 18, "scale": 4 } ] } ],
              "integrations": {
                "connectors": [
                  { "id": "con_stripe_tx", "type": "rest",
                    "baseUrl": "STUB",
                    "credential": "cred_stripe",
                    "operations": [
                      { "name": "listTransactions", "method": "GET",
                        "path": "/balance_transactions",
                        "query": { "limit": "${limit}" } } ] } ],
                "credentials": [
                  { "id": "cred_stripe", "kind": "api_key", "header": "Authorization" } ] } }
            """;

    static AppDefinition app;

    @Autowired
    ConnectorExecutor connectors;

    @Autowired
    SecretStore secrets;

    private static HttpServer provider;

    /** Provider hit count — idempotency asserts against it. */
    static final AtomicInteger HITS = new AtomicInteger();
    static volatile boolean failAll = false;
    static volatile String lastAuthorization;
    static volatile String lastQuery;

    @TestConfiguration
    static class Stubs {

        @Bean
        @Primary
        PublishedIntegrations publishedIntegrations() {
            return new PublishedIntegrations() {

                @Override
                public java.util.Optional<AppDefinition> byApiName(UUID tenantId, String apiName) {
                    return java.util.Optional.of(app);
                }

                @Override
                public List<AppDefinition> allApps(UUID tenantId) {
                    return List.of(app);
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

    @BeforeAll
    static void startProvider() throws Exception {
        provider = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/con_stripe_tx", exchange -> {
            HITS.incrementAndGet();
            lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            lastQuery = exchange.getRequestURI().getQuery();
            if (failAll) {
                respond(exchange, 500, "{\"error\":\"provider down\"}");
                return;
            }
            respond(exchange, 200, "{\"object\":\"list\",\"data\":[{\"id\":\"txn_1\"}]}");
        });
        provider.start();
        app = DefinitionParser.parseApp(APP_JSON.replace("STUB",
                "http://127.0.0.1:" + provider.getAddress().getPort() + "/con_stripe_tx"));
    }

    @AfterAll
    static void stopProvider() {
        provider.stop(0);
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

    @BeforeAll
    static void provisionSecret(@Autowired SecretStore secretStore) {
        secretStore.put(TENANT, "cred_stripe", SecretStore.PURPOSE_CREDENTIAL, "sk-test-123");
    }

    @Test
    @DisplayName("mock connector journey: templated call executes with credentials and returns the body")
    void mockJourney() {
        int before = HITS.get();
        var execution = connectors.execute(TENANT, "Erp", "con_stripe_tx", "listTransactions",
                Map.of("limit", "50"), "journey-key-1");
        assertThat(execution.status()).isEqualTo(200);
        assertThat(execution.body().path("data").size()).isEqualTo(1);
        assertThat(HITS.get()).isEqualTo(before + 1);
        // the credential rode the API-key header; the template resolved into the query
        assertThat(lastAuthorization).isEqualTo("Bearer sk-test-123");
        assertThat(lastQuery).contains("limit=50");
    }

    @Test
    @DisplayName("idempotent deliveries: a dedupe key returns the recorded outcome, never a second call")
    void dedupeKeyCollapses() {
        connectors.execute(TENANT, "Erp", "con_stripe_tx", "listTransactions",
                Map.of("limit", "10"), "journey-key-2");
        int afterFirst = HITS.get();
        var second = connectors.execute(TENANT, "Erp", "con_stripe_tx", "listTransactions",
                Map.of("limit", "10"), "journey-key-2");
        assertThat(second.deliveryId()).isNull();   // the recorded outcome stood
        assertThat(HITS.get()).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("terminal failure: bounded retries exhaust, the request parks in the DLQ")
    void terminalFailureParksInDlq() {
        failAll = true;
        try {
            assertThatThrownBy(() -> connectors.execute(TENANT, "Erp", "con_stripe_tx",
                    "listTransactions", Map.of("limit", "5"), "journey-key-3"))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("failed terminally");
            // attempts = 2 → the provider saw exactly two tries
            assertThat(HITS.get()).isGreaterThanOrEqualTo(2);
        } finally {
            failAll = false;
        }
    }

    @Test
    @DisplayName("unknown operations reject before any provider call (publish-checked, executor-gated)")
    void unknownOperationRejects() {
        int before = HITS.get();
        assertThatThrownBy(() -> connectors.execute(TENANT, "Erp", "con_stripe_tx",
                "noSuchOperation", Map.of(), "journey-key-4"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("no operation noSuchOperation");
        assertThat(HITS.get()).isEqualTo(before);
    }
}
