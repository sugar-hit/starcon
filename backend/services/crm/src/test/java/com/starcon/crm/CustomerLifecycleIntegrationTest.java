package com.starcon.crm;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starcon.crm.CustomerOrderFactsPort.OrderFact;
import com.starcon.crm.CustomerOrderFactsPort.OrderPage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * 15.6.2.1 生命周期与流失预警的集成测试。
 *
 * <p>跑的是 AGENTS.md 要求的<b>真实 PostgreSQL 16 + FORCE RLS</b>。
 * 租户隔离三断言在 {@link CustomerLifecycleTenancyIntegrationTest} 里，本类专攻业务不变量：
 *
 * <ul>
 *   <li><b>阈值按类目生效</b>：类目策略 > 全局策略 > 租户真实订单历史基线；
 *       <b>代码里没有写死的周数</b> —— 没有策略时阈值来自真实订单历史的中位复购间隔。</li>
 *   <li><b>「本应复购未复购」有可复核依据</b>：预期复购间隔 + 容差，
 *       加上客户本人历史复购间隔的中位数与样本数。</li>
 *   <li><b>风险分可解释</b>：四个因子各带权重、原始值、得分与说明，加总等于风险分。</li>
 *   <li><b>召回只产出建议</b>：归属写在 suggestion_owner 上（本模块不发消息）。</li>
 *   <li><b>上游不造数据</b>：trade / catalog 读不到就明确失败（503），
 *       <b>绝不</b>返回空集合冒充「没有客户」。</li>
 *   <li><b>禁浮点 / 时区不静默偏移</b>：金额逐位相同；带偏移量的时刻写回等于同一绝对时刻。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerLifecycleIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID userId = UUID.randomUUID();
  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          // ⚠️ 默认会把 JSON 数字读成 double —— 那样断言本身就先把值舍入了，
          // 「没掉精度」这件事就再也证不出来。这里强制读成 BigDecimal。
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 判定的参考时刻（窗口终点）。固定值 ⇒ 断言可复现。 */
  static final OffsetDateTime T0 = OffsetDateTime.parse("2026-11-01T00:00:00Z");

  /** 客户甲：快消类目，个人复购间隔 20 天（4 单 ⇒ 3 段间隔）。 */
  static final UUID partyA = UUID.randomUUID();

  /** 客户乙：家具类目，个人复购间隔 30 天（3 单 ⇒ 2 段间隔）。 */
  static final UUID partyB = UUID.randomUUID();

  /** 客户丙：只下过一单的新客（**没有自己的复购间隔基线**）。 */
  static final UUID partyC = UUID.randomUUID();

  /** 客户丁：带 +08:00 偏移量的时刻，用来钉死时区不静默偏移。 */
  static final UUID partyD = UUID.randomUUID();
  static final OffsetDateTime partyDOrderAt = OffsetDateTime.parse("2026-10-10T08:00:00+08:00");

  static final List<OrderFact> upstreamOrders = new CopyOnWriteArrayList<>();
  static final Map<String, String> upstreamSkuCategories = new ConcurrentHashMap<>();

  /** 上游读不到时的开关（用来验证「明确失败」而不是空集合）。 */
  static volatile boolean tradeDown = false;
  static volatile boolean catalogDown = false;

  static HttpServer identity;
  static HttpServer trade;
  static HttpServer catalog;

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，三条断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role crm_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    List<String> permissions = List.of("crm.lifecycle.read", "crm.lifecycle.update");
    actors.put(
        "session",
        new AccessService.Access(
            userId,
            tenant,
            "Test",
            UUID.randomUUID(),
            null,
            permissions,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions))));
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
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    try {
      trade = HttpServer.create(new InetSocketAddress(0), 0);
      trade.createContext(
          "/trade/sales-orders/list",
          e -> {
            if (tradeDown) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            var query = query(e);
            OffsetDateTime from = moment(query.get("orderedFrom"));
            OffsetDateTime to = moment(query.get("orderedTo"));
            List<OrderFact> matched = new ArrayList<>();
            for (OrderFact o : upstreamOrders) {
              OffsetDateTime at = o.submittedAt() == null ? o.createdAt() : o.submittedAt();
              if (at == null) continue;
              if (from != null && at.isBefore(from)) continue;
              if (to != null && !at.isBefore(to)) continue;
              matched.add(o);
            }
            int size = Integer.parseInt(query.getOrDefault("size", "200"));
            int pageNo = Integer.parseInt(query.getOrDefault("page", "0"));
            int offset = Math.min(pageNo * size, matched.size());
            int limit = Math.min(offset + size, matched.size());
            write(
                e,
                new OrderPage(
                    new ArrayList<>(matched.subList(offset, limit)), pageNo, size, matched.size()));
          });
      trade.setExecutor(Executors.newFixedThreadPool(4));
      trade.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    try {
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/skus",
          e -> {
            if (catalogDown) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            var query = query(e);
            String q = query.getOrDefault("q", "");
            List<Map<String, Object>> items = new ArrayList<>();
            for (var entry : upstreamSkuCategories.entrySet()) {
              if (!entry.getKey().contains(q)) continue;
              Map<String, Object> row = new HashMap<>();
              row.put("id", UUID.nameUUIDFromBytes(entry.getKey().getBytes()).toString());
              row.put("code", entry.getKey());
              row.put("name", entry.getKey());
              row.put("category", entry.getValue());
              items.add(row);
            }
            write(e, Map.of("items", items, "total", items.size(), "page", 0, "size", 20));
          });
      catalog.setExecutor(Executors.newFixedThreadPool(4));
      catalog.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "crm_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://127.0.0.1:" + trade.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://127.0.0.1:" + catalog.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  // ------------------------------------------------------------ 测试脚手架

  private static Map<String, String> query(HttpExchange e) {
    Map<String, String> out = new HashMap<>();
    String raw = e.getRequestURI().getRawQuery();
    if (raw == null || raw.isBlank()) return out;
    for (String part : raw.split("&")) {
      int eq = part.indexOf('=');
      if (eq < 0) continue;
      out.put(
          URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8),
          URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
    }
    return out;
  }

  private static OffsetDateTime moment(String text) {
    return text == null || text.isBlank() ? null : OffsetDateTime.parse(text);
  }

  private static void write(HttpExchange e, Object body) {
    try {
      byte[] bytes = json.writeValueAsBytes(body);
      e.getResponseHeaders().add("Content-Type", "application/json");
      e.sendResponseHeaders(200, bytes.length);
      e.getResponseBody().write(bytes);
      e.close();
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  /** 造一条上游订单。 */
  private static OrderFact order(
      UUID id,
      String orderNo,
      String source,
      UUID party,
      String buyer,
      String currency,
      String amount,
      List<String> skuCodes,
      OffsetDateTime at) {
    return new OrderFact(
        id,
        orderNo,
        source,
        "SUBMITTED",
        currency,
        new BigDecimal(amount),
        party,
        buyer,
        skuCodes,
        at,
        at);
  }

  @BeforeEach
  void reset() throws Exception {
    upstreamOrders.clear();
    upstreamSkuCategories.clear();
    tradeDown = false;
    catalogDown = false;
    try (var c = admin();
        var s = c.createStatement()) {
      s.execute(
          "truncate table crm_lifecycle_policy, crm_customer_lifecycle, crm_churn_alert,"
              + " crm_lifecycle_run cascade");
    }

    // 客户甲：快消（FAST_MOVING），4 单，间隔 20 天，最后一单在窗口终点前 40 天。
    // 金额刻意用 0.10 / 0.20 / 0.30 / 0.40 ⇒ 浮点加法会得到 0.9999999999999999。
    upstreamSkuCategories.put("SKU-A1", "FAST_MOVING");
    String[] amounts = {"0.10", "0.20", "0.30", "0.40"};
    for (int i = 0; i < 4; i++) {
      upstreamOrders.add(
          order(
              UUID.randomUUID(),
              "SO-A-" + i,
              "AMAZON",
              partyA,
              "买家甲",
              "USD",
              amounts[i],
              List.of("SKU-A1"),
              T0.minusDays(100 - 20 * i)));
    }
    // 客户乙：家具（FURNITURE），3 单，间隔 30 天，最后一单在窗口终点前 60 天。
    upstreamSkuCategories.put("SKU-B1", "FURNITURE");
    for (int i = 0; i < 3; i++) {
      upstreamOrders.add(
          order(
              UUID.randomUUID(),
              "SO-B-" + i,
              "AMAZON",
              partyB,
              "买家乙",
              "USD",
              "500.00",
              List.of("SKU-B1"),
              T0.minusDays(120 - 30 * i)));
    }
    // 客户丙：只下一单 ⇒ **没有自己的复购间隔基线**。
    upstreamSkuCategories.put("SKU-C1", "FAST_MOVING");
    upstreamOrders.add(
        order(
            UUID.randomUUID(),
            "SO-C-0",
            "SHOPIFY",
            partyC,
            "买家丙",
            "USD",
            "88.80",
            List.of("SKU-C1"),
            T0.minusDays(5)));
    // 客户丁：一单，时刻带 +08:00 偏移量（= 2026-10-10T00:00Z，距窗口终点 22 天）。
    upstreamSkuCategories.put("SKU-D1", "FAST_MOVING");
    upstreamOrders.add(
        order(
            UUID.randomUUID(),
            "SO-D-0",
            "AMAZON",
            partyD,
            "买家丁",
            "USD",
            "66.60",
            List.of("SKU-D1"),
            partyDOrderAt));
  }

  // ------------------------------------------------------------ 工具

  /**
   * 迁移用户（超级用户）直连。
   *
   * <p>⚠️ 测试里**直接查库**时必须走这条连接：应用自己的连接池里
   * {@code app.current_tenant} 只在请求事务内绑定，事务外查业务表会被
   * FORCE RLS 挡住（一行都看不到）—— 那不是「数据没落库」，是「你没站在租户里看」。
   */
  private static java.sql.Connection admin() throws Exception {
    return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
  }

  private String body(String path) throws Exception {
    return http.perform(
            get(path)
                .header("Authorization", "session")
                .header("X-Tenant-Id", tenant.toString())
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private String postJson(String path, Object payload, int expectedStatus) throws Exception {
    var result =
        http.perform(
                post(path)
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload == null ? "{}" : json.writeValueAsString(payload)))
            .andReturn();
    assertThat(result.getResponse().getStatus())
        .as("POST %s 的状态码（响应：%s）", path, result.getResponse().getContentAsString())
        .isEqualTo(expectedStatus);
    return result.getResponse().getContentAsString();
  }

  private String putJson(String path, Object payload, int expectedStatus) throws Exception {
    var result =
        http.perform(
                put(path)
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload == null ? "{}" : json.writeValueAsString(payload)))
            .andReturn();
    assertThat(result.getResponse().getStatus())
        .as("PUT %s 的状态码（响应：%s）", path, result.getResponse().getContentAsString())
        .isEqualTo(expectedStatus);
    return result.getResponse().getContentAsString();
  }

  /** 用固定窗口重算（窗口终点固定 ⇒ 逾期天数可复现）。 */
  private String compute() throws Exception {
    String result =
        postJson(
            "/crm/customer-lifecycle/compute",
            Map.of("windowFrom", T0.minusDays(730).toString(), "windowTo", T0.toString()),
            200);
    assertThat(json.readTree(result).get("status").asText()).isEqualTo("SUCCEEDED");
    return result;
  }

  /** 造一条阈值策略。 */
  private void createPolicy(
      String scope,
      String categoryCode,
      int expectedDays,
      int toleranceDays,
      OffsetDateTime from,
      OffsetDateTime to) throws Exception {
    Map<String, Object> payload = new HashMap<>();
    payload.put("scope", scope);
    if (categoryCode != null) payload.put("categoryCode", categoryCode);
    payload.put("expectedRepurchaseDays", expectedDays);
    payload.put("toleranceDays", toleranceDays);
    payload.put("rfmWindowDays", 365);
    payload.put("recencyScoreDays", 30);
    payload.put("frequencyMinOrders", 2);
    payload.put("effectiveFrom", from.toString());
    if (to != null) payload.put("effectiveTo", to.toString());
    payload.put("note", "测试用策略");
    postJson("/crm/customer-lifecycle/policies", payload, 200);
  }

  private com.fasterxml.jackson.databind.node.ObjectNode segmentRow(String customerRef)
      throws Exception {
    var rows = json.readTree(body("/crm/customer-lifecycle/segments?size=100")).get("rows");
    for (var row : rows) {
      if (row.get("customerRef").asText().equals(customerRef)) return (com.fasterxml.jackson.databind.node.ObjectNode) row;
    }
    throw new AssertionError("分层列表里找不到客户 " + customerRef);
  }

  private com.fasterxml.jackson.databind.node.ObjectNode alertRow(String customerRef)
      throws Exception {
    var rows = json.readTree(body("/crm/customer-lifecycle/alerts?size=100")).get("rows");
    for (var row : rows) {
      if (row.get("customerRef").asText().equals(customerRef)) return (com.fasterxml.jackson.databind.node.ObjectNode) row;
    }
    throw new AssertionError("预警清单里找不到客户 " + customerRef);
  }

  // ================================================================ 阈值来源

  @Test
  void 没有类目也没有全局策略时阈值来自真实订单历史而不是写死的周数() throws Exception {
    String result = compute();
    var run = json.readTree(result);

    // 租户级基线 = 客户甲（20 天）与客户乙（30 天）的中位数 = 25 天。
    assertThat(run.get("categoryUnresolvedCount").asInt()).isZero();
    assertThat(run.get("customerCount").asInt()).isEqualTo(4);

    var rowA = segmentRow(partyA.toString());
    assertThat(rowA.get("thresholdSource").asText()).isEqualTo("ORDER_HISTORY_BASELINE");
    assertThat(rowA.get("expectedRepurchaseDays").asInt()).isEqualTo(25);
    assertThat(rowA.get("toleranceDays").asInt()).isZero();
    // 距上次购买 40 天 > 25 天 ⇒ 逾期 15 天。
    assertThat(rowA.get("recencyDays").asInt()).isEqualTo(40);
    assertThat(rowA.get("overdueDays").asInt()).isEqualTo(15);
    // 证据：本人历史复购间隔中位数 20 天（3 段间隔）。
    assertThat(rowA.get("customerBaselineDays").asInt()).isEqualTo(20);
    assertThat(rowA.get("customerBaselineSamples").asInt()).isEqualTo(3);
    assertThat(rowA.get("baselineSource").asText()).isEqualTo("ORDER_HISTORY_MEDIAN");
    // 理由里必须**说清**用的是订单历史基线，而不是让人以为按类目生效了。
    assertThat(rowA.get("segmentReason").asText())
        .contains("无类目 / 全局策略")
        .contains("租户真实订单历史的中位复购间隔")
        .contains("25 天");
  }

  @Test
  void 类目策略优先于全局策略且两者都优先于订单历史基线() throws Exception {
    createPolicy("GLOBAL", null, 90, 0, T0.minusDays(400), null);
    createPolicy("CATEGORY", "FAST_MOVING", 14, 7, T0.minusDays(400), null);
    compute();

    // 客户甲在快消类目 ⇒ 命中类目策略（14 + 7 = 21 天阈值）。
    var rowA = segmentRow(partyA.toString());
    assertThat(rowA.get("thresholdSource").asText()).isEqualTo("CATEGORY_POLICY");
    assertThat(rowA.get("expectedRepurchaseDays").asInt()).isEqualTo(14);
    assertThat(rowA.get("toleranceDays").asInt()).isEqualTo(7);
    assertThat(rowA.get("overdueDays").asInt()).isEqualTo(40 - 21);
    assertThat(rowA.get("categoryCode").asText()).isEqualTo("FAST_MOVING");

    // 客户乙在家具类目（没配类目策略）⇒ 命中全局策略（90 天阈值，60 天没买 ⇒ 不超期）。
    var rowB = segmentRow(partyB.toString());
    assertThat(rowB.get("thresholdSource").asText()).isEqualTo("GLOBAL_POLICY");
    assertThat(rowB.get("expectedRepurchaseDays").asInt()).isEqualTo(90);
    assertThat(rowB.get("overdueDays").asInt()).isZero();
    assertThat(rowB.get("segment").asText()).isEqualTo("DORMANT");
  }

  @Test
  void 过期失效的策略不算生效会退到订单历史基线() throws Exception {
    createPolicy("GLOBAL", null, 90, 0, T0.minusDays(400), T0.minusDays(10));
    compute();
    var rowA = segmentRow(partyA.toString());
    assertThat(rowA.get("thresholdSource").asText()).isEqualTo("ORDER_HISTORY_BASELINE");
    assertThat(rowA.get("expectedRepurchaseDays").asInt()).isEqualTo(25);
  }

  @Test
  void 阈值不可就地修改要新建一条策略() throws Exception {
    createPolicy("GLOBAL", null, 90, 0, T0.minusDays(400), null);
    var policies = json.readTree(body("/crm/customer-lifecycle/policies"));
    var id = policies.get("rows").get(0).get("id").asText();

    Map<String, Object> payload = new HashMap<>();
    payload.put("scope", "GLOBAL");
    payload.put("expectedRepurchaseDays", 30);
    payload.put("toleranceDays", 0);
    payload.put("effectiveFrom", T0.minusDays(400).toString());
    http.perform(
            put("/crm/customer-lifecycle/policies/" + id)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload)))
        .andExpect(status().isConflict());

    // 改备注是允许的。
    payload.put("expectedRepurchaseDays", 90);
    payload.put("note", "改成备注");
    putJson("/crm/customer-lifecycle/policies/" + id, payload, 200);
  }

  // ================================================================ 分层与预警

  @Test
  void 本应复购未复购进预警清单且逾期倍数决定召回还是流失() throws Exception {
    compute();
    var rowA = segmentRow(partyA.toString());
    // 逾期 15 天 < 阈值 25 天 ⇒ 还不是流失很久，是「待召回」。
    assertThat(rowA.get("segment").asText()).isEqualTo("RECALL");

    var rowB = segmentRow(partyB.toString());
    // 逾期 35 天 ≥ 阈值 25 天 ⇒ 流失已久。
    assertThat(rowB.get("segment").asText()).isEqualTo("CHURNED");

    var rowC = segmentRow(partyC.toString());
    // 只下一单 ⇒ 新客，且**没有自己的复购间隔基线**（样本不足，不是「基线为 0」）。
    assertThat(rowC.get("segment").asText()).isEqualTo("NEW");
    assertThat(rowC.get("baselineSource").asText()).isEqualTo("INSUFFICIENT_SAMPLE");
    assertThat(rowC.get("customerBaselineSamples").asInt()).isZero();
    assertThat(rowC.get("customerBaselineDays").isNull()).isTrue();

    var alertA = alertRow(partyA.toString());
    assertThat(alertA.get("overdueDays").asInt()).isEqualTo(15);
    assertThat(alertA.get("daysSinceLastOrder").asInt()).isEqualTo(40);
    assertThat(alertA.get("status").asText()).isEqualTo("OPEN");
  }

  @Test
  void 风险分由四个因子构成且加总等于分数() throws Exception {
    compute();
    var alert = alertRow(partyA.toString());
    var factors = alert.get("riskFactors");
    assertThat(factors.size()).isEqualTo(4);
    int sum = 0;
    List<String> codes = new ArrayList<>();
    for (var factor : factors) {
      codes.add(factor.get("code").asText());
      assertThat(factor.get("explanation").asText()).isNotBlank();
      assertThat(factor.get("weight").asInt()).isPositive();
      sum += factor.get("points").asInt();
    }
    assertThat(codes)
        .containsExactlyInAnyOrder("OVERDUE_RATIO", "RECENCY", "FREQUENCY", "VALUE_EXPOSURE");
    assertThat(new BigDecimal(alert.get("riskScore").asText()))
        .isEqualByComparingTo(BigDecimal.valueOf(sum));
    assertThat(alert.get("riskLevel").asText()).isNotBlank();
  }

  @Test
  void 召回只是建议并写明归属模块不触达() throws Exception {
    compute();
    var alert = alertRow(partyA.toString());
    assertThat(alert.get("recallSuggestion").asText())
        .contains("建议")
        .contains("15.5.2")
        .contains("15.6.2.2")
        .contains("不发送任何消息");
    assertThat(alert.get("suggestionOwner").asText()).isEqualTo("15.5.2");
    assertThat(alert.get("suggestionOwnerLabel").asText()).contains("触达");
  }

  @Test
  void 预警处理状态只能向前推进() throws Exception {
    compute();
    var alert = alertRow(partyA.toString());
    String id = alert.get("id").asText();

    var acked = json.readTree(postJson("/crm/customer-lifecycle/alerts/" + id + "/acknowledge", Map.of(), 200));
    assertThat(acked.get("status").asText()).isEqualTo("ACKNOWLEDGED");

    var resolved = json.readTree(postJson("/crm/customer-lifecycle/alerts/" + id + "/resolve", Map.of(), 200));
    assertThat(resolved.get("status").asText()).isEqualTo("RESOLVED");

    // 重算**不会**把已处理的重新打开。
    compute();
    var after = alertRow(partyA.toString());
    assertThat(after.get("status").asText()).isEqualTo("RESOLVED");
  }

  @Test
  void 不再超期的旧预警被标为已处理且分层行被清掉() throws Exception {
    compute();
    assertThat(alertRow(partyA.toString()).get("status").asText()).isEqualTo("OPEN");

    // 把客户甲的订单从上游撤走（模拟他不再出现在扫描窗口里）。
    upstreamOrders.removeIf(o -> o.customerPartyId().equals(partyA));
    compute();

    var alerts = json.readTree(body("/crm/customer-lifecycle/alerts?status=RESOLVED&size=100")).get("rows");
    assertThat(alerts.size()).isEqualTo(1);
    assertThat(alerts.get(0).get("customerRef").asText()).isEqualTo(partyA.toString());
    assertThat(alerts.get(0).get("statusNote").asText()).contains("不再满足预警条件");

    var segments = json.readTree(body("/crm/customer-lifecycle/segments?size=100")).get("rows");
    for (var row : segments) {
      assertThat(row.get("customerRef").asText()).isNotEqualTo(partyA.toString());
    }
  }

  @Test
  void 重复重算幂等不翻倍() throws Exception {
    compute();
    int first = json.readTree(body("/crm/customer-lifecycle/alerts?size=100")).get("total").asInt();
    compute();
    int second = json.readTree(body("/crm/customer-lifecycle/alerts?size=100")).get("total").asInt();
    assertThat(first).isEqualTo(second);
    assertThat(first).isEqualTo(2);
  }

  // ================================================================ 禁浮点与时区

  @Test
  void 金额逐位相同不飘浮点误差() throws Exception {
    compute();
    var rowA = segmentRow(partyA.toString());
    // 0.10 + 0.20 + 0.30 + 0.40 = 1.00（浮点会得到 0.9999999999999999）。
    assertThat(new BigDecimal(rowA.get("monetary").asText())).isEqualByComparingTo(new BigDecimal("1.00"));
    assertThat(rowA.get("currency").asText()).isEqualTo("USD");
  }

  @Test
  void 带偏移量的时刻写回等于同一绝对时刻() throws Exception {
    compute();
    var rowD = segmentRow(partyD.toString());
    assertThat(OffsetDateTime.parse(rowD.get("lastOrderAt").asText()).toInstant())
        .isEqualTo(partyDOrderAt.toInstant());
    // 2026-10-10T00:00Z 距窗口终点 2026-11-01T00:00Z = 22 天。
    assertThat(rowD.get("recencyDays").asInt()).isEqualTo(22);
  }

  // ================================================================ 分页与统计

  @Test
  void 分页是真的且分层统计不受分页影响() throws Exception {
    compute();
    var page0 = json.readTree(body("/crm/customer-lifecycle/segments?page=0&size=2"));
    var page1 = json.readTree(body("/crm/customer-lifecycle/segments?page=1&size=2"));
    assertThat(page0.get("rows").size()).isEqualTo(2);
    assertThat(page1.get("rows").size()).isEqualTo(2);
    assertThat(page0.get("total").asInt()).isEqualTo(4);
    assertThat(page0.get("totalPages").asInt()).isEqualTo(2);

    var refs0 = new ArrayList<String>();
    page0.get("rows").forEach(r -> refs0.add(r.get("customerRef").asText()));
    var refs1 = new ArrayList<String>();
    page1.get("rows").forEach(r -> refs1.add(r.get("customerRef").asText()));
    assertThat(refs0).doesNotContainAnyElementsOf(refs1);

    // 统计来自独立查询 ⇒ 两页的分布一样。
    assertThat(page0.get("stats").get("totalCustomers").asInt())
        .isEqualTo(page1.get("stats").get("totalCustomers").asInt())
        .isEqualTo(4);
  }

  // ================================================================ 上游不可用

  @Test
  void 上游订单读不到就明确失败不落任何分层结果() throws Exception {
    tradeDown = true;
    // ⚠️ Spring Boot 3 默认 `server.error.include-message=never` ⇒ 响应体是空的，
    //   「明确失败」的判据是**状态码 503**（而不是 200 + 空列表）。
    http.perform(
            post("/crm/customer-lifecycle/compute")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isServiceUnavailable());

    // 一条分层结果都没落（用迁移用户直连查，绕开 RLS）。
    try (var c = admin();
        var s = c.prepareStatement("select count(*) from crm_customer_lifecycle")) {
      try (var rs = s.executeQuery()) {
        rs.next();
        assertThat(rs.getLong(1)).isZero();
      }
    }
  }

  @Test
  void catalog读不到就明确失败而不是当成没有类目() throws Exception {
    catalogDown = true;
    http.perform(
            post("/crm/customer-lifecycle/compute")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void sku在catalog里查不到时如实标注为未解析而不是当作没有类目() throws Exception {
    upstreamSkuCategories.remove("SKU-A1");
    compute();
    var rowA = segmentRow(partyA.toString());
    assertThat(rowA.get("categoryCode").isNull()).isTrue();
    assertThat(rowA.get("categorySource").asText()).isEqualTo("UNRESOLVED");
    // 类目没解析出来 ⇒ 退到全局/订单历史基线，并如实说明。
    assertThat(rowA.get("thresholdSource").asText()).isEqualTo("ORDER_HISTORY_BASELINE");
  }

  // ================================================================ DB 层不变量

  @Test
  void db层拒绝没有分层理由的分层行() throws Exception {
    try (var c = admin();
        var s = c.prepareStatement(
            "insert into crm_customer_lifecycle"
                + "(id,tenant_id,customer_ref,source_shop,order_count,recency_days,frequency,"
                + " r_score,f_score,m_score,segment,segment_reason,threshold_source,baseline_source,"
                + " overdue_days,computed_at)"
                + " values (?,?,?,'AMAZON',1,1,1,5,1,1,'NEW','','NONE','INSUFFICIENT_SAMPLE',0,"
                + " ?::timestamptz)")) {
      s.setObject(1, UUID.randomUUID());
      s.setObject(2, tenant);
      s.setObject(3, "NO-REASON");
      s.setObject(4, OffsetDateTime.now(ZoneOffset.UTC));
      assertThatThrownBy(s::executeUpdate).hasMessageContaining("必须写明分层理由");
    }
  }

  @Test
  void db层拒绝没有因子明细的风险分() throws Exception {
    try (var c = admin();
        var s = c.prepareStatement(
            "insert into crm_churn_alert"
                + "(id,tenant_id,customer_ref,source_shop,days_since_last_order,"
                + " expected_repurchase_days,tolerance_days,overdue_days,threshold_source,"
                + " risk_score,risk_level,risk_factors,recall_suggestion,suggestion_owner,detected_at)"
                + " values (?,?,?,'AMAZON',10,7,0,3,'GLOBAL_POLICY',10.00,'LOW','[]'::jsonb,"
                + " '建议','15.5.2',?::timestamptz)")) {
      s.setObject(1, UUID.randomUUID());
      s.setObject(2, tenant);
      s.setObject(3, "NO-FACTORS");
      s.setObject(4, OffsetDateTime.now(ZoneOffset.UTC));
      assertThatThrownBy(s::executeUpdate).hasMessageContaining("风险分必须带因子明细");
    }
  }

  @Test
  void db层拒绝把已处理的预警退回待处理() throws Exception {
    compute();
    var alert = alertRow(partyA.toString());
    String id = alert.get("id").asText();
    postJson("/crm/customer-lifecycle/alerts/" + id + "/resolve", Map.of(), 200);
    try (var c = admin();
        var s = c.prepareStatement(
            "update crm_churn_alert set status = 'OPEN' where tenant_id = ? and id = ?")) {
      s.setObject(1, tenant);
      s.setObject(2, UUID.fromString(id));
      assertThatThrownBy(s::executeUpdate).hasMessageContaining("只能向前推进");
    }
  }

  // ================================================================ 能力声明

  @Test
  void 能力声明写清阈值口径与触达未交付() throws Exception {
    var capabilities = json.readTree(body("/crm/customer-lifecycle/capabilities"));
    String raw = capabilities.toString();
    assertThat(raw).contains("复购周期阈值是租户数据");
    assertThat(raw).contains("代码里**没有任何「默认 30 天」**");
    assertThat(raw).contains("touch_execution");
    assertThat(raw).contains("15.5.2 触达未交付");
    assertThat(raw).contains("15.6.2.2 购物车与弃单挽回");
  }
}
