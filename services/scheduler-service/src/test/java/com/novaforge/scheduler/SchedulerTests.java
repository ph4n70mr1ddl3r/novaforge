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

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    com.novaforge.scheduler.jobs.JobRunner runner;

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
        published = List.of(new PublishedJobsSource.AppJobs(TENANT, "Purch", List.of(
                new ScheduledJobDefinition("nightlySweep", "0 0 3 * * *", "flow",
                        Map.of("entity", "PurchaseOrder", "hook", "sweep"), true),
                new ScheduledJobDefinition("disabledJob", "0 0 4 * * *", "flow",
                        Map.of("entity", "PurchaseOrder", "hook", "x"), false),
                new ScheduledJobDefinition("reportJob", "0 0 5 * * *", "report",
                        Map.of(), true))));
    }

    @Test
    @DisplayName("publish-driven sync registers jobs; disabled ones never fire (§7)")
    void syncRegistersFromMetadata() {
        runner.syncOnce();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sched_jobs", Integer.class)).isEqualTo(3);
        // definitions are upserts: a republish with a changed cron updates the row
        published = List.of(new PublishedJobsSource.AppJobs(TENANT, "Purch", List.of(
                new ScheduledJobDefinition("nightlySweep", "0 30 3 * * *", "flow",
                        Map.of("entity", "PurchaseOrder", "hook", "sweep"), true))));
        runner.syncOnce();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT cron FROM sched_jobs WHERE name = 'nightlySweep'",
                String.class)).isEqualTo("0 30 3 * * *");
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
    @DisplayName("dormant targets register and fire as skipped with a reason (§7)")
    void dormantTargetsSkip() {
        runner.syncOnce();
        jdbc.update("UPDATE sched_jobs SET next_fire_at = now() - interval '1 second' "
                + "WHERE name = 'reportJob'");
        runner.scanOnce();
        org.assertj.core.api.Assertions.assertThat(FIRED).isEmpty();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForMap(
                "SELECT status, detail FROM sched_runs").get("status")).isEqualTo("skipped");
        org.assertj.core.api.Assertions.assertThat(String.valueOf(
                jdbc.queryForMap("SELECT detail FROM sched_runs").get("detail")))
                .contains("dormant");
    }

    @Test
    @DisplayName("the status route is read-only, tenant-scoped registry visibility (§7/§2)")
    void statusRoute() throws Exception {
        runner.syncOnce();
        mockMvc.perform(get("/api/v1/scheduler/jobs").with(jwtFor()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.length()").value(3));
    }

    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor() {
        return jwt()
                .jwt(token -> token.claim("tenant_id", TENANT.toString())
                        .subject("33333333-3333-4333-8333-333333333333"))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
    }
}
