package com.novaforge.runtime.api;

import com.novaforge.common.context.TenantContext;
import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.runtime.engine.RecordEngine;
import com.novaforge.security.ServiceClientGate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Integration Service's write/read surfaces (PHASE-6 §6/§7): inbound-webhook
 * applications and import chunks write through the complete path — validations,
 * state machines, hooks — as the per-app integration principal (a distinct
 * principal from the engine's system principal, so audit provenance separates
 * integration-sourced writes); export datasets page under an explicitly
 * permissioned role (the scheduled-report scope, PHASE-5 §7), and webhook upsert
 * keys resolve through the same lowered queries as every list. Internal surfaces,
 * no gateway route: the trusted platform service client only.
 */
@RestController
@RequestMapping("/api/v1/hooks/integration")
public class IntegrationAccessController {

    private static final tools.jackson.databind.json.JsonMapper MAPPER =
            tools.jackson.databind.json.JsonMapper.builder().build();

    /**
     * The §6/§7 write surface's door read rides decimal-exact (PLAN.md §1 /
     * ARCHITECTURE.md §4 money rule): this is the door every webhook application
     * and import chunk crosses, and a default Map read types every JSON float as
     * Double — an amount a provider sent EXACT re-corrupted here, after the wire
     * already carried it faithfully (9999999999999.9999 → Double 1.0E13 →
     * FieldCoercer's BigDecimal(Double.toString) = 10000000000000, silently wrong
     * money in the record, its shadow inside the field's precision/scale). The
     * same stance ReportRunner's CACHE_READ and the connector chain's exact reads
     * pin: the platform owns this parse.
     */
    private static final tools.jackson.databind.json.JsonMapper EXACT_READ =
            tools.jackson.databind.json.JsonMapper.builder()
                    .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .build();

    private final RecordEngine engine;

    public IntegrationAccessController(RecordEngine engine) {
        this.engine = engine;
    }

    /** One write item: create or versioned update, per-item outcomes (§6/§7). */
    public record WriteItem(String op, String entity, String id, Integer version,
                            Map<String, Object> record) {
    }

    public record WriteRequest(String tenantId, List<WriteItem> items) {
    }

    /**
     * Batch writes as the integration principal: the PHASE-1 §5 per-item-outcome
     * contract (capped at the engine's batch bound) — import chunks and webhook
     * applications ride it; every item traverses the full write path. The raw body
     * decodes decimal-exact (the door read above) — a malformed body still rejects
     * VALIDATION_FAILED at the door, now shaped here.
     */
    @PostMapping("/write")
    public Map<String, Object> write(@RequestBody String body) {
        ServiceClientGate.require("integration-write");
        WriteRequest request;
        try {
            request = EXACT_READ.readValue(body, WriteRequest.class);
        } catch (RuntimeException malformed) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "integration write body is not a valid write request: "
                            + malformed.getMessage());
        }
        if (request.tenantId() == null || request.items() == null || request.items().isEmpty()) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "integration writes require tenantId and at least one item");
        }
        UUID tenantId = UUID.fromString(request.tenantId());
        if (request.items().size() > 500) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "integration write chunks are capped at 500 items (the batch bound, §5)");
        }
        List<Map<String, Object>> outcomes = new ArrayList<>();
        for (WriteItem item : request.items()) {
            try {
                Map<String, Object> result = switch (item.op() == null ? "create" : item.op()) {
                    case "create" -> TenantContext.call(integrationContext(tenantId), () ->
                            engine.integrationCreate(tenantId, item.entity(), item.record()));
                    case "update" -> {
                        if (item.id() == null || item.version() == null) {
                            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                                    "update items require id and version (optimistic locking)");
                        }
                        UUID recordId = parseRecordId(item.id());
                        yield TenantContext.call(integrationContext(tenantId), () ->
                                engine.integrationUpdate(tenantId, item.entity(), recordId,
                                        item.version(), item.record()));
                    }
                    default -> throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                            "unknown integration write op: " + item.op());
                };
                outcomes.add(Map.of("status", "ok", "record", result));
            } catch (PlatformException e) {
                outcomes.add(Map.of("status", "error", "code", e.errorCode().code(),
                        "detail", String.valueOf(e.getMessage()),
                        // §6/§7: the write path's field-scoped verdicts ride per item —
                        // a webhook/import sees exactly which rule rejected
                        "errors", e.detail().map(detail -> (Object) detail.errors())
                                .orElse(List.of())));
            } catch (RuntimeException e) {
                // A SQL-level abort (unique race, deadlock) rolled back only this item's
                // transaction — it reports as that item's outcome, never a request-level
                // 500 after earlier items committed and their verdicts vanished.
                outcomes.add(Map.of("status", "error", "code", PlatformErrorCode.INTERNAL.code(),
                        "detail", "item failed: " + e.getClass().getSimpleName()
                                + (e.getMessage() == null ? "" : ": " + e.getMessage()),
                        "errors", List.of()));
            }
        }
        return Map.of("outcomes", outcomes);
    }

    /** A malformed record id is the item's verdict, not an unhandled parse error. */
    private static UUID parseRecordId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "update item id must be a uuid: " + id);
        }
    }

    /** One paged read: role-scoped exports (§7) or integration-scoped key lookups (§6). */
    public record ListRequest(String tenantId, String entity, String asRole,
                              Map<String, Object> query) {

        String queryJson() {
            return query == null ? "{}" : MAPPER.writeValueAsString(query);
        }
    }

    @PostMapping("/list")
    public Map<String, Object> list(@RequestBody String body) {
        ServiceClientGate.require("integration-list");
        // the same exact door read as /write: the upsert-key lookup's filter values
        // ride it, and a float key re-typed through the Double would lower to its
        // shadow — the lookup would miss the exact-stored row it is owed
        ListRequest request;
        try {
            request = EXACT_READ.readValue(body, ListRequest.class);
        } catch (RuntimeException malformed) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "integration list body is not a valid read request: "
                            + malformed.getMessage());
        }
        if (request.tenantId() == null || request.entity() == null) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "integration reads require tenantId and entity");
        }
        UUID tenantId = UUID.fromString(request.tenantId());
        Object result = TenantContext.call(integrationContext(tenantId), () ->
                request.asRole() == null || request.asRole().isBlank()
                        ? engine.listAsIntegration(tenantId, request.entity(), request.queryJson())
                        : engine.listAsRole(tenantId, request.entity(), request.asRole(),
                                request.queryJson()));
        return Map.of("result", result);
    }

    private static TenantContext.Context integrationContext(UUID tenantId) {
        return new TenantContext.Context(tenantId.toString(),
                UUID.nameUUIDFromBytes("integration:context".getBytes()).toString());
    }
}
