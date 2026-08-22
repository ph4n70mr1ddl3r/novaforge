package com.novaforge.workflow.events;

import com.novaforge.workflow.task.TaskService;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code novaforge.record} (PHASE-4 §1/T1): record deletion cancels the
 * record's open tasks (§5) — the Workflow Service's one record-side reaction. It
 * never mutates records; state changes arrive as events and approvals resolve through
 * the runtime's write path (§3's single-write-path pin).
 */
@Component
public class RecordEventConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(RecordEventConsumer.class);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final TaskService tasks;

    public RecordEventConsumer(TaskService tasks) {
        this.tasks = tasks;
    }

    @KafkaListener(topics = "novaforge.record", groupId = "novaforge-workflow")
    public void onEvent(String payload) {
        try {
            Map<String, Object> event = MAPPER.readValue(payload, Map.class);
            if (!"record.deleted".equals(event.get("event"))) {
                return;
            }
            tasks.cancelForRecord(UUID.fromString(String.valueOf(event.get("tenantId"))),
                    UUID.fromString(String.valueOf(event.get("recordId"))));
        } catch (Exception e) {
            LOG.error("invalid record event ignored: {}", payload, e);
        }
    }
}
