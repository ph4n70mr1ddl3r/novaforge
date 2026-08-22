package com.novaforge.script;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * NovaForge Script Engine (port 8084) — the ADR-008 escape hatch. Internal: the Data
 * Runtime's hooks invoke it; there is no gateway route in v0 (ARCHITECTURE.md §2.5).
 * Stateless — scripts are versioned artifacts in the Metadata Service.
 */
@SpringBootApplication
public class ScriptEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScriptEngineApplication.class, args);
    }
}
