package com.novaforge.integration.connector;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.integration.clients.PublishedIntegrations;
import com.novaforge.integration.secrets.SecretStore;
import com.novaforge.integration.store.DeliveryStore;
import com.novaforge.metadata.ConnectorDefinition;
import com.novaforge.metadata.CredentialDefinition;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The REST connector executor (PHASE-6 §3): mapping templates resolve with the
 * shared {@code ${…}} convention, the auth set (API-key header, HTTP basic, OAuth2
 * client-credentials — §13 Q1's resolved scope) materializes from the credential
 * reference + secret store, and every call rides a per-connector circuit breaker
 * with bounded retries and exponential backoff under the §4-pinned 10 s timeout.
 * Deliveries are idempotent — a dedupe key (the provider event/call id) returns
 * the recorded outcome, never a second call — and terminal failures park in the
 * DLQ with the request preserved for builder replay. Every outcome emits
 * {@code connector.delivered} and lands in the audit trail.
 */
@Component
public class ConnectorExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectorExecutor.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final Pattern TEMPLATE = Pattern.compile("\\$\\{([^}]+)}");

    /** The §4-pinned synchronous budget — a connector call never suspends a flow. */
    private final Duration timeout;

    private final PublishedIntegrations definitions;
    private final SecretStore secrets;
    private final DeliveryStore deliveries;
    private final CircuitBreakerRegistry breakers;
    private final RetryRegistry retries;
    public ConnectorExecutor(PublishedIntegrations definitions, SecretStore secrets,
                             DeliveryStore deliveries,
                             @Value("${novaforge.connector.timeout-ms:10000}") long timeoutMs,
                             @Value("${novaforge.connector.attempts:4}") int attempts,
                             @Value("${novaforge.connector.backoff-initial-ms:200}") long backoffInitial,
                             @Value("${novaforge.connector.backoff-max-ms:2000}") long backoffMax) {
        this.definitions = definitions;
        this.secrets = secrets;
        this.deliveries = deliveries;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.breakers = CircuitBreakerRegistry.ofDefaults();
        io.github.resilience4j.retry.RetryConfig config = io.github.resilience4j.retry.RetryConfig
                .custom()
                .maxAttempts(attempts)
                .intervalFunction(in -> Math.min(backoffInitial * (1L << (in - 1)), backoffMax))
                .build();
        this.retries = RetryRegistry.of(config);
    }

    /** A settled connector call: status, body, and the delivery id it logged. */
    public record Execution(int status, JsonNode body, UUID deliveryId) {
    }

    /**
     * Executes one operation of one connector as the tenant's published definition
     * dictates. {@code dedupeKey} is the caller's idempotency handle — the flow
     * engine passes record-scoped keys, so an after-hook retry (PHASE-3 §2) collapses
     * onto the recorded outcome instead of double-calling the provider.
     */
    public Execution execute(UUID tenantId, String appApiName, String connectorId,
                             String operationName, Map<String, Object> template,
                             String dedupeKey) {
        ConnectorDefinition connector = definitions.byApiName(tenantId, appApiName)
                .flatMap(app -> app.connector(connectorId))
                .orElseThrow(() -> new PlatformException(PlatformErrorCode.NOT_FOUND,
                        "connector " + connectorId + " is not published in app " + appApiName));
        ConnectorDefinition.Operation operation = connector.operation(operationName)
                .orElseThrow(() -> new PlatformException(PlatformErrorCode.NOT_FOUND,
                        "connector " + connectorId + " has no operation " + operationName));

        Map<String, Object> params = template == null ? Map.of() : template;
        String key = dedupeKey == null || dedupeKey.isBlank()
                ? UUID.randomUUID().toString() : dedupeKey;
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("connector", connectorId);
        request.put("operation", operationName);
        request.put("template", params);
        var settled = deliveries.settleOrOpen(tenantId, DeliveryStore.KIND_CONNECTOR,
                connectorId + ":" + operationName, key, MAPPER.writeValueAsString(request));
        if (settled.isPresent() && settled.get().delivered()) {
            return new Execution(200, MAPPER.readTree(
                    settled.get().responseSummary() == null ? "{}" : settled.get().responseSummary()),
                    null);   // the recorded outcome stands — the call never re-fires
        }

        CircuitBreaker breaker = breakers.circuitBreaker(tenantId + ":" + connectorId);
        Retry retry = retries.retry(tenantId + ":" + connectorId);
        UUID deliveryId = deliveries.find(tenantId, DeliveryStore.KIND_CONNECTOR,
                connectorId + ":" + operationName, key).map(DeliveryStore.Delivery::id)
                .orElse(null);
        long start = System.nanoTime();
        try {
            Execution execution = Retry.decorateSupplier(retry,
                    CircuitBreaker.decorateSupplier(breaker, () ->
                            call(tenantId, connector, operation, params))).get();
            long latencyMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            if (deliveryId != null) {
                deliveries.record(deliveryId, "delivered", execution.status(), latencyMs,
                        MAPPER.writeValueAsString(execution.body()), null);
            }
            deliveries.outbox(tenantId, "connector.delivered", Map.of(
                    "connector", connectorId, "operation", operationName,
                    "deliveryId", String.valueOf(deliveryId),
                    "status", execution.status(), "latencyMs", latencyMs));
            return execution;
        } catch (Exception e) {
            long latencyMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (deliveryId != null) {
                deliveries.record(deliveryId, "dlq", null, latencyMs, null, error);
            }
            deliveries.park(tenantId, DeliveryStore.KIND_CONNECTOR,
                    connectorId + ":" + operationName, request, null, error);
            deliveries.outbox(tenantId, "connector.delivered", Map.of(
                    "connector", connectorId, "operation", operationName,
                    "deliveryId", String.valueOf(deliveryId),
                    "status", "dlq", "error", error));
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "connector " + connectorId + "." + operationName + " failed terminally "
                            + "after bounded retries: " + error);
        }
    }

    /** One HTTP call: interpolated address + templated query/body + credential auth. */
    private Execution call(UUID tenantId, ConnectorDefinition connector,
                           ConnectorDefinition.Operation operation, Map<String, Object> params) {
        String path = interpolate(operation.path(), params, true);
        StringBuilder uri = new StringBuilder(stripSlash(connector.baseUrl())).append(path);
        if (!operation.query().isEmpty()) {
            StringBuilder query = new StringBuilder("?");
            for (Map.Entry<String, Object> entry : operation.query().entrySet()) {
                if (query.length() > 1) {
                    query.append('&');
                }
                query.append(url(entry.getKey())).append('=')
                        .append(url(interpolate(String.valueOf(entry.getValue()), params, false)));
            }
            uri.append(query);
        }
        RestClient client = RestClient.builder()
                .requestFactory(timedFactory())
                .build();
        RestClient.RequestBodySpec spec = client.method(HttpMethod.valueOf(operation.method()))
                .uri(uri.toString())
                .accept(MediaType.APPLICATION_JSON);
        for (Map.Entry<String, Object> header : operation.headers().entrySet()) {
            spec.header(header.getKey(), interpolate(String.valueOf(header.getValue()), params, false));
        }
        authenticate(tenantId, connector, spec);
        if (operation.body() != null) {
            spec.contentType(MediaType.APPLICATION_JSON)
                    .body(MAPPER.writeValueAsString(resolve(operation.body(), params)));
        }
        long start = System.nanoTime();
        String response = spec.retrieve().body(String.class);
        long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
        LOG.debug("connector {}.{} → {} in {}ms", connector.id(), operation.name(),
                uri, elapsed);
        JsonNode body = response == null || response.isBlank()
                ? MAPPER.readTree("{}") : MAPPER.readTree(response);
        return new Execution(200, body, null);
    }

    /**
     * The v1 auth set (§3): API-key header, HTTP basic, OAuth2 client-credentials.
     * Secrets resolve from the store by the credential's id — AES-GCM at rest, never
     * in metadata (§9). OAuth2 tokens cache per credential until 30 s before expiry.
     */
    private void authenticate(UUID tenantId, ConnectorDefinition connector,
                              RestClient.RequestBodySpec spec) {
        if (connector.credential() == null) {
            return;
        }
        CredentialDefinition credential = definitions.allApps(tenantId).stream()
                .map(app -> app.integrations().credential(connector.credential()))
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .findFirst()
                .orElseThrow(() -> new PlatformException(PlatformErrorCode.NOT_FOUND,
                        "credential " + connector.credential() + " not published"));
        List<String> active = secrets.active(tenantId, credential.id());
        String secret = active.isEmpty() ? null : active.getFirst();
        switch (credential.kind()) {
            case CredentialDefinition.KIND_API_KEY -> {
                if (secret == null) {
                    throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                            "credential " + credential.id() + " has no secret provisioned");
                }
                String prefix = "Authorization".equalsIgnoreCase(credential.header())
                        ? "Bearer " : "";
                spec.header(credential.header(), prefix + secret);
            }
            case CredentialDefinition.KIND_BASIC -> {
                if (secret == null) {
                    throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                            "credential " + credential.id() + " has no secret provisioned");
                }
                String token = Base64.getEncoder().encodeToString(
                        (credential.username() + ":" + secret).getBytes(StandardCharsets.UTF_8));
                spec.header("Authorization", "Basic " + token);
            }
            case CredentialDefinition.KIND_OAUTH2_CC -> spec.header("Authorization",
                    "Bearer " + oauthToken(credential, secret));
            default -> throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "unknown credential kind: " + credential.kind());
        }
    }

    private final Map<String, CachedToken> tokenCache = new java.util.concurrent.ConcurrentHashMap<>();

    private record CachedToken(String token, Instant refreshAt) {
    }

    /** Client-credentials grant at the credential's token URL (§13 Q1's resolved scope). */
    private String oauthToken(CredentialDefinition credential, String secret) {
        CachedToken cached = tokenCache.get(credential.id());
        if (cached != null && Instant.now().isBefore(cached.refreshAt())) {
            return cached.token();
        }
        String form = "grant_type=client_credentials&client_id="
                + url(credential.clientId()) + "&client_secret=" + url(secret == null ? "" : secret);
        String response = RestClient.create().post()
                .uri(credential.tokenUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
        Map<String, Object> granted = MAPPER.readValue(response == null ? "{}" : response, Map.class);
        if (granted.get("access_token") == null) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "oauth2 grant at " + credential.tokenUrl() + " returned no token");
        }
        long seconds = granted.get("expires_in") instanceof Number number ? number.longValue() : 300;
        tokenCache.put(credential.id(), new CachedToken(String.valueOf(granted.get("access_token")),
                Instant.now().plusSeconds(Math.max(0, seconds - 30))));
        return String.valueOf(granted.get("access_token"));
    }

    // --- ${…} mapping (ADR-008's shared convention, host-resolved) ---

    /** Path templates URL-encode their values; query values encode at the param level. */
    private String interpolate(String text, Map<String, Object> params, boolean encodePath) {
        if (text == null) {
            return "";
        }
        Matcher matcher = TEMPLATE.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            Object value = resolveValue(matcher.group(1), params);
            String rendered = value == null ? "" : String.valueOf(value);
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    encodePath ? url(rendered) : rendered));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** Deep template resolution for body maps (nested objects and arrays ride along). */
    private Object resolve(Object value, Map<String, Object> params) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            map.forEach((k, v) -> resolved.put(String.valueOf(k), resolve(v, params)));
            return resolved;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> resolve(item, params)).toList();
        }
        if (value instanceof String text && text.startsWith("${") && text.endsWith("}")) {
            Object resolved = resolveValue(text.substring(2, text.length() - 1), params);
            return resolved == null ? null : resolved;
        }
        return value;
    }

    private Object resolveValue(String reference, Map<String, Object> params) {
        String[] path = reference.split("\\.");
        Object current = params;
        for (String segment : path) {
            if (current instanceof Map<?, ?> map && map.containsKey(segment)) {
                current = map.get(segment);
            } else {
                return null;
            }
        }
        return current;
    }

    private org.springframework.http.client.ClientHttpRequestFactory timedFactory() {
        SimpleFactory factory = new SimpleFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout((int) timeout.toMillis());
        return factory;
    }

    /** A plain factory with the §4 timeout applied per request (read = the call budget). */
    static class SimpleFactory extends org.springframework.http.client.SimpleClientHttpRequestFactory {
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
