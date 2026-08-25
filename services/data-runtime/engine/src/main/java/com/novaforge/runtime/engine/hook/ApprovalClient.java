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

    /**
     * One suspension request: who must approve, and where the flow resumes. The
     * {@code transition} is the state-machine edge of the triggering write
     * ({@code PRIOR->NEW}, null when the write changed no state) — the SLA match
     * binding of PHASE-4 §6 / PHASE-2 Annex A.
     */
    record Suspension(UUID tenantId, String appApiName, String entityApiName,
                      String entityKey, UUID recordId, String hookName, String stepId,
                      String afterStep, FlowStep onReject,
                      String approversRole, List<String> approverUsers, String mode,
                      String timeout, String escalateTo, UUID initiatingActor,
                      String transition) {
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
