package com.novaforge.integration.webhook;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.integration.clients.PublishedIntegrations;
import com.novaforge.integration.clients.RuntimeClient;
import com.novaforge.integration.secrets.SecretStore;
import com.novaforge.integration.store.DeliveryStore;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.WebhookDefinition;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Inbound webhook processing (PHASE-6 §6): the anonymous route's HMAC verification
 * (the same scheme that signs outbound — §5), the ±5-minute replay window with
 * per-signature nonces, idempotency on the provider event id (the body hash when
 * absent), and the mapping → Data Runtime write path as the per-app integration
 * principal — validations, state machines, and hooks all fire, because a webhook
 * is just another writer. Poison messages (unmappable payloads, terminally
 * rejected writes) DLQ with the payload preserved for builder replay.
 */
@Component
public class InboundProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(InboundProcessor.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final PublishedIntegrations definitions;
    private final SecretStore secrets;
    private final DeliveryStore deliveries;
    private final HmacScheme hmac;
    private final RuntimeClient runtime;

    public InboundProcessor(PublishedIntegrations definitions, SecretStore secrets,
                            DeliveryStore deliveries, HmacScheme hmac, RuntimeClient runtime) {
        this.definitions = definitions;
        this.secrets = secrets;
        this.deliveries = deliveries;
        this.hmac = hmac;
        this.runtime = runtime;
    }

    /** The resolved hook + its app for an inbound address. */
    public record Hook(AppDefinition app, WebhookDefinition webhook) {
    }

    /**
     * The anonymous route's full leg: resolve the address, then verify. An address
     * that binds no published hook — malformed tenant id, unknown tenant, unbound
     * entity or hook id — verifies against an empty active set, so it walks the
     * same timestamp-window and signature checks a known hook's wrong-secret
     * failure does and renders the exact same {@code SIGNATURE_INVALID}: the
     * anonymous surface never confirms or denies an address's existence (the
     * 2026-08-28 pen pass's PF-1 close).
     */
    public Map<String, Object> receive(String tenant, String entity, String hookId,
                                       byte[] body, String timestamp, String signature) {
        UUID tenantId = null;
        try {
            tenantId = UUID.fromString(tenant);
        } catch (IllegalArgumentException malformed) {
            // an unknown address is an unknown address — verified below like any other
        }
        Hook hook = null;
        if (tenantId != null) {
            try {
                hook = resolve(tenantId, entity, hookId);
            } catch (PlatformException unresolved) {
                // nothing binds this address — the empty active set answers below
            }
        }
        if (hook == null) {
            hmac.verify(timestamp, signature, body, List.of());
            throw new PlatformException(PlatformErrorCode.SIGNATURE_INVALID,
                    "webhook signature verification failed: "
                            + "signature does not match any active secret");
        }
        return apply(tenantId, hook, body, timestamp, signature);
    }

    /** Resolves {@code /webhooks/inbound/{tenant}/{entity}/{hookId}} against published apps. */
    public Hook resolve(UUID tenantId, String entity, String hookId) {
        for (AppDefinition app : definitions.allApps(tenantId)) {
            var found = app.inboundWebhook(entity, hookId);
            if (found.isPresent()) {
                return new Hook(app, found.get());
            }
        }
        throw new PlatformException(PlatformErrorCode.NOT_FOUND,
                "no inbound webhook " + hookId + " bound to " + entity + " in this tenant");
    }

    /** Resolves a webhook by id in either direction (the replay legs' lookup). */
    public Hook resolveById(UUID tenantId, String hookId) {
        for (AppDefinition app : definitions.allApps(tenantId)) {
            var found = app.integrations().webhook(hookId);
            if (found.isPresent()) {
                return new Hook(app, found.get());
            }
        }
        throw new PlatformException(PlatformErrorCode.NOT_FOUND,
                "no webhook " + hookId + " published in this tenant");
    }

    /**
     * The full inbound leg: verify → dedupe → map → write through the single write
     * path. Signature failures render {@code SIGNATURE_INVALID} (the caller's
     * problem+json); everything else surfaces as the write path's own outcome.
     */
    public Map<String, Object> apply(UUID tenantId, Hook hook, byte[] body,
                                     String timestamp, String signature) {
        List<String> active = secrets.active(tenantId, hook.webhook().secretRef());
        hmac.verify(timestamp, signature, body, active);
        if (!deliveries.claimReplayNonce(tenantId, hook.webhook().id(),
                SecretStore.sha256(signature))) {
            throw new PlatformException(PlatformErrorCode.SIGNATURE_INVALID,
                    "webhook signature replayed inside the window");
        }
        return applyMapped(tenantId, hook, body, signature);
    }

    /**
     * The DLQ replay leg (§6/§11): a poison message's signature was verified before
     * it parked — the trusted builder replays the preserved payload straight into
     * the dedupe/map/write leg, exactly-once by the same dedupe key.
     */
    public Map<String, Object> replay(UUID tenantId, Hook hook, byte[] body) {
        return applyMapped(tenantId, hook, body, null);
    }

    private Map<String, Object> applyMapped(UUID tenantId, Hook hook, byte[] body,
                                            String signature) {
        JsonNode payload = MAPPER.readTree(new String(body, StandardCharsets.UTF_8));
        String dedupeKey = idempotencyKey(hook.webhook().mapping(), payload,
                bodyHash(body));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("hook", hook.webhook().id());
        request.put("payload", MAPPER.convertValue(payload, Map.class));
        var settled = deliveries.settleOrOpen(tenantId, DeliveryStore.KIND_WEBHOOK_INBOUND,
                hook.webhook().id(), dedupeKey, MAPPER.writeValueAsString(request));
        if (settled.isPresent() && settled.get().delivered()) {
            return Map.of("status", "ok", "deduped", true,
                    "detail", settled.get().responseSummary());
        }
        UUID deliveryId = deliveries.find(tenantId, DeliveryStore.KIND_WEBHOOK_INBOUND,
                hook.webhook().id(), dedupeKey).map(DeliveryStore.Delivery::id).orElse(null);

        try {
            Map<String, Object> mapped = map(hook.webhook(), payload);
            Map<String, Object> outcome = write(tenantId, hook, mapped);
            if (deliveryId != null) {
                deliveries.record(deliveryId, "delivered", 200, null,
                        MAPPER.writeValueAsString(outcome), null);
            }
            deliveries.outbox(tenantId, "webhook.dispatched", Map.of(
                    "webhook", hook.webhook().id(), "direction", "inbound",
                    "deliveryId", String.valueOf(deliveryId), "status", 200,
                    "record", String.valueOf(outcome.get("id"))));
            return outcome;
        } catch (Exception e) {
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (deliveryId != null) {
                deliveries.record(deliveryId, "dlq", null, null, null, error);
            }
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("hook", hook.webhook().id());
            envelope.put("signature", signature);
            envelope.put("payload", MAPPER.convertValue(payload, Map.class));
            deliveries.park(tenantId, DeliveryStore.KIND_WEBHOOK_INBOUND,
                    hook.webhook().id(), envelope, signature, error);
            deliveries.outbox(tenantId, "webhook.dispatched", Map.of(
                    "webhook", hook.webhook().id(), "direction", "inbound",
                    "deliveryId", String.valueOf(deliveryId), "status", "dlq",
                    "error", error));
            LOG.error("inbound webhook {} moved to DLQ: {}", hook.webhook().id(), error);
            if (e instanceof PlatformException platform) {
                throw platform;   // the write path's own problem surfaces verbatim
            }
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "inbound webhook failed: " + error);
        }
    }

    /** Maps the provider payload to a record body (§6: {@code ${…}} field templates). */
    Map<String, Object> map(WebhookDefinition webhook, JsonNode payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : webhook.mapping().fields().entrySet()) {
            String template = String.valueOf(entry.getValue());
            body.put(entry.getKey(), templateValue(template, payload));
        }
        return body;
    }

    /** Create or upsert through the integration principal's write path (§6/§7). */
    private Map<String, Object> write(UUID tenantId, Hook hook, Map<String, Object> body) {
        WebhookDefinition.Mapping mapping = hook.webhook().mapping();
        String entity = hook.webhook().entity();
        String mode = mapping.mode() == null ? WebhookDefinition.Mapping.MODE_CREATE : mapping.mode();
        if (WebhookDefinition.Mapping.MODE_UPSERT.equals(mode) && !mapping.keyFields().isEmpty()) {
            // the query-DSL filter shape the runtime's list parser pins (§6): one
            // key is a single eq leaf, several conjoin under and — a flat leaf
            // built by looping the keys overwrote field/value and kept only the
            // LAST key, so a multi-key upsert resolved by that field alone and
            // could update a record its other keys exclude
            Map<String, Object> filter = RuntimeClient.keyLookupFilter(mapping.keyFields(), body);
            RuntimeClient.ListPage found = runtime.lookup(tenantId, entity,
                    Map.of("filter", filter, "page", Map.of("size", 1)));
            if (!found.rows().isEmpty()) {
                Map<String, Object> existing = found.rows().getFirst();
                RuntimeClient.Outcome outcome = runtime.write(tenantId, List.of(Map.of(
                        "op", "update", "entity", entity,
                        "id", String.valueOf(existing.get("id")),
                        "version", existing.get("version"),
                        "record", body))).getFirst();
                if (!outcome.ok()) {
                    throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                            "webhook upsert rejected by the write path: "
                                    + outcome.detail() + " (" + outcome.code() + ")");
                }
                return outcome.record();
            }
        }
        RuntimeClient.Outcome outcome = runtime.write(tenantId, List.of(Map.of(
                "op", "create", "entity", entity, "record", body))).getFirst();
        if (!outcome.ok()) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "webhook create rejected by the write path: "
                            + outcome.detail() + " (" + outcome.code() + ")");
        }
        return outcome.record();
    }

    // --- helpers ---

    private static String idempotencyKey(WebhookDefinition.Mapping mapping, JsonNode payload,
                                         String fallback) {
        if (mapping == null || mapping.idempotencyKey() == null) {
            return fallback;
        }
        Object value = templateValue(mapping.idempotencyKey(), payload);
        return value == null ? fallback : String.valueOf(value);
    }

    private static String bodyHash(byte[] body) {
        try {
            return Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL, "digest failed");
        }
    }

    /** {@code ${path.to.value}} over the provider payload — the mapping's engine (§3). */
    static Object templateValue(String template, JsonNode payload) {
        if (template == null) {
            return null;
        }
        if (template.startsWith("${") && template.endsWith("}")) {
            String pointer = "/" + template.substring(2, template.length() - 1).replace('.', '/');
            JsonNode node = payload.at(pointer);
            return node.isMissingNode() || node.isNull() ? null
                    : MAPPER.convertValue(node, Object.class);
        }
        return template;
    }
}
