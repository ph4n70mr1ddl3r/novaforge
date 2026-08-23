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

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestBase::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestBase::jdbcUsername);
        registry.add("spring.datasource.password", PostgresTestBase::jdbcPassword);
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
                                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    private String mockMvcPerform(UUID recordId) throws Exception {
        return mockMvc.perform(get("/api/v1/audit/records/" + recordId)
                        .with(jwt().jwt(token -> token.claim("tenant_id", TENANT.toString())
                                .subject(ACTOR.toString()))
                                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }
}
