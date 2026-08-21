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

    private final MetadataStore store;
    private final MetadataPublishEventPublisher events;

    public DefinitionService(MetadataStore store, MetadataPublishEventPublisher events) {
        this.store = store;
        this.events = events;
    }

    public AppDefinition createApp(UUID tenantId, UUID actorId, AppDefinition draft) {
        ProblemErrors errors = DefinitionValidator.validate(draft);
        compileCheckExpressions(draft, errors);
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

    public AppDefinition updateApp(UUID tenantId, UUID actorId, UUID appId, AppDefinition patch) {
        AppDefinition current = store.requireApp(tenantId, appId);
        AppDefinition merged = mergeApp(current, patch);
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
        compileCheckExpressions(candidate, errors);
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
        store.deleteEntity(tenantId, appId, entityApiName);
        return store.requireApp(tenantId, appId);
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
        compileCheckExpressions(draft, errors);
        if (!errors.isEmpty()) {
            throw validationFailure("publish rejected: drafts fail validation", errors);
        }
        MetadataStore.PublishedBundle previous = store.latestPublished(tenantId, appId).orElse(null);
        List<String> breaking = previous == null ? List.of() : breakingChanges(previous.app(), draft);
        if (!breaking.isEmpty() && !acknowledgeDataImpact) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "publish contains breaking changes; re-run with acknowledgeDataImpact=true",
                    ProblemErrors.of(new ProblemErrors.GlobalError(draft.apiName(),
                            "breaking changes require acknowledgeDataImpact: " + String.join("; ", breaking))));
        }
        int version = (previous == null ? 0 : store.versions(tenantId, appId).getFirst().version()) + 1;
        MetadataStore.VersionInfo info = store.publish(tenantId, actorId, appId, version,
                draft, breaking, acknowledgeDataImpact);
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
    private static void compileCheckExpressions(AppDefinition app, ProblemErrors errors) {
        List<ProblemErrors.FieldError> found = new ArrayList<>(errors.errors());
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

    private static void check(String source, EntityDefinition entity, java.util.Set<String> fields,
                              boolean allowClock, List<ProblemErrors.FieldError> errors) {
        try {
            Expression.parse(source)
                    .compileCheck(Expression.CompilePolicy.recordContext(fields, allowClock));
        } catch (ExpressionException e) {
            errors.add(new ProblemErrors.FieldError(
                    entity.apiName() + ".expressions", source + " — " + e.getMessage(), source));
        }
    }

    // --- compatibility check (§4) ---

    static List<String> breakingChanges(AppDefinition previous, AppDefinition next) {
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

    private static AppDefinition mergeApp(AppDefinition current, AppDefinition patch) {
        return new AppDefinition(
                current.id(),
                current.apiName(),
                patch.label() != null ? patch.label() : current.label(),
                patch.labelI18n().isEmpty() ? current.labelI18n() : patch.labelI18n(),
                patch.description() != null ? patch.description() : current.description(),
                current.entities(),
                current.pages(),
                patch.settings() != null && !patch.settings().sequences().isEmpty()
                        ? patch.settings() : current.settings());
    }

    private static EntityDefinition mergeEntity(EntityDefinition current, EntityDefinition patch) {
        return new EntityDefinition(
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
    }

    private static AppDefinition withEntities(AppDefinition app, List<EntityDefinition> entities) {
        return new AppDefinition(app.id(), app.apiName(), app.label(), app.labelI18n(),
                app.description(), entities, app.pages(), app.settings());
    }

    private static PlatformException validationFailure(String message, ProblemErrors errors) {
        return new PlatformException(PlatformErrorCode.VALIDATION_FAILED, message, errors);
    }
}
