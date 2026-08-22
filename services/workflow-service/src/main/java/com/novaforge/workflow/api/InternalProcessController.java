package com.novaforge.workflow.api;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.workflow.process.ProcessStarts;
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

/**
 * The internal process-start surface (PHASE-4 §9/§7): the Scheduler's
 * {@code processStart} target calls here with the platform service client's token
 * — service-client gated like the approval surface, never user traffic.
 */
@RestController
@RequestMapping("/api/v1/workflow/internal")
public class InternalProcessController {

    private final ProcessStarts starts;

    public InternalProcessController(ProcessStarts starts) {
        this.starts = starts;
    }

    public record StartRequest(String tenantId, String app, String process,
                               String recordId, Map<String, Object> variables) {
    }

    @PostMapping("/processes/start")
    public Map<String, Object> start(@RequestBody StartRequest request) {
        requireServiceClient();
        if (request.process() == null || request.process().isBlank()
                || request.app() == null || request.app().isBlank()) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "process start requires app and process");
        }
        UUID recordId = request.recordId() == null || request.recordId().isBlank()
                ? null : UUID.fromString(request.recordId());
        String instanceId = starts.start(UUID.fromString(request.tenantId()),
                request.app(), request.process(), recordId, request.variables());
        return Map.of("instanceId", instanceId, "started", true);
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
                "the process-start surface is service-client only");
    }
}
