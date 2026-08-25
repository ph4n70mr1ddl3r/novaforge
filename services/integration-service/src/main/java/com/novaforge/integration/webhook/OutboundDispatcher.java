package com.novaforge.integration.webhook;

import com.novaforge.integration.clients.PublishedIntegrations;
import com.novaforge.integration.secrets.SecretStore;
import com.novaforge.integration.store.DeliveryStore;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.WebhookDefinition;
import com.novaforge.expression.Expression;
import com.novaforge.security.EventHeaders;
import com.novaforge.security.TracePropagation;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Outbound webhook dispatch (PHASE-6 §5): a pure spine consumer — every platform
 * event (record.* and app events alike) evaluates each enabled outbound
 * webhook's filter expression over the spine envelope ({@code event}, {@code
 * entityId}, {@code recordId}, {@code actorId} + the payload's own bindings);
 * matches deliver with the pinned HMAC scheme, bounded retries with exponential
 * backoff, and a terminal DLQ; every attempt lands in the delivery log
 * (status, latency, response code) and {@code webhook.dispatched} rides the spine
 * for audit. Dispatches dedupe on the event id, so at-least-once redelivery
 * collapses instead of double-posting.
 */
@Component
public class OutboundDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(OutboundDispatcher.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final PublishedIntegrations definitions;
    private final SecretStore secrets;
    private final DeliveryStore deliveries;
    private final HmacScheme hmac;
    private final io.micrometer.tracing.Tracer tracer;
    private final int attempts;
    private final long backoffInitial;
    private final long backoffMax;

    public OutboundDispatcher(PublishedIntegrations definitions, SecretStore secrets,
                              DeliveryStore deliveries, HmacScheme hmac,
                              io.micrometer.tracing.Tracer tracer,
                              @Value("${novaforge.webhook.attempts:5}") int attempts,
                              @Value("${novaforge.webhook.backoff-initial-ms:200}") long backoffInitial,
                              @Value("${novaforge.webhook.backoff-max-ms:2000}") long backoffMax) {
        this.definitions = definitions;
        this.secrets = secrets;
        this.deliveries = deliveries;
        this.hmac = hmac;
        this.tracer = tracer;
        this.attempts = attempts;
        this.backoffInitial = backoffInitial;
        this.backoffMax = backoffMax;
    }

    /** The spine: every family topic (record.*, app events, platform events alike). */
    @KafkaListener(topicPattern = "novaforge\\..*", groupId = "novaforge-integration-dispatch")
    public void onEvent(ConsumerRecord<String, String> message) {
        var header = message.headers().lastHeader(EventHeaders.TRACEPARENT);
        String traceparent = header == null ? null
                : new String(header.value(), StandardCharsets.UTF_8);
        TracePropagation.inConsumerSpan(tracer, traceparent,
                "novaforge dispatch consume", () -> consume(message.value()));
    }

    void consume(String payload) {
        Map<String, Object> event;
        try {
            event = MAPPER.readValue(payload, Map.class);
        } catch (Exception e) {
            LOG.error("unparseable spine event ignored by dispatch: {}", payload, e);
            return;
        }
        try {
            dispatch(event, payload);
        } catch (IllegalArgumentException e) {
            LOG.error("malformed spine event ignored by dispatch: {}", payload, e);
        }
    }

    private void dispatch(Map<String, Object> event, String raw) {
        UUID tenantId = UUID.fromString(String.valueOf(event.get("tenantId")));
        String eventId = String.valueOf(event.get("eventId"));
        Map<String, Object> bindings = new LinkedHashMap<>(event);
        for (AppDefinition app : definitions.allApps(tenantId)) {
            for (WebhookDefinition webhook : app.integrations().enabledOutbound()) {
                if (!matches(webhook, bindings)) {
                    continue;
                }
                deliver(tenantId, webhook, eventId, raw);
            }
        }
    }

    /** The filter expression over the spine envelope + payload bindings (§5). */
    private boolean matches(WebhookDefinition webhook, Map<String, Object> bindings) {
        if (webhook.events() == null || webhook.events().isBlank()) {
            return true;   // a filterless subscription matches every event
        }
        try {
            Object outcome = Expression.parse(webhook.events())
                    .evaluate(Expression.Bindings.of(bindings), java.time.Clock.systemUTC());
            return Boolean.TRUE.equals(outcome);
        } catch (RuntimeException e) {
            LOG.warn("webhook {} filter failed to evaluate: {}", webhook.id(), e.getMessage());
            return false;
        }
    }

    /** One signed delivery: HMAC headers, bounded retries, DLQ on exhaustion. */
    public void deliver(UUID tenantId, WebhookDefinition webhook, String eventId, String raw) {
        String target = webhook.id();
        var settled = deliveries.settleOrOpen(tenantId, DeliveryStore.KIND_WEBHOOK_OUTBOUND,
                target, eventId, raw);
        if (settled.isPresent() && settled.get().delivered()) {
            return;   // the recorded outcome stands — a redelivered event never re-posts
        }
        UUID deliveryId = deliveries.find(tenantId, DeliveryStore.KIND_WEBHOOK_OUTBOUND,
                target, eventId).map(DeliveryStore.Delivery::id).orElse(null);
        String secret = secrets.active(tenantId, webhook.secretRef()).isEmpty() ? null
                : secrets.newest(tenantId, webhook.secretRef());
        if (secret == null) {
            String error = "no active secret for " + webhook.secretRef()
                    + " — provision it before dispatching";
            fail(tenantId, webhook, eventId, raw, deliveryId, error);
            throw new IllegalArgumentException(error);
        }
        HmacScheme.Signed signed = hmac.sign(secret, raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Exception last = null;
        Integer lastStatus = null;
        long start = System.nanoTime();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                RestClient.create().method(HttpMethod.POST)
                        .uri(webhook.url())
                        .header(HmacScheme.TIMESTAMP_HEADER, signed.timestamp())
                        .header(HmacScheme.SIGNATURE_HEADER, signed.signature())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(raw)
                        .retrieve()
                        .toBodilessEntity();
                long latency = Duration.ofNanos(System.nanoTime() - start).toMillis();
                if (deliveryId != null) {
                    deliveries.record(deliveryId, "delivered", 200, latency,
                            webhook.url(), null);
                }
                deliveries.outbox(tenantId, "webhook.dispatched", Map.of(
                        "webhook", webhook.id(), "eventId", eventId, "status", 200,
                        "deliveryId", String.valueOf(deliveryId), "latencyMs", latency));
                return;
            } catch (Exception e) {
                last = e;
                if (e instanceof org.springframework.web.client.RestClientResponseException status) {
                    lastStatus = status.getStatusCode().value();
                }
                LOG.warn("webhook {} dispatch attempt {}/{} failed: {}", webhook.id(), attempt,
                        attempts, e.getMessage());
                if (attempt < attempts) {
                    try {
                        Thread.sleep(Math.min(backoffInitial * (1L << (attempt - 1)), backoffMax));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        String error = last == null ? "dispatch failed" : String.valueOf(last.getMessage());
        fail(tenantId, webhook, eventId, raw, deliveryId, error + (lastStatus == null
                ? "" : " (HTTP " + lastStatus + ")"));
    }

    private void fail(UUID tenantId, WebhookDefinition webhook, String eventId, String raw,
                      UUID deliveryId, String error) {
        if (deliveryId != null) {
            deliveries.record(deliveryId, "dlq", null, null, webhook.url(), error);
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("url", webhook.url());
        envelope.put("body", raw);
        deliveries.park(tenantId, DeliveryStore.KIND_WEBHOOK_OUTBOUND, webhook.id(),
                envelope, null, error);
        deliveries.outbox(tenantId, "webhook.dispatched", Map.of(
                "webhook", webhook.id(), "eventId", eventId, "status", "dlq",
                "deliveryId", String.valueOf(deliveryId), "error", error));
        LOG.error("webhook {} moved to DLQ: {}", webhook.id(), error);
    }
}
