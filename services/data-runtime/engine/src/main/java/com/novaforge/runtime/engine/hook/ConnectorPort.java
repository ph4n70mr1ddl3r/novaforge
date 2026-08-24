package com.novaforge.runtime.engine.hook;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The connector port (PHASE-6 §4): {@code callConnector} steps call the
 * Integration Service's internal execution surface with the platform service
 * client — the executor owns the §4-pinned 10 s timeout, the circuit breaker,
 * and the credential machinery, so this client's read timeout (11 s) only needs
 * to outlive one bounded attempt. Failures propagate to the hook failure policy
 * unchanged: before-hooks abort the transaction, after-hooks ride the spine's
 * retry leg (PHASE-3 §2).
 */
public interface ConnectorPort {

    /** A settled connector call: the provider's status and body. */
    record ConnectorResult(int status, JsonNode body) {
    }

    /**
     * Executes one operation of one published connector.
     *
     * @param tenantId    the tenant of the triggering write
     * @param appApiName  the app whose published definition carries the connector
     * @param connector   the connector id
     * @param operation   the operation name
     * @param template    the step's template (operation params — ${field} resolved)
     * @param dedupeKey   idempotency handle (record-scoped): after-hook retries collapse
     */
    ConnectorResult execute(String tenantId, String appApiName, String connector,
                            String operation, Map<String, Object> template, String dedupeKey);
}
