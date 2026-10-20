package com.starcon.trade;

import static com.starcon.trade.PlatformOrderSyncModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.jooq.Record;
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
 * 9.1.2.1 平台订单同步的<b>多租户三断言</b>。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条，且<b>实跑在真实业务表</b>上
 * （platform_order_sync_batch / platform_order_sync_event / platform_order /
 * platform_order_component / platform_order_enum_mapping / platform_order_sync_exception），
 * 不是占位探针表 —— 探针表能证明 RLS 开着，证明不了<b>同步查询本身</b>没漏租户谓词。
 *
 * <p>本模块尤其不能省隔离：平台订单同步写的是<b>共享的统一订单模型</b>
 * （sales_order 簇），两个租户的平台单落在同一张表里 —— 漏掉租户谓词的后果不是
 * 「少看见几行」，而是<b>把甲租户的买家地址与金额读给乙租户</b>。
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlatformOrderSyncTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> allPermissions =
      java.util.stream.Stream.of("orders")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "trade." + p + "." + op))
          .toList();

  static AccessService.Access actor(UUID uid, UUID tenant, List<String> permissions) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        null,
        permissions,
        List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions)));
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，三条断言全部变成空断言。
        s.execute("create role starcon_app nologin");
        s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
      }
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
      actors.put("Bearer tenant-a", actor(user, a, allPermissions));
      actors.put("Bearer tenant-b", actor(user, b, allPermissions));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "trade_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("CHANNEL_URL", () -> "http://127.0.0.1:1");
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  @Autowired PlatformOrderSyncService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  AccessService.Access actorA;
  AccessService.Access actorB;

  static final String PLATFORM = "AMAZON";
  static final String SHOP = "SHOP-A";

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate platform_order_sync_exception, platform_order_component, platform_order,"
              + " platform_order_sync_event, platform_order_sync_batch, platform_order_enum_mapping,"
              + " order_charge, order_address_snapshot, sales_order_party_snapshot,"
              + " sales_order_line, order_line_attribute_snapshot, sales_order_status_history,"
              + " sales_order cascade");
    }
  }

  // ================================================================ 工具

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "trade.orders.read", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "trade.orders.read", work);
  }

  void registerMapping(String token, String platform, String external, String internal)
      throws Exception {
    http.perform(
            post("/trade/platform-orders/mappings")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsBytes(
                        Map.of(
                            "platformCode", platform,
                            "enumKind", "ORDER_STATUS",
                            "externalValue", external,
                            "internalValue", internal))))
        .andExpect(status().is2xxSuccessful());
  }

  Map<String, Object> address() {
    Map<String, Object> a = new LinkedHashMap<>();
    a.put("recipientName", "Jane Doe");
    a.put("recipientPhone", "+1-555-0100");
    a.put("recipientEmail", "jane@acme.example");
    a.put("companyName", "Acme Trading Co.");
    a.put("countryCode", "US");
    a.put("province", "CA");
    a.put("city", "Los Angeles");
    a.put("district", "");
    a.put("postcode", "90001");
    a.put("addressLine1", "100 Main St");
    a.put("addressLine2", "");
    a.put("taxId", "");
    a.put("note", "");
    return a;
  }

  Map<String, Object> line() {
    Map<String, Object> l = new LinkedHashMap<>();
    l.put("skuId", UUID.randomUUID().toString());
    l.put("skuCode", "SKU-1");
    l.put("skuName", "商品 1");
    l.put("specSnapshot", "蓝色 / M");
    l.put("skuMappingVersion", "map-v3");
    l.put("quantity", "1");
    l.put("unitPrice", "10.00");
    l.put("discountAmount", "0");
    l.put("taxAmount", "0");
    l.put("note", "");
    l.put("attributes", List.of());
    l.put("components", List.of());
    return l;
  }

  Map<String, Object> event(String externalOrderNo) {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("eventType", "ORDER_CREATED");
    e.put("externalOrderNo", externalOrderNo);
    e.put("platformStatus", "Shipped");
    e.put("platformEventVersion", 1L);
    e.put("currency", "USD");
    e.put("paymentMethod", "PREPAID");
    e.put("buyerId", "BUYER-1");
    e.put("buyerName", "Acme Trading Co.");
    e.put("buyerType", "ORGANIZATION");
    e.put("platformGrandTotalAmount", "10.00");
    e.put("shippingAddress", address());
    e.put("lines", List.of(line()));
    return e;
  }

  Map<String, Object> webhookBody(Map<String, Object> event) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("platformCode", PLATFORM);
    body.put("shopCode", SHOP);
    body.put("events", List.of(event));
    return body;
  }

  void postWebhook(Map<String, Object> body, String token) throws Exception {
    http.perform(
            post("/trade/platform-orders/webhook")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body)))
        .andExpect(status().is2xxSuccessful());
  }

  PlatformOrderSyncOrderQuery query() {
    return new PlatformOrderSyncOrderQuery(null, null, null, null, 0, 50);
  }

  // ==========================================================================
  // 断言 1
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() throws Exception {
    registerMapping("Bearer tenant-a", PLATFORM, "Shipped", "CONFIRMED");
    registerMapping("Bearer tenant-b", PLATFORM, "Shipped", "CONFIRMED");
    String externalA = "AMZ-A-" + UUID.randomUUID().toString().substring(0, 6);
    String externalB = "AMZ-B-" + UUID.randomUUID().toString().substring(0, 6);
    postWebhook(webhookBody(event(externalA)), "Bearer tenant-a");
    postWebhook(webhookBody(event(externalB)), "Bearer tenant-b");

    assertThat(asA(() -> service.pageOrders(query())).total()).as("A 只看得到自己那一份").isEqualTo(1);
    assertThat(asB(() -> service.pageOrders(query())).total()).as("B 只看得到自己那一份").isEqualTo(1);

    // 更强的一格：按主键直查对方的单也拿不到（不只是列表过滤）。
    UUID orderA =
        asA(
            () ->
                sql.fetchOne(
                        "select id from platform_order where external_order_no = ?", externalA)
                    .get(0, UUID.class));
    assertThat(
            asB(
                () ->
                    sql.fetch("select id from platform_order where id = ?::uuid", orderA.toString())
                        .size()))
        .as("按主键直查也必须被 RLS 挡住")
        .isZero();

    // 详情端点同样跨租户不可见（RLS 下查不到 → 404，不是空详情）。
    var error =
        org.assertj.core.api.Assertions.catchThrowable(() -> asB(() -> service.order(orderA)));
    assertThat(error).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(((org.springframework.web.server.ResponseStatusException) error).getStatusCode().value())
        .isEqualTo(404);

    // 批次与异常明细同样隔离：B 看不到 A 的同步批次。
    assertThat(asA(() -> service.pageBatches(new PlatformOrderSyncBatchQuery(null, null, null, null, 0, 50))).total())
        .isEqualTo(1);
    assertThat(asB(() -> service.pageBatches(new PlatformOrderSyncBatchQuery(null, null, null, null, 0, 50))).total())
        .isEqualTo(1);
    assertThat(
            asB(
                () ->
                    sql.fetch("select id from platform_order_sync_batch where platform_code = ?", PLATFORM)
                        .size()))
        .as("按主键之外的查询同样受 RLS 约束")
        .isEqualTo(1);
  }

  // ==========================================================================
  // 断言 2
  // ==========================================================================
  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    registerMapping("Bearer tenant-a", PLATFORM, "Shipped", "CONFIRMED");
    String external = "AMZ-HDR-" + UUID.randomUUID().toString().substring(0, 6);
    postWebhook(webhookBody(event(external)), "Bearer tenant-a");

    // HTTP 边界：带合法会话 + 伪造 X-Tenant-Id → 拿到的仍然是**会话租户**的数据，
    // 而且行确实落在会话租户 a 名下（不是只断言「B 看不见」）。
    var body =
        http.perform(
                get("/trade/platform-orders/orders")
                    .header("Authorization", "Bearer tenant-a")
                    .header("X-Tenant-Id", b))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var parsed = json.readTree(body);
    assertThat(parsed.get("total").asLong()).isEqualTo(1);
    assertThat(parsed.get("rows").get(0).get("externalOrderNo").asText()).isEqualTo(external);

    // 行确实落在 a 名下：用 B 的事务按 B 的租户谓词查，必须查不到。
    assertThat(
            asB(
                () ->
                    sql.fetch(
                            "select id from platform_order where external_order_no = ?", external)
                        .size()))
        .as("伪造请求头不得把行带进 B 的租户")
        .isZero();

    // 伪造 X-Tenant-Id 也不能替 B 开通任何入口：B 读自己的订单仍然是空的。
    http.perform(get("/trade/platform-orders/orders").header("Authorization", "Bearer tenant-b"))
        .andExpect(status().isOk());
    assertThat(asB(() -> service.pageOrders(query())).total()).isZero();

    // 没有会话就没有租户上下文。
    http.perform(get("/trade/platform-orders/orders")).andExpect(status().isUnauthorized());
  }

  // ==========================================================================
  // 断言 3
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    registerMapping("Bearer tenant-a", PLATFORM, "Shipped", "CONFIRMED");
    registerMapping("Bearer tenant-b", PLATFORM, "Shipped", "CONFIRMED");
    postWebhook(
        webhookBody(event("AMZ-PA-" + UUID.randomUUID().toString().substring(0, 6))),
        "Bearer tenant-a");
    postWebhook(
        webhookBody(event("AMZ-PB-" + UUID.randomUUID().toString().substring(0, 6))),
        "Bearer tenant-b");

    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证后面几轮读到的仍然是本租户而不是上一轮残留。
    for (int i = 0; i < 5; i++) {
      assertThat(asA(() -> service.pageOrders(query())).total()).as("第 %d 轮租户 A", i).isEqualTo(1);
      assertThat(asB(() -> service.pageOrders(query())).total()).as("第 %d 轮租户 B", i).isEqualTo(1);
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(asA(() -> service.pageOrders(query())).total()).isEqualTo(1);
    assertThat(asB(() -> service.pageOrders(query())).total()).isEqualTo(1);
  }
}
