package com.novaforge.runtime.storage.record;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * All tenant-record SQL (storage SPI implementation — ARCHITECTURE.md §4/§7). Writes go
 * to {@code rec_records} only; projections stay current via the materializer's trigger
 * (ADR-001). Reads that need filters run against the projection table; the base table
 * stays the source of truth for point access.
 */
@Repository
public class RecordStore {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;

    public RecordStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record StoredRecord(UUID id, UUID tenantId, String entityId, int version,
                               String createdAt, String updatedAt, UUID createdBy, UUID updatedBy,
                               boolean deleted, Map<String, Object> data) {
    }

    // --- writes (base table only; trigger syncs projections) ---

    public void insert(UUID tenantId, String entityId, UUID id, Map<String, Object> data,
                       UUID actor) {
        jdbc.update("""
                INSERT INTO rec_records (id, tenant_id, entity_id, version, created_by, updated_by, data)
                VALUES (?, ?, ?, 1, ?, ?, ?::jsonb)""",
                id, tenantId, entityId, actor, actor, MAPPER.writeValueAsString(data));
    }

    /**
     * Optimistic-locked update (PHASE-1 §5): returns the new version, or throws
     * CONFLICT_VERSION when the stored version moved or the row is gone/soft-deleted.
     */
    public int update(UUID tenantId, String entityId, UUID id, Map<String, Object> data,
                      int expectedVersion, UUID actor) {
        List<Integer> updated = jdbc.query("""
                UPDATE rec_records
                   SET version = version + 1, updated_at = now(), updated_by = ?, data = ?::jsonb
                 WHERE id = ? AND tenant_id = ? AND entity_id = ? AND version = ? AND NOT deleted
                RETURNING version""",
                (rs, i) -> rs.getInt(1),
                actor, MAPPER.writeValueAsString(data), id, tenantId, entityId, expectedVersion);
        if (updated.isEmpty()) {
            throw conflict(entityId, id, expectedVersion);
        }
        return updated.getFirst();
    }

    /** Soft delete; cascade rows are deleted by the caller in the same transaction. */
    public void softDelete(UUID tenantId, String entityId, UUID id, int expectedVersion, UUID actor) {
        int rows = jdbc.update("""
                UPDATE rec_records
                   SET deleted = true, version = version + 1, updated_at = now(), updated_by = ?
                 WHERE id = ? AND tenant_id = ? AND entity_id = ? AND version = ? AND NOT deleted""",
                actor, id, tenantId, entityId, expectedVersion);
        if (rows == 0) {
            throw conflict(entityId, id, expectedVersion);
        }
    }

    private static PlatformException conflict(String entityId, UUID id, int expectedVersion) {
        return new PlatformException(PlatformErrorCode.CONFLICT_VERSION,
                "record " + entityId + "/" + id + " changed since version " + expectedVersion);
    }

    // --- reads ---

    public Optional<StoredRecord> find(UUID tenantId, String entityId, UUID id, boolean includeDeleted) {
        return jdbc.query("""
                        SELECT id, tenant_id, entity_id, version, created_at, updated_at, created_by, updated_by, deleted, data
                          FROM rec_records
                         WHERE id = ? AND tenant_id = ? AND entity_id = ?"""
                        + (includeDeleted ? "" : " AND NOT deleted"),
                RecordStore::mapRow, id, tenantId, entityId).stream().findFirst();
    }

    /** Uniqueness pre-check (friendly error shaper — the partial index is enforcement, §6). */
    public boolean valueExists(UUID tenantId, String entityId, String field, Object value, UUID excludeId) {
        String exclude = excludeId == null ? "" : " AND id <> ?";
        Object[] args = excludeId == null
                ? new Object[] {tenantId, entityId, field, String.valueOf(value)}
                : new Object[] {tenantId, entityId, field, String.valueOf(value), excludeId};
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM rec_records
                 WHERE tenant_id = ? AND entity_id = ? AND data->>? = ? AND NOT deleted""" + exclude,
                Integer.class, args);
        return count != null && count > 0;
    }

    /**
     * The numeric-field twin: the text pre-check cannot see that {@code 10} and a
     * stored {@code 10.00} are the same number — jsonb preserves the written scale,
     * while the projection's unique index compares the cast numeric, where they do
     * collide. Compare numerically so the pre-check matches what the index enforces
     * (and the friendly field-scoped error fires instead of the constraint's).
     */
    public boolean numericValueExists(UUID tenantId, String entityId, String field,
                                      Object value, UUID excludeId) {
        String exclude = excludeId == null ? "" : " AND id <> ?";
        Object[] args = excludeId == null
                ? new Object[] {tenantId, entityId, field, field, String.valueOf(value)}
                : new Object[] {tenantId, entityId, field, field, String.valueOf(value), excludeId};
        // The regex gate keeps the cast total: a legacy row holding a non-numeric
        // string under the field simply fails the shape test (NULL ≠ the number) and
        // cannot abort the scan.
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM rec_records
                 WHERE tenant_id = ? AND entity_id = ?
                   AND (CASE WHEN data->>? ~ '^-?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?$'
                             THEN (data->>?)::numeric END) = ?::numeric
                   AND NOT deleted""" + exclude,
                Integer.class, args);
        return count != null && count > 0;
    }

    /** Lookup-target existence check (PHASE-1 §5 validations). */
    public boolean targetExists(UUID tenantId, String targetEntityId, UUID targetId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM rec_records
                 WHERE tenant_id = ? AND entity_id = ? AND id = ? AND NOT deleted""",
                Integer.class, tenantId, targetEntityId, targetId);
        return count != null && count > 0;
    }

    public record PageResult(List<Map<String, Object>> rows, long total) {
    }

    public record GroupedResult(List<Map<String, Object>> rows) {
    }

    /** Executes the lowered statements — the storage SPI stays engine-type-free. */
    public PageResult list(String countSql, List<Object> countParams,
                           String listSql, List<Object> listParams) {
        Long total = jdbc.queryForObject(countSql, Long.class, countParams.toArray());
        List<Map<String, Object>> rows = new ArrayList<>();
        jdbc.query(listSql, (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                rows.add(rowShape(rs)), listParams.toArray());
        return new PageResult(rows, total == null ? 0 : total);
    }

    /** Scalar count for roll-ups. */
    public Long countValue(String sql, List<Object> params) {
        return jdbc.queryForObject(sql, Long.class, params.toArray());
    }

    /** Scalar aggregate value by result column label. */
    public Object aggregateValue(String sql, List<Object> params, String label) {
        return jdbc.query(sql, (org.springframework.jdbc.core.ResultSetExtractor<Object>) rs ->
                rs.next() ? rs.getObject(label) : null, params.toArray());
    }

    public GroupedResult aggregate(String sql, List<Object> params) {
        List<Map<String, Object>> rows = new ArrayList<>();
        jdbc.query(sql, (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                }
                rows.add(row);
            }
            return null;
        }, params.toArray());
        return new GroupedResult(rows);
    }

    /** List-row shape matches the single-record shape: system fields + flattened data. */
    private static Map<String, Object> rowShape(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getObject("id", UUID.class));
        row.put("version", rs.getInt("version"));
        row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
        row.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());
        row.put("createdBy", rs.getObject("created_by", UUID.class));
        row.put("updatedBy", rs.getObject("updated_by", UUID.class));
        row.put("deleted", rs.getBoolean("deleted"));
        row.putAll(dataFields(rs.getString("data")));
        return row;
    }

    private static StoredRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> fields = dataFields(rs.getString("data"));
        return new StoredRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("entity_id"),
                rs.getInt("version"),
                rs.getTimestamp("created_at").toInstant().toString(),
                rs.getTimestamp("updated_at").toInstant().toString(),
                rs.getObject("created_by", UUID.class),
                rs.getObject("updated_by", UUID.class),
                rs.getBoolean("deleted"),
                fields);
    }

    /**
     * The stored data jsonb → the record's field map. Scalars decode as before
     * (numbers exact as BigDecimal, booleans, strings); a nested object or array —
     * a json-typed field's opaque value — round-trips as a map/list instead of
     * reaching {@code asString()}, which throws for non-scalar nodes on Jackson 3
     * (and silently erased them to "" on Jackson 2): one such field poisoned every
     * read of the record — point finds, list pages, the update merge, child walks.
     * An explicit JSON null decodes as null, never "".
     */
    static Map<String, Object> dataFields(String dataJson) {
        JsonNode data = MAPPER.readTree(dataJson);
        Map<String, Object> fields = new LinkedHashMap<>();
        data.properties().forEach(p -> fields.put(p.getKey(), fieldValue(p.getValue())));
        return fields;
    }

    private static Object fieldValue(JsonNode node) {
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isString()) {
            return node.asString();
        }
        if (node.isNull() || node.isMissingNode()) {
            return null;
        }
        return MAPPER.convertValue(node, Object.class);
    }
}
