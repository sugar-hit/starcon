package com.starcon.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * 6.2.2.2 需求合并与批量的**共享底座**。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身</b>。跨服务一律走**真的本地 HTTP
 * 服务**（identity 的权限端点、warehouse 的仓 / 法人参照、catalog 的供应商清单）——
 * 替身只按上游契约形状返回数，绝不替业务逻辑做决定。
 *
 * <p><b>上游事实怎么来的</b>：6.2.2.1 的采购申请单与申请行直接种进**它们自己的表** ——
 * 本模块是它们的下游消费者，读它们的事实表正是本模块要证明的事；
 * 种数据用迁移用户（超级用户），生产运行角色 {@code trade_app} 对那些表只有上游授予的权限，
 * 本模块**不新增**任何权限。
 *
 * <p>🔴 种上游事实时**必须按状态机走**：6.2.2.1 的 DB 触发器
 * {@code guard_purchase_request_line_draft_only} 只允许在**草稿期**写申请行，
 * {@code guard_purchase_request_status} 只允许 草稿→待审/已批→已转单 的合法流转。
 * 所以本底座先建**草稿**单 + 写行，再逐步推进状态 —— 直插一个「已批」单会被 DB 拒。
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class PurchaseDemandMergeFixture {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenantA = UUID.randomUUID(), tenantB = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity, warehouse, catalog;
  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .enable(
              com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  // ---- 参照数据 ----
  static final UUID warehouseId = UUID.randomUUID();
  static final UUID legalEntityId = UUID.randomUUID();
  /** 供应商甲（catalog 的 SUPPLIER 角色 id）。 */
  static final UUID supplierA = UUID.randomUUID();
  /** 供应商乙 —— 用来证明「跨供应商不合并」。 */
  static final UUID supplierB = UUID.randomUUID();
  /** 第三个供应商（只出现在 catalog 清单里，不出现在任何需求行上）。 */
  static final UUID supplierC = UUID.randomUUID();

  static final LocalDate ARRIVAL = LocalDate.of(2026, 12, 5);

  static final List<String> allPermissions =
      List.of("trade.demand-merge.read", "trade.demand-merge.update");

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static void write(HttpServer server, HttpExchange e, int code, Object body) throws IOException {
    byte[] bytes = json.writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(code, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
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

      // ---- identity：权限替身（只要 /internal/access） ----
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
      actors.put("Bearer tenant-a", actor(user, tenantA, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, tenantB, List.of(grant("ALL", allPermissions))));
      identity.start();

      // ---- warehouse：仓 / 法人参照 ----
      warehouse = HttpServer.create(new InetSocketAddress(0), 0);
      warehouse.createContext(
          "/warehouse/warehouses",
          e -> {
            if (tenantOf(e.getRequestHeaders().getFirst("Authorization")) == null) {
              write(warehouse, e, 401, Map.of("message", "未认证"));
              return;
            }
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
                        "status", "ACTIVE")));
          });
      warehouse.createContext(
          "/warehouse/legal-entities",
          e -> {
            if (tenantOf(e.getRequestHeaders().getFirst("Authorization")) == null) {
              write(warehouse, e, 401, Map.of("message", "未认证"));
              return;
            }
            write(
                warehouse,
                e,
                200,
                List.of(
                    Map.of(
                        "id", legalEntityId.toString(),
                        "code", "US-LLC",
                        "name", "美国子公司",
                        "status", "ACTIVE")));
          });
      warehouse.start();

      // ---- catalog：供应商清单（6.1.1.x 的只读接口） ----
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/supply/vendors",
          e -> {
            if (tenantOf(e.getRequestHeaders().getFirst("Authorization")) == null) {
              write(catalog, e, 401, Map.of("message", "未认证"));
              return;
            }
            String path = e.getRequestURI().getPath();
            if (path.equals("/catalog/supply/vendors")) {
              write(
                  catalog,
                  e,
                  200,
                  Map.of(
                      "items",
                      List.of(vendorRow(supplierA, "SUP-001", "深圳华强电子"),
                          vendorRow(supplierB, "SUP-002", "义乌小商品市场"),
                          vendorRow(supplierC, "SUP-003", "宁波通达五金")),
                      "total",
                      3,
                      "page",
                      0,
                      "size",
                      200));
              return;
            }
            String roleId = path.substring("/catalog/supply/vendors/".length());
            var found = vendors().stream().filter(v -> v.get("roleId").toString().equals(roleId)).findFirst();
            if (found.isEmpty()) {
              write(catalog, e, 404, Map.of("message", "该供应商不存在"));
              return;
            }
            write(catalog, e, 200, Map.of("summary", found.get()));
          });
      catalog.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static Map<String, Object> vendorRow(UUID roleId, String code, String name) {
    var row = new java.util.LinkedHashMap<String, Object>();
    row.put("roleId", roleId.toString());
    row.put("partyId", UUID.randomUUID().toString());
    row.put("partyCode", code);
    row.put("partyName", name);
    row.put("partyStatus", "ACTIVE");
    return row;
  }

  static List<Map<String, Object>> vendors() {
    var rows = new ArrayList<Map<String, Object>>();
    rows.add(vendorRow(supplierA, "SUP-001", "深圳华强电子"));
    rows.add(vendorRow(supplierB, "SUP-002", "义乌小商品市场"));
    rows.add(vendorRow(supplierC, "SUP-003", "宁波通达五金"));
    return rows;
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
    r.add("CATALOG_URL", () -> "http://localhost:" + catalog.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
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
          "truncate purchase_demand_batch_adjustment, purchase_demand_merge_unmerged,"
              + " purchase_demand_merge_line_source, purchase_demand_merge_line,"
              + " purchase_demand_merge_draft, purchase_demand_merge_batch,"
              + " purchase_request_conversion, purchase_request_adjustment, purchase_request_line,"
              + " purchase_request cascade");
    }
  }

  /**
   * 一条需求行的种子。
   *
   * @param supplier 供应商（null = 还没选供应商）
   * @param unitPrice 预估单价（null = 没填）
   */
  record LineSpec(
      String skuCode,
      String quantity,
      String unitPrice,
      UUID supplier,
      String supplierCode,
      String supplierName,
      LocalDate arrival) {

    /** 常用形状：带供应商、带单价、带到货日。 */
    static LineSpec of(String skuCode, String quantity, String unitPrice, UUID supplier) {
      return new LineSpec(
          skuCode,
          quantity,
          unitPrice,
          supplier,
          supplier == null ? null : "SUP-" + supplier.toString().substring(0, 4),
          supplier == null ? null : "供应商" + supplier.toString().substring(0, 4),
          ARRIVAL);
    }
  }

  /**
   * 种一张 6.2.2.1 的采购申请单 + 它的需求行，并按状态机推进到目标状态。
   *
   * <p>🔴 **顺序不能反**：6.2.2.1 的 DB 触发器 {@code guard_purchase_request_line_draft_only}
   * 只允许在**草稿期**写申请行，{@code guard_purchase_request_status} 只认合法流转
   * （草稿→待审/已批→已转单；待审→已批/已驳回）。所以必须**先建草稿单 → 写行 → 再推进状态**，
   * 直插一个「已批」单或先推进状态再写行都会被 DB 拒。
   *
   * @param status 目标状态：DRAFT / APPROVED / REJECTED / CONVERTED
   */
  static UUID seedRequestWithLines(
      UUID tenant,
      String status,
      String currency,
      UUID warehouse,
      UUID entity,
      List<LineSpec> lines)
      throws Exception {
    UUID id = UUID.randomUUID();
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into purchase_request(id,tenant_id,request_no,source_type,target_warehouse_id,"
                    + " target_warehouse_code,target_warehouse_name,legal_entity_id,legal_entity_code,"
                    + " legal_entity_name,status,note,created_by,created_at,updated_at)"
                    + " values (?::uuid,?::uuid,?::varchar,'MANUAL',?::uuid,'WH-LA-01','洛杉矶仓',"
                    + " ?::uuid,'US-LLC','美国子公司','DRAFT','上游事实',?::uuid,now(),now())")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setString(3, "PR-TEST-" + id.toString().replace("-", "").substring(0, 8));
      ps.setObject(4, warehouse == null ? warehouseId : warehouse);
      ps.setObject(5, entity == null ? legalEntityId : entity);
      ps.setObject(6, user);
      ps.executeUpdate();
    }
    int rank = 0;
    for (LineSpec spec : lines) {
      insertLine(tenant, id, spec, rank++);
    }
    advanceRequest(tenant, id, status, currency);
    return id;
  }

  /** 种一张 6.2.2.1 的采购申请单（没有行）。 */
  static UUID seedRequest(
      UUID tenant, String status, String currency, UUID warehouse, UUID entity) throws Exception {
    return seedRequestWithLines(tenant, status, currency, warehouse, entity, List.of());
  }

  /** 按 6.2.2.1 的合法流转推进申请单状态（DB 触发器只认合法流转）。 */
  static void advanceRequest(UUID tenant, UUID requestId, String status, String currency)
      throws Exception {
    if ("DRAFT".equals(status)) return;
    if ("REJECTED".equals(status)) {
      // 草稿 → 待审 → 已驳回（草稿不能直接跳已驳回）。
      setRequestStatus(tenant, requestId, "PENDING_APPROVAL", currency);
      setRequestStatus(tenant, requestId, "REJECTED", currency);
      return;
    }
    setRequestStatus(tenant, requestId, "APPROVED", currency);
    if (!"CONVERTED".equals(status)) return;
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "update purchase_request set status = 'CONVERTED', converted_at = now(),"
                    + " converted_by = ?::uuid, updated_at = now()"
                    + " where tenant_id = ?::uuid and id = ?::uuid")) {
      ps.setObject(1, user);
      ps.setObject(2, tenant);
      ps.setObject(3, requestId);
      ps.executeUpdate();
    }
  }

  /** 把申请单推到某个状态（同时设币种）。 */
  private static void setRequestStatus(
      UUID tenant, UUID requestId, String status, String currency) throws Exception {
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "update purchase_request set status = ?::varchar, currency_code = ?::varchar,"
                    + " updated_at = now() where tenant_id = ?::uuid and id = ?::uuid")) {
      ps.setString(1, status);
      ps.setString(2, currency);
      ps.setObject(3, tenant);
      ps.setObject(4, requestId);
      ps.executeUpdate();
    }
  }

  /** 写一条 6.2.2.1 的申请行（**必须**在申请单还是草稿时写）。 */
  private static void insertLine(
      UUID tenant, UUID requestId, LineSpec spec, int rank) throws Exception {
    UUID id = UUID.randomUUID();
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into purchase_request_line(id,tenant_id,request_id,sku_id,sku_code,sku_name,"
                    + " requested_quantity,unit_price,line_amount,supplier_id,supplier_code,supplier_name,"
                    + " requested_arrival_date,priority,note,sort_rank,created_at,updated_at)"
                    + " values (?::uuid,?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::numeric,"
                    + " ?::numeric,?::numeric,?::uuid,?::varchar,?::varchar,?::date,'NORMAL','',?,now(),now())")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setObject(3, requestId);
      ps.setObject(4, UUID.nameUUIDFromBytes(spec.skuCode().getBytes()));
      ps.setString(5, spec.skuCode());
      ps.setString(6, spec.skuCode() + " 名称");
      ps.setBigDecimal(7, new BigDecimal(spec.quantity()));
      if (spec.unitPrice() == null) {
        ps.setNull(8, java.sql.Types.NUMERIC);
        ps.setNull(9, java.sql.Types.NUMERIC);
      } else {
        ps.setBigDecimal(8, new BigDecimal(spec.unitPrice()));
        ps.setBigDecimal(9, new BigDecimal(spec.unitPrice()).multiply(new BigDecimal(spec.quantity())));
      }
      if (spec.supplier() == null) {
        ps.setNull(10, java.sql.Types.OTHER);
      } else {
        ps.setObject(10, spec.supplier());
      }
      ps.setString(11, spec.supplierCode() == null ? "" : spec.supplierCode());
      ps.setString(12, spec.supplierName() == null ? "" : spec.supplierName());
      if (spec.arrival() == null) {
        ps.setNull(13, java.sql.Types.DATE);
      } else {
        ps.setDate(13, java.sql.Date.valueOf(spec.arrival()));
      }
      ps.setInt(14, rank);
      ps.executeUpdate();
    }
  }

  /** 请求体读成 Map（测试里反复用）。 */
  @SuppressWarnings("unchecked")
  static Map<String, Object> readJson(String payload) throws Exception {
    return json.readValue(payload, Map.class);
  }

  /** 取数组字段。 */
  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> listOf(Map<String, Object> payload, String field) {
    Object raw = payload.get(field);
    if (raw == null) return List.of();
    return (List<Map<String, Object>>) raw;
  }

  /** 把字符串读成 BigDecimal（避免 double）。 */
  static BigDecimal decimal(Object value) {
    if (value == null) return null;
    return new BigDecimal(String.valueOf(value));
  }

  /** 供断言用的 UTF-8 读取（避免平台默认编码差异）。 */
  static String text(byte[] bytes) {
    return new String(bytes, StandardCharsets.UTF_8);
  }
}
