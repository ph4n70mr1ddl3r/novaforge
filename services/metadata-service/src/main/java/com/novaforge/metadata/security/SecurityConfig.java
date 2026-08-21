package com.novaforge.metadata.security;

import java.util.Collection;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Resource-server security (PHASE-0 §6.2, PHASE-1 §4/§7): services never trust the
 * gateway alone. Draft CRUD and publish are design-time surfaces ({@code builder} or
 * {@code admin} platform realm roles — PHASE-2 §9's stance applied from Phase 1); the
 * published runtime read serves any authenticated tenant user. Actuator anonymous.
 *
 * <p>Platform roles are the fixed Keycloak realm roles (admin/builder/user); app-defined
 * roles stay platform-DB metadata per ADR-002 and never ride the token.</p>
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
                        .requestMatchers("/api/v1/metadata/ping").authenticated()
                        .requestMatchers("/api/v1/metadata/published-apps").authenticated()
                        .requestMatchers("/api/v1/metadata/apps/*/published/**").authenticated()
                        .requestMatchers("/api/v1/metadata/**").hasAnyRole("builder", "admin")
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(realmRolesConverter())))
                .build();
    }

    /**
     * Maps platform roles from the token: the {@code platform_roles} claim (the
     * novaforge.api client scope's user-attribute mapper — same mechanism as the
     * tenant claim) with {@code realm_access.roles} accepted as a fallback shape.
     */
    static Converter<Jwt, ? extends AbstractAuthenticationToken> realmRolesConverter() {
        return jwt -> {
            Collection<String> roles = new java.util.ArrayList<>();
            Object platformRoles = jwt.getClaim("platform_roles");
            if (platformRoles instanceof Collection<?> platform) {
                platform.stream().map(String::valueOf).forEach(roles::add);
            }
            Object realmAccess = jwt.getClaim("realm_access");
            if (realmAccess instanceof java.util.Map<?, ?> access
                    && access.get("roles") instanceof Collection<?> collection) {
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
