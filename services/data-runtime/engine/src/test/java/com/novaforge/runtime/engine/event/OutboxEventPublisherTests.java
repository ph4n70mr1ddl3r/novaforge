package com.novaforge.runtime.engine.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.novaforge.runtime.engine.event.DomainEventPublisher.DomainEvent;
import com.novaforge.runtime.storage.outbox.OutboxStore;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * OutboxEventPublisher's envelope had no direct pin (twenty-ninth pass coverage
 * audit) — only the relay's payload shape was tested. The envelope is the
 * consumer contract: eventId keys consumer dedup, recordless app events must
 * OMIT the record id (consumers key on the event id regardless), and the
 * captured W3C traceparent is what links a consumer's span onto the request
 * that caused the event. A dropped field regresses downstream, far from here.
 */
class OutboxEventPublisherTests {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ACTOR = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID RECORD = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String ENTITY_ID = "invoice";
    private static final String TRACE_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SPAN_ID = "bbbbbbbbbbbbbbbb";

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedPayload(OutboxStore outbox) {
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(outbox).append(any(UUID.class), eq(TENANT), eq(ENTITY_ID), any(), anyString(),
                payload.capture());
        return payload.getValue();
    }

    private static Tracer tracerWithActiveSpan() {
        TraceContext context = mock(TraceContext.class);
        when(context.traceId()).thenReturn(TRACE_ID);
        when(context.spanId()).thenReturn(SPAN_ID);
        when(context.sampled()).thenReturn(true);
        Span span = mock(Span.class);
        when(span.context()).thenReturn(context);
        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenReturn(span);
        return tracer;
    }

    @Test
    @DisplayName("a record event's envelope carries eventId, ids, occurredAt, and the recordId")
    void recordEventEnvelope() {
        var outbox = mock(OutboxStore.class);
        var publisher = new OutboxEventPublisher(outbox, null);
        var event = new DomainEvent("record.created", TENANT, ENTITY_ID, RECORD, ACTOR,
                "2026-09-02T00:00:00Z");

        publisher.publish(event);

        Map<String, Object> payload = capturedPayload(outbox);
        assertThat(payload).containsEntry("event", "record.created")
                .containsEntry("tenantId", TENANT.toString())
                .containsEntry("entityId", ENTITY_ID)
                .containsEntry("recordId", RECORD.toString())
                .containsEntry("actorId", ACTOR.toString())
                .containsEntry("occurredAt", "2026-09-02T00:00:00Z");
        // the dedup key: present and a parseable uuid
        assertThat(UUID.fromString((String) payload.get("eventId"))).isNotNull();
    }

    @Test
    @DisplayName("a recordless app event omits recordId from the payload AND the outbox row")
    void recordlessEventOmitsRecordId() {
        var outbox = mock(OutboxStore.class);
        var publisher = new OutboxEventPublisher(outbox, null);
        var event = new DomainEvent("flow.published", TENANT, ENTITY_ID, null, ACTOR, null);

        publisher.publish(event, Map.of("trigger", "schedule"));

        ArgumentCaptor<UUID> recordId = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(outbox).append(any(UUID.class), eq(TENANT), eq(ENTITY_ID), recordId.capture(),
                eq("flow.published"), payload.capture());
        assertThat(recordId.getValue()).isNull();
        assertThat(payload.getValue()).doesNotContainKey("recordId");
        // a null occurredAt falls back to now — never absent
        assertThat(payload.getValue().get("occurredAt")).isNotNull();
        // envelope extensions ride alongside the envelope fields
        assertThat(payload.getValue()).containsEntry("trigger", "schedule");
    }

    @Test
    @DisplayName("the W3C traceparent of the causing request rides the payload; without a span it is absent")
    void traceparentCapture() {
        var outbox = mock(OutboxStore.class);
        new OutboxEventPublisher(outbox, tracerWithActiveSpan())
                .publish(new DomainEvent("record.updated", TENANT, ENTITY_ID, RECORD, ACTOR, null));

        Map<String, Object> payload = capturedPayload(outbox);
        assertThat(payload).containsEntry("traceparent", "00-" + TRACE_ID + "-" + SPAN_ID + "-01");

        var quietOutbox = mock(OutboxStore.class);
        new OutboxEventPublisher(quietOutbox, null)
                .publish(new DomainEvent("record.updated", TENANT, ENTITY_ID, RECORD, ACTOR, null));
        Map<String, Object> quietPayload = capturedPayload(quietOutbox);
        assertThat(quietPayload).doesNotContainKey("traceparent");
    }

    @Test
    @DisplayName("the outbox row is tenant- and entity-keyed for the relay's partitioning")
    void rowKeys() {
        var outbox = mock(OutboxStore.class);
        new OutboxEventPublisher(outbox, null)
                .publish(new DomainEvent("record.deleted", TENANT, ENTITY_ID, RECORD, ACTOR, null));

        verify(outbox).append(any(UUID.class), eq(TENANT), eq(ENTITY_ID), eq(RECORD),
                eq("record.deleted"), any());
    }
}
