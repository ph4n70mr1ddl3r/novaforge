package com.novaforge.workflow.events;

import com.novaforge.security.EventHeaders;
import com.novaforge.security.TracePropagation;
import com.novaforge.workflow.process.ProcessStarts;
import com.novaforge.workflow.task.TaskService;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code novaforge.record} (PHASE-4 §1/T1, §9): record deletion cancels
 * the record's open tasks (§5), and record created/updated events evaluate the
 * deployed event-start subscriptions (§9) — matching events start BPMN processes
 * with the engine's transaction joined to the dedupe claim (at-least-once
 * redelivery collapses). The service never mutates records either way; state
 * changes arrive as events and approvals resolve through the runtime's write path
 * (§3's single-write-path pin).
 */
@Component
public class RecordEventConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(RecordEventConsumer.class);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final TaskService tasks;
    private final ProcessStarts starts;
    private final Tracer tracer;

    public RecordEventConsumer(TaskService tasks, ProcessStarts starts, Tracer tracer) {
        this.tasks = tasks;
        this.starts = starts;
        this.tracer = tracer;
    }

    @KafkaListener(topics = "novaforge.record", groupId = "novaforge-workflow")
    public void onEvent(ConsumerRecord<String, String> message) {
        var header = message.headers().lastHeader(EventHeaders.TRACEPARENT);
        String traceparent = header == null ? null
                : new String(header.value(), StandardCharsets.UTF_8);
        TracePropagation.inConsumerSpan(tracer, traceparent,
                "novaforge.record consume", () -> consume(message.value()));
    }

    void consume(String payload) {
        Map<String, Object> event;
        try {
            event = MAPPER.readValue(payload, Map.class);
        } catch (Exception e) {
            LOG.error("invalid record event ignored: {}", payload, e);
            return;   // unparseable — no redelivery can fix it
        }
        // Processing failures propagate: the spine is at-least-once, so the
        // consumer must redeliver on a transient error (the runtime down, a lock
        // timeout) rather than swallow the event — the §9 dedupe claim collapses
        // any double-start, so rethrowing is safe. Only envelope-shape errors are
        // terminal here.
        try {
            dispatch(event, payload);
        } catch (IllegalArgumentException e) {
            LOG.error("malformed record event ignored: {}", payload, e);
        }
    }

    private void dispatch(Map<String, Object> event, String payload) {
        String type = String.valueOf(event.get("event"));
        UUID tenantId = UUID.fromString(String.valueOf(event.get("tenantId")));
        UUID recordId = UUID.fromString(String.valueOf(event.get("recordId")));
        if ("record.deleted".equals(type)) {
            tasks.cancelForRecord(tenantId, recordId);
            return;
        }
        if ("record.created".equals(type) || "record.updated".equals(type)) {
            UUID eventId = UUID.fromString(String.valueOf(event.get("eventId")));
            UUID actorId = event.get("actorId") == null ? null
                    : UUID.fromString(String.valueOf(event.get("actorId")));
            starts.onRecordEvent(eventId, type, tenantId,
                    String.valueOf(event.get("entityId")), recordId, actorId);
        }
    }
}
