package com.novaforge.notification.events;

import com.novaforge.notification.notify.Notifier;
import com.novaforge.notification.notify.RuntimeRecordPort;
import com.novaforge.security.EventHeaders;
import com.novaforge.security.TracePropagation;
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
 * Consumes the spine (PHASE-4 §8): {@code task.assigned} fans out as
 * {@code task-assignment}, {@code sla.warn}/{@code sla.breach} as
 * {@code sla-warning}. Built-in platform templates per category (no authoring
 * surface in v1); the event payload is the {@code ${task.*}} binding set, and the
 * task's record — fetched once per event through the runtime's internal read — is
 * the {@code ${record.*}} binding set (§8's token pin; best-effort, per §5).
 */
@Component
public class TaskEventConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(TaskEventConsumer.class);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final Notifier notifier;
    private final RuntimeRecordPort records;
    private final Tracer tracer;

    public TaskEventConsumer(Notifier notifier, RuntimeRecordPort records, Tracer tracer) {
        this.notifier = notifier;
        this.records = records;
        this.tracer = tracer;
    }

    @KafkaListener(topics = {"novaforge.task", "novaforge.sla"},
                   groupId = "novaforge-notification")
    public void onEvent(ConsumerRecord<String, String> message) {
        var header = message.headers().lastHeader(EventHeaders.TRACEPARENT);
        String traceparent = header == null ? null
                : new String(header.value(), StandardCharsets.UTF_8);
        TracePropagation.inConsumerSpan(tracer, traceparent,
                "novaforge.task consume", () -> consume(message.value()));
    }

    void consume(String payload) {
        Map<String, Object> event;
        try {
            event = MAPPER.readValue(payload, Map.class);
        } catch (Exception e) {
            LOG.error("invalid task/sla event ignored: {}", payload, e);
            return;   // unparseable — no redelivery can fix it
        }
        // Processing failures propagate: the spine is at-least-once, so a transient
        // error (SMTP down, a lock timeout — Notifier's inbox writes roll back with
        // the failed send) must redeliver rather than ack a dropped fan-out — the
        // consumer convention RecordEventConsumer pins. The inbox's
        // (tenant, user, event) dedupe collapses the replay; only envelope-shape
        // errors are terminal here.
        try {
            dispatch(event);
        } catch (IllegalArgumentException e) {
            LOG.error("malformed task/sla event ignored: {}", payload, e);
        }
    }

    private void dispatch(Map<String, Object> event) {
        String type = String.valueOf(event.get("event"));
        UUID tenantId = UUID.fromString(String.valueOf(event.get("tenantId")));
        String eventId = String.valueOf(event.get("eventId"));
        Map<String, Object> record = recordOf(tenantId, event);
        switch (type) {
            case "task.assigned" -> notifier.onEvent(eventId, tenantId,
                    Notifier.TASK_ASSIGNMENT, event, record,
                    "Task assigned: ${task.entityId} ${task.taskId}",
                    "A task awaits you — record ${task.recordId} on "
                            + "${task.entityId}.");
            case "sla.warn" -> notifier.onEvent(eventId, tenantId,
                    Notifier.SLA_WARNING, event, record,
                    "SLA warning: ${task.entityId} ${task.taskId}",
                    "The task on record ${task.recordId} is approaching its "
                            + "SLA target.");
            case "sla.breach" -> {
                // §6's onBreach.notify: an authored "notify: false" escalation rides
                // the spine for metrics and audit — it never fans out as an sla-warning
                if (Boolean.FALSE.equals(event.get("notify"))) {
                    LOG.debug("sla.breach {} authored notify: false — no fan-out", eventId);
                    return;
                }
                notifier.onEvent(eventId, tenantId,
                        Notifier.SLA_WARNING, event, record,
                        "SLA breached: ${task.entityId} ${task.taskId}",
                        "The task on record ${task.recordId} breached its SLA and "
                                + "has been escalated.");
            }
            default -> { /* terminal task events carry no notification category in v1 */ }
        }
    }

    /**
     * The {@code ${record.*}} binding set, fetched once per event (§8) — a task
     * without a record, a process-keyed entity, or an unreachable runtime all
     * degrade to an empty binding rather than blocking the fan-out.
     */
    private Map<String, Object> recordOf(UUID tenantId, Map<String, Object> event) {
        Object recordId = event.get("recordId");
        String entityKey = String.valueOf(event.get("entityId"));
        if (recordId == null || "null".equals(String.valueOf(recordId))
                || entityKey.isBlank() || "null".equals(entityKey)) {
            return Map.of();
        }
        try {
            return records.recordOf(tenantId, entityKey, UUID.fromString(
                    String.valueOf(recordId)));
        } catch (IllegalArgumentException e) {
            return Map.of();   // a non-uuid record reference never blocks delivery
        } catch (RuntimeException e) {
            // the port is best-effort by contract; a misbehaving binding must never
            // take the fan-out down with it — empty tokens, delivery proceeds
            LOG.warn("record fetch for template tokens failed ({}): {}", entityKey,
                    e.getMessage());
            return Map.of();
        }
    }
}
