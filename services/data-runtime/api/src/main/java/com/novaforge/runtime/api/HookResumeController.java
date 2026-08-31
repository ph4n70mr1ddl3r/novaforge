package com.novaforge.runtime.api;

import com.novaforge.common.context.TenantContext;
import com.novaforge.metadata.FlowStep;
import com.novaforge.runtime.engine.RecordEngine;
import com.novaforge.security.ServiceClientGate;
import java.util.Map;
import java.util.UUID;
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
                                String onReject, boolean approved, String instanceId) {
    }

    @PostMapping("/resume")
    public Map<String, Object> resume(@RequestBody ResumeRequest request) {
        ServiceClientGate.require("resume");
        UUID tenantId = UUID.fromString(request.tenantId());
        UUID recordId = UUID.fromString(request.recordId());
        FlowStep onReject = request.onReject() == null || request.onReject().isBlank()
                ? null : MAPPER.readValue(request.onReject(), FlowStep.class);
        // The instanceId-keyed claim: the workflow side's remote-succeeds-local-
        // commit-fails retry re-entered the engine and re-ran the approval subgraph.
        // The claim rides the resume's own transaction — the first execution inserts
        // it, a retried delivery of the same key observes it and skips (the engine
        // already ran; the workflow side simply re-commits its side).
        UUID instanceId = request.instanceId() == null || request.instanceId().isBlank()
                ? null : UUID.fromString(request.instanceId());
        if (instanceId != null && !engine.claimResume(instanceId, tenantId, recordId,
                request.approved())) {
            return Map.of("status", "already-resumed");
        }
        TenantContext.with(new TenantContext.Context(request.tenantId(),
                UUID.nameUUIDFromBytes(("system:" + request.app()).getBytes()).toString()),
                () -> engine.resumeApproval(tenantId, request.entityApiName(), recordId,
                        request.hook(), request.afterStep(), onReject, request.approved()));
        return Map.of("status", "resumed");
    }

}
