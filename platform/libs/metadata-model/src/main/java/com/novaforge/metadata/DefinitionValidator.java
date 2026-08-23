package com.novaforge.metadata;

import com.novaforge.common.error.ProblemErrors;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Schema-v0 save validation for app definitions (PHASE-1 §3): the referential rules
 * the JSON Schema cannot express. Runs on every draft save and on publish; invalid
 * definitions are rejected with {@code VALIDATION_FAILED} carrying field-scoped
 * problems.
 */
public final class DefinitionValidator {

    /** Entity apiNames: PascalCase (ARCHITECTURE.md §3). */
    public static final Pattern PASCAL_CASE = Pattern.compile("^[A-Z][A-Za-z0-9]*$");

    /** Field/relationship/sequence apiNames: camelCase. */
    public static final Pattern CAMEL_CASE = Pattern.compile("^[a-z][A-Za-z0-9]*$");

    /** State names (PHASE-4 §3) — the ERP convention (DRAFT, SUBMITTED, POSTED). */
    public static final Pattern UPPER_SNAKE = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    /** One cron item: {@code * | n | n-m | n/m | name} (a structural save check). */
    private static final Pattern CRON_ITEM =
            Pattern.compile("^(?:\\*|\\d+(?:-\\d+)?|\\d+/\\d+|[A-Za-z]+)$");

    /**
     * Cron shape for scheduled jobs (PHASE-4 §7): five or six space-separated fields,
     * each a comma list of {@link #CRON_ITEM}s — a structural check; the Scheduler's
     * cron parser stays the final authority.
     */
    private static boolean cronShaped(String cron) {
        if (cron == null) {
            return false;
        }
        String[] fields = cron.trim().split("\\s+");
        if (fields.length != 5 && fields.length != 6) {
            return false;
        }
        for (String field : fields) {
            for (String item : field.split(",")) {
                if (!CRON_ITEM.matcher(item).matches()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** System field names the record API exposes; authored fields must not shadow them. */
    public static final Set<String> RESERVED_FIELD_NAMES = Set.of(
            "id", "version", "createdAt", "updatedAt", "createdBy", "updatedBy", "deleted");

    private DefinitionValidator() {
    }

    /** Validates the whole app; returns empty when the definition is save-clean. */
    public static ProblemErrors validate(AppDefinition app) {
        List<ProblemErrors.FieldError> errors = new ArrayList<>();
        List<ProblemErrors.GlobalError> globals = new ArrayList<>();

        if (app == null) {
            return ProblemErrors.of(new ProblemErrors.GlobalError("app", "definition must not be null"));
        }
        if (app.apiName() == null || !PASCAL_CASE.matcher(app.apiName()).matches()) {
            errors.add(field("apiName", "app apiName must be PascalCase (e.g. Erp)", app.apiName()));
        }

        Set<String> entityNames = new HashSet<>();
        for (EntityDefinition entity : app.entities()) {
            if (entity.apiName() == null || !PASCAL_CASE.matcher(entity.apiName()).matches()) {
                errors.add(field("apiName", "entity apiName must be PascalCase", entity.apiName()));
                continue;
            }
            if (!entityNames.add(entity.apiName())) {
                errors.add(field("entities", "entity apiName must be unique per app: " + entity.apiName(),
                        entity.apiName()));
            }
        }

        for (EntityDefinition entity : app.entities()) {
            validateEntity(app, entity, errors);
        }
        validateStateMachines(app, errors);
        validateSlas(app, errors);
        validateSharingRules(app, errors);
        validateWorkflows(app, errors);
        validateJobs(app, errors);
        validateReports(app, errors);
        validateDashboards(app, errors);
        validatePermissionSet(app, errors);
        return new ProblemErrors(errors, globals);
    }

    /**
     * SLA rules (PHASE-4 §6): the target parses as an ISO-8601 duration, warnAt is a
     * fraction in (0, 1] or null (disabled), taskType is a known task type, and the
     * escalation target is shaped like a role reference. Match expressions compile at
     * publish (the FlowCompiler rides the same engine).
     */
    private static void validateSlas(AppDefinition app, List<ProblemErrors.FieldError> errors) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (SlaDefinition sla : app.slas()) {
            String scope = sla.id() == null ? "sla" : sla.id();
            if (sla.id() != null && !ids.add(sla.id())) {
                errors.add(field("slas", "SLA ids must be unique per app: " + sla.id(),
                        sla.id()));
            }
            if (sla.target() == null) {
                errors.add(field("slas." + scope + ".target",
                        "SLA target is a required ISO-8601 duration (e.g. PT24H)", null));
            } else {
                try {
                    java.time.Duration.parse(sla.target());
                } catch (Exception e) {
                    errors.add(field("slas." + scope + ".target",
                            "SLA target must parse as an ISO-8601 duration: " + sla.target(),
                            sla.target()));
                }
            }
            if (sla.warnAt() != null && (sla.warnAt() <= 0 || sla.warnAt() > 1)) {
                errors.add(field("slas." + scope + ".warnAt",
                        "warnAt is a fraction of target in (0, 1] — or null to disable",
                        sla.warnAt()));
            }
            if (sla.scope() != null && sla.scope().taskType() != null
                    && !sla.scope().taskType().equals("approval")
                    && !sla.scope().taskType().equals("todo")) {
                errors.add(field("slas." + scope + ".scope.taskType",
                        "taskType must be approval or todo: " + sla.scope().taskType(),
                        sla.scope().taskType()));
            }
            if (sla.onBreach() != null && sla.onBreach().escalateTo() != null
                    && !sla.onBreach().escalateTo().matches("(role:)?[A-Za-z][A-Za-z0-9._-]*")) {
                errors.add(field("slas." + scope + ".onBreach.escalateTo",
                        "escalateTo is a role reference: " + sla.onBreach().escalateTo(),
                        sla.onBreach().escalateTo()));
            }
        }
    }

    /**
     * Sharing-rule checks (PHASE-4 §10): the bound entity resolves, the type is the
     * closed v1 set, the named roles exist, and hierarchy-bearing rules sit on
     * leveled roles. Criteria expressions compile at publish.
     */
    private static void validateSharingRules(AppDefinition app,
                                             List<ProblemErrors.FieldError> errors) {
        for (SharingRuleDefinition rule : app.permissionSet().sharingRules()) {
            String scope = "permissionSet.sharingRules[" + rule.entity() + ":" + rule.type() + "]";
            if (rule.entity() == null || app.entity(rule.entity() == null ? "" : rule.entity()).isEmpty()) {
                errors.add(field(scope + ".entity",
                        "sharing rule must bind to an entity of the app: " + rule.entity(),
                        rule.entity()));
                continue;
            }
            if (rule.type() == null || !SharingRuleDefinition.TYPES.contains(rule.type())) {
                errors.add(field(scope + ".type",
                        "sharing rule type must be one of " + SharingRuleDefinition.TYPES,
                        rule.type()));
            }
            for (String role : rule.roles()) {
                if (app.permissionSet().role(role).isEmpty()) {
                    errors.add(field(scope + ".roles",
                            "sharing rule names an unknown role: " + role, role));
                }
            }
            if (SharingRuleDefinition.ROLE_HIERARCHY.equals(rule.type())) {
                for (String role : rule.roles()) {
                    var definition = app.permissionSet().role(role);
                    if (definition.isPresent() && definition.get().level() == null) {
                        errors.add(field(scope + ".roles",
                                "roleHierarchy rules require leveled roles — " + role
                                        + " carries no level (§10)", role));
                    }
                }
            }
            if (SharingRuleDefinition.CRITERIA.equals(rule.type())
                    && (rule.criteria() == null || rule.criteria().isBlank())) {
                errors.add(field(scope + ".criteria",
                        "criteria rules require a match expression", null));
            }
        }
    }

    /**
     * State-machine rules (PHASE-4 §3): the bound entity and enum stateField exist,
     * initial ∈ states, transitions reference known states, terminal states have no
     * outgoing edges, one machine per entity. Guard expressions compile at publish
     * (the FlowCompiler rides the same JVM engine as every other slot).
     */
    private static void validateStateMachines(AppDefinition app,
                                              List<ProblemErrors.FieldError> errors) {
        Set<String> boundEntities = new HashSet<>();
        for (StateMachineDefinition machine : app.stateMachines()) {
            String scope = machine.id() != null ? machine.id() : "stateMachine";
            var entity = app.entity(machine.entity() == null ? "" : machine.entity());
            if (entity.isEmpty()) {
                errors.add(field("stateMachines." + scope + ".entity",
                        "state machine must bind to an entity of the app: " + machine.entity(),
                        machine.entity()));
                continue;
            }
            if (!boundEntities.add(machine.entity())) {
                errors.add(field("stateMachines." + scope,
                        "one state machine per entity in v1: " + machine.entity(),
                        machine.entity()));
            }
            var stateField = entity.get().field(machine.stateField() == null ? "" : machine.stateField());
            if (stateField.isEmpty() || stateField.get().type() != FieldType.ENUM) {
                errors.add(field("stateMachines." + scope + ".stateField",
                        "stateField must be an enum field on " + machine.entity() + ": "
                                + machine.stateField(), machine.stateField()));
            } else if (stateField.get().values() != null) {
                for (StateMachineDefinition.State state : machine.states()) {
                    if (state.name() != null && !stateField.get().values().contains(state.name())) {
                        errors.add(field("stateMachines." + scope + ".states",
                                "state must be a value of the enum field " + machine.stateField()
                                        + ": " + state.name(), state.name()));
                    }
                }
            }
            if (machine.states().isEmpty()) {
                errors.add(field("stateMachines." + scope + ".states",
                        "a state machine requires at least one state", null));
            }
            Set<String> names = new HashSet<>();
            for (StateMachineDefinition.State state : machine.states()) {
                if (state.name() == null || !UPPER_SNAKE.matcher(state.name()).matches()) {
                    errors.add(field("stateMachines." + scope + ".states",
                            "state names must be UPPER_SNAKE: " + state.name(), state.name()));
                } else if (!names.add(state.name())) {
                    errors.add(field("stateMachines." + scope + ".states",
                            "duplicate state name: " + state.name(), state.name()));
                }
            }
            if (machine.initial() == null || !names.contains(machine.initial())) {
                errors.add(field("stateMachines." + scope + ".initial",
                        "initial must be one of the machine's states: " + machine.initial(),
                        machine.initial()));
            }
            for (StateMachineDefinition.State state : machine.states()) {
                if (state.terminalOn()) {
                    for (StateMachineDefinition.Transition transition : machine.transitions()) {
                        if (state.name() != null && state.name().equals(transition.from())) {
                            errors.add(field("stateMachines." + scope + ".transitions",
                                    "terminal state " + state.name() + " admits no outgoing "
                                            + "transitions", transition.from() + "→" + transition.to()));
                        }
                    }
                }
            }
            for (StateMachineDefinition.Transition transition : machine.transitions()) {
                if (transition.from() == null || !names.contains(transition.from())
                        || transition.to() == null || !names.contains(transition.to())) {
                    errors.add(field("stateMachines." + scope + ".transitions",
                            "transition must reference known states: "
                                    + transition.from() + "→" + transition.to(), null));
                }
            }
        }
    }

    /**
     * BPMN workflow rules (PHASE-4 §9): the id is a process key and unique per app,
     * the XML is well-formed (XXE-hardened parse — BPMN is authored input), carries
     * exactly one {@code <process>} whose id equals the definition id, and stays
     * within the size bound. Event-start subscriptions use the closed event set and
     * bind to entities of the app; filter expressions compile at publish.
     */
    private static void validateWorkflows(AppDefinition app,
                                          List<ProblemErrors.FieldError> errors) {
        Set<String> ids = new HashSet<>();
        for (WorkflowDefinition workflow : app.workflows()) {
            String scope = workflow.id() == null ? "workflow" : workflow.id();
            if (workflow.id() == null || !WorkflowDefinition.PROCESS_KEY
                    .matcher(workflow.id()).matches()) {
                errors.add(field("workflows." + scope + ".id",
                        "workflow id is the BPMN process key — a letter/underscore then "
                                + "word characters: " + workflow.id(), workflow.id()));
                continue;
            }
            if (!ids.add(workflow.id())) {
                errors.add(field("workflows." + scope + ".id",
                        "workflow ids must be unique per app: " + workflow.id(),
                        workflow.id()));
            }
            String processId = checkBpmn(workflow, scope, errors);
            if (processId != null && !processId.equals(workflow.id())) {
                errors.add(field("workflows." + scope + ".bpmn",
                        "the BPMN <process id> must equal the workflow id (the process key): "
                                + processId + " ≠ " + workflow.id(), processId));
            }
            for (WorkflowDefinition.EventStart start : workflow.eventStarts()) {
                String startScope = "workflows." + scope + ".eventStarts";
                if (start.event() == null
                        || !WorkflowDefinition.EVENT_TYPES.contains(start.event())) {
                    errors.add(field(startScope + ".event",
                            "event-start event must be one of "
                                    + WorkflowDefinition.EVENT_TYPES + ": " + start.event(),
                            start.event()));
                }
                if (start.entity() == null
                        || app.entity(start.entity() == null ? "" : start.entity()).isEmpty()) {
                    errors.add(field(startScope + ".entity",
                            "event-start must bind to an entity of the app: " + start.entity(),
                            start.entity()));
                }
            }
        }
    }

    /**
     * The BPMN source checks: non-blank, within the size cap, and a well-formed
     * single-process document. Returns the {@code <process id>} when the document
     * parses — null when structural errors were already reported. The parser is
     * XXE-hardened (no DOCTYPE, no external entities): definitions are authored
     * input, not trusted.
     */
    private static String checkBpmn(WorkflowDefinition workflow, String scope,
                                    List<ProblemErrors.FieldError> errors) {
        if (workflow.bpmn() == null || workflow.bpmn().isBlank()) {
            errors.add(field("workflows." + scope + ".bpmn",
                    "workflow requires BPMN XML source", null));
            return null;
        }
        if (workflow.bpmn().length() > WorkflowDefinition.MAX_BPMN_CHARS) {
            errors.add(field("workflows." + scope + ".bpmn",
                    "BPMN source exceeds " + WorkflowDefinition.MAX_BPMN_CHARS + " characters",
                    workflow.bpmn().length()));
            return null;
        }
        try {
            javax.xml.parsers.DocumentBuilderFactory factory =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            org.w3c.dom.Document document = factory.newDocumentBuilder()
                    .parse(new org.xml.sax.InputSource(new java.io.StringReader(workflow.bpmn())));
            org.w3c.dom.NodeList processes = document.getElementsByTagNameNS(
                    "http://www.omg.org/spec/BPMN/20100524/MODEL", "process");
            if (processes.getLength() == 0) {
                // tolerate namespace-less authoring — match by local name
                processes = document.getElementsByTagName("process");
            }
            if (processes.getLength() != 1) {
                errors.add(field("workflows." + scope + ".bpmn",
                        "BPMN requires exactly one <process> element (found "
                                + processes.getLength() + ")", null));
                return null;
            }
            org.w3c.dom.Element process = (org.w3c.dom.Element) processes.item(0);
            return process.getAttribute("id");
        } catch (Exception e) {
            errors.add(field("workflows." + scope + ".bpmn",
                    "BPMN source must be well-formed XML: " + e.getMessage(), null));
            return null;
        }
    }

    /**
     * Scheduled-job rules (PHASE-4 §7 grown at PHASE-5 §7): names unique, cron is
     * structurally shaped (the Scheduler's cron parser is the final authority), the
     * target is the closed v1 set, and per-target params resolve against the app —
     * report jobs pin their report, runAsRole (explicit or the {@code reporting}
     * default), recipient roles, and format; flow jobs pin entity + hook. A job that
     * cannot fire audibly is a save-time error, never a silent registry skip.
     */
    private static void validateJobs(AppDefinition app, List<ProblemErrors.FieldError> errors) {
        Set<String> names = new HashSet<>();
        for (ScheduledJobDefinition job : app.jobs()) {
            String scope = job.name() == null ? "job" : job.name();
            if (job.name() == null || job.name().isBlank()) {
                errors.add(field("jobs", "scheduled jobs require a name", job.name()));
                continue;
            }
            if (!names.add(job.name())) {
                errors.add(field("jobs", "scheduled job names must be unique per app: "
                        + job.name(), job.name()));
            }
            if (!cronShaped(job.cron())) {
                errors.add(field("jobs." + scope + ".cron",
                        "cron must be five or six space-separated fields: " + job.cron(),
                        job.cron()));
            }
            if (job.target() == null || !ScheduledJobDefinition.TARGETS.contains(job.target())) {
                errors.add(field("jobs." + scope + ".target",
                        "target must be one of " + ScheduledJobDefinition.TARGETS + ": "
                                + job.target(), job.target()));
                continue;
            }
            switch (job.target()) {
                case "flow" -> {
                    if (isBlank(job.param("entity")) || isBlank(job.param("hook"))) {
                        errors.add(field("jobs." + scope + ".params",
                                "flow jobs require params {entity, hook}", job.params()));
                    }
                }
                case "report" -> validateReportJob(app, job, scope, errors);
                default -> { }   // script/processStart params are their services' contract
            }
        }
    }

    /** The report-target params (PHASE-5 §7): reportId, runAsRole, recipients, format. */
    private static void validateReportJob(AppDefinition app, ScheduledJobDefinition job,
                                          String scope, List<ProblemErrors.FieldError> errors) {
        String reportId = job.param("reportId");
        if (isBlank(reportId) || app.report(reportId).isEmpty()) {
            errors.add(field("jobs." + scope + ".params.reportId",
                    "report jobs must reference a report of the app: " + reportId, reportId));
        }
        String runAsRole = job.param("runAsRole");
        if (runAsRole != null && !runAsRole.isBlank()) {
            if (app.permissionSet().role(runAsRole).isEmpty()) {
                errors.add(field("jobs." + scope + ".params.runAsRole",
                        "runAsRole must resolve against the app's roles: " + runAsRole,
                        runAsRole));
            }
        } else if (app.permissionSet().role("reporting").isEmpty()) {
            // §7: the default is the app's `reporting` role — the default must resolve too
            errors.add(field("jobs." + scope + ".params.runAsRole",
                    "report jobs default runAsRole to the app's 'reporting' role — declare it "
                            + "or set runAsRole explicitly", null));
        }
        String format = job.param("format");
        if (format != null && !format.isBlank() && !format.equals("csv") && !format.equals("xlsx")) {
            errors.add(field("jobs." + scope + ".params.format",
                    "report delivery format must be csv or xlsx: " + format, format));
        }
        if (job.params().get("recipients") instanceof java.util.Map<?, ?> recipients) {
            List<?> roles = recipients.get("roles") instanceof List<?> list ? list : List.of();
            List<?> users = recipients.get("users") instanceof List<?> list ? list : List.of();
            for (Object role : roles) {
                if (app.permissionSet().role(String.valueOf(role)).isEmpty()) {
                    errors.add(field("jobs." + scope + ".params.recipients",
                            "recipient role must resolve against the app's roles: " + role,
                            role));
                }
            }
            for (Object user : users) {
                try {   // structural: existence is the platform's, shape is ours
                    java.util.UUID.fromString(String.valueOf(user));
                } catch (IllegalArgumentException e) {
                    errors.add(field("jobs." + scope + ".params.recipients",
                            "recipient users must be uuids: " + user, user));
                }
            }
            if (roles.isEmpty() && users.isEmpty()) {
                // a job that fires but reaches nobody is a save-time error, not a
                // silent no-op delivery
                errors.add(field("jobs." + scope + ".params.recipients",
                        "report jobs require at least one recipient role or user",
                        job.params()));
            }
        } else {
            errors.add(field("jobs." + scope + ".params.recipients",
                    "report jobs require recipients {roles: […], users: […]}", job.params()));
        }
    }

    /**
     * Report rules (PHASE-5 §3): the bound entity resolves; filter/group-by/aggregate
     * fields exist; aggregates are numeric (decimal sums stay BigDecimal — PLAN.md §1);
     * group-by and aggregate fields are projection-promoted (the §4 materialized path —
     * sums execute on promoted columns) or the definition is rejected with guidance;
     * bucket labels are non-empty and unique. Bucket expressions compile at publish
     * (JVM engine + SQL lowering — the FlowCompiler's checkReports).
     */
    private static void validateReports(AppDefinition app, List<ProblemErrors.FieldError> errors) {
        Set<String> ids = new HashSet<>();
        for (ReportDefinition report : app.reports()) {
            String scope = report.id() == null ? "report" : report.id();
            if (report.id() == null || !ReportDefinition.REPORT_KEY.matcher(report.id()).matches()) {
                errors.add(field("reports." + scope + ".id",
                        "report id must be a letter/underscore then word characters: "
                                + report.id(), report.id()));
                continue;
            }
            if (!ids.add(report.id())) {
                errors.add(field("reports." + scope + ".id",
                        "report ids must be unique per app: " + report.id(), report.id()));
            }
            var entity = app.entity(report.entity() == null ? "" : report.entity());
            if (entity.isEmpty()) {
                errors.add(field("reports." + scope + ".entity",
                        "report must bind to an entity of the app: " + report.entity(),
                        report.entity()));
                continue;
            }
            EntityDefinition bound = entity.get();
            java.util.Map<String, String> promoted = PromotionPolicy.promotedColumns(bound);
            for (ReportDefinition.Filter filter : report.filters()) {
                String fScope = "reports." + scope + ".filters[" + filter.field() + "]";
                var fieldRef = bound.field(filter.field() == null ? "" : filter.field());
                if (fieldRef.isEmpty()) {
                    errors.add(field(fScope,
                            "filter field must exist on " + report.entity() + ": "
                                    + filter.field(), filter.field()));
                } else if (filter.op() == null
                        || !ReportDefinition.FILTER_OPS.contains(filter.op())) {
                    errors.add(field(fScope + ".op",
                            "filter op must be one of " + ReportDefinition.FILTER_OPS + ": "
                                    + filter.op(), filter.op()));
                } else if ("contains".equals(filter.op())
                        && !fieldRef.get().type().textual()) {
                    errors.add(field(fScope + ".op",
                            "contains filters require a textual field: "
                                    + filter.field() + " is " + fieldRef.get().type().wireName(),
                            filter.op()));
                } else if ("isNull".equals(filter.op()) && filter.value() != null) {
                    errors.add(field(fScope,
                            "isNull filters carry no value", filter.value()));
                } else if ("in".equals(filter.op())
                        && !(filter.value() instanceof List<?>)) {
                    errors.add(field(fScope,
                            "in filters require a list value", filter.value()));
                }
            }
            Set<String> aliases = new HashSet<>();
            for (ReportDefinition.GroupBy group : report.groupBy()) {
                String gScope = "reports." + scope + ".groupBy[" + group.field() + "]";
                var fieldRef = bound.field(group.field() == null ? "" : group.field());
                if (fieldRef.isEmpty()) {
                    errors.add(field(gScope,
                            "group-by field must exist on " + report.entity() + ": "
                                    + group.field(), group.field()));
                    continue;
                }
                if (!promoted.containsKey(group.field())) {
                    errors.add(field(gScope,
                            "group-by fields must be projection-promoted — add " + group.field()
                                    + " to an index (or uniqueOn/displayField/lookup) so sums "
                                    + "execute on a promoted column (§4): " + report.entity()
                                    + "." + group.field(), group.field()));
                }
                Set<String> labels = new HashSet<>();
                for (ReportDefinition.Bucket bucket : group.buckets()) {
                    if (bucket.label() == null || bucket.label().isBlank()) {
                        errors.add(field(gScope + ".buckets",
                                "bucket labels must be non-empty", bucket.label()));
                    } else if (!labels.add(bucket.label())) {
                        errors.add(field(gScope + ".buckets",
                                "bucket labels must be unique per group-by: " + bucket.label(),
                                bucket.label()));
                    }
                    if (bucket.expression() == null || bucket.expression().isBlank()) {
                        errors.add(field(gScope + ".buckets",
                                "buckets require a match expression", bucket.label()));
                    }
                }
            }
            for (ReportDefinition.AggregateField aggregate : report.aggregates()) {
                if (aggregate.op() == null
                        || !ReportDefinition.AGGREGATE_OPS.contains(aggregate.op())) {
                    errors.add(field("reports." + scope + ".aggregates",
                            "aggregate op must be one of " + ReportDefinition.AGGREGATE_OPS
                                    + ": " + aggregate.op(), aggregate.op()));
                    continue;
                }
                if (aggregate.field() == null) {
                    if (!aggregate.op().equals("count")) {
                        errors.add(field("reports." + scope + ".aggregates",
                                aggregate.op() + " requires a field", aggregate.op()));
                    }
                    continue;
                }
                var fieldRef = bound.field(aggregate.field());
                if (fieldRef.isEmpty()) {
                    errors.add(field("reports." + scope + ".aggregates",
                            "aggregate field must exist on " + report.entity() + ": "
                                    + aggregate.field(), aggregate.field()));
                } else if (!fieldRef.get().type().numeric()) {
                    errors.add(field("reports." + scope + ".aggregates",
                            "aggregate fields must be numeric (decimal sums stay BigDecimal — "
                                    + "PLAN.md §1): " + aggregate.field() + " is "
                                    + fieldRef.get().type().wireName(), aggregate.field()));
                }
                if (!promoted.containsKey(aggregate.field())) {
                    errors.add(field("reports." + scope + ".aggregates",
                            "aggregate fields must be projection-promoted — add "
                                    + aggregate.field() + " to an index so sums execute on a "
                                    + "promoted column (§4)", aggregate.field()));
                }
                if (aggregate.alias() != null) {
                    if (!ReportDefinition.REPORT_KEY.matcher(aggregate.alias()).matches()) {
                        errors.add(field("reports." + scope + ".aggregates",
                                "aggregate alias must be a letter/underscore then word "
                                        + "characters: " + aggregate.alias(), aggregate.alias()));
                    } else if (!aliases.add(aggregate.alias())) {
                        errors.add(field("reports." + scope + ".aggregates",
                                "aggregate aliases must be unique per report: "
                                        + aggregate.alias(), aggregate.alias()));
                    }
                }
            }
            if (report.groupBy().isEmpty() && report.aggregates().isEmpty()) {
                errors.add(field("reports." + scope,
                        "a report requires at least one group-by or aggregate", null));
            }
            if (report.drillThrough() != null && (report.drillThrough().entity() == null
                    || app.entity(report.drillThrough().entity()).isEmpty())) {
                errors.add(field("reports." + scope + ".drillThrough.entity",
                        "drill-through must bind to an entity of the app: "
                                + (report.drillThrough().entity()), report.drillThrough().entity()));
            }
        }
    }

    /**
     * Dashboard rules (PHASE-5 §5): ids unique, widget types from the closed set,
     * report refs resolve to reports of the same app, spans are 1..12 grid columns,
     * visibility roles resolve. Roles govern composition only — every widget's run
     * still executes under the requesting actor's grants (§8).
     */
    private static void validateDashboards(AppDefinition app,
                                           List<ProblemErrors.FieldError> errors) {
        Set<String> ids = new HashSet<>();
        for (DashboardDefinition dashboard : app.dashboards()) {
            String scope = dashboard.id() == null ? "dashboard" : dashboard.id();
            if (dashboard.id() == null
                    || !DashboardDefinition.DASHBOARD_KEY.matcher(dashboard.id()).matches()) {
                errors.add(field("dashboards." + scope + ".id",
                        "dashboard id must be a letter/underscore then word characters: "
                                + dashboard.id(), dashboard.id()));
                continue;
            }
            if (!ids.add(dashboard.id())) {
                errors.add(field("dashboards." + scope + ".id",
                        "dashboard ids must be unique per app: " + dashboard.id(),
                        dashboard.id()));
            }
            if (dashboard.widgets().isEmpty()) {
                errors.add(field("dashboards." + scope + ".widgets",
                        "a dashboard requires at least one widget", null));
            }
            for (DashboardDefinition.Widget widget : dashboard.widgets()) {
                String wScope = "dashboards." + scope + ".widgets[" + widget.reportRef() + "]";
                if (widget.widget() == null
                        || !DashboardDefinition.WIDGET_TYPES.contains(widget.widget())) {
                    errors.add(field(wScope,
                            "widget type must be one of " + DashboardDefinition.WIDGET_TYPES
                                    + ": " + widget.widget(), widget.widget()));
                }
                if (widget.reportRef() == null || app.report(widget.reportRef()).isEmpty()) {
                    errors.add(field(wScope,
                            "widget reportRef must resolve to a report of the app: "
                                    + widget.reportRef(), widget.reportRef()));
                }
                if (widget.span() != null && (widget.span() < 1 || widget.span() > 12)) {
                    errors.add(field(wScope + ".span",
                            "widget span is 1..12 grid columns: " + widget.span(), widget.span()));
                }
            }
            for (String role : dashboard.roles()) {
                if (app.permissionSet().role(role).isEmpty()) {
                    errors.add(field("dashboards." + scope + ".roles",
                            "visibility role must resolve against the app's roles: " + role,
                            role));
                }
            }
        }
    }

    /** PermissionSet rules (PHASE-2 §9): roles unique, entities/fields resolve, access ∈ {readonly, hidden}. */
    private static void validatePermissionSet(AppDefinition app, List<ProblemErrors.FieldError> errors) {
        PermissionSet permissions = app.permissionSet();
        Set<String> roleNames = new HashSet<>();
        for (PermissionSet.RoleDefinition role : permissions.roles()) {
            if (role.name() == null || !CAMEL_CASE.matcher(role.name()).matches()) {
                errors.add(field("permissionSet.roles", "role names must be camelCase", role.name()));
                continue;
            }
            if (!roleNames.add(role.name())) {
                errors.add(field("permissionSet.roles", "role names must be unique: " + role.name(), role.name()));
            }
        }
        for (PermissionSet.ObjectPermission permission : permissions.objectPermissions()) {
            if (!roleNames.contains(permission.role())) {
                errors.add(field("permissionSet.objectPermissions",
                        "role must be declared: " + permission.role(), permission.role()));
            }
            if (!appEntityExists(app, permission.entity())) {
                errors.add(field("permissionSet.objectPermissions",
                        "entity must resolve within the app: " + permission.entity(), permission.entity()));
            }
        }
        for (PermissionSet.FieldSecurity security : permissions.fieldSecurity()) {
            if (!roleNames.contains(security.role())) {
                errors.add(field("permissionSet.fieldSecurity",
                        "role must be declared: " + security.role(), security.role()));
            }
            Optional<EntityDefinition> entity = app.entity(security.entity());
            if (entity.isEmpty()) {
                errors.add(field("permissionSet.fieldSecurity",
                        "entity must resolve within the app: " + security.entity(), security.entity()));
                continue;
            }
            if (entity.get().field(security.field()).isEmpty()) {
                errors.add(field("permissionSet.fieldSecurity",
                        "field must exist on " + security.entity() + ": " + security.field(), security.field()));
            }
            if (!PermissionSet.FieldSecurity.READONLY.equals(security.access())
                    && !PermissionSet.FieldSecurity.HIDDEN.equals(security.access())) {
                errors.add(field("permissionSet.fieldSecurity",
                        "access must be readonly|hidden: " + security.access(), security.access()));
            }
        }
    }

    private static void validateEntity(AppDefinition app, EntityDefinition entity,
                                       List<ProblemErrors.FieldError> errors) {
        String scope = "entities[" + entity.apiName() + "].";
        Set<String> fieldNames = new HashSet<>();
        Set<String> relationshipNames = new HashSet<>();

        for (FieldDefinition f : entity.fields()) {
            String fScope = scope + "fields[" + f.apiName() + "]";
            if (f.apiName() == null || !CAMEL_CASE.matcher(f.apiName()).matches()) {
                errors.add(field(fScope + ".apiName", "field apiName must be camelCase", f.apiName()));
                continue;
            }
            if (RESERVED_FIELD_NAMES.contains(f.apiName())) {
                errors.add(field(fScope + ".apiName",
                        "field apiName is reserved by the record API: " + f.apiName(), f.apiName()));
            }
            if (!fieldNames.add(f.apiName())) {
                errors.add(field(fScope + ".apiName", "field apiName must be unique per entity", f.apiName()));
            }
            if (f.type() == null) {
                errors.add(field(fScope + ".type", "field type is required", null));
                continue;
            }
            if (f.type() == FieldType.ENUM && f.values().isEmpty()) {
                errors.add(field(fScope + ".values", "enum fields require a non-empty values list", null));
            }
            if (f.type() == FieldType.LOOKUP
                    && (f.target() == null || !appEntityExists(app, f.target()))) {
                errors.add(field(fScope + ".target",
                        "lookup target must resolve within the app: " + f.target(), f.target()));
            }
            if (f.type().numeric()) {
                validatePrecision(f, fScope, errors);
            }
            if (f.length() != null && f.length() < 1) {
                errors.add(field(fScope + ".length", "length must be >= 1", f.length()));
            }
            if (f.defaultValue() instanceof DefaultValue.SequenceReference ref
                    && app.settings().sequence(ref.sequence()).isEmpty()) {
                errors.add(field(fScope + ".default",
                        "default sequence reference must resolve within the app: " + ref.sequence(),
                        ref.sequence()));
            }
            if (f.defaultValue() instanceof DefaultValue.SequenceReference
                    && !f.type().textual() && f.type() != FieldType.UUID) {
                errors.add(field(fScope + ".default",
                        "a sequence default requires a text or uuid field", f.type().wireName()));
            }
        }

        for (RelationshipDefinition r : entity.relationships()) {
            String rScope = scope + "relationships[" + r.apiName() + "]";
            if (r.apiName() == null || !CAMEL_CASE.matcher(r.apiName()).matches()) {
                errors.add(field(rScope + ".apiName", "relationship apiName must be camelCase", r.apiName()));
                continue;
            }
            if (!relationshipNames.add(r.apiName())) {
                errors.add(field(rScope + ".apiName", "relationship apiName must be unique per entity",
                        r.apiName()));
            }
            if (r.type() == null) {
                errors.add(field(rScope + ".type", "relationship type is required (child|m2m)", null));
            }
            if (r.target() == null || !appEntityExists(app, r.target())) {
                errors.add(field(rScope + ".target",
                        "relationship target must resolve within the app: " + r.target(), r.target()));
                continue;
            }
            if (r.type() == RelationshipType.CHILD) {
                Optional<EntityDefinition> target = app.entity(r.target());
                target.ifPresent(childEntity -> {
                    boolean bound = childEntity.fields().stream()
                            .anyMatch(f -> f.type() == FieldType.LOOKUP
                                    && entity.apiName().equals(f.target()));
                    if (!bound) {
                        errors.add(field(rScope + ".target",
                                "child relationship requires the target entity to declare a lookup field "
                                        + "targeting " + entity.apiName(), r.target()));
                    }
                });
            }
        }

        if (entity.displayField() != null && entity.field(entity.displayField()).isEmpty()) {
            errors.add(field(scope + "displayField",
                    "displayField must name an existing field", entity.displayField()));
        }

        Set<String> indexFieldsChecked = new HashSet<>();
        for (EntityDefinition.IndexDefinition index : entity.indexes()) {
            for (String fieldName : index.fields()) {
                if (!indexFieldsChecked.add(entity.apiName() + "." + String.join(",", index.fields()) + fieldName)) {
                    continue;
                }
                if (entity.field(fieldName).isEmpty()) {
                    errors.add(field(scope + "indexes",
                            "index field must exist on the entity: " + fieldName, fieldName));
                }
            }
            if (index.fields().isEmpty()) {
                errors.add(field(scope + "indexes", "index requires at least one field", null));
            }
        }
    }

    private static void validatePrecision(FieldDefinition f, String scope,
                                          List<ProblemErrors.FieldError> errors) {
        Integer precision = f.precision();
        Integer scale = f.scale();
        int effPrecision = precision == null ? defaultPrecision(f.type()) : precision;
        int effScale = scale == null ? defaultScale(f.type()) : scale;
        if (effPrecision < 1 || effPrecision > 38) {
            errors.add(field(scope + ".precision", "precision must be 1..38", precision));
        }
        if (effScale < 0 || effScale > effPrecision) {
            errors.add(field(scope + ".scale", "scale must be 0..precision", scale));
        }
        if (f.type() == FieldType.MONEY && (effPrecision < 18 || effScale < 4)) {
            errors.add(field(scope + ".precision",
                    "money requires decimal(18,4) minimum (ARCHITECTURE.md §4 money rule)", precision));
        }
    }

    private static int defaultPrecision(FieldType type) {
        return type == FieldType.MONEY ? 18 : 38;
    }

    private static int defaultScale(FieldType type) {
        return type == FieldType.MONEY ? 4 : 6;
    }

    private static boolean appEntityExists(AppDefinition app, String apiName) {
        return app.entity(apiName).isPresent();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static ProblemErrors.FieldError field(String field, String message, Object rejected) {
        return new ProblemErrors.FieldError(field, message, rejected);
    }

    /** Convenience for callers that only need the boolean. */
    public static boolean isValid(AppDefinition app) {
        return validate(app).isEmpty();
    }

    /** Locale-stable apiName key helper for error payloads. */
    public static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
