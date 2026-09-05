package com.novaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.runtime.engine.metadata.MetadataClient;
import com.novaforge.runtime.storage.materializer.Materializer;
import com.novaforge.testsupport.PostgresTestBase;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.json.JsonMapper;

/**
 * The G-2 harvest's execution half (PHASE-7 §3.7, 2026-09-03): a {@code bind} step
 * binds a lookup target's canonical field view into the graph's expression scope —
 * later steps address {@code item.<field>} dot-paths — so the weighted-average
 * costing the dogfood logged as inexpressible (G-2's cross-record arithmetic) runs
 * as a declarative flow: receipt stamps its value; posting an issue prices it at
 * {@code inventoryValue / qtyOnHand} off the bound Item's roll-up view. The
 * dot-paths type-check against the target entity at publish (the compiler's
 * rejection matrix rides DefinitionLifecycleTests).
 *
 * <p>The G-15 adoption rides the same shape (the 42nd pass): the Item's roll-ups
 * carry the shipped WHERE clause — {@code SUM(movements.qty WHERE status =
 * 'POSTED')} — so DRAFT movements never count into stock, and the flow's manual
 * row discount ({@code qtyOnHand - qty}, the unconditional-roll-up workaround)
 * is retired: the divisor is the plain bound view. The pinned numbers are
 * unchanged — 50 / 10 = 5.0000 either way — and the corpus's suite keeps them.</p>
 */
@SpringBootTest(properties = {"novaforge.events.relay-interval-ms=3600000"})
@AutoConfigureMockMvc
class BindStepTests extends PostgresTestBase {

    static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID ACTOR = UUID.fromString("33333333-3333-4333-8333-333333333333");
    static final UUID APP_ID = UUID.fromString("77777777-6666-4666-8666-666666666667");

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * The ERP's costing shape, authored as the §3.7 flow (the exact graph the
     * erp-app.json artifact carries, on the same field types): the receipt leg
     * stamps value from the authored unit cost; the issue leg binds the Item, guards
     * on the bound roll-up view, and prices at the running weighted average.
     */
    static final String APP_JSON = """
{
 "apiName": "WhAvg",
 "entities": [
  {
   "apiName": "Item",
   "displayField": "sku",
   "fields": [
    {
     "apiName": "sku",
     "type": "text",
     "required": true
    },
    {
     "apiName": "unitCost",
     "type": "money"
    },
    {
     "apiName": "qtyOnHand",
     "type": "decimal",
     "precision": 18,
     "scale": 6,
     "rollup": "SUM(movements.qty WHERE status = 'POSTED')"
    },
    {
     "apiName": "inventoryValue",
     "type": "money",
     "rollup": "SUM(movements.value WHERE status = 'POSTED')"
    }
   ],
   "relationships": [
    {
     "apiName": "movements",
     "type": "child",
     "target": "StockLedger",
     "cascadeDelete": true
    }
   ]
  },
  {
   "apiName": "StockLedger",
   "displayField": "movementType",
   "fields": [
    {
     "apiName": "item",
     "type": "lookup",
     "target": "Item",
     "required": true
    },
    {
     "apiName": "movementType",
     "type": "enum",
     "values": [
      "RECEIPT",
      "ISSUE"
     ],
     "required": true
    },
    {
     "apiName": "movementDate",
     "type": "date",
     "required": true
    },
    {
     "apiName": "qty",
     "type": "decimal",
     "precision": 18,
     "scale": 6,
     "required": true
    },
    {
     "apiName": "unitCost",
     "type": "money"
    },
    {
     "apiName": "value",
     "type": "money"
    },
    {
     "apiName": "status",
     "type": "enum",
     "values": [
      "DRAFT",
      "POSTED"
     ]
    }
   ],
   "validations": [
    {
     "name": "receiptPriced",
     "scope": "record",
     "expression": "movementType != 'RECEIPT' || status != 'POSTED' || unitCost != null",
     "message": "Posting a receipt requires its unit cost"
    }
   ],
   "hooks": [
    {
     "name": "costMovement",
     "trigger": "beforeSave",
     "flow": {
      "id": "route",
      "op": "branch",
      "params": {
       "guard": "status == 'POSTED' && movementType == 'ISSUE' && unitCost == null"
      },
      "onTrue": "fetch",
      "onFalse": "receipt",
      "body": {
       "id": "fetch",
       "op": "bind",
       "params": {
        "lookup": "item"
       },
       "next": "avgGuard",
       "body": {
        "id": "avgGuard",
        "op": "branch",
        "params": {
         "guard": "item.qtyOnHand != null && item.inventoryValue != null && item.qtyOnHand > 0"
        },
        "onTrue": "issueUnitCost",
        "body": {
         "id": "issueUnitCost",
         "op": "setField",
         "params": {
          "field": "unitCost",
          "expression": "item.inventoryValue / item.qtyOnHand"
         },
         "next": "issueValue",
         "body": {
          "id": "issueValue",
          "op": "setField",
          "params": {
           "field": "value",
           "expression": "qty * unitCost"
          },
          "next": "receipt",
          "body": {
           "id": "receipt",
           "op": "branch",
           "params": {
            "guard": "movementType == 'RECEIPT' && unitCost != null && value == null"
           },
           "onTrue": "receiptValue",
           "body": {
            "id": "receiptValue",
            "op": "setField",
            "params": {
             "field": "value",
             "expression": "qty * unitCost"
            }
           }
          }
         }
        }
       }
      }
     }
    }
   ]
  }
 ]
}
            """;

    static AppDefinition app;

    @Autowired
    MockMvc mockMvc;

    @TestConfiguration
    static class StubMetadata {

        @Bean
        @Primary
        MetadataClient metadataClient() {
            MetadataClient client = Mockito.mock(MetadataClient.class);
            app = DefinitionParser.parseApp(APP_JSON);
            Mockito.when(client.publishedApps()).thenAnswer(inv ->
                    List.of(new MetadataClient.PublishedApp(APP_ID, "WhAvg", 1)));
            Mockito.when(client.publishedBundle(Mockito.any(UUID.class))).thenAnswer(inv ->
                    new MetadataClient.PublishedBundle(1, app));
            return client;
        }
    }

    private static final GenericContainer<?> REDIS = new GenericContainer<>("docker.io/library/redis:7.4.11")
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    private static final org.testcontainers.kafka.KafkaContainer KAFKA =
            new org.testcontainers.kafka.KafkaContainer("apache/kafka:4.3.1");

    static {
        REDIS.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestBase::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestBase::jdbcUsername);
        registry.add("spring.datasource.password", PostgresTestBase::jdbcPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @BeforeAll
    static void materialize(@Autowired Materializer materializer) {
        materializer.apply(DefinitionParser.parseApp(APP_JSON));
    }

    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor() {
        return jwt()
                .jwt(token -> token.claim("tenant_id", TENANT.toString()).subject(ACTOR.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_novaforge.api"));
    }

    private MvcResult create(String entity, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/runtime/" + entity).with(jwtFor())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        if (result.getResponse().getStatus() != 200) {
            throw new IllegalStateException(entity + " create " + result.getResponse().getStatus()
                    + ": " + result.getResponse().getContentAsString());
        }
        return result;
    }

    /** The single row matching a sku filter — never a positional assumption. */
    private tools.jackson.databind.JsonNode rowBySku(String sku) throws Exception {
        String filter = "{\"field\":\"sku\",\"op\":\"eq\",\"value\":\"" + sku + "\"}";
        String body = mockMvc.perform(get("/api/v1/runtime/Item").with(jwtFor())
                        .param("filter", filter))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(body).get("rows").get(0);
    }

    @Test
    @DisplayName("the bind flow prices issues at the running weighted average, declaratively (§3.7)")
    void weightedAverageCostingRunsDeclaratively() throws Exception {
        create("Item", "{\"sku\":\"W-1\"}");
        String itemId = rowBySku("W-1").get("id").asString();

        // receipt 10 @ 5.0000, posted: the receipt leg stamps value = qty × unitCost
        create("StockLedger", "{\"item\":\"" + itemId + "\",\"movementType\":\"RECEIPT\","
                + "\"movementDate\":\"2026-09-01\",\"qty\":\"10\",\"unitCost\":\"5.0000\","
                + "\"status\":\"POSTED\"}");

        // issue −4 as DRAFT: the WHERE-conditioned roll-ups do NOT count it — the
        // Item still reads 10 on hand / 50.0000 (the §3.5 defect dead; the
        // unconditional roll-ups served 6 here) — then post it: the bind leg reads
        // the bound Item (qtyOnHand 10, inventoryValue 50) and prices 50 / 10 =
        // 5.0000 — the exact numbers the ERP corpus's suite pins, the manual row
        // discount retired because the roll-up itself excludes the DRAFT row
        MvcResult draft = create("StockLedger", "{\"item\":\"" + itemId + "\","
                + "\"movementType\":\"ISSUE\",\"movementDate\":\"2026-09-02\",\"qty\":\"-4\","
                + "\"status\":\"DRAFT\"}");
        var afterDraft = rowBySku("W-1");
        assertThat(new BigDecimal(afterDraft.get("qtyOnHand").asString()))
                .as("a DRAFT issue never moves the WHERE-conditioned roll-up (§3.5)")
                .isEqualByComparingTo("10");
        assertThat(new BigDecimal(afterDraft.get("inventoryValue").asString()))
                .isEqualByComparingTo("50.0000");
        String issueId = MAPPER.readTree(draft.getResponse().getContentAsString())
                .get("id").asString();
        String version = MAPPER.readTree(draft.getResponse().getContentAsString())
                .get("version").asString();
        MvcResult posted = mockMvc.perform(patch("/api/v1/runtime/StockLedger/" + issueId).with(jwtFor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version + ",\"status\":\"POSTED\"}"))
                .andReturn();
        if (posted.getResponse().getStatus() != 200) {
            throw new IllegalStateException("post " + posted.getResponse().getStatus()
                    + ": " + posted.getResponse().getContentAsString());
        }

        // the shaped issue by id — a shared-item filter would match both movements
        // and their store order is not a contract
        String issueBody = mockMvc
                .perform(get("/api/v1/runtime/StockLedger/" + issueId).with(jwtFor()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var issue = MAPPER.readTree(issueBody);
        assertThat(new BigDecimal(issue.get("unitCost").asString()))
                .as("the issue prices at the weighted average (50 / 10)")
                .isEqualByComparingTo("5.0000");
        assertThat(new BigDecimal(issue.get("value").asString()))
                .as("the issue's value = qty × unitCost")
                .isEqualByComparingTo("-20.0000");

        // the bound view drove real writes: the Item's roll-ups net 6 on hand,
        // 30.0000 in value — the corpus's exact reconciliation
        var refreshed = rowBySku("W-1");
        assertThat(new BigDecimal(refreshed.get("qtyOnHand").asString()))
                .isEqualByComparingTo("6");
        assertThat(new BigDecimal(refreshed.get("inventoryValue").asString()))
                .isEqualByComparingTo("30.0000");
    }

    @Test
    @DisplayName("a DRAFT receipt never counts into stock — the G-15 defect the WHERE clause kills (§3.5 adoption)")
    void draftMovementsNeverCountIntoStock() throws Exception {
        create("Item", "{\"sku\":\"W-3\"}");
        String itemId = rowBySku("W-3").get("id").asString();

        // a priced receipt parks in DRAFT: the unconditional roll-ups counted it at
        // create (the logged defect — stock reported for goods not yet posted); the
        // WHERE-conditioned roll-ups leave the Item at zero until the machine posts
        create("StockLedger", "{\"item\":\"" + itemId + "\",\"movementType\":\"RECEIPT\","
                + "\"movementDate\":\"2026-09-01\",\"qty\":\"10\",\"unitCost\":\"5.0000\","
                + "\"status\":\"DRAFT\"}");
        var draft = rowBySku("W-3");
        assertThat(new BigDecimal(draft.get("qtyOnHand").asString()))
                .as("a DRAFT receipt never moves qtyOnHand (§3.5)")
                .isEqualByComparingTo("0");
        assertThat(new BigDecimal(draft.get("inventoryValue").asString()))
                .isEqualByComparingTo("0.0000");

        // the movement's own transition is what moves the parent: post it
        var created = MAPPER.readTree(mockMvc.perform(get("/api/v1/runtime/StockLedger")
                        .with(jwtFor())
                        .param("filter", "{\"field\":\"item\",\"op\":\"eq\",\"value\":\"" + itemId + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("rows").get(0);
        mockMvc.perform(patch("/api/v1/runtime/StockLedger/" + created.get("id").asString())
                        .with(jwtFor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + created.get("version").asString()
                                + ",\"status\":\"POSTED\"}"))
                .andExpect(status().isOk());

        // a DRAFT issue on top of the posted receipt still does not count
        create("StockLedger", "{\"item\":\"" + itemId + "\",\"movementType\":\"ISSUE\","
                + "\"movementDate\":\"2026-09-02\",\"qty\":\"-4\",\"status\":\"DRAFT\"}");
        var after = rowBySku("W-3");
        assertThat(new BigDecimal(after.get("qtyOnHand").asString()))
                .as("only the POSTED receipt counts; the DRAFT issue stays out (§3.5)")
                .isEqualByComparingTo("10");
        assertThat(new BigDecimal(after.get("inventoryValue").asString()))
                .isEqualByComparingTo("50.0000");
    }

    @Test
    @DisplayName("an unresolvable bind (no receipt value yet) fails the guard open — the no-op the script returned")
    void guardWithoutBoundValueStaysNoOp() throws Exception {
        create("Item", "{\"sku\":\"W-2\"}");
        String itemId = rowBySku("W-2").get("id").asString();

        // an issue with no receipt behind it: the bound inventoryValue is null —
        // the avgGuard is false, unitCost stays null, the write proceeds
        MvcResult draft = create("StockLedger", "{\"item\":\"" + itemId + "\","
                + "\"movementType\":\"ISSUE\",\"movementDate\":\"2026-09-03\",\"qty\":\"-4\","
                + "\"status\":\"DRAFT\"}");
        String issueId = MAPPER.readTree(draft.getResponse().getContentAsString())
                .get("id").asString();
        String version = MAPPER.readTree(draft.getResponse().getContentAsString())
                .get("version").asString();
        MvcResult posted = mockMvc.perform(patch("/api/v1/runtime/StockLedger/" + issueId).with(jwtFor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version + ",\"status\":\"POSTED\"}"))
                .andReturn();
        if (posted.getResponse().getStatus() != 200) {
            throw new IllegalStateException("post " + posted.getResponse().getStatus()
                    + ": " + posted.getResponse().getContentAsString());
        }

        String shaped = mockMvc.perform(get("/api/v1/runtime/StockLedger/" + issueId).with(jwtFor()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var unitCost = MAPPER.readTree(shaped).get("unitCost");
        assertThat(unitCost == null || unitCost.isNull())
                .as("the guard-false leg left the costing to a later receipt (the script's no-op; "
                    + "the shaped projection omits null fields)")
                .isTrue();
    }
}
