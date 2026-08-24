package com.novaforge.workflow.tenants;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.security.ServiceTokenClient;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The {@link TenantLookup} binding: the shared service client's token against the
 * runtime's admin read surface (the same trusted-service path the role lookup
 * rides). Tenant names are near-static; a 30-second cache keeps the scratch gate
 * off the hot path without caching authorization data.
 */
@Component
public class RestTenantLookup implements TenantLookup {

    private final RestClient runtime;
    private final ServiceTokenClient serviceToken;
    private final Map<UUID, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(String apiName, long fetchedAt) {
    }

    public RestTenantLookup(@Value("${novaforge.data-runtime.url:http://localhost:8083}") String runtimeUrl,
                            ServiceTokenClient serviceToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(5_000);
        this.runtime = RestClient.builder().baseUrl(runtimeUrl).requestFactory(factory).build();
        this.serviceToken = serviceToken;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String apiNameOf(UUID tenantId) {
        CacheEntry entry = cache.get(tenantId);
        if (entry != null && System.currentTimeMillis() - entry.fetchedAt() < 30_000) {
            return entry.apiName();
        }
        try {
            Map<String, Object> tenant = runtime.method(HttpMethod.GET)
                    .uri("/api/v1/admin/tenants/" + tenantId)
                    .headers(headers -> headers.setBearerAuth(serviceToken.token()))
                    .retrieve()
                    .body(Map.class);
            String apiName = tenant == null ? null : (String) tenant.get("apiName");
            cache.put(tenantId, new CacheEntry(apiName, System.currentTimeMillis()));
            return apiName;
        } catch (Exception e) {
            // a lookup outage fails closed: unknown tenants are never scratch
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "tenant lookup failed for " + tenantId + ": " + e.getMessage(), null, e);
        }
    }
}
