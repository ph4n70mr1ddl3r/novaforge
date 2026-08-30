package com.novaforge.gateway.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless resource server (PHASE-0 §6.1): actuator endpoints anonymous, everything
 * else requires scope {@code novaforge.api}. Validation failures render RFC 7807
 * problem+json via {@link ProblemAuthenticationEntryPoint}.
 *
 * <p>The browser bundles are static assets behind the gateway (PHASE-2 §13 Q5,
 * same-origin hosting): asset paths are anonymous — the SPAs drive their own OIDC
 * flow — while every API call still carries the JWT. SPA deep links fall back to
 * the shell documents ({@link com.novaforge.gateway.ui.SpaFallbackController}).
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
                        // health/info stay anonymous (probes, humans); prometheus
                        // carries per-route volumes, upstream latencies, and infra
                        // detail — the gateway is the one internet-facing component,
                        // so the exposition surface is authenticated (any valid token:
                        // the scraper rides the platform's own client). The
                        // behind-the-cluster posture the L-TP7 note recorded only ever
                        // held for the backend services, not for the edge.
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasAuthority("SCOPE_novaforge.api")
                        // the one anonymous API route (PHASE-6 §2/§6): the gateway's
                        // default JWT requirement lifts for exactly this prefix — the
                        // Integration Service authenticates by HMAC, and the prefix is
                        // rate-limited from its first day (WebhookRateLimitFilter).
                        .requestMatchers("/api/v1/webhooks/inbound/**").permitAll()
                        // the static browser bundles (PHASE-2 §13 Q5): "/" serves the
                        // runtime shell; /runtime/** and /builder/** carry the two SPAs.
                        // Assets are public; APIs behind them stay scope-gated.
                        .requestMatchers("/", "/runtime/**", "/builder/**", "/assets/**").permitAll()
                        .anyRequest().hasAuthority("SCOPE_novaforge.api"))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> { }))
                .build();
    }
}
