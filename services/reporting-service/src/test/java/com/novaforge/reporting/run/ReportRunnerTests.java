package com.novaforge.reporting.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.reporting.source.PublishedApps;
import com.novaforge.reporting.source.PublishedApps.PublishedApp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The run-path authorization + cache contract (PHASE-5 §4/§8/§9): report runs
 * execute as the requesting actor behind the object-level {@code report: execute}
 * grant (default deny until an app grants it), and the result cache is keyed by the
 * actor — role set alone is never a key, because owner-based sharing differs
 * between users holding identical roles. Cached results never leak across roles or
 * between same-role users; a Redis hiccup degrades to the uncached path (the cache
 * is a latency tool, never an authorization boundary).
 */
@ExtendWith(MockitoExtension.class)
class ReportRunnerTests {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CLERK_A = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
    private static final UUID CLERK_B = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000002");
    private static final UUID MANAGER = UUID.fromString("cccccccc-0000-4000-8000-000000000003");
    private static final UUID ADMIN = UUID.fromString("dddddddd-0000-4000-8000-000000000004");

    private static final String APP_JSON = """
            { "apiName": "ArDesk",
              "permissionSet": {
                "roles": [ { "name": "clerk" }, { "name": "manager" } ],
                "objectPermissions": [
                  { "role": "clerk", "entity": "Invoice", "read": true,
                    "reportExecute": true },
                  { "role": "manager", "entity": "Invoice", "read": true } ] },
              "entities": [
                { "apiName": "Invoice",
                  "displayField": "customer",
                  "fields": [
                    { "apiName": "customer", "type": "text" },
                    { "apiName": "amount", "type": "decimal", "precision": 18, "scale": 4 } ] } ],
              "reports": [
                { "id": "byCustomer", "entity": "Invoice",
                  "groupBy": [ { "field": "customer" } ],
                  "aggregates": [ { "op": "sum", "field": "amount", "alias": "sum_amount" } ] },
                { "id": "totalBook", "entity": "Invoice",
                  "aggregates": [ { "op": "sum", "field": "amount", "alias": "sum_amount" } ] } ] }
            """;

    private PublishedApps published;
    private RuntimeReportGateway runtime;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private ReportRunner runner;

    private final AtomicInteger executions = new AtomicInteger();
    private final Map<String, String> store = new HashMap<>();

    @BeforeEach
    void wire() {
        published = mock(PublishedApps.class);
        runtime = mock(RuntimeReportGateway.class);
        redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        values = ops;
        lenient().when(redis.opsForValue()).thenReturn(values);
        runner = new ReportRunner(published, runtime, redis, 60, 50_000, 1_000_000);

        AppDefinition app = DefinitionParser.parseApp(APP_JSON);
        PublishedApp bundle = new PublishedApp(TENANT, UUID.randomUUID().toString(), "ArDesk", 3, app);
        lenient().when(published.byApiName(TENANT, "ArDesk")).thenReturn(Optional.of(bundle));

        // the runtime leg answers the grouped query and its totals twin distinctly
        // (the twin drops groupBy) and counts *runs* — one grouped call per run; the
        // sharing-rule row filters the real runtime applies are invisible here by
        // design: this suite pins that the runner never bypasses the actor boundary.
        // The entity address is app-qualified — the owning app disambiguates a
        // tenant's same-named entities (found live: ERP and the A/R demo both
        // define `Invoice`; the unqualified leg rejected as ambiguous)
        lenient().when(runtime.queryAsCaller(eq("ArDesk.Invoice"), any(), any())).thenAnswer(inv -> {
            Map<String, Object> query = inv.getArgument(1, Map.class);
            if (query.containsKey("groupBy")) {
                executions.incrementAndGet();
                return grouped();
            }
            return totals();
        });
    }

    private static JsonNode grouped() {
        return MAPPER.readTree("""
                { "rows": [ { "customer": "acme", "sum_amount": "120.0000" } ] }
                """);
    }

    private static JsonNode totals() {
        return MAPPER.readTree("""
                { "rows": [ { "sum_amount": "120.0000" } ] }
                """);
    }

    private void rolesOf(UUID actor, String... roles) {
        lenient().when(runtime.rolesOf(TENANT, actor)).thenReturn(List.of(roles));
    }

    private void cacheWorking() {
        lenient().when(values.get(anyString())).thenAnswer(inv ->
                store.get(inv.getArgument(0, String.class)));
        lenient().doAnswer(inv -> {
                    store.put(inv.getArgument(0, String.class), inv.getArgument(1, String.class));
                    return null;
                })
                .when(values).set(anyString(), anyString(), any(java.time.Duration.class));
    }

    @Test
    @DisplayName("§8: report: execute default-denies until granted; admin stays unrestricted")
    void executeGrantDefaultDenies() {
        rolesOf(MANAGER, "ArDesk.manager");
        assertThatThrownBy(() -> runner.run(TENANT, MANAGER, "ArDesk", "byCustomer",
                Map.of(), "token"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("report: execute")
                .extracting(e -> ((PlatformException) e).errorCode())
                .isEqualTo(PlatformErrorCode.FORBIDDEN);
        assertThat(executions.get()).isZero();

        rolesOf(CLERK_A, "ArDesk.clerk");
        runner.run(TENANT, CLERK_A, "ArDesk", "byCustomer", Map.of(), "token");
        assertThat(executions.get()).isEqualTo(1);

        rolesOf(ADMIN, "admin");
        runner.run(TENANT, ADMIN, "ArDesk", "byCustomer", Map.of(), "token");
        assertThat(executions.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("§4/§9: the cache key carries the actor — same-role users never share results")
    void cacheNeverLeaksAcrossActors() {
        cacheWorking();
        rolesOf(CLERK_A, "ArDesk.clerk");
        rolesOf(CLERK_B, "ArDesk.clerk");   // identical role set, different actor

        Map<String, Object> first = runner.run(TENANT, CLERK_A, "ArDesk", "byCustomer",
                Map.of(), "token-a");
        Map<String, Object> second = runner.run(TENANT, CLERK_B, "ArDesk", "byCustomer",
                Map.of(), "token-b");

        // both executed — B was not served A's cached result
        assertThat(executions.get()).isEqualTo(2);
        assertThat(first).isEqualTo(second);
        // two distinct cache keys, each naming its actor
        assertThat(store).hasSize(2);
        assertThat(store.keySet()).anyMatch(key -> key.contains(":" + CLERK_A + ":"));
        assertThat(store.keySet()).anyMatch(key -> key.contains(":" + CLERK_B + ":"));
    }

    @Test
    @DisplayName("§4: the same actor's repeat run is served from cache; the evaluation date keys it")
    void cacheServesRepeatsAndKeysTheEvaluationDate() {
        cacheWorking();
        rolesOf(CLERK_A, "ArDesk.clerk");

        runner.run(TENANT, CLERK_A, "ArDesk", "byCustomer", Map.of(), "token");
        runner.run(TENANT, CLERK_A, "ArDesk", "byCustomer", Map.of(), "token");
        assertThat(executions.get()).isEqualTo(1);   // the repeat was a cache hit

        // a pinned asOf is a different evaluation date — a distinct key, a fresh run
        runner.run(TENANT, CLERK_A, "ArDesk", "byCustomer", Map.of("asOf", "2026-01-15"),
                "token");
        assertThat(executions.get()).isEqualTo(2);
        assertThat(store).hasSize(2);

        // the unpinned key carries today's evaluation date — a day boundary within
        // the TTL can never serve yesterday's buckets
        assertThat(store.keySet()).anyMatch(key ->
                key.contains(LocalDate.now(ZoneOffset.UTC).toString()));
    }

    @Test
    @DisplayName("a cached run keeps decimals decimal — the cache read re-types money as BigDecimal, never Double")
    void cachedRunKeepsMoneyDecimal() {
        cacheWorking();
        rolesOf(CLERK_A, "ArDesk.clerk");
        // the runtime leg answers money as a JSON number (the real lowering does):
        // the fresh path shapes it BigDecimal, and the cache round-trip must too
        JsonNode numericTotals = MAPPER.readTree("{\"rows\":[{\"sum_amount\":120.0000}]}");
        when(runtime.queryAsCaller(eq("ArDesk.Invoice"), any(), any()))
                .thenAnswer(inv -> {
                    Map<String, Object> query = inv.getArgument(1, Map.class);
                    executions.incrementAndGet();
                    return query.containsKey("groupBy") ? grouped() : numericTotals;
                });

        Map<String, Object> fresh = runner.run(TENANT, CLERK_A, "ArDesk", "totalBook",
                Map.of(), "token");
        // decimal-exact value in a BigDecimal (the JSON parse normalizes scale;
        // the money rule bars the binary float, not trailing zeros)
        assertThat((java.math.BigDecimal) asBigDecimal(fresh)).isEqualByComparingTo("120");
        int runsAfterFresh = executions.get();

        // the cached hit: the JSON re-parse must carry USE_BIG_DECIMAL_FOR_FLOATS —
        // a default re-parse types the money column Double and the cached total
        // answers sub-cent-drifted where the fresh path answered BigDecimal. The
        // round-trip normalizes scale; the pin is the exact VALUE in the exact
        // TYPE — a Double fails the instance check first.
        Map<String, Object> cached = runner.run(TENANT, CLERK_A, "ArDesk", "totalBook",
                Map.of(), "token");
        assertThat(executions.get()).isEqualTo(runsAfterFresh);   // served from cache
        Object cachedTotal = asBigDecimal(cached);
        assertThat(cachedTotal).isInstanceOf(java.math.BigDecimal.class);
        assertThat((java.math.BigDecimal) cachedTotal).isEqualByComparingTo("120");
    }

    /** The run's totals aggregate (the pin asserts its concrete type). */
    private static Object asBigDecimal(Map<String, Object> run) {
        return ((Map<?, ?>) run.get("totals")).get("sum_amount");
    }

    @Test
    @DisplayName("§4: a Redis outage degrades to the uncached path — never a failed run")
    void redisOutageRunsUncached() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("down"))
                .when(values).set(anyString(), anyString(), any(java.time.Duration.class));
        rolesOf(CLERK_A, "ArDesk.clerk");

        Map<String, Object> result = runner.run(TENANT, CLERK_A, "ArDesk", "byCustomer",
                Map.of(), "token");
        assertThat(executions.get()).isEqualTo(1);
        assertThat(result.get("columns")).isEqualTo(List.of("customer", "sum_amount"));

        // and a second run still succeeds (re-executing — the cache stayed down)
        runner.run(TENANT, CLERK_A, "ArDesk", "byCustomer", Map.of(), "token");
        assertThat(executions.get()).isEqualTo(2);
        verify(redis, times(4)).opsForValue();
    }

    @Test
    @DisplayName("§6: the async export's render rides the ACTOR's own scope — the grant re-checked, never a re-scoped role")
    void runAsActorUsesTheActorsOwnScope() {
        // without the grant the handoff render fails closed before any leg runs
        rolesOf(MANAGER, "ArDesk.manager");
        assertThatThrownBy(() -> runner.runAsActor(TENANT, MANAGER, "ArDesk", "byCustomer",
                Map.of()))
                .isInstanceOf(PlatformException.class)
                .extracting(e -> ((PlatformException) e).errorCode())
                .isEqualTo(PlatformErrorCode.FORBIDDEN);

        rolesOf(CLERK_A, "ArDesk.clerk");
        when(runtime.queryAsActor(eq(TENANT), eq("ArDesk"), eq("ArDesk.Invoice"),
                eq(CLERK_A), any())).thenAnswer(inv -> grouped());
        Map<String, Object> run = runner.runAsActor(TENANT, CLERK_A, "ArDesk", "byCustomer",
                Map.of());
        assertThat(((List<?>) run.get("rows"))).hasSize(1);
        verify(runtime, atLeastOnce()).queryAsActor(eq(TENANT), eq("ArDesk"),
                eq("ArDesk.Invoice"), eq(CLERK_A), any());
        // the role-scoped leg was never touched — the export cannot re-scope wider
        verify(runtime, never()).queryAsRole(any(), any(), any(), any(), any());

    }

    @Test
    @DisplayName("§7: the scheduled leg addresses the app-qualified entity — same-named entities never collide")
    void runScheduledAddressesTheAppQualifiedEntity() {
        when(runtime.queryAsRole(eq(TENANT), eq("ArDesk"), eq("ArDesk.Invoice"),
                eq("ArDesk.reporting"), any())).thenAnswer(inv -> grouped());
        Map<String, Object> run = runner.runScheduled(TENANT, "ArDesk", "byCustomer",
                "ArDesk.reporting", Map.of());
        assertThat(((List<?>) run.get("rows"))).hasSize(1);
        verify(runtime, atLeastOnce()).queryAsRole(eq(TENANT), eq("ArDesk"),
                eq("ArDesk.Invoice"), eq("ArDesk.reporting"), any());
    }

    @Test
    @DisplayName("an aggregate-only report carries totals — its single row is the totals")
    void aggregateOnlyReportCarriesTotals() {
        rolesOf(CLERK_A, "ArDesk.clerk");
        when(runtime.queryAsCaller(eq("ArDesk.Invoice"), any(), any()))
                .thenAnswer(inv -> totals());
        Map<String, Object> run = runner.run(TENANT, CLERK_A, "ArDesk", "totalBook",
                Map.of(), "token");
        // KPI tiles and export closing rows read totals — once empty for every
        // aggregate-only report (the un-grouped twin was skipped as identical)
        assertThat(run.get("totals")).isEqualTo(Map.of("sum_amount", "120.0000"));
        assertThat(((List<?>) run.get("rows"))).hasSize(1);
    }

    @Test
    @DisplayName("the doors bound materialization — the limit rides the lowered query, over-ceiling fails closed")
    void doorsBoundMaterialization() {
        rolesOf(CLERK_A, "ArDesk.clerk");
        List<Map<String, Object>> many = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            many.add(Map.of("customer", "c" + i, "sum_amount", "1.0000"));
        }
        JsonNode threeRows = MAPPER.valueToTree(Map.of("rows", many));
        when(runtime.queryAsCaller(eq("ArDesk.Invoice"), any(), any()))
                .thenReturn(threeRows);

        // a ceiling of 2: the query carries LIMIT 3 (one past — detection by size),
        // and 3 rows fail the run closed instead of draining an unbounded dataset
        ReportRunner bounded = new ReportRunner(published, runtime, redis, 60, 2, 1_000_000);
        assertThatThrownBy(() -> bounded.run(TENANT, CLERK_A, "ArDesk", "byCustomer",
                Map.of(), "token"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("ceiling of 2");
        org.mockito.ArgumentCaptor<Map<String, Object>> query =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(runtime, atLeastOnce()).queryAsCaller(eq("ArDesk.Invoice"), query.capture(),
                anyString());
        assertThat(query.getValue().get("limit")).isEqualTo(3);

        // the export door bounds one past ITS cap — the controller's over-cap 202
        // detection materializes at most cap+1 rows
        org.mockito.Mockito.clearInvocations(runtime);
        when(runtime.queryAsCaller(eq("ArDesk.Invoice"), any(), any()))
                .thenReturn(threeRows);
        bounded.exportRows(TENANT, CLERK_A, "ArDesk", "byCustomer", Map.of(), "token", 2);
        verify(runtime, atLeastOnce()).queryAsCaller(eq("ArDesk.Invoice"),
                org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                        body -> Integer.valueOf(3).equals(body.get("limit"))),
                anyString());
    }
}
