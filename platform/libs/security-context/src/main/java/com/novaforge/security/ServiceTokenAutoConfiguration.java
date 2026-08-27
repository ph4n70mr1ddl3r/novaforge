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
 *
 * <p><b>Fail-closed on the dev secret (the 2025-08-27 review):</b> the committed
 * development default ({@link ServiceClientGate#DEFAULT_DEV_SECRET}) keeps local
 * bring-up working but never authenticates a staged deployment —
 * {@code novaforge.auth.service-client.allow-default-secret=false} (the helm charts
 * set it) fails startup instead, because a bearer of this client's token passes
 * every internal service gate. Locally the default stays and boots with a warning.</p>
 */
@AutoConfiguration
public class ServiceTokenAutoConfiguration {

    private static final System.Logger LOG =
            System.getLogger(ServiceTokenAutoConfiguration.class.getName());

    @Bean
    @ConditionalOnMissingBean
    ServiceTokenClient serviceTokenClient(
            @Value("${novaforge.auth.issuer-uri:http://localhost:8082/realms/novaforge}") String issuer,
            @Value("${novaforge.auth.service-client.id:" + ServiceClientGate.CLIENT_ID + "}") String clientId,
            @Value("${novaforge.auth.service-client.secret:" + ServiceClientGate.DEFAULT_DEV_SECRET + "}") String clientSecret,
            @Value("${novaforge.auth.service-client.allow-default-secret:true}") boolean allowDefaultSecret) {
        boolean devSecret = ServiceClientGate.DEFAULT_DEV_SECRET.equals(clientSecret);
        if (devSecret && !allowDefaultSecret) {
            throw new IllegalStateException("the service-client secret is the committed "
                    + "development default and defaults are not allowed here — set "
                    + "NOVAFORGE_SERVICE_CLIENT_SECRET from the deployment's secret source "
                    + "before starting this service");
        }
        if (devSecret) {
            LOG.log(System.Logger.Level.WARNING,
                    "the service-client secret is the committed development default "
                            + "(novaforge-runtime-secret) — local bring-up only; staged "
                            + "environments set NOVAFORGE_SERVICE_CLIENT_SECRET and "
                            + "NOVAFORGE_AUTH_ALLOW_DEFAULT_SECRET=false");
        }
        return new ServiceTokenClient(issuer, clientId, clientSecret);
    }
}
