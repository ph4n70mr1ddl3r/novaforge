package com.novaforge.reporting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * NovaForge Reporting Service (PHASE-5): reports compile to Data Runtime aggregate
 * calls — never raw SQL — and run as the requesting actor, sharing-rule row filters
 * applying exactly as to lists. Stateless by design (§2): definitions come from the
 * Metadata Service's published read, results cache in Redis, scheduled delivery rides
 * the Scheduler's report target in with the delivery through the Notification Service.
 */
@SpringBootApplication
public class ReportingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReportingServiceApplication.class, args);
    }
}
