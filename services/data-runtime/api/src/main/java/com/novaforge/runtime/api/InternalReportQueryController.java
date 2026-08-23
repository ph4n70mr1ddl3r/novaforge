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
 * The Reporting Service's scheduled leg (PHASE-5 §7): report runs under the per-app
 * system principal over an explicitly permissioned scope — {@code asRole} decides the
 * entity READ, field security, and the sharing-rule row filters, so a scheduled
 * report is bounded by its role, never system-principal-everything. Internal surface,
 * no gateway route: the trusted platform service client only. The interactive path
 * does not pass through here — actor-scoped runs ride the public query surface with
 * the requesting user's relayed token (§4).
 */
@RestController
@RequestMapping("/api/v1/hooks")
public class InternalReportQueryController {

    private final RecordEngine engine;

    public InternalReportQueryController(RecordEngine engine) {
        this.engine = engine;
    }

    public record ReportQueryRequest(String tenantId, String app, String entityApiName,
                                     String asRole, Map<String, Object> query) {

        private static final tools.jackson.databind.json.JsonMapper MAPPER =
                tools.jackson.databind.json.JsonMapper.builder().build();

        String queryJson() {
            return query == null ? "{}" : MAPPER.writeValueAsString(query);
        }
    }

    @PostMapping("/reports/query")
    public Map<String, Object> query(@RequestBody ReportQueryRequest request) {
        ServiceClientGate.require("report-query");
        if (request.tenantId() == null || request.entityApiName() == null
                || request.asRole() == null || request.asRole().isBlank()) {
            throw new IllegalArgumentException(
                    "tenantId, entityApiName, and asRole are required");
        }
        var context = new TenantContext.Context(request.tenantId(),
                UUID.nameUUIDFromBytes(("system:" + request.app()).getBytes()).toString());
        var result = new Object[] {null};
        TenantContext.with(context, () -> result[0] = engine.aggregateAsRole(
                UUID.fromString(request.tenantId()), request.entityApiName(),
                request.asRole(), request.queryJson()));
        return Map.of("result", result[0]);
    }
}
