package com.novaforge.audit.store;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The monthly partition rotation the schema promised (V1: "month partitions rotate
 * forward") but nothing implemented — every row landed in {@code audit_events_default}
 * forever, so the trail had no rotation and no retention story. Runs as the database
 * owner (the {@code spring.flyway.user} credentials — V2's design: the owner creates
 * and rotates partitions; the runtime role is INSERT/SELECT only and cannot DDL).
 *
 * <p>A month whose range already holds rows in the DEFAULT partition (any stack that
 * ran before this rotation existed — always true for the live current month) cannot
 * simply be {@code CREATEd PARTITION OF}: Postgres rejects the create because the
 * default partition's contents would violate the new bounds. Those months take the
 * standard move-and-attach path, in one owner transaction: create the standalone
 * table with the parent's key and indexes, move the in-range rows across, drop them
 * from the default, attach. New partitions inherit the owner's default privileges,
 * which V2 already scopes to the runtime role's exact grants. A rotation failure
 * logs and retries on the next pass — inserts stay total through the DEFAULT
 * partition either way.</p>
 */
@Component
public class AuditPartitionRotation {

    private static final Logger LOG = LoggerFactory.getLogger(AuditPartitionRotation.class);

    private final JdbcTemplate owner;
    private final TransactionTemplate ownerTx;

    public AuditPartitionRotation(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.flyway.user}") String user,
            @Value("${spring.flyway.password}") String password) {
        DataSource source = new DriverManagerDataSource(url, user, password);
        this.owner = new JdbcTemplate(source);
        this.ownerTx = new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
    }

    /** Twice daily: idempotent, cheap, and resilient across a long owner outage. */
    @Scheduled(cron = "0 17 5,17 * * *")
    public void scheduled() {
        rotate();
    }

    /** Creates this month's and next month's partitions, idempotently. */
    public void rotate() {
        for (LocalDate month : List.of(LocalDate.now().withDayOfMonth(1),
                LocalDate.now().plusMonths(1).withDayOfMonth(1))) {
            partition(month);
        }
    }

    private void partition(LocalDate monthStart) {
        YearMonth month = YearMonth.from(monthStart);
        String name = "audit_events_y%dm%02d".formatted(month.getYear(), month.getMonthValue());
        String from = month.atDay(1).toString();
        String to = month.plusMonths(1).atDay(1).toString();
        try {
            owner.update("""
                    CREATE TABLE IF NOT EXISTS %s PARTITION OF audit_events
                     FOR VALUES FROM ('%s') TO ('%s')""".formatted(name, from, to));
            LOG.debug("audit partition {} ready [{}, {})", name, from, to);
        } catch (DataAccessException defaultHoldsRows) {
            try {
                moveOutOfDefault(name, from, to);
                LOG.info("audit partition {} attached [{}, {}) — in-range rows moved out "
                        + "of the default partition", name, from, to);
            } catch (Exception e) {
                LOG.error("audit partition rotation failed for {} (will retry; inserts "
                        + "stay total through the DEFAULT partition)", name, e);
            }
        }
    }

    /**
     * The pre-rotation migration path: a standalone twin of the parent (key and
     * indexes included — ATTACH requires them), the in-range rows moved across, the
     * default emptied of them, and the table attached — one owner transaction, so a
     * crash mid-move leaves the default untouched and the next pass retries clean.
     */
    private void moveOutOfDefault(String name, String from, String to) {
        ownerTx.executeWithoutResult(tx -> {
            owner.execute("CREATE TABLE " + name + " (LIKE audit_events INCLUDING DEFAULTS)");
            owner.update("ALTER TABLE " + name + " ADD PRIMARY KEY (event_id, occurred_at)");
            owner.update("CREATE INDEX " + name + "_record ON " + name
                    + " (tenant_id, record_id, occurred_at DESC)");
            owner.update("CREATE INDEX " + name + "_entity ON " + name
                    + " (tenant_id, entity_id, occurred_at DESC)");
            owner.update("INSERT INTO " + name + " SELECT * FROM audit_events_default"
                    + " WHERE occurred_at >= '" + from + "' AND occurred_at < '" + to + "'");
            owner.update("DELETE FROM audit_events_default"
                    + " WHERE occurred_at >= '" + from + "' AND occurred_at < '" + to + "'");
            owner.execute("ALTER TABLE audit_events ATTACH PARTITION " + name
                    + " FOR VALUES FROM ('" + from + "') TO ('" + to + "')");
        });
    }
}
