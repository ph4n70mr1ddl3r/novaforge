package com.novaforge.reporting.run;

import tools.jackson.databind.JsonNode;
import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.ReportDefinition;
import com.novaforge.reporting.source.PublishedApps;
import com.novaforge.reporting.source.PublishedApps.PublishedApp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The execution engine (§4): report runs execute as the requesting actor through the
 * same Data Runtime query path — sharing-rule row filters apply to reports exactly as
 * to lists; no system-principal reporting on the interactive path (the scheduled leg
 * is separately scoped by runAsRole). The result cache is a latency tool keyed by
 * (report, params, definition version, the requesting actor, the evaluation date) —
 * role set alone is not a safe key, owner-based sharing differs between users
 * holding identical roles — and it is never an authorization boundary: every miss
 * re-evaluates row filters per actor.
 */
@Component
public class ReportRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ReportRunner.class);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * The cache READ keeps decimals decimal: a default re-parse types JSON floats as
     * Double, and a cached run would answer sub-cent-drifted money where the fresh
     * path answers BigDecimal (§10 item 1 pins decimal-exact totals).
     */
    private static final JsonMapper CACHE_READ = JsonMapper.builder()
            .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private final PublishedApps published;
    private final RuntimeReportGateway runtime;
    private final StringRedisTemplate redis;
    private final long ttlSeconds;
    private final int runCeiling;
    private final int asyncCeiling;

    public ReportRunner(PublishedApps published, RuntimeReportGateway runtime,
                        StringRedisTemplate redis,
                        @Value("${novaforge.reporting.cache-ttl-seconds:60}") long ttlSeconds,
                        @Value("${novaforge.reporting.run-max-rows:50000}") int runCeiling,
                        @Value("${novaforge.reporting.async-max-rows:1000000}") int asyncCeiling) {
        this.published = published;
        this.runtime = runtime;
        this.redis = redis;
        this.ttlSeconds = ttlSeconds;
        this.runCeiling = runCeiling;
        this.asyncCeiling = asyncCeiling;
    }

    /** A resolved report: the definition plus its app's identity and version. */
    public record Resolved(PublishedApp app, ReportDefinition report) {
    }

    public Resolved resolve(UUID tenantId, String appApiName, String reportId) {
        PublishedApp app = published.byApiName(tenantId, appApiName).orElseThrow(() ->
                new PlatformException(PlatformErrorCode.NOT_FOUND,
                        "no published app " + appApiName + " for this tenant"));
        ReportDefinition report = app.definition().report(reportId).orElseThrow(() ->
                new PlatformException(PlatformErrorCode.NOT_FOUND,
                        "no report " + reportId + " in app " + appApiName));
        return new Resolved(app, report);
    }

    /**
     * The interactive run (§4): the object-level {@code report: execute} grant decides
     * first (default deny until an app grants it — platform admin/builder stay
     * unrestricted), then the runtime executes the compiled query as the caller.
     */
    public Map<String, Object> run(UUID tenantId, UUID actor, String appApiName,
                                   String reportId, Map<String, Object> params,
                                   String callerToken) {
        return run(tenantId, actor, appApiName, reportId, params, callerToken, false);
    }

    /**
     * The interactive run with a cache-bypass: {@code fresh} skips the cache READ
     * (the write still lands) — a sequential caller whose writes changed the data
     * must not read its own pre-write aggregate back (found live: the bank-feed
     * suite's post-settlement aging served the cached pre-settlement total).
     */
    public Map<String, Object> run(UUID tenantId, UUID actor, String appApiName,
                                   String reportId, Map<String, Object> params,
                                   String callerToken, boolean fresh) {
        Resolved resolved = resolve(tenantId, appApiName, reportId);
        requireExecuteGrant(tenantId, actor, resolved);
        String cacheKey = cacheKey(tenantId, actor, resolved, params);
        if (!fresh) {
            String cached = cacheGet(cacheKey);
            if (cached != null) {
                return CACHE_READ.readValue(cached, Map.class);
            }
        }
        Map<String, Object> result = execute(resolved, params,
                query -> runtime.queryAsCaller(qualified(resolved),
                        bounded(query, runCeiling + 1), callerToken));
        requireWithinCeiling(result, runCeiling, "run");
        cacheSet(cacheKey, MAPPER.writeValueAsString(result));
        return result;
    }

    /**
     * The scheduled run (§7): the system principal over an explicitly permissioned
     * role — the compiled query rides the runtime's role-scoped internal surface, so
     * the matrix, field security, and sharing row filters of {@code runAsRole} bound
     * the dataset. Never cached (one-shot renders).
     */
    public Map<String, Object> runScheduled(UUID tenantId, String appApiName, String reportId,
                                            String runAsRole, Map<String, Object> params) {
        Resolved resolved = resolve(tenantId, appApiName, reportId);
        Map<String, Object> result = execute(resolved, params, query -> runtime.queryAsRole(
                tenantId, appApiName, qualified(resolved), runAsRole,
                bounded(query, asyncCeiling + 1)));
        requireWithinCeiling(result, asyncCeiling, "delivery");
        return result;
    }

    /**
     * The async export handoff's render (§6's "same authorization as a run"): the
     * initiating actor's own scopes — never a re-scoped role. The runtime leg
     * re-evaluates the actor's matrix, field security, and owner-based sharing, and
     * the {@code report: execute} grant is re-checked so a grant lost between the
     * handoff and the job's run fails the job audibly instead of exporting anyway.
     * Never cached (one-shot renders).
     */
    public Map<String, Object> runAsActor(UUID tenantId, UUID actor, String appApiName,
                                          String reportId, Map<String, Object> params) {
        Resolved resolved = resolve(tenantId, appApiName, reportId);
        requireExecuteGrant(tenantId, actor, resolved);
        Map<String, Object> result = execute(resolved, params, query -> runtime.queryAsActor(
                tenantId, appApiName, qualified(resolved), actor,
                bounded(query, asyncCeiling + 1)));
        requireWithinCeiling(result, asyncCeiling, "async export");
        return result;
    }

    /**
     * The export's rows — the same authorization as a run (§6), shaped as columns.
     * The grouped query is LIMIT-capped one past {@code exportMaxRows}: an over-cap
     * export materializes at most cap+1 rows before the controller hands off to the
     * async job — never the whole dataset (§6's cap bounds resources, not just the
     * response; the pre-limit path drained every group into memory first).
     */
    public Map<String, Object> exportRows(UUID tenantId, UUID actor, String appApiName,
                                          String reportId, Map<String, Object> params,
                                          String callerToken, int exportMaxRows) {
        Resolved resolved = resolve(tenantId, appApiName, reportId);
        requireExecuteGrant(tenantId, actor, resolved);
        return execute(resolved, params, query ->
                runtime.queryAsCaller(qualified(resolved), bounded(query, exportMaxRows + 1),
                        callerToken));
    }

    /** The limit key the runtime's aggregate door understands — one past the ceiling,
     *  so an over-ceiling result is detected by its size, never by draining it all. */
    private static Map<String, Object> bounded(Map<String, Object> query, int limit) {
        Map<String, Object> bounded = new LinkedHashMap<>(query);
        bounded.put("limit", limit);
        return bounded;
    }

    private static void requireWithinCeiling(Map<String, Object> result, int ceiling,
                                             String door) {
        int rows = ((List<?>) result.getOrDefault("rows", List.of())).size();
        if (rows > ceiling) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "report " + door + " exceeded the configured ceiling of " + ceiling
                            + " grouped rows — tighten the filters or narrow the group-by");
        }
    }

    // --- internals ---

    /**
     * The runtime leg's entity address, app-qualified: a tenant's published apps may
     * collide on an entity apiName (found live: the ERP and the A/R demo both define
     * `Invoice`), and the report's owning app is the qualification the runtime needs.
     */
    private static String qualified(Resolved resolved) {
        return resolved.app().apiName() + "." + resolved.report().entity();
    }

    private Map<String, Object> execute(Resolved resolved, Map<String, Object> params,
                                        java.util.function.Function<Map<String, Object>,
                                                JsonNode> leg) {
        ReportCompiler.Compiled compiled = ReportCompiler.compile(resolved.report(), params);
        JsonNode grouped = leg.apply(compiled.query());
        List<Map<String, Object>> rows = new ArrayList<>();
        grouped.path("rows").forEach(row -> {
            Map<String, Object> shaped = new LinkedHashMap<>();
            compiled.columns().forEach(column ->
                    shaped.put(column, valueOf(row.path(column))));
            rows.add(shaped);
        });
        Map<String, Object> totals = new LinkedHashMap<>();
        if (!compiled.totalsQuery().equals(compiled.query())) {
            JsonNode totalsRow = leg.apply(compiled.totalsQuery());
            if (totalsRow.path("rows").isArray() && !totalsRow.path("rows").isEmpty()) {
                JsonNode first = totalsRow.path("rows").path(0);
                resolved.report().aggregates().forEach(aggregate -> {
                    String key = aggregateAlias(aggregate);
                    totals.put(key, valueOf(first.path(key)));
                });
            }
        } else if (!rows.isEmpty() && !resolved.report().aggregates().isEmpty()) {
            // an aggregate-only report (no group-by): its single row IS the totals —
            // the twin leg is skipped as identical, and KPI tiles plus the export's
            // closing row read totals, which were empty
            Map<String, Object> only = rows.getFirst();
            resolved.report().aggregates().forEach(aggregate ->
                    totals.put(aggregateAlias(aggregate), only.get(aggregateAlias(aggregate))));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", compiled.columns());
        result.put("rows", rows);
        result.put("totals", totals);
        result.put("chart", chart(compiled.columns(), rows));
        return result;
    }

    /** The aggregate's result key — its alias, else op_field per the compiler. */
    private static String aggregateAlias(ReportDefinition.AggregateField aggregate) {
        return aggregate.alias() != null ? aggregate.alias()
                : aggregate.op().toLowerCase() + (aggregate.field() == null ? ""
                        : "_" + ReportCompiler.snakeOf(aggregate.field()));
    }

    /** A chart-shaped projection for direct ECharts binding (§4): first column the axis. */    private static Map<String, Object> chart(List<String> columns, List<Map<String, Object>> rows) {
        Map<String, Object> chart = new LinkedHashMap<>();
        if (columns.isEmpty() || rows.isEmpty()) {
            chart.put("xAxis", Map.of("data", List.of()));
            chart.put("series", List.of());
            return chart;
        }
        List<Object> axis = new ArrayList<>();
        rows.forEach(row -> axis.add(row.get(columns.getFirst())));
        List<Map<String, Object>> series = new ArrayList<>();
        for (int i = 1; i < columns.size(); i++) {
            String name = columns.get(i);
            List<Object> data = new ArrayList<>();
            rows.forEach(row -> data.add(row.get(name)));
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("name", name);
            line.put("data", data);
            series.add(line);
        }
        chart.put("xAxis", Map.of("data", axis));
        chart.put("series", series);
        return chart;
    }

    private static Object valueOf(JsonNode node) {
        return node.isNumber() ? node.decimalValue() : node.isBoolean() ? node.asBoolean()
                : node.isNull() || node.isMissingNode() ? null : node.asString();
    }

    /**
     * The object-level grant (§8): {@code report: execute} rides app role definitions —
     * default deny until an app grants it. Platform admin/builder stay unrestricted
     * (the house bypass).
     */
    private void requireExecuteGrant(UUID tenantId, UUID actor, Resolved resolved) {
        List<String> held = runtime.rolesOf(tenantId, actor);
        if (held.contains("admin") || held.contains("builder")) {
            return;
        }
        AppDefinition definition = resolved.app().definition();
        String entity = resolved.report().entity();
        String prefix = resolved.app().apiName() + ".";
        boolean granted = definition.permissionSet().objectPermissions().stream()
                .filter(p -> p.entity().equals(entity))
                .filter(p -> held.contains(prefix + p.role()))
                .anyMatch(p -> p.allows("reportExecute"));
        if (!granted) {
            throw new PlatformException(PlatformErrorCode.FORBIDDEN,
                    "actor " + actor + " is not granted report: execute on " + entity
                            + " (roles: " + held + ")");
        }
    }

    /** Columns whose aggregate field is money-typed — the §6 formatting rule's scope.
     *  A {@code count} over a money field is a cardinality, not money — never formatted. */
    public static Set<String> moneyColumns(Resolved resolved) {
        ReportDefinition report = resolved.report();
        var entity = resolved.app().definition().entity(report.entity());
        Set<String> moneyFields = new LinkedHashSet<>();
        entity.ifPresent(e -> e.fields().stream()
                .filter(f -> f.type() == com.novaforge.metadata.FieldType.MONEY)
                .forEach(f -> moneyFields.add(f.apiName())));
        Set<String> columns = new LinkedHashSet<>();
        for (ReportDefinition.AggregateField aggregate : report.aggregates()) {
            if (aggregate.field() == null || !moneyFields.contains(aggregate.field())
                    || aggregate.op().equalsIgnoreCase("count")) {
                continue;
            }
            columns.add(aggregate.alias() != null ? aggregate.alias()
                    : aggregate.op().toLowerCase() + "_" + ReportCompiler.snakeOf(
                            aggregate.field()));
        }
        return columns;
    }

    private String cacheKey(UUID tenantId, UUID actor, Resolved resolved,
                            Map<String, Object> params) {
        // the evaluation date joins the key (§4): a run without a pinned asOf evaluates
        // at today — a day boundary within the TTL must never serve yesterday's buckets
        String evaluationDate = params != null && params.get("asOf") != null
                ? String.valueOf(params.get("asOf"))
                : java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        String identity = tenantId + ":" + resolved.app().apiName() + ":"
                + resolved.report().id() + ":v" + resolved.app().version() + ":"
                + actor + ":" + evaluationDate + ":" + digest(params);
        return "novaforge:reporting:results:" + identity;
    }

    // the cache is a latency tool, never an authorization boundary — a Redis
    // hiccup degrades to the uncached path (row filters re-evaluate per actor),
    // it never fails the run
    private String cacheGet(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            LOG.warn("report cache read failed — running uncached: {}", e.getMessage());
            return null;
        }
    }

    private void cacheSet(String key, String value) {
        try {
            redis.opsForValue().set(key, value, java.time.Duration.ofSeconds(ttlSeconds));
        } catch (RuntimeException e) {
            LOG.warn("report cache write failed — result stays uncached: {}", e.getMessage());
        }
    }

    private static String digest(Map<String, Object> params) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            String canonical = params == null ? "{}" : MAPPER.writeValueAsString(params);
            return HexFormat.of().formatHex(
                    sha.digest(canonical.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "cache key digest failed: " + e.getMessage(), null, e);
        }
    }
}
