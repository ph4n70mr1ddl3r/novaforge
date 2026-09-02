package com.novaforge.metadata.lifecycle;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.metadata.EntityDefinition;
import com.novaforge.metadata.TranslationsDefinition;
import com.novaforge.metadata.api.DefinitionService;
import com.novaforge.metadata.events.MetadataPublishEventPublisher;
import com.novaforge.metadata.store.MetadataStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * The app lifecycle (PHASE-8): environments, suite-gated promotion with audited
 * overrides, compatibility-scoped rollback, the promotion artifact (a versioned ZIP
 * of definitions, content-hashed and signed), change-set review payloads, headless
 * suite runs, the template catalog, and the i18n translation workspaces.
 *
 * <p>Pins honored here: environments are the scratch mechanism grown up — one
 * provisioning path (§2); the gate is a mechanical content-hash match of recorded
 * green runs (§4 item 1); overrides are platform-admin-only, reason-recorded, and
 * render in change-set review forever (§4 item 3); rollback is automatic only when
 * storage-compatible, else admin override with an explicit data-migration
 * acknowledgment (§4 item 4); nothing is destroyed at publish — JSONB keeps removed
 * fields' values until a tenant-scoped prune (§4 item 5, PHASE-1 §4's rule).</p>
 */
@Service
public class LifecycleService {

    private final MetadataStore store;
    private final DefinitionService definitions;
    private final EnvironmentProvisioner provisioner;
    private final MetadataPublishEventPublisher events;
    private final String signingKey;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final org.springframework.transaction.support.TransactionTemplate deployTx;

    public LifecycleService(MetadataStore store, DefinitionService definitions,
                            EnvironmentProvisioner provisioner,
                            MetadataPublishEventPublisher events,
                            org.springframework.beans.factory.ObjectProvider<org.springframework.transaction.PlatformTransactionManager> transactions,
                            @Value("${novaforge.artifacts.signing-key:novaforge-dev-artifact-key}") String signingKey) {
        this.store = store;
        this.definitions = definitions;
        this.provisioner = provisioner;
        this.events = events;
        this.signingKey = signingKey;
        // The promotion tail (publish + outbox + pin) is one transaction: three
        // separate auto-commits left a crash between them able to skip the
        // metadata.published outbox row for the environment tenant's new version
        // entirely — its data runtime would hold stale cached bundles with no
        // eviction ever coming. Null only in the no-datasource smoke context.
        org.springframework.transaction.PlatformTransactionManager manager =
                transactions.getIfAvailable();
        this.deployTx = manager == null ? null
                : new org.springframework.transaction.support.TransactionTemplate(manager);
    }

    // --- environments (§2) ---

    public List<Map<String, Object>> environments(UUID tenantId, UUID appId) {
        return store.environments(tenantId, appId).stream().map(env -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("env", env.env());
            row.put("pinnedVersion", env.pinnedVersion());
            row.put("tenantId", env.envTenantId() == null ? null : env.envTenantId().toString());
            row.put("appId", env.envAppId() == null ? null : env.envAppId().toString());
            return row;
        }).toList();
    }

    // --- promotion gate + override + audit (§4) ---

    /**
     * Promotes version {@code version} to {@code staging|prod}. The gate: a recorded
     * green run of every app suite against exactly that version's content hash — free
     * when the app defines no suites (opt-in is authoring tests, ADR-010 #4). Order is
     * enforced (prod requires staging pinned to the same artifact) and the prod hop is
     * itself the explicit platform-admin approval (§11 Q1). An override skips the gate
     * only for a platform admin, with a recorded reason, audited forever.
     */
    public Map<String, Object> promote(UUID tenantId, UUID actorId, UUID appId, String env,
                                       int version, boolean override, String reason,
                                       boolean isAdmin) {
        requireEnvironment(env);
        AppDefinition bundle = requireVersion(tenantId, appId, version);
        if ("prod".equals(env) && !isAdmin) {
            throw new PlatformException(PlatformErrorCode.FORBIDDEN,
                    "the prod hop is the explicit platform-admin approval (PHASE-8 §4 item 2)");
        }
        Integer previousPin = store.environment(tenantId, appId, env)
                .map(MetadataStore.EnvironmentRow::pinnedVersion).orElse(null);
        if (previousPin != null && version < previousPin) {
            // An older version is a rollback: deploying it through promote would bypass
            // the rollback gate's storage-compatibility check and the data-migration
            // acknowledgment an incompatible redeployment owes (§4 item 4).
            throw new PlatformException(PlatformErrorCode.CONFLICT_VERSION,
                    "version " + version + " predates the pinned " + previousPin
                            + " — redeploying an older version is a rollback (§4 item 4), with its "
                            + "compatibility gate and data-migration acknowledgment");
        }
        if ("prod".equals(env)) {
            var staging = store.environment(tenantId, appId, "staging")
                    .orElseThrow(() -> new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                            "promote through staging first — dev → staging → prod, each hop gated (§4)"));
            String target = store.contentHashOf(tenantId, appId, version);
            String staged = store.contentHashOf(tenantId, appId, staging.pinnedVersion());
            if (!java.util.Objects.equals(target, staged)) {
                throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                        "staging is pinned to a different artifact — promote the same version through "
                                + "staging before prod (§4 item 2)");
            }
        }
        Map<String, Object> evidence = gateEvidence(tenantId, appId, bundle, version);
        boolean suitesGreen = Boolean.TRUE.equals(evidence.get("green"));
        if (!suitesGreen) {
            if (!override) {
                throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                        "promotion gate: not every app suite has a recorded green run against exactly "
                                + "version " + version + " (§4 item 1)",
                        new com.novaforge.common.error.ProblemErrors(List.of(),
                                List.of(new com.novaforge.common.error.ProblemErrors.GlobalError(
                                        "promotionGate", mapper.writeValueAsString(evidence)))));
            }
            requireOverride(isAdmin, reason);
            evidence.put("overridden", true);
            evidence.put("overrideReason", reason);
        }
        deployToEnvironment(tenantId, actorId, appId, env, version, bundle);
        store.recordPromotion(tenantId, appId, env, "promote", previousPin,
                version, !suitesGreen && override, reason, evidence, actorId);
        return evidence;
    }

    /**
     * Rollback (§4 item 4): redeploying a prior version through the same gate
     * machinery. Automatic only when the prior version's metadata is
     * storage-compatible with the current one — projection/field removals or type
     * changes block one-click rollback; incompatible rollbacks require admin override
     * with an explicit data-migration acknowledgment. The materializer handles the
     * compatible downgrade (columns drop lazily; nothing is destroyed at publish).
     * Moving the prod pin — like promoting to prod — is the explicit platform-admin
     * hop, whether the gate is green or not.
     */
    public Map<String, Object> rollback(UUID tenantId, UUID actorId, UUID appId, String env,
                                        int toVersion, boolean override, String reason,
                                        boolean dataMigrationAcknowledged, boolean isAdmin) {
        requireEnvironment(env);
        if ("prod".equals(env) && !isAdmin) {
            // The same hop promote enforces: moving prod's pin is the explicit
            // platform-admin approval — a builder rolling prod back to a green,
            // storage-compatible version otherwise skips the control entirely.
            throw new PlatformException(PlatformErrorCode.FORBIDDEN,
                    "a prod rollback is the explicit platform-admin approval (PHASE-8 §4 item 2)");
        }
        var current = store.environment(tenantId, appId, env)
                .orElseThrow(() -> new PlatformException(PlatformErrorCode.NOT_FOUND,
                        "environment " + env + " is not provisioned — nothing to roll back"));
        if (current.pinnedVersion() == toVersion) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "environment " + env + " is already pinned to version " + toVersion);
        }
        AppDefinition prior = requireVersion(tenantId, appId, toVersion);
        AppDefinition now = requireVersion(tenantId, appId, current.pinnedVersion());
        List<String> incompatible = DefinitionService.breakingChanges(now, prior);
        Map<String, Object> evidence = gateEvidence(tenantId, appId, prior, toVersion);
        boolean suitesGreen = Boolean.TRUE.equals(evidence.get("green"));
        boolean allowed = suitesGreen && incompatible.isEmpty();
        if (!allowed) {
            if (!override) {
                throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                        incompatible.isEmpty()
                                ? "rollback gate: no recorded green run of every suite against exactly "
                                        + "version " + toVersion + " — override to force (§4 item 4)"
                                : "rollback to version " + toVersion + " is storage-incompatible: "
                                        + String.join("; ", incompatible)
                                        + " — one-click rollback requires compatibility; an incompatible "
                                        + "rollback needs admin override + dataMigrationAcknowledged "
                                        + "(§4 item 4)",
                        new com.novaforge.common.error.ProblemErrors(List.of(),
                                List.of(new com.novaforge.common.error.ProblemErrors.GlobalError(
                                        "rollbackGate", mapper.writeValueAsString(Map.of(
                                                "incompatible", incompatible, "gate", evidence))))));
            }
            requireOverride(isAdmin, reason);
            if (!incompatible.isEmpty() && !dataMigrationAcknowledged) {
                throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                        "an incompatible rollback requires dataMigrationAcknowledged=true — projection "
                                + "columns the prior version lacks will drop lazily and their data "
                                + "leaves the queryable store (JSONB retains it until a tenant-scoped "
                                + "prune; §4 items 4–5)");
            }
            evidence.put("overridden", true);
            evidence.put("overrideReason", reason);
            evidence.put("incompatible", incompatible);
        }
        deployToEnvironment(tenantId, actorId, appId, env, toVersion, prior);
        store.recordPromotion(tenantId, appId, env, "rollback", current.pinnedVersion(),
                toVersion, !allowed && override, reason, evidence, actorId);
        return evidence;
    }

    /**
     * The gate evidence (§4 item 1): for every suite of the app at the target
     * version, the latest recorded run whose content hash is exactly the version's —
     * version identity is content, mechanically matched.
     */
    private Map<String, Object> gateEvidence(UUID tenantId, UUID appId, AppDefinition bundle,
                                             int version) {
        String hash = store.contentHashOf(tenantId, appId, version);
        List<Map<String, Object>> suites = new ArrayList<>();
        boolean green = true;
        for (var suite : bundle.testSuites()) {
            var latest = store.suiteRuns(tenantId, appId, suite.apiName()).stream()
                    .filter(run -> hash != null && hash.equals(run.contentHash()))
                    .findFirst();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("suite", suite.apiName());
            row.put("green", latest.map(MetadataStore.SuiteRunInfo::green).orElse(false));
            row.put("runAt", latest.map(run -> run.runAt().toString()).orElse(null));
            suites.add(row);
            green &= latest.map(MetadataStore.SuiteRunInfo::green).orElse(false);
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("version", version);
        evidence.put("contentHash", hash);
        evidence.put("suites", suites);
        evidence.put("green", bundle.testSuites().isEmpty() || green);
        return evidence;
    }

    private static void requireOverride(boolean isAdmin, String reason) {
        if (!isAdmin) {
            throw new PlatformException(PlatformErrorCode.FORBIDDEN,
                    "overrides are platform-admin only — reason recorded, audited, and rendered in "
                            + "change-set review forever (§4 item 3)");
        }
        if (reason == null || reason.isBlank()) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "an override requires a recorded reason (§4 item 3)");
        }
    }

    /**
     * Deploys the bundle into the environment (§2): the first promotion provisions the
     * environment's own tenant through the scratch mechanism; later promotions re-import
     * the bundle into that tenant's app and publish — the environment pins the dev
     * version and its data plane survives promotions.
     */
    private void deployToEnvironment(UUID tenantId, UUID actorId, UUID appId, String env,
                                     int version, AppDefinition bundle) {
        var existing = store.environment(tenantId, appId, env);
        if (existing.isEmpty() || existing.get().envTenantId() == null) {
            // The intent lands first (V12): a crash between here and completion is a
            // visible dangling intent, and the retry converges — provisioning is keyed
            // on (tenant, app, env) with deterministic names and adopt-before-create,
            // so no orphaned sandbox tenant can accumulate.
            store.recordProvisionIntent(tenantId, appId, env, version,
                    existing.map(MetadataStore.EnvironmentRow::provisionKey)
                            .orElse(UUID.randomUUID()), actorId);
            EnvironmentProvisioner.EnvironmentRef ref = provisioner.provision(tenantId,
                    bundle, env);
            store.completeProvision(tenantId, appId, env, ref.tenantId(), ref.appId(), actorId);
            return;
        }
        UUID envTenant = existing.get().envTenantId();
        UUID envApp = existing.get().envAppId() == null
                ? store.listApps(envTenant).stream()
                        .filter(app -> app.apiName().equals(bundle.apiName()))
                        .findFirst().map(AppDefinition::id).map(UUID::fromString)
                        .orElseThrow(() -> new PlatformException(PlatformErrorCode.INTERNAL,
                                "environment app missing for " + env))
                : existing.get().envAppId();
        int next = store.versions(envTenant, envApp).stream().findFirst()
                .map(MetadataStore.VersionInfo::version).orElse(0) + 1;
        store.updateApp(envTenant, actorId, envApp, bundle);
        if (deployTx != null) {
            int publishedVersion = next;
            deployTx.executeWithoutResult(tx -> deployTail(tenantId, appId, env, version,
                    envTenant, envApp, publishedVersion, bundle, actorId));
        } else {
            deployTail(tenantId, appId, env, version, envTenant, envApp, next, bundle, actorId);
        }
    }

    /** publish + outbox + pin — one unit whenever a transaction manager exists. */
    private void deployTail(UUID tenantId, UUID appId, String env, int version, UUID envTenant,
                            UUID envApp, int publishedVersion, AppDefinition bundle,
                            UUID actorId) {
        store.publish(envTenant, actorId, envApp, publishedVersion, bundle, List.of(), false);
        events.publishMetadataPublished(envTenant, envApp, publishedVersion, actorId,
                Instant.now());
        store.pinEnvironment(tenantId, appId, env, version, envTenant, envApp, actorId);
    }

    // --- change sets (§3) ---

    /**
     * The change set = the diff between an environment's pinned version (or an
     * explicit version) and the dev draft, with the review attachments: suite results
     * for exactly the draft's content, the script-ratio delta, the credential
     * references the target environment must re-bind (secrets never ride metadata),
     * and the promotion history — overrides render here forever.
     */
    public Map<String, Object> changeSet(UUID tenantId, UUID appId, String env) {
        AppDefinition draft = store.requireApp(tenantId, appId);
        AppDefinition published = publishedFor(tenantId, appId, env);
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("app", draft.apiName());
        review.put("env", env);
        Integer pinned = env == null || "dev".equals(env) ? null
                : store.environment(tenantId, appId, env)
                        .map(MetadataStore.EnvironmentRow::pinnedVersion).orElse(null);
        review.put("publishedVersion", pinned);
        Map<String, Object> diff = published == null
                ? diff(emptyBundle(draft.apiName()), draft)
                : diff(published, draft);
        review.put("diff", diff);
        String draftHash = LifecycleHash.contentHash(draft);
        List<Map<String, Object>> suiteResults = new ArrayList<>();
        for (var suite : draft.testSuites()) {
            var latest = store.suiteRuns(tenantId, appId, suite.apiName()).stream()
                    .filter(run -> draftHash.equals(run.contentHash())).findFirst();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("suite", suite.apiName());
            row.put("green", latest.map(MetadataStore.SuiteRunInfo::green).orElse(null));
            row.put("runAt", latest.map(run -> run.runAt().toString()).orElse(null));
            suiteResults.add(row);
        }
        review.put("suiteResults", suiteResults);
        review.put("scriptRatio", Map.of(
                "draft", scriptRatio(draft),
                "published", published == null ? 0 : scriptRatio(published),
                "modules", scriptRatioByModule(draft)));
        // §3: "any credential references stripped from the artifact ... listed for
        // re-binding in the target environment" — the union of what the target
        // currently binds and what the promoting draft references (a newly authored
        // connector's ref must list too, not only the ones already bound).
        Set<String> credentialRefs = new LinkedHashSet<>();
        if (published != null) {
            published.integrations().credentials().stream()
                    .map(com.novaforge.metadata.CredentialDefinition::id).forEach(credentialRefs::add);
        }
        draft.integrations().credentials().stream()
                .map(com.novaforge.metadata.CredentialDefinition::id).forEach(credentialRefs::add);
        review.put("credentialRefs", List.copyOf(credentialRefs));
        // §3: "the gap-log entries the version resolves (Phase 7 continuity)" —
        // entries whose disposition became resolving in this change set.
        review.put("resolvedGaps", resolvedGaps(published, draft));
        review.put("promotions", store.promotions(tenantId, appId).stream()
                .map(promotion -> Map.of(
                        "env", promotion.env(),
                        "kind", promotion.kind(),
                        "toVersion", promotion.toVersion(),
                        "overridden", promotion.overridden(),
                        "reason", String.valueOf(promotion.reason()),
                        "at", promotion.promotedAt().toString()))
                .toList());
        return review;
    }

    private AppDefinition publishedFor(UUID tenantId, UUID appId, String env) {
        if (env == null || "dev".equals(env)) {
            return store.latestPublished(tenantId, appId).map(MetadataStore.PublishedBundle::app)
                    .orElse(null);
        }
        var environment = store.environment(tenantId, appId, env).orElse(null);
        return environment == null ? null
                : store.exportVersion(tenantId, appId, environment.pinnedVersion()).orElse(null);
    }

    /** Structural per-definition diff: add/modify/remove by apiName, every branch. */
    private Map<String, Object> diff(AppDefinition from, AppDefinition to) {
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("entities", diffDocuments(
                from.entities().stream().map(e -> Map.entry(e.apiName(), (Object) e)).toList(),
                to.entities().stream().map(e -> Map.entry(e.apiName(), (Object) e)).toList()));
        diff.put("stateMachines", diffDocuments(names(from.stateMachines(), m -> ((com.novaforge.metadata.StateMachineDefinition) m).id()),
                names(to.stateMachines(), m -> ((com.novaforge.metadata.StateMachineDefinition) m).id())));
        diff.put("reports", diffDocuments(names(from.reports(), r -> ((com.novaforge.metadata.ReportDefinition) r).id()),
                names(to.reports(), r -> ((com.novaforge.metadata.ReportDefinition) r).id())));
        diff.put("testSuites", diffDocuments(names(from.testSuites(), s -> ((com.novaforge.metadata.TestSuiteDefinition) s).apiName()),
                names(to.testSuites(), s -> ((com.novaforge.metadata.TestSuiteDefinition) s).apiName())));
        diff.put("permissionSetChanged",
                !DefinitionParser.write(from.permissionSet()).equals(DefinitionParser.write(to.permissionSet())));
        diff.put("translations", diffDocuments(names(from.translations(), t -> ((TranslationsDefinition) t).locale()),
                names(to.translations(), t -> ((TranslationsDefinition) t).locale())));
        diff.put("gapLog", diffDocuments(names(from.gapLog(), g -> ((com.novaforge.metadata.GapLogEntry) g).id()),
                names(to.gapLog(), g -> ((com.novaforge.metadata.GapLogEntry) g).id())));
        return diff;
    }

    /**
     * The gap-log entries this change set resolves (PHASE-8 §3): draft entries whose
     * disposition is a resolving one (shipped as a platform feature, or closed) where
     * the published side's same entry — if present — was not yet resolving. New
     * resolving entries count; re-triaged existing ones count once.
     */
    private static List<Map<String, Object>> resolvedGaps(AppDefinition published, AppDefinition draft) {
        Map<String, com.novaforge.metadata.GapLogEntry> prior = published == null ? Map.of()
                : published.gapLog().stream().collect(java.util.stream.Collectors.toMap(
                        com.novaforge.metadata.GapLogEntry::id, gap -> gap, (a, b) -> a));
        return draft.gapLog().stream()
                .filter(gap -> com.novaforge.metadata.GapLogEntry.resolving(gap.disposition()))
                .filter(gap -> !(prior.containsKey(gap.id())
                        && com.novaforge.metadata.GapLogEntry.resolving(
                                prior.get(gap.id()).disposition())))
                .map(gap -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", gap.id());
                    row.put("area", gap.area());
                    row.put("disposition", gap.disposition());
                    if (gap.resolvedIn() != null) {
                        row.put("resolvedIn", gap.resolvedIn());
                    }
                    if (gap.proposed() != null) {
                        row.put("proposed", gap.proposed());
                    }
                    return row;
                })
                .toList();
    }

    private List<Map.Entry<String, Object>> names(List<?> documents, java.util.function.Function<Object, String> key) {
        return documents.stream().map(doc -> Map.entry(key.apply(doc), doc)).toList();
    }

    /** The empty baseline for a first change set (everything is "added"). */
    private static AppDefinition emptyBundle(String apiName) {
        return new AppDefinition(null, apiName, null, Map.of(), null, List.of(), List.of(),
                null, null, null, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), null, List.of(), List.of());
    }

    private Map<String, Object> diffDocuments(List<Map.Entry<String, Object>> from,
                                              List<Map.Entry<String, Object>> to) {
        Set<String> fromKeys = new LinkedHashSet<>();
        from.forEach(entry -> fromKeys.add(entry.getKey()));
        Set<String> toKeys = new LinkedHashSet<>();
        to.forEach(entry -> toKeys.add(entry.getKey()));
        List<String> added = toKeys.stream().filter(key -> !fromKeys.contains(key)).toList();
        List<String> removed = fromKeys.stream().filter(key -> !toKeys.contains(key)).toList();
        List<String> modified = to.stream()
                .filter(entry -> fromKeys.contains(entry.getKey()))
                .filter(entry -> from.stream().anyMatch(prev -> prev.getKey().equals(entry.getKey())
                        && !DefinitionParser.write(prev.getValue())
                                .equals(DefinitionParser.write(entry.getValue()))))
                .map(Map.Entry::getKey)
                .toList();
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("added", added);
        diff.put("modified", modified);
        diff.put("removed", removed);
        return diff;
    }

    static double scriptRatio(AppDefinition app) {
        List<com.novaforge.metadata.HookRule> hooks = app.entities().stream()
                .flatMap(entity -> entity.hooks().stream()).toList();
        if (hooks.isEmpty()) {
            return 0.0;
        }
        return (double) hooks.stream().filter(hook -> hook.script() != null).count() / hooks.size();
    }

    /**
     * The per-module breakdown (PHASE-7 §9 item 7: "script ratio reported per module
     * at exit review (≤ 20%, rule 3)") — hooks grouped by the entities' {@code module}
     * (the apiName when an entity carries none): each module reports its hook count,
     * its script count, and the script share. ADR-008 #5's KPI, made reportable at
     * the granularity rule 3 budgets — the change-set review is the exit surface.
     */
    static Map<String, Map<String, Object>> scriptRatioByModule(AppDefinition app) {
        Map<String, Map<String, Object>> modules = new LinkedHashMap<>();
        for (EntityDefinition entity : app.entities()) {
            List<com.novaforge.metadata.HookRule> hooks = entity.hooks();
            if (hooks.isEmpty()) {
                continue;
            }
            long scripts = hooks.stream().filter(hook -> hook.script() != null).count();
            String module = entity.module() == null || entity.module().isBlank()
                    ? entity.apiName() : entity.module();
            Map<String, Object> row = modules.computeIfAbsent(module, key -> new LinkedHashMap<>());
            long hooksSoFar = ((Number) row.getOrDefault("hooks", 0)).longValue();
            long scriptsSoFar = ((Number) row.getOrDefault("scripts", 0)).longValue();
            row.put("hooks", hooksSoFar + hooks.size());
            row.put("scripts", scriptsSoFar + scripts);
        }
        modules.values().forEach(row -> row.put("scriptShare",
                (double) ((Number) row.get("scripts")).longValue()
                        / ((Number) row.get("hooks")).longValue()));
        return modules;
    }

    // --- the promotion artifact (§2): versioned ZIP, hashed + signed ---

    public byte[] exportArtifact(UUID tenantId, UUID appId, int version) {
        AppDefinition bundle = requireVersion(tenantId, appId, version);
        String definitions = DefinitionParser.writeApp(bundle);
        String manifest = mapper.writeValueAsString(Map.of(
                "format", "novaforge-app",
                "formatVersion", 1,
                "apiName", bundle.apiName(),
                "appVersion", version,
                "definitionsSha256", LifecycleHash.sha256(definitions),
                "createdAt", Instant.now().toString()));
        String signature = LifecycleHash.hmacSha256(signingKey, manifest);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                writeEntry(zip, "manifest.json", manifest);
                writeEntry(zip, "definitions.json", definitions);
                writeEntry(zip, "signature.txt", signature);
            }
            return bytes.toByteArray();
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL, "artifact export failed");
        }
    }

    /**
     * Import verifies hash + signature, then creates a new draft app from the bundle.
     * {@code apiName} optionally renames on import (the artifact's name may already be
     * taken in the target workspace — §2's artifact moves between environments).
     */
    public AppDefinition importArtifact(UUID tenantId, UUID actorId, byte[] artifact,
                                        String apiNameOverride) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(artifact))) {
            String manifest = null;
            String definitionsJson = null;
            String signature = null;
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.endsWith("/") || name.contains("..")) {
                    continue;   // no path escapes, no directory noise
                }
                String content = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                switch (name) {
                    case "manifest.json" -> manifest = content;
                    case "definitions.json" -> definitionsJson = content;
                    case "signature.txt" -> signature = content;
                    default -> throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                            "unknown artifact entry: " + name);
                }
            }
            if (manifest == null || definitionsJson == null || signature == null) {
                throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                        "artifact requires manifest.json, definitions.json, signature.txt");
            }
            Map<String, Object> parsed = mapper.readValue(manifest, Map.class);
            String declared = String.valueOf(parsed.get("definitionsSha256"));
            if (!declared.equals(LifecycleHash.sha256(definitionsJson))) {
                throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                        "artifact definitions do not match the manifest hash — refusing import");
            }
            if (!signature.equals(LifecycleHash.hmacSha256(signingKey, manifest))) {
                throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                        "artifact signature verification failed — refusing import");
            }
            AppDefinition bundle = DefinitionParser.parseApp(definitionsJson);
            String finalName = apiNameOverride == null || apiNameOverride.isBlank()
                    ? bundle.apiName() : apiNameOverride;
            return definitions.createApp(tenantId, actorId,
                    new AppDefinition(null, finalName, bundle.label(), bundle.labelI18n(),
                            bundle.description(), bundle.entities(), bundle.pages(), bundle.settings(),
                            bundle.permissionSet(), bundle.testSuites(), bundle.stateMachines(),
                            bundle.slas(), bundle.jobs(), bundle.workflows(), bundle.reports(),
                            bundle.dashboards(), bundle.integrations(), bundle.translations(),
                            bundle.gapLog()));
        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "artifact is not a readable novaforge-app ZIP: " + e.getMessage(), null, e);
        }
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    // --- templates (§6) ---

    /** Registers a published version as a template — definitions + fixtures only. */
    public UUID registerTemplate(UUID tenantId, UUID actorId, UUID appId, int version,
                                 String name, String publisher, String description) {
        AppDefinition bundle = requireVersion(tenantId, appId, version);
        String templateName = name == null || name.isBlank() ? bundle.apiName() : name;
        return store.insertTemplate(tenantId, templateName,
                publisher == null ? "NovaForge" : publisher, description,
                String.valueOf(version), bundle, actorId);
    }

    public List<Map<String, Object>> templates(UUID tenantId) {
        return store.templates(tenantId).stream().map(template -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", template.id().toString());
            row.put("name", template.name());
            row.put("publisher", template.publisher());
            row.put("description", template.description());
            row.put("version", template.version());
            return row;
        }).toList();
    }

    /** Installs a template as a new draft app (§6): import creates a new app in draft. */
    public AppDefinition installTemplate(UUID tenantId, UUID actorId, UUID templateId,
                                         String apiName) {
        AppDefinition bundle = store.templateBundle(tenantId, templateId)
                .orElseThrow(() -> new PlatformException(PlatformErrorCode.NOT_FOUND,
                        "template " + templateId + " not found"));
        boolean taken = store.listApps(tenantId).stream()
                .anyMatch(app -> app.apiName().equals(apiName == null ? bundle.apiName() : apiName));
        if (taken) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "apiName already exists in this workspace: " + apiName);
        }
        String finalName = apiName == null ? bundle.apiName() : apiName;
        return definitions.createApp(tenantId, actorId, new AppDefinition(null, finalName,
                bundle.label(), bundle.labelI18n(), bundle.description(), bundle.entities(),
                bundle.pages(), bundle.settings(), bundle.permissionSet(), bundle.testSuites(),
                bundle.stateMachines(), bundle.slas(), bundle.jobs(), bundle.workflows(),
                bundle.reports(), bundle.dashboards(), bundle.integrations(), bundle.translations(),
                bundle.gapLog()));
    }

    // --- i18n workspaces (§7) ---

    /** Per-locale state + the missing-translation report. */
    public List<Map<String, Object>> translations(UUID tenantId, UUID appId) {
        AppDefinition app = store.requireApp(tenantId, appId);
        List<Map<String, Object>> locales = new ArrayList<>();
        for (TranslationsDefinition translations : app.translations()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("locale", translations.locale());
            row.put("entries", translations.entries());
            row.put("missing", TranslationsDefinition.missing(translations, app));
            locales.add(row);
        }
        if (locales.isEmpty()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("locale", null);
            row.put("entries", Map.of());
            row.put("missing", TranslationsDefinition.missing(null, app));
            locales.add(row);
        }
        return locales;
    }

    /** Upserts one locale workspace; keys must address real translatable slots. */
    public void putTranslations(UUID tenantId, UUID actorId, UUID appId, String locale,
                                Map<String, String> entries) {
        if (locale == null || !TranslationsDefinition.LOCALE.matcher(locale).matches()) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "locale must be a BCP-47-ish tag (ll or ll-CC): " + locale);
        }
        AppDefinition app = store.requireApp(tenantId, appId);
        Set<String> translatable = new LinkedHashSet<>(TranslationsDefinition.translatableKeys(app));
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            if (!translatable.contains(entry.getKey())) {
                throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                        "translation key does not address a translatable slot: " + entry.getKey()
                                + " (slots: <Entity>.<field>.label, <Entity>.label, report.<id>.label, "
                                + "app.label)");
            }
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                        "translation values must not be blank: " + entry.getKey());
            }
        }
        store.putTranslation(tenantId, actorId, appId, locale, entries);
    }

    /** CSV/JSON export for translators (§7): {@code key,value} rows, RFC 4180 quoting. */
    public String exportTranslations(UUID tenantId, UUID appId, String locale, String format) {
        AppDefinition app = store.requireApp(tenantId, appId);
        TranslationsDefinition translations = app.translations(locale).orElse(new TranslationsDefinition(locale, Map.of()));
        if ("json".equals(format)) {
            return mapper.writeValueAsString(translations.entries());
        }
        StringBuilder csv = new StringBuilder("key,value\n");
        for (String key : TranslationsDefinition.translatableKeys(app)) {
            String value = translations.entries().getOrDefault(key, "");
            csv.append('"').append(key.replace("\"", "\"\"")).append("\",\"")
                    .append(value.replace("\"", "\"\"")).append("\"\n");
        }
        return csv.toString();
    }

    /** Import merges (never wipes): parsed CSV/JSON entries land in the locale workspace. */
    public void importTranslations(UUID tenantId, UUID actorId, UUID appId, String locale,
                                   String body, String contentType) {
        Map<String, String> entries = new LinkedHashMap<>();
        if (body != null && body.stripLeading().startsWith("{")) {
            Map<String, Object> parsed = mapper.readValue(body, Map.class);
            parsed.forEach((key, value) -> entries.put(key, String.valueOf(value)));
        } else {
            boolean first = true;
            for (String line : body.split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                if (first && line.startsWith("key,")) {
                    first = false;
                    continue;
                }
                first = false;
                List<String> fields = parseCsvLine(line);
                if (fields.size() != 2) {
                    throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                            "CSV rows must be exactly key,value — got: " + line);
                }
                entries.put(fields.get(0), fields.get(1));
            }
        }
        AppDefinition app = store.requireApp(tenantId, appId);
        Map<String, String> merged = new LinkedHashMap<>();
        app.translations(locale).ifPresent(translations -> merged.putAll(translations.entries()));
        merged.putAll(entries);
        putTranslations(tenantId, actorId, appId, locale, merged);
    }

    private static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else if (c != '\r') {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    // --- helpers ---

    private static void requireEnvironment(String env) {
        if (!"staging".equals(env) && !"prod".equals(env)) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "named environments are staging and prod — dev is the draft workspace (§2)");
        }
    }

    private AppDefinition requireVersion(UUID tenantId, UUID appId, int version) {
        return store.exportVersion(tenantId, appId, version)
                .orElseThrow(() -> new PlatformException(PlatformErrorCode.NOT_FOUND,
                        "version " + version + " of app " + appId + " not found"));
    }
}
