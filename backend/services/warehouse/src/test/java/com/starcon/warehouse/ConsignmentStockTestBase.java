package com.starcon.warehouse;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
 * 7.7.2.2 平台托管与全托管备货的测试基座：真实 PostgreSQL 16 + 强制 RLS + 两个本地桩服务。
 *
 * <p>两个桩：
 *
 * <ul>
 *   <li><b>identity 桩</b>（{@code /internal/access}）：与既有 warehouse 集成测试同构 —— 返回调用方的
 *       身份与租户，租户**不**来自任何客户端头。
 *   <li><b>channel 桩</b>（{@code /channel/shops/{id}} 与 {@code /channel/stock-forecasts/{id}}）：
 *       本模块的两个只读上游（2.3.2.1 经营模式、10.4.2.2 备货预报）。
 *       桩必须能分别模拟「读到」「没有这条记录（404）」「上游坏了（500）」三种情况 ——
 *       只模拟「读到」的话，「读不到 ≠ 0」这条纪律就测不出来。
 * </ul>
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class ConsignmentStockTestBase {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer channel;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper json = new ObjectMapper();
  static final List<String> allPermissions =
      Stream.of("inventory")
          .flatMap(
              p ->
                  Stream.of("read", "create", "update", "delete")
                      .map(op -> "warehouse." + p + "." + op))
          .toList();

  /** 全托管店铺（2.3.2.1）。 */
  static final UUID SHOP_FULL = UUID.nameUUIDFromBytes("starcon-cs-shop-full".getBytes());

  /** 半托管店铺。 */
  static final UUID SHOP_SEMI = UUID.nameUUIDFromBytes("starcon-cs-shop-semi".getBytes());

  /** 自发货店铺 —— 不允许建平台托管备货单。 */
  static final UUID SHOP_SELF = UUID.nameUUIDFromBytes("starcon-cs-shop-self".getBytes());

  /** channel 读不出来的店铺（上游 500）。 */
  static final UUID SHOP_BROKEN = UUID.nameUUIDFromBytes("starcon-cs-shop-broken".getBytes());

  /** channel 没有这条记录的店铺（上游 404）。 */
  static final UUID SHOP_MISSING = UUID.nameUUIDFromBytes("starcon-cs-shop-missing".getBytes());

  /** channel 有的备货预报（10.4.2.2）。 */
  static final UUID FORECAST_KNOWN = UUID.nameUUIDFromBytes("starcon-cs-forecast-known".getBytes());

  /** channel 没有的备货预报。 */
  static final UUID FORECAST_MISSING = UUID.nameUUIDFromBytes("starcon-cs-forecast-missing".getBytes());

  /** channel 读不出来的备货预报（上游 500）。 */
  static final UUID FORECAST_BROKEN = UUID.nameUUIDFromBytes("starcon-cs-forecast-broken".getBytes());

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-warehouse-consignment-stock-" + tenant + "-" + ordinal).getBytes());
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

      // channel 桩：2.3.2.1 经营模式 + 10.4.2.2 备货预报。
      channel = HttpServer.create(new InetSocketAddress(0), 0);
      channel.createContext(
          "/channel/shops",
          e -> {
            var path = e.getRequestURI().getPath();
            var id = path.substring(path.lastIndexOf('/') + 1);
            UUID shopId;
            try {
              shopId = UUID.fromString(id);
            } catch (IllegalArgumentException ex) {
              respond(e, 404, Map.of("message", "not found"));
              return;
            }
            if (SHOP_SELF.equals(shopId)) {
              respond(e, 200, Map.of("id", id, "code", "SHOP-SELF", "name", "自发货店铺",
                  "operatingMode", "SELF_FULFILLED"));
              return;
            }
            if (SHOP_SEMI.equals(shopId)) {
              respond(e, 200, Map.of("id", id, "code", "SHOP-SEMI", "name", "半托管店铺",
                  "operatingMode", "SEMI_CONSIGNMENT"));
              return;
            }
            if (SHOP_BROKEN.equals(shopId)) {
              // 上游真的坏了：端口必须报 503「读不到」，而不是报一个猜出来的模式。
              respond(e, 500, Map.of("message", "channel boom"));
              return;
            }
            if (SHOP_FULL.equals(shopId)) {
              respond(e, 200, Map.of("id", id, "code", "SHOP-FULL", "name", "全托管店铺",
                  "operatingMode", "FULL_CONSIGNMENT"));
              return;
            }
            respond(e, 404, Map.of("message", "shop not found"));
          });
      channel.createContext(
          "/channel/stock-forecasts",
          e -> {
            var path = e.getRequestURI().getPath();
            var id = path.substring(path.lastIndexOf('/') + 1);
            if (FORECAST_BROKEN.toString().equals(id)) {
              // 上游真的坏了：端口必须报 503「读不到」，而不是报「没有备货需求」。
              respond(e, 500, Map.of("message", "channel boom"));
              return;
            }
            if (FORECAST_KNOWN.toString().equals(id)) {
              respond(
                  e,
                  200,
                  Map.of(
                      "forecast",
                      Map.of(
                          "id", id,
                          "forecastNo", "FC-0001",
                          "status", "SUBMITTED",
                          "expectedArrivalAt", "2026-11-01T00:00:00+08:00",
                          "totalQuantity", new BigDecimal("150"),
                          "lineCount", 2),
                      "lines",
                      List.of(
                          Map.of("skuCode", "SKU-1", "quantity", new BigDecimal("100"), "unit", "PCS"),
                          Map.of("skuCode", "SKU-2", "quantity", new BigDecimal("50"), "unit", "PCS"))));
              return;
            }
            respond(e, 404, Map.of("message", "forecast not found"));
          });
      identity.start();
      channel.start();
      // ⚠️ 容器与两个桩是**整个 JVM 共用**的（两个测试类继承本基座）。
      //    清理只能挂在 JVM 退出上 —— 若在某个 @AfterAll 里停掉，
      //    后一个测试类拿到的就是已停止的容器（"Mapped port can only be obtained
      //    after the container is started"）。
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
                      channel.stop(0);
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
    r.add("CHANNEL_URL", () -> "http://localhost:" + channel.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired ConsignmentStockService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  AccessService.Access actorA;
  AccessService.Access actorB;

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    // TRUNCATE 不触发行级触发器，以超级用户连接执行可绕过 FORCE RLS ——
    // 只用于测试夹具重建，不给应用角色开这个口子。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate consignment_stock_audit, consignment_stock_settlement_receipt,"
              + " consignment_stock_receipt, consignment_stock_order_line, consignment_stock_order,"
              + " inventory_entry, inventory_transaction, inventory_balance, stock_bucket,"
              + " warehouse_location, inventory_quality_status, warehouse, sku_ref, legal_entity_ref"
              + " restart identity cascade");
    }
    for (var tenant : List.of(a, b)) {
      access.run(
          actorFor(tenant),
          "warehouse.inventory.read",
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
                "SKU-1",
                "商品一");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 3),
                tenant,
                "SKU-2",
                "商品二");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 4),
                tenant,
                "GOOD",
                "良品",
                "SELLABLE");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
                idFor(tenant, 5),
                tenant,
                "WH-ONE",
                "一仓",
                "CN",
                "Asia/Shanghai");
            return null;
          });
    }
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  static OffsetDateTime now() {
    return OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
  }

  /** 以 +08:00 偏移构造一个时刻（东八区，正是会静默偏 −28800s 的场景）。 */
  static OffsetDateTime atEight(int dayOfMonth, int hour) {
    return OffsetDateTime.of(
        LocalDate.of(2026, 11, dayOfMonth), java.time.LocalTime.of(hour, 0), ZoneOffset.ofHours(8));
  }

  /** 以**应用账号**直连数据库（不是超级用户）：只有这样 RLS 才真的生效。 */
  java.sql.Connection appConnection() throws Exception {
    var c = DriverManager.getConnection(pg.getJdbcUrl(), "warehouse_app", "test-only");
    c.setAutoCommit(true);
    return c;
  }
}
