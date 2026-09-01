package com.novaforge.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novaforge.testsupport.KafkaTestBase;
import com.novaforge.testsupport.PostgresTestBase;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * The durable audit trail (PHASE-3 §5): record events consumed from the spine land
 * append-only, redelivery is idempotent (dedup on event_id), and reads are
 * tenant-scoped through the API.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuditTrailTests extends PostgresTestBase {

    private static final org.testcontainers.kafka.KafkaContainer KAFKA =
            new org.testcontainers.kafka.KafkaContainer("apache/kafka:4.3.1");

    static {
        KAFKA.start();
    }

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-4999-8999-999999999999");
    private static final UUID ACTOR = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<String, String>
            listenerFactory;

    @Autowired
    com.novaforge.audit.store.AuditPartitionRotation rotation;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestBase::jdbcUrl);
        // The runtime pool rides the restricted role (yaml default novaforge_audit_app)
        // — every consumer insert and read below goes through it; migrations alone
        // connect as the container owner.
        registry.add("spring.flyway.user", PostgresTestBase::jdbcUsername);
        registry.add("spring.flyway.password", PostgresTestBase::jdbcPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Test
    void eventsLandAppendOnlyWithIdempotentRedelivery() throws Exception {
        UUID recordId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String payload = MAPPER.writeValueAsString(Map.of(
                "event", "record.created",
                "eventId", eventId.toString(),
                "tenantId", TENANT.toString(),
                "entityId", "Erp.JournalEntry",
                "recordId", recordId.toString(),
                "actorId", ACTOR.toString(),
                "occurredAt", "2026-08-21T12:00:00.000Z"));
        kafka.send(new ProducerRecord<>("novaforge.record", TENANT + ":" + recordId, payload)).get();
        // at-least-once: redeliver the same event — dedup collapses it
        kafka.send(new ProducerRecord<>("novaforge.record", TENANT + ":" + recordId, payload)).get();

        // await the event itself, not a non-empty body — the trail reads "[]" until
        // the consumer lands the row, and "[]" is a non-empty string
        await().atMost(Duration.ofSeconds(30)).until(() ->
                mockMvcPerform(recordId).contains("record.created"));

        String body = mockMvcPerform(recordId);
        assertThat(body).contains("record.created").contains(recordId.toString());
        // exactly one row despite the redelivery
        int occurrences = body.split("event_id", -1).length - 1;
        assertThat(occurrences).isGreaterThan(0);
        try (var connection = java.sql.DriverManager.getConnection(
                jdbcUrl(), jdbcUsername(), jdbcPassword());
             var statement = connection.createStatement();
             var rs = statement.executeQuery(
                     "SELECT count(*) FROM audit_events WHERE event_id = '" + eventId + "'::uuid")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }

        // reads are tenant-scoped: another tenant sees nothing
        mockMvc.perform(get("/api/v1/audit/records/" + recordId)
                        .with(jwt().jwt(token -> token.claim("tenant_id", OTHER_TENANT.toString())
                                .subject(ACTOR.toString()))
                                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"),
                                        new SimpleGrantedAuthority("ROLE_admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("PHASE-3 §5: the read API is admin-facing — a plain user token is denied")
    void readsAreAdminGated() throws Exception {
        mockMvc.perform(get("/api/v1/audit/records/" + UUID.randomUUID())
                        .with(jwt().jwt(token -> token.claim("tenant_id", TENANT.toString())
                                .subject(ACTOR.toString()))
                                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"),
                                        new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("read limit bounds: 0 and 201 reject 400, the 200 ceiling serves — no unbounded trail read")
    void readLimitBoundsReject() throws Exception {
        var adminJwt = jwt().jwt(token -> token.claim("tenant_id", TENANT.toString())
                .subject(ACTOR.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"),
                        new SimpleGrantedAuthority("ROLE_admin"));
        mockMvc.perform(get("/api/v1/audit/records/" + UUID.randomUUID())
                        .param("limit", "0").with(adminJwt))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4000"));
        mockMvc.perform(get("/api/v1/audit/records/" + UUID.randomUUID())
                        .param("limit", "201").with(adminJwt))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/audit/entities/Erp.Ticket")
                        .param("limit", "201").with(adminJwt))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/audit/entities/Erp.Ticket")
                        .param("limit", "200").with(adminJwt))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PHASE-3 §5: append-only enforced mechanically — the store role has no UPDATE/DELETE")
    void appendOnlyIsMechanical() throws Exception {
        UUID recordId = UUID.randomUUID();
        try (var connection = java.sql.DriverManager.getConnection(
                jdbcUrl(), "novaforge_audit_app", "novaforge")) {
            // INSERT and SELECT are the whole surface
            var insert = connection.prepareStatement("""
                    INSERT INTO audit_events (event_id, tenant_id, entity_id, record_id,
                                              event_type, actor_id, occurred_at, payload)
                    VALUES (?, ?, 'Erp.JournalEntry', ?, 'record.created', ?, now(), '{}'::jsonb)
                    """);
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, TENANT);
            insert.setObject(3, recordId);
            insert.setObject(4, ACTOR);
            insert.executeUpdate();
            try (var rs = connection.createStatement().executeQuery(
                    "SELECT count(*) FROM audit_events WHERE record_id = '" + recordId + "'::uuid")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            // everything else denies at the database — not by convention
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE audit_events SET payload = payload");
                org.assertj.core.api.Assertions.fail("UPDATE must deny for the store role");
            } catch (java.sql.SQLException expected) {
                assertThat(expected.getMessage()).contains("permission denied");
            }
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM audit_events");
                org.assertj.core.api.Assertions.fail("DELETE must deny for the store role");
            } catch (java.sql.SQLException expected) {
                assertThat(expected.getMessage()).contains("permission denied");
            }
        }
    }

    @Test
    @DisplayName("PHASE-3 §4/§5 + PHASE-4 §5/§13: the audit-side families land in the trail")
    void auditSideFamiliesLandInTheTrail() throws Exception {
        // definition publishes (metadata.published — no occurredAt, publishedAt rides instead)
        UUID appId = UUID.randomUUID();
        send(Map.of("eventId", UUID.randomUUID().toString(), "event", "metadata.published",
                "tenantId", TENANT.toString(), "appId", appId.toString(), "version", 3,
                "publishedAt", "2026-08-24T09:00:00.000Z", "actorId", ACTOR.toString()),
                "novaforge.metadata");
        // permission changes (the platform-admin API's writes, PHASE-2 §10)
        UUID userId = UUID.randomUUID();
        send(Map.of("eventId", UUID.randomUUID().toString(), "event", "permission.role.assigned",
                "tenantId", TENANT.toString(), "userId", userId.toString(),
                "actorId", ACTOR.toString(), "role", "accountingManager",
                "occurredAt", "2026-08-24T09:01:00.000Z"), "novaforge.permission");
        // auth events (the deployed Keycloak listener)
        send(Map.of("eventId", UUID.randomUUID().toString(), "event", "auth.login",
                "tenantId", TENANT.toString(), "userId", userId.toString(),
                "username", "demo", "clientId", "novaforge-api",
                "occurredAt", "2026-08-24T09:02:00.000Z"), "novaforge.auth");
        // the human-task plane + scheduler fires + notification deliveries
        UUID taskId = UUID.randomUUID();
        send(Map.of("eventId", UUID.randomUUID().toString(), "event", "task.approved",
                "tenantId", TENANT.toString(), "taskId", taskId.toString(),
                "entityId", "Erp.JournalEntry", "actorId", ACTOR.toString(),
                "occurredAt", "2026-08-24T09:03:00.000Z"), "novaforge.task");
        send(Map.of("eventId", UUID.randomUUID().toString(), "event", "sla.breach",
                "tenantId", TENANT.toString(), "taskId", taskId.toString(),
                "occurredAt", "2026-08-24T09:04:00.000Z"), "novaforge.sla");
        send(Map.of("eventId", UUID.randomUUID().toString(), "event", "scheduler.job.run",
                "tenantId", TENANT.toString(), "app", "erp", "job", "nightlyAging",
                "status", "ok", "occurredAt", "2026-08-24T09:05:00.000Z"),
                "novaforge.scheduler");
        send(Map.of("eventId", UUID.randomUUID().toString(), "event", "notification.delivered",
                "tenantId", TENANT.toString(), "userId", userId.toString(),
                "channel", "inbox", "category", "task-assignment",
                "occurredAt", "2026-08-24T09:06:00.000Z"), "novaforge.notification");

        // every family lands on its own record key, tenant-scoped
        await().atMost(Duration.ofSeconds(30)).until(() ->
                mockMvcPerform(taskId).contains("task.approved"));
        assertThat(mockMvcPerform(appId)).contains("metadata.published");
        assertThat(mockMvcPerform(userId)).contains("permission.role.assigned")
                .contains("auth.login").contains("notification.delivered");
        assertThat(mockMvcPerform(taskId)).contains("sla.breach");
        // scheduler fires key on the event id (the payload carries job names, not ids)
        // — assert through the store: the row exists exactly once per event id
        try (var connection = java.sql.DriverManager.getConnection(
                jdbcUrl(), jdbcUsername(), jdbcPassword());
             var statement = connection.createStatement();
             var rs = statement.executeQuery(
                     "SELECT count(*) FROM audit_events WHERE event_type = 'scheduler.job.run'")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
        // reads stay tenant-scoped: the other tenant sees nothing of any family
        mockMvc.perform(get("/api/v1/audit/records/" + taskId)
                        .with(jwt().jwt(token -> token.claim("tenant_id", OTHER_TENANT.toString())
                                .subject(ACTOR.toString()))
                                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"),
                                        new SimpleGrantedAuthority("ROLE_admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    private void send(Map<String, Object> payload, String topic) throws Exception {
        kafka.send(new ProducerRecord<>(topic,
                TENANT + ":" + payload.get("eventId"), MAPPER.writeValueAsString(payload))).get();
    }

    @Test
    @DisplayName("a failing append redelivers with real backoff — never the default 9x zero-backoff log-and-skip")
    void failingAppendRedeliversWithBackoff() throws Exception {
        // Anti-regression (2026-08-31): the consumers deliberately propagate a failed
        // store.append, but with no container error handler Boot's default answered
        // that propagation with nine ZERO-backoff retries and a log-and-skip — a
        // database outage burned its budget in under a second, committed the offset,
        // and left permanent silent holes in the trail. ConsumerErrorConfig mirrors
        // the Workflow Service's mechanism: exponential backoff + a dead-letter
        // publisher, never a silent skip.
        // wiring: the custom handler rides the factory every listener resolves by
        // default — asserted through a container the factory itself builds
        var probe = listenerFactory.createContainer("novaforge-audit-wiring-probe");
        var handler = probe.getCommonErrorHandler();
        assertThat(handler).isInstanceOf(
                org.springframework.kafka.listener.DefaultErrorHandler.class);
        assertThat(handler.seeksAfterHandling()).isTrue();   // seek back = redeliver

        // Behavior against the real broker: one always-failing listener on a private
        // topic/group riding the very factory every audit listener resolves. The
        // default handler's nine zero-backoff retries land as a sub-second burst of
        // ten deliveries then silence; ours spreads real retries (1 s doubling).
        String topic = "novaforge.record.pin-" + UUID.randomUUID();
        java.util.List<Long> deliveries = new java.util.concurrent.CopyOnWriteArrayList<>();
        var container = listenerFactory.createContainer(topic);
        container.getContainerProperties().setGroupId("novaforge-audit-pin-" + UUID.randomUUID());
        java.util.Properties consumerProps = new java.util.Properties();
        consumerProps.setProperty("auto.offset.reset", "earliest");
        container.getContainerProperties().setKafkaConsumerProperties(consumerProps);
        container.getContainerProperties().setMessageListener(
                (org.springframework.kafka.listener.MessageListener<String, String>) record -> {
                    deliveries.add(System.currentTimeMillis());
                    throw new org.springframework.dao.DataAccessResourceFailureException(
                            "postgres restarting");
                });
        try {
            container.start();
            kafka.send(new ProducerRecord<>(topic, "pin", "{}")).get();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20))
                    .until(() -> deliveries.size() >= 3);
            // the first three deliveries span seconds (backoff), not a burst; and the
            // ten-attempt budget is nowhere near spent — the record is being
            // redelivered, not skipped toward the drop
            assertThat(deliveries.get(2) - deliveries.get(0)).isGreaterThanOrEqualTo(1_500L);
            assertThat(deliveries.size()).isLessThan(10);
        } finally {
            container.stop();
        }
    }

    @org.junit.jupiter.api.Test
    @DisplayName("the monthly partition rotation creates current+next month partitions (V1's promise)")
    void partitionRotationCreatesMonthsAhead() throws Exception {
        // Anti-regression (2026-08-31): the schema promised rotating month partitions
        // but nothing created them — every row landed in audit_events_default forever.
        // The rotation runs as the owner role (V2's design: the runtime role cannot DDL).
        rotation.rotate();
        Integer months = jdbc.queryForObject(
                "SELECT count(*) FROM pg_tables WHERE tablename LIKE 'audit_events_y20%'",
                Integer.class);
        assertThat(months).isGreaterThanOrEqualTo(2);   // current + next
        java.time.YearMonth next = java.time.YearMonth.now().plusMonths(1);
        String nextName = "audit_events_y" + next.getYear() + "m"
                + String.format("%02d", next.getMonthValue());
        Integer present = jdbc.queryForObject(
                "SELECT count(*) FROM pg_tables WHERE tablename = ?", Integer.class, nextName);
        assertThat(present).isEqualTo(1);
        // and inserts still flow into the current month's partition — V2's
        // default-privilege grant rides the owner's creations
        UUID recordId = UUID.randomUUID();
        kafka.send(new ProducerRecord<>("novaforge.record", TENANT + ":" + recordId,
                MAPPER.writeValueAsString(Map.of(
                        "event", "record.updated",
                        "eventId", UUID.randomUUID().toString(),
                        "tenantId", TENANT.toString(),
                        "entityId", "Erp.JournalEntry",
                        "recordId", recordId.toString(),
                        "actorId", ACTOR.toString(),
                        "occurredAt", java.time.Instant.now().toString())))).get();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(mockMvcPerform(recordId)).contains("record.updated"));
        Integer inMonthPartition = jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE event_type = 'record.updated' "
                        + "AND occurred_at >= date_trunc('month', now())",
                Integer.class);
        assertThat(inMonthPartition).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("rotation MOVES in-range rows out of the default partition — the trail never loses a row")
    void rotationMovesDefaultPartitionRowsIntoTheMonth() throws Exception {
        // The move path (not the fresh-CREATE path) is what runs for any month
        // whose default partition already holds rows — the live current month at
        // rollout. Regression either wedges rotation forever (unbounded default
        // growth) or deletes an in-range row from the append-only trail.
        java.time.YearMonth next = java.time.YearMonth.now().plusMonths(1);
        String nextName = "audit_events_y" + next.getYear() + "m"
                + String.format("%02d", next.getMonthValue());
        try (var connection = java.sql.DriverManager.getConnection(
                jdbcUrl(), jdbcUsername(), jdbcPassword());   // the owner: DDL is owner-only
             var statement = connection.createStatement()) {
            rotation.rotate();   // guarantee the month partitions exist before surgery
            statement.execute("ALTER TABLE audit_events DETACH PARTITION " + nextName);
            statement.execute("DROP TABLE " + nextName);
        }

        // an event dated next month now lands in the default partition (nothing
        // else claims the range) — exactly the rollout scenario
        UUID recordId = UUID.randomUUID();
        String futureStart = next.atDay(1).atTime(9, 0)
                .atZone(java.time.ZoneOffset.UTC).toInstant().toString();
        kafka.send(new ProducerRecord<>("novaforge.record", TENANT + ":" + recordId,
                MAPPER.writeValueAsString(Map.of(
                        "event", "record.updated",
                        "eventId", UUID.randomUUID().toString(),
                        "tenantId", TENANT.toString(),
                        "entityId", "Erp.JournalEntry",
                        "recordId", recordId.toString(),
                        "actorId", ACTOR.toString(),
                        "occurredAt", futureStart)))).get();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(ownerCount("SELECT count(*) FROM audit_events_default "
                        + "WHERE record_id = '" + recordId + "'::uuid")).isEqualTo(1));

        // rotation must MOVE the row into the recreated partition — readable
        // through the parent afterwards, never silently dropped
        rotation.rotate();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_tables WHERE tablename = ?", Integer.class, nextName))
                .isEqualTo(1);
        // the default partition is empty of the row — the owner role reads it;
        // the runtime role's grants cover only the parent
        assertThat(ownerCount("SELECT count(*) FROM audit_events_default "
                + "WHERE record_id = '" + recordId + "'::uuid")).isZero();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(mockMvcPerform(recordId)).contains("record.updated"));
    }

    /** The default partition is owner-readable only (V2's grant shape). */
    private int ownerCount(String sql) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(
                jdbcUrl(), jdbcUsername(), jdbcPassword());
             var statement = connection.createStatement();
             var rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private String mockMvcPerform(UUID recordId) throws Exception {
        return mockMvc.perform(get("/api/v1/audit/records/" + recordId)
                        .with(jwt().jwt(token -> token.claim("tenant_id", TENANT.toString())
                                .subject(ACTOR.toString()))
                                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"),
                                        new SimpleGrantedAuthority("ROLE_admin"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }
}
