package com.novaforge.runtime.api;

import com.novaforge.common.context.TenantContext;
import com.novaforge.runtime.engine.RecordEngine;
import com.novaforge.security.ServiceClientGate;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The internal record read (PHASE-4 §9): the Workflow Service's event-start
 * evaluation fetches the record's current state here — spine events carry the
 * envelope only. A system-context read (the resume surface's pattern): raw stored
 * fields, never shaped or stripped for a user — filters are app logic evaluated
 * as the system principal, and this surface never mutates anything (ADR-004 #2).
 * Internal, service-client gated, no gateway route.
 */
@RestController
@RequestMapping("/api/v1/hooks")
public class InternalRecordController {

    private final RecordEngine engine;

    public InternalRecordController(RecordEngine engine) {
        this.engine = engine;
    }

    @GetMapping("/records/{recordId}")
    public Map<String, Object> record(@PathVariable UUID recordId,
                                      @RequestParam String tenantId,
                                      @RequestParam String app,
                                      @RequestParam String entity) {
        ServiceClientGate.require("record read");
        TenantContext.set(new TenantContext.Context(tenantId,
                UUID.nameUUIDFromBytes(("system:" + app).getBytes()).toString()));
        try {
            return engine.recordForSubscription(UUID.fromString(tenantId), entity, recordId);
        } finally {
            TenantContext.clear();
        }
    }

}
