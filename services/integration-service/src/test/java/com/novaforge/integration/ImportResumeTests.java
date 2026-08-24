package com.novaforge.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.novaforge.integration.clients.FileClient;
import com.novaforge.integration.clients.PublishedIntegrations;
import com.novaforge.integration.clients.RuntimeClient;
import com.novaforge.integration.jobs.JobRunner;
import com.novaforge.integration.store.JobStore;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.testsupport.PostgresTestBase;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * §11 item 4: import resumability — a run killed mid-way (the runtime chunk leg
 * failing) restarts from its checkpoint, the per-row ledger skips every settled
 * row, and each row applies exactly once (count + recorded ids). The chunked
 * transport rides the (faked) runtime write leg; the checkpoint/ledger mechanics
 * are the system under test.
 */
@SpringBootTest(properties = {"novaforge.jobs.chunk-size=4"})
class ImportResumeTests extends PostgresTestBase {

    static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID ACTOR = UUID.fromString("33333333-3333-4333-8333-333333333333");

    static final String APP_JSON = """
            { "apiName": "Erp",
              "entities": [
                { "apiName": "Payment", "displayField": "reference",
                  "fields": [
                    { "apiName": "reference", "type": "text", "required": true },
                    { "apiName": "amount", "type": "decimal", "precision": 18, "scale": 4 } ] } ],
              "integrations": {
                "imports": [
                  { "apiName": "paymentFeed", "entity": "Payment", "mode": "create",
                    "mapping": { "reference": "Ref", "amount": "Amount" } } ] } }
            """;

    /** The CSV source: ten rows, chunk size four → three chunks. */
    static final String CSV = """
            Ref,Amount
            pay-0,10.00
            pay-1,11.00
            pay-2,12.00
            pay-3,13.00
            pay-4,14.00
            pay-5,15.00
            pay-6,16.00
            pay-7,17.00
            pay-8,18.00
            pay-9,19.00
            """;

    /** Rows the fake runtime applied (create calls only — mode is create). */
    static final List<String> APPLIED = new ArrayList<>();

    /** Set to a chunk ordinal to fail that chunk (simulating the kill). */
    static volatile int failOnChunk = -1;

    static AppDefinition app;

    @Autowired
    JobStore jobs;

    @Autowired
    JobRunner runner;

    @TestConfiguration
    static class Stubs {

        @Bean
        @Primary
        PublishedIntegrations publishedIntegrations() {
            app = DefinitionParser.parseApp(APP_JSON);
            return new PublishedIntegrations() {

                @Override
                public java.util.Optional<AppDefinition> byApiName(UUID tenantId, String apiName) {
                    return java.util.Optional.of(app);
                }

                @Override
                public List<AppDefinition> allApps(UUID tenantId) {
                    return List.of(app);
                }
            };
        }

        @Bean
        @Primary
        RuntimeClient runtimeClient() {
            return new RuntimeClient() {

                private int chunk = 0;

                @Override
                public List<Outcome> write(UUID tenantId, List<Map<String, Object>> items) {
                    if (failOnChunk == ++chunk) {
                        throw new com.novaforge.common.error.PlatformException(
                                com.novaforge.common.error.PlatformErrorCode.INTERNAL,
                                "simulated kill: the runtime chunk leg failed");
                    }
                    List<Outcome> outcomes = new ArrayList<>();
                    for (Map<String, Object> item : items) {
                        Map<String, Object> record = (Map<String, Object>) item.get("record");
                        APPLIED.add(String.valueOf(record.get("reference")));
                        Map<String, Object> created = new LinkedHashMap<>(record);
                        created.put("id", UUID.randomUUID().toString());
                        created.put("version", 1);
                        outcomes.add(new Outcome("ok", created, null, null));
                    }
                    return outcomes;
                }
            };
        }

        @Bean
        @Primary
        FileClient fileClient() {
            return new FileClient() {

                @Override
                public UUID upload(UUID tenantId, String fileName, String contentType,
                                   byte[] content, UUID initiatedBy) {
                    return UUID.randomUUID();
                }

                @Override
                public byte[] download(UUID tenantId, UUID fileId) {
                    return CSV.getBytes(StandardCharsets.UTF_8);
                }
            };
        }
    }

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("docker.io/library/redis:7.4.11")
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestBase::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestBase::jdbcUsername);
        registry.add("spring.datasource.password", PostgresTestBase::jdbcPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Test
    @DisplayName("kill mid-run → resume → per-row exactly-once (count + ledger)")
    void killMidRunThenResumeAppliesEachRowExactlyOnce() {
        UUID jobId = jobs.create(TENANT, JobStore.Kind.IMPORT, "Erp", null, "paymentFeed",
                null, null, null, UUID.randomUUID(), "feed.csv", null, ACTOR, null);
        failOnChunk = 2;   // the second chunk fails — rows 0-3 settled, 4+ outstanding
        runner.scan();
        JobStore.Job failed = jobs.find(TENANT, jobId).orElseThrow();
        assertThat(failed.status()).isEqualTo("failed");
        assertThat(failed.processedRows()).isEqualTo(4L);   // chunk one's checkpoint
        assertThat(APPLIED).hasSize(4);
        assertThat(APPLIED).containsExactly("pay-0", "pay-1", "pay-2", "pay-3");

        // the heal: resume from the checkpoint — settled rows never re-apply
        failOnChunk = -1;
        runner.resume(TENANT, jobId);
        runner.scan();
        JobStore.Job completed = jobs.find(TENANT, jobId).orElseThrow();
        assertThat(completed.status()).isEqualTo("completed");
        assertThat(completed.processedRows()).isEqualTo(10L);

        // exactly-once: every reference exactly once, in order
        assertThat(APPLIED).hasSize(10);
        assertThat(APPLIED).containsExactly("pay-0", "pay-1", "pay-2", "pay-3", "pay-4",
                "pay-5", "pay-6", "pay-7", "pay-8", "pay-9");

        // the per-row ledger retains every outcome (§7: per-item outcomes retained)
        List<JobStore.RowOutcome> rows = jobs.rows(jobId);
        assertThat(rows).hasSize(10);
        assertThat(rows).allSatisfy(row -> assertThat(row.status()).isEqualTo("ok"));
        assertThat(rows.stream().map(JobStore.RowOutcome::recordId))
                .doesNotContainNull();
    }
}
