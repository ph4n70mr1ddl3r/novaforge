package com.novaforge.metadata;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Projection promotion policy (PHASE-1 §12 Q3, decided): fields named in entity-level
 * {@code indexes} declarations and unique constraints promote, plus automatic promotion
 * of display and lookup fields. Promotion lowers to generated columns on the ADR-001
 * projection table (text/numeric only — cast-immutability constraint).
 *
 * <p>Lives in the metadata model because promotion is a pure function of the published
 * entity definition with three consumers that must never disagree: the materializer's
 * DDL (publish time), the query lowering (run time), and the Metadata Service's
 * save-time validation (PHASE-5 §3 — report group-by/aggregate fields must promote
 * or the definition is rejected with guidance).</p>
 */
public final class PromotionPolicy {

    private PromotionPolicy() {
    }

    /** Promoted fields in deterministic order: apiName → promoted column name. */
    public static Map<String, String> promotedColumns(EntityDefinition entity) {
        Set<String> promoted = new LinkedHashSet<>();
        for (EntityDefinition.IndexDefinition index : entity.indexes()) {
            promoted.addAll(index.fields());
        }
        for (FieldDefinition field : entity.fields()) {
            if (field.uniqueOn()) {
                promoted.add(field.apiName());
            }
        }
        if (entity.displayField() != null) {
            promoted.add(entity.displayField());
        }
        for (FieldDefinition field : entity.fields()) {
            if (field.type() == FieldType.LOOKUP || field.type() == FieldType.CHILD
                    || field.type() == FieldType.M2M) {
                promoted.add(field.apiName());
            }
        }
        // Only types that can lower to a generated column promote (ADR-001: text/numeric).
        Set<String> promotable = new LinkedHashSet<>();
        for (FieldDefinition field : entity.fields()) {
            if (promoted.contains(field.apiName()) && promotableType(field.type())) {
                promotable.add(field.apiName());
            }
        }
        Map<String, String> columns = new LinkedHashMap<>();
        Set<String> used = new LinkedHashSet<>();
        for (String apiName : promotable) {
            String column = Snake.caseName(apiName);
            while (used.contains(column)) {
                column = column + "_";
            }
            used.add(column);
            columns.put(apiName, column);
        }
        return columns;
    }

    private static boolean promotableType(FieldType type) {
        return switch (type) {
            case TEXT, LONG_TEXT, RICH_TEXT, ENUM, INT, LONG, DECIMAL, MONEY, DATE,
                    DATETIME, TIME, UUID, EMAIL, PHONE, URL, LOOKUP -> true;
            case BOOLEAN, JSON, CHILD, M2M, FILE -> false;
        };
    }
}
