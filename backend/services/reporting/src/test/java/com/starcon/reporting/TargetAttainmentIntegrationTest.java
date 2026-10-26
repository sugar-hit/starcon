package com.starcon.reporting;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 13.1.2.2 达成追踪的**集成测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>本文件验的是**业务口径**（多租户三条断言在 {@code TargetAttainmentTenancyIntegrationTest}）：
 *
 * <ul>
 *   <li>🔴 **「算不出来」≠「等于 0」**：拿不到实际值时当前值与达成率都是 {@code null}，
 *       **绝不返回 0% 达成率**；</li>
 *   <li>🔴 **预测达成 = 按当前速度直线推算期末值**，且**周期未开始 / 实际值不可得 ⇒ 预测为 null**；</li>
 *   <li>🔴 **未达标高亮只认「算得出达成率且预测不达标」**，「算不出来」不算未达标；</li>
 *   <li>🔴 **排名只在「同维度 + 同口径 + 同币种 + 同周期」内**，「算不出来」不进排名；</li>
 *   <li>🔴 **部门 / 人员维度的实际值尚未投影 ⇒ 如实报「不可得」**（不拿租户级数字冒充）；</li>
 *   <li>🔴 **产品维度**：商品维度投影跑过 ⇒ 算得出；没跑过 ⇒ 「不可得」（不是 0%）。</li>
 * </ul>
 *
 * <p>⚠️ **时间夹具用数据库时钟**：评估日取自 PG 的 {@code now()}（服务层与本测试**同源**）。
 * 用 JVM 墙钟造数据再跟 DB 侧算出来的值比，就是本仓已经踩过两次的「两个钟域」事故。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TargetAttainmentIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
  static final UUID productId = UUID.randomUUID();

  static HttpServer identity;

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of(
          "reporting.target-attainment.read", "reporting.target-attainment.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  static final String BEARER = "Bearer tenant-a";

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，多租户断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute(
          "create role reporting_app login password 'test-only' nosuperuser nobypassrls");
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
            byte[] body = JSON.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static AccessService.Access actor(UUID tenantId) {
    var grant = new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions);
    return new AccessService.Access(
        user, tenantId, "Test", UUID.randomUUID(), null, permissions, List.of(grant));
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "reporting_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("CATALOG_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ReportingTargetAttainmentService attainment;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate reporting_target_attainment, reporting_target, reporting_target_bucket, "
              + "reporting_store_metric_fact, reporting_store_metric_definition, "
              + "reporting_product_metric_fact, reporting_product_metric_definition, "
              + "reporting_metric_fact, reporting_metric_definition cascade");
    }
    actors.put(BEARER, actor(tenant));
  }

  // ==========================================================================
  // 夹具（时间一律取自数据库时钟）
  // ==========================================================================

  /** **数据库时钟**的今天（与服务层评估日同源）。 */
  private LocalDate dbToday() {
    return tenants.run(tenant, () -> sql.fetchOne("select (now() at time zone 'UTC')::date").get(0, LocalDate.class));
  }

  /** 某个自然月。 */
  private record Month(LocalDate start, LocalDate end) {}

  private Month monthOf(LocalDate day) {
    var start = day.withDayOfMonth(1);
    return new Month(start, start.withDayOfMonth(start.lengthOfMonth()));
  }

  private Month previousMonth(LocalDate day) {
    return monthOf(day.withDayOfMonth(1).minusMonths(1));
  }

  private Month nextMonth(LocalDate day) {
    return monthOf(day.withDayOfMonth(1).plusMonths(1));
  }

  /** 播种店铺口径（事实行的指标码必须已登记 —— DB 触发器钉死）。 */
  private void seedStoreCaliber() {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into reporting_store_metric_definition (id,tenant_id,metric_code,display_name,"
                  + "unit,aggregation,currency_aware,description,owner_module) "
                  + "values (?,?,?,?,?,?,?,?,?)",
              Ids.next(),
              tenant,
              "STORE_GMV_PLATFORM",
              "平台侧成交金额",
              "CURRENCY",
              "SUM",
              true,
              "各平台自己的 GMV 口径。",
              "13.1.1.2");
          return null;
        });
  }

  /** 播种租户口径（组织维度的目标引用它）。 */
  private void seedOrgCaliber() {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into reporting_metric_definition (id,tenant_id,metric_code,display_name,unit,"
                  + "aggregation,currency_aware,description,owner_module) "
                  + "values (?,?,?,?,?,?,?,?,?)",
              Ids.next(),
              tenant,
              "SALES_AMOUNT",
              "成交金额",
              "CURRENCY",
              "SUM",
              true,
              "订单成交金额（9.1.1.1 订单列表口径）。",
              "9.1.1.1");
          return null;
        });
  }

  /** 播种商品维度口径（13.2.2.2 的口径表）。 */
  private void seedProductCaliber() {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into reporting_product_metric_definition (id,tenant_id,metric_code,"
                  + "display_name,unit,aggregation,currency_scope,currency_aware,description,owner_module) "
                  + "values (?,?,?,?,?,?,?,?,?,?)",
              Ids.next(),
              tenant,
              "PRODUCT_SALES_AMOUNT",
              "成交金额",
              "CURRENCY",
              "SUM",
              "SAME",
              true,
              "该商品维度下成交订单行的成交金额合计。",
              "9.1.1.1");
          return null;
        });
  }

  /** 落一个目标（附一条分解计划；分解项合计必须精确等于目标值）。 */
  private UUID seedTarget(
      String scopeType,
      String metricCode,
      String ownerId,
      String ownerName,
      String currency,
      BigDecimal targetValue,
      LocalDate periodStart,
      LocalDate periodEnd) {
    var id = Ids.next();
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into reporting_target (id,tenant_id,metric_code,scope_type,owner_id,owner_code,"
                  + "owner_name,currency,target_value,baseline_status,period_start,period_end,"
                  + "granularity,decomposition_method,status) "
                  + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
              id,
              tenant,
              metricCode,
              scopeType,
              ownerId,
              ownerName,
              ownerName,
              currency,
              targetValue,
              "NO_DATA",
              periodStart,
              periodEnd,
              "MONTHLY",
              "EVEN",
              "ACTIVE");
          sql.execute(
              "insert into reporting_target_bucket (id,tenant_id,target_id,bucket_start,bucket_end,"
                  + "granularity,currency,target_value,weight) values (?,?,?,?,?,?,?,?,?)",
              Ids.next(),
              tenant,
              id,
              periodStart,
              periodEnd,
              "MONTHLY",
              currency,
              targetValue,
              new BigDecimal("1"));
          return null;
        });
    return id;
  }

  /** 落一条店铺维度事实。 */
  private void seedStoreFact(
      String shopCode, String metricCode, LocalDate day, String currency, BigDecimal value) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into reporting_store_metric_fact (id,tenant_id,shop_code,metric_code,"
                  + "bucket_date,currency,value,quantity,timezone,source_service,source_reference) "
                  + "values (?,?,?,?,?,?,?,?,?,?,?)",
              Ids.next(),
              tenant,
              shopCode,
              metricCode,
              day,
              currency,
              value,
              BigDecimal.ZERO,
              "UTC",
              "channel",
              shopCode + "-" + day);
          return null;
        });
  }

  /** 落一条商品维度事实（SPU 层）。 */
  private void seedProductFact(
      UUID dimensionId, String metricCode, LocalDate day, String currency, BigDecimal value) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into reporting_product_metric_fact (id,tenant_id,dimension_type,dimension_id,"
                  + "dimension_key,metric_code,bucket_date,currency,value,quantity,timezone,"
                  + "source_service,source_reference) values (?,?,?,?,?,?,?,?,?,?,?,?,?)",
              Ids.next(),
              tenant,
              "SPU",
              dimensionId,
              "SPU-" + dimensionId,
              metricCode,
              day,
              currency,
              value,
              BigDecimal.ZERO,
              "UTC",
              "trade",
              "spu-" + dimensionId + "-" + day);
          return null;
        });
  }

  /** 看板里按归属对象找一行。 */
  private ReportingTargetAttainmentModels.AttainmentRow rowOf(
      ReportingTargetAttainmentModels.AttainmentBoard board, String ownerName) {
    for (var group : board.groups()) {
      for (var row : group.rows()) {
        if (ownerName.equals(row.ownerName())) return row;
      }
    }
    throw new AssertionError("看板里没有归属对象：" + ownerName);
  }

  // ==========================================================================
  // 断言：当前值 / 达成率
  // ==========================================================================

  @Test
  void 周期已过完_当前值与达成率都真实_预测等于当前值() {
    var today = dbToday();
    var last = previousMonth(today);
    seedStoreCaliber();
    seedStoreFact("SHOP-A", "STORE_GMV_PLATFORM", last.start(), "USD", new BigDecimal("300"));
    seedStoreFact("SHOP-A", "STORE_GMV_PLATFORM", last.end(), "USD", new BigDecimal("200"));
    seedTarget(
        "SHOP",
        "STORE_GMV_PLATFORM",
        "SHOP-A",
        "店铺甲",
        "USD",
        new BigDecimal("1000"),
        last.start(),
        last.end());

    var board = attainment.board(tenant, null, null, null, null, 0, 50);

    var row = rowOf(board, "店铺甲");
    assertThat(row.currentValue()).isEqualByComparingTo("500");
    assertThat(row.attainmentRate()).isEqualByComparingTo("0.5");
    // 周期已走完 ⇒ 时间进度 = 1 ⇒ 预测期末值 = 当前值。
    assertThat(row.elapsedRatio()).isEqualByComparingTo("1");
    assertThat(row.forecastValue()).isEqualByComparingTo("500");
    assertThat(row.forecastRate()).isEqualByComparingTo("0.5");
    // 达成率 50% ⇒ 预测也是 50%（周期已走完，预测 = 当前值）⇒ 低于 80% ⇒ BEHIND。
    assertThat(row.status()).isEqualTo("BEHIND");
    assertThat(row.actualStatus()).isEqualTo("AVAILABLE");
    assertThat(row.underAchieved()).as("已过完的周期只完成一半 ⇒ 进未达标名单").isTrue();
  }

  @Test
  void 周期进行中_预测按当前速度推算期末值() {
    var today = dbToday();
    var current = monthOf(today);
    seedStoreCaliber();
    // 周期内每天 100，到今天为止共 value = 100 × 已经过的天数。
    var elapsedDays = today.getDayOfMonth();
    for (int day = 1; day <= elapsedDays; day++) {
      seedStoreFact(
          "SHOP-A", "STORE_GMV_PLATFORM", current.start().plusDays(day - 1), "USD", new BigDecimal("100"));
    }
    seedTarget(
        "SHOP",
        "STORE_GMV_PLATFORM",
        "SHOP-A",
        "店铺甲",
        "USD",
        new BigDecimal("1000"),
        current.start(),
        current.end());

    var board = attainment.board(tenant, null, null, null, null, 0, 50);

    var row = rowOf(board, "店铺甲");
    var expectedCurrent = new BigDecimal("100").multiply(new BigDecimal(elapsedDays));
    var expectedRate =
        expectedCurrent.divide(new BigDecimal("1000"), 6, java.math.RoundingMode.HALF_UP);
    assertThat(row.currentValue()).isEqualByComparingTo(expectedCurrent);
    assertThat(row.attainmentRate()).isEqualByComparingTo(expectedRate);
    // 时间进度 = 已过天数 ÷ 本月天数。
    assertThat(row.elapsedRatio())
        .isEqualByComparingTo(
            new BigDecimal(elapsedDays)
                .divide(new BigDecimal(current.end().getDayOfMonth()), 6, java.math.RoundingMode.HALF_UP));
    // 每天 100 ⇒ 按当前速度推算，期末 = 100 × 本月天数。
    assertThat(row.forecastValue()).isEqualByComparingTo(new BigDecimal("100").multiply(new BigDecimal(current.end().getDayOfMonth())));
    // 状态由算出来的达成率决定（每天 100 ⇒ 已过 10 天就达到 1000）：**不写死**，跟着算。
    assertThat(row.status())
        .isEqualTo(expectedRate.compareTo(BigDecimal.ONE) >= 0 ? "ACHIEVED" : "ON_TRACK");
  }

  // ==========================================================================
  // 断言：「算不出来」≠「等于 0」
  // ==========================================================================

  @Test
  void 周期尚未开始_达成率算不出来而不是零() {
    var today = dbToday();
    var next = nextMonth(today);
    seedStoreCaliber();
    seedTarget(
        "SHOP",
        "STORE_GMV_PLATFORM",
        "SHOP-A",
        "店铺甲",
        "USD",
        new BigDecimal("1000"),
        next.start(),
        next.end());

    var board = attainment.board(tenant, null, null, null, null, 0, 50);

    var row = rowOf(board, "店铺甲");
    assertThat(row.status()).isEqualTo("NOT_STARTED");
    assertThat(row.attainmentRate()).as("周期还没开始 ⇒ 达成率算不出来，**不是 0%**").isNull();
    assertThat(row.currentValue()).as("没有发生过 ⇒ 当前值算不出来，**不是 0**").isNull();
    assertThat(row.forecastValue()).as("时间进度为 0 ⇒ 预测算不出来，**不是 0**").isNull();
    assertThat(row.underAchieved()).as("「还没开始」不是「没达标」").isFalse();
  }

  @Test
  void 从来没有过事实_是取不到而不是零() {
    var today = dbToday();
    var last = previousMonth(today);
    seedStoreCaliber();
    // 只给**另一个**店铺落事实 ⇒ SHOP-B 这套口径上**从来没有过事实行**。
    seedStoreFact("SHOP-A", "STORE_GMV_PLATFORM", last.start(), "USD", new BigDecimal("300"));
    seedTarget(
        "SHOP",
        "STORE_GMV_PLATFORM",
        "SHOP-B",
        "店铺乙",
        "USD",
        new BigDecimal("1000"),
        last.start(),
        last.end());

    var board = attainment.board(tenant, null, null, null, null, 0, 50);

    var row = rowOf(board, "店铺乙");
    assertThat(row.actualStatus()).isEqualTo("NO_DATA");
    assertThat(row.status()).isEqualTo("UNAVAILABLE");
    assertThat(row.currentValue()).as("从来没有事实 ⇒ 当前值取不到，**不是 0**").isNull();
    assertThat(row.attainmentRate()).as("取不到 ⇒ 达成率算不出来，**不是 0%**").isNull();
    assertThat(row.underAchieved()).as("「取不到」不是「没达标」").isFalse();
  }

  @Test
  void 有过事实但本窗口没有_当前值是真的零() {
    var today = dbToday();
    var last = previousMonth(today);
    seedStoreCaliber();
    // SHOP-A 在**上上月**有过事实（上上个月不属于本目标周期）⇒ 这套口径是「活的」，
    // 但本窗口内一行都没有 ⇒ 当前值是**真的 0**（窗口内确实没有发生）。
    var older = monthOf(last.start().minusMonths(1));
    seedStoreFact("SHOP-A", "STORE_GMV_PLATFORM", older.start(), "USD", new BigDecimal("700"));
    seedTarget(
        "SHOP",
        "STORE_GMV_PLATFORM",
        "SHOP-A",
        "店铺甲",
        "USD",
        new BigDecimal("1000"),
        last.start(),
        last.end());

    var board = attainment.board(tenant, null, null, null, null, 0, 50);

    var row = rowOf(board, "店铺甲");
    assertThat(row.actualStatus()).isEqualTo("AVAILABLE");
    assertThat(row.currentValue()).as("窗口内确实没有发生 ⇒ 当前值是真的 0").isEqualByComparingTo("0");
    assertThat(row.attainmentRate()).isEqualByComparingTo("0");
    assertThat(row.status()).isEqualTo("BEHIND");
    assertThat(row.underAchieved()).as("0% 达成率是真的断言 ⇒ 进未达标名单").isTrue();
  }

  @Test
  void 部门与人员维度_实际值尚未投影_如实报不可得() {
    var today = dbToday();
    var last = previousMonth(today);
    seedOrgCaliber();
    seedTarget(
        "DEPARTMENT",
        "SALES_AMOUNT",
        UUID.randomUUID().toString(),
        "销售部",
        "USD",
        new BigDecimal("1000"),
        last.start(),
        last.end());
    seedTarget(
        "MEMBER",
        "SALES_AMOUNT",
        UUID.randomUUID().toString(),
        "张三",
        "USD",
        new BigDecimal("1000"),
        last.start(),
        last.end());

    var board = attainment.board(tenant, null, null, null, null, 0, 50);

    var department = rowOf(board, "销售部");
    assertThat(department.actualStatus()).isEqualTo("NOT_AVAILABLE");
    assertThat(department.status()).isEqualTo("UNAVAILABLE");
    assertThat(department.currentValue()).as("部门维度没有事实来源 ⇒ **不拿租户级数字冒充**").isNull();
    assertThat(department.attainmentRate()).isNull();

    var member = rowOf(board, "张三");
    assertThat(member.actualStatus()).isEqualTo("NOT_AVAILABLE");
    assertThat(member.currentValue()).isNull();
    assertThat(member.attainmentRate()).isNull();
  }

  // ==========================================================================
  // 断言：产品维度
  // ==========================================================================

  @Test
  void 产品维度_商品投影跑过则算得出_没跑过则报不可得() {
    var today = dbToday();
    var last = previousMonth(today);
    seedOrgCaliber();
    seedProductCaliber();
    var withFacts = UUID.randomUUID();
    var withoutFacts = UUID.randomUUID();
    seedTarget(
        "PRODUCT",
        "SALES_AMOUNT",
        withFacts.toString(),
        "蓝色T恤",
        "USD",
        new BigDecimal("1000"),
        last.start(),
        last.end());
    seedTarget(
        "PRODUCT",
        "SALES_AMOUNT",
        withoutFacts.toString(),
        "红色T恤",
        "USD",
        new BigDecimal("1000"),
        last.start(),
        last.end());
    seedProductFact(
        withFacts, "PRODUCT_SALES_AMOUNT", last.start(), "USD", new BigDecimal("250"));

    var board = attainment.board(tenant, null, null, null, null, 0, 50);

    var ready = rowOf(board, "蓝色T恤");
    assertThat(ready.actualStatus()).isEqualTo("AVAILABLE");
    assertThat(ready.currentValue()).isEqualByComparingTo("250");
    assertThat(ready.attainmentRate()).isEqualByComparingTo("0.25");

    var notReady = rowOf(board, "红色T恤");
    assertThat(notReady.actualStatus()).isEqualTo("NO_DATA");
    assertThat(notReady.currentValue()).as("商品维度还没投影 ⇒ 当前值取不到，**不是 0**").isNull();
    assertThat(notReady.attainmentRate()).as("取不到 ⇒ **不是 0% 达成率**").isNull();
  }

  @Test
  void 产品维度_毛利额没有等价口径_如实报不可得() {
    var today = dbToday();
    var last = previousMonth(today);
    seedOrgCaliber();
    // 毛利额在商品维度**没有等价口径**（成本分录没有商品维度）⇒ 不得拿别的口径冒充。
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into reporting_metric_definition (id,tenant_id,metric_code,display_name,unit,"
                  + "aggregation,numerator_metric_code,denominator_metric_code,currency_aware,"
                  + "description,owner_module) values (?,?,?,?,?,?,?,?,?,?,?)",
              Ids.next(),
              tenant,
              "GROSS_PROFIT",
              "毛利额",
              "CURRENCY",
              "DERIVED_DIFF",
              "SALES_AMOUNT",
              "COGS_AMOUNT",
              true,
              "销售额 − 成本。",
              "13.1.1.1");
          return null;
        });
    seedTarget(
        "PRODUCT",
        "GROSS_PROFIT",
        UUID.randomUUID().toString(),
        "蓝色T恤",
        "USD",
        new BigDecimal("1000"),
        last.start(),
        last.end());

    var board = attainment.board(tenant, null, null, null, null, 0, 50);

    var row = rowOf(board, "蓝色T恤");
    assertThat(row.actualStatus()).isEqualTo("NOT_AVAILABLE");
    assertThat(row.currentValue()).isNull();
    assertThat(row.attainmentRate()).isNull();
  }

  // ==========================================================================
  // 断言：排名与预警
  // ==========================================================================

  @Test
  void 排名_只在同维度同口径同币种同周期内_算不出来的不进排名() {
    var today = dbToday();
    var last = previousMonth(today);
    seedStoreCaliber();
    seedStoreFact("SHOP-A", "STORE_GMV_PLATFORM", last.start(), "USD", new BigDecimal("300"));
    seedStoreFact("SHOP-B", "STORE_GMV_PLATFORM", last.start(), "USD", new BigDecimal("800"));
    seedTarget(
        "SHOP",
        "STORE_GMV_PLATFORM",
        "SHOP-A",
        "店铺甲",
        "USD",
        new BigDecimal("1000"),
        last.start(),
        last.end());
    seedTarget(
        "SHOP",
        "STORE_GMV_PLATFORM",
        "SHOP-B",
        "店铺乙",
        "USD",
        new BigDecimal("1000"),
        last.start(),
        last.end());
    seedTarget(
        "SHOP",
        "STORE_GMV_PLATFORM",
        "SHOP-C",
        "店铺丙",
        "USD",
        new BigDecimal("1000"),
        last.start(),
        last.end());

    var ranking = attainment.ranking(tenant, "SHOP", null, null, null, 0, 50);

    assertThat(ranking.total()).as("算不出达成率的目标不进排名").isEqualTo(2);
    assertThat(ranking.entries().get(0).ownerName()).isEqualTo("店铺乙");
    assertThat(ranking.entries().get(0).rank()).isEqualTo(1);
    assertThat(ranking.entries().get(1).ownerName()).isEqualTo("店铺甲");
    assertThat(ranking.entries().get(1).rank()).isEqualTo(2);
    assertThat(ranking.entries().get(0).rankTotal()).isEqualTo(2);
    assertThat(ranking.entries()).extracting(r -> r.ownerName()).doesNotContain("店铺丙");
  }

  @Test
  void 预警_只报算得出达成率且不达标的_取不到的另算一类() {
    var today = dbToday();
    var last = previousMonth(today);
    seedStoreCaliber();
    seedStoreFact("SHOP-A", "STORE_GMV_PLATFORM", last.start(), "USD", new BigDecimal("100"));
    seedTarget(
        "SHOP",
        "STORE_GMV_PLATFORM",
        "SHOP-A",
        "店铺甲",
        "USD",
        new BigDecimal("1000"),
        last.start(),
        last.end());
    seedTarget(
        "SHOP",
        "STORE_GMV_PLATFORM",
        "SHOP-B",
        "店铺乙",
        "USD",
        new BigDecimal("1000"),
        last.start(),
        last.end());

    var board = attainment.board(tenant, null, null, null, null, 0, 50);

    assertThat(board.alerts()).isNotEmpty();
    var behind = board.alerts().stream().filter(a -> "店铺甲".equals(a.ownerName())).findFirst();
    assertThat(behind).isPresent();
    assertThat(behind.get().kind()).isEqualTo("FORECAST_BELOW_TARGET");
    assertThat(behind.get().level()).isEqualTo("CRITICAL");

    var unavailable = board.alerts().stream().filter(a -> "店铺乙".equals(a.ownerName())).findFirst();
    assertThat(unavailable).isPresent();
    assertThat(unavailable.get().kind()).isEqualTo("NO_ACTUALS");
    assertThat(unavailable.get().level()).isEqualTo("WARNING");
    assertThat(unavailable.get().message()).contains("不是").contains("落后了");
  }

  @Test
  void 概览计数_算不出来的那几类单独计数_不并进未达标() {
    var today = dbToday();
    var last = previousMonth(today);
    var next = nextMonth(today);
    seedStoreCaliber();
    seedStoreFact("SHOP-A", "STORE_GMV_PLATFORM", last.start(), "USD", new BigDecimal("100"));
    seedTarget(
        "SHOP",
        "STORE_GMV_PLATFORM",
        "SHOP-A",
        "店铺甲",
        "USD",
        new BigDecimal("1000"),
        last.start(),
        last.end());
    seedTarget(
        "SHOP",
        "STORE_GMV_PLATFORM",
        "SHOP-B",
        "店铺乙",
        "USD",
        new BigDecimal("1000"),
        last.start(),
        last.end());
    seedTarget(
        "SHOP",
        "STORE_GMV_PLATFORM",
        "SHOP-C",
        "店铺丙",
        "USD",
        new BigDecimal("1000"),
        next.start(),
        next.end());

    var summary = attainment.board(tenant, null, null, null, null, 0, 50).summary();

    assertThat(summary.total()).isEqualTo(3);
    assertThat(summary.behind()).isEqualTo(1);
    assertThat(summary.unavailable()).isEqualTo(1);
    assertThat(summary.notStarted()).isEqualTo(1);
    assertThat(summary.underAchieved()).as("「算不出来」的两类不进未达标").isEqualTo(1);
  }

  // ==========================================================================
  // 断言：分解计划与快照
  // ==========================================================================

  @Test
  void 分解计划_逐项达成情况来自真实的分解表() {
    var today = dbToday();
    var last = previousMonth(today);
    seedStoreCaliber();
    seedStoreFact("SHOP-A", "STORE_GMV_PLATFORM", last.start(), "USD", new BigDecimal("250"));
    seedTarget(
        "SHOP",
        "STORE_GMV_PLATFORM",
        "SHOP-A",
        "店铺甲",
        "USD",
        new BigDecimal("1000"),
        last.start(),
        last.end());

    var row = rowOf(attainment.board(tenant, null, null, null, null, 0, 50), "店铺甲");

    assertThat(row.buckets()).hasSize(1);
    var bucket = row.buckets().get(0);
    assertThat(bucket.bucketStart()).isEqualTo(last.start());
    assertThat(bucket.bucketEnd()).isEqualTo(last.end());
    assertThat(bucket.targetValue()).isEqualByComparingTo("1000");
    assertThat(bucket.currentValue()).isEqualByComparingTo("250");
    assertThat(bucket.attainmentRate()).isEqualByComparingTo("0.25");
  }

  @Test
  void 评估落快照_时间戳原样写回() {
    var today = dbToday();
    var last = previousMonth(today);
    seedStoreCaliber();
    seedStoreFact("SHOP-A", "STORE_GMV_PLATFORM", last.start(), "USD", new BigDecimal("500"));
    seedTarget(
        "SHOP",
        "STORE_GMV_PLATFORM",
        "SHOP-A",
        "店铺甲",
        "USD",
        new BigDecimal("1000"),
        last.start(),
        last.end());

    var result = attainment.evaluate(tenant, null, null, null, null);
    assertThat(result.evaluated()).isEqualTo(1);
    assertThat(result.asOfDate()).isEqualTo(today);

    var snapshots = attainment.snapshots(tenant, null, null, null, 0, 50);
    assertThat(snapshots.total()).isEqualTo(1);
    var snapshot = snapshots.snapshots().get(0);
    assertThat(snapshot.evaluatedOn()).isEqualTo(today);
    assertThat(snapshot.currentValue()).isEqualByComparingTo("500");
    assertThat(snapshot.attainmentRate()).isEqualByComparingTo("0.5");
    assertThat(snapshot.status()).isEqualTo("BEHIND");

    // 🔴 **断言写回的值**（不是「没抛异常」）：评估时刻必须原样落库。
    var written =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                        "select evaluated_at from reporting_target_attainment where target_id = ?",
                        snapshot.targetId())
                    .get(0, java.time.OffsetDateTime.class));
    assertThat(written.toInstant())
        .as("evaluated_at 必须原样写回（**不是**偏了 8 小时的另一个瞬间）")
        .isEqualTo(result.evaluatedAt().toInstant());
  }

  @Test
  void 快照清单_真分页与真计数() {
    var today = dbToday();
    var last = previousMonth(today);
    seedStoreCaliber();
    for (int i = 0; i < 3; i++) {
      seedStoreFact("SHOP-" + i, "STORE_GMV_PLATFORM", last.start(), "USD", new BigDecimal("100"));
      seedTarget(
          "SHOP",
          "STORE_GMV_PLATFORM",
          "SHOP-" + i,
          "店铺" + i,
          "USD",
          new BigDecimal("1000"),
          last.start(),
          last.end());
    }
    attainment.evaluate(tenant, null, null, null, null);

    var first = attainment.snapshots(tenant, null, null, null, 0, 2);
    assertThat(first.total()).isEqualTo(3);
    assertThat(first.snapshots()).hasSize(2);

    var second = attainment.snapshots(tenant, null, null, null, 1, 2);
    assertThat(second.total()).isEqualTo(3);
    assertThat(second.snapshots()).hasSize(1);
  }

  // ==========================================================================
  // 断言：能力声明
  // ==========================================================================

  @Test
  void 能力声明_如实列出拿不到的部分() {
    var capabilities = attainment.capabilities(tenant);

    assertThat(capabilities.upstreamConnected()).isTrue();
    assertThat(capabilities.notDelivered())
        .as("部门 / 人员维度的实际值不可得必须**如实列出**")
        .anyMatch(line -> line.contains("部门") && line.contains("不可得"));
    assertThat(capabilities.rules()).isNotEmpty();
  }
}
