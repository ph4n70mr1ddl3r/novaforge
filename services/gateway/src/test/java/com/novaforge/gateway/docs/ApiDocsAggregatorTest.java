package com.novaforge.gateway.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The aggregation merge (PLAN.md §4 — "aggregated at gateway"): paths union under
 * their owning service's tag, components merge first-wins, an unavailable upstream
 * degrades audibly instead of failing the edge, and duplicate path+method pairs
 * surface as conflicts rather than being silently overwritten.
 */
class ApiDocsAggregatorTest {

    private static final String METADATA_DOC = """
            {"openapi":"3.1.0","info":{"title":"NovaForge Metadata Service"},
             "paths":{"/api/v1/metadata/apps":{"get":{"operationId":"listApps","tags":["apps"]}}},
             "components":{"schemas":{"AppDefinition":{"type":"object"}}},
             "tags":[{"name":"apps"}]}
            """;

    private static final String RUNTIME_DOC = """
            {"openapi":"3.1.0","info":{"title":"NovaForge Data Runtime Service"},
             "paths":{"/api/v1/runtime/{entity}":{"post":{"operationId":"createRecord"}},
                       "/api/v1/metadata/apps":{"post":{"operationId":"createApp"}}},
             "components":{"schemas":{"AppDefinition":{"type":"object","x":"colliding"},"Record":{"type":"object"}}}}
            """;

    @Test
    @DisplayName("paths union under the owning service; components merge; tags carry the services")
    void mergesServiceDocs() {
        var aggregator = new ApiDocsAggregator((upstream, auth) -> switch (upstream.name()) {
            case "metadata-service" -> Optional.of(METADATA_DOC);
            case "data-runtime" -> Optional.of(RUNTIME_DOC);
            default -> Optional.empty();
        });
        Map<String, Object> merged = aggregator.aggregate(List.of(
                new ApiDocsAggregator.Upstream("metadata-service", "http://localhost:8081"),
                new ApiDocsAggregator.Upstream("data-runtime", "http://localhost:8083")), null);

        assertThat(merged).containsEntry("openapi", "3.1.0");
        assertThat(merged.get("info")).isInstanceOf(Map.class);
        assertThat((Map<Object, Object>) (Map<?, ?>) merged.get("info")).containsEntry("title", "NovaForge Platform API");

        @SuppressWarnings("unchecked")
        Map<String, Object> paths = (Map<String, Object>) merged.get("paths");
        assertThat(paths).containsKeys("/api/v1/metadata/apps", "/api/v1/runtime/{entity}");
        @SuppressWarnings("unchecked")
        Map<String, Object> apps = (Map<String, Object>) paths.get("/api/v1/metadata/apps");
        // the union keeps both methods of the same path, each from its owner
        assertThat(apps).containsKeys("get", "post");
        // the owning service rides every path item
        assertThat(apps).containsEntry("x-novaforge-service", "metadata-service");

        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) merged.get("components");
        @SuppressWarnings("unchecked")
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        assertThat(schemas).containsKeys("AppDefinition", "Record");

        assertThat((List<Object>) (List<?>) ((Map<?, ?>) merged.get("info")).get("x-novaforge-services"))
                .containsExactly("metadata-service", "data-runtime");
        assertThat((List<Object>) (List<?>) ((Map<?, ?>) merged.get("info")).get("x-novaforge-unavailable"))
                .isNull();
    }

    @Test
    @DisplayName("an unavailable upstream degrades audibly — the document still serves")
    void unavailableUpstreamDegrades() {
        var aggregator = new ApiDocsAggregator((upstream, auth) -> Optional.empty());
        Map<String, Object> merged = aggregator.aggregate(List.of(
                new ApiDocsAggregator.Upstream("metadata-service", "http://localhost:8081")), null);

        assertThat((Map<Object, Object>) (Map<?, ?>) merged.get("info"))
                .containsEntry("x-novaforge-unavailable", List.of("metadata-service"));
        assertThat((Map<?, ?>) merged.get("paths")).isEmpty();
    }

    @Test
    @DisplayName("a colliding path+method pair is surfaced, not silently overwritten")
    void collidingPathsSurface() {
        String colliding = """
                {"openapi":"3.1.0","paths":{"/api/v1/metadata/apps":{"get":{"operationId":"theirs"}}}}
                """;
        var aggregator = new ApiDocsAggregator((upstream, auth) -> Optional.of(
                "metadata-service".equals(upstream.name()) ? METADATA_DOC : colliding));
        Map<String, Object> merged = aggregator.aggregate(List.of(
                new ApiDocsAggregator.Upstream("metadata-service", "http://localhost:8081"),
                new ApiDocsAggregator.Upstream("other-service", "http://localhost:8099")), null);

        assertThat((List<Object>) (List<?>) ((Map<?, ?>) merged.get("info")).get("x-novaforge-conflicts"))
                .containsExactly("other-service:/api/v1/metadata/apps:get");
        @SuppressWarnings("unchecked")
        Map<String, Object> apps = (Map<String, Object>) ((Map<?, ?>) merged.get("paths"))
                .get("/api/v1/metadata/apps");
        @SuppressWarnings("unchecked")
        Map<String, Object> get = (Map<String, Object>) apps.get("get");
        assertThat(get).containsEntry("operationId", "listApps");
    }

    @Test
    @DisplayName("the authorization header relays to the fetch (docs sit behind the API gate)")
    void relaysAuthorization() {
        List<String> seen = new java.util.ArrayList<>();
        var aggregator = new ApiDocsAggregator((upstream, auth) -> {
            seen.add(auth);
            return Optional.of(METADATA_DOC);
        });
        aggregator.aggregate(List.of(
                new ApiDocsAggregator.Upstream("metadata-service", "http://localhost:8081")),
                "Bearer token");
        assertThat(seen).containsExactly("Bearer token");
    }
}
