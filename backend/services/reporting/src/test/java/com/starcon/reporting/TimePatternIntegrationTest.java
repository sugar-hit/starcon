package com.starcon.reporting;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
 * 13.2.1.2 分时与分周模式的**集成测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>本文件验的是**业务口径**（多租户三条断言在 {@code TimePatternTenancyIntegrationTest}）：
 *
 * <ul>
 *   <li>🔴 **「按小时」是店铺本地小时**（不是 UTC 小时，也不是服务器时区小时）；
 *   <li>🔴 **「按星期」是店铺本地星期几**（ISO 1 = 周一 … 7 = 周日），不是周数；
 *   <li>🔴 **夏令时**：同一店铺在冬夏两季的同一 UTC 时刻，本地小时**相同**
 *       （用固定 UTC 偏移就会差一小时）；
 *   <li>🔴 **跨店铺汇总的口径**：LOCAL_TIME 与 UTC_SLOT **结果不同**，且响应里写明用的是哪个；
 *   <li>🔴 **时段配置是租户数据**：判定读表（改配置 ⇒ 判定跟着变），
 *       区间**不可重叠、不留空洞**（数据层拒绝）；
 *   <li>🔴 **高峰时段排序**：排序依据的指标码**登记在口径表里**；
 *   <li>🔴 **P90 阈值**：24 个小时桶全部参与（含没单的桶）；
 *   <li>🔴 **定不了时区的店铺**的订单不进任何本地桶，单列；
 *   <li>**多币种绝不相加**；
 *   <li>数据更新时间在**空表时为 null**（不是长得像时间的假值）；
 *   <li>重复刷新幂等；上游读不到就 503，不返回空集合。
 * </ul>
 *
 * <p>⚠️ **上游是真的 HTTP**：本测试起一个假的上游服务（trade + channel），驱动
 * {@code ReportingTimePatternRefreshService} 的**真实**拉取与投影路径 ——
 * 端口写错了路径 / 字段名，在这一层就会暴露。
 *
 * <p>⚠️ **连接池固定为 1 条连接**：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TimePatternIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
  static final String BEARER = "Bearer tenant-a";

  static HttpServer identity;
  static HttpServer upstream;

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of(
          "reporting.time-pattern.read",
          // ⚠️ 写权限必须在**同一个 grant** 里同时命中对应的 .read ——
          //   服务端的判据是「能写不能读」的组合不放行。
          "reporting.time-pattern.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  /** 当前窗口（2026-10-01 是**周四**）。 */
  static final LocalDate DAY1 = LocalDate.of(2026, 10, 1);

  static final LocalDate DAY7 = LocalDate.of(2026, 10, 7);

  /** 夏令时对照窗口（冬 / 夏各一）。 */
  static final LocalDate WINTER = LocalDate.of(2026, 1, 15);

  static final LocalDate SUMMER = LocalDate.of(2026, 7, 15);

  /** ⚠️ 上游故障开关：打开后假上游一律 500，用来证「读不到 ≠ 没有数据」。 */
  static volatile boolean upstreamDown = false;

  // ==========================================================================
  // 夹具数据
  // ==========================================================================

  /**
   * 平台与站点目录（channel）。
   *
   * <p>🔴 **站点才有时区**（`defaultTimezone`）—— 店铺自己没有时区列。
   */
  static final List<Map<String, Object>> PLATFORMS =
      List.of(
          platform(
              "AMAZON",
              marketplace("US", "US", "America/Los_Angeles"),
              marketplace("DE", "DE", "Europe/Berlin")),
          platform(
              "TIKTOK",
              marketplace("SG", "SG", "Asia/Shanghai")));

  /** 店铺清单（channel）。 */
  static final List<Map<String, Object>> SHOPS =
      List.of(
          shop("shop-a", "北美店", "AMAZON"),
          shop("shop-b", "新加坡店", "TIKTOK"),
          shop("shop-c", "没站点店", "AMAZON"),
          shop("shop-d", "多站点店", "AMAZON"));

  /** 店铺详情：每个店铺**启用了哪些站点**（channel 的 shop_marketplace）。 */
  static final Map<String, List<String>> SHOP_SITES =
      Map.of(
          "shop-a", List.of("US"),
          "shop-b", List.of("SG"),
          // ⚠️ shop-c 没启用任何站点 ⇒ 定不了时区
          "shop-c", List.of(),
          // ⚠️ shop-d 启用了两个**时区不同**的站点 ⇒ 定不了时区
          "shop-d", List.of("US", "DE"));

  /**
   * 销售订单。
   *
   * <p>🔴 布局刻意做成「**同一绝对时刻、不同店铺落在不同的本地小时与本地星期**」：
   * {@code SO-1} 与 {@code SO-2} 都是 {@code 2026-10-02T02:00:00Z}，
   * 但 shop-a 在洛杉矶（本地 10-01 周四 19:00）、shop-b 在新加坡（本地 10-02 周五 10:00）。
   */
  static final List<Map<String, Object>> ORDERS =
      List.of(
          order("SO-1", "shop-a", "2026-10-02T02:00:00Z", "100.00", "USD", 2),
          order("SO-2", "shop-b", "2026-10-02T02:00:00Z", "200.00", "USD", 3),
          order("SO-3", "shop-a", "2026-10-03T02:00:00Z", "300.00", "EUR", 1),
          order("SO-4", "shop-c", "2026-10-02T02:00:00Z", "400.00", "USD", 1),
          order("SO-5", "shop-d", "2026-10-02T02:00:00Z", "500.00", "USD", 1),
          order("SO-6", "shop-a", "2026-10-04T00:30:00Z", "600.00", "USD", 4),
          // ⚠️ **没有平台单** ⇒ 挂不上店铺 ⇒ 不计入任何店铺。
          order("SO-7", null, "2026-10-02T02:00:00Z", "700.00", "USD", 1),
          // 🔴 **夏令时对照**：同一店铺、同一个「本地 10:00」，冬夏各一单。
          //   冬：2026-01-15T18:00Z（PST, UTC-8）⇒ 本地 10:00
          //   夏：2026-07-15T17:00Z（PDT, UTC-7）⇒ 本地 10:00
          //   用固定 UTC 偏移的话，夏天那单会变成 09:00 ⇒ 断言能抓住。
          order("SO-DST-JAN", "shop-a", "2026-01-15T18:00:00Z", "100.00", "USD", 1),
          order("SO-DST-JUL", "shop-a", "2026-07-15T17:00:00Z", "100.00", "USD", 1));

  /** 平台单（**销售订单 → 店铺**的唯一来源）。 */
  static final List<Map<String, Object>> PLATFORM_ORDERS =
      List.of(
          platformOrder("SO-1", "shop-a"),
          platformOrder("SO-2", "shop-b"),
          platformOrder("SO-3", "shop-a"),
          platformOrder("SO-4", "shop-c"),
          platformOrder("SO-5", "shop-d"),
          platformOrder("SO-6", "shop-a"),
          platformOrder("SO-DST-JAN", "shop-a"),
          platformOrder("SO-DST-JUL", "shop-a"));

  // ==========================================================================
  // 假上游
  // ==========================================================================

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
      identity.start();

      upstream = HttpServer.create(new InetSocketAddress(0), 0);
      upstream.createContext("/trade/sales-orders/list", e -> respond(e, salesOrderPage(e)));
      upstream.createContext("/trade/sales-orders/detail", e -> respond(e, salesOrderDetail(e)));
      upstream.createContext("/trade/platform-orders/orders", e -> respond(e, pageBody("rows", PLATFORM_ORDERS)));
      upstream.createContext("/channel/platforms", e -> respond(e, PLATFORMS));
      upstream.createContext("/channel/shops", e -> respond(e, shopEndpoint(e)));
      upstream.start();
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
    r.add("CHANNEL_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://localhost:" + upstream.getAddress().getPort());
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ReportingTimePatternRefreshService refresh;
  @Autowired ReportingTimePatternService pattern;

  @AfterAll
  static void stop() {
    identity.stop(0);
    upstream.stop(0);
    pg.stop();
  }

  @BeforeEach
  void reset() throws Exception {
    upstreamDown = false;
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate reporting_time_pattern_fact, reporting_time_pattern_shop_profile,"
              + " reporting_time_pattern_slot, reporting_time_pattern_metric_definition,"
              + " reporting_metric_definition cascade");
    }
  }

  // ==========================================================================
  // 业务口径
  // ==========================================================================

  @Test
  void 刷新把上游事实投影成分时事实() {
    var results = refreshAll();
    assertThat(results).as("四路上游都要有结果（口径 / 时段配置 / 店铺时区 / 订单）").hasSize(4);
    assertThat(results).allSatisfy(r -> assertThat(r.status()).isNotEqualTo("FAILED"));
    assertThat(factCount()).as("事实行必须落库").isPositive();
  }

  @Test
  void 店铺时区来自站点参数定不了的店铺也说清原因() {
    refreshAll();
    var shops = pattern.shops(tenant);

    var a = shop(shops, "shop-a");
    assertThat(a.shopTimezone()).as("shop-a 启用了 US 站点 ⇒ 洛杉矶时区").isEqualTo("America/Los_Angeles");
    assertThat(a.timezoneStatus()).isEqualTo("RESOLVED");

    var b = shop(shops, "shop-b");
    assertThat(b.shopTimezone()).isEqualTo("Asia/Shanghai");

    var c = shop(shops, "shop-c");
    assertThat(c.timezoneStatus()).as("没启用站点 ⇒ 定不了").isEqualTo("UNRESOLVED");
    assertThat(c.shopTimezone()).isEmpty();
    assertThat(c.timezoneReason()).as("必须说清为什么定不了").contains("没有启用任何站点");

    var d = shop(shops, "shop-d");
    assertThat(d.timezoneStatus()).as("多个站点时区不一致 ⇒ 定不了（不挑一个凑数）").isEqualTo("UNRESOLVED");
    assertThat(d.timezoneReason()).contains("时区不一致");
  }

  @Test
  void 按小时是店铺本地小时不是utc小时() {
    refreshAll();
    var response = pattern.pattern(tenant, DAY1, DAY7, alignment("LOCAL_TIME"), List.of(), null);

    // 🔴 SO-1（shop-a，洛杉矶）：UTC 2026-10-02 02:00 ⇒ 本地 **10-01 19:00（周四）**
    var thu19 = cell(response, 4, 19);
    assertThat(thu19.orderCount()).as("洛杉矶本地周四 19 点这一格").isEqualByComparingTo("1");
    assertThat(amount(thu19, "USD")).isEqualByComparingTo("100");

    // 🔴 SO-2（shop-b，新加坡）：UTC 2026-10-02 02:00 ⇒ 本地 **10-02 10:00（周五）**
    var fri10 = cell(response, 5, 10);
    assertThat(fri10.orderCount()).isEqualByComparingTo("1");
    assertThat(amount(fri10, "USD")).isEqualByComparingTo("200");

    // ⚠️ **UTC 槽位那一格必须是空的** —— 证明没有拿 UTC 小时当本地小时。
    var utcSlot = cell(response, 5, 2);
    assertThat(utcSlot.orderCount()).as("UTC 周五 02 点这一格在本地口径下不该有单").isEqualByComparingTo("0");
  }

  @Test
  void 按星期是店铺本地星期几不是周数() {
    refreshAll();
    var response = pattern.pattern(tenant, DAY1, DAY7, alignment("LOCAL_TIME"), List.of(), null);

    // 🔴 **每个店铺按自己的本地时区算星期几** —— 同一批 UTC 时刻落在不同店铺的不同星期几：
    //   SO-1（shop-a，洛杉矶）2026-10-02T02:00Z ⇒ 本地 **10-01 周四** 19:00
    //   SO-2（shop-b，新加坡）2026-10-02T02:00Z ⇒ 本地 **10-02 周五** 10:00
    //   SO-3（shop-a，洛杉矶）2026-10-03T02:00Z ⇒ 本地 **10-02 周五** 19:00
    //   SO-6（shop-a，洛杉矶）2026-10-04T00:30Z ⇒ 本地 **10-03 周六** 17:00
    // 2026-10-01 是周四（ISO 4）；2026-10-02 是周五（ISO 5）；2026-10-03 是周六（ISO 6）。
    var thursday = weekday(response, 4);
    var friday = weekday(response, 5);
    var saturday = weekday(response, 6);
    assertThat(thursday.orderCount()).as("周四只有 SO-1").isEqualByComparingTo("1");
    assertThat(friday.orderCount()).as("周五有 SO-2 / SO-3").isEqualByComparingTo("2");
    assertThat(saturday.orderCount()).as("周六有 SO-6").isEqualByComparingTo("1");
    // ⚠️ **星期几 ≠ 周数**：本模块**不产出**周数（不是「第几周」）。
    assertThat(response.weekdayDistribution()).hasSize(7);
    assertThat(response.weekdayDistribution().get(0).weekday()).isEqualTo(1);
    assertThat(response.weekdayDistribution().get(0).weekdayLabel()).isEqualTo("周一");
  }

  @Test
  void 夏令时用iana时区而不是固定utc偏移() {
    refreshAll();
    refresh.refresh(tenant, BEARER, WINTER, WINTER);
    refresh.refresh(tenant, BEARER, SUMMER, SUMMER);

    var winter = pattern.pattern(tenant, WINTER, WINTER, alignment("LOCAL_TIME"), List.of(), null);
    var summer = pattern.pattern(tenant, SUMMER, SUMMER, alignment("LOCAL_TIME"), List.of(), null);

    // 🔴 冬（PST, UTC-8）：2026-01-15T18:00Z ⇒ 本地 10:00
    //    夏（PDT, UTC-7）：2026-07-15T17:00Z ⇒ 本地 10:00
    //    ⇒ 用**固定** UTC 偏移的话，夏天那单会落在 09:00。
    assertThat(cell(winter, weekdayOf(winter, 10), 10).orderCount())
        .as("冬季那单落在本地 10 点")
        .isEqualByComparingTo("1");
    assertThat(cell(summer, weekdayOf(summer, 10), 10).orderCount())
        .as("夏季那单也落在本地 10 点（夏令时没有被固定偏移吃掉）")
        .isEqualByComparingTo("1");
    assertThat(hour(winter, 10).orderCount()).isEqualByComparingTo("1");
    assertThat(hour(summer, 10).orderCount()).isEqualByComparingTo("1");
    assertThat(hour(summer, 9).orderCount()).as("本地 9 点不该有单").isEqualByComparingTo("0");
  }

  @Test
  void 跨店铺汇总的两种口径结果不同且响应写明用的是哪个() {
    refreshAll();
    var local = pattern.pattern(tenant, DAY1, DAY7, alignment("LOCAL_TIME"), List.of(), null);
    var utc = pattern.pattern(tenant, DAY1, DAY7, alignment("UTC_SLOT"), List.of(), null);

    // 🔴 LOCAL_TIME：shop-a 的单在（周四 19），shop-b 的单在（周五 10）
    assertThat(cell(local, 4, 19).orderCount()).isEqualByComparingTo("1");
    assertThat(cell(local, 5, 10).orderCount()).isEqualByComparingTo("1");
    // 🔴 UTC_SLOT：SO-1 与 SO-2 是同一绝对时刻 ⇒ 落在同一格（周五 02）
    assertThat(cell(utc, 5, 2).orderCount()).as("同一绝对时刻的两单在 UTC 口径下同一格").isEqualByComparingTo("2");
    assertThat(cell(local, 5, 2).orderCount()).as("本地口径下不是同一格").isEqualByComparingTo("0");

    // ⚠️ **响应必须写明用的是哪个口径、为什么**。
    assertThat(local.alignment()).isEqualTo("LOCAL_TIME");
    assertThat(local.alignmentLabel()).contains("本地");
    assertThat(local.alignmentNote()).contains("买家在什么时间点下单");
    assertThat(utc.alignment()).isEqualTo("UTC_SLOT");
    assertThat(utc.alignmentNote()).contains("仓内同一绝对时刻");
  }

  @Test
  void 定不了时区的订单不进任何格子并单列() {
    refreshAll();
    var response = pattern.pattern(tenant, DAY1, DAY7, alignment("LOCAL_TIME"), List.of(), null);

    // 4 单进了格子（SO-1 / SO-2 / SO-3 / SO-6）
    var cellTotal = BigDecimal.ZERO;
    for (var cell : response.heatmap()) cellTotal = cellTotal.add(cell.orderCount());
    assertThat(cellTotal).as("只有定得了时区的订单进格子").isEqualByComparingTo("4");

    // ⚠️ 定不了时区的两单（SO-4 / SO-5）单列出来
    assertThat(response.unattributed().orderCount()).isEqualByComparingTo("2");
    assertThat(response.unattributed().shopCodes()).containsExactlyInAnyOrder("shop-c", "shop-d");
    assertThat(amountOf(response.unattributed().salesAmounts(), "USD")).isEqualByComparingTo("900");
    assertThat(response.unattributed().reason()).contains("不进任何时段与格子");
  }

  @Test
  void 挂不上店铺的订单不计入任何店铺() {
    refreshAll();
    var response = pattern.pattern(tenant, DAY1, DAY7, alignment("LOCAL_TIME"), List.of(), null);
    var cellTotal = BigDecimal.ZERO;
    for (var cell : response.heatmap()) cellTotal = cellTotal.add(cell.orderCount());
    // ⚠️ SO-7（700 USD）没有平台单 ⇒ 不计入任何店铺 ⇒ 格子里没有它。
    assertThat(cellTotal).isEqualByComparingTo("4");
    var usd = BigDecimal.ZERO;
    for (var cell : response.heatmap()) usd = usd.add(amount(cell, "USD"));
    assertThat(usd).as("挂不上店铺的 700 不在里面").isEqualByComparingTo("900");
  }

  @Test
  void 时段配置是租户数据判定读表() throws Exception {
    refreshAll();
    var before = pattern.pattern(tenant, DAY1, DAY7, alignment("LOCAL_TIME"), List.of(), null);
    var afternoon = slot(before, "AFTERNOON");
    assertThat(afternoon.level()).isEqualTo("PEAK");
    assertThat(afternoon.qualified()).as("占比 25% ≥ 阈值 20% ⇒ 达标").isTrue();
    assertThat(afternoon.reason()).contains("达标");

    // 🔴 **改配置 ⇒ 判定跟着变**（判定读表，不是 if-else 硬编码）。
    //
    // ⚠️ 两处必须写对，否则请求根本到不了数据层：
    //   ① **阈值是 0..1 的小数**（不是百分数）—— 按订单占比判定时传 9 会被服务层拒成 400；
    //   ② **整体替换必须完整覆盖 0..24 小时**（时段区间不可重叠、不留空洞）——
    //      只给两段、中间空着，服务层就先拒了。
    var threshold = new BigDecimal("0.9");
    http.perform(
            put("/reporting/time-pattern/slots")
                .header("Authorization", BEARER)
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        Map.of(
                            "slots",
                            List.of(
                                Map.of(
                                    "slotCode", "DAWN",
                                    "displayName", "凌晨",
                                    "startHour", 0,
                                    "endHour", 12,
                                    "level", "SHOULDER",
                                    "qualifyBasis", "ORDER_SHARE",
                                    "qualifyComparison", "AT_LEAST",
                                    "qualifyThreshold", 0),
                                Map.of(
                                    "slotCode", "AFTERNOON",
                                    "displayName", "下午",
                                    "startHour", 12,
                                    "endHour", 18,
                                    "level", "PEAK",
                                    "qualifyBasis", "ORDER_SHARE",
                                    "qualifyComparison", "AT_LEAST",
                                    "qualifyThreshold", threshold),
                                Map.of(
                                    "slotCode", "EVENING",
                                    "displayName", "晚间",
                                    "startHour", 18,
                                    "endHour", 24,
                                    "level", "SHOULDER",
                                    "qualifyBasis", "ORDER_SHARE",
                                    "qualifyComparison", "AT_LEAST",
                                    "qualifyThreshold", 0))))))
        .andExpect(status().isOk());

    var after = pattern.pattern(tenant, DAY1, DAY7, alignment("LOCAL_TIME"), List.of(), null);
    var changed = slot(after, "AFTERNOON");
    assertThat(changed.qualified()).as("阈值提到 90% ⇒ 实际占比 25% 未达标").isFalse();
    assertThat(changed.reason()).as("理由要说清为什么").contains("未达标");
    // ⚠️ 改配置后**时段区间也变了**（凌晨扩到 12 点）⇒ 归属跟着变。
    assertThat(slot(after, "DAWN").orderCount()).as("凌晨时段现在含 10 点那一格").isEqualByComparingTo("1");
    // 🔴 **整体替换必须是「一次成型」的**：新配置整套落库，旧的 MORNING 不再存在。
    assertThat(pattern.slotConfigs(tenant)).as("整体替换后是租户自己定的那三段").hasSize(3);
  }

  @Test
  void 时段区间不可重叠不留空洞由数据层拒绝() throws Exception {
    refreshAll();
    // ① 重叠 ⇒ 400
    http.perform(
            post("/reporting/time-pattern/slots")
                .header("Authorization", BEARER)
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        Map.of(
                            "slotCode", "OVERLAP",
                            "displayName", "重叠段",
                            "startHour", 9,
                            "endHour", 15,
                            "level", "PEAK",
                            "qualifyThreshold", 0))))
        .andExpect(status().isBadRequest());

    // ② 留空洞 ⇒ 400（整体替换只给两段，中间 6 点没归属）
    http.perform(
            put("/reporting/time-pattern/slots")
                .header("Authorization", BEARER)
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        Map.of(
                            "slots",
                            List.of(
                                Map.of(
                                    "slotCode", "A",
                                    "displayName", "A",
                                    "startHour", 0,
                                    "endHour", 8,
                                    "level", "SHOULDER",
                                    "qualifyThreshold", 0),
                                Map.of(
                                    "slotCode", "B",
                                    "displayName", "B",
                                    "startHour", 8,
                                    "endHour", 16,
                                    "level", "SHOULDER",
                                    "qualifyThreshold", 0))))))
        .andExpect(status().isBadRequest());

    // ⚠️ **整体替换失败 ⇒ 旧配置原样保留**（事务回滚）。
    var configs = pattern.slotConfigs(tenant);
    assertThat(configs).as("失败的整体替换不许留下半套配置").hasSize(4);
  }

  @Test
  void 删除时段留了空洞会被数据层拒绝() throws Exception {
    refreshAll();
    http.perform(delete("/reporting/time-pattern/slots/" + slotId("EVENING")).header("Authorization", BEARER))
        .andExpect(status().isBadRequest());
    assertThat(pattern.slotConfigs(tenant)).as("删除被拒 ⇒ 配置原样保留").hasSize(4);
  }

  @Test
  void 高峰时段排序的依据指标登记在口径表里() {
    refreshAll();
    var response = pattern.pattern(tenant, DAY1, DAY7, alignment("LOCAL_TIME"), List.of(), null);

    assertThat(response.rankingMetricCode()).isEqualTo(ReportingMetricCatalog.ORDER_COUNT);
    assertThat(response.peakRanking()).as("每个时段都要有排序").hasSize(4);
    assertThat(response.peakRanking().get(0).rank()).isEqualTo(1);
    // 🔴 晚间（18..24）含 19 点那两单 ⇒ 排第一
    assertThat(response.peakRanking().get(0).slotCode()).isEqualTo("EVENING");
    assertThat(response.peakRanking().get(0).metricValue()).isEqualByComparingTo("2");
    assertThat(response.peakRanking().get(0).metricCode()).isEqualTo(ReportingMetricCatalog.ORDER_COUNT);

    // ⚠️ **排序依据的指标码必须在口径表里登记过**。
    var codes = new ArrayList<String>();
    for (var definition : pattern.metricDefinitions(tenant)) codes.add(definition.metricCode());
    assertThat(codes).contains(ReportingMetricCatalog.ORDER_COUNT);
  }

  @Test
  void 公司维度的三个基础口径引用不重定义() {
    refreshAll();
    var definitions = pattern.metricDefinitions(tenant);
    var codes = new ArrayList<String>();
    for (var definition : definitions) {
      codes.add(definition.metricCode());
      // 🔴 **同一个指标码只能有一份定义** —— 本模块的口径表里不得出现公司口径的重定义。
      if (definition.referencedFrom().startsWith("reporting_time_pattern_metric_definition")) {
        assertThat(definition.metricCode())
            .as("公司维度的三个基础口径**不重定义**")
            .isNotIn(
                ReportingMetricCatalog.SALES_AMOUNT,
                ReportingMetricCatalog.UNIT_SOLD,
                ReportingMetricCatalog.ORDER_COUNT);
      }
    }
    assertThat(codes).contains(ReportingMetricCatalog.SALES_AMOUNT);
    assertThat(codes).contains(ReportingMetricCatalog.UNIT_SOLD);
    assertThat(codes).contains(ReportingMetricCatalog.ORDER_COUNT);
    assertThat(codes).contains(ReportingTimePatternCatalog.TIME_PATTERN_SLOT_ORDER_SHARE);
    assertThat(codes).contains(ReportingTimePatternCatalog.TIME_PATTERN_HOUR_P90_ORDER_COUNT);
  }

  @Test
  void p90阈值把没单的桶也算进去() {
    refreshAll();
    var response = pattern.pattern(tenant, DAY1, DAY7, alignment("LOCAL_TIME"), List.of(), null);

    // 24 个桶：21 个 0、两个 1、一个 2 ⇒ P90 = 0 + 0.7 × (1 − 0) = 0.7
    assertThat(response.p90OrderCount()).isEqualByComparingTo("0.7");
    assertThat(hour(response, 19).p90Peak()).as("19 点那格达到 P90").isTrue();
    assertThat(hour(response, 10).p90Peak()).isTrue();
    assertThat(hour(response, 3).p90Peak()).as("没单的桶达不到 P90").isFalse();
  }

  @Test
  void 多币种绝不相加() {
    refreshAll();
    var response = pattern.pattern(tenant, DAY1, DAY7, alignment("LOCAL_TIME"), List.of(), null);

    var usd = BigDecimal.ZERO;
    var eur = BigDecimal.ZERO;
    for (var cell : response.heatmap()) {
      usd = usd.add(amount(cell, "USD"));
      eur = eur.add(amount(cell, "EUR"));
    }
    assertThat(usd).isEqualByComparingTo("900");
    assertThat(eur).isEqualByComparingTo("300");
    // ⚠️ **绝没有把两个币种加在一起**：任何一格都不是 900 + 300 = 1200。
    for (var cell : response.heatmap()) {
      var total = BigDecimal.ZERO;
      for (var a : cell.salesAmounts()) total = total.add(a.value());
      assertThat(total).isNotEqualByComparingTo("1200");
    }
  }

  @Test
  void 空表时数据更新时间为null且状态是未投影() {
    var response = pattern.pattern(tenant, DAY1, DAY7, alignment("LOCAL_TIME"), List.of(), null);
    assertThat(response.dataUpdatedAt()).as("空表时是 null，不是长得像时间的假值").isNull();
    assertThat(response.status()).isEqualTo("NOT_PROJECTED");
    assertThat(response.emptyReason()).isEqualTo("NOT_PROJECTED");
    assertThat(response.emptyNote()).contains("先刷新投影");
  }

  @Test
  void 重复刷新是幂等的不是翻倍() {
    refreshAll();
    var first = factCount();
    refreshAll();
    assertThat(factCount()).as("重复刷新必须覆盖而不是翻倍").isEqualTo(first);
  }

  @Test
  void 上游读不到就失败不返回空集合() {
    upstreamDown = true;
    try {
      assertThatThrownBy(() -> refresh.refresh(tenant, BEARER, DAY1, DAY7))
          .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
          .hasMessageContaining("读不到");
    } finally {
      upstreamDown = false;
    }
  }

  @Test
  void 店铺筛选只影响选中的店铺() {
    refreshAll();
    var all = pattern.pattern(tenant, DAY1, DAY7, alignment("LOCAL_TIME"), List.of(), null);
    var onlyA = pattern.pattern(tenant, DAY1, DAY7, alignment("LOCAL_TIME"), List.of("shop-a"), null);

    var total = BigDecimal.ZERO;
    for (var cell : onlyA.heatmap()) total = total.add(cell.orderCount());
    // 🔴 shop-a 的**三**单：SO-1（本地周四 19 点）、SO-3（本地周五 19 点）、SO-6（本地周六 17 点）。
    //   SO-2 属于 shop-b ⇒ 选 shop-a 时它不在里面。
    assertThat(total).as("只有 shop-a 的三单（SO-1 / SO-3 / SO-6）").isEqualByComparingTo("3");
    assertThat(onlyA.alignment()).isEqualTo(all.alignment());
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  private List<ReportingTimePatternModels.TimePatternRefreshResult> refreshAll() {
    return refresh.refresh(tenant, BEARER, DAY1, DAY7);
  }

  private int factCount() {
    return tenants.run(tenant, () -> sql.fetch("select id from reporting_time_pattern_fact").size());
  }

  private static ReportingTimePatternCatalog.Alignment alignment(String name) {
    for (var alignment : ReportingTimePatternCatalog.Alignment.values()) {
      if (alignment.name().equals(name)) return alignment;
    }
    throw new IllegalArgumentException(name);
  }

  private static ReportingTimePatternModels.TimePatternCell cell(
      ReportingTimePatternModels.TimePatternResponse response, int weekday, int hour) {
    for (var cell : response.heatmap()) {
      if (cell.weekday() == weekday && cell.hour() == hour) return cell;
    }
    throw new AssertionError("热力图里没有这一格：周" + weekday + " " + hour + " 点");
  }

  private static ReportingTimePatternModels.TimePatternHourBucket hour(
      ReportingTimePatternModels.TimePatternResponse response, int hour) {
    for (var bucket : response.hourDistribution()) {
      if (bucket.hour() == hour) return bucket;
    }
    throw new AssertionError("小时分布里没有这一桶：" + hour);
  }

  private static ReportingTimePatternModels.TimePatternWeekdayBucket weekday(
      ReportingTimePatternModels.TimePatternResponse response, int weekday) {
    for (var bucket : response.weekdayDistribution()) {
      if (bucket.weekday() == weekday) return bucket;
    }
    throw new AssertionError("星期分布里没有这一桶：" + weekday);
  }

  /** 从小时分布里找出那一格落在星期几（夹具用）。 */
  private static int weekdayOf(
      ReportingTimePatternModels.TimePatternResponse response, int hour) {
    for (var cell : response.heatmap()) {
      if (cell.hour() == hour && cell.orderCount().signum() > 0) return cell.weekday();
    }
    throw new AssertionError("这一小时没有任何事实：" + hour);
  }

  private static ReportingTimePatternModels.TimePatternSlotView slot(
      ReportingTimePatternModels.TimePatternResponse response, String slotCode) {
    for (var slot : response.slots()) {
      if (slot.slotCode().equals(slotCode)) return slot;
    }
    throw new AssertionError("没有这个时段：" + slotCode);
  }

  private static ReportingTimePatternModels.TimePatternShopView shop(
      List<ReportingTimePatternModels.TimePatternShopView> shops, String shopCode) {
    for (var shop : shops) {
      if (shop.shopCode().equals(shopCode)) return shop;
    }
    throw new AssertionError("档案里没有这个店铺：" + shopCode);
  }

  private String slotId(String slotCode) {
    return tenants.run(
        tenant,
        () ->
            sql.fetchOne(
                    "select id from reporting_time_pattern_slot where tenant_id = ? and slot_code = ?",
                    tenant,
                    slotCode)
                .get(0, UUID.class)
                .toString());
  }

  private static BigDecimal amount(ReportingTimePatternModels.TimePatternCell cell, String currency) {
    return amountOf(cell.salesAmounts(), currency);
  }

  private static BigDecimal amountOf(
      List<ReportingTimePatternModels.TimePatternAmount> amounts, String currency) {
    for (var amount : amounts) {
      if (amount.currency().equals(currency)) return amount.value();
    }
    return BigDecimal.ZERO;
  }

  // ---------------------------------------------------------------------------
  // 假上游
  // ---------------------------------------------------------------------------

  private static void respond(com.sun.net.httpserver.HttpExchange e, Object body) {
    if (upstreamDown) {
      try {
        e.sendResponseHeaders(500, -1);
        e.close();
      } catch (Exception ex) {
        throw new IllegalStateException(ex);
      }
      return;
    }
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

  private static Map<String, Object> pageBody(String field, List<Map<String, Object>> rows) {
    var body = new LinkedHashMap<String, Object>();
    body.put(field, rows);
    body.put("page", 0);
    body.put("size", 200);
    body.put("total", rows.size());
    return body;
  }

  private static Map<String, Object> salesOrderPage(com.sun.net.httpserver.HttpExchange e) {
    var query = query(e);
    var from = query.get("orderedFrom") == null ? null : java.time.Instant.parse(query.get("orderedFrom"));
    var to = query.get("orderedTo") == null ? null : java.time.Instant.parse(query.get("orderedTo"));
    var rows = new ArrayList<Map<String, Object>>();
    for (var order : ORDERS) {
      var submittedAt = java.time.Instant.parse((String) order.get("submittedAt"));
      if (from != null && submittedAt.isBefore(from)) continue;
      if (to != null && !submittedAt.isBefore(to)) continue;
      rows.add(order);
    }
    return pageBody("rows", rows);
  }

  private static Map<String, Object> salesOrderDetail(com.sun.net.httpserver.HttpExchange e) {
    var path = e.getRequestURI().getPath();
    var id = path.substring(path.lastIndexOf('/') + 1);
    var order =
        ORDERS.stream()
            .filter(o -> id.equals(o.get("id")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("没有这张订单：" + id));
    var body = new LinkedHashMap<String, Object>();
    var header = new LinkedHashMap<String, Object>();
    header.put("orderNo", order.get("orderNo"));
    header.put("currency", order.get("currency"));
    header.put("grandTotalAmount", order.get("grandTotalAmount"));
    header.put("submittedAt", order.get("submittedAt"));
    body.put("header", header);
    var line = new LinkedHashMap<String, Object>();
    line.put("skuCode", "SKU-A");
    line.put("skuName", "蓝色 T 恤");
    line.put("quantity", order.get("quantity"));
    line.put("unitPrice", "10.00");
    line.put("lineAmount", "10.00");
    body.put("lines", List.of(line));
    return body;
  }

  /** 店铺端点：列表与详情共用一个 context（按路径区分）。 */
  private static Object shopEndpoint(com.sun.net.httpserver.HttpExchange e) {
    var path = e.getRequestURI().getPath();
    if (path.endsWith("/shops")) return SHOPS;
    var id = path.substring(path.lastIndexOf('/') + 1);
    var shop =
        SHOPS.stream()
            .filter(s -> id.equals(s.get("id")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("没有这个店铺：" + id));
    var body = new LinkedHashMap<String, Object>(shop);
    var sites = new ArrayList<Map<String, Object>>();
    for (var code : SHOP_SITES.getOrDefault((String) shop.get("code"), List.of())) {
      var site = new LinkedHashMap<String, Object>();
      site.put("marketplaceId", UUID.nameUUIDFromBytes(code.getBytes(StandardCharsets.UTF_8)).toString());
      site.put("marketplaceCode", code);
      site.put("marketplaceName", code);
      site.put("status", "ACTIVE");
      site.put("enabledAt", "2026-01-01T00:00:00Z");
      sites.add(site);
    }
    body.put("marketplaces", sites);
    return body;
  }

  private static Map<String, String> query(com.sun.net.httpserver.HttpExchange e) {
    var out = new LinkedHashMap<String, String>();
    var raw = e.getRequestURI().getRawQuery();
    if (raw == null || raw.isBlank()) return out;
    for (var pair : raw.split("&")) {
      var index = pair.indexOf('=');
      if (index < 0) continue;
      out.put(
          URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8),
          URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8));
    }
    return out;
  }

  private static Map<String, Object> platform(String code, Map<String, Object>... marketplaces) {
    var body = new LinkedHashMap<String, Object>();
    body.put("id", UUID.nameUUIDFromBytes(code.getBytes(StandardCharsets.UTF_8)).toString());
    body.put("code", code);
    body.put("name", code);
    body.put("marketplaces", List.of(marketplaces));
    return body;
  }

  private static Map<String, Object> marketplace(
      String code, String country, String defaultTimezone) {
    var body = new LinkedHashMap<String, Object>();
    body.put("id", UUID.nameUUIDFromBytes(code.getBytes(StandardCharsets.UTF_8)).toString());
    body.put("code", code);
    body.put("name", code);
    body.put("countryCode", country);
    body.put("defaultCurrency", "USD");
    body.put("defaultTimezone", defaultTimezone);
    return body;
  }

  private static Map<String, Object> shop(String code, String name, String platformCode) {
    var body = new LinkedHashMap<String, Object>();
    body.put("id", UUID.nameUUIDFromBytes(code.getBytes(StandardCharsets.UTF_8)).toString());
    body.put("code", code);
    body.put("name", name);
    body.put("platformCode", platformCode);
    body.put("platformName", platformCode);
    return body;
  }

  private static Map<String, Object> order(
      String orderNo,
      String shopCode,
      String submittedAt,
      String amount,
      String currency,
      int quantity) {
    var body = new LinkedHashMap<String, Object>();
    body.put("id", UUID.nameUUIDFromBytes(orderNo.getBytes(StandardCharsets.UTF_8)).toString());
    body.put("orderNo", orderNo);
    body.put("orderSource", "PLATFORM");
    body.put("currency", currency);
    body.put("grandTotalAmount", amount);
    body.put("lineCount", 1);
    body.put("submittedAt", submittedAt);
    body.put("createdAt", submittedAt);
    body.put("shopCode", shopCode);
    body.put("quantity", quantity);
    return body;
  }

  private static Map<String, Object> platformOrder(String orderNo, String shopCode) {
    var body = new LinkedHashMap<String, Object>();
    body.put("id", UUID.nameUUIDFromBytes(("po:" + orderNo).getBytes(StandardCharsets.UTF_8)).toString());
    body.put(
        "salesOrderId",
        UUID.nameUUIDFromBytes(orderNo.getBytes(StandardCharsets.UTF_8)).toString());
    body.put("orderNo", orderNo);
    body.put("platformCode", "AMAZON");
    body.put("shopCode", shopCode);
    body.put("currency", "USD");
    body.put("platformPlacedAt", "2026-10-02T02:00:00Z");
    body.put("syncedAt", "2026-10-02T02:05:00Z");
    return body;
  }
}
