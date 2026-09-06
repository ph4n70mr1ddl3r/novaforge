package com.novaforge.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.store.MetadataStore;
import com.novaforge.testsupport.PostgresTestBase;
import java.sql.Connection;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The page optimistic-lock fence under its nastiest interleaving (PHASE-2 §8): two
 * FIRST saves of the same page racing. The loser's pre-check ran before the winner's
 * row existed (READ COMMITTED never sees an uncommitted insert), so its upsert rides
 * the ON CONFLICT arm — and that arm must never ADOPT the raced row: a first save
 * holds no revision token, so it has nothing to trade against the row's revision and
 * must lose with the same concurrent-save 409 any stale revision gets.
 *
 * <p>The regression this pins: the ON CONFLICT arm bound the first-save DEFAULT (1)
 * into {@code WHERE md_pages.revision = ?} — exactly the winner's fresh revision — so
 * the raced first save clobbered the winner's row, the V8 trigger bumped the physical
 * revision to 2, and the stored document kept the loser's embedded revision 1. Reads
 * serve the document, so every later save rebased against a revision the physical
 * column had already left behind: the page 409-looped forever, unsavable, and the
 * winner's draft was silently lost — the exact lost update the fence exists to
 * prevent, wearing a 200.</p>
 */
@SpringBootTest
class PageRevisionRaceTests extends PostgresTestBase {

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

    @Autowired
    MetadataStore store;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Test
    @DisplayName("two racing first saves: the loser 409s, the winner's row survives revision-consistent and savable")
    void racedFirstSaveLosesCleanlyAndNeverDivergesTheRevision() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID appId = UUID.fromString(store.insertApp(tenantId, actorId,
                new AppDefinition(null, "RaceApp", "RaceApp", null, null, null, null, null))
                .id());

        // The winner's first save, parked mid-flight: its row is inserted (document
        // embeds revision 1; the physical column defaults to 1) but uncommitted.
        try (Connection winner = dataSource.getConnection()) {
            winner.setAutoCommit(false);
            try (var insert = winner.prepareStatement("""
                    INSERT INTO md_pages (id, tenant_id, app_id, api_name, document, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)""")) {
                insert.setObject(1, UUID.randomUUID());
                insert.setObject(2, tenantId);
                insert.setObject(3, appId);
                insert.setString(4, "dashboard");
                insert.setString(5,
                        "{\"apiName\":\"dashboard\",\"label\":\"Winner\",\"type\":\"dashboard\",\"revision\":1}");
                insert.setObject(6, actorId);
                insert.setObject(7, actorId);
                insert.execute();
            }

            // The loser's first save: its pre-check sees no row (the winner's insert
            // is invisible), then its own insert blocks on the winner's row.
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<Integer> loser = pool.submit(() -> {
                    try {
                        store.putPage(tenantId, actorId, appId,
                                new AppDefinition.PageDefinition(null, "dashboard", "Challenger",
                                        null, "dashboard", null, null));
                        return 200;
                    } catch (PlatformException e) {
                        return e.errorCode() == PlatformErrorCode.CONFLICT_VERSION ? 409 : 500;
                    }
                });
                // Deterministic rendezvous: the loser is provably parked ON the
                // winner's row before it settles.
                await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                        assertThat(jdbc.queryForObject("""
                                SELECT count(*) FROM pg_stat_activity
                                 WHERE datname = current_database()
                                   AND wait_event_type = 'Lock'
                                   AND cardinality(pg_blocking_pids(pid)) > 0""",
                                Integer.class)).isEqualTo(1));

                winner.commit();

                // The raced first save holds no revision token — it must lose with
                // the fence's own 409, never succeed into a clobber.
                assertThat(loser.get()).isEqualTo(409);
            } finally {
                pool.shutdownNow();
            }
        }

        // The winner's draft survived — the loser's write is not in the row.
        String label = jdbc.queryForObject(
                "SELECT document->>'label' FROM md_pages WHERE tenant_id = ? AND app_id = ? "
                        + "AND api_name = 'dashboard'",
                String.class, tenantId, appId);
        assertThat(label).isEqualTo("Winner");

        // The invariant the divergence broke: the physical revision the fence checks
        // is the revision the document serves — every reader can rebase against it.
        Integer physical = jdbc.queryForObject(
                "SELECT revision FROM md_pages WHERE tenant_id = ? AND app_id = ? "
                        + "AND api_name = 'dashboard'",
                Integer.class, tenantId, appId);
        Integer embedded = jdbc.queryForObject(
                "SELECT (document->>'revision')::int FROM md_pages WHERE tenant_id = ? "
                        + "AND app_id = ? AND api_name = 'dashboard'",
                Integer.class, tenantId, appId);
        assertThat(physical).isEqualTo(1);
        assertThat(embedded).isEqualTo(physical);

        // The page is savable with the revision the document serves — no 409 loop.
        store.putPage(tenantId, actorId, appId,
                new AppDefinition.PageDefinition(null, "dashboard", "Rebased", null,
                        "dashboard", null, null, physical));
        Integer after = jdbc.queryForObject(
                "SELECT (document->>'revision')::int FROM md_pages WHERE tenant_id = ? "
                        + "AND app_id = ? AND api_name = 'dashboard'",
                Integer.class, tenantId, appId);
        assertThat(after).isEqualTo(physical + 1);
    }
}
