package com.novaforge.workflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * NovaForge Workflow Service (port 8086, PHASE-4 §2): human tasks and the inbox API
 * (§5), approvals with durable flow suspension (§4), and SLA/escalation timers (§6)
 * as those land. A pure spine participant — it consumes {@code record.*} (deletion
 * cancels open tasks) and emits {@code task.*}; it never mutates records.
 */
@SpringBootApplication
@EnableScheduling
public class WorkflowServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkflowServiceApplication.class, args);
    }
}
