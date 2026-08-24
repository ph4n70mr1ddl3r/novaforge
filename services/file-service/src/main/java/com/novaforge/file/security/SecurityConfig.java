package com.novaforge.file.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Route gates (PHASE-6 §9): {@code /api/v1/files/**} is user+ — attachment access
 * is governed by the owning record's authorization (the presign legs relay the
 * caller's token to the Data Runtime's read path; presigned URLs themselves are
 * short-lived and attachment-scoped, §8). Internal surfaces (job output upload,
 * import source download) are service-client gated at the method, as everywhere.
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
                        .anyRequest().hasAuthority("SCOPE_novaforge.api"))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(
                        realmRolesConverter())))
                .build();
    }

    /** Platform roles from the token — the metadata service's mapping. */
    static org.springframework.core.convert.converter.Converter<
            org.springframework.security.oauth2.jwt.Jwt,
            ? extends org.springframework.security.authentication.AbstractAuthenticationToken> realmRolesConverter() {
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
            return new org.springframework.security.oauth2.server.resource.authentication
                    .JwtAuthenticationToken(jwt, authorities);
        };
    }
}
