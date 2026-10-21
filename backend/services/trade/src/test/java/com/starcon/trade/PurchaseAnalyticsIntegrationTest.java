package com.starcon.trade;

import static com.starcon.trade.PurchaseAnalyticsModels.*;
import static com.starcon.trade.PurchaseArrivalModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 6.5.2.1 采购分析的集成测试。
 *
 * <p>跑的是**真 PostgreSQL 16 + 真数据**：到货流水由 6.3.2.2 的真实服务层写入（金额由 V235 的
 * DB 触发器算），采购计划行由真实表写入，报表指标全部由本模块的 jOOQ 显式 SQL 算出来 ——
 * **没有任何 mock 数据源**。
 *
 * <p>跨服务的两个读端口（catalog 的类目 / 汇率、warehouse 的收货单）用
 * {@code @MockitoSpyBean} 打桩：桩的是**端口的返回值**，不是报表逻辑；
 * 端口不可用时本模块必须显式降级（标 UNSET / 未归类 / 同步失败），这一条也有断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PurchaseAnalyticsIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        // 运行角色必须 NOBYPASSRLS，否则它会绕过 RLS，隔离断言全部变成空断言。
        s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
      }
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "trade_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:8081");
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @AfterAll
  static void stop() {
    pg.stop();
  }

  @Autowired PurchaseArrivalService arrivals;
  @MockitoSpyBean PurchaseAnalyticsCatalogPort catalog;
  @MockitoSpyBean PurchaseAnalyticsWarehousePort warehouse;

  /** 只桩掉 {@code require}（对 identity 的一次 HTTP 调用），{@code run} 仍是真实实现。 */
  @MockitoSpyBean AccessService access;

  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  static final List<String> permissions =
      List.of("trade.purchase-analytics.read", "trade.purchase-analytics.update");

  AccessService.Access actor() {
    return new AccessService.Access(
        user,
        tenant,
        "Test",
        UUID.randomUUID(),
        null,
        permissions,
        List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions)));
  }

  @BeforeEach
  void stubIdentity() {
    org.mockito.Mockito
        .doAnswer(
            inv -> {
              String permission = inv.getArgument(1, String.class);
              boolean allowed =
                  permissions.contains(permission)
                      && permissions.contains(
                          permission.substring(0, permission.lastIndexOf('.')) + ".read");
              if (!allowed) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前角色没有此操作权限");
              }
              return actor();
            })
        .when(access)
        .require(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
  }

  @BeforeEach
  void clean() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate purchase_analytics_fact, purchase_analytics_sync_run,"
              + " purchase_analytics_sku_dimension,"
              + " purchase_arrival_event, purchase_arrival_disposition,"
              + " purchase_arrival_payable_confirmation, purchase_arrival_receipt,"
              + " purchase_arrival_tracking, purchase_plan_adjustment, purchase_plan_lock_log,"
              + " purchase_plan_line, purchase_plan, purchase_plan_campaign,"
              + " purchase_plan_lock_policy cascade");
    }
  }

  <T> T as(java.util.function.Supplier<T> work) {
    return access.run(actor(), "trade.purchase-analytics.read", work);
  }

  // ================================================================ 夹具

  static final LocalDate FROM = LocalDate.of(2026, 3, 1);
  static final LocalDate TO = LocalDate.of(2026, 3, 31);
  /** **非 UTC 偏移**夹具：只有这种时刻才能测出 Timestamp.valueOf 的 −28800s 静默偏移。 */
  static final OffsetDateTime ARRIVED_ON_TIME = OffsetDateTime.parse("2026-03-05T10:00:00+08:00");
  static final OffsetDateTime ARRIVED_LATE = OffsetDateTime.parse("2026-03-12T09:30:00+08:00");

  /** 建一张跟踪单并登记一次到货（走 6.3.2.2 的真实服务层 ⇒ 金额由 DB 触发器算）。 */
  PurchaseArrivalDetail seedArrival(
      String tag, String qty, String price, String currency, LocalDate promised, OffsetDateTime arrivedAt) {
    UUID supplier = UUID.randomUUID();
    PurchaseArrivalDetail head =
        as(
            () ->
                arrivals.create(
                    new PurchaseArrivalCreate(
                        "PO-" + tag,
                        UUID.randomUUID(),
                        "SRC-" + tag,
                        "PURCHASE_ORDER",
                        supplier,
                        "SUP-" + tag,
                        "供应商-" + tag,
                        new BigDecimal(qty),
                        new BigDecimal(price),
                        currency,
                        promised,
                        "FU-" + tag),
                    user));
    return as(
        () ->
            arrivals.registerArrival(
                head.head().id(),
                new PurchaseArrivalArrival(
                    head.head().version(), new BigDecimal(qty), arrivedAt, null, "", "首批到货"),
                user,
                "Bearer main"));
  }

  /** 建一张带计划行的采购计划（真实表 + 真实列）。 */
  UUID seedPlanLine(String tag, UUID skuId, String skuCode, String qty, LocalDate expected, String shop) {
    UUID planId = UUID.randomUUID();
    // plan_type='CAMPAIGN' 的 DB CHECK 要求 campaign_id 必填 ⇒ 大促日历总是建一份（店铺可为空）。
    UUID campaignId = UUID.randomUUID();
    return as(
        () -> {
          {
            sql.execute(
                "insert into purchase_plan_campaign (id, tenant_id, code, name, shop_code,"
                    + " start_date, end_date, sales_multiplier, stock_factor, arrival_lead_days,"
                    + " status, note, created_by, created_at, updated_at)"
                    + " values (?::uuid, ?::uuid, ?::varchar, ?::varchar, ?::varchar, ?::date,"
                    + " ?::date, 1, 1, 0, 'ACTIVE', '', ?::uuid, now(), now())",
                campaignId,
                tenant,
                "CAMP-" + tag,
                "大促-" + tag,
                shop,
                FROM,
                TO,
                user);
          }
          sql.execute(
              "insert into purchase_plan (id, tenant_id, plan_no, plan_type, campaign_id,"
                  + " period_type, period_start, period_end, target_warehouse_id,"
                  + " target_warehouse_code, target_warehouse_name, legal_entity_id,"
                  + " legal_entity_code, legal_entity_name, status, priority, note, created_by,"
                  + " created_at, updated_at)"
                  + " values (?::uuid, ?::uuid, ?::varchar, 'CAMPAIGN', ?::uuid, 'MONTHLY',"
                  + " ?::date, ?::date, ?::uuid, 'WH-01', '主仓', ?::uuid, 'LE-01', '主体',"
                  + " 'DRAFT', 'NORMAL', '', ?::uuid, now(), now())",
              planId,
              tenant,
              "PP-" + tag,
              campaignId,
              FROM,
              TO,
              UUID.randomUUID(),
              UUID.randomUUID(),
              user);
          UUID lineId = UUID.randomUUID();
          sql.execute(
              "insert into purchase_plan_line (id, tenant_id, plan_id, sku_id, sku_code, sku_name,"
                  + " suggested_quantity, plan_quantity, quantity_basis, target_warehouse_id,"
                  + " target_warehouse_code, target_warehouse_name, expected_arrival_date,"
                  + " priority, status, lock_status, note, sort_rank, created_at, updated_at)"
                  + " values (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::varchar, ?::varchar,"
                  + " ?::numeric, ?::numeric, '', ?::uuid, 'WH-01', '主仓', ?::date,"
                  + " 'NORMAL', 'PLANNED', 'NOT_REQUESTED', '', 0, now(), now())",
              lineId,
              tenant,
              planId,
              skuId,
              skuCode,
              "商品-" + skuCode,
              new BigDecimal(qty),
              new BigDecimal(qty),
              UUID.randomUUID(),
              expected,
              user);
          return lineId;
        });
  }

  /** catalog 端口：类目 + 单位换算 + 本位币 + 汇率台账。 */
  void stubCatalog(String baseCurrency, Map<String, BigDecimal> rates) {
    org.mockito.Mockito.doAnswer(
            inv -> {
              UUID skuId = inv.getArgument(0, UUID.class);
              return new PurchaseAnalyticsCatalogPort.SkuView(
                  skuId,
                  "SKU-" + skuId.toString().substring(0, 4),
                  "商品",
                  "CAT-01",
                  "PCS",
                  List.of(
                      new PurchaseAnalyticsCatalogPort.ConversionView(
                          "BOX", "PURCHASE", new BigDecimal("12"), true)));
            })
        .when(catalog)
        .sku(org.mockito.ArgumentMatchers.any(UUID.class), org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.doAnswer(
            inv -> {
              String base = inv.getArgument(0, String.class);
              String quote = inv.getArgument(1, String.class);
              BigDecimal rate = rates.get(base + ">" + quote);
              if (rate == null) {
                return List.of();
              }
              return List.of(
                  new PurchaseAnalyticsCatalogPort.RateView(
                      UUID.randomUUID(),
                      base,
                      quote,
                      rate,
                      LocalDate.of(2026, 1, 1),
                      "MANUAL",
                      "MANUAL",
                      "1 " + base + " = " + rate + " " + quote));
            })
        .when(catalog)
        .rates(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.doAnswer(
            inv ->
                List.of(
                    new PurchaseAnalyticsCatalogPort.FunctionalCurrencyRow(
                        UUID.randomUUID(), "LE-01", "主体", baseCurrency)))
        .when(catalog)
        .functionalCurrencies(org.mockito.ArgumentMatchers.anyString());
  }

  /** warehouse 端口：收货单明细。 */
  void stubWarehouse(List<PurchaseAnalyticsWarehousePort.ReceiptLineFact> lines) {
    org.mockito.Mockito.doAnswer(
            inv ->
                new PurchaseAnalyticsWarehousePort.ReceiptScan(
                    lines, lines.isEmpty() ? 0 : 1, false, "夹具"))
        .when(warehouse)
        .receiptLines(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
  }

  // ================================================================ 工具

  MvcResult postJson(String path, Object body) throws Exception {
    return http.perform(
            MockMvcRequestBuilders.post(path)
                .header("Authorization", "Bearer main")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isOk())
        .andReturn();
  }

  String readText(String path) throws Exception {
    return http.perform(get(path).header("Authorization", "Bearer main"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> map(String payload) throws Exception {
    return json.readValue(payload, Map.class);
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> list(String payload, String key) throws Exception {
    Object value = map(payload).get(key);
    return value == null ? List.of() : (List<Map<String, Object>>) value;
  }

  /** 解析一个 JSON 数组（响应里的数组字段）。 */
  @SuppressWarnings("unchecked")
  List<Map<String, Object>> arr(String jsonArray) throws Exception {
    return (List<Map<String, Object>>) json.readValue(jsonArray, List.class);
  }

  /** 同步一次（默认 3 月窗口）。 */
  Map<String, Object> sync() throws Exception {
    return map(
        postJson(
                "/trade/purchase-analytics/facts/sync",
                Map.of("sourceType", "ALL", "from", FROM.toString(), "to", TO.toString()))
            .getResponse()
            .getContentAsString());
  }

  // ================================================================ 断言组

  @Test
  void 同步把到货流水物化成真实金额且金额等于单价乘数量() throws Exception {
    seedArrival("A", "100", "2.500000", "USD", LocalDate.of(2026, 3, 10), ARRIVED_ON_TIME);
    stubCatalog("CNY", Map.of());
    stubWarehouse(List.of());

    Map<String, Object> result = sync();
    assertThat(result.get("status")).as("同步要成功").isEqualTo("SUCCESS");
    assertThat(((Number) result.get("createdCount")).intValue()).isEqualTo(1);

    String facts = readText("/trade/purchase-analytics/facts?from=2026-03-01&to=2026-03-31");
    var rows = list(facts, "items");
    assertThat(rows).hasSize(1);
    Map<String, Object> row = rows.get(0);
    // 金额 = 单价快照 × 本次到货数量（100 × 2.5 = 250），不是写死的数。
    assertThat(new BigDecimal(String.valueOf(row.get("amount")))).isEqualByComparingTo("250.0000");
    assertThat(row.get("currency")).isEqualTo("USD");
    assertThat(row.get("sourceType")).isEqualTo("ARRIVAL_RECEIPT");
    assertThat(row.get("factKind")).isEqualTo("COST");
    // 时间必须写回正确的时刻（+08:00 的偏移不能被静默偏 −28800s）。
    assertThat(OffsetDateTime.parse(String.valueOf(row.get("occurredAt"))))
        .isEqualTo(ARRIVED_ON_TIME);
  }

  @Test
  void 同步是幂等的_重跑不会把金额翻倍() throws Exception {
    seedArrival("B", "100", "2.500000", "USD", LocalDate.of(2026, 3, 10), ARRIVED_ON_TIME);
    stubCatalog("CNY", Map.of());
    stubWarehouse(List.of());

    sync();
    Map<String, Object> second = sync();
    assertThat(((Number) second.get("createdCount")).intValue()).as("第二次不该新增行").isZero();
    assertThat(((Number) second.get("updatedCount")).intValue()).isEqualTo(1);

    String facts = readText("/trade/purchase-analytics/facts?from=2026-03-01&to=2026-03-31");
    assertThat(list(facts, "items")).hasSize(1);
  }

  @Test
  void 跨币种不合并_趋势按币种分列() throws Exception {
    seedArrival("C", "100", "2.500000", "USD", LocalDate.of(2026, 3, 10), ARRIVED_ON_TIME);
    seedArrival("D", "40", "3.000000", "EUR", LocalDate.of(2026, 3, 12), ARRIVED_LATE);
    stubCatalog("CNY", Map.of());
    stubWarehouse(List.of());
    sync();

    Map<String, Object> summary = map(readText("/trade/purchase-analytics/summary?from=2026-03-01&to=2026-03-31"));
    assertThat(summary.get("purchaseAmountTotal")).as("多币种不给合计").isNull();
    var currencies = arr(json.writeValueAsString(summary.get("purchaseAmounts")));
    assertThat(currencies).isNotEmpty();

    Map<String, Object> trend =
        map(readText("/trade/purchase-analytics/trend?from=2026-03-01&to=2026-03-31&granularity=MONTH"));
    var points = arr(json.writeValueAsString(trend.get("points")));
    assertThat(points).hasSize(2);
    assertThat(points.stream().map(p -> p.get("currency")).distinct().count()).isEqualTo(2);
  }

  @Test
  void 汇率登记后按快照折算且汇率取不到时绝不按一比一折算() throws Exception {
    seedArrival("E", "100", "2.500000", "USD", LocalDate.of(2026, 3, 10), ARRIVED_ON_TIME);
    // 登记了 USD→CNY 的汇率。
    stubCatalog("CNY", Map.of("USD>CNY", new BigDecimal("7.00000000")));
    stubWarehouse(List.of());
    sync();

    String facts = readText("/trade/purchase-analytics/facts?from=2026-03-01&to=2026-03-31");
    Map<String, Object> row = list(facts, "items").get(0);
    assertThat(row.get("fxStatus")).isEqualTo("APPLIED");
    assertThat(new BigDecimal(String.valueOf(row.get("fxRate")))).isEqualByComparingTo("7");
    // 250 USD × 7 = 1750 CNY —— 折算等式必须成立。
    assertThat(new BigDecimal(String.valueOf(row.get("baseAmount")))).isEqualByComparingTo("1750.0000");

    // 汇率台账里没有这个币种 ⇒ UNSET，且本位币金额**为空**（不是 0，也不是 1:1）。
    stubCatalog("CNY", Map.of());
    sync();
    String facts2 = readText("/trade/purchase-analytics/facts?from=2026-03-01&to=2026-03-31");
    Map<String, Object> row2 = list(facts2, "items").get(0);
    assertThat(row2.get("fxStatus")).isEqualTo("UNSET");
    assertThat(row2.get("baseAmount")).as("未折算就没有本位币金额").isNull();
  }

  @Test
  void 到货准时率的分母是承诺到货行_逾期行单独列出() throws Exception {
    // 准时：承诺 3-10，3-05 到货。
    seedArrival("F", "100", "2.500000", "USD", LocalDate.of(2026, 3, 10), ARRIVED_ON_TIME);
    // 逾期：承诺 3-10，3-12 到货。
    seedArrival("G", "100", "2.500000", "USD", LocalDate.of(2026, 3, 10), ARRIVED_LATE);
    // 未到货：承诺 3-10，从未到货 ⇒ 不进分母。
    UUID supplier = UUID.randomUUID();
    as(
        () ->
            arrivals.create(
                new PurchaseArrivalCreate(
                    "PO-H",
                    UUID.randomUUID(),
                    "SRC-H",
                    "PURCHASE_ORDER",
                    supplier,
                    "SUP-H",
                    "供应商-H",
                    new BigDecimal("100"),
                    new BigDecimal("2.500000"),
                    "USD",
                    LocalDate.of(2026, 3, 10),
                    "FU-H"),
                user));
    stubCatalog("CNY", Map.of());
    stubWarehouse(List.of());
    sync();

    Map<String, Object> rate =
        map(readText("/trade/purchase-analytics/on-time-rate?from=2026-03-01&to=2026-03-31"));
    assertThat(((Number) rate.get("denominatorCount")).intValue()).as("分母是已到货的承诺行").isEqualTo(2);
    assertThat(((Number) rate.get("onTimeCount")).intValue()).isEqualTo(1);
    assertThat(((Number) rate.get("lateCount")).intValue()).isEqualTo(1);
    assertThat(((Number) rate.get("pendingCount")).intValue()).as("未到货的行不进分母").isEqualTo(1);
    assertThat(new BigDecimal(String.valueOf(rate.get("onTimeRate")))).isEqualByComparingTo("0.5");
    assertThat(arr(json.writeValueAsString(rate.get("lateRows")))).hasSize(1);
    // 口径必须写在响应里。
    assertThat(String.valueOf(rate.get("denominatorRule"))).contains("到货跟踪行").contains("不是收货行");
  }

  @Test
  void 供应商集中度给出占比与hhi且hhi标度写明() throws Exception {
    seedArrival("I", "100", "10.000000", "USD", LocalDate.of(2026, 3, 10), ARRIVED_ON_TIME);
    seedArrival("J", "30", "10.000000", "USD", LocalDate.of(2026, 3, 11), ARRIVED_ON_TIME);
    stubCatalog("CNY", Map.of());
    stubWarehouse(List.of());
    sync();

    Map<String, Object> concentration =
        map(readText("/trade/purchase-analytics/supplier-concentration?from=2026-03-01&to=2026-03-31&topN=1"));
    var buckets = arr(json.writeValueAsString(concentration.get("buckets")));
    assertThat(buckets).hasSize(1);
    Map<String, Object> bucket = buckets.get(0);
    assertThat(bucket.get("currency")).isEqualTo("USD");
    assertThat(((Number) bucket.get("supplierCount")).intValue()).isEqualTo(2);
    // 1000 : 300 ⇒ top1 占比 0.769230…，HHI = 0.769231² + 0.230769² ≈ 0.6450。
    assertThat(new BigDecimal(String.valueOf(bucket.get("topShare"))))
        .isBetween(new BigDecimal("0.7692300"), new BigDecimal("0.7692310"));
    assertThat(new BigDecimal(String.valueOf(bucket.get("hhi"))))
        .isBetween(new BigDecimal("0.6449"), new BigDecimal("0.6451"));
    assertThat(String.valueOf(bucket.get("hhiScale"))).contains("0–1");
  }

  @Test
  void 价格波动是环比且上期没数据时给无数据而不是零() throws Exception {
    // 3 月：100 件 × 2.5
    seedArrival("K", "100", "2.500000", "USD", LocalDate.of(2026, 3, 10), ARRIVED_ON_TIME);
    stubCatalog("CNY", Map.of());
    stubWarehouse(List.of());
    sync();

    Map<String, Object> variance =
        map(readText("/trade/purchase-analytics/price-variance?from=2026-03-01&to=2026-03-31"));
    assertThat(String.valueOf(variance.get("comparison"))).as("口径写明是环比").contains("环比");
    var rows = arr(json.writeValueAsString(variance.get("rows")));
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("status")).as("上期没数据 ⇒ 不是 0%").isEqualTo("NO_PREVIOUS_DATA");
    assertThat(rows.get(0).get("changeRate")).isNull();
  }

  @Test
  void 采购成本占销售额比标算不出并点名归属模块() throws Exception {
    seedArrival("L", "100", "2.500000", "USD", LocalDate.of(2026, 3, 10), ARRIVED_ON_TIME);
    stubCatalog("CNY", Map.of());
    stubWarehouse(List.of());
    sync();

    Map<String, Object> ratio =
        map(readText("/trade/purchase-analytics/cost-to-sales-ratio?from=2026-03-01&to=2026-03-31"));
    assertThat(ratio.get("available")).as("销售分母不可用 ⇒ 算不出").isEqualTo(false);
    assertThat(ratio.get("ownerModule")).isEqualTo("13 利润与经营分析");
    assertThat(ratio.get("impact")).as("必须写清影响").isNotNull();
    assertThat(ratio.get("ratio")).as("绝不拿采购额当分母造一个假的比率").isNull();
    // 分子照算并返回。
    assertThat(arr(json.writeValueAsString(ratio.get("purchaseAmounts")))).isNotEmpty();
  }

  @Test
  void 按类目维度的采购效率给出计划量实收量与达成率() throws Exception {
    UUID skuId = UUID.randomUUID();
    seedPlanLine("M", skuId, "SKU-M", "100", LocalDate.of(2026, 3, 15), "SHOP-A");
    stubCatalog("CNY", Map.of());
    stubWarehouse(
        List.of(
            new PurchaseAnalyticsWarehousePort.ReceiptLineFact(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "GR-1",
                "WH-01",
                "RECEIVED",
                ARRIVED_ON_TIME,
                skuId,
                "SKU-M",
                "商品-SKU-M",
                new BigDecimal("80"),
                BigDecimal.ZERO)));
    sync();

    Map<String, Object> efficiency =
        map(readText("/trade/purchase-analytics/efficiency?from=2026-03-01&to=2026-03-31&dimension=CATEGORY"));
    var rows = arr(json.writeValueAsString(efficiency.get("rows")));
    assertThat(rows).isNotEmpty();
    Map<String, Object> row = rows.get(0);
    assertThat(row.get("dimensionCode")).isEqualTo("CAT-01");
    assertThat(new BigDecimal(String.valueOf(row.get("plannedQuantity")))).isEqualByComparingTo("100");
    assertThat(new BigDecimal(String.valueOf(row.get("receivedQuantity")))).isEqualByComparingTo("80");
    assertThat(new BigDecimal(String.valueOf(row.get("fulfillmentRate")))).isEqualByComparingTo("0.8");
  }

  @Test
  void 店铺维度只给计划侧口径不给达成率() throws Exception {
    UUID skuId = UUID.randomUUID();
    seedPlanLine("N", skuId, "SKU-N", "100", LocalDate.of(2026, 3, 15), "SHOP-A");
    stubCatalog("CNY", Map.of());
    stubWarehouse(List.of());
    sync();

    Map<String, Object> efficiency =
        map(readText("/trade/purchase-analytics/efficiency?from=2026-03-01&to=2026-03-31&dimension=SHOP"));
    var rows = arr(json.writeValueAsString(efficiency.get("rows")));
    assertThat(rows).isNotEmpty();
    assertThat(rows.get(0).get("status")).isEqualTo("PLAN_ONLY");
    assertThat(rows.get(0).get("fulfillmentRate")).as("分子无法按店铺归属时不给比率").isNull();
  }

  @Test
  void 能力声明逐条点名未交付的模块() throws Exception {
    Map<String, Object> caps = map(readText("/trade/purchase-analytics/capabilities"));
    var sections = arr(json.writeValueAsString(caps.get("sections")));
    Map<String, Map<String, Object>> byKey = new java.util.HashMap<>();
    for (Map<String, Object> section : sections) {
      byKey.put(String.valueOf(section.get("key")), section);
    }
    assertThat(byKey.get("PURCHASE_ORDER").get("available")).isEqualTo(false);
    assertThat(String.valueOf(byKey.get("PURCHASE_ORDER").get("ownerModule"))).contains("6.3.1");
    assertThat(byKey.get("QUALITY_INSPECTION").get("available")).isEqualTo(false);
    assertThat(byKey.get("QUALITY_INSPECTION").get("ownerModule")).isEqualTo("6.4.2.1");
    assertThat(byKey.get("SALES_DENOMINATOR").get("available")).isEqualTo(false);
    assertThat(byKey.get("ARRIVAL_RECEIPT").get("available")).isEqualTo(true);
  }

  @Test
  void 导出是流式csv且内容与筛选一致() throws Exception {
    seedArrival("P", "100", "2.500000", "USD", LocalDate.of(2026, 3, 10), ARRIVED_ON_TIME);
    stubCatalog("CNY", Map.of());
    stubWarehouse(List.of());
    sync();

    MvcResult result =
        http.perform(
                get("/trade/purchase-analytics/export?from=2026-03-01&to=2026-03-31")
                    .header("Authorization", "Bearer main"))
            .andExpect(status().isOk())
            .andReturn();
    // StreamingResponseBody 是异步写出的：必须先 getAsyncResult() 等它跑完。
    result.getAsyncResult();
    String csv = new String(result.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(csv).contains("事实键");
    assertThat(csv).contains("ARRIVAL_RECEIPT");
    assertThat(csv.lines().count()).isGreaterThanOrEqualTo(2);
  }

  @Test
  void 事实台账是真分页真筛选() throws Exception {
    for (int i = 1; i <= 5; i++) {
      seedArrival("Q" + i, "10", "1.000000", "USD", LocalDate.of(2026, 3, 10), ARRIVED_ON_TIME);
    }
    stubCatalog("CNY", Map.of());
    stubWarehouse(List.of());
    sync();

    Map<String, Object> page =
        map(readText("/trade/purchase-analytics/facts?from=2026-03-01&to=2026-03-31&page=0&size=2"));
    assertThat(((Number) page.get("total")).intValue()).isEqualTo(5);
    assertThat(arr(json.writeValueAsString(page.get("items")))).hasSize(2);

    Map<String, Object> filtered =
        map(
            readText(
                "/trade/purchase-analytics/facts?from=2026-03-01&to=2026-03-31&keyword=PO-Q3"));
    assertThat(((Number) filtered.get("total")).intValue()).isEqualTo(1);

    Map<String, Object> empty =
        map(
            readText(
                "/trade/purchase-analytics/facts?from=2020-01-01&to=2020-01-31"));
    assertThat(((Number) empty.get("total")).intValue()).isZero();
  }

  @Test
  void 事实落库遵守金额与汇率的数量级约束() throws Exception {
    seedArrival("R", "100", "2.500000", "USD", LocalDate.of(2026, 3, 10), ARRIVED_ON_TIME);
    stubCatalog("CNY", Map.of("USD>CNY", new BigDecimal("7")));
    stubWarehouse(List.of());
    sync();

    var row =
        as(
            () ->
                sql.fetchOne(
                    "select amount, base_amount, fx_rate, fx_status from purchase_analytics_fact"
                        + " where tenant_id = ?::uuid",
                    tenant));
    assertThat(row).isNotNull();
    assertThat(row.get("amount", BigDecimal.class)).isEqualByComparingTo("250");
    assertThat(row.get("base_amount", BigDecimal.class)).isEqualByComparingTo("1750");
    assertThat(row.get("fx_status", String.class)).isEqualTo("APPLIED");
  }
}
