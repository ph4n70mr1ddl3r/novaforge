package com.novaforge.runtime.engine.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.runtime.engine.metadata.EntityResolver.EntityHandle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Anti-regression (money rule, PLAN.md §1 / ARCHITECTURE.md §4): the connector
 * response path used to type provider numbers through the binary float —
 * {@code walkNode} bound floats as {@code BigDecimal.valueOf(doubleValue())} and
 * integers as {@code Long.valueOf(longValue())}, so a provider amount past 17
 * significant digits bound as its float64 shadow (9999999999999999.99 → "1.0E16"
 * — silently wrong money in the record the flow writes) and a JSON integer past
 * 64 bits threw a raw {@code JsonNodeException} that killed the trigger instead of
 * binding the value. The same switch had no null arm: a provider body without the
 * bound path NPE'd the whole trigger where the contract resolves it empty. The
 * exact upstream parses (ConnectorExecutor's provider read, RestConnectorPort's
 * envelope read) land DecimalNode/BigIntegerNode; this suite pins that the binding
 * keeps them decimal-exact on both template surfaces — the direct
 * {@code ${connector.…}} path and iterate rows — and that absent paths resolve.
 */
class HookConnectorNumberTests {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID APP_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID RECORD_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final UUID SYSTEM = UUID.fromString("88888888-8888-4888-8888-888888888888");

    /**
     * The parse the fixed upstream chain delivers (ConnectorExecutor's PROVIDER_READ
     * over the provider body, RestConnectorPort's EXACT_READ over the executor
     * envelope): floats as DecimalNode, past-64-bit integers as BigIntegerNode.
     */
    private static final JsonMapper EXACT = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    /** 17+ significant digits of money and a 21-digit provider id — the bite shapes. */
    private static final String CHARGE_BODY = """
            {"reference": "ch_2718",
             "chargeAmount": 9999999999999999.99,
             "providerRef": 123456789012345678901}""";

    /**
     * The app under test: Payment's afterSave booking flow binds the connector
     * response into a Charge through the direct {@code ${connector.…}} namespace;
     * the iterate twin walks the response's rows the bank-feed way.
     */
    private static final String APP_JSON = """
            {
              "apiName": "Bank",
              "entities": [
                {
                  "apiName": "Payment",
                  "displayField": "number",
                  "fields": [
                    { "apiName": "number", "type": "text", "required": true },
                    { "apiName": "amount", "type": "money" }
                  ],
                  "hooks": [
                    {
                      "name": "bookCharge",
                      "trigger": "afterSave",
                      "flow": {
                        "id": "c1",
                        "op": "callConnector",
                        "params": { "connector": "bankFeed", "operation": "charge" },
                        "next": "w1",
                        "body": {
                          "id": "w1",
                          "op": "createRecord",
                          "params": {
                            "entity": "Charge",
                            "template": {
                              "reference": "${connector.c1.reference}",
                              "amount": "${connector.c1.chargeAmount}",
                              "providerId": "${connector.c1.providerRef}"
                            }
                          }
                        }
                      }
                    },
                    {
                      "name": "bookRows",
                      "trigger": "afterSave",
                      "flow": {
                        "id": "r1",
                        "op": "callConnector",
                        "params": { "connector": "bankFeed", "operation": "rows" },
                        "next": "i1",
                        "body": {
                          "id": "i1",
                          "op": "iterate",
                          "params": { "path": "connector.r1.rows" },
                          "body": {
                            "id": "w2",
                            "op": "createRecord",
                            "params": {
                              "entity": "Charge",
                              "template": { "reference": "${ref}", "amount": "${charge}" }
                            }
                          }
                        }
                      }
                    }
                  ]
                },
                {
                  "apiName": "Charge",
                  "displayField": "reference",
                  "fields": [
                    { "apiName": "reference", "type": "text", "required": true },
                    { "apiName": "amount", "type": "money" },
                    { "apiName": "providerId", "type": "decimal",
                      "precision": 38, "scale": 0 }
                  ]
                }
              ],
              "integrations": {
                "connectors": [
                  {
                    "id": "bankFeed",
                    "type": "rest",
                    "baseUrl": "http://127.0.0.1:1",
                    "operations": [
                      { "name": "charge", "method": "GET", "path": "/charge" },
                      { "name": "rows", "method": "GET", "path": "/rows" }
                    ]
                  }
                ]
              }
            }
            """;

    private HookExecutor hooks;
    private ConnectorPort connectors;
    private EntityHandle handle;
    private AppDefinition app;

    /** Every createRecord template the flow handed the sink (order-stable). */
    private List<Map<String, Object>> writes;

    private final HookExecutor.HookSink sink = new HookExecutor.HookSink() {
        @Override
        public Map<String, Object> writeRecord(String entityApiName, Map<String, Object> body,
                                               String recordId, UUID systemPrincipal, int depth) {
            writes.add(new LinkedHashMap<>(body));
            return Map.of("id", UUID.randomUUID().toString(), "version", 1);
        }

        @Override
        public void publishAppEvent(String name, Map<String, Object> payload, UUID tenantId,
                                    String entityKey, UUID recordId, UUID systemPrincipal) {
        }

        @Override
        public List<Map<String, Object>> children(UUID tenantId, String appApiName,
                                                  String parentEntityApiName, String relationship,
                                                  UUID parentRecordId) {
            return List.of();
        }

        @Override
        public Map<String, Object> record(UUID tenantId, String appApiName,
                                          String entityApiName, String recordId) {
            return Map.of();
        }
    };

    @BeforeEach
    void setUp() {
        app = DefinitionParser.parseApp(APP_JSON);
        handle = new EntityHandle(APP_ID, "Bank", 1, app.entity("Payment").orElseThrow(),
                "Bank.Payment");
        connectors = mock(ConnectorPort.class);
        hooks = new HookExecutor(mock(ScriptClient.class), mock(ApprovalClient.class),
                connectors, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        writes = new ArrayList<>();
    }

    private void connectorAnswers(String body) {
        when(connectors.execute(anyString(), anyString(), anyString(), anyString(), any(),
                any())).thenAnswer(inv -> new ConnectorPort.ConnectorResult(200,
                        EXACT.readTree(body)));
    }

    private Map<String, Object> payment() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("number", "pay-1");
        data.put("amount", "150.00");
        return data;
    }

    @Test
    @DisplayName("the connector namespace binds provider money decimal-exact — never its float64 shadow")
    void connectorNumbersBindExact() {
        connectorAnswers(CHARGE_BODY);

        hooks.runOneByName(app, handle, TENANT, RECORD_ID, payment(), "afterSave",
                "bookCharge", SYSTEM, sink);

        // before the fix the 21-digit providerRef threw a raw JsonNodeException out of
        // walkNode — the after-hook recorded a retry and no record was ever written
        assertThat(writes).hasSize(1);
        Map<String, Object> template = writes.getFirst();
        assertThat(template.get("reference")).isEqualTo("ch_2718");
        // money: the exact decimal — BigDecimal.valueOf(doubleValue()) answered the
        // float64's "1.0E16" here, a quadrillion shy of the charged amount, and the
        // write path's coercion would have accepted it as money
        assertThat(template.get("amount")).isEqualTo("9999999999999999.99");
        // past-64-bit integers: the full magnitude, never Long.valueOf(longValue())'s
        // raw JsonNodeException
        assertThat(template.get("providerId")).isEqualTo("123456789012345678901");
    }

    @Test
    @DisplayName("iterate rows bind decimal-exact too — the bank-feed's per-row createRecord")
    void iterateRowsBindExact() {
        // This surface was already exact (the ARRAY arm's convertValue keeps
        // DecimalNode/BigIntegerNode as BigDecimal/BigInteger) — the pin guards the
        // NUMBER arm's rewrite: a future "simplification" routing rows through the
        // leaf conversion would corrupt money here the same way the direct path did.
        connectorAnswers("""
                {"rows": [
                  {"ref": "txn-1", "charge": 9999999999999999.99},
                  {"ref": "txn-2", "charge": 123456789012345678901} ]}""");

        hooks.runOneByName(app, handle, TENANT, RECORD_ID, payment(), "afterSave",
                "bookRows", SYSTEM, sink);

        assertThat(writes).hasSize(2);
        assertThat(writes.get(0).get("amount")).isEqualTo("9999999999999999.99");
        assertThat(writes.get(0).get("reference")).isEqualTo("txn-1");
        assertThat(writes.get(1).get("amount")).isEqualTo("123456789012345678901");
        assertThat(writes.get(1).get("reference")).isEqualTo("txn-2");
    }

    @Test
    @DisplayName("a provider body without the bound path resolves empty — never an NPE")
    void absentPathResolvesEmpty() {
        // chargeAmount/providerRef absent from the provider's answer: the binding
        // resolves empty exactly like every other unresolved reference — before the
        // null arm, walkNode's enum switch threw a raw NPE and the whole trigger
        // 500'd on a document shape the provider legitimately owns
        connectorAnswers("{\"reference\": \"ch_9\"}");

        hooks.runOneByName(app, handle, TENANT, RECORD_ID, payment(), "afterSave",
                "bookCharge", SYSTEM, sink);

        assertThat(writes).hasSize(1);
        assertThat(writes.getFirst().get("reference")).isEqualTo("ch_9");
        // unresolved bindings render the null reference — the write path's coercion
        // owns rejecting it as "not a number" (a 400, never a 500)
        assertThat(writes.getFirst().get("amount")).isEqualTo("null");
    }
}
