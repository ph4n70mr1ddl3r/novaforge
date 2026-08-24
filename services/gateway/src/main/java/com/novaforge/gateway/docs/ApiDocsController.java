package com.novaforge.gateway.docs;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the aggregated OpenAPI document (PLAN.md §4): the gateway fetches every
 * routed service's {@code /v3/api-docs} — relaying the caller's token, since the
 * docs sit behind the same authentication as the APIs they describe — and merges
 * them into one contract at {@code GET /api/v1/openapi.json}. The merged document
 * caches briefly (60 s): it is documentation, not a data path. Scope-gated like
 * every other API route.
 */
@RestController
public class ApiDocsController {

    /** The routed upstreams whose docs aggregate (route order, fixed). */
    private static final List<String[]> UPSTREAM_KEYS = List.of(
            new String[]{"metadata-service", "http://localhost:8081"},
            new String[]{"data-runtime", "http://localhost:8083"},
            new String[]{"audit-service", "http://localhost:8085"},
            new String[]{"workflow-service", "http://localhost:8086"},
            new String[]{"scheduler-service", "http://localhost:8087"},
            new String[]{"notification-service", "http://localhost:8088"},
            new String[]{"reporting-service", "http://localhost:8089"},
            new String[]{"integration-service", "http://localhost:8090"},
            new String[]{"file-service", "http://localhost:8091"});

    private static final long CACHE_MILLIS = 60_000;

    private final ApiDocsAggregator aggregator;
    private final Environment environment;

    private volatile Cached cached;

    private record Cached(Map<String, Object> document, Instant fetchedAt) {
    }

    public ApiDocsController(ApiDocsAggregator aggregator, Environment environment) {
        this.aggregator = aggregator;
        this.environment = environment;
    }

    @GetMapping("/api/v1/openapi.json")
    public Map<String, Object> openApi(@RequestHeader(value = "Authorization", required = false)
                                       String authorization) {
        Cached current = cached;
        if (current == null || Instant.now().isAfter(current.fetchedAt().plusMillis(CACHE_MILLIS))
                || degraded(current.document())) {
            Map<String, Object> document = aggregator.aggregate(upstreams(), authorization);
            cached = new Cached(document, Instant.now());
            current = cached;
        }
        return current.document();
    }

    /** A degraded doc (some upstream missing) refetches eagerly — self-healing. */
    private static boolean degraded(Map<String, Object> document) {
        return document.get("info") instanceof Map<?, ?> info
                && info.get("x-novaforge-unavailable") instanceof List<?> missing && !missing.isEmpty();
    }

    private List<ApiDocsAggregator.Upstream> upstreams() {
        List<ApiDocsAggregator.Upstream> upstreams = new ArrayList<>();
        for (String[] key : UPSTREAM_KEYS) {
            String baseUri = environment.getProperty(
                    "novaforge.upstreams." + key[0], key[1]);
            upstreams.add(new ApiDocsAggregator.Upstream(key[0], baseUri));
        }
        return upstreams;
    }
}
