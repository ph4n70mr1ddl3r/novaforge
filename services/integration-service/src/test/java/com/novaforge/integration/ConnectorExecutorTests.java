package com.novaforge.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.novaforge.common.error.PlatformException;
import com.novaforge.integration.clients.PublishedIntegrations;
import com.novaforge.integration.connector.ConnectorExecutor;
import com.novaforge.integration.secrets.SecretStore;
import com.novaforge.integration.store.DeliveryStore;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.testsupport.PostgresTestBase;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
 * request preserved for builder replay. The OAuth2 leg rides a mock token endpoint:
 * RFC 6749 §2.3.1 Basic client authentication, tokens cached per credential until
 * expiry, and a failed grant that never poisons the cache. The timeout/failure-policy
 * legs against the flow engine live in the Data Runtime suite (callConnector before/after).
 */
@SpringBootTest(properties = {
        "novaforge.connector.attempts=2",
        "novaforge.connector.backoff-initial-ms=20",
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConnectorExecutorTests extends PostgresTestBase {

    static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");

    /** The second tenant: its own app sharing the SAME credential id (the leak case). */
    static final UUID TENANT_B = UUID.fromString("22222222-2222-4222-8222-222222222222");

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
                        "query": { "limit": "${limit}" } } ] },
                  { "id": "con_oauth", "type": "rest",
                    "baseUrl": "OAUTH_A",
                    "credential": "cred_oauth",
                    "operations": [
                      { "name": "ping", "method": "GET", "path": "/ping" } ] },
                  { "id": "con_oauth_exp", "type": "rest",
                    "baseUrl": "OAUTH_B",
                    "credential": "cred_oauth_exp",
                    "operations": [
                      { "name": "ping", "method": "GET", "path": "/ping" } ] },
                  { "id": "con_oauth_flaky", "type": "rest",
                    "baseUrl": "OAUTH_C",
                    "credential": "cred_oauth_flaky",
                    "operations": [
                      { "name": "ping", "method": "GET", "path": "/ping" } ] } ],
                "credentials": [
                  { "id": "cred_stripe", "kind": "api_key", "header": "Authorization" },
                  { "id": "cred_oauth", "kind": "oauth2_client_credentials",
                    "tokenUrl": "TOKENENDPOINT", "clientId": "erp-client" },
                  { "id": "cred_oauth_exp", "kind": "oauth2_client_credentials",
                    "tokenUrl": "TOKENENDPOINT", "clientId": "erp-client" },
                  { "id": "cred_oauth_flaky", "kind": "oauth2_client_credentials",
                    "tokenUrl": "TOKENENDPOINT", "clientId": "erp-client" } ] } }
            """;

    static AppDefinition app;

    /** Tenant B's app: the same credential id, a different client (the assertable difference). */
    static AppDefinition appB;

    @Autowired
    ConnectorExecutor connectors;

    @Autowired
    PublishedIntegrations publishedIntegrations;

    @Autowired
    SecretStore secrets;

    @Autowired
    DeliveryStore deliveryStore;

    private static HttpServer provider;

    /** Provider hit count — idempotency asserts against it. */
    static final AtomicInteger HITS = new AtomicInteger();
    static volatile boolean failAll = false;
    static volatile String lastAuthorization;
    static volatile String lastQuery;

    /** The mock token endpoint: hit count, the Basic grant it saw, and its TTL knob. */
    static final AtomicInteger TOKEN_HITS = new AtomicInteger();
    static volatile String lastTokenAuthorization;
    static volatile String lastTokenBody;
    static volatile long tokenExpiresIn = 300;
    static volatile boolean tokenEndpointFails = false;

    @TestConfiguration
    static class Stubs {

        @Bean
        @Primary
        PublishedIntegrations publishedIntegrations() {
            return new PublishedIntegrations() {

                @Override
                public java.util.Optional<AppDefinition> byApiName(UUID tenantId, String apiName) {
                    return java.util.Optional.of(TENANT_B.equals(tenantId) ? appB : app);
                }

                @Override
                public List<AppDefinition> allApps(UUID tenantId) {
                    return List.of(TENANT_B.equals(tenantId) ? appB : app);
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
        // the OAuth2 connectors' provider legs — every one echoes the bearer it rode
        for (String oauthConnector : List.of("con_oauth", "con_oauth_exp", "con_oauth_flaky")) {
            provider.createContext("/" + oauthConnector, exchange -> {
                lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
                respond(exchange, 200, "{\"ok\":true}");
            });
        }
        provider.createContext("/token", exchange -> {
            TOKEN_HITS.incrementAndGet();
            lastTokenAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            lastTokenBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (tokenEndpointFails) {
                respond(exchange, 500, "{\"error\":\"token endpoint down\"}");
                return;
            }
            respond(exchange, 200, "{\"access_token\":\"tok-" + TOKEN_HITS.get()
                    + "\",\"expires_in\":" + tokenExpiresIn + "}");
        });
        provider.start();
        String base = "http://127.0.0.1:" + provider.getAddress().getPort();
        app = DefinitionParser.parseApp(APP_JSON
                .replace("STUB", base + "/con_stripe_tx")
                .replace("TOKENENDPOINT", base + "/token")
                .replace("OAUTH_A", base + "/con_oauth")
                .replace("OAUTH_B", base + "/con_oauth_exp")
                .replace("OAUTH_C", base + "/con_oauth_flaky"));
        // tenant B: the same credential id "cred_oauth" over its own client id —
        // the cross-tenant cache-key case (the connector id stays "con_oauth" so
        // the provider leg records into the same observed slot)
        appB = DefinitionParser.parseApp("""
                { "apiName": "ErpB",
                  "entities": [
                    { "apiName": "Payment", "displayField": "reference",
                      "fields": [ { "apiName": "reference", "type": "text", "required": true } ] } ],
                  "integrations": {
                    "connectors": [
                      { "id": "con_oauth", "type": "rest",
                        "baseUrl": "%s/con_oauth",
                        "credential": "cred_oauth",
                        "operations": [
                          { "name": "ping", "method": "GET", "path": "/ping" } ] } ],
                    "credentials": [
                      { "id": "cred_oauth", "kind": "oauth2_client_credentials",
                        "tokenUrl": "%s/token", "clientId": "other-client" } ] } }
                """.formatted(base, base));
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
        for (String credential : List.of("cred_oauth", "cred_oauth_exp", "cred_oauth_flaky")) {
            secretStore.put(TENANT, credential, SecretStore.PURPOSE_CREDENTIAL, "sk-oauth-1");
        }
        secretStore.put(TENANT_B, "cred_oauth", SecretStore.PURPOSE_CREDENTIAL, "sk-oauth-2");
    }

    @Test
    @Order(1)
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
    @Order(9)
    @DisplayName("execution-time egress re-check (PHASE-6 §9 layer 2): a strict executor refuses loopback dispatch before any delivery")
    void strictEgressRefusesLoopbackDispatch() {
        // the staged/production posture (the charts' allowLoopback=false): the
        // provider stub binds 127.0.0.1, so the strict executor must refuse the
        // dispatch — no provider hit, no delivery opened (a policy verdict, not
        // a failed delivery)
        ConnectorExecutor strict = new ConnectorExecutor(publishedIntegrations, secrets,
                deliveryStore, 10000, 4, 200, 2000, false);
        int before = HITS.get();
        assertThatThrownBy(() -> strict.execute(TENANT, "Erp", "con_stripe_tx",
                "listTransactions", Map.of("limit", "5"), "egress-strict-1"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("internal-network target")
                .hasMessageContaining("con_stripe_tx");
        assertThat(HITS.get()).isEqualTo(before);   // nothing dispatched
        // …while the default local posture (allow-loopback=true — the harness mock's
        // shape) dispatches to the same loopback provider
        var allowed = connectors.execute(TENANT, "Erp", "con_stripe_tx", "listTransactions",
                Map.of("limit", "5"), "egress-strict-2");
        assertThat(allowed.status()).isEqualTo(200);
    }

    @Test
    @Order(2)
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
    @Order(3)
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
    @Order(4)
    @DisplayName("unknown operations reject before any provider call (publish-checked, executor-gated)")
    void unknownOperationRejects() {
        int before = HITS.get();
        assertThatThrownBy(() -> connectors.execute(TENANT, "Erp", "con_stripe_tx",
                "noSuchOperation", Map.of(), "journey-key-4"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("no operation noSuchOperation");
        assertThat(HITS.get()).isEqualTo(before);
    }

    // --- the OAuth2 client-credentials leg (RFC 6749 §2.3.1, against the mock token endpoint) ---

    @Test
    @Order(5)
    @DisplayName("oauth2 journey: a Basic-auth grant fetches the token once and caches it")
    void oauthJourneyRidesBasicGrantAndCaches() {
        tokenExpiresIn = 300;
        int before = TOKEN_HITS.get();
        var execution = connectors.execute(TENANT, "Erp", "con_oauth", "ping",
                Map.of(), "oauth-key-1");
        assertThat(execution.status()).isEqualTo(200);
        int fetched = TOKEN_HITS.get();
        assertThat(fetched).isEqualTo(before + 1);
        // RFC 6749 §2.3.1: the client credentials ride the Basic header — never the body
        assertThat(lastTokenAuthorization).isEqualTo("Basic " + Base64.getEncoder()
                .encodeToString("erp-client:sk-oauth-1".getBytes(StandardCharsets.UTF_8)));
        assertThat(lastTokenBody).isEqualTo("grant_type=client_credentials");
        assertThat(lastAuthorization).isEqualTo("Bearer tok-" + fetched);
        // fresh token — the second call rides the cache, no second grant
        connectors.execute(TENANT, "Erp", "con_oauth", "ping", Map.of(), "oauth-key-2");
        assertThat(TOKEN_HITS.get()).isEqualTo(fetched);
    }

    @Test
    @Order(6)
    @DisplayName("an expired grant refetches — the cache honors the refresh window, not the first fetch")
    void expiredGrantRefetches() {
        tokenExpiresIn = 0;   // refreshAt lands in the past — every grant is born expired
        try {
            connectors.execute(TENANT, "Erp", "con_oauth_exp", "ping", Map.of(), "oauth-key-3");
            int afterFirst = TOKEN_HITS.get();
            connectors.execute(TENANT, "Erp", "con_oauth_exp", "ping", Map.of(), "oauth-key-4");
            assertThat(TOKEN_HITS.get()).isEqualTo(afterFirst + 1);
        } finally {
            tokenExpiresIn = 300;
        }
    }

    @Test
    @Order(7)
    @DisplayName("a failed grant never poisons the cache — recovery follows the endpoint back up")
    void failedGrantDoesNotPoisonTheCache() {
        tokenEndpointFails = true;
        try {
            assertThatThrownBy(() -> connectors.execute(TENANT, "Erp", "con_oauth_flaky",
                    "ping", Map.of(), "oauth-key-5"))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("failed terminally");
        } finally {
            tokenEndpointFails = false;
        }
        // the failure cached nothing — the next call fetches and succeeds
        var recovered = connectors.execute(TENANT, "Erp", "con_oauth_flaky",
                "ping", Map.of(), "oauth-key-6");
        assertThat(recovered.status()).isEqualTo(200);
    }

    @Test
    @Order(8)
    @DisplayName("cross-tenant: a same-named credential never serves another tenant's cached token")
    void sameNamedCredentialDoesNotLeakAcrossTenants() {
        // Anti-regression (eighteenth pass): the token cache was keyed by the
        // credential's authored id alone — two tenants sharing a credential name
        // served each other's tokens, so tenant B's provider call rode tenant A's
        // OAuth grant (cross-tenant leakage into A's provider account).
        int before = TOKEN_HITS.get();
        var execution = connectors.execute(TENANT_B, "ErpB", "con_oauth", "ping",
                Map.of(), "oauth-tenantb-1");
        assertThat(execution.status()).isEqualTo(200);
        // a FRESH grant for tenant B (its own client id), never A's cached entry
        int fetched = TOKEN_HITS.get();
        assertThat(fetched).isEqualTo(before + 1);
        assertThat(lastTokenAuthorization).isEqualTo("Basic " + Base64.getEncoder()
                .encodeToString("other-client:sk-oauth-2".getBytes(StandardCharsets.UTF_8)));
        assertThat(lastAuthorization).isEqualTo("Bearer tok-" + fetched);
    }
}
