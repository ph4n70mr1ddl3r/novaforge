package com.novaforge.reporting.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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
        runner = new ReportRunner(published, runtime, redis, 60);

        AppDefinition app = DefinitionParser.parseApp(APP_JSON);
        PublishedApp bundle = new PublishedApp(TENANT, UUID.randomUUID().toString(), "ArDesk", 3, app);
        lenient().when(published.byApiName(TENANT, "ArDesk")).thenReturn(Optional.of(bundle));

        // the runtime leg answers the grouped query and its totals twin distinctly
        // (the twin drops groupBy) and counts *runs* — one grouped call per run; the
        // sharing-rule row filters the real runtime applies are invisible here by
        // design: this suite pins that the runner never bypasses the actor boundary
        when(runtime.queryAsCaller(eq("Invoice"), any(), any())).thenAnswer(inv -> {
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
}
