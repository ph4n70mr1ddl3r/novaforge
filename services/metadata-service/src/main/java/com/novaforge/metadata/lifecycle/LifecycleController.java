package com.novaforge.metadata.lifecycle;

import com.novaforge.common.context.TenantContext;
import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.store.MetadataStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The lifecycle APIs (PHASE-8): environments, suite-gated promotion + audited
 * override, compatibility-scoped rollback, change sets, the hashed+signed promotion
 * artifact, headless suite runs (§5 — builder-gated like every design-time surface;
 * the pipeline client is a realm concern, ARCHITECTURE.md §7), the template catalog,
 * and the i18n workspaces. The builder review UI is a client of these APIs, never a
 * prerequisite.
 */
@RestController
@RequestMapping("/api/v1/metadata")
public class LifecycleController {

    private final LifecycleService lifecycle;
    private final com.novaforge.metadata.api.DefinitionService definitions;
    private final MetadataStore store;

    public LifecycleController(LifecycleService lifecycle,
                               com.novaforge.metadata.api.DefinitionService definitions,
                               MetadataStore store) {
        this.lifecycle = lifecycle;
        this.definitions = definitions;
        this.store = store;
    }

    // --- environments + promotion + rollback (§2/§4) ---

    @GetMapping("/apps/{appId}/environments")
    public List<Map<String, Object>> environments(@PathVariable UUID appId) {
        return lifecycle.environments(tenant(), appId);
    }

    public record PromoteRequest(Integer version, Boolean override, String reason) {
    }

    @PostMapping("/apps/{appId}/environments/{env}/promote")
    public Map<String, Object> promote(@PathVariable UUID appId, @PathVariable String env,
                                       @RequestBody PromoteRequest request,
                                       Authentication authentication) {
        if (request.version() == null) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "version is required — the candidate version being promoted (§4)");
        }
        return lifecycle.promote(tenant(), actor(authentication), appId, env,
                request.version(), Boolean.TRUE.equals(request.override()), request.reason(),
                isAdmin(authentication));
    }

    public record RollbackRequest(Integer toVersion, Boolean override, String reason,
                                  Boolean dataMigrationAcknowledged) {
    }

    @PostMapping("/apps/{appId}/environments/{env}/rollback")
    public Map<String, Object> rollback(@PathVariable UUID appId, @PathVariable String env,
                                        @RequestBody RollbackRequest request,
                                        Authentication authentication) {
        if (request.toVersion() == null) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "toVersion is required — the prior version being redeployed (§4 item 4)");
        }
        return lifecycle.rollback(tenant(), actor(authentication), appId, env,
                request.toVersion(), Boolean.TRUE.equals(request.override()), request.reason(),
                Boolean.TRUE.equals(request.dataMigrationAcknowledged()),
                isAdmin(authentication));
    }

    // --- change sets (§3) ---

    @GetMapping("/apps/{appId}/changeset")
    public Map<String, Object> changeSet(@PathVariable UUID appId,
                                         @RequestParam(defaultValue = "dev") String env) {
        return lifecycle.changeSet(tenant(), appId, env);
    }

    // --- the promotion artifact (§2) ---

    @GetMapping("/apps/{appId}/versions/{version}/artifact")
    public ResponseEntity<byte[]> exportArtifact(@PathVariable UUID appId,
                                                 @PathVariable int version) {
        byte[] artifact = lifecycle.exportArtifact(tenant(), appId, version);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"app-" + appId + "-v" + version + ".zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(artifact);
    }

    public record ImportArtifactRequest(String zipBase64, String apiName) {
    }

    @PostMapping("/artifacts/import")
    public AppDefinition importArtifact(@RequestBody ImportArtifactRequest request,
                                        Authentication authentication) {
        if (request.zipBase64() == null || request.zipBase64().isBlank()) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "zipBase64 is required — the exported artifact bytes");
        }
        byte[] zip;
        try {
            zip = Base64.getDecoder().decode(request.zipBase64());
        } catch (IllegalArgumentException e) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "zipBase64 is not valid base64");
        }
        return lifecycle.importArtifact(tenant(), actor(authentication), zip,
                request.apiName());
    }

    // --- headless suite runs (§5) ---

    public record SuiteRunRequest(List<String> suites) {
    }

    /** App-wide headless run: every suite (or the named subset), each recorded (§4). */
    @PostMapping("/apps/{appId}/suite-runs")
    public Map<String, Object> runSuites(@PathVariable UUID appId,
                                         @RequestBody(required = false) SuiteRunRequest request,
                                         Authentication authentication) {
        AppDefinition app = store.requireApp(tenant(), appId);
        List<String> names = request == null || request.suites() == null || request.suites().isEmpty()
                ? app.testSuites().stream().map(com.novaforge.metadata.TestSuiteDefinition::apiName).toList()
                : request.suites();
        if (names.isEmpty()) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "the app defines no suites — the gate is free by construction (ADR-010 #4)");
        }
        String batch = UUID.randomUUID().toString();
        List<Map<String, Object>> runs = new ArrayList<>();
        for (String name : names) {
            Map<String, Object> artifact = definitions.runSuite(tenant(), actor(authentication),
                    appId, name);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("suite", name);
            row.put("green", artifact.get("green"));
            row.put("runId", artifact.get("runId"));
            row.put("artifact", artifact);
            runs.add(row);
        }
        boolean green = runs.stream().allMatch(run -> Boolean.TRUE.equals(run.get("green")));
        return Map.of("batch", batch, "green", green, "runs", runs);
    }

    @GetMapping("/apps/{appId}/suite-runs")
    public List<Map<String, Object>> suiteRuns(@PathVariable UUID appId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MetadataStore.SuiteRunInfo run : store.suiteRuns(tenant(), appId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", run.id().toString());
            row.put("suite", run.suite());
            row.put("contentHash", run.contentHash());
            row.put("green", run.green());
            row.put("runAt", run.runAt().toString());
            row.put("artifact", run.artifact());
            rows.add(row);
        }
        return rows;
    }

    /** Single-suite headless run by suite row id (§5's pinned path). */
    @PostMapping("/suites/{suiteId}/runs")
    public Map<String, Object> runSuite(@PathVariable UUID suiteId,
                                        Authentication authentication) {
        MetadataStore.SuiteRow row = store.suiteRow(suiteId)
                .orElseThrow(() -> new PlatformException(PlatformErrorCode.NOT_FOUND,
                        "suite " + suiteId + " not found"));
        return definitions.runSuite(tenant(), actor(authentication), row.appId(), row.apiName());
    }

    @GetMapping("/suites/{suiteId}/runs")
    public List<Map<String, Object>> suiteRowRuns(@PathVariable UUID suiteId) {
        MetadataStore.SuiteRow row = store.suiteRow(suiteId)
                .orElseThrow(() -> new PlatformException(PlatformErrorCode.NOT_FOUND,
                        "suite " + suiteId + " not found"));
        return store.suiteRuns(tenant(), row.appId(), row.apiName()).stream()
                .map(run -> Map.<String, Object>of(
                        "id", run.id().toString(),
                        "green", run.green(),
                        "contentHash", String.valueOf(run.contentHash()),
                        "runAt", run.runAt().toString(),
                        "artifact", run.artifact()))
                .toList();
    }

    // --- templates (§6) ---

    public record RegisterTemplateRequest(UUID appId, Integer version, String name,
                                          String publisher, String description) {
    }

    @PostMapping("/templates")
    public Map<String, Object> registerTemplate(@RequestBody RegisterTemplateRequest request,
                                                Authentication authentication) {
        if (request.appId() == null || request.version() == null) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "registering a template pins the (appId, version) whose bundle ships");
        }
        UUID id = lifecycle.registerTemplate(tenant(), actor(authentication), request.appId(),
                request.version(), request.name(), request.publisher(), request.description());
        return Map.of("id", id.toString());
    }

    @GetMapping("/templates")
    public List<Map<String, Object>> templates() {
        return lifecycle.templates(tenant());
    }

    public record InstallTemplateRequest(String apiName) {
    }

    @PostMapping("/templates/{templateId}/install")
    public AppDefinition installTemplate(@PathVariable UUID templateId,
                                         @RequestBody(required = false) InstallTemplateRequest request,
                                         Authentication authentication) {
        return lifecycle.installTemplate(tenant(), actor(authentication), templateId,
                request == null ? null : request.apiName());
    }

    // --- i18n (§7) ---

    @GetMapping("/apps/{appId}/translations")
    public List<Map<String, Object>> translations(@PathVariable UUID appId) {
        return lifecycle.translations(tenant(), appId);
    }

    @PutMapping("/apps/{appId}/translations/{locale}")
    public ResponseEntity<Void> putTranslations(@PathVariable UUID appId,
                                                @PathVariable String locale,
                                                @RequestBody Map<String, String> entries,
                                                Authentication authentication) {
        lifecycle.putTranslations(tenant(), actor(authentication), appId, locale, entries);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/apps/{appId}/translations/{locale}/export")
    public ResponseEntity<String> exportTranslations(@PathVariable UUID appId,
                                                     @PathVariable String locale,
                                                     @RequestParam(defaultValue = "csv") String format) {
        String body = lifecycle.exportTranslations(tenant(), appId, locale, format);
        return ResponseEntity.ok()
                .contentType("json".equals(format) ? MediaType.APPLICATION_JSON
                        : MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"translations-" + locale + "."
                                + ("json".equals(format) ? "json" : "csv") + "\"")
                .body(body);
    }

    @PostMapping("/apps/{appId}/translations/{locale}/import")
    public ResponseEntity<Void> importTranslations(@PathVariable UUID appId,
                                                   @PathVariable String locale,
                                                   @RequestBody String body,
                                                   @RequestHeader(value = HttpHeaders.CONTENT_TYPE,
                                                           required = false) String contentType,
                                                   Authentication authentication) {
        lifecycle.importTranslations(tenant(), actor(authentication), appId, locale, body,
                contentType);
        return ResponseEntity.noContent().build();
    }

    // --- helpers ---

    private static UUID tenant() {
        return UUID.fromString(TenantContext.current()
                .orElseThrow(() -> new PlatformException(PlatformErrorCode.TENANT_MISSING,
                        "no tenant context bound (missing tenant_id claim?)")).tenantId());
    }

    private static UUID actor(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof
                org.springframework.security.oauth2.jwt.Jwt jwt && jwt.getSubject() != null) {
            return UUID.fromString(jwt.getSubject());
        }
        return UUID.fromString(TenantContext.current().orElseThrow().actorId());
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_admin".equals(authority.getAuthority()));
    }
}
