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
import java.util.ArrayList;
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
 * 6.1.1.3 供应商评级的<b>共享底座</b>。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身</b>。跨服务一律走**真的本地 HTTP 服务**：
 *
 * <ul>
 *   <li><b>identity</b>：{@code GET /internal/access} —— 只回答「你是谁、在哪个租户、有哪些权限与范围」；
 *       租户隔离仍由 trade 侧的 RLS 在数据库层强制。</li>
 *   <li><b>catalog</b>：{@code GET /catalog/supplier-quotes} —— 3.6.2 报价台账，
 *       价格竞争力维度的原料（跨服务只读，AGENTS.md 禁止跨库读表）。</li>
 * </ul>
 *
 * <p><b>上游事实怎么来的</b>：6.3.1.1 的采购单、6.3.2.2 的到货跟踪、6.3.2.1 的跟单记录、
 * 6.4.2.1 的来料质检单、11.2.3.3 的供应商索赔**直接种进它们自己的表** ——
 * 本模块是它们的下游消费者，读它们的事实表正是本模块要证明的事；
 * 种数据用迁移用户（超级用户），生产运行角色 {@code trade_app} 对那些表只有上游授予的权限。
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class SupplierRatingFixture {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenantA = UUID.randomUUID(), tenantB = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID(), userB = UUID.randomUUID(), userC = UUID.randomUUID();
  static final UUID departmentA = UUID.randomUUID();
  static final UUID warehouseId = UUID.randomUUID();
  static final UUID legalEntityId = UUID.randomUUID();
  static final UUID supplierA = UUID.randomUUID(), supplierB = UUID.randomUUID();

  static HttpServer identity, catalog;
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 置位后 catalog 一律回 500（模拟「商品主数据服务不可达」）。 */
  static final AtomicBoolean catalogDown = new AtomicBoolean();

  /** 报价台账的替身内容：SKU id → 该 SKU 的有效报价行。 */
  static final Map<UUID, List<Map<String, Object>>> quoteRows = new ConcurrentHashMap<>();

  static final List<String> perms = List.of("trade.ratings.read", "trade.ratings.update");

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

      // ---- catalog：3.6.2 报价台账（价格竞争力维度的原料） ----
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/supplier-quotes",
          e -> {
            if (catalogDown.get()) {
              write(catalog, e, 500, Map.of("message", "商品主数据服务内部错误"));
              return;
            }
            UUID tenant = tenantOf(e.getRequestHeaders().getFirst("Authorization"));
            if (tenant == null) {
              write(catalog, e, 401, Map.of("message", "未认证"));
              return;
            }
            List<Map<String, Object>> items = new ArrayList<>();
            for (var entry : quoteRows.entrySet()) {
              for (Map<String, Object> row : entry.getValue()) {
                items.add(row);
              }
            }
            var params = queryOf(e);
            int page = intOf(params, "page", 0);
            int size = intOf(params, "size", 200);
            int from = Math.min(page * size, items.size());
            int to = Math.min(from + size, items.size());
            var body = new LinkedHashMap<String, Object>();
            body.put("items", items.subList(from, to));
            body.put("total", items.size());
            body.put("page", page);
            body.put("size", size);
            write(catalog, e, 200, body);
          });
      catalog.start();

      actors.put("Bearer tenant-a", actor(userA, tenantA, departmentA, "ALL"));
      actors.put("Bearer tenant-b", actor(userB, tenantB, null, "ALL"));
      actors.put("Bearer self-a", actor(userC, tenantA, departmentA, "SELF"));
      // 只读身份：写端点必须 403（权限码不是一个筐）。
      actors.put(
          "Bearer read-only",
          new AccessService.Access(
              userC,
              tenantA,
              "Test",
              UUID.randomUUID(),
              departmentA,
              List.of("trade.ratings.read"),
              List.of(
                  new AccessService.Grant(
                      UUID.randomUUID(), "TEST", "ALL", List.of("trade.ratings.read")))));
    } catch (Exception e) {
      throw new IllegalStateException("测试底座启动失败", e);
    }
  }

  static int intOf(Map<String, String> params, String key, int fallback) {
    String raw = params.get(key);
    if (raw == null || raw.isBlank()) return fallback;
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  static Map<String, String> queryOf(HttpExchange e) {
    Map<String, String> out = new LinkedHashMap<>();
    String q = e.getRequestURI().getRawQuery();
    if (q == null || q.isBlank()) return out;
    for (String pair : q.split("&")) {
      int i = pair.indexOf('=');
      if (i < 0) continue;
      out.put(
          URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
          URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
    }
    return out;
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
    return access.run(actors.get(bearer), SupplierRatingService.READ_PERMISSION, work);
  }

  /** 在**写权限**的租户事务里跑一段工作。 */
  <T> T asWrite(String bearer, java.util.function.Supplier<T> work) {
    return access.run(actors.get(bearer), SupplierRatingService.UPDATE_PERMISSION, work);
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
          "truncate supplier_rating_change, supplier_rating_adjustment, supplier_rating_result,"
              + " supplier_rating_weight_policy,"
              + " supplier_claim_negotiation, supplier_claim_item, supplier_claim,"
              + " quality_supplier_ref,"
              + " quality_inspection_order_item, quality_inspection_order,"
              + " quality_inspection_template_item, quality_inspection_template,"
              + " purchase_followup_record, purchase_delivery_change,"
              + " purchase_order_event, purchase_order_line, purchase_order,"
              + " purchase_arrival_receipt, purchase_arrival_tracking"
              + " cascade");
    }
    quoteRows.clear();
    catalogDown.set(false);
  }

  /** 数据库口径的今天（UTC 日历日）。 */
  static LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  /** 某个自然月的第一天（用于把种子数据钉在本月里）。 */
  static LocalDate monthStart() {
    return today().withDayOfMonth(1);
  }

  /**
   * 种一张采购单（头 + 一行）。
   *
   * <p>行金额由 DB 触发器算（调用方传不进来），所以只传数量与单价。
   */
  static UUID seedOrder(
      UUID tenant,
      String orderNo,
      UUID supplier,
      String supplierCode,
      String supplierName,
      UUID skuId,
      String skuCode,
      String qty,
      String unitPrice,
      String currency,
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
      ps.setObject(4, supplier);
      ps.setString(5, supplierCode);
      ps.setString(6, supplierName);
      ps.setObject(7, legalEntityId);
      ps.setString(8, currency);
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
      ps.setBigDecimal(7, new BigDecimal(qty));
      ps.setBigDecimal(8, new BigDecimal(unitPrice));
      ps.setBigDecimal(9, new BigDecimal(qty).multiply(new BigDecimal(unitPrice)));
      ps.executeUpdate();
    }
    return id;
  }

  /**
   * 种一条 6.3.2.2 的到货跟踪 + 一次到货流水（上游事实）。
   *
   * <p>⚠️ **已收数量只能通过「到货流水」进去**：6.3.2.2 的 BEFORE 触发器在 INSERT 时把
   * {@code received_qty} 强制改成 0，AFTER 触发器才按流水把它算出来（并把状态推到 FULLY_RECEIVED）。
   */
  static UUID seedArrival(
      UUID tenant,
      String orderNo,
      UUID supplier,
      String supplierCode,
      String supplierName,
      LocalDate promised,
      OffsetDateTime arrivedAt)
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
    return id;
  }

  /**
   * 种一条 6.3.2.1 的交期变更（承诺日的历史事实）。
   *
   * <p>「到货时刻生效的承诺日」= 到货时刻之前最后一条变更的 to_promised_date ——
   * 评级用��就是这条口径（与跟单延迟统计同源）。
   */
  static UUID seedDeliveryChange(
      UUID tenant,
      String changeNo,
      String orderNo,
      UUID supplier,
      LocalDate from,
      LocalDate to,
      OffsetDateTime changedAt)
      throws Exception {
    UUID id = UUID.randomUUID();
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into purchase_delivery_change(id,tenant_id,change_no,purchase_order_no,"
                    + " supplier_ref_id,supplier_code,supplier_name,from_promised_date,to_promised_date,"
                    + " reason,changed_by,changed_at)"
                    + " values (?::uuid,?::uuid,?::varchar,?::varchar,?::uuid,?::varchar,?::varchar,"
                    + "?::date,?::date,'测试：供应商改期',?::uuid,?::timestamptz)")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setString(3, changeNo);
      ps.setString(4, orderNo);
      ps.setObject(5, supplier);
      ps.setString(6, "SUP-A");
      ps.setString(7, "供应商 A");
      ps.setDate(8, java.sql.Date.valueOf(from));
      ps.setDate(9, java.sql.Date.valueOf(to));
      ps.setObject(10, userA);
      ps.setObject(11, changedAt);
      ps.executeUpdate();
    }
    return id;
  }

  /** 种一条 6.3.2.1 的跟单记录（沟通凭据）。 */  static UUID seedFollowup(
      UUID tenant,
      String followupNo,
      String orderNo,
      UUID supplier,
      String supplierCode,
      String supplierName,
      String content,
      String reply,
      OffsetDateTime occurredAt)
      throws Exception {
    UUID id = UUID.randomUUID();
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into purchase_followup_record(id,tenant_id,followup_no,purchase_order_no,"
                    + " purchase_order_ref_id,supplier_ref_id,supplier_code,supplier_name,followup_type,"
                    + " channel,content,supplier_reply,occurred_at,next_followup_at,created_by,created_at)"
                    + " values (?::uuid,?::uuid,?::varchar,?::varchar,null,?::uuid,?::varchar,?::varchar,"
                    + " 'URGING','PHONE',?::varchar,?::varchar,?::timestamptz,null,?::uuid,now())")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setString(3, followupNo);
      ps.setString(4, orderNo);
      ps.setObject(5, supplier);
      ps.setString(6, supplierCode);
      ps.setString(7, supplierName);
      ps.setString(8, content);
      ps.setString(9, reply == null ? "" : reply);
      ps.setObject(10, occurredAt);
      ps.setObject(11, userA);
      ps.executeUpdate();
    }
    return id;
  }

  /** 种一张 6.4.2.1 的来料质检模板（质检单的 FK 目标）。 */
  static UUID seedTemplate(UUID tenant) throws Exception {
    UUID id = UUID.randomUUID();
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into quality_inspection_template(id,tenant_id,code,name,inspection_type,"
                    + " sampling_ratio,min_sample_quantity,max_sample_quantity,rounding_mode,"
                    + " critical_failure_action,defective_target,status,created_by)"
                    + " values (?::uuid,?::uuid,?::varchar,?::varchar,'INCOMING',?::numeric,?::numeric,"
                    + "?::numeric,'CEILING','DEDUCT_ONLY','PENDING_INSPECTION','ACTIVE',?::uuid)")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setString(3, "TPL-TEST-" + tenant.toString().substring(0, 8));
      ps.setString(4, "测试模板");
      ps.setBigDecimal(5, new BigDecimal("0.1"));
      ps.setBigDecimal(6, new BigDecimal("1"));
      ps.setBigDecimal(7, new BigDecimal("0"));
      ps.setObject(8, userA);
      ps.executeUpdate();
    }
    return id;
  }

  /**
   * 种一张**已判定完成**的来料质检单（质量合格率的原料）。
   *
   * <p>三量自洽由 DB CHECK 钉死：合格 + 不合格 + 让步 = 覆盖批量；
   * 不合格 + 让步 = 扣量合计。
   */
  static UUID seedInspection(
      UUID tenant,
      UUID templateId,
      UUID supplier,
      String supplierName,
      UUID skuId,
      String skuCode,
      String lotSize,
      String qualified,
      String defective,
      OffsetDateTime completedAt)
      throws Exception {
    UUID id = UUID.randomUUID();
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into quality_inspection_order(id,tenant_id,inspection_no,template_id,"
                    + " template_code,template_name,inspection_type,sku_id,sku_code,sku_name,"
                    + " supplier_id,supplier_name,arrived_quantity,lot_size,sampling_ratio,"
                    + " min_sample_quantity,max_sample_quantity,rounding_mode,sample_quantity,"
                    + " sampled_quantity,qualified_quantity,defective_quantity,concession_quantity,"
                    + " gross_deducted_quantity,status,result,disposition,disposition_status,"
                    + " created_by,completed_at,created_at,updated_at)"
                    + " values (?::uuid,?::uuid,?::varchar,?::uuid,?::varchar,?::varchar,'INCOMING',"
                    + " ?::uuid,?::varchar,?::varchar,?::uuid,?::varchar,?::numeric,?::numeric,?::numeric,"
                    + " ?::numeric,?::numeric,'CEILING',?::numeric,?::numeric,?::numeric,?::numeric,"
                    + " ?::numeric,?::numeric,'COMPLETED','PARTIAL','DEFECTIVE','NOT_DISPATCHED',"
                    + " ?::uuid,?::timestamptz,now(),now())")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setString(3, "QI-TEST-" + id.toString().substring(0, 8));
      ps.setObject(4, templateId);
      ps.setString(5, "TPL-TEST");
      ps.setString(6, "测试模板");
      ps.setObject(7, skuId);
      ps.setString(8, skuCode);
      ps.setString(9, skuCode + " 名称");
      ps.setObject(10, supplier);
      ps.setString(11, supplierName);
      ps.setBigDecimal(12, new BigDecimal(lotSize));
      ps.setBigDecimal(13, new BigDecimal(lotSize));
      ps.setBigDecimal(14, new BigDecimal("0.1"));
      ps.setBigDecimal(15, new BigDecimal("1"));
      ps.setBigDecimal(16, new BigDecimal("0"));
      ps.setBigDecimal(17, new BigDecimal(lotSize));
      ps.setBigDecimal(18, new BigDecimal(lotSize));
      ps.setBigDecimal(19, new BigDecimal(qualified));
      ps.setBigDecimal(20, new BigDecimal(defective));
      ps.setBigDecimal(21, BigDecimal.ZERO);
      ps.setBigDecimal(22, new BigDecimal(defective));
      ps.setObject(23, userA);
      ps.setObject(24, completedAt);
      ps.executeUpdate();
    }
    return id;
  }

  /** 种一条 11.5.2.1 的供应商引用镜像（索赔单的 FK 目标）。 */
  static UUID seedSupplierRef(UUID tenant, UUID supplier, String code, String name) throws Exception {
    UUID id = UUID.randomUUID();
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into quality_supplier_ref(id,tenant_id,supplier_ref_id,supplier_code,"
                    + " supplier_name,ref_source,source_reference,ref_status,created_by)"
                    + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,'TENANT_MAINTAINED',"
                    + "'TEST','ACTIVE',?::uuid)"
                    + " on conflict (tenant_id,supplier_ref_id) do nothing")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setObject(3, supplier);
      ps.setString(4, code);
      ps.setString(5, name);
      ps.setObject(6, userA);
      ps.executeUpdate();
    }
    return id;
  }

  /**
   * 种一张 11.2.3.3 的供应商索赔（售后配合度的原料）。
   *
   * @param withSupplierNegotiation 是否补一条供应商侧的协商记录（配合度的分子）
   */
  static UUID seedClaim(
      UUID tenant, UUID supplier, String code, String name, OffsetDateTime submittedAt,
      boolean withSupplierNegotiation)
      throws Exception {
    seedSupplierRef(tenant, supplier, code, name);
    UUID id = UUID.randomUUID();
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into supplier_claim(id,tenant_id,claim_no,supplier_ref_id,supplier_code,"
                    + " supplier_name,claim_reason_code,claim_currency,claimed_amount,claimable_base_amount,"
                    + " claim_status,offset_status,created_by,submitted_by,submitted_at,updated_at)"
                    + " values (?::uuid,?::uuid,?::varchar,?::uuid,?::varchar,?::varchar,'QUALITY_DEFECT',"
                    + " 'USD',?::numeric,?::numeric,'SUBMITTED','NOT_APPLIED',?::uuid,?::uuid,?::timestamptz,now())")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setString(3, "CL-TEST-" + id.toString().substring(0, 8));
      ps.setObject(4, supplier);
      ps.setString(5, code);
      ps.setString(6, name);
      ps.setBigDecimal(7, new BigDecimal("100"));
      ps.setBigDecimal(8, new BigDecimal("100"));
      ps.setObject(9, userA);
      ps.setObject(10, userA);
      ps.setObject(11, submittedAt);
      ps.executeUpdate();
    }
    if (withSupplierNegotiation) {
      try (var c = admin();
          var ps =
              c.prepareStatement(
                  "insert into supplier_claim_negotiation(id,tenant_id,claim_id,negotiation_seq,"
                      + " negotiation_type,participant_role,negotiation_note,occurred_at,recorded_by,created_at)"
                      + " values (?::uuid,?::uuid,?::uuid,1,'SUPPLIER_RESPONSE','SUPPLIER',?::varchar,"
                      + "?::timestamptz,?::uuid,now())")) {
        ps.setObject(1, UUID.randomUUID());
        ps.setObject(2, tenant);
        ps.setObject(3, id);
        ps.setString(4, "供应商答复：同意赔付");
        ps.setObject(5, submittedAt.plusHours(2));
        ps.setObject(6, userA);
        ps.executeUpdate();
      }
    }
    return id;
  }

  /**
   * 在 catalog 替身里种一条有效报价。
   *
   * @param tiers 起订量 → 单价的阶梯（从大到小或从小到大都能用）
   */
  static void seedQuote(
      UUID skuId, UUID supplierRoleId, String currency, List<Object[]> tiers) {
    var tierList = new ArrayList<Map<String, Object>>();
    for (Object[] tier : tiers) {
      var m = new LinkedHashMap<String, Object>();
      m.put("minQuantity", new BigDecimal(tier[0].toString()));
      m.put("unitPrice", new BigDecimal(tier[1].toString()));
      tierList.add(m);
    }
    var row = new LinkedHashMap<String, Object>();
    row.put("supplierRoleId", supplierRoleId.toString());
    row.put("skuId", skuId.toString());
    row.put("currency", currency);
    row.put("tiers", tierList);
    quoteRows.computeIfAbsent(skuId, k -> new ArrayList<>()).add(row);
  }
}
