package com.novaforge.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** NovaForge Audit Service (port 8085) — the append-only trail on the event spine. */
@SpringBootApplication
public class AuditServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditServiceApplication.class, args);
    }
}
