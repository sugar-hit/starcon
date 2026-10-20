package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * 6.2.1.1 缺货与断货风险（trade 侧）。
 *
 * <p><b>本测试是 V234 迁移那些 DB 级不变量的独立复验</b>：不采信任何外部结论，逐条在真实
 * PostgreSQL 16 上跑一遍，并且走 HTTP 端点而不是直连数据库，以便同时验证「服务层 + DB 层」
 * 两层都没有把闸门漏掉。
 *
 * <p><b>warehouse 与 channel 都起本地 stub 服务</b>（而不是在测试里 new 一个假对象）：
 * 可用量、在途量、店铺映射都必须来自上游的**授权只读接口** —— 本模块跨服务一律走端口，
 * 禁跨库 SQL / FK / 视图。stub 只负责按上游契约形状返回数，数字本身是按各模块自己的算式
 * 真算出来的，不是写死的数组。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StockoutRiskIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity, warehouse, channel;
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();

  /** 评估基准日：写死是为了让「哪天断货」这个断言不随跑测试的日期漂移。 */
  static final LocalDate AS_OF = LocalDate.of(2026, 1, 15);

  /** 备货周期（天）。 */
  static final int LEAD_TIME = 30;

  static final List<String> allPermissions =
      List.of("trade.stockout.read", "trade.stockout.update");

  // ---------------------------------------------------------------- 上游 stub 数据

  /** 租户 → 该租户 warehouse 的可用量行。 */
  static final Map<UUID, List<Map<String, Object>>> stockRows = new ConcurrentHashMap<>();

  /** 租户 → 该租户 warehouse 的在途腿。 */
  static final Map<UUID, List<Map<String, Object>>> transitRows = new ConcurrentHashMap<>();

  /** 租户 → SKU id → 店铺编码列表（channel 的映射）。 */
  static final Map<UUID, Map<String, List<String>>> shopRows = new ConcurrentHashMap<>();

  /** 置 true 时 warehouse stub 返回 500（模拟上游故障）。 */
  static final java.util.concurrent.atomic.AtomicBoolean warehouseDown =
      new java.util.concurrent.atomic.AtomicBoolean(false);

  static UUID skuFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-stockout-test-sku-" + tenant + "-" + ordinal).getBytes());
  }

  static AccessService.Access actor(
      UUID uid, UUID tenant, UUID dept, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        dept,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static void write(HttpServer server, com.sun.net.httpserver.HttpExchange e, int code, Object body)
      throws java.io.IOException {
    byte[] bytes = json.writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(code, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }

  /** 从 Authorization 头解析出租户（stub 只按已认证身份给数，绝不认 X-Tenant-Id）。 */
  static UUID tenantOf(String bearer) {
    var access = actors.get(bearer);
    return access == null ? null : access.tenantId();
  }

  static Map<String, String> queryOf(com.sun.net.httpserver.HttpExchange e) {
    Map<String, String> params = new LinkedHashMap<>();
    String raw = e.getRequestURI().getRawQuery();
    if (raw == null || raw.isBlank()) {
      return params;
    }
    for (String pair : raw.split("&")) {
      int eq = pair.indexOf('=');
      String key = eq < 0 ? pair : pair.substring(0, eq);
      String value = eq < 0 ? "" : pair.substring(eq + 1);
      params.put(
          URLDecoder.decode(key, StandardCharsets.UTF_8),
          URLDecoder.decode(value, StandardCharsets.UTF_8));
    }
    return params;
  }

  /** 按 page / size 对一份列表分页（stub 的真分页，不是假分页）。 */
  static <T> Map<String, Object> paged(List<T> rows, Map<String, String> params) {
    return paged("rows", rows, params);
  }

  /** 同上，但行数组的字段名可指定（channel 的分页行数组叫 {@code items}）。 */
  static <T> Map<String, Object> paged(String key, List<T> rows, Map<String, String> params) {
    int page = Integer.parseInt(params.getOrDefault("page", "0"));
    int size = Integer.parseInt(params.getOrDefault("size", "50"));
    int from = Math.min(page * size, rows.size());
    int to = Math.min(from + size, rows.size());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put(key, rows.subList(from, to));
    body.put("total", rows.size());
    body.put("page", page);
    body.put("size", size);
    return body;
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
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
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer read-only",
          actor(user, a, null, List.of(grant("ALL", List.of("trade.stockout.read")))));
      identity.start();

      // ---- warehouse stub：7.1.1.3 可用量 + 7.5.1.2 在途腿
      warehouse = HttpServer.create(new InetSocketAddress(0), 0);
      warehouse.createContext(
          "/warehouse/inventory/sellable/skus",
          e -> {
            if (warehouseDown.get()) {
              write(warehouse, e, 500, Map.of("message", "仓库服务内部错误"));
              return;
            }
            UUID tenant = tenantOf(e.getRequestHeaders().getFirst("Authorization"));
            if (tenant == null) {
              write(warehouse, e, 401, Map.of("message", "未认证"));
              return;
            }
            write(warehouse, e, 200, paged(stockRows.getOrDefault(tenant, List.of()), queryOf(e)));
          });
      warehouse.createContext(
          "/warehouse/inventory/in-transit",
          e -> {
            if (warehouseDown.get()) {
              write(warehouse, e, 500, Map.of("message", "仓库服务内部错误"));
              return;
            }
            UUID tenant = tenantOf(e.getRequestHeaders().getFirst("Authorization"));
            if (tenant == null) {
              write(warehouse, e, 401, Map.of("message", "未认证"));
              return;
            }
            var params = queryOf(e);
            List<Map<String, Object>> rows =
                new ArrayList<>(transitRows.getOrDefault(tenant, List.of()));
            String kinds = params.get("transitKind");
            if (kinds != null && !kinds.isBlank()) {
              var allowed = List.of(kinds.split(","));
              rows.removeIf(r -> !allowed.contains(String.valueOf(r.get("transitKind"))));
            }
            write(warehouse, e, 200, paged(rows, params));
          });
      warehouse.start();

      // ---- channel stub：4.1.1.1 映射（内部 SKU → 店铺）
      channel = HttpServer.create(new InetSocketAddress(0), 0);
      channel.createContext(
          "/channel/listing-mappings",
          e -> {
            UUID tenant = tenantOf(e.getRequestHeaders().getFirst("Authorization"));
            if (tenant == null) {
              write(channel, e, 401, Map.of("message", "未认证"));
              return;
            }
            var params = queryOf(e);
            String skuId = params.get("internalSkuId");
            var shops = shopRows.getOrDefault(tenant, Map.of()).getOrDefault(skuId, List.of());
            List<Map<String, Object>> items = new ArrayList<>();
            int ordinal = 0;
            for (String shop : shops) {
              ordinal++;
              items.add(
                  Map.of(
                      "id", UUID.randomUUID().toString(),
                      "shopCode", shop,
                      "internalSkuId", skuId,
                      "relationType", "ONE_TO_ONE",
                      "status", "ACTIVE",
                      "mappingStatus", "MAPPED"));
            }
            write(channel, e, 200, paged("items", items, params));
          });
      channel.start();
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
    r.add("WAREHOUSE_URL", () -> "http://localhost:" + warehouse.getAddress().getPort());
    r.add("CHANNEL_URL", () -> "http://localhost:" + channel.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    warehouse.stop(0);
    channel.stop(0);
    pg.stop();
  }

  @Autowired StockoutRiskService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ================================================================ 数据准备

  /** 一行 warehouse 可用量。 */
  static Map<String, Object> stock(UUID skuId, String code, String onHand, String reserved, String sellable) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("skuId", skuId.toString());
    row.put("skuCode", code);
    row.put("skuName", code + " 名称");
    row.put("onHand", new BigDecimal(onHand));
    row.put("reserved", new BigDecimal(reserved));
    row.put("available", new BigDecimal(onHand).subtract(new BigDecimal(reserved)));
    row.put("sellableBeforeBuffer", new BigDecimal(onHand).subtract(new BigDecimal(reserved)));
    row.put("safetyBuffer", BigDecimal.ZERO);
    row.put("sellable", new BigDecimal(sellable));
    return row;
  }

  /** 一条在途腿。{@code eta} 为 null 表示没有预计到达日。 */
  static Map<String, Object> leg(
      UUID skuId, String code, String kind, String quantity, String eta, String reference) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", UUID.randomUUID().toString());
    row.put("transitKind", kind);
    row.put("skuId", skuId.toString());
    row.put("skuCode", code);
    row.put("outstandingQuantity", new BigDecimal(quantity));
    row.put("expectedArriveAt", eta == null ? null : eta);
    row.put("sourceSystem", "warehouse");
    row.put("sourceReference", reference);
    return row;
  }

  @BeforeEach
  void seed() throws Exception {
    warehouseDown.set(false);
    stockRows.clear();
    transitRows.clear();
    shopRows.clear();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate stockout_risk_item_deduction, stockout_risk_item_shop, stockout_risk_item,"
              + " stockout_risk_snapshot, stockout_sales_velocity cascade");
    }
    for (UUID tenant : List.of(a, b)) {
      UUID healthy = skuFor(tenant, 1);
      UUID critical = skuFor(tenant, 2);
      UUID noVelocity = skuFor(tenant, 3);
      UUID covered = skuFor(tenant, 4);
      UUID uncovered = skuFor(tenant, 5);
      // 库存充足
      stockRows.put(
          tenant,
          List.of(
              stock(healthy, "SKU-" + shortCode(tenant) + "-01", "1000", "0", "1000"),
              // 只有 5 件、日均 10 件 —— 今天就断货
              stock(critical, "SKU-" + shortCode(tenant) + "-02", "5", "0", "5"),
              // 有库存但没登记销售速度 —— 应当是「无数据」而不是 0
              stock(noVelocity, "SKU-" + shortCode(tenant) + "-03", "50", "0", "50"),
              // 同样 100 件、日均 10 件，但有在途顶得上
              stock(covered, "SKU-" + shortCode(tenant) + "-04", "100", "0", "100"),
              // 同样 100 件、日均 10 件，没有在途
              stock(uncovered, "SKU-" + shortCode(tenant) + "-05", "100", "0", "100")));
      transitRows.put(
          tenant,
          List.of(
              // 备货周期内到达（评估日 2026-01-15 + 30 天 = 2026-02-14）
              leg(covered, "SKU-" + shortCode(tenant) + "-04", "FIRST_MILE", "250",
                  "2026-02-01T00:00:00Z", "FM-001"),
              // 备货周期之后才到 —— 不计入缺口
              leg(covered, "SKU-" + shortCode(tenant) + "-04", "PURCHASE", "999",
                  "2026-05-01T00:00:00Z", "PO-001"),
              // 没有 ETA —— 救不了确定的那一天
              leg(uncovered, "SKU-" + shortCode(tenant) + "-05", "TRANSFER", "80", null, "TR-001")));
      Map<String, List<String>> shops = new LinkedHashMap<>();
      shops.put(critical.toString(), List.of("US-AMAZON", "US-SHOPIFY"));
      shops.put(covered.toString(), List.of("US-AMAZON"));
      shopRows.put(tenant, shops);
    }
  }

  static String shortCode(UUID tenant) {
    return tenant.equals(a) ? "A" : "B";
  }

  // ================================================================ 工具

  String body(String json) {
    return json;
  }

  /** 登记一条销售速度（走 HTTP，验证真实写入路径）。 */
  void registerVelocity(
      String bearer, UUID skuId, String code, String daily, LocalDate effectiveFrom) throws Exception {
    http.perform(
            post("/trade/stockout-risk/velocities")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "skuId", skuId.toString(),
                            "skuCode", code,
                            "skuName", code + " 名称",
                            "dailySalesQuantity", new BigDecimal(daily),
                            "windowStart", "2025-12-01",
                            "windowEnd", "2025-12-31",
                            "effectiveFrom", effectiveFrom.toString(),
                            "sourceKind", "MANUAL",
                            "sourceRef", "手工登记",
                            "note", ""))))
        .andExpect(status().isOk());
  }

  /** 发起一次计算（走 HTTP）。 */
  String compute(String bearer) throws Exception {
    var result =
        http.perform(
                post("/trade/stockout-risk/snapshots")
                    .header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of("asOfDate", AS_OF.toString(), "leadTimeDays", LEAD_TIME))))
            .andReturn();
    var response = result.getResponse();
    if (response.getStatus() != 200) {
      var ex = result.getResolvedException();
      throw new AssertionError(
          "计算失败："
              + response.getStatus()
              + " "
              + (ex == null ? response.getContentAsString() : ex.toString()),
          ex);
    }
    return response.getContentAsString();
  }

  /** 清单（走 HTTP）。 */
  String items(String bearer) throws Exception {
    return http.perform(get("/trade/stockout-risk/items").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> rowsOf(String payload, String key) throws Exception {
    Map<String, Object> parsed = json.readValue(payload, Map.class);
    return (List<Map<String, Object>>) parsed.get(key);
  }

  Map<String, Object> rowByCode(List<Map<String, Object>> rows, String code) {
    return rows.stream()
        .filter(r -> code.equals(r.get("skuCode")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("清单里没有 SKU " + code + "：" + rows));
  }

  /** 异常链最底层的 SQL 异常（DB 触发器的拒绝理由就在那里）。 */
  static java.sql.SQLException rootSqlException(Throwable t) {
    var cur = t;
    while (cur.getCause() != null && cur.getCause() != cur) {
      cur = cur.getCause();
    }
    return cur instanceof java.sql.SQLException sql ? sql : null;
  }

  /** 异常链上的全部消息拼起来（DB 触发器的拒绝理由可能在中间某一层）。 */
  static String allMessages(Throwable t) {
    StringBuilder sb = new StringBuilder();
    for (var cur = t; cur != null && cur.getCause() != cur; cur = cur.getCause()) {
      if (cur.getMessage() != null) {
        sb.append(cur.getMessage()).append('\n');
      }
    }
    return sb.toString();
  }

  // ================================================================ 销售速度登记

  @Test
  void 销售速度登记能写入能读回且带生效期() throws Exception {
    UUID sku = skuFor(a, 1);
    registerVelocity("Bearer tenant-a", sku, "SKU-A-01", "10", LocalDate.of(2026, 1, 1));

    http.perform(get("/trade/stockout-risk/velocities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[0].skuCode").value("SKU-A-01"))
        .andExpect(jsonPath("$.rows[0].dailySalesQuantity").value(10))
        .andExpect(jsonPath("$.rows[0].effectiveFrom").value("2026-01-01"))
        .andExpect(jsonPath("$.rows[0].effectiveTo").doesNotExist())
        .andExpect(jsonPath("$.rows[0].effective").value(true))
        .andExpect(jsonPath("$.rows[0].sourceKindLabel").value("人工登记"));
  }

  @Test
  void 新登记收口旧登记而不是覆盖它() throws Exception {
    UUID sku = skuFor(a, 1);
    registerVelocity("Bearer tenant-a", sku, "SKU-A-01", "10", LocalDate.of(2026, 1, 1));
    registerVelocity("Bearer tenant-a", sku, "SKU-A-01", "20", LocalDate.of(2026, 2, 1));

    // 两条都在（旧的那条被收口），历史可追溯
    http.perform(get("/trade/stockout-risk/velocities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2));

    var rows =
        access.run(
            actors.get("Bearer tenant-a"),
            "trade.stockout.read",
            () ->
                sql.fetch(
                    "select daily_sales_quantity, effective_from, effective_to"
                        + " from stockout_sales_velocity order by effective_from"));
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).get("daily_sales_quantity", BigDecimal.class))
        .isEqualByComparingTo("10");
    assertThat(rows.get(0).get("effective_to", LocalDate.class))
        .as("旧登记必须被收口到新登记的生效起点，而不是被覆盖")
        .isEqualTo(LocalDate.of(2026, 2, 1));
    assertThat(rows.get(1).get("daily_sales_quantity", BigDecimal.class))
        .isEqualByComparingTo("20");
  }

  @Test
  void 销售速度登记的统计口径不可改() throws Exception {
    UUID sku = skuFor(a, 1);
    AccessService.Access actorA = actors.get("Bearer tenant-a");
    access.run(
        actorA,
        "trade.stockout.update",
        () -> {
          sql.execute(
              "insert into stockout_sales_velocity(id,tenant_id,sku_id,sku_code,sku_name,"
                  + " daily_sales_quantity,window_start,window_end,effective_from,source_kind,"
                  + " created_by,created_at)"
                  + " values (?::uuid,?::uuid,?::uuid,?,?,?::numeric,?::date,?::date,?::date,?::varchar,"
                  + " ?::uuid,?::timestamptz)",
              UUID.randomUUID(),
              a,
              sku,
              "SKU-A-01",
              "",
              new BigDecimal("10"),
              LocalDate.of(2025, 12, 1),
              LocalDate.of(2025, 12, 31),
              LocalDate.of(2026, 1, 1),
              "MANUAL",
              user,
              java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
          return null;
        });

    // 改统计口径必须被 DB 触发器挡下
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "trade.stockout.update",
                    () -> {
                      sql.execute(
                          "update stockout_sales_velocity set daily_sales_quantity = 99::numeric"
                              + " where tenant_id = ?::uuid and sku_id = ?::uuid",
                          a,
                          sku);
                      return null;
                    }))
        .as("改日均销量等于追溯改写「当时按哪个数算的断货日」")
        .satisfies(
            e -> {
              assertThat(allMessages(e)).contains("不可改");
              var sqlError = rootSqlException(e);
              assertThat((Object) sqlError)
                  .as("必须是 DB 层拒绝（23514），不是服务层挡的")
                  .isNotNull();
              assertThat(sqlError.getSQLState()).isEqualTo("23514");
            });

    // 删掉登记同样不行：应用角色根本没有 DELETE 权限（V234 里 REVOKE 过）。
    assertThat(
            sql.fetchOne(
                    "select has_table_privilege('trade_app','stockout_sales_velocity','DELETE')")
                .get(0, Boolean.class))
        .as("应用角色不得有 DELETE 权限 —— append-only 必须是第一道闸")
        .isFalse();
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "trade.stockout.update",
                    () -> {
                      sql.execute(
                          "delete from stockout_sales_velocity where tenant_id = ?::uuid and sku_id = ?::uuid",
                          a,
                          sku);
                      return null;
                    }))
        .satisfies(e -> assertThat(allMessages(e)).contains("permission denied"));

    // 第二道闸：即使绕过权限（用表 owner 直连 SQL），触发器也必须挡住删除。
    try (var c =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.execute(
                      "delete from stockout_sales_velocity where tenant_id = '"
                          + a
                          + "' and sku_id = '"
                          + sku
                          + "'"))
          .satisfies(e -> assertThat(allMessages(e)).contains("append-only"));
    }
  }

  @Test
  void 同一sku同一生效日不能登记两次() {
    UUID sku = skuFor(a, 1);
    AccessService.Access actorA = actors.get("Bearer tenant-a");
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "trade.stockout.update",
                    () -> {
                      for (int i = 0; i < 2; i++) {
                        sql.execute(
                            "insert into stockout_sales_velocity(id,tenant_id,sku_id,sku_code,"
                                + " daily_sales_quantity,window_start,window_end,effective_from,"
                                + " source_kind,created_by,created_at)"
                                + " values (?::uuid,?::uuid,?::uuid,?,?::numeric,?::date,?::date,?::date,"
                                + " ?::varchar,?::uuid,?::timestamptz)",
                            UUID.randomUUID(),
                            a,
                            sku,
                            "SKU-A-01",
                            new BigDecimal("10"),
                            LocalDate.of(2025, 12, 1),
                            LocalDate.of(2025, 12, 31),
                            LocalDate.of(2026, 1, 1),
                            "MANUAL",
                            user,
                            java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
                      }
                      return null;
                    }))
        .hasMessageContaining("duplicate key");
  }

  // ================================================================ 风险清单

  @Test
  void 清单输出蓝图要求的七项() throws Exception {
    UUID healthy = skuFor(a, 1);
    UUID critical = skuFor(a, 2);
    UUID covered = skuFor(a, 4);
    registerVelocity("Bearer tenant-a", healthy, "SKU-A-01", "10", LocalDate.of(2026, 1, 1));
    registerVelocity("Bearer tenant-a", critical, "SKU-A-02", "10", LocalDate.of(2026, 1, 1));
    registerVelocity("Bearer tenant-a", covered, "SKU-A-04", "10", LocalDate.of(2026, 1, 1));
    compute("Bearer tenant-a");

    var rows = rowsOf(items("Bearer tenant-a"), "rows");
    Map<String, Object> criticalRow = rowByCode(rows, "SKU-A-02");

    // ① SKU
    assertThat(criticalRow.get("skuCode")).isEqualTo("SKU-A-02");
    // ② 可用量
    assertThat(new BigDecimal(criticalRow.get("availableQuantity").toString()))
        .isEqualByComparingTo("5");
    // ③ 日均销
    assertThat(new BigDecimal(criticalRow.get("dailySalesQuantity").toString()))
        .isEqualByComparingTo("10");
    // ④ 可支撑天数（整数天）
    assertThat(criticalRow.get("coverageDays")).isEqualTo(0);
    // ⑤ 预计断货日
    assertThat(criticalRow.get("expectedStockoutDate")).isEqualTo(AS_OF.toString());
    // ⑥ 缺口量 = 10 × 30 − 5 − 0 = 295
    assertThat(new BigDecimal(criticalRow.get("shortageQuantity").toString()))
        .isEqualByComparingTo("295");
    // ⑦ 涉及店铺
    assertThat(criticalRow.get("shopCodes")).isEqualTo(List.of("US-AMAZON", "US-SHOPIFY"));
    assertThat(criticalRow.get("shopCount")).isEqualTo(2);
  }

  @Test
  void 没有销售速度时给无数据而不是零() throws Exception {
    // SKU-A-03 有库存但没有销售速度登记
    compute("Bearer tenant-a");
    var rows = rowsOf(items("Bearer tenant-a"), "rows");
    Map<String, Object> row = rowByCode(rows, "SKU-A-03");
    assertThat(row.get("dailySalesQuantity")).as("没有销售速度时必须是 null，不是 0").isNull();
    assertThat(row.get("coverageDays")).as("算不出可支撑天数就不能给 0").isNull();
    assertThat(row.get("expectedStockoutDate")).isNull();
    assertThat(row.get("urgency")).isEqualTo("NO_DATA");
    assertThat(row.get("urgencyBasis").toString()).contains("未登记销售速度");
  }

  @Test
  void 可支撑天数是整数天且向下取整() throws Exception {
    // SKU-A-01：可售 1000、日均 10 → 100 天
    registerVelocity("Bearer tenant-a", skuFor(a, 1), "SKU-A-01", "10", LocalDate.of(2026, 1, 1));
    compute("Bearer tenant-a");
    var rows = rowsOf(items("Bearer tenant-a"), "rows");
    Map<String, Object> row = rowByCode(rows, "SKU-A-01");
    assertThat(row.get("coverageDays")).isEqualTo(100);
    assertThat(row.get("expectedStockoutDate")).isEqualTo(AS_OF.plusDays(100).toString());
    // 库存充足 + 无缺口 → LOW
    assertThat(row.get("urgency")).isEqualTo("LOW");
  }

  @Test
  void 缺口量扣掉备货周期内能到的在途() throws Exception {
    registerVelocity("Bearer tenant-a", skuFor(a, 4), "SKU-A-04", "10", LocalDate.of(2026, 1, 1));
    registerVelocity("Bearer tenant-a", skuFor(a, 5), "SKU-A-05", "10", LocalDate.of(2026, 1, 1));
    compute("Bearer tenant-a");

    var rows = rowsOf(items("Bearer tenant-a"), "rows");
    Map<String, Object> covered = rowByCode(rows, "SKU-A-04");
    Map<String, Object> uncovered = rowByCode(rows, "SKU-A-05");

    // SKU-A-04：消耗 300 − 可售 100 − 备货周期内可到达在途 250 = 0
    assertThat(new BigDecimal(covered.get("shortageQuantity").toString()))
        .isEqualByComparingTo("0");
    // 备货周期之后才到的 999 与没有 ETA 的 80 都不计入
    assertThat(new BigDecimal(covered.get("inTransitQuantity").toString()))
        .isEqualByComparingTo("1249");
    assertThat(new BigDecimal(covered.get("inTransitArrivingQuantity").toString()))
        .isEqualByComparingTo("250");
    assertThat(new BigDecimal(covered.get("inTransitNoEtaQuantity").toString()))
        .isEqualByComparingTo("0");

    // SKU-A-05：消耗 300 − 可售 100 − 0 = 200；没有 ETA 的 80 顶不上
    assertThat(new BigDecimal(uncovered.get("shortageQuantity").toString()))
        .isEqualByComparingTo("200");
    assertThat(new BigDecimal(uncovered.get("inTransitNoEtaQuantity").toString()))
        .isEqualByComparingTo("80");
    assertThat(new BigDecimal(uncovered.get("inTransitArrivingQuantity").toString()))
        .isEqualByComparingTo("0");

    // 断货日都落在备货周期内：缺口为 0 是 MEDIUM，有缺口是 HIGH
    assertThat(covered.get("urgency")).isEqualTo("MEDIUM");
    assertThat(uncovered.get("urgency")).isEqualTo("HIGH");
  }

  @Test
  void 零可售量且没有销售速度登记也必须出现在清单里() throws Exception {
    // 把 SKU-A-03 的可售量改成 0 —— 货都没有是最硬的断货信号，
    // 不能因为「没登记日均销」就看不见它。
    stockRows.put(
        a,
        List.of(stock(skuFor(a, 3), "SKU-A-03", "0", "0", "0")));
    compute("Bearer tenant-a");
    var rows = rowsOf(items("Bearer tenant-a"), "rows");
    Map<String, Object> row = rowByCode(rows, "SKU-A-03");
    assertThat(row.get("urgency")).isEqualTo("NO_DATA");
    assertThat(new BigDecimal(row.get("availableQuantity").toString()))
        .isEqualByComparingTo("0");
  }

  @Test
  void 清单按紧急度排序() throws Exception {
    registerVelocity("Bearer tenant-a", skuFor(a, 1), "SKU-A-01", "10", LocalDate.of(2026, 1, 1));
    registerVelocity("Bearer tenant-a", skuFor(a, 2), "SKU-A-02", "10", LocalDate.of(2026, 1, 1));
    registerVelocity("Bearer tenant-a", skuFor(a, 4), "SKU-A-04", "10", LocalDate.of(2026, 1, 1));
    registerVelocity("Bearer tenant-a", skuFor(a, 5), "SKU-A-05", "10", LocalDate.of(2026, 1, 1));
    compute("Bearer tenant-a");

    var rows = rowsOf(items("Bearer tenant-a"), "rows");
    List<String> urgencies = rows.stream().map(r -> String.valueOf(r.get("urgency"))).toList();
    // CRITICAL(0) → HIGH(1) → MEDIUM(2) → LOW(3) → NO_DATA(4)
    List<String> ranks = new java.util.ArrayList<>();
    for (String urgency : urgencies) {
      ranks.add(
          switch (urgency) {
            case "CRITICAL" -> "0";
            case "HIGH" -> "1";
            case "MEDIUM" -> "2";
            case "LOW" -> "3";
            default -> "4";
          });
    }
    assertThat(ranks).isSorted();
    assertThat(urgencies.get(0)).isEqualTo("CRITICAL");
    // 排序位次是落库的，界面可以直接显示「第几」
    assertThat(rows.get(0).get("sortRank")).isEqualTo(0);
  }

  @Test
  void 详情给出逐项拆解() throws Exception {
    registerVelocity("Bearer tenant-a", skuFor(a, 2), "SKU-A-02", "10", LocalDate.of(2026, 1, 1));
    compute("Bearer tenant-a");
    var rows = rowsOf(items("Bearer tenant-a"), "rows");
    String itemId = String.valueOf(rowByCode(rows, "SKU-A-02").get("id"));

    http.perform(get("/trade/stockout-risk/items/" + itemId).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.item.skuCode").value("SKU-A-02"))
        .andExpect(jsonPath("$.shops.length()").value(2))
        .andExpect(jsonPath("$.shops[0].shopCode").value("US-AMAZON"))
        .andExpect(jsonPath("$.deductions.length()").value(10))
        .andExpect(jsonPath("$.deductions[0].stepKind").value("ON_HAND"))
        .andExpect(jsonPath("$.deductions[3].stepKind").value("SELLABLE"))
        .andExpect(jsonPath("$.deductions[9].stepKind").value("SHORTAGE"))
        .andExpect(jsonPath("$.deductions[9].quantity").value(295));
  }

  @Test
  void 清单支持按紧急度与缺口筛选() throws Exception {
    registerVelocity("Bearer tenant-a", skuFor(a, 4), "SKU-A-04", "10", LocalDate.of(2026, 1, 1));
    registerVelocity("Bearer tenant-a", skuFor(a, 5), "SKU-A-05", "10", LocalDate.of(2026, 1, 1));
    compute("Bearer tenant-a");

    http.perform(
            get("/trade/stockout-risk/items")
                .header("Authorization", "Bearer tenant-a")
                .param("urgency", "HIGH"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.rows[0].skuCode").value("SKU-A-05"));

    http.perform(
            get("/trade/stockout-risk/items")
                .header("Authorization", "Bearer tenant-a")
                .param("onlyShortage", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.rows[0].skuCode").value("SKU-A-05"));

    http.perform(
            get("/trade/stockout-risk/items")
                .header("Authorization", "Bearer tenant-a")
                .param("onlyWithoutVelocity", "true"))
        .andExpect(status().isOk())
        // A-01 / A-02 / A-03 都没登记销售速度
        .andExpect(jsonPath("$.total").value(3))
        .andExpect(jsonPath("$.rows[0].skuCode").value("SKU-A-01"))
        .andExpect(jsonPath("$.rows[0].urgency").value("NO_DATA"));
  }

  @Test
  void 上游不可达时显式失败而不是给一份零库存的清单() throws Exception {
    warehouseDown.set(true);
    http.perform(
            post("/trade/stockout-risk/snapshots")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("leadTimeDays", LEAD_TIME))))
        .andExpect(status().isServiceUnavailable());
    warehouseDown.set(false);
  }

  @Test
  void 只读身份不能计算也不能登记() throws Exception {
    http.perform(
            post("/trade/stockout-risk/snapshots")
                .header("Authorization", "Bearer read-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("leadTimeDays", LEAD_TIME))))
        .andExpect(status().isForbidden());
    http.perform(
            post("/trade/stockout-risk/velocities")
                .header("Authorization", "Bearer read-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "skuId", skuFor(a, 1).toString(),
                            "skuCode", "SKU-A-01",
                            "dailySalesQuantity", new BigDecimal("10"),
                            "windowStart", "2025-12-01",
                            "windowEnd", "2025-12-31",
                            "effectiveFrom", "2026-01-01",
                            "sourceKind", "MANUAL"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void 能力声明把每个输入的归属与口径说清楚() throws Exception {
    http.perform(get("/trade/stockout-risk/capabilities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.warehouseQuantityAvailable").value(true))
        .andExpect(jsonPath("$.warehouseInTransitAvailable").value(true))
        .andExpect(jsonPath("$.channelShopMappingAvailable").value(true))
        .andExpect(jsonPath("$.shopNameAvailable").value(false))
        .andExpect(jsonPath("$.purchaseOrderAvailable").value(false))
        .andExpect(jsonPath("$.coverageFormula").value(org.hamcrest.Matchers.containsString("floor")));
  }
}
