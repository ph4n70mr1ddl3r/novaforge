package com.novaforge.audit.security;

import java.util.Collection;
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
 * Resource server (PHASE-3 §5): the trail's read API is admin-facing — the durable
 * record of every write (field diffs included) is not end-user surface. The trail has
 * no write surface at all (append-only mechanically, §5). Platform roles map from the
 * token the same way every service maps them (the {@code platform_roles} claim with
 * {@code realm_access.roles} as the fallback shape).
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
                        .requestMatchers("/api/v1/audit/**").hasRole("admin")
                        .anyRequest().hasAuthority("SCOPE_novaforge.api"))
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
