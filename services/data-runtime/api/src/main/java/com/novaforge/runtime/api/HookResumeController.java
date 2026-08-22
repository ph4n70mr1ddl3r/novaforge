package com.novaforge.runtime.api;

import com.novaforge.common.context.TenantContext;
import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.FlowStep;
import com.novaforge.runtime.engine.RecordEngine;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * The suspension leg's re-entry (PHASE-4 §4): the Workflow Service calls here when an
 * approval resolves — the compiled-graph engine resumes after the requestApproval
 * step (or runs the step's own onReject subgraph) as the per-app system principal
 * against the record's current state. Internal surface, no gateway route: the
 * trusted platform service client only (the same gate the admin API uses).
 */
@RestController
@RequestMapping("/api/v1/hooks")
public class HookResumeController {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final RecordEngine engine;

    public HookResumeController(RecordEngine engine) {
        this.engine = engine;
    }

    public record ResumeRequest(String tenantId, String app, String entityApiName,
                                String recordId, String hook, String afterStep,
                                String onReject, boolean approved) {
    }

    @PostMapping("/resume")
    public Map<String, Object> resume(@RequestBody ResumeRequest request) {
        requireServiceClient();
        UUID tenantId = UUID.fromString(request.tenantId());
        UUID recordId = UUID.fromString(request.recordId());
        FlowStep onReject = request.onReject() == null || request.onReject().isBlank()
                ? null : MAPPER.readValue(request.onReject(), FlowStep.class);
        TenantContext.with(new TenantContext.Context(request.tenantId(),
                UUID.nameUUIDFromBytes(("system:" + request.app()).getBytes()).toString()),
                () -> engine.resumeApproval(tenantId, request.entityApiName(), recordId,
                        request.hook(), request.afterStep(), onReject, request.approved()));
        return Map.of("status", "resumed");
    }

    /** Trusted-service gate: the platform service client, same rule as the admin API. */
    private static void requireServiceClient() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuth
                && jwtAuth.getToken() instanceof Jwt jwt) {
            String azp = jwt.getClaimAsString("azp");
            String clientId = jwt.getClaimAsString("client_id");
            if ("novaforge-runtime".equals(azp) || "novaforge-runtime".equals(clientId)) {
                return;
            }
        }
        throw new PlatformException(PlatformErrorCode.FORBIDDEN,
                "the resume surface is service-client only");
    }
}
