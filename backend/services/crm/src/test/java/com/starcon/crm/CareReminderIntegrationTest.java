package com.starcon.crm;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * 15.5.2.1 关怀与催办的集成测试。
 *
 * <p>跑的是 AGENTS.md 要求的<b>真实 PostgreSQL 16 + FORCE RLS</b>。租户隔离三断言在
 * {@link CareReminderTenancyIntegrationTest} 里，本类专攻业务不变量：
 *
 * <ul>
 *   <li><b>🔴 加粗句「遵守频次上限与退订要求」</b>：
 *       <ul>
 *         <li>退订闸真的拦得住（走 15.3.2.2 的退订名单，<b>不另建退订表</b>）；
 *         <li>频次闸真的拦得住；</li>
 *         <li>被拦的触达<b>留痕</b>，且带**明确原因**（不是静默跳过）。</li>
 *       </ul>
 *   </li>
 *   <li><b>🔴 加粗句「催付有合规边界：部分平台限制催付话术与频次，规则需按平台配置」</b>：
 *       白名单外的平台**建不出催付规则**，且**一行都不写</b>。</li>
 *   <li><b>🔴 催评只对已签收订单成立</b>：未签收的订单**不生成**催评任务，
 *       且生成结果里如实报出未签收的条数。</li>
 *   <li><b>催付只对未付款订单成立</b>：已付款与支付状态未知的订单都不纳入。</li>
 *   <li><b>真分页真筛选</b>：规则与任务的总数、页数、筛选都来自真实查询。</li>
 *   <li><b>禁浮点 / 时区不静默偏移</b>：计划时刻写回等于同一个绝对时刻。</li>
 *   <li><b>统计口径服务端算</b>：打开数 / 点击数 / 两类拦截数由 SQL 聚合得出。</li>
 * </ul>
 *
 * <p>⚠️ 本类所有**直连数据库**的读写都在 {@code tenants.run(tenant, …)} 里 ——
 * RLS 是<b>强制</b>的，事务外没有租户上下文时一行都看不到。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CareReminderIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID userId = UUID.randomUUID();
  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  // ---- 上游夹具（真的起 HTTP 服务，**不 mock 上游服务**）----
  static final List<CustomerFactsPort.OrderView> upstreamOrders = new CopyOnWriteArrayList<>();
  static final List<CustomerFactsPort.AftersalesCaseView> upstreamCases =
      new CopyOnWriteArrayList<>();
  static final List<OrderPaymentStatusPort.PaymentCheckView> upstreamPayments =
      new CopyOnWriteArrayList<>();
  static final List<SignoffDeliveredOrderPort.DeliveredCaseView> upstreamDelivered =
      new CopyOnWriteArrayList<>();
  static final List<SignoffDeliveredOrderPort.ShopView> upstreamShops = new CopyOnWriteArrayList<>();

  static HttpServer identity;
  static HttpServer trade;
  static HttpServer channel;

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，隔离断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role crm_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    List<String> permissions =
        List.of(
            "crm.care_reminder.read",
            "crm.care_reminder.update",
            "crm.contact.read",
            "crm.contact.update");
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
          e ->
              write(
                  e,
                  Map.of(
                      "rows",
                      List.copyOf(upstreamOrders),
                      "page",
                      0,
                      "size",
                      upstreamOrders.size(),
                      "total",
                      upstreamOrders.size())));
      trade.createContext(
          "/trade/aftersales/cases",
          e ->
              write(
                  e,
                  Map.of(
                      "rows",
                      List.copyOf(upstreamCases),
                      "page",
                      0,
                      "size",
                      upstreamCases.size(),
                      "total",
                      upstreamCases.size())));
      trade.createContext(
          "/trade/order-address-payment-check/checks",
          e ->
              write(
                  e,
                  Map.of(
                      "items",
                      List.copyOf(upstreamPayments),
                      "total",
                      upstreamPayments.size(),
                      "page",
                      0,
                      "size",
                      upstreamPayments.size(),
                      "totalPages",
                      upstreamPayments.isEmpty() ? 0 : 1)));
      trade.setExecutor(Executors.newFixedThreadPool(2));
      trade.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    try {
      channel = HttpServer.create(new InetSocketAddress(0), 0);
      channel.createContext(
          "/channel/logistics-pickup-signoff/cases",
          e ->
              write(
                  e,
                  Map.of(
                      "items",
                      List.copyOf(upstreamDelivered),
                      "page",
                      0,
                      "size",
                      upstreamDelivered.size(),
                      "totalElements",
                      upstreamDelivered.size(),
                      "totalPages",
                      upstreamDelivered.isEmpty() ? 0 : 1)));
      channel.createContext("/channel/shops", e -> write(e, List.copyOf(upstreamShops)));
      channel.setExecutor(Executors.newFixedThreadPool(2));
      channel.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static void write(com.sun.net.httpserver.HttpExchange e, Object body) {
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

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "crm_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://127.0.0.1:" + trade.getAddress().getPort());
    r.add("CHANNEL_URL", () -> "http://127.0.0.1:" + channel.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  static final UUID SHOPIFY_SHOP = UUID.randomUUID();

  void seedShop() {
    upstreamShops.clear();
    upstreamShops.add(
        new SignoffDeliveredOrderPort.ShopView(
            SHOPIFY_SHOP, "SHOP-1", "测试店铺", "SHOPIFY", "Shopify", "OWN", "ACTIVE"));
  }

  CustomerFactsPort.OrderView order(
      String orderNo, String source, UUID customer, String buyer, String amount) {
    return new CustomerFactsPort.OrderView(
        UUID.randomUUID(),
        orderNo,
        source,
        "SUBMITTED",
        "USD",
        new BigDecimal(amount),
        customer,
        buyer,
        OffsetDateTime.now(ZoneOffset.UTC).minusDays(2),
        OffsetDateTime.now(ZoneOffset.UTC).minusDays(2));
  }

  void seedOrder(CustomerFactsPort.OrderView row) {
    upstreamOrders.add(row);
  }

  void seedPayment(String orderNo, String status) {
    upstreamPayments.add(
        new OrderPaymentStatusPort.PaymentCheckView(
            UUID.randomUUID(),
            UUID.randomUUID(),
            orderNo,
            "SHOPIFY",
            new BigDecimal("100.00"),
            OffsetDateTime.now(ZoneOffset.UTC).minusDays(2),
            "PREPAID",
            status,
            status == null ? null : "PLATFORM_CALLBACK",
            status == null ? null : OffsetDateTime.now(ZoneOffset.UTC).minusDays(1)));
  }

  void seedDelivered(String orderNo, UUID channelId) {
    upstreamDelivered.add(
        new SignoffDeliveredOrderPort.DeliveredCaseView(
            UUID.randomUUID(),
            "SC-" + orderNo,
            "BATCH-1",
            "WB-" + orderNo,
            "SF",
            channelId,
            orderNo,
            "US",
            OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
            OffsetDateTime.now(ZoneOffset.UTC).minusHours(6),
            "DELIVERED",
            "DELIVERED"));
  }

  @BeforeEach
  void reset() throws Exception {
    upstreamOrders.clear();
    upstreamCases.clear();
    upstreamPayments.clear();
    upstreamDelivered.clear();
    upstreamShops.clear();
    seedShop();
    // ⚠️ TRUNCATE 走**迁移用户**（超级用户）直连：运行角色 crm_app 没有 TRUNCATE 权限。
    //   与同服务既有测试同一手法。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table crm_care_reminder_stat, crm_care_reminder_task,"
              + " crm_care_reminder_rule, crm_customer_opt_out_event, crm_customer_opt_out"
              + " cascade");
    }
  }

  // ================================================================ 工具

  String postJson(String path, String body) throws Exception {
    var result =
        http.perform(
                post(path)
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andReturn();
    if (result.getResponse().getStatus() != 200) {
      throw new AssertionError(
          "HTTP " + result.getResponse().getStatus() + " : "
              + result.getResponse().getContentAsString()
              + " resolved=" + result.getResolvedException());
    }
    return result.getResponse().getContentAsString();
  }

  String getJson(String path) throws Exception {
    var result = http.perform(get(path).header("Authorization", "session")).andReturn();
    if (result.getResponse().getStatus() != 200) {
      throw new AssertionError(
          "HTTP " + result.getResponse().getStatus() + " : "
              + result.getResponse().getContentAsString()
              + " resolved=" + result.getResolvedException());
    }
    return result.getResponse().getContentAsString();
  }

  long countRows(String table) {
    return tenants.run(tenant, () -> sql.fetchOne("select count(*) from " + table).get(0, Long.class));
  }

  /**
   * 失败时把**原因**取出来。
   *
   * <p>⚠️ 不能读响应体：{@code ResponseStatusException} 走 Spring 的 {@code sendError}，
   * MockMvc 里**响应体是空的**，原因在 {@code getResolvedException()} 上
   * （与本服务既有测试同一手法）。
   */
  String errorOf(String path, String body) throws Exception {
    var resolved =
        http.perform(
                post(path)
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andReturn()
            .getResolvedException();
    assertThat(resolved).as("应当抛出带原因的异常").isNotNull();
    String message = resolved.getMessage() == null ? "" : resolved.getMessage();
    if (resolved instanceof org.springframework.web.server.ResponseStatusException rse
        && rse.getReason() != null) {
      message = message + " " + rse.getReason();
    }
    return message;
  }

  /** 登记一条退订（走 15.3.2.2 的真端点 —— 本模块**只读**它）。 */
  void grantOptOut(String customerRef, String scope) throws Exception {
    postJson(
        "/crm/customer-contacts/opt-outs",
        json.writeValueAsString(
            Map.of(
                "customerRef", customerRef,
                "scope", scope,
                "reason", "客户明确表示不要再联系我（测试夹具）")));
  }

  String ruleBody(
      String code,
      String type,
      String platform,
      int limit,
      int windowHours,
      String quietStart,
      String quietEnd) {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("ruleCode", code);
    body.put("ruleName", "规则 " + code);
    body.put("reminderType", type);
    body.put("platformCode", platform);
    body.put("frequencyLimit", limit);
    body.put("frequencyWindowHours", windowHours);
    body.put("sendTime", "09:00");
    body.put("timezone", "UTC");
    body.put("subject", "包裹已发出");
    body.put("body", "你的订单已发出，点击查看物流。");
    if (quietStart != null) body.put("quietHoursStart", quietStart);
    if (quietEnd != null) body.put("quietHoursEnd", quietEnd);
    try {
      return json.writeValueAsString(body);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // ================================================================ 规则

  @Test
  void 触达规则真的落库且可读回() throws Exception {
    String created = postJson("/crm/care-reminders/rules", ruleBody("R-SHIP-1", "SHIPMENT_NOTICE", "SHOPIFY", 2, 24, null, null));
    String id = json.readTree(created).get("id").asText();
    assertThat(countRows("crm_care_reminder_rule")).isEqualTo(1);

    String fetched = getJson("/crm/care-reminders/rules/" + id);
    var node = json.readTree(fetched);
    assertThat(node.get("ruleCode").asText()).isEqualTo("R-SHIP-1");
    assertThat(node.get("reminderType").asText()).isEqualTo("SHIPMENT_NOTICE");
    assertThat(node.get("platformCode").asText()).isEqualTo("SHOPIFY");
    assertThat(node.get("frequencyLimit").asInt()).isEqualTo(2);
    assertThat(node.get("frequencyWindowHours").asInt()).isEqualTo(24);
  }

  @Test
  void 催付规则必须按平台配置且平台须在白名单内_白名单外一行都不写() throws Exception {
    var response =
        http.perform(
                post("/crm/care-reminders/rules")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(ruleBody("R-PAY-AMAZON", "PAYMENT_DEMAND", "AMAZON", 1, 72, null, null)))
            .andReturn();
    assertThat(response.getResponse().getStatus()).isEqualTo(400);
    assertThat(errorOf("/crm/care-reminders/rules", ruleBody("R-PAY-AMAZON", "PAYMENT_DEMAND", "AMAZON", 1, 72, null, null)))
        .contains("该平台不支持催付");
    // 🔴 拒绝之后**一行都不写**（不是「写了再报错」）。
    assertThat(countRows("crm_care_reminder_rule")).isZero();
  }

  @Test
  void 规则编码在租户内唯一_重复登记被拒且不覆盖既有那条() throws Exception {
    postJson("/crm/care-reminders/rules", ruleBody("R-DUP", "SHIPMENT_NOTICE", "SHOPIFY", 1, 24, null, null));
    var response =
        http.perform(
                post("/crm/care-reminders/rules")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(ruleBody("R-DUP", "LOGISTICS_REMINDER", "SHOPIFY", 3, 24, null, null)))
            .andReturn();
    assertThat(response.getResponse().getStatus()).isEqualTo(409);
    assertThat(countRows("crm_care_reminder_rule")).isEqualTo(1);
  }

  @Test
  void 静默时段只填一半被拒() throws Exception {
    var response =
        http.perform(
                post("/crm/care-reminders/rules")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(ruleBody("R-Q1", "SHIPMENT_NOTICE", "SHOPIFY", 1, 24, "22:00", null)))
            .andReturn();
    assertThat(response.getResponse().getStatus()).isEqualTo(400);
    assertThat(countRows("crm_care_reminder_rule")).isZero();
  }

  // ================================================================ 生成 + 两道闸

  @Test
  void 退订闸拦得住_落一条带明确原因的拦截任务而不是静默跳过() throws Exception {
    UUID buyer = UUID.randomUUID();
    seedOrder(order("SO-OPT", "SHOPIFY", buyer, "买家甲", "88.00"));
    String ruleId = createRule("R-OPT", "SHIPMENT_NOTICE", "SHOPIFY", 5, 24);
    grantOptOut(buyer.toString(), "ALL");

    String result =
        postJson("/crm/care-reminders/rules/" + ruleId + "/generate", "{\"plannedAt\":\"2026-10-22T02:00:00Z\"}");
    var node = json.readTree(result);
    assertThat(node.get("created").asInt()).isZero();
    assertThat(node.get("blocked").asInt()).isEqualTo(1);

    // 拦截留痕 + 明确原因。
    List<Map<String, Object>> rows = taskRows();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("status")).isEqualTo("BLOCKED");
    assertThat(rows.get(0).get("blockCode")).isEqualTo("OPT_OUT");
    assertThat(String.valueOf(rows.get(0).get("blockReason"))).contains("客户已退订");
    assertThat(String.valueOf(rows.get(0).get("blockReason"))).contains("ALL");
  }

  @Test
  void 频次闸拦得住_超限的那一条落成带明确原因的拦截任务() throws Exception {
    UUID buyer = UUID.randomUUID();
    seedOrder(order("SO-FREQ", "SHOPIFY", buyer, "买家乙", "120.00"));
    String ruleId = createRule("R-FREQ", "SHIPMENT_NOTICE", "SHOPIFY", 1, 24);

    // 第一次：排期成功。
    String first =
        postJson("/crm/care-reminders/rules/" + ruleId + "/generate", "{\"plannedAt\":\"2026-10-22T02:00:00Z\"}");
    assertThat(json.readTree(first).get("created").asInt()).isEqualTo(1);

    // 第二次（同一天同一人）：命中幂等，不重复记账 —— 这是幂等，不是频次。
    String second =
        postJson("/crm/care-reminders/rules/" + ruleId + "/generate", "{\"plannedAt\":\"2026-10-22T02:00:00Z\"}");
    assertThat(json.readTree(second).get("idempotentHits").asInt()).isEqualTo(1);
    assertThat(json.readTree(second).get("created").asInt()).isZero();

    // 换一天：幂等键不同 ⇒ 走频次闸 ⇒ 被拦。
    String third =
        postJson("/crm/care-reminders/rules/" + ruleId + "/generate", "{\"plannedAt\":\"2026-10-23T02:00:00Z\"}");
    assertThat(json.readTree(third).get("blocked").asInt()).isEqualTo(1);
    assertThat(json.readTree(third).get("created").asInt()).isZero();

    List<Map<String, Object>> rows = taskRows();
    assertThat(rows).hasSize(2);
    var blocked = rows.stream().filter(r -> "BLOCKED".equals(r.get("status"))).toList();
    assertThat(blocked).hasSize(1);
    assertThat(blocked.get(0).get("blockCode")).isEqualTo("FREQUENCY_LIMIT");
    assertThat(String.valueOf(blocked.get(0).get("blockReason"))).contains("已触达");
    assertThat(String.valueOf(blocked.get(0).get("blockReason"))).contains("上限 1 次");
  }

  @Test
  void 频次上限为零的规则不产生任何触达_每条都带明确原因() throws Exception {
    UUID buyer = UUID.randomUUID();
    seedOrder(order("SO-ZERO", "SHOPIFY", buyer, "买家丙", "50.00"));
    String ruleId = createRule("R-ZERO", "SHIPMENT_NOTICE", "SHOPIFY", 0, 24);

    String result =
        postJson("/crm/care-reminders/rules/" + ruleId + "/generate", "{\"plannedAt\":\"2026-10-22T02:00:00Z\"}");
    assertThat(json.readTree(result).get("blocked").asInt()).isEqualTo(1);
    assertThat(json.readTree(result).get("created").asInt()).isZero();
    assertThat(String.valueOf(json.readTree(result).get("reasons"))).contains("频次上限是 0");
  }

  @Test
  void 正常路径真的落一条待发任务且计划时刻写回等于同一个绝对时刻() throws Exception {
    UUID buyer = UUID.randomUUID();
    seedOrder(order("SO-OK", "SHOPIFY", buyer, "买家丁", "99.00"));
    String ruleId = createRule("R-OK", "SHIPMENT_NOTICE", "SHOPIFY", 3, 24);

    String result =
        postJson("/crm/care-reminders/rules/" + ruleId + "/generate", "{\"plannedAt\":\"2026-10-22T10:00:00+08:00\"}");
    assertThat(json.readTree(result).get("created").asInt()).isEqualTo(1);

    List<Map<String, Object>> rows = taskRows();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("status")).isEqualTo("QUEUED");
    assertThat(rows.get(0).get("customerRef")).isEqualTo(buyer.toString());
    assertThat(rows.get(0).get("orderReference")).isEqualTo("SO-OK");
    // 🔴 时区不静默偏移：10:00+08:00 ⇒ 02:00Z（**同一个绝对时刻**）。
    //   ⚠️ 断言比的是**绝对时刻**，不是字符串 —— PG 按会话时区渲染 timestamptz，
    //   字符串前缀会随机器时区变（那是断言写错，不是数据错）。
    assertThat(((OffsetDateTime) rows.get(0).get("plannedAt")).toInstant())
        .isEqualTo(OffsetDateTime.parse("2026-10-22T10:00:00+08:00").toInstant());
  }

  @Test
  void 静默时段把计划时刻顺延到静默结束而不是拒绝() throws Exception {
    UUID buyer = UUID.randomUUID();
    seedOrder(order("SO-QUIET", "SHOPIFY", buyer, "买家戊", "60.00"));
    String ruleId = createRule("R-QUIET", "SHIPMENT_NOTICE", "SHOPIFY", 3, 24, "22:00", "08:00");

    // 计划时刻 23:30Z ⇒ 落在 22:00 - 08:00 的静默时段 ⇒ 顺延到 08:00Z。
    String result =
        postJson("/crm/care-reminders/rules/" + ruleId + "/generate", "{\"plannedAt\":\"2026-10-22T23:30:00Z\"}");
    assertThat(json.readTree(result).get("created").asInt()).isEqualTo(1);

    List<Map<String, Object>> rows = taskRows();
    assertThat(rows).hasSize(1);
    // 顺延到静默结束（UTC 08:00）⇒ 同一个绝对时刻。
    assertThat(((OffsetDateTime) rows.get(0).get("plannedAt")).toInstant())
        .isEqualTo(OffsetDateTime.parse("2026-10-23T08:00:00Z").toInstant());
    assertThat(String.valueOf(rows.get(0).get("note"))).contains("静默时段");
  }

  @Test
  void 催评只对已签收订单成立_未签收不生成任务但如实报出条数() throws Exception {
    UUID deliveredBuyer = UUID.randomUUID();
    UUID pendingBuyer = UUID.randomUUID();
    seedOrder(order("SO-DELIVERED", "SHOPIFY", deliveredBuyer, "已签收买家", "70.00"));
    seedOrder(order("SO-PENDING", "SHOPIFY", pendingBuyer, "未签收买家", "80.00"));
    seedDelivered("SO-DELIVERED", SHOPIFY_SHOP);
    String ruleId = createRule("R-REVIEW", "REVIEW_REQUEST", "SHOPIFY", 3, 24);

    String result =
        postJson("/crm/care-reminders/rules/" + ruleId + "/generate", "{\"plannedAt\":\"2026-10-22T02:00:00Z\"}");
    var node = json.readTree(result);
    assertThat(node.get("created").asInt()).isEqualTo(1);
    assertThat(String.valueOf(node.get("note"))).contains("未签收 1 张");

    List<Map<String, Object>> rows = taskRows();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("customerRef")).isEqualTo(deliveredBuyer.toString());
    assertThat(rows.get(0).get("orderReference")).isEqualTo("SO-DELIVERED");
  }

  @Test
  void 催付只对未付款订单成立_已付款与支付状态未知都不纳入() throws Exception {
    UUID unpaidBuyer = UUID.randomUUID();
    UUID paidBuyer = UUID.randomUUID();
    UUID unknownBuyer = UUID.randomUUID();
    seedOrder(order("SO-UNPAID", "SHOPIFY", unpaidBuyer, "未付款买家", "30.00"));
    seedOrder(order("SO-PAID", "SHOPIFY", paidBuyer, "已付款买家", "40.00"));
    seedOrder(order("SO-UNKNOWN", "SHOPIFY", unknownBuyer, "状态未知买家", "50.00"));
    seedPayment("SO-UNPAID", "UNPAID");
    seedPayment("SO-PAID", "PAID");
    // 故意不给 SO-UNKNOWN 登记支付状态观察值。
    String ruleId = createRule("R-PAY", "PAYMENT_DEMAND", "SHOPIFY", 3, 72);

    String result =
        postJson("/crm/care-reminders/rules/" + ruleId + "/generate", "{\"plannedAt\":\"2026-10-22T02:00:00Z\"}");
    var node = json.readTree(result);
    assertThat(node.get("created").asInt()).isEqualTo(1);
    assertThat(String.valueOf(node.get("note"))).contains("支付状态未知 1 张跳过");

    List<Map<String, Object>> rows = taskRows();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("customerRef")).isEqualTo(unpaidBuyer.toString());
    assertThat(rows.get(0).get("reminderType")).isEqualTo("PAYMENT_DEMAND");
  }

  // ================================================================ 发送结果与统计

  @Test
  void 人工交接登记发送结果_失败必须写原因且写回真的落库() throws Exception {
    UUID buyer = UUID.randomUUID();
    seedOrder(order("SO-DISP", "SHOPIFY", buyer, "买家己", "20.00"));
    String ruleId = createRule("R-DISP", "SHIPMENT_NOTICE", "SHOPIFY", 3, 24);
    postJson("/crm/care-reminders/rules/" + ruleId + "/generate", "{\"plannedAt\":\"2026-10-22T02:00:00Z\"}");
    String taskId = String.valueOf(taskRows().get(0).get("id"));

    // 失败必须带原因。
    var bad =
        http.perform(
                post("/crm/care-reminders/tasks/" + taskId + "/dispatch")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"outcome\":\"FAILED\"}"))
            .andReturn();
    assertThat(bad.getResponse().getStatus()).isEqualTo(400);

    // 带原因的失败：真的落库。
    postJson(
        "/crm/care-reminders/tasks/" + taskId + "/dispatch",
        "{\"outcome\":\"FAILED\",\"failureReason\":\"平台接口返回 503\",\"platformReceipt\":\"R-1\"}");
    List<Map<String, Object>> rows = taskRows();
    assertThat(rows.get(0).get("status")).isEqualTo("FAILED");
    assertThat(rows.get(0).get("failureReason")).isEqualTo("平台接口返回 503");

    // 重复登记被拒（否则「发没发出去」会有两个答案）。
    var again =
        http.perform(
                post("/crm/care-reminders/tasks/" + taskId + "/dispatch")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"outcome\":\"SENT\"}"))
            .andReturn();
    assertThat(again.getResponse().getStatus()).isEqualTo(409);
  }

  @Test
  void 统计口径由服务端算_打开数点击数与两类拦截数都不是前端算的() throws Exception {
    UUID buyer = UUID.randomUUID();
    UUID optedOut = UUID.randomUUID();
    seedOrder(order("SO-S1", "SHOPIFY", buyer, "买家庚", "20.00"));
    seedOrder(order("SO-S2", "SHOPIFY", optedOut, "买家辛", "20.00"));
    String ruleId = createRule("R-STAT", "SHIPMENT_NOTICE", "SHOPIFY", 5, 24);
    grantOptOut(optedOut.toString(), "ALL");

    postJson("/crm/care-reminders/rules/" + ruleId + "/generate", "{\"plannedAt\":\"2026-10-22T02:00:00Z\"}");
    List<Map<String, Object>> rows = taskRows();
    assertThat(rows).hasSize(2);
    String sentTaskId = null;
    for (var r : rows) {
      if ("QUEUED".equals(r.get("status"))) sentTaskId = String.valueOf(r.get("id"));
    }
    assertThat(sentTaskId).isNotNull();

    // 登记两条打开、一条点击。
    postJson("/crm/care-reminders/stats/events", "{\"taskId\":\"" + sentTaskId + "\",\"eventType\":\"OPEN\",\"source\":\"webhook\"}");
    postJson("/crm/care-reminders/stats/events", "{\"taskId\":\"" + sentTaskId + "\",\"eventType\":\"OPEN\",\"source\":\"webhook\"}");
    postJson("/crm/care-reminders/stats/events", "{\"taskId\":\"" + sentTaskId + "\",\"eventType\":\"CLICK\",\"source\":\"webhook\"}");

    String stats = getJson("/crm/care-reminders/stats");
    var node = json.readTree(stats);
    assertThat(node.get("totalTasks").asLong()).isEqualTo(2);
    assertThat(node.get("queuedTasks").asLong()).isEqualTo(1);
    assertThat(node.get("blockedTasks").asLong()).isEqualTo(1);
    assertThat(node.get("optOutBlockedCount").asLong()).isEqualTo(1);
    assertThat(node.get("frequencyBlockedCount").asLong()).isZero();
    assertThat(node.get("openCount").asLong()).isEqualTo(2);
    assertThat(node.get("clickCount").asLong()).isEqualTo(1);
    assertThat(String.valueOf(node.get("note"))).contains("触达总数 2");

    // 单条任务上也有它自己的打开数与点击数。
    var one = json.readTree(getJson("/crm/care-reminders/tasks/" + sentTaskId));
    assertThat(one.get("openCount").asLong()).isEqualTo(2);
    assertThat(one.get("clickCount").asLong()).isEqualTo(1);
  }

  @Test
  void 平台打开点击回执未接入_端点如实答未接入且不写任何数据() throws Exception {
    UUID buyer = UUID.randomUUID();
    seedOrder(order("SO-PF", "SHOPIFY", buyer, "买家壬", "20.00"));
    String ruleId = createRule("R-PF", "SHIPMENT_NOTICE", "SHOPIFY", 3, 24);
    postJson("/crm/care-reminders/rules/" + ruleId + "/generate", "{\"plannedAt\":\"2026-10-22T02:00:00Z\"}");

    var response =
        http.perform(
                get("/crm/care-reminders/stats/platform-events").header("Authorization", "session"))
            .andReturn();
    assertThat(response.getResponse().getStatus()).isEqualTo(200);
    var node = json.readTree(response.getResponse().getContentAsString());
    assertThat(node.get("available").asBoolean()).as("平台回执未接入").isFalse();
    assertThat(node.get("reason").asText()).contains("未接入");
    // 🔴 未接入 ⇒ 不写任何统计数据。
    assertThat(countRows("crm_care_reminder_stat")).isZero();
  }

  // ================================================================ 触发器与引用完整性

  @Test
  void 触达任务必须挂在存在的规则上_引用完整性由触发器保证() {
    tenants.run(
        tenant,
        () -> {
          assertThatThrownBy(
                  () ->
                      sql.execute(
                          "insert into crm_care_reminder_task"
                              + "(id,tenant_id,task_no,rule_id,reminder_type,customer_ref,customer_name,"
                              + " shop_id,shop_name,platform_code,order_reference,subject,body,transport,"
                              + " status,planned_at,sent_at,failure_reason,block_code,block_reason,"
                              + " platform_receipt,idempotency_key,created_by,note,created_at,updated_at)"
                              + " values (?,?,'CR-X',?,'SHIPMENT_NOTICE','C1','',null,'','SHOPIFY','SO-1',"
                              + " '','','MANUAL_HANDOFF','QUEUED',?::timestamptz,null,'','','','',"
                              + " 'k1',null,'',?::timestamptz,?::timestamptz)",
                          UUID.randomUUID(),
                          tenant,
                          UUID.randomUUID(),
                          OffsetDateTime.now(ZoneOffset.UTC),
                          OffsetDateTime.now(ZoneOffset.UTC),
                          OffsetDateTime.now(ZoneOffset.UTC)))
              .as("挂在不存在的规则上的触达任务必须被拒绝")
              .hasMessageContaining("触达规则");
          return null;
        });
  }

  @Test
  void 打开点击统计必须挂在存在的任务上() {
    tenants.run(
        tenant,
        () -> {
          assertThatThrownBy(
                  () ->
                      sql.execute(
                          "insert into crm_care_reminder_stat"
                              + "(id,tenant_id,task_id,event_type,occurred_at,source,created_at)"
                              + " values (?,?,?,'OPEN',?::timestamptz,'',?::timestamptz)",
                          UUID.randomUUID(),
                          tenant,
                          UUID.randomUUID(),
                          OffsetDateTime.now(ZoneOffset.UTC),
                          OffsetDateTime.now(ZoneOffset.UTC)))
              .as("挂在不存在的任务上的统计必须被拒绝")
              .hasMessageContaining("触达任务");
          return null;
        });
  }

  @Test
  void 退订物理闸拦得住_退订客户根本落不了待发任务() throws Exception {
    UUID buyer = UUID.randomUUID();
    seedOrder(order("SO-GUARD", "SHOPIFY", buyer, "买家癸", "20.00"));
    String ruleId = createRule("R-GUARD", "SHIPMENT_NOTICE", "SHOPIFY", 3, 24);
    grantOptOut(buyer.toString(), "ALL");

    // 绕过服务层直接写一条「待发」任务 ⇒ 触发器必须挡住。
    tenants.run(
        tenant,
        () -> {
          assertThatThrownBy(
                  () ->
                      sql.execute(
                          "insert into crm_care_reminder_task"
                              + "(id,tenant_id,task_no,rule_id,reminder_type,customer_ref,customer_name,"
                              + " shop_id,shop_name,platform_code,order_reference,subject,body,transport,"
                              + " status,planned_at,sent_at,failure_reason,block_code,block_reason,"
                              + " platform_receipt,idempotency_key,created_by,note,created_at,updated_at)"
                              + " values (?,?,'CR-G',?,'SHIPMENT_NOTICE',?,'',null,'','SHOPIFY','SO-G',"
                              + " '','','MANUAL_HANDOFF','QUEUED',?::timestamptz,null,'','','','',"
                              + " 'kg',null,'',?::timestamptz,?::timestamptz)",
                          UUID.randomUUID(),
                          tenant,
                          UUID.fromString(ruleId),
                          buyer.toString(),
                          OffsetDateTime.now(ZoneOffset.UTC),
                          OffsetDateTime.now(ZoneOffset.UTC),
                          OffsetDateTime.now(ZoneOffset.UTC)))
              .as("退订客户的待发任务必须被触发器挡住")
              .hasMessageContaining("退订");
          return null;
        });
  }

  // ================================================================ 真分页

  @Test
  void 任务列表真分页真筛选() throws Exception {
    List<UUID> buyers = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      UUID buyer = UUID.randomUUID();
      buyers.add(buyer);
      seedOrder(order("SO-P" + i, "SHOPIFY", buyer, "买家" + i, "20.00"));
    }
    String ruleId = createRule("R-PAGE", "SHIPMENT_NOTICE", "SHOPIFY", 9, 24);
    postJson("/crm/care-reminders/rules/" + ruleId + "/generate", "{\"plannedAt\":\"2026-10-22T02:00:00Z\"}");
    assertThat(countRows("crm_care_reminder_task")).isEqualTo(5);

    var page = json.readTree(getJson("/crm/care-reminders/tasks?page=0&size=2"));
    assertThat(page.get("totalElements").asLong()).isEqualTo(5);
    assertThat(page.get("totalPages").asInt()).isEqualTo(3);
    assertThat(page.get("items")).hasSize(2);

    var filtered =
        json.readTree(
            getJson("/crm/care-reminders/tasks?customerRef=" + buyers.get(3) + "&page=0&size=50"));
    assertThat(filtered.get("totalElements").asLong()).isEqualTo(1);
  }

  // ================================================================ 小工具

  String createRule(String code, String type, String platform, int limit, int window) throws Exception {
    return createRule(code, type, platform, limit, window, null, null);
  }

  String createRule(
      String code,
      String type,
      String platform,
      int limit,
      int window,
      String quietStart,
      String quietEnd)
      throws Exception {
    String created =
        postJson("/crm/care-reminders/rules", ruleBody(code, type, platform, limit, window, quietStart, quietEnd));
    return json.readTree(created).get("id").asText();
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> taskRows() {
    return tenants.run(
        tenant,
        () -> {
          var out = new ArrayList<Map<String, Object>>();
          for (var r :
              sql.fetch(
                  "select id,task_no,rule_id,reminder_type,customer_ref,customer_name,platform_code,"
                      + " order_reference,status,planned_at,sent_at,failure_reason,block_code,"
                      + " block_reason,note from crm_care_reminder_task order by created_at, id")) {
            var m = new java.util.LinkedHashMap<String, Object>();
            // ⚠️ 断言用**驼峰**（与 API 字段一致），DB 列名是下划线 —— 这里显式映射，
            //   免得断言读到 null 却以为「没落库」。
            m.put("id", r.get("id", UUID.class));
            m.put("taskNo", r.get("task_no", String.class));
            m.put("ruleId", r.get("rule_id", UUID.class));
            m.put("reminderType", r.get("reminder_type", String.class));
            m.put("customerRef", r.get("customer_ref", String.class));
            m.put("customerName", r.get("customer_name", String.class));
            m.put("platformCode", r.get("platform_code", String.class));
            m.put("orderReference", r.get("order_reference", String.class));
            m.put("status", r.get("status", String.class));
            m.put("plannedAt", r.get("planned_at", OffsetDateTime.class));
            m.put("sentAt", r.get("sent_at", OffsetDateTime.class));
            m.put("failureReason", r.get("failure_reason", String.class));
            m.put("blockCode", r.get("block_code", String.class));
            m.put("blockReason", r.get("block_reason", String.class));
            m.put("note", r.get("note", String.class));
            out.add(m);
          }
          return out;
        });
  }
}
