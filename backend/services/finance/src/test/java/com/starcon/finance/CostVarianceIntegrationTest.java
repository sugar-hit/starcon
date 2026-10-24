package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.CostVarianceModels.*;
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
 * 12.4.1.3 成本差异分析的业务断言，跑在**真实 PostgreSQL 16** 上。
 *
 * <p>覆盖蓝图 12-finance.md 里 12.4.1.3 的五个要点与「关键约束」：
 *
 * <ul>
 *   <li>「实际成本 vs 预算/标准成本」⇒ 标准成本 = 标准单价 × 出库量，总差异 = 实际 − 标准，逐位相等；</li>
 *   <li>「采购价波动、头程成本波动、损耗差异、汇率影响」⇒ 五类差异**各自独立可看**，各算各的；</li>
 *   <li>「定位利润波动主因」⇒ 按币种排名，rank = 1 且金额非零的那一类是主因；</li>
 *   <li>「差异分析报表 + 主因归因 + 异常提示」⇒ 三样都出，且异常提示能定位到 SKU；</li>
 *   <li>🔴 <b>基线缺失 ≠ 差异为 0</b> ⇒ 无基线的 SKU 的金额是<b>空</b>、可比性是
 *       {@code NO_BASELINE}，**绝不是 0**；</li>
 *   <li>🔴 <b>多币种绝不相加</b> ⇒ 两个币种分两组呈现，金额互不混合；</li>
 *   <li>🔴 <b>上游读不到就说读不到</b> ⇒ 汇率 / 采购价 / 头程 / 损耗读不到时，
 *       那一类差异是<b>不可计算</b>，报表其余部分照常出。</li>
 * </ul>
 *
 * <p><b>上游用真实的 HTTP 服务模拟</b>（{@code com.sun.net.httpserver}，与同服务其它模块逐字同构）：
 * identity 返回成员关系、warehouse 返回库存流水台账（列表 + 分录下钻）与头程发运单、
 * catalog 返回汇率事实、trade 返回采购成本事实。
 *
 * <p><b>断言风格与本仓其它模块一致</b>：业务规则与拒绝理由走**服务层**
 * （{@code assertThatThrownBy(...).hasStackTraceContaining(...)}），HTTP 层只断言**状态码**。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CostVarianceIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID legalEntityId = UUID.randomUUID();
  static final UUID skuA = UUID.randomUUID();
  static final UUID skuB = UUID.randomUUID();
  static final UUID skuC = UUID.randomUUID();
  static final UUID runId = UUID.randomUUID();
  static final UUID runIdEur = UUID.randomUUID();
  /** 损耗用的一笔调整流水。 */
  static final UUID adjustmentTx = UUID.randomUUID();

  static final List<String> PERMISSIONS =
      List.of("finance.cost_variance.read", "finance.cost_variance.create");

  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;
  static HttpServer warehouse;
  static HttpServer catalog;
  static HttpServer trade;

  /** catalog 读不到汇率时置 true。 */
  static volatile boolean catalogDown = false;

  /** trade 读不到采购事实时置 true。 */
  static volatile boolean tradeDown = false;

  /** warehouse 读不到时置 true。 */
  static volatile boolean warehouseDown = false;

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

      // ---- warehouse：库存流水台账（列表 + 下钻）与头程发运单 ----
      warehouse = HttpServer.create(new InetSocketAddress(0), 0);
      warehouse.createContext(
          "/warehouse/inventory/ledger",
          e -> {
            if (warehouseDown) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            byte[] body =
                """
                {"rows":[{"transactionId":"%s","kind":"ADJUSTMENT","refType":"STOCKTAKE",
                  "occurredAt":"2026-01-15T08:00:00Z","entryCount":1}],"total":1}
                """
                    .formatted(adjustmentTx)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      warehouse.createContext(
          "/warehouse/inventory/ledger/",
          e -> {
            if (warehouseDown) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            byte[] body =
                ("{\"transaction\":{\"transactionId\":\"" + adjustmentTx + "\"},"
                        + "\"entries\":[{\"entryId\":\""
                        + UUID.randomUUID()
                        + "\",\"skuId\":\""
                        + skuA
                        + "\",\"skuCode\":\"SKU-A\",\"onHandDelta\":-2.000000}]}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      warehouse.createContext(
          "/warehouse/first-mile-shipments",
          e -> {
            if (warehouseDown) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            byte[] body =
                """
                {"rows":[
                  {"id":"%s","shipmentCode":"FM-2601","carrierName":"某承运商","currency":"USD",
                   "freightCost":400.0000,"insuranceCost":50.0000,"handlingCost":50.0000,
                   "totalCost":500.0000,"status":"DISPATCHED","dispatchedAt":"2026-01-05T00:00:00Z"},
                  {"id":"%s","shipmentCode":"FM-2512","carrierName":"某承运商","currency":"USD",
                   "freightCost":300.0000,"insuranceCost":50.0000,"handlingCost":50.0000,
                   "totalCost":400.0000,"status":"DISPATCHED","dispatchedAt":"2025-12-05T00:00:00Z"}],
                 "page":0,"size":200,"total":2}
                """
                    .formatted(UUID.randomUUID(), UUID.randomUUID())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      warehouse.setExecutor(Executors.newFixedThreadPool(2));
      warehouse.start();

      // ---- catalog：汇率事实 ----
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/exchange-rates/latest",
          e -> {
            if (catalogDown) {
              e.sendResponseHeaders(404, -1);
              e.close();
              return;
            }
            var asOf = query(e.getRequestURI().getRawQuery(), "asOf");
            // 期初 7.0000，期末 8.0000（USD → CNY）。
            var rate = asOf != null && asOf.compareTo("2026-02-01") < 0 ? "7.00000000" : "8.00000000";
            byte[] body =
                ("{\"baseCurrency\":\"USD\",\"quoteCurrency\":\"CNY\",\"rate\":" + rate
                        + ",\"effectiveDate\":\""
                        + (asOf == null ? "2026-02-01" : asOf)
                        + "\",\"sourceCode\":\"TEST\",\"display\":\"1 USD = "
                        + rate
                        + " CNY\"}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      catalog.setExecutor(Executors.newFixedThreadPool(2));
      catalog.start();

      // ---- trade：采购成本事实 ----
      trade = HttpServer.create(new InetSocketAddress(0), 0);
      trade.createContext(
          "/trade/purchase-analytics/facts",
          e -> {
            if (tradeDown) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            var from = query(e.getRequestURI().getRawQuery(), "from");
            var to = query(e.getRequestURI().getRawQuery(), "to");
            var facts = new java.util.ArrayList<String>();
            if ("2026-01-10".compareTo(from) >= 0 && "2026-01-10".compareTo(to) <= 0) {
              facts.add(
                  "{\"id\":\""
                      + UUID.randomUUID()
                      + "\",\"factKind\":\"COST\",\"sourceType\":\"ARRIVAL_RECEIPT\","
                      + "\"occurredDate\":\"2026-01-10\",\"skuRefId\":\""
                      + skuA
                      + "\",\"skuCode\":\"SKU-A\",\"skuName\":\"测试商品A\","
                      + "\"quantity\":10,\"baseQuantity\":10,\"unitPrice\":11.000000,"
                      + "\"amount\":110.0000,\"currency\":\"USD\"}");
            }
            if ("2025-12-10".compareTo(from) >= 0 && "2025-12-10".compareTo(to) <= 0) {
              facts.add(
                  "{\"id\":\""
                      + UUID.randomUUID()
                      + "\",\"factKind\":\"COST\",\"sourceType\":\"ARRIVAL_RECEIPT\","
                      + "\"occurredDate\":\"2025-12-10\",\"skuRefId\":\""
                      + skuA
                      + "\",\"skuCode\":\"SKU-A\",\"skuName\":\"测试商品A\","
                      + "\"quantity\":10,\"baseQuantity\":10,\"unitPrice\":10.000000,"
                      + "\"amount\":100.0000,\"currency\":\"USD\"}");
            }
            byte[] body =
                ("{\"items\":["
                        + String.join(",", facts)
                        + "],\"total\":"
                        + facts.size()
                        + ",\"page\":0,\"size\":200}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      trade.setExecutor(Executors.newFixedThreadPool(2));
      trade.start();
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
    r.add("TRADE_URL", () -> "http://127.0.0.1:" + trade.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired CostVarianceService variance;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_cost_variance_item, finance_cost_variance_driver,"
              + " finance_cost_variance_alert, finance_cost_variance_report,"
              + " finance_cost_variance_baseline, finance_cost_calculation_entry,"
              + " finance_cost_calculation_layer, finance_cost_calculation_run"
              + " restart identity cascade");
    }
    catalogDown = false;
    tradeDown = false;
    warehouseDown = false;
    seedRun();
    seedBaseline();
  }

  /** 在**租户事务里**跑一段工作（与生产同一条路径：RLS 靠它隔离）。 */
  <T> T as(java.util.function.Supplier<T> work) {
    return tenants.run(tenant, work);
  }

  /** 12.4.1.2 的一个已完成批次 + 三条成本分录（USD 两笔、EUR 一笔）。 */
  void seedRun() {
    as(
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into finance_cost_calculation_run(id,tenant_id,scope_type,scope_ref_id,scope_code,"
                  + " scope_name,valuation_method,valuation_method_version,currency,period_from,period_to,"
                  + " recalculation_reason,status,total_quantity,total_cost_amount,created_at,completed_at)"
                  + " values(?,?,'LEGAL_ENTITY',?,'LE-US','美国主体','FIFO',1,'USD',?::timestamptz,"
                  + " ?::timestamptz,'INITIAL','COMPLETED',25.000000,340.0000,?::timestamptz,"
                  + " ?::timestamptz)",
              runId,
              tenant,
              legalEntityId,
              OffsetDateTime.parse("2026-01-01T00:00:00Z"),
              OffsetDateTime.parse("2026-02-01T00:00:00Z"),
              now,
              now);
          sql.execute(
              "insert into finance_cost_calculation_run(id,tenant_id,scope_type,scope_ref_id,scope_code,"
                  + " scope_name,valuation_method,valuation_method_version,currency,period_from,period_to,"
                  + " recalculation_reason,status,total_quantity,total_cost_amount,created_at,completed_at)"
                  + " values(?,?,'LEGAL_ENTITY',?,'LE-US','美国主体','FIFO',1,'EUR',?::timestamptz,"
                  + " ?::timestamptz,'INITIAL','COMPLETED',5.000000,120.0000,?::timestamptz,"
                  + " ?::timestamptz)",
              runIdEur,
              tenant,
              legalEntityId,
              OffsetDateTime.parse("2026-01-01T00:00:00Z"),
              OffsetDateTime.parse("2026-02-01T00:00:00Z"),
              now,
              now);
          // SKU-A：出库 10，结转 10，单价 12 → 实际成本 120.0000。
          seedEntry(runId, skuA, "SKU-A", "10", "10", "12.0000", "120.0000", "USD", "CALCULATED");
          // SKU-B：出库 5，结转 5，单价 20 → 实际成本 100.0000（**故意不给基线**）。
          seedEntry(runId, skuB, "SKU-B", "5", "5", "20.0000", "100.0000", "USD", "CALCULATED");
          // SKU-C：EUR 币种的那笔（验证多币种分组）。
          seedEntry(runIdEur, skuC, "SKU-C", "5", "5", "24.0000", "120.0000", "EUR", "CALCULATED");
          return null;
        });
  }

  void seedEntry(
      UUID run,
      UUID sku,
      String code,
      String issueQty,
      String coveredQty,
      String unitCost,
      String costAmount,
      String currency,
      String status) {
    sql.execute(
        "insert into finance_cost_calculation_entry(id,tenant_id,run_id,flow_transaction_id,"
            + " flow_ref_type,flow_kind,occurred_at,business_type,sku_id,sku_code,issue_quantity,"
            + " covered_quantity,shortfall_quantity,unit_cost,cost_amount,currency,layer_count,status,"
            + " created_at)"
            + " values(?,?,?,?,'ORDER_OUT','SHIPMENT',?::timestamptz,'SALES_OUTBOUND',?,?,?,?,0.000000,"
            + " ?,?,?,1,?,?::timestamptz)",
        UUID.randomUUID(),
        tenant,
        run,
        UUID.randomUUID(),
        OffsetDateTime.parse("2026-01-10T10:00:00Z"),
        sku,
        code,
        new BigDecimal(issueQty),
        new BigDecimal(coveredQty),
        new BigDecimal(unitCost),
        new BigDecimal(costAmount),
        currency,
        status,
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  /** 给 SKU-A 登记一版标准成本（USD，单价 10.0000）。 */
  void seedBaseline() {
    as(
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into finance_cost_variance_baseline(id,tenant_id,baseline_type,dimension,"
                  + " dimension_key,dimension_ref_id,dimension_code,dimension_name,currency,unit_cost,"
                  + " effective_from,effective_to,status,version,note,created_at,updated_at)"
                  + " values(?,?,'STANDARD_COST','SKU',?,?,'SKU-A','测试商品A','USD',10.0000,"
                  + " ?::timestamptz,null,'ACTIVE',1,'初始标准成本',?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              skuA.toString(),
              skuA,
              OffsetDateTime.parse("2025-12-01T00:00:00Z"),
              now,
              now);
          return null;
        });
  }

  /** 跑一次差异分析（服务端直调，走与 HTTP 端点同一条路径）。 */
  FinanceCostVarianceReportDetail analyze(String reportingCurrency) {
    var actor = actors.get("session");
    return as(
        () ->
            variance.analyze(
                new FinanceCostVarianceReportRequest(
                    "LEGAL_ENTITY",
                    legalEntityId,
                    "LE-US",
                    "美国主体",
                    OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                    OffsetDateTime.parse("2026-02-01T00:00:00Z"),
                    reportingCurrency,
                    "STANDARD_COST",
                    "SKU",
                    null),
                actor,
                "session"));
  }

  static String query(String rawQuery, String key) {
    if (rawQuery == null) return null;
    for (var pair : rawQuery.split("&")) {
      var kv = pair.split("=", 2);
      if (kv.length == 2 && kv[0].equals(key)) return java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
    }
    return null;
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 本模块的五张表强制rls而不是只启用() {
    for (String table :
        List.of(
            "finance_cost_variance_baseline",
            "finance_cost_variance_report",
            "finance_cost_variance_item",
            "finance_cost_variance_driver",
            "finance_cost_variance_alert")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V362 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住「运行角色被误授 owner」").isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('finance_app','" + table + "','SELECT')")
                  .get(0, Boolean.class))
          .as("finance_app 必须真的能读 " + table)
          .isTrue();
    }
  }

  // ==========================================================================
  // 实际成本 vs 预算 / 标准成本
  // ==========================================================================
  @Test
  void 实际成本对标准成本_总差异逐位相等() {
    var detail = analyze("USD");

    var usd =
        detail.currencyGroups().stream().filter(g -> g.currency().equals("USD")).findFirst().orElseThrow();
    // SKU-A：实际 120.0000，标准 10.0000 × 10 = 100.0000 ⇒ 差异 +20.0000。
    // SKU-B 无基线 ⇒ 不计入合计。
    assertThat(usd.actualCostAmount()).isEqualByComparingTo("120.0000");
    assertThat(usd.standardCostAmount()).isEqualByComparingTo("100.0000");
    assertThat(usd.totalVarianceAmount()).isEqualByComparingTo("20.0000");
    assertThat(usd.comparableSkuCount()).isEqualTo(1);
    assertThat(usd.noBaselineSkuCount()).isEqualTo(1);
  }

  @Test
  void 无基线的sku_差异不可计算而不是0() {
    var detail = analyze("USD");

    var items =
        as(() -> variance.items(null, skuB, "STANDARD_COST", "USD", null, 0, 50).items());
    assertThat(items).hasSize(1);
    var row = items.get(0);
    assertThat(row.comparability()).isEqualTo("NO_BASELINE");
    assertThat(row.amount()).as("无基线 ≠ 差异为 0：金额必须是空").isNull();
    assertThat(row.comparableReason()).contains("无基线");
    assertThat(row.comparableReason()).contains("不可计算");
  }

  @Test
  void 币种不一致时不可比而不是跨币种相减() {
    // 给 **SKU-B**（本来就没有基线）补一版 **EUR** 的基线；
    // 它的实际成本是 USD ⇒ 币种不一致 ⇒ 绝不能拿 EUR 的标准成本去减 USD 的实际成本。
    as(
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into finance_cost_variance_baseline(id,tenant_id,baseline_type,dimension,"
                  + " dimension_key,dimension_ref_id,dimension_code,dimension_name,currency,unit_cost,"
                  + " effective_from,effective_to,status,version,note,created_at,updated_at)"
                  + " values(?,?,'STANDARD_COST','SKU',?,?,'SKU-B','测试商品B','EUR',9.0000,"
                  + " ?::timestamptz,null,'ACTIVE',1,'欧元标准成本',?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              skuB.toString(),
              skuB,
              OffsetDateTime.parse("2025-12-01T00:00:00Z"),
              now,
              now);
          return null;
        });

    analyze("USD");
    var items = as(() -> variance.items(null, skuB, "STANDARD_COST", "USD", null, 0, 50).items());
    assertThat(items).hasSize(1);
    assertThat(items.get(0).comparability()).isEqualTo("CURRENCY_MISMATCH");
    assertThat(items.get(0).amount()).isNull();
    assertThat(items.get(0).comparableReason()).contains("绝不跨币种相减");
  }

  // ==========================================================================
  // 五类差异各自独立可看
  // ==========================================================================
  @Test
  void 五类差异各自独立可看() {
    var detail = analyze("CNY");

    var drivers =
        detail.currencyGroups().stream()
            .filter(g -> g.currency().equals("USD"))
            .findFirst()
            .orElseThrow()
            .drivers();
    var names = drivers.stream().map(FinanceCostVarianceDriverRow::driver).toList();
    assertThat(names).contains("STANDARD_COST", "PURCHASE_PRICE", "FIRST_MILE", "SHRINKAGE", "FX");
  }

  @Test
  void 采购价波动_本期均价减上期均价乘结转量() {
    var detail = analyze("USD");

    var rows = as(() -> variance.items(null, skuA, "PURCHASE_PRICE", "USD", null, 0, 50).items());
    assertThat(rows).hasSize(1);
    var row = rows.get(0);
    assertThat(row.comparability()).isEqualTo("COMPARABLE");
    // 本期均价 11.0000 − 上期均价 10.0000 = 1.0000 × 结转量 10 = 10.0000。
    assertThat(row.amount()).isEqualByComparingTo("10.0000");
  }

  @Test
  void 头程成本波动_本期减上期() {
    var detail = analyze("USD");

    var rows = as(() -> variance.items(null, null, null, "USD", null, 0, 50).items());
    var firstMileItem =
        rows.stream().filter(r -> r.driver().equals("FIRST_MILE")).findFirst().orElse(null);
    // 头程是单据级 ⇒ 只进按币种的驱动因素汇总，不进逐 SKU 明细。
    assertThat(firstMileItem).isNull();

    var driver =
        detail.currencyGroups().stream()
            .filter(g -> g.currency().equals("USD"))
            .findFirst()
            .orElseThrow()
            .drivers()
            .stream()
            .filter(d -> d.driver().equals("FIRST_MILE"))
            .findFirst()
            .orElseThrow();
    assertThat(driver.comparability()).isEqualTo("COMPARABLE");
    // 本期 500.0000 − 上期 400.0000 = 100.0000。
    assertThat(driver.amount()).isEqualByComparingTo("100.0000");
  }

  @Test
  void 损耗差异_负向调整流水乘单位成本() {
    analyze("USD");

    var rows = as(() -> variance.items(null, skuA, "SHRINKAGE", "USD", null, 0, 50).items());
    assertThat(rows).hasSize(1);
    var row = rows.get(0);
    assertThat(row.comparability()).isEqualTo("COMPARABLE");
    // 损耗 2 × 单位成本 12.0000（120.0000 ÷ 10）= 24.0000。
    assertThat(row.quantity()).isEqualByComparingTo("2.000000");
    assertThat(row.amount()).isEqualByComparingTo("24.0000");
  }

  @Test
  void 没有损耗的sku_是0而不是不可计算() {
    analyze("USD");

    var rows = as(() -> variance.items(null, skuB, "SHRINKAGE", "USD", null, 0, 50).items());
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).comparability()).isEqualTo("COMPARABLE");
    assertThat(rows.get(0).amount()).as("「本期没有损耗」是事实（0），不是「算不出来」").isEqualByComparingTo("0.0000");
  }

  @Test
  void 汇率影响_期初汇率与期末汇率隔离出来() {
    analyze("CNY");

    var rows = as(() -> variance.items(null, skuA, "FX", "USD", null, 0, 50).items());
    assertThat(rows).hasSize(1);
    var row = rows.get(0);
    assertThat(row.comparability()).isEqualTo("COMPARABLE");
    // 120.0000 × (1 − 7.0000 ÷ 8.0000) = 120 × 0.125 = 15.0000。
    assertThat(row.amount()).isEqualByComparingTo("15.0000");
  }

  @Test
  void 同币种时汇率影响恒为0() {
    analyze("USD");

    var rows = as(() -> variance.items(null, skuA, "FX", "USD", null, 0, 50).items());
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).comparability()).isEqualTo("COMPARABLE");
    assertThat(rows.get(0).amount()).isEqualByComparingTo("0.0000");
  }

  // ==========================================================================
  // 多币种绝不相加
  // ==========================================================================
  @Test
  void 多币种按分组呈现_互不相加() {
    var detail = analyze("USD");

    var usd =
        detail.currencyGroups().stream().filter(g -> g.currency().equals("USD")).findFirst().orElseThrow();
    var eur =
        detail.currencyGroups().stream().filter(g -> g.currency().equals("EUR")).findFirst().orElseThrow();
    // USD 组只含 USD 的金额，EUR 组只含 EUR 的金额。
    assertThat(usd.actualCostAmount()).isEqualByComparingTo("120.0000");
    assertThat(eur.actualCostAmount()).isEqualByComparingTo("0.0000");
    // EUR 的 SKU-C 没有基线 ⇒ 不可比，金额为空（不是 0）。
    var eurItems = as(() -> variance.items(null, skuC, "STANDARD_COST", "EUR", null, 0, 50).items());
    assertThat(eurItems).hasSize(1);
    assertThat(eurItems.get(0).comparability()).isEqualTo("NO_BASELINE");
    assertThat(eurItems.get(0).amount()).isNull();
    assertThat(eur.noBaselineSkuCount()).isEqualTo(1);
  }

  // ==========================================================================
  // 主因归因 + 异常提示
  // ==========================================================================
  @Test
  void 主因归因_按金额排名且rank1是主因() {
    var detail = analyze("CNY");

    var usd =
        detail.currencyGroups().stream().filter(g -> g.currency().equals("USD")).findFirst().orElseThrow();
    var ranked =
        usd.drivers().stream().filter(d -> d.rank() != null).sorted((a, b) -> a.rank() - b.rank()).toList();
    assertThat(ranked).isNotEmpty();
    assertThat(ranked.get(0).rank()).isEqualTo(1);
    assertThat(ranked.get(0).isPrimary()).isTrue();
    // 排名按 |金额| 递减。
    for (int i = 1; i < ranked.size(); i++) {
      assertThat(ranked.get(i).amount().abs())
          .as("排名必须按金额绝对值递减")
          .isLessThanOrEqualTo(ranked.get(i - 1).amount().abs());
    }
    // 贡献占比合计为 1。
    var shareSum =
        ranked.stream().map(d -> d.contributionShare()).reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(shareSum).isCloseTo(new BigDecimal("1.000000"), within(new BigDecimal("0.00001")));
  }

  @Test
  void 异常提示_无基线的sku必须被点名() {
    var detail = analyze("USD");

    var alerts =
        detail.currencyGroups().stream()
            .filter(g -> g.currency().equals("USD"))
            .findFirst()
            .orElseThrow()
            .alerts();
    assertThat(alerts).anyMatch(a -> a.code().equals("NO_BASELINE") && a.skuId().equals(skuB));
  }

  @Test
  void 异常提示_整类上游不可得也会留痕() {
    tradeDown = true;
    var detail = analyze("USD");

    var all = as(() -> variance.alerts(null, "PURCHASE_PRICE_UNAVAILABLE", null, 0, 50).items());
    assertThat(all).isNotEmpty();
    assertThat(all.get(0).message()).contains("采购价波动整类不可计算");
    // 采购价那一类**不参与排名**（不可计算 ⇒ 没有 rank）。
    var purchase =
        detail.currencyGroups().stream()
            .filter(g -> g.currency().equals("USD"))
            .findFirst()
            .orElseThrow()
            .drivers()
            .stream()
            .filter(d -> d.driver().equals("PURCHASE_PRICE"))
            .findFirst()
            .orElseThrow();
    assertThat(purchase.comparability()).isEqualTo("UPSTREAM_UNAVAILABLE");
    assertThat(purchase.amount()).as("不可计算 ⇒ 金额是空，不是 0").isNull();
    assertThat(purchase.rank()).isNull();
  }

  @Test
  void 汇率读不到时汇率影响不可计算而不是按1比1兜底() {
    catalogDown = true;
    var detail = analyze("CNY");

    var rows = as(() -> variance.items(null, skuA, "FX", "USD", null, 0, 50).items());
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).comparability()).isEqualTo("UPSTREAM_UNAVAILABLE");
    assertThat(rows.get(0).amount()).as("汇率不可得 ⇒ 金额是空，绝不按 1:1 兜底").isNull();
    assertThat(as(() -> variance.alerts(null, "FX_RATE_UNAVAILABLE", null, 0, 50).items())).isNotEmpty();
  }

  @Test
  void 上游读不到时其余差异照常出数() {
    warehouseDown = true;
    var detail = analyze("USD");

    // 头程与损耗读不到 ⇒ 那两类不可计算；但标准成本差异照常出。
    var usd =
        detail.currencyGroups().stream().filter(g -> g.currency().equals("USD")).findFirst().orElseThrow();
    assertThat(usd.totalVarianceAmount()).isEqualByComparingTo("20.0000");
    var firstMile =
        usd.drivers().stream().filter(d -> d.driver().equals("FIRST_MILE")).findFirst().orElseThrow();
    assertThat(firstMile.comparability()).isEqualTo("UPSTREAM_UNAVAILABLE");
  }

  // ==========================================================================
  // 基数：12.4.1.2 的真实结转金额 + 批次快照
  // ==========================================================================
  @Test
  void 计价方法取批次行上的快照而不是当前生效版本() {
    var detail = analyze("USD");

    assertThat(detail.report().valuationMethod()).isEqualTo("FIFO");
    assertThat(detail.report().valuationMethodVersion()).isEqualTo(1);
    assertThat(detail.report().costRunIds()).contains(runId.toString());
    assertThat(detail.report().basisNote()).contains("批次行上的快照");
  }

  @Test
  void 已被接替的批次不计入_避免把两批成本加起来() {
    // 把 USD 批次标记为「已被接替」（重算后的旧批次）⇒ 它不再作为基数。
    as(
        () -> {
          sql.execute(
              "update finance_cost_calculation_run set superseded_by_run_id = ?::uuid"
                  + " where id = ? and tenant_id = ?",
              runIdEur, runId, tenant);
          return null;
        });

    var detail = analyze("USD");
    assertThat(detail.currencyGroups().stream().map(g -> g.currency()))
        .as("已被接替的批次不再作为基数 ⇒ USD 分组整个消失（不能把两批成本加起来）")
        .doesNotContain("USD");
  }

  @Test
  void 没有基数时明确拒绝而不是出一张空报表() {
    var actor = actors.get("session");
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        variance.analyze(
                            new FinanceCostVarianceReportRequest(
                                "LEGAL_ENTITY",
                                UUID.randomUUID(),
                                "LE-XX",
                                "另一个主体",
                                OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                                OffsetDateTime.parse("2026-02-01T00:00:00Z"),
                                "USD",
                                "STANDARD_COST",
                                "SKU",
                                null),
                            actor,
                            "session")))
        .hasStackTraceContaining("没有已完成的成本计算批次");
  }

  // ==========================================================================
  // 基线维护：版本链 + 不可改写
  // ==========================================================================
  @Test
  void 登记基线_追加版本而不是就地改() {
    var actor = actors.get("session");
    as(
        () ->
            variance.upsertBaseline(
                new FinanceCostVarianceBaselineRequest(
                    "STANDARD_COST", "SKU", skuA, "SKU-A", "测试商品A", "USD",
                    new BigDecimal("11.5000"), OffsetDateTime.parse("2026-02-01T00:00:00Z"), "第二版"),
                actor));

    var versions =
        as(
            () ->
                variance.baselineVersions(
                    "STANDARD_COST", "SKU", skuA.toString(), "USD"));
    assertThat(versions).hasSize(2);
    var newest = versions.get(0);
    assertThat(newest.version()).isEqualTo(2);
    assertThat(newest.unitCost()).isEqualByComparingTo("11.5000");
    assertThat(newest.supersedesId()).isNotNull();
    // 旧版本被收口（不再是生效版本），且基线值没被改写。
    var older = versions.get(1);
    assertThat(older.version()).isEqualTo(1);
    assertThat(older.unitCost()).isEqualByComparingTo("10.0000");
    assertThat(older.status()).isEqualTo("SUPERSEDED");
    assertThat(older.effectiveTo()).isNotNull();
  }

  @Test
  void 生效起点必须晚于上一版_否则历史差异分析会被追溯改写() {
    var actor = actors.get("session");
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        variance.upsertBaseline(
                            new FinanceCostVarianceBaselineRequest(
                                "STANDARD_COST", "SKU", skuA, "SKU-A", "测试商品A", "USD",
                                new BigDecimal("11.0000"),
                                OffsetDateTime.parse("2025-06-01T00:00:00Z"), null),
                            actor)))
        .hasStackTraceContaining("必须晚于上一版本");
  }

  @Test
  void 基线值写成0被拒绝_因为那是参数没维护不是成本为0() {
    var actor = actors.get("session");
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        variance.upsertBaseline(
                            new FinanceCostVarianceBaselineRequest(
                                "STANDARD_COST", "SKU", skuA, "SKU-A", "测试商品A", "USD",
                                BigDecimal.ZERO, OffsetDateTime.parse("2026-02-01T00:00:00Z"), null),
                            actor)))
        .hasStackTraceContaining("必须大于 0");
  }

  @Test
  void 按SKU建基线却没给SKU_id被拒绝_不能落到500() {
    var actor = actors.get("session");
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        variance.upsertBaseline(
                            new FinanceCostVarianceBaselineRequest(
                                "STANDARD_COST", "SKU", null, "SKU-X", "测试商品X", "USD",
                                new BigDecimal("10.0000"),
                                OffsetDateTime.parse("2026-02-01T00:00:00Z"), null),
                            actor)))
        .as("SKU 维度的链键就是 dimensionRefId ⇒ 缺它必须被明确拒绝，而不是 NPE 变成 500")
        .hasStackTraceContaining("按 SKU 建基线必须给出 dimensionRefId");
  }

  @Test
  void 按类目建基线却没给类目编码被拒绝() {
    var actor = actors.get("session");
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        variance.upsertBaseline(
                            new FinanceCostVarianceBaselineRequest(
                                "STANDARD_COST", "CATEGORY", null, null, null, "USD",
                                new BigDecimal("10.0000"),
                                OffsetDateTime.parse("2026-02-01T00:00:00Z"), null),
                            actor)))
        .as("类目维度的链键是 CATEGORY:<编码> ⇒ 缺编码必须被明确拒绝")
        .hasStackTraceContaining("按类目建基线必须给出 dimensionCode");
  }

  @Test
  void 已存在的基线版本不可改写() {
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "update finance_cost_variance_baseline set unit_cost = 99.0000"
                              + " where tenant_id = ?",
                          tenant);
                      return null;
                    }))
        .as("改基线 = 追加新版本，不是就地改")
        .hasStackTraceContaining("不可改写");
    // 行必须还在原来的值。
    var value =
        as(
            () ->
                sql.fetchOne(
                        "select unit_cost from finance_cost_variance_baseline where tenant_id = ?",
                        tenant)
                    .get(0, BigDecimal.class));
    assertThat(value).isEqualByComparingTo("10.0000");
  }

  @Test
  void 版本对比给出逐字段差异() {
    var actor = actors.get("session");
    as(
        () ->
            variance.upsertBaseline(
                new FinanceCostVarianceBaselineRequest(
                    "STANDARD_COST", "SKU", skuA, "SKU-A", "测试商品A", "USD",
                    new BigDecimal("11.5000"), OffsetDateTime.parse("2026-02-01T00:00:00Z"), null),
                actor));

    var comparison =
        as(
            () ->
                variance.compareBaselines(
                    "STANDARD_COST", "SKU", skuA.toString(), "USD", 1, 2));
    assertThat(comparison.unitCostDifference()).isEqualByComparingTo("1.5000");
    assertThat(comparison.changedFields()).contains("unitCost");
  }

  // ==========================================================================
  // 报表不可改写
  // ==========================================================================
  @Test
  void 报表一经生成不可改写() {
    var detail = analyze("USD");

    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "update finance_cost_variance_report set sku_count = 999"
                              + " where id = ? and tenant_id = ?",
                          detail.report().id(), tenant);
                      return null;
                    }))
        .as("历史即证据：报表不可改写")
        .hasStackTraceContaining("不可改写");
  }

  // ==========================================================================
  // 端点
  // ==========================================================================
  @Test
  void 端点_能力声明与选项() throws Exception {
    http.perform(get("/finance/cost-variance/capabilities").header("Authorization", "session"))
        .andExpect(status().isOk());
    http.perform(get("/finance/cost-variance/options").header("Authorization", "session"))
        .andExpect(status().isOk());
  }

  @Test
  void 端点_执行分析并返回报表() throws Exception {
    var body =
        """
        {"scopeType":"LEGAL_ENTITY","scopeRefId":"%s","scopeCode":"LE-US","scopeName":"美国主体",
         "periodFrom":"2026-01-01T00:00:00Z","periodTo":"2026-02-01T00:00:00Z",
         "reportingCurrency":"USD","baselineType":"STANDARD_COST","dimension":"SKU"}
        """
            .formatted(legalEntityId);

    var detail =
        json.readValue(
            http.perform(
                    post("/finance/cost-variance/reports")
                        .header("Authorization", "session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceCostVarianceReportDetail.class);
    assertThat(detail.currencyGroups()).isNotEmpty();

    // 列表 / 明细 / 驱动因素 / 告警四个只读端点。
    http.perform(get("/finance/cost-variance/reports").header("Authorization", "session"))
        .andExpect(status().isOk());
    http.perform(
            get("/finance/cost-variance/reports/" + detail.report().id())
                .header("Authorization", "session"))
        .andExpect(status().isOk());
    http.perform(get("/finance/cost-variance/items").header("Authorization", "session"))
        .andExpect(status().isOk());
    http.perform(get("/finance/cost-variance/drivers").header("Authorization", "session"))
        .andExpect(status().isOk());
    http.perform(get("/finance/cost-variance/alerts").header("Authorization", "session"))
        .andExpect(status().isOk());
  }

  @Test
  void 端点_没有权限就401() throws Exception {
    http.perform(get("/finance/cost-variance/capabilities").header("Authorization", "nope"))
        .andExpect(status().isUnauthorized());
  }
}
