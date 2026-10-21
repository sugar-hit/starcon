package com.starcon.warehouse;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 8.2.2.2 待检处理的测试基座：真实 PostgreSQL 16 + 强制 RLS + 两个本地桩服务。
 *
 * <p>两个桩：
 *
 * <ul>
 *   <li><b>identity 桩</b>（{@code /internal/access}）：与既有 warehouse 集成测试同构 ——
 *       返回调用方的身份与租户，租户<b>不</b>来自任何客户端头。
 *   <li><b>trade 桩</b>（{@code /trade/quality-inspections/**}）：6.4.2.1 质检单在
 *       <b>trade 服务</b>，本模块经 {@link QualityInspectionTradePort} 与它交互。
 *       桩必须能分别模拟「建单成功」「质检单还没完结」「上游坏了（500）」三种情况 ——
 *       只模拟「成功」的话，「取不到 ≠ 没有质检单」这条纪律就测不出来。
 * </ul>
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class PendingInspectionTestBase {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer trade;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  static final List<String> allPermissions =
      Stream.of("pending-inspection")
          .flatMap(
              p ->
                  Stream.of("read", "create", "update", "delete")
                      .map(op -> "warehouse." + p + "." + op))
          .toList();

  /** trade 桩里已建好的质检单（id → 状态）。 */
  static final Map<UUID, Map<String, Object>> tradeOrders = new ConcurrentHashMap<>();

  /** trade 桩：建单时返回的 HTTP 状态码（默认 200）。改成 500 模拟「上游坏了」。 */
  static volatile int tradeCreateStatus = 200;

  /** trade 桩：质检单的状态（默认 COMPLETED；改成 DRAFT 模拟「还没完结」）。 */
  static volatile String tradeOrderStatus = "COMPLETED";

  /** trade 桩：不合格量（默认 0；调大它可以测「部分合格」）。 */
  static volatile BigDecimal tradeDefective = BigDecimal.ZERO;

  /** trade 桩：让步接收量（默认 0）。 */
  static volatile BigDecimal tradeConcession = BigDecimal.ZERO;

  /**
   * trade 桩：三量的偏移量（默认 0）。
   *
   * <p>非 0 时合格量会被加上这个偏移，于是「合格 + 不合格 + 让步 ≠ 批量」——
   * 用来证明「结论与库存不是同一批货」时仓库这一侧会拒。
   */
  static volatile BigDecimal tradeQuantityDelta = BigDecimal.ZERO;

  /** trade 桩收到的建单请求体（断言「我们真的把批次与来源单报上去了」）。 */
  static final Map<String, Object> lastCreateBody = new ConcurrentHashMap<>();

  /** 质检模板（trade 侧）。 */
  static final UUID TEMPLATE = UUID.nameUUIDFromBytes("starcon-pending-inspection-template".getBytes());

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-pending-inspection-" + tenant + "-" + ordinal).getBytes());
  }

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

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        s.execute("create role warehouse_app login password 'test-only' nosuperuser nobypassrls");
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
      actors.put("Bearer tenant-a", actor(user, a, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, List.of(grant("ALL", allPermissions))));

      // trade 桩：6.4.2.1 质检单。
      trade = HttpServer.create(new InetSocketAddress(0), 0);
      trade.createContext(
          "/trade/quality-inspections",
          e -> {
            var path = e.getRequestURI().getPath();
            var method = e.getRequestMethod();
            if (path.endsWith("/templates")) {
              respond(
                  e,
                  200,
                  Map.of(
                      "rows",
                      List.of(
                          Map.of(
                              "id",
                              TEMPLATE.toString(),
                              "templateCode",
                              "TPL-1",
                              "templateName",
                              "来料质检模板",
                              "inspectionType",
                              "INCOMING",
                              "inspectionTypeLabel",
                              "来料质检")),
                      "total",
                      1,
                      "page",
                      0,
                      "size",
                      100));
              return;
            }
            if ("POST".equals(method) && path.endsWith("/quality-inspections")) {
              if (tradeCreateStatus != 200) {
                respond(e, tradeCreateStatus, Map.of("message", "trade boom"));
                return;
              }
              var body = json.readValue(e.getRequestBody().readAllBytes(), Map.class);
              lastCreateBody.clear();
              lastCreateBody.putAll(body);
              var lot = new BigDecimal(String.valueOf(body.get("lotSize")));
              UUID id = UUID.randomUUID();
              String no = "QI-20261021-" + id.toString().substring(0, 8);
              tradeOrders.put(id, Map.of("id", id, "inspectionNo", no, "lotSize", lot));
              respond(
                  e,
                  200,
                  Map.of(
                      "id", id.toString(),
                      "inspectionNo", no,
                      "status", "DRAFT",
                      "lotSize", lot,
                      "receiptCode", body.get("receiptCode"),
                      "receiptLineId", body.get("receiptLineId")));
              return;
            }
            if ("POST".equals(method) && path.endsWith("/cancel")) {
              respond(e, 200, Map.of("cancelled", true));
              return;
            }
            if ("GET".equals(method)) {
              var id = path.substring(path.lastIndexOf('/') + 1);
              UUID orderId;
              try {
                orderId = UUID.fromString(id);
              } catch (IllegalArgumentException ex) {
                respond(e, 404, Map.of("message", "not found"));
                return;
              }
              var order = tradeOrders.get(orderId);
              if (order == null) {
                respond(e, 404, Map.of("message", "not found"));
                return;
              }
              var lot = (BigDecimal) order.get("lotSize");
              var defective = tradeDefective.min(lot);
              var concession = tradeConcession.min(lot.subtract(defective));
              var qualified = lot.subtract(defective).subtract(concession).add(tradeQuantityDelta);
              var body = new java.util.LinkedHashMap<String, Object>();
              body.put("id", orderId.toString());
              body.put("inspectionNo", order.get("inspectionNo"));
              body.put("status", tradeOrderStatus);
              body.put(
                  "result",
                  "COMPLETED".equals(tradeOrderStatus)
                      ? (defective.signum() == 0 ? "PASSED" : "PARTIAL")
                      : null);
              body.put("lotSize", lot);
              body.put("qualifiedQuantity", qualified);
              body.put("defectiveQuantity", defective);
              body.put("concessionQuantity", concession);
              body.put("sellableQuantity", qualified.add(concession));
              body.put("nonSellableQuantity", defective);
              body.put("receiptCode", order.get("receiptCode"));
              body.put("receiptLineId", order.get("receiptLineId"));
              body.put("disposition", "SELLABLE");
              body.put("dispositionStatus", "UNAVAILABLE");
              respond(e, 200, body);
              return;
            }
            respond(e, 404, Map.of("message", "not found"));
          });
      identity.start();
      trade.start();
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    try {
                      identity.stop(0);
                    } catch (RuntimeException ignored) {
                      // 停不干净也不影响测试结论。
                    }
                    try {
                      trade.stop(0);
                    } catch (RuntimeException ignored) {
                      // 同上。
                    }
                    try {
                      pg.stop();
                    } catch (RuntimeException ignored) {
                      // 同上：容器最终由 ryuk 回收。
                    }
                  }));
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static void respond(com.sun.net.httpserver.HttpExchange e, int code, Object body) {
    try {
      byte[] bytes = json.writeValueAsBytes(body);
      e.getResponseHeaders().add("Content-Type", "application/json");
      e.sendResponseHeaders(code, bytes.length);
      e.getResponseBody().write(bytes);
      e.close();
    } catch (Exception ex) {
      e.close();
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "warehouse_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://localhost:" + trade.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired PendingInspectionService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired AvailableQuantityService available;

  AccessService.Access actorA;
  AccessService.Access actorB;

  // 夹具 id（按租户派生）
  final UUID leA = idFor(a, 1),
      skuA = idFor(a, 2),
      skuB = idFor(a, 3),
      whA = idFor(a, 4),
      qSellA = idFor(a, 5),
      qPendingA = idFor(a, 6),
      qDefA = idFor(a, 7),
      locQuarantineA = idFor(a, 8),
      locStorageA = idFor(a, 9),
      locDefectiveA = idFor(a, 10),
      lotA = idFor(a, 11),
      receiptA = idFor(a, 12),
      receiptLineA = idFor(a, 13),
      balancePendingA = idFor(a, 14),
      bucketPendingA = idFor(a, 15);
  final UUID leB = idFor(b, 1),
      skuB2 = idFor(b, 2),
      whB = idFor(b, 4),
      qSellB = idFor(b, 5),
      qPendingB = idFor(b, 6),
      qDefB = idFor(b, 7),
      locQuarantineB = idFor(b, 8),
      locStorageB = idFor(b, 9),
      locDefectiveB = idFor(b, 10),
      lotB = idFor(b, 11),
      receiptB = idFor(b, 12),
      receiptLineB = idFor(b, 13),
      balancePendingB = idFor(b, 14),
      bucketPendingB = idFor(b, 15);

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    tradeOrders.clear();
    lastCreateBody.clear();
    tradeCreateStatus = 200;
    tradeOrderStatus = "COMPLETED";
    tradeDefective = BigDecimal.ZERO;
    tradeConcession = BigDecimal.ZERO;
    tradeQuantityDelta = BigDecimal.ZERO;
    // TRUNCATE 不触发行级触发器，以超级用户连接执行可绕过 FORCE RLS ——
    // 只用于测试夹具重建，不给应用角色开这个口子。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate pending_inspection_event, pending_inspection_disposition,"
              + " pending_inspection_task, goods_receipt_event_serial, goods_receipt_event_line,"
              + " goods_receipt_event, goods_receipt_line, goods_receipt,"
              + " inventory_entry, inventory_transaction, stock_bucket, warehouse_location,"
              + " inventory_lot, inventory_balance, inventory_quality_status, warehouse, sku_ref,"
              + " legal_entity_ref restart identity cascade");
    }
    for (var tenant : List.of(a, b)) {
      access.run(
          actorFor(tenant),
          "warehouse.pending-inspection.read",
          () -> {
            sql.execute(
                "insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 1),
                tenant,
                "LE-1",
                "主体一");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 2),
                tenant,
                "SKU-A",
                "商品一");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 3),
                tenant,
                "SKU-B",
                "商品二");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone)"
                    + " values(?,?,?,?,?,?)",
                idFor(tenant, 4),
                tenant,
                "WH-SH",
                "上海仓",
                "CN",
                "Asia/Shanghai");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 5),
                tenant,
                "SELLABLE",
                "可售",
                "SELLABLE");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 6),
                tenant,
                "PENDING",
                "待检",
                "PENDING_INSPECTION");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 7),
                tenant,
                "DEFECTIVE",
                "残次",
                "DEFECTIVE");
            // 待检库位（不可拣：待检品发不出去）
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,"
                    + "status,pickable) values(?,?,?,?,?,'QUARANTINE','ACTIVE',false)",
                idFor(tenant, 8),
                tenant,
                idFor(tenant, 4),
                "QC-01",
                "待检位");
            // 正式存储库位（可拣）
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,"
                    + "status,pickable) values(?,?,?,?,?,'STORAGE','ACTIVE',true)",
                idFor(tenant, 9),
                tenant,
                idFor(tenant, 4),
                "A-01",
                "存储一");
            // 残次库位
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,"
                    + "status,pickable) values(?,?,?,?,?,'DEFECTIVE','ACTIVE',false)",
                idFor(tenant, 10),
                tenant,
                idFor(tenant, 4),
                "D-01",
                "残次位");
            sql.execute(
                "insert into inventory_lot(id,tenant_id,sku_id,lot_code,status)"
                    + " values(?,?,?,?,'ACTIVE')",
                idFor(tenant, 11),
                tenant,
                idFor(tenant, 2),
                "LOT-1");
            // 8.2.1.1 收货单与收货明细行：待检库存的来源
            sql.execute(
                "insert into goods_receipt(id,tenant_id,receipt_code,source_type,source_ref_code,"
                    + "warehouse_id,legal_entity_id,status,created_by)"
                    + " values(?,?,?,'TRANSFER',?,?,?,'RECEIVED',?)",
                idFor(tenant, 12),
                tenant,
                "GR-1",
                "TR-1",
                idFor(tenant, 4),
                idFor(tenant, 1),
                user);
            sql.execute(
                "insert into goods_receipt_line(id,tenant_id,receipt_id,sku_id,quality_status_id,"
                    + "expected_quantity,received_quantity) values(?,?,?,?,?,?,?)",
                idFor(tenant, 13),
                tenant,
                idFor(tenant, 12),
                idFor(tenant, 2),
                idFor(tenant, 6),
                new BigDecimal("100"),
                new BigDecimal("60"));
            // 待检余额行与库位明细行：60 件待检
            sql.execute(
                "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                    + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,0)",
                idFor(tenant, 14),
                tenant,
                idFor(tenant, 2),
                idFor(tenant, 4),
                idFor(tenant, 1),
                idFor(tenant, 6),
                new BigDecimal("60"));
            sql.execute(
                "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,"
                    + "location_id,lot_id,quality_status_id,balance_id,on_hand,reserved,"
                    + "last_movement_at) values(?,?,?,?,?,?,?,?,?,?,0,now())",
                idFor(tenant, 15),
                tenant,
                idFor(tenant, 1),
                idFor(tenant, 2),
                idFor(tenant, 4),
                idFor(tenant, 8),
                idFor(tenant, 11),
                idFor(tenant, 6),
                idFor(tenant, 14),
                new BigDecimal("60"));
            return null;
          });
    }
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  /** 以**应用账号**直连数据库（不是超级用户）：只有这样 RLS 才真的生效。 */
  java.sql.Connection appConnection() throws Exception {
    var c = DriverManager.getConnection(pg.getJdbcUrl(), "warehouse_app", "test-only");
    c.setAutoCommit(true);
    return c;
  }

  /**
   * 某条余额行在手量。
   *
   * <p>必须在**租户上下文**里查：RLS 策略是 {@code tenant_id = current_setting('app.current_tenant')},
   * 没有租户上下文时一行都查不到 —— 那不是「余额是 0」，是「查不到」。
   */
  BigDecimal onHand(UUID tenant, UUID balanceId) {
    return access
        .run(
            actorFor(tenant),
            "warehouse.pending-inspection.read",
            () ->
                sql.fetchOne("select on_hand from inventory_balance where id=?", balanceId)
                    .get("on_hand", BigDecimal.class));
  }

  /** 某库位上某质量状态的 bucket 在手量（0 = 没有这条 bucket）。 */
  BigDecimal bucketOnHand(UUID tenant, UUID locationId, UUID qualityStatusId) {
    return access
        .run(
            actorFor(tenant),
            "warehouse.pending-inspection.read",
            () ->
                sql.fetchOne(
                        "select coalesce(sum(on_hand),0) as q from stock_bucket"
                            + " where location_id=? and quality_status_id=?",
                        locationId,
                        qualityStatusId)
                    .get("q", BigDecimal.class));
  }
}
