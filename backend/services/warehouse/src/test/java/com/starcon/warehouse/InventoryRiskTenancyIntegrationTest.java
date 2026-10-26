package com.starcon.warehouse;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
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
 * 1.1.3.1 库存风险只读投影的**多租户隔离三断言** + 口径断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>三条断言缺一不可（AGENTS.md「多租户」）：
 *
 * <ol>
 *   <li>两租户互不可见
 *   <li>伪造 tenant header 无效
 *   <li>连接池归还后不串租户
 * </ol>
 *
 * <p>「读到零行」每一条都配一条**同样写法的正对照**读到 1 行 —— 否则「因为查询写错了所以读不到」
 * 也会显示成绿灯。
 *
 * <p>本模块只读现有表、不建表，所以隔离测试落在**端点级**：A 租户调投影端点看不到 B 租户的
 * 库存 bucket / 阈值 / 预占 / 在途。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InventoryRiskTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();

  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      Stream.of("inventory")
          .flatMap(
              p ->
                  Stream.of("read", "create", "update", "delete")
                      .map(op -> "warehouse." + p + "." + op))
          .toList();

  static final ObjectMapper JSON = new ObjectMapper();

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-inventory-risk-" + tenant + "-" + ordinal).getBytes());
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
            byte[] body = JSON.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, List.of(grant("ALL", allPermissions))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
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
    r.add("INTERNAL_KEY", () -> "test-key");
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    r.add("TRADE_URL", () -> "http://127.0.0.1:1");
  }

  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  AccessService.Access actorA;
  AccessService.Access actorB;

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate reservation_allocation, inventory_reservation, inventory_transfer,"
              + " inventory_policy, inventory_aging_policy, inventory_entry, inventory_transaction,"
              + " stock_bucket, inventory_balance, warehouse_location, inventory_lot,"
              + " inventory_quality_status, warehouse, sku_ref, legal_entity_ref restart identity cascade");
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
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone,kind)"
                    + " values(?,?,?,?,?,?,?)",
                idFor(tenant, 3),
                tenant,
                "WH-ONE",
                "一仓",
                "CN",
                "Asia/Shanghai",
                "LOCAL");
            // 第二个仓（海外仓）：在途腿的**起点**。V1 的 CHECK 要求 from ≠ to，
            // 所以头程腿必须从一个不同的仓发出。
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone,kind)"
                    + " values(?,?,?,?,?,?,?)",
                idFor(tenant, 6),
                tenant,
                "WH-OS",
                "海外仓",
                "US",
                "America/Los_Angeles",
                "OVERSEAS");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 4),
                tenant,
                "SELLABLE",
                "可售",
                "SELLABLE");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name) values(?,?,?,?,?)",
                idFor(tenant, 5),
                tenant,
                idFor(tenant, 3),
                "A-01",
                "A 区一排");
            return null;
          });
    }
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  static OffsetDateTime now() {
    return OffsetDateTime.now();
  }

  /**
   * 给某个租户落一条完整的库存事实：在手 100、预占 0、可用量 100。
   *
   * <p>同时落齐本端点要读的四类事实：bucket（可用量）、policy（阈值）、aging policy（呆滞线）、
   * reservation_allocation（活跃预占）、inventory_transfer（在途）。
   */
  void newFactsFor(UUID tenant) {
    access.run(
        actorFor(tenant),
        "warehouse.inventory.read",
        () -> {
          UUID balanceId = UUID.randomUUID();
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,?)",
              balanceId,
              tenant,
              idFor(tenant, 2),
              idFor(tenant, 3),
              idFor(tenant, 1),
              idFor(tenant, 4),
              new BigDecimal("100"),
              BigDecimal.ZERO);
          sql.execute(
              "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,location_id,"
                  + "quality_status_id,balance_id,on_hand,reserved,last_movement_at,created_at)"
                  + " values(?,?,?,?,?,?,?,?,?,0::numeric,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              idFor(tenant, 1),
              idFor(tenant, 2),
              idFor(tenant, 3),
              idFor(tenant, 5),
              idFor(tenant, 4),
              balanceId,
              new BigDecimal("100"),
              now(),
              now());
          // 阈值：SKU 档，最低保有量 120（高于在手量 100 → 命中缺货）、最高保有量 1000（不命中超储）。
          sql.execute(
              "insert into inventory_policy(id,tenant_id,scope_type,scope_key,min_on_hand,max_on_hand,"
                  + "reorder_point,safety_stock) values(?,?,'SKU',?,?,?,?,?)",
              UUID.randomUUID(),
              tenant,
              idFor(tenant, 2).toString(),
              new BigDecimal("120"),
              new BigDecimal("1000"),
              new BigDecimal("150"),
              new BigDecimal("10"));
          // 呆滞线：逐 SKU 一条。
          sql.execute(
              "insert into inventory_aging_policy(id,tenant_id,sku_id,slow_moving_days,obsolete_days)"
                  + " values(?,?,?,30,60)",
              UUID.randomUUID(),
              tenant,
              idFor(tenant, 2),
              30,
              60);
          // 活跃预占：HELD 的分配明细 150 件 > 可用量 100 → 命中超卖风险。
          UUID reservationId = UUID.randomUUID();
          sql.execute(
              "insert into inventory_reservation(id,tenant_id,reservation_no,source_system,source_type,"
                  + "legal_entity_id,sku_id,requested_quantity,status,requested_at,held_at)"
                  + " values(?,?,?,'TRADE','SALES_ORDER',?,?,?,'HELD',?::timestamptz,?::timestamptz)",
              reservationId,
              tenant,
              "RSV-" + tenant.toString().substring(0, 8),
              idFor(tenant, 1),
              idFor(tenant, 2),
              new BigDecimal("150"),
              now(),
              now());
          sql.execute(
              "insert into reservation_allocation(id,tenant_id,reservation_id,warehouse_id,"
                  + "allocated_quantity,status,allocated_at) values(?,?,?,?,?,'HELD',?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              reservationId,
              idFor(tenant, 3),
              new BigDecimal("150"),
              now());
          // 在途：头程 40 件，目标是本仓。
          sql.execute(
              "insert into inventory_transfer(id,tenant_id,sku_id,legal_entity_id,from_warehouse_id,"
                  + "to_warehouse_id,quality_status_id,quantity,status,shipped_at,transit_kind)"
                  + " values(?,?,?,?,?,?,?,?,'IN_TRANSIT',?::timestamptz,'FIRST_MILE')",
              UUID.randomUUID(),
              tenant,
              idFor(tenant, 2),
              idFor(tenant, 1),
              idFor(tenant, 6),
              idFor(tenant, 3),
              idFor(tenant, 4),
              new BigDecimal("40"),
              now());
          return null;
        });
  }

  // ================================================================ 三断言

  @Test
  void 断言一_两租户互不可见() throws Exception {
    newFactsFor(a);
    newFactsFor(b);

    // 正对照：A 看到自己那一行（证明下面读到 0 行不是因为查询写错了）
    http.perform(
            get("/warehouse/inventory-risk/projection")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.rows[0].warehouseId").value(idFor(a, 3).toString()))
        .andExpect(jsonPath("$.rows[0].skuId").value(idFor(a, 2).toString()));

    // A 按 B 的仓过滤 → 读不到
    http.perform(
            get("/warehouse/inventory-risk/projection")
                .header("Authorization", "Bearer tenant-a")
                .param("warehouseId", idFor(b, 3).toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0))
        .andExpect(jsonPath("$.rows.length()").value(0));

    // A 按 B 的 SKU 过滤 → 读不到
    http.perform(
            get("/warehouse/inventory-risk/projection")
                .header("Authorization", "Bearer tenant-a")
                .param("skuId", idFor(b, 2).toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));

    // B 那边同理
    http.perform(
            get("/warehouse/inventory-risk/projection")
                .header("Authorization", "Bearer tenant-b"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.rows[0].warehouseId").value(idFor(b, 3).toString()));
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    newFactsFor(a);
    newFactsFor(b);

    // 身份是 A，伪造 X-Tenant-Id 指向 B → 只能看到 A 的
    http.perform(
            get("/warehouse/inventory-risk/projection")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.rows[0].warehouseId").value(idFor(a, 3).toString()));

    // 身份是 B，伪造指向 A → 只能看到 B 的
    http.perform(
            get("/warehouse/inventory-risk/projection")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.rows[0].warehouseId").value(idFor(b, 3).toString()));

    // 伪造头也不能让 A 读到 B 的仓
    http.perform(
            get("/warehouse/inventory-risk/projection")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString())
                .param("warehouseId", idFor(b, 3).toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    newFactsFor(a);
    newFactsFor(b);
    // 池大小为 1，同一条连接被反复借还：交替访问两个租户 20 轮。
    for (int i = 0; i < 20; i++) {
      assertThat(countProjections(a)).isEqualTo(1);
      assertThat(countProjections(b)).isEqualTo(1);
      assertThat(countProjections(a)).isEqualTo(1);
      assertThat(countProjections(b)).isEqualTo(1);
    }
    // 逐张表确认 RLS 是真的开着（不是靠应用层拼 tenant_id 糊出来的）
    for (var table :
        List.of(
            "stock_bucket",
            "inventory_policy",
            "inventory_aging_policy",
            "reservation_allocation",
            "inventory_transfer")) {
      var row =
          access.run(
              actorA,
              "warehouse.inventory.read",
              () ->
                  sql.fetchOne(
                      "select relforcerowsecurity from pg_class where relname = ? and relnamespace"
                          + " = 'public'::regnamespace",
                      table));
      assertThat(row.get("relforcerowsecurity", Boolean.class)).as(table).isTrue();
    }
  }

  long countProjections(UUID tenant) {
    return access.run(
        actorFor(tenant),
        "warehouse.inventory.read",
        () ->
            sql.fetchOne(
                    "select count(*) from stock_bucket where tenant_id = ?", tenant)
                .get(0, Long.class));
  }

  // ================================================================ 口径断言

  @Test
  void 口径_可用量阈值库龄活跃预占在途都按既有模块的口径() throws Exception {
    newFactsFor(a);

    http.perform(
            get("/warehouse/inventory-risk/projection")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[0].available").value(100))
        .andExpect(jsonPath("$.rows[0].onHand").value(100))
        .andExpect(jsonPath("$.rows[0].reserved").value(0))
        // 阈值来自 inventory_policy 的 SKU 档，档位随行返回
        .andExpect(jsonPath("$.rows[0].minOnHand").value(120))
        .andExpect(jsonPath("$.rows[0].maxOnHand").value(1000))
        .andExpect(jsonPath("$.rows[0].thresholdScopeType").value("SKU"))
        // 呆滞线来自 inventory_aging_policy（不是兜底默认 90）
        .andExpect(jsonPath("$.rows[0].obsoleteDays").value(60))
        .andExpect(jsonPath("$.rows[0].obsoleteThresholdSource").value("POLICY"))
        // 活跃预占 = 分配明细 remaining（HELD）
        .andExpect(jsonPath("$.rows[0].activeReservedQuantity").value(150))
        .andExpect(jsonPath("$.rows[0].activeReservationCount").value(1))
        // 在途 = 仍在途的腿的 quantity 之和
        .andExpect(jsonPath("$.rows[0].inTransitQuantity").value(40))
        .andExpect(jsonPath("$.rows[0].inTransitFirstMileQuantity").value(40))
        .andExpect(jsonPath("$.rows[0].inTransitLegCount").value(1))
        // 评估基准日由数据库时钟给出（不是浏览器本地时区）
        .andExpect(jsonPath("$.asOfDate").isNotEmpty())
        .andExpect(jsonPath("$.warehouseKindOptions[0].value").value("LOCAL"));
  }

  @Test
  void 能力声明_如实报出未接通的输入及其归属模块() throws Exception {
    http.perform(get("/warehouse/inventory-risk/capabilities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stockProjectionAvailable").value(true))
        .andExpect(jsonPath("$.thresholdAvailable").value(true))
        .andExpect(jsonPath("$.agingAvailable").value(true))
        .andExpect(jsonPath("$.reservationAvailable").value(true))
        .andExpect(jsonPath("$.inTransitAvailable").value(true))
        // 日均销在 trade、采购到货在 trade：本服务不跨库读，如实报未接通
        .andExpect(jsonPath("$.salesVelocityAvailable").value(false))
        .andExpect(jsonPath("$.salesVelocityOwnerModule").value("6.2.1.1 / trade"))
        .andExpect(jsonPath("$.purchaseArrivalAvailable").value(false))
        .andExpect(jsonPath("$.purchaseArrivalOwnerModule").value("6.3.2.2 / trade"));
  }
}
