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
import java.time.Duration;
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
 * 15.2.2.1 Request a Review（索评）的集成测试。
 *
 * <p>跑的是 AGENTS.md 要求的<b>真实 PostgreSQL 16 + FORCE RLS</b>。租户隔离三断言在
 * {@link ReviewRequestTenancyIntegrationTest} 里，本类专攻业务不变量：
 *
 * <ul>
 *   <li><b>🔴 加粗句「索评频次必须受限」</b>：每订单上限 / 每买家上限 / 冷却窗口三个上限
 *       各自要能把索评<b>挡住</b>，被挡住的请求<b>留下原因</b>（status=REJECTED +
 *       reject_reason），而<b>被拒绝的不占额度</b>。
 *   <li><b>上限不是界面提示</b>：绕过服务层直接写库，更松的规则必须被 DB 触发器拒掉。
 *   <li><b>上游不造数据</b>：上游确认单没签收就不许索评；上游读不到就 503，
 *       绝不返回空集合冒充「没有已签收订单」。
 *   <li><b>平台侧索评接口未接入</b>：{@code transport=PLATFORM_API} 必须被拒 ——
 *       一个「返回成功回执」的假通道会让「买家已经被索评过」在数据上看起来发生过。
 *   <li><b>评价只落回收桶</b>：下游 15.2.1.1 未交付，被频次拒绝的请求不可能有评价回收。
 *   <li><b>时区不静默偏移（硬性要求 6）</b>：用 {@code +08:00} 的偏移量写入，
 *       再<b>断言写回的值</b>等于同一个绝对时刻。
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReviewRequestIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID userId = UUID.randomUUID();
  static final UUID shopId = UUID.randomUUID();
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 上游（channel）的已签收确认单 —— 测试自己往里塞，**不 mock 上游服务**。 */
  static final Map<UUID, SignoffDeliveredOrderPort.DeliveredCaseView> upstreamCases =
      new ConcurrentHashMap<>();

  /** 上游（channel）的店铺清单。 */
  static final List<SignoffDeliveredOrderPort.ShopView> upstreamShops = new CopyOnWriteArrayList<>();

  static HttpServer identity;
  static HttpServer channel;

  /** 固定的上游签收时刻（东八区偏移量，用来验时区不静默偏移）。 */
  static final String DELIVERED_AT = "2026-06-01T09:30:00+08:00";

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
    List<String> permissions = List.of("crm.review.read", "crm.review.update");
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
          "/channel/logistics-pickup-signoff/cases",
          e -> {
            String path = e.getRequestURI().getPath();
            // /cases/{id} → 单件详情；/cases → 列表
            if (path.endsWith("/cases")) {
              var rows =
                  upstreamCases.values().stream()
                      .filter(c -> "DELIVERED".equals(c.status()))
                      .toList();
              Map<String, Object> page =
                  Map.of(
                      "items",
                      rows,
                      "page",
                      0,
                      "size",
                      rows.size(),
                      "totalElements",
                      rows.size(),
                      "totalPages",
                      rows.isEmpty() ? 0 : 1);
              write(e, page);
              return;
            }
            String id = path.substring(path.lastIndexOf('/') + 1);
            var found = upstreamCases.get(UUID.fromString(id));
            if (found == null) {
              e.sendResponseHeaders(404, -1);
              e.close();
              return;
            }
            write(e, Map.of("signoffCase", found));
          });
      channel.createContext(
          "/channel/shops",
          e -> write(e, List.copyOf(upstreamShops)));
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
    r.add("CHANNEL_URL", () -> "http://127.0.0.1:" + channel.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  /** 默认策略：每买家 3 次/30 天、冷却 72 小时、每订单 1 次、时点下限 24 小时。 */
  void seedPolicy(
      int maxPerBuyer,
      int windowDays,
      double minCooldownHours,
      int maxPerOrder,
      double minDelayHours,
      int maxBatchSize) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into crm_review_frequency_policy"
                  + "(id,tenant_id,max_per_buyer_per_window,window_days,min_cooldown_hours,"
                  + " max_per_order,min_delay_hours,max_batch_size,created_at,updated_at)"
                  + " values (?,?,?,?,?,?,?,?,now(),now())"
                  + " on conflict (tenant_id) do update set"
                  + " max_per_buyer_per_window=excluded.max_per_buyer_per_window,"
                  + " window_days=excluded.window_days,"
                  + " min_cooldown_hours=excluded.min_cooldown_hours,"
                  + " max_per_order=excluded.max_per_order,"
                  + " min_delay_hours=excluded.min_delay_hours,"
                  + " max_batch_size=excluded.max_batch_size",
              UUID.randomUUID(),
              tenant,
              maxPerBuyer,
              windowDays,
              java.math.BigDecimal.valueOf(minCooldownHours),
              maxPerOrder,
              java.math.BigDecimal.valueOf(minDelayHours),
              maxBatchSize);
          return null;
        });
  }

  void seedShop() {
    upstreamShops.clear();
    upstreamShops.add(
        new SignoffDeliveredOrderPort.ShopView(
            shopId, "SHOP-1", "主店铺", "AMAZON", "亚马逊", "SELF_FULFILLED", "ACTIVE"));
  }

  /** 往上游塞一件已签收的确认单。 */
  void seedDeliveredCase(String orderNo, String caseNo) {
    UUID id = UUID.randomUUID();
    upstreamCases.put(
        id,
        new SignoffDeliveredOrderPort.DeliveredCaseView(
            id,
            caseNo,
            "HB-001",
            "DHL123",
            "DHL",
            UUID.randomUUID(),
            orderNo,
            "US",
            OffsetDateTime.parse("2026-05-30T09:30:00+08:00"),
            OffsetDateTime.parse(DELIVERED_AT),
            "DELIVERED",
            "CONFIRMED"));
  }

  /** 往上游塞一件**未签收**的确认单。 */
  void seedNotDeliveredCase(String orderNo, String caseNo) {
    UUID id = UUID.randomUUID();
    upstreamCases.put(
        id,
        new SignoffDeliveredOrderPort.DeliveredCaseView(
            id,
            caseNo,
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

  @BeforeEach
  void reset() throws Exception {
    upstreamCases.clear();
    upstreamShops.clear();
    seedShop();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table crm_review_recovery, crm_review_send_record, crm_review_request,"
              + " crm_review_frequency_policy restart identity cascade");
    }
    seedPolicy(3, 30, 72, 1, 24, 200);
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

  JsonNode putJson(String path, Object body) throws Exception {
    var result =
        http.perform(
                put(path)
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

  /**
   * 失败时把**原因**取出来。
   *
   * <p>⚠️ 不能读响应体：{@code ResponseStatusException} 走 Spring 的 {@code sendError}，
   * MockMvc 里**响应体是空的**，原因在 {@code getResolvedException()} 上。
   * （本仓既有测试也是这么取的，见 trade 的 OrderTimelinessIntegrationTest。）
   */
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
    assertThat(resolved).as("应当抛出带原因���异常").isNotNull();
    String message = resolved.getMessage() == null ? "" : resolved.getMessage();
    if (resolved instanceof org.springframework.web.server.ResponseStatusException rse
        && rse.getReason() != null) {
      message = message + " " + rse.getReason();
    }
    return message;
  }

  Map<String, Object> rule(double delay, double cooldown, int perBuyer, int perOrder) {
    return Map.of(
        "delayHours",
        java.math.BigDecimal.valueOf(delay),
        "cooldownHours",
        java.math.BigDecimal.valueOf(cooldown),
        "maxPerBuyer",
        perBuyer,
        "maxPerOrder",
        perOrder);
  }

  Map<String, Object> createBody(UUID caseId, String buyerRef, Map<String, Object> rule) {
    return Map.of(
        "upstreamCaseId", caseId.toString(),
        "shopId", shopId.toString(),
        "buyerRef", buyerRef,
        "rule", rule);
  }

  long countRequests(String status) {
    return tenants.run(
        tenant,
        () ->
            sql.fetchOne(
                    "select count(*) from crm_review_request where tenant_id = ? and status = ?",
                    tenant,
                    status)
                .get(0, Long.class));
  }

  // ================================================================ 能力声明

  @Test
  void 能力声明逐条说清有没有_缺的三维必须点名归属模块() throws Exception {
    var result =
        http.perform(get("/crm/review-requests/capabilities").header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode node = json.readTree(result.getResponse().getContentAsString());
    var dimensions = node.get("dimensions");
    assertThat(dimensions).as("能力声明必须逐维列出").isNotNull();
    assertThat(dimensions.size()).isGreaterThanOrEqualTo(6);

    var byKey = new java.util.LinkedHashMap<String, JsonNode>();
    for (JsonNode d : dimensions) {
      byKey.put(d.get("dimension").asText(), d);
    }
    // 上游签收事实：available=true。
    assertThat(byKey.get("delivered_order_source").get("available").asBoolean()).isTrue();
    // 频次受限：available=true，且必须点名 DB 层的落点。
    assertThat(byKey.get("frequency_control").get("available").asBoolean()).as("频次维度必须可用").isTrue();
    assertThat(byKey.get("frequency_control").get("source").asText()).contains("V220");

    // 三维必须 available=false **且**带 ownerModule 与 impact —— 不能只标一个 false。
    for (String key : List.of("candidate_shop_attribution", "buyer_identity", "platform_review_api")) {
      var dimension = byKey.get(key);
      assertThat(dimension).as(key + " 必须出现在能力声明里").isNotNull();
      assertThat(dimension.get("available").asBoolean()).as(key + " 必须标成不可用").isFalse();
      assertThat(dimension.get("ownerModule").asText()).as(key + " 必须点名归属模块").isNotBlank();
      assertThat(dimension.get("impact").asText()).as(key + " 必须说清缺了会怎样").isNotBlank();
    }
  }

  // ================================================================ 频次硬上限

  @Test
  void 频次策略读写往返_上限值原样回来() throws Exception {
    var result =
        putJson(
            "/crm/review-requests/frequency-policy",
            Map.of(
                "maxPerBuyerPerWindow", 2,
                "windowDays", 14,
                "minCooldownHours", java.math.BigDecimal.valueOf(48),
                "maxPerOrder", 1,
                "minDelayHours", java.math.BigDecimal.valueOf(12),
                "maxBatchSize", 50));
    assertThat(result.get("maxPerBuyerPerWindow").asInt()).isEqualTo(2);
    assertThat(result.get("windowDays").asInt()).isEqualTo(14);
    assertThat(result.get("minCooldownHours").decimalValue()).isEqualByComparingTo("48");
    assertThat(result.get("maxBatchSize").asInt()).isEqualTo(50);
  }

  @Test
  void 频次策略没配时不给默认额度_发索评直接被拒() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("delete from crm_review_frequency_policy");
    }
    seedDeliveredCase("SO-1", "PSO-1");
    var caseId = upstreamCases.keySet().iterator().next();
    String body = errorOf("POST", "/crm/review-requests", createBody(caseId, "B-1", rule(48, 96, 3, 1)));
    assertThat(body).contains("频次策略");
    assertThat(countRequests("SCHEDULED")).isZero();
  }

  @Test
  void 规则比租户硬上限更松时直接被拒_不许把上限调松绕过去() throws Exception {
    seedDeliveredCase("SO-1", "PSO-1");
    var caseId = upstreamCases.keySet().iterator().next();
    // 每买家上限 9 次 > 租户硬上限 3 次。
    String body =
        errorOf("POST", "/crm/review-requests", createBody(caseId, "B-1", rule(48, 96, 9, 1)));
    assertThat(body).contains("更松");
    assertThat(countRequests("SCHEDULED")).isZero();
  }

  // ================================================================ 单条发起索评

  @Test
  void 正常发起索评_排期时刻等于签收时刻加时点_时间不静默偏移() throws Exception {
    seedDeliveredCase("SO-1", "PSO-1");
    var caseId = upstreamCases.keySet().iterator().next();
    var result = postJson("/crm/review-requests", createBody(caseId, "B-1", rule(48, 96, 3, 1)));

    assertThat(result.get("status").asText()).isEqualTo("SCHEDULED");
    assertThat(result.get("requestNo").asText()).startsWith("RR-");
    assertThat(result.get("salesOrderNo").asText()).isEqualTo("SO-1");
    assertThat(result.get("buyerRef").asText()).isEqualTo("B-1");
    assertThat(result.get("platformCode").asText()).isEqualTo("AMAZON");
    assertThat(result.get("shopName").asText()).isEqualTo("主店铺");

    // 🔴 断言**写回的值**：签收时刻必须还是同一个绝对时刻（+08:00 偏移，不是偏了 8 小时的墙钟）。
    OffsetDateTime deliveredAt = OffsetDateTime.parse(DELIVERED_AT);
    OffsetDateTime stored =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                        "select delivered_at from crm_review_request where tenant_id = ? and id = ?",
                        tenant,
                        java.util.UUID.fromString(result.get("id").asText()))
                    .get(0, OffsetDateTime.class));
    assertThat(stored.toInstant()).as("签收时刻必须原样写回（时区不静默偏移）").isEqualTo(deliveredAt.toInstant());

    // 排期时刻 = 签收时刻 + 时点（48 小时）。
    OffsetDateTime scheduled = OffsetDateTime.parse(result.get("scheduledAt").asText());
    assertThat(scheduled.toInstant())
        .as("排期时刻必须等于签收时刻加时点")
        .isEqualTo(deliveredAt.plus(Duration.ofHours(48)).toInstant());
  }

  @Test
  void 上游还没签收就不许索评() throws Exception {
    seedNotDeliveredCase("SO-9", "PSO-9");
    var caseId = upstreamCases.keySet().iterator().next();
    String body = errorOf("POST", "/crm/review-requests", createBody(caseId, "B-1", rule(48, 96, 3, 1)));
    assertThat(body).contains("尚未签收");
    assertThat(countRequests("SCHEDULED")).isZero();
  }

  @Test
  void 目标店铺不在上游店铺清单里就拒绝() throws Exception {
    seedDeliveredCase("SO-1", "PSO-1");
    var caseId = upstreamCases.keySet().iterator().next();
    var body =
        Map.of(
            "upstreamCaseId", caseId.toString(),
            "shopId", UUID.randomUUID().toString(),
            "buyerRef", "B-1",
            "rule", rule(48, 96, 3, 1));
    String text = errorOf("POST", "/crm/review-requests", body);
    assertThat(text).contains("店铺清单");
  }

  // ================================================================ 🔴 频次上限真正挡住索评

  @Test
  void 同一订单第二次索评被挡住_并留下原因() throws Exception {
    seedDeliveredCase("SO-1", "PSO-1");
    var caseId = upstreamCases.keySet().iterator().next();
    postJson("/crm/review-requests", createBody(caseId, "B-1", rule(48, 96, 3, 1)));

    var second = postJson("/crm/review-requests", createBody(caseId, "B-2", rule(48, 96, 3, 1)));
    assertThat(second.get("status").asText()).isEqualTo("REJECTED");
    assertThat(second.get("rejectCode").asText()).isEqualTo("PER_ORDER_LIMIT");
    assertThat(second.get("rejectReason").asText()).contains("每订单上限");

    // 拒绝**留下原因**：那一行确实落库了。
    assertThat(countRequests("REJECTED")).isEqualTo(1);
    assertThat(countRequests("SCHEDULED")).isEqualTo(1);
  }

  @Test
  void 冷却窗口没过时同一买家的另一张单也被挡住() throws Exception {
    seedDeliveredCase("SO-1", "PSO-1");
    seedDeliveredCase("SO-2", "PSO-2");
    var ids = new ArrayList<>(upstreamCases.keySet());
    postJson("/crm/review-requests", createBody(ids.get(0), "B-1", rule(48, 96, 3, 1)));

    var second = postJson("/crm/review-requests", createBody(ids.get(1), "B-1", rule(48, 96, 3, 1)));
    assertThat(second.get("status").asText()).isEqualTo("REJECTED");
    assertThat(second.get("rejectCode").asText()).isEqualTo("COOLDOWN_ACTIVE");
    assertThat(second.get("rejectReason").asText()).contains("冷却窗口");
  }

  @Test
  void 每买家上限在窗口内被挡住() throws Exception {
    // 放宽到「每订单 2 次、冷却 1 小时、每买家 2 次」，才能把买家上限单独测出来。
    seedPolicy(2, 30, 1, 2, 24, 200);
    seedDeliveredCase("SO-1", "PSO-1");
    seedDeliveredCase("SO-2", "PSO-2");
    seedDeliveredCase("SO-3", "PSO-3");
    var ids = new ArrayList<>(upstreamCases.keySet());

    postJson("/crm/review-requests", createBody(ids.get(0), "B-1", rule(48, 1, 2, 2)));
    // 把第一条挪到 2 小时前：冷却窗口（1 小时）已过，但仍在 30 天窗口内 ⇒ 买家上限仍然计数。
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "update crm_review_request set created_at = now() - interval '2 hours'"
                  + " where tenant_id = ? and buyer_ref = 'B-1'",
              tenant);
          return null;
        });
    postJson("/crm/review-requests", createBody(ids.get(1), "B-1", rule(48, 1, 2, 2)));
    var third = postJson("/crm/review-requests", createBody(ids.get(2), "B-1", rule(48, 1, 2, 2)));

    assertThat(third.get("status").asText()).isEqualTo("REJECTED");
    assertThat(third.get("rejectCode").asText()).isEqualTo("PER_BUYER_LIMIT");
    assertThat(third.get("rejectReason").asText()).contains("每买家上限");
  }

  @Test
  void 被拒绝的请求不占额度_放宽上限后同一买家仍能再排一次() throws Exception {
    // 每买家上限 1 次、冷却 1 小时、每订单 3 次 —— 让「每买家上限」单独成为被挡的原因。
    seedPolicy(1, 30, 1, 3, 24, 200);
    seedDeliveredCase("SO-1", "PSO-1");
    seedDeliveredCase("SO-2", "PSO-2");
    seedDeliveredCase("SO-3", "PSO-3");
    var ids = new ArrayList<>(upstreamCases.keySet());

    postJson("/crm/review-requests", createBody(ids.get(0), "B-1", rule(48, 1, 1, 3)));
    // 把第一条挪到 2 小时前：冷却窗口（1 小时）已过，但仍在 30 天窗口内 ⇒ 买家上限仍然计数。
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "update crm_review_request set created_at = now() - interval '2 hours'"
                  + " where tenant_id = ? and buyer_ref = 'B-1'",
              tenant);
          return null;
        });
    var rejected = postJson("/crm/review-requests", createBody(ids.get(1), "B-1", rule(48, 1, 1, 3)));
    assertThat(rejected.get("status").asText()).isEqualTo("REJECTED");
    assertThat(rejected.get("rejectCode").asText()).isEqualTo("PER_BUYER_LIMIT");

    // 放宽每买家上限到 2 —— 被拒绝的那一次**没有**占额度（否则这里仍然是 REJECTED）。
    seedPolicy(2, 30, 1, 3, 24, 200);
    var again = postJson("/crm/review-requests", createBody(ids.get(2), "B-1", rule(48, 1, 2, 3)));
    assertThat(again.get("status").asText()).as("被拒绝的请求不占额度").isEqualTo("SCHEDULED");
  }

  @Test
  void 绕过服务层直接写库_更松的规则被db触发器拒掉() {
    seedDeliveredCase("SO-1", "PSO-1");
    var caseId = upstreamCases.keySet().iterator().next();
    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () -> {
                      sql.execute(
                          "insert into crm_review_request"
                              + "(id,tenant_id,request_no,upstream_case_id,shop_id,shop_name,"
                              + " platform_code,sales_order_no,buyer_ref,logistics_status,delivered_at,"
                              + " status,rule_delay_hours,rule_cooldown_hours,rule_max_per_buyer,"
                              + " rule_max_per_order,scheduled_at,created_at,updated_at)"
                              + " values (?,?,'RR-BYPASS',?,?,'店铺','AMAZON','SO-1','B-1','DELIVERED',"
                              + " ?::timestamptz,'SCHEDULED',48,96,99,1,"
                              + " ?::timestamptz,now(),now())",
                          UUID.randomUUID(),
                          tenant,
                          caseId,
                          shopId,
                          OffsetDateTime.parse(DELIVERED_AT),
                          OffsetDateTime.parse(DELIVERED_AT).plus(Duration.ofHours(48)));
                      return null;
                    }))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
        .hasMessageContaining("更松");
  }

  // ================================================================ 发送记录

  @Test
  void 平台侧索评接口未接入_不允许登记一条已发到平台的记录() throws Exception {
    seedDeliveredCase("SO-1", "PSO-1");
    var caseId = upstreamCases.keySet().iterator().next();
    var request = postJson("/crm/review-requests", createBody(caseId, "B-1", rule(48, 96, 3, 1)));
    String id = request.get("id").asText();

    String body =
        errorOf(
            "POST",
            "/crm/review-requests/" + id + "/send-records",
            Map.of("transport", "PLATFORM_API", "outcome", "SENT"));
    assertThat(body).contains("未接入");
  }

  @Test
  void 人工交接登记为已发出_索评请求随之变为已发出() throws Exception {
    seedDeliveredCase("SO-1", "PSO-1");
    var caseId = upstreamCases.keySet().iterator().next();
    var request = postJson("/crm/review-requests", createBody(caseId, "B-1", rule(48, 96, 3, 1)));
    String id = request.get("id").asText();

    var record =
        postJson(
            "/crm/review-requests/" + id + "/send-records",
            Map.of("transport", "MANUAL_HANDOFF", "outcome", "SENT", "note", "运营已手工发出"));
    assertThat(record.get("status").asText()).isEqualTo("SENT");
    assertThat(record.get("attemptedAt").asText()).isNotBlank();

    var detail =
        http.perform(
                get("/crm/review-requests/" + id).header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode node = json.readTree(detail.getResponse().getContentAsString());
    assertThat(node.get("request").get("status").asText()).isEqualTo("SENT");
    assertThat(node.get("sendRecords").size()).isEqualTo(1);
  }

  @Test
  void 登记失败必须写明原因() throws Exception {
    seedDeliveredCase("SO-1", "PSO-1");
    var caseId = upstreamCases.keySet().iterator().next();
    var request = postJson("/crm/review-requests", createBody(caseId, "B-1", rule(48, 96, 3, 1)));
    String id = request.get("id").asText();
    String body =
        errorOf(
            "POST",
            "/crm/review-requests/" + id + "/send-records",
            Map.of("transport", "MANUAL_HANDOFF", "outcome", "FAILED"));
    assertThat(body).contains("失败原因");
  }

  // ================================================================ 评价回收桶与统计

  @Test
  void 被频次拒绝的请求不可能有评价回收() throws Exception {
    seedDeliveredCase("SO-1", "PSO-1");
    var caseId = upstreamCases.keySet().iterator().next();
    postJson("/crm/review-requests", createBody(caseId, "B-1", rule(48, 96, 3, 1)));
    var rejected = postJson("/crm/review-requests", createBody(caseId, "B-2", rule(48, 96, 3, 1)));
    assertThat(rejected.get("status").asText()).isEqualTo("REJECTED");

    String body =
        errorOf(
            "POST",
            "/crm/review-requests/" + rejected.get("id").asText() + "/recoveries",
            Map.of("rating", 5, "content", "很好", "receivedAt", "2026-06-10T10:00:00+08:00"));
    assertThat(body).contains("拒绝");
  }

  @Test
  void 登记评价回收后请求变为已发出_统计算得出来() throws Exception {
    seedDeliveredCase("SO-1", "PSO-1");
    seedDeliveredCase("SO-2", "PSO-2");
    var ids = new ArrayList<>(upstreamCases.keySet());
    var first = postJson("/crm/review-requests", createBody(ids.get(0), "B-1", rule(48, 96, 3, 1)));
    var second = postJson("/crm/review-requests", createBody(ids.get(1), "B-2", rule(48, 96, 3, 1)));

    postJson(
        "/crm/review-requests/" + first.get("id").asText() + "/recoveries",
        Map.of(
            "rating", 5,
            "content", "物流很快",
            "hasImage", true,
            "receivedAt", "2026-06-10T10:00:00+08:00"));
    postJson(
        "/crm/review-requests/" + second.get("id").asText() + "/recoveries",
        Map.of(
            "rating", 3,
            "content", "一般",
            "receivedAt", "2026-06-11T10:00:00+08:00"));

    var stats =
        http.perform(
                get("/crm/review-requests/recovery-stats").header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode node = json.readTree(stats.getResponse().getContentAsString());
    assertThat(node.get("totalRequests").asLong()).isEqualTo(2);
    assertThat(node.get("sentCount").asLong()).isEqualTo(2);
    assertThat(node.get("recoveredRequests").asLong()).isEqualTo(2);
    assertThat(node.get("recoveryRecords").asLong()).isEqualTo(2);
    assertThat(node.get("averageRating").decimalValue()).isEqualByComparingTo("4.00");
    assertThat(node.get("recoveryRate").decimalValue()).isEqualByComparingTo("1.0000");
    // 星级分布：5 星 1 条、3 星 1 条。
    var distribution = node.get("ratingDistribution");
    assertThat(distribution.size()).isEqualTo(5);
    assertThat(distribution.get(0).get("rating").asInt()).isEqualTo(1);
    assertThat(distribution.get(0).get("count").asLong()).isZero();
    assertThat(distribution.get(2).get("rating").asInt()).isEqualTo(3);
    assertThat(distribution.get(2).get("count").asLong()).isEqualTo(1);
    assertThat(distribution.get(4).get("count").asLong()).isEqualTo(1);
    // 按店铺分组。
    assertThat(node.get("byShop").size()).isEqualTo(1);
    assertThat(node.get("byShop").get(0).get("requests").asLong()).isEqualTo(2);
  }

  @Test
  void 没有已发出的索评时回收率报空而不是0() throws Exception {
    var stats =
        http.perform(
                get("/crm/review-requests/recovery-stats").header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode node = json.readTree(stats.getResponse().getContentAsString());
    assertThat(node.get("sentCount").asLong()).isZero();
    assertThat(node.get("recoveryRate").isNull()).as("分母为 0 时回收率必须报空").isTrue();
    assertThat(node.get("note").asText()).contains("不计算");
  }

  // ================================================================ 批量执行

  @Test
  void 批量执行逐条给出结论_缺买家标识的跳过并写明原因() throws Exception {
    seedDeliveredCase("SO-1", "PSO-1");
    seedDeliveredCase("SO-2", "PSO-2");
    seedDeliveredCase("SO-3", "PSO-3");
    var ids = new ArrayList<>(upstreamCases.keySet());
    var byOrder = new java.util.HashMap<String, UUID>();
    upstreamCases.forEach((id, c) -> byOrder.put(c.salesOrderNo(), id));

    var result =
        postJson(
            "/crm/review-requests/batch",
            Map.of(
                "shopId", shopId.toString(),
                "buyers",
                List.of(
                    Map.of("salesOrderNo", "SO-1", "buyerRef", "B-1"),
                    Map.of("salesOrderNo", "SO-2", "buyerRef", "B-2")),
                "rule", rule(48, 96, 3, 1)));
    assertThat(result.get("candidateCount").asInt()).isEqualTo(3);
    assertThat(result.get("createdCount").asInt()).isEqualTo(2);
    assertThat(result.get("skippedCount").asInt()).isEqualTo(1);

    String skippedReason = null;
    for (JsonNode item : result.get("items")) {
      if ("SKIPPED".equals(item.get("outcome").asText())) {
        skippedReason = item.get("reason").asText();
      }
    }
    assertThat(skippedReason).as("跳过的候选必须写明原因").isNotNull();
    assertThat(skippedReason).contains("买家标识");
  }

  @Test
  void 批量执行里同一买家的第二单被频次规则挡住() throws Exception {
    seedDeliveredCase("SO-1", "PSO-1");
    seedDeliveredCase("SO-2", "PSO-2");
    var result =
        postJson(
            "/crm/review-requests/batch",
            Map.of(
                "shopId", shopId.toString(),
                "buyers",
                List.of(
                    Map.of("salesOrderNo", "SO-1", "buyerRef", "B-1"),
                    Map.of("salesOrderNo", "SO-2", "buyerRef", "B-1")),
                "rule", rule(48, 96, 3, 1)));
    assertThat(result.get("createdCount").asInt()).isEqualTo(1);
    assertThat(result.get("rejectedCount").asInt()).isEqualTo(1);
    for (JsonNode item : result.get("items")) {
      if ("REJECTED".equals(item.get("outcome").asText())) {
        assertThat(item.get("reason").asText()).contains("冷却窗口");
      }
    }
  }

  @Test
  void 批量执行按签收时间窗过滤() throws Exception {
    seedDeliveredCase("SO-1", "PSO-1");
    var result =
        postJson(
            "/crm/review-requests/batch",
            Map.of(
                "shopId", shopId.toString(),
                "buyers", List.of(Map.of("salesOrderNo", "SO-1", "buyerRef", "B-1")),
                "deliveredFrom", "2026-07-01T00:00:00+08:00",
                "deliveredTo", "2026-08-01T00:00:00+08:00",
                "rule", rule(48, 96, 3, 1)));
    assertThat(result.get("candidateCount").asInt()).as("时间窗外的候选不进批次").isZero();
    assertThat(result.get("createdCount").asInt()).isZero();
  }

  // ================================================================ 列表与详情

  @Test
  void 列表按状态与店铺筛选_拒绝留痕也查得到() throws Exception {
    seedDeliveredCase("SO-1", "PSO-1");
    var caseId = upstreamCases.keySet().iterator().next();
    postJson("/crm/review-requests", createBody(caseId, "B-1", rule(48, 96, 3, 1)));
    postJson("/crm/review-requests", createBody(caseId, "B-2", rule(48, 96, 3, 1)));

    var rejected =
        http.perform(
                get("/crm/review-requests")
                    .param("status", "REJECTED")
                    .header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(rejected.getResponse().getContentAsString()).size()).isEqualTo(1);

    var all =
        http.perform(
                get("/crm/review-requests").param("shopId", shopId.toString())
                    .header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(all.getResponse().getContentAsString()).size()).isEqualTo(2);
  }
}
