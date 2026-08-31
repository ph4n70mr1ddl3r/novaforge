package com.novaforge.audit.api;

import com.novaforge.audit.store.AuditStore;
import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Audit reads (PHASE-3 §5): who/what/when per record and per entity, tenant-scoped by
 * the caller's claim; the trail is append-only — no write surface exists.
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditStore store;

    public AuditController(AuditStore store) {
        this.store = store;
    }

    @GetMapping("/records/{recordId}")
    public List<Map<String, Object>> forRecord(@PathVariable UUID recordId,
                                               @RequestParam(defaultValue = "200") int limit) {
        if (limit < 1 || limit > 200) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "limit must be 1..200");
        }
        return store.forRecord(tenant(), recordId, limit);
    }

    @GetMapping("/entities/{entityId}")
    public List<Map<String, Object>> forEntity(@PathVariable String entityId,
                                               @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 200) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "limit must be 1..200");
        }
        return store.forEntity(tenant(), entityId, limit);
    }

    private static UUID tenant() {
        return com.novaforge.common.context.TenantContext.current()
                .map(ctx -> UUID.fromString(ctx.tenantId()))
                .orElseThrow(() -> new PlatformException(PlatformErrorCode.TENANT_MISSING,
                        "no tenant context bound (missing tenant_id claim?)"));
    }
}
