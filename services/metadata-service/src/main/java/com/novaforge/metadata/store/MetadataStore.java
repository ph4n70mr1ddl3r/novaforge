package com.novaforge.metadata.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.metadata.EntityDefinition;
import com.novaforge.metadata.PermissionSet;
import com.novaforge.metadata.SequenceDefinition;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Draft workspace + version persistence (PHASE-1 §4): drafts are mutable rows, publishes
 * are immutable snapshots. Every query is tenant-scoped by the bound TenantContext —
 * the tenant never comes from client input.
 */
@Repository
public class MetadataStore {

    public static final String KIND_SEQUENCE = "sequence";

    private final JdbcTemplate jdbc;

    public MetadataStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // --- apps (draft) ---

    @Transactional
    public AppDefinition insertApp(UUID tenantId, UUID actorId, AppDefinition app) {
        UUID appId = app.id() == null ? UUID.randomUUID() : UUID.fromString(app.id());
        jdbc.update("""
                INSERT INTO md_apps (id, tenant_id, api_name, label, label_i18n, description, permission_set, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?)""",
                appId, tenantId, app.apiName(), app.label(),
                DefinitionParser.write(app.labelI18n()), app.description(),
                app.permissionSet() == null ? "{}" : DefinitionParser.write(app.permissionSet()),
                actorId, actorId);
        insertChildren(tenantId, actorId, appId, app);
        return withIds(appId, app);
    }

    public List<AppDefinition> listApps(UUID tenantId) {
        return jdbc.query("""
                SELECT id, api_name, label, label_i18n, description, permission_set, current_version
                FROM md_apps WHERE tenant_id = ? ORDER BY api_name""",
                (rs, i) -> {
                    UUID appId = rs.getObject("id", UUID.class);
                    return assembleApp(tenantId, appId,
                            rs.getString("api_name"), rs.getString("label"),
                            rs.getString("label_i18n"), rs.getString("description"),
                            rs.getString("permission_set"), rs.getInt("current_version"));
                }, tenantId);
    }

    /** Owning tenant of an app — the service-caller path (no tenant claim to derive from). */
    public Optional<UUID> tenantOfApp(UUID appId) {
        return jdbc.query("SELECT tenant_id FROM md_apps WHERE id = ?",
                (rs, i) -> rs.getObject("tenant_id", UUID.class), appId).stream().findFirst();
    }

    /** Every (tenant, app) pair with a draft workspace — the service-caller index base. */
    public java.util.List<UUID[]> allTenantAppIds() {
        return jdbc.query("SELECT tenant_id, id FROM md_apps", (rs, i) ->
                new UUID[] {rs.getObject("tenant_id", UUID.class), rs.getObject("id", UUID.class)});
    }

    public Optional<AppDefinition> findApp(UUID tenantId, UUID appId) {
        return jdbc.query("""
                SELECT api_name, label, label_i18n, description, permission_set, current_version
                FROM md_apps WHERE tenant_id = ? AND id = ?""",
                (rs, i) -> assembleApp(tenantId, appId,
                        rs.getString("api_name"), rs.getString("label"),
                        rs.getString("label_i18n"), rs.getString("description"),
                        rs.getString("permission_set"), rs.getInt("current_version")),
                tenantId, appId).stream().findFirst();
    }

    public AppDefinition requireApp(UUID tenantId, UUID appId) {
        return findApp(tenantId, appId).orElseThrow(() ->
                new PlatformException(PlatformErrorCode.NOT_FOUND,
                        "app " + appId + " not found"));
    }

    @Transactional
    public AppDefinition updateApp(UUID tenantId, UUID actorId, UUID appId, AppDefinition draft) {
        int updated = jdbc.update("""
                UPDATE md_apps
                   SET label = ?, label_i18n = ?::jsonb, description = ?, permission_set = ?::jsonb,
                       updated_at = now(), updated_by = ?
                 WHERE tenant_id = ? AND id = ?""",
                draft.label(), DefinitionParser.write(draft.labelI18n()), draft.description(),
                draft.permissionSet() == null ? "{}" : DefinitionParser.write(draft.permissionSet()),
                actorId, tenantId, appId);
        if (updated == 0) {
            throw new PlatformException(PlatformErrorCode.NOT_FOUND, "app " + appId + " not found");
        }
        replaceChildren(tenantId, actorId, appId, draft);
        return requireApp(tenantId, appId);
    }

    @Transactional
    public void deleteApp(UUID tenantId, UUID appId) {
        int deleted = jdbc.update("DELETE FROM md_apps WHERE tenant_id = ? AND id = ?",
                tenantId, appId);
        if (deleted == 0) {
            throw new PlatformException(PlatformErrorCode.NOT_FOUND, "app " + appId + " not found");
        }
    }

    // --- entities (draft) ---

    @Transactional
    public AppDefinition putEntity(UUID tenantId, UUID actorId, UUID appId, EntityDefinition entity) {
        requireApp(tenantId, appId);
        UUID entityId = entity.id() == null ? UUID.randomUUID() : UUID.fromString(entity.id());
        jdbc.update("""
                INSERT INTO md_entities (id, tenant_id, app_id, api_name, document, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (tenant_id, app_id, api_name) DO UPDATE
                   SET document = EXCLUDED.document, updated_at = now(), updated_by = EXCLUDED.updated_by""",
                entityId, tenantId, appId, entity.apiName(),
                DefinitionParser.write(entity), actorId, actorId);
        return requireApp(tenantId, appId);
    }

    @Transactional
    public AppDefinition patchEntity(UUID tenantId, UUID actorId, UUID appId, String entityApiName,
                                     EntityDefinition patch) {
        requireApp(tenantId, appId);
        EntityDefinition current = requireApp(tenantId, appId)
                .entity(entityApiName)
                .orElseThrow(() -> new PlatformException(PlatformErrorCode.NOT_FOUND,
                        "entity " + entityApiName + " not found"));
        EntityDefinition merged = new EntityDefinition(
                current.id(),
                patch.apiName() != null ? patch.apiName() : current.apiName(),
                patch.label() != null ? patch.label() : current.label(),
                patch.labelI18n().isEmpty() ? current.labelI18n() : patch.labelI18n(),
                patch.displayField() != null ? patch.displayField() : current.displayField(),
                patch.module() != null ? patch.module() : current.module(),
                patch.fields().isEmpty() ? current.fields() : patch.fields(),
                patch.relationships().isEmpty() ? current.relationships() : patch.relationships(),
                patch.validations().isEmpty() ? current.validations() : patch.validations(),
                patch.indexes().isEmpty() ? current.indexes() : patch.indexes());
        int updated = jdbc.update("""
                UPDATE md_entities
                   SET api_name = ?, document = ?::jsonb, updated_at = now(), updated_by = ?
                 WHERE tenant_id = ? AND app_id = ? AND api_name = ?""",
                merged.apiName(), DefinitionParser.write(merged), actorId,
                tenantId, appId, entityApiName);
        if (updated == 0) {
            throw new PlatformException(PlatformErrorCode.NOT_FOUND,
                    "entity " + entityApiName + " not found");
        }
        return requireApp(tenantId, appId);
    }

    @Transactional
    public AppDefinition deleteEntity(UUID tenantId, UUID appId, String entityApiName) {
        int deleted = jdbc.update("""
                DELETE FROM md_entities WHERE tenant_id = ? AND app_id = ? AND api_name = ?""",
                tenantId, appId, entityApiName);
        if (deleted == 0) {
            throw new PlatformException(PlatformErrorCode.NOT_FOUND,
                    "entity " + entityApiName + " not found");
        }
        return requireApp(tenantId, appId);
    }

    // --- versions ---

    public record VersionInfo(int version, Instant publishedAt, List<String> breakingChanges,
                              boolean acknowledged) {
    }

    public record PublishedBundle(int version, AppDefinition app) {
    }

    public Optional<PublishedBundle> latestPublished(UUID tenantId, UUID appId) {
        return jdbc.query("""
                SELECT version, bundle FROM md_versions
                 WHERE tenant_id = ? AND app_id = ?
                 ORDER BY version DESC LIMIT 1""",
                (rs, i) -> new PublishedBundle(rs.getInt("version"),
                        DefinitionParser.parseApp(rs.getString("bundle"))),
                tenantId, appId).stream().findFirst();
    }

    public List<VersionInfo> versions(UUID tenantId, UUID appId) {
        return jdbc.query("""
                SELECT version, published_at, breaking_changes, acknowledged FROM md_versions
                 WHERE tenant_id = ? AND app_id = ? ORDER BY version DESC""",
                (rs, i) -> new VersionInfo(rs.getInt("version"),
                        rs.getTimestamp("published_at").toInstant(),
                        parseStrings(rs.getString("breaking_changes")),
                        rs.getBoolean("acknowledged")),
                tenantId, appId);
    }

    public Optional<AppDefinition> exportVersion(UUID tenantId, UUID appId, int version) {
        return jdbc.query("""
                SELECT bundle FROM md_versions
                 WHERE tenant_id = ? AND app_id = ? AND version = ?""",
                (rs, i) -> DefinitionParser.parseApp(rs.getString("bundle")),
                tenantId, appId, version).stream().findFirst();
    }

    @Transactional
    public VersionInfo publish(UUID tenantId, UUID actorId, UUID appId, int version,
                               AppDefinition bundle, List<String> breakingChanges,
                               boolean acknowledged) {
        jdbc.update("""
                INSERT INTO md_versions (id, tenant_id, app_id, version, bundle, breaking_changes, acknowledged, published_by)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)""",
                UUID.randomUUID(), tenantId, appId, version, DefinitionParser.writeApp(bundle),
                DefinitionParser.write(breakingChanges), acknowledged, actorId);
        jdbc.update("UPDATE md_apps SET current_version = ?, updated_at = now(), updated_by = ? WHERE tenant_id = ? AND id = ?",
                version, actorId, tenantId, appId);
        return new VersionInfo(version, Instant.now(), breakingChanges, acknowledged);
    }

    // --- helpers ---

    private void insertChildren(UUID tenantId, UUID actorId, UUID appId, AppDefinition app) {
        for (EntityDefinition entity : app.entities()) {
            jdbc.update("""
                    INSERT INTO md_entities (id, tenant_id, app_id, api_name, document, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)""",
                    entity.id() == null ? UUID.randomUUID() : UUID.fromString(entity.id()),
                    tenantId, appId, entity.apiName(), DefinitionParser.write(entity),
                    actorId, actorId);
        }
        for (AppDefinition.PageDefinition page : app.pages()) {
            jdbc.update("""
                    INSERT INTO md_pages (id, tenant_id, app_id, api_name, document, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)""",
                    page.id() == null ? UUID.randomUUID() : UUID.fromString(page.id()),
                    tenantId, appId, page.apiName(), DefinitionParser.write(page), actorId, actorId);
        }
        for (SequenceDefinition sequence : app.settings().sequences()) {
            jdbc.update("""
                    INSERT INTO md_settings (id, tenant_id, app_id, kind, api_name, document, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)""",
                    UUID.randomUUID(), tenantId, appId, KIND_SEQUENCE, sequence.apiName(),
                    DefinitionParser.write(sequence), actorId, actorId);
        }
    }

    private void replaceChildren(UUID tenantId, UUID actorId, UUID appId, AppDefinition draft) {
        jdbc.update("DELETE FROM md_settings WHERE tenant_id = ? AND app_id = ?", tenantId, appId);
        for (SequenceDefinition sequence : draft.settings().sequences()) {
            jdbc.update("""
                    INSERT INTO md_settings (id, tenant_id, app_id, kind, api_name, document, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)""",
                    UUID.randomUUID(), tenantId, appId, KIND_SEQUENCE, sequence.apiName(),
                    DefinitionParser.write(sequence), actorId, actorId);
        }
    }

    private AppDefinition assembleApp(UUID tenantId, UUID appId, String apiName, String label,
                                      String labelI18nJson, String description, String permissionSetJson,
                                      int currentVersion) {
        List<EntityDefinition> entities = jdbc.query(
                "SELECT document FROM md_entities WHERE tenant_id = ? AND app_id = ? ORDER BY api_name",
                (rs, i) -> DefinitionParser.parse(rs.getString("document"), EntityDefinition.class),
                tenantId, appId);
        List<AppDefinition.PageDefinition> pages = jdbc.query(
                "SELECT document FROM md_pages WHERE tenant_id = ? AND app_id = ? ORDER BY api_name",
                (rs, i) -> DefinitionParser.parse(rs.getString("document"), AppDefinition.PageDefinition.class),
                tenantId, appId);
        List<SequenceDefinition> sequences = jdbc.query(
                "SELECT document FROM md_settings WHERE tenant_id = ? AND app_id = ? AND kind = ? ORDER BY api_name",
                (rs, i) -> DefinitionParser.parse(rs.getString("document"), SequenceDefinition.class),
                tenantId, appId, KIND_SEQUENCE);
        PermissionSet permissionSet = (permissionSetJson == null || permissionSetJson.isBlank()
                || permissionSetJson.equals("{}"))
                ? new PermissionSet(null, null, null)
                : DefinitionParser.parse(permissionSetJson, PermissionSet.class);
        return new AppDefinition(appId.toString(), apiName, label,
                DefinitionParser.parse(labelI18nJson == null ? "{}" : labelI18nJson, Map.class),
                description, entities, pages,
                new AppDefinition.SettingsDefinition(sequences, null, null), permissionSet);
    }

    @SuppressWarnings("unchecked")
    private AppDefinition withIds(UUID appId, AppDefinition app) {
        return new AppDefinition(appId.toString(), app.apiName(), app.label(), app.labelI18n(),
                app.description(), app.entities(), app.pages(), app.settings());
    }

    private static List<String> parseStrings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return DefinitionParser.parse(json, ArrayList.class);
    }
}
