package com.novaforge.runtime.events;

import com.novaforge.runtime.storage.outbox.OutboxStore.OutboxEntry;
import com.novaforge.security.EventHeaders;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spine record's wire shape (PHASE-3 §4): the §13 Q3 partition key
 * {@code tenantId:entityId:recordId} — entity_id is the entity-definition id, the
 * record id keeps the key per-record — and the header conventions: event id/type/tenant
 * plus the W3C {@code traceparent} lifted from the append-time payload (ARCHITECTURE.md
 * §6), absent when the append carried no trace.
 */
class KafkaOutboxRelayShapeTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID RECORD = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    @DisplayName("topicFor derives family topics; keyFor is the §13 Q3 three-part key")
    void topicAndKey() {
        assertThat(KafkaOutboxRelay.topicFor("record.created")).isEqualTo("novaforge.record");
        assertThat(KafkaOutboxRelay.topicFor("hook.retry")).isEqualTo("novaforge.hook");
        assertThat(KafkaOutboxRelay.topicFor("permission.role.assigned"))
                .isEqualTo("novaforge.permission");
        OutboxEntry entry = new OutboxEntry(UUID.randomUUID(), TENANT, "Erp.JournalEntry",
                RECORD, "record.created", "{}");
        assertThat(KafkaOutboxRelay.keyFor(entry))
                .isEqualTo(TENANT + ":Erp.JournalEntry:" + RECORD);
        // a recordless app event (a scheduled flow's publishEvent tail, V5) keys
        // tenant:entity — per-entity ordering holds, no record exists to order per
        OutboxEntry recordless = new OutboxEntry(UUID.randomUUID(), TENANT, "Erp.Payment",
                null, "erp.bankfeed.synced", "{}");
        assertThat(KafkaOutboxRelay.keyFor(recordless))
                .isEqualTo(TENANT + ":Erp.Payment");
    }

    @Test
    @DisplayName("headers: the shared constants, and traceparent lifts from the payload")
    void headersStampAndLift() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", "evt-1");
        payload.put("event", "record.created");
        payload.put("tenantId", TENANT.toString());
        payload.put("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        ProducerRecord<String, String> record = new ProducerRecord<>("novaforge.record", "k", "{}");
        KafkaOutboxRelay.stampHeaders(record, payload);
        assertThat(new String(record.headers().lastHeader(EventHeaders.EVENT_ID).value(),
                StandardCharsets.UTF_8)).isEqualTo("evt-1");
        assertThat(new String(record.headers().lastHeader(EventHeaders.EVENT_TYPE).value(),
                StandardCharsets.UTF_8)).isEqualTo("record.created");
        assertThat(new String(record.headers().lastHeader(EventHeaders.TENANT_ID).value(),
                StandardCharsets.UTF_8)).isEqualTo(TENANT.toString());
        assertThat(new String(record.headers().lastHeader(EventHeaders.TRACEPARENT).value(),
                StandardCharsets.UTF_8)).contains("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    @DisplayName("no captured trace at append means no traceparent header — never a blank one")
    void noTraceNoHeader() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", "evt-2");
        payload.put("event", "record.updated");
        payload.put("tenantId", TENANT.toString());
        ProducerRecord<String, String> record = new ProducerRecord<>("novaforge.record", "k", "{}");
        KafkaOutboxRelay.stampHeaders(record, payload);
        assertThat(record.headers().headers(EventHeaders.TRACEPARENT)).isEmpty();
    }
}
