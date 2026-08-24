package com.novaforge.file.api;

import com.novaforge.common.context.TenantContext;
import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The owning record's authorization gate (PHASE-6 §9): attachment access rides the
 * record — a presigned download for a bound attachment relays the caller's token
 * to the Data Runtime's public read and lets its verdict (matrix, field security,
 * sharing rules) decide. Unbound attachments (fresh uploads, job outputs) are
 * governed by possession of the short-lived, attachment-scoped URL alone (§8).
 */
@Component
public class RecordReadGate {

    private final RestClient runtime;

    public RecordReadGate(@Value("${novaforge.data-runtime.url:http://localhost:8083}") String url) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(5_000);
        this.runtime = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    /** True when the caller may read {@code entity}/{recordId} (their token decides). */
    public boolean canRead(String entity, UUID recordId) {
        if (entity == null || recordId == null) {
            return true;   // unbound: possession of the URL governs (§8)
        }
        String token = callerToken();
        try {
            runtime.method(HttpMethod.GET)
                    .uri("/api/v1/runtime/{entity}/{id}", entity, recordId)
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (org.springframework.web.client.RestClientResponseException e) {
            return false;   // 403/404 — the runtime's verdict stands
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "owning-record read gate unreachable: " + e.getMessage());
        }
    }

    private static String callerToken() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String header = attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
            if (header != null && header.startsWith("Bearer ")) {
                return header.substring(7);
            }
        }
        throw new PlatformException(PlatformErrorCode.FORBIDDEN,
                "no caller token bound — attachment access runs caller-context (§9)");
    }

    static UUID tenant() {
        return UUID.fromString(TenantContext.require().tenantId());
    }
}
