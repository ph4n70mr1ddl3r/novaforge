package com.novaforge.scheduler;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novaforge.metadata.ScheduledJobDefinition;
import com.novaforge.scheduler.jobs.JobRunner.FlowTarget;
import com.novaforge.scheduler.jobs.PublishedJobsSource;
import com.novaforge.testsupport.PostgresTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The cron registry (PHASE-4 §7, §14 item 4): publish-driven definitions sync into
 * runtime state, due jobs fire exactly once under the lease (the atomic conditional
 * update is the distributed lock), missed windows skip (misfire policy), every fire
 * records a run and emits {@code scheduler.job.run}, and the read-only status route
 * serves the tenant's registry.
 */
@SpringBootTest(properties = {"novaforge.scheduler.scan-interval-ms=3600000",
        "novaforge.scheduler.sync-interval-ms=3600000",
        "novaforge.events.relay-interval-ms=3600000"})
@AutoConfigureMockMvc
class SchedulerTests extends PostgresTestBase {

    static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");

    /** Flow firings the stub observed (the runtime's stand-in). */
    static final List<String> FIRED = new CopyOnWriteArrayList<>();

    /** Process firings the stub observed (the workflow service's stand-in). */
    static final List<String> PROCESSES = new CopyOnWriteArrayList<>();

    /** Report deliveries the stub observed (the reporting service's stand-in). */
    static final List<String> REPORTS = new CopyOnWriteArrayList<>();

    /** When set, the process stub fails — the run records the failure (§7). */
    static volatile boolean failProcesses = false;

    /** When set, the report stub fails — the delivery run records the failure (§7). */
    static volatile boolean failReports = false;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    com.novaforge.scheduler.jobs.JobRunner runner;

    @Autowired
    com.novaforge.scheduler.events.SchedulerOutboxRelay outboxRelay;

    /** The app whose published jobs the stub serves. */
    static volatile List<PublishedJobsSource.AppJobs> published = List.of();

    @TestConfiguration
    static class Stubs {

        @Bean
        @Primary
        PublishedJobsSource jobsSource() {
            return () -> published;
        }

        @Bean
        @Primary
        FlowTarget flowTarget() {
            return (tenantId, app, entity, hook) -> FIRED.add(app + ":" + hook);
        }

        @Bean
        @Primary
        com.novaforge.scheduler.jobs.RestProcessTarget processTarget() {
            return new com.novaforge.scheduler.jobs.RestProcessTarget(
                    "http://localhost:1",
                    new com.novaforge.security.ServiceTokenClient("http://localhost:1",
                            "id", "secret")) {
                @Override
                public String run(UUID tenantId, String appApiName, String process,
                                  String recordId, Map<String, Object> variables) {
                    if (failProcesses) {
                        throw new com.novaforge.common.error.PlatformException(
                                com.novaforge.common.error.PlatformErrorCode.INTERNAL,
                                "workflow unreachable");
                    }
                    PROCESSES.add(appApiName + ":" + process
                            + (recordId == null ? "" : ":" + recordId));
                    return "instance-1";
                }
            };
        }

        @Bean
        @Primary
        com.novaforge.scheduler.jobs.RestReportTarget reportTarget() {
            return new com.novaforge.scheduler.jobs.RestReportTarget(
                    "http://localhost:1",
                    new com.novaforge.security.ServiceTokenClient("http://localhost:1",
                            "id", "secret")) {
                @Override
                public Map<String, Object> run(UUID tenantId, String appApiName,
                                               Map<String, Object> params, String deliveryId) {
                    if (failReports) {
                        throw new com.novaforge.common.error.PlatformException(
                                com.novaforge.common.error.PlatformErrorCode.INTERNAL,
                                "reporting unreachable");
                    }
                    // the fired-window delivery key rides every scheduled fire — the
                    // notification leg dedupes retried windows on it
                    org.assertj.core.api.Assertions.assertThat(deliveryId)
                            .startsWith("job-").contains("@");
                    REPORTS.add(appApiName + ":" + params.get("reportId"));
                    return Map.of("status", "delivered", "rows", 7);
                }
            };
        }
    }

    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestBase::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestBase::jdbcUsername);
        registry.add("spring.datasource.password", PostgresTestBase::jdbcPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM sched_event_outbox");
        jdbc.update("DELETE FROM sched_runs");
        jdbc.update("DELETE FROM sched_leases");
        jdbc.update("DELETE FROM sched_jobs");
        FIRED.clear();
        PROCESSES.clear();
        REPORTS.clear();
        failProcesses = false;
        failReports = false;
        published = List.of(new PublishedJobsSource.AppJobs(TENANT, "Purch", List.of(
                new ScheduledJobDefinition("nightlySweep", "0 0 3 * * *", "flow",
                        Map.of("entity", "PurchaseOrder", "hook", "sweep"), true),
                new ScheduledJobDefinition("disabledJob", "0 0 4 * * *", "flow",
                        Map.of("entity", "PurchaseOrder", "hook", "x"), false),
                new ScheduledJobDefinition("processJob", "0 0 5 * * *", "processStart",
                        Map.of("process", "po_review"), true),
                new ScheduledJobDefinition("reportJob", "0 0 6 * * *", "report",
                        Map.of("reportId", "arAging"), true),
                new ScheduledJobDefinition("scriptJob", "0 0 7 * * *", "script",
                        Map.of("entity", "PurchaseOrder", "hook", "reprice"), true))));
    }

    @Test
    @DisplayName("publish-driven sync registers jobs; disabled ones never fire (§7)")
    void syncRegistersFromMetadata() {
        runner.syncOnce();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sched_jobs", Integer.class)).isEqualTo(5);
        // definitions are upserts: a republish with a changed cron updates the row
        published = List.of(new PublishedJobsSource.AppJobs(TENANT, "Purch", List.of(
                new ScheduledJobDefinition("nightlySweep", "0 30 3 * * *", "flow",
                        Map.of("entity", "PurchaseOrder", "hook", "sweep"), true))));
        runner.syncOnce();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT cron FROM sched_jobs WHERE name = 'nightlySweep'",
                String.class)).isEqualTo("0 30 3 * * *");
        // vanished definitions leave the registry — an orphan can never keep firing
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForList(
                "SELECT name FROM sched_jobs ORDER BY name", String.class))
                .containsExactly("nightlySweep");
    }

    @Test
    @DisplayName("an empty source listing never wipes the registry (outage-safe prune, §7)")
    void emptySourceKeepsRegistry() {
        runner.syncOnce();
        published = List.of();   // a metadata outage shape — not an unpublish
        runner.syncOnce();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sched_jobs", Integer.class)).isEqualTo(5);
    }

    @Test
    @DisplayName("due jobs fire exactly once under the lease; runs and events land (§7)")
    void dueJobsFireOnceWithEvents() {
        runner.syncOnce();
        // force the sweep due now
        jdbc.update("UPDATE sched_jobs SET next_fire_at = now() - interval '1 second' "
                + "WHERE name = 'nightlySweep'");
        runner.scanOnce();
        runner.scanOnce();   // a second pass must not double-fire

        org.assertj.core.api.Assertions.assertThat(FIRED)
                .containsExactly("Purch:sweep");
        Map<String, Object> job = jdbc.queryForMap(
                "SELECT last_status, next_fire_at FROM sched_jobs WHERE name = 'nightlySweep'");
        org.assertj.core.api.Assertions.assertThat(job.get("last_status")).isEqualTo("ok");
        org.assertj.core.api.Assertions.assertThat(
                ((java.sql.Timestamp) job.get("next_fire_at")).toInstant().isAfter(
                        java.time.Instant.now()));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForList(
                "SELECT event_type FROM sched_event_outbox", String.class))
                .containsExactly("scheduler.job.run");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForMap(
                "SELECT payload->>'status' AS s, payload->>'job' AS j FROM sched_event_outbox")
                .get("s")).isEqualTo("ok");
    }

    @Test
    @DisplayName("a lease from one window never suppresses the next — consecutive windows both fire (§7)")
    void consecutiveWindowsBothFire() {
        runner.syncOnce();
        // window one fires and takes the lease — with no DELETE FROM sched_leases,
        // the pre-window-keyed lease (locked_until = next_fire + lease) blocked the
        // next due window forever: every job with a cron period longer than the
        // lease fired at half its intended rate
        jdbc.update("UPDATE sched_jobs SET next_fire_at = now() - interval '1 second' "
                + "WHERE name = 'nightlySweep'");
        runner.scanOnce();
        jdbc.update("UPDATE sched_jobs SET next_fire_at = now() - interval '1 second' "
                + "WHERE name = 'nightlySweep'");
        runner.scanOnce();

        org.assertj.core.api.Assertions.assertThat(FIRED)
                .containsExactly("Purch:sweep", "Purch:sweep");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sched_runs", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("one unparseable cron skips its own job, never the rest of the sync pass (§7)")
    void oneBadCronDoesNotAbortSync() {
        // save validation's cron check is shape-only (field count and item syntax) —
        // the parser stays the range authority, so an out-of-range hour reaches the
        // registry sync and must not stop every app listed after it from syncing
        published = List.of(
                new PublishedJobsSource.AppJobs(TENANT, "Bad", List.of(
                        new ScheduledJobDefinition("badCron", "0 0 25 * * *", "flow",
                                Map.of("entity", "PurchaseOrder", "hook", "sweep"), true))),
                new PublishedJobsSource.AppJobs(TENANT, "Good", List.of(
                        new ScheduledJobDefinition("goodCron", "0 0 3 * * *", "flow",
                                Map.of("entity", "PurchaseOrder", "hook", "sweep"), true))));
        runner.syncOnce();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForList(
                "SELECT app || ':' || name FROM sched_jobs ORDER BY 1", String.class))
                .containsExactly("Good:goodCron");
    }

    @Test
    @DisplayName("outbox retention: published rows older than the window leave; fresh and unpublished stay")
    void outboxRetentionDropsOldPublishedRows() {
        for (int i = 0; i < 2; i++) {
            jdbc.update("""
                    INSERT INTO sched_event_outbox (id, tenant_id, event_type, payload)
                    VALUES (?, ?, 'scheduler.job.run', '{}'::jsonb)""",
                    UUID.randomUUID(), TENANT);
        }
        jdbc.update("UPDATE sched_event_outbox SET published_at = now() - interval '30 days'");
        jdbc.update("""
                INSERT INTO sched_event_outbox (id, tenant_id, event_type, payload, published_at)
                VALUES (?, ?, 'scheduler.job.run', '{}'::jsonb, now())""",
                UUID.randomUUID(), TENANT);
        jdbc.update("""
                INSERT INTO sched_event_outbox (id, tenant_id, event_type, payload)
                VALUES (?, ?, 'scheduler.job.run', '{}'::jsonb)""",
                UUID.randomUUID(), TENANT);

        outboxRelay.retain();

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sched_event_outbox", Integer.class)).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sched_event_outbox WHERE published_at IS NULL",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("misfire skips: a missed window advances to the next tick, one fire (§7)")
    void misfireSkipsMissedWindows() {
        runner.syncOnce();
        // simulate a long-missed window (next_fire far in the past)
        jdbc.update("UPDATE sched_jobs SET next_fire_at = now() - interval '2 hours' "
                + "WHERE name = 'nightlySweep'");
        runner.scanOnce();
        org.assertj.core.api.Assertions.assertThat(FIRED).hasSize(1);   // fired once
        java.time.Instant next = ((java.sql.Timestamp) jdbc.queryForObject(
                "SELECT next_fire_at FROM sched_jobs WHERE name = 'nightlySweep'",
                java.sql.Timestamp.class)).toInstant();
        // and the advanced fire is the NEXT cron tick after now — not after the
        // missed window (fire once, skip missed)
        org.assertj.core.api.Assertions.assertThat(next).isAfter(java.time.Instant.now());
        org.assertj.core.api.Assertions.assertThat(next).isBefore(
                java.time.Instant.now().plusSeconds(86400));
    }

    @Test
    @DisplayName("the script target fires the same scheduled-hook surface as flow (§7)")
    void scriptTargetFires() {
        runner.syncOnce();
        jdbc.update("UPDATE sched_jobs SET next_fire_at = now() - interval '1 second' "
                + "WHERE name = 'scriptJob'");
        runner.scanOnce();
        org.assertj.core.api.Assertions.assertThat(FIRED)
                .containsExactly("Purch:reprice");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForMap(
                "SELECT last_status FROM sched_jobs WHERE name = 'scriptJob'")
                .get("last_status")).isEqualTo("ok");
    }

    @Test
    @DisplayName("report fires the reporting service's delivery surface; failures record failed runs (§7/PHASE-5)")
    void reportTargetFires() {
        runner.syncOnce();
        jdbc.update("UPDATE sched_jobs SET next_fire_at = now() - interval '1 second' "
                + "WHERE name = 'reportJob'");
        runner.scanOnce();

        org.assertj.core.api.Assertions.assertThat(REPORTS)
                .containsExactly("Purch:arAging");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForMap(
                "SELECT last_status FROM sched_jobs WHERE name = 'reportJob'")
                .get("last_status")).isEqualTo("ok");
        org.assertj.core.api.Assertions.assertThat(String.valueOf(jdbc.queryForMap(
                "SELECT detail FROM sched_runs ORDER BY fired_at DESC LIMIT 1")
                .get("detail"))).contains("delivered");

        // a delivery the reporting service rejects records a failed run — audibly
        failReports = true;
        jdbc.update("UPDATE sched_jobs SET next_fire_at = now() - interval '1 second' "
                + "WHERE name = 'reportJob'");
        // release the lease the first fire holds (the distributed lock's window)
        jdbc.update("DELETE FROM sched_leases WHERE job_id = "
                + "(SELECT id FROM sched_jobs WHERE name = 'reportJob')");
        runner.scanOnce();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForMap(
                "SELECT last_status FROM sched_jobs WHERE name = 'reportJob'")
                .get("last_status")).isEqualTo("failed");
    }

    @Test
    @DisplayName("processStart fires the workflow service's internal surface; failures record failed runs (§7/§9)")
    void processStartTargetFires() {
        runner.syncOnce();
        jdbc.update("UPDATE sched_jobs SET next_fire_at = now() - interval '1 second' "
                + "WHERE name = 'processJob'");
        runner.scanOnce();

        org.assertj.core.api.Assertions.assertThat(PROCESSES)
                .containsExactly("Purch:po_review");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForMap(
                "SELECT last_status FROM sched_jobs WHERE name = 'processJob'")
                .get("last_status")).isEqualTo("ok");

        // a fire the workflow service rejects records a failed run — audibly, not skipped
        failProcesses = true;
        jdbc.update("UPDATE sched_jobs SET next_fire_at = now() - interval '1 second' "
                + "WHERE name = 'processJob'");
        // release the lease the first fire holds (the distributed lock's window)
        jdbc.update("DELETE FROM sched_leases WHERE job_id = "
                + "(SELECT id FROM sched_jobs WHERE name = 'processJob')");
        runner.scanOnce();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForList(
                "SELECT status FROM sched_runs ORDER BY fired_at", String.class))
                .containsExactly("ok", "failed");
    }

    @Test
    @DisplayName("the status route serves builders/admins only — a user token is denied (§2/§13)")
    void statusRouteIsBuilderGated() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/jobs")
                        .with(jwt().jwt(token -> token.claim("tenant_id", TENANT.toString())
                                .subject("33333333-3333-4333-8333-333333333333"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"),
                                        new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the status route is read-only, tenant-scoped registry visibility (§7/§2)")
    void statusRoute() throws Exception {
        runner.syncOnce();
        mockMvc.perform(get("/api/v1/scheduler/jobs").with(jwtFor()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.length()").value(5));
    }

    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor() {
        return jwt()
                .jwt(token -> token.claim("tenant_id", TENANT.toString())
                        .subject("33333333-3333-4333-8333-333333333333"))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"),
                        new SimpleGrantedAuthority("ROLE_builder"));
    }
}
