package com.starcon.crm;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 15.1.1.1 消息收件箱的集成测试。
 *
 * <p>跑的是 AGENTS.md 要求的<b>真实 PostgreSQL 16 + FORCE RLS</b>。租户隔离三断言在
 * {@link MessageInboxTenancyIntegrationTest} 里，本类专攻业务不变量：
 *
 * <ul>
 *   <li><b>🔴 加粗句「消息必须关联到买家与订单」</b>：买家 / 订单缺一不可 ——
 *       服务层拒绝，<b>DB 层也拒绝</b>（绕开服务层直接写库同样写不进去）。
 *   <li><b>列表是真分页真筛选</b>：店铺 / 类型 / 优先级 / 未读 / 方向 / 买家 / 订单 /
 *       关键字 / 平台时间窗，每一个都对应一条真实存在的列；总数是独立的 {@code COUNT(*)}。
 *   <li><b>订单上下文查不到时明确说「不可用 + 原因」</b>，**不显示空白**；
 *       而且「上游没有这一单」与「上游读不到」是<b>两种不同的文案</b>。
 *   <li><b>上游读不到就 503</b>：店铺清单绝不返回空集合冒充「没有店铺」。
 *   <li><b>平台消息同步未接入</b>：端点明确 <b>409</b> 且<b>一条数据都不写</b>。
 *   <li><b>下游未交付</b>：转售后 / 建工单只落<b>处置意图</b>，
 *       <b>库里没有任何下游单据 id</b>。
 *   <li><b>时区不静默偏移（硬性要求 5）</b>：用 {@code +08:00} 的偏移量写入，
 *       再<b>断言写回的值</b>等于同一个绝对时刻。
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class MessageInboxIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID userId = UUID.randomUUID();
  static final UUID shopId = UUID.randomUUID();
  static final UUID shop2Id = UUID.randomUUID();
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 上游（channel）的店铺清单 —— 测试自己往里塞，**不 mock 上游服务**。 */
  static final List<MessageContextPort.ShopView> upstreamShops = new CopyOnWriteArrayList<>();

  /** 上游（trade）的订单 —— 测试自己往里塞。 */
  static final List<MessageContextPort.OrderView> upstreamOrders = new CopyOnWriteArrayList<>();

  /** 让店铺清单接口报 500（用来证明「读不到」不是「空清单」）。 */
  static final AtomicBoolean shopsBroken = new AtomicBoolean(false);

  /** 让订单列表接口报 500。 */
  static final AtomicBoolean ordersBroken = new AtomicBoolean(false);

  static HttpServer identity;
  static HttpServer channel;
  static HttpServer trade;

  /** 固定的平台时刻（东八区偏移量，用来验时区不静默偏移）。 */
  static final String PLATFORM_AT = "2026-06-01T09:30:00+08:00";

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
    List<String> permissions = List.of("crm.message.read", "crm.message.update");
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
    // 上游 stub：真的起 HTTP 服务，路径与真实 channel / trade 服务同形。
    try {
      channel = HttpServer.create(new InetSocketAddress(0), 0);
      channel.createContext(
          "/channel/shops",
          e -> {
            if (shopsBroken.get()) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            write(e, List.copyOf(upstreamShops));
          });
      channel.setExecutor(Executors.newFixedThreadPool(2));
      channel.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    try {
      trade = HttpServer.create(new InetSocketAddress(0), 0);
      trade.createContext(
          "/trade/sales-orders/list",
          e -> {
            if (ordersBroken.get()) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            String query = e.getRequestURI().getQuery();
            String keyword = "";
            if (query != null) {
              for (String part : query.split("&")) {
                if (part.startsWith("keyword=")) {
                  keyword = java.net.URLDecoder.decode(part.substring("keyword=".length()));
                }
              }
            }
            final String kw = keyword;
            var rows =
                upstreamOrders.stream()
                    .filter(
                        o ->
                            kw.isEmpty()
                                || (o.orderNo() != null && o.orderNo().contains(kw))
                                || (o.externalOrderNo() != null && o.externalOrderNo().contains(kw))
                                || (o.buyerName() != null && o.buyerName().contains(kw)))
                    .toList();
            write(
                e,
                Map.of(
                    "rows",
                    rows,
                    "page",
                    0,
                    "size",
                    rows.size(),
                    "total",
                    rows.size(),
                    "dataScope",
                    "ALL",
                    "dataScopeNote",
                    "本租户全量"));
          });
      trade.setExecutor(Executors.newFixedThreadPool(2));
      trade.start();
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
    r.add("CHANNEL_URL", () -> "http://127.0.0.1:" + channel.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://127.0.0.1:" + trade.getAddress().getPort());
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
      s.execute("truncate table crm_message_attachment, crm_message_intent, crm_message");
    }
    upstreamShops.clear();
    upstreamOrders.clear();
    shopsBroken.set(false);
    ordersBroken.set(false);
    upstreamShops.add(
        new MessageContextPort.ShopView(
            shopId, "SHOP-A", "美国主店", "AMAZON", "亚马逊", "SELF_FULFILLED", "ACTIVE"));
    upstreamShops.add(
        new MessageContextPort.ShopView(
            shop2Id, "SHOP-B", "德国店", "SHOPIFY", "Shopify", "PLATFORM_FULFILLED", "ACTIVE"));
    upstreamOrders.add(
        new MessageContextPort.OrderView(
            UUID.randomUUID(),
            "SO-1001",
            "AMZ-1001",
            "AMAZON",
            null,
            "SUBMITTED",
            "USD",
            new java.math.BigDecimal("39.90"),
            2,
            UUID.randomUUID(),
            "Ada Buyer",
            List.of("SKU-A", "SKU-B"),
            OffsetDateTime.parse("2026-05-30T10:00:00+08:00"),
            OffsetDateTime.parse("2026-05-30T09:59:00+08:00"),
            "AWAITING_SHIPMENT",
            "PAID",
            "UNFULFILLED",
            "WH-01",
            null));
  }

  /** 登记一条消息的请求体。 */
  private String body(
      UUID shop,
      String buyer,
      String order,
      String subject,
      String type,
      String direction,
      String priority,
      String platformTime) {
    return """
        {"shopId":"%s","buyerReference":"%s","orderReference":"%s","subject":"%s",
         "direction":"%s","messageType":"%s","priority":"%s","platformTime":"%s",
         "body":"买家留言正文"}
        """
        .formatted(shop, buyer, order, subject, direction, type, priority, platformTime);
  }

  private JsonNode postJson(String path, String content) throws Exception {
    MvcResult result =
        http.perform(
                MockMvcRequestBuilders.post(path)
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(content))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString());
  }

  private JsonNode getJson(String path) throws Exception {
    MvcResult result =
        http.perform(MockMvcRequestBuilders.get(path).header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString());
  }

  /** 登记一条默认消息（INBOUND / BUYER_MESSAGE / NORMAL）。 */
  private String createMessage(UUID shop, String buyer, String order, String subject)
      throws Exception {
    return postJson(
            "/crm/message-inbox/messages",
            body(shop, buyer, order, subject, "BUYER_MESSAGE", "INBOUND", "NORMAL", PLATFORM_AT))
        .get("id")
        .asText();
  }

  // ================================================================ 迁移与 RLS

  @Test
  void 迁移落地且业务表强制rls() {
    for (String table : List.of("crm_message", "crm_message_attachment", "crm_message_intent")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V291 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住").isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('crm_app','" + table + "','SELECT')")
                  .get(0, Boolean.class))
          .as("crm_app 必须真的能读 " + table)
          .isTrue();
    }
  }

  // ================================================================ 加粗句：必须关联买家与订单

  @Test
  void 登记消息缺买家或订单一律被拒() throws Exception {
    http.perform(
            MockMvcRequestBuilders.post("/crm/message-inbox/messages")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"shopId":"%s","orderReference":"SO-1001","direction":"INBOUND",
                     "messageType":"BUYER_MESSAGE","platformTime":"%s"}
                    """
                        .formatted(shopId, PLATFORM_AT)))
        .andExpect(status().isBadRequest());
    http.perform(
            MockMvcRequestBuilders.post("/crm/message-inbox/messages")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"shopId":"%s","buyerReference":"buyer-a","direction":"INBOUND",
                     "messageType":"BUYER_MESSAGE","platformTime":"%s"}
                    """
                        .formatted(shopId, PLATFORM_AT)))
        .andExpect(status().isBadRequest());
    assertThat(countMessages()).as("被拒的登记不能留下任何一行").isZero();
  }

  @Test
  void 数据库层同样拒收没有买家或订单的消息() {
    // 绕开服务层直接写库：DB 层的 CHECK 必须自己挡住。
    var missingBuyer =
        catchThrowable(
                () ->
                    tenants.run(
                        tenant,
                        () ->
                            sql.execute(
                        "insert into crm_message"
                            + "(id,tenant_id,message_no,shop_id,shop_name,platform_code,"
                            + " buyer_reference,order_reference,direction,message_type,platform_time,"
                            + " source,created_at,updated_at)"
                            + " values (?,?,?,?,?,'AMAZON','','SO-1','INBOUND','BUYER_MESSAGE',"
                            + " ?::timestamptz,'MANUAL_ENTRY',?::timestamptz,?::timestamptz)",
                        UUID.randomUUID(),
                        tenant,
                        "MSG-T-1",
                        shopId,
                        "店",
                        OffsetDateTime.now(ZoneOffset.UTC),
                        OffsetDateTime.now(ZoneOffset.UTC),
                        OffsetDateTime.now(ZoneOffset.UTC))));
    assertThat(missingBuyer).as("DB 层必须自己挡住没有买家的消息").isNotNull();
    assertThat(allMessages(missingBuyer)).contains("buyer_reference");
    assertThat(allMessages(missingBuyer)).contains("check constraint");

    var missingOrder =
        catchThrowable(
                () ->
                    tenants.run(
                        tenant,
                        () ->
                            sql.execute(
                        "insert into crm_message"
                            + "(id,tenant_id,message_no,shop_id,shop_name,platform_code,"
                            + " buyer_reference,order_reference,direction,message_type,platform_time,"
                            + " source,created_at,updated_at)"
                            + " values (?,?,?,?,?,'AMAZON','buyer-a','','INBOUND','BUYER_MESSAGE',"
                            + " ?::timestamptz,'MANUAL_ENTRY',?::timestamptz,?::timestamptz)",
                        UUID.randomUUID(),
                        tenant,
                        "MSG-T-2",
                        shopId,
                        "店",
                        OffsetDateTime.now(ZoneOffset.UTC),
                        OffsetDateTime.now(ZoneOffset.UTC),
                        OffsetDateTime.now(ZoneOffset.UTC))));
    assertThat(missingOrder).as("DB 层必须自己挡住没有订单的消息").isNotNull();
    assertThat(allMessages(missingOrder)).contains("order_reference");
    assertThat(allMessages(missingOrder)).contains("check constraint");
  }

  @Test
  void 登记消息必须挂在真实店铺上() throws Exception {
    http.perform(
            MockMvcRequestBuilders.post("/crm/message-inbox/messages")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    body(
                        UUID.randomUUID(),
                        "buyer-a",
                        "SO-1001",
                        "问个问题",
                        "BUYER_MESSAGE",
                        "INBOUND",
                        "NORMAL",
                        PLATFORM_AT)))
        .andExpect(status().isBadRequest());
    assertThat(countMessages()).isZero();
  }

  // ================================================================ 列表：真分页 + 真筛选

  @Test
  void 列表支持店铺类型优先级未读筛选与真分页() throws Exception {
    String m1 = createMessage(shopId, "buyer-a", "SO-1001", "关于发货");
    String m2 = createMessage(shopId, "buyer-b", "SO-1001", "关于退款");
    String m3 =
        postJson(
                "/crm/message-inbox/messages",
                body(
                    shop2Id,
                    "buyer-c",
                    "SO-2002",
                    "平台通知",
                    "COMPLIANCE",
                    "INBOUND",
                    "NORMAL",
                    PLATFORM_AT))
            .get("id")
            .asText();

    var all = getJson("/crm/message-inbox/messages");
    assertThat(all.get("total").asLong()).isEqualTo(3);
    assertThat(all.get("items")).hasSize(3);

    // 按店铺筛
    var byShop = getJson("/crm/message-inbox/messages?shopId=" + shopId);
    assertThat(byShop.get("total").asLong()).isEqualTo(2);

    // 按类型筛
    var byType = getJson("/crm/message-inbox/messages?messageType=COMPLIANCE");
    assertThat(byType.get("total").asLong()).isEqualTo(1);
    var byTypeNone = getJson("/crm/message-inbox/messages?messageType=SYSTEM_NOTICE");
    assertThat(byTypeNone.get("total").asLong()).isZero();
    var byType2 = getJson("/crm/message-inbox/messages?messageType=BUYER_MESSAGE");
    assertThat(byType2.get("total").asLong()).isEqualTo(2);

    // 按买家 / 订单筛
    assertThat(getJson("/crm/message-inbox/messages?buyerReference=buyer-b").get("total").asLong())
        .isEqualTo(1);
    assertThat(getJson("/crm/message-inbox/messages?orderReference=SO-1001").get("total").asLong())
        .isEqualTo(2);

    // 关键字（主题 / 正文 / 买家 / 订单 / 消息号）
    assertThat(getJson("/crm/message-inbox/messages?keyword=退款").get("total").asLong()).isEqualTo(1);
    assertThat(getJson("/crm/message-inbox/messages?keyword=buyer-c").get("total").asLong()).isEqualTo(1);

    // 真分页：size=1 时总数仍是 3，页里只有 1 条
    var page = getJson("/crm/message-inbox/messages?size=1&page=0");
    assertThat(page.get("items")).hasSize(1);
    assertThat(page.get("total").asLong()).isEqualTo(3);
    assertThat(page.get("size").asInt()).isEqualTo(1);

    // 标记已读后，「未读」筛选能把它筛掉
    postJson(
        "/crm/message-inbox/batch",
        """
        {"action":"MARK_READ","messageIds":["%s"]}
        """
            .formatted(m1));
    assertThat(getJson("/crm/message-inbox/messages?readStatus=UNREAD").get("total").asLong()).isEqualTo(2);
    assertThat(getJson("/crm/message-inbox/messages?readStatus=READ").get("total").asLong()).isEqualTo(1);

    // 优先级筛选（先改一条）
    postJson(
        "/crm/message-inbox/batch",
        """
        {"action":"SET_PRIORITY","messageIds":["%s"],"priority":"URGENT"}
        """
            .formatted(m2));
    assertThat(getJson("/crm/message-inbox/messages?priority=URGENT").get("total").asLong()).isEqualTo(1);

    // 方向筛选
    assertThat(getJson("/crm/message-inbox/messages?direction=INBOUND").get("total").asLong()).isEqualTo(3);
    assertThat(getJson("/crm/message-inbox/messages?direction=OUTBOUND").get("total").asLong()).isZero();

    // 汇总（与分页无关）
    var summary = getJson("/crm/message-inbox/messages").get("summary");
    assertThat(summary.get("totalCount").asLong()).isEqualTo(3);
    assertThat(summary.get("unreadCount").asLong()).isEqualTo(2);
    assertThat(summary.get("readCount").asLong()).isEqualTo(1);
    assertThat(summary.get("byShop")).hasSize(2);
  }

  @Test
  void 平台时间窗筛选按闭开区间() throws Exception {
    createMessage(shopId, "buyer-a", "SO-1001", "早");
    postJson(
        "/crm/message-inbox/messages",
        body(
            shopId,
            "buyer-b",
            "SO-1001",
            "晚",
            "BUYER_MESSAGE",
            "INBOUND",
            "NORMAL",
            "2026-07-01T09:30:00+08:00"));
    assertThat(
            getJson("/crm/message-inbox/messages?platformTimeFrom=2026-06-01T00:00:00Z")
                .get("total")
                .asLong())
        .isEqualTo(2);
    assertThat(
            getJson("/crm/message-inbox/messages?platformTimeFrom=2026-06-15T00:00:00Z")
                .get("total")
                .asLong())
        .isEqualTo(1);
    // 起点含、终点不含（[from, to)）：落在窗口之前的那条不算
    assertThat(
            getJson(
                    "/crm/message-inbox/messages?platformTimeFrom=2026-06-01T10:00:00Z"
                        + "&platformTimeTo=2026-06-02T00:00:00Z")
                .get("total")
                .asLong())
        .isEqualTo(0);
    // 空窗口（终点不晚于起点）必须被**明确拒绝**，不是悄悄返回空集
    http.perform(
            MockMvcRequestBuilders.get(
                    "/crm/message-inbox/messages?platformTimeFrom=2026-06-01T09:30:00Z"
                        + "&platformTimeTo=2026-06-01T09:30:00Z")
                .header("Authorization", "session"))
        .andExpect(status().isBadRequest());
  }

  // ================================================================ 详情与订单上下文

  @Test
  void 详情给出附件与订单上下文() throws Exception {
    String id =
        postJson(
                "/crm/message-inbox/messages",
                """
                {"shopId":"%s","buyerReference":"buyer-a","orderReference":"SO-1001",
                 "subject":"关于发货","direction":"INBOUND","messageType":"BUYER_MESSAGE",
                 "priority":"HIGH","platformTime":"%s","body":"买家留言正文",
                 "attachments":[{"fileName":"photo.png","fileUrl":"https://x/photo.png",
                                "contentType":"image/png","sizeBytes":1024.5}]}
                """
                    .formatted(shopId, PLATFORM_AT))
            .get("id")
            .asText();
    var detail = getJson("/crm/message-inbox/messages/" + id);
    assertThat(detail.get("message").get("orderReference").asText()).isEqualTo("SO-1001");
    assertThat(detail.get("attachments")).hasSize(1);
    assertThat(detail.get("attachments").get(0).get("fileName").asText()).isEqualTo("photo.png");
    assertThat(detail.get("attachments").get(0).get("sizeBytes").decimalValue().toPlainString())
        .isEqualTo("1024.5");

    var ctx = detail.get("orderContext");
    assertThat(ctx.get("available").asBoolean()).as("上游有这一单 ⇒ 上下文可用").isTrue();
    assertThat(ctx.get("orderNo").asText()).isEqualTo("SO-1001");
    assertThat(ctx.get("currency").asText()).isEqualTo("USD");
    assertThat(ctx.get("grandTotalAmount").decimalValue()).isEqualByComparingTo("39.90");
    assertThat(ctx.get("platformStatus").asText()).isEqualTo("AWAITING_SHIPMENT");
  }

  @Test
  void 订单上下文查不到时明确说不可用而不是空白() throws Exception {
    String id = createMessage(shopId, "buyer-a", "SO-NOT-EXIST", "查不到的一单");
    var ctx = getJson("/crm/message-inbox/messages/" + id).get("orderContext");
    assertThat(ctx.get("available").asBoolean()).isFalse();
    assertThat(ctx.get("reason").asText()).contains("订单上下文不可用");
    assertThat(ctx.get("reason").asText()).contains("没有订单号 SO-NOT-EXIST 这一单");
    // ⚠️ 关键：不是空白
    assertThat(ctx.get("orderNo").isNull()).isTrue();
    assertThat(ctx.get("grandTotalAmount").isNull()).isTrue();
  }

  @Test
  void 上游只有相近订单时不做关联() throws Exception {
    // 上游有一单 SO-1001，消息指向 SO-100 —— 相近但不相等。
    String id = createMessage(shopId, "buyer-a", "SO-100", "相近的一单");
    var ctx = getJson("/crm/message-inbox/messages/" + id).get("orderContext");
    assertThat(ctx.get("available").asBoolean()).as("不相等就不能当成关联订单").isFalse();
    assertThat(ctx.get("reason").asText()).contains("相近");
  }

  @Test
  void 上游订单读不到时说读不到而不是没有这一单() throws Exception {
    String id = createMessage(shopId, "buyer-a", "SO-1001", "上游挂了");
    ordersBroken.set(true);
    var ctx = getJson("/crm/message-inbox/messages/" + id).get("orderContext");
    assertThat(ctx.get("available").asBoolean()).isFalse();
    assertThat(ctx.get("reason").asText()).contains("订单上下文不可用");
    assertThat(ctx.get("reason").asText()).contains("读不到");
    assertThat(ctx.get("reason").asText()).doesNotContain("没有订单号");
  }

  @Test
  void 店铺清单读不到时报503而不是空清单() throws Exception {
    shopsBroken.set(true);
    http.perform(MockMvcRequestBuilders.get("/crm/message-inbox/shops").header("Authorization", "session"))
        .andExpect(status().isServiceUnavailable());
  }

  // ================================================================ 批量处理

  @Test
  void 批量处理逐条给出结论() throws Exception {
    String m1 = createMessage(shopId, "buyer-a", "SO-1001", "一");
    String m2 = createMessage(shopId, "buyer-b", "SO-1001", "二");
    var result =
        postJson(
            "/crm/message-inbox/batch",
            """
            {"action":"MARK_READ","messageIds":["%s","%s","%s"]}
            """
                .formatted(m1, m2, UUID.randomUUID()));
    assertThat(result.get("requestedCount").asInt()).isEqualTo(3);
    assertThat(result.get("succeededCount").asInt()).isEqualTo(2);
    assertThat(result.get("failedCount").asInt()).isEqualTo(1);
    assertThat(result.get("items")).hasSize(3);
    // 失败的那条必须说清原因（不存在的消息）
    JsonNode failed = null;
    for (JsonNode item : result.get("items")) {
      if (!item.get("success").asBoolean()) {
        failed = item;
      }
    }
    assertThat(failed).as("必须有一条失败结论").isNotNull();
    assertThat(failed.get("reason").asText()).contains("消息不存在");
    // 成功的消息确实变成已读
    assertThat(getJson("/crm/message-inbox/messages?readStatus=READ").get("total").asLong()).isEqualTo(2);
  }

  @Test
  void 归档后不能再改已读状态() throws Exception {
    String id = createMessage(shopId, "buyer-a", "SO-1001", "归档");
    postJson(
        "/crm/message-inbox/batch",
        """
        {"action":"ARCHIVE","messageIds":["%s"]}
        """
            .formatted(id));
    var result =
        postJson(
            "/crm/message-inbox/batch",
            """
            {"action":"MARK_READ","messageIds":["%s"]}
            """
                .formatted(id));
    assertThat(result.get("succeededCount").asInt()).isZero();
    assertThat(result.get("items").get(0).get("reason").asText()).contains("已归档");
  }

  @Test
  void 处置意图重复登记被拒且可撤销() throws Exception {
    String id = createMessage(shopId, "buyer-a", "SO-1001", "要转售后");
    var first =
        postJson(
            "/crm/message-inbox/batch",
            """
            {"action":"RECORD_INTENT","messageIds":["%s"],"intentType":"AFTERSALE_CASE",
             "reason":"买家要求退货"}
            """
                .formatted(id));
    assertThat(first.get("succeededCount").asInt()).isEqualTo(1);

    var second =
        postJson(
            "/crm/message-inbox/batch",
            """
            {"action":"RECORD_INTENT","messageIds":["%s"],"intentType":"AFTERSALE_CASE",
             "reason":"再登记一次"}
            """
                .formatted(id));
    assertThat(second.get("succeededCount").asInt()).isZero();
    assertThat(second.get("items").get(0).get("reason").asText()).contains("已经登记过");

    // 意图必须**只落在本服务**：库里没有任何下游单据 id。
    var intents = getJson("/crm/message-inbox/messages/" + id).get("intents");
    assertThat(intents).hasSize(1);
    assertThat(intents.get(0).get("intentType").asText()).isEqualTo("AFTERSALE_CASE");
    assertThat(intents.get(0).get("status").asText()).isEqualTo("RECORDED");
    assertThat(intents.get(0).has("afterSaleCaseId")).isFalse();
    assertThat(intents.get(0).has("ticketId")).isFalse();

    // 撤销后可再登记
    postJson(
        "/crm/message-inbox/batch",
        """
        {"action":"CANCEL_INTENT","messageIds":["%s"],"intentType":"AFTERSALE_CASE"}
        """
            .formatted(id));
    var third =
        postJson(
            "/crm/message-inbox/batch",
            """
            {"action":"RECORD_INTENT","messageIds":["%s"],"intentType":"AFTERSALE_CASE",
             "reason":"重新登记"}
            """
                .formatted(id));
    assertThat(third.get("succeededCount").asInt()).isEqualTo(1);
  }

  @Test
  void 登记意图必须写明原因() throws Exception {
    String id = createMessage(shopId, "buyer-a", "SO-1001", "没原因");
    var result =
        postJson(
            "/crm/message-inbox/batch",
            """
            {"action":"RECORD_INTENT","messageIds":["%s"],"intentType":"SERVICE_TICKET"}
            """
                .formatted(id));
    assertThat(result.get("succeededCount").asInt()).isZero();
    assertThat(result.get("items").get(0).get("reason").asText()).contains("必须写明原因");
  }

  // ================================================================ 平台同步未接入

  @Test
  void 平台消息同步未接入时409且不写数据() throws Exception {
    http.perform(
            MockMvcRequestBuilders.post("/crm/message-inbox/sync")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"platformCode":"AMAZON","shopId":"%s"}
                    """
                        .formatted(shopId)))
        .andExpect(status().isConflict());
    assertThat(countMessages()).as("未接入的同步不能写任何一行").isZero();

    // 平台清单逐个答「未接入」
    var platforms = getJson("/crm/message-inbox/platforms");
    assertThat(platforms).hasSize(2);
    for (JsonNode p : platforms) {
      assertThat(p.get("connected").asBoolean()).isFalse();
      assertThat(p.get("reason").asText()).contains("未接入");
    }
  }

  // ================================================================ 附件计数由 DB 维护

  @Test
  void 附件计数由数据库触发器维护() throws Exception {
    String id =
        postJson(
                "/crm/message-inbox/messages",
                """
                {"shopId":"%s","buyerReference":"buyer-a","orderReference":"SO-1001",
                 "direction":"INBOUND","messageType":"BUYER_MESSAGE","platformTime":"%s",
                 "attachments":[{"fileName":"a.png"},{"fileName":"b.png"}]}
                """
                    .formatted(shopId, PLATFORM_AT))
            .get("id")
            .asText();
    assertThat(getJson("/crm/message-inbox/messages/" + id).get("message").get("attachmentCount").asInt())
        .isEqualTo(2);
    // 直接删一行附件，计数必须自己跟着走
    tenants.run(
        tenant,
        () ->
            sql.execute(
                "delete from crm_message_attachment where message_id = ? and file_name = ?",
                UUID.fromString(id),
                "a.png"));
    assertThat(getJson("/crm/message-inbox/messages/" + id).get("message").get("attachmentCount").asInt())
        .isEqualTo(1);
  }

  // ================================================================ 时区不静默偏移

  @Test
  void 平台时间按绝对时刻写回不静默偏移() throws Exception {
    String id = createMessage(shopId, "buyer-a", "SO-1001", "时区");
    var written =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                        "select platform_time from crm_message where id = ?",
                        UUID.fromString(id))
                    .get(0, OffsetDateTime.class));
    assertThat(written.toInstant())
        .as("写入的绝对时刻必须与运营填的 +08:00 时刻一致")
        .isEqualTo(OffsetDateTime.parse(PLATFORM_AT).toInstant());
    // 读回给界面的字符串带同一偏移量
    assertThat(getJson("/crm/message-inbox/messages/" + id).get("message").get("platformTime").asText())
        .startsWith("2026-06-01T09:30");
  }

  // ================================================================ 身份列不可改

  @Test
  void 身份列建后不可改() throws Exception {
    String id = createMessage(shopId, "buyer-a", "SO-1001", "不可改");
    var thrown =
        catchThrowable(
                () ->
                    tenants.run(
                        tenant,
                        () ->
                            sql.execute(
                                "update crm_message set order_reference = 'SO-OTHER'"
                                    + " where tenant_id = ? and id = ?",
                                tenant,
                                UUID.fromString(id))));
    assertThat(thrown).as("身份列必须不可改").isNotNull();
    assertThat(allMessages(thrown)).contains("身份列");
  }

  // ================================================================ 工具

  /** 从 DataAccessException 里取最内层的人话（PG 的 RAISE EXCEPTION / CHECK 消息）。 */
  static String allMessages(Throwable e) {
    StringBuilder sb = new StringBuilder();
    Throwable current = e;
    while (current != null) {
      if (current.getMessage() != null) {
        sb.append(current.getMessage()).append('\n');
      }
      current = current.getCause() == current ? null : current.getCause();
    }
    return sb.toString();
  }

  static String rootMessage(Throwable e) {
    Throwable current = e;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    String message = current.getMessage();
    return message == null ? e.getMessage() : message;
  }

  long countMessages() {
    return sql.fetchOne("select count(*) from crm_message where tenant_id = ?", tenant)
        .get(0, Long.class);
  }
}
