package com.starcon.crm;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starcon.crm.CustomerFactsPort.AftersalesCasePage;
import com.starcon.crm.CustomerFactsPort.AftersalesCaseView;
import com.starcon.crm.CustomerFactsPort.OrderPage;
import com.starcon.crm.CustomerFactsPort.OrderView;
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
 * 15.3.1.1 客户列表的集成测试。
 *
 * <p>跑的是 AGENTS.md 要求的<b>真实 PostgreSQL 16 + FORCE RLS</b>。
 * 租户隔离三断言在 {@link CustomerListTenancyIntegrationTest} 里，本类专攻业务不变量：
 *
 * <ul>
 *   <li><b>客户是「按来源店铺」的</b>：同一个客户标识在两个来源下 ⇒ <b>两行</b>，
 *       不是一行（那是 15.3.1.3 跨店合并的事，本模块**不提前做**）。</li>
 *   <li><b>分页是真的</b>：{@code LIMIT/OFFSET} + 稳定全序，翻页不重不漏。</li>
 *   <li><b>分层统计不受分页影响</b>：统计来自**独立查询**，不是当前页的聚合。</li>
 *   <li><b>联系方式默认脱敏</b>；明文要**显式授权**（{@code crm.customers.pii.read}），
 *       没授权还硬要 ⇒ 403，不是悄悄退回脱敏。</li>
 *   <li><b>上游不造数据</b>：上游读不到就明确失败（503），
 *       <b>绝不</b>返回空集合冒充「没有客户」。</li>
 *   <li><b>禁浮点 / 时区不静默偏移</b>：金额逐位相同；带偏移量的时刻写回等于同一绝对时刻。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerListIntegrationTest {
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

  // ------------------------------------------------------------ 上游（trade / catalog）事实

  /** 客户甲：5 单 AMAZON + 1 单 SHOPIFY ⇒ **按来源店铺是两行**。 */
  static final UUID partyA = UUID.randomUUID();
  /** 客户乙：1 单 + 1 张退货单 —— 样本不足，风险标记必须是「不可判定」。 */
  static final UUID partyB = UUID.randomUUID();
  /** 客户丙：10 单全退 —— 退款率显著高于租户基线。 */
  static final UUID partyC = UUID.randomUUID();
  /** 客户丁：40 单 1 张退货单 —— 租户基线的大头（用来把基线压下去）。 */
  static final UUID partyD = UUID.randomUUID();

  /** 客户乙那一单：**带 +08:00 偏移量**的时刻，用来钉死时区不静默偏移。 */
  static final OffsetDateTime partyBOrderAt = OffsetDateTime.parse("2026-10-10T08:00:00+08:00");

  static final List<OrderView> upstreamOrders = new CopyOnWriteArrayList<>();
  static final List<AftersalesCaseView> upstreamCases = new CopyOnWriteArrayList<>();
  static final Map<String, String> upstreamParties = new ConcurrentHashMap<>();

  /** 上游读不到时的开关（用来验证「明确失败」而不是空集合）。 */
  static volatile boolean tradeDown = false;

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
    List<String> readOnly = List.of("crm.customers.read", "crm.customers.update");
    List<String> withPii =
        List.of("crm.customers.read", "crm.customers.update", "crm.customers.pii.read");
    actors.put(
        "session",
        new AccessService.Access(
            userId,
            tenant,
            "Test",
            UUID.randomUUID(),
            null,
            readOnly,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", readOnly))));
    actors.put(
        "session-pii",
        new AccessService.Access(
            userId,
            tenant,
            "Test",
            UUID.randomUUID(),
            null,
            withPii,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", withPii))));
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
            List<OrderView> matched = new ArrayList<>();
            for (OrderView o : upstreamOrders) {
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
      trade.createContext(
          "/trade/aftersales/cases",
          e -> {
            if (tradeDown) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            var query = query(e);
            OffsetDateTime from = moment(query.get("receivedFrom"));
            OffsetDateTime to = moment(query.get("receivedTo"));
            List<AftersalesCaseView> matched = new ArrayList<>();
            for (AftersalesCaseView c : upstreamCases) {
              if (c.receivedAt() == null) continue;
              if (from != null && c.receivedAt().isBefore(from)) continue;
              if (to != null && !c.receivedAt().isBefore(to)) continue;
              matched.add(c);
            }
            int size = Integer.parseInt(query.getOrDefault("size", "200"));
            int pageNo = Integer.parseInt(query.getOrDefault("page", "0"));
            int offset = Math.min(pageNo * size, matched.size());
            int limit = Math.min(offset + size, matched.size());
            write(
                e,
                new AftersalesCasePage(
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
          "/catalog/parties",
          e -> {
            if (tradeDown) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            String path = e.getRequestURI().getPath();
            String id = path.substring(path.lastIndexOf('/') + 1);
            Map<String, Object> party = new HashMap<>();
            party.put("id", id);
            party.put("code", "C-" + id);
            party.put("name", "客户");
            String email = upstreamParties.get(id + "|email");
            String phone = upstreamParties.get(id + "|phone");
            party.put("email", email);
            party.put("phone", phone);
            party.put("status", "ACTIVE");
            byte[] body = json.writeValueAsBytes(party);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
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
  private static OrderView order(
      UUID id,
      String orderNo,
      String source,
      UUID party,
      String buyer,
      String currency,
      String amount,
      OffsetDateTime at) {
    return new OrderView(
        id, orderNo, source, "SUBMITTED", currency, new BigDecimal(amount), party, buyer, at, at);
  }

  /** 造一条上游售后单。 */
  private static AftersalesCaseView aftersales(
      UUID id, String caseNo, UUID orderId, String orderNo, String caseType, OffsetDateTime at) {
    return new AftersalesCaseView(
        id,
        caseNo,
        orderId,
        orderNo,
        caseType,
        "RECEIVED",
        "BUYER_REASON",
        new BigDecimal("10.00"),
        "USD",
        at);
  }

  @BeforeEach
  void reset() throws Exception {
    upstreamOrders.clear();
    upstreamCases.clear();
    upstreamParties.clear();
    tradeDown = false;
    try (var c = admin();
        var s = c.createStatement()) {
      s.execute(
          "truncate table crm_customer_amount, crm_customer_profile, crm_customer_sync_run,"
              + " crm_customer_tag_assignment, crm_customer_tag_rule, crm_customer_tag cascade");
    }

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    // 客户甲：AMAZON 5 单（金额带零头，用来钉死禁浮点）+ SHOPIFY 1 单 ⇒ **两行**。
    String[] amounts = {"100.10", "200.20", "0.30", "10.00", "5.00"};
    for (int i = 0; i < 5; i++) {
      upstreamOrders.add(
          order(
              UUID.randomUUID(),
              "SO-A-" + i,
              "AMAZON",
              partyA,
              "买家甲",
              "USD",
              amounts[i],
              now.minusDays(10 + i)));
    }
    upstreamOrders.add(
        order(
            UUID.randomUUID(),
            "SO-A-SHOP",
            "SHOPIFY",
            partyA,
            "买家甲",
            "USD",
            "50.00",
            now.minusDays(9)));
    // 客户乙：1 单（带 +08:00 偏移量的时刻）+ 1 张退货单 ⇒ 样本不足。
    UUID orderB = UUID.randomUUID();
    upstreamOrders.add(
        order(orderB, "SO-B-1", "AMAZON", partyB, "买家乙", "USD", "20.00", partyBOrderAt));
    upstreamCases.add(
        aftersales(
            UUID.randomUUID(), "AS-B-1", orderB, "SO-B-1", "RETURN_REFUND", now.minusDays(3)));
    // 客户丙：10 单全退 ⇒ 退款率显著高于租户基线。
    for (int i = 0; i < 10; i++) {
      UUID id = UUID.randomUUID();
      upstreamOrders.add(
          order(
              id,
              "SO-C-" + i,
              "AMAZON",
              partyC,
              "买家丙",
              "USD",
              "100.00",
              now.minusDays(20 + i)));
      upstreamCases.add(
          aftersales(
              UUID.randomUUID(), "AS-C-" + i, id, "SO-C-" + i, "RETURN_REFUND", now.minusDays(15)));
    }
    // 客户丁：40 单 1 张退货单 —— 租户基线的大头。
    for (int i = 0; i < 40; i++) {
      UUID id = UUID.randomUUID();
      upstreamOrders.add(
          order(
              id,
              "SO-D-" + i,
              "AMAZON",
              partyD,
              "买家丁",
              "USD",
              "10.00",
              now.minusDays(30)));
      if (i == 0) {
        upstreamCases.add(
            aftersales(
                UUID.randomUUID(), "AS-D-1", id, "SO-D-0", "RETURN_REFUND", now.minusDays(5)));
      }
    }
    // 客户甲的联系方式在 catalog 档案里（脱敏 → 明文的链路）。
    upstreamParties.put(partyA + "|email", "alice@example.com");
    upstreamParties.put(partyA + "|phone", "13812341234");
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

  /** 直连库里数一数客户档案行（迁移用户，不受 RLS 影响）。 */
  private long profileCount() throws Exception {
    try (var c = admin();
        var s = c.prepareStatement(
            "select count(*) from crm_customer_profile where tenant_id = ?")) {
      s.setObject(1, tenant);
      try (var rs = s.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
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

  private String bodyAs(String session, String path) throws Exception {
    return http.perform(
            get(path)
                .header("Authorization", session)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private String postJson(String path, Object payload) throws Exception {
    return http.perform(
            post(path)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload == null ? "{}" : json.writeValueAsString(payload)))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private void putJson(String path, Object payload) throws Exception {
    http.perform(
            put(path)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload)))
        .andExpect(status().isOk());
  }

  private void sync() throws Exception {
    String result = postJson("/crm/customers/sync", Map.of());
    assertThat(json.readTree(result).get("status").asText()).isEqualTo("SUCCEEDED");
  }

  // ================================================================ 刷新与落库

  @Test
  void 刷新真的从上游拉事实并落库() throws Exception {
    sync();
    String list = body("/crm/customers?size=200");
    var root = json.readTree(list);
    assertThat(root.get("total").asLong()).isEqualTo(5);

    var rowA =
        rows(root).stream()
            .filter(r -> r.get("sourceShop").asText().equals("AMAZON"))
            .filter(r -> r.get("customerRef").asText().equals(partyA.toString()))
            .findFirst()
            .orElseThrow();
    assertThat(rowA.get("orderCount").asInt()).isEqualTo(5);
    assertThat(rowA.get("customerName").asText()).isEqualTo("买家甲");
    // 禁浮点：100.10 + 200.20 + 0.30 + 10.00 + 5.00 = 315.60，逐位相同。
    assertThat(rowA.get("totalOrderAmount").decimalValue()).isEqualByComparingTo("315.60");
    assertThat(rowA.get("totalOrderCurrency").asText()).isEqualTo("USD");
    // 客单价 = 累计消费 ÷ 订单数 = 315.60 ÷ 5 = 63.12（服务端算）。
    assertThat(rowA.get("avgOrderAmount").decimalValue()).isEqualByComparingTo("63.12");
    // 退款率 = 0 ÷ 5 = 0.000000（不是 null —— 有分母）。
    assertThat(rowA.get("refundRate").decimalValue()).isEqualByComparingTo("0.000000");
  }

  @Test
  void 客户是按来源店铺的_同一个人两个来源是两行() throws Exception {
    sync();
    var root = json.readTree(body("/crm/customers?size=200"));
    List<String> refs =
        rows(root).stream()
            .map(r -> r.get("sourceShop").asText() + "/" + r.get("customerRef").asText())
            .toList();
    assertThat(refs).contains("AMAZON/" + partyA);
    assertThat(refs).contains("SHOPIFY/" + partyA);
    // 同一客户标识在两个来源下 ⇒ 两行（**不是**一行，那是 15.3.1.3 的事）。
    assertThat(rows(root).stream().filter(r -> r.get("customerRef").asText().equals(partyA.toString()))
            .count())
        .isEqualTo(2);
  }

  @Test
  void 上游读不到就明确失败_不会落一条客户档案() throws Exception {
    sync();
    long before = profileCount();
    assertThat(before).isEqualTo(5);

    tradeDown = true;
    http.perform(
            post("/crm/customers/sync")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isServiceUnavailable());

    long after = profileCount();
    assertThat(after).as("上游读不到时不能落任何新档案").isEqualTo(5);
  }

  // ================================================================ 分页与统计

  @Test
  void 列表是真分页_翻页不重不漏() throws Exception {
    sync();
    var page0 = json.readTree(body("/crm/customers?size=2&page=0"));
    var page1 = json.readTree(body("/crm/customers?size=2&page=1"));
    var page2 = json.readTree(body("/crm/customers?size=2&page=2"));

    assertThat(page0.get("total").asLong()).isEqualTo(5);
    assertThat(rows(page0)).hasSize(2);
    assertThat(rows(page1)).hasSize(2);
    assertThat(rows(page2)).hasSize(1);
    assertThat(page0.get("totalPages").asInt()).isEqualTo(3);

    List<String> keys = new ArrayList<>();
    rows(page0).forEach(r -> keys.add(key(r)));
    rows(page1).forEach(r -> keys.add(key(r)));
    rows(page2).forEach(r -> keys.add(key(r)));
    assertThat(keys).doesNotHaveDuplicates();
    assertThat(keys).hasSize(5);
  }

  @Test
  void 分层统计不受分页影响() throws Exception {
    sync();
    var first = json.readTree(body("/crm/customers?size=2&page=0")).get("stats");
    var last = json.readTree(body("/crm/customers?size=2&page=2")).get("stats");
    // 🔴 统计是**独立查询**的结果：翻到最后一页，分层分布必须一模一样。
    assertThat(last.toString()).isEqualTo(first.toString());
    assertThat(first.get("totalCustomers").asLong()).isEqualTo(5);
    assertThat(first.get("totalOrders").asLong()).isEqualTo(57);
    // 价值分层统计：HIGH = 客户丙（1000.00）与客户丁（400.00），
    // NORMAL = 客户甲的两行与客户乙（基线 = 1785.60 ÷ 5 = 357.12）。
    var tiers = first.get("byValueTier");
    assertThat(tiers).hasSize(2);
    assertThat(tierOf(tiers, "HIGH").get("customerCount").asLong()).isEqualTo(2);
    assertThat(tierOf(tiers, "NORMAL").get("customerCount").asLong()).isEqualTo(3);
    // 金额按币种分开统计（只有 USD 一种币种）。
    assertThat(first.get("amountsByCurrency")).hasSize(1);
    assertThat(first.get("amountsByCurrency").get(0).get("currency").asText()).isEqualTo("USD");
    assertThat(first.get("amountsByCurrency").get(0).get("amount").decimalValue())
        .isEqualByComparingTo("1785.60");
  }

  // ================================================================ 脱敏与授权

  @Test
  void 联系方式默认脱敏() throws Exception {
    sync();
    var row = rowOf(partyA, "AMAZON");
    assertThat(row.get("contactEmail").asText()).isEqualTo("a***@***.com");
    assertThat(row.get("contactPhone").asText()).isEqualTo("138****1234");
    assertThat(row.get("contactMasked").asBoolean()).isTrue();
  }

  @Test
  void 明文联系方式必须显式授权() throws Exception {
    sync();
    // 没有 pii 权限却显式要求 ⇒ 403（不是悄悄退回脱敏）。
    http.perform(
            get("/crm/customers?revealContact=true")
                .header("Authorization", "session")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isForbidden());

    // 有权限 ⇒ 明文。
    var row =
        rows(json.readTree(bodyAs("session-pii", "/crm/customers?revealContact=true&size=200")))
            .stream()
            .filter(r -> r.get("sourceShop").asText().equals("AMAZON"))
            .filter(r -> r.get("customerRef").asText().equals(partyA.toString()))
            .findFirst()
            .orElseThrow();
    assertThat(row.get("contactEmail").asText()).isEqualTo("alice@example.com");
    assertThat(row.get("contactPhone").asText()).isEqualTo("13812341234");
    assertThat(row.get("contactMasked").asBoolean()).isFalse();

    // 有权限但**没有显式要求** ⇒ 仍然是脱敏（默认脱敏）。
    var masked =
        rows(json.readTree(bodyAs("session-pii", "/crm/customers?size=200")))
            .stream()
            .filter(r -> r.get("sourceShop").asText().equals("AMAZON"))
            .filter(r -> r.get("customerRef").asText().equals(partyA.toString()))
            .findFirst()
            .orElseThrow();
    assertThat(masked.get("contactEmail").asText()).isEqualTo("a***@***.com");
  }

  @Test
  void 人工登记联系方式也走脱敏() throws Exception {
    sync();
    putJson(
        "/crm/customers/contact",
        Map.of(
            "customerRef", partyB.toString(),
            "sourceShop", "AMAZON",
            "email", "bob@example.com",
            "phone", "13900001111"));
    var row = rowOf(partyB, "AMAZON");
    assertThat(row.get("contactEmail").asText()).isEqualTo("b***@***.com");
    assertThat(row.get("contactPhone").asText()).isEqualTo("139****1111");
    assertThat(row.get("contactSource").asText()).isEqualTo("MANUAL");
  }

  @Test
  void 登记不存在的客户会被拒绝() throws Exception {
    sync();
    http.perform(
            put("/crm/customers/contact")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "customerRef", UUID.randomUUID().toString(),
                            "sourceShop", "AMAZON",
                            "email", "nobody@example.com"))))
        .andExpect(status().isNotFound());
  }

  // ================================================================ 筛选

  @Test
  void 筛选_店铺_价值分层_风险_金额() throws Exception {
    sync();
    assertThat(rows(json.readTree(body("/crm/customers?sourceShop=SHOPIFY&size=200")))).hasSize(1);
    assertThat(rows(json.readTree(body("/crm/customers?valueTier=HIGH&size=200")))).hasSize(2);
    assertThat(rows(json.readTree(body("/crm/customers?riskFlag=HIGH_RETURN_RATE&size=200"))))
        .hasSize(1);
    // 样本不足是**不可判定**，与「没风险」分开。
    assertThat(rows(json.readTree(body("/crm/customers?riskFlag=INSUFFICIENT_SAMPLE&size=200"))))
        .hasSize(1);
    assertThat(rows(json.readTree(body("/crm/customers?riskFlag=NONE&size=200")))).hasSize(3);

    // 按金额筛选**必须**给币种。
    http.perform(
            get("/crm/customers?minTotalAmount=1000")
                .header("Authorization", "session")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());
    assertThat(rows(json.readTree(body("/crm/customers?minTotalAmount=1000&currency=USD&size=200"))))
        .hasSize(1);
  }

  @Test
  void 按标签筛选走的是标签模块的记录() throws Exception {
    sync();
    UUID tagId = UUID.randomUUID();
    OffsetDateTime moment = OffsetDateTime.now(ZoneOffset.UTC);
    try (var c = admin();
        var s = c.prepareStatement(
            "insert into crm_customer_tag"
                + "(id,tenant_id,tag_code,name,category,status,contestable,review_required,"
                + " created_at,updated_at)"
                + " values (?::uuid,?::uuid,?,'VIP','VALUE','ACTIVE',false,false,"
                + "?::timestamptz,?::timestamptz)")) {
      s.setObject(1, tagId);
      s.setObject(2, tenant);
      s.setString(3, "VIP");
      s.setObject(4, moment);
      s.setObject(5, moment);
      s.executeUpdate();
    }
    try (var c = admin();
        var s = c.prepareStatement(
            "insert into crm_customer_tag_assignment"
                + "(id,tenant_id,customer_ref,customer_name,tag_id,source,origin_key,state,reason,"
                + " evidence,pending_review,created_at,updated_at)"
                + " values (?::uuid,?::uuid,?,?,?::uuid,'MANUAL','MANUAL','ACTIVE','人工标记',"
                + "?::jsonb,false,?::timestamptz,?::timestamptz)")) {
      s.setObject(1, UUID.randomUUID());
      s.setObject(2, tenant);
      s.setString(3, partyA.toString());
      s.setString(4, "买家甲");
      s.setObject(5, tagId);
      s.setString(6, "{}");
      s.setObject(7, moment);
      s.setObject(8, moment);
      s.executeUpdate();
    }

    var filtered = json.readTree(body("/crm/customers?tagCode=VIP&size=200"));
    assertThat(rows(filtered)).hasSize(2);
    var row = rowOf(partyA, "AMAZON");
    assertThat(row.get("tags")).hasSize(1);
    assertThat(row.get("tags").get(0).get("tagCode").asText()).isEqualTo("VIP");
  }

  // ================================================================ 时间与精度

  @Test
  void 带偏移量的时刻写回等于同一绝对时刻() throws Exception {
    sync();
    var row = rowOf(partyB, "AMAZON");
    assertThat(OffsetDateTime.parse(row.get("firstOrderAt").asText()))
        .isEqualTo(partyBOrderAt);
    assertThat(OffsetDateTime.parse(row.get("firstOrderAt").asText()).toInstant())
        .isEqualTo(partyBOrderAt.toInstant());

    // 断言**写回库里的值**（不是只有响应里对）。
    try (var c = admin();
        var s = c.prepareStatement(
            "select first_order_at from crm_customer_profile where tenant_id = ?"
                + " and source_shop = 'AMAZON' and customer_ref = ?")) {
      s.setObject(1, tenant);
      s.setString(2, partyB.toString());
      try (var rs = s.executeQuery()) {
        assertThat(rs.next()).as("客户乙的客户档案必须真的落库了").isTrue();
        var written = rs.getObject(1, OffsetDateTime.class);
        assertThat(written.toInstant())
            .as("timestamptz 必须原样存下同一个绝对时刻，不许静默偏 8 小时")
            .isEqualTo(partyBOrderAt.toInstant());
      }
    }
  }

  @Test
  void 样本不足时风险标记是不可判定而不是没风险() throws Exception {
    sync();
    var row = rowOf(partyB, "AMAZON");
    assertThat(row.get("riskFlag").asText()).isEqualTo("INSUFFICIENT_SAMPLE");
    assertThat(row.get("riskReason").asText()).contains("不可判定");
    assertThat(row.get("riskReason").asText()).contains("样本");
  }

  // ================================================================ 能力声明

  @Test
  void 能力声明讲清边界() throws Exception {
    var caps = json.readTree(body("/crm/customers/capabilities"));
    assertThat(caps.get("maskingPolicy").asText()).contains("脱敏");
    assertThat(caps.get("boundaryNote").asText()).contains("按来源店铺");
    List<String> dimensions = new ArrayList<>();
    caps.get("dimensions").forEach(d -> dimensions.add(d.get("dimension").asText()));
    assertThat(dimensions).contains("logical_customer", "customer_detail");
    for (var d : caps.get("dimensions")) {
      if (d.get("dimension").asText().equals("logical_customer")) {
        assertThat(d.get("available").asBoolean()).isFalse();
        assertThat(d.get("ownerModule").asText()).isEqualTo("15.3.1.3");
      }
    }
  }

  // ------------------------------------------------------------ 小工具

  private static List<com.fasterxml.jackson.databind.JsonNode> rows(
      com.fasterxml.jackson.databind.JsonNode page) {
    List<com.fasterxml.jackson.databind.JsonNode> out = new ArrayList<>();
    page.get("rows").forEach(out::add);
    return out;
  }

  private static String key(com.fasterxml.jackson.databind.JsonNode row) {
    return row.get("sourceShop").asText() + "/" + row.get("customerRef").asText();
  }

  private com.fasterxml.jackson.databind.JsonNode rowOf(UUID party, String shop) throws Exception {
    return rows(json.readTree(body("/crm/customers?size=200"))).stream()
        .filter(r -> r.get("sourceShop").asText().equals(shop))
        .filter(r -> r.get("customerRef").asText().equals(party.toString()))
        .findFirst()
        .orElseThrow();
  }

  private static com.fasterxml.jackson.databind.JsonNode tierOf(
      com.fasterxml.jackson.databind.JsonNode tiers, String tier) {
    for (var t : tiers) {
      if (t.get("valueTier").asText().equals(tier)) return t;
    }
    throw new AssertionError("分层统计里没有 " + tier);
  }
}
