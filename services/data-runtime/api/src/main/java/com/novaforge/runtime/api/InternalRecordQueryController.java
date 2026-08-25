package com.novaforge.runtime.api;

import com.novaforge.common.context.TenantContext;
import com.novaforge.runtime.engine.RecordEngine;
import com.novaforge.security.ServiceClientGate;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The scheduled script's read leg (PHASE-4 §7): the Script Engine's
 * {@code $data.query} in the synthetic {@code scheduled} context executes here —
 * a recordless firing has no user token to relay, so the per-app system principal
 * bound in the body runs the standard list DSL through the storage path (the
 * {@code recordForSubscription} system-context shape: raw rows, never shaped or
 * user-stripped, never a mutation). Internal surface, no gateway route: the
 * trusted platform service client only — the same gate the other hook surfaces use.
 */
@RestController
@RequestMapping("/api/v1/hooks")
public class InternalRecordQueryController {

    private final RecordEngine engine;

    public InternalRecordQueryController(RecordEngine engine) {
        this.engine = engine;
    }

    public record RecordQueryRequest(String tenantId, String app, String entityApiName,
                                     String query) {
    }

    @PostMapping("/records/query")
    public Map<String, Object> query(@RequestBody RecordQueryRequest request) {
        ServiceClientGate.require("record-query");
        if (request.tenantId() == null || request.entityApiName() == null
                || request.entityApiName().isBlank()) {
            throw new IllegalArgumentException("tenantId and entityApiName are required");
        }
        var context = new TenantContext.Context(request.tenantId(),
                UUID.nameUUIDFromBytes(("system:" + request.app()).getBytes()).toString());
        var result = new Object[] {null};
        TenantContext.with(context, () -> result[0] = engine.listAsPrincipal(
                UUID.fromString(request.tenantId()), request.entityApiName(),
                request.query() == null || request.query().isBlank() ? "{}" : request.query()));
        return Map.of("result", result[0]);
    }
}
