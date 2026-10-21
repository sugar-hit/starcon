package com.starcon.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 6.3.2.1 跟单与交期的<b>共享底座</b>。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身</b>。跨服务一律走**真的本地 HTTP 服务**：
 *
 * <ul>
 *   <li><b>identity</b>：{@code GET /internal/access} —— 只回答「你是谁、在哪个租户、有哪些权限与范围」；
 *       租户隔离仍由 trade 侧的 RLS 在数据库层强制。</li>
 *   <li><b>warehouse</b>：7.1.1.3 可用量 / 7.5.1.2 在途腿 —— 6.2.1.1 断货风险的输入。</li>
 *   <li><b>channel</b>：4.1.1.1 SKU → 店铺映射 —— 6.2.1.1 的另一项输入。</li>
 *   <li><b>catalog</b>：3.6.2 有效报价 / 3.1.2.4 SKU 详情 —— 6.2.1.2 补货建议的输入。</li>
 * </ul>
 *
 * <p><b>上游事实怎么来的</b>：6.3.1.1 的采购单、6.3.2.2 的到货跟踪、6.2.1.1 的销售速度登记
 * **直接种进它们自己的表** —— 本模块是它们的下游消费者，读它们的事实表正是本模块要证明的事；
 * 种数据用迁移用户（超级用户），生产运行角色 {@code trade_app} 对那些表只有上游授予的权限。
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class PurchaseFollowupFixture {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenantA = UUID.randomUUID(), tenantB = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID(), userB = UUID.randomUUID(), userC = UUID.randomUUID();
  static final UUID departmentA = UUID.randomUUID();
  static final UUID warehouseId = UUID.randomUUID();
  static final UUID legalEntityId = UUID.randomUUID();
  static final UUID supplierA = UUID.randomUUID(), supplierB = UUID.randomUUID();

  static HttpServer identity, warehouse, channel, catalog;
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 置位后 warehouse 一律回 500（模拟「仓库服务不可达」⇒ 回写必须如实记 FAILED）。 */
  static final AtomicBoolean warehouseDown = new AtomicBoolean();

  /** 置位后 catalog 一律回 500（模拟「商品主数据服务不可达」）。 */
  static final AtomicBoolean catalogDown = new AtomicBoolean();

  /** 租户 → warehouse 的可用量行。 */
  static final Map<UUID, List<Map<String, Object>>> stockRows = new ConcurrentHashMap<>();

  /** 租户 → warehouse 的在途腿。 */
  static final Map<UUID, List<Map<String, Object>>> transitRows = new ConcurrentHashMap<>();

  static final List<String> perms = List.of("trade.purchase.read", "trade.purchase.update");

  static AccessService.Access actor(UUID uid, UUID tenant, UUID dept, String scope) {
    var grants = List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", scope, perms));
    return new AccessService.Access(uid, tenant, "Test", UUID.randomUUID(), dept, perms, grants);
  }

  static UUID tenantOf(String bearer) {
    var access = actors.get(bearer);
    return access == null ? null : access.tenantId();
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，隔离断言全部变成空断言。
        s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
      }

      // ---- identity：只回答「你是谁 / 哪个租户 / 什么权限与范围」 ----
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
      identity.start();

      // ---- warehouse：7.1.1.3 可用量 + 7.5.1.2 在途腿 ----
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
                new java.util.ArrayList<>(transitRows.getOrDefault(tenant, List.of()));
            String kinds = params.get("transitKind");
            if (kinds != null && !kinds.isBlank()) {
              var allowed = List.of(kinds.split(","));
              rows.removeIf(r -> !allowed.contains(String.valueOf(r.get("transitKind"))));
            }
            write(warehouse, e, 200, paged(rows, params));
          });
      warehouse.start();

      // ---- channel：4.1.1.1 SKU → 店铺映射 ----
      channel = HttpServer.create(new InetSocketAddress(0), 0);
      channel.createContext(
          "/channel/listing-mappings",
          e -> {
            UUID tenant = tenantOf(e.getRequestHeaders().getFirst("Authorization"));
            if (tenant == null) {
              write(channel, e, 401, Map.of("message", "未认证"));
              return;
            }
            write(channel, e, 200, paged("items", List.of(), queryOf(e)));
          });
      channel.start();

      // ---- catalog：3.6.2 有效报价 + 3.1.2.4 SKU 详情 ----
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/supplier-quotes/effective",
          e -> {
            if (catalogDown.get()) {
              write(catalog, e, 500, Map.of("message", "商品主数据服务内部错误"));
              return;
            }
            var body = new LinkedHashMap<String, Object>();
            body.put("candidates", List.of());
            body.put("ambiguous", false);
            body.put("ambiguityProblem", null);
            body.put("note", "测试替身：没有有效报价");
            write(catalog, e, 200, body);
          });
      catalog.createContext(
          "/catalog/skus",
          e -> {
            if (catalogDown.get()) {
              write(catalog, e, 500, Map.of("message", "商品主数据服务内部错误"));
              return;
            }
            String path = e.getRequestURI().getPath();
            String id = path.substring(path.lastIndexOf('/') + 1);
            var body = new LinkedHashMap<String, Object>();
            body.put("id", id);
            body.put("code", "SKU-T");
            body.put("name", "SKU-T 名称");
            body.put("category", "CAT-T");
            body.put("baseUnit", "件");
            body.put("unitConversions", List.of());
            write(catalog, e, 200, body);
          });
      catalog.start();

      actors.put("Bearer tenant-a", actor(userA, tenantA, departmentA, "ALL"));
      actors.put("Bearer tenant-b", actor(userB, tenantB, null, "ALL"));
      // 同一个租户里的**另一个**用户：数据范围 SELF 时只看得到自己负责的单。
      actors.put("Bearer self-a", actor(userC, tenantA, departmentA, "SELF"));
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static Map<String, String> queryOf(HttpExchange e) {
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

  static <T> Map<String, Object> paged(List<T> rows, Map<String, String> params) {
    return paged("rows", rows, params);
  }

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

  static void write(HttpServer server, HttpExchange e, int code, Object body) throws IOException {
    byte[] bytes = json.writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(code, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
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
    r.add("CATALOG_URL", () -> "http://localhost:" + catalog.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // ⚠️ **连接池固定为 1**：池大于 1 时每次可能拿到不同连接，
    // 「归还后不串租户」可能只是碰巧换了条干净连接。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @AfterAll
  static void noteSharedResources() {
    // 🔴 **刻意不在这里停容器与替身服务**：它们被两个测试类共享，
    // 先结束的测试类把共享资源停掉会让后面的类全部失败。
    // 改由 JVM 退出时由 Testcontainers 的 Ryuk 统一回收。
  }

  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ================================================================ 事务助手

  /** 在**读权限**的租户事务里跑一段工作（RLS 已绑定）。 */
  <T> T as(String bearer, java.util.function.Supplier<T> work) {
    return access.run(actors.get(bearer), PurchaseFollowupService.READ_PERMISSION, work);
  }

  /** 在**写权限**的租户事务里跑一段工作。 */
  <T> T asWrite(String bearer, java.util.function.Supplier<T> work) {
    return access.run(actors.get(bearer), PurchaseFollowupService.UPDATE_PERMISSION, work);
  }

  // ================================================================ 数据准备

  /** 用迁移用户（超级用户）连接：绕开 RLS，只用来清表与种上游事实。 */
  static Connection admin() throws Exception {
    return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
  }

  /** 清掉本模块的表与上游事实表。 */
  static void truncateAll() throws Exception {
    try (var c = admin();
        var s = c.createStatement()) {
      s.execute(
          "truncate purchase_delivery_writeback, purchase_delivery_change, purchase_followup_record,"
              + " purchase_order_event, purchase_order_line, purchase_order,"
              + " purchase_arrival_event, purchase_arrival_disposition,"
              + " purchase_arrival_payable_confirmation, purchase_arrival_receipt,"
              + " purchase_arrival_tracking,"
              + " stockout_risk_item_deduction, stockout_risk_item_shop, stockout_risk_item,"
              + " stockout_risk_snapshot, stockout_sales_velocity,"
              + " replenishment_suggestion_decision, replenishment_suggestion_step,"
              + " replenishment_suggestion, replenishment_suggestion_batch,"
              + " replenishment_order_policy, replenishment_coverage_policy cascade");
    }
  }

  /** 数据库口径的今天（UTC 日历日）。 */
  static LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  /**
   * 种一张采购单（头 + 一行；金额与行数由 DB 触发器算）。
   *
   * @param createdAt 建单时刻（用来断言「承诺交期天数」的口径）
   */
  static UUID seedOrder(
      UUID tenant,
      UUID owner,
      UUID dept,
      String orderNo,
      UUID supplier,
      String supplierCode,
      String supplierName,
      LocalDate promised,
      String status,
      java.time.OffsetDateTime createdAt)
      throws Exception {
    UUID id = UUID.randomUUID();
    // ⚠️ **已关闭的单子必须先按未关闭建出来再关掉**：
    //   V254 的行级约束触发器拒绝对 CLOSED 的单子加行（"已关闭，不允许再改它的行"），
    //   而状态机只允许 IN_FULFILLMENT → CLOSED。
    boolean closeLater = "CLOSED".equals(status);
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into purchase_order(id,tenant_id,order_no,source_type,supplier_ref_id,"
                    + " supplier_code,supplier_name,legal_entity_id,legal_entity_code,legal_entity_name,"
                    + " currency,status,promised_arrival_date,target_warehouse_id,target_warehouse_code,"
                    + " target_warehouse_name,owner_user_id,owner_department_id,created_by,created_at,updated_at)"
                    + " values (?::uuid,?::uuid,?::varchar,'MANUAL',?::uuid,?::varchar,?::varchar,"
                    + " ?::uuid,'US-LLC','美国子公司','USD',?::varchar,?::date,?::uuid,'WH-LA-01','洛杉矶仓',"
                    + " ?::uuid,?::uuid,?::uuid,?::timestamptz,?::timestamptz)")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setString(3, orderNo);
      ps.setObject(4, supplier);
      ps.setString(5, supplierCode);
      ps.setString(6, supplierName);
      ps.setObject(7, legalEntityId);
      ps.setString(8, closeLater ? "IN_FULFILLMENT" : status);
      ps.setDate(9, java.sql.Date.valueOf(promised));
      ps.setObject(10, warehouseId);
      ps.setObject(11, owner);
      if (dept == null) {
        ps.setNull(12, java.sql.Types.OTHER);
      } else {
        ps.setObject(12, dept);
      }
      ps.setObject(13, userA);
      ps.setObject(14, createdAt);
      ps.setObject(15, createdAt);
      ps.executeUpdate();
    }
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into purchase_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                    + " quantity,unit_code,unit_price,line_amount,created_at)"
                    + " values (?::uuid,?::uuid,?::uuid,1,?::uuid,?::varchar,?::varchar,"
                    + " ?::numeric,'件',?::numeric,?::numeric,now())")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setObject(2, tenant);
      ps.setObject(3, id);
      ps.setObject(4, UUID.nameUUIDFromBytes(("SKU-" + orderNo).getBytes()));
      ps.setString(5, "SKU-" + orderNo);
      ps.setString(6, "SKU-" + orderNo + " 名称");
      ps.setBigDecimal(7, new BigDecimal("100"));
      ps.setBigDecimal(8, new BigDecimal("2.5"));
      ps.setBigDecimal(9, new BigDecimal("250"));
      ps.executeUpdate();
    }
    // 关单：状态机只允许 IN_FULFILLMENT → CLOSED，且关闭必须留理由与时刻。
    if (closeLater) {
      try (var c = admin();
          var ps =
              c.prepareStatement(
                  "update purchase_order set status='CLOSED', closed_at=?::timestamptz,"
                      + " close_reason='测试：已关闭' where id=?::uuid")) {
        ps.setObject(1, createdAt);
        ps.setObject(2, id);
        ps.executeUpdate();
      }
    }
    return id;
  }

  /**
   * 种一张 6.3.2.2 的到货跟踪（上游事实）。
   *
   * <p>⚠️ **已收数量只能通过「到货流水」进去**：6.3.2.2 的 BEFORE 触发器在 INSERT 时把
   * {@code received_qty} 强制改成 0，AFTER 触发器才按流水把它算出来。
   *
   * @param arrivedAt 实际到货时刻（null = 还没到货）
   */
  static UUID seedArrivalTracking(
      UUID tenant, String orderNo, UUID supplier, String supplierCode, String supplierName,
      LocalDate promised, java.time.OffsetDateTime arrivedAt)
      throws Exception {
    UUID id = UUID.randomUUID();
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into purchase_arrival_tracking(id,tenant_id,tracking_no,purchase_order_no,"
                    + " source_doc_no,source_doc_type,supplier_ref_id,supplier_code,supplier_name,"
                    + " receivable_qty,unit_price,currency,promised_arrival_date,arrival_status,"
                    + " created_by,created_at,updated_at)"
                    + " values (?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,'PURCHASE_ORDER',"
                    + " ?::uuid,?::varchar,?::varchar,?::numeric,?::numeric,'USD',?::date,"
                    + " 'PENDING_SHIPMENT',?::uuid,now(),now())")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setString(3, "PA-TEST-" + orderNo);
      ps.setString(4, orderNo);
      ps.setString(5, orderNo);
      ps.setObject(6, supplier);
      ps.setString(7, supplierCode);
      ps.setString(8, supplierName);
      ps.setBigDecimal(9, new BigDecimal("100"));
      ps.setBigDecimal(10, new BigDecimal("2.5"));
      ps.setDate(11, java.sql.Date.valueOf(promised));
      ps.setObject(12, userA);
      ps.executeUpdate();
    }
    if (arrivedAt != null) {
      try (var c = admin();
          var ps =
              c.prepareStatement(
                  "insert into purchase_arrival_receipt(id,tenant_id,tracking_id,arrived_qty,"
                      + " arrived_at,recorded_at,warehouse_receipt_code,warehouse_receipt_received_qty,"
                      + " warehouse_receipt_synced_at,note,recorded_by)"
                      + " values (?::uuid,?::uuid,?::uuid,?::numeric,?::timestamptz,now(),?::varchar,"
                      + " ?::numeric,now(),'上游事实',?::uuid)")) {
        ps.setObject(1, UUID.randomUUID());
        ps.setObject(2, tenant);
        ps.setObject(3, id);
        ps.setBigDecimal(4, new BigDecimal("100"));
        ps.setObject(5, arrivedAt);
        ps.setString(6, "GR-TEST-" + orderNo);
        ps.setBigDecimal(7, new BigDecimal("100"));
        ps.setObject(8, userA);
        ps.executeUpdate();
      }
    }
    return id;
  }

  /** 种一条 6.2.1.1 的销售速度登记（让断货风险清单真的能算出一个 SKU）。 */
  static UUID seedVelocity(UUID tenant, UUID skuId, String skuCode, String daily) throws Exception {
    UUID id = UUID.randomUUID();
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into stockout_sales_velocity(id,tenant_id,sku_id,sku_code,sku_name,"
                    + " daily_sales_quantity,window_start,window_end,effective_from,effective_to,"
                    + " source_kind,source_ref,note,created_by,created_at)"
                    + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::numeric,?::date,?::date,"
                    + " ?::date,null,'MANUAL','TEST','',?::uuid,now())")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setObject(3, skuId);
      ps.setString(4, skuCode);
      ps.setString(5, skuCode + " 名称");
      ps.setBigDecimal(6, new BigDecimal(daily));
      ps.setDate(7, java.sql.Date.valueOf(today().minusDays(30)));
      ps.setDate(8, java.sql.Date.valueOf(today().minusDays(1)));
      ps.setDate(9, java.sql.Date.valueOf(today().minusDays(30)));
      ps.setObject(10, userA);
      ps.executeUpdate();
    }
    return id;
  }

  /** 种一条 6.2.1.2 的默认覆盖策略（租户默认覆盖天数）。 */
  static void seedCoveragePolicy(UUID tenant, int coverageDays) throws Exception {
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into replenishment_coverage_policy(id,tenant_id,scope,coverage_days,"
                    + " effective_from,effective_to,note,created_by,created_at)"
                    + " values (?::uuid,?::uuid,'DEFAULT',?::int,?::date,null,'',?::uuid,now())")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setObject(2, tenant);
      ps.setInt(3, coverageDays);
      ps.setDate(4, java.sql.Date.valueOf(today().minusDays(30)));
      ps.setObject(5, userA);
      ps.executeUpdate();
    }
  }
}
