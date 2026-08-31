package com.novaforge.file;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novaforge.file.api.AttachmentService;
import com.novaforge.file.api.RecordReadGate;
import com.novaforge.file.storage.StoragePort;
import com.novaforge.testsupport.PostgresTestBase;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The §9 record gate on the BINDING doors (eighteenth pass, closing the recorded
 * open): a caller-supplied target record must be readable by THIS caller — both
 * when the upload begins (the stored tag makes the attachment record-governed
 * from the first moment) and when the completion binds. Ungated, any same-tenant
 * user could plant attachment metadata on records they cannot read, and the bind
 * simultaneously stripped the uploader's own access (a bound attachment rides
 * the record's authorization alone).
 */
@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "novaforge.storage.binding=none",
})
@AutoConfigureMockMvc
class FileBindGateTests extends PostgresTestBase {

    static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID ACTOR = UUID.fromString("33333333-3333-4333-8333-333333333333");

    /** The gate's controllable verdict + what target it was asked about. */
    static final AtomicBoolean READABLE = new AtomicBoolean(true);
    static final AtomicReference<String> ASKED = new AtomicReference<>();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AttachmentService attachments;

    @Autowired
    StoragePort storage;

    @Autowired
    JdbcTemplate jdbc;

    @TestConfiguration
    static class Stubs {

        @Bean
        @Primary
        StoragePort storage() {
            return new StoragePort.InMemory();
        }

        @Bean
        @Primary
        RecordReadGate gate() {
            return new RecordReadGate("http://127.0.0.1:1") {
                @Override
                public boolean canRead(String entity, UUID recordId) {
                    ASKED.set(entity + "/" + recordId);
                    return READABLE.get();
                }
            };
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestBase::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestBase::jdbcUsername);
        registry.add("spring.datasource.password", PostgresTestBase::jdbcPassword);
    }

    private UUID uploaded(String content) {
        var grant = attachments.beginUpload(TENANT, ACTOR, "sample.txt", "text/plain",
                (long) content.length(), null, null);
        storage.put(TENANT + "/" + grant.id(), content.getBytes(StandardCharsets.UTF_8),
                "text/plain");
        return grant.id();
    }

    @Test
    @DisplayName("binding to an unreadable record rejects — the attachment stays unbound")
    void unreadableTargetRejectsTheBind() throws Exception {
        UUID id = uploaded("hello novaforge");
        UUID target = UUID.randomUUID();
        READABLE.set(false);
        mockMvc.perform(post("/api/v1/files/{id}/complete", id).with(jwtFor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entity\":\"Confidential\",\"recordId\":\"" + target + "\"}"))
                .andExpect(status().isForbidden());
        // the gate saw exactly this target, and nothing was planted
        org.assertj.core.api.Assertions.assertThat(ASKED.get())
                .isEqualTo("Confidential/" + target);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT entity FROM fl_attachments WHERE id = ?", String.class, id)).isNull();
        // the gate ran BEFORE completion: a doomed bind fires no external side
        // effects — the checksum/scan the completion writes never landed, so the
        // attachment is still completable once the caller binds a readable target
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT checksum FROM fl_attachments WHERE id = ?", String.class, id)).isNull();
    }

    @Test
    @DisplayName("a readable target binds; an ungated upload tag rejects the same way")
    void readableTargetBindsAndTagDoorGates() throws Exception {
        UUID id = uploaded("hello novaforge");
        UUID target = UUID.randomUUID();
        READABLE.set(true);
        mockMvc.perform(post("/api/v1/files/{id}/complete", id).with(jwtFor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entity\":\"Order\",\"recordId\":\"" + target + "\"}"))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT entity FROM fl_attachments WHERE id = ?", String.class, id))
                .isEqualTo("Order");

        // the upload door: a caller-supplied tag on an unreadable target never lands
        READABLE.set(false);
        UUID denied = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/files/uploads").with(jwtFor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"x.txt\",\"size\":1,"
                                + "\"entity\":\"Confidential\",\"recordId\":\"" + denied + "\"}"))
                .andExpect(status().isForbidden());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM fl_attachments WHERE record_id = ?", Integer.class,
                denied)).isZero();
    }

    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor() {
        return jwt()
                .jwt(token -> token.claim("tenant_id", TENANT.toString())
                        .subject(ACTOR.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
    }
}
