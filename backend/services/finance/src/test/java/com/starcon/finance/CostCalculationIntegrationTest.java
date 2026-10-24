package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.CostCalculationModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 12.4.1.2 成本计算执行的业务断言，跑在**真实 PostgreSQL 16** 上。
 *
 * <p>覆盖蓝图 12-finance.md 里 12.4.1.2 的四个要点与「关键约束」：
 *
 * <ul>
 *   <li>「对销售出库、发货、平台仓转出计算应结转成本」⇒ 三类业务类型都能识别，
 *       出库量取自 warehouse 的真实带符号分录（取绝对值）；</li>
 *   <li>「生成成本层与成本分录」⇒ 成本层行（按批次扣减）与成本分录行（按出库记账）
 *       两张表都真的落库，数字逐位相等；</li>
 *   <li>「支持按期间批量重算与差异对比」⇒ 重算<b>另起批次</b>、旧批次不被改写，
 *       差异对比逐 SKU 给出差额；</li>
 *   <li>「<b>数量账在 warehouse、价值账在 finance</b>——重算不能写库存」
 *       ⇒ 集成测试直接断言：跑完一轮计算与重算之后，
 *       warehouse 的流水一条都没变（成本计算<b>不写库存</b>）；</li>
 *   <li>「金额一律 BigDecimal + NUMERIC，禁止浮点」⇒ 金额是四位小数的 numeric，逐位相等；
 *       数量是六位小数的 numeric；</li>
 *   <li>「币种不许跨币种相加」⇒ 异币种成本层被排除并如实报出。</li>
 * </ul>
 *
 * <p><b>上游用真实的 HTTP 服务模拟</b>（{@code com.sun.net.httpserver}，与同服务其它模块逐字同构）：
 * identity 返回成员关系、warehouse 返回库存流水台账（列表 + 分录下钻）、
 * catalog 返回成本构成（真实到货记录派生的成本层）。
 * 上游读不到时本模块一律 <b>503</b>，不用空集合冒充「这个期间没有出库」。
 *
 * <p><b>断言风格与本仓其它模块一致</b>：业务规则与拒绝理由走**服务层**
 * （{@code assertThatThrownBy(...).hasStackTraceContaining(...)}），HTTP 层只断言**状态码**。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CostCalculationIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID legalEntityId = UUID.randomUUID();
  static final UUID skuId = UUID.randomUUID();
  static final UUID txA = UUID.randomUUID();
  static final UUID txB = UUID.randomUUID();
  static final UUID txC = UUID.randomUUID();

  static final List<String> PERMISSIONS =
      List.of(
          "finance.cost_calculation.read",
          "finance.cost_calculation.create",
          "finance.cost_calculation.update");

  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  /** 记录对上游的调用次数（读 / 写）。 */
  static final java.util.concurrent.atomic.AtomicInteger ledgerCalls =
      new java.util.concurrent.atomic.AtomicInteger();
  static final java.util.concurrent.atomic.AtomicInteger drillCalls =
      new java.util.concurrent.atomic.AtomicInteger();
  static final java.util.concurrent.atomic.AtomicInteger writeCalls =
      new java.util.concurrent.atomic.AtomicInteger();

  record UpstreamCalls(int ledger, int drill, int writeCalls) {}

  static UpstreamCalls upstreamCalls() {
    return new UpstreamCalls(
        ledgerCalls.get(), drillCalls.get(), writeCalls.get());
  }

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;
  static HttpServer warehouse;
  static HttpServer catalog;

  /** warehouse 的台账列表响应（期间出库的流水）。 */
  static volatile String ledgerPayload = "";

  /** warehouse 的分录下钻响应（流水 ID → 分录 JSON 数组）。 */
  static final Map<String, String> drillPayloads = new ConcurrentHashMap<>();

  /** catalog 的成本构成响应。 */
  static volatile String compositionPayload = "";

  /** warehouse 读不到时置 true（模拟上游 500）。 */
  static volatile boolean warehouseDown = false;

  /** catalog 读不到时置 true（模拟上游 500）。 */
  static volatile boolean catalogDown = false;

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("create role starcon_app nologin");
      s.execute("create role finance_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    actors.put(
        "session",
        new AccessService.Access(
            UUID.randomUUID(),
            tenant,
            "Test",
            UUID.randomUUID(),
            null,
            PERMISSIONS,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", PERMISSIONS))));

    try {
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            var access = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (access == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = json.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      identity.setExecutor(Executors.newFixedThreadPool(2));
      identity.start();

      warehouse = HttpServer.create(new InetSocketAddress(0), 0);
      warehouse.createContext(
          "/warehouse/inventory/ledger",
          e -> {
            if (!"GET".equals(e.getRequestMethod())) writeCalls.incrementAndGet();
            ledgerCalls.incrementAndGet();
            if (warehouseDown) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            byte[] body = ledgerPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      warehouse.createContext(
          "/warehouse/inventory/ledger/",
          e -> {
            if (!"GET".equals(e.getRequestMethod())) writeCalls.incrementAndGet();
            drillCalls.incrementAndGet();
            if (warehouseDown) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            var id = e.getRequestURI().getPath().substring("/warehouse/inventory/ledger/".length());
            var payload = drillPayloads.get(id);
            if (payload == null) {
              e.sendResponseHeaders(404, -1);
              e.close();
              return;
            }
            byte[] body = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      warehouse.setExecutor(Executors.newFixedThreadPool(2));
      warehouse.start();

      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/sku-cost-compositions",
          e -> {
            if (catalogDown) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            byte[] body = compositionPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      catalog.setExecutor(Executors.newFixedThreadPool(2));
      catalog.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "finance_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://127.0.0.1:" + catalog.getAddress().getPort());
    r.add("WAREHOUSE_URL", () -> "http://127.0.0.1:" + warehouse.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired CostCalculationService calculations;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_cost_calculation_entry, finance_cost_calculation_layer,"
              + " finance_cost_calculation_run, finance_cost_valuation_specified_cost,"
              + " finance_cost_valuation_method restart identity cascade");
    }
    warehouseDown = false;
    catalogDown = false;
    drillPayloads.clear();

    // 两层成本（同币种）+ 一层异币种（必须被排除）。
    compositionPayload =
        """
        {"skuId":"%s","skuCode":"SKU-001","skuName":"测试商品","currency":"USD",
         "lots":[
           {"lotRef":"LOT-A","receivedDate":"2025-12-01","quantity":100,"baseQuantity":100,
            "amount":1000.00,"unitCost":10.0000,"currency":"USD"},
           {"lotRef":"LOT-B","receivedDate":"2025-12-15","quantity":100,"baseQuantity":100,
            "amount":1200.00,"unitCost":12.0000,"currency":"USD"},
           {"lotRef":"LOT-EUR","receivedDate":"2025-12-20","quantity":100,"baseQuantity":100,
            "amount":900.00,"unitCost":9.0000,"currency":"EUR"}],
         "excludedDeliveryCount":0,"windowFrom":"2024-01-01","windowTo":"2026-12-31",
         "missingComponents":[]}
        """
            .formatted(skuId);

    // 两笔出库：一笔发货、一笔销售出库。
    ledgerPayload =
        """
        {"rows":[
          {"transactionId":"%s","kind":"SHIPMENT","refType":"SHIPMENT_HANDOVER","refId":"%s",
           "occurredAt":"2026-01-10T10:00:00Z","entryCount":1},
          {"transactionId":"%s","kind":"SHIPMENT","refType":"ORDER_OUT","refId":"%s",
           "occurredAt":"2026-01-20T10:00:00Z","entryCount":1}],
         "total":2}
        """
            .formatted(txA, UUID.randomUUID(), txB, UUID.randomUUID());
    drillPayloads.put(txA.toString(), drillJson("-30.000000"));
    drillPayloads.put(txB.toString(), drillJson("-80.000000"));
  }

  /** 在**租户事务里**跑一段工作（与生产同一条路径：RLS 靠它隔离）。 */
  <T> T as(java.util.function.Supplier<T> work) {
    return tenants.run(tenant, work);
  }

  /** warehouse 的分录下钻响应（7.1.2.1 的 drill 形状：{transaction, entries}）。 */
  static String drillJson(String onHandDelta) {
    return "{\"transaction\":{\"transactionId\":\"" + UUID.randomUUID() + "\"},"
        + "\"entries\":[{\"entryId\":\"" + UUID.randomUUID() + "\",\"skuId\":\"" + skuId + "\","
        + "\"skuCode\":\"SKU-001\",\"onHandDelta\":" + onHandDelta + "}]}";
  }

  /** 登记一版计价方法（在租户事务里 —— 与生产同一条路径）。 */
  void seedMethod(String method, String currency, OffsetDateTime effectiveFrom) {
    as(
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into finance_cost_valuation_method(id,tenant_id,scope_type,scope_ref_id,scope_code,"
                  + " scope_name,valuation_method,currency,effective_from,status,version,"
                  + " recalculation_status,recalculation_owner_module,created_at,updated_at)"
                  + " values(?,?,'LEGAL_ENTITY',?,'LE-US','美国主体',?,?,?::timestamptz,'ACTIVE',1,"
                  + " 'NOT_REQUIRED','12.4.1.2',?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              legalEntityId,
              method,
              currency,
              effectiveFrom,
              now,
              now);
          return null;
        });
  }

  /** 维护一条指定成本单价（12.4.1.1 的表，供指定成本法用）。 */
  void seedSpecifiedCost(BigDecimal unitCost) {
    as(
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into finance_cost_valuation_specified_cost(id,tenant_id,sku_id,sku_code,currency,"
                  + " unit_cost,effective_from,status,version,created_at,updated_at)"
                  + " values(?,?,?,'SKU-001','USD',?,?::timestamptz,'ACTIVE',1,"
                  + " ?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              skuId,
              unitCost,
              OffsetDateTime.parse("2025-01-01T00:00:00Z"),
              now,
              now);
          return null;
        });
  }

  /** 发起一次成本计算。 */
  FinanceCostCalculationRunRow calculate(
      String method, String currency, OffsetDateTime from, OffsetDateTime to, String batchRef) {
    return as(
        () ->
            calculations.calculate(
                new FinanceCostCalculationRequest(
                    "LEGAL_ENTITY",
                    legalEntityId,
                    "LE-US",
                    "美国主体",
                    method,
                    currency,
                    from,
                    to,
                    batchRef,
                    null,
                    null),
                actors.get("session"),
                "session"));
  }

  static final OffsetDateTime PERIOD_FROM = OffsetDateTime.parse("2026-01-01T00:00:00Z");
  static final OffsetDateTime PERIOD_TO = OffsetDateTime.parse("2026-02-01T00:00:00Z");

  // ==========================================================================
  // 能力声明 / 选项
  // ==========================================================================

  @Test
  void 能力声明把三个上游与边界说清楚() {
    var capabilities = as(() -> calculations.capabilities());
    assertThat(capabilities.capabilities())
        .as("能力声明必须逐维说清依据")
        .anyMatch(c -> c.basis().contains("finance_cost_calculation_run"))
        .anyMatch(c -> c.basis().contains("warehouse"))
        .anyMatch(c -> c.basis().contains("catalog"));
    assertThat(capabilities.capabilities())
        .as("必须写明「数量账在 warehouse、价值账在 finance」")
        .anyMatch(c -> c.detail().contains("数量账在 warehouse"));
    assertThat(capabilities.downstreamGaps())
        .as("12.4.1.3 与 13 必须如实记为未交付")
        .anyMatch(g -> g.module().equals("12.4.1.3") && !g.available())
        .anyMatch(g -> g.module().equals("13") && !g.available());
  }

  @Test
  void 选项里的四个计价方法复用12_4_1_1() {
    var options = as(() -> calculations.options());
    assertThat(options.methods())
        .as("方法选项必须复用 12.4.1.1 的那一份（口径说明只有一份）")
        .extracting(m -> m.value())
        .containsExactlyInAnyOrder("FIFO", "MOVING_AVERAGE", "BATCH", "SPECIFIED");
    assertThat(options.businessTypes()).containsExactlyInAnyOrder("SALES_OUTBOUND", "SHIPMENT", "PLATFORM_TRANSFER_OUT");
  }

  // ==========================================================================
  // 先进先出：成本层 + 成本分录
  // ==========================================================================

  @Test
  void 先进先出按到货顺序消耗成本层并算出结转金额() {
    seedMethod("FIFO", "USD", OffsetDateTime.parse("2025-01-01T00:00:00Z"));
    var run = calculate("FIFO", "USD", PERIOD_FROM, PERIOD_TO, null);

    assertThat(run.status()).isEqualTo("COMPLETED");
    assertThat(run.valuationMethod()).isEqualTo("FIFO");
    assertThat(run.valuationMethodVersion()).as("必须记下当时生效的那一版配置").isEqualTo(1);
    assertThat(run.entryCount()).isEqualTo(2);
    assertThat(run.layerCount()).isEqualTo(2);
    assertThat(run.skuCount()).isEqualTo(1);
    assertThat(run.incompleteEntryCount()).isZero();

    // 30 件从 LOT-A（10.0000）→ 300.0000；80 件 = LOT-A 剩余 70 件（700.0000）+ LOT-B 10 件（120.0000）。
    assertThat(run.totalQuantity()).isEqualByComparingTo("110.000000");
    assertThat(run.totalCoveredQuantity()).isEqualByComparingTo("110.000000");
    assertThat(run.totalShortfallQuantity()).isEqualByComparingTo("0.000000");
    assertThat(run.totalCostAmount()).isEqualByComparingTo("1120.0000");

    var detail = as(() -> calculations.runDetail(run.id()));
    assertThat(detail.layers()).hasSize(2);
    var lotA =
        detail.layers().stream().filter(l -> l.lotRef().equals("LOT-A")).findFirst().orElseThrow();
    assertThat(lotA.layerQuantity()).isEqualByComparingTo("100.000000");
    assertThat(lotA.layerAmount()).isEqualByComparingTo("1000.0000");
    assertThat(lotA.consumedQuantity()).isEqualByComparingTo("100.000000");
    assertThat(lotA.consumedAmount()).isEqualByComparingTo("1000.0000");
    assertThat(lotA.unitCost()).isEqualByComparingTo("10.0000");

    var lotB =
        detail.layers().stream().filter(l -> l.lotRef().equals("LOT-B")).findFirst().orElseThrow();
    assertThat(lotB.consumedQuantity()).isEqualByComparingTo("10.000000");
    assertThat(lotB.consumedAmount()).isEqualByComparingTo("120.0000");
    assertThat(lotB.unitCost()).isEqualByComparingTo("12.0000");

    // 异币种层被排除，绝不跨币种相加。
    assertThat(detail.layers()).noneMatch(l -> l.lotRef().equals("LOT-EUR"));
    assertThat(run.sourceNote()).contains("绝不跨币种相加");

    // 分录：按出库记账的口径。
    assertThat(detail.entries()).hasSize(2);
    var first = detail.entries().get(0);
    assertThat(first.flowTransactionId()).isEqualTo(txA);
    assertThat(first.businessType()).isEqualTo("SHIPMENT");
    assertThat(first.issueQuantity()).isEqualByComparingTo("30.000000");
    assertThat(first.coveredQuantity()).isEqualByComparingTo("30.000000");
    assertThat(first.shortfallQuantity()).isEqualByComparingTo("0.000000");
    assertThat(first.unitCost()).isEqualByComparingTo("10.0000");
    assertThat(first.costAmount()).isEqualByComparingTo("300.0000");
    assertThat(first.status()).isEqualTo("CALCULATED");

    var second = detail.entries().get(1);
    assertThat(second.businessType()).as("非发货交接单 ⇒ 销售出库").isEqualTo("SALES_OUTBOUND");
    assertThat(second.issueQuantity()).isEqualByComparingTo("80.000000");
    assertThat(second.costAmount()).isEqualByComparingTo("820.0000");
    assertThat(second.layerCount()).isEqualTo(2);
  }

  @Test
  void 成本层不足时给出缺口而不是把成本算成零() {
    seedMethod("FIFO", "USD", OffsetDateTime.parse("2025-01-01T00:00:00Z"));
    // 200 件 > 剩余可消耗的 110 件。
    ledgerPayload =
        """
        {"rows":[{"transactionId":"%s","kind":"SHIPMENT","refType":"SHIPMENT_HANDOVER","refId":"%s",
          "occurredAt":"2026-01-10T10:00:00Z","entryCount":1}],"total":1}
        """
            .formatted(txC, UUID.randomUUID());
    drillPayloads.put(txC.toString(), drillJson("-250.000000"));

    var run = calculate("FIFO", "USD", PERIOD_FROM, PERIOD_TO, null);
    assertThat(run.incompleteEntryCount()).isEqualTo(1);
    assertThat(run.totalShortfallQuantity()).isEqualByComparingTo("50.000000");
    assertThat(run.totalCostAmount()).isEqualByComparingTo("2200.0000");
    assertThat(run.sourceNote()).contains("存在缺口");

    var detail = as(() -> calculations.runDetail(run.id()));
    var entry = detail.entries().get(0);
    assertThat(entry.status()).isEqualTo("INCOMPLETE");
    assertThat(entry.shortfallQuantity()).isEqualByComparingTo("50.000000");
    assertThat(entry.unitCost()).as("有缺口时单价必须留空（半截成本不能当完整单价）").isNull();
    assertThat(entry.gapNote()).isNotBlank();
  }

  @Test
  void 覆盖量加缺口量必须等于出库量() {
    seedMethod("FIFO", "USD", OffsetDateTime.parse("2025-01-01T00:00:00Z"));
    // 直接建一个**未完成**的批次：这样触发的是「覆盖量 + 缺口量 = 出库量」这一条，
    // 而不是「已完成批次不可改写」那一条（后者会在 UPDATE 时先挡住）。
    var runId = UUID.randomUUID();
    as(
        () -> {
          sql.execute(
              "insert into finance_cost_calculation_run(id,tenant_id,scope_type,scope_ref_id,scope_code,"
                  + " scope_name,valuation_method,valuation_method_version,currency,period_from,period_to,"
                  + " recalculation_reason,status,created_at)"
                  + " values(?,?,'LEGAL_ENTITY',?,'LE-US','美国主体','FIFO',1,'USD',"
                  + " ?::timestamptz,?::timestamptz,'INITIAL','PENDING',?::timestamptz)",
              runId, tenant, legalEntityId, PERIOD_FROM, PERIOD_TO, OffsetDateTime.now(ZoneOffset.UTC));
          return null;
        });
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "insert into finance_cost_calculation_entry(id,tenant_id,run_id,"
                              + " flow_transaction_id,flow_ref_type,flow_kind,occurred_at,business_type,"
                              + " sku_id,sku_code,issue_quantity,covered_quantity,shortfall_quantity,"
                              + " cost_amount,currency,layer_count,status,created_at)"
                              + " values(?,?,?,?,'ORDER_OUT','SHIPMENT',?::timestamptz,'SALES_OUTBOUND',"
                              + " ?,'SKU-001',30.000000,1.000000,1.000000,10.0000,'USD',1,'CALCULATED',"
                              + " ?::timestamptz)",
                          UUID.randomUUID(), tenant, runId, txA, PERIOD_FROM, skuId,
                          OffsetDateTime.now(ZoneOffset.UTC));
                      return null;
                    }))
        .as("覆盖量 + 缺口量 = 出库量是数据层不变量")
        .hasStackTraceContaining("必须等于出库数量");
  }

  // ==========================================================================
  // 其它计价方法
  // ==========================================================================

  @Test
  void 移动加权平均按可用成本层的加权平均计价() {
    seedMethod("MOVING_AVERAGE", "USD", OffsetDateTime.parse("2025-01-01T00:00:00Z"));
    var run = calculate("MOVING_AVERAGE", "USD", PERIOD_FROM, PERIOD_TO, null);
    // 可用层：LOT-A 100 件 1000 元 + LOT-B 100 件 1200 元 ⇒ 加权平均 11.0000。
    // 30 件 → 330.0000；剩余 170 件 / 1870 元 ⇒ 11.0000；80 件 → 880.0000。
    assertThat(run.totalCostAmount()).isEqualByComparingTo("1210.0000");
    var detail = as(() -> calculations.runDetail(run.id()));
    assertThat(detail.entries().get(0).unitCost()).isEqualByComparingTo("11.0000");
  }

  @Test
  void 批次成本法必须指定批次否则算不出来() {
    seedMethod("BATCH", "USD", OffsetDateTime.parse("2025-01-01T00:00:00Z"));
    var run = calculate("BATCH", "USD", PERIOD_FROM, PERIOD_TO, null);
    assertThat(run.incompleteEntryCount()).isEqualTo(2);
    var detail = as(() -> calculations.runDetail(run.id()));
    assertThat(detail.entries()).allSatisfy(e -> assertThat(e.unitCost()).isNull());
    assertThat(detail.entries().get(0).gapNote()).contains("批次");

    // 指定了批次 ⇒ 只消耗那一层。
    var withBatch = calculate("BATCH", "USD", PERIOD_FROM, PERIOD_TO, "LOT-B");
    var detail2 = as(() -> calculations.runDetail(withBatch.id()));
    assertThat(detail2.layers()).hasSize(1);
    assertThat(detail2.layers().get(0).lotRef()).isEqualTo("LOT-B");
    // 30 件 × 12.0000 = 360.0000。
    assertThat(detail2.entries().get(0).costAmount()).isEqualByComparingTo("360.0000");
  }

  @Test
  void 指定成本法用12_4_1_1维护的单价() {
    seedMethod("SPECIFIED", "USD", OffsetDateTime.parse("2025-01-01T00:00:00Z"));
    seedSpecifiedCost(new BigDecimal("9.5000"));
    var run = calculate("SPECIFIED", "USD", PERIOD_FROM, PERIOD_TO, null);
    // 30 × 9.5000 = 285.0000；80 × 9.5000 = 760.0000。
    assertThat(run.totalCostAmount()).isEqualByComparingTo("1045.0000");
    var detail = as(() -> calculations.runDetail(run.id()));
    assertThat(detail.entries().get(0).unitCost()).isEqualByComparingTo("9.5000");
  }

  @Test
  void 指定成本法没有维护单价时算不出来() {
    seedMethod("SPECIFIED", "USD", OffsetDateTime.parse("2025-01-01T00:00:00Z"));
    var run = calculate("SPECIFIED", "USD", PERIOD_FROM, PERIOD_TO, null);
    assertThat(run.incompleteEntryCount()).isEqualTo(2);
    var detail = as(() -> calculations.runDetail(run.id()));
    assertThat(detail.entries().get(0).gapNote()).contains("未维护");
    assertThat(detail.entries().get(0).unitCost()).isNull();
  }

  // ==========================================================================
  // 重算与差异对比
  // ==========================================================================

  @Test
  void 重算另起批次且旧批次不被改写() {
    seedMethod("FIFO", "USD", OffsetDateTime.parse("2025-01-01T00:00:00Z"));
    var first = calculate("FIFO", "USD", PERIOD_FROM, PERIOD_TO, null);
    assertThat(first.totalCostAmount()).isEqualByComparingTo("1120.0000");

    // 切换计价方法（追加新版本，12.4.1.1 的版本链 —— 新版本必须接在上一版之后）。
    as(
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          var previousId =
              sql.fetchOne(
                      "select id from finance_cost_valuation_method"
                          + " where tenant_id = ? and scope_ref_id = ? order by version desc limit 1",
                      tenant, legalEntityId)
                  .get("id", UUID.class);
          sql.execute(
              "update finance_cost_valuation_method set status = 'SUPERSEDED',"
                  + " effective_to = ?::timestamptz, recalculation_status = 'PENDING',"
                  + " updated_at = ?::timestamptz where tenant_id = ? and scope_ref_id = ?",
              OffsetDateTime.parse("2026-01-01T00:00:00Z"),
              now,
              tenant,
              legalEntityId);
          sql.execute(
              "insert into finance_cost_valuation_method(id,tenant_id,scope_type,scope_ref_id,scope_code,"
                  + " scope_name,valuation_method,currency,effective_from,status,version,supersedes_id,"
                  + " recalculation_status,recalculation_owner_module,created_at,updated_at)"
                  + " values(?,?,'LEGAL_ENTITY',?,'LE-US','美国主体','MOVING_AVERAGE','USD',"
                  + " ?::timestamptz,'ACTIVE',2,?,'PENDING','12.4.1.2',?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              legalEntityId,
              OffsetDateTime.parse("2026-01-01T00:00:00Z"),
              previousId,
              now,
              now);
          return null;
        });

    var second = as(() -> calculations.recalculate(first.id(), actors.get("session"), "session"));
    assertThat(second.id()).isNotEqualTo(first.id());
    assertThat(second.supersedesRunId()).isEqualTo(first.id());
    assertThat(second.valuationMethod()).isEqualTo("MOVING_AVERAGE");
    assertThat(second.totalCostAmount()).isEqualByComparingTo("1210.0000");

    // 旧批次**不被改写**：数字原样，状态仍是 COMPLETED。
    var old = as(() -> calculations.runs(null, null, null, null, 0, 50).items().stream()
        .filter(r -> r.id().equals(first.id())).findFirst().orElseThrow());
    assertThat(old.totalCostAmount()).as("旧批次是当时那笔计算的证据，不被改写")
        .isEqualByComparingTo("1120.0000");
    assertThat(old.supersededByRunId()).isEqualTo(second.id());

    // 重算成功后，12.4.1.1 的重算标记被改回「无需重算」（只改取值，不改表结构）。
    var pending =
        as(
            () ->
                sql.fetchOne(
                        "select count(*) from finance_cost_valuation_method"
                            + " where tenant_id = ? and recalculation_status = 'PENDING'",
                        tenant)
                    .get(0, Long.class));
    assertThat(pending).as("重算成功后重算标记必须被清掉").isZero();
  }

  @Test
  void 差异对比逐sku给出两次批次的差额() {
    seedMethod("FIFO", "USD", OffsetDateTime.parse("2025-01-01T00:00:00Z"));
    var first = calculate("FIFO", "USD", PERIOD_FROM, PERIOD_TO, null);
    var second = calculate("MOVING_AVERAGE", "USD", PERIOD_FROM, PERIOD_TO, null);

    var comparison = as(() -> calculations.variance(first.id(), second.id()));
    assertThat(comparison.totalCostAmountDifference())
        .as("1210.0000 - 1120.0000 = 90.0000")
        .isEqualByComparingTo("90.0000");
    assertThat(comparison.comparedSkuCount()).isEqualTo(1);
    assertThat(comparison.rows()).hasSize(1);
    var row = comparison.rows().get(0);
    assertThat(row.skuId()).isEqualTo(skuId);
    assertThat(row.fromCostAmount()).isEqualByComparingTo("1120.0000");
    assertThat(row.toCostAmount()).isEqualByComparingTo("1210.0000");
    assertThat(row.costAmountDifference()).isEqualByComparingTo("90.0000");
    assertThat(row.quantityDifference()).isEqualByComparingTo("0.000000");
  }

  @Test
  void 已完成的批次不可改写() {
    seedMethod("FIFO", "USD", OffsetDateTime.parse("2025-01-01T00:00:00Z"));
    var run = calculate("FIFO", "USD", PERIOD_FROM, PERIOD_TO, null);
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "update finance_cost_calculation_run set total_cost_amount = 0"
                              + " where tenant_id = ? and id = ?",
                          tenant, run.id());
                      return null;
                    }))
        .as("历史即证据：已完成的批次不可改写")
        .hasStackTraceContaining("不可改写");
  }

  @Test
  void 已完成的批次的结果行不可删除() {
    seedMethod("FIFO", "USD", OffsetDateTime.parse("2025-01-01T00:00:00Z"));
    var run = calculate("FIFO", "USD", PERIOD_FROM, PERIOD_TO, null);
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "delete from finance_cost_calculation_entry where tenant_id = ? and run_id = ?",
                          tenant, run.id());
                      return null;
                    }))
        .hasStackTraceContaining("不可改写或删除");
  }

  // ==========================================================================
  // 边界：上游读不到 / 币种 / 期间
  // ==========================================================================

  @Test
  void 上游读不到一律503而不是空结果() {
    seedMethod("FIFO", "USD", OffsetDateTime.parse("2025-01-01T00:00:00Z"));
    warehouseDown = true;
    assertThatThrownBy(() -> calculate("FIFO", "USD", PERIOD_FROM, PERIOD_TO, null))
        .hasStackTraceContaining("读不到");

    warehouseDown = false;
    catalogDown = true;
    assertThatThrownBy(() -> calculate("FIFO", "USD", PERIOD_FROM, PERIOD_TO, null))
        .hasStackTraceContaining("读不到");
  }

  @Test
  void 币种与配置不一致时明确报错而不是静默换算() {
    seedMethod("FIFO", "USD", OffsetDateTime.parse("2025-01-01T00:00:00Z"));
    assertThatThrownBy(() -> calculate("FIFO", "EUR", PERIOD_FROM, PERIOD_TO, null))
        .as("成本绝不跨币种相加")
        .hasStackTraceContaining("绝不跨币种相加");
  }

  @Test
  void 期间必须合法() {
    seedMethod("FIFO", "USD", OffsetDateTime.parse("2025-01-01T00:00:00Z"));
    assertThatThrownBy(() -> calculate("FIFO", "USD", PERIOD_TO, PERIOD_FROM, null))
        .hasStackTraceContaining("止必须晚于起");
  }

  @Test
  void 没有生效配置又没指定方法时明确报错() {
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        calculations.calculate(
                            new FinanceCostCalculationRequest(
                                "LEGAL_ENTITY",
                                legalEntityId,
                                "LE-US",
                                "美国主体",
                                null,
                                "USD",
                                PERIOD_FROM,
                                PERIOD_TO,
                                null,
                                null,
                                null),
                            actors.get("session"),
                            "session")))
        .as("不能替用户决定计价政策")
        .hasStackTraceContaining("没有生效的计价方法配置");
  }

  // ==========================================================================
  // 数量账在 warehouse、价值账在 finance
  // ==========================================================================

  @Test
  void 成本计算不写库存只写价值() {
    seedMethod("FIFO", "USD", OffsetDateTime.parse("2025-01-01T00:00:00Z"));
    var before = upstreamCalls();
    calculate("FIFO", "USD", PERIOD_FROM, PERIOD_TO, null);
    var after = upstreamCalls();

    assertThat(after.ledger()).as("只读上游：读台账一次").isEqualTo(before.ledger() + 1);
    assertThat(after.drill()).as("只读上游：下钻两笔").isEqualTo(before.drill() + 2);
    assertThat(after.writeCalls()).as("上游一次都不许被写 —— 重算不能写库存").isZero();
  }

  // ==========================================================================
  // HTTP 层：只断言状态码
  // ==========================================================================

  @Test
  void 端点返回真实数据() throws Exception {
    seedMethod("FIFO", "USD", OffsetDateTime.parse("2025-01-01T00:00:00Z"));
    http.perform(get("/finance/cost-calculation/capabilities").header("Authorization", "session"))
        .andExpect(status().isOk());
    http.perform(get("/finance/cost-calculation/options").header("Authorization", "session"))
        .andExpect(status().isOk());

    var body =
        """
        {"scopeType":"LEGAL_ENTITY","scopeRefId":"%s","scopeCode":"LE-US","scopeName":"美国主体",
         "valuationMethod":"FIFO","currency":"USD","periodFrom":"2026-01-01T00:00:00Z",
         "periodTo":"2026-02-01T00:00:00Z"}
        """
            .formatted(legalEntityId);
    http.perform(
            post("/finance/cost-calculation/runs")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());

    http.perform(get("/finance/cost-calculation/runs").header("Authorization", "session"))
        .andExpect(status().isOk());
    http.perform(get("/finance/cost-calculation/entries").header("Authorization", "session"))
        .andExpect(status().isOk());
    http.perform(get("/finance/cost-calculation/layers").header("Authorization", "session"))
        .andExpect(status().isOk());
  }
}
