package com.novaforge.metadata;

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
                .title("NovaForge Metadata Service")
                .description("Design-time control plane: definition APIs with save validation, versioned publish, the builder test harness, and the Phase 8 app lifecycle (environments, gated promotion, rollback, templates, i18n).")
                .version("0.1.0"));
    }
}
