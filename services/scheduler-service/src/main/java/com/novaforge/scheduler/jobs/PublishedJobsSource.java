package com.novaforge.scheduler.jobs;

import com.novaforge.metadata.ScheduledJobDefinition;
import java.util.List;
import java.util.UUID;

/**
 * Read access to the published jobs of every app (PHASE-4 §7): the registry syncs
 * from the Metadata Service's published surface — definitions are versioned
 * artifacts, the registry is runtime state.
 */
public interface PublishedJobsSource {

    /** One app's published jobs: {app apiName → its job definitions}. */
    List<AppJobs> all();

    record AppJobs(UUID tenantId, String appApiName, List<ScheduledJobDefinition> jobs) {
    }

    /** An empty source for hermetic tests. */
    class None implements PublishedJobsSource {

        @Override
        public List<AppJobs> all() {
            return List.of();
        }
    }
}
