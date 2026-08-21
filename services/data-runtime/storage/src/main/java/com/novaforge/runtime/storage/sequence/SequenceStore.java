package com.novaforge.runtime.storage.sequence;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Gapless sequence state (PHASE-1 §5): the draw is an UPDATE inside the creating
 * record's transaction — the row lock serializes writers on that sequence (acceptable
 * for document numbering, required by PLAN.md §1), and a rolled-back transaction
 * reverts the increment, so no number is lost and no gap opens (§9 item 2).
 * {@code cached} sequences never touch this store (Redis block allocation).
 */
@Repository
public class SequenceStore {

    private final JdbcTemplate jdbc;

    public SequenceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Draws the next number inside the caller's transaction. */
    public long drawGapless(UUID tenantId, String appId, String sequenceName, long start) {
        jdbc.update("""
                INSERT INTO seq_state (tenant_id, app_id, sequence_name, next_value)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (tenant_id, app_id, sequence_name) DO NOTHING""",
                tenantId, appId, sequenceName, start);
        List<Long> next = jdbc.query("""
                UPDATE seq_state SET next_value = next_value + 1
                 WHERE tenant_id = ? AND app_id = ? AND sequence_name = ?
                 RETURNING next_value - 1""",
                (rs, i) -> rs.getLong(1), tenantId, appId, sequenceName);
        return next.getFirst();
    }

    /** Current counter value (test observation). */
    public long currentValue(UUID tenantId, String appId, String sequenceName) {
        Long value = jdbc.queryForObject("""
                SELECT next_value - 1 FROM seq_state
                 WHERE tenant_id = ? AND app_id = ? AND sequence_name = ?""",
                Long.class, tenantId, appId, sequenceName);
        return value == null ? 0 : value;
    }
}
