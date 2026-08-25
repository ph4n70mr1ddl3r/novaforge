package com.novaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.runtime.engine.hook.ScriptClient;
import com.novaforge.runtime.engine.metadata.EntityResolver;
import com.novaforge.runtime.engine.metadata.MetadataClient;
import com.novaforge.runtime.events.HookRetryConsumer;
import com.novaforge.runtime.events.HookRetryScanner;
import com.novaforge.runtime.events.KafkaOutboxRelay;
import com.novaforge.runtime.storage.materializer.Materializer;
import com.novaforge.runtime.storage.retry.HookRetryStore;
import com.novaforge.testsupport.PostgresTestBase;
import java.time.Duration;
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
 * The after-hook retry leg, end to end (PHASE-3 §2 failure policy): a failed
 * after-hook never blocks the write, rides the spine out of the write's own
 * transaction ({@code hook.retry} → outbox → relay → Kafka), and re-drives against the
 * record's current state until it runs clean — bounded attempts, exponential backoff,
 * durable parks. Script failures park at consume time (caller-context only —
 * ADR-003 #2). Relay and scanner run manually here for deterministic sequencing.
 */
@SpringBootTest(properties = {
        "novaforge.hooks.retry.max-attempts=3",
        "novaforge.hooks.retry.backoff-base-ms=0",       // tests drive attempts directly
        "novaforge.hooks.retry.scan-interval-ms=3600000",   // manual: scanOnce()
        "novaforge.events.relay-interval-ms=3600000"})      // manual: relay()
@AutoConfigureMockMvc
class HookRetryTests extends PostgresTestBase {

    static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID ACTOR = UUID.fromString("33333333-3333-4333-8333-333333333333");
    static final UUID APP_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    static final String APP_JSON = """
            { "apiName": "Ops",
              "entities": [
                { "apiName": "Escalation",
                  "displayField": "subject",
                  "fields": [
                    { "apiName": "subject", "type": "text", "required": true },
                    { "apiName": "status", "type": "enum", "values": ["NEW", "OPEN"] } ],
                  "hooks": [
                    { "name": "fileNote", "trigger": "afterSave", "flow":
                      { "id": "n1", "op": "createRecord",
                        "params": { "entity": "Note",
                                    "template": { "about": "${id}", "severity": "${status}" } } } } ] },
                { "apiName": "Note",
                  "fields": [
                    { "apiName": "about", "type": "text" },
                    { "apiName": "severity", "type": "text" } ],
                  "validations": [
                    { "name": "onlyOpen", "scope": "record",
                      "expression": "severity != 'NEW'",
                      "message": "notes land only on OPEN escalations" } ] },
                { "apiName": "Scripty",
                  "fields": [
                    { "apiName": "label", "type": "text", "required": true } ],
                  "hooks": [
                    { "name": "notify", "trigger": "afterSave",
                      "script": { "language": "js",
                                  "source": "$log.info('saved ' + $record.id)" } } ] } ] }
            """;

    static AppDefinition app;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    HookRetryStore retries;

    @Autowired
    HookRetryScanner scanner;

    @Autowired
    KafkaOutboxRelay relay;

    @Autowired
    HookRetryConsumer consumer;

    @Autowired
    EntityResolver resolver;

    static volatile boolean failAfterScripts;

    @TestConfiguration
    static class StubMetadata {

        @Bean
        @Primary
        MetadataClient metadataClient() {
            MetadataClient client = Mockito.mock(MetadataClient.class);
            app = DefinitionParser.parseApp(APP_JSON);
            Mockito.when(client.publishedApps()).thenAnswer(inv ->
                    List.of(new MetadataClient.PublishedApp(APP_ID, "Ops", 1)));
            Mockito.when(client.publishedBundle(Mockito.any(UUID.class))).thenAnswer(inv ->
                    new MetadataClient.PublishedBundle(1, app));
            return client;
        }

        @Bean
        @Primary
        ScriptClient scriptClient() {
            return new ScriptClient() {
                @Override
                public ScriptOutcome execute(String appApiName, int appVersion, String hook,
                                             String trigger, com.novaforge.metadata.ScriptDefinition script,
                                             Map<String, Object> record) {
                    if (failAfterScripts && trigger.startsWith("after")) {
                        throw new com.novaforge.common.error.PlatformException(
                                com.novaforge.common.error.PlatformErrorCode.INTERNAL,
                                "script hook " + hook + " exploded");
                    }
                    return new ScriptOutcome(Map.of(), List.of("stub notify"));
                }

                @Override
                public ScriptOutcome executeScheduled(java.util.UUID tenantId, String app,
                                                      int appVersion, String hook,
                                                      com.novaforge.metadata.ScriptDefinition script) {
                    return new ScriptOutcome(Map.of(), List.of("stub scheduled"));
                }
            };
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

    @Test
    @DisplayName("failed afterSave never blocks the write, rides the spine, and heals against current state")
    void afterHookFailureRidesTheSpineAndHeals() throws Exception {
        // status NEW → the afterSave flow hook's createRecord fails Note's validation.
        // The write itself succeeds — §2's "never blocks the write".
        MvcResult created = mockMvc.perform(post("/api/v1/runtime/Escalation")
                        .with(jwtFor(TENANT)).contentType("application/json")
                        .content("{\"subject\":\"svc-1\",\"status\":\"NEW\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String id = MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();
        assertThat(notesFor(id)).isZero();   // the hook's write did not land

        // The failure rode the same transaction as an outbox row → relay → Kafka.
        relay.relay();
        UUID eventId = awaitRetryRow("fileNote", "pending", id);

        // Healing: the escalation moves to OPEN — the retry re-runs against CURRENT
        // state, so the same flow now passes. The PATCH's own afterSave also fires
        // (and succeeds immediately), so two notes land in total.
        mockMvc.perform(patch("/api/v1/runtime/Escalation/" + id).with(jwtFor(TENANT))
                        .contentType("application/json")
                        .content("{\"version\":1,\"status\":\"OPEN\"}"))
                .andExpect(status().isOk());

        scanner.scanOnce();

        assertThat(retries.statusOf(eventId)).containsEntry("status", "ok");
        assertThat(notesFor(id)).isEqualTo(2);
        assertThat(noteSeverities(id)).containsOnly("OPEN");
    }

    @Test
    @DisplayName("retries that cannot converge park after the attempt budget — durable, never retried again")
    void exhaustedRetriesPark() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/runtime/Escalation")
                        .with(jwtFor(TENANT)).contentType("application/json")
                        .content("{\"subject\":\"svc-2\",\"status\":\"NEW\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String id = MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();
        relay.relay();
        UUID eventId = awaitRetryRow("fileNote", "pending", id);

        // max-attempts=3, backoff 0: three scan passes, then the park.
        scanner.scanOnce();
        scanner.scanOnce();
        scanner.scanOnce();
        scanner.scanOnce();

        Map<String, Object> row = retries.statusOf(eventId);
        assertThat(row).containsEntry("status", "parked");
        assertThat(row.get("attempt")).isEqualTo(3);
        assertThat(String.valueOf(row.get("last_error"))).contains("attempts exhausted");
        assertThat(notesFor(id)).isZero();   // nothing ever landed
    }

    @Test
    @DisplayName("a record deleted after a failed afterSave parks the retry — nothing to re-drive")
    void deletedRecordParks() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/runtime/Escalation")
                        .with(jwtFor(TENANT)).contentType("application/json")
                        .content("{\"subject\":\"svc-3\",\"status\":\"NEW\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String id = MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();
        relay.relay();
        UUID eventId = awaitRetryRow("fileNote", "pending", id);

        mockMvc.perform(delete("/api/v1/runtime/Escalation/" + id).with(jwtFor(TENANT))
                        .param("version", "1"))
                .andExpect(status().isNoContent());

        scanner.scanOnce();
        Map<String, Object> row = retries.statusOf(eventId);
        assertThat(row).containsEntry("status", "parked");
        assertThat(String.valueOf(row.get("last_error"))).contains("record is gone");
    }

    @Test
    @DisplayName("script after-hook failures ride the spine but park — caller-context only (ADR-003 #2)")
    void scriptFailuresParkAtConsume() throws Exception {
        failAfterScripts = true;
        try {
            MvcResult created = mockMvc.perform(post("/api/v1/runtime/Scripty")
                            .with(jwtFor(TENANT)).contentType("application/json")
                            .content("{\"label\":\"notify-me\"}"))
                    .andExpect(status().isOk())   // the write still succeeds (uniform policy)
                    .andReturn();
            String id = MAPPER.readTree(created.getResponse().getContentAsString())
                    .get("id").asString();
            relay.relay();
            UUID eventId = awaitRetryRow("notify", "parked", id);
            Map<String, Object> row = retries.statusOf(eventId);
            assertThat(String.valueOf(row.get("last_error"))).contains("caller-context");
        } finally {
            failAfterScripts = false;
        }
    }

    @Test
    @DisplayName("spine redelivery collapses on the event id — one retry row, one execution")
    void redeliveryCollapses() {
        UUID eventId = UUID.randomUUID();
        String payload = """
                { "event": "hook.retry", "eventId": "%s",
                  "tenantId": "%s", "entityId": "Ops.Escalation", "recordId": "%s",
                  "actorId": "%s", "occurredAt": "2026-08-22T00:00:00Z",
                  "trigger": "afterSave", "hook": "fileNote", "kind": "flow", "attempt": 1,
                  "error": "redelivery probe" }"""
                .formatted(eventId, TENANT, UUID.randomUUID(), ACTOR);
        consumer.onEvent(record(payload));
        consumer.onEvent(record(payload));   // the redelivery
        Map<String, Object> row = retries.statusOf(eventId);
        assertThat(row).containsEntry("status", "pending");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM hook_retry_log WHERE event_id = ?", Integer.class, eventId))
                .isEqualTo(1);
    }

    // --- helpers ---

    /** The retry row's event id, awaited into the given status via the real Kafka leg. */
    private UUID awaitRetryRow(String hookName, String status, String recordId) {
        List<UUID> found = new CopyOnWriteArrayList<>();
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    jdbc.queryForList("""
                                    SELECT event_id FROM hook_retry_log
                                     WHERE hook_name = ? AND status = ? AND record_id = ?
                                     ORDER BY created_at DESC LIMIT 1""",
                            UUID.class, hookName, status, UUID.fromString(recordId))
                            .forEach(found::add);
                    assertThat(found).isNotEmpty();
                });
        return found.getFirst();
    }

    private int notesFor(String escalationId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM rec_records
                 WHERE entity_id = 'Ops.Note' AND NOT deleted AND data->>'about' = ?""",
                Integer.class, escalationId);
        return count == null ? 0 : count;
    }

    private List<String> noteSeverities(String escalationId) {
        return jdbc.queryForList("""
                SELECT data->>'severity' FROM rec_records
                 WHERE entity_id = 'Ops.Note' AND NOT deleted AND data->>'about' = ?""",
                String.class, escalationId);
    }

    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(UUID tenant) {
        return jwt()
                .jwt(token -> token.claim("tenant_id", tenant.toString()).subject(ACTOR.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
    }
    /** A consumer record wrapping the payload — the listener reads the spine's
     *  traceparent header off the record (ARCHITECTURE.md §6). */
    private static org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record(
            String payload) {
        return new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                "novaforge.hook", 0, 0L, "key", payload);
    }

}
