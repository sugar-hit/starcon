package com.starcon.reporting;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * 13.1.2.1 目标设定的**集成测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>本文件验的是**业务口径**（多租户三条断言在 {@code TargetSettingTenancyIntegrationTest}）：
 *
 * <ul>
 *   <li>🔴 **分解项合计精确等于目标值** —— 均分时差额归最后一项；按权重时用最大余额法补齐。
 *       「均分后合计 ≠ 目标值」是浮点加法最常见的坑，这里用 {@code BigDecimal} 钉死；</li>
 *   <li>🔴 **口径唯一**：目标只能引用**已登记**的口径码；未登记的码在数据层就写不进来；</li>
 *   <li>🔴 **销售额在店铺维度是三个并列口径**（平台侧 / 还原口径 / 口径差异额）——
 *       取其一改名叫「销售额」就是抹掉口径差异；</li>
 *   <li>🔴 **广告 ROI 与库存周转**在没有登记口径码的维度上**如实报「未登记」**，
 *       不拿别的指标冒充；</li>
 *   <li>🔴 **「没有基线」与「基线为 0」分开说**：基线取不到时是 {@code null}；</li>
 *   <li>🔴 **多币种绝不相加**：合计按币种分列。</li>
 * </ul>
 *
 * <p>⚠️ **上游是真的 HTTP**：本测试起一个假的 identity（部门 / 人员）与一个假的 catalog（产品），
 * 驱动 {@code ReportingTargetOrgPort} 的**真实**拉取路径 —— 路径或字段名写错了在这一层就会暴露。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TargetSettingIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
  static final UUID departmentId = UUID.randomUUID();
  static final UUID memberId = UUID.randomUUID();
  static final UUID productId = UUID.randomUUID();

  static HttpServer identity;
  static HttpServer catalog;

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of("reporting.targets.read", "reporting.targets.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  static final String BEARER = "Bearer tenant-a";

  /** 目标周期：2026 年 1 ~ 3 月（按月分解 ⇒ 起是 1 日、止是月末日）。 */
  static final LocalDate PERIOD_START = LocalDate.of(2026, 1, 1);

  static final LocalDate PERIOD_END = LocalDate.of(2026, 3, 31);

  /** 去年同期（基线窗口）。 */
  static final LocalDate BASELINE_START = LocalDate.of(2025, 1, 1);

  static final LocalDate BASELINE_END = LocalDate.of(2025, 3, 31);

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
      identity.createContext("/identity/departments", e -> respond(e, departments()));
      identity.createContext("/identity/members", e -> respond(e, members()));
      identity.start();

      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext("/catalog/products", e -> respond(e, products()));
      catalog.start();
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
    r.add("CATALOG_URL", () -> "http://localhost:" + catalog.getAddress().getPort());
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ReportingTargetService targets;

  @AfterAll
  static void stop() {
    identity.stop(0);
    catalog.stop(0);
    pg.stop();
  }

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate reporting_target, reporting_target_bucket, reporting_store_metric_fact, "
              + "reporting_store_metric_definition, reporting_store_profile, "
              + "reporting_metric_fact, reporting_metric_definition cascade");
    }
    actors.put(BEARER, actor(tenant));
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  /** 部门清单（identity 的树，摊平后只有一项）。 */
  static List<Map<String, Object>> departments() {
    var node = new LinkedHashMap<String, Object>();
    node.put("id", departmentId);
    node.put("code", "SALES");
    node.put("name", "销售部");
    node.put("parentId", null);
    node.put("leaderMembershipId", memberId);
    node.put("leaderName", "张三");
    node.put("memberCount", 3);
    node.put("children", List.of());
    return List.of(node);
  }

  /** 人员清单。 */
  static List<Map<String, Object>> members() {
    var member = new LinkedHashMap<String, Object>();
    member.put("id", memberId);
    member.put("userId", UUID.randomUUID());
    member.put("username", "张三");
    member.put("status", "ACTIVE");
    member.put("departmentId", departmentId);
    member.put("assignments", List.of());
    member.put("owner", false);
    return List.of(member);
  }

  /** 产品清单（catalog 的 SPU）。 */
  static List<Map<String, Object>> products() {
    var product = new LinkedHashMap<String, Object>();
    product.put("id", productId);
    product.put("code", "SPU-001");
    product.put("name", "蓝色T恤");
    product.put("brand", "Acme");
    product.put("category", "服饰");
    product.put("status", "ACTIVE");
    product.put("version", 1);
    product.put("skuCount", 3);
    return List.of(product);
  }

  private static void respond(com.sun.net.httpserver.HttpExchange e, Object body) {
    try {
      byte[] bytes = JSON.writeValueAsBytes(body);
      e.getResponseHeaders().add("Content-Type", "application/json");
      e.sendResponseHeaders(200, bytes.length);
      e.getResponseBody().write(bytes);
      e.close();
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  /** 店铺档案（13.1.1.2 的投影表；本模块的店铺归属对象清单读它）。 */
  private void seedStore(String shopCode, String shopName) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into reporting_store_profile (id, tenant_id, shop_code, shop_name, "
                  + "platform_code, platform_name, source_service, source_reference) "
                  + "values (?,?,?,?,?,?,?,?)",
              Ids.next(),
              tenant,
              shopCode,
              shopName,
              "AMAZON",
              "亚马逊",
              "channel",
              shopCode);
          return null;
        });
  }

  /**
   * 播种该租户的口径表。
   *
   * <p>⚠️ **事实行的指标码必须已登记**（DB 触发器钉死：口径唯一 ⇒ 事实不能自己发明指标码），
   * 而口径表是**懒播种**的 ⇒ 落事实之前先把口径表播种出来。
   */
  private void seedCalibers() {
    targets.metrics(tenant, "DEPARTMENT");
  }

  /** 租户级事实（公司维度）—— 基线参考值的事实来源。 */
  private void seedTenantFact(
      String metricCode, LocalDate bucketDate, String currency, BigDecimal value, String timezone) {
    seedCalibers();
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into reporting_metric_fact (id, tenant_id, scope_type, legal_entity_id, "
                  + "shop_id, metric_code, bucket_date, currency, value, timezone, "
                  + "source_service, source_reference) values (?,?,'COMPANY',null,null,?,?,?,?,?,?,?)",
              Ids.next(),
              tenant,
              metricCode,
              bucketDate,
              currency,
              value,
              timezone,
              "trade",
              metricCode + ":" + bucketDate);
          return null;
        });
  }

  /** 店铺维度事实 —— 店铺目标的基线事实来源。 */
  private void seedStoreFact(
      String shopCode,
      String metricCode,
      LocalDate bucketDate,
      String currency,
      BigDecimal value) {
    seedCalibers();
    targets.metrics(tenant, "SHOP");
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into reporting_store_metric_fact (id, tenant_id, shop_code, metric_code, "
                  + "bucket_date, currency, value, quantity, timezone, source_service, "
                  + "source_reference) values (?,?,?,?,?,?,?,0,'UTC',?,?)",
              Ids.next(),
              tenant,
              shopCode,
              metricCode,
              bucketDate,
              currency,
              value,
              "trade",
              metricCode + ":" + shopCode + ":" + bucketDate);
          return null;
        });
  }

  /** 一个部门维度的目标入参。 */
  private ReportingTargetModels.TargetInput deptInput(
      String metricCode, BigDecimal value, String currency) {
    return new ReportingTargetModels.TargetInput(
        metricCode,
        "DEPARTMENT",
        departmentId.toString(),
        currency,
        value,
        PERIOD_START,
        PERIOD_END,
        "MONTHLY",
        "EVEN",
        null,
        null,
        "销售部 2026 Q1 销售额目标");
  }

  // ==========================================================================
  // 分解：合计精确等于目标值
  // ==========================================================================

  @Test
  void 分解_均分_合计精确等于目标值_差额归最后一项() {
    var preview =
        targets.decompositionPreview(
            PERIOD_START, PERIOD_END, "MONTHLY", "EVEN", "USD", new BigDecimal("1000"), null);

    assertThat(preview.buckets()).hasSize(3);
    // 1000 / 3 = 333.3333… ⇒ 前两项 333.3333，差额 0.0001 归最后一项（金额刻度 4 位）。
    assertThat(preview.buckets().get(0).targetValue()).isEqualByComparingTo("333.3333");
    assertThat(preview.buckets().get(1).targetValue()).isEqualByComparingTo("333.3333");
    assertThat(preview.buckets().get(2).targetValue()).isEqualByComparingTo("333.3334");
    assertThat(preview.bucketSum()).isEqualByComparingTo("1000.0000");
    assertThat(preview.reconciled()).isTrue();
    assertThat(preview.remainderNote()).contains("差额");
  }

  @Test
  void 分解_均分_余数不会让合计差一分钱() {
    // 100.01 / 3 —— 浮点加法最常见的坑就在这里。
    var preview =
        targets.decompositionPreview(
            PERIOD_START, PERIOD_END, "MONTHLY", "EVEN", "USD", new BigDecimal("100.01"), null);

    assertThat(preview.bucketSum()).isEqualByComparingTo("100.0100");
    assertThat(preview.reconciled()).isTrue();
  }

  @Test
  void 分解_按权重_合计精确等于目标值() {
    var preview =
        targets.decompositionPreview(
            PERIOD_START,
            PERIOD_END,
            "MONTHLY",
            "WEIGHTED",
            "USD",
            new BigDecimal("100"),
            List.of(new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("1")));

    assertThat(preview.bucketSum()).isEqualByComparingTo("100.0000");
    assertThat(preview.reconciled()).isTrue();
    // 1:1:1 ⇒ 三项各 33.3333… ⇒ 取整后 33.3333 / 33.3333 / 33.3334。
    assertThat(preview.buckets().get(0).targetValue()).isEqualByComparingTo("33.3333");
    assertThat(preview.buckets().get(2).targetValue()).isEqualByComparingTo("33.3334");
  }

  @Test
  void 分解_按权重_权重不等也能精确合计() {
    var preview =
        targets.decompositionPreview(
            PERIOD_START,
            PERIOD_END,
            "MONTHLY",
            "WEIGHTED",
            "USD",
            new BigDecimal("1000"),
            List.of(new BigDecimal("50"), new BigDecimal("30"), new BigDecimal("20")));

    assertThat(preview.bucketSum()).isEqualByComparingTo("1000.0000");
    assertThat(preview.buckets().get(0).targetValue()).isEqualByComparingTo("500.00");
    assertThat(preview.buckets().get(1).targetValue()).isEqualByComparingTo("300.00");
    assertThat(preview.buckets().get(2).targetValue()).isEqualByComparingTo("200.00");
  }

  @Test
  void 分解_按周_分解项是ISO周() {
    var preview =
        targets.decompositionPreview(
            LocalDate.of(2026, 1, 5),
            LocalDate.of(2026, 1, 25),
            "WEEKLY",
            "EVEN",
            "USD",
            new BigDecimal("300"),
            null);

    assertThat(preview.buckets()).hasSize(3);
    assertThat(preview.buckets().get(0).bucketStart()).isEqualTo(LocalDate.of(2026, 1, 5));
    assertThat(preview.buckets().get(0).bucketEnd()).isEqualTo(LocalDate.of(2026, 1, 11));
    assertThat(preview.bucketSum()).isEqualByComparingTo("300.0000");
  }

  @Test
  void 分解_周期不对齐_400() {
    // 起点不是某月 1 日 ⇒ 切不干净 ⇒ 明确拒绝，不悄悄截断。
    var thrown =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                targets.decompositionPreview(
                    LocalDate.of(2026, 1, 15),
                    PERIOD_END,
                    "MONTHLY",
                    "EVEN",
                    "USD",
                    new BigDecimal("100"),
                    null));
    assertThat(thrown.getStatusCode().value()).isEqualTo(400);
    assertThat(thrown.getMessage()).contains("1 日");
  }

  @Test
  void 分解_权重个数与分解项个数不匹配_400() {
    var thrown =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                targets.decompositionPreview(
                    PERIOD_START,
                    PERIOD_END,
                    "MONTHLY",
                    "WEIGHTED",
                    "USD",
                    new BigDecimal("100"),
                    List.of(new BigDecimal("1"), new BigDecimal("2"))));
    assertThat(thrown.getStatusCode().value()).isEqualTo(400);
    assertThat(thrown.getMessage()).contains("权重个数");
  }

  // ==========================================================================
  // 目标指标清单：口径唯一 + 三个并列口径 + 未登记如实报
  // ==========================================================================

  @Test
  void 指标清单_销售额在店铺维度是三个并列口径() {
    var metrics = targets.metrics(tenant, "SHOP");
    var sales =
        metrics.stream()
            .filter(m -> m.key().equals(ReportingTargetMetricCatalog.SALES))
            .findFirst()
            .orElseThrow();

    assertThat(sales.available()).isTrue();
    assertThat(sales.calipers())
        .extracting(ReportingTargetModels.TargetCaliberView::metricCode)
        .containsExactly(
            ReportingStoreComparisonCatalog.STORE_GMV_PLATFORM,
            ReportingStoreComparisonCatalog.STORE_GMV_RESTORED,
            ReportingStoreComparisonCatalog.STORE_CALIBER_GAP);
  }

  @Test
  void 指标清单_销售额在组织维度只有租户口径() {
    var metrics = targets.metrics(tenant, "DEPARTMENT");
    var sales =
        metrics.stream()
            .filter(m -> m.key().equals(ReportingTargetMetricCatalog.SALES))
            .findFirst()
            .orElseThrow();

    assertThat(sales.calipers())
        .extracting(ReportingTargetModels.TargetCaliberView::metricCode)
        .containsExactly(ReportingMetricCatalog.SALES_AMOUNT);
  }

  @Test
  void 指标清单_广告ROI在店铺维度用已登记的ROAS() {
    var metrics = targets.metrics(tenant, "SHOP");
    var adRoi =
        metrics.stream()
            .filter(m -> m.key().equals(ReportingTargetMetricCatalog.AD_ROI))
            .findFirst()
            .orElseThrow();

    assertThat(adRoi.available()).isTrue();
    assertThat(adRoi.calipers())
        .extracting(ReportingTargetModels.TargetCaliberView::metricCode)
        .containsExactly(ReportingStoreComparisonCatalog.STORE_AD_ROAS);
  }

  @Test
  void 指标清单_广告ROI在组织维度如实报未登记() {
    var metrics = targets.metrics(tenant, "DEPARTMENT");
    var adRoi =
        metrics.stream()
            .filter(m -> m.key().equals(ReportingTargetMetricCatalog.AD_ROI))
            .findFirst()
            .orElseThrow();

    assertThat(adRoi.available()).isFalse();
    assertThat(adRoi.calipers()).isEmpty();
    assertThat(adRoi.unavailableReason()).isNotBlank();
  }

  @Test
  void 指标清单_库存周转任何维度都未登记() {
    for (var scope : List.of("DEPARTMENT", "MEMBER", "SHOP", "PRODUCT")) {
      var turnover =
          targets.metrics(tenant, scope).stream()
              .filter(m -> m.key().equals(ReportingTargetMetricCatalog.INVENTORY_TURNOVER))
              .findFirst()
              .orElseThrow();
      assertThat(turnover.available()).as("库存周转在 %s 维度必须是未登记", scope).isFalse();
      assertThat(turnover.calipers()).isEmpty();
      assertThat(turnover.ownerModule()).contains("库存");
    }
  }

  @Test
  void 指标清单_毛利率引用已登记的派生口径() {
    var margin =
        targets.metrics(tenant, "DEPARTMENT").stream()
            .filter(m -> m.key().equals(ReportingTargetMetricCatalog.GROSS_MARGIN))
            .findFirst()
            .orElseThrow();

    assertThat(margin.available()).isTrue();
    var caliber = margin.calipers().get(0);
    assertThat(caliber.metricCode()).isEqualTo(ReportingMetricCatalog.GROSS_MARGIN);
    // 🔴 派生型只写分子分母引用 —— 口径表里写的是什么，这里就显示什么。
    assertThat(caliber.aggregation()).isEqualTo("DERIVED_RATIO");
  }

  // ==========================================================================
  // 目标：写入 + 分解计划 + 基线
  // ==========================================================================

  @Test
  void 创建目标_分解计划落库且合计精确等于目标值() {
    var created = targets.create(tenant, deptInput("SALES_AMOUNT", new BigDecimal("1000"), "USD"), BEARER);

    assertThat(created.targetValue()).isEqualByComparingTo("1000.0000");
    assertThat(created.buckets()).hasSize(3);
    assertThat(created.bucketSum()).isEqualByComparingTo("1000.0000");
    assertThat(created.reconciled()).isTrue();
    assertThat(created.ownerName()).isEqualTo("销售部");
    assertThat(created.ownerCode()).isEqualTo("SALES");
    assertThat(created.scopeType()).isEqualTo("DEPARTMENT");
  }

  @Test
  void 创建目标_归属对象快照来自权威来源() {
    var created = targets.create(tenant, deptInput("SALES_AMOUNT", new BigDecimal("100"), "USD"), BEARER);

    // 快照是「当时的写法」：owner_name 来自 identity，不是客户端自称。
    assertThat(created.ownerName()).isEqualTo("销售部");
    assertThat(created.ownerCode()).isEqualTo("SALES");
  }

  @Test
  void 创建目标_归属对象不在清单里_400() {
    var input =
        new ReportingTargetModels.TargetInput(
            "SALES_AMOUNT",
            "DEPARTMENT",
            UUID.randomUUID().toString(),
            "USD",
            new BigDecimal("100"),
            PERIOD_START,
            PERIOD_END,
            "MONTHLY",
            "EVEN",
            null,
            null,
            "");
    var thrown =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> targets.create(tenant, input, BEARER));
    assertThat(thrown.getStatusCode().value()).isEqualTo(400);
    assertThat(thrown.getMessage()).contains("归属对象不存在");
  }

  @Test
  void 基线_没有事实_是null不是0() {
    var created = targets.create(tenant, deptInput("SALES_AMOUNT", new BigDecimal("100"), "USD"), BEARER);

    assertThat(created.baseline().status()).isEqualTo("NO_DATA");
    assertThat(created.baseline().value()).isNull();
    assertThat(created.baseline().note()).contains("不是基线为 0");
  }

  @Test
  void 基线_组织维度取租户级参考值并写明不是该归属对象自己的历史() {
    seedTenantFact("SALES_AMOUNT", BASELINE_START, "USD", new BigDecimal("800"), "Asia/Shanghai");

    var created = targets.create(tenant, deptInput("SALES_AMOUNT", new BigDecimal("1000"), "USD"), BEARER);

    assertThat(created.baseline().status()).isEqualTo("AVAILABLE");
    assertThat(created.baseline().value()).isEqualByComparingTo("800");
    assertThat(created.baseline().scope()).isEqualTo("TENANT");
    assertThat(created.baseline().note()).contains("租户级参考值");
  }

  @Test
  void 基线_店铺维度取该店铺自己的历史() {
    seedStore("shop-a", "亚马逊旗舰店");
    seedStoreFact(
        "shop-a",
        ReportingStoreComparisonCatalog.STORE_GMV_PLATFORM,
        BASELINE_START,
        "USD",
        new BigDecimal("1234"));

    var input =
        new ReportingTargetModels.TargetInput(
            ReportingStoreComparisonCatalog.STORE_GMV_PLATFORM,
            "SHOP",
            "shop-a",
            "USD",
            new BigDecimal("2000"),
            PERIOD_START,
            PERIOD_END,
            "MONTHLY",
            "EVEN",
            null,
            null,
            "");
    var created = targets.create(tenant, input, BEARER);

    assertThat(created.baseline().status()).isEqualTo("AVAILABLE");
    assertThat(created.baseline().value()).isEqualByComparingTo("1234");
    assertThat(created.baseline().scope()).isEqualTo("OWNER");
  }

  @Test
  void 刷新基线_事实进来之后基线会变() {
    var created = targets.create(tenant, deptInput("SALES_AMOUNT", new BigDecimal("100"), "USD"), BEARER);
    assertThat(created.baseline().status()).isEqualTo("NO_DATA");

    seedTenantFact("SALES_AMOUNT", BASELINE_START, "USD", new BigDecimal("80"), "UTC");
    var refreshed = targets.refreshBaseline(tenant, created.id());

    assertThat(refreshed.baseline().status()).isEqualTo("AVAILABLE");
    assertThat(refreshed.baseline().value()).isEqualByComparingTo("80");
  }

  @Test
  void 更新目标_分解计划随之重写() {
    var created = targets.create(tenant, deptInput("SALES_AMOUNT", new BigDecimal("1000"), "USD"), BEARER);

    var updated =
        targets.update(
            tenant,
            created.id(),
            new ReportingTargetModels.TargetInput(
                "SALES_AMOUNT",
                "DEPARTMENT",
                departmentId.toString(),
                "USD",
                new BigDecimal("2400"),
                PERIOD_START,
                PERIOD_END,
                "MONTHLY",
                "EVEN",
                null,
                null,
                "改过的目标"),
            BEARER);

    assertThat(updated.targetValue()).isEqualByComparingTo("2400.0000");
    assertThat(updated.bucketSum()).isEqualByComparingTo("2400.0000");
    assertThat(updated.reconciled()).isTrue();
    assertThat(updated.buckets()).hasSize(3);
  }

  @Test
  void 重复目标_同一归属对象指标币种周期_409() {
    targets.create(tenant, deptInput("SALES_AMOUNT", new BigDecimal("100"), "USD"), BEARER);
    var thrown =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> targets.create(tenant, deptInput("SALES_AMOUNT", new BigDecimal("200"), "USD"), BEARER));
    assertThat(thrown.getStatusCode().value()).isEqualTo(409);
  }

  @Test
  void 不同币种的目标各存各的_不相加() {
    targets.create(tenant, deptInput("SALES_AMOUNT", new BigDecimal("100"), "USD"), BEARER);
    targets.create(tenant, deptInput("SALES_AMOUNT", new BigDecimal("200"), "EUR"), BEARER);

    var list = targets.list(tenant, null, null, null, null, 0, 50);

    assertThat(list.total()).isEqualTo(2);
    assertThat(list.currencyTotals()).hasSize(2);
    assertThat(list.currencyTotals())
        .extracting(ReportingTargetModels.TargetCurrencyTotal::currency)
        .containsExactly("EUR", "USD");
    // 绝不跨币种相加：没有一笔合计是 300。
    assertThat(list.currencyTotals())
        .extracting(ReportingTargetModels.TargetCurrencyTotal::total)
        .containsExactly(new BigDecimal("200.0000"), new BigDecimal("100.0000"));
  }

  @Test
  void 归档目标_保留行只改状态() {
    var created = targets.create(tenant, deptInput("SALES_AMOUNT", new BigDecimal("100"), "USD"), BEARER);

    var archived =
        targets.update(
            tenant,
            created.id(),
            new ReportingTargetModels.TargetInput(
                "SALES_AMOUNT",
                "DEPARTMENT",
                departmentId.toString(),
                "USD",
                new BigDecimal("100"),
                PERIOD_START,
                PERIOD_END,
                "MONTHLY",
                "EVEN",
                null,
                "ARCHIVED",
                ""),
            BEARER);

    assertThat(archived.status()).isEqualTo("ARCHIVED");
    assertThat(targets.list(tenant, null, null, "ARCHIVED", null, 0, 50).total()).isEqualTo(1);
    assertThat(targets.list(tenant, null, null, "ACTIVE", null, 0, 50).total()).isZero();
  }

  @Test
  void 删除目标_分解计划级联删除() {
    var created = targets.create(tenant, deptInput("SALES_AMOUNT", new BigDecimal("100"), "USD"), BEARER);

    targets.delete(tenant, created.id());

    var remaining =
        tenants.run(
            tenant, () -> sql.fetch("select id from reporting_target_bucket").size());
    assertThat(remaining).isZero();
    assertThat(targets.list(tenant, null, null, null, null, 0, 50).total()).isZero();
  }

  // ==========================================================================
  // 数据层不变量
  // ==========================================================================

  @Test
  void 数据层_未登记的指标码写不进来() {
    var thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                tenants.run(
                    tenant,
                    () -> {
                      sql.execute(
                          "insert into reporting_target (id, tenant_id, metric_code, scope_type, "
                              + "owner_id, currency, target_value, baseline_status, period_start, "
                              + "period_end, granularity, decomposition_method) "
                              + "values (?,?,'NOT_REGISTERED_AT_ALL','DEPARTMENT',?,'USD',100, "
                              + "'NO_DATA',?,?,'MONTHLY','EVEN')",
                          Ids.next(),
                          tenant,
                          departmentId.toString(),
                          PERIOD_START,
                          PERIOD_END);
                      return null;
                    }));
    assertThat(rootMessage(thrown)).contains("尚未在口径表");
  }

  @Test
  void 数据层_店铺口径码不能挂到部门目标上() {
    // STORE_GMV_PLATFORM 只登记在**店铺口径表**里 ⇒ 部门目标引用它写不进来。
    // 这是「店铺维度的销售额有三个并列口径、而它们不能被搬到部门维度」的落地形态。
    var thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                tenants.run(
                    tenant,
                    () -> {
                      sql.execute(
                          "insert into reporting_target (id, tenant_id, metric_code, scope_type, "
                              + "owner_id, currency, target_value, baseline_status, period_start, "
                              + "period_end, granularity, decomposition_method) "
                              + "values (?,?,'STORE_GMV_PLATFORM','DEPARTMENT',?,'USD',100, "
                              + "'NO_DATA',?,?,'MONTHLY','EVEN')",
                          Ids.next(),
                          tenant,
                          departmentId.toString(),
                          PERIOD_START,
                          PERIOD_END);
                      return null;
                    }));
    assertThat(rootMessage(thrown)).contains("尚未在口径表");
  }

  @Test
  void 数据层_租户口径码不能挂到店铺目标上() {
    // SALES_AMOUNT 只登记在**租户口径表**里 ⇒ 店铺目标引用它写不进来。
    seedCalibers();
    var thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                tenants.run(
                    tenant,
                    () -> {
                      sql.execute(
                          "insert into reporting_target (id, tenant_id, metric_code, scope_type, "
                              + "owner_id, currency, target_value, baseline_status, period_start, "
                              + "period_end, granularity, decomposition_method) "
                              + "values (?,?,'SALES_AMOUNT','SHOP','shop-a','USD',100, "
                              + "'NO_DATA',?,?,'MONTHLY','EVEN')",
                          Ids.next(),
                          tenant,
                          PERIOD_START,
                          PERIOD_END);
                      return null;
                    }));
    assertThat(rootMessage(thrown)).contains("店铺对比口径表");
  }

  @Test
  void 数据层_分解项合计不等于目标值_事务失败() {
    var created = targets.create(tenant, deptInput("SALES_AMOUNT", new BigDecimal("1000"), "USD"), BEARER);

    // 绕过服务层，直接写一个合计对不上的分解项 ⇒ 延迟约束触发器在提交时拦下。
    var thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                tenants.run(
                    tenant,
                    () -> {
                      sql.execute(
                          "insert into reporting_target_bucket (id, tenant_id, target_id, "
                              + "bucket_start, bucket_end, granularity, currency, target_value, weight) "
                              + "values (?,?,?,?,?,'MONTHLY','USD',1.0000,1)",
                          Ids.next(),
                          tenant,
                          created.id(),
                          LocalDate.of(2026, 4, 1),
                          LocalDate.of(2026, 4, 30));
                      return null;
                    }));
    assertThat(rootMessage(thrown)).contains("分解项合计");
  }

  @Test
  void 数据层_周期与粒度不对齐_写不进来() {
    var thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                tenants.run(
                    tenant,
                    () -> {
                      sql.execute(
                          "insert into reporting_target (id, tenant_id, metric_code, scope_type, "
                              + "owner_id, currency, target_value, baseline_status, period_start, "
                              + "period_end, granularity, decomposition_method) "
                              + "values (?,?,'SALES_AMOUNT','DEPARTMENT',?,'USD',100,'NO_DATA',"
                              + "?,'2026-03-30','MONTHLY','EVEN')",
                          Ids.next(),
                          tenant,
                          departmentId.toString(),
                          PERIOD_START);
                      return null;
                    }));
    assertThat(rootMessage(thrown)).contains("reporting_target");
  }

  // ==========================================================================
  // 归属对象 / 周期 / 端点
  // ==========================================================================

  @Test
  void 归属对象清单_部门来自_identity() {
    var owners = targets.owners(tenant, "DEPARTMENT", BEARER);

    assertThat(owners.owners()).hasSize(1);
    assertThat(owners.owners().get(0).ownerName()).isEqualTo("销售部");
    assertThat(owners.source()).contains("identity");
  }

  @Test
  void 归属对象清单_成员来自_identity() {
    var owners = targets.owners(tenant, "MEMBER", BEARER);

    assertThat(owners.owners()).hasSize(1);
    assertThat(owners.owners().get(0).ownerName()).isEqualTo("张三");
    assertThat(owners.owners().get(0).detail()).contains("销售部");
  }

  @Test
  void 归属对象清单_产品来自_catalog() {
    var owners = targets.owners(tenant, "PRODUCT", BEARER);

    assertThat(owners.owners()).hasSize(1);
    assertThat(owners.owners().get(0).ownerName()).isEqualTo("蓝色T恤");
    assertThat(owners.source()).contains("catalog");
  }

  @Test
  void 归属对象清单_店铺来自本服务的店铺档案() {
    seedStore("shop-a", "亚马逊旗舰店");

    var owners = targets.owners(tenant, "SHOP", BEARER);

    assertThat(owners.owners()).hasSize(1);
    assertThat(owners.owners().get(0).ownerId()).isEqualTo("shop-a");
    assertThat(owners.source()).contains("reporting");
  }

  @Test
  void 当前周期_按本租户时区算_不写死UTC() {
    seedTenantFact("SALES_AMOUNT", BASELINE_START, "USD", new BigDecimal("1"), "Asia/Shanghai");

    var period = targets.currentPeriod(tenant, "MONTHLY");

    assertThat(period.timezone()).isEqualTo("Asia/Shanghai");
    assertThat(period.timezoneSource()).isEqualTo("PROJECTION");
    assertThat(period.from().getDayOfMonth()).isEqualTo(1);
    assertThat(period.to()).isEqualTo(period.from().withDayOfMonth(period.from().lengthOfMonth()));
  }

  @Test
  void 当前周期_没有投影事实时按UTC并说清楚() {
    var period = targets.currentPeriod(tenant, "MONTHLY");

    assertThat(period.timezone()).isEqualTo("UTC");
    assertThat(period.timezoneSource()).isEqualTo("FALLBACK_UTC");
    assertThat(period.note()).isNotBlank();
  }

  @Test
  void 端点_创建目标_返回分解计划() throws Exception {
    var body =
        JSON.writeValueAsString(
            new ReportingTargetModels.TargetInput(
                "SALES_AMOUNT",
                "DEPARTMENT",
                departmentId.toString(),
                "USD",
                new BigDecimal("1000"),
                PERIOD_START,
                PERIOD_END,
                "MONTHLY",
                "EVEN",
                null,
                null,
                ""));

    var response =
        http.perform(
                post("/reporting/targets")
                    .header("Authorization", BEARER)
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    var created = JSON.readValue(response, ReportingTargetModels.TargetView.class);
    assertThat(created.buckets()).hasSize(3);
    assertThat(created.bucketSum()).isEqualByComparingTo("1000.0000");
  }

  @Test
  void 端点_没有会话_401() throws Exception {
    http.perform(get("/reporting/targets")).andExpect(status().isUnauthorized());
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  /** 异常链里最内层的信息（PostgreSQL 的报错文本在 cause 链末端）。 */
  private static String rootMessage(Throwable error) {
    var current = error;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    var messages = new ArrayList<String>();
    current = error;
    while (current != null) {
      if (current.getMessage() != null) messages.add(current.getMessage());
      current = current.getCause();
    }
    return String.join(" | ", messages);
  }
}
