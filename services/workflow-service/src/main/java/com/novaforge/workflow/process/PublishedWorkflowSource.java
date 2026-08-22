package com.novaforge.workflow.process;

import com.novaforge.metadata.WorkflowDefinition;
import java.util.List;
import java.util.UUID;

/**
 * Read access to every tenant's published {@link WorkflowDefinition}s (PHASE-4 §9):
 * the Workflow Service is a pure metadata consumer — definitions are versioned
 * artifacts on the Metadata Service, never authored here (the §7
 * definitions-vs-registry split, applied to process deployments).
 */
public interface PublishedWorkflowSource {

    /** One tenant's one app with its published workflow definitions. */
    record AppWorkflows(UUID tenantId, String appApiName, List<WorkflowDefinition> workflows) {
    }

    /** Every published app's workflows, across tenants (the service-caller index). */
    List<AppWorkflows> all();

    /** An empty source for hermetic tests: nothing deploys, nothing starts. */
    class None implements PublishedWorkflowSource {

        @Override
        public List<AppWorkflows> all() {
            return List.of();
        }
    }
}
