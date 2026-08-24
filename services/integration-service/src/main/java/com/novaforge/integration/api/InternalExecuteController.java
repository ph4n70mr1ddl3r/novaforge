package com.novaforge.integration.api;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.integration.connector.ConnectorExecutor;
import com.novaforge.security.ServiceClientGate;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The internal execution surface (PHASE-6 §4): the Data Runtime's
 * {@code callConnector} primitive and the Script Engine's connector-sandbox
 * {@code $http} both land here — scripts may call REST only through the same
 * circuit-breaker/credential machinery, never raw sockets (the PHASE-3 §6
 * deferral activating per its terms). The §4 timeout policy (10 s, synchronous,
 * no suspension) is the executor's own; failure propagates to the caller so the
 * hook failure policy (before-aborts, after-retries) applies unchanged.
 * Service-client gated — never user traffic.
 */
@RestController
@RequestMapping("/api/v1/integrations/internal")
public class InternalExecuteController {

    private final ConnectorExecutor connectors;

    public InternalExecuteController(ConnectorExecutor connectors) {
        this.connectors = connectors;
    }

    /** One connector call: {tenantId, app, connector, operation, template, dedupeKey?}. */
    public record ExecuteRequest(String tenantId, String app, String connector,
                                 String operation, Map<String, Object> template,
                                 String dedupeKey) {
    }

    @PostMapping("/execute")
    public Map<String, Object> execute(@RequestBody ExecuteRequest request) {
        ServiceClientGate.require("connector-execute");
        if (request.tenantId() == null || request.app() == null || request.connector() == null
                || request.operation() == null) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "connector executions require tenantId, app, connector, and operation");
        }
        UUID tenantId;
        try {
            tenantId = UUID.fromString(request.tenantId());
        } catch (IllegalArgumentException e) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "tenantId must be a uuid: " + request.tenantId());
        }
        ConnectorExecutor.Execution execution = connectors.execute(tenantId, request.app(),
                request.connector(), request.operation(), request.template(), request.dedupeKey());
        return Map.of("status", execution.status(), "body", execution.body());
    }
}
