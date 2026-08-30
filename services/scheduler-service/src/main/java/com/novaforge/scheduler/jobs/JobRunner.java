package com.novaforge.scheduler.jobs;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives the registry (PHASE-4 §7): a sync pass refreshes job definitions from the
 * published surface (metadata is the source of truth; the registry is runtime
 * state), and a scan pass fires due jobs under a window-keyed lease — the atomic
 * conditional upsert is the distributed lock, so concurrent replicas single-fire a
 * window (§14 item 4) while one window's lease never suppresses the next. Misfire
 * policy: fire once, skip missed — {@code next_fire} always advances past
 * {@code now}, a missed window waits for the next cron tick. Every fire records a
 * run row and a {@code scheduler.job.run} event (success or failure).
 */
@Component
public class JobRunner {

    private static final Logger LOG = LoggerFactory.getLogger(JobRunner.class);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final PublishedJobsSource source;
    private final FlowTarget flows;
    private final org.springframework.beans.factory.ObjectProvider<RestProcessTarget> processes;
    private final org.springframework.beans.factory.ObjectProvider<RestReportTarget> reports;
    private final long leaseMs;

    public JobRunner(JdbcTemplate jdbc, PublishedJobsSource source, FlowTarget flows,
                     org.springframework.beans.factory.ObjectProvider<RestProcessTarget> processes,
                     org.springframework.beans.factory.ObjectProvider<RestReportTarget> reports,
                     @Value("${novaforge.scheduler.lease-ms:60000}") long leaseMs) {
        this.jdbc = jdbc;
        this.source = source;
        this.flows = flows;
        this.processes = processes;
        this.reports = reports;
        this.leaseMs = leaseMs;
    }

    /** Registry sync — publish-driven definitions, refreshed on an interval. */
    @Scheduled(fixedDelayString = "${novaforge.scheduler.sync-interval-ms:30000}")
    public void sync() {
        syncOnce();
    }

    public void syncOnce() {
        java.util.List<PublishedJobsSource.AppJobs> apps = source.all();
        for (PublishedJobsSource.AppJobs app : apps) {
            for (com.novaforge.metadata.ScheduledJobDefinition job : app.jobs()) {
                // One unregistrable job (a cron the parser rejects — save validation's
                // shape check is not the parser's range check) must never abort the
                // pass: every app listed after it would silently stop syncing.
                try {
                    jdbc.update("""
                            INSERT INTO sched_jobs (id, tenant_id, app, name, cron, target,
                                                    params, enabled, next_fire_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, now())
                            ON CONFLICT (tenant_id, app, name) DO UPDATE SET
                              cron = EXCLUDED.cron, target = EXCLUDED.target,
                              params = EXCLUDED.params, enabled = EXCLUDED.enabled,
                              updated_at = now()""",
                            UUID.nameUUIDFromBytes((app.tenantId() + ":" + app.appApiName()
                                    + ":" + job.name()).getBytes()),
                            app.tenantId(), app.appApiName(), job.name(), job.cron(),
                            job.target(), MAPPER.writeValueAsString(job.params()),
                            job.enabledOn(),
                            Timestamp.from(nextFire(job.cron(), Instant.now())));
                } catch (RuntimeException e) {
                    LOG.error("job {}.{} not registered — its cron '{}' is not fireable: {}",
                            app.appApiName(), job.name(), job.cron(), e.getMessage());
                }
            }
        }
        prune(apps);
    }

    /**
     * The registry mirrors published definitions — vanished jobs (unpublished,
     * renamed, or synced under a key the definitions no longer carry) leave the
     * registry, so an orphan can never keep firing and failing forever. Skipped when
     * the source serves nothing at all: an empty listing can be a metadata outage,
     * and wiping the registry on one is worse than a stale row (found live by the
     * PHASE-5 §7 demo — a null-app orphan survived every later sync).
     */
    private void prune(java.util.List<PublishedJobsSource.AppJobs> apps) {
        if (apps.isEmpty()) {
            return;
        }
        StringBuilder keys = new StringBuilder("DELETE FROM sched_jobs WHERE NOT (");
        java.util.List<Object> params = new java.util.ArrayList<>();
        for (PublishedJobsSource.AppJobs app : apps) {
            if (!app.jobs().isEmpty()) {
                for (com.novaforge.metadata.ScheduledJobDefinition job : app.jobs()) {
                    if (!params.isEmpty()) {
                        keys.append(" OR ");
                    }
                    keys.append("(tenant_id = ? AND app = ? AND name = ?)");
                    params.add(app.tenantId());
                    params.add(app.appApiName());
                    params.add(job.name());
                }
                continue;
            }
            // an app listed with no jobs: its whole registry partition goes
            keys.append(params.isEmpty() ? "" : " OR ").append("(tenant_id = ? AND app = ?)");
            params.add(app.tenantId());
            params.add(app.appApiName());
        }
        keys.append(")");
        jdbc.update(keys.toString(), params.toArray());
    }

    /** The scan pass — due, enabled jobs fire under lease. */
    @Scheduled(fixedDelayString = "${novaforge.scheduler.scan-interval-ms:1000}")
    public void scan() {
        scanOnce();
    }

    public void scanOnce() {
        Instant now = Instant.now();
        List<Map<String, Object>> due = jdbc.queryForList("""
                SELECT id, tenant_id, app, name, cron, target, params, next_fire_at
                  FROM sched_jobs
                 WHERE enabled AND next_fire_at IS NOT NULL AND next_fire_at <= ?
                 ORDER BY next_fire_at""", Timestamp.from(now));
        for (Map<String, Object> job : due) {
            Instant next = nextFire(String.valueOf(job.get("cron")), now);
            // advance first (misfire: missed windows skip — the next tick wins)
            jdbc.update("UPDATE sched_jobs SET next_fire_at = ?, updated_at = now() WHERE id = ?",
                    Timestamp.from(next), job.get("id"));
            Instant window = asInstant(job.get("next_fire_at"));
            if (!acquire(job.get("id"), window)) {
                continue;   // another replica already fired this window — single fire
            }
            fire(job, window);
        }
    }

    private static Instant asInstant(Object timestamp) {
        return ((java.sql.Timestamp) timestamp).toInstant();
    }

    /**
     * The lease: the atomic conditional upsert is the distributed lock, keyed by the
     * fired window. A lease taken for window N never suppresses window N+1 — the scan
     * race it guards spans seconds (two replicas reading the same due row), not the
     * cron period — while two replicas scanning the same window single-fire: the
     * loser's upsert no-ops on the window it already sees fired.
     */
    private boolean acquire(Object jobId, Instant window) {
        int taken = jdbc.update("""
                INSERT INTO sched_leases (job_id, fired_window, locked_until)
                VALUES (?, ?, ?)
                ON CONFLICT (job_id) DO UPDATE SET
                  fired_window = EXCLUDED.fired_window, locked_until = EXCLUDED.locked_until
                 WHERE sched_leases.fired_window IS DISTINCT FROM EXCLUDED.fired_window""",
                jobId, Timestamp.from(window),
                Timestamp.from(Instant.now().plusMillis(leaseMs)));
        return taken > 0;
    }

    private void fire(Map<String, Object> job, Instant window) {
        String target = String.valueOf(job.get("target"));
        String status;
        String detail = null;
        if ("flow".equals(target)) {
            try {
                Map<String, Object> params = MAPPER.readValue(
                        String.valueOf(job.get("params")), Map.class);
                flows.run((UUID) job.get("tenant_id"), String.valueOf(job.get("app")),
                        String.valueOf(params.get("entity")),
                        String.valueOf(params.get("hook")));
                status = "ok";
            } catch (Exception e) {
                status = "failed";
                detail = e.getMessage();
                LOG.error("scheduled flow failed for {}.{}: {}",
                        job.get("app"), job.get("name"), e.getMessage(), e);
            }
        } else if ("processStart".equals(target)) {
            // §9 activation: the Workflow Service's internal start surface — the
            // process fires under the engine's per-app system principal.
            try {
                Map<String, Object> params = MAPPER.readValue(
                        String.valueOf(job.get("params")), Map.class);
                Object recordId = params.get("recordId");
                Object variables = params.get("variables");
                processes.getObject().run((UUID) job.get("tenant_id"),
                        String.valueOf(job.get("app")),
                        String.valueOf(params.get("process")),
                        recordId == null ? null : String.valueOf(recordId),
                        variables instanceof Map<?, ?> map ? (Map<String, Object>) map : null);
                status = "ok";
            } catch (Exception e) {
                status = "failed";
                detail = e.getMessage();
                LOG.error("scheduled process start failed for {}.{}: {}",
                        job.get("app"), job.get("name"), e.getMessage(), e);
            }
        } else if ("script".equals(target)) {
            // §7's script target: the same scheduled-hook surface the flow target
            // rides — the runtime resolves the hook from the published bundle and a
            // script hook executes recordless as the per-app system principal through
            // the Script Engine's scheduled surface ($record absent, $data on the
            // internal system leg). "The same way" as flow, per the spec's pin.
            try {
                Map<String, Object> params = MAPPER.readValue(
                        String.valueOf(job.get("params")), Map.class);
                flows.run((UUID) job.get("tenant_id"), String.valueOf(job.get("app")),
                        String.valueOf(params.get("entity")),
                        String.valueOf(params.get("hook")));
                status = "ok";
            } catch (Exception e) {
                status = "failed";
                detail = e.getMessage();
                LOG.error("scheduled script failed for {}.{}: {}",
                        job.get("app"), job.get("name"), e.getMessage(), e);
            }
        } else if ("report".equals(target)) {
            // §7 activation (PHASE-5): the Reporting Service's internal delivery
            // surface — the run executes under the job's runAsRole, the export
            // delivers through the Notification Service.
            try {
                Map<String, Object> params = MAPPER.readValue(
                        String.valueOf(job.get("params")), Map.class);
                // the fired window is the delivery's idempotency key: a retried fire
                // of the same window collapses its notifications, the next window
                // delivers fresh
                Map<String, Object> summary = reports.getObject().run(
                        (UUID) job.get("tenant_id"), String.valueOf(job.get("app")), params,
                        "job-" + job.get("id") + "@" + window);
                status = "ok";
                detail = summary == null ? null
                        : String.valueOf(summary.getOrDefault("status", "delivered"));
            } catch (Exception e) {
                status = "failed";
                detail = e.getMessage();
                LOG.error("scheduled report delivery failed for {}.{}: {}",
                        job.get("app"), job.get("name"), e.getMessage(), e);
            }
        } else {
            // defensive: authoring (save validation) and the store (a CHECK on the
            // target column) both enforce the closed v1 set — flow | script |
            // processStart | report (§7) — so this branch guards only registry rows
            // that predate a target leaving the vocabulary; it answers audibly
            // instead of guessing
            status = "skipped";
            detail = "target '" + target + "' is registered but dormant";
        }
        jdbc.update("""
                UPDATE sched_jobs SET last_run_at = now(), last_status = ?, updated_at = now()
                 WHERE id = ?""", status, job.get("id"));
        jdbc.update("""
                INSERT INTO sched_runs (id, job_id, tenant_id, status, detail)
                VALUES (?, ?, ?, ?, ?)""",
                UUID.randomUUID(), job.get("id"), job.get("tenant_id"), status, detail);
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("event", "scheduler.job.run");
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("tenantId", String.valueOf(job.get("tenant_id")));
        payload.put("app", String.valueOf(job.get("app")));
        payload.put("job", String.valueOf(job.get("name")));
        payload.put("status", status);
        if (detail != null) {
            payload.put("detail", detail);
        }
        payload.put("occurredAt", Instant.now().toString());
        jdbc.update("""
                INSERT INTO sched_event_outbox (id, tenant_id, event_type, payload)
                VALUES (?, ?, 'scheduler.job.run', ?::jsonb)""",
                UUID.randomUUID(), job.get("tenant_id"), MAPPER.writeValueAsString(payload));
        LOG.info("scheduled job {}.{} fired: {}", job.get("app"), job.get("name"), status);
    }

    /**
     * The next fire at or after {@code from} — five-field cron normalizes to
     * Spring's six-field (seconds prepended).
     */
    static Instant nextFire(String cron, Instant from) {
        String normalized = cron.trim().chars().filter(c -> c == ' ').count() == 4
                ? "0 " + cron.trim() : cron.trim();
        CronExpression expression = CronExpression.parse(normalized);
        LocalDateTime next = expression.next(from.atZone(ZoneOffset.UTC)
                .toLocalDateTime().truncatedTo(ChronoUnit.SECONDS));
        return next == null ? from.plusSeconds(3600)
                : next.toInstant(ZoneOffset.UTC);
    }

    /** The flow and script targets: the runtime's scheduled-hook surface (§7). */
    public interface FlowTarget {

        void run(UUID tenantId, String appApiName, String entityApiName, String hookName);
    }
}
