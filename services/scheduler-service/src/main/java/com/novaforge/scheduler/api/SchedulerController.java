package com.novaforge.scheduler.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one Scheduler route (PHASE-4 §2/§7): read-only builder visibility into the
 * registry — job list with next-fire and last-run status. Administration is
 * publish-driven; no write or admin route exists, ever.
 */
@RestController
@RequestMapping("/api/v1/scheduler")
public class SchedulerController {

    private final JdbcTemplate jdbc;

    public SchedulerController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/jobs")
    public List<Map<String, Object>> jobs() {
        var ctx = com.novaforge.common.context.TenantContext.current().orElseThrow();
        return jdbc.queryForList("""
                SELECT app, name, cron, target, enabled, next_fire_at, last_run_at, last_status
                  FROM sched_jobs WHERE tenant_id = ? ORDER BY app, name""",
                UUID.fromString(ctx.tenantId()));
    }
}
