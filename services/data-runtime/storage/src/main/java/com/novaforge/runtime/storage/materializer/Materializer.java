package com.novaforge.runtime.storage.materializer;

import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.EntityDefinition;
import com.novaforge.metadata.FieldDefinition;
import com.novaforge.metadata.FieldType;
import com.novaforge.metadata.PromotionPolicy;
import com.novaforge.metadata.Snake;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The storage materializer (PHASE-1 §6, ARCHITECTURE.md §2.3/§4): owns the data-plane
 * DDL, reacting to {@code metadata.published} — DDL happens at publish time only, never
 * on the hot path. ADR-001 variant B: per-entity generated projection table with the
 * data duplicated, STORED generated columns for promoted fields (text/numeric only —
 * cast-immutability), regular + partial-unique indexes, and an AFTER trigger on
 * rec_records keeping the projection current transactionally. The base table stays the
 * single write target.
 *
 * <p><b>Reconcile (PHASE-8 §4 items 4–5):</b> applying a published set is a reconcile,
 * not a create-only pass — projection columns and indexes drop lazily at publish when
 * no live definition needs them, so removing a field (an acknowledged breaking change),
 * dropping an index declaration, or re-typing a field retires the stale derived shape
 * while {@code rec_records} keeps every value in JSONB (nothing is destroyed at
 * publish). Version downgrades ride the same machinery: a compatible rollback
 * re-publishes the prior shape, and a re-promoted column is re-added and backfilled
 * from {@code data} — the projection is derived state, never the record of truth.
 * Because projections are shared per entity apiName across apps and tenants (the DDL is
 * tenant-shared), the reconcile runs over <em>every currently published app</em> — the
 * union — so one app's removal can never retire a column another published app still
 * promotes, and every claiming app's rows sync through one shared trigger (the sync
 * matches the union's entity keys, so two apps publishing the same entity apiName both
 * land). Entities published by no live app retire their projection wholesale (trigger,
 * function, table — recreate-on-republish backfills from rec_records).</p>
 *
 * <p>Each statement runs on its own: a Postgres transaction aborts on the first failed
 * statement, and per-shape isolation ("one app's bad DDL never blocks the others",
 * retried idempotently on the next publish) needs exactly that. The DDL is idempotent,
 * so any partially applied pass converges on the next publish or restart catch-up.</p>
 *
 * <p><b>Pass serialization:</b> Postgres's {@code CREATE … IF NOT EXISTS} is not atomic
 * against a concurrent creator — two interleaved passes race into {@code pg_class} and
 * one dies with a duplicate-key error, its shape skipped until an unpromised "next
 * publish". The subscriber's executor serializes passes only within one JVM; every
 * replica's subscriber and boot catch-up runs its own pass. Each pass therefore holds a
 * session-level Postgres advisory lock (cluster-wide, one pass at a time) around the
 * reconcile — idempotent statements stay per-statement-isolated, but never interleave.</p>
 */
@Component
public class Materializer {

    /** The projection table name for an entity (schema contract, ADR-001). */
    public static String projectionTable(String entityApiName) {
        return "rec_" + Snake.caseName(entityApiName);
    }

    private static final Logger LOG = LoggerFactory.getLogger(Materializer.class);

    /**
     * The cluster-wide reconcile mutex: every pass (publish event, boot catch-up,
     * direct apply) holds this session advisory lock for its duration, so two passes
     * never interleave DDL — {@code CREATE … IF NOT EXISTS} is not atomic against a
     * concurrent creator, and an interleaved loser aborts with a {@code pg_class}
     * duplicate key, its shape skipped until the next publish.
     */
    public static final long PASS_LOCK_KEY = "novaforge.materializer".hashCode();

    /** The fixed non-generated columns every projection carries (ADR-001 schema). */
    private static final Set<String> SYSTEM_COLUMNS = Set.of("id", "tenant_id", "version",
            "created_at", "updated_at", "created_by", "updated_by", "deleted", "data",
            // the owning App.Entity key — projections are shared per bare entity
            // apiName, and per-app unique enforcement needs the discriminator
            "entity_id");

    private final JdbcTemplate jdbc;

    public Materializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Applies one published app (the single-app convenience; see {@link #applyAll}). */
    public void apply(AppDefinition app) {
        applyAll(List.of(app));
    }

    /**
     * Applies the union of every currently published app, reconciling the projections:
     * entities absent from the union retire, stale columns/indexes drop, missing ones
     * (re-)create. Per-shape failures are logged and skipped — one app's bad DDL
     * (e.g. data that no longer casts under a re-typed column) never blocks the rest,
     * and the next publish retries idempotently.
     */
    public void applyAll(List<AppDefinition> apps) {
        Map<String, Shape> shapes = new LinkedHashMap<>();
        int applied = 0;
        for (AppDefinition app : apps) {
            if (app == null) {
                continue;
            }
            applied++;
            for (EntityDefinition entity : app.entities()) {
                String table = projectionTable(entity.apiName());
                if (table.equals("rec_records")) {
                    LOG.warn("entity {} collides with the base table rec_records — skipped",
                            entity.apiName());
                    continue;
                }
                shapes.computeIfAbsent(table, ignored -> new Shape(table)).merge(app, entity);
            }
        }
        // Projections we own are exactly the trg_<table> triggers on rec_records;
        // a managed table no live shape claims retires (§4 item 5's lazy drop).
        withPassLock(() -> {
            for (String table : managedTables()) {
                if (!shapes.containsKey(table)) {
                    try {
                        retire(table);
                    } catch (Exception e) {
                        LOG.error("retiring projection {} failed", table, e);
                    }
                }
            }
            for (Shape shape : shapes.values()) {
                try {
                    applyShape(shape);
                } catch (Exception e) {
                    LOG.error("materializing projection {} failed (retried on the next publish)",
                            shape.table, e);
                }
            }
        });
        LOG.info("materialized {} published app(s) → {} live projection(s)", applied, shapes.size());
    }

    /**
     * Runs one reconcile pass holding {@link #PASS_LOCK_KEY}: a session-level advisory
     * lock on a dedicated guard connection, so concurrent passes — this JVM's executor,
     * another replica's, a boot catch-up — queue instead of interleaving. The lock
     * bounds its own wait (a holder doing slow DDL must not wedge waiters forever);
     * a failed acquisition surfaces as an exception the callers already treat as
     * "retried on the next publish". The pass's DDL keeps running on pooled
     * connections — mutual exclusion comes from every pass passing this gate, not
     * from the statements sharing the guard session.
     */
    private void withPassLock(Runnable pass) {
        var dataSource = Objects.requireNonNull(jdbc.getDataSource(),
                "the materializer requires a DataSource-backed JdbcTemplate");
        try (Connection guard = dataSource.getConnection();
             Statement statement = guard.createStatement()) {
            guard.setAutoCommit(true);
            statement.execute("SET lock_timeout = '120s'");
            statement.execute("SELECT pg_advisory_lock(" + PASS_LOCK_KEY + ")");
            try {
                pass.run();
            } finally {
                try {
                    statement.execute("SELECT pg_advisory_unlock(" + PASS_LOCK_KEY + ")");
                } catch (SQLException unreleased) {
                    // closing the guard session releases a held advisory lock anyway
                    LOG.warn("materializer pass-lock unlock failed — the session close releases it",
                            unreleased);
                }
            }
        } catch (SQLException e) {
            throw new DataAccessResourceFailureException(
                    "could not hold the materializer pass lock (retried on the next publish)", e);
        }
    }

    // --- the union of one projection table's needs across every publishing app ---

    private static final class Shape {
        final String table;
        /** column name → type class; a column claimed as both across apps is a conflict. */
        final Map<String, String> columns = new TreeMap<>();
        /** column name → source field apiName (the generated expression's JSONB key). */
        final Map<String, String> sources = new TreeMap<>();
        /** field apiName → promoted column (union across apps, for index lowering). */
        final Map<String, String> promotedByField = new TreeMap<>();
        /** index name → its field list, keyed so both apps declaring it converge. */
        final Map<String, List<String>> indexColumns = new TreeMap<>();
        final Set<String> uniqueFields = new LinkedHashSet<>();
        /** field apiName → numeric class, agreed (a field is numeric only if every claim is). */
        final Map<String, Boolean> fieldNumeric = new TreeMap<>();
        /** every 'App.Entity' key claiming this table — the trigger's sync set. */
        final Set<String> entityKeys = new LinkedHashSet<>();
        String displayField;

        Shape(String table) {
            this.table = table;
        }

        void merge(AppDefinition app, EntityDefinition entity) {
            entityKeys.add(app.apiName() + "." + entity.apiName());
            for (FieldDefinition field : entity.fields()) {
                fieldNumeric.merge(field.apiName(), field.type().numeric(),
                        (a, b) -> a && b);
            }
            for (Map.Entry<String, String> promotion : PromotionPolicy.promotedColumns(entity).entrySet()) {
                String field = promotion.getKey();
                String column = promotion.getValue();
                String type = generatedColumnType(entity.field(field).orElseThrow().type());
                String prior = columns.putIfAbsent(column, type);
                if (prior != null && !prior.equals(type)) {
                    // Two live apps claim the shared column with different type classes —
                    // the table cannot be both. Keep whatever exists; the losing app's
                    // lowering falls back to the JSONB path. Loud, never silent.
                    LOG.warn("projection {} column {} claimed as both {} and {} — leaving it "
                            + "untouched; the losing shape reads the JSONB path", table, column,
                            prior, type);
                } else {
                    sources.putIfAbsent(column, field);
                }
                promotedByField.putIfAbsent(field, column);
            }
            for (EntityDefinition.IndexDefinition index : entity.indexes()) {
                // Snake-cased field lists collide ([totalAmount] vs [total, amount]
                // both lower to total_amount) and CREATE INDEX IF NOT EXISTS would
                // silently drop the second declaration — disambiguate with a suffix
                // so every declared index exists
                String name = indexName(table, index.fields());
                while (indexColumns.containsKey(name)
                        && !indexColumns.get(name).equals(index.fields())) {
                    name = name + "_";
                }
                indexColumns.putIfAbsent(name, new ArrayList<>(index.fields()));
            }
            for (FieldDefinition field : entity.fields()) {
                if (field.uniqueOn()) {
                    uniqueFields.add(field.apiName());
                }
            }
            if (entity.displayField() != null) {
                displayField = entity.displayField();
            }
        }

        /** One index-lowering target: the promoted column when the union has it, else JSONB. */
        String indexTarget(String field) {
            String column = promotedByField.get(field);
            if (column != null) {
                return column;
            }
            return Boolean.TRUE.equals(fieldNumeric.get(field))
                    ? "((data->>'" + field + "')::numeric)"
                    : "(data->>'" + field + "')";
        }

        static String indexName(String table, List<String> fields) {
            return "ix_" + table + "_" + String.join("_",
                    fields.stream().map(Snake::caseName).toList());
        }
    }

    // --- reconcile ---

    private void applyShape(Shape shape) {
        boolean existing = tableExists(shape.table);
        if (!existing) {
            jdbc.execute(createTable(shape));
        } else {
            // Stage 1 of the pre-entity_id migration: the column lands NULLABLE and
            // backfills — never NOT NULL yet, because the trigger function installed
            // by the previous code version still inserts WITHOUT entity_id, and a
            // NOT NULL column would abort every live insert from here until the
            // function swap below commits.
            ensureEntityKeyColumn(shape);
        }
        reconcileColumns(shape);
        reconcileIndexes(shape);
        applySyncMachinery(shape);
        if (existing) {
            // Stage 2: the new trigger (which stamps entity_id) is live and every row
            // carries its key — rows the old function wrote during the window get a
            // final stamp here — so the column can go NOT NULL safely.
            enforceEntityKeyNotNull(shape);
        }
        if (!existing) {
            // Backfill a freshly created (or recreated) projection from the base table;
            // an existing projection stays current through its trigger, and a generated
            // column added later computes over the rows already present.
            jdbc.execute(backfill(shape));
        }
        LOG.debug("materialized projection {} ({} promoted columns)", shape.table,
                shape.columns.size());
    }

    private String createTable(Shape shape) {
        StringBuilder create = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(shape.table)
                .append(" (")
                .append("id uuid PRIMARY KEY, ")
                .append("tenant_id uuid NOT NULL, ")
                .append("entity_id text NOT NULL, ")
                .append("version int NOT NULL, ")
                .append("created_at timestamptz NOT NULL, ")
                .append("updated_at timestamptz NOT NULL, ")
                .append("created_by uuid NOT NULL, ")
                .append("updated_by uuid NOT NULL, ")
                .append("deleted boolean NOT NULL DEFAULT false, ")
                .append("data jsonb NOT NULL");
        for (Map.Entry<String, String> column : shape.columns.entrySet()) {
            create.append(", ").append(column.getKey()).append(' ').append(column.getValue())
                    .append(" GENERATED ALWAYS AS (")
                    .append(jsonbExtract(shape.sources.get(column.getKey()),
                            "numeric".equals(column.getValue())))
                    .append(") STORED");
        }
        return create.append(")").toString();
    }

    private String backfill(Shape shape) {
        return "INSERT INTO " + shape.table
                + " (id, tenant_id, entity_id, version, created_at, updated_at, created_by, "
                + "updated_by, deleted, data) "
                + "SELECT id, tenant_id, entity_id, version, created_at, updated_at, created_by, "
                + "updated_by, deleted, data "
                + "FROM rec_records WHERE entity_id = ANY(" + keyLiterals(shape) + "::text[]) "
                + "ON CONFLICT (id) DO NOTHING";
    }

    /**
     * Migration stage 1: pre-entity_id projections gain the owning App.Entity key —
     * the column lands nullable and backfills from the base rows; rows with no base
     * counterpart are dead artifacts the sync trigger would have removed. Nullable
     * on purpose: until {@code applySyncMachinery} installs the new trigger body,
     * the previous function still inserts without the column.
     */
    private void ensureEntityKeyColumn(Shape shape) {
        jdbc.execute("ALTER TABLE " + shape.table + " ADD COLUMN IF NOT EXISTS entity_id text");
        jdbc.update("UPDATE " + shape.table + " p SET entity_id = r.entity_id "
                + "FROM rec_records r WHERE r.id = p.id AND (p.entity_id IS NULL "
                + "OR p.entity_id <> r.entity_id)");
        jdbc.update("DELETE FROM " + shape.table + " p WHERE p.entity_id IS NULL "
                + "AND NOT EXISTS (SELECT 1 FROM rec_records r WHERE r.id = p.id)");
    }

    /**
     * Migration stage 2 — after the new trigger is live: a final stamp for rows the
     * old function wrote during the window, then the column goes NOT NULL. Fails
     * soft: if a row still cannot resolve (no base counterpart, no trigger stamp),
     * the column stays nullable and the next pass retries — inserts keep working,
     * and the per-app unique index (NULLs distinct) just enforces less until it
     * converges.
     */
    private void enforceEntityKeyNotNull(Shape shape) {
        jdbc.update("UPDATE " + shape.table + " p SET entity_id = r.entity_id "
                + "FROM rec_records r WHERE r.id = p.id AND p.entity_id IS NULL");
        try {
            jdbc.execute("ALTER TABLE " + shape.table
                    + " ALTER COLUMN entity_id SET NOT NULL");
        } catch (org.springframework.dao.DataAccessException unresolved) {
            LOG.warn("projection {} still holds rows without an entity key — NOT NULL "
                    + "deferred to the next pass: {}", shape.table, unresolved.getMessage());
        }
    }

    private void reconcileColumns(Shape shape) {
        Map<String, String> present = existingColumns(shape.table);
        // Lazy drop (§4 item 5): a generated column no live shape promotes retires —
        // dropping it drops its dependent indexes with it; values stay in data.
        for (String column : new java.util.TreeSet<>(present.keySet())) {
            if (!SYSTEM_COLUMNS.contains(column) && !shape.columns.containsKey(column)) {
                jdbc.execute("ALTER TABLE " + shape.table + " DROP COLUMN IF EXISTS " + column);
                present.remove(column);
            }
        }
        for (Map.Entry<String, String> desired : shape.columns.entrySet()) {
            String column = desired.getKey();
            String type = desired.getValue();
            String current = present.get(column);
            if (current == null) {
                // ADD COLUMN backfills the stored generated expression over existing rows.
                jdbc.execute("ALTER TABLE " + shape.table + " ADD COLUMN IF NOT EXISTS " + column
                        + " " + type + " GENERATED ALWAYS AS ("
                        + jsonbExtract(shape.sources.get(column), "numeric".equals(type))
                        + ") STORED");
            } else if (!current.equals(type)) {
                // A re-typed field (an acknowledged breaking change): drop and re-add —
                // the stored expression recomputes from data under the new type class.
                jdbc.execute("ALTER TABLE " + shape.table + " DROP COLUMN " + column);
                jdbc.execute("ALTER TABLE " + shape.table + " ADD COLUMN " + column
                        + " " + type + " GENERATED ALWAYS AS ("
                        + jsonbExtract(shape.sources.get(column), "numeric".equals(type))
                        + ") STORED");
            }
        }
    }

    private void reconcileIndexes(Shape shape) {
        Set<String> expected = new LinkedHashSet<>(shape.indexColumns.keySet());
        for (String field : shape.uniqueFields) {
            // the _app twin retires the tenant-wide predecessor (the managed-index
            // sweep below) — scoping by the App.Entity key is the whole point
            expected.add("ux_" + shape.table + "_" + Snake.caseName(field) + "_app");
        }
        boolean displayIndexed = shape.displayField != null
                && shape.promotedByField.containsKey(shape.displayField);
        if (displayIndexed) {
            expected.add("ix_" + shape.table + "_display");
        }
        expected.add("ix_" + shape.table + "_updated");

        // Managed indexes this table carries but no live shape declares retire with it.
        for (String index : managedIndexes(shape.table)) {
            if (!expected.contains(index)) {
                jdbc.execute("DROP INDEX IF EXISTS " + index);
            }
        }
        for (Map.Entry<String, List<String>> index : shape.indexColumns.entrySet()) {
            String columns = String.join(", ", index.getValue().stream()
                    .map(shape::indexTarget).toList());
            jdbc.execute("CREATE INDEX IF NOT EXISTS " + index.getKey()
                    + " ON " + shape.table + " (tenant_id, " + columns + ") WHERE NOT deleted");
        }
        for (String field : shape.uniqueFields) {
            jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_" + shape.table + "_"
                    + Snake.caseName(field) + "_app"
                    + " ON " + shape.table + " (tenant_id, entity_id, "
                    + shape.indexTarget(field)
                    + ") WHERE NOT deleted");
        }
        if (displayIndexed) {
            jdbc.execute("CREATE INDEX IF NOT EXISTS ix_" + shape.table + "_display"
                    + " ON " + shape.table + " (tenant_id, " + shape.indexTarget(shape.displayField)
                    + ") WHERE NOT deleted");
        }
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_" + shape.table + "_updated"
                + " ON " + shape.table + " (tenant_id, updated_at DESC) WHERE NOT deleted");
    }

    private void applySyncMachinery(Shape shape) {
        String function = "sync_" + shape.table;
        String keys = keyLiterals(shape);
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION %s() RETURNS trigger AS $$
                BEGIN
                  IF TG_OP = 'INSERT' AND NEW.entity_id = ANY(%s::text[]) THEN
                    INSERT INTO %s (id, tenant_id, entity_id, version, created_at, updated_at, created_by, updated_by, deleted, data)
                    VALUES (NEW.id, NEW.tenant_id, NEW.entity_id, NEW.version, NEW.created_at, NEW.updated_at, NEW.created_by, NEW.updated_by, NEW.deleted, NEW.data);
                  ELSIF TG_OP = 'UPDATE' AND NEW.entity_id = ANY(%s::text[]) THEN
                    UPDATE %s SET version = NEW.version, updated_at = NEW.updated_at, updated_by = NEW.updated_by,
                                   deleted = NEW.deleted, data = NEW.data WHERE id = NEW.id;
                  ELSIF TG_OP = 'DELETE' AND OLD.entity_id = ANY(%s::text[]) THEN
                    DELETE FROM %s WHERE id = OLD.id;
                  END IF;
                  RETURN NULL;
                END $$ LANGUAGE plpgsql""".formatted(function, keys,
                shape.table, keys, shape.table, keys, shape.table));
        jdbc.execute("DROP TRIGGER IF EXISTS trg_" + shape.table + " ON rec_records");
        jdbc.execute("CREATE TRIGGER trg_" + shape.table
                + " AFTER INSERT OR UPDATE OR DELETE ON rec_records "
                + "FOR EACH ROW EXECUTE FUNCTION " + function + "()");

        // RLS on the projection, same fail-closed shape as rec_records (ADR-006).
        jdbc.execute("ALTER TABLE " + shape.table + " ENABLE ROW LEVEL SECURITY");
        jdbc.execute("ALTER TABLE " + shape.table + " FORCE ROW LEVEL SECURITY");
        jdbc.execute("DROP POLICY IF EXISTS tenant_isolation ON " + shape.table);
        jdbc.execute("CREATE POLICY tenant_isolation ON " + shape.table + " USING ("
                + "current_setting('app.tenant', true) <> '' "
                + "AND tenant_id::text = current_setting('app.tenant', true))");
    }

    private void retire(String table) {
        jdbc.execute("DROP TRIGGER IF EXISTS trg_" + table + " ON rec_records");
        jdbc.execute("DROP FUNCTION IF EXISTS sync_" + table + "()");
        jdbc.execute("DROP TABLE IF EXISTS " + table);
        LOG.info("retired projection {} — no published app carries its entity", table);
    }

    // --- catalog reads ---

    private boolean tableExists(String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT to_regclass(?) IS NOT NULL", Boolean.class, table));
    }

    /** The managed trigger-owning projections (trg_ on rec_records). */
    private Set<String> managedTables() {
        return new LinkedHashSet<>(jdbc.queryForList("""
                        SELECT DISTINCT trigger_name FROM information_schema.triggers
                         WHERE event_object_table = 'rec_records' AND trigger_name LIKE 'trg\\_%'""",
                        String.class).stream().map(name -> name.substring("trg_".length())).toList());
    }

    /** The materializer-owned indexes on one projection (the ix_/ux_ conventions). */
    private Set<String> managedIndexes(String table) {
        return new LinkedHashSet<>(jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = ? "
                        + "AND (indexname LIKE 'ix\\_" + table + "\\_%' OR indexname LIKE 'ux\\_" + table + "\\_%')",
                String.class, table));
    }

    /** column name → data_type for one projection's columns. */
    private Map<String, String> existingColumns(String table) {
        Map<String, String> columns = new TreeMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT column_name, data_type FROM information_schema.columns WHERE table_name = ?",
                table)) {
            columns.put(String.valueOf(row.get("column_name")), String.valueOf(row.get("data_type")));
        }
        return columns;
    }

    /** The union's entity keys as a SQL array literal: ARRAY['Erp.Invoice',...] */
    private static String keyLiterals(Shape shape) {
        StringBuilder literal = new StringBuilder("ARRAY[");
        for (String key : shape.entityKeys) {
            if (literal.length() > "ARRAY[".length()) {
                literal.append(", ");
            }
            literal.append('\'').append(key.replace("'", "''")).append('\'');
        }
        return literal.append("]").toString();
    }

    private static String jsonbExtract(String field, boolean numeric) {
        return numeric ? "((data->>'" + field + "')::numeric)" : "(data->>'" + field + "')";
    }

    private static String generatedColumnType(FieldType type) {
        return switch (type) {
            case INT, LONG, DECIMAL, MONEY -> "numeric";
            default -> "text";
        };
    }
}
