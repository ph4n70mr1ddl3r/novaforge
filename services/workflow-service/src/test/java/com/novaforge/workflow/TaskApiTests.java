package com.novaforge.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novaforge.testsupport.PostgresTestBase;
import com.novaforge.workflow.events.TaskOutboxRelay;
import com.novaforge.workflow.roles.RoleLookup;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The task lifecycle and inbox (PHASE-4 §5, §14 T4): my-tasks resolution, resolution
 * with comments, claim, delegation chains with SoD fail-closed, admin-only reassign,
 * server-side access checks, {@code task.*} events riding the outbox to the spine,
 * and record deletion cancelling open tasks through the real Kafka leg.
 */
@SpringBootTest(properties = "novaforge.events.relay-interval-ms=3600000")   // manual relay
@AutoConfigureMockMvc
class TaskApiTests extends PostgresTestBase {

    static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID CLERK = UUID.fromString("33333333-3333-4333-8333-333333333333");
    static final UUID MANAGER = UUID.fromString("77777777-7777-4777-8777-777777777777");
    static final UUID SENIOR = UUID.fromString("88888888-8888-4888-8888-888888888888");
    static final UUID OUTSIDER = UUID.fromString("99999999-9999-4999-8999-999999999999");

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Actor → roles, per the stubbed lookup (platform + app-scoped). */
    static final Map<String, List<String>> ROLES = new ConcurrentHashMap<>();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TaskOutboxRelay relay;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    com.novaforge.workflow.task.TaskService tasks;

    /** Resumes the stub observed (the Data Runtime's stand-in). */
    static final List<String> RESUMES = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Autowired
    com.novaforge.workflow.task.SuspensionService suspensions;

    @TestConfiguration
    static class StubRoles {

        @Bean
        @Primary
        RoleLookup roleLookup() {
            return (tenantId, actor) -> ROLES.getOrDefault(actor.toString(), List.of());
        }

        @Bean
        @Primary
        com.novaforge.workflow.runtime.ResumeClient resumeClient() {
            return resume -> RESUMES.add(resume.approved() + ":" + resume.hook() + ":"
                    + (resume.afterStep() == null ? "-" : resume.afterStep()));
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
        jdbc.update("DELETE FROM wf_event_outbox");
        jdbc.update("DELETE FROM wf_suspended_flows");
        jdbc.update("DELETE FROM wf_tasks");
        RESUMES.clear();
        ROLES.clear();
        ROLES.put(CLERK.toString(), List.of("user"));
        ROLES.put(MANAGER.toString(), List.of("user", "Purch.manager"));
        ROLES.put(SENIOR.toString(), List.of("user", "Purch.seniorManager", "builder"));
        ROLES.put(OUTSIDER.toString(), List.of("user"));
    }

    @Test
    @DisplayName("my tasks: assigned to me or my roles, open by default, paged (§5)")
    void myTasksResolution() throws Exception {
        UUID record = UUID.randomUUID();
        tasks.create(TENANT, "approval", "Purch.PurchaseOrder", record, MANAGER, null,
                null, null, CLERK, null);
        tasks.create(TENANT, "approval", "Purch.PurchaseOrder", UUID.randomUUID(), null,
                "Purch.manager", null, null, CLERK, null);
        tasks.create(TENANT, "todo", "Purch.PurchaseOrder", UUID.randomUUID(), OUTSIDER,
                null, null, null, CLERK, null);

        // the manager sees the direct assignment and the role task; not the outsider's
        mockMvc.perform(get("/api/v1/workflow/tasks").with(jwtFor(MANAGER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));
        // the outsider sees only their own
        mockMvc.perform(get("/api/v1/workflow/tasks").with(jwtFor(OUTSIDER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
        // an actor with no matching role or assignment sees none
        ROLES.put("aaaaaaaa-0000-4000-8000-000000000000", List.of("user"));
        mockMvc.perform(get("/api/v1/workflow/tasks").with(jwtFor(UUID.fromString(
                        "aaaaaaaa-0000-4000-8000-000000000000"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    @DisplayName("approve/reject resolve with the comment and emit task.* on the spine (§5)")
    void resolutionEmitsEvents() throws Exception {
        UUID record = UUID.randomUUID();
        var task = tasks.create(TENANT, "approval", "Purch.PurchaseOrder", record, MANAGER,
                null, null, null, CLERK, null);

        mockMvc.perform(post("/api/v1/workflow/tasks/" + task.id() + "/approve")
                        .with(jwtFor(MANAGER)).contentType("application/json")
                        .content("{\"comment\":\"within budget\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // the lifecycle events rode the transaction: created, assigned, approved
        List<String> events = jdbc.queryForList(
                "SELECT event_type FROM wf_event_outbox ORDER BY created_at", String.class);
        assertThat(events).containsExactly("task.created", "task.assigned", "task.approved");

        // the relay ships them to novaforge.task keyed tenant:task, then marks published
        relay.relay();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM wf_event_outbox WHERE published_at IS NOT NULL",
                Integer.class)).isEqualTo(3);

        // re-resolution is a conflict, not a silent overwrite
        mockMvc.perform(post("/api/v1/workflow/tasks/" + task.id() + "/reject")
                        .with(jwtFor(MANAGER)).contentType("application/json").content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("claim converts a role task into an assignment, staying OPEN (§5)")
    void claimAssigns() throws Exception {
        var task = tasks.create(TENANT, "approval", "Purch.PurchaseOrder",
                UUID.randomUUID(), null, "Purch.manager", null, null, CLERK, null);
        mockMvc.perform(post("/api/v1/workflow/tasks/" + task.id() + "/claim")
                        .with(jwtFor(MANAGER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignee").value(MANAGER.toString()))
                .andExpect(jsonPath("$.status").value("OPEN"));
        mockMvc.perform(get("/api/v1/workflow/tasks").with(jwtFor(MANAGER)))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    @DisplayName("delegation chains via contextRef; SoD blocks the initiator (§5/§4)")
    void delegationChainsAndSod() throws Exception {
        var task = tasks.create(TENANT, "approval", "Purch.PurchaseOrder",
                UUID.randomUUID(), MANAGER, null, null, null, CLERK, null);

        MvcResult delegated = mockMvc.perform(post("/api/v1/workflow/tasks/" + task.id()
                        + "/delegate").with(jwtFor(MANAGER)).contentType("application/json")
                        .content("{\"toUser\":\"" + SENIOR + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignee").value(SENIOR.toString()))
                .andReturn();
        String replacementId = MAPPER.readTree(delegated.getResponse().getContentAsString())
                .get("id").asString();

        // the original went DELEGATED, the replacement carries the chain root
        Map<String, Object> original = jdbc.queryForMap(
                "SELECT status, context_ref FROM wf_tasks WHERE id = ?", task.id());
        assertThat(original.get("status")).isEqualTo("DELEGATED");
        assertThat(original.get("context_ref")).isEqualTo(task.id());
        assertThat(jdbc.queryForObject(
                "SELECT context_ref FROM wf_tasks WHERE id = ?",
                UUID.class, UUID.fromString(replacementId))).isEqualTo(task.id());

        // delegating back to the initiating clerk is segregation of duties — 4011
        mockMvc.perform(post("/api/v1/workflow/tasks/" + replacementId + "/delegate")
                        .with(jwtFor(SENIOR)).contentType("application/json")
                        .content("{\"toUser\":\"" + CLERK + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4011"));
    }

    @Test
    @DisplayName("reassign is admin/builder-only and audited; access checks 403 (§5/§13)")
    void reassignGateAndAccess() throws Exception {
        var task = tasks.create(TENANT, "approval", "Purch.PurchaseOrder",
                UUID.randomUUID(), MANAGER, null, null, null, CLERK, null);

        // a plain holder of the task's role cannot reassign
        mockMvc.perform(post("/api/v1/workflow/tasks/" + task.id() + "/reassign")
                        .with(jwtFor(MANAGER)).contentType("application/json")
                        .content("{\"toRole\":\"Purch.seniorManager\"}"))
                .andExpect(status().isForbidden());

        // the builder can (to a role), and it lands
        mockMvc.perform(post("/api/v1/workflow/tasks/" + task.id() + "/reassign")
                        .with(jwtFor(SENIOR)).contentType("application/json")
                        .content("{\"toRole\":\"Purch.seniorManager\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("Purch.seniorManager"));

        // access checks: an outsider cannot resolve a task they cannot see
        var mine = tasks.create(TENANT, "approval", "Purch.PurchaseOrder",
                UUID.randomUUID(), MANAGER, null, null, null, CLERK, null);
        mockMvc.perform(post("/api/v1/workflow/tasks/" + mine.id() + "/approve")
                        .with(jwtFor(OUTSIDER)).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("record.deleted on the spine cancels the record's open tasks (§5)")
    void recordDeletionCancelsTasks() throws Exception {
        UUID record = UUID.randomUUID();
        var first = tasks.create(TENANT, "approval", "Purch.PurchaseOrder", record, MANAGER,
                null, null, null, CLERK, null);
        tasks.create(TENANT, "todo", "Purch.PurchaseOrder", record, null, "Purch.manager",
                null, null, CLERK, first.id());
        var other = tasks.create(TENANT, "approval", "Purch.PurchaseOrder",
                UUID.randomUUID(), MANAGER, null, null, null, CLERK, null);

        // the runtime's record.deleted shape rides novaforge.record
        kafka.send("novaforge.record", TENANT + ":" + record, """
                { "event": "record.deleted", "eventId": "%s", "tenantId": "%s",
                  "entityId": "Purch.PurchaseOrder", "recordId": "%s",
                  "actorId": "%s", "occurredAt": "2026-08-22T00:00:00Z" }"""
                .formatted(UUID.randomUUID(), TENANT, record, CLERK)).get();

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    assertThat(jdbc.queryForObject(
                            "SELECT status FROM wf_tasks WHERE id = ?", String.class, first.id()))
                            .isEqualTo("CANCELLED");
                    assertThat(jdbc.queryForObject(
                            "SELECT status FROM wf_tasks WHERE record_id = ? AND id != ?",
                            String.class, record, first.id())).isEqualTo("CANCELLED");
                    // other records untouched
                    assertThat(jdbc.queryForObject(
                            "SELECT status FROM wf_tasks WHERE id = ?", String.class, other.id()))
                            .isEqualTo("OPEN");
                });
        // and the cancellations emitted their events
        assertThat(jdbc.queryForList(
                "SELECT event_type FROM wf_event_outbox WHERE event_type = 'task.cancelled'",
                String.class)).hasSize(2);
    }

    @Test
    @DisplayName("requestApproval suspends durably; resolution resumes the engine once (§4)")
    void suspensionAndResume() throws Exception {
        UUID record = UUID.randomUUID();
        var result = suspensions.request(TENANT, "Purch", "PurchaseOrder",
                "Purch.PurchaseOrder", record, "submit", "a1", "s2",
                "{\"id\":\"r1\",\"op\":\"transitionState\",\"params\":{\"to\":\"REJECTED\"}}",
                "Purch.manager", null, "any", CLERK);
        UUID instance = UUID.fromString(String.valueOf(result.get("instanceId")));

        // the role task exists and links the instance; the manager approves
        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("SELECT count(*) FROM wf_tasks WHERE instance_id = ?",
                        Integer.class, instance)).isEqualTo(1);
        String taskId = jdbc.queryForObject(
                "SELECT id FROM wf_tasks WHERE instance_id = ?", UUID.class, instance).toString();
        RESUMES.clear();
        mockMvc.perform(post("/api/v1/workflow/tasks/" + taskId + "/approve")
                        .with(jwtFor(MANAGER)).contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        // the engine re-entered after the step, exactly once
        org.assertj.core.api.Assertions.assertThat(RESUMES)
                .containsExactly("true:submit:s2");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT status FROM wf_suspended_flows WHERE id = ?", String.class, instance))
                .isEqualTo("RESUMED");

        // the initiating MANAGER (a role holder — access passes, SoD does not) cannot
        // resolve their own approval (§4, fail closed)
        UUID own = UUID.randomUUID();
        suspensions.request(TENANT, "Purch", "PurchaseOrder", "Purch.PurchaseOrder", own,
                "submit", "a1", "s2", null, "Purch.manager", null, "any", MANAGER);
        String ownTask = jdbc.queryForObject(
                "SELECT id FROM wf_tasks WHERE instance_id = ?", UUID.class,
                jdbc.queryForObject("SELECT id FROM wf_suspended_flows WHERE record_id = ?",
                        UUID.class, own)).toString();
        mockMvc.perform(post("/api/v1/workflow/tasks/" + ownTask + "/approve")
                        .with(jwtFor(MANAGER)).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4011"));
    }

    @Test
    @DisplayName("an explicit approver set empty after SoD rejects fail closed (§4)")
    void sodEmptySetRejects() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        suspensions.request(TENANT, "Purch", "PurchaseOrder",
                                "Purch.PurchaseOrder", UUID.randomUUID(), "submit", "a1",
                                "s2", null, null, List.of(CLERK.toString()), "all", CLERK))
                .isInstanceOf(com.novaforge.common.error.PlatformException.class)
                .hasMessageContaining("segregated");
    }

    @Test
    @DisplayName("reject routes the onReject subgraph into the engine (§4)")
    void rejectRunsOnReject() throws Exception {
        UUID record = UUID.randomUUID();
        suspensions.request(TENANT, "Purch", "PurchaseOrder", "Purch.PurchaseOrder", record,
                "submit", "a1", "s2",
                "{\"id\":\"r1\",\"op\":\"transitionState\",\"params\":{\"to\":\"REJECTED\"}}",
                "Purch.manager", null, "any", CLERK);
        String taskId = jdbc.queryForObject(
                "SELECT id FROM wf_tasks WHERE record_id = ?", UUID.class, record).toString();
        RESUMES.clear();
        mockMvc.perform(post("/api/v1/workflow/tasks/" + taskId + "/reject")
                        .with(jwtFor(MANAGER)).contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        // the verdict routes onReject; the engine ignores afterStep on reject
        org.assertj.core.api.Assertions.assertThat(RESUMES)
                .containsExactly("false:submit:s2");
    }

    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(UUID actor) {
        return jwt()
                .jwt(token -> token.claim("tenant_id", TENANT.toString()).subject(actor.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
    }
}
