package com.novaforge.workflow.process;

import java.util.ArrayList;
import java.util.List;
import org.flowable.common.engine.impl.persistence.StrongUuidGenerator;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Embedded-engine wiring (PHASE-4 §9, ADR-004): the schema lives in this service's
 * own database on the shared DataSource (the starter's default against
 * {@code novaforge_workflow}), engine ids are UUIDs (no cluster-wide sequence
 * contention), and the global event listener set carries the inbox bridge — the
 * starter registers none by default, so the assignment is additive-safe.
 */
@Configuration
public class FlowableEngineConfig {

    @Bean
    EngineConfigurationConfigurer<SpringProcessEngineConfiguration> novaforgeProcessEngine(
            ProcessTaskBridge bridge) {
        return configuration -> {
            configuration.setIdGenerator(new StrongUuidGenerator());
            // in-engine timers acquire on a 1 s cycle (the 10 s default empty-poll
            // wait stretched past every test budget on a loaded runner — timers
            // are §6/§9 machinery, not a batch workload; a faster tick is free)
            var executorConfig = configuration.getAsyncExecutorConfiguration();
            if (executorConfig != null) {
                executorConfig.setDefaultTimerJobAcquireWaitTime(
                        java.time.Duration.ofSeconds(1));
                executorConfig.setDefaultAsyncJobAcquireWaitTime(
                        java.time.Duration.ofSeconds(1));
            }
            List<org.flowable.common.engine.api.delegate.event.FlowableEventListener> listeners =
                    new ArrayList<>();
            if (configuration.getEventListeners() != null) {
                listeners.addAll(configuration.getEventListeners());
            }
            listeners.add(bridge);
            configuration.setEventListeners(listeners);
        };
    }
}
