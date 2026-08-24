package com.novaforge.gateway.docs;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The gateway-side aggregation of the per-service OpenAPI documents (PLAN.md §4:
 * "OpenAPI 3 generated per service, aggregated at gateway"). Each service serves
 * {@code /v3/api-docs} (springdoc); this merges them into one document — paths are
 * the public paths each service's controllers already declare, so aggregation is a
 * union, with the owning service recorded per path item and collisions surfaced
 * instead of silently overwritten.
 *
 * <p>An unavailable upstream degrades audibly: the merged document still serves,
 * with the missing service listed under {@code info.x-novaforge-unavailable} — API
 * docs must never take the edge down. The Script Engine is deliberately absent
 * from the upstream set: it is internal, with no gateway route (ARCHITECTURE.md
 * §2.5).</p>
 */
@Component
public class ApiDocsAggregator {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** One aggregable upstream: the route property key + its base URI. */
    public record Upstream(String name, String baseUri) {
    }

    /** Pluggable fetch so tests drive the merge without sockets. */
    public interface Fetcher {
        Optional<String> fetch(Upstream upstream, String authorization);
    }

    /** The production fetcher: JDK HttpClient, short timeouts, token relayed. */
    static class HttpFetcher implements Fetcher {
        private final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build();

        @Override
        public Optional<String> fetch(Upstream upstream, String authorization) {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(
                                URI.create(stripSlash(upstream.baseUri()) + "/v3/api-docs"))
                        .timeout(Duration.ofSeconds(3))
                        .GET();
                if (authorization != null && !authorization.isBlank()) {
                    request.header("Authorization", authorization);
                }
                HttpResponse<String> response = http.send(request.build(),
                        HttpResponse.BodyHandlers.ofString());
                return response.statusCode() == 200
                        ? Optional.of(response.body()) : Optional.empty();
            } catch (Exception e) {
                return Optional.empty();
            }
        }
    }

    private final Fetcher fetcher;

    @Autowired
    public ApiDocsAggregator() {
        this(new HttpFetcher());
    }

    public ApiDocsAggregator(Fetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * Merges the upstreams' documents into one OpenAPI document. Pure with respect
     * to the fetch results — deterministic order in, deterministic document out.
     */
    public Map<String, Object> aggregate(List<Upstream> upstreams, String authorization) {
        Map<String, Object> paths = new LinkedHashMap<>();
        Map<String, Object> components = new LinkedHashMap<>();
        List<Map<String, Object>> tags = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        String openapiVersion = null;

        for (Upstream upstream : upstreams) {
            Optional<String> body = fetcher.fetch(upstream, authorization);
            if (body.isEmpty()) {
                unavailable.add(upstream.name());
                continue;
            }
            Map<String, Object> doc = MAPPER.readValue(body.get(), Map.class);
            if (openapiVersion == null && doc.get("openapi") instanceof String version) {
                openapiVersion = version;
            }
            tags.add(Map.of("name", upstream.name(),
                    "description", upstream.baseUri()));
            if (doc.get("paths") instanceof Map<?, ?> upstreamPaths) {
                for (Map.Entry<?, ?> pathEntry : upstreamPaths.entrySet()) {
                    String path = String.valueOf(pathEntry.getKey());
                    if (!(pathEntry.getValue() instanceof Map<?, ?> item)) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> mergedItem = (Map<String, Object>) paths.computeIfAbsent(
                            path, key -> new LinkedHashMap<String, Object>());
                    // the owning service rides every path item — first-wins, matching
                    // the method-merge policy below (distinct service prefixes never
                    // collide; a genuine overlap surfaces under x-novaforge-conflicts)
                    mergedItem.putIfAbsent("x-novaforge-service", upstream.name());
                    for (Map.Entry<?, ?> method : item.entrySet()) {
                        String key = String.valueOf(method.getKey());
                        if (key.startsWith("x-") || !(method.getValue() instanceof Map)) {
                            continue;
                        }
                        if (mergedItem.containsKey(key)) {
                            conflicts.add(upstream.name() + ":" + path + ":" + key);
                        } else {
                            mergedItem.put(key, method.getValue());
                        }
                    }
                }
            }
            if (doc.get("components") instanceof Map<?, ?> upstreamComponents) {
                for (Map.Entry<?, ?> group : upstreamComponents.entrySet()) {
                    String groupKey = String.valueOf(group.getKey());
                    if (!(group.getValue() instanceof Map<?, ?> entries)) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> mergedGroup = (Map<String, Object>) components
                            .computeIfAbsent(groupKey, key -> new LinkedHashMap<String, Object>());
                    for (Map.Entry<?, ?> entry : entries.entrySet()) {
                        mergedGroup.putIfAbsent(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
            }
            if (doc.get("tags") instanceof List<?> upstreamTags) {
                upstreamTags.stream()
                        .filter(tag -> tag instanceof Map)
                        .map(tag -> (Map<String, Object>) tag)
                        .forEach(tags::add);
            }
        }

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", "NovaForge Platform API");
        info.put("version", "0.1.0");
        info.put("description", """
                The aggregated platform API: one document merged from the per-service
                OpenAPI contracts (PLAN.md §4). Every path carries the owning service
                under x-novaforge-service; authentication is the realm JWT with scope
                novaforge.api, except the anonymous HMAC-authenticated
                /api/v1/webhooks/inbound prefix.""");
        List<String> included = upstreams.stream().map(Upstream::name).toList();
        info.put("x-novaforge-services", included);
        if (!unavailable.isEmpty()) {
            info.put("x-novaforge-unavailable", unavailable);
        }
        if (!conflicts.isEmpty()) {
            info.put("x-novaforge-conflicts", conflicts);
        }

        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("openapi", openapiVersion == null ? "3.1.0" : openapiVersion);
        merged.put("info", info);
        merged.put("tags", tags);
        merged.put("paths", paths);
        if (!components.isEmpty()) {
            merged.put("components", components);
        }
        return merged;
    }

    private static String stripSlash(String uri) {
        return uri == null || uri.isBlank() || !uri.endsWith("/")
                ? uri : uri.substring(0, uri.length() - 1);
    }
}
