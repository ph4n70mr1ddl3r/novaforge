package com.novaforge.audit.api;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.novaforge.audit.store.AuditStore;
import io.micrometer.tracing.Tracer;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Anti-regression (found in the 2026-08-31 hunt): the audit consumers wrapped the
 * store append inside a catch-all — a database failure (pool exhaustion, failover,
 * serialization error) was classified "invalid event ignored", the offset committed,
 * and the event left a permanent, silent hole in the compliance trail. The live
 * reactor flake (a missing {@code notification.delivered} row under parallel load)
 * was exactly this mechanism.
 *
 * <p>The convention (the notification consumer's, re-pinned here): envelope-shape
 * errors are terminal — log and skip; processing failures propagate so the spine
 * redelivers, with the append's (event_id, occurred_at) dedupe collapsing the
 * replay.</p>
 */
class AuditConsumerFailureTests {

    private static final String RECORD_EVENT = """
            { "event": "record.created", "eventId": "5e9bdf15-c450-452a-aabf-24a54c44a178",
              "tenantId": "11111111-1111-4111-8111-111111111111",
              "entityId": "Erp.Order", "recordId": "2f4108cf-e95f-45a0-9a5a-b83d450db071",
              "actorId": "33333333-3333-4333-8333-333333333333",
              "occurredAt": "2026-08-24T09:02:00.000Z" }""";

    private static final String PLATFORM_EVENT = """
            { "event": "notification.delivered", "eventId": "6f9bdf15-c450-452a-aabf-24a54c44a178",
              "tenantId": "11111111-1111-4111-8111-111111111111",
              "deliveryId": "2f4108cf-e95f-45a0-9a5a-b83d450db071",
              "publishedAt": "2026-08-24T09:02:00.000Z" }""";

    private static final String INTEGRATION_EVENT = """
            { "event": "webhook.dispatched", "eventId": "7f9bdf15-c450-452a-aabf-24a54c44a178",
              "tenantId": "11111111-1111-4111-8111-111111111111",
              "deliveryId": "2f4108cf-e95f-45a0-9a5a-b83d450db071",
              "occurredAt": "2026-08-24T09:02:00.000Z" }""";

    private final AuditStore store = mock(AuditStore.class);
    private final Tracer tracer = mock(Tracer.class);

    @Test
    @DisplayName("a store failure propagates out of every consumer — redelivery, not a silent gap")
    void storeFailurePropagates() {
        doThrow(new DataAccessResourceFailureException("postgres restarting"))
                .when(store).append(any(UUID.class), any(UUID.class), anyString(), any(UUID.class),
                anyString(), any(UUID.class), any(Instant.class), anyString());
        RecordEventConsumer record = new RecordEventConsumer(store, tracer);
        PlatformEventConsumer platform = new PlatformEventConsumer(store, tracer);
        IntegrationEventConsumer integration = new IntegrationEventConsumer(store, tracer);

        assertThatThrownBy(() -> record.consume(RECORD_EVENT))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThatThrownBy(() -> platform.consume(PLATFORM_EVENT))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThatThrownBy(() -> integration.consume(INTEGRATION_EVENT))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    @DisplayName("envelope-shape errors stay terminal — no throw, no redelivery storm")
    void malformedEnvelopesAreTerminal() {
        RecordEventConsumer record = new RecordEventConsumer(store, tracer);
        assertThatCode(() -> record.consume("not json at all")).doesNotThrowAnyException();
        assertThatCode(() -> record.consume("""
                { "event": "record.created", "eventId": "not-a-uuid",
                  "tenantId": "11111111-1111-4111-8111-111111111111",
                  "entityId": "Erp.Order", "recordId": "2f4108cf-e95f-45a0-9a5a-b83d450db071",
                  "actorId": "33333333-3333-4333-8333-333333333333",
                  "occurredAt": "2026-08-24T09:02:00.000Z" }"""))
                .doesNotThrowAnyException();
        assertThatCode(() -> record.consume("""
                { "event": "record.created", "eventId": "5e9bdf15-c450-452a-aabf-24a54c44a178",
                  "tenantId": "11111111-1111-4111-8111-111111111111",
                  "entityId": "Erp.Order", "recordId": "2f4108cf-e95f-45a0-9a5a-b83d450db071",
                  "actorId": "33333333-3333-4333-8333-333333333333",
                  "occurredAt": "not-a-timestamp" }"""))
                .doesNotThrowAnyException();
        // a platform event with no timestamp of any kind is malformed, not defaulted
        // (a per-redelivery now() would defeat the dedupe and duplicate every replay)
        PlatformEventConsumer platform = new PlatformEventConsumer(store, tracer);
        assertThatCode(() -> platform.consume("""
                { "event": "notification.delivered", "eventId": "6f9bdf15-c450-452a-aabf-24a54c44a178",
                  "tenantId": "11111111-1111-4111-8111-111111111111",
                  "deliveryId": "2f4108cf-e95f-45a0-9a5a-b83d450db071" }"""))
                .doesNotThrowAnyException();
    }
}
