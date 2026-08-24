package com.novaforge.runtime.engine;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.common.error.ProblemErrors;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefaultValue;
import com.novaforge.metadata.EntityDefinition;
import com.novaforge.metadata.FieldDefinition;
import com.novaforge.metadata.FieldType;
import com.novaforge.metadata.HookRule;
import com.novaforge.metadata.PermissionSet;
import com.novaforge.metadata.RelationshipDefinition;
import com.novaforge.metadata.RelationshipType;
import com.novaforge.common.context.TenantContext;
import com.novaforge.expression.Expression;
import com.novaforge.runtime.engine.hook.HookExecutor;
import com.novaforge.runtime.authorization.RoleMatrix;
import com.novaforge.runtime.engine.event.DomainEventPublisher;
import com.novaforge.runtime.engine.metadata.EntityResolver;
import com.novaforge.runtime.engine.metadata.EntityResolver.EntityHandle;
import com.novaforge.runtime.engine.sequence.SequenceService;
import com.novaforge.runtime.engine.write.FieldCoercer;
import com.novaforge.runtime.engine.query.QueryLowering;
import com.novaforge.runtime.engine.query.QueryModel;
import com.novaforge.runtime.engine.query.QueryParser;
import com.novaforge.runtime.storage.record.RecordStore;
import com.novaforge.metadata.Snake;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The write/query pipeline (PHASE-1 §5 — the Phase 1 slice of ARCHITECTURE.md §2.4):
 * resolve metadata → authorize → apply defaults (static + sequence references drawn once
 * at create) → field validations → inline children (atomic, ≤100) → persist with
 * optimistic locking → event seam → shaped projection. Lists/aggregates lower the query
 * DSL; batch runs per-item outcomes.
 */
@Service
public class RecordEngine {

    public static final int MAX_INLINE_CHILDREN = 100;
    public static final int MAX_BATCH = 500;

    private final EntityResolver resolver;
    private final RoleMatrix roleMatrix;
    private final com.novaforge.runtime.authorization.SharingGate sharing;
    private final RecordStore records;
    private final SequenceService sequences;
    private final DomainEventPublisher events;
    private final HookExecutor hooks;
    private final HookExecutor.HookSink hookSink;

    public RecordEngine(EntityResolver resolver, RoleMatrix roleMatrix,
                        com.novaforge.runtime.authorization.SharingGate sharing,
                        RecordStore records,
                        SequenceService sequences, DomainEventPublisher events,
                        HookExecutor hooks) {
        this.resolver = resolver;
        this.roleMatrix = roleMatrix;
        this.sharing = sharing;
        this.records = records;
        this.sequences = sequences;
        this.events = events;
        this.hooks = hooks;
        this.hookSink = new EngineHookSink();
    }

    // --- write path ---

    @Transactional
    public Map<String, Object> create(UUID tenantId, UUID actorId, String entityApiName,
                                      Map<String, Object> body) {
        EntityHandle handle = resolver.resolve(tenantId, entityApiName);
        AppDefinition app = resolver.bundle(tenantId, handle.appId());
        roleMatrix.require(tenantId, actorId, RoleMatrix.Action.CREATE, entityApiName,
                handle.appApiName(), app.permissionSet());

        List<ProblemErrors.FieldError> errors = new ArrayList<>();
        Map<String, Object> fields = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> children = new LinkedHashMap<>();

        splitChildren(handle.entity(), body, children, fields, errors);
        applyDefaults(tenantId, app, handle.entity(), fields, errors);
        Map<String, Object> canonical = FieldCoercer.canonicalize(handle.entity(), fields,
                externalChecks(tenantId, handle, app), null, errors);
        FieldCoercer.checkRequired(handle.entity(), canonical, errors);
        evaluateFormulas(handle.entity(), canonical);
        evaluateValidationRules(handle.entity(), canonical, errors);
        reject(errors, "create " + entityApiName + " failed validation");

        UUID id = UUID.randomUUID();
        runHooks(app, handle, tenantId, id, canonical, "beforeSave", appSystemPrincipal(handle), actorId);
        requireParentsNotFrozen(tenantId, app, handle, canonical);
        enforceCreateState(app, handle, canonical);
        enforcePeriodLock(tenantId, app, handle, canonical);
        evaluateRollupsFromChildren(app, handle, children, canonical);
        persistWithChildren(tenantId, actorId, app, handle, id, canonical, children, errors);

        events.publish(event("record.created", tenantId, handle.entityKey(), id, actorId));
        runHooks(app, handle, tenantId, id, canonical, "afterSave", appSystemPrincipal(handle), actorId);
        return shape(handle.entity(), records.find(tenantId, handle.entityKey(), id, false)
                .orElseThrow(), strip(tenantId, actorId, handle, app));
    }

    @Transactional
    public Map<String, Object> update(UUID tenantId, UUID actorId, String entityApiName,
                                      UUID id, int expectedVersion, Map<String, Object> body) {
        EntityHandle handle = resolver.resolve(tenantId, entityApiName);
        AppDefinition app = resolver.bundle(tenantId, handle.appId());
        roleMatrix.require(tenantId, actorId, RoleMatrix.Action.UPDATE, entityApiName,
                handle.appApiName(), app.permissionSet());

        RecordStore.StoredRecord existing = records.find(tenantId, handle.entityKey(), id, false)
                .orElseThrow(() -> notFound(entityApiName, id));
        requireVisible(sharing.forActor(tenantId, actorId, handle.entity(), app), existing);
        requireNotFrozen(app, handle, existing.data());
        rejectReadonlyWrites(handle.entity(), body);
        rejectFieldSecurityWrites(tenantId, actorId, handle, app, body);

        List<ProblemErrors.FieldError> errors = new ArrayList<>();
        Map<String, Object> fields = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> children = new LinkedHashMap<>();
        // The body carries the system field `version` for optimistic locking — strip it
        // before field validation (system fields are not authorable, §3 reserved names).
        Map<String, Object> fieldBody = new LinkedHashMap<>(body);
        fieldBody.remove("version");
        splitChildren(handle.entity(), fieldBody, children, fields, errors);

        Map<String, Object> merged = new LinkedHashMap<>(existing.data());
        Map<String, Object> canonical = FieldCoercer.canonicalize(handle.entity(), fields,
                externalChecks(tenantId, handle, app), id, errors);
        canonical.forEach(merged::put);
        FieldCoercer.checkRequired(handle.entity(), merged, errors);
        evaluateFormulas(handle.entity(), merged);
        evaluateValidationRules(handle.entity(), merged, errors);
        reject(errors, "update " + entityApiName + "/" + id + " failed validation");
        runHooks(app, handle, tenantId, id, merged, "beforeSave", appSystemPrincipal(handle), actorId);
        requireParentsNotFrozen(tenantId, app, handle, merged);
        enforceTransition(app, handle, existing.data(), merged);
        enforcePeriodLock(tenantId, app, handle, merged);

        int newVersion = records.update(tenantId, handle.entityKey(), id, merged,
                expectedVersion, actorId);
        replaceChildren(tenantId, actorId, app, handle, id, children);
        newVersion = recomputeRollupsIfChanged(tenantId, actorId, app, handle, id, merged, newVersion);
        events.publish(event("record.updated", tenantId, handle.entityKey(), id, actorId));
        runHooks(app, handle, tenantId, id, merged, "afterSave", appSystemPrincipal(handle), actorId);

        Map<String, Object> shaped = shape(handle.entity(),
                records.find(tenantId, handle.entityKey(), id, false).orElseThrow(),
                strip(tenantId, actorId, handle, app));
        shaped.put("version", newVersion);
        return shaped;
    }

    @Transactional
    public void delete(UUID tenantId, UUID actorId, String entityApiName, UUID id,
                       int expectedVersion) {
        EntityHandle handle = resolver.resolve(tenantId, entityApiName);
        AppDefinition app = resolver.bundle(tenantId, handle.appId());
        roleMatrix.require(tenantId, actorId, RoleMatrix.Action.DELETE, entityApiName,
                handle.appApiName(), app.permissionSet());

        RecordStore.StoredRecord existingRecord = records.find(tenantId, handle.entityKey(),
                id, false).orElseThrow(() -> notFound(entityApiName, id));
        requireVisible(sharing.forActor(tenantId, actorId, handle.entity(), app), existingRecord);
        Map<String, Object> existingData = existingRecord.data();
        requireNotFrozen(app, handle, existingData);
        requireParentsNotFrozen(tenantId, app, handle, existingData);
        runHooks(app, handle, tenantId, id, existingData, "beforeDelete",
                appSystemPrincipal(handle), actorId);
        records.softDelete(tenantId, handle.entityKey(), id, expectedVersion, actorId);
        cascadeChildren(tenantId, actorId, app, handle, id);
        events.publish(event("record.deleted", tenantId, handle.entityKey(), id, actorId));
        runHooks(app, handle, tenantId, id, existingData, "afterDelete",
                appSystemPrincipal(handle), actorId);
    }

    // --- read path ---

    public Map<String, Object> get(UUID tenantId, UUID actorId, String entityApiName, UUID id,
                                   boolean includeDeleted) {
        EntityHandle handle = resolver.resolve(tenantId, entityApiName);
        AppDefinition app = resolver.bundle(tenantId, handle.appId());
        roleMatrix.require(tenantId, actorId, RoleMatrix.Action.READ, entityApiName,
                handle.appApiName(), app.permissionSet());
        if (includeDeleted) {
            roleMatrix.requireAdmin(tenantId, actorId);   // admin-only (PHASE-1 §5)
        }
        RecordStore.StoredRecord record = records.find(tenantId, handle.entityKey(), id,
                        includeDeleted)
                .orElseThrow(() -> notFound(entityApiName, id));
        requireVisible(sharing.forActor(tenantId, actorId, handle.entity(), app), record);
        return shape(handle.entity(), record, strip(tenantId, actorId, handle, app));
    }

    /**
     * Sharing enforcement (PHASE-4 §10): a record outside the actor's visibility
     * reads as absent — NOT_FOUND, not FORBIDDEN (no existence leak). Unrestricted
     * actors (no rules defined, admins/builders, owner-rule-named roles) pass.
     */
    private void requireVisible(com.novaforge.runtime.authorization.SharingGate.Restriction restriction,
                                RecordStore.StoredRecord record) {
        if (restriction == null) {
            return;
        }
        Map<String, Object> view = new LinkedHashMap<>(record.data());
        view.put("__owner__", record.createdBy());
        if (!restriction.recordVisible().test(view)) {
            throw new PlatformException(PlatformErrorCode.NOT_FOUND,
                    "record not visible to this actor (sharing rules apply)");
        }
    }

    public QueryModel.QueryResult list(UUID tenantId, UUID actorId, String entityApiName,
                                       String queryJson) {
        EntityHandle handle = resolver.resolve(tenantId, entityApiName);
        AppDefinition app = resolver.bundle(tenantId, handle.appId());
        roleMatrix.require(tenantId, actorId, RoleMatrix.Action.READ, entityApiName,
                handle.appApiName(), app.permissionSet());
        QueryModel.ListQuery query = QueryParser.parseList(queryJson, handle.entity());
        QueryLowering lowering = new QueryLowering(handle.entity());
        QueryLowering.Lowered countSql = lowering.count(handle.entity().apiName(), tenantId,
                query.filter());
        QueryLowering.Lowered listSql = lowering.list(handle.entity().apiName(), tenantId, query);
        var restriction = sharing.forActor(tenantId, actorId, handle.entity(), app);
        if (restriction != null) {
            String placeholders = restriction.visibleOwners().stream().map(o -> "?")
                    .reduce((a, b) -> a + "," + b).orElse("?");
            List<Object> owners = List.copyOf(restriction.visibleOwners());
            countSql = countSql.and("created_by IN (" + placeholders + ")", owners);
            listSql = listSql.and("created_by IN (" + placeholders + ")", owners);
        }
        RecordStore.PageResult page = records.list(countSql.sql(), countSql.params(),
                listSql.sql(), listSql.params());
        java.util.function.Predicate<String> strip = strip(tenantId, actorId, handle, app);
        List<Map<String, Object>> rows = page.rows().stream()
                .filter(row -> restriction == null   // criteria rules post-filter the
                        || restriction.recordVisible().test(row))   // page (§10 note)
                .map(row -> stripHidden(row, strip))
                .toList();
        return new QueryModel.QueryResult(rows, page.total());
    }

    public QueryModel.AggregateResult aggregate(UUID tenantId, UUID actorId, String entityApiName,
                                                String queryJson) {
        EntityHandle handle = resolver.resolve(tenantId, entityApiName);
        AppDefinition app = resolver.bundle(tenantId, handle.appId());
        roleMatrix.require(tenantId, actorId, RoleMatrix.Action.READ, entityApiName,
                handle.appApiName(), app.permissionSet());
        QueryModel.AggregateQuery query = QueryParser.parseAggregate(queryJson, handle.entity());
        // aggregates leak values, not rows — hidden group-by/aggregate fields fail closed
        for (QueryModel.GroupBy group : query.groupBy()) {
            requireFieldVisible(roleMatrix.fieldAccess(tenantId, actorId, handle.appApiName(),
                    app.permissionSet(), entityApiName, group.field()), entityApiName,
                    group.field());
        }
        for (QueryModel.Aggregate aggregate : query.aggregates()) {
            if (aggregate.field() != null) {
                requireFieldVisible(roleMatrix.fieldAccess(tenantId, actorId, handle.appApiName(),
                        app.permissionSet(), entityApiName, aggregate.field()), entityApiName,
                        aggregate.field());
            }
        }
        QueryLowering.Lowered lowered = new QueryLowering(handle.entity())
                .aggregate(handle.entity().apiName(), tenantId, query,
                        java.time.LocalDate.now(clock));
        lowered = applySharing(lowered, handle,
                sharing.forActor(tenantId, actorId, handle.entity(), app));
        return new QueryModel.AggregateResult(
                query.groupBy().stream().map(QueryModel.GroupBy::field).toList(),
                records.aggregate(lowered.sql(), lowered.params()).rows());
    }

    /**
     * The scheduled-report scope (PHASE-5 §7): the per-app system principal executes
     * over an explicitly permissioned role — the app's matrix decides the entity READ
     * and field security for {@code asRole}, and the sharing rules evaluate as-if a
     * holder of exactly that role (no personal ownership). Never a bypass: an
     * ungranted role fails closed exactly like an ungranted actor.
     */
    public QueryModel.AggregateResult aggregateAsRole(UUID tenantId, String entityApiName,
                                                      String asRole, String queryJson) {
        EntityHandle handle = resolver.resolve(tenantId, entityApiName);
        AppDefinition app = resolver.bundle(tenantId, handle.appId());
        var role = app.permissionSet().role(asRole == null ? "" : asRole);
        if (role.isEmpty()) {
            throw new PlatformException(PlatformErrorCode.FORBIDDEN,
                    "runAsRole must resolve against the app's roles: " + asRole);
        }
        boolean granted = app.permissionSet().objectPermissions().stream()
                .filter(p -> p.entity().equals(entityApiName))
                .filter(p -> p.role().equals(asRole))
                .anyMatch(p -> p.allows("read"));
        if (!granted) {
            throw new PlatformException(PlatformErrorCode.FORBIDDEN,
                    "role " + asRole + " is not granted read on " + entityApiName);
        }
        QueryModel.AggregateQuery query = QueryParser.parseAggregate(queryJson, handle.entity());
        for (QueryModel.GroupBy group : query.groupBy()) {
            requireFieldVisible(roleFieldAccess(app, entityApiName, group.field(), asRole),
                    entityApiName, group.field());
        }
        for (QueryModel.Aggregate aggregate : query.aggregates()) {
            if (aggregate.field() != null) {
                requireFieldVisible(
                        roleFieldAccess(app, entityApiName, aggregate.field(), asRole),
                        entityApiName, aggregate.field());
            }
        }
        QueryLowering.Lowered lowered = new QueryLowering(handle.entity())
                .aggregate(handle.entity().apiName(), tenantId, query,
                        java.time.LocalDate.now(clock));
        lowered = applySharing(lowered, handle, sharing.forRole(tenantId, handle.entity(),
                app, asRole));
        return new QueryModel.AggregateResult(
                query.groupBy().stream().map(QueryModel.GroupBy::field).toList(),
                records.aggregate(lowered.sql(), lowered.params()).rows());
    }

    private static void requireFieldVisible(String access, String entityApiName, String field) {
        if (com.novaforge.metadata.PermissionSet.FieldSecurity.HIDDEN.equals(access)) {
            throw new PlatformException(PlatformErrorCode.FORBIDDEN,
                    "field " + entityApiName + "." + field + " is hidden — aggregates fail closed");
        }
    }

    /** Field access for one authored role, without an actor's platform lookup. */
    private static String roleFieldAccess(AppDefinition app, String entityApiName,
                                          String field, String role) {
        String access = "visible";
        for (com.novaforge.metadata.PermissionSet.FieldSecurity security
                : app.permissionSet().fieldSecurity()) {
            if (!security.entity().equals(entityApiName) || !security.field().equals(field)
                    || !security.role().equals(role)) {
                continue;
            }
            if (com.novaforge.metadata.PermissionSet.FieldSecurity.HIDDEN.equals(security.access())) {
                return com.novaforge.metadata.PermissionSet.FieldSecurity.HIDDEN;
            }
            access = com.novaforge.metadata.PermissionSet.FieldSecurity.READONLY;
        }
        return access;
    }

    /**
     * Sharing applies to aggregates exactly as to lists (PHASE-5 §4): the owner set
     * lowers to {@code created_by IN (…)}, criteria expressions lower into the same
     * predicate — visibility is one OR over both, evaluated in the pipeline. A
     * criteria that cannot lower (round(), collections) fails closed rather than
     * widening the dataset.
     */
    private QueryLowering.Lowered applySharing(QueryLowering.Lowered lowered, EntityHandle handle,
                                               com.novaforge.runtime.authorization.SharingGate.Restriction restriction) {
        if (restriction == null) {
            return lowered;
        }
        java.time.LocalDate asOf = java.time.LocalDate.now(clock);
        java.time.Instant asOfInstant = asOf.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        var resolver = com.novaforge.metadata.ExpressionFields.resolver(handle.entity());
        List<String> alternatives = new ArrayList<>();
        List<Object> clauseParams = new ArrayList<>();
        if (!restriction.visibleOwners().isEmpty()) {
            alternatives.add("created_by IN (" + restriction.visibleOwners().stream()
                    .map(o -> "?").reduce((a, b) -> a + "," + b).orElse("?") + ")");
            clauseParams.addAll(restriction.visibleOwners());
        }
        for (String criteria : restriction.criteriaExpressions()) {
            try {
                var loweredCriteria = com.novaforge.expression.ExpressionSql.lowerBoolean(
                        com.novaforge.expression.Expression.parse(criteria), resolver,
                        asOf, asOfInstant);
                alternatives.add("(" + loweredCriteria.sql() + ")");
                clauseParams.addAll(loweredCriteria.params());
            } catch (com.novaforge.expression.ExpressionException e) {
                throw new PlatformException(PlatformErrorCode.FORBIDDEN,
                        "a sharing criterion does not lower for aggregate scope — reports "
                                + "over " + handle.entity().apiName() + " stay fail closed: "
                                + e.getMessage());
            }
        }
        if (alternatives.isEmpty()) {
            return lowered.and("false", List.of());   // restricted to nobody
        }
        return lowered.and(String.join(" OR ", alternatives), clauseParams);
    }

    /** Bulk ops with per-item outcomes, max 500 (PHASE-1 §5). */
    public List<Map<String, Object>> batch(UUID tenantId, UUID actorId, List<Map<String, Object>> items) {
        if (items.size() > MAX_BATCH) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "batch exceeds " + MAX_BATCH + " items",
                    ProblemErrors.of(new ProblemErrors.FieldError("items",
                            "batch is capped at " + MAX_BATCH + " items", items.size())));
        }
        List<Map<String, Object>> outcomes = new ArrayList<>();
        for (Map<String, Object> item : items) {
            try {
                Map<String, Object> result = switch (String.valueOf(item.get("op"))) {
                    case "create" -> create(tenantId, actorId, String.valueOf(item.get("entity")),
                            typedFields(item.get("record")));
                    case "update" -> update(tenantId, actorId, String.valueOf(item.get("entity")),
                            UUID.fromString(String.valueOf(item.get("id"))),
                            ((Number) item.get("version")).intValue(),
                            typedFields(item.get("record")));
                    case "delete" -> {
                        delete(tenantId, actorId, String.valueOf(item.get("entity")),
                                UUID.fromString(String.valueOf(item.get("id"))),
                                ((Number) item.get("version")).intValue());
                        yield Map.<String, Object>of("status", "ok");
                    }
                    default -> throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                            "unknown batch op: " + item.get("op"));
                };
                outcomes.add(Map.of("status", "ok", "record", result));
            } catch (PlatformException e) {
                outcomes.add(Map.of(
                        "status", "error",
                        "code", e.errorCode().code(),
                        "detail", e.getMessage()));
            }
        }
        return outcomes;
    }

    // --- the integration principal's write/read paths (PHASE-6 §3/§6/§7) ---

    /**
     * The per-app integration principal (PHASE-6 §3): a distinct principal from the
     * engine's per-app system principal (PHASE-4 §4), so audit provenance separates
     * integration-sourced writes (webhook applications, imports) from engine actions.
     */
    static UUID appIntegrationPrincipal(EntityHandle handle) {
        return UUID.nameUUIDFromBytes(("integration:" + handle.appApiName()).getBytes());
    }

    /**
     * A webhook/import write (PHASE-6 §6/§7): the complete write path — defaults,
     * coercions, validations, state machines, and hooks all fire; a webhook is just
     * another writer and the single write path is absolute. The matrix is the flow
     * principal's own bypass (PHASE-4 §13 Q1 — authorization is the integration's,
     * not a user's); metadata-level enforcement (required/readonly/validations)
     * applies to every writer.
     */
    @Transactional
    public Map<String, Object> integrationCreate(UUID tenantId, String entityApiName,
                                                 Map<String, Object> body) {
        EntityHandle handle = resolver.resolve(tenantId, entityApiName);
        AppDefinition app = resolver.bundle(tenantId, handle.appId());
        UUID principal = appIntegrationPrincipal(handle);

        List<ProblemErrors.FieldError> errors = new ArrayList<>();
        Map<String, Object> fields = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> children = new LinkedHashMap<>();
        splitChildren(handle.entity(), body, children, fields, errors);
        rejectReadonlyWrites(handle.entity(), body);
        applyDefaults(tenantId, app, handle.entity(), fields, errors);
        Map<String, Object> canonical = FieldCoercer.canonicalize(handle.entity(), fields,
                externalChecks(tenantId, handle, app), null, errors);
        FieldCoercer.checkRequired(handle.entity(), canonical, errors);
        evaluateFormulas(handle.entity(), canonical);
        evaluateValidationRules(handle.entity(), canonical, errors);
        reject(errors, "integration create " + entityApiName + " failed validation");

        UUID id = UUID.randomUUID();
        runHooks(app, handle, tenantId, id, canonical, "beforeSave", appSystemPrincipal(handle),
                principal);
        requireParentsNotFrozen(tenantId, app, handle, canonical);
        enforceCreateState(app, handle, canonical);
        enforcePeriodLock(tenantId, app, handle, canonical);
        evaluateRollupsFromChildren(app, handle, children, canonical);
        persistWithChildren(tenantId, principal, app, handle, id, canonical, children, errors);
        events.publish(event("record.created", tenantId, handle.entityKey(), id, principal));
        runHooks(app, handle, tenantId, id, canonical, "afterSave", appSystemPrincipal(handle),
                principal);
        Map<String, Object> shaped = shape(handle.entity(),
                records.find(tenantId, handle.entityKey(), id, false).orElseThrow(),
                field -> false);
        shaped.put("integration", true);
        return shaped;
    }

    /** The update twin (§6): optimistic locking, transition guards, hooks — all fired. */
    @Transactional
    public Map<String, Object> integrationUpdate(UUID tenantId, String entityApiName, UUID id,
                                                 int expectedVersion, Map<String, Object> body) {
        EntityHandle handle = resolver.resolve(tenantId, entityApiName);
        AppDefinition app = resolver.bundle(tenantId, handle.appId());
        UUID principal = appIntegrationPrincipal(handle);

        RecordStore.StoredRecord existing = records.find(tenantId, handle.entityKey(), id, false)
                .orElseThrow(() -> notFound(entityApiName, id));
        rejectReadonlyWrites(handle.entity(), body);

        List<ProblemErrors.FieldError> errors = new ArrayList<>();
        Map<String, Object> fields = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> children = new LinkedHashMap<>();
        Map<String, Object> fieldBody = new LinkedHashMap<>(body);
        fieldBody.remove("version");
        splitChildren(handle.entity(), fieldBody, children, fields, errors);

        Map<String, Object> merged = new LinkedHashMap<>(existing.data());
        Map<String, Object> canonical = FieldCoercer.canonicalize(handle.entity(), fields,
                externalChecks(tenantId, handle, app), id, errors);
        canonical.forEach(merged::put);
        FieldCoercer.checkRequired(handle.entity(), merged, errors);
        evaluateFormulas(handle.entity(), merged);
        evaluateValidationRules(handle.entity(), merged, errors);
        reject(errors, "integration update " + entityApiName + "/" + id + " failed validation");
        runHooks(app, handle, tenantId, id, merged, "beforeSave", appSystemPrincipal(handle),
                principal);
        requireNotFrozen(app, handle, existing.data());
        requireParentsNotFrozen(tenantId, app, handle, merged);
        enforceTransition(app, handle, existing.data(), merged);
        enforcePeriodLock(tenantId, app, handle, merged);

        int newVersion = records.update(tenantId, handle.entityKey(), id, merged,
                expectedVersion, principal);
        replaceChildren(tenantId, principal, app, handle, id, children);
        newVersion = recomputeRollupsIfChanged(tenantId, principal, app, handle, id, merged,
                newVersion);
        events.publish(event("record.updated", tenantId, handle.entityKey(), id, principal));
        runHooks(app, handle, tenantId, id, merged, "afterSave", appSystemPrincipal(handle),
                principal);
        Map<String, Object> shaped = shape(handle.entity(),
                records.find(tenantId, handle.entityKey(), id, false).orElseThrow(),
                field -> false);
        shaped.put("version", newVersion);
        shaped.put("integration", true);
        return shaped;
    }

    /**
     * The export scope (PHASE-6 §7): an entity dataset pages under an explicitly
     * permissioned role — {@code asRole} decides READ, field security, and sharing
     * exactly as the scheduled-report scope does (PHASE-5 §7). Never a bypass: an
     * ungranted role fails closed.
     */
    public QueryModel.QueryResult listAsRole(UUID tenantId, String entityApiName, String asRole,
                                             String queryJson) {
        EntityHandle handle = resolver.resolve(tenantId, entityApiName);
        AppDefinition app = resolver.bundle(tenantId, handle.appId());
        var role = app.permissionSet().role(asRole == null ? "" : asRole);
        if (role.isEmpty()) {
            throw new PlatformException(PlatformErrorCode.FORBIDDEN,
                    "runAsRole must resolve against the app's roles: " + asRole);
        }
        boolean granted = app.permissionSet().objectPermissions().stream()
                .filter(p -> p.entity().equals(entityApiName))
                .filter(p -> p.role().equals(asRole))
                .anyMatch(p -> p.allows("read"));
        if (!granted) {
            throw new PlatformException(PlatformErrorCode.FORBIDDEN,
                    "role " + asRole + " is not granted read on " + entityApiName);
        }
        QueryModel.ListQuery query = QueryParser.parseList(queryJson, handle.entity());
        QueryLowering lowering = new QueryLowering(handle.entity());
        QueryLowering.Lowered countSql = lowering.count(handle.entity().apiName(), tenantId,
                query.filter());
        QueryLowering.Lowered listSql = lowering.list(handle.entity().apiName(), tenantId, query);
        var restriction = sharing.forRole(tenantId, handle.entity(), app, asRole);
        if (restriction != null) {
            String placeholders = restriction.visibleOwners().stream().map(o -> "?")
                    .reduce((a, b) -> a + "," + b).orElse("?");
            List<Object> owners = List.copyOf(restriction.visibleOwners());
            countSql = countSql.and("created_by IN (" + placeholders + ")", owners);
            listSql = listSql.and("created_by IN (" + placeholders + ")", owners);
        }
        RecordStore.PageResult page = records.list(countSql.sql(), countSql.params(),
                listSql.sql(), listSql.params());
        java.util.function.Predicate<String> hidden = field ->
                com.novaforge.metadata.PermissionSet.FieldSecurity.HIDDEN.equals(
                        roleFieldAccess(app, entityApiName, field, asRole));
        List<Map<String, Object>> rows = page.rows().stream()
                .filter(row -> restriction == null || restriction.recordVisible().test(row))
                .map(row -> stripHidden(row, hidden))
                .toList();
        return new QueryModel.QueryResult(rows, page.total());
    }

    /**
     * The integration principal's bounded lookup (PHASE-6 §6): webhook upsert keys
     * resolve through the same query lowering as every list — metadata-typed filters,
     * no raw SQL. Reads, never mutations.
     */
    public QueryModel.QueryResult listAsIntegration(UUID tenantId, String entityApiName,
                                                    String queryJson) {
        EntityHandle handle = resolver.resolve(tenantId, entityApiName);
        QueryModel.ListQuery query = QueryParser.parseList(queryJson, handle.entity());
        QueryLowering lowering = new QueryLowering(handle.entity());
        QueryLowering.Lowered countSql = lowering.count(handle.entity().apiName(), tenantId,
                query.filter());
        QueryLowering.Lowered listSql = lowering.list(handle.entity().apiName(), tenantId, query);
        RecordStore.PageResult page = records.list(countSql.sql(), countSql.params(),
                listSql.sql(), listSql.params());
        return new QueryModel.QueryResult(
                page.rows().stream().map(row -> stripHidden(row, field -> false)).toList(),
                page.total());
    }

    // --- flow hooks (PHASE-3 §2) ---

    /**
     * State machines on the write path (PHASE-4 §3): metadata enforced like
     * validations, after the beforeSave hooks so hook-driven transitions ride the
     * same check — no bypass exists for flows, scripts, or humans.
     */
    private void enforceCreateState(AppDefinition app, EntityHandle handle,
                                    Map<String, Object> data) {
        var machine = app.stateMachineFor(handle.entity().apiName());
        if (machine.isEmpty()) {
            return;
        }
        Object current = data.get(machine.get().stateField());
        if (current == null) {
            data.put(machine.get().stateField(), machine.get().initial());
        } else if (!machine.get().initial().equals(current)) {
            throw new PlatformException(PlatformErrorCode.STATE_TRANSITION,
                    "create must start in the initial state " + machine.get().initial()
                            + ", got " + current);
        }
    }

    /** A changed state field requires a listed transition with a passing guard (§3). */
    private void enforceTransition(AppDefinition app, EntityHandle handle,
                                   Map<String, Object> existing, Map<String, Object> data) {
        var machine = app.stateMachineFor(handle.entity().apiName());
        if (machine.isEmpty()) {
            return;
        }
        String stateField = machine.get().stateField();
        Object from = existing.get(stateField);
        Object to = data.get(stateField);
        if (java.util.Objects.equals(from, to)) {
            return;
        }
        var transition = machine.get().transition(String.valueOf(from), String.valueOf(to));
        if (transition.isEmpty()) {
            // Terminal states admit no transitions at all (publish-validated) — this
            // is also the terminal-frozen-state rejection.
            throw new PlatformException(PlatformErrorCode.STATE_TRANSITION,
                    "no transition " + from + " → " + to + " on " + machine.get().id());
        }
        String guard = transition.get().guard();
        if (guard != null) {
            Object outcome = evaluate(handle.entity(), guard, data);
            if (!(outcome instanceof Boolean allowed) || !allowed) {
                throw new PlatformException(PlatformErrorCode.STATE_TRANSITION,
                        "transition " + from + " → " + to + " guard failed: " + guard);
            }
        }
    }

    /**
     * Period locking (PHASE-7 §3.2): a dated write resolves its period by date-range
     * lookup over the bound period entity (the resolved §8 pin — documents carry
     * dates, not period pointers); a date inside a {@code closedStatus} period rejects
     * with {@code PERIOD_LOCKED}. Runs after the beforeSave hooks, like the
     * state-machine check, so hook-dated writes ride the same gate. No period rows →
     * no locks (the absence of periods never blocks a tenant's writes); the period
     * entity is app metadata, its {@code CLOSED} status an authored value — the
     * platform reads the configuration, it never special-cases an app's enum.
     */
    private void enforcePeriodLock(UUID tenantId, AppDefinition app, EntityHandle handle,
                                   Map<String, Object> data) {
        EntityDefinition.PeriodLock lock = handle.entity().periodLock();
        if (lock == null) {
            return;
        }
        Object dateValue = data.get(lock.dateField());
        if (dateValue == null) {
            return;   // undated writes resolve no period; field-required rules own presence
        }
        String iso = String.valueOf(dateValue);
        String date = iso.length() > 10 ? iso.substring(0, 10) : iso;
        try {
            java.time.LocalDate.parse(date);
        } catch (RuntimeException malformed) {
            return;   // coercion reports malformed dates with its own error
        }
        EntityHandle periodHandle = resolver.resolve(tenantId, lock.entity());
        String queryJson = "{\"filter\":{\"and\":["
                + "{\"field\":\"" + lock.from() + "\",\"op\":\"lte\",\"value\":\"" + date + "\"},"
                + "{\"field\":\"" + lock.to() + "\",\"op\":\"gte\",\"value\":\"" + date + "\"},"
                + "{\"field\":\"" + lock.status() + "\",\"op\":\"eq\",\"value\":\"" + lock.closed() + "\"}]}}";
        QueryModel.ListQuery query = QueryParser.parseList(queryJson, periodHandle.entity());
        QueryLowering lowering = new QueryLowering(periodHandle.entity());
        QueryLowering.Lowered count = lowering.count(periodHandle.entity().apiName(), tenantId,
                query.filter());
        Long closed = records.countValue(count.sql(), count.params());
        if (closed != null && closed > 0) {
            throw new PlatformException(PlatformErrorCode.PERIOD_LOCKED,
                    handle.entity().apiName() + " is dated " + date + " — inside a closed "
                            + lock.entity() + " (PeriodLock, PHASE-7 §3.2); a closed period "
                            + "only reopens through its own audited flow");
        }
    }

    /** The per-app system principal declarative flows run as (§13 Q1 — audited). */
    private static UUID appSystemPrincipal(EntityHandle handle) {
        return UUID.nameUUIDFromBytes(("system:" + handle.appApiName()).getBytes());
    }

    // --- Phase 7 harvests: freezeOnTerminal (§3.1) + PeriodLock (§3.2) ---

    /**
     * {@code freezeOnTerminal} (PHASE-7 §3.1): a record whose bound machine sits in a
     * terminal state is an immutable document — every write to it (field updates,
     * deletes, an inline child array on a PATCH) rejects with {@code RECORD_FROZEN}.
     * Corrections are new reversal records, never edits. The check precedes everything
     * else on the update path — including roll-up evaluation — so nothing recomputes
     * a frozen document.
     */
    private void requireNotFrozen(AppDefinition app, EntityHandle handle,
                                  Map<String, Object> existing) {
        if (!handle.entity().freezesOnTerminal()) {
            return;
        }
        var machine = app.stateMachineFor(handle.entity().apiName());
        if (machine.isEmpty()) {
            return;   // save validation requires a machine; the runtime stays fail-open
        }           // on the pairing so republishing cannot wedge a tenant's writes
        String stateField = machine.get().stateField();
        Object state = existing.get(stateField);
        if (state != null && machine.get().isTerminal(String.valueOf(state))) {
            throw new PlatformException(PlatformErrorCode.RECORD_FROZEN,
                    handle.entity().apiName() + " is frozen — its state machine sits in the "
                            + "terminal state " + state
                            + " (freezeOnTerminal, PHASE-7 §3.1); corrections are reversal "
                            + "records, never edits");
        }
    }

    /**
     * The document scope of the freeze (§3.1): children are independently addressable
     * records (PHASE-1 §5), but the freeze covers the parent's whole document — a child
     * create, update, or delete naming a frozen parent (any lookup field targeting a
     * freeze-bound entity) rejects identically. Runs before roll-up evaluation, so a
     * child write never recomputes a frozen parent.
     */
    private void requireParentsNotFrozen(UUID tenantId, AppDefinition app, EntityHandle childHandle,
                                         Map<String, Object> childData) {
        for (FieldDefinition field : childHandle.entity().fields()) {
            if (field.type() != FieldType.LOOKUP) {
                continue;
            }
            var parentEntity = app.entity(field.target() == null ? "" : field.target());
            if (parentEntity.isEmpty() || !parentEntity.get().freezesOnTerminal()) {
                continue;
            }
            Object value = childData.get(field.apiName());
            if (value == null) {
                continue;
            }
            UUID parentId;
            try {
                parentId = UUID.fromString(String.valueOf(value));
            } catch (IllegalArgumentException notAUuid) {
                continue;   // coercion reports malformed lookups with its own error
            }
            String parentKey = childHandle.appApiName() + "." + field.target();
            records.find(tenantId, parentKey, parentId, false).ifPresent(parent ->
                    requireNotFrozen(app, new EntityHandle(childHandle.appId(),
                            childHandle.appApiName(), childHandle.version(), parentEntity.get(),
                            parentKey), parent.data()));
        }
    }

    private void runHooks(AppDefinition app, EntityHandle handle, UUID tenantId, UUID recordId,
                          Map<String, Object> data, String trigger, UUID systemPrincipal,
                          UUID initiatingActor) {
        if (handle.entity().hooks().isEmpty()) {
            return;
        }
        HookExecutor.Outcome outcome = hooks.runTrigger(app, handle, tenantId, recordId,
                data, trigger, systemPrincipal, initiatingActor, hookSink);
        // §2 failure policy: after-hook failures ride the spine as hook.retry outbox
        // rows — same transaction as the write, so the failure is never lost and the
        // spine's retry consumer re-drives it (idempotently, bounded).
        for (HookExecutor.Outcome.Retry retry : outcome.retryQueue()) {
            events.publish(new DomainEventPublisher.DomainEvent("hook.retry", tenantId,
                    handle.entityKey(), recordId, systemPrincipal, Instant.now().toString()),
                    Map.of("trigger", trigger, "hook", retry.hook(), "kind", retry.kind(),
                            "attempt", 1, "error", String.valueOf(retry.error())));
        }
    }

    /**
     * The suspension leg's re-entry (PHASE-4 §4): a resolved approval resumes the
     * compiled graph as the per-app system principal against the record's current
     * state. Callers bind the tenant context; failures surface to the Workflow
     * Service as problem+json (the instance stays resolvable, never silently lost).
     */
    public void resumeApproval(UUID tenantId, String entityApiName, UUID recordId,
                               String hookName, String afterStep,
                               com.novaforge.metadata.FlowStep onReject, boolean approved) {
        EntityHandle handle = resolver.resolve(tenantId, entityApiName);
        AppDefinition app = resolver.bundle(tenantId, handle.appId());
        HookRule hook = handle.entity().hooks().stream()
                .filter(h -> hookName.equals(h.name()))
                .findFirst()
                .orElseThrow(() -> new PlatformException(PlatformErrorCode.NOT_FOUND,
                        "hook " + hookName + " no longer exists in the published "
                                + "definition — the suspended flow cannot resume"));
        Map<String, Object> current = records.find(tenantId, handle.entityKey(), recordId,
                        true)
                .orElseThrow(() -> new PlatformException(PlatformErrorCode.NOT_FOUND,
                        entityApiName + "/" + recordId + " not found for resume"))
                .data();
        hooks.resumeFrom(app, handle, tenantId, recordId, current, hook, afterStep,
                onReject, approved, appSystemPrincipal(handle), hookSink);
    }

    /**
     * The Workflow Service's event-start read (PHASE-4 §9): the record's raw stored
     * fields for subscription-filter evaluation — system context (the resume
     * surface's read pattern), never shaped or user-stripped, never a mutation
     * (ADR-004 #2).
     */
    public Map<String, Object> recordForSubscription(UUID tenantId, String entityApiName,
                                                     UUID recordId) {
        EntityHandle handle = resolver.resolve(tenantId, entityApiName);
        return records.find(tenantId, handle.entityKey(), recordId, false)
                .map(RecordStore.StoredRecord::data)
                .orElseThrow(() -> new PlatformException(PlatformErrorCode.NOT_FOUND,
                        entityApiName + "/" + recordId + " not found"));
    }

    /**
     * The Scheduler's flow target (PHASE-4 §7): runs one named hook in the synthetic
     * {@code scheduled} trigger context — no record, empty bindings, the per-app
     * system principal. Flows here create records, publish events, or drive other
     * entities; own-record references resolve empty.
     */
    public void runScheduledHook(UUID tenantId, String entityApiName, String hookName) {
        EntityHandle handle = resolver.resolve(tenantId, entityApiName);
        AppDefinition app = resolver.bundle(tenantId, handle.appId());
        HookRule hook = handle.entity().hooks().stream()
                .filter(h -> hookName.equals(h.name()))
                .findFirst()
                .orElseThrow(() -> new PlatformException(PlatformErrorCode.NOT_FOUND,
                        "hook " + hookName + " not found on " + entityApiName));
        hooks.runTrigger(app, handle, tenantId, null, new LinkedHashMap<>(), "scheduled",
                appSystemPrincipal(handle), null, hookSink);
    }

    /** The retry leg's terminal dispositions (§2) — the scanner maps these to row states. */
    public enum RetryOutcome {

        /** The hook re-ran clean against the record's current state. */
        OK,

        /** The hook is gone from the published definition — retrying can never converge. */
        HOOK_GONE,

        /** The record is gone — nothing left to drive the hook against. */
        RECORD_GONE
    }

    /**
     * Re-drives one failed after-hook from the spine (§2 failure policy): the record's
     * current state, the per-app system principal, the standard hook sink — the same
     * context the original execution had. Callers bind the tenant context; failures
     * surface as {@link PlatformException} for the scanner's attempt bookkeeping.
     */
    public RetryOutcome retryAfterHook(UUID tenantId, String entityApiName, UUID recordId,
                                       String trigger, String hookName) {
        EntityHandle handle = resolver.resolve(tenantId, entityApiName);
        AppDefinition app = resolver.bundle(tenantId, handle.appId());
        boolean deletedTrigger = "afterDelete".equals(trigger);
        RecordStore.StoredRecord current = records.find(tenantId, handle.entityKey(),
                recordId, deletedTrigger).orElse(null);
        if (current == null && deletedTrigger) {
            return RetryOutcome.RECORD_GONE;   // hard-gone: nothing to re-drive against
        }
        if (current == null) {
            // afterSave retry on a since-deleted record: nothing left to observe.
            return records.find(tenantId, handle.entityKey(), recordId, true)
                    .map(gone -> gone.deleted() ? RetryOutcome.RECORD_GONE : null)
                    .orElseGet(() -> {
                        throw new PlatformException(PlatformErrorCode.NOT_FOUND,
                                entityApiName + "/" + recordId + " not found for hook retry");
                    });
        }
        boolean ran = hooks.runOneByName(app, handle, tenantId, recordId, current.data(),
                trigger, hookName, appSystemPrincipal(handle), hookSink);
        return ran ? RetryOutcome.OK : RetryOutcome.HOOK_GONE;
    }

    /**
     * The hook sink: nested engine writes as the flow's system principal (authorization
     * is the flow's, not a user's — §13 Q1), app events ride the outbox, iterate reads
     * children through the query path.
     */
    /** The engine as its own hook sink (plain adapter — no bean cycle). */
    private class EngineHookSink implements HookExecutor.HookSink {

        @Override
        public Map<String, Object> writeRecord(String entityApiName, Map<String, Object> body,
                                                String recordId, UUID systemPrincipal, int depth) {
            if (depth > HookExecutor.MAX_DEPTH) {
                throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                        "hook nesting exceeds depth " + HookExecutor.MAX_DEPTH);
            }
            if (recordId == null || "null".equals(recordId)) {
                return createAsPrincipal(entityApiName, body, systemPrincipal, depth);
            }
            return updateAsPrincipal(entityApiName, recordId, body, systemPrincipal, depth);
        }

        @Override
        public void publishAppEvent(String name, Map<String, Object> payload, UUID tenantId,
                                     String entityKey, UUID recordId, UUID systemPrincipal) {
            // App events ride the same spine as record events (family topic novaforge.<family>).
            events.publish(new DomainEventPublisher.DomainEvent(name, tenantId, entityKey,
                    recordId, systemPrincipal, Instant.now().toString()));
        }

        @Override
        public List<Map<String, Object>> children(UUID tenantId, String appApiName,
                                                  String parentEntityApiName, String relationship,
                                                  UUID parentRecordId) {
            EntityHandle parent = resolver.resolve(tenantId, parentEntityApiName);
            AppDefinition app = resolver.bundle(tenantId, parent.appId());
            EntityHandle childHandle = childHandle(tenantId, app, parent, relationship);
            String bindingField = bindingLookupField(parent.entity(), childHandle.entity());
            return currentChildren(tenantId, childHandle, bindingField, parentRecordId)
                    .stream().map(stored -> {
                        Map<String, Object> row = new LinkedHashMap<>(stored.data());
                        row.put("id", stored.id().toString());
                        return row;
                    }).toList();
        }
    }

    /** Nested create as the system principal: full write path, matrix bypassed (§13 Q1). */
    Map<String, Object> createAsPrincipal(String entityApiName, Map<String, Object> body,
                                           UUID systemPrincipal, int depth) {
        var ctx = TenantContext.current().orElseThrow();
        UUID tenantId = UUID.fromString(ctx.tenantId());
        EntityHandle handle = resolver.resolve(tenantId, entityApiName);
        AppDefinition app = resolver.bundle(tenantId, handle.appId());
        List<ProblemErrors.FieldError> errors = new ArrayList<>();
        Map<String, Object> fields = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> children = new LinkedHashMap<>();
        splitChildren(handle.entity(), body, children, fields, errors);
        applyDefaults(tenantId, app, handle.entity(), fields, errors);
        Map<String, Object> canonical = FieldCoercer.canonicalize(handle.entity(), fields,
                externalChecks(tenantId, handle, app), null, errors);
        FieldCoercer.checkRequired(handle.entity(), canonical, errors);
        evaluateFormulas(handle.entity(), canonical);
        evaluateValidationRules(handle.entity(), canonical, errors);
        reject(errors, "hook create " + entityApiName + " failed validation");
        UUID id = UUID.randomUUID();
        requireParentsNotFrozen(tenantId, app, handle, canonical);
        enforceCreateState(app, handle, canonical);
        enforcePeriodLock(tenantId, app, handle, canonical);
        persistWithChildren(tenantId, systemPrincipal, app, handle, id, canonical, children, errors);
        events.publish(event("record.created", tenantId, handle.entityKey(), id, systemPrincipal));
        return canonical;
    }

    /** Nested update as the system principal (flow-driven field writes). */
    Map<String, Object> updateAsPrincipal(String entityApiName, String recordId,
                                          Map<String, Object> body, UUID systemPrincipal,
                                          int depth) {
        var ctx = TenantContext.current().orElseThrow();
        UUID tenantId = UUID.fromString(ctx.tenantId());
        EntityHandle handle = resolver.resolve(tenantId, entityApiName);
        AppDefinition app = resolver.bundle(tenantId, handle.appId());
        RecordStore.StoredRecord existing = records.find(tenantId, handle.entityKey(),
                        UUID.fromString(recordId), false)
                .orElseThrow(() -> new PlatformException(PlatformErrorCode.NOT_FOUND,
                        entityApiName + "/" + recordId + " not found"));
        requireNotFrozen(app, handle, existing.data());
        Map<String, Object> merged = new LinkedHashMap<>(existing.data());
        body.forEach((key, value) -> {
            if (!key.equals("version") && handle.entity().field(key).isPresent()) {
                merged.put(key, value);
            }
        });
        evaluateFormulas(handle.entity(), merged);
        requireParentsNotFrozen(tenantId, app, handle, merged);
        enforceTransition(app, handle, existing.data(), merged);
        enforcePeriodLock(tenantId, app, handle, merged);
        int version = body.get("version") instanceof Number number ? number.intValue()
                : existing.version();
        records.update(tenantId, handle.entityKey(), UUID.fromString(recordId), merged,
                version, systemPrincipal);
        events.publish(event("record.updated", tenantId, handle.entityKey(),
                UUID.fromString(recordId), systemPrincipal));
        return merged;
    }

    // --- roll-up summaries (PHASE-3 §3) ---

    /**
     * Roll-up summaries (PHASE-3 §3): parent aggregates over child collections,
     * recomputed in the child's write transaction — synchronous, in-transaction,
     * consistency wins in v1 (§13 Q2). Creates aggregate the in-memory child set
     * before the insert (no extra write); updates recompute from the store and only
     * rewrite the parent when a roll-up value actually moved (no version churn).
     */
    private void evaluateRollupsFromChildren(AppDefinition app, EntityHandle parent,
                                             Map<String, List<Map<String, Object>>> children,
                                             Map<String, Object> parentData) {
        for (FieldDefinition field : parent.entity().fields()) {
            if (field.rollup() == null) {
                continue;
            }
            Rollup rollup = Rollup.parse(field.rollup());
            List<Map<String, Object>> childRows = children.getOrDefault(rollup.relationship(),
                    List.of());
            parentData.put(field.apiName(), rollup.aggregateInMemory(childRows));
        }
    }

    private int recomputeRollupsIfChanged(UUID tenantId, UUID actorId, AppDefinition app,
                                          EntityHandle parent, UUID parentId,
                                          Map<String, Object> parentData, int currentVersion) {
        boolean changed = false;
        for (FieldDefinition field : parent.entity().fields()) {
            if (field.rollup() == null) {
                continue;
            }
            Rollup rollup = Rollup.parse(field.rollup());
            EntityHandle childHandle = childHandle(tenantId, app, parent, rollup.relationship());
            String bindingField = bindingLookupField(parent.entity(), childHandle.entity());
            Object aggregate = rollup.aggregate(records, tenantId, childHandle, bindingField,
                    parentId, rollup.field());
            if (!java.util.Objects.equals(aggregate, parentData.get(field.apiName()))) {
                parentData.put(field.apiName(), aggregate);
                changed = true;
            }
        }
        if (changed) {
            return records.update(tenantId, parent.entityKey(), parentId, parentData,
                    currentVersion, actorId);
        }
        return currentVersion;
    }

    /** {@code SUM(lines.debit)} — aggregate op, relationship, child field. */
    record Rollup(String op, String relationship, String field) {

        static Rollup parse(String source) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("^(SUM|COUNT|MIN|MAX|AVG)\\(([a-zA-Z]+)(?:\\.([a-zA-Z]+))?\\)$")
                    .matcher(source.trim());
            if (!matcher.matches() || ("COUNT".equals(matcher.group(1)) == false
                    ? matcher.group(3) == null : false)) {
                throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                        "rollup must be OP(relationship.field) — COUNT(relationship) also allowed: " + source);
            }
            return new Rollup(matcher.group(1), matcher.group(2), matcher.group(3));
        }

        /** Aggregates the in-memory inline child set (create path — no store round-trip). */
        Object aggregateInMemory(List<Map<String, Object>> childRows) {
            if (op.equals("COUNT")) {
                return java.math.BigDecimal.valueOf(childRows.size());
            }
            java.math.BigDecimal result = op.equals("SUM") ? java.math.BigDecimal.ZERO : null;
            java.math.BigDecimal min = null;
            java.math.BigDecimal max = null;
            long count = 0;
            for (Map<String, Object> row : childRows) {
                Object value = row.get(field);
                java.math.BigDecimal decimal = value instanceof java.math.BigDecimal big
                        ? big : value instanceof Number number
                        ? new java.math.BigDecimal(number.toString()) : null;
                if (decimal == null) {
                    continue;
                }
                count++;
                result = result == null ? decimal : result.add(decimal);
                min = min == null ? decimal : min.min(decimal);
                max = max == null ? decimal : max.max(decimal);
            }
            return switch (op) {
                case "SUM" -> result;
                case "AVG" -> count == 0 ? null
                        : result.divide(java.math.BigDecimal.valueOf(count), Expression.MATH);
                case "MIN" -> min;
                case "MAX" -> max;
                default -> null;
            };
        }

        Object aggregate(RecordStore records, UUID tenantId, EntityHandle childHandle,
                         String bindingField, UUID parentId, String field) {
            String queryJson = "{\"filter\":{\"field\":\"" + bindingField + "\",\"op\":\"eq\","
                    + "\"value\":\"" + parentId + "\"}"
                    + (op.equals("COUNT") ? "" : ",\"aggregates\":[{\"op\":\"" + op.toLowerCase()
                    + "\",\"field\":\"" + field + "\"}]") + "}";
            if (op.equals("COUNT")) {
                QueryModel.ListQuery query = QueryParser.parseList(queryJson, childHandle.entity());
                QueryLowering lowering = new QueryLowering(childHandle.entity());
                QueryLowering.Lowered count = lowering.count(childHandle.entity().apiName(),
                        tenantId, query.filter());
                Long total = records.countValue(count.sql(), count.params());
                return java.math.BigDecimal.valueOf(total == null ? 0 : total);
            }
            QueryModel.AggregateQuery query = QueryParser.parseAggregate(queryJson, childHandle.entity());
            QueryLowering.Lowered lowered = new QueryLowering(childHandle.entity())
                    .aggregate(childHandle.entity().apiName(), tenantId, query);
            Object outcome = records.aggregateValue(lowered.sql(), lowered.params(),
                    query.aggregates().getFirst().op().name().toLowerCase()
                            + "_" + Snake.caseName(field));
            if (outcome == null) {
                // SUM over an empty set is its identity (0); AVG/MIN/MAX stay null.
                return op.equals("SUM") ? java.math.BigDecimal.ZERO : null;
            }
            return outcome instanceof java.math.BigDecimal decimal ? decimal
                    : new java.math.BigDecimal(String.valueOf(outcome));
        }
    }

    // --- Phase 3 expression evaluation (§3) ---

    /**
     * Formula fields: own-record expressions evaluated at write time and stored, never
     * computed on read; implicitly readonly (Phase 1 rule rejects app writes).
     */
    private void evaluateFormulas(EntityDefinition entity, Map<String, Object> data) {
        for (FieldDefinition field : entity.fields()) {
            if (field.formula() == null) {
                continue;
            }
            Object value = evaluate(entity, field.formula(), data);
            data.put(field.apiName(), canonicalizeValue(value));
        }
    }

    /**
     * Validation rules: record-scope expressions extending the Phase 1 field
     * constraints; a false (or null) predicate fails with the rule's message.
     */
    private void evaluateValidationRules(EntityDefinition entity, Map<String, Object> data,
                                         List<ProblemErrors.FieldError> errors) {
        for (EntityDefinition.ValidationRule rule : entity.validations()) {
            Object outcome = evaluate(entity, rule.expression(), data);
            if (!(outcome instanceof Boolean b) || !b) {
                errors.add(new ProblemErrors.FieldError(
                        entity.apiName() + ".validations",
                        rule.message() != null ? rule.message()
                                : "validation rule failed: " + rule.name(),
                        rule.expression()));
            }
        }
    }

    private Object evaluate(EntityDefinition entity, String source, Map<String, Object> data) {
        Expression expression = Expression.parse(source);
        Object outcome = expression.evaluate(Expression.Bindings.of(data), clock);
        return outcome;
    }

    /** Storage-canonical BigDecimal for numeric expression outcomes. */
    private static Object canonicalizeValue(Object value) {
        return value instanceof java.math.BigDecimal decimal ? decimal : value;
    }

    private final java.time.Clock clock = java.time.Clock.systemUTC();

    // --- defaults + children ---

    private void applyDefaults(UUID tenantId, AppDefinition app, EntityDefinition entity,
                               Map<String, Object> fields, List<ProblemErrors.FieldError> errors) {
        for (FieldDefinition field : entity.fields()) {
            if (fields.containsKey(field.apiName()) || field.defaultValue() == null) {
                continue;
            }
            DefaultValue defaultValue = field.defaultValue();
            if (defaultValue instanceof DefaultValue.SequenceReference ref) {
                var sequence = app.settings().sequence(ref.sequence());
                if (sequence.isPresent()) {
                    // Drawn once at create, in the write path's defaults step, before
                    // validations — the only authored surface that draws (PHASE-1 §5).
                    fields.put(field.apiName(), sequences.draw(tenantId, app.apiName(), sequence.get()));
                } else {
                    errors.add(new ProblemErrors.FieldError(field.apiName(),
                            "sequence no longer resolves: " + ref.sequence(), null));
                }
            } else if (defaultValue instanceof DefaultValue.Static statische) {
                Object value = statische.value();
                if (value != null) {
                    fields.put(field.apiName(), value);
                }
            } else if (defaultValue instanceof DefaultValue.ExpressionDefault expression) {
                // Evaluated at the defaults step, before validations (PHASE-3 §3) —
                // clock functions are compile-rejected here (a stored value goes stale).
                fields.put(field.apiName(), evaluate(entity, expression.expression(), fields));
            }
        }
    }

    private void splitChildren(EntityDefinition entity, Map<String, Object> body,
                               Map<String, List<Map<String, Object>>> children,
                               Map<String, Object> fields, List<ProblemErrors.FieldError> errors) {
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            var relationship = entity.relationship(entry.getKey());
            if (relationship.isPresent()) {
                if (!(entry.getValue() instanceof List<?> list)) {
                    errors.add(new ProblemErrors.FieldError(entry.getKey(),
                            "relationship value must be an array", entry.getValue()));
                    continue;
                }
                if (list.size() > MAX_INLINE_CHILDREN) {
                    errors.add(new ProblemErrors.FieldError(entry.getKey(),
                            "inline children capped at " + MAX_INLINE_CHILDREN + " per request — use /batch",
                            list.size()));
                    continue;
                }
                List<Map<String, Object>> childMaps = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        childMaps.add(typedFields(map));
                    } else {
                        errors.add(new ProblemErrors.FieldError(entry.getKey(),
                                "child items must be objects", item));
                    }
                }
                children.put(entry.getKey(), childMaps);
            } else {
                fields.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private void persistWithChildren(UUID tenantId, UUID actorId, AppDefinition app,
                                     EntityHandle parent, UUID parentId,
                                     Map<String, Object> parentData,
                                     Map<String, List<Map<String, Object>>> children,
                                     List<ProblemErrors.FieldError> errors) {
        try {
            records.insert(tenantId, parent.entityKey(), parentId, parentData, actorId);
        } catch (DataIntegrityViolationException e) {
            // The partial unique index is the enforcement; shape the friendly error (§6).
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "create violates a uniqueness rule", ProblemErrors.of(
                            new ProblemErrors.GlobalError(parent.entity().apiName(),
                                    "unique constraint violated: " + rootMessage(e))), e);
        }
        for (Map.Entry<String, List<Map<String, Object>>> entry : children.entrySet()) {
            EntityHandle childHandle = childHandle(tenantId, app, parent, entry.getKey());
            String bindingField = bindingLookupField(parent.entity(), childHandle.entity());
            for (Map<String, Object> childBody : entry.getValue()) {
                Map<String, Object> childFields = new LinkedHashMap<>(childBody);
                childFields.put(bindingField, parentId.toString());
                Map<String, Object> canonicalChild = FieldCoercer.canonicalize(
                        childHandle.entity(), childFields,
                        externalChecks(tenantId, childHandle, app), null, errors);
                FieldCoercer.checkRequired(childHandle.entity(), canonicalChild, errors);
                UUID childId = UUID.randomUUID();
                // An inline child may name frozen parents through its own lookup fields
                // (the §3.1 document scope) — the check rides child inserts too.
                requireParentsNotFrozen(tenantId, app, childHandle, canonicalChild);
                records.insert(tenantId, childHandle.entityKey(), childId, canonicalChild, actorId);
                events.publish(event("record.created", tenantId, childHandle.entityKey(), childId, actorId));
            }
        }
        reject(errors, "inline children failed validation");
    }

    private void replaceChildren(UUID tenantId, UUID actorId, AppDefinition app,
                                 EntityHandle parent, UUID parentId,
                                 Map<String, List<Map<String, Object>>> children) {
        for (Map.Entry<String, List<Map<String, Object>>> entry : children.entrySet()) {
            EntityHandle childHandle = childHandle(tenantId, app, parent, entry.getKey());
            String bindingField = bindingLookupField(parent.entity(), childHandle.entity());
            // Replace semantics for the inline array: soft-delete current children, then
            // insert the new set — atomically in the parent's transaction.
            List<RecordStore.StoredRecord> current = currentChildren(tenantId, childHandle,
                    bindingField, parentId);
            for (RecordStore.StoredRecord existing : current) {
                records.softDelete(tenantId, childHandle.entityKey(), existing.id(),
                        existing.version(), actorId);
                events.publish(event("record.deleted", tenantId, childHandle.entityKey(),
                        existing.id(), actorId));
            }
            List<ProblemErrors.FieldError> errors = new ArrayList<>();
            for (Map<String, Object> childBody : entry.getValue()) {
                Map<String, Object> childFields = new LinkedHashMap<>(childBody);
                childFields.put(bindingField, parentId.toString());
                Map<String, Object> canonicalChild = FieldCoercer.canonicalize(
                        childHandle.entity(), childFields,
                        externalChecks(tenantId, childHandle, app), null, errors);
                FieldCoercer.checkRequired(childHandle.entity(), canonicalChild, errors);
                // An inline child may name frozen parents through its own lookup fields
                // (the §3.1 document scope) — the check rides child inserts too.
                requireParentsNotFrozen(tenantId, app, childHandle, canonicalChild);
                UUID childId = UUID.randomUUID();
                records.insert(tenantId, childHandle.entityKey(), childId, canonicalChild, actorId);
                events.publish(event("record.created", tenantId, childHandle.entityKey(), childId, actorId));
            }
            reject(errors, "inline children failed validation");
        }
    }

    private void cascadeChildren(UUID tenantId, UUID actorId, AppDefinition app,
                                 EntityHandle parent, UUID parentId) {
        for (RelationshipDefinition relationship : parent.entity().relationships()) {
            if (relationship.type() != RelationshipType.CHILD || !relationship.cascadeOn()) {
                continue;
            }
            EntityHandle childHandle = childHandle(tenantId, app, parent, relationship.apiName());
            String bindingField = bindingLookupField(parent.entity(), childHandle.entity());
            for (RecordStore.StoredRecord child : currentChildren(tenantId, childHandle,
                    bindingField, parentId)) {
                records.softDelete(tenantId, childHandle.entityKey(), child.id(),
                        child.version(), actorId);
                events.publish(event("record.deleted", tenantId, childHandle.entityKey(),
                        child.id(), actorId));
            }
        }
    }

    private List<RecordStore.StoredRecord> currentChildren(UUID tenantId, EntityHandle childHandle,
                                                           String bindingField, UUID parentId) {
        String queryJson = "{\"filter\":{\"field\":\"" + bindingField + "\",\"op\":\"eq\","
                + "\"value\":\"" + parentId + "\"}}";
        QueryModel.ListQuery query = QueryParser.parseList(queryJson, childHandle.entity());
        QueryLowering lowering = new QueryLowering(childHandle.entity());
        QueryLowering.Lowered countSql = lowering.count(childHandle.entity().apiName(), tenantId,
                query.filter());
        QueryLowering.Lowered listSql = lowering.list(childHandle.entity().apiName(), tenantId, query);
        RecordStore.PageResult page = records.list(countSql.sql(), countSql.params(),
                listSql.sql(), listSql.params());
        List<RecordStore.StoredRecord> children = new ArrayList<>();
        for (Map<String, Object> row : page.rows()) {
            records.find(tenantId, childHandle.entityKey(), (UUID) row.get("id"), false)
                    .ifPresent(children::add);
        }
        return children;
    }

    private EntityHandle childHandle(UUID tenantId, AppDefinition app, EntityHandle parent,
                                     String relationshipName) {
        RelationshipDefinition relationship = parent.entity().relationship(relationshipName)
                .orElseThrow(() -> new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                        "unknown relationship: " + relationshipName));
        EntityDefinition child = app.entity(relationship.target()).orElseThrow(
                () -> new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                        "relationship target missing from app: " + relationship.target()));
        return new EntityHandle(parent.appId(), parent.appApiName(), parent.version(), child,
                parent.appApiName() + "." + child.apiName());
    }

    /** The child's lookup field binding it to the parent (validated at save, §3). */
    private static String bindingLookupField(EntityDefinition parent, EntityDefinition child) {
        return child.fields().stream()
                .filter(f -> f.type() == FieldType.LOOKUP && parent.apiName().equals(f.target()))
                .map(FieldDefinition::apiName)
                .findFirst()
                .orElseThrow(() -> new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                        child.apiName() + " lacks a lookup field targeting " + parent.apiName()));
    }

    private FieldCoercer.ExternalChecks externalChecks(UUID tenantId, EntityHandle handle,
                                                       AppDefinition app) {
        return new FieldCoercer.ExternalChecks() {
            @Override
            public boolean targetExists(String targetEntityApiName, UUID targetId) {
                String targetKey = handle.appApiName() + "." + targetEntityApiName;
                return records.targetExists(tenantId, targetKey, targetId);
            }

            @Override
            public boolean valueIsUnique(EntityDefinition entity, FieldDefinition field,
                                         String canonicalText, UUID excludeRecordId) {
                return !records.valueExists(tenantId, handle.appApiName() + "." + entity.apiName(),
                        field.apiName(), canonicalText, excludeRecordId);
            }
        };
    }

    // --- helpers ---

    private static void rejectReadonlyWrites(EntityDefinition entity, Map<String, Object> body) {
        for (String key : body.keySet()) {
            var field = entity.field(key);
            if (field.isPresent() && field.get().readonlyOn()) {
                throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                        "field is readonly: " + key, ProblemErrors.of(
                                new ProblemErrors.FieldError(key, "field is readonly", key)));
            }
        }
    }

    private static void reject(List<ProblemErrors.FieldError> errors, String message) {
        if (!errors.isEmpty()) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED, message,
                    new ProblemErrors(errors, List.of()));
        }
    }

    private static DomainEventPublisher.DomainEvent event(String type, UUID tenantId,
                                                          String entityKey, UUID id, UUID actorId) {
        return new DomainEventPublisher.DomainEvent(type, tenantId, entityKey, id, actorId,
                Instant.now().toString());
    }

    /**
     * Field security (PHASE-2 §9): the predicate reports fields hidden for this actor —
     * projections strip them server-side (enforcement; the UI only renders).
     */
    private java.util.function.Predicate<String> strip(UUID tenantId, UUID actorId,
                                                       EntityHandle handle, AppDefinition app) {
        return field -> com.novaforge.metadata.PermissionSet.FieldSecurity.HIDDEN.equals(
                roleMatrix.fieldAccess(tenantId, actorId, handle.appApiName(),
                        app.permissionSet(), handle.entity().apiName(), field));
    }

    private static Map<String, Object> stripHidden(Map<String, Object> row,
                                                   java.util.function.Predicate<String> hidden) {
        Map<String, Object> shaped = new LinkedHashMap<>();
        row.forEach((key, value) -> {
            if (!hidden.test(key)) {
                shaped.put(key, value);
            }
        });
        return shaped;
    }

    /** Writes to PermissionSet-hidden or readonly fields reject (server-side, §9). */
    private void rejectFieldSecurityWrites(UUID tenantId, UUID actorId, EntityHandle handle,
                                           AppDefinition app, Map<String, Object> body) {
        for (String key : body.keySet()) {
            String access = roleMatrix.fieldAccess(tenantId, actorId, handle.appApiName(),
                    app.permissionSet(), handle.entity().apiName(), key);
            if (PermissionSet.FieldSecurity.HIDDEN.equals(access)
                    || PermissionSet.FieldSecurity.READONLY.equals(access)) {
                throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                        "field is " + access + " for this role: " + key, ProblemErrors.of(
                                new ProblemErrors.FieldError(key, "field is " + access, key)));
            }
        }
    }

    /** Shaped projection: system fields + entity fields, hidden fields stripped (§5/§9). */
    private Map<String, Object> shape(EntityDefinition entity, RecordStore.StoredRecord record,
                                      java.util.function.Predicate<String> hidden) {
        Map<String, Object> shaped = new LinkedHashMap<>();
        shaped.put("id", record.id().toString());
        shaped.put("version", record.version());
        shaped.put("createdAt", record.createdAt());
        shaped.put("updatedAt", record.updatedAt());
        shaped.put("createdBy", record.createdBy().toString());
        shaped.put("updatedBy", record.updatedBy().toString());
        record.data().forEach((key, value) -> {
            if (!hidden.test(key)) {
                shaped.put(key, value);
            }
        });
        return shaped;
    }

    private static PlatformException notFound(String entityApiName, UUID id) {
        return new PlatformException(PlatformErrorCode.NOT_FOUND,
                entityApiName + "/" + id + " not found");
    }

    private static String rootMessage(Exception e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> typedFields(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED, "expected an object");
    }
}
