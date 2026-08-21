package com.novaforge.metadata.api;

import java.util.Map;
import org.springframework.core.SpringVersion;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 0 stack proof (PHASE-0 §6.2). The exact framework version is asserted in tests so
 * silent framework downgrades fail the build (PHASE-0 §10 version-drift rule).
 */
@RestController
@RequestMapping("/api/v1/metadata")
public class PingController {

    @GetMapping("/ping")
    Map<String, String> ping() {
        return Map.of(
                "service", "metadata-service",
                "status", "ok",
                "springFrameworkVersion", SpringVersion.getVersion());
    }
}
