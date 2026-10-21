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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * 6.1.2.2 同款货源推荐的<b>共享底座</b>。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身</b>。跨服务一律走**真的本地 HTTP 服务**：
 *
 * <ul>
 *   <li><b>identity</b>：{@code GET /internal/access} —— 只回答「你是谁、在哪个租户、有哪些权限与范围」；
 *       租户隔离仍由 trade 侧的 RLS 在数据库层强制。</li>
 *   <li><b>catalog</b>：{@code GET /catalog/skus/{id}} —— 内部 SKU 的唯一来源（跨服务只读）。</li>
 *   <li><b>warehouse</b>：{@code GET /warehouse/warehouses} / {@code /warehouse/legal-entities}
 *       —— 目标仓与法人（生成采购申请时必填）。</li>
 * </ul>
 *
 * <p><b>上游事实怎么来的</b>：6.1.2.1 的货源池（档口 / 条目 / 快照 / SKU 关联）、6.1.1.3 的评级结果、
 * 6.3 的采购单 —— <b>直接种进它们自己的表</b>。本模块是它们的下游消费者，读它们的事实表
 * 正是本模块要证明的事；种数据用迁移用户（超级用户），生产运行角色 {@code trade_app}
 * 对那些表只有上游授予的权限。
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class SourceAlternativeFixture {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenantA = UUID.randomUUID(), tenantB = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID(), userB = UUID.randomUUID(), userC = UUID.randomUUID();
  static final UUID departmentA = UUID.randomUUID();
  static final UUID warehouseId = UUID.randomUUID();
  static final UUID legalEntityId = UUID.randomUUID();
  static final UUID supplierA = UUID.randomUUID(), supplierB = UUID.randomUUID();
  static final UUID skuA = UUID.randomUUID(), skuB = UUID.randomUUID();

  static HttpServer identity, catalog, warehouse;
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** catalog 侧的 SKU：id → {code, name, status}。 */
  static final Map<UUID, Map<String, Object>> catalogSkus = new ConcurrentHashMap<>();

  static final List<String> perms = List.of("trade.alternatives.read", "trade.alternatives.update");

  static AccessService.Access actor(UUID uid, UUID tenant, UUID dept, String scope) {
    var grants = List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", scope, perms));
    return new AccessService.Access(uid, tenant, "Test", UUID.randomUUID(), dept, perms, grants);
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

      // ---- catalog：内部 SKU（跨服务只读） ----
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/skus",
          e -> {
            String path = e.getRequestURI().getPath();
            String id = path.substring(path.lastIndexOf('/') + 1);
            Map<String, Object> sku;
            try {
              sku = catalogSkus.get(UUID.fromString(id));
            } catch (RuntimeException ex) {
              sku = null;
            }
            if (sku == null) {
              write(catalog, e, 404, Map.of("message", "商品不存在"));
              return;
            }
            write(catalog, e, 200, sku);
          });
      catalog.start();

      // ---- warehouse：目标仓与法人 ----
      warehouse = HttpServer.create(new InetSocketAddress(0), 0);
      warehouse.createContext(
          "/warehouse/warehouses",
          e ->
              write(
                  warehouse,
                  e,
                  200,
                  List.of(
                      Map.of(
                          "id", warehouseId.toString(),
                          "code", "WH-LA-01",
                          "name", "洛杉矶仓",
                          "countryCode", "US",
                          "status", "ACTIVE"))));
      warehouse.createContext(
          "/warehouse/legal-entities",
          e ->
              write(
                  warehouse,
                  e,
                  200,
                  List.of(
                      Map.of(
                          "id", legalEntityId.toString(),
                          "code", "US-LLC",
                          "name", "美国子公司",
                          "status", "ACTIVE"))));
      warehouse.start();

      actors.put("Bearer tenant-a", actor(userA, tenantA, departmentA, "ALL"));
      actors.put("Bearer tenant-b", actor(userB, tenantB, null, "ALL"));
      // 只读身份：写端点必须 403（权限码不是一个筐）。
      actors.put(
          "Bearer read-only",
          new AccessService.Access(
              userC,
              tenantA,
              "Test",
              UUID.randomUUID(),
              departmentA,
              List.of("trade.alternatives.read"),
              List.of(
                  new AccessService.Grant(
                      UUID.randomUUID(), "TEST", "ALL", List.of("trade.alternatives.read")))));
    } catch (Exception e) {
      throw new IllegalStateException("测试底座启动失败", e);
    }
  }

  static void write(HttpServer server, HttpExchange e, int code, Object body) throws IOException {
    byte[] bytes = json.writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(code, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }

  static String queryParam(HttpExchange e, String key) {
    String q = e.getRequestURI().getRawQuery();
    if (q == null || q.isBlank()) return null;
    for (String pair : q.split("&")) {
      int i = pair.indexOf('=');
      if (i < 0) continue;
      if (URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8).equals(key)) {
        return URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "trade_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://localhost:" + catalog.getAddress().getPort());
    r.add("WAREHOUSE_URL", () -> "http://localhost:" + warehouse.getAddress().getPort());
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
    return access.run(actors.get(bearer), SourceAlternativeService.READ_PERMISSION, work);
  }

  /** 在**写权限**的租户事务里跑一段工作。 */
  <T> T asWrite(String bearer, java.util.function.Supplier<T> work) {
    return access.run(actors.get(bearer), SourceAlternativeService.UPDATE_PERMISSION, work);
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
          "truncate source_alternative_decision, source_alternative_candidate,"
              + " source_alternative_run, source_alternative_policy,"
              + " supplier_rating_result,"
              + " supplier_source_sku_link, supplier_source_snapshot, supplier_source_item,"
              + " supplier_source_collection, supplier_source_stall,"
              + " purchase_order_event, purchase_order_line, purchase_order"
              + " cascade");
    }
    catalogSkus.clear();
  }

  /** 在 catalog 侧登记一个 SKU（跨服务只读的事实来源）。 */
  static void seedCatalogSku(UUID skuId, String code, String name) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", skuId.toString());
    row.put("code", code);
    row.put("name", name);
    row.put("status", "ACTIVE");
    catalogSkus.put(skuId, row);
  }

  /** 数据库口径的今天（UTC 日历日）。 */
  static LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  /** 种一个档口（6.1.2.1）。 */
  static UUID seedStall(
      UUID tenant, String code, String platform, String platformStallId, String name, UUID supplierId)
      throws Exception {
    UUID id = UUID.randomUUID();
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into supplier_source_stall(id,tenant_id,code,platform,platform_stall_id,name,"
                    + " url,collect_method,supplier_party_id,supplier_name_snapshot,status,note,"
                    + " created_at,updated_at)"
                    + " values (?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::varchar,'',"
                    + " 'PLUGIN_PUSH',?::uuid,?::varchar,'ACTIVE','',now(),now())")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setString(3, code);
      ps.setString(4, platform);
      ps.setString(5, platformStallId);
      ps.setString(6, name);
      if (supplierId == null) ps.setNull(7, java.sql.Types.OTHER);
      else ps.setObject(7, supplierId);
      ps.setString(8, code + " 供应商");
      ps.executeUpdate();
    }
    return id;
  }

  /** 种一个货源条目（6.1.2.1）。 */
  static UUID seedItem(UUID tenant, UUID stallId, String platformItemId) throws Exception {
    UUID id = UUID.randomUUID();
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into supplier_source_item(id,tenant_id,stall_id,platform_item_id,"
                    + " first_seen_at,last_seen_at,current_snapshot_id,version,created_at,updated_at)"
                    + " values (?::uuid,?::uuid,?::uuid,?::varchar,now(),now(),null,0,now(),now())")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setObject(3, stallId);
      ps.setString(4, platformItemId);
      ps.executeUpdate();
    }
    return id;
  }

  /** 种一版货源快照（6.1.2.1；价格永远是区间）。 */
  static UUID seedSnapshot(
      UUID tenant,
      UUID itemId,
      int seq,
      String title,
      String spec,
      String priceMin,
      String priceMax,
      String currency,
      String moq,
      String imageUrl,
      boolean onSale,
      OffsetDateTime collectedAt)
      throws Exception {
    UUID collectionId = UUID.randomUUID();
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into supplier_source_collection(id,tenant_id,stall_id,collect_method,status,"
                    + " trigger_source,collected_at,started_at,finished_at,item_count,skipped_count,"
                    + " skipped_reason,failure_reason,created_at)"
                    + " values (?::uuid,?::uuid,(select stall_id from supplier_source_item where id=?::uuid),"
                    + " 'PLUGIN_PUSH','SUCCEEDED','PLUGIN',?::timestamptz,?::timestamptz,?::timestamptz,1,0,'','',now())")) {
      ps.setObject(1, collectionId);
      ps.setObject(2, tenant);
      ps.setObject(3, itemId);
      ps.setObject(4, collectedAt);
      ps.setObject(5, collectedAt);
      ps.setObject(6, collectedAt);
      ps.executeUpdate();
    }
    UUID id = UUID.randomUUID();
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into supplier_source_snapshot(id,tenant_id,item_id,collection_id,snapshot_seq,"
                    + " title,price_min,price_max,currency,moq,spec,image_url,item_url,on_sale,"
                    + " collected_at,created_at)"
                    + " values (?::uuid,?::uuid,?::uuid,?::uuid,?,?::varchar,?::numeric,?::numeric,"
                    + " ?::varchar,?::numeric,?::varchar,?::varchar,'',?,?::timestamptz,now())")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setObject(3, itemId);
      ps.setObject(4, collectionId);
      ps.setInt(5, seq);
      ps.setString(6, title);
      ps.setBigDecimal(7, new BigDecimal(priceMin));
      ps.setBigDecimal(8, new BigDecimal(priceMax));
      ps.setString(9, currency);
      ps.setBigDecimal(10, new BigDecimal(moq));
      ps.setString(11, spec);
      ps.setString(12, imageUrl);
      ps.setBoolean(13, onSale);
      ps.setObject(14, collectedAt);
      ps.executeUpdate();
    }
    // 当前指针指到这一版。
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "update supplier_source_item set current_snapshot_id=?::uuid, updated_at=now()"
                    + " where id=?::uuid")) {
      ps.setObject(1, id);
      ps.setObject(2, itemId);
      ps.executeUpdate();
    }
    return id;
  }

  /** 建立货源与内部 SKU 的关联（6.1.2.1 的**人工确认**关联 = 同款判定的最强证据）。 */
  static UUID seedSkuLink(UUID tenant, UUID itemId, UUID skuId, String skuCode, String skuName)
      throws Exception {
    UUID id = UUID.randomUUID();
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into supplier_source_sku_link(id,tenant_id,item_id,sku_id,sku_code,sku_name,"
                    + " linked_at,linked_by,note,created_at)"
                    + " values (?::uuid,?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,now(),null,'',now())")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setObject(3, itemId);
      ps.setObject(4, skuId);
      ps.setString(5, skuCode);
      ps.setString(6, skuName);
      ps.executeUpdate();
    }
    return id;
  }

  /**
   * 种一张已下过的采购单（6.3 采购历史：供过这个货的供应商 + 历史交期）。
   *
   * <p>状态取 IN_FULFILLMENT：**只有真正下过、没有被驳回的采购单**才算「历史采购过」。
   */
  static UUID seedPurchaseOrder(
      UUID tenant,
      String orderNo,
      UUID supplierId,
      String supplierCode,
      String supplierName,
      UUID skuId,
      String skuCode,
      String quantity,
      String unitPrice,
      LocalDate promised,
      OffsetDateTime createdAt)
      throws Exception {
    UUID id = UUID.randomUUID();
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into purchase_order(id,tenant_id,order_no,source_type,supplier_ref_id,"
                    + " supplier_code,supplier_name,legal_entity_id,legal_entity_code,legal_entity_name,"
                    + " currency,status,promised_arrival_date,target_warehouse_id,target_warehouse_code,"
                    + " target_warehouse_name,owner_user_id,owner_department_id,created_by,created_at,updated_at)"
                    + " values (?::uuid,?::uuid,?::varchar,'MANUAL',?::uuid,?::varchar,?::varchar,"
                    + " ?::uuid,'US-LLC','美国子公司',?::varchar,'IN_FULFILLMENT',?::date,?::uuid,'WH-LA-01',"
                    + " '洛杉矶仓',?::uuid,?::uuid,?::uuid,?::timestamptz,?::timestamptz)")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setString(3, orderNo);
      ps.setObject(4, supplierId);
      ps.setString(5, supplierCode);
      ps.setString(6, supplierName);
      ps.setObject(7, legalEntityId);
      ps.setString(8, "USD");
      ps.setDate(9, java.sql.Date.valueOf(promised));
      ps.setObject(10, warehouseId);
      ps.setObject(11, userA);
      ps.setObject(12, departmentA);
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
      ps.setObject(4, skuId);
      ps.setString(5, skuCode);
      ps.setString(6, skuCode + " 名称");
      ps.setBigDecimal(7, new BigDecimal(quantity));
      ps.setBigDecimal(8, new BigDecimal(unitPrice));
      ps.setBigDecimal(9, new BigDecimal(quantity).multiply(new BigDecimal(unitPrice)));
      ps.executeUpdate();
    }
    return id;
  }

  /**
   * 种一条评级结果（6.1.1.3）。
   *
   * <p>⚠️ 人工调整过的那条必须**同时**带上理由、操作人与时刻（DB CHECK 钉死），
   * 否则「人工加权分需单独标记」这件事在数据层就存不住。
   */
  static UUID seedRating(
      UUID tenant,
      UUID supplierId,
      String supplierCode,
      String supplierName,
      String objectiveScore,
      String manualAdjustment,
      String finalScore,
      String grade,
      boolean manuallyAdjusted,
      LocalDate periodStart,
      LocalDate periodEnd)
      throws Exception {
    UUID id = UUID.randomUUID();
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into supplier_rating_result(id,tenant_id,period_type,period_start,period_end,"
                    + " supplier_ref_id,supplier_code,supplier_name,objective_score,manual_adjustment,"
                    + " final_score,grade,available_weight,unavailable_dimensions,unavailable_reason,"
                    + " manually_adjusted,manual_adjusted_by,manual_adjusted_at,manual_adjustment_reason,"
                    + " computed_at,version,created_at,updated_at)"
                    + " values (?::uuid,?::uuid,'MONTHLY',?::date,?::date,?::uuid,?::varchar,?::varchar,"
                    + " ?::numeric,?::numeric,?::numeric,?::varchar,1.0,'','',?,?,?::timestamptz,?,"
                    + " now(),0,now(),now())")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setDate(3, java.sql.Date.valueOf(periodStart));
      ps.setDate(4, java.sql.Date.valueOf(periodEnd));
      ps.setObject(5, supplierId);
      ps.setString(6, supplierCode);
      ps.setString(7, supplierName);
      ps.setBigDecimal(8, new BigDecimal(objectiveScore));
      ps.setBigDecimal(9, new BigDecimal(manualAdjustment));
      ps.setBigDecimal(10, new BigDecimal(finalScore));
      ps.setString(11, grade);
      ps.setBoolean(12, manuallyAdjusted);
      if (manuallyAdjusted) {
        ps.setObject(13, userA);
        ps.setObject(14, OffsetDateTime.now(ZoneOffset.UTC));
        ps.setString(15, "测试：人工扶持理由");
      } else {
        ps.setNull(13, java.sql.Types.OTHER);
        ps.setNull(14, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
        ps.setString(15, "");
      }
      ps.executeUpdate();
    }
    return id;
  }
}
