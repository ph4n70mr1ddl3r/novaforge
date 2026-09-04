package com.novaforge.integration.api;

import com.novaforge.common.context.TenantContext;
import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.integration.connector.ConnectorExecutor;
import com.novaforge.integration.secrets.SecretStore;
import com.novaforge.integration.store.DeliveryStore;
import com.novaforge.integration.webhook.InboundProcessor;
import com.novaforge.integration.webhook.OutboundDispatcher;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * The builder's operational surface (PHASE-6 §5–§7): secret provisioning and
 * rotation (references only in metadata — the material lands in the encrypted
 * store, §9; two secrets stay active through a rotation window), the delivery log
 * beside the definition editors, and DLQ replay with the payload preserved —
 * an inbound hook re-applies through the write path (its signature was verified
 * before it parked), an outbound hook re-dispatches, a connector call re-executes.
 * Builder+ at the route (§9); every read is tenant-scoped by the bound context.
 */
@RestController
@RequestMapping("/api/v1/integrations")
public class IntegrationController {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The parked payload renders to the builder as the provider sent it — a
     *  default readTree would show its float64 shadow in the DLQ surface. */
    private static final JsonMapper EXACT_READ = JsonMapper.builder()
            .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private final SecretStore secrets;
    private final DeliveryStore deliveries;
    private final InboundProcessor inbound;
    private final OutboundDispatcher outbound;
    private final ConnectorExecutor connectors;

    public IntegrationController(SecretStore secrets, DeliveryStore deliveries,
                                 InboundProcessor inbound, OutboundDispatcher outbound,
                                 ConnectorExecutor connectors) {
        this.secrets = secrets;
        this.deliveries = deliveries;
        this.inbound = inbound;
        this.outbound = outbound;
        this.connectors = connectors;
    }

    // --- secrets (§9) ---

    public record SecretPut(String material, Boolean retireEarlier) {
    }

    /** Provisions (first write) or rotates (subsequent) a secret version for a ref. */
    @PostMapping("/secrets/{ref}")
    public Map<String, Object> putSecret(@PathVariable String ref,
                                         @RequestBody SecretPut request) {
        UUID tenant = tenant();
        secrets.put(tenant, ref, SecretStore.PURPOSE_WEBHOOK, request.material());
        if (Boolean.TRUE.equals(request.retireEarlier())) {
            secrets.retireEarlierVersions(tenant, ref);
        }
        return Map.of("ref", ref, "status", "provisioned",
                "rotation", Boolean.TRUE.equals(request.retireEarlier())
                        ? "retired" : "dual-active");
    }

    // --- delivery log + DLQ (§5/§6) ---

    @GetMapping("/deliveries")
    public List<Map<String, Object>> deliveries(@RequestParam(required = false) String kind,
                                                @RequestParam(defaultValue = "100") int limit) {
        return deliveries.log(tenant(), kind, Math.min(Math.max(limit, 1), 500)).stream()
                .map(delivery -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", delivery.id());
                    row.put("kind", delivery.kind());
                    row.put("target", delivery.target());
                    row.put("dedupeKey", delivery.dedupeKey());
                    row.put("status", delivery.status());
                    row.put("attempts", delivery.attempts());
                    row.put("lastStatus", delivery.lastStatus());
                    row.put("latencyMs", delivery.latencyMs());
                    row.put("error", delivery.error());
                    row.put("createdAt", delivery.createdAt().toString());
                    return row;
                }).toList();
    }

    @GetMapping("/dlq")
    public List<Map<String, Object>> dlq(@RequestParam(required = false) String kind) {
        return deliveries.dlq(tenant(), kind, true).stream()
                .map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", entry.id());
                    row.put("kind", entry.kind());
                    row.put("target", entry.target());
                    row.put("payload", EXACT_READ.readTree(entry.payload()));
                    row.put("error", entry.error());
                    row.put("attempts", entry.attempts());
                    row.put("createdAt", entry.createdAt().toString());
                    return row;
                }).toList();
    }

    /** Replay: the payload's preserved context re-runs through its own leg (§11 item 2). */
    public record ReplayRequest(String app) {
    }

    @PostMapping("/dlq/{id}/replay")
    public Map<String, Object> replay(@PathVariable UUID id,
                                      @RequestBody(required = false) ReplayRequest request) {
        UUID tenant = tenant();
        var entry = deliveries.dlqEntry(tenant, id).orElseThrow(() ->
                new PlatformException(PlatformErrorCode.NOT_FOUND, "dlq entry " + id));
        if (DeliveryStore.KIND_WEBHOOK_INBOUND.equals(entry.kind())) {
            InboundProcessor.Hook hook = inbound.resolveById(tenant, entry.target());
            // the parked envelope decodes decimal-exact inside the processor —
            // the controller-side default-mapper decode used to re-type every
            // float through the binary float before the replay leg saw it
            Map<String, Object> result = inbound.replayParked(tenant, hook, entry.payload());
            deliveries.markReplayed(tenant, id);
            return Map.of("status", "replayed", "outcome", result);
        }
        Map<String, Object> payload = MAPPER.readValue(entry.payload(), Map.class);
        if (DeliveryStore.KIND_WEBHOOK_OUTBOUND.equals(entry.kind())) {
            InboundProcessor.Hook hook = inbound.resolveById(tenant, entry.target());
            outbound.deliver(tenant, hook.webhook(),
                    String.valueOf(payload.get("eventId")), String.valueOf(payload.get("body")));
            deliveries.markReplayed(tenant, id);
            return Map.of("status", "replayed", "at", Instant.now().toString());
        }
        if (DeliveryStore.KIND_CONNECTOR.equals(entry.kind())) {
            if (request == null || request.app() == null) {
                throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                        "connector DLQ replay requires the app's apiName (the payload's context)");
            }
            ConnectorExecutor.Execution execution = connectors.execute(tenant, request.app(),
                    String.valueOf(payload.get("connector")),
                    String.valueOf(payload.get("operation")),
                    (Map<String, Object>) payload.getOrDefault("template", Map.of()),
                    "replay:" + id);
            deliveries.markReplayed(tenant, id);
            return Map.of("status", "replayed", "execution", Map.of(
                    "status", execution.status()));
        }
        throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                "dlq kind has no replay leg: " + entry.kind());
    }

    private static UUID tenant() {
        return UUID.fromString(TenantContext.require().tenantId());
    }
}
