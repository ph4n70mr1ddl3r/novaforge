package com.novaforge.script;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 per service (PLAN.md §4, PHASE-1 §4): the /v3/api-docs endpoint
 * serves the machine-readable contract of this service's public surface; the
 * gateway aggregates them into one document. Authenticated like every other route.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI serviceOpenApi() {
        return new OpenAPI().info(new Info()
                .title("NovaForge Script Engine")
                .description("Sandboxed GraalVM JS execution for escape-hatch scripts (ADR-003) — internal: hooks invoke it, there is no gateway route.")
                .version("0.1.0"));
    }
}
