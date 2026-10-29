package com.starcon.reporting;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
 * 13.6.1.1 自定义报表的**集成测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>本文件验的是**业务口径**（多租户三条断言在 {@code CustomReportTenancyIntegrationTest}）：
 *
 * <ul>
 *   <li>度量**只能引用已登记的 metric_code**（口径唯一）—— 服务层拒绝 **且 DB 触发器也拒绝**；</li>
 *   <li>维度**只能是白名单里的 5 个码** —— 服务层拒绝 **且 DB CHECK 也拒绝**；</li>
 *   <li>派生型指标**按登记口径在服务端算**（分子分母分别取数再算）；</li>
 *   <li>🔴 **分母为 0 ⇒ 比率为 NULL（不可比），不是 0**；</li>
 *   <li>🔴 **涉币指标不跨币种相加**（与货币无关的量才给跨币种合计）；</li>
 *   <li>🔴 **筛选值只走绑定参数**（SQL 注入串进不来）；</li>
 *   <li>分享链接**绑定租户 + 绑定归属**，未授权与不存在**逐字相同的 404**；</li>
 *   <li>订阅计划按 cron 落成**待投递记录**（PENDING，**不是「已发送」**）。</li>
 * </ul>
 *
 * <p>⚠️ **事实是真的投影事实**：夹具直接写 {@code reporting_metric_fact}（13.1.1.1 的投影表），
 * 口径表由 13.1.1.1 的服务层播种 —— 本模块**只读**它们，不自己发明指标码。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomReportIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
  static final String BEARER = "Bearer tenant-a";

  static HttpServer identity;

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of("reporting.custom-report.read", "reporting.custom-report.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  static final LocalDate DAY1 = LocalDate.of(2026, 10, 1);
  static final LocalDate DAY2 = LocalDate.of(2026, 10, 2);

  /** 同一个租户里的**另一个用户**（用来测「个人分享链接对别人不可见」）。 */
  static final UUID colleagueUser = UUID.randomUUID();
  static final String BEARER_COLLEAGUE = "Bearer tenant-a-colleague";

  static final UUID SHOP_A = UUID.randomUUID();
  static final UUID SHOP_B = UUID.randomUUID();
  /** 🔴 只有零销售额的店铺：用来证明「分母为 0 ⇒ 不可比」，不是 0。 */
  static final UUID SHOP_ZERO = UUID.randomUUID();

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
      actors.put(BEARER, actor(tenant));
      actors.put(BEARER_COLLEAGUE, actor(tenant, colleagueUser));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static AccessService.Access actor(UUID tenantId) {
    return actor(tenantId, user);
  }

  static AccessService.Access actor(UUID tenantId, UUID userId) {
    var grant = new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions);
    return new AccessService.Access(
        userId, tenantId, "Test", UUID.randomUUID(), null, permissions, List.of(grant));
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
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ReportingCustomReportService reports;

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
          "truncate reporting_custom_report_delivery, reporting_custom_report_subscription,"
              + " reporting_custom_report_share, reporting_custom_report_filter,"
              + " reporting_custom_report_dimension, reporting_custom_report_measure,"
              + " reporting_custom_report, reporting_metric_fact cascade");
    }
    // 播种口径（13.1.1.1 的服务层干的活）—— 本模块只读它。
    reports.metricCatalog(tenant);
    seedFacts();
  }

  // ==========================================================================
  // 夹具事实
  // ==========================================================================

  private void seedFacts() {
    // 店铺 A：DAY1 销售额 100 / 成本 60 / 订单 2 / 销量 3；DAY2 销售额 200 / 成本 100 / 订单 4。
    fact(SHOP_A, DAY1, "SALES_AMOUNT", "USD", "100.00");
    fact(SHOP_A, DAY1, "COGS_AMOUNT", "USD", "60.00");
    fact(SHOP_A, DAY1, "ORDER_COUNT", "USD", "2");
    fact(SHOP_A, DAY1, "UNIT_SOLD", "USD", "3");
    fact(SHOP_A, DAY2, "SALES_AMOUNT", "USD", "200.00");
    fact(SHOP_A, DAY2, "COGS_AMOUNT", "USD", "100.00");
    fact(SHOP_A, DAY2, "ORDER_COUNT", "USD", "4");
    // 店铺 B：DAY1 美元；DAY2 欧元 ⇒ 跨币种，但**绝不相加**。
    fact(SHOP_B, DAY1, "SALES_AMOUNT", "USD", "50.00");
    fact(SHOP_B, DAY1, "COGS_AMOUNT", "USD", "50.00");
    fact(SHOP_B, DAY1, "ORDER_COUNT", "USD", "1");
    fact(SHOP_B, DAY2, "SALES_AMOUNT", "EUR", "80.00");
    fact(SHOP_B, DAY2, "COGS_AMOUNT", "EUR", "20.00");
    fact(SHOP_B, DAY2, "ORDER_COUNT", "EUR", "2");
    // 🔴 零店铺：销售额真的是 0（**这个 0 是真的**），退货额 0 ⇒ 退货率的分母是 0。
    fact(SHOP_ZERO, DAY1, "SALES_AMOUNT", "USD", "0.00");
    fact(SHOP_ZERO, DAY1, "RETURNED_AMOUNT", "USD", "0.00");
    fact(SHOP_ZERO, DAY1, "ORDER_COUNT", "USD", "3");
  }

  private void fact(UUID shopId, LocalDate day, String metricCode, String currency, String value) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into reporting_metric_fact"
                  + " (id,tenant_id,scope_type,legal_entity_id,shop_id,metric_code,bucket_date,currency,"
                  + " value,timezone,source_service,source_reference,updated_at)"
                  + " values (?,?,?,?,?,?,?,?,?,?,?,?,now())",
              UUID.randomUUID(),
              tenant,
              "SHOP",
              null,
              shopId,
              metricCode,
              day,
              currency,
              new BigDecimal(value),
              "UTC",
              "trade",
              "seed-" + shopId + "-" + metricCode + "-" + day + "-" + currency);
          return null;
        });
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  private UUID newReport(
      String code,
      List<String> measures,
      List<String> dimensions,
      List<ReportingCustomReportModels.CustomReportFilterRequest> filters) {
    var view =
        reports.save(
            tenant,
            user,
            null,
            new ReportingCustomReportModels.CustomReportSaveRequest(
                code, "测试报表 " + code, "说明", "TEAM", "TABLE", DAY1, DAY2, measures, dimensions, filters));
    return UUID.fromString(view.reportId());
  }

  private ReportingCustomReportModels.CustomReportRunResponse run(UUID reportId) {
    return reports.run(tenant, reportId, user, DAY1, DAY2, 0, 50);
  }

  private ReportingCustomReportModels.CustomReportMeasureCell cell(
      ReportingCustomReportModels.CustomReportRow row, String metricCode) {
    for (var measure : row.measures()) {
      if (measure.metricCode().equals(metricCode)) return measure;
    }
    throw new AssertionError("这一行没有度量 " + metricCode);
  }

  private ReportingCustomReportModels.CustomReportRow rowOf(
      ReportingCustomReportModels.CustomReportRunResponse response, String dimensionValue) {
    for (var row : response.rows()) {
      for (var dimension : row.dimensions()) {
        if (dimensionValue.equals(dimension.value())) return row;
      }
    }
    throw new AssertionError("没有维度取值为 " + dimensionValue + " 的行");
  }

  private BigDecimal valueOf(
      ReportingCustomReportModels.CustomReportMeasureCell cell, String currency) {
    for (var value : cell.values()) {
      if (currency.equals(value.currency())) return value.value();
    }
    return null;
  }

  // ==========================================================================
  // 语义层目录
  // ==========================================================================

  @Test
  void 语义层指标清单来自口径表() {
    var catalog = reports.metricCatalog(tenant);
    assertThat(catalog).as("标准口径要能被读到").isNotEmpty();
    assertThat(catalog).extracting(m -> m.metricCode()).contains("SALES_AMOUNT", "GROSS_MARGIN");
    var margin = catalog.stream().filter(m -> m.metricCode().equals("GROSS_MARGIN")).findFirst().orElseThrow();
    assertThat(margin.aggregation()).isEqualTo("DERIVED_RATIO");
    assertThat(margin.numeratorMetricCode()).isEqualTo("GROSS_PROFIT");
    assertThat(margin.denominatorMetricCode()).isEqualTo("SALES_AMOUNT");
    assertThat(margin.description()).as("口径说明要能原样显示").isNotBlank();
  }

  @Test
  void 维度白名单只有五个() {
    var dimensions = reports.dimensionCatalog();
    assertThat(dimensions).extracting(d -> d.dimensionCode())
        .containsExactly("SCOPE_TYPE", "LEGAL_ENTITY", "SHOP", "BUCKET_DATE", "CURRENCY");
  }

  @Test
  void 能力声明显式标出未交付的导出与投递() {
    var capabilities = reports.capabilities();
    assertThat(capabilities.exportAvailable()).as("导出属 13.6.2.1，未交付").isFalse();
    assertThat(capabilities.exportOwnerModule()).contains("13.6.2.1");
    assertThat(capabilities.exportUnavailableReason()).isNotBlank();
    assertThat(capabilities.deliveryAvailable()).as("实际投递属 13.6.2.1，未交付").isFalse();
    assertThat(capabilities.deliveryOwnerModule()).contains("13.6.2.1");
    assertThat(capabilities.deliveryUnavailableReason()).isNotBlank();
    assertThat(capabilities.reportAvailable()).isTrue();
    assertThat(capabilities.subscriptionAvailable()).isTrue();
  }

  // ==========================================================================
  // 口径唯一（度量必须引用已登记的指标码）
  // ==========================================================================

  @Test
  void 未登记的指标码被服务层拒绝() {
    var error =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                reports.save(
                    tenant,
                    user,
                    null,
                    new ReportingCustomReportModels.CustomReportSaveRequest(
                        "R-UNKNOWN",
                        "未登记指标",
                        "",
                        "PRIVATE",
                        "TABLE",
                        null,
                        null,
                        List.of("NOT_REGISTERED_METRIC"),
                        List.of("SHOP"),
                        List.of())));
    assertThat(error.getStatusCode().value()).isEqualTo(400);
    assertThat(error.getReason()).contains("未在口径表");
  }

  @Test
  void 未登记的指标码被数据库触发器拒绝() {
    // 🔴 口径唯一在**数据层**也是真的：绕过服务层直接写，也写不进未登记的指标码。
    var error =
        assertThrows(
            RuntimeException.class,
            () ->
                tenants.run(
                    tenant,
                    () -> {
                      sql.execute(
                          "insert into reporting_custom_report"
                              + " (id,tenant_id,report_code,display_name,description,owner_user_id,"
                              + " visibility,chart_type,created_at,updated_at)"
                              + " values (?,?,?,?,'',?,'PRIVATE','TABLE',now(),now())",
                          UUID.randomUUID(),
                          tenant,
                          "R-TRIGGER",
                          "触发器用",
                          user);
                      sql.execute(
                          "insert into reporting_custom_report_measure"
                              + " (id,tenant_id,report_id,metric_code,display_order,created_at)"
                              + " values (?,?,?,?,0,now())",
                          UUID.randomUUID(),
                          tenant,
                          sql.fetchOne(
                                  "select id from reporting_custom_report where tenant_id = ? and report_code = ?",
                                  tenant,
                                  "R-TRIGGER")
                              .get(0, UUID.class),
                          "STILL_NOT_REGISTERED");
                      return null;
                    }));
    assertThat(error.getMessage()).as("触发器要说出「未登记」").contains("尚未在口径表中登记");
  }

  @Test
  void 白名单外的维度码被数据库拒绝() {
    var error =
        assertThrows(
            RuntimeException.class,
            () ->
                tenants.run(
                    tenant,
                    () -> {
                      sql.execute(
                          "insert into reporting_custom_report"
                              + " (id,tenant_id,report_code,display_name,description,owner_user_id,"
                              + " visibility,chart_type,created_at,updated_at)"
                              + " values (?,?,?,?,'',?,'PRIVATE','TABLE',now(),now())",
                          UUID.randomUUID(),
                          tenant,
                          "R-BADDIM",
                          "白名单外维度",
                          user);
                      sql.execute(
                          "insert into reporting_custom_report_dimension"
                              + " (id,tenant_id,report_id,dimension_code,display_order,created_at)"
                              + " values (?,?,?,?,0,now())",
                          UUID.randomUUID(),
                          tenant,
                          sql.fetchOne(
                                  "select id from reporting_custom_report where tenant_id = ? and report_code = ?",
                                  tenant,
                                  "R-BADDIM")
                              .get(0, UUID.class),
                          "PII_TABLE");
                      return null;
                    }));
    assertThat(error.getMessage()).as("CHECK 约束要挡住白名单外的维度").isNotBlank();
  }

  @Test
  void 白名单外的维度码被服务层拒绝() {
    var error =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                reports.save(
                    tenant,
                    user,
                    null,
                    new ReportingCustomReportModels.CustomReportSaveRequest(
                        "R-BADDIM2",
                        "白名单外维度",
                        "",
                        "PRIVATE",
                        "TABLE",
                        null,
                        null,
                        List.of("SALES_AMOUNT"),
                        List.of("PII_TABLE"),
                        List.of())));
    assertThat(error.getStatusCode().value()).isEqualTo(400);
    assertThat(error.getReason()).contains("维度码只能是");
  }

  // ==========================================================================
  // 报表结果
  // ==========================================================================

  @Test
  void 按维度分组并按币种分别报() {
    var reportId = newReport("R-GROUP", List.of("SALES_AMOUNT", "ORDER_COUNT"), List.of("SHOP"), List.of());
    var response = run(reportId);

    assertThat(response.status()).isEqualTo("AVAILABLE");
    assertThat(response.total()).as("三个店铺 = 三个维度组合").isEqualTo(3);

    var shopA = rowOf(response, SHOP_A.toString());
    assertThat(valueOf(cell(shopA, "SALES_AMOUNT"), "USD"))
        .as("店铺 A 的销售额 = 100 + 200").isEqualByComparingTo("300.00");
    assertThat(valueOf(cell(shopA, "ORDER_COUNT"), ""))
        .as("订单量与货币无关 ⇒ 给一个跨币种合计，币种是空串").isEqualByComparingTo("6");
  }

  @Test
  void 涉币指标不跨币种相加() {
    var reportId = newReport("R-CUR", List.of("SALES_AMOUNT"), List.of("SHOP"), List.of());
    var response = run(reportId);

    var shopB = rowOf(response, SHOP_B.toString());
    var sales = cell(shopB, "SALES_AMOUNT");
    assertThat(sales.currencyAware()).isTrue();
    assertThat(sales.values()).extracting(v -> v.currency()).containsExactlyInAnyOrder("USD", "EUR");
    assertThat(valueOf(sales, "USD")).isEqualByComparingTo("50.00");
    assertThat(valueOf(sales, "EUR")).isEqualByComparingTo("80.00");
    assertThat(valueOf(sales, "")).as("涉币指标**没有**跨币种合计").isNull();
  }

  @Test
  void 派生指标按登记口径在服务端算() {
    var reportId = newReport("R-DERIVED", List.of("GROSS_MARGIN"), List.of("SHOP"), List.of());
    var response = run(reportId);

    // 毛利率 = 毛利额 ÷ 销售额 = (100+200 − 60−100) ÷ (100+200) = 140 ÷ 300 = 46.6667%
    var margin = cell(rowOf(response, SHOP_A.toString()), "GROSS_MARGIN");
    assertThat(valueOf(margin, "USD")).isEqualByComparingTo("46.6667");
  }

  @Test
  void 分母为零时比率是不可比而不是零() {
    var reportId = newReport("R-ZERO", List.of("RETURN_RATE", "SALES_AMOUNT"), List.of("SHOP"), List.of());
    var response = run(reportId);

    var zero = rowOf(response, SHOP_ZERO.toString());
    // 🔴 销售额是真的 0 —— 这个 0 要如实显示。
    assertThat(valueOf(cell(zero, "SALES_AMOUNT"), "USD"))
        .as("销售额真的是 0（这与「算不出来」是两件事）").isEqualByComparingTo("0.00");
    // 🔴 退货率的分母是 0 ⇒ **不可比**（不是 0%）。
    var rate = cell(zero, "RETURN_RATE");
    assertThat(valueOf(rate, "USD")).as("分母为 0 ⇒ 比率为 NULL，不是 0").isNull();
    assertThat(rate.availability()).isEqualTo("NOT_COMPARABLE");
    assertThat(rate.availabilityNote()).contains("分母为 0");
  }

  @Test
  void 没有事实的度量是空态而不是零() {
    var reportId =
        newReport("R-NODATA", List.of("SALES_AMOUNT", "AD_SPEND"), List.of("SHOP"), List.of());
    var response = run(reportId);

    var shopA = rowOf(response, SHOP_A.toString());
    var ad = cell(shopA, "AD_SPEND");
    assertThat(ad.availability()).isEqualTo("NO_DATA");
    assertThat(ad.values()).as("没有事实 ⇒ 没有取值行").isEmpty();
    assertThat(ad.availabilityNote()).contains("不是 0");
  }

  @Test
  void 筛选值只走绑定参数不进SQL文本() {
    // 🔴 用户输入**只作为参数值**：它绝不出现在 SQL 文本里。
    var reportId =
        newReport(
            "R-INJECT",
            List.of("SALES_AMOUNT"),
            List.of("SHOP"),
            List.of(
                new ReportingCustomReportModels.CustomReportFilterRequest(
                    "CURRENCY", "EQ", "USD", null)));
    var sqlText = reports.explainQuerySql(tenant, reportId, DAY1, DAY2);

    assertThat(sqlText).as("SQL 文本里只能是占位符").contains("?::text");
    assertThat(sqlText).as("筛选值**绝不**拼进 SQL 文本").doesNotContain("'USD'");
    assertThat(sqlText).doesNotContain("drop table");
  }

  @Test
  void 注入串匹配不到任何行而且表还在() {
    // 注入串在**校验层**就被挡（币种必须是 3 位字母）⇒ 它连绑定参数都进不去。
    var error =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                newReport(
                    "R-INJECT2",
                    List.of("SALES_AMOUNT"),
                    List.of("SHOP"),
                    List.of(
                        new ReportingCustomReportModels.CustomReportFilterRequest(
                            "CURRENCY", "EQ", "'; drop table reporting_metric_fact; --", null))));
    assertThat(error.getStatusCode().value()).isEqualTo(400);
    assertThat(
            tenants.run(tenant, () -> sql.fetch("select id from reporting_metric_fact").size()))
        .as("事实表没被删掉").isPositive();
  }

  @Test
  void 筛选真的缩小结果集() {
    var reportId =
        newReport(
            "R-FILTER",
            List.of("SALES_AMOUNT"),
            List.of("SHOP"),
            List.of(
                new ReportingCustomReportModels.CustomReportFilterRequest(
                    "CURRENCY", "EQ", "EUR", null)));
    var response = run(reportId);

    assertThat(response.rows()).as("只有店铺 B 的 DAY2 是欧元").hasSize(1);
    assertThat(valueOf(cell(response.rows().get(0), "SALES_AMOUNT"), "EUR"))
        .isEqualByComparingTo("80.00");
  }

  @Test
  void 日期维度的筛选按日历日生效() {
    var reportId =
        newReport(
            "R-DATE",
            List.of("SALES_AMOUNT"),
            List.of("BUCKET_DATE"),
            List.of(
                new ReportingCustomReportModels.CustomReportFilterRequest(
                    "BUCKET_DATE", "GTE", DAY2.toString(), null)));
    var response = run(reportId);

    assertThat(response.rows()).as("只剩 DAY2 那一行").hasSize(1);
    assertThat(response.rows()).extracting(r -> r.dimensions().get(0).value())
        .containsExactly(DAY2.toString());
  }

  @Test
  void 非法日期筛选值被拒绝() {
    var error =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                newReport(
                    "R-BADDATE",
                    List.of("SALES_AMOUNT"),
                    List.of("SHOP"),
                    List.of(
                        new ReportingCustomReportModels.CustomReportFilterRequest(
                            "BUCKET_DATE", "EQ", "not-a-date", null))));
    assertThat(error.getStatusCode().value()).isEqualTo(400);
    assertThat(error.getReason()).contains("YYYY-MM-DD");
  }

  @Test
  void 分页是真分页() {
    var reportId = newReport("R-PAGE", List.of("SALES_AMOUNT"), List.of("SHOP"), List.of());
    var page = reports.run(tenant, reportId, user, DAY1, DAY2, 0, 2);
    assertThat(page.rows()).hasSize(2);
    assertThat(page.total()).as("总条数不受分页影响").isEqualTo(3);
    var second = reports.run(tenant, reportId, user, DAY1, DAY2, 1, 2);
    assertThat(second.rows()).hasSize(1);
    assertThat(second.total()).isEqualTo(3);
  }

  @Test
  void 饼图形状约束是真的() {
    var error =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                reports.save(
                    tenant,
                    user,
                    null,
                    new ReportingCustomReportModels.CustomReportSaveRequest(
                        "R-PIE",
                        "饼图",
                        "",
                        "PRIVATE",
                        "PIE",
                        null,
                        null,
                        List.of("SALES_AMOUNT"),
                        List.of("SHOP", "CURRENCY"),
                        List.of())));
    assertThat(error.getStatusCode().value()).isEqualTo(400);
    assertThat(error.getReason()).contains("饼图");
  }

  @Test
  void 折线图至少要一个维度() {
    var error =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                reports.save(
                    tenant,
                    user,
                    null,
                    new ReportingCustomReportModels.CustomReportSaveRequest(
                        "R-LINE",
                        "折线图",
                        "",
                        "PRIVATE",
                        "LINE",
                        null,
                        null,
                        List.of("SALES_AMOUNT"),
                        List.of(),
                        List.of())));
    assertThat(error.getStatusCode().value()).isEqualTo(400);
    assertThat(error.getReason()).contains("维度");
  }

  @Test
  void 报表配置能读回也能改() {
    var reportId =
        newReport(
            "R-EDIT",
            List.of("SALES_AMOUNT"),
            List.of("SHOP"),
            List.of(
                new ReportingCustomReportModels.CustomReportFilterRequest(
                    "CURRENCY", "EQ", "USD", null)));
    var view = reports.get(tenant, user, reportId);
    assertThat(view.measures()).extracting(m -> m.metricCode()).containsExactly("SALES_AMOUNT");
    assertThat(view.dimensions()).containsExactly("SHOP");
    assertThat(view.filters()).hasSize(1);
    assertThat(view.filters().get(0).valueText()).isEqualTo("USD");

    var updated =
        reports.save(
            tenant,
            user,
            reportId,
            new ReportingCustomReportModels.CustomReportSaveRequest(
                "R-EDIT", "改名了", "", "TEAM", "BAR", DAY1, DAY2,
                List.of("SALES_AMOUNT", "ORDER_COUNT"), List.of("SHOP", "CURRENCY"), List.of()));
    assertThat(updated.displayName()).isEqualTo("改名了");
    assertThat(updated.chartType()).isEqualTo("BAR");
    assertThat(updated.measures()).hasSize(2);
    assertThat(updated.filters()).as("筛选整体替换").isEmpty();
  }

  @Test
  void 删报表连同子表() {
    var reportId = newReport("R-DEL", List.of("SALES_AMOUNT"), List.of("SHOP"), List.of());
    reports.createShare(tenant, reportId, user, "TEAM");
    reports.saveSubscription(
        tenant,
        reportId,
        user,
        new ReportingCustomReportModels.CustomReportSubscriptionRequest("0 9 * * *", "EMAIL", "a@b.c", true));

    reports.delete(tenant, user, reportId);

    assertThat(
            tenants.run(
                tenant,
                () ->
                    sql.fetch(
                            "select id from reporting_custom_report_measure where report_id = ?", reportId)
                        .size()
                        + sql.fetch(
                                "select id from reporting_custom_report_dimension where report_id = ?",
                                reportId)
                            .size()
                        + sql.fetch(
                                "select id from reporting_custom_report_share where report_id = ?", reportId)
                            .size()
                        + sql.fetch(
                                "select id from reporting_custom_report_subscription where report_id = ?",
                                reportId)
                            .size()))
        .as("子表都要跟着删干净").isZero();
  }

  @Test
  void 数据更新时间常驻() {
    var reportId = newReport("R-TIME", List.of("SALES_AMOUNT"), List.of("SHOP"), List.of());
    var response = run(reportId);
    assertThat(response.dataUpdatedAt()).as("卷 13 卷首「数据延迟要显示」").isNotNull();
    assertThat(response.dataUpdatedNote()).isNotBlank();
  }

  // ==========================================================================
  // 分享
  // ==========================================================================

  @Test
  void 分享链接能读出报表与结果() {
    var reportId = newReport("R-SHARE", List.of("SALES_AMOUNT"), List.of("SHOP"), List.of());
    var share = reports.createShare(tenant, reportId, user, "TEAM");
    assertThat(share.shareToken()).hasSize(32);
    assertThat(share.sharePath()).contains(share.shareToken());

    var shared = reports.shared(tenant, user, share.shareToken(), 0, 50);
    assertThat(shared.report().reportCode()).isEqualTo("R-SHARE");
    assertThat(shared.result().rows()).isNotEmpty();
  }

  @Test
  void 不存在的分享令牌与未授权的返回逐字相同的404() {
    var reportId = newReport("R-SHARE-PRIVATE", List.of("SALES_AMOUNT"), List.of("SHOP"), List.of());
    var share = reports.createShare(tenant, reportId, user, "PRIVATE");

    // 另一个人拿着 PRIVATE 的令牌 ⇒ 与「令牌不存在」**逐字相同**的 404。
    var other = UUID.randomUUID();
    var missing =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> reports.shared(tenant, other, "0123456789abcdef0123456789abcdef", 0, 50));
    var unauthorized =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> reports.shared(tenant, other, share.shareToken(), 0, 50));
    assertThat(unauthorized.getStatusCode().value()).isEqualTo(404);
    assertThat(unauthorized.getReason())
        .as("与「令牌不存在」**逐字相同**（不泄露存在性）")
        .isEqualTo(missing.getReason());
  }

  @Test
  void 不存在的分享令牌报404() {
    var error =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> reports.shared(tenant, user, "0123456789abcdef0123456789abcdef", 0, 50));
    assertThat(error.getStatusCode().value()).isEqualTo(404);
  }

  // ==========================================================================
  // 订阅与投递
  // ==========================================================================

  @Test
  void 订阅计划保存后有下一次执行时刻() {
    var reportId = newReport("R-SUB", List.of("SALES_AMOUNT"), List.of("SHOP"), List.of());
    var subscription =
        reports.saveSubscription(
            tenant,
            reportId,
            user,
            new ReportingCustomReportModels.CustomReportSubscriptionRequest(
                "0 9 * * *", "EMAIL", "ops@example.com", true));
    assertThat(subscription.nextRunAt()).as("cron 要算出下一次执行时刻").isNotNull();
    assertThat(subscription.channel()).isEqualTo("EMAIL");
    assertThat(subscription.target()).isEqualTo("ops@example.com");

    var list = reports.listSubscriptions(tenant, user, reportId);
    assertThat(list).hasSize(1);
  }

  @Test
  void 不支持的cron写法被拒绝() {
    var reportId = newReport("R-SUB-BAD", List.of("SALES_AMOUNT"), List.of("SHOP"), List.of());
    var error =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                reports.saveSubscription(
                    tenant,
                    reportId,
                    user,
                    new ReportingCustomReportModels.CustomReportSubscriptionRequest(
                        "0 0 * *", "EMAIL", "ops@example.com", true)));
    assertThat(error.getStatusCode().value()).isEqualTo(400);
  }

  @Test
  void 到期的订阅落成待投递记录() {
    var reportId = newReport("R-TICK", List.of("SALES_AMOUNT"), List.of("SHOP"), List.of());
    reports.saveSubscription(
        tenant,
        reportId,
        user,
        new ReportingCustomReportModels.CustomReportSubscriptionRequest(
            "*/5 * * * *", "EMAIL", "ops@example.com", true));
    // 把下一次执行时刻定在一个**具体的历史时刻**（模拟「到点了」）。
    var past = java.time.OffsetDateTime.of(2026, 10, 1, 9, 0, 0, 0, java.time.ZoneOffset.UTC);
    setNextRun(past);

    var result = reports.tick(tenant);
    assertThat(result.enqueued()).as("本轮要落一条待投递记录").isEqualTo(1);
    assertThat(result.advanced()).isEqualTo(1);
    assertThat(result.note()).contains("未交付");

    var deliveries = reports.listDeliveries(tenant, "PENDING", 0, 20);
    assertThat(deliveries.total()).isEqualTo(1);
    assertThat(deliveries.rows().get(0).status()).as("是「待投递」，不是「已发送」").isEqualTo("PENDING");
    assertThat(deliveries.rows().get(0).attemptCount()).isZero();

    // 幂等：**同一个计划时刻**只有一条待投递记录 ⇒ 外部调度重复触发不会排两次。
    setNextRun(past);
    reports.tick(tenant);
    assertThat(reports.listDeliveries(tenant, null, 0, 20).total()).isEqualTo(1);
  }

  private void setNextRun(java.time.OffsetDateTime at) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "update reporting_custom_report_subscription set next_run_at = ?::timestamptz", at);
          return null;
        });
  }

  @Test
  void 订阅可以停用再启用() {
    var reportId = newReport("R-SUB-TOGGLE", List.of("SALES_AMOUNT"), List.of("SHOP"), List.of());
    var subscription =
        reports.saveSubscription(
            tenant,
            reportId,
            user,
            new ReportingCustomReportModels.CustomReportSubscriptionRequest(
                "0 9 * * *", "IM", "ops-group", true));
    var off =
        reports.toggleSubscription(tenant, user, UUID.fromString(subscription.subscriptionId()), false);
    assertThat(off.enabled()).isFalse();
    var on =
        reports.toggleSubscription(tenant, user, UUID.fromString(subscription.subscriptionId()), true);
    assertThat(on.enabled()).isTrue();
    assertThat(on.nextRunAt()).as("启用后要重新算出下一次执行时刻").isNotNull();
  }

  // ==========================================================================
  // HTTP 边界
  // ==========================================================================

  @Test
  void 能力声明端点可用() throws Exception {
    http.perform(get("/reporting/custom-report/capabilities").header("Authorization", BEARER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.exportAvailable").value(false))
        .andExpect(jsonPath("$.deliveryAvailable").value(false));
  }

  @Test
  void 指标清单端点可用() throws Exception {
    http.perform(get("/reporting/custom-report/metrics").header("Authorization", BEARER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThan(0)));
  }

  @Test
  void 跑报表端点可用() throws Exception {
    var reportId = newReport("R-HTTP", List.of("SALES_AMOUNT"), List.of("SHOP"), List.of());
    http.perform(
            post("/reporting/custom-report/reports/" + reportId + "/run")
                .header("Authorization", BEARER)
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        new ReportingCustomReportModels.CustomReportRunRequest(
                            DAY1, DAY2, 0, 50))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("AVAILABLE"))
        .andExpect(jsonPath("$.total").value(3));
  }

  @Test
  void 分享链接的404在HTTP层也是逐字相同() throws Exception {
    var reportId = newReport("R-HTTP-404", List.of("SALES_AMOUNT"), List.of("SHOP"), List.of());
    var share = reports.createShare(tenant, reportId, user, "PRIVATE");

    var missing =
        http.perform(
                get("/reporting/custom-report/shared/0123456789abcdef0123456789abcdef")
                    .header("Authorization", BEARER_COLLEAGUE))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var unauthorized =
        http.perform(
                get("/reporting/custom-report/shared/" + share.shareToken())
                    .header("Authorization", BEARER_COLLEAGUE))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString();
    // 令牌不存在与令牌存在但没权限 ⇒ **逐字相同**（不泄露存在性）。
    assertThat(unauthorized).isEqualTo(missing);
  }
}
