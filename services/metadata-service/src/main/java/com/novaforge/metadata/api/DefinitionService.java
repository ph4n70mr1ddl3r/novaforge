package com.novaforge.metadata.api;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.common.error.ProblemErrors;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionValidator;
import com.novaforge.metadata.EntityDefinition;
import com.novaforge.expression.Expression;
import com.novaforge.expression.ExpressionException;
import com.novaforge.metadata.events.MetadataPublishEventPublisher;
import com.novaforge.metadata.harness.TestRunner;
import com.novaforge.metadata.harness.TestRunner;
import com.novaforge.metadata.store.MetadataStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Definition lifecycle: save validation (§3 rules), draft CRUD, publish with
 * compatibility check + versioning, version reads (PHASE-1 §4).
 */
@Service
public class DefinitionService {

    /** The page-model action ladder (PHASE-2 §4; runFlow activates with PHASE-3 §8). */
    private static final java.util.Set<String> CLOSED_PAGE_ACTIONS =
            java.util.Set.of("save", "cancel", "delete", "openPage", "runFlow");

    private final MetadataStore store;
    private final MetadataPublishEventPublisher events;
    private final TestRunner testRunner;

    public DefinitionService(MetadataStore store, MetadataPublishEventPublisher events,
                             TestRunner testRunner) {
        this.store = store;
        this.events = events;
        this.testRunner = testRunner;
    }

    public AppDefinition createApp(UUID tenantId, UUID actorId, AppDefinition draft) {
        ProblemErrors errors = DefinitionValidator.validate(draft);
        compileCheckExpressions(draft, errors, false);
        FlowCompiler.compile(draft);
        if (!errors.isEmpty()) {
            throw validationFailure("app definition failed save validation", errors);
        }
        return store.insertApp(tenantId, actorId, draft);
    }

    public List<AppDefinition> listApps(UUID tenantId) {
        return store.listApps(tenantId);
    }

    public AppDefinition getApp(UUID tenantId, UUID appId) {
        return store.requireApp(tenantId, appId);
    }

    public AppDefinition updateApp(UUID tenantId, UUID actorId, UUID appId, AppPatch patch) {
        AppDefinition current = store.requireApp(tenantId, appId);
        // the presence-preserving merge (AppPatch): null keeps a branch, an explicit
        // empty list clears it, a non-empty list replaces it — the last item of a
        // list branch became removable through the API
        AppDefinition merged = patch.mergeOver(current);
        ProblemErrors errors = DefinitionValidator.validate(merged);
        if (!errors.isEmpty()) {
            throw validationFailure("app update failed save validation", errors);
        }
        return store.updateApp(tenantId, actorId, appId, merged);
    }

    public void deleteApp(UUID tenantId, UUID appId) {
        store.deleteApp(tenantId, appId);
    }

    public AppDefinition putEntity(UUID tenantId, UUID actorId, UUID appId, EntityDefinition entity) {
        AppDefinition current = store.requireApp(tenantId, appId);
        List<EntityDefinition> entities = new ArrayList<>(current.entities().stream()
                .filter(e -> !e.apiName().equals(entity.apiName())).toList());
        entities.add(entity);
        AppDefinition candidate = withEntities(current, entities);
        ProblemErrors errors = DefinitionValidator.validate(candidate);
        compileCheckExpressions(candidate, errors, false);
        if (!errors.isEmpty()) {
            throw validationFailure("entity definition failed save validation", errors);
        }
        return store.putEntity(tenantId, actorId, appId, entity);
    }

    public AppDefinition patchEntity(UUID tenantId, UUID actorId, UUID appId, String entityApiName,
                                     EntityDefinition patch) {
        AppDefinition current = store.requireApp(tenantId, appId);
        List<EntityDefinition> entities = current.entities().stream()
                .map(e -> e.apiName().equals(entityApiName) ? mergeEntity(e, patch) : e)
                .toList();
        ProblemErrors errors = DefinitionValidator.validate(withEntities(current, entities));
        if (!errors.isEmpty()) {
            throw validationFailure("entity update failed save validation", errors);
        }
        return store.patchEntity(tenantId, actorId, appId, entityApiName, mergeEntity(
                current.entity(entityApiName).orElseThrow(), patch));
    }

    public AppDefinition deleteEntity(UUID tenantId, UUID appId, String entityApiName) {
        // The post-delete draft must still validate: entities are referenced by pages,
        // state machines, reports, permission-set branches, webhook/import mappings, and
        // lookup targets — deleting one that is referenced left the draft failing
        // validation with no way to publish (or even re-save) until every referencing
        // definition was hand-repaired. The candidate check names them, at the door.
        AppDefinition current = store.requireApp(tenantId, appId);
        if (current.entity(entityApiName).isEmpty()) {
            throw new PlatformException(PlatformErrorCode.NOT_FOUND,
                    "entity " + entityApiName + " not found");
        }
        List<EntityDefinition> remaining = current.entities().stream()
                .filter(e -> !e.apiName().equals(entityApiName)).toList();
        ProblemErrors errors = DefinitionValidator.validate(withEntities(current, remaining));
        if (!errors.isEmpty()) {
            throw validationFailure("entity delete failed save validation — referencing "
                    + "definitions must be removed first", errors);
        }
        return store.deleteEntity(tenantId, appId, entityApiName);
    }

    public AppDefinition putTestSuite(UUID tenantId, UUID actorId, UUID appId,
                                      com.novaforge.metadata.TestSuiteDefinition suite) {
        store.requireApp(tenantId, appId);
        validateSuite(suite);
        return store.putTestSuite(tenantId, actorId, appId, suite);
    }

    /**
     * Saves one page definition (PHASE-2 §4/§8 — pages are versioned metadata riding
     * the same definition path as entities; the L2 overlay content is the author's,
     * the service checks the slot contract: identity, entity reference, and the
     * expression slots inside the layout compile against the entity's fields).
     */
    public AppDefinition putPage(UUID tenantId, UUID actorId, UUID appId, String apiName,
                                 AppDefinition.PageDefinition page) {
        AppDefinition current = store.requireApp(tenantId, appId);
        AppDefinition.PageDefinition normalized = new AppDefinition.PageDefinition(
                page.id(), apiName, page.label(), page.labelI18n(), page.type(),
                page.entity(), page.layout(), page.revision());
        ProblemErrors errors = DefinitionValidator.validate(withPage(current, normalized));
        List<ProblemErrors.FieldError> found = new ArrayList<>(errors.errors());
        validatePageSlots(normalized, current, found);
        if (!found.isEmpty()) {
            throw validationFailure("page definition failed save validation",
                    new ProblemErrors(found, List.of()));
        }
        return store.putPage(tenantId, actorId, appId, normalized);
    }

    public AppDefinition deletePage(UUID tenantId, UUID appId, String apiName) {
        return store.deletePage(tenantId, appId, apiName);
    }

    private static AppDefinition withPage(AppDefinition app, AppDefinition.PageDefinition page) {
        List<AppDefinition.PageDefinition> pages = new ArrayList<>(app.pages().stream()
                .filter(p -> !p.apiName().equals(page.apiName())).toList());
        pages.add(page);
        return new AppDefinition(app.id(), app.apiName(), app.label(), app.labelI18n(),
                app.description(), app.entities(), List.copyOf(pages), app.settings(),
                app.permissionSet(), app.testSuites(), app.stateMachines(), app.slas(),
                app.jobs(), app.workflows(), app.reports(), app.dashboards(),
                app.integrations(), app.translations(), app.gapLog());
    }

    /**
     * Page slot contract (PHASE-2 §4/§7): the entity must exist, every
     * visibility/required/readonly binding in the layout tree compiles against the
     * entity's field apiNames (UI bindings are UX sugar, but they must at least
     * parse and resolve), the §4 bind rules hold — where the bound name repeats
     * in widget config ({@code props.field}/{@code props.relationship}) a mismatch
     * rejects, a binding-taking component carries a bind, and a bind resolves to a
     * field or relationship on the page's entity — and the §4 catalog contract
     * holds: the component id is known, its version pin matches the catalog, and
     * the props validate against the component's props JSON Schema. All are the
     * same rules the TS twin enforces in the builder, mirrored here so the
     * metadata store can never hold a page no catalog component renders per
     * contract no matter which client authored it. Save mode resolves a missing
     * version pin to the catalog's current stable (the twin's save rule); publish
     * mode rejects it — §4: "a missing {@code version} resolves to the catalog's
     * current stable but is rejected at publish".
     */
    @SuppressWarnings("unchecked")
    private static void validatePageSlots(AppDefinition.PageDefinition page, AppDefinition app,
                                          List<ProblemErrors.FieldError> errors) {
        String entityApiName = page.entity();
        if (entityApiName == null) {
            return;
        }
        EntityDefinition entity = app.entity(entityApiName).orElse(null);
        if (entity == null) {
            errors.add(new ProblemErrors.FieldError(page.apiName() + ".entity",
                    "page entity " + entityApiName + " not found", entityApiName));
            return;
        }
        java.util.Set<String> fields = new java.util.LinkedHashSet<>();
        entity.fields().forEach(f -> fields.add(f.apiName()));
        entity.relationships().forEach(r -> fields.add(r.apiName()));
        checkNodeSlots(page.apiName(), page.layout(), fields,
                com.novaforge.metadata.ExpressionTypes.of(entity), errors, false);
    }

    /**
     * Encoding-agnostic slot walk: the persisted layout is structural deltas (§13
     * Q2) but the export/interchange form and builder previews carry resolved trees
     * — every visibility/required/readonly string anywhere in the document
     * compile-checks, whichever encoding authored it, and every component node
     * (any map carrying a catalog {@code type} — a resolved-tree node, or the node
     * inside an {@code insertNode} delta) passes the §4 bind checks and the §4
     * catalog contract. {@code publish} switches the version-pin rule: save
     * resolves a missing pin, publish rejects it.
     */
    private static void checkNodeSlots(String pageApiName, Object node, java.util.Set<String> fields,
                                       java.util.function.Function<String, Expression.ValueType> types,
                                       List<ProblemErrors.FieldError> errors, boolean publish) {
        if (node instanceof java.util.Map<?, ?> map) {
            checkNodeBinds(pageApiName, map, fields, errors);
            checkNodeCatalog(pageApiName, map, publish, errors);
            for (String slot : java.util.List.of("visibility", "required", "readonly")) {
                Object expression = map.get(slot);
                if (expression instanceof String source && !source.isBlank()) {
                    try {
                        Expression parsed = Expression.parse(source);
                        parsed.compileCheck(Expression.CompilePolicy.recordContext(fields, true));
                        parsed.arithmeticCheck(types);   // PHASE-3 §2's type-aware leg
                    } catch (ExpressionException e) {
                        errors.add(new ProblemErrors.FieldError(
                                pageApiName + ".layout", source + " — " + e.getMessage(), source));
                    }
                }
            }
            checkAction(pageApiName, map.get("actions"), errors);
            if ("addAction".equals(map.get("op"))) {
                checkAction(pageApiName, map.get("action"), errors);
            }
            for (Object value : map.values()) {
                checkNodeSlots(pageApiName, value, fields, types, errors, publish);
            }
        } else if (node instanceof java.util.List<?> list) {
            for (Object child : list) {
                checkNodeSlots(pageApiName, child, fields, types, errors, publish);
            }
        }
    }

    /**
     * The §4 catalog contract, node-local (the document carries id, version pin,
     * and props; the catalog itself is the platform manifest the twin pins in
     * lockstep): an unknown component id rejects; a version pin that disagrees
     * with the catalog rejects; a missing pin rejects at publish (save resolves
     * it to the current stable — the twin's save rule, the resolver's rendering
     * rule); and the props validate against the component's props JSON Schema.
     * Unknown ids skip the version/props checks exactly as the twin's walk does —
     * there is nothing to pin or validate against.
     */
    private static void checkNodeCatalog(String pageApiName, java.util.Map<?, ?> map,
                                         boolean publish, List<ProblemErrors.FieldError> errors) {
        if (!(map.get("type") instanceof String type) || !type.startsWith("novaforge.")) {
            return;
        }
        ComponentCatalog.Entry entry = ComponentCatalog.find(type);
        if (entry == null) {
            errors.add(new ProblemErrors.FieldError(pageApiName + ".layout",
                    "unknown component '" + type + "'", type));
            return;
        }
        Object version = map.get("version");
        if (version == null) {
            if (publish) {
                errors.add(new ProblemErrors.FieldError(pageApiName + ".layout",
                        "missing pinned version (publish requires " + type + "@"
                                + entry.version() + ")", type));
            }
        } else if (!entry.version().equals(String.valueOf(version))) {
            errors.add(new ProblemErrors.FieldError(pageApiName + ".layout",
                    "unknown version " + type + "@" + version + " (catalog serves "
                            + entry.version() + ")", String.valueOf(version)));
        }
        for (ComponentCatalog.SchemaIssue issue : ComponentCatalog.validateProps(
                map.get("props"), entry.schema(), "props")) {
            errors.add(new ProblemErrors.FieldError(pageApiName + ".layout",
                    issue.path() + ": " + issue.message(), issue.path()));
        }
    }

    /**
     * The §4 bind rules, node-local (the only form the server can check — the L1
     * default the deltas overlay is role-resolved client-side, ADR-009): where the
     * bound name repeats in widget config a mismatch rejects; a binding-taking
     * component (catalog contract §6 item 1 — the {@code novaforge.field-*}
     * widgets and {@code novaforge.related-list}) carries a bind; a bind's head
     * resolves to a field or relationship on the entity. Delta envelopes
     * ({@code op}-shaped maps) and action maps (closed-ladder types) are not
     * component nodes and never match the shape test.
     */
    private static void checkNodeBinds(String pageApiName, java.util.Map<?, ?> map,
                                       java.util.Set<String> fields,
                                       List<ProblemErrors.FieldError> errors) {
        if (!(map.get("type") instanceof String type) || !type.startsWith("novaforge.")) {
            return;
        }
        Object bind = map.get("bind");
        if (bind instanceof String bound && !bound.isBlank()) {
            if (map.get("props") instanceof java.util.Map<?, ?> props) {
                for (String prop : java.util.List.of("field", "relationship")) {
                    if (props.get(prop) instanceof String repeated && !repeated.equals(bound)) {
                        errors.add(new ProblemErrors.FieldError(pageApiName + ".layout",
                                "bind '" + bound + "' and props." + prop + " '" + repeated
                                        + "' disagree", bound));
                    }
                }
            }
        }
        if (takesBinding(type)) {
            if (!(bind instanceof String bound) || bound.isBlank()) {
                errors.add(new ProblemErrors.FieldError(pageApiName + ".layout",
                        type + " requires a bind", type));
            } else {
                String head = bound.split("\\.")[0];
                if (!fields.contains(head)) {
                    errors.add(new ProblemErrors.FieldError(pageApiName + ".layout",
                            "bind '" + bound + "' resolves to no field or relationship",
                            bound));
                }
            }
        }
    }

    /** Whether a component consumes a {@code bind} slot (§6 item 1) — the TS twin's rule. */
    private static boolean takesBinding(String componentType) {
        return componentType.startsWith("novaforge.field-")
                || componentType.equals("novaforge.related-list");
    }

    /**
     * The closed action ladder (PHASE-2 §4 + PHASE-3 §8's runFlow activation): the
     * persisted page may only carry actions from the versioned set — save rejects
     * unknown types and shape errors the TS twin already guards, so the metadata
     * store can never hold an action no runtime dispatches.
     */
    private static void checkAction(String pageApiName, Object actions, List<ProblemErrors.FieldError> errors) {
        // resolved trees carry the ladder as a list; a structural delta's addAction op
        // carries one action — both forms check identically (the encoding-agnostic walk)
        java.util.List<?> entries;
        if (actions instanceof java.util.List<?> list) {
            entries = list;
        } else if (actions instanceof java.util.Map<?, ?> single) {
            entries = java.util.List.of(single);
        } else {
            return;
        }
        for (Object entry : entries) {
            if (!(entry instanceof java.util.Map<?, ?> action)) {
                continue;
            }
            String type = String.valueOf(action.get("type"));
            if (!CLOSED_PAGE_ACTIONS.contains(type)) {
                errors.add(new ProblemErrors.FieldError(pageApiName + ".actions",
                        "unknown action type '" + type + "'", type));
            } else if ("openPage".equals(type)
                    && !(action.get("props") instanceof java.util.Map<?, ?> props
                            && props.get("page") instanceof String page && !page.isBlank())) {
                errors.add(new ProblemErrors.FieldError(pageApiName + ".actions",
                        "openPage requires props.page", type));
            } else if ("runFlow".equals(type)
                    && !(action.get("props") instanceof java.util.Map<?, ?> props
                            && props.get("hook") instanceof String hook && !hook.isBlank())) {
                errors.add(new ProblemErrors.FieldError(pageApiName + ".actions",
                        "runFlow requires props.hook (a named flow hook on the bound entity)", type));
            }
        }
    }

    /** Runs the suite against the current draft candidate on a scratch tenant (ADR-010 #3). */
    public Map<String, Object> runSuite(UUID tenantId, UUID actorId, UUID appId, String suiteApiName) {
        AppDefinition candidate = store.requireApp(tenantId, appId);
        var suite = candidate.testSuite(suiteApiName).orElseThrow(() ->
                new PlatformException(PlatformErrorCode.NOT_FOUND,
                        "test suite " + suiteApiName + " not found"));
        Map<String, Object> artifact = testRunner.run(candidate, suite, actorId);
        // PHASE-8 §4 item 1: run artifacts are version-bound — the candidate's content
        // hash is what publish records on the version row, so the promotion gate
        // matches runs to versions mechanically. Every run (interactive or headless)
        // records evidence.
        store.recordSuiteRun(tenantId, appId, suiteApiName,
                com.novaforge.metadata.lifecycle.LifecycleHash.contentHash(candidate),
                Boolean.TRUE.equals(artifact.get("green")), artifact, actorId);
        return artifact;
    }

    /** Suite save-validation: ops known, expectations shaped, op params present (§7, §12). */
    static void validateSuite(com.novaforge.metadata.TestSuiteDefinition suite) {
        for (var testCase : suite.cases()) {
            for (var step : testCase.steps()) {
                if (!com.novaforge.metadata.TestSuiteDefinition.Step.OPS.contains(step.op())) {
                    throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                            "unknown suite step op: " + step.op());
                }
                String expect = step.expect() == null ? "ok" : step.expect();
                if (!expect.equals("ok") && !expect.matches("(error|validation)\\(.+\\)")) {
                    throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                            "expect must be ok | error(code) | validation(rule): " + expect);
                }
                String where = "case '" + testCase.name() + "' step " + step.op()
                        + (step.entity() == null ? "" : " " + step.entity());
                switch (step.op()) {
                    // record-addressed ops interpolate step.recordId() — absent means an
                    // NPE at run time; authoring errors belong at save time
                    case "updateRecord", "deleteRecord", "resolveTask" -> {
                        if (step.recordId() == null || step.recordId().isBlank()) {
                            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                                    where + " requires recordId");
                        }
                    }
                    default -> { }
                }
                if ("postWebhook".equals(step.op())
                        && (step.template() == null
                        || !(step.template().get("hookId") instanceof String)
                        || !(step.template().get("body") instanceof Map))) {
                    throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                            where + " requires template.hookId and template.body (§10)");
                }
                if ("deleteRecord".equals(step.op())
                        && (step.template() == null
                        || !(step.template().get("version") instanceof Number
                        // a ${…} reference resolves at run time — the runner
                        // interpolates versions exactly like every other template slot
                        || isReference(String.valueOf(step.template().get("version")))))) {
                    throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                            where + " requires template.version (optimistic locking)");
                }
                if ("resolveTask".equals(step.op()) && step.template() != null) {
                    Object action = step.template().get("action");
                    if (action != null && !"approve".equals(action) && !"reject".equals(action)) {
                        throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                                where + " action must be approve or reject: " + action);
                    }
                }
                // scanSla (§12's clock leg): exactly one governing instant — a duration
                // to advance past now or an absolute asOf, both parse-checked at save
                if ("scanSla".equals(step.op())) {
                    Object advance = step.template() == null ? null : step.template().get("advance");
                    Object asOf = step.template() == null ? null : step.template().get("asOf");
                    boolean hasAdvance = advance instanceof String text && !text.isBlank();
                    boolean hasAsOf = asOf instanceof String text && !text.isBlank();
                    if (hasAdvance == hasAsOf) {
                        throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                                where + " requires exactly one of template.advance"
                                        + " (ISO-8601 duration) or template.asOf (instant)");
                    }
                    try {
                        if (hasAdvance) {
                            java.time.Duration.parse((String) advance);
                        } else {
                            java.time.Instant.parse((String) asOf);
                        }
                    } catch (Exception malformed) {
                        throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                                where + " governing instant does not parse: " + malformed.getMessage());
                    }
                }
                if ("queryRecord".equals(step.op()) && "Task".equals(step.entity())
                        && step.template() != null && step.template().get("filter") != null) {
                    Object filter = step.template().get("filter");
                    boolean statusOnly = filter instanceof Map<?, ?> map && map.size() == 1
                            && map.get("status") instanceof String;
                    if (!statusOnly) {
                        throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                                where + " filter supports {status: <string>} in v1");
                    }
                }
            }
        }
    }

    /**
     * Publish: validate all drafts, snapshot into a new immutable version, record the
     * compatibility check against the previously published version (breaking changes
     * need {@code acknowledgeDataImpact} — nothing is destroyed silently: JSONB keeps
     * removed-field data intact until a tenant-scoped prune), emit
     * {@code metadata.published} (PHASE-1 §4).
     */
    @Transactional
    public MetadataStore.VersionInfo publish(UUID tenantId, UUID actorId, UUID appId,
                                             boolean acknowledgeDataImpact) {
        AppDefinition draft = store.requireApp(tenantId, appId);
        ProblemErrors errors = DefinitionValidator.validate(draft);
        compileCheckExpressions(draft, errors, true);
        if (!errors.isEmpty()) {
            throw validationFailure("publish rejected: drafts fail validation", errors);
        }
        FlowCompiler.compile(draft);
        MetadataStore.PublishedBundle previous = store.latestPublished(tenantId, appId).orElse(null);
        List<String> breaking = previous == null ? List.of() : breakingChanges(previous.app(), draft);
        if (!breaking.isEmpty() && !acknowledgeDataImpact) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "publish contains breaking changes; re-run with acknowledgeDataImpact=true",
                    ProblemErrors.of(new ProblemErrors.GlobalError(draft.apiName(),
                            "breaking changes require acknowledgeDataImpact: " + String.join("; ", breaking))));
        }
        int version = (previous == null ? 0 : store.versions(tenantId, appId).getFirst().version()) + 1;
        MetadataStore.VersionInfo info;
        try {
            info = store.publish(tenantId, actorId, appId, version, draft, breaking,
                    acknowledgeDataImpact);
        } catch (org.springframework.dao.DataIntegrityViolationException lostRace) {
            // Two concurrent publishes computed the same next version; the unique
            // (tenant, app, version) constraint holds — the loser is a routine lost
            // race, not an internal error.
            throw new PlatformException(PlatformErrorCode.CONFLICT_VERSION,
                    "a concurrent publish claimed version " + version + " first — retry the "
                            + "publish", null, lostRace);
        }
        events.publishMetadataPublished(tenantId, appId, version, actorId, info.publishedAt());
        return info;
    }

    public List<MetadataStore.VersionInfo> versions(UUID tenantId, UUID appId) {
        return store.versions(tenantId, appId);
    }

    public AppDefinition exportVersion(UUID tenantId, UUID appId, int version) {
        return store.exportVersion(tenantId, appId, version).orElseThrow(() ->
                new PlatformException(PlatformErrorCode.NOT_FOUND,
                        "version " + version + " of app " + appId + " not found"));
    }

    /** The runtime read path for rendering (PHASE-1 §4): latest bundle + its version. */
    public MetadataStore.PublishedBundle published(UUID tenantId, UUID appId) {
        return store.latestPublished(tenantId, appId).orElseThrow(() ->
                new PlatformException(PlatformErrorCode.NOT_FOUND,
                        "app " + appId + " has no published version"));
    }

    /**
     * Expression compile-check (PHASE-2 §7): every expression slot is parsed and its
     * references resolved against the entity's field apiNames. Validation rules may
     * read the clock; formula fields may not (PHASE-3 §3 — determinism of stored
     * values). Slots stay inert until Phase 3 activates write-path evaluation.
     */
    /** Expression compile-check — package-private so the artifact tests ride the exact save-path check.
     *  {@code publish} switches the §4 version-pin rule the page walk enforces: save resolves a
     *  missing pin to the catalog's current stable, publish rejects it. */
    static void compileCheckExpressions(AppDefinition app, ProblemErrors errors, boolean publish) {
        List<ProblemErrors.FieldError> found = new ArrayList<>(errors.errors());
        for (AppDefinition.PageDefinition page : app.pages()) {
            compileCheckPage(page, app, found, publish);
        }
        for (EntityDefinition entity : app.entities()) {
            java.util.Set<String> fields = new java.util.LinkedHashSet<>();
            entity.fields().forEach(f -> fields.add(f.apiName()));
            entity.relationships().forEach(r -> fields.add(r.apiName()));
            for (EntityDefinition.ValidationRule rule : entity.validations()) {
                check(rule.expression(), entity, fields, true, found);
            }
            for (com.novaforge.metadata.FieldDefinition field : entity.fields()) {
                if (field.formula() != null) {
                    check(field.formula(), entity, fields, false, found);
                }
                if (field.defaultValue() instanceof com.novaforge.metadata.DefaultValue.ExpressionDefault expression) {
                    check(expression.expression(), entity, fields, false, found);
                }
            }
        }
        if (!found.isEmpty()) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "expression compile-check failed", new ProblemErrors(found, List.of()));
        }
    }

    /** The page slot compile-check from putPage, run app-wide at publish (§7) —
     *  publish mode: the §4 version pin is required, not resolved. */
    private static void compileCheckPage(AppDefinition.PageDefinition page, AppDefinition app,
                                         List<ProblemErrors.FieldError> errors, boolean publish) {
        if (page.entity() == null || !(page.layout() instanceof java.util.Map<?, ?>)) {
            return;
        }
        app.entity(page.entity()).ifPresent(entity -> {
            java.util.Set<String> fields = new java.util.LinkedHashSet<>();
            entity.fields().forEach(f -> fields.add(f.apiName()));
            entity.relationships().forEach(r -> fields.add(r.apiName()));
            checkNodeSlots(page.apiName(), page.layout(), fields,
                    com.novaforge.metadata.ExpressionTypes.of(entity), errors, publish);
        });
    }

    private static void check(String source, EntityDefinition entity, java.util.Set<String> fields,
                              boolean allowClock, List<ProblemErrors.FieldError> errors) {
        try {
            Expression parsed = Expression.parse(source);
            parsed.compileCheck(Expression.CompilePolicy.recordContext(fields, allowClock));
            // PHASE-3 §2's type-aware leg (the same guard every flow slot passes):
            // statically-typed Annex A violations reject at save, never at evaluation
            parsed.arithmeticCheck(com.novaforge.metadata.ExpressionTypes.of(entity));
        } catch (ExpressionException e) {
            errors.add(new ProblemErrors.FieldError(
                    entity.apiName() + ".expressions", source + " — " + e.getMessage(), source));
        }
    }

    // --- compatibility check (§4) ---

    public static List<String> breakingChanges(AppDefinition previous, AppDefinition next) {
        List<String> changes = new ArrayList<>();
        Map<String, EntityDefinition> nextEntities = new java.util.HashMap<>();
        next.entities().forEach(e -> nextEntities.put(e.apiName(), e));
        for (EntityDefinition prevEntity : previous.entities()) {
            EntityDefinition nextEntity = nextEntities.get(prevEntity.apiName());
            if (nextEntity == null) {
                changes.add("entity removed: " + prevEntity.apiName());
                continue;
            }
            Map<String, com.novaforge.metadata.FieldDefinition> nextFields = new java.util.HashMap<>();
            nextEntity.fields().forEach(f -> nextFields.put(f.apiName(), f));
            for (com.novaforge.metadata.FieldDefinition prevField : prevEntity.fields()) {
                com.novaforge.metadata.FieldDefinition nextField = nextFields.get(prevField.apiName());
                if (nextField == null) {
                    changes.add("field removed: " + prevEntity.apiName() + "." + prevField.apiName());
                } else if (nextField.type() != prevField.type()) {
                    changes.add("field type changed: " + prevEntity.apiName() + "." + prevField.apiName()
                            + " " + prevField.type().wireName() + " → " + nextField.type().wireName());
                }
            }
            if (prevEntity.displayField() != null
                    && nextEntity.field(prevEntity.displayField()).isEmpty()) {
                changes.add("displayField removed: " + prevEntity.apiName() + "."
                        + prevEntity.displayField());
            }
        }
        return changes;
    }

    // --- merging ---

    private static EntityDefinition mergeEntity(EntityDefinition current, EntityDefinition patch) {
        return new EntityDefinition(
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
    }

    private static AppDefinition withEntities(AppDefinition app, List<EntityDefinition> entities) {
        return new AppDefinition(app.id(), app.apiName(), app.label(), app.labelI18n(),
                app.description(), entities, app.pages(), app.settings(), app.permissionSet(),
                app.testSuites(), app.stateMachines(), app.slas(), app.jobs(), app.workflows(),
                app.reports(), app.dashboards(), app.integrations(), app.translations(),
                app.gapLog());
    }

    /** A {@code ${…}} template reference — resolves at run time, validated for shape at save. */
    private static boolean isReference(String value) {
        return value != null && value.matches("\\$\\{[A-Za-z0-9_.\\[\\]]+}");
    }

    private static PlatformException validationFailure(String message, ProblemErrors errors) {
        return new PlatformException(PlatformErrorCode.VALIDATION_FAILED, message, errors);
    }
}
