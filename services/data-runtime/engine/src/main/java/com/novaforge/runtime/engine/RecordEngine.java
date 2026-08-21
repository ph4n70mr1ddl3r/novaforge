package com.novaforge.runtime.engine;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.common.error.ProblemErrors;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefaultValue;
import com.novaforge.metadata.EntityDefinition;
import com.novaforge.metadata.FieldDefinition;
import com.novaforge.metadata.FieldType;
import com.novaforge.metadata.PermissionSet;
import com.novaforge.metadata.RelationshipDefinition;
import com.novaforge.metadata.RelationshipType;
import com.novaforge.expression.Expression;
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
import com.novaforge.runtime.storage.schema.Snake;
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
    private final RecordStore records;
    private final SequenceService sequences;
    private final DomainEventPublisher events;

    public RecordEngine(EntityResolver resolver, RoleMatrix roleMatrix, RecordStore records,
                        SequenceService sequences, DomainEventPublisher events) {
        this.resolver = resolver;
        this.roleMatrix = roleMatrix;
        this.records = records;
        this.sequences = sequences;
        this.events = events;
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
        evaluateRollupsFromChildren(app, handle, children, canonical);
        persistWithChildren(tenantId, actorId, app, handle, id, canonical, children, errors);

        events.publish(event("record.created", tenantId, handle.entityKey(), id, actorId));
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

        int newVersion = records.update(tenantId, handle.entityKey(), id, merged,
                expectedVersion, actorId);
        replaceChildren(tenantId, actorId, app, handle, id, children);
        newVersion = recomputeRollupsIfChanged(tenantId, actorId, app, handle, id, merged, newVersion);
        events.publish(event("record.updated", tenantId, handle.entityKey(), id, actorId));

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

        records.softDelete(tenantId, handle.entityKey(), id, expectedVersion, actorId);
        cascadeChildren(tenantId, actorId, app, handle, id);
        events.publish(event("record.deleted", tenantId, handle.entityKey(), id, actorId));
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
        return shape(handle.entity(), record, strip(tenantId, actorId, handle, app));
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
        roleMatrix.require(tenantId, actorId, RoleMatrix.Action.READ, entityApiName,
                handle.appApiName(), app.permissionSet());
        QueryModel.AggregateQuery query = QueryParser.parseAggregate(queryJson, handle.entity());
        QueryLowering.Lowered lowered = new QueryLowering(handle.entity())
                .aggregate(handle.entity().apiName(), tenantId, query);
        return new QueryModel.AggregateResult(query.groupBy(),
                records.aggregate(lowered.sql(), lowered.params()).rows());
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
