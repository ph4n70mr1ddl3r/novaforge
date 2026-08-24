package com.novaforge.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.novaforge.common.error.PlatformException;
import com.novaforge.file.api.AttachmentService;
import com.novaforge.file.storage.StoragePort;
import com.novaforge.file.virus.VirusScanner;
import com.novaforge.testsupport.PostgresTestBase;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * §11 item 5: checksum mismatch rejects (the object deletes), the pinned
 * 15-minute presign expiry is recorded server-side with every grant, and the
 * config-on ClamAV gate quarantines an EICAR sample — download blocked, the
 * quarantine event outboxed. The in-memory storage twin keeps the logic
 * hermetic; the MinIO binding carries the same port contract in deployment.
 */
@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "novaforge.storage.binding=none",
        "novaforge.file.clamav.enabled=true",
        "novaforge.file.presign-expiry-seconds=900",
})
class FileServiceTests extends PostgresTestBase {

    static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID ACTOR = UUID.fromString("33333333-3333-4333-8333-333333333333");

    static final String EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

    @Autowired
    AttachmentService attachments;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StoragePort storage;

    /** The movable clock — presign expiry asserts against it. */
    static final AtomicReference<Clock> CLOCK =
            new AtomicReference<>(Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC));

    @TestConfiguration
    static class Stubs {

        @Bean
        @Primary
        StoragePort storage() {
            return new StoragePort.InMemory();
        }

        @Bean
        @Primary
        Clock clock() {
            return new Clock() {
                @Override
                public Instant instant() {
                    return CLOCK.get().instant();
                }

                @Override
                public ZoneOffset getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(java.time.ZoneId zone) {
                    return this;
                }
            };
        }

        /** The clamd stand-in: flags exactly the EICAR signature (§11 item 5). */
        @Bean
        @Primary
        VirusScanner scanner() {
            return content -> new String(content, StandardCharsets.UTF_8).contains("EICAR-STANDARD");
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestBase::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestBase::jdbcUsername);
        registry.add("spring.datasource.password", PostgresTestBase::jdbcPassword);
    }

    private UUID upload(String content, String contentType) {
        var grant = attachments.beginUpload(TENANT, ACTOR, "sample.txt", contentType,
                (long) content.length(), null, null);
        storage.put(TENANT + "/" + grant.id(), content.getBytes(StandardCharsets.UTF_8),
                contentType);
        return grant.id();
    }

    @Test
    @DisplayName("upload completion verifies the checksum server-side over the stored bytes")
    void checksumVerified() {
        UUID id = upload("hello novaforge", "text/plain");
        var completion = attachments.complete(TENANT, id, null);
        assertThat(completion.virusScan()).isEqualTo("clean");
        assertThat(completion.size()).isEqualTo(15L);
        assertThat(completion.checksum()).isEqualTo(AttachmentService.sha256(
                "hello novaforge".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("checksum mismatch rejects and deletes the object (§11 item 5)")
    void checksumMismatchRejects() {
        UUID id = upload("hello novaforge", "text/plain");
        assertThatThrownBy(() -> attachments.complete(TENANT, id, "bm90LXRoZS1oYXNo"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("checksum mismatch");
        // the stored object is gone — a rejected upload leaves no bytes behind
        assertThatThrownBy(() -> ((StoragePort.InMemory) storage).get(TENANT + "/" + id))
                .isInstanceOf(PlatformException.class);
    }

    @Test
    @DisplayName("presign expiry pinned at 15 minutes — grants record it server-side (§8)")
    void presignExpiryPinned() {
        var grant = attachments.beginUpload(TENANT, ACTOR, "exp.txt", "text/plain", 1L, null, null);
        assertThat(grant.expiresAt()).isEqualTo(Instant.parse("2026-08-24T00:15:00Z"));
        assertThat(grant.uploadUrl()).contains("expires=900");
        // the grant ledger carries the same expiry
        var recorded = jdbc.queryForList(
                "SELECT expires_at FROM fl_grants WHERE attachment = ?", grant.id());
        assertThat(recorded).hasSize(1);
        assertThat(((java.sql.Timestamp) recorded.getFirst().get("expires_at")).toInstant())
                .isEqualTo(Instant.parse("2026-08-24T00:15:00Z"));
        // the clock moves past expiry: a download presign for the same attachment
        // is a fresh grant — 15 minutes from the new now
        UUID id = upload("fresh", "text/plain");
        attachments.complete(TENANT, id, null);
        CLOCK.set(Clock.fixed(Instant.parse("2026-08-24T01:00:00Z"), ZoneOffset.UTC));
        var download = attachments.presignDownload(TENANT, id);
        assertThat(download.expiresAt()).isEqualTo(Instant.parse("2026-08-24T01:15:00Z"));
    }

    @Test
    @DisplayName("ClamAV gate (config-on): EICAR quarantines — download blocked, event outboxed")
    void clamavQuarantinesEicar() {
        UUID id = upload(EICAR, "application/octet-stream");
        var completion = attachments.complete(TENANT, id, null);
        assertThat(completion.virusScan()).isEqualTo("infected");
        // download is blocked for quarantined files (§8)
        assertThatThrownBy(() -> attachments.presignDownload(TENANT, id))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("quarantined");
        // the quarantine audit event rides the outbox
        var events = jdbc.queryForList(
                "SELECT payload FROM fl_event_outbox WHERE event_type = 'file.quarantined'");
        assertThat(events).anySatisfy(row -> assertThat(String.valueOf(row.get("payload")))
                .contains(id.toString()));
    }

    @Test
    @DisplayName("the internal server-side leg stores job outputs with checksums (§7)")
    void internalUploadStores() {
        var completion = attachments.storeServiceUpload(TENANT, ACTOR, "export-1.csv",
                "text/csv;charset=UTF-8", "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8));
        assertThat(completion.virusScan()).isEqualTo("clean");
        assertThat(completion.size()).isEqualTo("a,b\n1,2\n".length());
        assertThat(attachments.content(TENANT, completion.id())).isNotNull();
    }
}
