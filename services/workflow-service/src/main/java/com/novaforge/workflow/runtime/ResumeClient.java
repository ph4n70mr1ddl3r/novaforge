package com.novaforge.workflow.runtime;

import java.util.UUID;

/**
 * The resume port (PHASE-4 §4): the Workflow Service re-enters the Data Runtime's
 * compiled-graph engine when an approval resolves — system principal, the record's
 * current state, guarded writes.
 */
public interface ResumeClient {

    record Resume(UUID tenantId, String app, String entityApiName, UUID recordId,
                  String hook, String afterStep, String onRejectJson, boolean approved) {
    }

    /** Throws on failure — the caller records it on the suspended instance. */
    void resume(Resume resume);
}
