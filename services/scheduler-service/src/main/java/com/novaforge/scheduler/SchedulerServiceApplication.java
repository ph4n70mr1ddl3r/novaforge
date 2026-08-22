package com.novaforge.scheduler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * NovaForge Scheduler Service (port 8087, PHASE-4 §7): the cron registry. Job
 * definitions are versioned app metadata activated on publish; this service owns
 * runtime state only — next-fire, leases (single-fire under concurrent replicas),
 * run history — and {@code scheduler.job.run} rides the spine per fire. One
 * read-only gateway route ({@code GET /api/v1/scheduler/jobs}); administration is
 * publish-driven, never REST.
 */
@SpringBootApplication
@EnableScheduling
public class SchedulerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchedulerServiceApplication.class, args);
    }
}
