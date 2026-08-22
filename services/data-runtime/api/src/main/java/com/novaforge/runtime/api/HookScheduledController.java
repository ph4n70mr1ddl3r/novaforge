package com.novaforge.runtime.api;

import com.novaforge.common.context.TenantContext;
import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
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

/**
 * The Scheduler's flow target (PHASE-4 §7): the compiled-graph engine in its
 * synthetic {@code scheduled} context — no record, empty bindings, the per-app
 * system principal. Internal surface, no gateway route: the trusted platform
 * service client only (the same gate the admin and resume surfaces use).
 */
@RestController
@RequestMapping("/api/v1/hooks")
public class HookScheduledController {

    private final RecordEngine engine;

    public HookScheduledController(RecordEngine engine) {
        this.engine = engine;
    }

    public record ScheduledRequest(String tenantId, String app, String entityApiName,
                                   String hook) {
    }

    @PostMapping("/scheduled")
    public Map<String, Object> run(@RequestBody ScheduledRequest request) {
        requireServiceClient();
        TenantContext.with(new TenantContext.Context(request.tenantId(),
                UUID.nameUUIDFromBytes(("system:" + request.app()).getBytes()).toString()),
                () -> engine.runScheduledHook(UUID.fromString(request.tenantId()),
                        request.entityApiName(), request.hook()));
        return Map.of("status", "fired");
    }

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
                "the scheduled-hook surface is service-client only");
    }
}
