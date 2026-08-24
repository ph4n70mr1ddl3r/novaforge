package com.novaforge.integration.store;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * The job ledger (PHASE-6 §7): import runs and entity/report export jobs are
 * tenant data (the ImportDefinition itself is promoted metadata). Checkpoints
 * record the resume point; the per-row outcomes table is the resume leg's
 * exactly-once ledger — a row with an `ok` outcome is never re-applied.
 */
@Repository
public class JobStore {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;

    public JobStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public enum Kind { IMPORT("import"), EXPORT_ENTITY("export_entity"), EXPORT_REPORT("export_report");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        public static Kind of(String wire) {
            for (Kind kind : values()) {
                if (kind.wire.equals(wire)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("unknown job kind: " + wire);
        }
    }

    public record Job(UUID id, UUID tenantId, String kind, String status, String app,
                      String entity, String importMapping, String reportId, String runAsRole,
                      Map<String, Object> params, UUID fileId, String fileName, String format,
                      UUID initiatedBy, Long totalRows, long processedRows, long failedRows,
                      Map<String, Object> checkpoint, String error, Instant createdAt) {
    }

    public UUID create(UUID tenantId, Kind kind, String app, String entity, String importMapping,
                       String reportId, String runAsRole, Map<String, Object> params, UUID fileId,
                       String fileName, String format, UUID initiatedBy, Long totalRows) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO it_jobs (id, tenant_id, kind, status, app, entity, import_mapping,
                                     report_id, run_as_role, params, file_id, file_name, format,
                                     initiated_by, total_rows)
                VALUES (?, ?, ?, 'pending', ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)""",
                id, tenantId, kind.wire(), app, entity, importMapping, reportId, runAsRole,
                MAPPER.writeValueAsString(params == null ? Map.of() : params),
                fileId, fileName, format, initiatedBy, totalRows);
        return id;
    }

    public Optional<Job> find(UUID tenantId, UUID id) {
        return jdbc.query("SELECT * FROM it_jobs WHERE tenant_id = ? AND id = ?",
                (rs, i) -> row(rs), tenantId, id).stream().findFirst();
    }

    public List<Job> list(UUID tenantId, int limit) {
        return jdbc.query("SELECT * FROM it_jobs WHERE tenant_id = ? ORDER BY created_at DESC LIMIT ?",
                (rs, i) -> row(rs), tenantId, limit);
    }

    /** The runner's scan: every pending job across tenants (resumed ones included). */
    public List<Job> pending(int limit) {
        return jdbc.query(
                "SELECT * FROM it_jobs WHERE status = 'pending' ORDER BY created_at LIMIT ?",
                (rs, i) -> row(rs), limit);
    }

    /** The runner's transition: pending→running→completed/failed, paused for kills. */
    public void updateStatus(UUID tenantId, UUID id, String status, String error) {
        int updated = jdbc.update("""
                UPDATE it_jobs SET status = ?, error = ?, updated_at = now()
                 WHERE tenant_id = ? AND id = ?""", status, error, tenantId, id);
        if (updated == 0) {
            throw new PlatformException(PlatformErrorCode.NOT_FOUND, "job " + id);
        }
    }

    /** Chunk commit: counters advance, the checkpoint moves (the resume point, §7). */
    public void checkpoint(UUID tenantId, UUID id, long processedRows, long failedRows,
                           Map<String, Object> checkpoint) {
        jdbc.update("""
                UPDATE it_jobs SET processed_rows = ?, failed_rows = ?, checkpoint = ?::jsonb,
                                   updated_at = now()
                 WHERE tenant_id = ? AND id = ?""",
                processedRows, failedRows, MAPPER.writeValueAsString(checkpoint), tenantId, id);
    }

    public void totalRows(UUID tenantId, UUID id, long totalRows) {
        jdbc.update("UPDATE it_jobs SET total_rows = ?, updated_at = now() WHERE tenant_id = ? AND id = ?",
                totalRows, tenantId, id);
    }

    // --- per-row outcomes (the resume ledger) ---

    public record RowOutcome(int rowIndex, String status, String recordId, String code,
                             String detail) {
    }

    public void recordRow(UUID jobId, RowOutcome outcome) {
        jdbc.update("""
                INSERT INTO it_job_rows (job_id, row_index, status, record_id, code, detail)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (job_id, row_index) DO UPDATE
                   SET status = EXCLUDED.status, record_id = EXCLUDED.record_id,
                       code = EXCLUDED.code, detail = EXCLUDED.detail""",
                jobId, outcome.rowIndex(), outcome.status(),
                outcome.recordId() == null ? null : UUID.fromString(outcome.recordId()),
                outcome.code(), outcome.detail());
    }

    /** The rows already settled `ok` below the checkpoint — the resume skip set. */
    public java.util.Set<Integer> okRows(UUID jobId) {
        return new java.util.HashSet<>(jdbc.query(
                "SELECT row_index FROM it_job_rows WHERE job_id = ? AND status = 'ok'",
                (rs, i) -> rs.getInt(1), jobId));
    }

    public List<RowOutcome> rows(UUID jobId) {
        return jdbc.query("""
                SELECT row_index, status, record_id, code, detail FROM it_job_rows
                 WHERE job_id = ? ORDER BY row_index""",
                (rs, i) -> new RowOutcome(rs.getInt("row_index"), rs.getString("status"),
                        rs.getString("record_id") == null ? null
                                : rs.getString("record_id"),
                        rs.getString("code"), rs.getString("detail")), jobId);
    }

    private static Job row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Job(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("kind"), rs.getString("status"), rs.getString("app"),
                rs.getString("entity"), rs.getString("import_mapping"), rs.getString("report_id"),
                rs.getString("run_as_role"), MAPPER.readValue(rs.getString("params"), Map.class),
                rs.getObject("file_id", UUID.class), rs.getString("file_name"),
                rs.getString("format"), rs.getObject("initiated_by", UUID.class),
                rs.getObject("total_rows") == null ? null : rs.getLong("total_rows"),
                rs.getLong("processed_rows"), rs.getLong("failed_rows"),
                MAPPER.readValue(rs.getString("checkpoint"), Map.class), rs.getString("error"),
                rs.getTimestamp("created_at").toInstant());
    }
}
