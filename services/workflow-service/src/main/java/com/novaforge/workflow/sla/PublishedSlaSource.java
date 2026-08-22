package com.novaforge.workflow.sla;

import com.novaforge.metadata.SlaDefinition;
import java.util.List;
import java.util.UUID;

/**
 * Read access to an app's published SLA definitions (PHASE-4 §6): the Workflow
 * Service is a pure metadata consumer — definitions are versioned artifacts on the
 * Metadata Service, never authored here.
 */
public interface PublishedSlaSource {

    List<SlaDefinition> slasOf(UUID tenantId, String appApiName);

    /** A no-op source for hermetic tests: no definitions, timers come from steps. */
  class None implements PublishedSlaSource {

        @Override
        public List<SlaDefinition> slasOf(UUID tenantId, String appApiName) {
            return List.of();
        }
    }
}
