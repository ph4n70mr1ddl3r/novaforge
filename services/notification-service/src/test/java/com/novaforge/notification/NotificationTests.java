package com.novaforge.notification;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novaforge.notification.notify.Notifier;
import com.novaforge.notification.notify.Notifier.EmailPort;
import com.novaforge.notification.notify.RecipientResolver.RuntimeAdminPort;
import com.novaforge.notification.notify.RuntimeRecordPort;
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
 * The Phase 5 growth rides here too: the internal send surface (PHASE-5 §7) — the
 * scheduled report-delivery leg with its inline attachment.
 */
@SpringBootTest(properties = "novaforge.events.relay-interval-ms=3600000")
@AutoConfigureMockMvc
class NotificationTests extends PostgresTestBase {

    static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID CLERK = UUID.fromString("33333333-3333-4333-8333-333333333333");
    static final UUID MANAGER = UUID.fromString("77777777-7777-4777-8777-777777777777");
    static final UUID SCRATCH_ACTOR = UUID.fromString("55555555-5555-4555-8555-555555555555");

    /** A user of ANOTHER tenant — a platform-valid id with no membership in TENANT. */
    static final UUID FOREIGN_USER = UUID.fromString("99999999-9999-4999-8999-999999999999");

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Emails the stub observed (Mailpit's stand-in). */
    static final List<String> EMAILS = new CopyOnWriteArrayList<>();

    /** Attachments the stub observed (the §7 report-delivery leg's inline export). */
    static final List<String> ATTACHMENTS = new CopyOnWriteArrayList<>();

    /** Record fetches the stub observed (the §8 ${record.*} binding). */
    static final List<String> RECORD_FETCHES = new CopyOnWriteArrayList<>();

    /** When set, the record port fails — delivery degrades, never blocks (§8). */
    static volatile boolean failRecordFetch = false;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    com.novaforge.notification.events.TaskEventConsumer consumer;

    @Autowired
    Notifier notifier;

    @Autowired
    com.novaforge.notification.events.NotificationOutboxRelay outboxRelay;

    @Autowired
    org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<String, String>
            listenerFactory;

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

                @Override
                public List<String> rolesOfUser(UUID tenantId, UUID user) {
                    // the platform DB's tenant binding: role rows in the tenant or none
                    return TENANT.equals(tenantId)
                            && (user.equals(CLERK) || user.equals(MANAGER))
                                    ? List.of("user") : List.of();
                }
            };
        }

        @Bean
        @Primary
        RuntimeRecordPort runtimeRecordPort() {
            return (tenantId, entityKey, recordId) -> {
                RECORD_FETCHES.add(tenantId + "/" + entityKey + "/" + recordId);
                if (failRecordFetch) {
                    throw new RuntimeException("runtime unreachable");
                }
                return Map.of("reference", "PO-1024", "total", "120.0000");
            };
        }

        @Bean
        @Primary
        EmailPort emailPort() {
            return new EmailPort() {
                @Override
                public void send(String to, String subject, String body) {
                    EMAILS.add(to + "|" + subject + "|" + body);
                }

                @Override
                public void send(String to, String subject, String body,
                                 Notifier.Attachment attachment) {
                    EMAILS.add(to + "|" + subject + "|" + body);
                    ATTACHMENTS.add(to + "|" + attachment.filename() + "|"
                            + attachment.contentType() + "|" + attachment.content().length);
                }
            };
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
        ATTACHMENTS.clear();
        RECORD_FETCHES.clear();
        failRecordFetch = false;
    }

    @Test
    @DisplayName("task.assigned fans out: inbox row, template tokens, both channels (§8)")
    void assignmentFansOut() throws Exception {
        consumer.onEvent(record(assignedEvent(MANAGER.toString(), null)));
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
    @DisplayName("over-limit page sizes reject, never silently clamp (PHASE-1 §5's convention via PHASE-4 §5)")
    void inboxRejectsOverLimitPaging() throws Exception {
        mockMvc.perform(get("/api/v1/notifications").queryParam("size", "201")
                        .with(jwtFor(MANAGER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4000"));
        // the boundary itself serves
        mockMvc.perform(get("/api/v1/notifications").queryParam("size", "200")
                        .with(jwtFor(MANAGER)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an overflowing page rejects 400 — int page*size never wraps to a negative OFFSET 500")
    void inboxRejectsOverflowingPage() throws Exception {
        // Anti-regression (2026-08-31): page was unbounded, and 2,000,000,000 × 200
        // overflowed the int OFFSET to negative — a Postgres error surfaced as 500.
        mockMvc.perform(get("/api/v1/notifications")
                        .queryParam("page", "2000000000").queryParam("size", "200")
                        .with(jwtFor(MANAGER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4000"));
        // the bound's boundary itself serves (empty page past the data, not an error)
        mockMvc.perform(get("/api/v1/notifications")
                        .queryParam("page", "1000000").queryParam("size", "200")
                        .with(jwtFor(MANAGER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(0));
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

        consumer.onEvent(record(assignedEvent(null, "Purch.manager")));
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
        consumer.onEvent(record(assignedEvent(SCRATCH_ACTOR.toString(), null)));
        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("SELECT count(*) FROM nf_notifications",
                        Integer.class)).isZero();
        org.assertj.core.api.Assertions.assertThat(EMAILS).isEmpty();
        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("SELECT count(*) FROM nf_event_outbox",
                        Integer.class)).isZero();
    }

    @Test
    @DisplayName("${record.field} tokens resolve from the fetched record; failure degrades (§8)")
    void recordTokensResolve() {
        java.util.UUID recordId = java.util.UUID.fromString(RECORD);
        // the token mechanism: both binding sets resolve side by side, absent keys empty
        org.assertj.core.api.Assertions.assertThat(Notifier.resolve(
                "${record.reference} owes ${record.total} (${task.entityId})",
                Map.of("entityId", "Purch.PurchaseOrder"),
                Map.of("reference", "PO-1024", "total", "120.0000")))
                .isEqualTo("PO-1024 owes 120.0000 (Purch.PurchaseOrder)");
        org.assertj.core.api.Assertions.assertThat(Notifier.resolve(
                "${record.ghost} | ${task.ghost}", Map.of(), Map.of()))
                .isEqualTo(" | ");

        // the fan-out fetches the record once per event and renders through it
        notifier.onEvent(java.util.UUID.randomUUID().toString(), TENANT,
                Notifier.TASK_ASSIGNMENT,
                Map.of("entityId", "Purch.PurchaseOrder", "recordId", RECORD,
                        "assignee", MANAGER.toString()),
                Map.of("reference", "PO-1024"),
                "${task.entityId} ${task.recordId}",
                "record ${record.reference} awaits");
        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("SELECT body FROM nf_notifications",
                        String.class)).isEqualTo("record PO-1024 awaits");

        // the consumer's leg: an event fetches once — and a failing fetch never
        // blocks the fan-out (tokens render empty, the inbox row still lands)
        RECORD_FETCHES.clear();
        consumer.onEvent(record(assignedEvent(MANAGER.toString(), null)));
        org.assertj.core.api.Assertions.assertThat(RECORD_FETCHES).containsExactly(
                TENANT + "/Purch.PurchaseOrder/" + RECORD);
        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("SELECT count(*) FROM nf_notifications",
                        Integer.class)).isEqualTo(2);

        jdbc.update("DELETE FROM nf_notifications");
        failRecordFetch = true;
        consumer.onEvent(record(assignedEvent(MANAGER.toString(), null)));
        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("SELECT count(*) FROM nf_notifications",
                        Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("a replayed spine event re-emails nobody — the email leg claims its key")
    void replayedEventNeverReEmails() {
        // Anti-regression (eighteenth pass): the inbox insert deduped on event_id but
        // the SMTP leg ran unconditionally — a Kafka redelivery of the event
        // (TaskEventConsumer deliberately rethrows transient failures) collapsed the
        // inbox row while every email-preferred recipient was emailed again.
        String event = assignedEvent(MANAGER.toString(), null);
        EMAILS.clear();
        consumer.onEvent(record(event));
        org.assertj.core.api.Assertions.assertThat(EMAILS).hasSize(1);
        // the redelivery of the SAME event id: the inbox collapses AND the email
        // leg's claim holds — exactly one email, ever
        consumer.onEvent(record(event));
        org.assertj.core.api.Assertions.assertThat(EMAILS).hasSize(1);
    }

    @Test
    @DisplayName("sla.warn delivers the warning category (§8)")
    void slaWarningDelivers() throws Exception {        String body = """
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

    @Test
    @DisplayName("onBreach.notify: false — the breach rides the spine but never fans out (§6)")
    void quietBreachNeverFansOut() throws Exception {
        String quiet = """
                { "event": "sla.breach", "eventId": "%s", "taskId": "%s",
                  "tenantId": "%s", "entityId": "Purch.PurchaseOrder",
                  "recordId": "%s", "assignee": "%s", "role": "",
                  "notify": false,
                  "occurredAt": "2026-08-22T00:00:00Z" }"""
                .formatted(UUID.randomUUID(), UUID.randomUUID(), TENANT, RECORD, MANAGER);
        consumer.onEvent(record(quiet));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM nf_notifications", Integer.class)).isZero();
        org.assertj.core.api.Assertions.assertThat(EMAILS).isEmpty();

        // without the flag the same breach delivers as an sla-warning
        String loud = quiet.replace("\"notify\": false,", "");
        consumer.onEvent(record(loud));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM nf_notifications WHERE category = 'sla-warning'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("outbox retention: published rows older than the window leave; fresh and unpublished stay")
    void outboxRetentionDropsOldPublishedRows() {
        for (int i = 0; i < 2; i++) {
            jdbc.update("""
                    INSERT INTO nf_event_outbox (id, tenant_id, event_type, payload)
                    VALUES (?, ?, 'notification.delivered', '{}'::jsonb)""",
                    UUID.randomUUID(), TENANT);
        }
        jdbc.update("UPDATE nf_event_outbox SET published_at = now() - interval '30 days'");
        jdbc.update("""
                INSERT INTO nf_event_outbox (id, tenant_id, event_type, payload, published_at)
                VALUES (?, ?, 'notification.delivered', '{}'::jsonb, now())""",
                UUID.randomUUID(), TENANT);
        jdbc.update("""
                INSERT INTO nf_event_outbox (id, tenant_id, event_type, payload)
                VALUES (?, ?, 'notification.delivered', '{}'::jsonb)""",
                UUID.randomUUID(), TENANT);

        outboxRelay.retain();

        // the 30-day-old published rows left; the fresh published and unpublished stay
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM nf_event_outbox", Integer.class)).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM nf_event_outbox WHERE published_at IS NULL",
                Integer.class)).isEqualTo(1);
    }

    // --- PHASE-5 §7: the internal send surface (scheduled report delivery) ---

    @Test
    @DisplayName("internal/send: role holders + explicit users, attachment inline, gated (§7)")
    void internalSendDelivers() throws Exception {
        String body = MAPPER.writeValueAsString(Map.of(
                "tenantId", TENANT.toString(),
                "category", "report-delivery",
                "title", "Report arAging (ArDesk)",
                "body", "The scheduled report arAging of app ArDesk is attached (csv).",
                "recipients", Map.of("roles", List.of("Purch.manager"),
                        "users", List.of(CLERK.toString())),
                "attachment", Map.of("filename", "arAging.csv", "contentType", "text/csv",
                        "contentBase64", java.util.Base64.getEncoder()
                                .encodeToString("customer,total\r\nacme,300.5\r\n"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)))));
        mockMvc.perform(post("/api/v1/notifications/internal/send")
                        .with(serviceJwt()).contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(4))   // 2 users × inbox+email
                .andExpect(jsonPath("$.recipients").value(2));
        // the manager arrives via the role's holders, the clerk by explicit id —
        // one inbox row each under the report-delivery category, attachments inline
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForList(
                "SELECT user_id FROM nf_notifications WHERE category = 'report-delivery' "
                        + "ORDER BY user_id", UUID.class))
                .containsExactly(CLERK, MANAGER);
        org.assertj.core.api.Assertions.assertThat(ATTACHMENTS).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(ATTACHMENTS)
                .anySatisfy(a -> org.assertj.core.api.Assertions.assertThat(a)
                        .startsWith("demo@localhost|arAging.csv|text/csv|"))
                .anySatisfy(a -> org.assertj.core.api.Assertions.assertThat(a)
                        .startsWith("manager@localhost|arAging.csv|text/csv|"));

        // user traffic never reaches the internal surface — the gate holds
        mockMvc.perform(post("/api/v1/notifications/internal/send")
                        .with(jwtFor(CLERK)).contentType("application/json").content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("internal/send: a keyed replay collapses — no duplicate inbox rows or emails")
    void internalSendKeyedReplayCollapses() throws Exception {
        // Anti-regression (2026-08-31): the internal send's event_id was a fresh UUID
        // per attempt, so any caller retry (report job re-fired, transient 5xx
        // retried) duplicated the inbox row and the email for every recipient.
        String body = MAPPER.writeValueAsString(Map.of(
                "tenantId", TENANT.toString(),
                "category", "job-completed",
                "title", "Job done", "body", "summary",
                "recipients", Map.of("users", List.of(CLERK.toString())),
                "deliveryId", "job-pin-1"));
        mockMvc.perform(post("/api/v1/notifications/internal/send")
                        .with(serviceJwt()).contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(2));   // inbox + email
        mockMvc.perform(post("/api/v1/notifications/internal/send")
                        .with(serviceJwt()).contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(0));   // the replay collapsed
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForList(
                "SELECT count(*) FROM nf_notifications WHERE category = 'job-completed' "
                        + "AND user_id = '" + CLERK + "'", Integer.class))
                .containsExactly(1);
        // an unkeyed send still delivers every time (fresh content, no false dedupe)
        String unkeyed = MAPPER.writeValueAsString(Map.of(
                "tenantId", TENANT.toString(),
                "category", "job-completed",
                "title", "Job done again", "body", "summary",
                "recipients", Map.of("users", List.of(CLERK.toString()))));
        mockMvc.perform(post("/api/v1/notifications/internal/send")
                        .with(serviceJwt()).contentType("application/json").content(unkeyed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(2));
    }

    @Test
    @DisplayName("keyed replays do not re-email inbox-opted-out recipients (the marker)")
    void keyedReplayNeverReEmailsInboxOptedOut() throws Exception {
        // Anti-regression (2026-08-31, fourteenth pass): a keyed send to a recipient
        // with inbox OFF produced no inbox row, so nothing recorded the email — every
        // replay (a retried scheduler window) re-emailed them. The V2 marker row
        // dedupes the email leg on the same key the inbox row always did.
        mockMvc.perform(post("/api/v1/notifications/preferences").with(jwtFor(CLERK))
                        .contentType("application/json")
                        .content("""
                                { "category": "job-completed", "inbox": false,
                                  "email": true } """))
                .andExpect(status().isOk());
        // EMAILS is shared with the spine consumers' asynchronous fan-out — pin only
        // this test's own sends by a unique subject sentinel
        String sentinel = "Job done marker-" + java.util.UUID.randomUUID();
        String body = MAPPER.writeValueAsString(Map.of(
                "tenantId", TENANT.toString(),
                "category", "job-completed",
                "title", sentinel, "body", "summary",
                "recipients", Map.of("users", List.of(CLERK.toString())),
                "deliveryId", "job-marker-1"));
        mockMvc.perform(post("/api/v1/notifications/internal/send")
                        .with(serviceJwt()).contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(1));   // email only
        mockMvc.perform(post("/api/v1/notifications/internal/send")
                        .with(serviceJwt()).contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(0));   // the replay collapsed
        org.assertj.core.api.Assertions.assertThat(
                EMAILS.stream().filter(e -> e.contains(sentinel)).count()).isEqualTo(1);
        Integer markers = jdbc.queryForObject(
                "SELECT count(*) FROM nf_email_deliveries WHERE event_id = 'job-marker-1'",
                Integer.class);
        org.assertj.core.api.Assertions.assertThat(markers).isEqualTo(1);
        // an unkeyed send still delivers (fresh content, no false dedupe)
        String unkeyed = MAPPER.writeValueAsString(Map.of(
                "tenantId", TENANT.toString(),
                "category", "job-completed",
                "title", sentinel + "-x", "body", "summary",
                "recipients", Map.of("users", List.of(CLERK.toString()))));
        mockMvc.perform(post("/api/v1/notifications/internal/send")
                        .with(serviceJwt()).contentType("application/json").content(unkeyed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(1));
        org.assertj.core.api.Assertions.assertThat(
                EMAILS.stream().filter(e -> e.contains(sentinel)).count()).isEqualTo(2);
    }

    @Test
    @DisplayName("internal/send: recipients that resolve to nobody reject audibly (§7)")
    void internalSendWithoutRecipientsRejects() throws Exception {
        String body = MAPPER.writeValueAsString(Map.of(
                "tenantId", TENANT.toString(), "category", "report-delivery",
                "title", "t", "body", "b",
                "recipients", Map.of("roles", List.of("Purch.ghost"))));
        mockMvc.perform(post("/api/v1/notifications/internal/send")
                        .with(serviceJwt()).contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("internal/send: a cross-tenant recipient id never delivers — no inbox row, no email")
    void crossTenantRecipientNeverDelivers() throws Exception {
        // Anti-regression (2026-08-31): explicit recipients.users ids rode verbatim
        // through a GLOBAL user lookup — a recipient list naming another tenant's
        // user delivered the sending tenant's data (inbox row + emailed export) to
        // a foreign user. Membership in the sending tenant is now the gate.
        String sentinel = "Cross tenant-" + UUID.randomUUID();
        String body = MAPPER.writeValueAsString(Map.of(
                "tenantId", TENANT.toString(), "category", "report-delivery",
                "title", sentinel, "body", "the sending tenant's data",
                "recipients", Map.of("users",
                        List.of(FOREIGN_USER.toString(), CLERK.toString()))));
        mockMvc.perform(post("/api/v1/notifications/internal/send")
                        .with(serviceJwt()).contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipients").value(1));   // only the member

        // the member got exactly one inbox row; the foreign user got NOTHING —
        // no row, no email, no delivered event under any tenant
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForList(
                "SELECT user_id FROM nf_notifications WHERE title = '" + sentinel + "'",
                UUID.class)).containsExactly(CLERK);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForList(
                "SELECT user_id FROM nf_notifications WHERE user_id = '"
                        + FOREIGN_USER + "'", UUID.class)).isEmpty();
        org.assertj.core.api.Assertions.assertThat(EMAILS.stream()
                .filter(e -> e.contains(sentinel)).count()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForList(
                "SELECT payload->>'userId' FROM nf_event_outbox", String.class))
                .doesNotContain(FOREIGN_USER.toString());

        // and when EVERY named recipient is foreign, the send fails closed — an
        // audible rejection beats a silent empty delivery
        String allForeign = MAPPER.writeValueAsString(Map.of(
                "tenantId", TENANT.toString(), "category", "report-delivery",
                "title", sentinel + "-x", "body", "b",
                "recipients", Map.of("users", List.of(FOREIGN_USER.toString()))));
        mockMvc.perform(post("/api/v1/notifications/internal/send")
                        .with(serviceJwt()).contentType("application/json").content(allForeign))
                .andExpect(status().isBadRequest());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForList(
                "SELECT count(*) FROM nf_notifications WHERE title = '" + sentinel + "-x'",
                Integer.class)).containsExactly(0);
    }

    // --- helpers ---

    @Test
    @DisplayName("a failed send redelivers with real backoff — never the default 9x zero-backoff log-and-skip")
    void failedSendRedeliversWithBackoff() throws Exception {
        // Anti-regression (2026-08-31): TaskEventConsumer deliberately propagates
        // non-envelope failures so Notifier's @Transactional inbox rows roll back
        // with a failed send — but with no container error handler Boot's default
        // burned nine ZERO-backoff retries in under a second, then logged and
        // skipped: an SMTP outage silently lost every task.assigned/sla.warn/
        // sla.breach in flight (no inbox row, no email, offset committed).
        // ConsumerErrorConfig mirrors the Workflow Service's mechanism.
        var probe = listenerFactory.createContainer("novaforge-notification-wiring-probe");
        var handler = probe.getCommonErrorHandler();
        org.assertj.core.api.Assertions.assertThat(handler).isInstanceOf(
                org.springframework.kafka.listener.DefaultErrorHandler.class);
        org.assertj.core.api.Assertions.assertThat(handler.seeksAfterHandling()).isTrue();

        // Behavior against the real broker: an always-failing listener on a private
        // topic/group riding the very factory the task/sla listener resolves. The
        // default handler's nine zero-backoff retries land as a sub-second burst of
        // ten deliveries then silence; ours spreads real retries (1 s doubling).
        String topic = "novaforge.task.pin-" + UUID.randomUUID();
        List<Long> deliveries = new CopyOnWriteArrayList<>();
        var container = listenerFactory.createContainer(topic);
        container.getContainerProperties().setGroupId("novaforge-notification-pin-"
                + UUID.randomUUID());
        java.util.Properties consumerProps = new java.util.Properties();
        consumerProps.setProperty("auto.offset.reset", "earliest");
        container.getContainerProperties().setKafkaConsumerProperties(consumerProps);
        container.getContainerProperties().setMessageListener(
                (org.springframework.kafka.listener.MessageListener<String, String>) record -> {
                    deliveries.add(System.currentTimeMillis());
                    throw new org.springframework.mail.MailSendException("smtp down");
                });
        try {
            container.start();
            kafka.send(topic, "pin", "{}").get();
            org.awaitility.Awaitility.await()
                    .atMost(java.time.Duration.ofSeconds(20))
                    .until(() -> deliveries.size() >= 3);
            org.assertj.core.api.Assertions
                    .assertThat(deliveries.get(2) - deliveries.get(0))
                    .isGreaterThanOrEqualTo(1_500L);   // backoff, not a burst
            org.assertj.core.api.Assertions.assertThat(deliveries.size()).isLessThan(10);
        } finally {
            container.stop();
        }
    }

    static final String RECORD = UUID.randomUUID().toString();

    /** A consumer record wrapping the payload — the listener takes the record so it
     *  can read the spine's traceparent header (ARCHITECTURE.md §6). */
    private static org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record(
            String payload) {
        return new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                "novaforge.task", 0, 0L, "key", payload);
    }

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

    /** The platform service client (azp) — the internal send surface's gate. */
    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor serviceJwt() {
        return jwt()
                .jwt(token -> token.claim("azp", "novaforge-runtime")
                        .subject("service-account-novaforge-runtime"))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
    }
}
