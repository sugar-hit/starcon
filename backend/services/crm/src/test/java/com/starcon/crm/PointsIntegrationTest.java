package com.starcon.crm;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starcon.crm.PointsOrderFactsPort.OrderFact;
import com.starcon.crm.PointsOrderFactsPort.OrderPage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * 15.6.1.2 积分与权益的集成测试。
 *
 * <p>跑的是 AGENTS.md 要求的<b>真实 PostgreSQL 16 + FORCE RLS</b>。
 * 租户隔离三断言在 {@link PointsTenancyIntegrationTest} 里，本类专攻业务不变量：
 *
 * <ul>
 *   <li><b>积分比率是租户数据</b>：拿多少分、抵多少比例、单积分值多少，
 *       全部来自租户配的规则，代码里没有写死的比率。</li>
 *   <li><b>扣减可审阅</b>：流水行带「扣前余额 → 扣后余额」；
 *       并发扣减时余额不足的那一方<b>被拒</b>（不是两个都扣成功）。</li>
 *   <li><b>有效期按绝对时刻</b>：带 +08:00 偏移量的时刻写回等于同一个绝对时刻；
 *       到期结算把余额与批次同步清零并留一条 EXPIRE 流水。</li>
 *   <li><b>积分只发一次</b>：同一笔订单 / 同一条评价只允许发一次积分。</li>
 *   <li><b>权益核销单向</b>：REDEEMED → USED，二次核销被拒。</li>
 *   <li><b>上游不造数据</b>：trade 读不到就明确失败（503）。</li>
 *   <li><b>负债如实标注</b>：{@code bookedInFinance} 恒为 false。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class PointsIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID userId = UUID.randomUUID();
  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          // ⚠️ 默认会把 JSON 数字读成 double —— 那样断言本身就先把值舍入了，
          // 「没掉精度」这件事就再也证不出来。这里强制读成 BigDecimal。
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap();

  /** 判定的参考时刻。固定值 ⇒ 断言可复现。 */
  static final OffsetDateTime T0 = OffsetDateTime.parse("2026-11-01T00:00:00Z");

  /** 带 +08:00 偏移量的时刻，用来钉死时区不静默偏移。 */
  static final OffsetDateTime T_PLUS8 = OffsetDateTime.parse("2026-11-01T08:00:00+08:00");

  /** 会员甲：AMAZON 店，USD。 */
  static final UUID partyA = UUID.randomUUID();

  /** 会员乙：SHOPIFY 店，EUR（用来钉死「不跨币种」）。 */
  static final UUID partyB = UUID.randomUUID();

  /** 会员丙：EBAY 店（用来钉死「订单必须属于该会员」）。 */
  static final UUID partyC = UUID.randomUUID();

  static final UUID orderA = UUID.randomUUID();
  static final UUID orderB = UUID.randomUUID();
  static final UUID orderC = UUID.randomUUID();

  static final String MEMBER_A = "AMAZON CUST-A";
  static final String MEMBER_B = "SHOPIFY CUST-B";
  static final String MEMBER_C = "EBAY CUST-C";

  static final List<OrderFact> upstreamOrders = new CopyOnWriteArrayList<>();

  /** 上游读不到时的开关（用来验证「明确失败」而不是空集合）。 */
  static volatile boolean tradeDown = false;

  static HttpServer identity;
  static HttpServer trade;

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
    List<String> permissions = List.of("crm.points.read", "crm.points.update");
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
        at,
        at);
  }

  @BeforeEach
  void reset() throws Exception {
    upstreamOrders.clear();
    tradeDown = false;
    try (var c = admin();
        var s = c.createStatement()) {
      s.execute(
          "truncate table crm_points_rule, crm_points_account, crm_points_lot,"
              + " crm_points_ledger, crm_points_entitlement, crm_points_redemption cascade");
      s.execute("truncate table crm_membership_profile cascade");
      s.execute("truncate table crm_product_review cascade");
    }

    // 上游订单：甲（AMAZON / USD）、乙（SHOPIFY / EUR）、丙（EBAY / USD）。
    upstreamOrders.add(order(orderA, "SO-A-1", "AMAZON", partyA, "买家甲", "USD", "100.00", T0));
    upstreamOrders.add(order(orderB, "SO-B-1", "SHOPIFY", partyB, "买家乙", "EUR", "50.00", T0));
    upstreamOrders.add(order(orderC, "SO-C-1", "EBAY", partyC, "买家丙", "USD", "10.00", T0));

    // 会员档案（15.6.1.1 的表，本模块只读它）。
    insertMember(MEMBER_A, "CUST-A", "AMAZON", partyA, "买家甲");
    insertMember(MEMBER_B, "CUST-B", "SHOPIFY", partyB, "买家乙");
    insertMember(MEMBER_C, "CUST-C", "EBAY", partyC, "买家丙");
  }

  // ------------------------------------------------------------ 工具

  /**
   * 迁移用户（超级用户）直连。
   *
   * <p>⚠️ 测试里**直接查库**时必须走这条连接：应用自己的连接池里
   * {@code app.current_tenant} 只在请求事务内绑定，事务外查业务表会被
   * FORCE RLS 挡住（一行都看不到）—— 那不是「数据没落库」，是「你没站在租户里看」。
   */
  private static Connection admin() throws Exception {
    return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
  }

  private void insertMember(
      String memberKey, String customerRef, String shop, UUID party, String name) throws Exception {
    try (var c = admin();
        var s = c.createStatement()) {
      s.execute(
          "insert into crm_membership_profile"
              + "(id,tenant_id,member_key,customer_ref,source_shop,customer_party_id,customer_name,"
              + " member_count,source_shops,members,order_count,total_spend,spend_currency,amounts,"
              + " repurchase_rate,first_order_at,last_order_at,level_code,level_rank,level_origin,"
              + " level_reason,level_evaluated_at,registered_at,created_at,updated_at)"
              + " values ('"
              + UUID.randomUUID()
              + "','"
              + tenant
              + "','"
              + memberKey
              + "','"
              + customerRef
              + "','"
              + shop
              + "','"
              + party
              + "','"
              + name
              + "',1,'[\""
              + shop
              + "\"]'::jsonb,'[]'::jsonb,1,100.00,'USD',"
              + "'[{\"currency\":\"USD\",\"orderCount\":1,\"totalSpend\":100.00}]'::jsonb,"
              + " 0.000000,'"
              + T0
              + "','"
              + T0
              + "','NONE',0,'RULE','测试用判定理由','"
              + T0
              + "','"
              + T0
              + "',now(),now())");
    }
  }

  private String body(String path) throws Exception {
    return http.perform(
            get(path)
                .header("Authorization", "session")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * 带查询参数的读。
   *
   * <p>🔴 **用 {@code queryParam} 而不是把参数拼进 URL 字符串**：会员主键里有空格
   * （{@code AMAZON CUST-A}），手工拼成 {@code %20} 会被 MockMvc 的模板编码
   * 变成 {@code %2520}，后端拿到的是「AMAZON%20CUST-A」⇒ 筛不出东西，
   * 而断言还会显示「列表是空的」—— 症状离病因很远。
   */
  private String bodyWithParams(String path, String... keyValues) throws Exception {
    var request = get(path);
    for (int i = 0; i + 1 < keyValues.length; i += 2) {
      request = request.queryParam(keyValues[i], keyValues[i + 1]);
    }
    return http.perform(request.header("Authorization", "session").accept(MediaType.APPLICATION_JSON))
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

  private String postStatus(String path, Object payload) throws Exception {
    var result =
        http.perform(
                post(path)
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload == null ? "{}" : json.writeValueAsString(payload)))
            .andReturn();
    return String.valueOf(result.getResponse().getStatus());
  }

  // ------------------------------------------------------------ 规则夹具

  /** 建一条获取规则（消费）。 */
  private void earnPurchaseRule(String code, String perUnit, Integer validMonths) throws Exception {
    earnPurchaseRule(code, perUnit, validMonths, "2026-01-01T00:00:00Z");
  }

  /**
   * 建一条获取规则（消费）。
   *
   * @param effectiveFrom 生效起点 —— 🔴 规则按**事件发生时刻**取生效中的那一条，
   *     所以「事件发生在过去」的用例必须把规则也往前挪，否则规则当时还没生效。
   */
  private void earnPurchaseRule(
      String code, String perUnit, Integer validMonths, String effectiveFrom) throws Exception {
    Map<String, Object> payload = new HashMap<>();
    payload.put("ruleCode", code);
    payload.put("ruleName", "消费获取规则");
    payload.put("ruleType", "EARN");
    payload.put("earnSource", "PURCHASE");
    payload.put("pointsPerUnit", new BigDecimal(perUnit));
    if (validMonths != null) payload.put("validMonths", validMonths);
    payload.put("effectiveFrom", effectiveFrom);
    postJson("/crm/points/rules", payload, 200);
  }

  /** 建一条获取规则（固定积分）。 */
  private void earnFixedRule(String code, String source, String points, Integer validMonths)
      throws Exception {
    Map<String, Object> payload = new HashMap<>();
    payload.put("ruleCode", code);
    payload.put("ruleName", "固定积分规则");
    payload.put("ruleType", "EARN");
    payload.put("earnSource", source);
    payload.put("fixedPoints", new BigDecimal(points));
    if (validMonths != null) payload.put("validMonths", validMonths);
    payload.put("effectiveFrom", "2026-01-01T00:00:00Z");
    postJson("/crm/points/rules", payload, 200);
  }

  /** 建一条消耗规则（抵扣率上限）。 */
  private void spendRule(String code, String rate) throws Exception {
    Map<String, Object> payload = new HashMap<>();
    payload.put("ruleCode", code);
    payload.put("ruleName", "抵扣规则");
    payload.put("ruleType", "SPEND");
    payload.put("maxDeductRate", new BigDecimal(rate));
    payload.put("effectiveFrom", "2026-01-01T00:00:00Z");
    postJson("/crm/points/rules", payload, 200);
  }

  /** 建一条估值规则（单积分价值 + 负债币种）。 */
  private void valuationRule(String code, String rate, String currency) throws Exception {
    Map<String, Object> payload = new HashMap<>();
    payload.put("ruleCode", code);
    payload.put("ruleName", "估值规则");
    payload.put("ruleType", "VALUATION");
    payload.put("pointValueRate", new BigDecimal(rate));
    payload.put("liabilityCurrency", currency);
    payload.put("effectiveFrom", "2026-01-01T00:00:00Z");
    postJson("/crm/points/rules", payload, 200);
  }

  /** 建一项权益（券：带面额与币种）。 */
  private void entitlement(String code, String name, String cost, String type) throws Exception {
    Map<String, Object> payload = new HashMap<>();
    payload.put("entitlementCode", code);
    payload.put("entitlementName", name);
    payload.put("pointCost", new BigDecimal(cost));
    payload.put("benefitType", type);
    if (!"FREE_SHIPPING".equals(type)) {
      payload.put("benefitValue", new BigDecimal("10"));
      payload.put("benefitCurrency", "USD");
    }
    payload.put("effectiveFrom", "2026-01-01T00:00:00Z");
    postJson("/crm/points/entitlements", payload, 200);
  }

  /** 会员甲的账户余额（直查库，绕过 RLS 看真实落库值）。 */
  private BigDecimal balanceOf(String accountKey) throws Exception {
    BigDecimal balance = balanceOrNull(accountKey);
    assertThat(balance).as("积分账户 %s 必须真的落库了", accountKey).isNotNull();
    return balance;
  }

  /** 会员甲的账户余额；账户不存在时返回 null（**不**断言存在）。 */
  private BigDecimal balanceOrNull(String accountKey) throws Exception {
    try (var c = admin();
        var s = c.prepareStatement("select balance from crm_points_account where account_key = ?")) {
      s.setString(1, accountKey);
      try (var rs = s.executeQuery()) {
        return rs.next() ? rs.getBigDecimal(1) : null;
      }
    }
  }

  // ==========================================================================
  // 积分获取
  // ==========================================================================

  @Test
  void 消费获取积分_余额与流水可审阅() throws Exception {
    earnPurchaseRule("EARN-PURCHASE", "1.0", 12);
    valuationRule("VALUATION", "0.01", "USD");

    String result =
        postJson(
            "/crm/points/earn",
            Map.of(
                "memberKey", MEMBER_A,
                "earnSource", "PURCHASE",
                "referenceId", orderA.toString(),
                "occurredAt", T0.toString()),
            200);
    var node = json.readTree(result);
    assertThat(node.get("points").asText()).isEqualTo("100");
    assertThat(node.get("balanceBefore").asText()).isEqualTo("0");
    assertThat(node.get("balanceAfter").asText()).isEqualTo("100");
    // 🔴 十进制走文本出参：不是 JSON number（300.0000 走 number 会被解析成 3E+2）。
    assertThat(node.get("points").isNumber()).isFalse();
    assertThat(node.get("points").isTextual()).isTrue();

    // 库里真的落库了。
    assertThat(balanceOf(MEMBER_A)).isEqualByComparingTo("100.000000");

    // 🔴 **扣前余额 → 扣后余额**在一行流水里看得见。
    try (var c = admin();
        var s = c.prepareStatement(
            "select direction, amount, balance_before, balance_after, reason_code, reference_type,"
                + " reference_id from crm_points_ledger order by occurred_at desc")) {
      try (var rs = s.executeQuery()) {
        assertThat(rs.next()).as("积分流水必须真的落库了").isTrue();
        assertThat(rs.getString("direction")).isEqualTo("EARN");
        assertThat(rs.getBigDecimal("amount")).isEqualByComparingTo("100.000000");
        assertThat(rs.getBigDecimal("balance_before")).isEqualByComparingTo("0.000000");
        assertThat(rs.getBigDecimal("balance_after")).isEqualByComparingTo("100.000000");
        assertThat(rs.getString("reason_code")).isEqualTo("PURCHASE");
        assertThat(rs.getString("reference_type")).isEqualTo("ORDER");
        assertThat(rs.getString("reference_id")).isEqualTo(orderA.toString());
        assertThat(rs.next()).as("一次发放只应有一条流水").isFalse();
      }
    }
  }

  @Test
  void 有效期按绝对时刻判定_带偏移量的时刻写回等于同一绝对时刻() throws Exception {
    earnPurchaseRule("EARN-PURCHASE", "1.0", 12);

    String result =
        postJson(
            "/crm/points/earn",
            Map.of(
                "memberKey", MEMBER_A,
                "earnSource", "PURCHASE",
                "referenceId", orderA.toString(),
                "occurredAt", T_PLUS8.toString()),
            200);
    // 2026-11-01T08:00:00+08:00 + 12 个月 = 2027-11-01T08:00:00+08:00 = 2027-11-01T00:00:00Z。
    assertThat(json.readTree(result).get("expiresAt").asText()).isEqualTo("2027-11-01T00:00:00Z");

    // 🔴 断言**写回的值**（不是只断言没抛异常）：库里那一列必须是同一个绝对时刻。
    try (var c = admin();
        var s = c.prepareStatement(
            "select expires_at from crm_points_lot where account_key = ?")) {
      s.setString(1, MEMBER_A);
      try (var rs = s.executeQuery()) {
        assertThat(rs.next()).as("积分批次必须真的落库了").isTrue();
        OffsetDateTime written = rs.getObject("expires_at", OffsetDateTime.class);
        assertThat(written.toInstant())
            .as("有效期必须是同一个绝对时刻（东八区偏移会静默偏 −28800 秒）")
            .isEqualTo(T_PLUS8.plusMonths(12).toInstant());
      }
    }
  }

  @Test
  void 同一笔订单只允许发一次积分() throws Exception {
    earnPurchaseRule("EARN-PURCHASE", "1.0", 12);
    Map<String, Object> payload =
        Map.of(
            "memberKey", MEMBER_A,
            "earnSource", "PURCHASE",
            "referenceId", orderA.toString(),
            "occurredAt", T0.toString());
    postJson("/crm/points/earn", payload, 200);

    // 🔴 第二次必须被拒 —— 否则同一笔消费能拿到两份积分。
    String status = postStatus("/crm/points/earn", payload);
    assertThat(status).isEqualTo("409");
    assertThat(balanceOf(MEMBER_A)).isEqualByComparingTo("100.000000");
  }

  @Test
  void 签到获取积分() throws Exception {
    earnFixedRule("EARN-CHECKIN", "CHECKIN", "5", null);
    String result =
        postJson(
            "/crm/points/earn",
            Map.of("memberKey", MEMBER_A, "earnSource", "CHECKIN", "occurredAt", T0.toString()),
            200);
    assertThat(json.readTree(result).get("points").asText()).isEqualTo("5");
    assertThat(json.readTree(result).get("expiresAt").isNull()).isTrue();
  }

  @Test
  void 评价获取积分_评价必须真实存在() throws Exception {
    earnFixedRule("EARN-REVIEW", "REVIEW", "20", 6);
    // 没有评价 ⇒ 404（评价获取的依据是**真实评价**）。
    assertThat(
            postStatus(
                "/crm/points/earn",
                Map.of(
                    "memberKey", MEMBER_A,
                    "earnSource", "REVIEW",
                    "referenceId",
                    "PR-NOT-EXIST")))
        .isEqualTo("404");

    // 造一条真实评价。
    try (var c = admin();
        var s = c.createStatement()) {
      s.execute(
          "insert into crm_product_review"
              + "(id,tenant_id,review_no,shop_id,shop_name,platform_code,rating,buyer_ref,buyer_name,"
              + "sales_order_no,platform_reviewed_at,registered_at,created_at,updated_at)"
              + " values ('"
              + UUID.randomUUID()
              + "','"
              + tenant
              + "','PR-20261101-000001','"
              + UUID.randomUUID()
              + "','AMAZON 店','AMAZON',5,'CUST-A','买家甲','SO-A-1','"
              + T0
              + "','"
              + T0
              + "',now(),now())");
    }
    String result =
        postJson(
            "/crm/points/earn",
            Map.of(
                "memberKey", MEMBER_A,
                "earnSource", "REVIEW",
                "referenceId",
                "PR-20261101-000001",
                "occurredAt",
                T0.toString()),
            200);
    assertThat(json.readTree(result).get("points").asText()).isEqualTo("20");
  }

  @Test
  void 没有生效规则时不发积分_代码里没有写死的比率() throws Exception {
    // 没有任何规则 ⇒ 409（**不是**按某个默认比率发）。
    assertThat(
            postStatus(
                "/crm/points/earn",
                Map.of("memberKey", MEMBER_A, "earnSource", "CHECKIN", "occurredAt", T0.toString())))
        .isEqualTo("409");
  }

  @Test
  void 积分只能记在真实会员名下() throws Exception {
    earnFixedRule("EARN-CHECKIN", "CHECKIN", "5", null);
    assertThat(
            postStatus(
                "/crm/points/earn",
                Map.of(
                    "memberKey", "AMAZON 不存在的客户",
                    "earnSource", "CHECKIN",
                    "occurredAt",
                    T0.toString())))
        .isEqualTo("404");
  }

  @Test
  void 订单必须属于该会员() throws Exception {
    earnPurchaseRule("EARN-PURCHASE", "1.0", 12);
    // 丙的订单不能记到甲名下。
    assertThat(
            postStatus(
                "/crm/points/earn",
                Map.of(
                    "memberKey", MEMBER_A,
                    "earnSource", "PURCHASE",
                    "referenceId", orderC.toString(),
                    "occurredAt", T0.toString())))
        .isEqualTo("400");
  }

  @Test
  void 上游读不到就明确失败而不是空集合() throws Exception {
    earnPurchaseRule("EARN-PURCHASE", "1.0", 12);
    tradeDown = true;
    String status =
        postStatus(
            "/crm/points/earn",
            Map.of(
                "memberKey", MEMBER_A,
                "earnSource", "PURCHASE",
                "referenceId", orderA.toString(),
                "occurredAt", T0.toString()));
    assertThat(status).isEqualTo("503");
    tradeDown = false;
    // 一条积分都不落（连账户都不建 —— 读不到上游就**不许**建出半个状态）。
    assertThat(balanceOrNull(MEMBER_A))
        .as("上游读不到时**一条积分都不落**（连积分账户都不该建）")
        .isNull();
  }

  // ==========================================================================
  // 积分消耗（抵扣）
  // ==========================================================================

  @Test
  void 抵扣消耗_扣前扣后余额看得见且上限被强制() throws Exception {
    earnPurchaseRule("EARN-PURCHASE", "1.0", 12);
    spendRule("SPEND-DEDUCT", "0.05");
    valuationRule("VALUATION", "0.01", "USD");
    postJson(
        "/crm/points/earn",
        Map.of(
            "memberKey", MEMBER_A,
            "earnSource", "PURCHASE",
            "referenceId", orderA.toString(),
            "occurredAt", T0.toString()),
        200);

    // 抵扣上限 = 100.00 USD × 0.05 ÷ 0.01 = 500 分；余额只有 100 分 ⇒ 只能抵 100 分。
    String ok =
        postJson(
            "/crm/points/spend",
            Map.of("memberKey", MEMBER_A, "orderId", orderA.toString(), "occurredAt", T0.toString()),
            200);
    var node = json.readTree(ok);
    assertThat(node.get("points").asText()).isEqualTo("100");
    assertThat(node.get("balanceBefore").asText()).isEqualTo("100");
    assertThat(node.get("balanceAfter").asText()).isEqualTo("0");
    assertThat(node.get("maxDeductPoints").asText()).isEqualTo("500");
    assertThat(balanceOf(MEMBER_A)).isEqualByComparingTo("0.000000");

    // 请求超过抵扣上限 ⇒ 409（并给出两个数）。
    earnFixedRule("EARN-CHECKIN", "CHECKIN", "1000", null);
    postJson(
        "/crm/points/earn",
        Map.of("memberKey", MEMBER_A, "earnSource", "CHECKIN", "occurredAt", T0.toString()),
        200);
    assertThat(
            postStatus(
                "/crm/points/spend",
                Map.of(
                    "memberKey", MEMBER_A,
                    "orderId", orderA.toString(),
                    "points", new BigDecimal("600"))))
        .isEqualTo("409");
  }

  @Test
  void 余额不足被拒_并发扣减只有一个成功() throws Exception {
    earnPurchaseRule("EARN-PURCHASE", "1.0", 12);
    spendRule("SPEND-DEDUCT", "0.5");
    valuationRule("VALUATION", "0.01", "USD");
    postJson(
        "/crm/points/earn",
        Map.of(
            "memberKey", MEMBER_A,
            "earnSource", "PURCHASE",
            "referenceId", orderA.toString(),
            "occurredAt", T0.toString()),
        200);

    // 🔴 **并发扣减**：两个请求同时扣 100 分（余额只有 100 分）。
    //   DB 层的保证是「条件更新 + 行锁」⇒ 只有一个能扣成功。
    int threads = 2;
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    AtomicInteger ok = new AtomicInteger();
    AtomicInteger rejected = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    for (int i = 0; i < threads; i++) {
      pool.submit(
          () -> {
            try {
              start.await();
              String status =
                  postStatus(
                      "/crm/points/spend",
                      Map.of(
                          "memberKey", MEMBER_A,
                          "orderId", orderA.toString(),
                          "points", new BigDecimal("100")));
              if ("200".equals(status)) ok.incrementAndGet();
              else rejected.incrementAndGet();
            } catch (Exception e) {
              rejected.incrementAndGet();
            } finally {
              done.countDown();
            }
          });
    }
    start.countDown();
    assertThat(done.await(30, TimeUnit.SECONDS)).as("并发用例必须在 30 秒内跑完").isTrue();
    pool.shutdownNow();

    assertThat(ok.get()).as("并发扣减时**只有一个**请求能扣成功").isEqualTo(1);
    assertThat(rejected.get()).as("另一个必须被拒").isEqualTo(1);
    assertThat(balanceOf(MEMBER_A)).isEqualByComparingTo("0.000000");

    // 流水里只有**一条** SPEND（没有两条把余额扣成负数）。
    try (var c = admin();
        var s = c.prepareStatement(
            "select count(*) from crm_points_ledger where direction = 'SPEND'")) {
      try (var rs = s.executeQuery()) {
        rs.next();
        assertThat(rs.getLong(1)).as("并发扣减只能留下一条消耗流水").isEqualTo(1);
      }
    }
  }

  @Test
  void 跨币种不折算_币种不一致直接拒绝() throws Exception {
    earnPurchaseRule("EARN-PURCHASE", "1.0", 12);
    spendRule("SPEND-DEDUCT", "0.05");
    valuationRule("VALUATION", "0.01", "USD");
    earnFixedRule("EARN-CHECKIN", "CHECKIN", "100", null);
    postJson(
        "/crm/points/earn",
        Map.of("memberKey", MEMBER_B, "earnSource", "CHECKIN", "occurredAt", T0.toString()),
        200);

    // 乙的订单是 EUR，估值币种是 USD ⇒ 400（不偷偷按一个汇率折算）。
    assertThat(
            postStatus(
                "/crm/points/spend",
                Map.of("memberKey", MEMBER_B, "orderId", orderB.toString())))
        .isEqualTo("400");
  }

  // ==========================================================================
  // 权益兑换与核销
  // ==========================================================================

  @Test
  void 权益兑换与核销_状态单向() throws Exception {
    earnFixedRule("EARN-CHECKIN", "CHECKIN", "100", null);
    entitlement("GIFT-10", "10 元券", "30", "COUPON");
    postJson(
        "/crm/points/earn",
        Map.of("memberKey", MEMBER_A, "earnSource", "CHECKIN", "occurredAt", T0.toString()),
        200);

    String redeemed =
        postJson(
            "/crm/points/redeem",
            Map.of("memberKey", MEMBER_A, "entitlementCode", "GIFT-10", "occurredAt", T0.toString()),
            200);
    var node = json.readTree(redeemed);
    assertThat(node.get("pointCost").asText()).isEqualTo("30");
    assertThat(node.get("balanceBefore").asText()).isEqualTo("100");
    assertThat(node.get("balanceAfter").asText()).isEqualTo("70");
    String redemptionId = node.get("redemptionId").asText();
    assertThat(node.get("redemptionNo").asText()).startsWith("PR-");

    // 核销记录状态：已兑换待核销。
    String list = bodyWithParams("/crm/points/redemptions", "memberKey", MEMBER_A);
    assertThat(list).contains("REDEEMED");

    // 核销 ⇒ USED。
    postJson("/crm/points/redemptions/" + redemptionId + "/use", Map.of("orderRef", "SO-A-1"), 200);
    String after = bodyWithParams("/crm/points/redemptions", "memberKey", MEMBER_A);
    assertThat(after).contains("USED");

    // 🔴 二次核销被拒（核销过的事实不能撤回）。
    assertThat(postStatus("/crm/points/redemptions/" + redemptionId + "/use", Map.of())).isEqualTo("409");
  }

  @Test
  void 权益兑换的余额与流水对得上() throws Exception {
    earnFixedRule("EARN-CHECKIN", "CHECKIN", "100", null);
    entitlement("GIFT-10", "10 元券", "30", "COUPON");
    postJson(
        "/crm/points/earn",
        Map.of("memberKey", MEMBER_A, "earnSource", "CHECKIN", "occurredAt", T0.toString()),
        200);
    postJson(
        "/crm/points/redeem",
        Map.of("memberKey", MEMBER_A, "entitlementCode", "GIFT-10", "occurredAt", T0.toString()),
        200);
    assertThat(balanceOf(MEMBER_A)).isEqualByComparingTo("70.000000");
    try (var c = admin();
        var s = c.prepareStatement(
            "select amount, balance_before, balance_after, reason_code from crm_points_ledger"
                + " where direction = 'SPEND'")) {
      try (var rs = s.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getBigDecimal("amount")).isEqualByComparingTo("30.000000");
        assertThat(rs.getBigDecimal("balance_before")).isEqualByComparingTo("100.000000");
        assertThat(rs.getBigDecimal("balance_after")).isEqualByComparingTo("70.000000");
        assertThat(rs.getString("reason_code")).isEqualTo("REDEEM");
      }
    }
  }

  // ==========================================================================
  // 到期结算
  // ==========================================================================

  @Test
  void 到期结算把余额与批次同步清零并留一条过期流水() throws Exception {
    // 🔴 规则与事件都在 2020 年：规则按**事件发生时刻**取生效中的那一条，
    //   事件发生在过去 ⇒ 规则必须当时就生效；validMonths=1 ⇒ 这批积分早已到期。
    earnPurchaseRule("EARN-PURCHASE", "1.0", 1, "2020-01-01T00:00:00Z");
    postJson(
        "/crm/points/earn",
        Map.of(
            "memberKey", MEMBER_A,
            "earnSource", "PURCHASE",
            "referenceId", orderA.toString(),
            "occurredAt", "2020-02-01T00:00:00Z"),
        200);
    assertThat(balanceOf(MEMBER_A)).isEqualByComparingTo("100.000000");

    String result = postJson("/crm/points/expire", null, 200);
    assertThat(json.readTree(result).get("expiredLotCount").asInt()).isEqualTo(1);
    assertThat(json.readTree(result).get("expiredPoints").asText()).isEqualTo("100");

    // 余额与批次**同步**归零。
    assertThat(balanceOf(MEMBER_A)).isEqualByComparingTo("0.000000");
    try (var c = admin();
        var s = c.prepareStatement(
            "select balance from crm_points_lot where account_key = ?")) {
      s.setString(1, MEMBER_A);
      try (var rs = s.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("0.000000");
      }
    }
    // 留一条 EXPIRE 流水，带扣前 → 扣后余额。
    try (var c = admin();
        var s = c.prepareStatement(
            "select amount, balance_before, balance_after from crm_points_ledger"
                + " where direction = 'EXPIRE'")) {
      try (var rs = s.executeQuery()) {
        assertThat(rs.next()).as("到期必须留一条 EXPIRE 流水").isTrue();
        assertThat(rs.getBigDecimal("amount")).isEqualByComparingTo("100.000000");
        assertThat(rs.getBigDecimal("balance_before")).isEqualByComparingTo("100.000000");
        assertThat(rs.getBigDecimal("balance_after")).isEqualByComparingTo("0.000000");
      }
    }
  }

  @Test
  void 未到期的积分不被结算() throws Exception {
    earnPurchaseRule("EARN-PURCHASE", "1.0", 12);
    postJson(
        "/crm/points/earn",
        Map.of(
            "memberKey", MEMBER_A,
            "earnSource", "PURCHASE",
            "referenceId", orderA.toString(),
            "occurredAt", T0.toString()),
        200);
    String result = postJson("/crm/points/expire", null, 200);
    assertThat(json.readTree(result).get("expiredLotCount").asInt()).isZero();
    assertThat(balanceOf(MEMBER_A)).isEqualByComparingTo("100.000000");
  }

  // ==========================================================================
  // 积分负债汇总
  // ==========================================================================

  @Test
  void 负债汇总如实标注_未在财务侧入账() throws Exception {
    earnFixedRule("EARN-CHECKIN", "CHECKIN", "100", null);
    valuationRule("VALUATION", "0.01", "USD");
    postJson(
        "/crm/points/earn",
        Map.of("memberKey", MEMBER_A, "earnSource", "CHECKIN", "occurredAt", T0.toString()),
        200);

    String result = body("/crm/points/liability");
    var node = json.readTree(result);
    assertThat(node.get("outstandingPoints").asText()).isEqualTo("100");
    assertThat(node.get("liabilityAmount").asText()).isEqualTo("1.00");
    assertThat(node.get("liabilityCurrency").asText()).isEqualTo("USD");
    // 🔴 蓝图加粗句：「未兑付积分是负债，需在财务侧有对应科目或备忘，不能只记在业务库」。
    //   本模块**只输出**汇总 ⇒ bookedInFinance 恒为 false。
    assertThat(node.get("bookedInFinance").asBoolean()).isFalse();
    assertThat(node.get("financeNote").asText()).contains("未交付");

    // 能力声明里这一维也是 available=false + 归属模块 12。
    var capabilities = json.readTree(body("/crm/points/capabilities"));
    boolean found = false;
    for (var dimension : capabilities.get("dimensions")) {
      if ("finance_liability_booking".equals(dimension.get("dimension").asText())) {
        found = true;
        assertThat(dimension.get("available").asBoolean()).isFalse();
        assertThat(dimension.get("ownerModule").asText()).isEqualTo("12");
      }
    }
    assertThat(found).as("能力声明必须显式说明 12 财务侧未交付").isTrue();
  }

  // ==========================================================================
  // DB 层不变量
  // ==========================================================================

  @Test
  void 积分流水只追加_改一行就被拒() throws Exception {
    earnFixedRule("EARN-CHECKIN", "CHECKIN", "5", null);
    postJson(
        "/crm/points/earn",
        Map.of("memberKey", MEMBER_A, "earnSource", "CHECKIN", "occurredAt", T0.toString()),
        200);
    try (var c = admin();
        var s = c.createStatement()) {
      assertThatThrownBy(() -> s.execute("update crm_points_ledger set amount = 999"))
          .as("积分流水只追加：改一行必须被拒")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> s.execute("delete from crm_points_ledger"))
          .as("积分流水只追加：删一行必须被拒")
          .isInstanceOf(SQLException.class);
    }
  }

  @Test
  void 余额永不为负() throws Exception {
    earnFixedRule("EARN-CHECKIN", "CHECKIN", "5", null);
    postJson(
        "/crm/points/earn",
        Map.of("memberKey", MEMBER_A, "earnSource", "CHECKIN", "occurredAt", T0.toString()),
        200);
    try (var c = admin();
        var s = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.execute(
                      "update crm_points_account set balance = -1 where account_key = '"
                          + MEMBER_A
                          + "'"))
          .as("余额永不为负：DB 层 CHECK 必须挡住")
          .isInstanceOf(SQLException.class);
    }
  }

  @Test
  void 积分账户必须挂在真实会员上() throws Exception {
    try (var c = admin();
        var s = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.execute(
                      "insert into crm_points_account"
                          + "(id,tenant_id,account_key,member_key,customer_ref,source_shop,"
                          + "customer_name,balance,earned_total,spent_total,expired_total,"
                          + "created_at,updated_at)"
                          + " values ('"
                          + UUID.randomUUID()
                          + "','"
                          + tenant
                          + "','AMAZON GHOST','AMAZON GHOST','GHOST','AMAZON','幽灵',0,0,0,0,now(),now())"))
          .as("给不存在的会员建积分账户必须被拒（触发器）")
          .isInstanceOf(SQLException.class);
    }
  }

  @Test
  void 一个租户只能有一条开着的估值规则() throws Exception {
    valuationRule("VALUATION", "0.01", "USD");
    assertThat(
            postStatus(
                "/crm/points/rules",
                Map.of(
                    "ruleCode", "VALUATION-2",
                    "ruleName", "第二个估值规则",
                    "ruleType", "VALUATION",
                    "pointValueRate", new BigDecimal("0.02"),
                    "liabilityCurrency", "USD",
                    "effectiveFrom", "2026-01-01T00:00:00Z")))
        .as("同时只能有一条开着的估值规则（负债口径必须唯一）")
        .isEqualTo("409");
  }

  // ==========================================================================
  // 列表与统计
  // ==========================================================================

  @Test
  void 账户列表是真分页_统计与分页无关() throws Exception {
    earnFixedRule("EARN-CHECKIN", "CHECKIN", "100", null);
    postJson(
        "/crm/points/earn",
        Map.of("memberKey", MEMBER_A, "earnSource", "CHECKIN", "occurredAt", T0.toString()),
        200);
    postJson(
        "/crm/points/earn",
        Map.of("memberKey", MEMBER_B, "earnSource", "CHECKIN", "occurredAt", T0.toString()),
        200);

    String first = body("/crm/points/accounts?page=0&size=1");
    var node = json.readTree(first);
    assertThat(node.get("rows").size()).isEqualTo(1);
    assertThat(node.get("total").asLong()).isEqualTo(2);
    assertThat(node.get("totalPages").asInt()).isEqualTo(2);
    // 统计**不受分页影响**。
    assertThat(node.get("stats").get("totalAccounts").asLong()).isEqualTo(2);
    assertThat(node.get("stats").get("outstandingPoints").asText()).isEqualTo("200");

    String second = body("/crm/points/accounts?page=1&size=1");
    assertThat(json.readTree(second).get("stats").get("totalAccounts").asLong()).isEqualTo(2);
    assertThat(json.readTree(second).get("rows").get(0).get("accountKey").asText())
        .isNotEqualTo(json.readTree(first).get("rows").get(0).get("accountKey").asText());
  }

  @Test
  void 流水列表可按方向筛选() throws Exception {
    earnFixedRule("EARN-CHECKIN", "CHECKIN", "100", null);
    spendRule("SPEND-DEDUCT", "0.5");
    valuationRule("VALUATION", "0.01", "USD");
    postJson(
        "/crm/points/earn",
        Map.of("memberKey", MEMBER_A, "earnSource", "CHECKIN", "occurredAt", T0.toString()),
        200);
    postJson(
        "/crm/points/spend",
        Map.of("memberKey", MEMBER_A, "orderId", orderA.toString(), "points", new BigDecimal("40")),
        200);

    String earnOnly = body("/crm/points/ledger?direction=EARN");
    assertThat(json.readTree(earnOnly).get("total").asLong()).isEqualTo(1);
    String spendOnly = body("/crm/points/ledger?direction=SPEND");
    assertThat(json.readTree(spendOnly).get("total").asLong()).isEqualTo(1);
    assertThat(json.readTree(spendOnly).get("rows").get(0).get("balanceBefore").asText())
        .isEqualTo("100");
    assertThat(json.readTree(spendOnly).get("rows").get(0).get("balanceAfter").asText()).isEqualTo("60");
  }
}
