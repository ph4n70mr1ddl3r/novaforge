package com.novaforge.runtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * NovaForge Data Runtime (port 8083) — the heart (ARCHITECTURE.md §2.4). Scans across
 * the module split: engine → storage/authorization, api on top (ARCHITECTURE.md §7).
 */
@SpringBootApplication(scanBasePackages = "com.novaforge")
public class NovaForgeDataRuntimeApplication {

    public static void main(String[] args) {
        SpringApplication.run(NovaForgeDataRuntimeApplication.class, args);
    }
}
