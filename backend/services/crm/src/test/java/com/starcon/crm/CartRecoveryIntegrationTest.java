package com.starcon.crm;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 15.6.2.2 购物车与弃单挽回的集成测试。
 *
 * <p>跑的是 AGENTS.md 要求的<b>真实 PostgreSQL 16 + FORCE RLS</b>。租户隔离三断言在
 * {@link CartRecoveryTenancyIntegrationTest} 里，本类专攻业务不变量：
 *
 * <ul>
 *   <li><b>🔴 平台订单不适用此项</b>：平台店铺（Amazon / eBay / TikTok Shop …）的购物车事件
 *       必须被<b>显式拒绝</b>（409），而且<b>不进弃单清单</b> —— 混进来会让挽回率报表整体失真。</li>
 *   <li><b>🔴 触达须遵守退订与频次上限</b>：已退订的联系人<b>一律不触达</b>（发出去之前查）；
 *       频次上限越界<b>明确失败并写明原因</b>，不是静默跳过。</li>
 *   <li><b>🔴 挽回话术需人工确认后启用</b>：未审核 / 未启用的话术<b>选不出来</b>；
 *       审核留痕记谁、何时、哪个版本；改了内容回到草稿。</li>
 *   <li><b>不假装已经发出</b>：触达记录只有「已登记待发」；平台购物车事件同步恒 409 且行数不变。</li>
 *   <li><b>时区不静默偏移（硬性要求 5）</b>：用 {@code +08:00} 的偏移量写入，
 *       再<b>断言写回的值</b>等于同一个绝对时刻。</li>
 *   <li><b>禁浮点</b>：金额与数量是 {@code numeric(19,6)}，写回必须逐位相同。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class CartRecoveryIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID userId = UUID.randomUUID();

  /** 独立站店铺（SHOPIFY）。 */
  static final UUID dtcShop = UUID.randomUUID();

  /** 平台店铺（AMAZON）。 */
  static final UUID marketplaceShop = UUID.randomUUID();

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 上游（channel）的店铺清单。 */
  static final List<SignoffDeliveredOrderPort.ShopView> upstreamShops = new CopyOnWriteArrayList<>();

  static HttpServer identity;
  static HttpServer channel;

  /** 平台购物车事件端口的开关（默认未接入；打开后模拟适配器已交付）。 */
  static final ToggleCartEventPort cartEventPort = new ToggleCartEventPort();

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
    List<String> permissions = List.of("crm.cart.read", "crm.cart.update");
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
    // 上游 stub：真的起一个 HTTP 服务，端口与真实 channel 服务同形。
    try {
      channel = HttpServer.create(new InetSocketAddress(0), 0);
      channel.createContext(
          "/channel/shops",
          e -> {
            write(e, List.copyOf(upstreamShops));
          });
      channel.setExecutor(Executors.newFixedThreadPool(2));
      channel.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * 平台购物车事件端口的测试替身。
   *
   * <p>🔴 它<b>默认委托给真的 {@link UnavailableCartEventPort}</b> ——
   * 「未接入」的判定跑的是生产代码，不是测试自己编的一套。
   */
  static final class ToggleCartEventPort implements CartEventPort {
    private final CartEventPort delegate = new UnavailableCartEventPort();
    volatile boolean available = false;
    final List<RawCartEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public Availability availability(String platformCode) {
      return available
          ? new Availability(true, "AVAILABLE", "适配器已交付（测试态）")
          : delegate.availability(platformCode);
    }

    @Override
    public FetchResult fetch(String platformCode, UUID shop, String bearer) {
      return available
          ? new FetchResult(true, "", List.copyOf(events))
          : delegate.fetch(platformCode, shop, bearer);
    }
  }

  @TestConfiguration
  static class PortConfig {
    @Bean
    @Primary
    CartEventPort cartEventPort() {
      return cartEventPort;
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
    r.add("CHANNEL_URL", () -> "http://127.0.0.1:" + channel.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table crm_cart_recovery_touch, crm_cart_recovery_message,"
              + " crm_cart_unsubscribe, crm_cart_abandonment, crm_cart_shop_profile,"
              + " crm_cart_recovery_policy cascade");
    }
    upstreamShops.clear();
    upstreamShops.add(
        new SignoffDeliveredOrderPort.ShopView(
            dtcShop, "SHOP-DTC", "独立站店铺", "SHOPIFY", "Shopify", "SELF_FULFILLED", "ACTIVE"));
    upstreamShops.add(
        new SignoffDeliveredOrderPort.ShopView(
            marketplaceShop,
            "SHOP-MKT",
            "平台店铺",
            "AMAZON",
            "Amazon",
            "SELF_FULFILLED",
            "ACTIVE"));
    cartEventPort.available = false;
    cartEventPort.events.clear();
  }

  // ================================================================ 直读夹具
  //
  // ⚠️ 这些读**必须**在绑定租户的事务里做：RLS 是 FORCE 的，事务外读业务表
  //   一行都看不到（那正是隔离生效的样子，不是 bug）。

  List<org.jooq.Record> rows(String text, Object... args) {
    return tenants.run(tenant, () -> sql.fetch(text, args));
  }

  org.jooq.Record row(String text, Object... args) {
    return tenants.run(tenant, () -> sql.fetchOne(text, args));
  }

  long count(String text, Object... args) {
    return tenants.run(tenant, () -> sql.fetchOne(text, args).get(0, Long.class));
  }

  // ================================================================ 请求助手

  /** 登记一条独立站弃单（走真实端点）。 */
  JsonNode createAbandonment(
      UUID shop, String buyerRef, String cartRef, String abandonedAt, String email) {
    try {
      String body =
          """
          {"shopId":"%s","cartRef":"%s","externalEventId":"","stage":"CART","buyerRef":"%s",
           "buyerEmail":"%s","buyerPhone":"","currency":"USD","amount":129.900000,
           "itemCount":2.000000,"itemSummary":"2 件商品","abandonedAt":"%s","note":""}
          """
              .formatted(shop, cartRef, buyerRef, email, abandonedAt);
      var response =
          http.perform(
                  post("/crm/cart-recovery/abandonments")
                      .header("Authorization", "session")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(body))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      return json.readTree(response);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 建一条话术（走真实端点），返回整棵树。 */
  JsonNode createMessage(String code, String channel, String subject, String body) {
    try {
      String payload =
          """
          {"messageCode":"%s","name":"%s 话术","channel":"%s","locale":"zh-CN",
           "subject":"%s","body":"%s","note":""}
          """
              .formatted(code, code, channel, subject, body);
      var response =
          http.perform(
                  post("/crm/cart-recovery/messages")
                      .header("Authorization", "session")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(payload))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      return json.readTree(response);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * 走真实端点建一条话术并推到「已启用」，返回它的 id。
   *
   * <p>⚠️ 参数是话术**编号**（业务键）而不是 id —— 编号在测试里更好读，
   * 而且能顺带覆盖「建草稿 → 审核 → 启用」这条完整链路。
   */
  String enableMessage(String messageCode) {
    try {
      String id = createMessage(messageCode, "EMAIL", "标题", "正文").get("id").asText();
      http.perform(
              post("/crm/cart-recovery/messages/" + id + "/approve")
                  .header("Authorization", "session")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{}"))
          .andExpect(status().isOk());
      var response =
          http.perform(
                  post("/crm/cart-recovery/messages/" + id + "/enable")
                      .header("Authorization", "session")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{}"))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      return json.readTree(response).get("id").asText();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 登记一次触达（走真实端点）。 */
  org.springframework.test.web.servlet.ResultActions touch(String cartId, String messageId)
      throws Exception {
    return http.perform(
        post("/crm/cart-recovery/abandonments/" + cartId + "/touches")
            .header("Authorization", "session")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"messageId\":\"" + messageId + "\",\"note\":\"\"}"));
  }

  /**
   * 取业务拒绝的**原因**。
   *
   * <p>⚠️ MockMvc <b>不渲染</b> {@code ResponseStatusException} 的响应体（它走 {@code sendError}），
   * 所以断言「写明了原因」只能从<b>解析到的异常</b>里取 —— 直接断言 body 会得到空串。
   */
  static String reason(org.springframework.test.web.servlet.MvcResult result) {
    var ex = result.getResolvedException();
    if (ex instanceof org.springframework.web.server.ResponseStatusException rse) {
      return rse.getReason() == null ? "" : rse.getReason();
    }
    if (ex == null) {
      try {
        return result.getResponse().getContentAsString();
      } catch (java.io.UnsupportedEncodingException e) {
        throw new IllegalStateException(e);
      }
    }
    return ex.getMessage() == null ? "" : ex.getMessage();
  }

  /**
   * 直接插一条 N 天前的触达（把「周期上限」与「每日上限」分开验证）。
   *
   * <p>为什么需要它：24 小时窗口 ⊂ 周期窗口，所以「周期上限」永远比「每日上限」晚触发；
   * 只有先有一条**跨天**的触达，才能证明周期那条线是独立生效的。
   */
  void insertOldTouch(UUID cartId, int daysAgo) {
    tenants.run(
        tenant,
        () -> {
          OffsetDateTime moment = OffsetDateTime.now(ZoneOffset.UTC).minusDays(daysAgo);
          sql.execute(
              "insert into crm_cart_recovery_touch"
                  + "(id,tenant_id,cart_id,cart_no,message_id,message_code,channel,transport,status,"
                  + " recipient,subject,body,failure_reason,sent_at,operator_id,created_at,updated_at)"
                  + " values (?,?,?,?,null,'','EMAIL','MANUAL_HANDOFF','REGISTERED',?,'','','',"
                  + " null,null,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              cartId,
              "CR-OLD",
              "old@example.com",
              moment,
              moment);
          return null;
        });
  }

  /** 改频次策略（走真实端点）。 */
  void putPolicy(int perDay, int perCycle, int cycleDays, int timeoutHours) throws Exception {
    http.perform(
            put("/crm/cart-recovery/policy")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"maxTouchesPerDay\":"
                        + perDay
                        + ",\"maxTouchesPerCycle\":"
                        + perCycle
                        + ",\"cycleDays\":"
                        + cycleDays
                        + ",\"abandonTimeoutHours\":"
                        + timeoutHours
                        + ",\"note\":\"\"}"))
        .andExpect(status().isOk());
  }

  // ================================================================ 🔴 平台订单不适用此项

  @Test
  void 平台店铺的购物车事件被显式拒绝且不进弃单清单() throws Exception {
    long before = count("select count(*) from crm_cart_abandonment");

    var response =
        http.perform(
                post("/crm/cart-recovery/abandonments")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"shopId":"%s","cartRef":"cart-mkt-1","stage":"CART","buyerRef":"buyer-mkt",
                         "buyerEmail":"mkt@example.com","amount":10.0,"itemCount":1.0,
                         "abandonedAt":"2026-06-01T09:30:00+08:00"}
                        """
                            .formatted(marketplaceShop)))
            .andExpect(status().isConflict())
            .andReturn();

    assertThat(reason(response)).contains("平台店铺").contains("购物车概念");
    assertThat(count("select count(*) from crm_cart_abandonment"))
        .as("平台店铺的购物车事件**必须一行都不写**")
        .isEqualTo(before);
  }

  @Test
  void 平台店铺连同步端点也进不去且行数不变() throws Exception {
    long before = count("select count(*) from crm_cart_abandonment");

    http.perform(
            post("/crm/cart-recovery/sync")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"shopId\":\"" + marketplaceShop + "\"}"))
        .andExpect(status().isConflict());

    assertThat(count("select count(*) from crm_cart_abandonment")).isEqualTo(before);
  }

  @Test
  void 平台店铺不可被声明成独立站() throws Exception {
    http.perform(
            put("/crm/cart-recovery/shops/" + marketplaceShop)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"platformType\":\"DTC\",\"note\":\"\"}"))
        .andExpect(status().isConflict());

    assertThat(count("select count(*) from crm_cart_shop_profile"))
        .as("声明被拒时不得留下任何档案行")
        .isZero();
  }

  @Test
  void 平台类型由系统判定_独立站是DTC平台店铺是MARKETPLACE() throws Exception {
    var shops =
        json.readTree(
            http.perform(get("/crm/cart-recovery/shops").header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    var dtc = shopById(shops, dtcShop);
    var mkt = shopById(shops, marketplaceShop);
    assertThat(dtc.get("platformType").asText()).isEqualTo("DTC");
    assertThat(dtc.get("platformTypeSource").asText()).isEqualTo("PLATFORM_CATALOG");
    assertThat(mkt.get("platformType").asText()).isEqualTo("MARKETPLACE");
    // 平台购物车事件适配器未交付 ⇒ 明确 available=false + 人能读的原因。
    assertThat(dtc.get("cartEventAvailable").asBoolean()).isFalse();
    assertThat(dtc.get("cartEventNote").asText()).contains("未交付");
  }

  /** 从店铺目录里按 id 取一行。 */
  private static JsonNode shopById(JsonNode shops, UUID shopId) {
    for (var node : shops) {
      if (shopId.toString().equals(node.get("id").asText())) return node;
    }
    throw new AssertionError("店铺目录里没有 " + shopId);
  }

  // ================================================================ 弃单登记（主路）

  @Test
  void 独立站弃单真落库并带平台类型快照() throws Exception {
    var created =
        createAbandonment(dtcShop, "buyer-1", "cart-1", "2026-06-01T09:30:00+08:00", "a@example.com");

    assertThat(created.get("cartNo").asText()).startsWith("CR-");
    assertThat(created.get("platformCode").asText()).isEqualTo("SHOPIFY");
    assertThat(created.get("platformType").asText()).isEqualTo("DTC");
    assertThat(created.get("stage").asText()).isEqualTo("CART");
    assertThat(created.get("status").asText()).isEqualTo("ABANDONED");
    assertThat(created.get("source").asText()).isEqualTo("MANUAL_ENTRY");
    assertThat(created.get("touchCount").asLong()).isZero();

    // 真落库（不是只返回了一个对象）。
    assertThat(count("select count(*) from crm_cart_abandonment where buyer_ref = ?", "buyer-1"))
        .isEqualTo(1L);
    var stored = row("select platform_type, status from crm_cart_abandonment where buyer_ref = ?", "buyer-1");
    assertThat(stored.get("platform_type", String.class)).isEqualTo("DTC");
    assertThat(stored.get("status", String.class)).isEqualTo("ABANDONED");
  }

  @Test
  void 金额与数量是numeric原样写回_禁浮点() throws Exception {
    var created =
        createAbandonment(dtcShop, "buyer-num", "cart-num", "2026-06-01T09:30:00+08:00", "n@example.com");
    var stored =
        row(
            "select amount, item_count from crm_cart_abandonment where id = ?",
            UUID.fromString(created.get("id").asText()));

    assertThat(stored.get("amount", BigDecimal.class))
        .as("金额是 numeric(19,6)：禁浮点，写回必须逐位相同")
        .isEqualByComparingTo(new BigDecimal("129.900000"));
    assertThat(stored.get("item_count", BigDecimal.class))
        .isEqualByComparingTo(new BigDecimal("2.000000"));
  }

  @Test
  void 时间带偏移量写入后断言写回值等于同一绝对时刻() throws Exception {
    var created =
        createAbandonment(dtcShop, "buyer-tz", "cart-tz", "2026-06-01T09:30:00+08:00", "tz@example.com");
    var expected = OffsetDateTime.parse("2026-06-01T09:30:00+08:00").toInstant();

    var returned = OffsetDateTime.parse(created.get("abandonedAt").asText()).toInstant();
    assertThat(returned)
        .as("写回的时刻必须与填进去的是同一个绝对时刻（用 Timestamp.valueOf 会静默偏 −28800 秒）")
        .isEqualTo(expected);

    // 直接查库再核一遍（不能只断言「没抛异常」）。
    var stored =
        row(
                "select abandoned_at from crm_cart_abandonment where id = ?",
                UUID.fromString(created.get("id").asText()))
            .get("abandoned_at", OffsetDateTime.class);
    assertThat(stored.toInstant()).isEqualTo(expected);
  }

  @Test
  void 同一购物车只登记一次() throws Exception {
    createAbandonment(dtcShop, "buyer-dup", "cart-dup", "2026-06-01T09:30:00+08:00", "d@example.com");

    http.perform(
            post("/crm/cart-recovery/abandonments")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"shopId":"%s","cartRef":"cart-dup","stage":"CART","buyerRef":"buyer-dup",
                     "buyerEmail":"d@example.com","abandonedAt":"2026-06-01T09:30:00+08:00"}
                    """
                        .formatted(dtcShop)))
        .andExpect(status().isConflict());

    assertThat(count("select count(*) from crm_cart_abandonment")).isEqualTo(1L);
  }

  // ================================================================ 列表：真分页 + 真筛选

  @Test
  void 弃单清单真分页且总数与筛选一致() throws Exception {
    for (int i = 0; i < 5; i++) {
      createAbandonment(
          dtcShop, "buyer-" + i, "cart-" + i, "2026-06-0" + (i + 1) + "T09:30:00+08:00", "p@example.com");
    }

    var page =
        json.readTree(
            http.perform(get("/crm/cart-recovery/abandonments").param("page", "0").param("size", "2")
                    .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(page.get("items").size()).isEqualTo(2);
    assertThat(page.get("totalElements").asLong()).isEqualTo(5L);
    assertThat(page.get("totalPages").asLong()).isEqualTo(3L);
    assertThat(page.get("page").asInt()).isZero();
    assertThat(page.get("size").asInt()).isEqualTo(2);

    var page2 =
        json.readTree(
            http.perform(get("/crm/cart-recovery/abandonments").param("page", "2").param("size", "2")
                    .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(page2.get("items").size()).isEqualTo(1);
  }

  @Test
  void 筛选按店铺状态阶段买家与时间窗生效() throws Exception {
    createAbandonment(dtcShop, "buyer-a", "cart-a", "2026-06-01T09:30:00+08:00", "a@example.com");
    createAbandonment(dtcShop, "buyer-b", "cart-b", "2026-06-05T09:30:00+08:00", "b@example.com");

    var byBuyer =
        json.readTree(
            http.perform(get("/crm/cart-recovery/abandonments").param("buyerRef", "buyer-a")
                    .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(byBuyer.get("totalElements").asLong()).isEqualTo(1L);
    assertThat(byBuyer.get("items").get(0).get("buyerRef").asText()).isEqualTo("buyer-a");

    var byStatus =
        json.readTree(
            http.perform(get("/crm/cart-recovery/abandonments").param("status", "RECOVERED")
                    .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(byStatus.get("totalElements").asLong()).isZero();

    // 时间窗（闭开区间 [from, to)）
    var byWindow =
        json.readTree(
            http.perform(get("/crm/cart-recovery/abandonments")
                    .param("abandonedFrom", "2026-06-04T00:00:00+08:00")
                    .param("abandonedTo", "2026-06-06T00:00:00+08:00")
                    .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(byWindow.get("totalElements").asLong()).isEqualTo(1L);
    assertThat(byWindow.get("items").get(0).get("buyerRef").asText()).isEqualTo("buyer-b");
  }

  // ================================================================ 🔴 话术状态机

  @Test
  void 话术从草稿到已审核到已启用且留痕记谁何时哪个版本() throws Exception {
    var draft = createMessage("CART-ABANDON-1", "EMAIL", "您的购物车还在", "您好，购物车还在等你。");
    String id = draft.get("id").asText();

    assertThat(draft.get("status").asText()).isEqualTo("DRAFT");
    assertThat(draft.get("version").asInt()).isEqualTo(1);

    var approved =
        json.readTree(
            http.perform(post("/crm/cart-recovery/messages/" + id + "/approve")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
    assertThat(approved.get("approvedBy").asText()).isEqualTo(userId.toString());
    assertThat(approved.get("approvedAt").asText()).isNotBlank();
    assertThat(approved.get("approvedVersion").asInt()).isEqualTo(1);

    var enabled =
        json.readTree(
            http.perform(post("/crm/cart-recovery/messages/" + id + "/enable")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(enabled.get("status").asText()).isEqualTo("ENABLED");
    assertThat(enabled.get("enabledBy").asText()).isEqualTo(userId.toString());
    assertThat(enabled.get("enabledVersion").asInt()).isEqualTo(1);
  }

  @Test
  void 跳过审核直接启用被拒() throws Exception {
    var draft = createMessage("CART-ABANDON-2", "EMAIL", "标题", "正文");
    String id = draft.get("id").asText();

    http.perform(post("/crm/cart-recovery/messages/" + id + "/enable")
            .header("Authorization", "session")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isConflict());

    assertThat(row("select status from crm_cart_recovery_message where id = ?", UUID.fromString(id))
            .get("status", String.class))
        .isEqualTo("DRAFT");
  }

  @Test
  void 改了内容回到草稿且版本加一审核痕迹清空() throws Exception {
    var draft = createMessage("CART-ABANDON-3", "EMAIL", "旧标题", "旧正文");
    String id = draft.get("id").asText();
    http.perform(post("/crm/cart-recovery/messages/" + id + "/approve")
            .header("Authorization", "session")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isOk());
    http.perform(post("/crm/cart-recovery/messages/" + id + "/enable")
            .header("Authorization", "session")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isOk());

    var updated =
        json.readTree(
            http.perform(put("/crm/cart-recovery/messages/" + id)
                            .header("Authorization", "session")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                """
                                {"messageCode":"CART-ABANDON-3","name":"改后的话术","channel":"EMAIL",
                                 "locale":"zh-CN","subject":"新标题","body":"新正文","note":""}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

    assertThat(updated.get("status").asText())
        .as("改了内容就回到草稿：否则会出现「审核的是 A 版、发出去的是 B 版」")
        .isEqualTo("DRAFT");
    assertThat(updated.get("version").asInt()).isEqualTo(2);
    assertThat(updated.get("approvedBy").isNull()).isTrue();
    assertThat(updated.get("approvedAt").isNull()).isTrue();
    assertThat(updated.get("enabledBy").isNull()).isTrue();
  }

  // ================================================================ 🔴 触达：话术 / 退订 / 频次

  @Test
  void 未启用的话术不能被触达流程选用() throws Exception {
    var created =
        createAbandonment(dtcShop, "buyer-draft", "cart-draft", "2026-06-01T09:30:00+08:00", "dr@example.com");
    String cartId = created.get("id").asText();
    var draft = createMessage("CART-ABANDON-4", "EMAIL", "标题", "正文");

    var response =
        touch(cartId, draft.get("id").asText())
            .andExpect(status().isConflict())
            .andReturn();

    assertThat(reason(response)).contains("不能被触达流程选用").contains("人工确认后启用");
    assertThat(count("select count(*) from crm_cart_recovery_touch"))
        .as("被话术状态挡住时**一行触达都不能写**")
        .isZero();
  }

  @Test
  void 触达登记成功但状态是已登记待发不假装已发出() throws Exception {
    var created =
        createAbandonment(dtcShop, "buyer-touch", "cart-touch", "2026-06-01T09:30:00+08:00", "t@example.com");
    String cartId = created.get("id").asText();
    String messageId = enableMessage("CART-ABANDON-5");

    var result =
        json.readTree(
            touch(cartId, messageId)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

    assertThat(result.get("touch").get("status").asText()).isEqualTo("REGISTERED");
    assertThat(result.get("touch").get("transport").asText()).isEqualTo("MANUAL_HANDOFF");
    assertThat(result.get("touch").get("recipient").asText()).isEqualTo("t@example.com");
    assertThat(result.get("note").asText()).contains("不假装已经发出");
    assertThat(count("select count(*) from crm_cart_recovery_touch")).isEqualTo(1L);
  }

  @Test
  void 未到挽回时点不允许触达() throws Exception {
    // 弃单时刻 = 现在（超时 24 小时后才到挽回时点）。
    String nowText = OffsetDateTime.now(ZoneOffset.UTC).toString();
    var created = createAbandonment(dtcShop, "buyer-soon", "cart-soon", nowText, "s@example.com");
    String cartId = created.get("id").asText();
    String messageId = enableMessage("CART-ABANDON-6");

    var response =
        touch(cartId, messageId)
            .andExpect(status().isConflict())
            .andReturn();

    assertThat(reason(response)).contains("挽回时点");
    assertThat(count("select count(*) from crm_cart_recovery_touch")).isZero();
  }

  @Test
  void 已退订的联系人一律不触达_发出去之前查() throws Exception {
    var created =
        createAbandonment(dtcShop, "buyer-unsub", "cart-unsub", "2026-06-01T09:30:00+08:00", "un@example.com");
    String cartId = created.get("id").asText();
    String messageId = enableMessage("CART-ABANDON-7");

    http.perform(
            post("/crm/cart-recovery/unsubscribes")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"contactType\":\"EMAIL\",\"contactValue\":\"un@example.com\","
                        + "\"shopId\":null,\"reason\":\"买家主动退订\",\"note\":\"\"}"))
        .andExpect(status().isOk());

    var response =
        touch(cartId, messageId)
            .andExpect(status().isConflict())
            .andReturn();

    assertThat(reason(response)).contains("已退订");
    assertThat(count("select count(*) from crm_cart_recovery_touch"))
        .as("退订是硬拦：触达记录**一行都不能写**")
        .isZero();
  }

  @Test
  void 频次上限越界明确失败并写明原因_不是静默跳过() throws Exception {
    putPolicy(1, 3, 30, 24);
    var created =
        createAbandonment(dtcShop, "buyer-freq", "cart-freq", "2026-06-01T09:30:00+08:00", "f@example.com");
    String cartId = created.get("id").asText();
    String messageId = enableMessage("CART-ABANDON-8");

    touch(cartId, messageId).andExpect(status().isOk());

    var response =
        touch(cartId, messageId)
            .andExpect(status().isConflict())
            .andReturn();

    assertThat(reason(response)).contains("每日上限").contains("频次上限");
    assertThat(count("select count(*) from crm_cart_recovery_touch"))
        .as("越界时不得多写一行触达")
        .isEqualTo(1L);
  }

  @Test
  void 频次上限可配置_调大之后第二次触达放行() throws Exception {
    putPolicy(2, 5, 30, 24);
    var created =
        createAbandonment(dtcShop, "buyer-freq2", "cart-freq2", "2026-06-01T09:30:00+08:00", "f2@example.com");
    String cartId = created.get("id").asText();
    String messageId = enableMessage("CART-ABANDON-9");

    touch(cartId, messageId).andExpect(status().isOk());
    touch(cartId, messageId).andExpect(status().isOk());

    assertThat(count("select count(*) from crm_cart_recovery_touch")).isEqualTo(2L);
  }

  @Test
  void 周期上限与每日上限是两条独立的线() throws Exception {
    // 每日 1 次、周期 1 次、周期 30 天。
    putPolicy(1, 1, 30, 24);
    var created =
        createAbandonment(dtcShop, "buyer-cycle", "cart-cycle", "2026-06-01T09:30:00+08:00", "cy@example.com");
    String cartId = created.get("id").asText();
    String messageId = enableMessage("CART-ABANDON-10");

    // 先落一条 **3 天前**的触达：它在 24 小时窗口之外、但在 30 天周期之内。
    // ⇒ 这次触达 Daily 计数为 0（每日上限不触发）、周期计数为 1（周期上限触发）。
    insertOldTouch(UUID.fromString(cartId), 3);

    var response =
        touch(cartId, messageId)
            .andExpect(status().isConflict())
            .andReturn();
    assertThat(reason(response))
        .as("24 小时窗口外的触达不该被每日上限挡住，而应由周期上限挡住")
        .contains("周期上限")
        .doesNotContain("每日上限");
    assertThat(count("select count(*) from crm_cart_recovery_touch")).isEqualTo(1L);
  }

  @Test
  void 触达结果可回填且失败必须带原因() throws Exception {
    var created =
        createAbandonment(dtcShop, "buyer-res", "cart-res", "2026-06-01T09:30:00+08:00", "re@example.com");
    String cartId = created.get("id").asText();
    String messageId = enableMessage("CART-ABANDON-11");
    var touchResult =
        json.readTree(
            touch(cartId, messageId)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    String touchId = touchResult.get("touch").get("id").asText();

    // 失败必须带原因。
    http.perform(post("/crm/cart-recovery/touches/" + touchId + "/result")
            .header("Authorization", "session")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"FAILED\",\"failureReason\":\"\",\"sentAt\":\"\",\"note\":\"\"}"))
        .andExpect(status().isBadRequest());

    var sent =
        json.readTree(
            http.perform(post("/crm/cart-recovery/touches/" + touchId + "/result")
                            .header("Authorization", "session")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                "{\"status\":\"SENT\",\"failureReason\":\"\","
                                    + "\"sentAt\":\"2026-06-03T10:00:00+08:00\",\"note\":\"\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(sent.get("status").asText()).isEqualTo("SENT");
    // ⚠️ 断言**绝对时刻**而不是字符串形状：PostgreSQL 会把秒为 0 的时刻写成
    //   「2026-06-03T10:00+08:00」（不补零），比字符串更可靠的是「还是不是同一个时刻」。
    assertThat(OffsetDateTime.parse(sent.get("sentAt").asText()).toInstant())
        .isEqualTo(OffsetDateTime.parse("2026-06-03T10:00:00+08:00").toInstant());
  }

  // ================================================================ 🔴 平台购物车事件同步

  @Test
  void 平台购物车事件同步恒为409且不写任何数据() throws Exception {
    long before = count("select count(*) from crm_cart_abandonment");

    var response =
        http.perform(
                post("/crm/cart-recovery/sync")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"shopId\":\"" + dtcShop + "\"}"))
            .andExpect(status().isConflict())
            .andReturn();

    assertThat(reason(response)).contains("未交付");
    assertThat(count("select count(*) from crm_cart_abandonment"))
        .as("未接入的同步**一行都不能写**")
        .isEqualTo(before);
  }

  // ================================================================ 挽回率报表

  @Test
  void 挽回率由服务端真算而不是写死的数字() throws Exception {
    // 3 条弃单：1 条已挽回。
    var a = createAbandonment(dtcShop, "buyer-r1", "cart-r1", "2026-06-01T09:30:00+08:00", "r1@example.com");
    createAbandonment(dtcShop, "buyer-r2", "cart-r2", "2026-06-02T09:30:00+08:00", "r2@example.com");
    createAbandonment(dtcShop, "buyer-r3", "cart-r3", "2026-06-03T09:30:00+08:00", "r3@example.com");

    var before =
        json.readTree(
            http.perform(get("/crm/cart-recovery/recovery-rate").header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(before.get("totalAbandonments").asLong()).isEqualTo(3L);
    assertThat(before.get("recoveredCount").asLong()).isZero();
    assertThat(before.get("recoveryRate").asDouble()).isEqualTo(0.0);

    http.perform(
            post("/crm/cart-recovery/abandonments/" + a.get("id").asText() + "/recoveries")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"salesOrderNo\":\"SO-R1\",\"recoveredAmount\":129.900000,"
                        + "\"recoveredAt\":\"2026-06-04T12:00:00+08:00\",\"note\":\"\"}"))
        .andExpect(status().isOk());

    var after =
        json.readTree(
            http.perform(get("/crm/cart-recovery/recovery-rate").header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(after.get("totalAbandonments").asLong()).isEqualTo(3L);
    assertThat(after.get("recoveredCount").asLong()).isEqualTo(1L);
    assertThat(after.get("recoveryRate").asDouble())
        .as("挽回率 = 1/3 ≈ 33.33（服务端真算）")
        .isEqualTo(33.33);
    assertThat(after.get("byShop").size()).isEqualTo(1);
    assertThat(after.get("byShop").get(0).get("recovered").asLong()).isEqualTo(1L);
  }

  @Test
  void 重复登记成交被拒_免得挽回率分子出错() throws Exception {
    var created =
        createAbandonment(dtcShop, "buyer-dup2", "cart-dup2", "2026-06-01T09:30:00+08:00", "d2@example.com");
    String cartId = created.get("id").asText();

    http.perform(
            post("/crm/cart-recovery/abandonments/" + cartId + "/recoveries")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"salesOrderNo\":\"SO-X\",\"recoveredAt\":\"2026-06-04T12:00:00+08:00\",\"note\":\"\"}"))
        .andExpect(status().isOk());

    http.perform(
            post("/crm/cart-recovery/abandonments/" + cartId + "/recoveries")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"salesOrderNo\":\"SO-Y\",\"recoveredAt\":\"2026-06-05T12:00:00+08:00\",\"note\":\"\"}"))
        .andExpect(status().isConflict());
  }

  // ================================================================ 退订名单与策略

  @Test
  void 退订名单真分页且重复登记被拒() throws Exception {
    for (int i = 0; i < 3; i++) {
      http.perform(
              post("/crm/cart-recovery/unsubscribes")
                  .header("Authorization", "session")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"contactType\":\"EMAIL\",\"contactValue\":\"u" + i
                          + "@example.com\",\"shopId\":null,\"reason\":\"\",\"note\":\"\"}"))
          .andExpect(status().isOk());
    }

    var page =
        json.readTree(
            http.perform(get("/crm/cart-recovery/unsubscribes").param("page", "0").param("size", "2")
                    .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(page.get("items").size()).isEqualTo(2);
    assertThat(page.get("totalElements").asLong()).isEqualTo(3L);

    http.perform(
            post("/crm/cart-recovery/unsubscribes")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"contactType\":\"EMAIL\",\"contactValue\":\"u0@example.com\","
                        + "\"shopId\":null,\"reason\":\"\",\"note\":\"\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void 频次策略没有配置行时给默认值且可改() throws Exception {
    var defaults =
        json.readTree(
            http.perform(get("/crm/cart-recovery/policy").header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(defaults.get("maxTouchesPerDay").asInt()).isEqualTo(1);
    assertThat(defaults.get("maxTouchesPerCycle").asInt()).isEqualTo(3);
    assertThat(defaults.get("cycleDays").asInt()).isEqualTo(30);
    assertThat(defaults.get("abandonTimeoutHours").asInt()).isEqualTo(24);
    assertThat(defaults.get("customized").asBoolean()).isFalse();

    putPolicy(3, 6, 15, 12);
    var updated =
        json.readTree(
            http.perform(get("/crm/cart-recovery/policy").header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(updated.get("maxTouchesPerDay").asInt()).isEqualTo(3);
    assertThat(updated.get("cycleDays").asInt()).isEqualTo(15);
    assertThat(updated.get("abandonTimeoutHours").asInt()).isEqualTo(12);
    assertThat(updated.get("customized").asBoolean()).isTrue();
  }

  @Test
  void 频次上限有天花板_调到999被拒() throws Exception {
    http.perform(
            put("/crm/cart-recovery/policy")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"maxTouchesPerDay\":999,\"maxTouchesPerCycle\":3,\"cycleDays\":30,"
                        + "\"abandonTimeoutHours\":24,\"note\":\"\"}"))
        .andExpect(status().isBadRequest());
  }

  // ================================================================ 能力声明

  @Test
  void 能力声明把未交付的维度逐条标出来() throws Exception {
    var caps =
        json.readTree(
            http.perform(get("/crm/cart-recovery/capabilities").header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

    var dimensions = new java.util.HashMap<String, Boolean>();
    for (var d : caps.get("dimensions")) {
      dimensions.put(d.get("dimension").asText(), d.get("available").asBoolean());
    }
    assertThat(dimensions).containsEntry("cart_event_sync", false);
    assertThat(dimensions).containsEntry("recovered_order_link", false);
    assertThat(dimensions).containsEntry("buyer_profile", false);
    assertThat(dimensions).containsEntry("recovery_analytics", false);
    assertThat(dimensions).containsEntry("abandonment_registration", true);
    assertThat(dimensions).containsEntry("unsubscribe_enforcement", true);
    assertThat(dimensions).containsEntry("frequency_cap", true);
    assertThat(dimensions).containsEntry("recovery_message_lifecycle", true);
  }
}
