package com.novaforge.workflow.api;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.workflow.process.ProcessStarts;
import com.novaforge.security.ServiceClientGate;
import java.util.Map;
import java.util.UUID;
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
        ServiceClientGate.require("process-start");
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

}
