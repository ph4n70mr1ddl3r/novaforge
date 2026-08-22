package com.novaforge.runtime.engine.hook;

import com.novaforge.metadata.FlowStep;
import java.util.List;
import java.util.UUID;

/**
 * The {@code requestApproval} suspension port (PHASE-4 §4): the write path hands the
 * approval to the Workflow Service, which persists the suspended instance and owns
 * the task lifecycle. Resolution re-enters the compiled-graph engine here —
 * {@link #resume} — system principal, never holding the triggering write's
 * transaction.
 */
public interface ApprovalClient {

    /** One suspension request: who must approve, and where the flow resumes. */
    record Suspension(UUID tenantId, String appApiName, String entityApiName,
                      String entityKey, UUID recordId, String hookName, String stepId,
                      String afterStep, FlowStep onReject,
                      String approversRole, List<String> approverUsers, String mode,
                      UUID initiatingActor) {
    }

    /**
     * Creates the approval (task + suspended instance). Throws
     * {@code SOD_VIOLATION} when the initiating actor is the only candidate
     * approver — the flow fails audibly (§4, fail closed).
     */
    void request(Suspension suspension);

    /** A no-op binding for contexts without the Workflow Service (hermetic tests). */
    class Disabled implements ApprovalClient {

        @Override
        public void request(Suspension suspension) {
            throw new com.novaforge.common.error.PlatformException(
                    com.novaforge.common.error.PlatformErrorCode.VALIDATION_FAILED,
                    "requestApproval is unavailable — no Workflow Service bound");
        }
    }
}
