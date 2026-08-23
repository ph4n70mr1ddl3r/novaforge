package com.novaforge.metadata;

import com.novaforge.expression.ExpressionSql;
import java.util.Map;

/**
 * Resolves an entity's fields into {@link ExpressionSql} lowering inputs — the shared
 * bridge between the definition model and the SQL lowering (PHASE-5 §3). The
 * Metadata Service's publish-time compile gate and the Data Runtime's aggregate
 * pipeline both lower bucket/criteria expressions through this resolver, so their
 * field shapes can never disagree: promoted fields use their projection columns,
 * everything else the JSONB extract, numerics cast, temporals compared as the
 * canonical ISO text the platform stores (ADR-001 — lexicographic order is
 * chronological order), booleans in the query DSL's canonical {@code 'true'/'false'}
 * text form.
 *
 * <p>Fields that cannot lower (json, file, collections) resolve to null — the lowering
 * then reports the reference as unlowerable, an authoring error surfaced at save.</p>
 */
public final class ExpressionFields {

    private ExpressionFields() {
    }

    /** The resolver over one entity's fields, promotion-aware. */
    public static ExpressionSql.FieldResolver resolver(EntityDefinition entity) {
        Map<String, String> promoted = PromotionPolicy.promotedColumns(entity);
        return path -> {
            var field = entity.field(path);
            if (field.isEmpty() || field.get().type() == null) {
                return null;   // unresolved — the compile check reports it
            }
            var type = field.get().type();
            String sql = promoted.containsKey(path) ? promoted.get(path)
                    : "(data->>'" + path + "')";
            return switch (type) {
                case INT, LONG, DECIMAL, MONEY -> new ExpressionSql.Field(
                        promoted.containsKey(path) ? sql : "((" + sql + ")::numeric)",
                        ExpressionSql.SqlType.NUMBER);
                case DATE -> new ExpressionSql.Field(sql, ExpressionSql.SqlType.DATE);
                case DATETIME -> new ExpressionSql.Field(sql, ExpressionSql.SqlType.DATETIME);
                case TIME -> new ExpressionSql.Field(sql, ExpressionSql.SqlType.TIME);
                case TEXT, LONG_TEXT, RICH_TEXT, ENUM, EMAIL, PHONE, URL, UUID, LOOKUP ->
                        new ExpressionSql.Field(sql, ExpressionSql.SqlType.TEXT);
                case BOOLEAN -> new ExpressionSql.Field("(data->>'" + path + "')",
                        ExpressionSql.SqlType.BOOLEAN);
                case JSON, FILE, CHILD, M2M -> null;   // never lowers
            };
        };
    }
}
