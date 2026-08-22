package com.novaforge.notification.events;

import com.novaforge.notification.notify.Notifier;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes the spine (PHASE-4 §8): {@code task.assigned} fans out as
 * {@code task-assignment}, {@code sla.warn}/{@code sla.breach} as
 * {@code sla-warning}. Built-in platform templates per category (no authoring
 * surface in v1); the event payload is the {@code ${task.*}} binding set.
 */
@Component
public class TaskEventConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(TaskEventConsumer.class);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final Notifier notifier;

    public TaskEventConsumer(Notifier notifier) {
        this.notifier = notifier;
    }

    @KafkaListener(topics = {"novaforge.task", "novaforge.sla"},
                   groupId = "novaforge-notification")
    public void onEvent(String payload) {
        try {
            Map<String, Object> event = MAPPER.readValue(payload, Map.class);
            String type = String.valueOf(event.get("event"));
            UUID tenantId = UUID.fromString(String.valueOf(event.get("tenantId")));
            String eventId = String.valueOf(event.get("eventId"));
            switch (type) {
                case "task.assigned" -> notifier.onEvent(eventId, tenantId,
                        Notifier.TASK_ASSIGNMENT, event,
                        "Task assigned: ${task.entityId} ${task.taskId}",
                        "A task awaits you — record ${task.recordId} on "
                                + "${task.entityId}.");
                case "sla.warn" -> notifier.onEvent(eventId, tenantId,
                        Notifier.SLA_WARNING, event,
                        "SLA warning: ${task.entityId} ${task.taskId}",
                        "The task on record ${task.recordId} is approaching its "
                                + "SLA target.");
                case "sla.breach" -> notifier.onEvent(eventId, tenantId,
                        Notifier.SLA_WARNING, event,
                        "SLA breached: ${task.entityId} ${task.taskId}",
                        "The task on record ${task.recordId} breached its SLA and "
                                + "has been escalated.");
                default -> { /* terminal task events carry no notification category in v1 */ }
            }
        } catch (Exception e) {
            LOG.error("invalid task/sla event ignored: {}", payload, e);
        }
    }
}
