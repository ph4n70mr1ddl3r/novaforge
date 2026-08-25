package com.novaforge.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.novaforge.common.context.TenantContext;
import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.script.engine.QueryProxy;
import com.novaforge.script.engine.ScriptBudgetExceededException;
import com.novaforge.script.engine.ScriptSandbox;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sandbox pins (PHASE-3 §6, ADR-003): the whitelisted surface works, host access is
 * closed, runaway scripts die at the ADR's caps — the loop watchdog, the CPU meter,
 * the heap tripwire — and the caller's authorization verdicts survive the sandbox
 * boundary unchanged. Plain unit tests on purpose: no Spring context means a quiet
 * heap, which is what the heap tripwire test needs.
 */
class ScriptSandboxTests {

    private static final TenantContext.Context CALLER =
            new TenantContext.Context("11111111-1111-4111-8111-111111111111", "33333333-3333-4333-8333-333333333333");

    /** Statement limit generous enough that only the cap under test can fire. */
    private static final long UNLIMITED = Long.MAX_VALUE / 4;

    private final AtomicReference<TenantContext.Context> seenCaller = new AtomicReference<>();

    private final QueryProxy proxy = new QueryProxy() {
        @Override
        public Object query(TenantContext.Context caller, String entity, String queryJson) {
            seenCaller.set(caller);
            return Map.of("rows", List.of(Map.of("sku", entity)), "total", 1L);
        }

        @Override
        public Object systemQuery(TenantContext.Context principal, String app,
                                  String entity, String queryJson) {
            seenCaller.set(principal);
            return Map.of("rows", List.of(Map.of("sku", entity)), "total", 1L);
        }
    };

    private ScriptSandbox sandbox(long cpuMillis, long wallMillis, long heapMb, long statements) {
        return new ScriptSandbox(proxy, null, cpuMillis, wallMillis, heapMb, statements, 4, 1000);
    }

    @Test
    @DisplayName("script reads $record, calls $log, returns a value")
    void recordAndLogSurface() {
        ScriptSandbox.ScriptResult result = sandbox(1000, 30000, 64, 10_000).execute(
                "const total = $record.price * $record.qty; $log.info('computed ' + total); total",
                Map.of("price", 3, "qty", 4), CALLER);
        assertThat(result.value()).isEqualTo(12L);
        assertThat(result.logs()).containsExactly("INFO computed 12");
    }

    @Test
    @DisplayName("$data.query rides the caller's authorization through the proxy")
    void querySurface() {
        ScriptSandbox.ScriptResult result = sandbox(1000, 30000, 64, 10_000).execute(
                "const found = $data.query('InventoryItem', '{}'); found.rows[0].sku",
                Map.of(), CALLER);
        assertThat(result.value()).isEqualTo("InventoryItem");
        assertThat(seenCaller.get()).isEqualTo(CALLER);
    }

    @Test
    @DisplayName("$data.query rejects malformed entity names")
    void queryEntityValidation() {
        assertThatThrownBy(() -> sandbox(1000, 30000, 64, 10_000).execute(
                "$data.query('../runtime/Order', '{}')", Map.of(), CALLER))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("api name");
    }

    @Test
    @DisplayName("host access is closed: Java classes are unreachable")
    void noHostAccess() {
        assertThatThrownBy(() -> sandbox(1000, 30000, 64, 10_000).execute(
                "typeof Java === 'undefined' ? nullRef.toString() : Java.type('java.lang.Runtime')",
                Map.of(), CALLER))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("script failed");
    }

    @Test
    @DisplayName("runaway loops hit the statement watchdog (ADR-003's loop cap)")
    void loopWatchdog() {
        assertThatThrownBy(() -> sandbox(10_000, 30_000, 256, 10_000)
                .execute("while (true) { }", Map.of(), CALLER))
                .isInstanceOf(ScriptBudgetExceededException.class)
                .hasMessageContaining("statement");
    }

    @Test
    @DisplayName("a CPU-hungry loop dies at its CPU budget, not the statement cap")
    void cpuWatchdog() {
        assertThatThrownBy(() -> sandbox(150, 30_000, 256, UNLIMITED)
                .execute("while (true) { }", Map.of(), CALLER))
                .isInstanceOf(ScriptBudgetExceededException.class)
                .hasMessageContaining("CPU");
    }

    @Test
    @DisplayName("a heap-hungry script dies at its heap budget")
    void heapWatchdog() {
        assertThatThrownBy(() -> sandbox(30_000, 60_000, 8, UNLIMITED).execute(
                "const a = []; while (true) { a.push('0123456789abcdef'.repeat(4096)); }",
                Map.of(), CALLER))
                .isInstanceOf(ScriptBudgetExceededException.class)
                .hasMessageContaining("heap");
    }

    @Test
    @DisplayName("$log capture is bounded")
    void boundedLogs() {
        ScriptSandbox.ScriptResult result = sandbox(10_000, 30_000, 64, UNLIMITED).execute(
                "for (let i = 0; i < 150; i++) { $log.info('entry ' + i); } 'done'",
                Map.of(), CALLER);
        assertThat(result.logs()).hasSize(100);
        assertThat(result.logs().getLast()).isEqualTo("INFO entry 99");
    }

    @Test
    @DisplayName("$record is a read-only view — the return value is the write-back channel")
    void recordIsReadOnly() {
        assertThatThrownBy(() -> sandbox(1000, 30000, 64, 10_000).execute(
                "$record.price = 99; $record.price", Map.of("price", 3), CALLER))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("script failed");
    }

    @Test
    @DisplayName("the caller's authorization verdicts pass through unchanged")
    void authorizationVerdictPassthrough() {
        QueryProxy denying = new QueryProxy() {
            @Override
            public Object query(TenantContext.Context caller, String entity, String queryJson) {
                throw new PlatformException(PlatformErrorCode.FORBIDDEN,
                        "actor lacks read grants on " + entity);
            }

            @Override
            public Object systemQuery(TenantContext.Context principal, String app,
                                      String entity, String queryJson) {
                throw new PlatformException(PlatformErrorCode.FORBIDDEN,
                        "actor lacks read grants on " + entity);
            }
        };
        ScriptSandbox denyingSandbox = new ScriptSandbox(denying, null, 1000, 30000, 64, 10_000, 4, 1000);
        assertThatThrownBy(() -> denyingSandbox.execute(
                "$data.query('Ledger', '{}')", Map.of(), CALLER))
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> assertThat(((PlatformException) e).errorCode())
                        .isEqualTo(PlatformErrorCode.FORBIDDEN))
                .hasMessageContaining("read grants");
    }

    @Test
    @DisplayName("errors surface as validation failures with the script's message")
    void errorSurfacing() {
        assertThatThrownBy(() -> sandbox(1000, 30000, 64, 10_000)
                .execute("nullRef.toString()", Map.of(), CALLER))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("script failed");
    }

    @Test
    @DisplayName("returned objects convert to wire shapes; integral numbers stay integral")
    void resultConversion() {
        ScriptSandbox.ScriptResult result = sandbox(1000, 30000, 64, 10_000).execute(
                "({ qty: 2, price: 19.5, code: 'A-1', flags: [true, false], nested: { ok: 1 } })",
                Map.of(), CALLER);
        org.assertj.core.api.Assertions.assertThat(result.value()).isInstanceOf(Map.class);
        Map<?, ?> value = (Map<?, ?>) result.value();
        assertThat(value.get("qty")).isEqualTo(2L);
        assertThat(value.get("price")).isEqualTo(19.5);
        assertThat(value.get("code")).isEqualTo("A-1");
        assertThat((List<Object>) value.get("flags")).containsExactly(true, false);
        assertThat(((Map<?, ?>) value.get("nested")).get("ok")).isEqualTo(1L);
    }

    @Test
    @DisplayName("an oversized result is rejected at conversion, not smuggled out")
    void resultBudget() {
        assertThatThrownBy(() -> sandbox(10_000, 30_000, 64, UNLIMITED).execute(
                "new Array(9000).fill('x')", Map.of(), CALLER))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("conversion budget");
    }

    @Test
    @DisplayName("a getter bomb detonates during conversion — the watchdog stays armed")
    void getterBombDiesDuringConversion() {
        assertThatThrownBy(() -> sandbox(200, 30_000, 64, UNLIMITED).execute(
                "({ get trap() { while (true) { } } })", Map.of(), CALLER))
                .isInstanceOf(ScriptBudgetExceededException.class)
                .hasMessageContaining("budget");
    }
}
