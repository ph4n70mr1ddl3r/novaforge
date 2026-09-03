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
                // Flowable 8's job-recovery defaults are tuned for long batch jobs
                // and single-engine deployments, and they wedge timer advancement
                // past every test budget under a loaded runner: a job locks for
                // ONE HOUR on acquisition (timerLockTime/asyncJobLockTime),
                // acquisition NEVER re-selects a locked job — the executor's
                // selectJobsToExecute takes LOCK_EXP_TIME_ IS NULL only — so recovery
                // rides exclusively the reset-expired pass, which defaults to a
                // ONE-minute cadence of three jobs. The module's surefire JVM
                // hosts several Spring contexts whose engines share one ACT_*
                // schema (and a production cluster shares it across replicas),
                // so one starved context's locked job froze a due PT1S timer for
                // the whole 60 s await — the 35th review pass's intermittent
                // reactor-run failure, green isolated, red roughly every second
                // full-reactor run. Engine jobs here are BPMN continuations —
                // sub-second; the longest engine-side leg is a callConnector step
                // at its pinned 10 s timeout — so a 20 s ownership window is
                // generous against double-execution (the optimistic job lock
                // bounds a lost race either way), and a 5 s reset-expired cadence
                // bounds the whole unlock-then-reacquire recovery at ~25 s:
                // inside every test budget here, and a crashed worker's jobs
                // recover in seconds instead of the default hour-plus in
                // production — strictly better on both counts.
                executorConfig.setTimerLockTime(java.time.Duration.ofSeconds(20));
                executorConfig.setAsyncJobLockTime(java.time.Duration.ofSeconds(20));
                executorConfig.setResetExpiredJobsInterval(java.time.Duration.ofSeconds(5));
                executorConfig.setTimerLockForceAcquireAfter(
                        java.time.Duration.ofSeconds(30));
                executorConfig.setAsyncJobsGlobalLockForceAcquireAfter(
                        java.time.Duration.ofSeconds(30));
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
