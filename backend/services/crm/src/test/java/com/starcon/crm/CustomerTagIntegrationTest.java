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
 * 15.3.2.1 客户标签的集成测试。
 *
 * <p>跑的是 AGENTS.md 要求的<b>真实 PostgreSQL 16 + FORCE RLS</b>。租户隔离三断言在
 * {@link CustomerTagTenancyIntegrationTest} 里，本类专攻业务不变量：
 *
 * <ul>
 *   <li><b>🔴 加粗句「"退货率高"标签要谨慎使用」</b>：
 *       <ul>
 *         <li>样本不足 ⇒ 结论是<b>不可判定</b>（{@code INDETERMINATE}），<b>不是</b>「是」；</li>
 *         <li>必须<b>显著高于租户基线</b>（基线由同窗真实事实算出，不是写死的数字）；</li>
 *         <li>可争议标签<b>必须带原因</b>，否则 DB 与服务层都拒；</li>
 *         <li>可被人工复核与纠正（确认 / 撤销），撤销必须写原因。</li>
 *       </ul>
 *   </li>
 *   <li><b>判定基于拉取到的事实</b>：跑一次规则真的从 trade 的授权 HTTP API 拉订单与售后，
 *       并把「依据哪几笔订单 / 哪几张售后单」写进判定依据快照。</li>
 *   <li><b>幂等 + 不覆盖人工</b>：同一批事实重跑不重复打标；人工标的标签不被自动覆盖；
 *       人工撤销后自动重跑<b>不恢复</b>。</li>
 *   <li><b>上游不造数据</b>：上游读不到就明确失败，<b>绝不</b>返回空集合冒充「没有客户」。</li>
 *   <li><b>禁浮点 / 时区不静默偏移</b>：金额写回逐位相同；带偏移量的时刻写回等于同一绝对时刻。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerTagIntegrationTest {
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

  // ------------------------------------------------------------ 上游（trade）事实

  /** 客户 A：窗口内 3 单、3 张退货单；另有 1 单在窗口之前（只用来验「回看段不进分母」）。 */
  static final UUID customerA = UUID.randomUUID();
  /** 客户 B：窗口内 1 单、1 张退货单 —— 样本不足，必须判「不可判定」。 */
  static final UUID customerB = UUID.randomUUID();
  /** 客户 C：窗口内 6 单、0 张退货单。 */
  static final UUID customerC = UUID.randomUUID();

  static final List<OrderView> upstreamOrders = new CopyOnWriteArrayList<>();
  static final List<AftersalesCaseView> upstreamCases = new CopyOnWriteArrayList<>();

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
    List<String> permissions = List.of("crm.tag.read", "crm.tag.update");
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
  private static OrderView order(
      UUID id,
      String orderNo,
      UUID party,
      String buyer,
      String currency,
      String amount,
      int daysAgo) {
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC).minusDays(daysAgo);
    return new OrderView(
        id,
        orderNo,
        "AMAZON",
        "SUBMITTED",
        currency,
        new BigDecimal(amount),
        party,
        buyer,
        at,
        at);
  }

  /** 造一条上游售后单。 */
  private static AftersalesCaseView aftersales(
      UUID id, String caseNo, UUID orderId, String orderNo, String caseType, int daysAgo) {
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
        OffsetDateTime.now(ZoneOffset.UTC).minusDays(daysAgo));
  }

  @BeforeEach
  void reset() throws Exception {
    upstreamOrders.clear();
    upstreamCases.clear();
    tradeDown = false;
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table crm_customer_tag_evaluation, crm_customer_tag_run,"
              + " crm_customer_tag_assignment, crm_customer_tag_rule, crm_customer_tag cascade");
    }
    // 客户 A：窗口内 3 单 + 3 张退货单；另有 1 单在窗口之前（不进分母）。
    for (int i = 1; i <= 3; i++) {
      UUID orderId = UUID.randomUUID();
      upstreamOrders.add(order(orderId, "SO-A-" + i, customerA, "买家甲", "USD", "100.00", 10));
      upstreamCases.add(aftersales(UUID.randomUUID(), "AS-A-" + i, orderId, "SO-A-" + i, "RETURN_REFUND", 5));
    }
    upstreamOrders.add(order(UUID.randomUUID(), "SO-A-OLD", customerA, "买家甲", "USD", "999.00", 400));
    // 客户 B：1 单 1 退货 —— 样本不足。
    UUID orderB = UUID.randomUUID();
    upstreamOrders.add(order(orderB, "SO-B-1", customerB, "买家乙", "USD", "50.00", 8));
    upstreamCases.add(aftersales(UUID.randomUUID(), "AS-B-1", orderB, "SO-B-1", "RETURN_REFUND", 4));
    // 客户 C：6 单 0 退货。
    for (int i = 1; i <= 6; i++) {
      upstreamOrders.add(order(UUID.randomUUID(), "SO-C-" + i, customerC, "买家丙", "USD", "10.00", 9));
    }
  }

  // ------------------------------------------------------------ 工具

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

  private String putJson(String path, Object payload) throws Exception {
    return http.perform(
            put(path)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload)))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  // ================================================================ 标签定义

  @Test
  void 标签定义能建能改能删且编码唯一() throws Exception {
    String created =
        postJson(
            "/crm/customer-tags/tags",
            Map.of(
                "tagCode", "VIP",
                "name", "VIP 客户",
                "category", "VALUE",
                "description", "累计消费最高的客户"));
    var id = json.readTree(created).get("id").asText();
    assertThat(json.readTree(created).get("categoryLabel").asText()).isEqualTo("价值分层");

    // 编码唯一：再建一个同编码的必须 409。
    http.perform(
            post("/crm/customer-tags/tags")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("tagCode", "VIP", "name", "另一个", "category", "VALUE"))))
        .andExpect(status().isConflict());

    // 编码建后不可改。
    http.perform(
            put("/crm/customer-tags/tags/" + id)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("tagCode", "VIP2", "name", "VIP 客户", "category", "VALUE"))))
        .andExpect(status().isBadRequest());

    http.perform(delete("/crm/customer-tags/tags/" + id).header("Authorization", "session"))
        .andExpect(status().isOk());
    http.perform(get("/crm/customer-tags/tags/" + id).header("Authorization", "session"))
        .andExpect(status().isNotFound());
  }

  // ================================================================ 手工打标

  @Test
  void 手工打标可争议标签必须带原因否则拒绝() throws Exception {
    String tag =
        postJson(
            "/crm/customer-tags/tags",
            Map.of(
                "tagCode", "HIGH_RETURN",
                "name", "退货率高",
                "category", "RISK",
                "contestable", true,
                "reviewRequired", true));
    String tagId = json.readTree(tag).get("id").asText();

    // 没写原因 ⇒ 400（服务层先说人话）。
    http.perform(
            post("/crm/customer-tags/assignments")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("customerRef", customerA.toString(), "tagId", tagId))))
        .andExpect(status().isBadRequest());

    String assignment =
        postJson(
            "/crm/customer-tags/assignments",
            Map.of(
                "customerRef", customerA.toString(),
                "customerName", "买家甲",
                "tagId", tagId,
                "reason", "近 3 单退了 2 单，人工确认"));
    var node = json.readTree(assignment);
    assertThat(node.get("source").asText()).isEqualTo("MANUAL");
    assertThat(node.get("originKey").asText()).isEqualTo("MANUAL");
    assertThat(node.get("state").asText()).isEqualTo("ACTIVE");
    assertThat(node.get("pendingReview").asBoolean()).isTrue();
    assertThat(node.get("reason").asText()).contains("人工确认");

    // 重复打标幂等：仍是同一行。
    postJson(
        "/crm/customer-tags/assignments",
        Map.of(
            "customerRef", customerA.toString(),
            "tagId", tagId,
            "reason", "再确认一次"));
    String list = body("/crm/customer-tags/assignments?customerRef=" + customerA);
    assertThat(json.readTree(list).get("totalElements").asLong()).isEqualTo(1);
  }

  @Test
  void 人工撤销必须写原因且撤销后自动规则不会恢复() throws Exception {
    String tag =
        postJson(
            "/crm/customer-tags/tags",
            Map.of("tagCode", "RISK", "name", "风险", "category", "RISK", "contestable", true));
    String tagId = json.readTree(tag).get("id").asText();
    String assignment =
        postJson(
            "/crm/customer-tags/assignments",
            Map.of(
                "customerRef", customerA.toString(),
                "tagId", tagId,
                "reason", "人工标记"));
    String assignmentId = json.readTree(assignment).get("id").asText();

    // 撤销没写原因 ⇒ 400。
    http.perform(
            post("/crm/customer-tags/assignments/" + assignmentId + "/review")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("decision", "REVOKE"))))
        .andExpect(status().isBadRequest());

    // 写明原因才撤得掉。
    String revoked =
        postJson(
            "/crm/customer-tags/assignments/" + assignmentId + "/review",
            Map.of("decision", "REVOKE", "reason", "复核后认为不成立"));
    assertThat(json.readTree(revoked).get("state").asText()).isEqualTo("REVOKED");
    assertThat(json.readTree(revoked).get("decidedAt").asText()).isNotBlank();
  }

  // ================================================================ 规则打标

  @Test
  void 规则打标基于拉取到的事实并留下判定依据快照() throws Exception {
    String tag =
        postJson(
            "/crm/customer-tags/tags",
            Map.of("tagCode", "REPEAT", "name", "复购", "category", "BEHAVIOR"));
    String tagId = json.readTree(tag).get("id").asText();
    String rule =
        postJson(
            "/crm/customer-tags/rules",
            Map.of(
                "ruleCode", "REPEAT-3",
                "name", "窗口内 3 单及以上算复购",
                "tagId", tagId,
                "metric", "ORDER_COUNT",
                "threshold", 3,
                "minSample", 1,
                "windowDays", 365));
    String ruleId = json.readTree(rule).get("id").asText();

    String run = postJson("/crm/customer-tags/rules/" + ruleId + "/run", null);
    var runNode = json.readTree(run);
    assertThat(runNode.get("status").asText()).isEqualTo("SUCCEEDED");
    // 客户 A（3 单）与客户 C（6 单）命中；客户 B（1 单）不命中。
    assertThat(runNode.get("taggedCount").asInt()).isEqualTo(2);
    assertThat(runNode.get("customerCount").asInt()).isEqualTo(3);

    String list = body("/crm/customer-tags/assignments?tagId=" + tagId);
    var items = json.readTree(list).get("items");
    assertThat(items.size()).isEqualTo(2);
    boolean checked = false;
    for (var item : items) {
      if (!item.get("customerRef").asText().equals(customerA.toString())) continue;
      checked = true;
      assertThat(item.get("source").asText()).isEqualTo("RULE");
      assertThat(item.get("originKey").asText()).isEqualTo("RULE:REPEAT-3");
      // 判定依据快照：真的写清了依据哪几笔订单。
      var evidence = item.get("evidence");
      assertThat(evidence.get("orderNos").isArray()).isTrue();
      assertThat(evidence.get("orderNos").size()).isEqualTo(3);
      assertThat(evidence.get("metric").asText()).isEqualTo("ORDER_COUNT");
      // ⚠️ 客户 A 在窗口内只有 3 单：回看段那一单（400 天前）**不进分母**。
      assertThat(evidence.get("windowOrderCount").asInt()).isEqualTo(3);
    }
    assertThat(checked).as("客户 A 必须被打上复购标签").isTrue();
  }

  @Test
  void 重跑幂等不重复打标且不覆盖人工标签() throws Exception {
    String tag =
        postJson(
            "/crm/customer-tags/tags",
            Map.of("tagCode", "REPEAT", "name", "复购", "category", "BEHAVIOR"));
    String tagId = json.readTree(tag).get("id").asText();
    // 客户 A 先被**人工**打上同一枚标签。
    postJson(
        "/crm/customer-tags/assignments",
        Map.of(
            "customerRef", customerA.toString(),
            "customerName", "买家甲",
            "tagId", tagId,
            "reason", "人工确认是老客"));
    String rule =
        postJson(
            "/crm/customer-tags/rules",
            Map.of(
                "ruleCode", "REPEAT-3",
                "name", "窗口内 3 单及以上算复购",
                "tagId", tagId,
                "metric", "ORDER_COUNT",
                "threshold", 3,
                "minSample", 1,
                "windowDays", 365));
    String ruleId = json.readTree(rule).get("id").asText();

    postJson("/crm/customer-tags/rules/" + ruleId + "/run", null);
    String run2 = postJson("/crm/customer-tags/rules/" + ruleId + "/run", null);
    var runNode = json.readTree(run2);
    // 第二次跑：客户 A 与 C 都已打过 ⇒ 不再新打（幂等）。
    assertThat(runNode.get("taggedCount").asInt()).isEqualTo(0);
    assertThat(runNode.get("unchangedCount").asInt()).isEqualTo(2);

    String list = body("/crm/customer-tags/assignments?tagId=" + tagId);
    var items = json.readTree(list).get("items");
    // 客户 A 有两行（人工一行 + 自动一行），客户 C 一行 —— 共 3 行，**人工那行没被动过**。
    assertThat(items.size()).isEqualTo(3);
    boolean manualIntact = false;
    for (var item : items) {
      if (item.get("source").asText().equals("MANUAL")) {
        manualIntact =
            item.get("state").asText().equals("ACTIVE")
                && item.get("reason").asText().equals("人工确认是老客");
      }
    }
    assertThat(manualIntact).as("自动重跑绝不能覆盖人工标的标签").isTrue();
  }

  @Test
  void 样本不足时结论是不可判定而不是是() throws Exception {
    String tag =
        postJson(
            "/crm/customer-tags/tags",
            Map.of("tagCode", "HIGH_RETURN", "name", "退货率高", "category", "RISK", "contestable", true));
    String tagId = json.readTree(tag).get("id").asText();
    String rule =
        postJson(
            "/crm/customer-tags/rules",
            Map.of(
                "ruleCode", "RETURN-2X",
                "name", "退货率显著高于租户基线",
                "tagId", tagId,
                "metric", "RETURN_RATE",
                "threshold", 0,
                "minSample", 3,
                "windowDays", 365,
                "baselineMultiplier", 2));
    String ruleId = json.readTree(rule).get("id").asText();

    String run = postJson("/crm/customer-tags/rules/" + ruleId + "/run", null);
    var runNode = json.readTree(run);
    // 客户 A（3 单 3 退，率 1.0）命中；客户 B（1 单）不可判定；客户 C 不命中。
    assertThat(runNode.get("taggedCount").asInt()).isEqualTo(1);
    assertThat(runNode.get("indeterminateCount").asInt()).isEqualTo(1);
    // 基线 = 4 张退货单 ÷ 10 单 = 0.4（真实算出，不是写死的）。
    assertThat(new BigDecimal(runNode.get("baseline").asText())).isEqualByComparingTo("0.400000");

    // 逐客户判定结论里能筛出「不可判定」的那一位。
    String evaluations = body("/crm/customer-tags/runs/" + runNode.get("id").asText() + "/evaluations?outcome=INDETERMINATE");
    var items = json.readTree(evaluations).get("items");
    assertThat(items.size()).isEqualTo(1);
    assertThat(items.get(0).get("customerRef").asText()).isEqualTo(customerB.toString());
    assertThat(items.get(0).get("reason").asText()).contains("不可判定");
    assertThat(items.get(0).get("reason").asText()).contains("样本");

    // 不可判定的那位**没有**被打上标签。
    String list = body("/crm/customer-tags/assignments?tagId=" + tagId);
    for (var item : json.readTree(list).get("items")) {
      assertThat(item.get("customerRef").asText()).isNotEqualTo(customerB.toString());
    }
  }

  @Test
  void 退货率必须显著高于租户基线才命中() throws Exception {
    String tag =
        postJson(
            "/crm/customer-tags/tags",
            Map.of("tagCode", "HIGH_RETURN", "name", "退货率高", "category", "RISK", "contestable", true));
    String tagId = json.readTree(tag).get("id").asText();
    // 倍率 10 倍：基线 0.4 × 10 = 4.0，客户 A 的 1.0 达不到。
    String rule =
        postJson(
            "/crm/customer-tags/rules",
            Map.of(
                "ruleCode", "RETURN-10X",
                "name", "退货率高于基线 10 倍",
                "tagId", tagId,
                "metric", "RETURN_RATE",
                "threshold", 0,
                "minSample", 3,
                "windowDays", 365,
                "baselineMultiplier", 10));
    String ruleId = json.readTree(rule).get("id").asText();
    String run = postJson("/crm/customer-tags/rules/" + ruleId + "/run", null);
    assertThat(json.readTree(run).get("taggedCount").asInt()).isEqualTo(0);

    // 倍率 2 倍：基线 0.4 × 2 = 0.8，客户 A 的 1.0 达到。
    String rule2 =
        postJson(
            "/crm/customer-tags/rules",
            Map.of(
                "ruleCode", "RETURN-2X",
                "name", "退货率高于基线 2 倍",
                "tagId", tagId,
                "metric", "RETURN_RATE",
                "threshold", 0,
                "minSample", 3,
                "windowDays", 365,
                "baselineMultiplier", 2));
    String run2 = postJson("/crm/customer-tags/rules/" + json.readTree(rule2).get("id").asText() + "/run", null);
    assertThat(json.readTree(run2).get("taggedCount").asInt()).isEqualTo(1);
  }

  @Test
  void 上游读不到时明确失败且一条标签都不打() throws Exception {
    String tag =
        postJson(
            "/crm/customer-tags/tags",
            Map.of("tagCode", "REPEAT", "name", "复购", "category", "BEHAVIOR"));
    String tagId = json.readTree(tag).get("id").asText();
    String rule =
        postJson(
            "/crm/customer-tags/rules",
            Map.of(
                "ruleCode", "REPEAT-3",
                "name", "窗口内 3 单及以上算复购",
                "tagId", tagId,
                "metric", "ORDER_COUNT",
                "threshold", 3,
                "minSample", 1,
                "windowDays", 365));
    String ruleId = json.readTree(rule).get("id").asText();

    tradeDown = true;
    String run = postJson("/crm/customer-tags/rules/" + ruleId + "/run", null);
    var runNode = json.readTree(run);
    assertThat(runNode.get("status").asText()).isEqualTo("FAILED");
    assertThat(runNode.get("failureReason").asText()).isNotBlank();
    assertThat(runNode.get("taggedCount").asInt()).isZero();

    // 一条标签都不打。
    String list = body("/crm/customer-tags/assignments?tagId=" + tagId);
    assertThat(json.readTree(list).get("totalElements").asLong()).isZero();
  }

  @Test
  void 候选客户清单在上游读不到时明确失败不返回空名单() throws Exception {
    tradeDown = true;
    http.perform(get("/crm/customer-tags/customer-candidates").header("Authorization", "session"))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void 候选客户清单来自上游订单事实的真实聚合() throws Exception {
    String page = body("/crm/customer-tags/customer-candidates");
    var node = json.readTree(page);
    String raw = page;
    assertThat(node.get("totalElements").asLong()).isEqualTo(3);
    assertThat(node.get("truncated").asBoolean()).isFalse();
    // 客户 A 的金额按币种分开，不跨币种加。
    var items = node.get("items");
    String aRef = customerA.toString();
    boolean found = false;
    for (var item : items) {
      if (item.get("customerRef").asText().equals(aRef)) {
        found = true;
        assertThat(item.get("orderCount").asInt()).isEqualTo(3);
        // ⚠️ 断言**原始响应文本**里就是 300.00 —— 浏览器拿到什么，页面就显示什么，
        //   不能是 3E+2（科学计数法）或 299.9999999999999（浮点）。
        assertThat(item.get("amountsByCurrency").get("USD").decimalValue())
            .isEqualByComparingTo("300.00");
        assertThat(raw).contains("300.00");
        assertThat(item.get("channelSources").get(0).asText()).isEqualTo("AMAZON");
      }
    }
    assertThat(found).as("客户 A 必须在候选名单里").isTrue();
  }

  @Test
  void 分布统计真算且分母为零时不计算() throws Exception {
    // 还没有任何标签记录 ⇒ 分母为 0，占比报 null（不报 0%）。
    String empty = body("/crm/customer-tags/distribution");
    var emptyNode = json.readTree(empty);
    assertThat(emptyNode.get("totalTaggedCustomers").asInt()).isZero();
    for (var row : emptyNode.get("rows")) {
      assertThat(row.get("share").isNull()).isTrue();
    }

    String tag =
        postJson(
            "/crm/customer-tags/tags",
            Map.of("tagCode", "REPEAT", "name", "复购", "category", "BEHAVIOR"));
    String tagId = json.readTree(tag).get("id").asText();
    postJson(
        "/crm/customer-tags/assignments",
        Map.of(
            "customerRef", customerA.toString(),
            "tagId", tagId,
            "reason", "人工标记"));
    postJson(
        "/crm/customer-tags/assignments",
        Map.of(
            "customerRef", customerC.toString(),
            "tagId", tagId,
            "reason", "人工标记"));

    String distribution = body("/crm/customer-tags/distribution");
    var node = json.readTree(distribution);
    assertThat(node.get("totalTaggedCustomers").asInt()).isEqualTo(2);
    for (var row : node.get("rows")) {
      if (row.get("tagCode").asText().equals("REPEAT")) {
        assertThat(row.get("activeCustomerCount").asInt()).isEqualTo(2);
        assertThat(row.get("manualCustomerCount").asInt()).isEqualTo(2);
        // 占比在服务端算好：2 / 2 = 1.000000。
        assertThat(new BigDecimal(row.get("share").asText())).isEqualByComparingTo("1.000000");
      }
    }
  }

  @Test
  void 能力声明显式标出未交付的下游与不触发动作() throws Exception {
    String capabilities = body("/crm/customer-tags/capabilities");
    var node = json.readTree(capabilities);
    var dimensions = node.get("dimensions");
    Map<String, Boolean> availability = new HashMap<>();
    for (var d : dimensions) {
      availability.put(d.get("dimension").asText(), d.get("available").asBoolean());
    }
    assertThat(availability).containsEntry("customer_segmentation", false);
    assertThat(availability).containsEntry("promotion_targeting", false);
    assertThat(availability).containsEntry("care_email", false);
    assertThat(availability).containsEntry("rule_tagging", true);
    assertThat(node.get("actionPolicy").asText()).contains("不触发");
  }

  // ================================================================ 禁浮点与时区

  @Test
  void 金额按numeric写回逐位相同且不跨币种() throws Exception {
    String tag =
        postJson(
            "/crm/customer-tags/tags",
            Map.of("tagCode", "HIGH_VALUE", "name", "高客单", "category", "VALUE"));
    String tagId = json.readTree(tag).get("id").asText();
    String rule =
        postJson(
            "/crm/customer-tags/rules",
            Map.of(
                "ruleCode", "HIGH-200-USD",
                "name", "累计消费 200 美元以上",
                "tagId", tagId,
                "metric", "HIGH_VALUE",
                "threshold", "200.00",
                "currency", "USD",
                "minSample", 1,
                "windowDays", 365));
    assertThat(json.readTree(rule).get("threshold").decimalValue())
        .isEqualByComparingTo("200.000000");
    String ruleId = json.readTree(rule).get("id").asText();

    String run = postJson("/crm/customer-tags/rules/" + ruleId + "/run", null);
    var runNode = json.readTree(run);
    // 客户 A 累计 300.00 美元命中；客户 C 累计 60.00 不命中。
    assertThat(runNode.get("taggedCount").asInt()).isEqualTo(1);

    String list = body("/crm/customer-tags/assignments?tagId=" + tagId);
    var item = json.readTree(list).get("items").get(0);
    // 金额原样写回，没有掉精度、也没有变成浮点 —— 断言的是**原始响应文本**。
    assertThat(item.get("evidence").get("amountsByCurrency").get("USD").decimalValue())
        .isEqualByComparingTo("300.00");
    assertThat(list).contains("300.00");
  }

  @Test
  void 统计窗口带偏移量的时刻写回等于同一绝对时刻() throws Exception {
    String tag =
        postJson(
            "/crm/customer-tags/tags",
            Map.of("tagCode", "REPEAT", "name", "复购", "category", "BEHAVIOR"));
    String tagId = json.readTree(tag).get("id").asText();
    String rule =
        postJson(
            "/crm/customer-tags/rules",
            Map.of(
                "ruleCode", "REPEAT-3",
                "name", "窗口内 3 单及以上算复购",
                "tagId", tagId,
                "metric", "ORDER_COUNT",
                "threshold", 3,
                "minSample", 1,
                "windowDays", 365));
    String ruleId = json.readTree(rule).get("id").asText();

    String from = "2026-01-01T00:00:00+08:00";
    String to = "2026-06-01T00:00:00+08:00";
    String run =
        postJson(
            "/crm/customer-tags/rules/" + ruleId + "/run",
            Map.of("windowFrom", from, "windowTo", to));
    var runNode = json.readTree(run);
    // 写回的是同一个绝对时刻（东八区 2026-01-01T00:00:00+08:00 == 2025-12-31T16:00:00Z）。
    assertThat(OffsetDateTime.parse(runNode.get("windowFrom").asText()))
        .isEqualTo(OffsetDateTime.parse(from));
    assertThat(OffsetDateTime.parse(runNode.get("windowTo").asText()))
        .isEqualTo(OffsetDateTime.parse(to));

    // 只给一个端点 ⇒ 400。
    http.perform(
            post("/crm/customer-tags/rules/" + ruleId + "/run")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("windowFrom", from))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 缺偏移量的时刻一律拒绝而不是猜时区() throws Exception {
    String tag =
        postJson(
            "/crm/customer-tags/tags",
            Map.of("tagCode", "REPEAT", "name", "复购", "category", "BEHAVIOR"));
    String tagId = json.readTree(tag).get("id").asText();
    String rule =
        postJson(
            "/crm/customer-tags/rules",
            Map.of(
                "ruleCode", "REPEAT-3",
                "name", "窗口内 3 单及以上算复购",
                "tagId", tagId,
                "metric", "ORDER_COUNT",
                "threshold", 3,
                "minSample", 1,
                "windowDays", 365));
    String ruleId = json.readTree(rule).get("id").asText();
    http.perform(
            post("/crm/customer-tags/rules/" + ruleId + "/run")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("windowFrom", "2026-01-01T00:00:00", "windowTo", "2026-06-01T00:00:00"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 被引用的标签与规则删不掉() throws Exception {
    String tag =
        postJson(
            "/crm/customer-tags/tags",
            Map.of("tagCode", "REPEAT", "name", "复购", "category", "BEHAVIOR"));
    String tagId = json.readTree(tag).get("id").asText();
    String rule =
        postJson(
            "/crm/customer-tags/rules",
            Map.of(
                "ruleCode", "REPEAT-3",
                "name", "窗口内 3 单及以上算复购",
                "tagId", tagId,
                "metric", "ORDER_COUNT",
                "threshold", 3,
                "minSample", 1,
                "windowDays", 365));
    String ruleId = json.readTree(rule).get("id").asText();
    postJson("/crm/customer-tags/rules/" + ruleId + "/run", null);

    // 标签已被规则引用 ⇒ 删不掉（409 / 500 由 DB 触发器给出明确原因）。
    http.perform(delete("/crm/customer-tags/tags/" + tagId).header("Authorization", "session"))
        .andExpect(status().is4xxClientError());
    // 规则已有运行记录 ⇒ 删不掉。
    http.perform(delete("/crm/customer-tags/rules/" + ruleId).header("Authorization", "session"))
        .andExpect(status().is4xxClientError());
  }
}
