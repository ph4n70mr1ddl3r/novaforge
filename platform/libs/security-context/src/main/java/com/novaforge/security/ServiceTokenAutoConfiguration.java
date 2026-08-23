package com.novaforge.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Binds the shared {@link ServiceTokenClient} in every service that carries
 * this library — no per-service wiring (the library sits outside every app's
 * component scan, so auto-configuration is the registration path). The same
 * {@code novaforge.auth.*} keys the hand-rolled copies read; a service may
 * define its own bean to override.
 */
@AutoConfiguration
public class ServiceTokenAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ServiceTokenClient serviceTokenClient(
            @Value("${novaforge.auth.issuer-uri:http://localhost:8082/realms/novaforge}") String issuer,
            @Value("${novaforge.auth.service-client.id:" + ServiceClientGate.CLIENT_ID + "}") String clientId,
            @Value("${novaforge.auth.service-client.secret:novaforge-runtime-secret}") String clientSecret) {
        return new ServiceTokenClient(issuer, clientId, clientSecret);
    }
}
