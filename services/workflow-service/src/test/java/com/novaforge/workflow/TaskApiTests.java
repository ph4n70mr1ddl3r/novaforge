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
@SpringBootTest(properties = {"novaforge.events.relay-interval-ms=3600000",   // manual relay
        "novaforge.sla.scan-interval-ms=3600000"})                            // manual scan
@AutoConfigureMockMvc
class TaskApiTests extends PostgresTestBase {

    static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");

    /** The canned SLA source's definitions — swap in a test for scoped matching. */
    static final java.util.concurrent.atomic.AtomicReference<
            java.util.List<com.novaforge.metadata.SlaDefinition>> CANNED_SLAS =
            new java.util.concurrent.atomic.AtomicReference<>(java.util.List.of(
                    new com.novaforge.metadata.SlaDefinition("sla_po",
                            new com.novaforge.metadata.SlaDefinition.Scope("approval",
                                    "entity == 'Purch.PurchaseOrder'"),
                            "PT1H", 0.5,
                            new com.novaforge.metadata.SlaDefinition.OnBreach(
                                    "role:Purch.seniorManager", true))));
    static final UUID CLERK = UUID.fromString("33333333-3333-4333-8333-333333333333");
    static final UUID MANAGER = UUID.fromString("77777777-7777-4777-8777-777777777777");
    static final UUID SENIOR = UUID.fromString("88888888-8888-4888-8888-888888888888");
    static final UUID OUTSIDER = UUID.fromString("99999999-9999-4999-8999-999999999999");

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Actor → roles, per the stubbed lookup (platform + app-scoped). */
    static final Map<String, List<String>> ROLES = new ConcurrentHashMap<>();

    /** Tenant → apiName, per the stubbed tenant lookup (the scratch gate, §12). */
    static final Map<String, String> TENANT_NAMES = new ConcurrentHashMap<>();

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

    /** When set, the resume stub fails — the transient-runtime-outage stand-in. */
    static volatile boolean failResumes = false;

    @Autowired
    com.novaforge.workflow.task.SuspensionService suspensions;

    @Autowired
    com.novaforge.workflow.sla.SlaScanner slaScanner;

    @Autowired
    com.novaforge.workflow.sla.SlaResolver slaResolver;

    @Autowired
    io.micrometer.core.instrument.MeterRegistry meters;

    @Test
    @DisplayName("an escalation target no user holds keeps the task OPEN — no ghost-role wedge")
    void unheldEscalationTargetStaysOpen() throws Exception {
        // Anti-regression (2026-08-31, sixteenth pass): the replacement was addressed
        // to the raw authored role with no reachability check — a typo'd ghost (or a
        // role emptied later) produced an OPEN task no inbox ever matches, with null
        // timers so it could never breach again: the approval wedged permanently.
        var result = suspensions.request(TENANT, "Quiet3", "PurchaseOrder",
                "Quiet3.PurchaseOrder", UUID.randomUUID(), "submit", "a1", "s9", null,
                "Purch.manager", null, "any", "PT1H", "role:Purch.ghostRole", CLERK,
                "DRAFT->SUBMITTED");
        UUID instance = UUID.fromString(String.valueOf(result.get("instanceId")));
        UUID taskId = jdbc.queryForObject(
                "SELECT id FROM wf_tasks WHERE instance_id = ?", UUID.class, instance);

        jdbc.update("UPDATE wf_tasks SET warn_at = now() - interval '1 second', "
                + "due_at = now() - interval '1 second' WHERE instance_id = ?", instance);
        slaScanner.scanOnce();

        // the breach rode the spine; the task stayed OPEN and resolvable
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT status FROM wf_tasks WHERE id = ?", String.class, taskId))
                .isEqualTo("OPEN");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForList(
                "SELECT event_type FROM wf_event_outbox", String.class)).contains("sla.breach");
        mockMvc.perform(post("/api/v1/workflow/tasks/" + taskId + "/approve")
                        .with(jwtFor(MANAGER)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a notify-only breach (no escalation target) leaves the task OPEN and resolvable")
    void notifyOnlyBreachStaysOpen() throws Exception {
        // Anti-regression (2026-08-31, fifteenth pass): ESCALATED is terminal and
        // wf_tasks.resolve can never act on it — a notify-only SLA (§6's "notify"
        // branch, or a step timeout with no escalateTo) terminalized the approval and
        // wedged the suspended instance forever with no surface to resume it.
        // the canned overlay carries an escalation — swap it for a notify-only one,
        // under a distinct app so the resolver's 30 s cache serves no prior entry
        CANNED_SLAS.set(java.util.List.of(
                new com.novaforge.metadata.SlaDefinition("sla_notify_only",
                        new com.novaforge.metadata.SlaDefinition.Scope("approval",
                                "entity == 'Quiet2.PurchaseOrder'"),
                        "PT1H", 0.5,
                        new com.novaforge.metadata.SlaDefinition.OnBreach(null, true))));
        var result = suspensions.request(TENANT, "Quiet2", "PurchaseOrder",
                "Quiet2.PurchaseOrder", UUID.randomUUID(), "submit", "a1", "s9", null,
                "Purch.manager", null, "any", "PT1H", null, CLERK, "DRAFT->SUBMITTED");
        UUID instance = UUID.fromString(String.valueOf(result.get("instanceId")));
        UUID taskId = jdbc.queryForObject(
                "SELECT id FROM wf_tasks WHERE instance_id = ?", UUID.class, instance);

        // expire the timer; the breach rides the spine but the task stays OPEN
        jdbc.update("UPDATE wf_tasks SET warn_at = now() - interval '1 second', "
                + "due_at = now() - interval '1 second' WHERE instance_id = ?", instance);
        slaScanner.scanOnce();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT status FROM wf_tasks WHERE id = ?", String.class, taskId))
                .isEqualTo("OPEN");
        List<String> events = jdbc.queryForList(
                "SELECT event_type FROM wf_event_outbox", String.class);
        org.assertj.core.api.Assertions.assertThat(events).contains("sla.breach");
        org.assertj.core.api.Assertions.assertThat(events).doesNotContain("task.escalated");

        // and the approval still resolves — the record unwedges
        mockMvc.perform(post("/api/v1/workflow/tasks/" + taskId + "/approve")
                        .with(jwtFor(MANAGER)))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT status FROM wf_tasks WHERE id = ?", String.class, taskId))
                .isEqualTo("APPROVED");
    }

    @TestConfiguration
    static class StubRoles {

        @Bean
        @Primary
        RoleLookup roleLookup() {
            return new RoleLookup() {
                @Override
                public List<String> of(UUID tenantId, UUID actor) {
                    return ROLES.getOrDefault(actor.toString(), List.of());
                }

                @Override
                public List<UUID> holdersOf(UUID tenantId, String role) {
                    // inverted from the ROLES map: the escalation-target fence reads
                    // exactly what the inbox would match
                    return ROLES.entrySet().stream()
                            .filter(entry -> entry.getValue().contains(role))
                            .map(entry -> UUID.fromString(entry.getKey()))
                            .toList();
                }
            };
        }

        /** Tenant names — the scratch gate's stand-in for the runtime admin read. */
        @Bean
        @Primary
        com.novaforge.workflow.tenants.TenantLookup tenantLookup() {
            return new com.novaforge.workflow.tenants.TenantLookup() {
                @Override
                public String apiNameOf(UUID tenantId) {
                    return TENANT_NAMES.get(tenantId.toString());
                }
            };
        }

        @Bean
        @Primary
        com.novaforge.workflow.runtime.ResumeClient resumeClient() {
            return resume -> {
                if (failResumes) {
                    throw new com.novaforge.common.error.PlatformException(
                            com.novaforge.common.error.PlatformErrorCode.INTERNAL,
                            "runtime unreachable (transient)");
                }
                RESUMES.add(resume.approved() + ":" + resume.hook() + ":"
                        + (resume.afterStep() == null ? "-" : resume.afterStep()));
            };
        }

        /** Canned SLA definitions — the governed overlay of §6's precedence. */
        @Bean
        @Primary
        com.novaforge.workflow.sla.PublishedSlaSource slaSource() {
            return (tenantId, appApiName) -> CANNED_SLAS.get();
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
        failResumes = false;
        ROLES.clear();
        ROLES.put(CLERK.toString(), List.of("user"));
        ROLES.put(MANAGER.toString(), List.of("user", "Purch.manager"));
        ROLES.put(SENIOR.toString(), List.of("user", "Purch.seniorManager", "Purch.manager", "builder"));
        ROLES.put(OUTSIDER.toString(), List.of("user"));
        TENANT_NAMES.clear();
        TENANT_NAMES.put(TENANT.toString(), "scratch-run");   // the harness's naming
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
    @DisplayName("the inbox sorts per the Phase 1 conventions: createdAt default, dueAt desc over the allowlist (§5)")
    void inboxSorts() throws Exception {
        // three tasks with distinct due dates; creation order is seed order
        java.time.Instant base = java.time.Instant.now();
        UUID first = tasks.create(TENANT, "approval", "Purch.PurchaseOrder",
                UUID.randomUUID(), MANAGER, null, base.plusSeconds(3_000), null, CLERK, null).id();
        UUID second = tasks.create(TENANT, "approval", "Purch.PurchaseOrder",
                UUID.randomUUID(), MANAGER, null, base.plusSeconds(1_000), null, CLERK, null).id();
        UUID third = tasks.create(TENANT, "todo", "Purch.PurchaseOrder",
                UUID.randomUUID(), MANAGER, null, base.plusSeconds(2_000), null, CLERK, null).id();

        // default: oldest first (creation order)
        MvcResult defaultOrder = mockMvc.perform(get("/api/v1/workflow/tasks").with(jwtFor(MANAGER)))
                .andExpect(status().isOk()).andReturn();
        assertThat(idsOf(defaultOrder)).containsExactly(first.toString(), second.toString(),
                third.toString());

        // sort=dueAt&dir=desc: the nearest deadline last
        MvcResult byDue = mockMvc.perform(get("/api/v1/workflow/tasks")
                        .queryParam("sort", "dueAt").queryParam("dir", "desc")
                        .with(jwtFor(MANAGER)))
                .andExpect(status().isOk()).andReturn();
        assertThat(idsOf(byDue)).containsExactly(first.toString(), third.toString(),
                second.toString());

        // an unknown sort field falls back to the default ordering (the inbox keeps serving)
        MvcResult unknown = mockMvc.perform(get("/api/v1/workflow/tasks")
                        .queryParam("sort", "nope").with(jwtFor(MANAGER)))
                .andExpect(status().isOk()).andReturn();
        assertThat(idsOf(unknown)).containsExactly(first.toString(), second.toString(),
                third.toString());
    }

    private static List<String> idsOf(MvcResult result) throws Exception {
        List<String> ids = new java.util.ArrayList<>();
        MAPPER.readTree(result.getResponse().getContentAsString()).get("rows")
                .forEach(row -> ids.add(row.get("id").asString()));
        return ids;
    }

    @Test
    @DisplayName("over-limit page sizes reject, never silently clamp (PHASE-1 §5's convention via §5)")
    void inboxRejectsOverLimitPaging() throws Exception {
        // 201 is over the 200 cap: the request rejects with VALIDATION_FAILED problem+json
        mockMvc.perform(get("/api/v1/workflow/tasks").queryParam("size", "201")
                        .with(jwtFor(MANAGER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value("4000"));
        mockMvc.perform(get("/api/v1/workflow/tasks").queryParam("size", "0")
                        .with(jwtFor(MANAGER)))
                .andExpect(status().isBadRequest());
        // the boundary itself serves
        mockMvc.perform(get("/api/v1/workflow/tasks").queryParam("size", "200")
                        .with(jwtFor(MANAGER)))
                .andExpect(status().isOk());
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
    @DisplayName("claim is a CAS: a second claimer 409s, a claimed task cannot be stolen")
    void claimIsCasOnAssignee() throws Exception {
        // Anti-regression (2026-08-31, fifteenth pass): the claim UPDATE matched any
        // OPEN role task — concurrent claims both succeeded (last-writer-wins) and a
        // later role holder could silently steal an assigned or delegated task.
        var task = tasks.create(TENANT, "approval", "Purch.PurchaseOrder",
                UUID.randomUUID(), null, "Purch.manager", null, null, CLERK, null);
        mockMvc.perform(post("/api/v1/workflow/tasks/" + task.id() + "/claim")
                        .with(jwtFor(MANAGER)))
                .andExpect(status().isOk());
        // a second holder of the same role cannot take it
        mockMvc.perform(post("/api/v1/workflow/tasks/" + task.id() + "/claim")
                        .with(jwtFor(SENIOR)))
                .andExpect(status().isConflict());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT assignee FROM wf_tasks WHERE id = ?", UUID.class, task.id()))
                .isEqualTo(MANAGER);
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
    @DisplayName("delegation keeps the escalation target — a delegated approval still escalates at breach (§6)")
    void delegationKeepsEscalationTarget() throws Exception {
        var task = tasks.create(TENANT, "approval", "Purch.PurchaseOrder",
                UUID.randomUUID(), MANAGER, null, null, null, CLERK, null, null,
                "Purch.seniorManager");

        MvcResult delegated = mockMvc.perform(post("/api/v1/workflow/tasks/" + task.id()
                        + "/delegate").with(jwtFor(MANAGER)).contentType("application/json")
                        .content("{\"toUser\":\"" + SENIOR + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String replacementId = MAPPER.readTree(delegated.getResponse().getContentAsString())
                .get("id").asString();

        // the replacement carries the chain's escalation role — the convenience
        // constructor once nulled it, and a delegated approval that can never
        // escalate wedges the record at breach
        assertThat(jdbc.queryForObject(
                "SELECT escalate_to FROM wf_tasks WHERE id = ?", String.class,
                UUID.fromString(replacementId))).isEqualTo("Purch.seniorManager");
        // …and its warned state rides too: no second sla.warn for the same window
        assertThat(jdbc.queryForObject(
                "SELECT sla_warned FROM wf_tasks WHERE id = ?", Boolean.class,
                UUID.fromString(replacementId))).isEqualTo(false);
    }

    @Test
    @DisplayName("an any-mode resolution supersedes its losing siblings — no phantom breach later (§4/§6)")
    void anyModeResolutionSupersedesSiblings() throws Exception {
        var result = suspensions.request(TENANT, "Purch", "PurchaseOrder",
                "Purch.PurchaseOrder", UUID.randomUUID(), "submit", "a1", "s2", null,
                null, List.of(MANAGER.toString(), SENIOR.toString()), "any",
                "PT2H", null, CLERK, null);
        UUID instance = UUID.fromString(String.valueOf(result.get("instanceId")));
        List<UUID> ids = jdbc.queryForList(
                "SELECT id FROM wf_tasks WHERE instance_id = ? ORDER BY created_at",
                UUID.class, instance);
        assertThat(ids).hasSize(2);

        // the first resolution wins and resumes…
        mockMvc.perform(post("/api/v1/workflow/tasks/" + ids.get(0) + "/approve")
                        .with(jwtFor(MANAGER)).contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "SELECT status FROM wf_suspended_flows WHERE id = ?", String.class, instance))
                .isEqualTo("RESUMED");
        // …and the losing sibling leaves the open set: an OPEN loser would sit past
        // its dueAt, "breach", and escalate an already-resolved approval
        assertThat(jdbc.queryForObject(
                "SELECT status FROM wf_tasks WHERE id = ?", String.class, ids.get(1)))
                .isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("a failed resume rolls the whole resolution back — the approval stays open and retryable (§4)")
    void failedResumeRollsBackAndStaysOpen() throws Exception {
        UUID record = UUID.randomUUID();
        var result = suspensions.request(TENANT, "Purch", "PurchaseOrder",
                "Purch.PurchaseOrder", record, "submit", "a1", "s2", null,
                "Purch.manager", null, "any", null, null, CLERK, null);
        UUID instance = UUID.fromString(String.valueOf(result.get("instanceId")));
        UUID taskId = jdbc.queryForObject(
                "SELECT id FROM wf_tasks WHERE instance_id = ?", UUID.class, instance);

        // the runtime is unreachable: the approve fails loudly…
        failResumes = true;
        RESUMES.clear();
        mockMvc.perform(post("/api/v1/workflow/tasks/" + taskId + "/approve")
                        .with(jwtFor(MANAGER)).contentType("application/json").content("{}"))
                .andExpect(status().is5xxServerError());
        assertThat(RESUMES).isEmpty();
        // …and nothing was consumed: the task stays OPEN (not terminal-and-dead),
        // the instance stays SUSPENDED — once this parked FAILED with the task
        // already APPROVED, the record was stuck mid-approval forever
        assertThat(jdbc.queryForObject(
                "SELECT status FROM wf_tasks WHERE id = ?", String.class, taskId))
                .isEqualTo("OPEN");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM wf_suspended_flows WHERE id = ?", String.class, instance))
                .isEqualTo("SUSPENDED");

        // the runtime heals: the same approval retries and completes
        failResumes = false;
        mockMvc.perform(post("/api/v1/workflow/tasks/" + taskId + "/approve")
                        .with(jwtFor(MANAGER)).contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        assertThat(RESUMES).containsExactly("true:submit:s2");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM wf_suspended_flows WHERE id = ?", String.class, instance))
                .isEqualTo("RESUMED");
    }

    @Test
    @DisplayName("the task read carries the same access rule as the mutations (§13)")
    void taskReadEnforcesAccess() throws Exception {
        var task = tasks.create(TENANT, "approval", "Purch.PurchaseOrder",
                UUID.randomUUID(), MANAGER, null, null, null, CLERK, null);
        // the assignee reads it…
        mockMvc.perform(get("/api/v1/workflow/tasks/" + task.id()).with(jwtFor(MANAGER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
        // …an unrelated tenant user cannot — a task id is not a grant
        mockMvc.perform(get("/api/v1/workflow/tasks/" + task.id()).with(jwtFor(CLERK)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("warnAt: null disables the warn timer; an absent warnAt authors the 0.8 default (§6)")
    void warnAtNullDisablesWarnTimer() {
        // an explicit null warnAt — the disable — resolves to no warn timer at all
        CANNED_SLAS.set(java.util.List.of(
                new com.novaforge.metadata.SlaDefinition("sla_nowarn",
                        new com.novaforge.metadata.SlaDefinition.Scope("approval", null),
                        "PT4H", null, null)));
        try {
            // "Ops" — a fresh resolver-cache key: the 30 s entry would serve the
            // class-default canned SLAs otherwise
            var timers = slaResolver.resolve(TENANT, "Ops", "Ops.Order",
                    "approval", null, null, java.time.Instant.now());
            assertThat(timers.warnAt()).isNull();
            assertThat(timers.dueAt()).isNotNull();
        } finally {
            CANNED_SLAS.set(java.util.List.of(
                    new com.novaforge.metadata.SlaDefinition("sla_po",
                            new com.novaforge.metadata.SlaDefinition.Scope("approval",
                                    "entity == 'Purch.PurchaseOrder'"),
                            "PT1H", 0.5,
                            new com.novaforge.metadata.SlaDefinition.OnBreach(
                                    "role:Purch.seniorManager", true))));
        }
    }

    @Test
    @DisplayName("delegation to an unreachable target rejects — a ghost UUID is not an assignee (§5)")
    void delegationValidatesTarget() throws Exception {
        var task = tasks.create(TENANT, "approval", "Purch.PurchaseOrder",
                UUID.randomUUID(), MANAGER, null, null, null, CLERK, null);
        mockMvc.perform(post("/api/v1/workflow/tasks/" + task.id() + "/delegate")
                        .with(jwtFor(MANAGER)).contentType("application/json")
                        .content("{\"toUser\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("4000"));
        // nothing landed — the original alone; no OPEN row for a nobody's inbox
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM wf_tasks", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM wf_tasks WHERE id = ?", String.class, task.id()))
                .isEqualTo("OPEN");
    }

    @Test
    @DisplayName("onBreach.notify: false rides the task and the breach event — escalation without the fan-out (§6)")
    void onBreachNotifyFalseEscalatesQuietly() throws Exception {
        CANNED_SLAS.set(java.util.List.of(
                new com.novaforge.metadata.SlaDefinition("sla_quiet",
                        new com.novaforge.metadata.SlaDefinition.Scope("approval", null),
                        "PT1H", null,
                        new com.novaforge.metadata.SlaDefinition.OnBreach(
                                "role:Purch.seniorManager", false))));
        try {
            // "Quiet" — a fresh resolver-cache key (the 30 s entry serves prior tests)
            var result = suspensions.request(TENANT, "Quiet", "Order", "Quiet.Order",
                    UUID.randomUUID(), "submit", "a1", "s2", null,
                    "Quiet.manager", null, "any", null, null, CLERK, null);
            UUID instance = UUID.fromString(String.valueOf(result.get("instanceId")));
            UUID taskId = jdbc.queryForObject(
                    "SELECT id FROM wf_tasks WHERE instance_id = ?", UUID.class, instance);
            // the authored switch rode the task
            assertThat(jdbc.queryForObject(
                    "SELECT notify_on FROM wf_tasks WHERE id = ?", Boolean.class, taskId))
                    .isFalse();

            jdbc.update("UPDATE wf_tasks SET due_at = now() - interval '1 second', "
                    + "warn_at = NULL WHERE id = ?", taskId);
            slaScanner.scanOnce();

            // the escalation still happened: ESCALATED + the senior-manager replacement
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM wf_tasks WHERE id = ?", String.class, taskId))
                    .isEqualTo("ESCALATED");
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM wf_tasks WHERE role = 'Purch.seniorManager' "
                            + "AND status = 'OPEN'", Integer.class)).isEqualTo(1);
            // …and the breach event carries the quiet flag for the Notification Service
            assertThat(jdbc.queryForObject("""
                    SELECT payload->>'notify' FROM wf_event_outbox
                     WHERE event_type = 'sla.breach'""", String.class)).isEqualTo("false");
        } finally {
            CANNED_SLAS.set(java.util.List.of(
                    new com.novaforge.metadata.SlaDefinition("sla_po",
                            new com.novaforge.metadata.SlaDefinition.Scope("approval",
                                    "entity == 'Purch.PurchaseOrder'"),
                            "PT1H", 0.5,
                            new com.novaforge.metadata.SlaDefinition.OnBreach(
                                    "role:Purch.seniorManager", true))));
        }
    }

    @Test
    @DisplayName("outbox retention: published rows older than the window leave; fresh and unpublished stay")
    void outboxRetentionDropsOldPublishedRows() {
        UUID task = UUID.randomUUID();
        for (int i = 0; i < 2; i++) {
            jdbc.update("""
                    INSERT INTO wf_event_outbox (id, tenant_id, task_id, event_type, payload)
                    VALUES (?, ?, ?, 'task.approved', '{}'::jsonb)""",
                    UUID.randomUUID(), TENANT, task);
        }
        jdbc.update("UPDATE wf_event_outbox SET published_at = now() - interval '30 days'");
        jdbc.update("""
                INSERT INTO wf_event_outbox (id, tenant_id, task_id, event_type, payload, published_at)
                VALUES (?, ?, ?, 'task.approved', '{}'::jsonb, now())""",
                UUID.randomUUID(), TENANT, task);
        jdbc.update("""
                INSERT INTO wf_event_outbox (id, tenant_id, task_id, event_type, payload)
                VALUES (?, ?, ?, 'task.approved', '{}'::jsonb)""",
                UUID.randomUUID(), TENANT, task);

        relay.retain();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM wf_event_outbox", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM wf_event_outbox WHERE published_at IS NULL",
                Integer.class)).isEqualTo(1);
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
                "Purch.manager", null, "any", null, null, CLERK, "DRAFT->SUBMITTED");
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
                "submit", "a1", "s2", null, "Purch.manager", null, "any", null, null, MANAGER, null);
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
                                "s2", null, null, List.of(CLERK.toString()), "all", null, null, CLERK, null))
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
                "Purch.manager", null, "any", null, null, CLERK, "SUBMITTED->APPROVED");
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

    @Test
    @DisplayName("SLAs: definition overrides the step timeout; warn once, breach escalates (§6)")
    void slaPrecedenceWarnAndBreach() throws Exception {
        // the step asked for PT2H + its own escalateTo — the matching SLA (PT1H,
        // warnAt 0.5, senior escalation) governs instead
        var result = suspensions.request(TENANT, "Purch", "PurchaseOrder",
                "Purch.PurchaseOrder", UUID.randomUUID(), "submit", "a1", "s2", null,
                "Purch.manager", null, "any", "PT2H", "Purch.stepEscalator", CLERK, "DRAFT->SUBMITTED");
        UUID instance = UUID.fromString(String.valueOf(result.get("instanceId")));
        Map<String, Object> task = jdbc.queryForMap(
                "SELECT id, warn_at, due_at, escalate_to FROM wf_tasks WHERE instance_id = ?",
                instance);
        long dueIn = ((java.sql.Timestamp) task.get("due_at")).getTime()
                - System.currentTimeMillis();
        org.assertj.core.api.Assertions.assertThat(dueIn)
                .isBetween(55L * 60 * 1000 - 5000, 65L * 60 * 1000);
        org.assertj.core.api.Assertions.assertThat(task.get("escalate_to"))
                .isEqualTo("Purch.seniorManager");

        // expire both timers; one scan warns once and breaches with a replacement
        jdbc.update("UPDATE wf_tasks SET warn_at = now() - interval '1 second', "
                + "due_at = now() - interval '1 second' WHERE instance_id = ?", instance);
        double before = meters.counter("novaforge.sla.breach",
                "app", "Purch").count();
        double warnBefore = meters.counter("novaforge.sla.warn",
                "app", "Purch").count();
        slaScanner.scanOnce();

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT status FROM wf_tasks WHERE id = ?", String.class, task.get("id")))
                .isEqualTo("ESCALATED");
        Map<String, Object> replacement = jdbc.queryForMap(
                "SELECT role, status, instance_id FROM wf_tasks "
                        + "WHERE role = 'Purch.seniorManager'");
        org.assertj.core.api.Assertions.assertThat(replacement.get("status"))
                .isEqualTo("OPEN");
        org.assertj.core.api.Assertions.assertThat(replacement.get("instance_id"))
                .isEqualTo(instance);
        List<String> events = jdbc.queryForList(
                "SELECT event_type FROM wf_event_outbox ORDER BY created_at", String.class);
        org.assertj.core.api.Assertions.assertThat(events).containsSubsequence(
                "task.created", "sla.warn", "task.escalated", "sla.breach");
        org.assertj.core.api.Assertions.assertThat(
                meters.counter("novaforge.sla.breach", "app", "Purch").count())
                .isGreaterThan(before);
        // §6: warn/breach counters are labeled per app and feed the Grafana baseline
        org.assertj.core.api.Assertions.assertThat(
                meters.counter("novaforge.sla.warn", "app", "Purch").count())
                .isGreaterThan(warnBefore);
        org.assertj.core.api.Assertions.assertThat(
                meters.counter("novaforge.sla.breach", "app", "Purch").count())
                .isGreaterThanOrEqualTo(1.0);

        // a second pass warns and escalates nothing further
        jdbc.update("DELETE FROM wf_event_outbox");
        slaScanner.scanOnce();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForList(
                "SELECT event_type FROM wf_event_outbox", String.class)).isEmpty();
    }

    @Test
    @DisplayName("no SLA and no step timeout: no dueAt, no timer — open until resolved (§6)")
    void noTimersWhenNothingGoverns() {
        var result = suspensions.request(TENANT, "Purch", "Other",
                "Purch.Other", UUID.randomUUID(), "hook", "a1", null, null,
                "Purch.manager", null, "any", null, null, CLERK, null);
        Map<String, Object> task = jdbc.queryForMap(
                "SELECT due_at, warn_at FROM wf_tasks WHERE instance_id = ?",
                UUID.fromString(String.valueOf(result.get("instanceId"))));
        org.assertj.core.api.Assertions.assertThat(task.get("due_at")).isNull();
        org.assertj.core.api.Assertions.assertThat(task.get("warn_at")).isNull();
    }

    @Test
    @DisplayName("§6/Annex A: a transition-scoped SLA matches only its edge — the "
            + "spec example's 'DRAFT->SUBMITTED' binding (empty when no state changed)")
    void transitionScopedSlaMatchesOnlyItsEdge() {
        try {
            CANNED_SLAS.set(java.util.List.of(
                    new com.novaforge.metadata.SlaDefinition("sla_submit_only",
                            new com.novaforge.metadata.SlaDefinition.Scope("approval",
                                    "entity == 'Purch.PurchaseOrder' "
                                            + "&& transition == 'DRAFT->SUBMITTED'"),
                            "PT1H", null, null)));
            // the triggering write's edge carried — the definition governs (fresh app
            // apiNames sidestep the resolver's 30s definition cache per test phase)
            var matched = suspensions.request(TENANT, "PurchT1", "PurchaseOrder",
                    "Purch.PurchaseOrder", UUID.randomUUID(), "submit", "a1", "s2", null,
                    "Purch.manager", null, "any", null, null, CLERK, "DRAFT->SUBMITTED");
            java.time.Instant due = jdbc.queryForObject(
                    "SELECT due_at FROM wf_tasks WHERE instance_id = ?",
                    java.sql.Timestamp.class,
                    UUID.fromString(String.valueOf(matched.get("instanceId")))).toInstant();
            org.assertj.core.api.Assertions.assertThat(
                    java.time.Duration.between(java.time.Instant.now(), due).toMinutes())
                    .isBetween(55L, 65L);

            // a different edge (or no state change at all): no match, no step timeout —
            // §6's no-timer path, the SLA stays dormant
            String[] apps = {"PurchT2", "PurchT3"};
            String[] others = {"SUBMITTED->APPROVED", null};
            for (int i = 0; i < apps.length; i++) {
                var unmatched = suspensions.request(TENANT, apps[i], "PurchaseOrder",
                        "Purch.PurchaseOrder", UUID.randomUUID(), "submit", "a1", "s2", null,
                        "Purch.manager", null, "any", null, null, CLERK, others[i]);
                org.assertj.core.api.Assertions.assertThat(
                                jdbc.queryForObject("SELECT count(*) FROM wf_tasks "
                                                + "WHERE instance_id = ? AND due_at IS NOT NULL", Integer.class,
                                        UUID.fromString(String.valueOf(unmatched.get("instanceId")))))
                        .isZero();
            }
        } finally {
            CANNED_SLAS.set(java.util.List.of(
                    new com.novaforge.metadata.SlaDefinition("sla_po",
                            new com.novaforge.metadata.SlaDefinition.Scope("approval",
                                    "entity == 'Purch.PurchaseOrder'"),
                            "PT1H", 0.5,
                            new com.novaforge.metadata.SlaDefinition.OnBreach(
                                    "role:Purch.seniorManager", true))));
        }
    }

    @Test
    @DisplayName("§12 clock leg: the scratch as-of scan warns then breaches deterministically — "
            + "gated twice, never wall clock")
    void scratchScanDrivesSlaClock() throws Exception {
        // a governed task: the canned SLA (PT1H target, warnAt 0.5 → warn at 30m,
        // senior escalation) — timers are in the future relative to wall clock
        var result = suspensions.request(TENANT, "Purch", "PurchaseOrder",
                "Purch.PurchaseOrder", UUID.randomUUID(), "submit", "a1", "s2", null,
                "Purch.manager", null, "any", "PT2H", "Purch.stepEscalator", CLERK, "DRAFT->SUBMITTED");
        UUID instance = UUID.fromString(String.valueOf(result.get("instanceId")));
        UUID task = jdbc.queryForObject("SELECT id FROM wf_tasks WHERE instance_id = ?",
                UUID.class, instance);

        // gate 1 — service client only: a user JWT cannot drive the clock
        mockMvc.perform(post("/api/v1/workflow/internal/sla/scan")
                        .with(jwtFor(MANAGER)).contentType("application/json")
                        .content("{\"tenantId\":\"" + TENANT + "\",\"advance\":\"PT50M\"}"))
                .andExpect(status().isForbidden());
        // gate 2 — scratch tenants only: a named production tenant answers 403
        UUID prod = UUID.randomUUID();
        TENANT_NAMES.put(prod.toString(), "acme-prod");
        mockMvc.perform(post("/api/v1/workflow/internal/sla/scan")
                        .with(serviceClient()).contentType("application/json")
                        .content("{\"tenantId\":\"" + prod + "\",\"advance\":\"PT50M\"}"))
                .andExpect(status().isForbidden());
        // authoring errors: neither instant, or both
        mockMvc.perform(post("/api/v1/workflow/internal/sla/scan")
                        .with(serviceClient()).contentType("application/json")
                        .content("{\"tenantId\":\"" + TENANT + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/workflow/internal/sla/scan")
                        .with(serviceClient()).contentType("application/json")
                        .content("{\"tenantId\":\"" + TENANT
                                + "\",\"advance\":\"PT50M\",\"asOf\":\"2026-08-24T00:00:00Z\"}"))
                .andExpect(status().isBadRequest());

        // the deterministic warn: 50m past now passes the 30m warn line, not the 1h
        // breach line — the wall-clock scanner stays out of it (scan-interval parked)
        mockMvc.perform(post("/api/v1/workflow/internal/sla/scan")
                        .with(serviceClient()).contentType("application/json")
                        .content("{\"tenantId\":\"" + TENANT + "\",\"advance\":\"PT50M\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanned").value(true))
                .andExpect(jsonPath("$.warned").value(1))
                .andExpect(jsonPath("$.breached").value(0));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM wf_tasks WHERE id = ?", String.class, task))
                .isEqualTo("OPEN");
        assertThat(jdbc.queryForList(
                "SELECT event_type FROM wf_event_outbox ORDER BY created_at", String.class))
                .contains("sla.warn");
        // the warn state is visible on the task — the warn leg's assertable surface
        mockMvc.perform(get("/api/v1/workflow/tasks/" + task).with(jwtFor(MANAGER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sla.warned").value(true))
                .andExpect(jsonPath("$.status").value("OPEN"));

        // the deterministic breach: 2h past now passes the 1h line — ESCALATED, a
        // replacement for the senior role, warn-once holds (no second sla.warn)
        mockMvc.perform(post("/api/v1/workflow/internal/sla/scan")
                        .with(serviceClient()).contentType("application/json")
                        .content("{\"tenantId\":\"" + TENANT + "\",\"advance\":\"PT2H\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warned").value(0))
                .andExpect(jsonPath("$.breached").value(1));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM wf_tasks WHERE id = ?", String.class, task))
                .isEqualTo("ESCALATED");
        Map<String, Object> replacement = jdbc.queryForMap(
                "SELECT role, status, instance_id FROM wf_tasks "
                        + "WHERE role = 'Purch.seniorManager'");
        assertThat(replacement.get("status")).isEqualTo("OPEN");
        assertThat(replacement.get("instance_id")).isEqualTo(instance);
        assertThat(jdbc.queryForList(
                "SELECT event_type FROM wf_event_outbox ORDER BY created_at", String.class))
                .containsSubsequence("sla.warn", "task.escalated", "sla.breach");

        // idempotent replay: the same governing instant escalates nothing further
        mockMvc.perform(post("/api/v1/workflow/internal/sla/scan")
                        .with(serviceClient()).contentType("application/json")
                        .content("{\"tenantId\":\"" + TENANT + "\",\"asOf\":\"2999-01-01T00:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warned").value(0))
                .andExpect(jsonPath("$.breached").value(0));
    }

    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor serviceClient() {
        return jwt().jwt(token -> token.claim("azp", "novaforge-runtime"))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
    }

    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(UUID actor) {
        return jwt()
                .jwt(token -> token.claim("tenant_id", TENANT.toString()).subject(actor.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
    }
}
