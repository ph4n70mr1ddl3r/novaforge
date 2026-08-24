package com.novaforge.integration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * NovaForge Integration Service (port 8090, PHASE-6): the connector runtime (REST
 * first — circuit breaker, bounded retries, idempotent deliveries, DLQ), webhooks
 * both directions under one HMAC scheme, inbound writes through the Data Runtime's
 * single write path as the per-app integration principal, and the async, resumable
 * import/export jobs that stream to the File Service. One deliberate anonymous
 * surface: the inbound-webhook prefix, authenticated by HMAC at this service (§6).
 */
@SpringBootApplication
@EnableScheduling
public class IntegrationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IntegrationServiceApplication.class, args);
    }
}
