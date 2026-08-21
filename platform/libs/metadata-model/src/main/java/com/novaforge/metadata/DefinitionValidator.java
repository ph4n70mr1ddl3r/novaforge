package com.novaforge.metadata;

import com.novaforge.common.error.ProblemErrors;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Schema-v0 save validation for app definitions (PHASE-1 §3): the referential rules
 * the JSON Schema cannot express. Runs on every draft save and on publish; invalid
 * definitions are rejected with {@code VALIDATION_FAILED} carrying field-scoped
 * problems.
 */
public final class DefinitionValidator {

    /** Entity apiNames: PascalCase (ARCHITECTURE.md §3). */
    public static final Pattern PASCAL_CASE = Pattern.compile("^[A-Z][A-Za-z0-9]*$");

    /** Field/relationship/sequence apiNames: camelCase. */
    public static final Pattern CAMEL_CASE = Pattern.compile("^[a-z][A-Za-z0-9]*$");

    /** System field names the record API exposes; authored fields must not shadow them. */
    public static final Set<String> RESERVED_FIELD_NAMES = Set.of(
            "id", "version", "createdAt", "updatedAt", "createdBy", "updatedBy", "deleted");

    private DefinitionValidator() {
    }

    /** Validates the whole app; returns empty when the definition is save-clean. */
    public static ProblemErrors validate(AppDefinition app) {
        List<ProblemErrors.FieldError> errors = new ArrayList<>();
        List<ProblemErrors.GlobalError> globals = new ArrayList<>();

        if (app == null) {
            return ProblemErrors.of(new ProblemErrors.GlobalError("app", "definition must not be null"));
        }
        if (app.apiName() == null || !PASCAL_CASE.matcher(app.apiName()).matches()) {
            errors.add(field("apiName", "app apiName must be PascalCase (e.g. Erp)", app.apiName()));
        }

        Set<String> entityNames = new HashSet<>();
        for (EntityDefinition entity : app.entities()) {
            if (entity.apiName() == null || !PASCAL_CASE.matcher(entity.apiName()).matches()) {
                errors.add(field("apiName", "entity apiName must be PascalCase", entity.apiName()));
                continue;
            }
            if (!entityNames.add(entity.apiName())) {
                errors.add(field("entities", "entity apiName must be unique per app: " + entity.apiName(),
                        entity.apiName()));
            }
        }

        for (EntityDefinition entity : app.entities()) {
            validateEntity(app, entity, errors);
        }
        return new ProblemErrors(errors, globals);
    }

    private static void validateEntity(AppDefinition app, EntityDefinition entity,
                                       List<ProblemErrors.FieldError> errors) {
        String scope = "entities[" + entity.apiName() + "].";
        Set<String> fieldNames = new HashSet<>();
        Set<String> relationshipNames = new HashSet<>();

        for (FieldDefinition f : entity.fields()) {
            String fScope = scope + "fields[" + f.apiName() + "]";
            if (f.apiName() == null || !CAMEL_CASE.matcher(f.apiName()).matches()) {
                errors.add(field(fScope + ".apiName", "field apiName must be camelCase", f.apiName()));
                continue;
            }
            if (RESERVED_FIELD_NAMES.contains(f.apiName())) {
                errors.add(field(fScope + ".apiName",
                        "field apiName is reserved by the record API: " + f.apiName(), f.apiName()));
            }
            if (!fieldNames.add(f.apiName())) {
                errors.add(field(fScope + ".apiName", "field apiName must be unique per entity", f.apiName()));
            }
            if (f.type() == null) {
                errors.add(field(fScope + ".type", "field type is required", null));
                continue;
            }
            if (f.type() == FieldType.ENUM && f.values().isEmpty()) {
                errors.add(field(fScope + ".values", "enum fields require a non-empty values list", null));
            }
            if (f.type() == FieldType.LOOKUP
                    && (f.target() == null || !appEntityExists(app, f.target()))) {
                errors.add(field(fScope + ".target",
                        "lookup target must resolve within the app: " + f.target(), f.target()));
            }
            if (f.type().numeric()) {
                validatePrecision(f, fScope, errors);
            }
            if (f.length() != null && f.length() < 1) {
                errors.add(field(fScope + ".length", "length must be >= 1", f.length()));
            }
            if (f.defaultValue() instanceof DefaultValue.SequenceReference ref
                    && app.settings().sequence(ref.sequence()).isEmpty()) {
                errors.add(field(fScope + ".default",
                        "default sequence reference must resolve within the app: " + ref.sequence(),
                        ref.sequence()));
            }
            if (f.defaultValue() instanceof DefaultValue.SequenceReference
                    && !f.type().textual() && f.type() != FieldType.UUID) {
                errors.add(field(fScope + ".default",
                        "a sequence default requires a text or uuid field", f.type().wireName()));
            }
        }

        for (RelationshipDefinition r : entity.relationships()) {
            String rScope = scope + "relationships[" + r.apiName() + "]";
            if (r.apiName() == null || !CAMEL_CASE.matcher(r.apiName()).matches()) {
                errors.add(field(rScope + ".apiName", "relationship apiName must be camelCase", r.apiName()));
                continue;
            }
            if (!relationshipNames.add(r.apiName())) {
                errors.add(field(rScope + ".apiName", "relationship apiName must be unique per entity",
                        r.apiName()));
            }
            if (r.type() == null) {
                errors.add(field(rScope + ".type", "relationship type is required (child|m2m)", null));
            }
            if (r.target() == null || !appEntityExists(app, r.target())) {
                errors.add(field(rScope + ".target",
                        "relationship target must resolve within the app: " + r.target(), r.target()));
                continue;
            }
            if (r.type() == RelationshipType.CHILD) {
                Optional<EntityDefinition> target = app.entity(r.target());
                target.ifPresent(childEntity -> {
                    boolean bound = childEntity.fields().stream()
                            .anyMatch(f -> f.type() == FieldType.LOOKUP
                                    && entity.apiName().equals(f.target()));
                    if (!bound) {
                        errors.add(field(rScope + ".target",
                                "child relationship requires the target entity to declare a lookup field "
                                        + "targeting " + entity.apiName(), r.target()));
                    }
                });
            }
        }

        if (entity.displayField() != null && entity.field(entity.displayField()).isEmpty()) {
            errors.add(field(scope + "displayField",
                    "displayField must name an existing field", entity.displayField()));
        }

        Set<String> indexFieldsChecked = new HashSet<>();
        for (EntityDefinition.IndexDefinition index : entity.indexes()) {
            for (String fieldName : index.fields()) {
                if (!indexFieldsChecked.add(entity.apiName() + "." + String.join(",", index.fields()) + fieldName)) {
                    continue;
                }
                if (entity.field(fieldName).isEmpty()) {
                    errors.add(field(scope + "indexes",
                            "index field must exist on the entity: " + fieldName, fieldName));
                }
            }
            if (index.fields().isEmpty()) {
                errors.add(field(scope + "indexes", "index requires at least one field", null));
            }
        }
    }

    private static void validatePrecision(FieldDefinition f, String scope,
                                          List<ProblemErrors.FieldError> errors) {
        Integer precision = f.precision();
        Integer scale = f.scale();
        int effPrecision = precision == null ? defaultPrecision(f.type()) : precision;
        int effScale = scale == null ? defaultScale(f.type()) : scale;
        if (effPrecision < 1 || effPrecision > 38) {
            errors.add(field(scope + ".precision", "precision must be 1..38", precision));
        }
        if (effScale < 0 || effScale > effPrecision) {
            errors.add(field(scope + ".scale", "scale must be 0..precision", scale));
        }
        if (f.type() == FieldType.MONEY && (effPrecision < 18 || effScale < 4)) {
            errors.add(field(scope + ".precision",
                    "money requires decimal(18,4) minimum (ARCHITECTURE.md §4 money rule)", precision));
        }
    }

    private static int defaultPrecision(FieldType type) {
        return type == FieldType.MONEY ? 18 : 38;
    }

    private static int defaultScale(FieldType type) {
        return type == FieldType.MONEY ? 4 : 6;
    }

    private static boolean appEntityExists(AppDefinition app, String apiName) {
        return app.entity(apiName).isPresent();
    }

    private static ProblemErrors.FieldError field(String field, String message, Object rejected) {
        return new ProblemErrors.FieldError(field, message, rejected);
    }

    /** Convenience for callers that only need the boolean. */
    public static boolean isValid(AppDefinition app) {
        return validate(app).isEmpty();
    }

    /** Locale-stable apiName key helper for error payloads. */
    public static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
