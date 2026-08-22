package com.novaforge.notification;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novaforge.notification.notify.Notifier.EmailPort;
import com.novaforge.notification.notify.RecipientResolver.RuntimeAdminPort;
import com.novaforge.testsupport.PostgresTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Notification v1 (PHASE-4 §8, §14 item 6): the fan-out from the spine — inbox
 * delivery, template token resolution, preference filtering (email observed through
 * the stubbed SMTP port), synthetic actors skipped with no
 * {@code notification.delivered} (ADR-010 #3), and the read API's own-data scope.
 */
@SpringBootTest(properties = "novaforge.events.relay-interval-ms=3600000")
@AutoConfigureMockMvc
class NotificationTests extends PostgresTestBase {

    static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID CLERK = UUID.fromString("33333333-3333-4333-8333-333333333333");
    static final UUID MANAGER = UUID.fromString("77777777-7777-4777-8777-777777777777");
    static final UUID SCRATCH_ACTOR = UUID.fromString("55555555-5555-4555-8555-555555555555");

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Emails the stub observed (Mailpit's stand-in). */
    static final List<String> EMAILS = new CopyOnWriteArrayList<>();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    com.novaforge.notification.events.TaskEventConsumer consumer;

    @TestConfiguration
    static class Stubs {

        @Bean
        @Primary
        RuntimeAdminPort runtimeAdminPort() {
            return new RuntimeAdminPort() {
                @Override
                public List<UUID> usersOfRole(UUID tenantId, String role) {
                    return "Purch.manager".equals(role) ? List.of(MANAGER) : List.of();
                }

                @Override
                public String usernameOf(UUID user) {
                    if (user.equals(CLERK)) {
                        return "demo";
                    }
                    if (user.equals(MANAGER)) {
                        return "manager";
                    }
                    return "actor-manager-1234";   // the synthetic shape (ADR-010 #3)
                }
            };
        }

        @Bean
        @Primary
        EmailPort emailPort() {
            return (to, subject, body) -> EMAILS.add(to + "|" + subject + "|" + body);
        }
    }

    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestBase::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestBase::jdbcUsername);
        registry.add("spring.datasource.password", PostgresTestBase::jdbcPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM nf_event_outbox");
        jdbc.update("DELETE FROM nf_notifications");
        jdbc.update("DELETE FROM nf_preferences");
        EMAILS.clear();
    }

    @Test
    @DisplayName("task.assigned fans out: inbox row, template tokens, both channels (§8)")
    void assignmentFansOut() throws Exception {
        consumer.onEvent(assignedEvent(MANAGER.toString(), null));
        // wait — assignee-targeted: one inbox row, one email, one delivered per channel
        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("SELECT count(*) FROM nf_notifications",
                        Integer.class)).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT title, body FROM nf_notifications");
        org.assertj.core.api.Assertions.assertThat(String.valueOf(row.get("title")))
                .startsWith("Task assigned: Purch.PurchaseOrder ");
        org.assertj.core.api.Assertions.assertThat(String.valueOf(row.get("body")))
                .contains("Purch.PurchaseOrder").contains("record ");
        org.assertj.core.api.Assertions.assertThat(EMAILS).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForList(
                "SELECT payload->>'channel' FROM nf_event_outbox ORDER BY payload->>'channel'",
                String.class)).containsExactly("email", "inbox");

        // the inbox read API serves the recipient's own data, paged
        mockMvc.perform(get("/api/v1/notifications").with(jwtFor(MANAGER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.rows[0].title").value(
                        org.hamcrest.Matchers.startsWith("Task assigned: Purch.PurchaseOrder ")));
        mockMvc.perform(get("/api/v1/notifications").with(jwtFor(CLERK)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    @DisplayName("role-targeted events resolve the role's holders; preferences filter (§8)")
    void roleFanOutAndPreferences() throws Exception {
        // the manager opts out of email for task assignments
        mockMvc.perform(post("/api/v1/notifications/preferences").with(jwtFor(MANAGER))
                        .contentType("application/json")
                        .content("""
                                { "category": "task-assignment", "inbox": true,
                                  "email": false } """))
                .andExpect(status().isOk());

        consumer.onEvent(assignedEvent(null, "Purch.manager"));
        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("SELECT count(*) FROM nf_notifications",
                        Integer.class)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(EMAILS).isEmpty();   // preference held
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForList(
                "SELECT payload->>'channel' FROM nf_event_outbox", String.class))
                .containsExactly("inbox");
    }

    @Test
    @DisplayName("synthetic actors have no channels — no inbox, no email, no delivered (§8, ADR-010 #3)")
    void syntheticActorsSkipped() {
        consumer.onEvent(assignedEvent(SCRATCH_ACTOR.toString(), null));
        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("SELECT count(*) FROM nf_notifications",
                        Integer.class)).isZero();
        org.assertj.core.api.Assertions.assertThat(EMAILS).isEmpty();
        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("SELECT count(*) FROM nf_event_outbox",
                        Integer.class)).isZero();
    }

    @Test
    @DisplayName("sla.warn delivers the warning category (§8)")
    void slaWarningDelivers() throws Exception {
        String body = """
                { "event": "sla.warn", "eventId": "%s", "taskId": "%s",
                  "tenantId": "%s", "entityId": "Purch.PurchaseOrder",
                  "recordId": "%s", "assignee": "%s", "role": "",
                  "occurredAt": "2026-08-22T00:00:00Z" }"""
                .formatted(UUID.randomUUID(), UUID.randomUUID(), TENANT, RECORD,
                        MANAGER);
        kafka.send("novaforge.sla", TENANT.toString(), body).get();
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(20))
                .pollInterval(java.time.Duration.ofMillis(200))
                .untilAsserted(() -> org.assertj.core.api.Assertions.assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM nf_notifications "
                                        + "WHERE category = 'sla-warning'",
                                Integer.class)).isEqualTo(1));
    }

    // --- helpers ---

    static final String RECORD = UUID.randomUUID().toString();

    private static String assignedEvent(String assignee, String role) {
        return """
                { "event": "task.assigned", "eventId": "%s", "taskId": "%s",
                  "tenantId": "%s", "entityId": "Purch.PurchaseOrder",
                  "recordId": "%s", "assignee": "%s", "role": "%s",
                  "occurredAt": "2026-08-22T00:00:00Z" }"""
                .formatted(UUID.randomUUID(), UUID.randomUUID(), TENANT, RECORD,
                        assignee == null ? "" : assignee, role == null ? "" : role);
    }

    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(UUID actor) {
        return jwt()
                .jwt(token -> token.claim("tenant_id", TENANT.toString()).subject(actor.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
    }
}
