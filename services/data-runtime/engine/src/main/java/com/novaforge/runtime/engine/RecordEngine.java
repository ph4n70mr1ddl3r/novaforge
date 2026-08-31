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
import com.novaforge.metadata.RollupExpression;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The write/query pipeline (PHASE-1 §5 — the Phase 1 slice of ARCHITECTURE.md §2.4):
 * resolve metadata → authorize → apply defaults (static + sequence references drawn once
 * at create) → field validations → inline children (atomic, ≤100) → persist with
 * optimistic locking → event seam → shaped projection. Lists/aggregates lower the query
 * DSL; batch runs per-item outcomes.
 */
@Service
public class RecordEngine {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(RecordEngine.class);


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
    /** The engine's own proxy, injected as a provider (no eager self-reference
     *  cycle): {@link #batch} routes each item through it so the per-item
     *  @Transactional boundaries apply — this-side calls would bypass the proxy and
     *  leave every statement to auto-commit (found in the 2025-08-27 review). */
    private final org.springframework.beans.factory.ObjectProvider<RecordEngine> self;

    public RecordEngine(EntityResolver resolver, RoleMatrix roleMatrix,
                        com.novaforge.runtime.authorization.SharingGate sharing,
                        RecordStore records,
                        SequenceService sequences, DomainEventPublisher events,
                        HookExecutor hooks,
                        org.springframework.beans.factory.ObjectProvider<RecordEngine> self) {
        this.resolver = resolver;
        this.roleMatrix = roleMatrix;
        this.sharing = sharing;
        this.records = records;
        this.sequences = sequences;
        this.events = events;
        this.hooks = hooks;
        this.hookSink = new EngineHookSink();
        this.self = self;
    }

    // --- write path ---

    @Transactional
    public Map<String, Object> create(UUID tenantId, UUID actorId, String entityApiName,
                                      Map<String, Object> body) {
        EntityHandle handle = resolver.resolve(tenantId, entityApiName);
        AppDefinition app = resolver.bundle(tenantId, handle.appId());
        roleMatrix.require(tenantId, actorId, RoleMatrix.Action.CREATE, handle.entity().apiName(),
                handle.appApiName(), app.permissionSet());
        // §9 parity with the update door: a role that cannot read or write a field
        // (field-security hidden, metadata readonly) must not seed it through the
        // create door either — values laundered in at create could never be seen or
        // corrected by that role afterwards
        rejectReadonlyWrites(handle.entity(), body);
        rejectFieldSecurityWrites(tenantId, actorId, handle, app, body);

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
        // guards before hooks: a write doomed by freeze/period must not fire its
        // hooks' external side effects (connector calls, approval tasks) first
        requireParentsNotFrozen(tenantId, app, handle, canonical);
        enforcePeriodLock(tenantId, app, handle, canonical);
        runHooks(app, handle, tenantId, id, canonical, "beforeSave", appSystemPrincipal(handle), actorId, null);
        reCanonicalizeHookWrites(tenantId, app, handle, canonical, id);
        // the initial-state guard validates the state the record LANDS in — hook
        // writes included (a transitionState hook cannot smuggle a non-initial state)
        enforceCreateState(app, handle, canonical);
        evaluateRollupsFromChildren(app, handle, children, canonical);
        persistWithChildren(tenantId, actorId, app, handle, id, canonical, children, errors);

        events.publish(event("record.created", tenantId, handle.entityKey(), id, actorId));
        recomputeParentRollups(tenantId, actorId, app, handle, canonical, null);
        runHooks(app, handle, tenantId, id, canonical, "afterSave", appSystemPrincipal(handle), actorId, null);
        return shape(handle.entity(), records.find(tenantId, handle.entityKey(), id, false)
                .orElseThrow(), strip(tenantId, actorId, handle, app));
    }

    @Transactional
    public Map<String, Object> update(UUID tenantId, UUID actorId, String entityApiName,
                                      UUID id, int expectedVersion, Map<String, Object> body) {
        EntityHandle handle = resolver.resolve(tenantId, entityApiName);
        AppDefinition app = resolver.bundle(tenantId, handle.appId());
        roleMatrix.require(tenantId, actorId, RoleMatrix.Action.UPDATE, handle.entity().apiName(),
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
        String transition = transitionOf(app, handle, existing.data(), merged);
        runHooks(app, handle, tenantId, id, merged, "beforeSave", appSystemPrincipal(handle), actorId, transition);
        reCanonicalizeHookWrites(tenantId, app, handle, merged, id);
        requireParentsNotFrozen(tenantId, app, handle, merged);
        enforceTransition(app, handle, existing.data(), merged);
        enforcePeriodLock(tenantId, app, handle, merged);

        int newVersion = updateShaped(tenantId, actorId, handle, id, merged,
                expectedVersion, "update");
        Map<String, Object> before = existing.data();
        replaceChildren(tenantId, actorId, app, handle, id, children);
        newVersion = recomputeRollupsIfChanged(tenantId, actorId, app, handle, id, merged,
                newVersion, false);
        events.publish(event("record.updated", tenantId, handle.entityKey(), id, actorId),
                changeMetadata(before, merged, strip(tenantId, actorId, handle, app)));
        recomputeParentRollups(tenantId, actorId, app, handle, merged, existing.data());
        runHooks(app, handle, tenantId, id, merged, "afterSave", appSystemPrincipal(handle), actorId, transition);

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
        roleMatrix.require(tenantId, actorId, RoleMatrix.Action.DELETE, handle.entity().apiName(),
                handle.appApiName(), app.permissionSet());

        RecordStore.StoredRecord existingRecord = records.find(tenantId, handle.entityKey(),
                id, false).orElseThrow(() -> notFound(entityApiName, id));
        requireVisible(sharing.forActor(tenantId, actorId, handle.entity(), app), existingRecord);
        Map<String, Object> existingData = existingRecord.data();
        requireNotFrozen(app, handle, existingData);
        requireParentsNotFrozen(tenantId, app, handle, existingData);
        runHooks(app, handle, tenantId, id, existingData, "beforeDelete",
                appSystemPrincipal(handle), actorId, null);
        records.softDelete(tenantId, handle.entityKey(), id, expectedVersion, actorId);
        cascadeChildren(tenantId, actorId, app, handle, id);
        events.publish(event("record.deleted", tenantId, handle.entityKey(), id, actorId),
                deletedMetadata(existingData, strip(tenantId, actorId, handle, app)));
        recomputeParentRollups(tenantId, actorId, app, handle, existingData, existingData);
        runHooks(app, handle, tenantId, id, existingData, "afterDelete",
                appSystemPrincipal(handle), actorId, null);
    }

    // --- read path ---

    public Map<String, Object> get(UUID tenantId, UUID actorId, String entityApiName, UUID id,
                                   boolean includeDeleted) {
        EntityHandle handle = resolver.resolve(tenantId, entityApiName);
        AppDefinition app = resolver.bundle(tenantId, handle.appId());
        roleMatrix.require(tenantId, actorId, RoleMatrix.Action.READ, handle.entity().apiName(),
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
        roleMatrix.require(tenantId, actorId, RoleMatrix.Action.READ, handle.entity().apiName(),
                handle.appApiName(), app.permissionSet());
        QueryModel.ListQuery query = QueryParser.parseList(queryJson, handle.entity());
        requireFilterFieldsVisible(tenantId, actorId, handle, app, query.filter());
        QueryLowering lowering = new QueryLowering(handle.entity());
        QueryLowering.Lowered countSql = lowering.count(handle.entity().apiName(), tenantId,
                query.filter());
        QueryLowering.Lowered listSql = lowering.list(handle.entity().apiName(), tenantId, query);
        // Sharing lowers into the pipeline exactly as the aggregate path does
        // (PHASE-5 §4): owners as created_by IN (…), criteria as compiled boolean
        // SQL — one OR over both. The old shape (owner set in SQL, criteria
        // post-filtered in Java over the fetched page) skewed pagination: rows
        // visible only by criteria sat beyond the page window forever, and `total`
        // counted rows the filter then removed (the 2025-08-27 review closed it).
        // A criterion that cannot lower fails closed — the same stance reports
        // already carry (applySharing).
        var restriction = sharing.forActor(tenantId, actorId, handle.entity(), app);
        countSql = applySharing(countSql, handle, restriction);
        listSql = applySharing(listSql, handle, restriction);
        RecordStore.PageResult page = records.list(countSql.sql(), countSql.params(),
                listSql.sql(), listSql.params());
        java.util.function.Predicate<String> strip = strip(tenantId, actorId, handle, app);
        List<Map<String, Object>> rows = page.rows().stream()
                .map(row -> stripHidden(row, strip))
                .toList();
        return new QueryModel.QueryResult(rows, page.total());
    }

    public QueryModel.AggregateResult aggregate(UUID tenantId, UUID actorId, String entityApiName,
                                                String queryJson) {
        EntityHandle handle = resolver.resolve(tenantId, entityApiName);
        AppDefinition app = resolver.bundle(tenantId, handle.appId());
        roleMatrix.require(tenantId, actorId, RoleMatrix.Action.READ, handle.entity().apiName(),
                handle.appApiName(), app.permissionSet());
        QueryModel.AggregateQuery query = QueryParser.parseAggregate(queryJson, handle.entity());
        requireFilterFieldsVisible(tenantId, actorId, handle, app, query.filter());
        // aggregates leak values, not rows — hidden group-by/aggregate fields fail closed
        for (QueryModel.GroupBy group : query.groupBy()) {
            requireFieldVisible(roleMatrix.fieldAccess(tenantId, actorId, handle.appApiName(),
                    app.permissionSet(), handle.entity().apiName(), group.field()),
                    handle.entity().apiName(), group.field());
        }
        for (QueryModel.Aggregate aggregate : query.aggregates()) {
            if (aggregate.field() != null) {
                requireFieldVisible(roleMatrix.fieldAccess(tenantId, actorId, handle.appApiName(),
                        app.permissionSet(), handle.entity().apiName(), aggregate.field()),
                        handle.entity().apiName(), aggregate.field());
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
                .filter(p -> p.entity().equals(handle.entity().apiName()))
                .filter(p -> p.role().equals(asRole))
                .anyMatch(p -> p.allows("read"));
        if (!granted) {
            throw new PlatformException(PlatformErrorCode.FORBIDDEN,
                    "role " + asRole + " is not granted read on " + entityApiName);
        }
        QueryModel.AggregateQuery query = QueryParser.parseAggregate(queryJson, handle.entity());
        requireFilterFieldsVisibleForRole(app, handle, asRole, query.filter());
        for (QueryModel.GroupBy group : query.groupBy()) {
            requireFieldVisible(roleFieldAccess(app, handle.entity().apiName(), group.field(), asRole),
                    handle.entity().apiName(), group.field());
        }
        for (QueryModel.Aggregate aggregate : query.aggregates()) {
            if (aggregate.field() != null) {
                requireFieldVisible(
                        roleFieldAccess(app, handle.entity().apiName(), aggregate.field(), asRole),
                        handle.entity().apiName(), aggregate.field());
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
                    "field " + entityApiName + "." + field + " is hidden — queries fail closed");
        }
    }

    /**
     * Filter fields fail closed exactly like group-by and aggregate fields: a hidden
     * field in a filter is a value oracle — row counts, totals, and chart shapes
     * answer questions about values the caller cannot read (binary-searchable).
     */
    private void requireFilterFieldsVisible(UUID tenantId, UUID actorId, EntityHandle handle,
                                           AppDefinition app, QueryModel.Filter filter) {
        if (filter == null) {
            return;
        }
        if (filter instanceof QueryModel.Filter.Leaf leaf) {
            requireFieldVisible(roleMatrix.fieldAccess(tenantId, actorId, handle.appApiName(),
                    app.permissionSet(), handle.entity().apiName(), leaf.field()),
                    handle.entity().apiName(), leaf.field());
        } else if (filter instanceof QueryModel.Filter.Composite composite) {
            composite.children().forEach(child ->
                    requireFilterFieldsVisible(tenantId, actorId, handle, app, child));
        }
    }

    /** The role-scoped twin of the filter walk ({@code listAsRole}/{@code aggregateAsRole}). */
    private static void requireFilterFieldsVisibleForRole(AppDefinition app, EntityHandle handle,
                                                          String role, QueryModel.Filter filter) {
        if (filter == null) {
            return;
        }
        if (filter instanceof QueryModel.Filter.Leaf leaf) {
            requireFieldVisible(roleFieldAccess(app, handle.entity().apiName(), leaf.field(), role),
                    handle.entity().apiName(), leaf.field());
        } else if (filter instanceof QueryModel.Filter.Composite composite) {
            composite.children().forEach(child ->
                    requireFilterFieldsVisibleForRole(app, handle, role, child));
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
                                + e.getMessage(), null, e);
            }
        }
        if (alternatives.isEmpty()) {
            return lowered.and("false", List.of());   // restricted to nobody
        }
        return lowered.and(String.join(" OR ", alternatives), clauseParams);
    }

    /** Bulk ops with per-item outcomes, max 500 (PHASE-1 §5). */
    public List<Map<String, Object>> batch(UUID tenantId, UUID actorId, List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        if (items.size() > MAX_BATCH) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "batch exceeds " + MAX_BATCH + " items",
                    ProblemErrors.of(new ProblemErrors.FieldError("items",
                            "batch is capped at " + MAX_BATCH + " items", items.size())));
        }
        List<Map<String, Object>> outcomes = new ArrayList<>();
        for (Map<String, Object> item : items) {
            try {
                // Through the proxy, never this-side: each item runs inside its own
                // transaction (create/update/delete are @Transactional), so a failing
                // item's partial writes roll back — parent + inline children + outbox
                // rows + sequence draws all atomic — while per-item outcomes survive
                // (a SQL-level abort poisons only that item's transaction, never the
                // rest of the batch).
                RecordEngine proxied = self.getObject();
                String op = String.valueOf(item.get("op"));
                Map<String, Object> result = switch (op) {
                    case "create" -> proxied.create(tenantId, actorId,
                            requireBatchText(item.get("entity"), "entity"),
                            typedFields(item.get("record")));
                    case "update" -> proxied.update(tenantId, actorId,
                            requireBatchText(item.get("entity"), "entity"),
                            requireBatchUuid(item.get("id")),
                            requireBatchVersion(item.get("version")),
                            typedFields(item.get("record")));
                    case "delete" -> {
                        proxied.delete(tenantId, actorId,
                                requireBatchText(item.get("entity"), "entity"),
                                requireBatchUuid(item.get("id")),
                                requireBatchVersion(item.get("version")));
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
            } catch (RuntimeException e) {
                // A SQL-level abort — unique-index race, deadlock, serialization failure —
                // rolls back only this item's transaction (the proxy's tx boundary), so
                // it reports as that item's error outcome. Letting it escape would 500
                // the whole request after earlier items already committed, losing their
                // verdicts exactly when the caller needs them most. The verdict names
                // the failure class only — the raw message (constraint names, value
                // excerpts) stays in the log, never the API response.
                LOG.warn("batch item failed ({}): {}", e.getClass().getSimpleName(), e.getMessage());
                outcomes.add(Map.of(
                        "status", "error",
                        "code", PlatformErrorCode.INTERNAL.code(),
                        "detail", "item failed: " + e.getClass().getSimpleName()));
            }
        }
        return outcomes;
    }

    /** Batch item shape guards: a malformed item is that item's verdict, never an NPE. */
    private static String requireBatchText(Object value, String name) {
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                "batch item " + name + " is required",
                ProblemErrors.of(new ProblemErrors.FieldError(name,
                        name + " is required", value)));
    }

    private static UUID requireBatchUuid(Object value) {
        if (value instanceof String text) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException e) {
                // fall through to the shaped rejection
            }
        }
        throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                "batch item id must be a uuid",
                ProblemErrors.of(new ProblemErrors.FieldError("id",
                        "id must be a uuid", value)));
    }

    private static int requireBatchVersion(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                "batch item version must be a number (optimistic locking)",
                ProblemErrors.of(new ProblemErrors.FieldError("version",
                        "version must be a number", value)));
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
        // guards before hooks: a write doomed by freeze/period must not fire its
        // hooks' external side effects (connector calls, approval tasks) first
        requireParentsNotFrozen(tenantId, app, handle, canonical);
        enforcePeriodLock(tenantId, app, handle, canonical);
        runHooks(app, handle, tenantId, id, canonical, "beforeSave", appSystemPrincipal(handle),
                principal, null);
        reCanonicalizeHookWrites(tenantId, app, handle, canonical, id);
        // the initial-state guard validates the landing state, hook writes included
        enforceCreateState(app, handle, canonical);
        evaluateRollupsFromChildren(app, handle, children, canonical);
        persistWithChildren(tenantId, principal, app, handle, id, canonical, children, errors);
        events.publish(event("record.created", tenantId, handle.entityKey(), id, principal));
        // standalone writes recompute every parent roll-up naming this entity (§3) —
        // the user doors always did; without this leg a webhook/import feeding child
        // rows leaves the parents' aggregates stale (the StockLedger/Item finding,
        // on the integration door)
        recomputeParentRollups(tenantId, principal, app, handle, canonical, null);
        runHooks(app, handle, tenantId, id, canonical, "afterSave", appSystemPrincipal(handle),
                principal, null);
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
        // guards before hooks (mirror the user update door): a doomed write must not
        // fire connector calls or approval tasks on its way to RECORD_FROZEN
        requireNotFrozen(app, handle, existing.data());
        requireParentsNotFrozen(tenantId, app, handle, merged);
        String transition = transitionOf(app, handle, existing.data(), merged);
        runHooks(app, handle, tenantId, id, merged, "beforeSave", appSystemPrincipal(handle),
                principal, transition);
        reCanonicalizeHookWrites(tenantId, app, handle, merged, id);
        enforceTransition(app, handle, existing.data(), merged);
        enforcePeriodLock(tenantId, app, handle, merged);

        Map<String, Object> before = existing.data();
        int newVersion = updateShaped(tenantId, principal, handle, id, merged,
                expectedVersion, "integration update");
        replaceChildren(tenantId, principal, app, handle, id, children);
        newVersion = recomputeRollupsIfChanged(tenantId, principal, app, handle, id, merged,
                newVersion, false);
        events.publish(event("record.updated", tenantId, handle.entityKey(), id, principal),
                changeMetadata(before, merged, strip(tenantId, principal, handle, app)));
        recomputeParentRollups(tenantId, principal, app, handle, merged, before);
        runHooks(app, handle, tenantId, id, merged, "afterSave", appSystemPrincipal(handle),
                principal, transition);
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
                .filter(p -> p.entity().equals(handle.entity().apiName()))
                .filter(p -> p.role().equals(asRole))
                .anyMatch(p -> p.allows("read"));
        if (!granted) {
            throw new PlatformException(PlatformErrorCode.FORBIDDEN,
                    "role " + asRole + " is not granted read on " + entityApiName);
        }
        QueryModel.ListQuery query = QueryParser.parseList(queryJson, handle.entity());
        requireFilterFieldsVisibleForRole(app, handle, asRole, query.filter());
        QueryLowering lowering = new QueryLowering(handle.entity());
        QueryLowering.Lowered countSql = lowering.count(handle.entity().apiName(), tenantId,
                query.filter());
        QueryLowering.Lowered listSql = lowering.list(handle.entity().apiName(), tenantId, query);
        // The export scope rides the same lowered sharing as user lists (the
        // 2025-08-27 review unified the two paths) — never the page-skewing
        // post-filter.
        var restriction = sharing.forRole(tenantId, handle.entity(), app, asRole);
        countSql = applySharing(countSql, handle, restriction);
        listSql = applySharing(listSql, handle, restriction);
        RecordStore.PageResult page = records.list(countSql.sql(), countSql.params(),
                listSql.sql(), listSql.params());
        java.util.function.Predicate<String> hidden = field ->
                com.novaforge.metadata.PermissionSet.FieldSecurity.HIDDEN.equals(
                        roleFieldAccess(app, handle.entity().apiName(), field, asRole));
        List<Map<String, Object>> rows = page.rows().stream()
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

    /**
     * The triggering write's state-machine edge ({@code PRIOR->NEW}) when the bound
     * state field changed — the {@code transition} SLA match binding a
     * {@code requestApproval} suspension carries (PHASE-4 §6 / PHASE-2 Annex A).
     * Null for creates (initial-state entry is not a transition), deletes, and
     * state-unchanged writes.
     */
    private static String transitionOf(AppDefinition app, EntityHandle handle,
                                       Map<String, Object> existing, Map<String, Object> merged) {
        var machine = app.stateMachineFor(handle.entity().apiName());
        if (machine.isEmpty()) {
            return null;
        }
        Object from = existing.get(machine.get().stateField());
        Object to = merged.get(machine.get().stateField());
        if (from == null || to == null || java.util.Objects.equals(from, to)) {
            return null;
        }
        return from + "->" + to;
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
     *
     * <p>PHASE-7 §4's soft close rides the same lookup: a date inside a
     * {@code restrictedStatus} period (the period's {@code CLOSING}) rejects
     * identically <em>unless</em> the written record's boolean {@code exemptField}
     * carries {@code true} (the app's {@code closeJournal} flag — app metadata, no
     * platform special-casing). The closed leg stays absolute: nothing exempts a
     * closed period.</p>
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
        if (blockingPeriodCount(tenantId, lock, date, lock.closed()) > 0) {
            throw new PlatformException(PlatformErrorCode.PERIOD_LOCKED,
                    handle.entity().apiName() + " is dated " + date + " — inside a closed "
                            + lock.entity() + " (PeriodLock, PHASE-7 §3.2); a closed period "
                            + "only reopens through its own audited flow");
        }
        if (lock.restrictedStatus() != null
                && !exemptFromRestriction(lock, data)
                && blockingPeriodCount(tenantId, lock, date, lock.restrictedStatus()) > 0) {
            throw new PlatformException(PlatformErrorCode.PERIOD_LOCKED,
                    handle.entity().apiName() + " is dated " + date + " — inside a "
                            + lock.restrictedStatus() + " " + lock.entity()
                            + " (PeriodLock, PHASE-7 §4); only records carrying "
                            + lock.exemptField() + " = true may post into it");
        }
    }

    /** The exempt field reads the merged record state — defaults included (§4). */
    private static boolean exemptFromRestriction(EntityDefinition.PeriodLock lock,
                                                 Map<String, Object> data) {
        if (lock.exemptField() == null) {
            return false;
        }
        return Boolean.parseBoolean(String.valueOf(data.get(lock.exemptField())));
    }

    private long blockingPeriodCount(UUID tenantId, EntityDefinition.PeriodLock lock,
                                     String date, String statusValue) {
        EntityHandle periodHandle = resolver.resolve(tenantId, lock.entity());
        String queryJson = "{\"filter\":{\"and\":["
                + "{\"field\":\"" + lock.from() + "\",\"op\":\"lte\",\"value\":\"" + date + "\"},"
                + "{\"field\":\"" + lock.to() + "\",\"op\":\"gte\",\"value\":\"" + date + "\"},"
                + "{\"field\":\"" + lock.status() + "\",\"op\":\"eq\",\"value\":\"" + statusValue + "\"}]}}";
        QueryModel.ListQuery query = QueryParser.parseList(queryJson, periodHandle.entity());
        QueryLowering lowering = new QueryLowering(periodHandle.entity());
        QueryLowering.Lowered count = lowering.count(periodHandle.entity().apiName(), tenantId,
                query.filter());
        Long countValue = records.countValue(count.sql(), count.params());
        return countValue == null ? 0 : countValue;
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
                          UUID initiatingActor, String transition) {
        if (handle.entity().hooks().isEmpty()) {
            return;
        }
        HookExecutor.Outcome outcome = hooks.runTrigger(app, handle, tenantId, recordId,
                data, trigger, systemPrincipal, initiatingActor, transition, hookSink);
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
     * Transactional like the write path's own hook legs — every flow write and its
     * outbox appends land or roll back as one unit (the 2025-08-27 review closed
     * the missing boundary here).
     */
    @Transactional
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
     * The scheduled script's read (PHASE-4 §7 — the Script Engine's internal query
     * leg): the standard list DSL executed by the storage path under the per-app
     * system principal — the same system-context shape {@link #recordForSubscription}
     * serves the Workflow Service: raw stored rows, never shaped or user-stripped,
     * never a mutation. The single query path lowers the DSL; the matrix, field
     * security, and sharing rules are user-context concerns this principal does not
     * carry (the §13 Q1 engine-action rule — nested engine writes ride the same
     * bypass).
     */
    public QueryModel.QueryResult listAsPrincipal(UUID tenantId, String entityApiName,
                                                  String queryJson) {
        EntityHandle handle = resolver.resolve(tenantId, entityApiName);
        QueryModel.ListQuery query = QueryParser.parseList(queryJson, handle.entity());
        QueryLowering lowering = new QueryLowering(handle.entity());
        QueryLowering.Lowered countSql = lowering.count(handle.entity().apiName(), tenantId,
                query.filter());
        QueryLowering.Lowered listSql = lowering.list(handle.entity().apiName(), tenantId, query);
        RecordStore.PageResult page = records.list(countSql.sql(), countSql.params(),
                listSql.sql(), listSql.params());
        List<Map<String, Object>> rows = page.rows().stream()
                .map(row -> (Map<String, Object>) new LinkedHashMap<>(row))
                .toList();
        return new QueryModel.QueryResult(rows, page.total());
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
     * entities; own-record references resolve empty. Transactional like the write
     * path's hook legs — flow writes and their outbox appends land or roll back as
     * one unit (the 2025-08-27 review closed the missing boundary here).
     */
    @Transactional
    public void runScheduledHook(UUID tenantId, String entityApiName, String hookName) {
        EntityHandle handle = resolver.resolve(tenantId, entityApiName);
        AppDefinition app = resolver.bundle(tenantId, handle.appId());
        HookRule hook = handle.entity().hooks().stream()
                .filter(h -> hookName.equals(h.name()))
                .findFirst()
                .orElseThrow(() -> new PlatformException(PlatformErrorCode.NOT_FOUND,
                        "hook " + hookName + " not found on " + entityApiName));
        hooks.runScheduled(app, handle, tenantId, hook, appSystemPrincipal(handle), hookSink);
    }

    /**
     * The page-model {@code runFlow} action's execution leg (PHASE-3 §8): one named
     * flow on demand against the record's current state. The calling user needs the
     * entity READ grant and sharing visibility (the button renders on a page they can
     * see); the flow itself runs as the per-app system principal with the initiating
     * actor recorded (PHASE-4 §13's system-identity-with-human-context audit shape).
     * Transactional — the flow's writes and outbox appends are one unit, exactly
     * like a write-path afterSave hook (the 2025-08-27 review closed the missing
     * boundary here).
     */
    @Transactional
    public Map<String, Object> runManualHook(UUID tenantId, UUID actorId, String entityApiName,
                                             UUID recordId, String hookName) {
        EntityHandle handle = resolver.resolve(tenantId, entityApiName);
        AppDefinition app = resolver.bundle(tenantId, handle.appId());
        roleMatrix.require(tenantId, actorId, RoleMatrix.Action.READ, handle.entity().apiName(),
                handle.appApiName(), app.permissionSet());
        RecordStore.StoredRecord record = records.find(tenantId, handle.entityKey(), recordId,
                        false)
                .orElseThrow(() -> notFound(entityApiName, recordId));
        requireVisible(sharing.forActor(tenantId, actorId, handle.entity(), app), record);
        hooks.runManual(app, handle, tenantId, recordId, record.data(), hookName,
                appSystemPrincipal(handle), actorId, hookSink);
        return shape(handle.entity(),
                records.find(tenantId, handle.entityKey(), recordId, false).orElse(record),
                strip(tenantId, actorId, handle, app));
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
     * Transactional — the re-driven hook's writes and outbox appends are one unit,
     * mirroring the original write-path execution (2025-08-27 review).
     */
    @Transactional
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
        // roll-ups aggregate the in-memory child set before the insert (PHASE-3 §3) —
        // the same semantics as the user path, now load-bearing for flow-created
        // parents with deep-resolved inline children (§3.3, the G-1 harvest)
        evaluateRollupsFromChildren(app, handle, children, canonical);
        persistWithChildren(tenantId, systemPrincipal, app, handle, id, canonical, children, errors);
        events.publish(event("record.created", tenantId, handle.entityKey(), id, systemPrincipal));
        // flow-created standalone rows feed parent roll-ups like any write (§3)
        recomputeParentRollups(tenantId, systemPrincipal, app, handle, canonical, null);
        // The created id rides the returned view (§3.3, the G-1 harvest): a
        // createRecord step captures it into flow scope as ${record.<stepId>.id}.
        // The persisted canonical map stays field-pure — the copy is the view.
        Map<String, Object> view = new LinkedHashMap<>(canonical);
        view.put("id", id.toString());
        return view;
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
        List<ProblemErrors.FieldError> errors = new ArrayList<>();
        // Templates render every ${…} binding as a string, so the principal path
        // canonicalizes like any writer — an unbound binding is the literal "null",
        // which must reject on a typed field, not ride into JSONB and poison the
        // roll-up SQL ((data->>'f')::numeric). Unknown fields stay ignored (a flow
        // outliving a metadata edit keeps running; its writes no-op, never wedge).
        Map<String, Object> fields = new LinkedHashMap<>();
        body.forEach((key, value) -> {
            if (!key.equals("version") && handle.entity().field(key).isPresent()) {
                fields.put(key, value);
            }
        });
        Map<String, Object> canonical = FieldCoercer.canonicalize(handle.entity(), fields,
                externalChecks(tenantId, handle, app), UUID.fromString(recordId), errors);
        Map<String, Object> merged = new LinkedHashMap<>(existing.data());
        canonical.forEach(merged::put);
        evaluateFormulas(handle.entity(), merged);
        evaluateValidationRules(handle.entity(), merged, errors);
        reject(errors, "hook update " + entityApiName + "/" + recordId + " failed validation");
        requireParentsNotFrozen(tenantId, app, handle, merged);
        enforceTransition(app, handle, existing.data(), merged);
        enforcePeriodLock(tenantId, app, handle, merged);
        int version = body.get("version") instanceof Number number ? number.intValue()
                : existing.version();
        updateShaped(tenantId, systemPrincipal, handle, UUID.fromString(recordId), merged,
                version, "hook update");
        java.util.function.Predicate<String> hookHidden = TenantContext.current()
                .map(context -> strip(tenantId, UUID.fromString(context.actorId()), handle, app))
                .orElse(field -> false);
        events.publish(event("record.updated", tenantId, handle.entityKey(),
                UUID.fromString(recordId), systemPrincipal),
                changeMetadata(existing.data(), merged, hookHidden));
        recomputeParentRollups(tenantId, systemPrincipal, app, handle, merged, existing.data());
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
            parentData.put(field.apiName(),
                    normalizeRollupScale(field, rollup.aggregateInMemory(childRows)));
        }
    }

    private int recomputeRollupsIfChanged(UUID tenantId, UUID actorId, AppDefinition app,
                                          EntityHandle parent, UUID parentId,
                                          Map<String, Object> parentData, int currentVersion,
                                          boolean publishEvent) {
        boolean changed = false;
        Map<String, Object> before = new LinkedHashMap<>();
        for (FieldDefinition field : parent.entity().fields()) {
            if (field.rollup() == null) {
                continue;
            }
            Rollup rollup = Rollup.parse(field.rollup());
            EntityHandle childHandle = childHandle(tenantId, app, parent, rollup.relationship());
            String bindingField = bindingLookupField(parent.entity(), childHandle.entity());
            Object aggregate = normalizeRollupScale(field, rollup.aggregate(records, tenantId,
                    childHandle, bindingField, parentId, rollup.field()));
            if (rollupMoved(aggregate, parentData.get(field.apiName()))) {
                before.put(field.apiName(), parentData.get(field.apiName()));
                parentData.put(field.apiName(), aggregate);
                changed = true;
            }
        }
        if (changed) {
            int newVersion = updateShaped(tenantId, actorId, parent, parentId, parentData,
                    currentVersion, "roll-up recompute");
            if (publishEvent) {
                // A roll-up recompute is a real mutation of the parent — version,
                // updated_at, updated_by all move — and subscribers (workflow
                // event-starts, audit) must see it like any other write. The writer's
                // own update path publishes its record.updated after this call, so it
                // passes false and the parent is not double-published.
                events.publish(event("record.updated", tenantId, parent.entityKey(),
                        parentId, actorId), changeMetadata(before, parentData,
                        strip(tenantId, actorId, parent, app)));
            }
            return newVersion;
        }
        return currentVersion;
    }

    /**
     * Post-hook canonicalization (2026-08-31, twelfth pass): beforeSave hooks (setField
     * steps, script returns) mutate the record AFTER validation ran — their writes
     * must ride the same type/shape contract as every writer, or a hook rendering
     * {@code amount} as "12." would poison ((data->>'amount')::numeric) for every
     * later query, exactly the unbound-template class the principal paths fixed.
     * Runs over the hook-mutated map, rejecting shaped violations; formulas
     * re-evaluate afterwards so derived fields follow hook-written inputs.
     */
    private void reCanonicalizeHookWrites(UUID tenantId, AppDefinition app, EntityHandle handle,
                                          Map<String, Object> data, UUID recordId) {
        List<ProblemErrors.FieldError> errors = new ArrayList<>();
        Map<String, Object> fields = new LinkedHashMap<>();
        data.forEach((key, value) -> {
            if (handle.entity().field(key).isPresent()) {
                fields.put(key, value);
            }
        });
        Map<String, Object> canonical = FieldCoercer.canonicalize(handle.entity(), fields,
                externalChecks(tenantId, handle, app), recordId, errors);
        canonical.forEach(data::put);
        evaluateFormulas(handle.entity(), data);
        evaluateValidationRules(handle.entity(), data, errors);
        reject(errors, "hook write failed validation on " + handle.entity().apiName());
    }
    /**
     * The event payload's change legs (2026-08-31): an update carries each changed
     * field's prior and next value (a consumer or the audit trail can reconstruct
     * what moved without re-fetching), a delete carries the deleted record's data —
     * a {@code record.deleted} consumer otherwise cannot know what left.
     *
     * <p>The spine is a distribution surface, not a private channel: outbound
     * webhooks subscribe to these topics and post payloads verbatim, so the change
     * legs ride through the writing actor's field visibility — a field the actor
     * cannot read (HIDDEN under field security) never rides the event it caused,
     * exactly as the read doors strip it.</p>
     */
    private static Map<String, Object> changeMetadata(Map<String, Object> before,
                                                      Map<String, Object> after,
                                                      java.util.function.Predicate<String> hidden) {
        Map<String, Object> changed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : after.entrySet()) {
            if (hidden.test(entry.getKey())) {
                continue;
            }
            if (!java.util.Objects.equals(entry.getValue(), before.get(entry.getKey()))) {
                // Arrays.asList, not List.of: a field may change from (or to) null —
                // the value pair is content, and List.of rejects null elements
                changed.put(entry.getKey(),
                        java.util.Arrays.asList(before.get(entry.getKey()), entry.getValue()));
            }
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("changed", changed);
        return metadata;
    }

    private static Map<String, Object> deletedMetadata(Map<String, Object> data,
                                                       java.util.function.Predicate<String> hidden) {
        Map<String, Object> visible = new LinkedHashMap<>();
        data.forEach((key, value) -> {
            if (!hidden.test(key)) {
                visible.put(key, value);
            }
        });
        return Map.of("before", visible);
    }

    /**
     * Scale-aware change detection: {@code BigDecimal.equals} is scale-sensitive
     * ({@code 15.5} ≠ {@code 15.50}), so an AVG computed in memory at 34-digit scale
     * versus the SQL engine's aggregate scale churned the parent's version on every
     * child write — {@code compareTo} is the equality that matters on the money path.
     */
    private static boolean rollupMoved(Object aggregate, Object stored) {
        if (aggregate == null && stored == null) {
            return false;
        }
        // numeric comparison across the value's many carriers: SQL aggregates arrive
        // as BigDecimal, jsonb-parsed data and the coercer's INT results as
        // Integer/Long — equal numbers must never read as drift (each false move
        // rewrites the parent and churns its version)
        if (aggregate instanceof Number next && stored instanceof Number prev) {
            return new java.math.BigDecimal(next.toString())
                    .compareTo(new java.math.BigDecimal(prev.toString())) != 0;
        }
        return !java.util.Objects.equals(aggregate, stored);
    }

    /**
     * Roll-up values ride the field's authored scale: the in-memory AVG path divides
     * at 34-digit MathContext, and every user write is scale-capped by the coercer —
     * a roll-up field is the one write path that must not smuggle a wider scale into
     * a DECIMAL(18,4) column than its own writers could put there.
     */
    private static Object normalizeRollupScale(FieldDefinition field, Object aggregate) {
        if (!(aggregate instanceof java.math.BigDecimal decimal)
                || (field.type() != FieldType.DECIMAL && field.type() != FieldType.MONEY)) {
            return aggregate;   // COUNT into int fields and text roll-ups ride as-is
        }
        int scale = field.scale() == null
                ? (field.type() == FieldType.MONEY ? 4 : 6) : field.scale();
        return decimal.setScale(scale, java.math.RoundingMode.HALF_EVEN);
    }

    /**
     * Standalone child writes recompute every parent roll-up that names this
     * entity's relationship (PHASE-3 §3: "updates recompute in the child's write
     * transaction") — inline children always did via the parent's own write; found
     * live running the ERP suites: a StockLedger row created on its own (the
     * warehouse's real write shape) left the Item's roll-ups stale, so the costing
     * script read qtyOnHand 0 and divided its way to NaN. Runs as the write's actor,
     * in the same transaction; a re-parenting update refreshes old and new parents;
     * frozen parents never reach here (their binding writes reject upstream, §3.1).
     */
    private void recomputeParentRollups(UUID tenantId, UUID actorId, AppDefinition app,
                                        EntityHandle child, Map<String, Object> childData,
                                        Map<String, Object> priorData) {
        for (EntityDefinition parent : app.entities()) {
            boolean relevant = false;
            for (FieldDefinition field : parent.fields()) {
                if (field.rollup() != null) {
                    relevant = true;
                    break;
                }
            }
            if (!relevant) {
                continue;
            }
            EntityHandle parentHandle = resolveLocal(app, parent.apiName());
            String binding;
            try {
                binding = bindingLookupField(parent, child.entity());
            } catch (PlatformException notBound) {
                continue;   // this parent's roll-ups do not ride this child entity
            }
            java.util.Set<UUID> parents = new java.util.LinkedHashSet<>();
            for (Map<String, Object> source : List.of(childData, priorData == null
                    ? Map.<String, Object>of() : priorData)) {
                Object value = source.get(binding);
                if (value != null) {
                    try {
                        parents.add(UUID.fromString(String.valueOf(value)));
                    } catch (IllegalArgumentException ignored) {
                        // a malformed binding cannot happen post-canonicalize; skip
                    }
                }
            }
            for (UUID parentId : parents) {
                // Two child writes to one parent read the same parent version; the
                // loser's CAS would fail the WHOLE child transaction (a legitimate
                // insert dying as 409 with no conflicting edit of its own). One
                // bounded re-read retry absorbs the lost race; a second loss means
                // real contention and surfaces as usual.
                for (int attempt = 0; attempt < 2; attempt++) {
                    var stored = records.find(tenantId, parentHandle.entityKey(), parentId, false);
                    if (stored.isEmpty()) {
                        break;
                    }
                    try {
                        recomputeRollupsIfChanged(tenantId, actorId, app, parentHandle,
                                parentId, stored.get().data(), stored.get().version(), true);
                        break;
                    } catch (PlatformException e) {
                        if (e.errorCode() != PlatformErrorCode.CONFLICT_VERSION || attempt == 1) {
                            throw e;
                        }
                    }
                }
            }
        }
    }

    /** Same-app handle for a parent entity (no cross-tenant resolution needed). */
    private EntityHandle resolveLocal(AppDefinition app, String entityApiName) {
        EntityDefinition entity = app.entity(entityApiName).orElseThrow(() ->
                new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                        "app entity missing: " + entityApiName));
        UUID appId = app.id() == null ? null : UUID.fromString(app.id());
        return new EntityHandle(appId, app.apiName(), 0, entity,
                app.apiName() + "." + entity.apiName());
    }

    /** {@code SUM(lines.debit)} — aggregate op, relationship, child field; PHASE-7
     *  §3.5 grows an optional AND-joined WHERE clause over the DSL leaf vocabulary,
     *  parsed by the shared {@link RollupExpression} so save validation and this
     *  engine can never disagree about what an authored roll-up means. */
    record Rollup(String op, String relationship, String field,
                  List<RollupExpression.Condition> conditions) {

        static Rollup parse(String source) {
            RollupExpression expression = RollupExpression.parse(source);
            return new Rollup(expression.op(), expression.relationship(), expression.field(),
                    expression.conditions());
        }

        /** Aggregates the in-memory inline child set (create path — no store round-trip).
         *  Conditions filter first (PHASE-7 §3.5): strings verbatim, numbers as exact
         *  decimals — never a float compare on the money path. */
        Object aggregateInMemory(List<Map<String, Object>> childRows) {
            if (!conditions.isEmpty()) {
                childRows = childRows.stream().filter(row -> conditions.stream()
                        .allMatch(c -> rowMatches(row, c))).toList();
            }
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
                if (decimal == null && value instanceof String text && !text.isBlank()
                        && !text.equals("null")) {
                    // exact decimal strings are the ${…} template channel's canonical
                    // form (deep-resolved inline children arrive as strings, §3.3) —
                    // parsed exactly, never floated. A non-numeric string skips here
                    // and fails loudly in the child's own canonicalize below.
                    try {
                        decimal = new java.math.BigDecimal(text.trim());
                    } catch (NumberFormatException skipped) {
                        // the child write rejects it — the rollup never masks it
                    }
                }
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
            // The binding leaf rides the same parser/lowering as every list query;
            // conditional roll-ups AND their authored leaves onto it (PHASE-7 §3.5),
            // serialized as DSL leaves so parse validation + lowering stay canonical.
            String bindingLeaf = "{\"field\":\"" + bindingField + "\",\"op\":\"eq\"," 
                    + "\"value\":\"" + parentId + "\"}";
            String filterJson = conditions.isEmpty() ? bindingLeaf
                    : "{\"and\":[" + bindingLeaf + ","
                    + conditionLeavesJson() + "]}";
            String aggregatesJson = op.equals("COUNT") ? ""
                    : ",\"aggregates\":[{\"op\":\"" + op.toLowerCase()
                    + "\",\"field\":\"" + field + "\"}]";
            String queryJson = "{\"filter\":" + filterJson + aggregatesJson + "}";
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

        /** Condition leaves in DSL wire shape — the mapper serializes exact-decimal/
         *  boolean values canonically; isNull carries no value. */
        private String conditionLeavesJson() {
            JsonMapper mapper = JsonMapper.builder().build();
            StringBuilder leaves = new StringBuilder();
            for (RollupExpression.Condition condition : conditions) {
                if (!leaves.isEmpty()) {
                    leaves.append(',');
                }
                Map<String, Object> leaf = new LinkedHashMap<>();
                leaf.put("field", condition.field());
                leaf.put("op", condition.op());
                if (condition.value() != null) {
                    leaf.put("value", condition.value() instanceof List<?> items
                            ? items : condition.value());
                }
                try {
                    leaves.append(mapper.writeValueAsString(leaf));
                } catch (JacksonException e) {
                    throw new PlatformException(PlatformErrorCode.INTERNAL,
                            "rollup condition serialization failed: " + e.getMessage(), null, e);
                }
            }
            return leaves.toString();
        }

        /** One condition against an in-memory inline child row — the same verdicts
         *  the store path's lowering would produce: numeric-parsable pairs compare
         *  as exact decimals, everything else as canonical strings. */
        private static boolean rowMatches(Map<String, Object> row,
                                          RollupExpression.Condition condition) {
            Object actual = row.get(condition.field());
            Object expected = condition.value();
            return switch (condition.op()) {
                case "isNull" -> actual == null;
                case "in" -> expected instanceof List<?> values && values.stream()
                        .anyMatch(v -> equivalent(actual, v));
                case "eq" -> equivalent(actual, expected);
                case "ne" -> !equivalent(actual, expected);
                default -> {
                    int c = compare(actual, expected);
                    yield switch (condition.op()) {
                        case "gt" -> c > 0;
                        case "gte" -> c >= 0;
                        case "lt" -> c < 0;
                        default -> c <= 0;   // lte
                    };
                }
            };
        }

        private static boolean equivalent(Object a, Object b) {
            if (a == null || b == null) {
                return a == b;
            }
            Integer numeric = numericCompare(a, b);
            if (numeric != null) {
                return numeric == 0;
            }
            return stringOf(a).equals(stringOf(b));
        }

        private static int compare(Object a, Object b) {
            Integer numeric = numericCompare(a, b);
            if (numeric != null) {
                return numeric;
            }
            return stringOf(a).compareTo(stringOf(b));   // enums, ISO dates — lexical is the order
        }

        /** Exact decimal comparison when both sides are numbers or number-strings; null otherwise. */
        private static Integer numericCompare(Object a, Object b) {
            java.math.BigDecimal left = asDecimal(a);
            java.math.BigDecimal right = asDecimal(b);
            return left != null && right != null ? left.compareTo(right) : null;
        }

        private static java.math.BigDecimal asDecimal(Object value) {
            if (value instanceof java.math.BigDecimal decimal) {
                return decimal;
            }
            if (value instanceof Number number) {
                return new java.math.BigDecimal(number.toString());
            }
            if (value instanceof Boolean || value instanceof List<?>) {
                return null;
            }
            try {
                return new java.math.BigDecimal(String.valueOf(value));
            } catch (NumberFormatException notNumeric) {
                return null;
            }
        }

        private static String stringOf(Object value) {
            return value instanceof java.math.BigDecimal decimal ? decimal.toPlainString()
                    : String.valueOf(value);
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
        insertShaped(tenantId, actorId, parent, parentId, parentData);
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
                insertShaped(tenantId, actorId, childHandle, childId, canonicalChild);
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
                        existing.id(), actorId), deletedMetadata(existing.data(),
                        strip(tenantId, actorId, childHandle, app)));
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
                insertShaped(tenantId, actorId, childHandle, childId, canonicalChild);
                events.publish(event("record.created", tenantId, childHandle.entityKey(), childId, actorId));
            }
            reject(errors, "inline children failed validation");
        }
    }

    /**
     * Every insert/update leg shapes a unique-index violation into the friendly
     * field-scoped error — the partial unique indexes are the enforcement (§6), and a
     * lost pre-check race (or a scale-collision the text pre-check cannot see) on the
     * update, inline-child, or roll-up legs used to surface as a raw 500 and abort a
     * whole batch after earlier items committed.
     */
    private void insertShaped(UUID tenantId, UUID actorId, EntityHandle handle, UUID id,
                              Map<String, Object> data) {
        try {
            records.insert(tenantId, handle.entityKey(), id, data, actorId);
        } catch (DataIntegrityViolationException e) {
            throw uniqueViolation(handle, "create", e);
        }
    }

    private int updateShaped(UUID tenantId, UUID actorId, EntityHandle handle, UUID id,
                             Map<String, Object> data, int expectedVersion, String op) {
        try {
            return records.update(tenantId, handle.entityKey(), id, data, expectedVersion, actorId);
        } catch (DataIntegrityViolationException e) {
            throw uniqueViolation(handle, op, e);
        }
    }

    private static PlatformException uniqueViolation(EntityHandle handle, String op,
                                                     DataIntegrityViolationException e) {
        return new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                op + " violates a uniqueness rule", ProblemErrors.of(
                        new ProblemErrors.GlobalError(handle.entity().apiName(),
                                "unique constraint violated: " + rootMessage(e))), e);
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
                        child.id(), actorId), deletedMetadata(child.data(),
                        strip(tenantId, actorId, childHandle, app)));
            }
        }
    }

    private List<RecordStore.StoredRecord> currentChildren(UUID tenantId, EntityHandle childHandle,
                                                           String bindingField, UUID parentId) {
        // The child walk pages to exhaustion, never the list default (50): inline
        // children are legal to 100 per request and standalone/batch writes grow a
        // parent's set without bound, so a single-page walk orphaned every child past
        // page one — replace-children left stale rows behind (duplicated by the new
        // set), cascade-delete left live children under a deleted parent, and both
        // stayed counted by the roll-ups, which aggregate in SQL unwindowed (found in
        // the 2026-08-28 hunt). The list lowers a deterministic ORDER BY id, so offset
        // paging is stable; callers mutate only after the walk completes.
        List<RecordStore.StoredRecord> children = new ArrayList<>();
        long offset = 0;
        while (true) {
            String queryJson = "{\"filter\":{\"field\":\"" + bindingField + "\",\"op\":\"eq\","
                    + "\"value\":\"" + parentId + "\"},"
                    + "\"page\":{\"size\":" + QueryModel.MAX_PAGE_SIZE
                    + ",\"offset\":" + offset + "}}";
            QueryModel.ListQuery query = QueryParser.parseList(queryJson, childHandle.entity());
            QueryLowering lowering = new QueryLowering(childHandle.entity());
            QueryLowering.Lowered countSql = lowering.count(childHandle.entity().apiName(), tenantId,
                    query.filter());
            QueryLowering.Lowered listSql = lowering.list(childHandle.entity().apiName(), tenantId, query);
            RecordStore.PageResult page = records.list(countSql.sql(), countSql.params(),
                    listSql.sql(), listSql.params());
            for (Map<String, Object> row : page.rows()) {
                records.find(tenantId, childHandle.entityKey(), (UUID) row.get("id"), false)
                        .ifPresent(children::add);
            }
            if (page.rows().size() < QueryModel.MAX_PAGE_SIZE) {
                return children;
            }
            offset += QueryModel.MAX_PAGE_SIZE;
        }
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
                String entityKey = handle.appApiName() + "." + entity.apiName();
                // Numeric fields compare numerically: the projection's unique index
                // casts, and a text compare cannot see that 10 and a stored 10.00
                // collide there.
                boolean numeric = field.type().numeric();
                boolean exists = numeric
                        ? records.numericValueExists(tenantId, entityKey, field.apiName(),
                                canonicalText, excludeRecordId)
                        : records.valueExists(tenantId, entityKey, field.apiName(),
                                canonicalText, excludeRecordId);
                return !exists;
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
     *
     * <p>Resolved <b>once per request</b>: {@code fieldAccess} is row-independent (each
     * call is a platform-store role lookup), and the lazy per-row evaluation made list
     * pages cost O(rows × fields) DB round trips — a 50-row page issued ~250 of them
     * (~450 ms), blowing the ARCHITECTURE.md §9 list target at the 1M-row PHASE-3 §11
     * measurement (2026-08-28, docs/loadtests/results-2026-08-28-hook-perf.md). The
     * hidden set is precomputed over the entity's fields; keys outside it (system
     * fields, unknown keys) stay visible exactly as {@code fieldAccess}'s default
     * already reports.</p>
     */
    private java.util.function.Predicate<String> strip(UUID tenantId, UUID actorId,
                                                       EntityHandle handle, AppDefinition app) {
        if (app.permissionSet().fieldSecurity().isEmpty()) {
            return field -> false;
        }
        java.util.Set<String> hiddenFields = new java.util.HashSet<>();
        for (FieldDefinition field : handle.entity().fields()) {
            if (PermissionSet.FieldSecurity.HIDDEN.equals(
                    roleMatrix.fieldAccess(tenantId, actorId, handle.appApiName(),
                            app.permissionSet(), handle.entity().apiName(), field.apiName()))) {
                hiddenFields.add(field.apiName());
            }
        }
        return hiddenFields::contains;
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
