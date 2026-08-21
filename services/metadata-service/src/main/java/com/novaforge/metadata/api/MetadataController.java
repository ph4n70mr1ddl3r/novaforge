package com.novaforge.metadata.api;

import com.novaforge.common.context.TenantContext;
import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.EntityDefinition;
import com.novaforge.metadata.store.MetadataStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Definition APIs (PHASE-1 §4): draft workspace CRUD, publish, versions/export, and the
 * published runtime read. Draft CRUD + publish are {@code builder}-scoped; the published
 * read serves any authenticated tenant user — enforced in {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/v1/metadata")
public class MetadataController {

    private final DefinitionService definitions;

    public MetadataController(DefinitionService definitions) {
        this.definitions = definitions;
    }

    // --- apps ---

    @PostMapping("/apps")
    public AppDefinition createApp(@RequestBody AppDefinition draft) {
        var ctx = requireContext();
        return definitions.createApp(UUID.fromString(ctx.tenantId()), UUID.fromString(ctx.actorId()), draft);
    }

    @GetMapping("/apps")
    public List<AppDefinition> listApps() {
        return definitions.listApps(UUID.fromString(requireContext().tenantId()));
    }

    @GetMapping("/apps/{appId}")
    public AppDefinition getApp(@PathVariable UUID appId) {
        return definitions.getApp(UUID.fromString(requireContext().tenantId()), appId);
    }

    @PatchMapping("/apps/{appId}")
    public AppDefinition updateApp(@PathVariable UUID appId, @RequestBody AppDefinition patch) {
        var ctx = requireContext();
        return definitions.updateApp(UUID.fromString(ctx.tenantId()), UUID.fromString(ctx.actorId()),
                appId, patch);
    }

    @DeleteMapping("/apps/{appId}")
    public ResponseEntity<Void> deleteApp(@PathVariable UUID appId) {
        definitions.deleteApp(UUID.fromString(requireContext().tenantId()), appId);
        return ResponseEntity.noContent().build();
    }

    // --- entities (as one document per ARCHITECTURE.md §3) ---

    @PostMapping("/apps/{appId}/entities")
    public AppDefinition putEntity(@PathVariable UUID appId, @RequestBody EntityDefinition entity) {
        var ctx = requireContext();
        return definitions.putEntity(UUID.fromString(ctx.tenantId()), UUID.fromString(ctx.actorId()),
                appId, entity);
    }

    @PatchMapping("/apps/{appId}/entities/{entityApiName}")
    public AppDefinition patchEntity(@PathVariable UUID appId, @PathVariable String entityApiName,
                                     @RequestBody EntityDefinition patch) {
        var ctx = requireContext();
        return definitions.patchEntity(UUID.fromString(ctx.tenantId()), UUID.fromString(ctx.actorId()),
                appId, entityApiName, patch);
    }

    @DeleteMapping("/apps/{appId}/entities/{entityApiName}")
    public AppDefinition deleteEntity(@PathVariable UUID appId, @PathVariable String entityApiName) {
        return definitions.deleteEntity(UUID.fromString(requireContext().tenantId()), appId, entityApiName);
    }

    // --- publish + versions ---

    @PostMapping("/apps/{appId}/publish")
    public MetadataStore.VersionInfo publish(@PathVariable UUID appId,
                                             @RequestBody(required = false) PublishRequest request) {
        var ctx = requireContext();
        return definitions.publish(UUID.fromString(ctx.tenantId()), UUID.fromString(ctx.actorId()),
                appId, request != null && Boolean.TRUE.equals(request.acknowledgeDataImpact()));
    }

    public record PublishRequest(Boolean acknowledgeDataImpact) {
    }

    @GetMapping("/apps/{appId}/versions")
    public List<MetadataStore.VersionInfo> versions(@PathVariable UUID appId) {
        return definitions.versions(UUID.fromString(requireContext().tenantId()), appId);
    }

    @GetMapping("/apps/{appId}/versions/{version}/export")
    public AppDefinition exportVersion(@PathVariable UUID appId, @PathVariable int version) {
        return definitions.exportVersion(UUID.fromString(requireContext().tenantId()), appId, version);
    }

    /** The runtime read path for rendering: bundle + version for cache keys (§4). */
    @GetMapping("/apps/{appId}/published")
    public Map<String, Object> published(@PathVariable UUID appId) {
        MetadataStore.PublishedBundle bundle =
                definitions.published(UUID.fromString(requireContext().tenantId()), appId);
        return Map.of("version", bundle.version(), "app", bundle.app());
    }

    /**
     * The published-apps index the Data Runtime's entity resolver consumes: every app
     * with at least one published version, carrying the current version for
     * version-keyed caching (PHASE-1 §4/§5).
     */
    @GetMapping("/published-apps")
    public List<Map<String, Object>> publishedApps() {
        String tenantId = requireContext().tenantId();
        return definitions.listApps(UUID.fromString(tenantId)).stream()
                .filter(app -> app.id() != null)
                .map(app -> Map.entry(app,
                        definitions.versions(UUID.fromString(tenantId), UUID.fromString(app.id()))))
                .filter(entry -> !entry.getValue().isEmpty())
                .map(entry -> Map.<String, Object>of(
                        "appId", entry.getKey().id(),
                        "apiName", entry.getKey().apiName(),
                        "version", entry.getValue().getFirst().version()))
                .toList();
    }

    private static TenantContext.Context requireContext() {
        return TenantContext.current().orElseThrow(() ->
                new PlatformException(PlatformErrorCode.TENANT_MISSING,
                        "no tenant context bound (missing tenant_id claim?)"));
    }
}
