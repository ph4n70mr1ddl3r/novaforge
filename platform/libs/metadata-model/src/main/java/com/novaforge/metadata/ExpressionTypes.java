package com.novaforge.metadata;

import com.novaforge.expression.Expression;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * The entity's fields as static expression shapes (PHASE-3 §2's type-aware
 * compile-check leg): every expression slot that compile-checks against an
 * entity's bindings also runs {@link Expression#arithmeticCheck} with this
 * resolver, so Annex A violations that static field types can name — text under
 * {@code +}, booleans under arithmetic, collections under {@code *} — reject at
 * save/publish instead of surfacing as evaluation-time 500s. Bindings whose
 * shape is not statically meaningful (json, file, dotted paths, unknown names)
 * resolve {@link Expression.ValueType#UNKNOWN} and stay fail-open: the
 * evaluator remains their authority.
 */
public final class ExpressionTypes {

    private ExpressionTypes() {
    }

    /**
     * The record-context shape resolver for one entity: field apiNames and
     * relationship apiNames by their authored types, plus the executor's injected
     * {@code id} (always a record-id string).
     */
    public static Function<String, Expression.ValueType> of(EntityDefinition entity) {
        Map<String, Expression.ValueType> types = new HashMap<>();
        entity.fields().forEach(field -> types.put(field.apiName(), shape(field.type())));
        entity.relationships().forEach(rel -> types.put(rel.apiName(), Expression.ValueType.LIST));
        types.put("id", Expression.ValueType.TEXT);
        return path -> types.getOrDefault(path, Expression.ValueType.UNKNOWN);
    }

    private static Expression.ValueType shape(FieldType type) {
        return switch (type) {
            case INT, LONG, DECIMAL, MONEY -> Expression.ValueType.NUMERIC;
            case DATE -> Expression.ValueType.DATE;
            case DATETIME -> Expression.ValueType.DATETIME;
            case BOOLEAN -> Expression.ValueType.BOOLEAN;
            case CHILD, M2M -> Expression.ValueType.LIST;
            case JSON, FILE -> Expression.ValueType.UNKNOWN;
            case TEXT, LONG_TEXT, RICH_TEXT, EMAIL, PHONE, URL, ENUM, UUID, LOOKUP, TIME ->
                    Expression.ValueType.TEXT;
        };
    }
}
