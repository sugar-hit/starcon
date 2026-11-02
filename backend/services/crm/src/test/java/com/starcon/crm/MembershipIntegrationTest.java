package com.starcon.crm;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starcon.crm.MembershipOrderFactsPort.OrderFact;
import com.starcon.crm.MembershipOrderFactsPort.OrderPage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
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
 * 15.6.1.1 会员与等级的集成测试。
 *
 * <p>跑的是 AGENTS.md 要求的<b>真实 PostgreSQL 16 + FORCE RLS</b>。
 * 租户隔离三断言在 {@link MembershipTenancyIntegrationTest} 里，本类专攻业务不变量：
 *
 * <ul>
 *   <li><b>等级由真实消费数据算出</b>：门槛全部来自租户配的规则，代码里没有写死的门槛。</li>
 *   <li><b>🔴 没有「手工挂等级」的口子</b>：服务端没有接受等级代码的端点，
 *       DB 层也直接拒绝 {@code level_origin='MANUAL_OVERRIDE'}。</li>
 *   <li><b>升级 / 降级都留痕</b>：等级变更记录带判定依据快照与理由。</li>
 *   <li><b>金额不跨币种相加</b>：分币种分行，主币种只是「选出来的一个」，不是唯一口径。</li>
 *   <li><b>上游不造数据</b>：trade 读不到就明确失败（503），
 *       <b>绝不</b>返回空集合冒充「没有订单」—— 那会让全员掉到 NONE。</li>
 *   <li><b>禁浮点 / 时区不静默偏移</b>：金额逐位相同；带偏移量的时刻写回等于同一绝对时刻。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class MembershipIntegrationTest {
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

  /** 会员甲：4 单 USD（金额 0.10/0.20/0.30/0.40 ⇒ 合计 1.00，浮点加法会得到 0.9999…）。 */
  static final UUID partyA = UUID.randomUUID();

  /** 会员乙：2 单 EUR（合计 300.00 EUR）。 */
  static final UUID partyB = UUID.randomUUID();

  /** 会员丙：只下过一单（复购率 0）。 */
  static final UUID partyC = UUID.randomUUID();

  /** 会员丁：带 +08:00 偏移量的时刻，用来钉死时区不静默偏移。 */
  static final UUID partyD = UUID.randomUUID();
  static final OffsetDateTime partyDOrderAt = OffsetDateTime.parse("2026-10-10T08:00:00+08:00");

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
    List<String> permissions = List.of("crm.membership.read", "crm.membership.update");
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
          "truncate table crm_membership_level_rule, crm_membership_profile,"
              + " crm_membership_level_change, crm_membership_run cascade");
    }

    // 会员甲：4 单 USD，金额 0.10 / 0.20 / 0.30 / 0.40 ⇒ 合计 1.00（浮点会得到 0.9999…）。
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
              T0.minusDays(100 - 20 * i)));
    }
    // 会员乙：2 单 EUR，合计 300.00。
    upstreamOrders.add(
        order(
            UUID.randomUUID(),
            "SO-B-0",
            "SHOPIFY",
            partyB,
            "买家乙",
            "EUR",
            "100.00",
            T0.minusDays(60)));
    upstreamOrders.add(
        order(
            UUID.randomUUID(),
            "SO-B-1",
            "SHOPIFY",
            partyB,
            "买家乙",
            "EUR",
            "200.00",
            T0.minusDays(30)));
    // 会员丙：只下一单 ⇒ 复购率 0。
    upstreamOrders.add(
        order(
            UUID.randomUUID(),
            "SO-C-0",
            "AMAZON",
            partyC,
            "买家丙",
            "USD",
            "5000.00",
            T0.minusDays(5)));
    // 会员丁：一单，时刻带 +08:00 偏移量。
    upstreamOrders.add(
        order(
            UUID.randomUUID(),
            "SO-D-0",
            "AMAZON",
            partyD,
            "买家丁",
            "USD",
            "66.60",
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
  private static Connection admin() throws Exception {
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

  /** 用固定窗口重算（窗口固定 ⇒ 断言可复现）。 */
  private String compute() throws Exception {
    String result =
        postJson(
            "/crm/customer-membership/compute",
            Map.of("windowFrom", T0.minusDays(365).toString(), "windowTo", T0.toString()),
            200);
    assertThat(json.readTree(result).get("status").asText()).isEqualTo("SUCCEEDED");
    return result;
  }

  /** 造一条等级规则。 */
  private void createLevel(
      String code,
      String name,
      int rank,
      String minSpend,
      String minSpendCurrency,
      Integer minOrders,
      String minRepurchaseRate,
      String discountRate,
      Boolean freeShipping,
      Boolean priorityShipping)
      throws Exception {
    Map<String, Object> payload = new HashMap<>();
    payload.put("levelCode", code);
    payload.put("levelName", name);
    payload.put("levelRank", rank);
    if (minSpend != null) payload.put("minTotalSpend", new BigDecimal(minSpend));
    if (minSpendCurrency != null) payload.put("minTotalSpendCurrency", minSpendCurrency);
    if (minOrders != null) payload.put("minOrderCount", minOrders);
    if (minRepurchaseRate != null) payload.put("minRepurchaseRate", new BigDecimal(minRepurchaseRate));
    if (discountRate != null) payload.put("discountRate", new BigDecimal(discountRate));
    if (freeShipping != null) payload.put("freeShipping", freeShipping);
    if (priorityShipping != null) payload.put("priorityShipping", priorityShipping);
    payload.put("effectiveFrom", "2020-01-01T00:00:00Z");
    payload.put("note", "测试用等级规则");
    postJson("/crm/customer-membership/levels", payload, 200);
  }

  /** 造全套等级规则：BRONZE(EUR) / SILVER / GOLD / PLATINUM。 */
  private void createAllLevels() throws Exception {
    createLevel("BRONZE", "青铜会员", 0, "100.00", "EUR", 1, null, null, false, false);
    createLevel("SILVER", "白银会员", 1, "100.00", "USD", 2, null, "0.03", true, false);
    createLevel("GOLD", "黄金会员", 2, "1.00", "USD", 4, "0.5", "0.05", true, true);
    createLevel("PLATINUM", "铂金会员", 3, "10000.00", "USD", 10, null, "0.08", true, true);
  }

  /** 会员档案里按客户标识找一行。 */
  private com.fasterxml.jackson.databind.node.ObjectNode memberRow(String customerRef)
      throws Exception {
    var rows = json.readTree(body("/crm/customer-membership/members?size=100")).get("rows");
    for (var row : rows) {
      if (row.get("customerRef").asText().equals(customerRef)) {
        return (com.fasterxml.jackson.databind.node.ObjectNode) row;
      }
    }
    throw new AssertionError("会员档案里找不到客户 " + customerRef);
  }


  // ===== 临时排查探针（用完即删）=====
  @Autowired MembershipService membershipService;
  @Autowired com.starcon.tenancy.TenantTransactions tenants;

  @Test
  void 临时排查_compute真实异常() {
    var ex = catchThrowable(
        () ->
            tenants.run(
                tenant,
                () ->
                    membershipService.compute(
                        new MembershipModels.CrmMembershipComputeInput(
                            T0.minusDays(365).toString(), T0.toString()),
                        userId,
                        "session")));
    System.out.println("PROBE >>> " + ex);
    if (ex != null) {
      Throwable t = ex;
      while (t != null) {
        System.out.println("PROBE cause >>> " + t.getClass().getName() + " : " + t.getMessage());
        t = t.getCause();
      }
    }
  }

  // ================================================================ 等级由真实消费数据算出

  @Test
  void 等级由真实消费数据算出且理由可复核() throws Exception {
    createAllLevels();
    compute();

    var a = memberRow(partyA.toString());
    assertThat(a.get("levelCode").asText()).isEqualTo("GOLD");
    assertThat(a.get("levelRank").asInt()).isEqualTo(2);
    assertThat(a.get("orderCount").asInt()).isEqualTo(4);
    assertThat(a.get("totalSpend").asText()).isEqualTo("1.00");
    assertThat(a.get("spendCurrency").asText()).isEqualTo("USD");
    // 复购率 = 非首单订单数 ÷ 订单数 = 3 ÷ 4。
    assertThat(a.get("repurchaseRate").asText()).isEqualTo("0.750000");
    // 判定理由必须能回答「凭什么落这一级」。
    assertThat(a.get("levelReason").asText()).contains("命中等级 GOLD").contains("订单数 4 ≥ 4");
    assertThat(a.get("levelReason").asText()).contains("复购率 0.750000 ≥ 0.5");
    // 等级来源恒为规则算出来的。
    assertThat(a.get("levelOrigin").asText()).isEqualTo("RULE");

    // 会员乙：币种 EUR ⇒ 只命中 EUR 规则；USD 规则对它**不适用**（跨币种不能比）。
    var b = memberRow(partyB.toString());
    assertThat(b.get("levelCode").asText()).isEqualTo("BRONZE");
    assertThat(b.get("totalSpend").asText()).isEqualTo("300.00");
    assertThat(b.get("spendCurrency").asText()).isEqualTo("EUR");
    assertThat(b.get("repurchaseRate").asText()).isEqualTo("0.500000");

    // 会员丙：1 单 ⇒ GOLD 要 4 单、SILVER 要 2 单 ⇒ 未达任何等级（**一等状态**）。
    var c = memberRow(partyC.toString());
    assertThat(c.get("levelCode").asText()).isEqualTo("NONE");
    assertThat(c.get("levelRank").asInt()).isZero();
    assertThat(c.get("levelReason").asText()).contains("未达任何等级");
    assertThat(c.get("repurchaseRate").asText()).isEqualTo("0.000000");
  }

  @Test
  void 没有配规则时全员未达任何等级而不是默认给一档() throws Exception {
    // 🔴 代码里**没有任何写死的门槛**：不配规则就没有等级。
    compute();
    var stats = json.readTree(body("/crm/customer-membership/members?size=100")).get("stats");
    assertThat(stats.get("totalMembers").asLong()).isEqualTo(4);
    assertThat(stats.get("unmatchedMembers").asLong()).isEqualTo(4);
    var a = memberRow(partyA.toString());
    assertThat(a.get("levelCode").asText()).isEqualTo("NONE");
    assertThat(a.get("levelReason").asText()).contains("租户还没有配置任何生效中的等级规则");
  }

  @Test
  void 金额不跨币种相加而是分币种分行() throws Exception {
    createAllLevels();
    compute();
    var b = memberRow(partyB.toString());
    var amounts = b.get("amounts");
    assertThat(amounts.size()).isEqualTo(1);
    assertThat(amounts.get(0).get("currency").asText()).isEqualTo("EUR");
    assertThat(amounts.get(0).get("totalSpend").asText()).isEqualTo("300.00");
    assertThat(amounts.get(0).get("orderCount").asInt()).isEqualTo(2);
    // 统计里的分币种合计同样一币种一行。
    var byCurrency = json.readTree(body("/crm/customer-membership/members?size=100")).get("stats").get("spendByCurrency");
    var currencies = new ArrayList<String>();
    for (var row : byCurrency) currencies.add(row.get("currency").asText());
    assertThat(currencies).containsExactlyInAnyOrder("EUR", "USD");
  }

  // ================================================================ 🔴 没有「手工挂等级」的口子

  @Test
  void 服务端没有任何能直接写等级的端点() throws Exception {
    createAllLevels();
    compute();

    // ① 会员档案**只读**：改不动等级。
    var a = memberRow(partyA.toString());
    String memberId = a.get("id").asText();
    for (String path :
        List.of(
            "/crm/customer-membership/members/" + memberId,
            "/crm/customer-membership/members/" + memberId + "/level",
            "/crm/customer-membership/members/" + memberId + "/set-level",
            "/crm/customer-membership/members/" + memberId + "/override")) {
      var result =
          http.perform(
                  post(path)
                      .header("Authorization", "session")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"levelCode\":\"PLATINUM\"}"))
              .andReturn();
      assertThat(result.getResponse().getStatus())
          .as("会员等级**不允许**被直接写（%s）", path)
          .isNotEqualTo(200);
    }
    // 重算之后等级仍是规则算出来的 GOLD（没有被改成 PLATINUM）。
    assertThat(memberRow(partyA.toString()).get("levelCode").asText()).isEqualTo("GOLD");
  }

  @Test
  void db层直接拒绝人工覆盖等级() throws Exception {
    createAllLevels();
    compute();
    var a = memberRow(partyA.toString());
    String memberId = a.get("id").asText();

    // 🔴 绕过应用层直连数据库写人工覆盖 ⇒ **触发器直接拒绝**。
    try (var c = admin();
        var s =
            c.prepareStatement(
                "update crm_membership_profile set level_origin = 'MANUAL_OVERRIDE',"
                    + " level_code = 'PLATINUM' where tenant_id = ? and id = ?")) {
      s.setObject(1, tenant);
      s.setObject(2, UUID.fromString(memberId));
      assertThatThrownBy(s::executeUpdate).hasMessageContaining("人工覆盖通道本轮未交付");
    }
    // 等级没被改掉。
    assertThat(memberRow(partyA.toString()).get("levelCode").asText()).isEqualTo("GOLD");
  }

  @Test
  void 能力声明如实标出人工覆盖与平台会员对接未交付() throws Exception {
    var capabilities = json.readTree(body("/crm/customer-membership/capabilities"));
    String raw = capabilities.toString();
    assertThat(raw).contains("manual_level_override");
    assertThat(raw).contains("人工覆盖等级本轮未交付");
    assertThat(raw).contains("platform_membership_sync");
    assertThat(raw).contains("platform_membership_sync");
    assertThat(raw).contains("points_and_redemption");
    assertThat(raw).contains("15.6.1.2 积分与权益未交付");
  }

  // ================================================================ 升级 / 降级留痕

  @Test
  void 升级与降级都留痕且带判定依据() throws Exception {
    createAllLevels();
    compute();
    assertThat(memberRow(partyA.toString()).get("levelCode").asText()).isEqualTo("GOLD");

    // 首次建档 → 一条 REGISTERED 记录。
    var changes =
        json.readTree(body("/crm/customer-membership/level-changes?size=100")).get("rows");
    assertThat(changes.size()).isEqualTo(4);
    var registered = changes.get(0);
    assertThat(registered.get("changeKind").asText()).isEqualTo("REGISTERED");
    assertThat(registered.get("fromLevelCode").asText()).isEqualTo("NONE");
    assertThat(registered.get("origin").asText()).isEqualTo("RULE");
    assertThat(registered.get("reason").asText()).isNotBlank();
    assertThat(registered.get("basis").get("orderCount").asInt()).isPositive();

    // 抽掉一单 ⇒ 会员甲掉到 3 单 ⇒ 不满足 GOLD 的 4 单 ⇒ 降级。
    upstreamOrders.removeIf(o -> o.orderNo().equals("SO-A-3"));
    compute();
    assertThat(memberRow(partyA.toString()).get("levelCode").asText()).isEqualTo("NONE");
    var after = json.readTree(body("/crm/customer-membership/level-changes?size=100")).get("rows");
    var downgrade = after.get(0);
    assertThat(downgrade.get("changeKind").asText()).isEqualTo("DOWNGRADE");
    assertThat(downgrade.get("fromLevelCode").asText()).isEqualTo("GOLD");
    assertThat(downgrade.get("toLevelCode").asText()).isEqualTo("NONE");
    assertThat(downgrade.get("basis").get("orderCount").asInt()).isEqualTo(3);

    // 放回去 ⇒ 升级。
    upstreamOrders.add(
        order(
            UUID.randomUUID(),
            "SO-A-3",
            "AMAZON",
            partyA,
            "买家甲",
            "USD",
            "0.40",
            T0.minusDays(40)));
    compute();
    assertThat(memberRow(partyA.toString()).get("levelCode").asText()).isEqualTo("GOLD");
    var upgraded = json.readTree(body("/crm/customer-membership/level-changes?size=100")).get("rows");
    assertThat(upgraded.get(0).get("changeKind").asText()).isEqualTo("UPGRADE");
    assertThat(upgraded.get(0).get("fromLevelCode").asText()).isEqualTo("NONE");
    assertThat(upgraded.get(0).get("toLevelCode").asText()).isEqualTo("GOLD");
  }

  @Test
  void 窗口外的会员不参与判定不被误降级() throws Exception {
    createAllLevels();
    compute();
    assertThat(memberRow(partyA.toString()).get("levelCode").asText()).isEqualTo("GOLD");

    // 把窗口收窄到**不含**会员甲的订单 ⇒ 他不在本次扫描里 ⇒ 等级与指标保持不变。
    //   （「窗口口径变了」不是「消费下降了」—— 误降级会误导运营。）
    String result =
        postJson(
            "/crm/customer-membership/compute",
            Map.of(
                "windowFrom", T0.minusDays(2).toString(),
                "windowTo", T0.toString()),
            200);
    assertThat(json.readTree(result).get("memberCount").asInt()).isEqualTo(1);
    assertThat(memberRow(partyA.toString()).get("levelCode").asText()).isEqualTo("GOLD");
    assertThat(memberRow(partyA.toString()).get("orderCount").asInt()).isEqualTo(4);
  }

  // ================================================================ 权益生效配置（→ 5 定价 / 14 促销）

  @Test
  void 权益生效配置只输出当前生效的等级() throws Exception {
    createAllLevels();
    var config = json.readTree(body("/crm/customer-membership/benefits"));
    var rows = config.get("rows");
    var codes = new ArrayList<String>();
    for (var row : rows) codes.add(row.get("levelCode").asText());
    assertThat(codes).containsExactlyInAnyOrder("BRONZE", "SILVER", "GOLD", "PLATINUM");
    // 「未达任何等级」**不在**配置里 —— 否则下游会给所有人打折。
    assertThat(codes).doesNotContain("NONE");

    var gold = json.readTree(body("/crm/customer-membership/benefits")).get("rows");
    com.fasterxml.jackson.databind.node.ObjectNode goldRow = null;
    for (var row : gold) {
      if (row.get("levelCode").asText().equals("GOLD")) {
        goldRow = (com.fasterxml.jackson.databind.node.ObjectNode) row;
      }
    }
    assertThat(goldRow).isNotNull();
    assertThat(goldRow.get("discountRate").asText()).isEqualTo("0.05");
    assertThat(goldRow.get("freeShipping").asBoolean()).isTrue();
    assertThat(goldRow.get("priorityShipping").asBoolean()).isTrue();
    assertThat(goldRow.get("benefitSummary").asText()).contains("减免 5%").contains("免邮");
    assertThat(goldRow.get("appliesTo").asText()).contains("累计消费").contains("订单数");
    assertThat(goldRow.get("downstreamConsumers").toString()).contains("5 定价").contains("14 促销");

    // 关掉一条规则 ⇒ 它不再出现在生效配置里。
    var rules = json.readTree(body("/crm/customer-membership/levels?size=100")).get("rows");
    String platinumId = null;
    for (var row : rules) {
      if (row.get("levelCode").asText().equals("PLATINUM")) platinumId = row.get("id").asText();
    }
    assertThat(platinumId).isNotNull();
    putJson("/crm/customer-membership/levels/" + platinumId, disabledPayload("PLATINUM", "铂金会员", 3), 200);
    var after = json.readTree(body("/crm/customer-membership/benefits"));
    var afterCodes = new ArrayList<String>();
    for (var row : after.get("rows")) afterCodes.add(row.get("levelCode").asText());
    assertThat(afterCodes).doesNotContain("PLATINUM");
  }

  private Map<String, Object> disabledPayload(String code, String name, int rank) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("levelCode", code);
    payload.put("levelName", name);
    payload.put("levelRank", rank);
    payload.put("minTotalSpend", new BigDecimal("10000.00"));
    payload.put("minTotalSpendCurrency", "USD");
    payload.put("minOrderCount", 10);
    payload.put("enabled", false);
    payload.put("effectiveFrom", "2020-01-01T00:00:00Z");
    payload.put("note", "关掉测试");
    return payload;
  }

  // ================================================================ 等级规则本身的纪律

  @Test
  void 等级门槛不可就地修改() throws Exception {
    createAllLevels();
    var rules = json.readTree(body("/crm/customer-membership/levels?size=100")).get("rows");
    String goldId = null;
    for (var row : rules) {
      if (row.get("levelCode").asText().equals("GOLD")) goldId = row.get("id").asText();
    }
    assertThat(goldId).isNotNull();

    Map<String, Object> payload = new HashMap<>();
    payload.put("levelCode", "GOLD");
    payload.put("levelName", "黄金会员");
    payload.put("levelRank", 2);
    payload.put("minTotalSpend", new BigDecimal("2.00"));
    payload.put("minTotalSpendCurrency", "USD");
    payload.put("minOrderCount", 4);
    payload.put("minRepurchaseRate", new BigDecimal("0.5"));
    payload.put("discountRate", new BigDecimal("0.05"));
    payload.put("enabled", true);
    payload.put("effectiveFrom", "2020-01-01T00:00:00Z");
    var result =
        http.perform(
                put("/crm/customer-membership/levels/" + goldId)
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(payload)))
            .andReturn();
    assertThat(result.getResponse().getStatus())
        .as("门槛不可就地修改（响应：%s）", result.getResponse().getContentAsString())
        .isEqualTo(409);
    assertThat(result.getResponse().getContentAsString()).contains("不可就地修改");
  }

  @Test
  void 给了消费门槛却没给币种会被拒() throws Exception {
    Map<String, Object> payload = new HashMap<>();
    payload.put("levelCode", "NO_CURRENCY");
    payload.put("levelName", "没有币种");
    payload.put("levelRank", 1);
    payload.put("minTotalSpend", new BigDecimal("100.00"));
    payload.put("effectiveFrom", "2020-01-01T00:00:00Z");
    var result =
        http.perform(
                post("/crm/customer-membership/levels")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(payload)))
            .andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(400);
    assertThat(result.getResponse().getContentAsString()).contains("跨币种的金额不能比");
  }

  // ================================================================ 上游不造数据

  @Test
  void 上游读不到时明确失败且不落任何会员档案() throws Exception {
    createAllLevels();
    tradeDown = true;
    var result =
        http.perform(
                post("/crm/customer-membership/compute")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andReturn();
    assertThat(result.getResponse().getStatus())
        .as("上游读不到必须是 503（响应：%s）", result.getResponse().getContentAsString())
        .isEqualTo(503);

    // 🔴 一条会员档案都不落 —— 绝不返回空集合冒充「这个租户没有订单」。
    try (var c = admin();
        var s = c.createStatement()) {
      try (var rs =
          s.executeQuery("select count(*) from crm_membership_profile where tenant_id = '" + tenant + "'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getLong(1)).as("上游读不到时不得落任何会员档案").isZero();
      }
    }
  }

  // ================================================================ 禁浮点 / 时区 / 分页

  @Test
  void 金额逐位相同不丢精度() throws Exception {
    createAllLevels();
    compute();
    var a = memberRow(partyA.toString());
    // 0.10 + 0.20 + 0.30 + 0.40 = 1.00（浮点加法会得到 0.9999999999999999）。
    assertThat(a.get("totalSpend").asText()).isEqualTo("1.00");
  }

  @Test
  void 带偏移量的时刻写回等于同一绝对时刻() throws Exception {
    createAllLevels();
    compute();
    var d = memberRow(partyD.toString());
    // 上游给的是 2026-10-10T08:00:00+08:00 = 2026-10-10T00:00:00Z。
    assertThat(d.get("lastOrderAt").asText()).isEqualTo("2026-10-10T00:00:00Z");
  }

  @Test
  void 真分页而不是假分页() throws Exception {
    createAllLevels();
    compute();
    var page = json.readTree(body("/crm/customer-membership/members?page=0&size=2"));
    assertThat(page.get("page").asInt()).isZero();
    assertThat(page.get("size").asInt()).isEqualTo(2);
    assertThat(page.get("total").asLong()).isEqualTo(4);
    assertThat(page.get("totalPages").asInt()).isEqualTo(2);
    assertThat(page.get("rows").size()).isEqualTo(2);

    var second = json.readTree(body("/crm/customer-membership/members?page=1&size=2"));
    assertThat(second.get("rows").size()).isEqualTo(2);
    // 两页的会员**不重复**（真分页，不是每页都返回同一批）。
    var keys = new ArrayList<String>();
    for (var row : page.get("rows")) keys.add(row.get("memberKey").asText());
    for (var row : second.get("rows")) {
      assertThat(keys).doesNotContain(row.get("memberKey").asText());
    }
    // 统计与分页无关（不是拿当前页聚合出来的）。
    assertThat(page.get("stats").get("totalMembers").asLong()).isEqualTo(4);
  }

  @Test
  void 真筛选而不是假筛选() throws Exception {
    createAllLevels();
    compute();
    var gold = json.readTree(body("/crm/customer-membership/members?levelCode=GOLD&size=100"));
    assertThat(gold.get("total").asLong()).isEqualTo(1);
    assertThat(gold.get("rows").get(0).get("customerRef").asText()).isEqualTo(partyA.toString());

    var none = json.readTree(body("/crm/customer-membership/members?levelCode=NONE&size=100"));
    assertThat(none.get("total").asLong()).isEqualTo(3);

    var keyword = json.readTree(body("/crm/customer-membership/members?keyword=" + partyB + "&size=100"));
    assertThat(keyword.get("total").asLong()).isEqualTo(1);
    assertThat(keyword.get("rows").get(0).get("customerRef").asText()).isEqualTo(partyB.toString());
  }

  // ================================================================ 合并口径

  @Test
  void 合并后的来源店铺客户合成一个会员() throws Exception {
    createAllLevels();
    // 造一条 15.3.1.3 的合并：会员甲（AMAZON）与会员乙（SHOPIFY）合并成一个逻辑客户。
    UUID logicalId = UUID.randomUUID();
    UUID profileA = UUID.randomUUID();
    UUID profileB = UUID.randomUUID();
    try (var c = admin()) {
      try (var s = c.createStatement()) {
        s.execute(
            "insert into crm_customer_merge_logical"
                + "(id,tenant_id,logical_ref,display_name,primary_customer_id,member_count,"
                + " source_shop_count,total_order_count,status)"
                + " values ('"
                + logicalId
                + "','"
                + tenant
                + "','LC-TEST-000001','买家甲','"
                + profileA
                + "',2,2,6,'ACTIVE')");
        s.execute(
            "insert into crm_customer_profile"
                + "(id,tenant_id,customer_ref,source_shop,customer_party_id,customer_name,"
                + " order_count,value_tier,synced_at)"
                + " values ('"
                + profileA
                + "','"
                + tenant
                + "','"
                + partyA
                + "','AMAZON','"
                + partyA
                + "','买家甲',4,'HIGH',now())");
        s.execute(
            "insert into crm_customer_profile"
                + "(id,tenant_id,customer_ref,source_shop,customer_party_id,customer_name,"
                + " order_count,value_tier,synced_at)"
                + " values ('"
                + profileB
                + "','"
                + tenant
                + "','"
                + partyB
                + "','SHOPIFY','"
                + partyB
                + "','买家乙',2,'HIGH',now())");
        s.execute(
            "insert into crm_customer_merge_mapping"
                + "(id,tenant_id,logical_customer_id,customer_profile_id,source_shop,customer_ref,"
                + " customer_name,role,state,merged_at)"
                + " values (gen_random_uuid(),'"
                + tenant
                + "','"
                + logicalId
                + "','"
                + profileA
                + "','AMAZON','"
                + partyA
                + "','买家甲','PRIMARY','ACTIVE',now())");
        s.execute(
            "insert into crm_customer_merge_mapping"
                + "(id,tenant_id,logical_customer_id,customer_profile_id,source_shop,customer_ref,"
                + " customer_name,role,state,merged_at)"
                + " values (gen_random_uuid(),'"
                + tenant
                + "','"
                + logicalId
                + "','"
                + profileB
                + "','SHOPIFY','"
                + partyB
                + "','买家乙','MEMBER','ACTIVE',now())");
      }
    }

    compute();

    var merged = memberRow(partyA.toString());
    assertThat(merged.get("memberKey").asText()).isEqualTo("LC-TEST-000001");
    assertThat(merged.get("logicalRef").asText()).isEqualTo("LC-TEST-000001");
    assertThat(merged.get("memberCount").asInt()).isEqualTo(2);
    assertThat(merged.get("orderCount").asInt()).isEqualTo(6);
    var shops = merged.get("sourceShops");
    assertThat(shops.size()).isEqualTo(2);
    // 合并后只剩**一个**会员（不是两行）。
    var all = json.readTree(body("/crm/customer-membership/members?size=100"));
    assertThat(all.get("total").asLong()).isEqualTo(3);
  }

  // ================================================================ 运行记录

  @Test
  void 重算运行记录留痕() throws Exception {
    createAllLevels();
    compute();
    var runs = json.readTree(body("/crm/customer-membership/runs?size=10"));
    assertThat(runs.get("rows").size()).isEqualTo(1);
    var run = runs.get("rows").get(0);
    assertThat(run.get("runNo").asText()).startsWith("MB-");
    assertThat(run.get("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(run.get("memberCount").asInt()).isEqualTo(4);
    assertThat(run.get("ruleCount").asInt()).isEqualTo(4);
    assertThat(run.get("levelChangedCount").asInt()).isEqualTo(4);
  }
}
