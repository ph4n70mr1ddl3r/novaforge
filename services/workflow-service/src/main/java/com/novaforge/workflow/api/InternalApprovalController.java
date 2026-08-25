package com.novaforge.workflow.api;

import com.novaforge.workflow.task.SuspensionService;
import com.novaforge.security.ServiceClientGate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The internal suspension surface (PHASE-4 §4): the Data Runtime's
 * {@code requestApproval} calls here with the platform service client's token — no
 * gateway route, no user traffic. SOD_VIOLATION renders back onto the write path as
 * problem+json (the flow fails audibly, §4's fail-closed pin).
 */
@RestController
@RequestMapping("/api/v1/workflow/internal")
public class InternalApprovalController {

    private final SuspensionService suspensions;

    public InternalApprovalController(SuspensionService suspensions) {
        this.suspensions = suspensions;
    }

    public record ApprovalRequest(String tenantId, String app, String entityApiName,
                                  String entityKey, String recordId, String hook,
                                  String stepId, String afterStep, String onReject,
                                  String approversRole, List<String> approverUsers,
                                  String mode, String timeout, String escalateTo,
                                  String initiatingActor, String transition) {
    }

    @PostMapping("/approvals")
    public Map<String, Object> request(@RequestBody ApprovalRequest request) {
        ServiceClientGate.require("suspension");
        return suspensions.request(UUID.fromString(request.tenantId()), request.app(),
                request.entityApiName(), request.entityKey(),
                UUID.fromString(request.recordId()), request.hook(), request.stepId(),
                request.afterStep(), request.onReject(), request.approversRole(),
                request.approverUsers(), request.mode(),
                request.timeout() == null || request.timeout().isBlank() ? null
                        : request.timeout(),
                request.escalateTo() == null || request.escalateTo().isBlank() ? null
                        : request.escalateTo(),
                request.initiatingActor() == null || request.initiatingActor().isBlank()
                        ? null : UUID.fromString(request.initiatingActor()),
                request.transition() == null || request.transition().isBlank()
                        ? null : request.transition());
    }

}
