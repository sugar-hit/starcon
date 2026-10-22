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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 15.2.1.1 评价列表的集成测试。
 *
 * <p>跑的是 AGENTS.md 要求的<b>真实 PostgreSQL 16 + FORCE RLS</b>。租户隔离三断言在
 * {@link ProductReviewTenancyIntegrationTest} 里，本类专攻业务不变量：
 *
 * <ul>
 *   <li><b>🔴 加粗句「评分趋势比单条评价更重要」</b>：评分统计必须给出均分、星级分布、
 *       时间趋势，并且<b>下滑要能定位到店铺 / 商品 / 变体</b>；
 *       「连续下滑」= 三窗单调（近窗 &lt; 中窗 &lt; 远窗）。</li>
 *   <li><b>趋势按平台评价时刻分桶</b>：补录老评价**不能**造成假下滑。</li>
 *   <li><b>平台评价同步未接入</b>：同步端点必须 409 且<b>一行都不写</b>。</li>
 *   <li><b>关联订单上下文</b>：读得到时给出订单与履约事实；读不到时<b>明确说明原因</b>，
 *       绝不返回空白（空白会被客服读成「这条评价没有关联订单」）。</li>
 *   <li><b>时区不静默偏移（硬性要求 6）</b>：用 {@code +08:00} 的偏移量写入，
 *       再<b>断言写回的值</b>等于同一个绝对时刻。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductReviewIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID userId = UUID.randomUUID();
  static final UUID shopId = UUID.randomUUID();
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 上游（channel）的店铺清单。 */
  static final List<SignoffDeliveredOrderPort.ShopView> upstreamShops = new CopyOnWriteArrayList<>();

  /** 上游（channel）的签收确认单（按订单号）。 */
  static final Map<String, SignoffDeliveredOrderPort.DeliveredCaseView> upstreamCases =
      new ConcurrentHashMap<>();

  /** 上游（trade）的销售订单（按订单号）。 */
  static final Map<String, ReviewOrderContextPort.SalesOrderRow> upstreamOrders =
      new ConcurrentHashMap<>();

  /** 上游 trade 是否模拟故障。 */
  static final AtomicBoolean tradeDown = new AtomicBoolean(false);

  /** 上游 channel 的签收确认单是否模拟故障。 */
  static final AtomicBoolean signoffDown = new AtomicBoolean(false);

  static HttpServer identity;
  static HttpServer channel;
  static HttpServer trade;

  /** 固定时刻（东八区偏移量，用来验时区不静默偏移）。 */
  static final String REVIEWED_AT = "2026-06-01T09:30:00+08:00";

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
    List<String> permissions = List.of("crm.product_review.read", "crm.product_review.update");
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
    // 上游 stub：真的起 HTTP 服务，端口与真实 channel / trade 服务同形。
    try {
      channel = HttpServer.create(new InetSocketAddress(0), 0);
      channel.createContext(
          "/channel/logistics-pickup-signoff/cases",
          e -> {
            if (signoffDown.get()) {
              e.sendResponseHeaders(503, -1);
              e.close();
              return;
            }
            String keyword = queryParam(e.getRequestURI().getRawQuery(), "keyword");
            // ⚠️ 刻意用**包含**语义（上游的真行为是 ilike '%关键字%'）：
            //   精确匹配是端口的责任，stub 若先替它精确匹配，这条断言就永远绿。
            List<SignoffDeliveredOrderPort.DeliveredCaseView> rows =
                upstreamCases.values().stream()
                    .filter(
                        c ->
                            keyword == null
                                || keyword.isEmpty()
                                || c.salesOrderNo().toLowerCase().contains(keyword.toLowerCase()))
                    .toList();
            write(
                e,
                Map.of(
                    "items", rows, "page", 0, "size", rows.size(), "totalElements", rows.size()));
          });
      channel.createContext("/channel/shops", e -> write(e, List.copyOf(upstreamShops)));
      channel.setExecutor(Executors.newFixedThreadPool(2));
      channel.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    try {
      trade = HttpServer.create(new InetSocketAddress(0), 0);
      trade.createContext(
          "/trade/sales-orders",
          e -> {
            if (tradeDown.get()) {
              e.sendResponseHeaders(503, -1);
              e.close();
              return;
            }
            String keyword = queryParam(e.getRequestURI().getRawQuery(), "keyword");
            List<ReviewOrderContextPort.SalesOrderRow> rows =
                upstreamOrders.values().stream()
                    .filter(
                        o ->
                            keyword == null
                                || keyword.isEmpty()
                                || o.orderNo().toLowerCase().contains(keyword.toLowerCase())
                                || o.externalOrderNo().toLowerCase().contains(keyword.toLowerCase()))
                    .toList();
            write(e, Map.of("rows", rows, "page", 0, "size", rows.size(), "total", rows.size()));
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

  /** 从 raw query 里取一个参数值。 */
  private static String queryParam(String rawQuery, String name) {
    if (rawQuery == null) return null;
    for (String pair : rawQuery.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0 && pair.substring(0, eq).equals(name)) {
        return java.net.URLDecoder.decode(
            pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
      }
    }
    return null;
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

  void seedShop() {
    upstreamShops.clear();
    upstreamShops.add(
        new SignoffDeliveredOrderPort.ShopView(
            shopId, "SHOP-1", "主店铺", "AMAZON", "亚马逊", "SELF_FULFILLED", "ACTIVE"));
  }

  /** 往上游塞一件**已签收**的确认单（按订单号索引）。 */
  void seedDeliveredCase(String orderNo) {
    UUID id = UUID.randomUUID();
    upstreamCases.put(
        orderNo,
        new SignoffDeliveredOrderPort.DeliveredCaseView(
            id,
            "PSO-" + orderNo,
            "HB-001",
            "DHL123",
            "DHL",
            UUID.randomUUID(),
            orderNo,
            "US",
            OffsetDateTime.parse("2026-05-30T09:30:00+08:00"),
            OffsetDateTime.parse("2026-06-01T09:30:00+08:00"),
            "DELIVERED",
            "CONFIRMED"));
  }

  /** 往上游塞一件**未签收**的确认单。 */
  void seedNotDeliveredCase(String orderNo) {
    UUID id = UUID.randomUUID();
    upstreamCases.put(
        orderNo,
        new SignoffDeliveredOrderPort.DeliveredCaseView(
            id,
            "PSO-" + orderNo,
            "HB-002",
            "DHL999",
            "DHL",
            UUID.randomUUID(),
            orderNo,
            "US",
            OffsetDateTime.parse("2026-05-30T09:30:00+08:00"),
            null,
            "PICKED_UP",
            "PENDING"));
  }

  /** 往上游（trade）塞一条销售订单。 */
  void seedOrder(String orderNo, String status) {
    upstreamOrders.put(
        orderNo,
        new ReviewOrderContextPort.SalesOrderRow(
            UUID.randomUUID(), orderNo, "PLATFORM", orderNo, status, "USD", "买家"));
  }

  @BeforeEach
  void reset() throws Exception {
    upstreamCases.clear();
    upstreamOrders.clear();
    tradeDown.set(false);
    signoffDown.set(false);
    seedShop();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("truncate table crm_product_review restart identity cascade");
    }
  }

  // ================================================================ HTTP 助手

  JsonNode postJson(String path, Object body) throws Exception {
    var result =
        http.perform(
                post(path)
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(body)))
            .andReturn();
    if (result.getResponse().getStatus() != 200) {
      throw new AssertionError(
          "HTTP "
              + result.getResponse().getStatus()
              + " : "
              + result.getResponse().getContentAsString());
    }
    return json.readTree(result.getResponse().getContentAsString());
  }

  JsonNode getJson(String path) throws Exception {
    var result = http.perform(get(path).header("Authorization", "session")).andReturn();
    if (result.getResponse().getStatus() != 200) {
      throw new AssertionError(
          "HTTP "
              + result.getResponse().getStatus()
              + " : "
              + result.getResponse().getContentAsString());
    }
    return json.readTree(result.getResponse().getContentAsString());
  }

  /** 失败时把**原因**取出来（ResponseStatusException 走 sendError，响应体是空的）。 */
  String errorOf(String method, String path, Object body) throws Exception {
    var request =
        method.equals("PUT")
            ? put(path)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))
            : post(path)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body));
    var resolved = http.perform(request).andReturn().getResolvedException();
    assertThat(resolved).as("应当抛出带原因的异常").isNotNull();
    return reasonOf(resolved);
  }

  /** 把异常里的可读原因取出来（ResponseStatusException 的 reason）。 */
  static String reasonOf(Throwable resolved) {
    String message = resolved.getMessage() == null ? "" : resolved.getMessage();
    if (resolved instanceof org.springframework.web.server.ResponseStatusException rse
        && rse.getReason() != null) {
      message = message + " " + rse.getReason();
    }
    return message;
  }

  /** 直接落一条评价（绕开 HTTP，用来造趋势数据）。 */
  void insertReview(
      String reviewNo,
      int rating,
      OffsetDateTime reviewedAt,
      String variantRef,
      String productRef,
      boolean replied,
      String salesOrderNo) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into crm_product_review"
                  + "(id,tenant_id,review_no,shop_id,shop_name,platform_code,source,"
                  + " rating,product_ref,product_title,variant_ref,variant_label,image_urls,"
                  + " has_image,has_video,replied,platform_reviewed_at,registered_at,"
                  + " created_at,updated_at)"
                  + " values (?,?,?,?,?,'AMAZON','MANUAL_ENTRY',?,?,?,?,?,'[]'::jsonb,"
                  + " false,false,?,?::timestamptz,?::timestamptz,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              reviewNo,
              shopId,
              "主店铺",
              rating,
              productRef,
              "商品",
              variantRef,
              "变体",
              replied,
              reviewedAt,
              OffsetDateTime.now(ZoneOffset.UTC),
              OffsetDateTime.now(ZoneOffset.UTC),
              OffsetDateTime.now(ZoneOffset.UTC));
          return null;
        });
  }

  Map<String, Object> reviewBody(int rating, String variantRef, String orderNo, String reviewedAt) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("shopId", shopId.toString());
    body.put("rating", rating);
    body.put("content", "评价内容");
    body.put("productRef", "SPU-1");
    body.put("variantRef", variantRef);
    body.put("variantLabel", "变体 " + variantRef);
    body.put("platformReviewedAt", reviewedAt);
    if (orderNo != null) body.put("salesOrderNo", orderNo);
    return body;
  }

  long countReviews() {
    return tenants.run(
        tenant, () -> sql.fetchOne("select count(*) from crm_product_review").get(0, Long.class));
  }

  // ================================================================ 能力声明

  @Test
  void 能力声明逐条说清有没有_未交付的三维必须点名归属模块() throws Exception {
    var node = getJson("/crm/reviews/capabilities");
    var dimensions = node.get("dimensions");
    assertThat(dimensions).as("能力声明必须逐维列出").isNotNull();
    assertThat(dimensions.size()).isGreaterThanOrEqualTo(6);

    var byKey = new LinkedHashMap<String, JsonNode>();
    for (JsonNode d : dimensions) byKey.put(d.get("dimension").asText(), d);

    // 加粗句的两条主能力必须可用，且必须点名 DB 层的落点。
    assertThat(byKey.get("rating_trend").get("available").asBoolean()).isTrue();
    assertThat(byKey.get("decline_attribution").get("available").asBoolean()).isTrue();
    assertThat(byKey.get("rating_trend").get("source").asText()).contains("V294");

    // 未交付的三维必须 available=false **且**带 ownerModule 与 impact —— 不能只标一个 false。
    for (String key :
        List.of("platform_review_sync", "review_reply", "negative_review_handling")) {
      var dimension = byKey.get(key);
      assertThat(dimension).as(key + " 必须出现在能力声明里").isNotNull();
      assertThat(dimension.get("available").asBoolean()).as(key + " 必须标成不可用").isFalse();
      assertThat(dimension.get("ownerModule").asText()).as(key + " 必须点名归属模块").isNotBlank();
      assertThat(dimension.get("impact").asText()).as(key + " 必须说清缺了会怎样").isNotBlank();
    }
  }

  // ================================================================ 登记与列表

  @Test
  void 登记评价后列表能查到_评价号与星级原样回来() throws Exception {
    var created = postJson("/crm/reviews", reviewBody(4, "SKU-1", null, REVIEWED_AT));
    assertThat(created.get("reviewNo").asText()).startsWith("PR-");
    assertThat(created.get("rating").asInt()).isEqualTo(4);
    assertThat(created.get("shopName").asText()).isEqualTo("主店铺");
    assertThat(created.get("platformCode").asText()).isEqualTo("AMAZON");
    assertThat(created.get("variantRef").asText()).isEqualTo("SKU-1");
    assertThat(created.get("hasImage").asBoolean()).isFalse();
    assertThat(created.get("replied").asBoolean()).isFalse();

    var page = getJson("/crm/reviews");
    assertThat(page.get("totalElements").asLong()).isEqualTo(1);
    assertThat(page.get("items").get(0).get("reviewNo").asText())
        .isEqualTo(created.get("reviewNo").asText());
  }

  @Test
  void 时区不静默偏移_写进去的绝对时刻原样读回() throws Exception {
    var created = postJson("/crm/reviews", reviewBody(5, "SKU-1", null, REVIEWED_AT));
    var read = getJson("/crm/reviews").get("items").get(0);
    OffsetDateTime written = OffsetDateTime.parse(read.get("platformReviewedAt").asText());
    assertThat(written.toInstant())
        .as("写入的 +08:00 时刻必须还是同一个绝对时刻（不能偏 8 小时）")
        .isEqualTo(OffsetDateTime.parse(REVIEWED_AT).toInstant());
    assertThat(read.get("platformReviewedAt").asText())
        .isEqualTo(created.get("platformReviewedAt").asText());
  }

  @Test
  void 缺变体或商品的评价被拒_不能把评分下滑的维度问丢掉() throws Exception {
    var body = reviewBody(4, "SKU-1", null, REVIEWED_AT);
    body.remove("variantRef");
    assertThat(errorOf("POST", "/crm/reviews", body)).contains("变体");
    assertThat(countReviews()).isZero();

    var body2 = reviewBody(4, "SKU-1", null, REVIEWED_AT);
    body2.remove("productRef");
    assertThat(errorOf("POST", "/crm/reviews", body2)).contains("商品");
    assertThat(countReviews()).isZero();
  }

  @Test
  void 同一平台评价重复登记被拒_否则评分统计会凭空多算() throws Exception {
    var body = reviewBody(4, "SKU-1", null, REVIEWED_AT);
    body.put("externalReviewId", "EXT-1");
    postJson("/crm/reviews", body);
    assertThat(countReviews()).isEqualTo(1);
    assertThat(errorOf("POST", "/crm/reviews", body)).contains("已经登记过");
    assertThat(countReviews()).isEqualTo(1);
  }

  @Test
  void 评价挂在不存在的店铺上被拒_否则按店铺汇总的趋势会散架() throws Exception {
    var body = reviewBody(4, "SKU-1", null, REVIEWED_AT);
    body.put("shopId", UUID.randomUUID().toString());
    assertThat(errorOf("POST", "/crm/reviews", body)).contains("店铺不存在");
    assertThat(countReviews()).isZero();
  }

  // ================================================================ 筛选面

  @Test
  void 星级_含图_含视频_未回复_时间范围_五个筛选面各自生效() throws Exception {
    var withImage = new LinkedHashMap<>(reviewBody(5, "SKU-A", null, REVIEWED_AT));
    withImage.put("imageUrls", List.of("https://cdn.example.com/a.jpg"));
    postJson("/crm/reviews", withImage);

    var withVideo = new LinkedHashMap<>(reviewBody(1, "SKU-B", null, REVIEWED_AT));
    withVideo.put("videoUrl", "https://cdn.example.com/b.mp4");
    postJson("/crm/reviews", withVideo);

    var replied = new LinkedHashMap<>(reviewBody(3, "SKU-C", null, REVIEWED_AT));
    replied.put("replied", true);
    replied.put("repliedAt", "2026-06-02T10:00:00+08:00");
    postJson("/crm/reviews", replied);

    var old = new LinkedHashMap<>(reviewBody(2, "SKU-D", null, "2025-01-01T10:00:00Z"));
    postJson("/crm/reviews", old);

    assertThat(getJson("/crm/reviews").get("totalElements").asLong()).isEqualTo(4);
    assertThat(getJson("/crm/reviews?rating=1").get("totalElements").asLong()).isEqualTo(1);
    assertThat(getJson("/crm/reviews?hasImage=true").get("totalElements").asLong()).isEqualTo(1);
    assertThat(getJson("/crm/reviews?hasVideo=true").get("totalElements").asLong()).isEqualTo(1);
    assertThat(getJson("/crm/reviews?unreplied=true").get("totalElements").asLong()).isEqualTo(3);
    assertThat(getJson("/crm/reviews?reviewedFrom=2026-01-01T00:00:00Z").get("totalElements").asLong())
        .isEqualTo(3);
    assertThat(getJson("/crm/reviews?reviewedTo=2026-01-01T00:00:00Z").get("totalElements").asLong())
        .isEqualTo(1);
    // 变体 / 商品维度筛选（「定位到维度」要能从列表侧筛进去）
    assertThat(getJson("/crm/reviews?variantRef=SKU-A").get("totalElements").asLong()).isEqualTo(1);
    assertThat(getJson("/crm/reviews?productRef=SPU-1").get("totalElements").asLong()).isEqualTo(4);
  }

  @Test
  void 分页是真分页_总数与页数都来自真实查询() throws Exception {
    for (int i = 0; i < 5; i++) {
      insertReview(
          "PR-SEED-" + i,
          4,
          OffsetDateTime.now(ZoneOffset.UTC).minusDays(i + 1),
          "SKU-" + i,
          "SPU-1",
          false,
          null);
    }
    var first = getJson("/crm/reviews?page=0&size=2");
    assertThat(first.get("items").size()).isEqualTo(2);
    assertThat(first.get("totalElements").asLong()).isEqualTo(5);
    assertThat(first.get("totalPages").asInt()).isEqualTo(3);
    var last = getJson("/crm/reviews?page=2&size=2");
    assertThat(last.get("items").size()).isEqualTo(1);
  }

  // ================================================================ 评分统计

  @Test
  void 评分统计给出均分_分布_趋势_下滑维度() throws Exception {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    // 远窗（30~20 天前）5 星、中窗（20~10 天前）3 星、近窗（10 天内）1 星 —— 单调下滑。
    // ⚠️ 时刻刻意**避开**趋势桶的边界（桶宽 3 天，边界在 -30/-27/-24/… 天）：
    //   落在边界上的评价会被切进两个桶，那是分桶的正常行为，不是缺陷。
    for (int i = 0; i < 3; i++) {
      insertReview(
          "PR-OLD-" + i,
          5,
          now.minusDays(25).plusHours(12 + i),
          "SKU-DOWN",
          "SPU-DOWN",
          false,
          null);
    }
    for (int i = 0; i < 3; i++) {
      insertReview(
          "PR-MID-" + i,
          3,
          now.minusDays(16).plusHours(12 + i),
          "SKU-DOWN",
          "SPU-DOWN",
          false,
          null);
    }
    for (int i = 0; i < 3; i++) {
      insertReview(
          "PR-NEW-" + i,
          1,
          now.minusDays(1).plusHours(12 + i),
          "SKU-DOWN",
          "SPU-DOWN",
          false,
          null);
    }

    var stats = getJson("/crm/reviews/stats?days=30");
    assertThat(stats.get("totalReviews").asLong()).isEqualTo(9);
    assertThat(stats.get("unrepliedCount").asLong()).isEqualTo(9);
    assertThat(stats.get("averageRating").decimalValue()).isEqualByComparingTo("3.0");

    // 星级分布：1/3/5 各 3 条，2 与 4 是 0 条（空档也列出来）。
    var distribution = stats.get("ratingDistribution");
    assertThat(distribution.size()).isEqualTo(5);
    assertThat(distribution.get(0).get("rating").asInt()).isEqualTo(5);
    assertThat(distribution.get(0).get("count").asLong()).isEqualTo(3);
    assertThat(distribution.get(2).get("rating").asInt()).isEqualTo(3);
    assertThat(distribution.get(3).get("rating").asInt()).isEqualTo(2);
    assertThat(distribution.get(3).get("count").asLong()).isZero();

    // 趋势：按时间先后，均分 5 → 3 → 1（空桶也补出来，count=0）。
    var trend = stats.get("trend");
    assertThat(trend.size()).as("趋势必须有多个时间桶").isGreaterThan(1);
    List<JsonNode> filled = new java.util.ArrayList<>();
    for (JsonNode bucket : trend) {
      if (bucket.get("count").asLong() > 0) filled.add(bucket);
    }
    assertThat(filled.size()).as("三个时间窗各有一个桶").isEqualTo(3);
    assertThat(filled.get(0).get("averageRating").decimalValue()).isEqualByComparingTo("5.0");
    assertThat(filled.get(1).get("averageRating").decimalValue()).isEqualByComparingTo("3.0");
    assertThat(filled.get(2).get("averageRating").decimalValue()).isEqualByComparingTo("1.0");
    // 桶的时间必须递增（趋势的轴是平台评价时刻）。
    assertThat(filled.get(0).get("bucketStart").asText())
        .isLessThan(filled.get(1).get("bucketStart").asText());

    // 🔴 加粗句：下滑要能**定位到维度**（店铺 / 商品 / 变体三处都要有）。
    var declining = stats.get("declining");
    assertThat(declining.size()).as("必须列出在下滑的维度").isGreaterThanOrEqualTo(3);
    var dimensions = new LinkedHashSet<String>();
    for (JsonNode d : declining) dimensions.add(d.get("dimension").asText());
    assertThat(dimensions).contains("SHOP", "PRODUCT", "VARIANT");
    for (JsonNode d : declining) {
      assertThat(d.get("delta").decimalValue()).as("delta 必须是负数（下滑）").isNegative();
    }
    JsonNode variantDecline = null;
    for (JsonNode d : declining) {
      if ("VARIANT".equals(d.get("dimension").asText())) variantDecline = d;
    }
    assertThat(variantDecline).as("变体维度必须能定位到").isNotNull();
    assertThat(variantDecline.get("key").asText()).isEqualTo("SKU-DOWN");
    assertThat(variantDecline.get("continuous").asBoolean())
        .as("三窗单调下滑 ⇒ 必须标成连续下滑")
        .isTrue();
  }

  @Test
  void 趋势按平台评价时刻分桶_补录老评价不会造成假下滑() throws Exception {
    // 两条**老**评价（60 天前），但**刚刚**登记（registered_at 是现在）。
    insertReview(
        "PR-OLD-1",
        1,
        OffsetDateTime.now(ZoneOffset.UTC).minusDays(60),
        "SKU-X",
        "SPU-X",
        false,
        null);
    insertReview(
        "PR-OLD-2",
        1,
        OffsetDateTime.now(ZoneOffset.UTC).minusDays(60),
        "SKU-X",
        "SPU-X",
        false,
        null);
    var stats = getJson("/crm/reviews/stats?days=30");
    // 作用域按**平台评价时刻**算 ⇒ 这两条**不在** 30 天窗口里。
    assertThat(stats.get("totalReviews").asLong()).isZero();
  }

  @Test
  void 统计与列表共用同一套筛选口径() throws Exception {
    insertReview(
        "PR-A",
        5,
        OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
        "SKU-A",
        "SPU-A",
        false,
        null);
    insertReview(
        "PR-B",
        1,
        OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
        "SKU-B",
        "SPU-B",
        false,
        null);
    assertThat(getJson("/crm/reviews?variantRef=SKU-A").get("totalElements").asLong()).isEqualTo(1);
    var stats = getJson("/crm/reviews/stats?days=30&variantRef=SKU-A");
    assertThat(stats.get("totalReviews").asLong()).isEqualTo(1);
    assertThat(stats.get("averageRating").decimalValue()).isEqualByComparingTo("5.0");
  }

  @Test
  void 作用域按天算_默认30天() throws Exception {
    var stats = getJson("/crm/reviews/stats");
    assertThat(stats.get("scopeDays").asInt()).isEqualTo(30);
    assertThat(stats.get("scopeFrom").asText()).isNotBlank();
    assertThat(stats.get("scopeTo").asText()).isNotBlank();
  }

  // ================================================================ 平台同步（未接入）

  @Test
  void 平台评价同步未接入_返回409且一行都不写() throws Exception {
    long before = countReviews();
    var result =
        http.perform(
                post("/crm/reviews/sync")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(Map.of("shopId", shopId.toString()))))
            .andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(409);
    var resolved = result.getResolvedException();
    assertThat(resolved).isNotNull();
    assertThat(reasonOf(resolved)).contains("适配器");
    assertThat(countReviews()).as("同步请求一行都不能写").isEqualTo(before);
  }

  // ================================================================ 关联订单上下文

  @Test
  void 订单上下文读得到时给出订单与履约事实() throws Exception {
    seedOrder("SO-1", "SHIPPED");
    seedDeliveredCase("SO-1");
    var created = postJson("/crm/reviews", reviewBody(4, "SKU-1", "SO-1", REVIEWED_AT));
    var detail = getJson("/crm/reviews/" + created.get("id").asText());
    var context = detail.get("orderContext");
    assertThat(context.get("available").asBoolean()).isTrue();
    assertThat(context.get("salesOrderNo").asText()).isEqualTo("SO-1");
    assertThat(context.get("orderStatus").asText()).isEqualTo("SHIPPED");
    assertThat(context.get("deliveryState").asText()).isEqualTo("CONFIRMED");
    assertThat(context.get("carrierCode").asText()).isEqualTo("DHL");
    assertThat(context.get("deliveredAt").asText()).isNotBlank();
  }

  @Test
  void 订单上下文读不到时明确说明原因_不留空白() throws Exception {
    seedOrder("SO-2", "SHIPPED");
    tradeDown.set(true);
    var created = postJson("/crm/reviews", reviewBody(4, "SKU-1", "SO-2", REVIEWED_AT));
    var detail = getJson("/crm/reviews/" + created.get("id").asText());
    var context = detail.get("orderContext");
    assertThat(context.get("available").asBoolean()).as("读不到就必须明说").isFalse();
    assertThat(context.get("reason").asText()).as("必须说明原因").isNotBlank();
    assertThat(context.get("reason").asText()).contains("订单事实读不到");
    assertThat(context.get("orderStatus").isNull()).as("读不到就不能凭空给一个状态").isTrue();
  }

  @Test
  void 上游没有这张单的签收记录时说明订单已定位但履约未知() throws Exception {
    seedOrder("SO-3", "SHIPPED");
    // 不塞签收记录 ⇒ 上游 channel 确实没有这张单。
    var created = postJson("/crm/reviews", reviewBody(4, "SKU-1", "SO-3", REVIEWED_AT));
    var context = getJson("/crm/reviews/" + created.get("id").asText()).get("orderContext");
    assertThat(context.get("available").asBoolean()).isTrue();
    assertThat(context.get("reason").asText()).contains("没有这张单的签收记录");
    assertThat(context.get("deliveryState").isNull()).isTrue();
  }

  @Test
  void 履约事实读不到时订单结论仍然保留_一个上游挂掉不抹掉另一个() throws Exception {
    seedOrder("SO-4", "SHIPPED");
    signoffDown.set(true);
    var created = postJson("/crm/reviews", reviewBody(4, "SKU-1", "SO-4", REVIEWED_AT));
    var context = getJson("/crm/reviews/" + created.get("id").asText()).get("orderContext");
    assertThat(context.get("available").asBoolean()).isTrue();
    assertThat(context.get("orderStatus").asText()).isEqualTo("SHIPPED");
    assertThat(context.get("reason").asText()).contains("履约事实读不到");
    assertThat(context.get("deliveredAt").isNull()).isTrue();
  }

  @Test
  void 没有登记订单号的评价明确说明本来就没有关联订单() throws Exception {
    var created = postJson("/crm/reviews", reviewBody(4, "SKU-1", null, REVIEWED_AT));
    var context = getJson("/crm/reviews/" + created.get("id").asText()).get("orderContext");
    assertThat(context.get("available").asBoolean()).isFalse();
    assertThat(context.get("reason").asText()).contains("没有登记关联订单号");
  }

  @Test
  void 订单号必须精确匹配_不能把别的单的履约事实挂上来() throws Exception {
    seedOrder("SO-1", "SHIPPED");
    seedDeliveredCase("SO-1");
    seedNotDeliveredCase("SO-10");
    var created = postJson("/crm/reviews", reviewBody(4, "SKU-1", "SO-1", REVIEWED_AT));
    var context = getJson("/crm/reviews/" + created.get("id").asText()).get("orderContext");
    // 上游的 keyword 是 ilike '%SO-1%' ⇒ 「SO-10」也会被捞回来。
    // 端口必须**精确匹配**，否则 SO-10 的履约事实会挂到 SO-1 的评价上。
    assertThat(context.get("caseNo").asText()).isEqualTo("PSO-SO-1");
    assertThat(context.get("deliveryState").asText()).isEqualTo("CONFIRMED");
  }

  // ================================================================ 导出

  @Test
  void 导出CSV包含当前筛选结果() throws Exception {
    insertReview(
        "PR-EXPORT-1",
        2,
        OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
        "SKU-EXPORT",
        "SPU-EXPORT",
        false,
        null);
    insertReview(
        "PR-EXPORT-2",
        5,
        OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
        "SKU-OTHER",
        "SPU-OTHER",
        false,
        null);
    var result =
        http.perform(
                get("/crm/reviews/export?variantRef=SKU-EXPORT").header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(result.getResponse().getContentType()).contains("text/csv");
    assertThat(result.getResponse().getHeader("X-Export-Row-Count")).isEqualTo("1");
    assertThat(result.getResponse().getHeader("X-Export-Truncated")).isEqualTo("false");
    String csv = result.getResponse().getContentAsString();
    assertThat(csv).contains("PR-EXPORT-1").doesNotContain("PR-EXPORT-2");
    assertThat(csv).contains("评价号,平台,店铺,星级");
  }

  // ================================================================ 店铺目录

  @Test
  void 店铺目录来自上游真实清单_并标出平台评价未接入() throws Exception {
    var shops = getJson("/crm/reviews/shops");
    assertThat(shops.size()).isEqualTo(1);
    assertThat(shops.get(0).get("id").asText()).isEqualTo(shopId.toString());
    assertThat(shops.get(0).get("platformReviewAvailable").asBoolean())
        .as("平台评价适配器未接入，必须标成不可用")
        .isFalse();
    assertThat(shops.get(0).get("platformReviewNote").asText()).isNotBlank();
  }
}
