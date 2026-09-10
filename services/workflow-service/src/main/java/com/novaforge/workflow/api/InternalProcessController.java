package com.novaforge.workflow.api;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.workflow.process.ProcessDeployer;
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
 * — service-client gated like the approval surface (the gateway's
 * {@code Path=/api/v1/workflow/**} route reaches this internal prefix too; the
 * gate refusing every user token with 403 is what keeps user traffic off it, the
 * leg {@code BpmnProcessTests} pins) — and the deployment catch-up leg (the G-17
 * harvest): a synchronous deployment sync the harness drives right after
 * publishing a candidate, so a suite's triggering write can never beat its own
 * event-started workflows into the registry.
 */
@RestController
@RequestMapping("/api/v1/workflow/internal")
public class InternalProcessController {

    private final ProcessStarts starts;
    private final ProcessDeployer deployer;

    public InternalProcessController(ProcessStarts starts, ProcessDeployer deployer) {
        this.starts = starts;
        this.deployer = deployer;
    }

    public record StartRequest(String tenantId, String app, String process,
                               String recordId, Map<String, Object> variables) {
    }

    @PostMapping("/processes/start")
    public Map<String, Object> start(@RequestBody StartRequest request) {
        ServiceClientGate.require("process-start");
        // the same edge validation the sibling sla/scan surface pins (and the
        // fiftieth pass's hole): an absent tenantId used to NPE inside
        // UUID.fromString — a 500 with a logged stack trace where the surface's
        // own contract answers 400 VALIDATION_FAILED
        if (request.tenantId() == null || request.tenantId().isBlank()) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "process start requires tenantId");
        }
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

    /**
     * The deployment catch-up (the G-17 harvest, found live authoring the
     * close-checklist suite leg, 2026-09-09): the deployer syncs on a schedule
     * (30 s default), so a freshly published app's event-started workflows are
     * inert until the next pass — and a spine event inside that window skips
     * quietly, never to retry ({@code ProcessStarts} evaluates deployed
     * subscriptions only; the filter reads the record's state at consume time,
     * so even the retry-by-re-entry shape cannot recover a missed start).
     * Any publish-then-write sequence — a suite run, the real promotion flow,
     * an integrator's onboarding — closes the race by driving this one
     * synchronous pass before its first write. Idempotent by content hash.
     */
    @PostMapping("/processes/sync")
    public Map<String, Object> sync() {
        ServiceClientGate.require("process-sync");
        deployer.syncOnce();
        return Map.of("synced", true);
    }

}
