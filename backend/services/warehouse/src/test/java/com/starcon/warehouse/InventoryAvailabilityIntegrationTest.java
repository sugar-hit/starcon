package com.starcon.warehouse;

import static com.starcon.warehouse.WarehouseModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * 3.5.2.2 BOM 展开消费的批量可用量端点：可用/在途的聚合口径、仓库过滤同时作用在两列上、 零库存返回 0 而不是缺席、校验，以及租户隔离三断言。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。</b>
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InventoryAvailabilityIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final java.util.Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final List<String> allPermissions =
      java.util.stream.Stream.of("inventory")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "warehouse." + p + "." + op))
          .toList();

  // 参照数据 ID 按租户派生：warehouse / sku_ref 的主键是**全局** uuid，
  // 两个租户不能用同一批固定 UUID（那会让租户 B 的参照数据插入直接撞租户 A 的主键）。
  static final UUID leA = idFor(a, 1);
  static final UUID skuA1 = idFor(a, 2);
  static final UUID skuA2 = idFor(a, 3);
  static final UUID skuA3 = idFor(a, 4);
  static final UUID whSh = idFor(a, 5);
  static final UUID whSz = idFor(a, 6);
  static final UUID qSellable = idFor(a, 7);
  static final UUID qPending = idFor(a, 8);
  static final UUID qDefective = idFor(a, 9);

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-availability-" + tenant + "-" + ordinal).getBytes());
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

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("create role starcon_app nologin");
      s.execute("create role warehouse_app login password 'test-only' nosuperuser nobypassrls");
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
            byte[] body =
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions))));
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
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired InventoryAvailabilityService service;
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
          "truncate inventory_entry, inventory_transaction, inventory_transfer, inventory_balance,"
              + " inventory_policy, inventory_quality_status, warehouse, sku_ref, legal_entity_ref"
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
                "组件一");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 3),
                tenant,
                "SKU-2",
                "组件二");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
                idFor(tenant, 5),
                tenant,
                "WH-SH",
                "上海仓",
                "CN",
                "Asia/Shanghai");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
                idFor(tenant, 6),
                tenant,
                "WH-SZ",
                "深圳仓",
                "CN",
                "Asia/Shanghai");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values"
                    + "(?,?,?,?,?),(?,?,?,?,?),(?,?,?,?,?)",
                idFor(tenant, 7),
                tenant,
                "SELLABLE",
                "可售",
                "SELLABLE",
                idFor(tenant, 8),
                tenant,
                "PENDING",
                "待检",
                "PENDING_INSPECTION",
                idFor(tenant, 9),
                tenant,
                "DEFECT",
                "残次",
                "DEFECTIVE");
            return null;
          });
    }
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  /** 一行余额（租户 A）。available 是生成列 on_hand − reserved，不含在途。 */
  void balance(UUID sku, UUID warehouse, UUID quality, String onHand, String reserved) {
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,?)",
              UUID.randomUUID(),
              a,
              sku,
              warehouse,
              leA,
              quality,
              new BigDecimal(onHand),
              new BigDecimal(reserved));
          return null;
        });
  }

  /** 一行在途：从 from 仓发往 to 仓。 */
  void inTransit(UUID sku, UUID from, UUID to, String quantity) {
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into inventory_transfer(id,tenant_id,sku_id,legal_entity_id,from_warehouse_id,"
                  + "to_warehouse_id,quality_status_id,quantity,shipped_at) values(?,?,?,?,?,?,?,?,now())",
              UUID.randomUUID(),
              a,
              sku,
              leA,
              from,
              to,
              qSellable,
              new BigDecimal(quantity));
          return null;
        });
  }

  AvailabilityView query(AccessService.Access who, List<UUID> skus, UUID warehouseId) {
    return access.run(
        who,
        "warehouse.inventory.read",
        () -> service.availability(new AvailabilityQuery(skus, warehouseId)));
  }

  static BigDecimal availableOf(AvailabilityView view, UUID skuId) {
    return view.items().stream()
        .filter(i -> i.skuId().equals(skuId))
        .findFirst()
        .orElseThrow()
        .available();
  }

  static BigDecimal transitOf(AvailabilityView view, UUID skuId) {
    return view.items().stream()
        .filter(i -> i.skuId().equals(skuId))
        .findFirst()
        .orElseThrow()
        .inTransit();
  }

  // ================================================================ 聚合口径

  @Test
  void 可用量只由生成列聚合而来且不含在途() {
    balance(skuA1, whSh, qSellable, "100", "30");
    balance(skuA1, whSh, qPending, "20", "0");
    // 残次也计入 Σ available —— 口径是「生成列的聚合」，不在本端点按质量角色再分一次。
    balance(skuA1, whSh, qDefective, "5", "0");
    inTransit(skuA1, whSh, whSz, "40");

    var view = query(actorA, List.of(skuA1), null);
    // (100-30) + (20-0) + (5-0) = 95。在途 40 单独一列，**不并入 available**。
    assertThat(availableOf(view, skuA1)).isEqualByComparingTo("95");
    assertThat(transitOf(view, skuA1)).isEqualByComparingTo("40");
  }

  @Test
  void 零库存的sku返回零而不是缺席() {
    balance(skuA1, whSh, qSellable, "7", "0");
    var view = query(actorA, List.of(skuA1, skuA2, skuA3), null);
    // skuA2 / skuA3 一行库存都没有：必须回 0，否则调用方分不清「没库存」与「不存在」。
    assertThat(view.items()).hasSize(3);
    assertThat(availableOf(view, skuA2)).isEqualByComparingTo("0");
    assertThat(transitOf(view, skuA2)).isEqualByComparingTo("0");
    assertThat(availableOf(view, skuA3)).isEqualByComparingTo("0");
    assertThat(view.requestedCount()).isEqualTo(3);
  }

  // ================================================================ 仓库过滤

  @Test
  void 仓库过滤同时作用在可用与在途上且在途按收货仓() {
    balance(skuA1, whSh, qSellable, "10", "0");
    balance(skuA1, whSz, qSellable, "3", "0");
    // 一行在途：上海仓 → 深圳仓。抵扣的是**会到深圳仓来**的货。
    inTransit(skuA1, whSh, whSz, "50");

    var sz = query(actorA, List.of(skuA1), whSz);
    assertThat(availableOf(sz, skuA1)).isEqualByComparingTo("3");
    assertThat(transitOf(sz, skuA1)).isEqualByComparingTo("50");

    // 按上海仓查时，这批在途**不计入**：它正在离开上海，不是在途地飞向上海。
    var sh = query(actorA, List.of(skuA1), whSh);
    assertThat(availableOf(sh, skuA1)).isEqualByComparingTo("10");
    assertThat(transitOf(sh, skuA1)).isEqualByComparingTo("0");

    // 不限仓库 = 两仓合计，在途全量。
    var all = query(actorA, List.of(skuA1), null);
    assertThat(availableOf(all, skuA1)).isEqualByComparingTo("13");
    assertThat(transitOf(all, skuA1)).isEqualByComparingTo("50");
  }

  @Test
  void 未知仓库被显式拒绝而不是被静默忽略() {
    assertThatThrownBy(() -> query(actorA, List.of(skuA1), UUID.randomUUID()))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("仓库在本租户不存在");
  }

  // ================================================================ 校验

  @Test
  void 空集合与超量集合被拒绝() {
    assertThatThrownBy(() -> query(actorA, List.of(), null)).hasMessageContaining("请至少传一个 SKU");
    List<UUID> tooMany = new ArrayList<>();
    for (int i = 0; i < InventoryAvailabilityService.MAX_SKUS + 1; i++) {
      tooMany.add(UUID.randomUUID());
    }
    assertThatThrownBy(() -> query(actorA, tooMany, null)).hasMessageContaining("一次最多查");
  }

  // ================================================================ 端点形状

  @Test
  void 端点经租户上下文解析后返回结果() throws Exception {
    balance(skuA1, whSh, qSellable, "12", "2");
    http.perform(
            post("/warehouse/inventory/availability")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"skuIds\":[\"" + skuA1 + "\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].available").value(10));
    // 没带 Authorization：租户无从解析，必须 401 而不是按空租户放行。
    http.perform(
            post("/warehouse/inventory/availability")
                .contentType("application/json")
                .content("{\"skuIds\":[\"" + skuA1 + "\"]}"))
        .andExpect(status().isUnauthorized());
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 租户隔离三断言覆盖读写与连接池复用() throws Exception {
    // 租户 A 有余额，租户 B 的 SKU 上没有任何余额行。
    balance(skuA1, whSh, qSellable, "60", "0");
    inTransit(skuA1, whSh, whSz, "25");
    UUID theirSku = idFor(b, 2);

    // 1) 两租户互不可见：B 查自己的 SKU 拿不到 A 的数，只能是 0。
    var mine = query(actorA, List.of(skuA1), null);
    assertThat(availableOf(mine, skuA1)).isEqualByComparingTo("60");
    assertThat(transitOf(mine, skuA1)).isEqualByComparingTo("25");
    var theirs = query(actorB, List.of(theirSku), null);
    assertThat(availableOf(theirs, theirSku)).isEqualByComparingTo("0");
    assertThat(transitOf(theirs, theirSku)).isEqualByComparingTo("0");

    // 2) 伪造 X-Tenant-Id 头无效：租户只来自已认证成员关系。
    http.perform(
            post("/warehouse/inventory/availability")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType("application/json")
                .content("{\"skuIds\":[\"" + skuA1 + "\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].available").value(0));

    // 3) 连接池归还后不串租户（单连接反复交替）。
    for (int i = 0; i < 5; i++) {
      assertThat(availableOf(query(actorA, List.of(skuA1), null), skuA1))
          .isEqualByComparingTo("60");
      assertThat(availableOf(query(actorB, List.of(theirSku), null), theirSku))
          .isEqualByComparingTo("0");
    }
  }

  @Test
  void 两张被查的表都强制rls() {
    var tables =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                sql.fetch(
                    "select relname,relrowsecurity,relforcerowsecurity from pg_class"
                        + " where relname in ('inventory_balance','inventory_transfer') and relkind='r'"));
    assertThat(tables).hasSize(2);
    assertThat(tables)
        .allMatch(
            r ->
                Boolean.TRUE.equals(r.get("relforcerowsecurity", Boolean.class))
                    && Boolean.TRUE.equals(r.get("relrowsecurity", Boolean.class)));
  }
}
