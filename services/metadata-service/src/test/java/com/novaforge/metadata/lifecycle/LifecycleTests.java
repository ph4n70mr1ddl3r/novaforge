package com.novaforge.metadata.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.TestSuiteDefinition;
import com.novaforge.metadata.harness.TestRunner;
import com.novaforge.testsupport.PostgresTestBase;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Phase 8 lifecycle surfaces (§4/§5/§6/§7): suite-gated promotion with version
 * identity by content hash, order enforcement + the admin-only prod hop, audited
 * overrides rendering in change-set review forever, compatibility-scoped rollback,
 * the hashed+signed artifact, headless suite runs, templates, and i18n workspaces.
 * The scratch-tenant runner and the environment provisioner are stubbed (their HTTP
 * legs ride the live stack); every gate, store, and audit decision is the real path.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LifecycleTests extends PostgresTestBase {

    private static final String TENANT = "11111111-1111-4111-8111-111111111111";
    private static final String ACTOR = "33333333-3333-4333-8333-333333333333";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Red/green switchable runner stub — the gate consumes recorded artifacts only. */
    private static final AtomicBoolean GREEN = new AtomicBoolean(true);

    @Autowired
    MockMvc mockMvc;

    @TestConfiguration
    static class Stubs {

        @Bean
        @Primary
        TestRunner testRunner() {
            TestRunner runner = Mockito.mock(TestRunner.class);
            Mockito.when(runner.run(Mockito.any(AppDefinition.class),
                            Mockito.any(TestSuiteDefinition.class), Mockito.any()))
                    .thenAnswer(inv -> {
                        Map<String, Object> artifact = new java.util.LinkedHashMap<>();
                        artifact.put("runId", UUID.randomUUID().toString());
                        artifact.put("suite", inv.getArgument(1, TestSuiteDefinition.class).apiName());
                        artifact.put("appVersion", "candidate");
                        artifact.put("green", GREEN.get());
                        artifact.put("cases", java.util.List.of());
                        return artifact;
                    });
            return runner;
        }

        @Bean
        @Primary
        EnvironmentProvisioner environmentProvisioner(com.novaforge.metadata.store.MetadataStore store) {
            EnvironmentProvisioner provisioner = Mockito.mock(EnvironmentProvisioner.class);
            Mockito.when(provisioner.provision(Mockito.any(AppDefinition.class), Mockito.anyString()))
                    .thenAnswer(inv -> {
                        AppDefinition bundle = inv.getArgument(0);
                        UUID envTenant = UUID.randomUUID();
                        // the real first-promotion writes a real app row for the env tenant,
                        // so later promotions re-import against it (§2: envs keep their data)
                        AppDefinition fresh = new AppDefinition(null, bundle.apiName(),
                                bundle.label(), bundle.labelI18n(), bundle.description(),
                                bundle.entities(), bundle.pages(), bundle.settings(),
                                bundle.permissionSet(), bundle.testSuites(),
                                bundle.stateMachines(), bundle.slas(), bundle.jobs(),
                                bundle.workflows(), bundle.reports(), bundle.dashboards(),
                                bundle.integrations(), bundle.translations());
                        AppDefinition created = store.insertApp(envTenant,
                                UUID.fromString(ACTOR), fresh);
                        store.publish(envTenant, UUID.fromString(ACTOR),
                                UUID.fromString(created.id()), 1, bundle, java.util.List.of(), false);
                        return new EnvironmentProvisioner.EnvironmentRef(envTenant,
                                UUID.fromString(created.id()));
                    });
            return provisioner;
        }
    }

    private static final GenericContainer<?> REDIS = new GenericContainer<>("docker.io/library/redis:7.4.11")
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

    // --- helpers ---

    private String createGatedApp() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/metadata/apps").with(builderJwt())
                        .contentType("application/json")
                        .content("""
                                { "apiName": "Gate%s", "entities": [ { "apiName": "Thing",
                                  "fields": [ { "apiName": "name", "type": "text" } ] } ],
                                  "testSuites": [ { "apiName": "smoke", "label": "smoke",
                                    "cases": [ { "name": "c", "steps": [
                                      { "op": "createRecord", "entity": "Thing",
                                        "template": { "name": "x" }, "expect": "ok" } ],
                                      "assertExpressions": [] } ] } ] }
                                """.formatted(UUID.randomUUID().toString().substring(0, 6))))
                .andExpect(status().isOk()).andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString()).get("id").asString();
    }

    private void publish(String appId) throws Exception {
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/publish").with(builderJwt()))
                .andExpect(status().isOk());
    }

    private int latestVersion(String appId) throws Exception {
        MvcResult versions = mockMvc.perform(get("/api/v1/metadata/apps/" + appId + "/versions")
                        .with(builderJwt()))
                .andExpect(status().isOk()).andReturn();
        return MAPPER.readTree(versions.getResponse().getContentAsString())
                .get(0).get("version").asInt();
    }

    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor builderJwt() {
        return jwt()
                .jwt(token -> token.claim("tenant_id", TENANT).subject(ACTOR))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"),
                        new SimpleGrantedAuthority("ROLE_builder"));
    }

    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor adminJwt() {
        return jwt()
                .jwt(token -> token.claim("tenant_id", TENANT).subject(ACTOR))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"),
                        new SimpleGrantedAuthority("ROLE_builder"),
                        new SimpleGrantedAuthority("ROLE_admin"));
    }

    // --- §4: the promotion gate ---

    @Test
    @DisplayName("§9 negative: a red suite blocks promotion; a green run of exactly V admits")
    void gateBlocksAndAdmits() throws Exception {
        String appId = createGatedApp();
        publish(appId);
        int version = latestVersion(appId);

        // no recorded run at all → gate rejects
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/environments/staging/promote")
                        .with(builderJwt()).contentType("application/json")
                        .content("{\"version\":" + version + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("promotion gate")));

        // a red run (§9's deliberately failing suite) still blocks
        GREEN.set(false);
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/suite-runs").with(builderJwt())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.green").value(false));
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/environments/staging/promote")
                        .with(builderJwt()).contentType("application/json")
                        .content("{\"version\":" + version + "}"))
                .andExpect(status().isBadRequest());

        // green run against exactly this content → admitted (stub provisioner)
        GREEN.set(true);
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/suite-runs").with(builderJwt())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.green").value(true));
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/environments/staging/promote")
                        .with(builderJwt()).contentType("application/json")
                        .content("{\"version\":" + version + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/metadata/apps/" + appId + "/environments").with(builderJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].env").value("staging"))
                .andExpect(jsonPath("$[0].pinnedVersion").value(version));
    }

    @Test
    @DisplayName("§4 item 1 exactly: a run against a different draft content does not admit")
    void gateMatchesContentHashExactly() throws Exception {
        String appId = createGatedApp();
        GREEN.set(true);
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/suite-runs").with(builderJwt())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        // draft changes after the run → the run's hash no longer matches the published version
        mockMvc.perform(patch("/api/v1/metadata/apps/" + appId + "/entities/Thing")
                        .with(builderJwt()).contentType("application/json")
                        .content("{\"fields\":[{\"apiName\":\"name\",\"type\":\"text\"},{\"apiName\":\"extra\",\"type\":\"text\"}]}"))
                .andExpect(status().isOk());
        publish(appId);
        int version = latestVersion(appId);
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/environments/staging/promote")
                        .with(builderJwt()).contentType("application/json")
                        .content("{\"version\":" + version + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("§4 item 2: prod is the admin hop and rides staging's artifact; §3 override renders forever")
    void prodOrderAndOverride() throws Exception {
        String appId = createGatedApp();
        publish(appId);
        int version = latestVersion(appId);
        GREEN.set(true);
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/suite-runs").with(builderJwt())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());

        // staging not promoted yet → prod rejects even for the admin
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/environments/prod/promote")
                        .with(adminJwt()).contentType("application/json")
                        .content("{\"version\":" + version + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("staging first")));

        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/environments/staging/promote")
                        .with(builderJwt()).contentType("application/json")
                        .content("{\"version\":" + version + "}"))
                .andExpect(status().isOk());

        // the prod hop is the explicit platform-admin approval — a builder cannot take it
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/environments/prod/promote")
                        .with(builderJwt()).contentType("application/json")
                        .content("{\"version\":" + version + "}"))
                .andExpect(status().isForbidden());

        // a red gate at prod: override without reason rejects; with reason + admin passes,
        // is audited, and renders in the change-set review forever
        GREEN.set(false);
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/suite-runs").with(builderJwt())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/environments/prod/promote")
                        .with(adminJwt()).contentType("application/json")
                        .content("{\"version\":" + version + ",\"override\":true}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/environments/prod/promote")
                        .with(adminJwt()).contentType("application/json")
                        .content("{\"version\":" + version + ",\"override\":true,"
                                + "\"reason\":\"incident 4711: hotfix ahead of suite stabilization\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/metadata/apps/" + appId + "/changeset?env=prod")
                        .with(builderJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.promotions[0].overridden").value(true))
                .andExpect(jsonPath("$.promotions[0].reason").value(
                        org.hamcrest.Matchers.containsString("incident 4711")));
    }

    // --- §4 item 4: rollback, compatibility-scoped ---

    @Test
    @DisplayName("§9 rollback suite: compatible branch one-click; incompatible needs override + ack")
    void rollbackBranches() throws Exception {
        String appId = createGatedApp();
        GREEN.set(true);
        publish(appId);   // v1
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/suite-runs").with(builderJwt())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());

        // v2 adds a field (record the green run against this exact draft)
        mockMvc.perform(patch("/api/v1/metadata/apps/" + appId + "/entities/Thing")
                        .with(builderJwt()).contentType("application/json")
                        .content("{\"fields\":[{\"apiName\":\"name\",\"type\":\"text\"},{\"apiName\":\"extra\",\"type\":\"text\"}]}"))
                .andExpect(status().isOk());
        publish(appId);
        int v2 = latestVersion(appId);
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/suite-runs").with(builderJwt())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());

        // v3 changes only the label — rollback to v2 stays storage-compatible
        mockMvc.perform(patch("/api/v1/metadata/apps/" + appId).with(builderJwt())
                        .contentType("application/json").content("{\"label\":\"renamed\"}"))
                .andExpect(status().isOk());
        publish(appId);
        int v3 = latestVersion(appId);
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/suite-runs").with(builderJwt())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/environments/staging/promote")
                        .with(builderJwt()).contentType("application/json")
                        .content("{\"version\":" + v3 + "}"))
                .andExpect(status().isOk());

        // compatible rollback: one click, gate green for exactly v2's content
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/environments/staging/rollback")
                        .with(builderJwt()).contentType("application/json")
                        .content("{\"toVersion\":" + v2 + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/metadata/apps/" + appId + "/environments").with(builderJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pinnedVersion").value(v2));

        // incompatible rollback to v1 (v2's field leaves the prior version) blocks one-click
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/environments/staging/rollback")
                        .with(adminJwt()).contentType("application/json")
                        .content("{\"toVersion\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("storage-incompatible")));
        // override without the data-migration acknowledgment still rejects
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/environments/staging/rollback")
                        .with(adminJwt()).contentType("application/json")
                        .content("{\"toVersion\":1,\"override\":true,\"reason\":\"r\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("dataMigrationAcknowledged")));
        // override + acknowledgment lands, audited as a rollback
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/environments/staging/rollback")
                        .with(adminJwt()).contentType("application/json")
                        .content("{\"toVersion\":1,\"override\":true,\"reason\":\"prune accepted\","
                                + "\"dataMigrationAcknowledged\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/metadata/apps/" + appId + "/changeset?env=staging")
                        .with(builderJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.promotions[0].kind").value("rollback"))
                .andExpect(jsonPath("$.promotions[0].overridden").value(true));
    }

    // --- §2: the promotion artifact + §6: templates ---

    @Test
    @DisplayName("T1/T6: artifact hash+signature verified; tampering rejects; template round-trips")
    void artifactAndTemplates() throws Exception {
        String appId = createGatedApp();
        publish(appId);
        int version = latestVersion(appId);

        MvcResult artifact = mockMvc.perform(get("/api/v1/metadata/apps/" + appId
                        + "/versions/" + version + "/artifact").with(builderJwt()))
                .andExpect(status().isOk()).andReturn();
        byte[] zip = artifact.getResponse().getContentAsByteArray();
        assertThat(zip.length).isGreaterThan(0);

        // round-trip import creates a new draft app
        mockMvc.perform(post("/api/v1/metadata/artifacts/import").with(builderJwt())
                        .contentType("application/json")
                        .content(MAPPER.writeValueAsString(Map.of("zipBase64",
                                java.util.Base64.getEncoder().encodeToString(zip),
                                "apiName", "ImportedApp"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty());

        // tampering with the definitions breaks the manifest hash → refuse
        String corrupted = corruptDefinitions(zip);
        mockMvc.perform(post("/api/v1/metadata/artifacts/import").with(builderJwt())
                        .contentType("application/json")
                        .content(MAPPER.writeValueAsString(Map.of("zipBase64", corrupted))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("manifest hash")));

        // templates: register the version, list the catalog, install as a new draft
        MvcResult template = mockMvc.perform(post("/api/v1/metadata/templates").with(builderJwt())
                        .contentType("application/json")
                        .content(MAPPER.writeValueAsString(Map.of(
                                "appId", appId, "version", version,
                                "name", "GateTemplate", "publisher", "NovaForge",
                                "description", "the first template (§6)"))))
                .andExpect(status().isOk()).andReturn();
        String templateId = MAPPER.readTree(template.getResponse().getContentAsString())
                .get("id").asString();
        mockMvc.perform(get("/api/v1/metadata/templates").with(builderJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("GateTemplate"));
        mockMvc.perform(post("/api/v1/metadata/templates/" + templateId + "/install")
                        .with(builderJwt()).contentType("application/json")
                        .content("{\"apiName\":\"InstalledApp\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiName").value("InstalledApp"))
                .andExpect(jsonPath("$.entities[0].apiName").value("Thing"));
    }

    /** Rewrites definitions.json inside the ZIP, keeping manifest + signature stale. */
    private static String corruptDefinitions(byte[] zip) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(zip));
             java.util.zip.ZipOutputStream rewritten = new java.util.zip.ZipOutputStream(out)) {
            java.util.zip.ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                byte[] content = in.readAllBytes();
                if (entry.getName().equals("definitions.json")) {
                    content = new String(content, java.nio.charset.StandardCharsets.UTF_8)
                            .replace("\"text\"", "\"longText'")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                rewritten.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                rewritten.write(content);
                rewritten.closeEntry();
            }
        }
        return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
    }

    // --- §5: headless runs ---

    @Test
    @DisplayName("T5: headless app-wide runs record artifacts consumable via API")
    void headlessRuns() throws Exception {
        String appId = createGatedApp();
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/suite-runs").with(builderJwt())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs[0].suite").value("smoke"));
        mockMvc.perform(get("/api/v1/metadata/apps/" + appId + "/suite-runs").with(builderJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].suite").value("smoke"))
                .andExpect(jsonPath("$[0].contentHash").isNotEmpty())
                .andExpect(jsonPath("$[0].artifact.runId").isNotEmpty());

        // the single-suite pinned path (§5): suite row id resolves to its app
        MvcResult suites = mockMvc.perform(get("/api/v1/metadata/apps/" + appId).with(builderJwt()))
                .andExpect(status().isOk()).andReturn();
        String suiteId = null;
        for (com.novaforge.metadata.TestSuiteDefinition suite : DefinitionParserHolder.parse(
                suites.getResponse().getContentAsString()).testSuites()) {
            suiteId = suite.apiName();
        }
        assertThat(suiteId).isNotNull();
        // headless callers are builder-gated like every design-time surface (§5)
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/suite-runs")
                        .with(jwt().jwt(token -> token.claim("tenant_id", TENANT).subject(ACTOR))
                                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"))))
                .andExpect(status().isForbidden());
    }

    private static final class DefinitionParserHolder {

        static AppDefinition parse(String json) {
            return com.novaforge.metadata.DefinitionParser.parseApp(json);
        }
    }

    // --- §7: i18n ---

    @Test
    @DisplayName("T7: translation workspace — put, missing report, CSV export/import, bad keys reject")
    void i18nWorkspaces() throws Exception {
        String appId = createGatedApp();
        // unknown key rejects with guidance
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/metadata/apps/" + appId + "/translations/de")
                        .with(builderJwt())
                        .contentType("application/json")
                        .content("{\"nope.label\":\"Nein\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/metadata/apps/" + appId + "/translations/de")
                        .with(builderJwt())
                        .contentType("application/json")
                        .content("{\"app.label\":\"NovaForge ERP (DE)\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/metadata/apps/" + appId + "/translations").with(builderJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].locale").value("de"))
                .andExpect(jsonPath("$[0].missing.length()").value(
                        org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$[0].missing[0]").value(
                        org.hamcrest.Matchers.not("app.label")));
        // CSV export carries the full translatable universe (missing rows empty)
        MvcResult csv = mockMvc.perform(get("/api/v1/metadata/apps/" + appId
                        + "/translations/de/export").with(builderJwt()))
                .andExpect(status().isOk()).andReturn();
        String body = csv.getResponse().getContentAsString();
        assertThat(body).startsWith("key,value").contains("\"app.label\",\"NovaForge ERP (DE)\"");
        // import merges: a completed CSV round-trips into the workspace
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/metadata/apps/" + appId + "/translations/de/import")
                        .with(builderJwt())
                        .contentType("text/csv")
                        .content("key,value\nThing.label,\"Ding\"\n"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/metadata/apps/" + appId + "/translations").with(builderJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].missing.length()").value(
                        org.hamcrest.Matchers.greaterThan(0)));
        // translations are versioned metadata: they ride the publish round-trip
        publish(appId);
        mockMvc.perform(get("/api/v1/metadata/apps/" + appId + "/versions/" + latestVersion(appId)
                        + "/export").with(builderJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.translations[0].locale").value("de"));
    }
}
