package com.novaforge.runtime.events;

import com.novaforge.runtime.storage.retry.HookRetryStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The after-hook retry consumer on the spine (PHASE-3 §2 failure policy): the
 * {@code hook.retry} events failed after-hooks emit into the transactional outbox
 * arrive here on {@code novaforge.hook} and become durable retry rows. Idempotent by
 * construction — the spine's event id claims the row ({@code ON CONFLICT DO NOTHING}),
 * so at-least-once redelivery collapses.
 *
 * <p>Script-kind failures park at consume time rather than re-drive: scripts are
 * caller-context only (ADR-003 #2) and the spine has no user token to relay — a retry
 * would have to silently escalate to a service account, which the design forbids. The
 * parked row keeps the failure visible (never lost); the write itself already
 * succeeded.</p>
 */
@Component
public class HookRetryConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(HookRetryConsumer.class);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final HookRetryStore retries;
    private final io.micrometer.core.instrument.Counter deduped;
    private final io.micrometer.core.instrument.Counter scriptParked;

    public HookRetryConsumer(HookRetryStore retries, MeterRegistry meters) {
        this.retries = retries;
        this.deduped = meters.counter("novaforge.hook.retry.outcome", "result", "deduped");
        this.scriptParked = meters.counter("novaforge.hook.retry.outcome", "result", "script_parked");
    }

    @KafkaListener(topics = "novaforge.hook", groupId = "novaforge-hook-retry")
    public void onEvent(String payload) {
        try {
            Map<String, Object> event = MAPPER.readValue(payload, Map.class);
            if (!"hook.retry".equals(event.get("event"))) {
                return;   // unknown hook.* type — not ours
            }
            UUID eventId = UUID.fromString(String.valueOf(event.get("eventId")));
            UUID tenantId = UUID.fromString(String.valueOf(event.get("tenantId")));
            String entityId = String.valueOf(event.get("entityId"));
            UUID recordId = UUID.fromString(String.valueOf(event.get("recordId")));
            String trigger = String.valueOf(event.get("trigger"));
            String hook = String.valueOf(event.get("hook"));
            String kind = String.valueOf(event.get("kind"));
            int attempt = event.get("attempt") instanceof Number number ? number.intValue() : 1;
            if ("script".equals(kind)) {
                retries.parkAtConsume(eventId, tenantId, entityId, recordId, trigger, hook,
                        kind, "script hooks are caller-context only — not re-drivable from "
                                + "the spine (ADR-003 #2); re-trigger the write as the user "
                                + "if the effect is still needed");
                scriptParked.increment();
                return;
            }
            boolean claimed = retries.claim(eventId, tenantId, entityId, recordId, trigger,
                    hook, kind, attempt, Instant.now());
            if (!claimed) {
                deduped.increment();   // redelivery — already claimed by an earlier copy
            }
        } catch (Exception e) {
            // Malformed payloads must not poison the group; the outbox relay keeps the
            // original in event_outbox for inspection.
            LOG.error("invalid hook.retry event ignored: {}", payload, e);
        }
    }
}
