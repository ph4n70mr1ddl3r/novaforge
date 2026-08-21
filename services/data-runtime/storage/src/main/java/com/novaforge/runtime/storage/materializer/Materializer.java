package com.novaforge.runtime.storage.materializer;

import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.EntityDefinition;
import com.novaforge.metadata.FieldDefinition;
import com.novaforge.metadata.FieldType;
import com.novaforge.runtime.storage.query.PromotionPolicy;
import com.novaforge.runtime.storage.query.QueryLowering;
import com.novaforge.runtime.storage.query.Snake;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The storage materializer (PHASE-1 §6, ARCHITECTURE.md §2.3/§4): owns the data-plane
 * DDL, reacting to {@code metadata.published} — DDL happens at publish time only, never
 * on the hot path. ADR-001 variant B: per-entity generated projection table with the
 * data duplicated, STORED generated columns for promoted fields (text/numeric only —
 * cast-immutability), regular + partial-unique indexes, and an AFTER trigger on
 * rec_records keeping the projection current transactionally. The base table stays the
 * single write target.
 */
@Component
public class Materializer {

    private static final Logger LOG = LoggerFactory.getLogger(Materializer.class);

    private final JdbcTemplate jdbc;

    public Materializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Applies a published bundle: one projection per entity, idempotently. */
    @Transactional
    public void apply(AppDefinition app) {
        for (EntityDefinition entity : app.entities()) {
            applyEntity(app, entity);
        }
        LOG.info("materialized app {} with {} entities", app.apiName(), app.entities().size());
    }

    private void applyEntity(AppDefinition app, EntityDefinition entity) {
        String table = QueryLowering.projectionTable(entity.apiName());
        String entityKey = app.apiName() + "." + entity.apiName();

        Map<String, String> promoted = PromotionPolicy.promotedColumns(entity);
        StringBuilder create = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(table).append(" (")
                .append("id uuid PRIMARY KEY, ")
                .append("tenant_id uuid NOT NULL, ")
                .append("version int NOT NULL, ")
                .append("created_at timestamptz NOT NULL, ")
                .append("updated_at timestamptz NOT NULL, ")
                .append("created_by uuid NOT NULL, ")
                .append("updated_by uuid NOT NULL, ")
                .append("deleted boolean NOT NULL DEFAULT false, ")
                .append("data jsonb NOT NULL");
        for (Map.Entry<String, String> promotion : promoted.entrySet()) {
            FieldDefinition field = entity.field(promotion.getKey()).orElseThrow();
            String type = generatedColumnType(field.type());
            create.append(", ").append(promotion.getValue()).append(' ').append(type)
                    .append(" GENERATED ALWAYS AS (").append(jsonbExtract(field)).append(") STORED");
        }
        create.append(")");

        List<String> statements = new ArrayList<>();
        statements.add(create.toString());
        // Backfill when the table pre-exists (added columns are missing rows).
        statements.add("INSERT INTO " + table + " (id, tenant_id, version, created_at, updated_at, created_by, updated_by, deleted, data) "
                + "SELECT id, tenant_id, version, created_at, updated_at, created_by, updated_by, deleted, data "
                + "FROM rec_records WHERE entity_id = '" + entityKey.replace("'", "''") + "' "
                + "ON CONFLICT (id) DO NOTHING");
        // Indexes: entity-level declarations + per-field uniqueness, partial over live rows.
        for (EntityDefinition.IndexDefinition index : entity.indexes()) {
            String columns = String.join(", ", index.fields().stream()
                    .map(f -> promoted.containsKey(f) ? promoted.get(f) : jsonbIndexExpr(entity, f))
                    .toList());
            String unique = index.uniqueOn() ? "UNIQUE " : "";
            statements.add("CREATE " + unique + "INDEX IF NOT EXISTS ix_" + table + "_"
                    + String.join("_", index.fields().stream().map(Snake::caseName).toList())
                    + " ON " + table + " (tenant_id, " + columns + ") WHERE NOT deleted");
        }
        for (FieldDefinition field : entity.fields()) {
            if (field.uniqueOn()) {
                String column = promoted.containsKey(field.apiName())
                        ? promoted.get(field.apiName())
                        : jsonbIndexExpr(entity, field.apiName());
                statements.add("CREATE UNIQUE INDEX IF NOT EXISTS ux_" + table + "_"
                        + Snake.caseName(field.apiName())
                        + " ON " + table + " (tenant_id, " + column + ") WHERE NOT deleted");
            }
        }
        if (entity.displayField() != null && promoted.containsKey(entity.displayField())) {
            statements.add("CREATE INDEX IF NOT EXISTS ix_" + table + "_display"
                    + " ON " + table + " (tenant_id, " + promoted.get(entity.displayField()) + ")"
                    + " WHERE NOT deleted");
        }
        // Default recency index for lists without declared sorts.
        statements.add("CREATE INDEX IF NOT EXISTS ix_" + table + "_updated"
                + " ON " + table + " (tenant_id, updated_at DESC) WHERE NOT deleted");

        // Trigger: rec_records is the write target; the projection rides its transaction.
        String function = "sync_" + table;
        String entityLiteral = "'" + entityKey.replace("'", "''") + "'";
        statements.add("""
                CREATE OR REPLACE FUNCTION %s() RETURNS trigger AS $$
                BEGIN
                  IF TG_OP IN ('INSERT', 'UPDATE') AND NEW.entity_id IS DISTINCT FROM %s THEN
                    RETURN NULL;
                  END IF;
                  IF TG_OP = 'DELETE' AND OLD.entity_id IS DISTINCT FROM %s THEN
                    RETURN NULL;
                  END IF;
                  IF TG_OP = 'INSERT' THEN
                    INSERT INTO %s (id, tenant_id, version, created_at, updated_at, created_by, updated_by, deleted, data)
                    VALUES (NEW.id, NEW.tenant_id, NEW.version, NEW.created_at, NEW.updated_at, NEW.created_by, NEW.updated_by, NEW.deleted, NEW.data);
                  ELSIF TG_OP = 'UPDATE' THEN
                    UPDATE %s SET version = NEW.version, updated_at = NEW.updated_at, updated_by = NEW.updated_by,
                                   deleted = NEW.deleted, data = NEW.data WHERE id = NEW.id;
                  ELSIF TG_OP = 'DELETE' THEN
                    DELETE FROM %s WHERE id = OLD.id;
                  END IF;
                  RETURN NULL;
                END $$ LANGUAGE plpgsql""".formatted(function, entityLiteral, entityLiteral,
                table, table, table));
        statements.add("DROP TRIGGER IF EXISTS trg_" + table + " ON rec_records");
        statements.add("CREATE TRIGGER trg_" + table + " AFTER INSERT OR UPDATE OR DELETE ON rec_records "
                + "FOR EACH ROW EXECUTE FUNCTION " + function + "()");

        // RLS on the projection, same fail-closed shape as rec_records (ADR-006).
        statements.add("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY");
        statements.add("ALTER TABLE " + table + " FORCE ROW LEVEL SECURITY");
        statements.add("DROP POLICY IF EXISTS tenant_isolation ON " + table);
        statements.add("CREATE POLICY tenant_isolation ON " + table + " USING ("
                + "current_setting('app.tenant', true) <> '' "
                + "AND tenant_id::text = current_setting('app.tenant', true))");

        for (String statement : statements) {
            jdbc.execute(statement);
        }
        LOG.debug("materialized entity {} → {} ({} promoted columns)",
                entityKey, table, promoted.size());
    }

    private static String jsonbExtract(FieldDefinition field) {
        if (field.type().numeric()) {
            return "((data->>'" + field.apiName() + "')::numeric)";
        }
        return "(data->>'" + field.apiName() + "')";
    }

    private static String jsonbIndexExpr(EntityDefinition entity, String fieldName) {
        return entity.field(fieldName).filter(f -> f.type().numeric())
                .map(f -> "((data->>'" + fieldName + "')::numeric)")
                .orElse("(data->>'" + fieldName + "')");
    }

    private static String generatedColumnType(FieldType type) {
        return switch (type) {
            case INT, LONG, DECIMAL, MONEY -> "numeric";
            default -> "text";
        };
    }
}
