package com.novaforge.metadata.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.metadata.EntityDefinition;
import com.novaforge.metadata.PermissionSet;
import com.novaforge.metadata.SequenceDefinition;
import com.novaforge.metadata.TestSuiteDefinition;
import com.novaforge.metadata.lifecycle.LifecycleHash;
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

    /** The app-definition branches persisting as kind-discriminated documents (PHASE-4). */
    public static final String KIND_STATE_MACHINE = "state_machine";
    public static final String KIND_SLA = "sla";
    public static final String KIND_SCHEDULED_JOB = "scheduled_job";
    public static final String KIND_WORKFLOW = "workflow";

    /** The reporting branches (PHASE-5 §3/§5) — same document pattern. */
    public static final String KIND_REPORT = "report";
    public static final String KIND_DASHBOARD = "dashboard";

    /** The Integration branches (PHASE-6 §2) — connectors, webhooks, credentials, imports. */
    public static final String KIND_CONNECTOR = "connector";
    public static final String KIND_WEBHOOK = "webhook";
    public static final String KIND_CREDENTIAL = "credential";
    public static final String KIND_IMPORT = "import_mapping";

    /** The Translations branch (PHASE-8 §7) — one kind row per locale. */
    public static final String KIND_TRANSLATION = "translation";

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

    /**
     * The app's apiName — the service-caller index must carry it (the Scheduler's and
     * the Reporting Service's job/definition sources key off `apiName`; a missing
     * field synced jobs with a null app — caught live by the PHASE-5 §7 demo).
     */
    public String apiNameOf(UUID tenantId, UUID appId) {
        return jdbc.query("SELECT api_name FROM md_apps WHERE tenant_id = ? AND id = ?",
                (rs, i) -> rs.getString(1), tenantId, appId).stream().findFirst().orElse(null);
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
                patch.freezeOnTerminal() != null ? patch.freezeOnTerminal() : current.freezeOnTerminal(),
                patch.periodLock() != null ? patch.periodLock() : current.periodLock(),
                patch.fields().isEmpty() ? current.fields() : patch.fields(),
                patch.relationships().isEmpty() ? current.relationships() : patch.relationships(),
                patch.validations().isEmpty() ? current.validations() : patch.validations(),
                patch.hooks().isEmpty() ? current.hooks() : patch.hooks(),
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

    // --- test suites (ADR-010) ---

    public AppDefinition putTestSuite(UUID tenantId, UUID actorId, UUID appId,
                                      TestSuiteDefinition suite) {
        requireApp(tenantId, appId);
        jdbc.update("""
                INSERT INTO md_test_suites (id, tenant_id, app_id, api_name, document, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (tenant_id, app_id, api_name) DO UPDATE
                   SET document = EXCLUDED.document, updated_at = now(), updated_by = EXCLUDED.updated_by""",
                UUID.randomUUID(), tenantId, appId, suite.apiName(),
                DefinitionParser.write(suite), actorId, actorId);
        return requireApp(tenantId, appId);
    }

    // --- pages (draft — the PHASE-2 §4 authoring surface; persisted from Phase 2,
    // replacing the create-only seeding of insertChildren) ---

    /**
     * Upsert with optimistic locking (PHASE-2 §8): an incoming {@code revision}
     * must match the stored row's — the builder's 409 → rebase prompt. First
     * saves (no revision) insert freely. The stored document carries the new
     * revision so reads round-trip the token.
     */
    @Transactional
    public AppDefinition putPage(UUID tenantId, UUID actorId, UUID appId,
                                 AppDefinition.PageDefinition page) {
        requireApp(tenantId, appId);
        Integer current = jdbc.query("""
                SELECT revision FROM md_pages WHERE tenant_id = ? AND app_id = ? AND api_name = ?""",
                (rs, i) -> rs.getInt(1), tenantId, appId, page.apiName()).stream().findFirst().orElse(null);
        if (current != null && page.revision() != null && !current.equals(page.revision())) {
            throw new PlatformException(PlatformErrorCode.CONFLICT_VERSION,
                    "page " + page.apiName() + " was modified by another editor (revision "
                            + current + ", yours " + page.revision() + ") — rebase and retry");
        }
        UUID pageId = page.id() == null ? UUID.randomUUID() : UUID.fromString(page.id());
        int revision = current == null ? 1 : current + 1;
        AppDefinition.PageDefinition stamped = new AppDefinition.PageDefinition(page.id(),
                page.apiName(), page.label(), page.labelI18n(), page.type(), page.entity(),
                page.layout(), revision);
        jdbc.update("""
                INSERT INTO md_pages (id, tenant_id, app_id, api_name, document, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (tenant_id, app_id, api_name) DO UPDATE
                   SET document = EXCLUDED.document, updated_by = EXCLUDED.updated_by""",
                pageId, tenantId, appId, page.apiName(),
                DefinitionParser.write(stamped), actorId, actorId);
        return requireApp(tenantId, appId);
    }

    @Transactional
    public AppDefinition deletePage(UUID tenantId, UUID appId, String apiName) {
        int deleted = jdbc.update("""
                DELETE FROM md_pages WHERE tenant_id = ? AND app_id = ? AND api_name = ?""",
                tenantId, appId, apiName);
        if (deleted == 0) {
            throw new PlatformException(PlatformErrorCode.NOT_FOUND,
                    "page " + apiName + " not found");
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
                INSERT INTO md_versions (id, tenant_id, app_id, version, bundle, breaking_changes, acknowledged, content_hash, published_by)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)""",
                UUID.randomUUID(), tenantId, appId, version, DefinitionParser.writeApp(bundle),
                DefinitionParser.write(breakingChanges), acknowledged,
                LifecycleHash.contentHash(bundle), actorId);
        jdbc.update("UPDATE md_apps SET current_version = ?, updated_at = now(), updated_by = ? WHERE tenant_id = ? AND id = ?",
                version, actorId, tenantId, appId);
        return new VersionInfo(version, Instant.now(), breakingChanges, acknowledged);
    }

    // --- suite runs (PHASE-8 §4/§5): version-bound by content hash ---

    /** Records one suite-run artifact; the hash binds it to the exact candidate (§4 item 1). */
    public void recordSuiteRun(UUID tenantId, UUID appId, String suite, String contentHash,
                               boolean green, Map<String, Object> artifact, UUID runBy) {
        jdbc.update("""
                INSERT INTO md_suite_runs (id, tenant_id, app_id, suite, content_hash, green, artifact, run_by)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)""",
                UUID.randomUUID(), tenantId, appId, suite, contentHash, green,
                DefinitionParser.write(artifact), runBy);
    }

    public record SuiteRunInfo(UUID id, String suite, String contentHash, boolean green,
                               Instant runAt, Map<String, Object> artifact) {
    }

    /** The recorded runs for one (app, suite) — latest first. */
    public List<SuiteRunInfo> suiteRuns(UUID tenantId, UUID appId, String suite) {
        return jdbc.query("""
                SELECT id, suite, content_hash, green, run_at, artifact FROM md_suite_runs
                 WHERE tenant_id = ? AND app_id = ? AND suite = ?
                 ORDER BY run_at DESC""",
                (rs, i) -> new SuiteRunInfo(rs.getObject("id", UUID.class), rs.getString("suite"),
                        rs.getString("content_hash"), rs.getBoolean("green"),
                        rs.getTimestamp("run_at").toInstant(),
                        DefinitionParser.parse(rs.getString("artifact"), Map.class)),
                tenantId, appId, suite);
    }

    public List<SuiteRunInfo> suiteRuns(UUID tenantId, UUID appId) {
        return jdbc.query("""
                SELECT id, suite, content_hash, green, run_at, artifact FROM md_suite_runs
                 WHERE tenant_id = ? AND app_id = ?
                 ORDER BY run_at DESC""",
                (rs, i) -> new SuiteRunInfo(rs.getObject("id", UUID.class), rs.getString("suite"),
                        rs.getString("content_hash"), rs.getBoolean("green"),
                        rs.getTimestamp("run_at").toInstant(),
                        DefinitionParser.parse(rs.getString("artifact"), Map.class)),
                tenantId, appId);
    }

    /** The content hash of a published version — the gate's version identity (§4). */
    public String contentHashOf(UUID tenantId, UUID appId, int version) {
        return jdbc.query("SELECT content_hash FROM md_versions WHERE tenant_id = ? AND app_id = ? AND version = ?",
                (rs, i) -> rs.getString(1), tenantId, appId, version).stream().findFirst().orElse(null);
    }

    /** The suite row's UUID — the headless single-suite handle (§5). */
    public Optional<UUID> suiteRowId(UUID tenantId, UUID appId, String suiteApiName) {
        return jdbc.query("SELECT id FROM md_test_suites WHERE tenant_id = ? AND app_id = ? AND api_name = ?",
                (rs, i) -> rs.getObject(1, UUID.class), tenantId, appId, suiteApiName).stream().findFirst();
    }

    public record SuiteRow(UUID appId, String apiName) {
    }

    /** Resolves a headless suite handle to its owning (tenant, app, name). */
    public Optional<SuiteRow> suiteRow(UUID suiteRowId) {
        return jdbc.query("SELECT app_id, api_name FROM md_test_suites WHERE id = ?",
                (rs, i) -> new SuiteRow(rs.getObject("app_id", UUID.class), rs.getString("api_name")),
                suiteRowId).stream().findFirst();
    }

    // --- environments + promotions (PHASE-8 §2/§4) ---

    public record EnvironmentRow(UUID appId, String env, int pinnedVersion,
                                 UUID envTenantId, UUID envAppId) {
    }

    public Optional<EnvironmentRow> environment(UUID tenantId, UUID appId, String env) {
        return jdbc.query("""
                SELECT app_id, env, pinned_version, env_tenant_id, env_app_id FROM md_environments
                 WHERE tenant_id = ? AND app_id = ? AND env = ?""",
                (rs, i) -> new EnvironmentRow(rs.getObject("app_id", UUID.class), rs.getString("env"),
                        rs.getInt("pinned_version"), rs.getObject("env_tenant_id", UUID.class),
                        rs.getObject("env_app_id", UUID.class)),
                tenantId, appId, env).stream().findFirst();
    }

    public List<EnvironmentRow> environments(UUID tenantId, UUID appId) {
        return jdbc.query("""
                SELECT app_id, env, pinned_version, env_tenant_id, env_app_id FROM md_environments
                 WHERE tenant_id = ? AND app_id = ? ORDER BY env""",
                (rs, i) -> new EnvironmentRow(rs.getObject("app_id", UUID.class), rs.getString("env"),
                        rs.getInt("pinned_version"), rs.getObject("env_tenant_id", UUID.class),
                        rs.getObject("env_app_id", UUID.class)),
                tenantId, appId);
    }

    @Transactional
    public void pinEnvironment(UUID tenantId, UUID appId, String env, int version,
                               UUID envTenantId, UUID envAppId, UUID actorId) {
        jdbc.update("""
                INSERT INTO md_environments (id, tenant_id, app_id, env, pinned_version, env_tenant_id, env_app_id, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, app_id, env) DO UPDATE
                   SET pinned_version = EXCLUDED.pinned_version,
                       env_tenant_id = EXCLUDED.env_tenant_id,
                       env_app_id = EXCLUDED.env_app_id,
                       updated_at = now(), updated_by = EXCLUDED.updated_by""",
                UUID.randomUUID(), tenantId, appId, env, version, envTenantId, envAppId,
                actorId, actorId);
    }

    public record PromotionRow(UUID id, String env, String kind, Integer fromVersion, int toVersion,
                               boolean overridden, String reason, Instant promotedAt, UUID promotedBy) {
    }

    @Transactional
    public void recordPromotion(UUID tenantId, UUID appId, String env, String kind,
                                Integer fromVersion, int toVersion, boolean overridden,
                                String reason, Map<String, Object> gateEvidence, UUID actorId) {
        jdbc.update("""
                INSERT INTO md_promotions (id, tenant_id, app_id, env, kind, from_version, to_version, overridden, reason, gate_evidence, promoted_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)""",
                UUID.randomUUID(), tenantId, appId, env, kind, fromVersion, toVersion,
                overridden, reason, DefinitionParser.write(gateEvidence), actorId);
    }

    public List<PromotionRow> promotions(UUID tenantId, UUID appId) {
        return jdbc.query("""
                SELECT id, env, kind, from_version, to_version, overridden, reason, promoted_at, promoted_by
                  FROM md_promotions WHERE tenant_id = ? AND app_id = ?
                 ORDER BY promoted_at DESC""",
                (rs, i) -> new PromotionRow(rs.getObject("id", UUID.class), rs.getString("env"),
                        rs.getString("kind"), rs.getObject("from_version", Integer.class),
                        rs.getInt("to_version"), rs.getBoolean("overridden"), rs.getString("reason"),
                        rs.getTimestamp("promoted_at").toInstant(),
                        rs.getObject("promoted_by", UUID.class)),
                tenantId, appId);
    }

    // --- templates (PHASE-8 §6) ---

    public record TemplateRow(UUID id, String name, String publisher, String description,
                              String version, String contentHash) {
    }

    @Transactional
    public UUID insertTemplate(UUID tenantId, String name, String publisher, String description,
                               String version, AppDefinition bundle, UUID actorId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO md_templates (id, tenant_id, name, publisher, description, version, bundle, content_hash, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)""",
                id, tenantId, name, publisher, description, version,
                DefinitionParser.writeApp(bundle), LifecycleHash.contentHash(bundle), actorId);
        return id;
    }

    public List<TemplateRow> templates(UUID tenantId) {
        return jdbc.query("""
                SELECT id, name, publisher, description, version, content_hash FROM md_templates
                 WHERE tenant_id = ? ORDER BY name, version""",
                (rs, i) -> new TemplateRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("publisher"), rs.getString("description"),
                        rs.getString("version"), rs.getString("content_hash")),
                tenantId);
    }

    public Optional<AppDefinition> templateBundle(UUID tenantId, UUID templateId) {
        return jdbc.query("SELECT bundle FROM md_templates WHERE tenant_id = ? AND id = ?",
                (rs, i) -> DefinitionParser.parseApp(rs.getString("bundle")),
                tenantId, templateId).stream().findFirst();
    }

    /** Upserts one locale workspace (PHASE-8 §7) — a kind-discriminated document row. */
    @Transactional
    public void putTranslation(UUID tenantId, UUID actorId, UUID appId, String locale,
                               Map<String, String> entries) {
        requireApp(tenantId, appId);
        jdbc.update("""
                INSERT INTO md_definitions (id, tenant_id, app_id, kind, api_name, document, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (tenant_id, app_id, kind, api_name) DO UPDATE
                   SET document = EXCLUDED.document, updated_at = now(), updated_by = EXCLUDED.updated_by""",
                UUID.randomUUID(), tenantId, appId, KIND_TRANSLATION, locale,
                DefinitionParser.write(new com.novaforge.metadata.TranslationsDefinition(locale, entries)),
                actorId, actorId);
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
        for (TestSuiteDefinition suite : app.testSuites()) {
            jdbc.update("""
                    INSERT INTO md_test_suites (id, tenant_id, app_id, api_name, document, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)""",
                    UUID.randomUUID(), tenantId, appId, suite.apiName(),
                    DefinitionParser.write(suite), actorId, actorId);
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
        insertBranches(tenantId, actorId, appId, app);
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
        replaceBranches(tenantId, actorId, appId, draft);
    }

    /**
     * The app-definition branches (PHASE-4 §3/§6/§7/§9) persist as
     * kind-discriminated documents — the md_pages pattern — so drafts and published
     * bundles round-trip every branch their consumers read (the runtime's
     * state-machine enforcement, the Workflow Service's SLA/workflow sources, the
     * Scheduler's jobs).
     */
    private void insertBranches(UUID tenantId, UUID actorId, UUID appId, AppDefinition app) {
        for (com.novaforge.metadata.StateMachineDefinition machine : app.stateMachines()) {
            insertBranch(tenantId, actorId, appId, KIND_STATE_MACHINE, machine.id(), machine);
        }
        for (com.novaforge.metadata.SlaDefinition sla : app.slas()) {
            insertBranch(tenantId, actorId, appId, KIND_SLA, sla.id(), sla);
        }
        for (com.novaforge.metadata.ScheduledJobDefinition job : app.jobs()) {
            insertBranch(tenantId, actorId, appId, KIND_SCHEDULED_JOB, job.name(), job);
        }
        for (com.novaforge.metadata.WorkflowDefinition workflow : app.workflows()) {
            insertBranch(tenantId, actorId, appId, KIND_WORKFLOW, workflow.id(), workflow);
        }
        for (com.novaforge.metadata.ReportDefinition report : app.reports()) {
            insertBranch(tenantId, actorId, appId, KIND_REPORT, report.id(), report);
        }
        for (com.novaforge.metadata.DashboardDefinition dashboard : app.dashboards()) {
            insertBranch(tenantId, actorId, appId, KIND_DASHBOARD, dashboard.id(), dashboard);
        }
        for (com.novaforge.metadata.ConnectorDefinition connector : app.integrations().connectors()) {
            insertBranch(tenantId, actorId, appId, KIND_CONNECTOR, connector.id(), connector);
        }
        for (com.novaforge.metadata.WebhookDefinition webhook : app.integrations().webhooks()) {
            insertBranch(tenantId, actorId, appId, KIND_WEBHOOK, webhook.id(), webhook);
        }
        for (com.novaforge.metadata.CredentialDefinition credential : app.integrations().credentials()) {
            insertBranch(tenantId, actorId, appId, KIND_CREDENTIAL, credential.id(), credential);
        }
        for (com.novaforge.metadata.ImportDefinition mapping : app.integrations().imports()) {
            insertBranch(tenantId, actorId, appId, KIND_IMPORT, mapping.apiName(), mapping);
        }
        for (com.novaforge.metadata.TranslationsDefinition translations : app.translations()) {
            insertBranch(tenantId, actorId, appId, KIND_TRANSLATION, translations.locale(), translations);
        }
    }

    private void insertBranch(UUID tenantId, UUID actorId, UUID appId, String kind,
                              String apiName, Object definition) {
        jdbc.update("""
                INSERT INTO md_definitions (id, tenant_id, app_id, kind, api_name, document, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)""",
                UUID.randomUUID(), tenantId, appId, kind, apiName,
                DefinitionParser.write(definition), actorId, actorId);
    }

    private void replaceBranches(UUID tenantId, UUID actorId, UUID appId, AppDefinition draft) {
        jdbc.update("DELETE FROM md_definitions WHERE tenant_id = ? AND app_id = ?",
                tenantId, appId);
        insertBranches(tenantId, actorId, appId, draft);
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
        List<TestSuiteDefinition> suites = jdbc.query(
                "SELECT document FROM md_test_suites WHERE tenant_id = ? AND app_id = ? ORDER BY api_name",
                (rs, i) -> DefinitionParser.parse(rs.getString("document"), TestSuiteDefinition.class),
                tenantId, appId);
        List<SequenceDefinition> sequences = jdbc.query(
                "SELECT document FROM md_settings WHERE tenant_id = ? AND app_id = ? AND kind = ? ORDER BY api_name",
                (rs, i) -> DefinitionParser.parse(rs.getString("document"), SequenceDefinition.class),
                tenantId, appId, KIND_SEQUENCE);
        PermissionSet permissionSet = (permissionSetJson == null || permissionSetJson.isBlank()
                || permissionSetJson.equals("{}"))
                ? new PermissionSet(null, null, null)
                : DefinitionParser.parse(permissionSetJson, PermissionSet.class);
        List<com.novaforge.metadata.StateMachineDefinition> stateMachines =
                branchDocuments(tenantId, appId, KIND_STATE_MACHINE,
                        com.novaforge.metadata.StateMachineDefinition.class);
        List<com.novaforge.metadata.SlaDefinition> slas = branchDocuments(tenantId, appId,
                KIND_SLA, com.novaforge.metadata.SlaDefinition.class);
        List<com.novaforge.metadata.ScheduledJobDefinition> jobs = branchDocuments(tenantId,
                appId, KIND_SCHEDULED_JOB, com.novaforge.metadata.ScheduledJobDefinition.class);
        List<com.novaforge.metadata.WorkflowDefinition> workflows = branchDocuments(tenantId,
                appId, KIND_WORKFLOW, com.novaforge.metadata.WorkflowDefinition.class);
        List<com.novaforge.metadata.ReportDefinition> reports = branchDocuments(tenantId,
                appId, KIND_REPORT, com.novaforge.metadata.ReportDefinition.class);
        List<com.novaforge.metadata.DashboardDefinition> dashboards = branchDocuments(tenantId,
                appId, KIND_DASHBOARD, com.novaforge.metadata.DashboardDefinition.class);
        com.novaforge.metadata.IntegrationsDefinition integrations =
                new com.novaforge.metadata.IntegrationsDefinition(
                        branchDocuments(tenantId, appId, KIND_CONNECTOR,
                                com.novaforge.metadata.ConnectorDefinition.class),
                        branchDocuments(tenantId, appId, KIND_WEBHOOK,
                                com.novaforge.metadata.WebhookDefinition.class),
                        branchDocuments(tenantId, appId, KIND_CREDENTIAL,
                                com.novaforge.metadata.CredentialDefinition.class),
                        branchDocuments(tenantId, appId, KIND_IMPORT,
                                com.novaforge.metadata.ImportDefinition.class));
        List<com.novaforge.metadata.TranslationsDefinition> translations = branchDocuments(
                tenantId, appId, KIND_TRANSLATION,
                com.novaforge.metadata.TranslationsDefinition.class);
        return new AppDefinition(appId.toString(), apiName, label,
                DefinitionParser.parse(labelI18nJson == null ? "{}" : labelI18nJson, Map.class),
                description, entities, pages,
                new AppDefinition.SettingsDefinition(sequences, null, null), permissionSet,
                suites, stateMachines, slas, jobs, workflows, reports, dashboards, integrations,
                translations);
    }

    private <T> List<T> branchDocuments(UUID tenantId, UUID appId, String kind, Class<T> type) {
        return jdbc.query("""
                        SELECT document FROM md_definitions
                         WHERE tenant_id = ? AND app_id = ? AND kind = ? ORDER BY api_name""",
                (rs, i) -> DefinitionParser.parse(rs.getString("document"), type),
                tenantId, appId, kind);
    }

    @SuppressWarnings("unchecked")
    private AppDefinition withIds(UUID appId, AppDefinition app) {
        return new AppDefinition(appId.toString(), app.apiName(), app.label(), app.labelI18n(),
                app.description(), app.entities(), app.pages(), app.settings(),
                app.permissionSet(), app.testSuites(), app.stateMachines(), app.slas(),
                app.jobs(), app.workflows(), app.reports(), app.dashboards(),
                app.integrations(), app.translations());
    }

    private static List<String> parseStrings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return DefinitionParser.parse(json, ArrayList.class);
    }
}
