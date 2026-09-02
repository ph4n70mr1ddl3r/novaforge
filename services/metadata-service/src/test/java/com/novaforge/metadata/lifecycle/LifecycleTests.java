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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Phase 8 lifecycle surfaces (§4/§5/§6/§7): suite-gated promotion with version
 * identity by content hash, order enforcement + the admin-only prod hop, audited
 * overrides rendering in change-set review forever, compatibility-scoped rollback,
 * the hashed+signed artifact, headless suite runs, templates, and i18n workspaces.
 * The scratch-tenant runner and the environment provisioner are stubbed (their HTTP
 * legs ride the live stack); every gate, store, and audit decision is the real path.
 */
    @SpringBootTest(properties = "novaforge.metadata.publish-transport=noop")
@AutoConfigureMockMvc
class LifecycleTests extends PostgresTestBase {

    private static final String TENANT = "11111111-1111-4111-8111-111111111111";
    private static final String ACTOR = "33333333-3333-4333-8333-333333333333";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Red/green switchable runner stub — the gate consumes recorded artifacts only. */
    private static final AtomicBoolean GREEN = new AtomicBoolean(true);

    /** Simulates a promotion crashing after the remote provision landed (the intent window). */
    private static final AtomicBoolean CRASH_AFTER_PROVISION = new AtomicBoolean(false);

    @Autowired
    MockMvc mockMvc;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    com.novaforge.metadata.store.MetadataStore store;

    @Autowired
    com.novaforge.metadata.lifecycle.EnvironmentReconciler reconciler;

    /** One MockMvc result → a parsed JSON value (maps/lists), for typed assertions. */
    private Object objectMapperRead(org.springframework.test.web.servlet.MvcResult result)
            throws Exception {
        return MAPPER.readValue(result.getResponse().getContentAsString(), Object.class);
    }

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
            // keyed on (source tenant, app, env) like the real HTTP provisioner: the
            // env tenant id is deterministic, a repeat provision adopts (recreates)
            // the app row instead of provisioning a second tenant, and a caller can
            // make the first call crash AFTER the remote work lands — the exact
            // window the intent journal exists for
            java.util.Set<String> provisioned = java.util.concurrent.ConcurrentHashMap.newKeySet();
            Mockito.when(provisioner.provision(Mockito.any(UUID.class),
                            Mockito.any(AppDefinition.class), Mockito.anyString()))
                    .thenAnswer(inv -> {
                        UUID sourceTenant = inv.getArgument(0);
                        AppDefinition bundle = inv.getArgument(1);
                        String envName = inv.getArgument(2);
                        UUID envTenant = UUID.nameUUIDFromBytes(
                                ("env:" + sourceTenant + ":" + bundle.apiName() + ":"
                                        + envName).getBytes());
                        String key = sourceTenant + ":" + bundle.apiName() + ":" + envName;
                        boolean first = provisioned.add(key);
                        // a partial prior attempt left its app behind — retire it (the
                        // real provisioner's adopt-or-recreate leg; md_apps pins one
                        // apiName per tenant)
                        for (AppDefinition existing : store.listApps(envTenant)) {
                            if (existing.apiName().equals(bundle.apiName())) {
                                store.deleteApp(envTenant, UUID.fromString(existing.id()));
                            }
                        }
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
                        if (first && CRASH_AFTER_PROVISION.get()) {
                            CRASH_AFTER_PROVISION.set(false);
                            throw new com.novaforge.common.error.PlatformException(
                                    com.novaforge.common.error.PlatformErrorCode.INTERNAL,
                                    "simulated crash after the remote provision landed");
                        }
                        return new EnvironmentProvisioner.EnvironmentRef(envTenant,
                                UUID.fromString(created.id()));
                    });
            return provisioner;
        }    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestBase::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestBase::jdbcUsername);
        registry.add("spring.datasource.password", PostgresTestBase::jdbcPassword);
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

    @Test
    @DisplayName("§4 item 2 symmetry: a prod rollback is the admin hop — a builder cannot move prod's pin back")
    void prodRollbackIsTheAdminHop() throws Exception {
        String appId = createGatedApp();
        GREEN.set(true);
        publish(appId);
        int version = latestVersion(appId);
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/suite-runs").with(builderJwt())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/environments/staging/promote")
                        .with(builderJwt()).contentType("application/json")
                        .content("{\"version\":" + version + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/environments/prod/promote")
                        .with(adminJwt()).contentType("application/json")
                        .content("{\"version\":" + version + "}"))
                .andExpect(status().isOk());

        // v2 so prod has somewhere to roll back to
        mockMvc.perform(patch("/api/v1/metadata/apps/" + appId).with(builderJwt())
                        .contentType("application/json").content("{\"label\":\"renamed\"}"))
                .andExpect(status().isOk());
        publish(appId);
        int v2 = latestVersion(appId);
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/suite-runs").with(builderJwt())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/environments/staging/promote")
                        .with(builderJwt()).contentType("application/json")
                        .content("{\"version\":" + v2 + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/environments/prod/promote")
                        .with(adminJwt()).contentType("application/json")
                        .content("{\"version\":" + v2 + "}"))
                .andExpect(status().isOk());

        // Anti-regression (2026-08-31): a compatible, green rollback of prod used to
        // skip every admin control — promote's prod hop, applied asymmetrically.
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/environments/prod/rollback")
                        .with(builderJwt()).contentType("application/json")
                        .content("{\"toVersion\":" + version + "}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/environments/prod/rollback")
                        .with(adminJwt()).contentType("application/json")
                        .content("{\"toVersion\":" + version + "}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("§4 item 4 bypass: promoting an older version must not dodge the rollback gate")
    void promotingAnOlderVersionIsARollback() throws Exception {
        String appId = createGatedApp();
        GREEN.set(true);
        publish(appId);   // v1
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/suite-runs").with(builderJwt())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        // v2 removes nothing (compatible) but is the newer artifact; pin staging to it
        mockMvc.perform(patch("/api/v1/metadata/apps/" + appId).with(builderJwt())
                        .contentType("application/json").content("{\"label\":\"renamed\"}"))
                .andExpect(status().isOk());
        publish(appId);
        int v2 = latestVersion(appId);
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/suite-runs").with(builderJwt())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/environments/staging/promote")
                        .with(builderJwt()).contentType("application/json")
                        .content("{\"version\":" + v2 + "}"))
                .andExpect(status().isOk());

        // Anti-regression (2026-08-31): promote {"version":1} deployed the older
        // artifact through the plain gate — no compatibility check, no
        // dataMigrationAcknowledgment — exactly what the rollback door enforces.
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/environments/staging/promote")
                        .with(builderJwt()).contentType("application/json")
                        .content("{\"version\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("is a rollback")));
    }

    @Test
    @DisplayName("§4 item 1 evidence: suite-run retention never evicts a published version's green run")
    void retentionKeepsPublishedVersionGateEvidence() throws Exception {
        String appId = createGatedApp();
        GREEN.set(true);
        publish(appId);   // v1, its hash recorded in md_versions
        int v1 = latestVersion(appId);
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/suite-runs").with(builderJwt())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());

        // Move the draft (a new content hash), then bury v1's run under 30 newer-hash
        // runs — anti-regression (2026-08-31): hash-blind trimming to the newest 25
        // evicted v1's green run permanently (the draft can never re-record it).
        mockMvc.perform(patch("/api/v1/metadata/apps/" + appId).with(builderJwt())
                        .contentType("application/json").content("{\"label\":\"iterated\"}"))
                .andExpect(status().isOk());
        for (int i = 0; i < 30; i++) {
            mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/suite-runs")
                            .with(builderJwt())
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isOk());
        }

        // v1's gate evidence survived the churn — promoting v1 stays admissible
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/environments/staging/promote")
                        .with(builderJwt()).contentType("application/json")
                        .content("{\"version\":" + v1 + "}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a promotion crashing after the remote provision converges on retry — no leaked tenant")
    void crashedProvisionRetriesConverge() throws Exception {
        // Anti-regression (2026-08-31): the first promotion wrote nothing before the
        // remote provisioning calls — a failure between provision and pin was
        // invisible, and every retry provisioned a second sandbox tenant. The intent
        // row (V12) lands first; provisioning is keyed on (tenant, app, env).
        String appId = createGatedApp();
        GREEN.set(true);
        publish(appId);
        int version = latestVersion(appId);
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/suite-runs").with(builderJwt())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());

        // the first attempt crashes after the remote work landed
        CRASH_AFTER_PROVISION.set(true);
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/environments/staging/promote")
                        .with(builderJwt()).contentType("application/json")
                        .content("{\"version\":" + version + "}"))
                .andExpect(status().is5xxServerError());

        // the retry converges: the environment completes under the SAME identity —
        // the deterministic env tenant the crashed attempt already provisioned
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/environments/staging/promote")
                        .with(builderJwt()).contentType("application/json")
                        .content("{\"version\":" + version + "}"))
                .andExpect(status().isOk());
        var app = objectMapperRead(mockMvc.perform(
                        get("/api/v1/metadata/apps/" + appId).with(builderJwt()))
                .andExpect(status().isOk()).andReturn());
        var environments = objectMapperRead(mockMvc.perform(
                        get("/api/v1/metadata/apps/" + appId + "/environments").with(builderJwt()))
                .andExpect(status().isOk()).andReturn());
        String apiName = String.valueOf(((java.util.Map<?, ?>) app).get("apiName"));
        UUID expectedTenant = UUID.nameUUIDFromBytes(
                ("env:" + TENANT + ":" + apiName + ":staging").getBytes());
        org.assertj.core.api.Assertions.assertThat(
                        String.valueOf(((java.util.Map<?, ?>) ((java.util.List<?>) environments)
                                .get(0)).get("tenantId")))
                .isEqualTo(expectedTenant.toString());
        // exactly one environment row, active, with its identity complete
        var row = jdbc.queryForMap(
                "SELECT status, env_tenant_id, provision_key FROM md_environments WHERE app_id = ?::uuid",
                appId);
        org.assertj.core.api.Assertions.assertThat(row.get("status")).isEqualTo("active");
        org.assertj.core.api.Assertions.assertThat(row.get("env_tenant_id"))
                .isEqualTo(expectedTenant);
        org.assertj.core.api.Assertions.assertThat(row.get("provision_key")).isNotNull();
    }

    @Test
    @DisplayName("boot reconcile aligns a pin that drifted from the environment's published version")
    void bootReconcileAlignsDriftedPins() throws Exception {
        // Anti-regression (2026-08-31): a promote/rollback dying between the
        // environment tenant's publish and the pin left the environment serving a
        // version the control plane could not see. The reconciler compares each pin
        // against the environment tenant's actual latest publish and realigns.
        String appId = createGatedApp();
        GREEN.set(true);
        publish(appId);
        int version = latestVersion(appId);
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/suite-runs").with(builderJwt())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/metadata/apps/" + appId + "/environments/staging/promote")
                        .with(builderJwt()).contentType("application/json")
                        .content("{\"version\":" + version + "}"))
                .andExpect(status().isOk());

        // simulate the drift: the environment tenant publishes v2 while the pin dies
        var row = jdbc.queryForMap("""
                SELECT env_tenant_id, env_app_id FROM md_environments
                 WHERE app_id = ?::uuid AND env = 'staging'""", appId);
        UUID envTenant = (UUID) row.get("env_tenant_id");
        UUID envApp = (UUID) row.get("env_app_id");
        store.publish(envTenant, UUID.fromString(ACTOR), envApp, 2,
                store.requireApp(envTenant, envApp), java.util.List.of(), false);

        reconciler.reconcile();

        Integer pinned = jdbc.queryForObject("""
                SELECT pinned_version FROM md_environments
                 WHERE app_id = ?::uuid AND env = 'staging'""", Integer.class, appId);
        org.assertj.core.api.Assertions.assertThat(pinned).isEqualTo(2);
        String kinds = jdbc.queryForObject("""
                SELECT string_agg(kind, ',' ORDER BY promoted_at) FROM md_promotions
                 WHERE app_id = ?::uuid AND env = 'staging'""", String.class, appId);
        org.assertj.core.api.Assertions.assertThat(kinds).contains("reconcile");
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

    @Test
    @DisplayName("§3: change-set review carries the gap-log entries the version resolves + the re-bind union")
    void changeSetRendersResolvedGaps() throws Exception {
        String appId = createGatedApp();
        // author the gap-log branch + one bound credential on the draft, publish
        mockMvc.perform(patch("/api/v1/metadata/apps/" + appId).with(builderJwt())
                        .contentType("application/json")
                        .content("""
                                { "integrations": { "credentials": [ { "id": "cred_bound",
                                    "kind": "api_key", "header": "Authorization" } ] },
                                  "gapLog": [
                                    { "id": "G-1", "area": "P3", "blocker": "b", "priority": "high",
                                      "disposition": "open" },
                                    { "id": "G-2", "area": "P4", "blocker": "b", "priority": "low",
                                      "disposition": "backlog" } ] }
                                """))
                .andExpect(status().isOk());
        publish(appId);

        // the branch rides publish (the Phase 4 §9 regression, now for gapLog)
        MvcResult published = mockMvc.perform(
                        get("/api/v1/metadata/apps/" + appId + "/published").with(builderJwt()))
                .andExpect(status().isOk()).andReturn();
        assertThat(MAPPER.readTree(published.getResponse().getContentAsString())
                .get("app").get("gapLog").size()).isEqualTo(2);

        // the promoting draft resolves G-1 and introduces a new credential ref
        mockMvc.perform(patch("/api/v1/metadata/apps/" + appId).with(builderJwt())
                        .contentType("application/json")
                        .content("""
                                { "integrations": { "credentials": [ { "id": "cred_bound",
                                    "kind": "api_key", "header": "Authorization" },
                                  { "id": "cred_new", "kind": "basic", "username": "u" } ] },
                                  "gapLog": [
                                    { "id": "G-1", "area": "P3", "blocker": "b", "priority": "high",
                                      "disposition": "closed", "resolvedIn": "this change set" },
                                    { "id": "G-2", "area": "P4", "blocker": "b", "priority": "low",
                                      "disposition": "backlog" } ] }
                                """))
                .andExpect(status().isOk());

        MvcResult review = mockMvc.perform(
                        get("/api/v1/metadata/apps/" + appId + "/changeset?env=dev").with(builderJwt()))
                .andExpect(status().isOk()).andReturn();
        var node = MAPPER.readTree(review.getResponse().getContentAsString());
        // §3: "the gap-log entries the version resolves" — G-1 (open → closed) yes, G-2 no
        assertThat(node.get("resolvedGaps").size()).isEqualTo(1);
        assertThat(node.get("resolvedGaps").get(0).get("id").asString()).isEqualTo("G-1");
        assertThat(node.get("resolvedGaps").get(0).get("resolvedIn").asString())
                .isEqualTo("this change set");
        // the gap branch diffs like every other branch
        assertThat(node.get("diff").get("gapLog").get("modified").toString()).contains("G-1");
        // §3: the re-bind list carries the union — the already-bound ref AND the new one
        assertThat(node.get("credentialRefs").toString()).contains("cred_bound", "cred_new");
    }

    @Test
    @DisplayName("PHASE-7 §9 item 7: change-set review reports the script ratio per module")
    void changeSetReportsPerModuleScriptRatio() throws Exception {
        // an app whose hooks split across modules (one module all-script, others
        // all-declarative, one entity module-less) — §9 item 7's exit-review report
        MvcResult created = mockMvc.perform(post("/api/v1/metadata/apps").with(builderJwt())
                        .contentType("application/json")
                        .content("""
                                { "apiName": "Mod%s", "entities": [
                                  { "apiName": "Ledger", "module": "GL",
                                    "fields": [ { "apiName": "name", "type": "text" } ],
                                    "hooks": [ { "name": "post", "trigger": "afterSave",
                                      "flow": { "id": "p1", "op": "publishEvent",
                                        "params": { "name": "ledger.posted" } } } ] },
                                  { "apiName": "Move", "module": "Inventory",
                                    "fields": [ { "apiName": "name", "type": "text" } ],
                                    "hooks": [ { "name": "cost", "trigger": "beforeSave",
                                      "script": { "language": "js",
                                        "source": "(function () { return {}; })()" } } ] },
                                  { "apiName": "Lone",
                                    "fields": [ { "apiName": "name", "type": "text" } ],
                                    "hooks": [ { "name": "side", "trigger": "afterSave",
                                      "flow": { "id": "q1", "op": "publishEvent",
                                        "params": { "name": "lone.posted" } } } ] } ] }
                                """.formatted(UUID.randomUUID().toString().substring(0, 6))))
                .andExpect(status().isOk()).andReturn();
        String appId = MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asString();

        MvcResult review = mockMvc.perform(
                        get("/api/v1/metadata/apps/" + appId + "/changeset?env=dev").with(builderJwt()))
                .andExpect(status().isOk()).andReturn();
        var ratio = MAPPER.readTree(review.getResponse().getContentAsString()).get("scriptRatio");
        // the app-level share: one script of three hooks
        assertThat(ratio.get("draft").asDouble()).isEqualTo(1.0 / 3);
        // the per-module report: GL declarative, Inventory all-script, the
        // module-less entity buckets under its own apiName
        assertThat(ratio.get("modules").get("GL").get("hooks").asInt()).isEqualTo(1);
        assertThat(ratio.get("modules").get("GL").get("scripts").asInt()).isEqualTo(0);
        assertThat(ratio.get("modules").get("GL").get("scriptShare").asDouble()).isEqualTo(0.0);
        assertThat(ratio.get("modules").get("Inventory").get("hooks").asInt()).isEqualTo(1);
        assertThat(ratio.get("modules").get("Inventory").get("scripts").asInt()).isEqualTo(1);
        assertThat(ratio.get("modules").get("Inventory").get("scriptShare").asDouble()).isEqualTo(1.0);
        assertThat(ratio.get("modules").get("Lone").get("hooks").asInt()).isEqualTo(1);
        assertThat(ratio.get("modules").get("Lone").get("scriptShare").asDouble()).isEqualTo(0.0);
    }
}
