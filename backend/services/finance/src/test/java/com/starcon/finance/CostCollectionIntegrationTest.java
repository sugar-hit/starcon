package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.CostCollectionModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 12.4.2.1 费用归集的业务断言，跑在**真实 PostgreSQL 16** 上。
 *
 * <p>覆盖蓝图 12-finance.md 12.4.2.1 的**四类归集口径**与「归集依据」：
 *
 * <ol>
 *   <li>按月 —— 平台费（12.3.1.2 结算明细行）与广告费（14.3.1.1 活动报表）；</li>
 *   <li>按单 —— 物流费（10.2.1.1 运单运费登记）；</li>
 *   <li>按 SKU —— 头程费（10.4.1.1 头程发运单）；</li>
 *   <li>按期间 —— 人员与办公费用（人工录入）。</li>
 * </ol>
 *
 * <p><b>🔴 归集与分摊的边界</b>单独用一条断言钉住：条目的分摊金额恒为 0、分摊状态恒为
 * {@code UNALLOCATED}、池的 {@code allocation_available} 恒为 false，
 * <b>而且库里根本不存在分摊结果表</b> —— 本模块产出的是「待分摊的费用池」，不是分摊结果。
 *
 * <p><b>上游数据怎么来的</b>：12.3.1.2 的结算明细行在**租户事务里**直接落库（遵守它自己的
 * 派生触发器）；10 物流 / 14 广告 是**跨服务只读端口**，用本地 stub 提供上游的 HTTP 契约
 * （端口本身是生产代码，测试只替掉「上游服务」这一端）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CostCollectionIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();

  static final List<String> PERMISSIONS =
      List.of("finance.costcollection.read", "finance.costcollection.create", "finance.costcollection.update");

  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;
  static HttpServer channel;
  static HttpServer trade;
  static HttpServer warehouse;

  /** 上游广告报表行（真值在这里，不在 finance 服务里）。 */
  static final List<String> AD_ROWS = new java.util.ArrayList<>();

  /** 上游运单行。 */
  static final List<String> WAYBILL_ROWS = new java.util.ArrayList<>();

  /** 上游头程发运单行。 */
  static final List<String> FIRST_MILE_ROWS = new java.util.ArrayList<>();

  /** 归集基准日：固定用一个确定的月份，断言才稳定。 */
  static final LocalDate BASE = LocalDate.of(2026, 3, 18);

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

      // 14.3.1.1 活动报表（channel 服务）。
      channel = HttpServer.create(new InetSocketAddress(0), 0);
      channel.createContext(
          "/channel/ad-reports/rows",
          e -> {
            String body = "{\"items\":[" + String.join(",", AD_ROWS) + "],\"page\":0,\"size\":200,\"total\":"
                + AD_ROWS.size() + "}";
            byte[] bytes = body.getBytes();
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, bytes.length);
            e.getResponseBody().write(bytes);
            e.close();
          });
      channel.setExecutor(Executors.newFixedThreadPool(2));
      channel.start();

      // 10.2.1.1 运单列表（trade 服务）。
      trade = HttpServer.create(new InetSocketAddress(0), 0);
      trade.createContext(
          "/trade/logistics/waybills/list",
          e -> {
            String body = "{\"items\":[" + String.join(",", WAYBILL_ROWS) + "],\"page\":0,\"size\":200,"
                + "\"totalElements\":" + WAYBILL_ROWS.size() + ",\"totalPages\":1}";
            byte[] bytes = body.getBytes();
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, bytes.length);
            e.getResponseBody().write(bytes);
            e.close();
          });
      trade.setExecutor(Executors.newFixedThreadPool(2));
      trade.start();

      // 10.4.1.1 头程发运单（warehouse 服务）。
      warehouse = HttpServer.create(new InetSocketAddress(0), 0);
      warehouse.createContext(
          "/warehouse/first-mile-shipments",
          e -> {
            String body = "{\"rows\":[" + String.join(",", FIRST_MILE_ROWS) + "],\"page\":0,\"size\":200,"
                + "\"total\":" + FIRST_MILE_ROWS.size() + "}";
            byte[] bytes = body.getBytes();
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, bytes.length);
            e.getResponseBody().write(bytes);
            e.close();
          });
      warehouse.setExecutor(Executors.newFixedThreadPool(2));
      warehouse.start();
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
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("CHANNEL_URL", () -> "http://127.0.0.1:" + channel.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://127.0.0.1:" + trade.getAddress().getPort());
    r.add("WAREHOUSE_URL", () -> "http://127.0.0.1:" + warehouse.getAddress().getPort());
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired CostCollectionService collections;

  // ================================================================ 夹具

  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_cost_collection_basis, finance_cost_collection_entry,"
              + " finance_cost_collection_pool, finance_cost_collection_rule,"
              + " finance_settlement_line, finance_settlement_summary, finance_settlement"
              + " restart identity cascade");
    }
    AD_ROWS.clear();
    WAYBILL_ROWS.clear();
    FIRST_MILE_ROWS.clear();
  }

  <T> T as(Supplier<T> work) {
    return tenants.run(tenant, work);
  }

  Record one(String statement, Object... binds) {
    return as(() -> sql.fetchOne(statement, binds));
  }

  // ================================================================ HTTP 小工具

  MvcResult postTo(String path) throws Exception {
    var result =
        http.perform(MockMvcRequestBuilders.post(path).header("Authorization", "session")).andReturn();
    assertThat(result.getResponse().getStatus())
        .as("POST " + path + " 失败：" + result.getResponse().getContentAsString())
        .isEqualTo(200);
    return result;
  }

  <T> T postToJson(String path, Class<T> type) throws Exception {
    var body = postTo(path).getResponse().getContentAsString();
    return json.readValue(body, type);
  }

  MvcResult getFrom(String path) throws Exception {
    return http.perform(MockMvcRequestBuilders.get(path).header("Authorization", "session"))
        .andExpect(status().isOk())
        .andReturn();
  }

  <T> T getFromJson(String path, Class<T> type) throws Exception {
    return json.readValue(getFrom(path).getResponse().getContentAsString(), type);
  }

  /** 列表响应（元素类型已知 ⇒ 显式声明泛型，`var` 会把元素推成 Object）。 */
  <T> List<T> getListFromJson(String path, Class<T> element) throws Exception {
    return json.readValue(
        getFrom(path).getResponse().getContentAsString(),
        json.getTypeFactory().constructCollectionType(List.class, element));
  }

  /** 建一条规则（走真实端点）。 */
  UUID createRule(
      String code,
      FeeSource source,
      FeeCategory category,
      TargetDimension dimension,
      PeriodBasis basis,
      String poolKey,
      LocalDate from) throws Exception {
    var body =
        """
        {"ruleCode":"%s","ruleName":"%s","feeSource":"%s","feeCategory":"%s","targetDimension":"%s",
         "periodBasis":"%s","poolKey":"%s","poolName":"%s","effectiveFrom":"%s"}
        """
            .formatted(
                code,
                code,
                source.name(),
                category.name(),
                dimension.name(),
                basis.name(),
                poolKey,
                poolKey,
                from);
    var response =
        http.perform(
                post("/finance/cost-collections/rules")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andReturn();
    assertThat(response.getResponse().getStatus())
        .as("建规则失败：" + response.getResponse().getContentAsString())
        .isEqualTo(200);
    return json.readValue(response.getResponse().getContentAsString(), FinanceCostCollectionRule.class).id();
  }

  /** 落一张结算单 + 若干明细行（遵守 12.3.1.1 的派生触发器）。 */
  UUID settlement(String no, List<Object[]> lines) {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var id = UUID.randomUUID();
    as(
        () -> {
          sql.execute(
              "insert into finance_settlement(id,tenant_id,settlement_no,shop_id,shop_code,shop_name,"
                  + " platform_code,period_start,period_end,currency,status,source,source_system,"
                  + " received_amount,note,created_at,updated_at)"
                  + " values(?,?,?,?,'SHOP-1','测试店铺','amazon',?::date,?::date,'USD','PENDING','MANUAL_UPLOAD',"
                  + " 'amazon',0,'',?::timestamptz,?::timestamptz)",
              id,
              tenant,
              no,
              UUID.randomUUID(),
              BASE.withDayOfMonth(1),
              BASE.withDayOfMonth(BASE.lengthOfMonth()),
              now,
              now);
          int lineNo = 0;
          for (var line : lines) {
            lineNo++;
            sql.execute(
                "insert into finance_settlement_line(id,tenant_id,settlement_id,line_no,category,amount,"
                    + " raw_amount,currency,quantity,occurred_on,external_reference,description,created_at)"
                    + " values(?,?,?,?,?,?,?,?,?,?::date,?,?,?::timestamptz)",
                UUID.randomUUID(),
                tenant,
                id,
                lineNo,
                line[0],
                line[1],
                line[1],
                "USD",
                null,
                line[2],
                "EXT-" + lineNo,
                "测试明细",
                now);
          }
          return null;
        });
    return id;
  }

  // ==========================================================================
  // 1. 归集规则：四类口径的配对由数据层钉死
  // ==========================================================================

  @Test
  void 归集规则_蓝图标准绑定模板不落库只给界面用() throws Exception {
    var templates =
        getListFromJson("/finance/cost-collections/rule-templates", FinanceCostCollectionRuleTemplate.class);
    assertThat(templates).hasSize(7);
    assertThat(templates)
        .extracting(FinanceCostCollectionRuleTemplate::targetDimension)
        .containsExactlyInAnyOrder(
            TargetDimension.MONTH,
            TargetDimension.MONTH,
            TargetDimension.MONTH,
            TargetDimension.ORDER,
            TargetDimension.SKU,
            TargetDimension.PERIOD,
            TargetDimension.PERIOD);
    // ⚠️ 模板**不落库**：系统不预置任何规则行。
    assertThat(one("select count(*) from finance_cost_collection_rule").get(0, Long.class)).isZero();
  }

  @Test
  void 归集规则_说不通的口径配对在服务层就被拒() throws Exception {
    var body =
        """
        {"ruleCode":"BAD","ruleName":"按 SKU 分摊的平台费","feeSource":"PLATFORM_SETTLEMENT",
         "feeCategory":"PLATFORM_FEE","targetDimension":"SKU","periodBasis":"CALENDAR_MONTH",
         "poolKey":"PLATFORM_FEE","effectiveFrom":"2026-01-01"}
        """;
    http.perform(
            post("/finance/cost-collections/rules")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());

    // 提示语直接问服务层要（MockMvc 的错误响应体不渲染 detail）。
    Throwable thrown =
        assertThatThrownBy(
                () ->
                    as(
                        () ->
                            collections.createRule(
                                new FinanceCostCollectionRuleInput(
                                    "BAD",
                                    "按 SKU 分摊的平台费",
                                    FeeSource.PLATFORM_SETTLEMENT,
                                    FeeCategory.PLATFORM_FEE,
                                    TargetDimension.SKU,
                                    PeriodBasis.CALENDAR_MONTH,
                                    "PLATFORM_FEE",
                                    null,
                                    LocalDate.of(2026, 1, 1),
                                    null,
                                    null,
                                    null))))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .actual();
    assertThat(thrown.getMessage()).contains("按蓝图只能配成");
  }

  @Test
  void 归集规则_绕开服务层写库也被数据层挡住() {
    // 直接写库（服务层被绕过）⇒ 表级 CHECK 必须挡住「按 SKU 分摊的平台费」。
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "insert into finance_cost_collection_rule(id,tenant_id,rule_code,rule_name,"
                              + " fee_source,fee_category,target_dimension,period_basis,pool_key,"
                              + " effective_from,status,created_at,updated_at)"
                              + " values(?,?,'BAD','按 SKU 分摊的平台费','PLATFORM_SETTLEMENT','PLATFORM_FEE',"
                              + " 'SKU','CALENDAR_MONTH','PLATFORM_FEE',?::date,'ACTIVE',"
                              + "?::timestamptz,?::timestamptz)",
                          UUID.randomUUID(),
                          tenant,
                          BASE.withDayOfMonth(1),
                          OffsetDateTime.now(ZoneOffset.UTC),
                          OffsetDateTime.now(ZoneOffset.UTC));
                      return null;
                    }))
        .as("四类口径的配对必须由 DB 层钉死")
        .hasStackTraceContaining("finance_cost_collection_rule");
  }

  // ==========================================================================
  // 2. 平台费（按月，12.3.1.2 结算明细行）
  // ==========================================================================

  @Test
  void 平台费_从结算明细行归集进按月费用池() throws Exception {
    var ruleId =
        createRule(
            "R-PLATFORM",
            FeeSource.PLATFORM_SETTLEMENT,
            FeeCategory.PLATFORM_FEE,
            TargetDimension.MONTH,
            PeriodBasis.CALENDAR_MONTH,
            "PLATFORM_FEE",
            LocalDate.of(2026, 1, 1));
    settlement(
        "ST-1",
        List.<Object[]>of(
            new Object[] {"COMMISSION", new BigDecimal("-120.5000"), BASE},
            new Object[] {"FBA_FEE", new BigDecimal("-30.0000"), BASE},
            // 广告费**不归这一档**（与活动报表是同一笔钱的两种口径）。
            new Object[] {"ADVERTISING", new BigDecimal("-50.0000"), BASE},
            // 销售额不是费用。
            new Object[] {"SALES", new BigDecimal("900.0000"), BASE}));

    var result =
        postToJson(
            "/finance/cost-collections/collect/platform-settlement?ruleId=" + ruleId,
            FinanceCostCollectionResult.class);

    assertThat(result.collected()).as("只有佣金与 FBA 费用两类进池").isEqualTo(2);
    assertThat(result.skippedDuplicated()).isZero();
    assertThat(result.entries())
        .extracting(FinanceCostCollectionEntry::amount)
        .containsExactlyInAnyOrder(new BigDecimal("120.5000"), new BigDecimal("30.0000"));
    assertThat(result.entries())
        .extracting(FinanceCostCollectionEntry::targetDimension)
        .containsOnly(TargetDimension.MONTH);
    assertThat(result.entries())
        .extracting(FinanceCostCollectionEntry::feeCategory)
        .containsOnly(FeeCategory.PLATFORM_FEE);

    // 归属期间 = 基准日期所在的自然月（UTC 日历日口径）。
    var entry = result.entries().get(0);
    assertThat(entry.periodStart()).isEqualTo(LocalDate.of(2026, 3, 1));
    assertThat(entry.periodEnd()).isEqualTo(LocalDate.of(2026, 3, 31));
    assertThat(entry.baseDate()).isEqualTo(BASE);

    // 归集依据：结算单号 + 行号 + 交易类型 + 原始金额。
    assertThat(entry.basis()).isNotNull();
    assertThat(entry.basis().basisType()).isEqualTo(BasisType.SETTLEMENT_LINE);
    assertThat(entry.basis().basisAmount()).isEqualByComparingTo("120.5000");
    assertThat(entry.basis().detail().settlementNo()).isEqualTo("ST-1");
    assertThat(entry.basis().detail().feeSubType()).isIn("COMMISSION", "FBA_FEE");

    // 池合计 = Σ条目金额（BigDecimal 精确相等）。
    var pool = result.pools().get(0);
    assertThat(pool.totalAmount()).isEqualByComparingTo("150.5000");
    assertThat(pool.entryCount()).isEqualTo(2);
    assertThat(pool.dimension()).isEqualTo(TargetDimension.MONTH);
    assertThat(pool.currency()).isEqualTo("USD");
  }

  @Test
  void 平台费_作废与被取代的结算单不进池() throws Exception {
    var ruleId =
        createRule(
            "R-PLATFORM",
            FeeSource.PLATFORM_SETTLEMENT,
            FeeCategory.PLATFORM_FEE,
            TargetDimension.MONTH,
            PeriodBasis.CALENDAR_MONTH,
            "PLATFORM_FEE",
            LocalDate.of(2026, 1, 1));
    settlement(
        "ST-VOID",
        List.<Object[]>of(new Object[] {"COMMISSION", new BigDecimal("-10.0000"), BASE}));
    as(
        () -> {
          sql.execute(
              "update finance_settlement set status = 'VOID' where settlement_no = 'ST-VOID'");
          return null;
        });

    var result =
        postToJson(
            "/finance/cost-collections/collect/platform-settlement?ruleId=" + ruleId,
            FinanceCostCollectionResult.class);
    assertThat(result.collected()).as("作废结算单不在资金口径里").isZero();
  }

  // ==========================================================================
  // 3. 广告费（按月，14.3.1.1 活动报表）
  // ==========================================================================

  @Test
  void 广告费_从活动报表归集进按月费用池() throws Exception {
    var ruleId =
        createRule(
            "R-AD",
            FeeSource.AD_SPEND,
            FeeCategory.AD_FEE,
            TargetDimension.MONTH,
            PeriodBasis.CALENDAR_MONTH,
            "AD_FEE",
            LocalDate.of(2026, 1, 1));
    AD_ROWS.add(
        "{\"dimension\":\"CAMPAIGN\",\"dimensionKey\":\"CMP-1\",\"dimensionLabel\":\"春季活动\","
            + "\"currency\":\"USD\",\"platformName\":\"amazon\",\"shopName\":\"SHOP-1\","
            + "\"externalCampaignId\":\"CMP-1\",\"spend\":250.75}");
    AD_ROWS.add(
        "{\"dimension\":\"CAMPAIGN\",\"dimensionKey\":\"CMP-2\",\"dimensionLabel\":\"零花费活动\","
            + "\"currency\":\"USD\",\"platformName\":\"amazon\",\"externalCampaignId\":\"CMP-2\","
            + "\"spend\":0}");

    var result =
        postToJson(
            "/finance/cost-collections/collect/ad-spend?ruleId=" + ruleId + "&baseDate=" + BASE,
            FinanceCostCollectionResult.class);

    assertThat(result.collected()).as("零花费的活动不产生费用").isEqualTo(1);
    assertThat(result.entries().get(0).amount()).isEqualByComparingTo("250.7500");
    assertThat(result.entries().get(0).targetDimension()).isEqualTo(TargetDimension.MONTH);
    assertThat(result.entries().get(0).baseDate()).isEqualTo(BASE);
    assertThat(result.entries().get(0).basis().basisType()).isEqualTo(BasisType.AD_REPORT_ROW);
    assertThat(result.entries().get(0).basis().detail().campaignId()).isEqualTo("CMP-1");
    assertThat(result.entries().get(0).basis().detail().campaignName()).isEqualTo("春季活动");
  }

  @Test
  void 广告费_不给基准日就拒绝而不是静默默认今天() throws Exception {
    var ruleId =
        createRule(
            "R-AD",
            FeeSource.AD_SPEND,
            FeeCategory.AD_FEE,
            TargetDimension.MONTH,
            PeriodBasis.CALENDAR_MONTH,
            "AD_FEE",
            LocalDate.of(2026, 1, 1));
    http.perform(
            post("/finance/cost-collections/collect/ad-spend").param("ruleId", ruleId.toString())
                .header("Authorization", "session"))
        .andExpect(status().isBadRequest());

    Throwable thrown =
        assertThatThrownBy(
                () -> as(() -> collections.collectAdSpend(ruleId, null, "session")))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .actual();
    assertThat(thrown.getMessage()).contains("不带单一发生日期");
  }

  // ==========================================================================
  // 4. 物流费（按单，10.2.1.1 运单运费登记）
  // ==========================================================================

  @Test
  void 物流费_从运单运费登记归集进按单费用池() throws Exception {
    var ruleId =
        createRule(
            "R-LOGISTICS",
            FeeSource.LOGISTICS_WAYBILL,
            FeeCategory.LOGISTICS_FEE,
            TargetDimension.ORDER,
            PeriodBasis.CALENDAR_MONTH,
            "LOGISTICS_FEE",
            LocalDate.of(2026, 1, 1));
    WAYBILL_ROWS.add(
        "{\"id\":\""
            + UUID.randomUUID()
            + "\",\"requestNo\":\"WB-1\",\"waybillNo\":\"1Z999\",\"salesOrderNo\":\"SO-2026-0001\","
            + "\"carrierCode\":\"DHL\",\"freightAmount\":18.30,\"freightCurrency\":\"USD\","
            + "\"freightGapCode\":\"REGISTERED\",\"freightSource\":\"CHANNEL_FEE_STATEMENT\","
            + "\"createdAt\":\"2026-03-10T08:00:00Z\"}");
    WAYBILL_ROWS.add(
        "{\"id\":\""
            + UUID.randomUUID()
            + "\",\"requestNo\":\"WB-2\",\"waybillNo\":\"1Z998\",\"salesOrderNo\":\"SO-2026-0002\","
            + "\"carrierCode\":\"DHL\",\"freightAmount\":null,\"freightCurrency\":\"USD\","
            + "\"freightGapCode\":\"NOT_REGISTERED\",\"freightSource\":\"MANUAL_REGISTERED\","
            + "\"createdAt\":\"2026-03-11T08:00:00Z\"}");

    var result =
        postToJson(
            "/finance/cost-collections/collect/logistics?ruleId=" + ruleId,
            FinanceCostCollectionResult.class);

    assertThat(result.collected()).as("未登记运费的运单不产生费用").isEqualTo(1);
    var entry = result.entries().get(0);
    assertThat(entry.amount()).isEqualByComparingTo("18.3000");
    assertThat(entry.targetDimension()).isEqualTo(TargetDimension.ORDER);
    assertThat(entry.baseDate()).isEqualTo(LocalDate.of(2026, 3, 10));
    assertThat(entry.basis().basisType()).isEqualTo(BasisType.WAYBILL_FREIGHT);
    assertThat(entry.basis().detail().orderReference()).isEqualTo("SO-2026-0001");
    assertThat(entry.basis().detail().waybillNo()).isEqualTo("1Z999");
    assertThat(entry.basis().detail().carrierCode()).isEqualTo("DHL");
    assertThat(entry.basis().detail().upstreamSource()).isEqualTo("CHANNEL_FEE_STATEMENT");
  }

  // ==========================================================================
  // 5. 头程费（按 SKU，10.4.1.1 头程发运单）
  // ==========================================================================

  @Test
  void 头程费_从头程发运单归集进按sku费用池() throws Exception {
    var ruleId =
        createRule(
            "R-HEADMILE",
            FeeSource.HEAD_MILE_SHIPMENT,
            FeeCategory.HEAD_MILE_FEE,
            TargetDimension.SKU,
            PeriodBasis.CALENDAR_MONTH,
            "HEAD_MILE_FEE",
            LocalDate.of(2026, 1, 1));
    FIRST_MILE_ROWS.add(
        "{\"id\":\""
            + UUID.randomUUID()
            + "\",\"shipmentCode\":\"FM-1\",\"carrierName\":\"COSCO\",\"currency\":\"USD\","
            + "\"freightCost\":1000.00,\"insuranceCost\":25.50,\"handlingCost\":14.25,"
            + "\"totalCost\":1039.75,\"status\":\"DISPATCHED\",\"dispatchedAt\":\"2026-03-05T02:30:00Z\"}");
    // 还没发出的发运单：运费是报价不是已发生成本 ⇒ 跳过。
    FIRST_MILE_ROWS.add(
        "{\"id\":\""
            + UUID.randomUUID()
            + "\",\"shipmentCode\":\"FM-2\",\"carrierName\":\"COSCO\",\"currency\":\"USD\","
            + "\"freightCost\":800.00,\"insuranceCost\":0,\"handlingCost\":0,\"totalCost\":800.00,"
            + "\"status\":\"DRAFT\",\"dispatchedAt\":null,\"expectedArriveAt\":\"2026-04-01T00:00:00Z\"}");

    var result =
        postToJson(
            "/finance/cost-collections/collect/head-mile?ruleId=" + ruleId,
            FinanceCostCollectionResult.class);

    assertThat(result.collected()).as("没发出的发运单不进池").isEqualTo(1);
    var entry = result.entries().get(0);
    assertThat(entry.amount()).isEqualByComparingTo("1039.7500");
    assertThat(entry.targetDimension()).isEqualTo(TargetDimension.SKU);
    assertThat(entry.baseDate()).isEqualTo(LocalDate.of(2026, 3, 5));
    assertThat(entry.basis().basisType()).isEqualTo(BasisType.FIRST_MILE_SHIPMENT);
    assertThat(entry.basis().detail().shipmentCode()).isEqualTo("FM-1");
    assertThat(entry.basis().detail().freightAmount()).isEqualByComparingTo("1000.00");
    assertThat(entry.basis().detail().insuranceAmount()).isEqualByComparingTo("25.50");
    assertThat(entry.basis().detail().handlingAmount()).isEqualByComparingTo("14.25");
  }

  // ==========================================================================
  // 6. 人工录入（订阅费按自然月；人员与办公费用按会计期间）
  // ==========================================================================

  @Test
  void 人工录入_订阅费归到自然月期间() throws Exception {
    var ruleId =
        createRule(
            "R-SUB",
            FeeSource.SUBSCRIPTION,
            FeeCategory.SUBSCRIPTION_FEE,
            TargetDimension.MONTH,
            PeriodBasis.CALENDAR_MONTH,
            "SUBSCRIPTION_FEE",
            LocalDate.of(2026, 1, 1));
    var body =
        """
        {"ruleId":"%s","sourceRefKey":"SUB-2026-03","sourceRefCode":"SUB-2026-03",
         "subject":"ERP 订阅 2026 年 3 月","amount":"899.00","currency":"usd","baseDate":"2026-03-15",
         "evidenceRef":"INV-SUB-2026-03"}
        """
            .formatted(ruleId);
    var response =
        http.perform(
                post("/finance/cost-collections/collect/manual")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn();
    var result = json.readValue(response.getResponse().getContentAsString(), FinanceCostCollectionResult.class);

    assertThat(result.collected()).isEqualTo(1);
    var entry = result.entries().get(0);
    assertThat(entry.currency()).isEqualTo("USD");
    assertThat(entry.periodStart()).isEqualTo(LocalDate.of(2026, 3, 1));
    assertThat(entry.periodEnd()).isEqualTo(LocalDate.of(2026, 3, 31));
    assertThat(entry.targetDimension()).isEqualTo(TargetDimension.MONTH);
    assertThat(entry.basis().basisType()).isEqualTo(BasisType.MANUAL_DOCUMENT);
    assertThat(entry.basis().evidenceRef()).isEqualTo("INV-SUB-2026-03");
    assertThat(entry.basis().detail().subject()).isEqualTo("ERP 订阅 2026 年 3 月");
  }

  @Test
  void 人工录入_人员费用按会计期间归集() throws Exception {
    var ruleId =
        createRule(
            "R-PERSONNEL",
            FeeSource.PERSONNEL,
            FeeCategory.PERSONNEL_COST,
            TargetDimension.PERIOD,
            PeriodBasis.ACCOUNTING_PERIOD,
            "PERSONNEL_COST",
            LocalDate.of(2026, 1, 1));
    var body =
        """
        {"ruleId":"%s","sourceRefKey":"PAYROLL-2026Q1","subject":"2026 年一季度人员成本",
         "amount":"120000.0000","currency":"CNY","baseDate":"2026-03-31",
         "periodStart":"2026-01-01","periodEnd":"2026-03-31","evidenceRef":"PAYROLL-2026Q1"}
        """
            .formatted(ruleId);
    var response =
        http.perform(
                post("/finance/cost-collections/collect/manual")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn();
    var result = json.readValue(response.getResponse().getContentAsString(), FinanceCostCollectionResult.class);

    assertThat(result.collected()).isEqualTo(1);
    var entry = result.entries().get(0);
    assertThat(entry.targetDimension()).isEqualTo(TargetDimension.PERIOD);
    assertThat(entry.periodStart()).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(entry.periodEnd()).isEqualTo(LocalDate.of(2026, 3, 31));
    assertThat(entry.amount()).isEqualByComparingTo("120000.0000");
  }

  @Test
  void 人工录入_会计期间口径不给期间就拒绝() throws Exception {
    var ruleId =
        createRule(
            "R-PERSONNEL",
            FeeSource.PERSONNEL,
            FeeCategory.PERSONNEL_COST,
            TargetDimension.PERIOD,
            PeriodBasis.ACCOUNTING_PERIOD,
            "PERSONNEL_COST",
            LocalDate.of(2026, 1, 1));
    var body =
        """
        {"ruleId":"%s","sourceRefKey":"PAYROLL-X","subject":"缺期间",
         "amount":"100","currency":"CNY","baseDate":"2026-03-31"}
        """
            .formatted(ruleId);
    http.perform(
            post("/finance/cost-collections/collect/manual")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());

    Throwable thrown =
        assertThatThrownBy(
                () ->
                    as(
                        () ->
                            collections.collectManual(
                                new FinanceCostCollectionManualInput(
                                    ruleId,
                                    "PAYROLL-X",
                                    null,
                                    "缺期间",
                                    new BigDecimal("100"),
                                    "CNY",
                                    BASE,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null))))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .actual();
    assertThat(thrown.getMessage()).contains("会计期间");
  }

  // ==========================================================================
  // 7. 幂等：同一笔上游事实只归集一次
  // ==========================================================================

  @Test
  void 幂等_同一笔上游事实只归集一次() throws Exception {
    var ruleId =
        createRule(
            "R-LOGISTICS",
            FeeSource.LOGISTICS_WAYBILL,
            FeeCategory.LOGISTICS_FEE,
            TargetDimension.ORDER,
            PeriodBasis.CALENDAR_MONTH,
            "LOGISTICS_FEE",
            LocalDate.of(2026, 1, 1));
    WAYBILL_ROWS.add(
        "{\"id\":\""
            + UUID.randomUUID()
            + "\",\"requestNo\":\"WB-1\",\"waybillNo\":\"1Z999\",\"salesOrderNo\":\"SO-1\","
            + "\"carrierCode\":\"DHL\",\"freightAmount\":18.30,\"freightCurrency\":\"USD\","
            + "\"freightGapCode\":\"REGISTERED\",\"freightSource\":\"MANUAL_REGISTERED\","
            + "\"createdAt\":\"2026-03-10T08:00:00Z\"}");

    var first =
        postToJson(
            "/finance/cost-collections/collect/logistics?ruleId=" + ruleId,
            FinanceCostCollectionResult.class);
    assertThat(first.collected()).isEqualTo(1);

    var second =
        postToJson(
            "/finance/cost-collections/collect/logistics?ruleId=" + ruleId,
            FinanceCostCollectionResult.class);
    assertThat(second.collected()).as("同一笔上游事实不能进池两次").isZero();
    assertThat(second.skippedDuplicated()).isEqualTo(1);
    assertThat(
            one("select count(*) from finance_cost_collection_entry").get(0, Long.class))
        .isEqualTo(1);
  }

  // ==========================================================================
  // 8. 归集与分摊的边界（本模块最要紧的一条）
  // ==========================================================================

  @Test
  void 归集与分摊的边界_池条目不是分摊结果() throws Exception {
    var ruleId =
        createRule(
            "R-HEADMILE",
            FeeSource.HEAD_MILE_SHIPMENT,
            FeeCategory.HEAD_MILE_FEE,
            TargetDimension.SKU,
            PeriodBasis.CALENDAR_MONTH,
            "HEAD_MILE_FEE",
            LocalDate.of(2026, 1, 1));
    FIRST_MILE_ROWS.add(
        "{\"id\":\""
            + UUID.randomUUID()
            + "\",\"shipmentCode\":\"FM-1\",\"carrierName\":\"COSCO\",\"currency\":\"USD\","
            + "\"freightCost\":1000.00,\"insuranceCost\":0,\"handlingCost\":0,\"totalCost\":1000.00,"
            + "\"status\":\"DISPATCHED\",\"dispatchedAt\":\"2026-03-05T02:30:00Z\"}");
    postToJson(
        "/finance/cost-collections/collect/head-mile?ruleId=" + ruleId,
        FinanceCostCollectionResult.class);

    // 条目上没有分摊结果：分摊金额恒为 0、状态恒为 UNALLOCATED。
    var entry =
        one(
            "select allocated_amount, allocation_status, allocation_owner_module"
                + " from finance_cost_collection_entry");
    assertThat(entry.get("allocated_amount", BigDecimal.class)).isEqualByComparingTo("0");
    assertThat(entry.get("allocation_status", String.class)).isEqualTo("UNALLOCATED");
    assertThat(entry.get("allocation_owner_module", String.class)).isEqualTo("12.4.2.2");

    // 池上也没有「可分摊」这个状态。
    var pool =
        one("select allocation_available, allocation_owner_module from finance_cost_collection_pool");
    assertThat(pool.get("allocation_available", Boolean.class)).isFalse();
    assertThat(pool.get("allocation_owner_module", String.class)).isEqualTo("12.4.2.2");

    // 🔴 本模块**只有四张表**，没有一张是分摊结果表 —— 分摊是下游 12.4.2.2 的职责。
    var moduleTables =
        sql.fetch(
                "select table_name from information_schema.tables where table_schema = 'public'"
                    + " and table_name like 'finance_cost_collection%' order by table_name")
            .map(r -> r.get(0, String.class));
    assertThat(moduleTables)
        .as("本模块只有规则 / 池 / 条目 / 依据四张表，没有分摊结果表")
        .containsExactlyInAnyOrder(
            "finance_cost_collection_basis",
            "finance_cost_collection_entry",
            "finance_cost_collection_pool",
            "finance_cost_collection_rule");

    // 能力声明里 12.4.2.2 明确 available=false 且点名归属。
    var capabilities =
        getFromJson(
            "/finance/cost-collections/capabilities",
            FinanceCostCollectionCapabilities.class);
    assertThat(capabilities.capabilities())
        .filteredOn(c -> c.moduleId().equals("12.4.2.2"))
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.available()).isFalse();
              assertThat(c.ownerModule()).isEqualTo("12.4.2.2");
            });
  }

  // ==========================================================================
  // 9. 金额合计与删除调整
  // ==========================================================================

  @Test
  void 金额合计_池合计恒等于条目金额之和() throws Exception {
    var ruleId =
        createRule(
            "R-PLATFORM",
            FeeSource.PLATFORM_SETTLEMENT,
            FeeCategory.PLATFORM_FEE,
            TargetDimension.MONTH,
            PeriodBasis.CALENDAR_MONTH,
            "PLATFORM_FEE",
            LocalDate.of(2026, 1, 1));
    settlement(
        "ST-1",
        List.<Object[]>of(
            new Object[] {"COMMISSION", new BigDecimal("-10.1000"), BASE},
            new Object[] {"COMMISSION", new BigDecimal("-20.2000"), BASE},
            new Object[] {"FBA_FEE", new BigDecimal("-30.3000"), BASE}));
    postToJson(
        "/finance/cost-collections/collect/platform-settlement?ruleId=" + ruleId,
        FinanceCostCollectionResult.class);

    var poolTotal =
        one("select total_amount, entry_count from finance_cost_collection_pool").get(
            "total_amount", BigDecimal.class);
    var sumOfEntries =
        one("select sum(amount) from finance_cost_collection_entry").get(0, BigDecimal.class);
    assertThat(poolTotal).isEqualByComparingTo("60.6000");
    assertThat(poolTotal).isEqualByComparingTo(sumOfEntries);
    assertThat(one("select entry_count from finance_cost_collection_pool").get(0, Integer.class))
        .isEqualTo(3);
  }

  @Test
  void 调整_删掉一条条目池合计当场回退() throws Exception {
    var ruleId =
        createRule(
            "R-PLATFORM",
            FeeSource.PLATFORM_SETTLEMENT,
            FeeCategory.PLATFORM_FEE,
            TargetDimension.MONTH,
            PeriodBasis.CALENDAR_MONTH,
            "PLATFORM_FEE",
            LocalDate.of(2026, 1, 1));
    settlement(
        "ST-1",
        List.<Object[]>of(
            new Object[] {"COMMISSION", new BigDecimal("-10.0000"), BASE},
            new Object[] {"COMMISSION", new BigDecimal("-20.0000"), BASE}));
    postToJson(
        "/finance/cost-collections/collect/platform-settlement?ruleId=" + ruleId,
        FinanceCostCollectionResult.class);
    assertThat(one("select total_amount from finance_cost_collection_pool").get(0, BigDecimal.class))
        .isEqualByComparingTo("30.0000");

    var entryId = one("select id from finance_cost_collection_entry limit 1").get(0, UUID.class);
    http.perform(
            post("/finance/cost-collections/entries/{id}/remove", entryId)
                .header("Authorization", "session"))
        .andExpect(status().isOk());

    assertThat(one("select total_amount from finance_cost_collection_pool").get(0, BigDecimal.class))
        .as("池合计必须跟着条目回退")
        .isEqualByComparingTo("20.0000");
    assertThat(one("select count(*) from finance_cost_collection_entry").get(0, Long.class))
        .isEqualTo(1);
    assertThat(one("select count(*) from finance_cost_collection_basis").get(0, Long.class))
        .as("依据随条目一起删（剩下的一条条目对应一条依据）")
        .isEqualTo(1);
    assertThat(
            one(
                    "select count(*) from finance_cost_collection_basis where entry_id = ?",
                    entryId)
                .get(0, Long.class))
        .as("被删掉那条的依据必须一起消失")
        .isZero();
  }

  // ==========================================================================
  // 10. 列表端点：真分页 + 真筛选 + 按币种分开汇总
  // ==========================================================================

  @Test
  void 列表_真分页真筛选且汇总覆盖整个结果集() throws Exception {
    var ruleId =
        createRule(
            "R-PLATFORM",
            FeeSource.PLATFORM_SETTLEMENT,
            FeeCategory.PLATFORM_FEE,
            TargetDimension.MONTH,
            PeriodBasis.CALENDAR_MONTH,
            "PLATFORM_FEE",
            LocalDate.of(2026, 1, 1));
    settlement(
        "ST-1",
        List.<Object[]>of(
            new Object[] {"COMMISSION", new BigDecimal("-1.0000"), BASE},
            new Object[] {"COMMISSION", new BigDecimal("-2.0000"), BASE},
            new Object[] {"COMMISSION", new BigDecimal("-4.0000"), BASE}));
    postToJson(
        "/finance/cost-collections/collect/platform-settlement?ruleId=" + ruleId,
        FinanceCostCollectionResult.class);

    var page =
        getFromJson(
            "/finance/cost-collections/entries?page=0&size=2",
            FinanceCostCollectionEntryPage.class);
    assertThat(page.items()).hasSize(2);
    assertThat(page.total()).isEqualTo(3);
    assertThat(page.totalsByCurrency())
        .singleElement()
        .satisfies(
            t -> {
              assertThat(t.currency()).isEqualTo("USD");
              assertThat(t.amount()).isEqualByComparingTo("7.0000");
              assertThat(t.count()).isEqualTo(3);
            });
    assertThat(page.totalsByDimension())
        .singleElement()
        .satisfies(
            t -> {
              assertThat(t.dimension()).isEqualTo(TargetDimension.MONTH);
              assertThat(t.amount()).isEqualByComparingTo("7.0000");
            });

    // 真筛选：按费用项筛。
    var filtered =
        getFromJson(
            "/finance/cost-collections/entries?feeCategory=LOGISTICS_FEE",
            FinanceCostCollectionEntryPage.class);
    assertThat(filtered.total()).isZero();
    assertThat(filtered.items()).isEmpty();
  }

  @Test
  void 能力声明_上游接通与下游缺口都点名() throws Exception {
    var capabilities =
        getFromJson(
            "/finance/cost-collections/capabilities",
            FinanceCostCollectionCapabilities.class);
    assertThat(capabilities.capabilities())
        .extracting(FinanceCostCollectionCapability::moduleId)
        .contains("12.4.2.1", "12.3.1.2", "14.3.1.1", "10.2.1.1", "10.4.1.1", "12.4.2.2");
    assertThat(capabilities.gaps())
        .extracting(FinanceCostCollectionDownstreamGap::moduleId)
        .contains("12.4.2.2");
  }
}
