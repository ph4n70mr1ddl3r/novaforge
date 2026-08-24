package com.novaforge.integration.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Route gates (PHASE-6 §9): {@code /api/v1/integrations/**} is builder+ —
 * definition authoring and the operational surfaces (delivery log, DLQ replay,
 * import/export jobs) are builder tooling in v1. The inbound-webhook prefix stays
 * anonymous by design (§6): the gateway lifts its default JWT requirement for
 * exactly that prefix, and this service authenticates by HMAC — the same scheme
 * that signs outbound (§5). Internal surfaces (callConnector execution, report
 * export jobs) are service-client gated at the method, as everywhere.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            ProblemAuthenticationEntryPoint entryPoint) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        // the one anonymous API route (§2/§6): HMAC decides, not JWT
                        .requestMatchers("/api/v1/webhooks/inbound/**").permitAll()
                        .requestMatchers("/api/v1/integrations/**").hasAnyRole("builder", "admin")
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(realmRolesConverter())))
                .build();
    }

    /** The security chain never spans the anonymous webhook prefix (no JWT at all). */
    @Bean
    WebSecurityCustomizer anonymousWebhooks() {
        return web -> web.ignoring().requestMatchers("/api/v1/webhooks/inbound/**");
    }

    /**
     * Platform roles from the token — the metadata service's mapping (the
     * {@code platform_roles} claim with {@code realm_access.roles} fallback).
     */
    static Converter<Jwt, ? extends AbstractAuthenticationToken> realmRolesConverter() {
        return jwt -> {
            java.util.Collection<String> roles = new java.util.ArrayList<>();
            Object platformRoles = jwt.getClaim("platform_roles");
            if (platformRoles instanceof java.util.Collection<?> platform) {
                platform.stream().map(String::valueOf).forEach(roles::add);
            }
            Object realmAccess = jwt.getClaim("realm_access");
            if (realmAccess instanceof java.util.Map<?, ?> access
                    && access.get("roles") instanceof java.util.Collection<?> collection) {
                collection.stream().map(String::valueOf).forEach(roles::add);
            }
            var authorities = roles.stream()
                    .map(role -> new org.springframework.security.core.authority.SimpleGrantedAuthority(
                            "ROLE_" + role))
                    .toList();
            return new JwtAuthenticationToken(jwt, authorities);
        };
    }
}
