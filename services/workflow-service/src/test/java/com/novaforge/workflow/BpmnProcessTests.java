package com.novaforge.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novaforge.metadata.WorkflowDefinition;
import com.novaforge.testsupport.PostgresTestBase;
import com.novaforge.workflow.process.ProcessDeployer;
import com.novaforge.workflow.process.ProcessRegistry;
import com.novaforge.workflow.process.ProcessStarts;
import com.novaforge.workflow.process.PublishedWorkflowSource;
import com.novaforge.workflow.process.RecordFieldsSource;
import com.novaforge.workflow.roles.RoleLookup;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * BPMN v1 end to end (PHASE-4 §9, §14): publish-driven deployment with content-hash
 * idempotency and the user-task gate, event-start subscriptions over the real Kafka
 * spine (filter evaluation, redelivery collapse), the §5-inbox bridge (approve
 * completes the engine task and ends the process; delegation rejects), in-engine
 * timers on the async executor, workflow removal cascading, and the internal
 * start surface's service-client gate.
 */
@SpringBootTest(properties = {"novaforge.events.relay-interval-ms=3600000",
        "novaforge.sla.scan-interval-ms=3600000", "novaforge.process.sync-interval-ms=3600000"})
@AutoConfigureMockMvc
class BpmnProcessTests extends PostgresTestBase {

    static final UUID TENANT = UUID.fromString("12121212-1212-4121-8121-121212121212");
    static final UUID CLERK = UUID.fromString("33333333-3333-4333-8333-333333333333");
    static final UUID MANAGER = UUID.fromString("77777777-7777-4777-8777-777777777777");

    static final String APP = "Purch";
    static final String ENTITY = "PurchaseOrder";

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The published apps the stub serves — tests mutate per scenario. */
    static final List<PublishedWorkflowSource.AppWorkflows> PUBLISHED =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** The record fields the stub serves for filter evaluation. */
    static final Map<String, Map<String, Object>> RECORDS = new ConcurrentHashMap<>();

    static final Map<String, List<String>> ROLES = new ConcurrentHashMap<>();

    @TestConfiguration
    static class Stubs {

        @Bean
        @Primary
        PublishedWorkflowSource workflowSource() {
            return () -> List.copyOf(PUBLISHED);
        }

        @Bean
        @Primary
        RecordFieldsSource recordFields() {
            return (tenantId, app, entity, recordId) -> RECORDS.get(recordId.toString());
        }

        @Bean
        @Primary
        RoleLookup roleLookup() {
            return (tenantId, actor) -> ROLES.getOrDefault(actor.toString(), List.of());
        }

        @Bean
        @Primary
        com.novaforge.workflow.sla.PublishedSlaSource slaSource() {
            return (tenantId, appApiName) -> List.of();
        }

        @Bean
        @Primary
        com.novaforge.workflow.runtime.ResumeClient resumeClient() {
            return resume -> {
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

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    ProcessDeployer deployer;

    @Autowired
    ProcessRegistry registry;

    @Autowired
    ProcessStarts starts;

    @Autowired
    RuntimeService runtime;

    @Autowired
    HistoryService history;

    @Autowired
    RepositoryService repository;

    @Autowired
    com.novaforge.workflow.task.TaskService tasks;

    @Autowired
    org.flowable.engine.TaskService engineTasks;

    @BeforeEach
    void reset() {
        // engine-side instances end; registry rows clear; the stub state empties
        for (var instance : runtime.createProcessInstanceQuery().list()) {
            runtime.deleteProcessInstance(instance.getId(), "test reset");
        }
        jdbc.update("DELETE FROM wf_process_tasks");
        jdbc.update("DELETE FROM wf_process_starts");
        jdbc.update("DELETE FROM wf_process_deployments");
        jdbc.update("DELETE FROM wf_tasks");
        jdbc.update("DELETE FROM wf_event_outbox");
        PUBLISHED.clear();
        RECORDS.clear();
        ROLES.clear();
        ROLES.put(MANAGER.toString(), List.of("user", "Purch.manager"));
    }

    // --- fixtures ---

    /** A review process: start → userTask (manager role) → end. */
    private static String reviewBpmn(String processId) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="novaforge">
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="review"/>
                    <userTask id="review" name="Review" flowable:candidateGroups="manager"/>
                    <sequenceFlow id="f2" sourceRef="review" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(processId);
    }

    /** A parallel review: start → fork → two user tasks → join → end. Each
     *  completion writes resolution_<its own key>; the shared `resolution` would
     *  overwrite, losing one approver's outcome. */
    private static String parallelBpmn(String processId) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="novaforge">
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="fork"/>
                    <parallelGateway id="fork"/>
                    <sequenceFlow id="f2" sourceRef="fork" targetRef="legal"/>
                    <sequenceFlow id="f3" sourceRef="fork" targetRef="finance"/>
                    <userTask id="legal" name="Legal" flowable:candidateGroups="manager"/>
                    <userTask id="finance" name="Finance" flowable:candidateGroups="manager"/>
                    <sequenceFlow id="f4" sourceRef="legal" targetRef="join"/>
                    <sequenceFlow id="f5" sourceRef="finance" targetRef="join"/>
                    <parallelGateway id="join"/>
                    <sequenceFlow id="f6" sourceRef="join" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(processId);
    }

    private static void publishApp(String workflowId, String bpmn,
                                   WorkflowDefinition.EventStart... eventStarts) {
        publishApp(APP, workflowId, bpmn, eventStarts);
    }

    /** The app-aware form (the V8 anti-regressions): workflow ids are app-scoped,
     *  so two apps may publish the same id in one tenant. */
    private static void publishApp(String app, String workflowId, String bpmn,
                                   WorkflowDefinition.EventStart... eventStarts) {
        PUBLISHED.add(new PublishedWorkflowSource.AppWorkflows(TENANT, app,
                List.of(new WorkflowDefinition(workflowId, bpmn, List.of(eventStarts)))));
    }

    /** Two sequential user tasks (start → t1 → t2 → end) — the redeploy scenario's
     *  shape: the instance sits at t1 when a new version deploys, and its LATER
     *  task must still bridge on the old definition id (V8). */
    private static String twoTaskBpmn(String processId, String firstName,
                                      String secondName) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="novaforge">
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="t1"/>
                    <userTask id="t1" name="%s" flowable:candidateGroups="manager"/>
                    <sequenceFlow id="f2" sourceRef="t1" targetRef="t2"/>
                    <userTask id="t2" name="%s" flowable:candidateGroups="manager"/>
                    <sequenceFlow id="f3" sourceRef="t2" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(processId, firstName, secondName);
    }

    private void sendRecordEvent(String eventId, String type, UUID recordId) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("event", type);
        payload.put("eventId", eventId);
        payload.put("tenantId", TENANT.toString());
        payload.put("entityId", APP + "." + ENTITY);
        payload.put("recordId", recordId.toString());
        payload.put("actorId", CLERK.toString());
        payload.put("occurredAt", "2026-08-22T00:00:00Z");
        kafka.send("novaforge.record", TENANT + ":" + recordId,
                MAPPER.writeValueAsString(payload));
    }

    private UUID inboxTaskOfInstance(String processInstanceId) {
        return jdbc.queryForObject("""
                        SELECT t.id FROM wf_tasks t JOIN wf_process_tasks p ON p.task_id = t.id
                         WHERE p.process_instance_id = ?""",
                UUID.class, processInstanceId);
    }

    @Test
    @DisplayName("deploy sync: publishes deploy by content hash; re-sync deploys nothing; change deploys a version")
    void deploySyncIdempotent() {
        publishApp("po_review", reviewBpmn("po_review"));
        deployer.syncOnce();

        ProcessRegistry.Deployment first = registry.find(TENANT, APP, "po_review").orElseThrow();
        assertThat(first.deployed()).isTrue();
        String engineDeploymentId = first.deploymentId();

        // unchanged re-sync: same registry row, same engine deployment
        deployer.syncOnce();
        ProcessRegistry.Deployment again = registry.find(TENANT, APP, "po_review").orElseThrow();
        assertThat(again.deploymentId()).isEqualTo(engineDeploymentId);

        // changed BPMN: a new engine deployment version, registry follows
        publishApp("po_review", reviewBpmn("po_review").replace("name=\"Review\"",
                "name=\"Review v2\""));
        PUBLISHED.removeFirst();   // the stale entry
        deployer.syncOnce();
        ProcessRegistry.Deployment changed = registry.find(TENANT, APP, "po_review").orElseThrow();
        assertThat(changed.deploymentId()).isNotEqualTo(engineDeploymentId);
        assertThat(repository.createProcessDefinitionQuery()
                .processDefinitionKey("po_review").processDefinitionTenantId(TENANT.toString())
                .count()).isGreaterThanOrEqualTo(2);

        // cleanup for the removal assertion below: drop the app, sync — REMOVED
        PUBLISHED.clear();
        deployer.syncOnce();
        assertThat(registry.find(TENANT, APP, "po_review").orElseThrow().status())
                .isEqualTo("REMOVED");
    }

    @Test
    @DisplayName("user-task gate: expression assignees and unassignable tasks fail the deploy audibly")
    void userTaskGate() {
        String expressionAssignee = reviewBpmn("po_expr")
                .replace("flowable:candidateGroups=\"manager\"",
                        "flowable:assignee=\"${starter}\"");
        publishApp("po_expr", expressionAssignee);
        deployer.syncOnce();

        ProcessRegistry.Deployment failed = registry.find(TENANT, APP, "po_expr").orElseThrow();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.error()).contains("expressions are not supported");
        assertThat(runtime.createProcessInstanceQuery().processDefinitionKey("po_expr")
                .count()).isZero();

        // a userTask with neither assignee nor candidate groups fails the gate too
        publishApp("po_bare", reviewBpmn("po_bare")
                .replace(" flowable:candidateGroups=\"manager\"", ""));
        deployer.syncOnce();
        assertThat(registry.find(TENANT, APP, "po_bare").orElseThrow().error())
                .contains("require a literal flowable:assignee");
    }

    @Test
    @DisplayName("event-start: matching spine event starts the process and bridges the user task into the inbox")
    void eventStartStartsAndBridges() {
        UUID record = UUID.randomUUID();
        RECORDS.put(record.toString(), Map.of("status", "SUBMITTED"));
        publishApp("po_review", reviewBpmn("po_review"),
                new WorkflowDefinition.EventStart("record.updated", ENTITY,
                        "status == 'SUBMITTED'"));
        deployer.syncOnce();

        sendRecordEvent(UUID.randomUUID().toString(), "record.updated", record);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(runtime.createProcessInstanceQuery()
                        .processDefinitionKey("po_review").count()).isEqualTo(1));
        String instanceId = runtime.createProcessInstanceQuery()
                .processDefinitionKey("po_review").singleResult().getId();

        // the user task bridged into the inbox (type todo, manager role, record context)
        UUID taskId = inboxTaskOfInstance(instanceId);
        var task = tasks.require(TENANT, taskId);
        assertThat(task.type()).isEqualTo("todo");
        assertThat(task.role()).isEqualTo(APP + ".manager");   // app-scoped role identity
        assertThat(task.recordId()).isEqualTo(record);
        assertThat(task.createdBy()).isEqualTo(CLERK);   // the event's actor rides variables

        // a distinct event on the same record is a legitimate new start — one per event
        sendRecordEvent(UUID.randomUUID().toString(), "record.updated", record);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(runtime.createProcessInstanceQuery()
                        .processDefinitionKey("po_review").count()).isEqualTo(2));
    }

    @Test
    @DisplayName("parallel completions: each approver's outcome survives its own variable")
    void parallelOutcomesDoNotCrossContaminate() throws Exception {
        // Anti-regression (2026-08-31, seventeenth pass): every bridged task wrote
        // ONE process-level `resolution` — the fork's second completion overwrote
        // the first, so the join's routing saw only the last writer and one
        // approver's outcome silently vanished.
        UUID record = UUID.randomUUID();
        RECORDS.put(record.toString(), Map.of("status", "SUBMITTED"));
        publishApp("par_review", parallelBpmn("par_review"),
                new WorkflowDefinition.EventStart("record.updated", ENTITY,
                        "status == 'SUBMITTED'"));
        deployer.syncOnce();
        sendRecordEvent(UUID.randomUUID().toString(), "record.updated", record);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM wf_process_tasks
                         WHERE workflow_id = 'par_review'""", Integer.class))
                        .isEqualTo(2));

        // two linked tasks, distinct definition keys
        var links = jdbc.queryForList("""
                SELECT t.id AS task_id, p.task_definition_key
                  FROM wf_process_tasks p JOIN wf_tasks t ON t.id = p.task_id
                 WHERE p.workflow_id = 'par_review'""");
        assertThat(links).hasSize(2);

        // approve one, reject the other: both completions write their OWN variable
        for (Map<String, Object> link : links) {
            boolean approve = "legal".equals(link.get("task_definition_key"));
            tasks.resolve(TENANT, MANAGER, (UUID) link.get("task_id"), approve, null);
        }
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(history.createHistoricProcessInstanceQuery()
                        .processDefinitionKey("par_review").finished().count()).isEqualTo(1));
        String instanceId = history.createHistoricProcessInstanceQuery()
                .processDefinitionKey("par_review").finished().singleResult().getId();
        var variables = history.createHistoricVariableInstanceQuery()
                .processInstanceId(instanceId).list();
        var byName = new java.util.HashMap<String, Object>();
        variables.forEach(v -> byName.put(v.getVariableName(), v.getValue()));
        assertThat(byName.get("resolution_legal")).isEqualTo("APPROVED");
        assertThat(byName.get("resolution_finance")).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("event-start dedupe: the same event id redelivered starts exactly one instance")
    void eventStartRedeliveryCollapses() {
        UUID record = UUID.randomUUID();
        RECORDS.put(record.toString(), Map.of("status", "SUBMITTED"));
        publishApp("po_review", reviewBpmn("po_review"),
                new WorkflowDefinition.EventStart("record.updated", ENTITY, null));
        deployer.syncOnce();

        String eventId = UUID.randomUUID().toString();
        sendRecordEvent(eventId, "record.updated", record);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(runtime.createProcessInstanceQuery()
                        .processDefinitionKey("po_review").count()).isEqualTo(1));

        // exact redelivery (at-least-once): the event id claim collapses it
        sendRecordEvent(eventId, "record.updated", record);
        await().during(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(runtime.createProcessInstanceQuery()
                        .processDefinitionKey("po_review").count()).isEqualTo(1));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM wf_process_starts WHERE event_id = ?",
                Long.class, UUID.fromString(eventId))).isEqualTo(1);
    }

    @Test
    @DisplayName("event-start filter: a non-matching record starts nothing; a gone record skips")
    void eventStartFilterSkips() {
        UUID draft = UUID.randomUUID();
        RECORDS.put(draft.toString(), Map.of("status", "DRAFT"));
        publishApp("po_review", reviewBpmn("po_review"),
                new WorkflowDefinition.EventStart("record.updated", ENTITY,
                        "status == 'SUBMITTED'"));
        deployer.syncOnce();

        sendRecordEvent(UUID.randomUUID().toString(), "record.updated", draft);
        // no record at all (deleted between event and evaluation)
        sendRecordEvent(UUID.randomUUID().toString(), "record.updated", UUID.randomUUID());
        await().during(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(runtime.createProcessInstanceQuery().count()).isZero());
        assertThat(registry.deployedWithStarts()).hasSize(1);   // deployed, just not matched
    }

    @Test
    @DisplayName("approve through the inbox completes the engine task and ends the process (§5/§9 bridge)")
    void approveEndsProcess() {
        UUID record = UUID.randomUUID();
        RECORDS.put(record.toString(), Map.of("status", "SUBMITTED"));
        publishApp("po_review", reviewBpmn("po_review"),
                new WorkflowDefinition.EventStart("record.updated", ENTITY, null));
        deployer.syncOnce();

        sendRecordEvent(UUID.randomUUID().toString(), "record.updated", record);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(runtime.createProcessInstanceQuery().count()).isEqualTo(1));
        String instanceId = runtime.createProcessInstanceQuery().singleResult().getId();
        UUID taskId = inboxTaskOfInstance(instanceId);

        tasks.resolve(TENANT, MANAGER, taskId, true, "looks good");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            HistoricProcessInstance historic = history.createHistoricProcessInstanceQuery()
                    .processInstanceId(instanceId).singleResult();
            assertThat(historic.getEndTime()).isNotNull();   // the process ran to its end
        });
        assertThat(tasks.require(TENANT, taskId).status()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("delegation of a process-managed task rejects in v1 (§9); reassign mirrors the engine assignee")
    void delegationRejected() {
        UUID record = UUID.randomUUID();
        RECORDS.put(record.toString(), Map.of());
        publishApp("po_review", reviewBpmn("po_review"),
                new WorkflowDefinition.EventStart("record.created", ENTITY, null));
        deployer.syncOnce();

        sendRecordEvent(UUID.randomUUID().toString(), "record.created", record);
        await().atMost(Duration.ofSeconds(20)).until(() ->
                !runtime.createProcessInstanceQuery().list().isEmpty());
        String instanceId = runtime.createProcessInstanceQuery().singleResult().getId();
        UUID taskId = inboxTaskOfInstance(instanceId);

        UUID other = UUID.randomUUID();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> tasks.delegate(TENANT, MANAGER, taskId, other))
                .isInstanceOf(com.novaforge.common.error.PlatformException.class)
                .hasMessageContaining("cannot be delegated");

        // claim mirrors the engine assignee
        tasks.claim(TENANT, MANAGER, taskId);
        String engineTaskId = jdbc.queryForObject(
                "SELECT engine_task_id FROM wf_process_tasks WHERE task_id = ?",
                String.class, taskId);
        assertThat(engineTasks.createTaskQuery().taskId(engineTaskId).singleResult()
                .getAssignee()).isEqualTo(MANAGER.toString());
    }

    @Test
    @DisplayName("record deletion cancels the bridged task and completes the engine task (§5 + §9)")
    void recordDeletionCancelsBridge() {
        UUID record = UUID.randomUUID();
        RECORDS.put(record.toString(), Map.of());
        publishApp("po_review", reviewBpmn("po_review"),
                new WorkflowDefinition.EventStart("record.created", ENTITY, null));
        deployer.syncOnce();

        sendRecordEvent(UUID.randomUUID().toString(), "record.created", record);
        await().atMost(Duration.ofSeconds(20)).until(() ->
                !runtime.createProcessInstanceQuery().list().isEmpty());
        String instanceId = runtime.createProcessInstanceQuery().singleResult().getId();
        UUID taskId = inboxTaskOfInstance(instanceId);

        sendRecordEvent(UUID.randomUUID().toString(), "record.deleted", record);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(tasks.require(TENANT, taskId).status()).isEqualTo("CANCELLED"));
        // the engine side completed (resolution CANCELLED) — the process ran to its end
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(history.createHistoricProcessInstanceQuery()
                    .processInstanceId(instanceId).singleResult().getEndTime()).isNotNull();
        });
    }

    @Test
    @DisplayName("in-engine timer: the async executor advances the wait, then the task lands (§9/ARCHITECTURE §2.8)")
    void timerAdvancesProcess() {
        String bpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="novaforge">
                  <process id="po_timed" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="wait"/>
                    <intermediateCatchEvent id="wait">
                      <timerEventDefinition>
                        <timeDuration>PT1S</timeDuration>
                      </timerEventDefinition>
                    </intermediateCatchEvent>
                    <sequenceFlow id="f2" sourceRef="wait" targetRef="review"/>
                    <userTask id="review" flowable:candidateGroups="manager"/>
                    <sequenceFlow id="f3" sourceRef="review" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """;
        publishApp("po_timed", bpmn);
        deployer.syncOnce();

        // started directly (the manual §9 leg): this test's subject is the
        // engine's own timer advancement, not the Kafka event-start path —
        // which the earlier suites cover — and routing through the shared
        // consumer made the budget a function of whatever the sibling contexts
        // were doing (this was the suite's one consistent flake: green isolated,
        // red in the full reactor run)
        starts.start(TENANT, APP, "po_timed", null, Map.of());

        // the instance waits at the timer; the executor moves it; the task bridges
        // (budget raised from 25 s: the acquisition cycle is pinned to 1 s in
        // FlowableEngineConfig, this budget is the load-sensitive backstop)
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM wf_process_tasks", Long.class)).isEqualTo(1));
    }

    @Test
    @DisplayName("workflow removal cascades: instances die, owned open tasks cancel, registry marks REMOVED")
    void removalCascades() {
        UUID record = UUID.randomUUID();
        RECORDS.put(record.toString(), Map.of());
        publishApp("po_review", reviewBpmn("po_review"),
                new WorkflowDefinition.EventStart("record.created", ENTITY, null));
        deployer.syncOnce();
        sendRecordEvent(UUID.randomUUID().toString(), "record.created", record);
        await().atMost(Duration.ofSeconds(20)).until(() ->
                !runtime.createProcessInstanceQuery().list().isEmpty());
        String instanceId = runtime.createProcessInstanceQuery().singleResult().getId();
        UUID taskId = inboxTaskOfInstance(instanceId);

        PUBLISHED.clear();   // the app no longer publishes the workflow
        deployer.syncOnce();

        assertThat(registry.find(TENANT, APP, "po_review").orElseThrow().status())
                .isEqualTo("REMOVED");
        assertThat(runtime.createProcessInstanceQuery().processInstanceId(instanceId)
                .count()).isZero();
        assertThat(tasks.require(TENANT, taskId).status()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("redeploy: an in-flight instance of the previous engine version keeps bridging — running instances finish on their own (§9, V8)")
    void redeployKeepsInflightInstancesBridging() {
        // Anti-regression (2026-09-03 spec review): markDeployed OVERWROTE
        // process_definition_id and the bridge resolved only that id — after a
        // changed-BPMN redeploy, an old-version instance's LATER user task found
        // no registry row, was silently never bridged, and the instance parked
        // forever with no inbox surface.
        publishApp("po_chain", twoTaskBpmn("po_chain", "First", "Second"));
        deployer.syncOnce();

        String instanceId = starts.start(TENANT, APP, "po_chain", null, Map.of());
        String v1Definition = runtime.createProcessInstanceQuery()
                .processInstanceId(instanceId).singleResult().getProcessDefinitionId();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM wf_process_tasks WHERE process_instance_id = ?""",
                        Long.class, instanceId)).isEqualTo(1));
        UUID firstTask = inboxTaskOfInstance(instanceId);

        // changed BPMN deploys v2: the registry row points at the NEW definition…
        PUBLISHED.removeFirst();
        publishApp("po_chain", twoTaskBpmn("po_chain", "First", "Second v2"));
        deployer.syncOnce();
        ProcessRegistry.Deployment row = registry.find(TENANT, APP, "po_chain").orElseThrow();
        assertThat(row.processDefinitionId()).isNotEqualTo(v1Definition);
        // …but the history keeps the v1 id — old-version instances still resolve
        assertThat(jdbc.queryForObject("""
                SELECT definition_ids @> to_jsonb(ARRAY[?::text])
                  FROM wf_process_deployments WHERE id = ?""",
                Boolean.class, v1Definition, row.id())).isTrue();

        // the in-flight v1 instance advances to its SECOND task — bridged on the
        // old definition id (this is the step that parked forever before V8)
        tasks.resolve(TENANT, MANAGER, firstTask, true, null);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM wf_process_tasks WHERE process_instance_id = ?""",
                        Long.class, instanceId)).isEqualTo(2));

        // and the instance finishes on its own version
        UUID secondTask = jdbc.queryForObject("""
                SELECT p.task_id FROM wf_process_tasks p
                 WHERE p.process_instance_id = ? AND p.task_id <> ?""",
                UUID.class, instanceId, firstTask);
        tasks.resolve(TENANT, MANAGER, secondTask, true, null);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(history.createHistoricProcessInstanceQuery()
                        .processInstanceId(instanceId).singleResult().getEndTime()).isNotNull());
    }

    @Test
    @DisplayName("removal is app-scoped: removing one app's workflow never cancels another app's same-keyed tasks (§9, V8)")
    void removalIsAppScoped() {
        // Anti-regression (2026-09-03 spec review): bridge rows carried only the
        // bare workflow id and openTasksOfWorkflow matched it unqualified — two
        // apps defining the same id in one tenant meant removing EITHER app's
        // workflow cancelled BOTH apps' same-keyed open tasks.
        String otherApp = "Invo";
        String bpmn = reviewBpmn("closeChecklist");
        publishApp(APP, "closeChecklist", bpmn);
        deployer.syncOnce();
        String purchInstance = starts.start(TENANT, APP, "closeChecklist", null, Map.of());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM wf_process_tasks WHERE app = ?""",
                        Long.class, APP)).isEqualTo(1));

        // the second app deploys the SAME workflow id — legal: ids are app-scoped
        publishApp(otherApp, "closeChecklist", bpmn);
        deployer.syncOnce();
        String invoInstance = starts.start(TENANT, otherApp, "closeChecklist", null, Map.of());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbc.queryForList("""
                        SELECT app FROM wf_process_tasks ORDER BY app""", String.class))
                        .containsExactlyInAnyOrder(APP, otherApp));
        UUID purchTask = jdbc.queryForObject("""
                SELECT task_id FROM wf_process_tasks WHERE app = ?""", UUID.class, APP);
        UUID invoTask = jdbc.queryForObject("""
                SELECT task_id FROM wf_process_tasks WHERE app = ?""", UUID.class, otherApp);

        // the Purch app stops publishing its workflow — only ITS task may cancel
        PUBLISHED.removeIf(app -> app.appApiName().equals(APP));
        deployer.syncOnce();

        assertThat(registry.find(TENANT, APP, "closeChecklist").orElseThrow().status())
                .isEqualTo("REMOVED");
        assertThat(registry.find(TENANT, otherApp, "closeChecklist").orElseThrow().deployed())
                .isTrue();
        assertThat(tasks.require(TENANT, purchTask).status()).isEqualTo("CANCELLED");
        assertThat(tasks.require(TENANT, invoTask).status()).isEqualTo("OPEN");   // untouched
        // the surviving app's instance still runs; the removed app's is cascaded
        assertThat(runtime.createProcessInstanceQuery().processInstanceId(invoInstance)
                .count()).isEqualTo(1);
        assertThat(runtime.createProcessInstanceQuery().processInstanceId(purchInstance)
                .count()).isZero();
    }

    @Test
    @DisplayName("internal start: the scheduler leg starts a deployed process; user tokens reject (service-client only)")
    void internalStartSurface() throws Exception {
        publishApp("po_review", reviewBpmn("po_review"));
        deployer.syncOnce();

        // user tokens never reach the surface (§13: service-client gate)
        mockMvc.perform(post("/api/v1/workflow/internal/processes/start")
                        .with(jwt().jwt(jwt -> jwt.claim("tenant_id", TENANT.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "tenantId", TENANT.toString(), "app", APP,
                                "process", "po_review"))))
                .andExpect(status().isForbidden());

        // the service-client path starts the process (call the service directly —
        // the token leg is the same trusted gate the approval surface uses)
        String instanceId = starts.start(TENANT, APP, "po_review", null, Map.of());
        assertThat(instanceId).isNotBlank();
        assertThat(runtime.createProcessInstanceQuery().processInstanceId(instanceId)
                .count()).isEqualTo(1);

        // an undeployed process rejects NOT_FOUND, audibly
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> starts.start(TENANT, APP, "nope", null, Map.of()))
                .isInstanceOf(com.novaforge.common.error.PlatformException.class)
                .hasMessageContaining("not deployed");
    }

    @Test
    @DisplayName("deploy failure stays audible and the service stands: structurally invalid BPMN records FAILED, retried next pass")
    void invalidBpmnFailsAudibly() {
        // valid XML, wrong shape: a <process> with no start event — Flowable's
        // deploy-time validation rejects it
        publishApp("po_broken", """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             targetNamespace="novaforge">
                  <process id="po_broken" isExecutable="true">
                    <userTask id="orphan" flowable:candidateGroups="manager"/>
                  </process>
                </definitions>
                """);
        deployer.syncOnce();
        ProcessRegistry.Deployment failed = registry.find(TENANT, APP, "po_broken").orElseThrow();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.error()).isNotBlank();

        // the next pass retries: fixing the definition deploys cleanly
        PUBLISHED.clear();
        publishApp("po_broken", reviewBpmn("po_broken"));
        deployer.syncOnce();
        assertThat(registry.find(TENANT, APP, "po_broken").orElseThrow().deployed()).isTrue();
    }
}
