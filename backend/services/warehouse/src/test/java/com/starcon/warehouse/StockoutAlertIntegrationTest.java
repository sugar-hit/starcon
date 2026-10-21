package com.starcon.warehouse;

import static com.starcon.warehouse.StockoutAlertModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 7.8.2.2 缺货与饥饿预警：两类问题分得开、「不知道」不是一个 0、建议指向具体上游对象、
 * DB 级约束、以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>夹具写法与 {@code AllocationResultIntegrationTest}（7.8.2.1）、
 * {@code AllocationPriorityIntegrationTest}（7.8.1.2）完全一致 —— 同样的 Testcontainers、
 * 同样的 fake identity server、同样的按租户派生 UUID、同样的 TRUNCATE 清场。
 *
 * <p><b>这个类最该守住的几件事</b>：
 *
 * <ol>
 *   <li><b>两类问题不混</b>：全网缺货的 SKU，它的店铺**不出现**在饥饿清单里
 *       （处置是补货，不是调优先级），而且被计进 {@code excludedByStockoutCount}。
 *   <li><b>「不知道」不是一个 0</b>：没有销量登记 ⇒ {@code demandUnits == null}
 *       且 {@code demandState == UNKNOWN}；账本里查不到记录 ⇒
 *       {@code availableQuantity == null} 且状态 {@code NO_LEDGER_RECORD}。
 *   <li><b>建议指向具体上游对象</b>：{@code allocation_floor} /
 *       {@code allocation_policy_member} / {@code allocation_shop_sales} / {@code 6.2.1.2}，
 *       而且带当前值与建议值。
 *   <li><b>时间往返逐字相等</b>：{@code detected_at} 写进去再读出来必须是同一个时刻。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class StockoutAlertIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      Stream.of("inventory")
          .flatMap(
              p ->
                  Stream.of("read", "create", "update", "delete")
                      .map(op -> "warehouse." + p + "." + op))
          .toList();

  static final UUID le = idFor(a, 1);
  static final UUID sku = idFor(a, 2);
  static final UUID wh = idFor(a, 3);
  static final UUID whTwo = idFor(a, 4);
  static final UUID quality = idFor(a, 5);
  static final UUID location = idFor(a, 6);
  static final UUID locationTwo = idFor(a, 7);

  /** 店铺是 channel 服务的实体：这里只造逻辑 id，warehouse 侧不建外键也不查它的表。 */
  static final UUID shopOne = UUID.randomUUID();

  static final UUID shopTwo = UUID.randomUUID();
  static final UUID shopThree = UUID.randomUUID();
  static final UUID shopFour = UUID.randomUUID();

  /** 三轮分摊的时刻（最近的一轮在前）。 */
  static final OffsetDateTime ALLOCATED_AT = OffsetDateTime.parse("2026-10-19T08:00:00+08:00");

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-warehouse-stockout-" + tenant + "-" + ordinal).getBytes());
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
            byte[] body =
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer read-only",
          actor(user, a, List.of(grant("ALL", List.of("warehouse.inventory.read")))));
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
  }

  @Autowired StockoutAlertService service;
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
    // TRUNCATE 不触发行级触发器，以超级用户连接执行可绕过 FORCE RLS ——
    // 只用于测试夹具重建，不给应用角色开这个口子。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate starvation_alert, stockout_alert, allocation_writeback_snapshot,"
              + " allocation_priority_resolution, allocation_floor, allocation_run_line,"
              + " allocation_run, allocation_shop_sales, allocation_policy_member,"
              + " allocation_policy, stock_bucket, inventory_balance, sellable_policy_warehouse,"
              + " sellable_policy, warehouse_location, inventory_quality_status, warehouse,"
              + " sku_ref, legal_entity_ref restart identity cascade");
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
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
                idFor(tenant, 3),
                tenant,
                "WH-1",
                "自有仓",
                "CN",
                "Asia/Shanghai");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,status,pickable)"
                    + " values(?,?,?,?,?,'STORAGE','ACTIVE',true)",
                idFor(tenant, 6),
                tenant,
                idFor(tenant, 3),
                "LOC-1",
                "库位一");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
                idFor(tenant, 4),
                tenant,
                "WH-2",
                "海外仓",
                "US",
                "America/Los_Angeles");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,status,pickable)"
                    + " values (?,?,?,?,?,'STORAGE','ACTIVE',true)",
                idFor(tenant, 7),
                tenant,
                idFor(tenant, 4),
                "LOC-2",
                "库位二");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 5),
                tenant,
                "SELLABLE",
                "可售",
                "SELLABLE");
            return null;
          });
    }
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  // ================================================================ 夹具

  /** 一档分摊策略（全租户兜底、无缓冲、FIXED_PRIORITY）+ 四家店的名册。 */
  UUID policy(AccessService.Access who) {
    UUID policyId = UUID.randomUUID();
    access.run(
        who,
        "warehouse.inventory.update",
        () -> {
          sql.execute(
              "insert into allocation_policy (effective_from,id,tenant_id,scope_type,source_system,"
                  + "reference_id,reference_label,method,buffer_mode,buffer_ratio,buffer_quantity,"
                  + "shop_type_order,sales_window_days,note,created_by_membership_id)"
                  + " values (?::timestamptz,?,?,'DEFAULT','',null,'兜底档','FIXED_PRIORITY','RATIO',0,0,"
                  + "'SELF_FULFILLED,SEMI_CONSIGNMENT,FULL_CONSIGNMENT,OVERSEAS_WAREHOUSE,LOCAL',"
                  + "30,'夹具',?)",
              ALLOCATED_AT.minusDays(1),
              policyId,
              who.tenantId(),
              user);
          int order = 0;
          for (var shop : List.of(shopOne, shopTwo, shopThree, shopFour)) {
            order++;
            sql.execute(
                "insert into allocation_policy_member (tenant_id,policy_id,source_system,"
                    + "reference_id,reference_label,shop_operating_mode,priority,share_limit,enabled)"
                    + " values (?,?,'CHANNEL',?,?,'SELF_FULFILLED',?,null,true)",
                who.tenantId(),
                policyId,
                shop,
                "店-" + order,
                order);
          }
          return null;
        });
    return policyId;
  }

  /** 给某个店铺配一档按件数的保底（7.8.1.2）。 */
  void floor(AccessService.Access who, UUID policyId, UUID shop, String quantity) {
    access.run(
        who,
        "warehouse.inventory.update",
        () -> {
          sql.execute(
              "insert into allocation_floor (id,tenant_id,policy_id,source_system,reference_id,"
                  + "reference_label,floor_mode,floor_ratio,floor_quantity,safety_quantity,"
                  + "effective_from,note,created_by_membership_id)"
                  + " values (?,?,?,'CHANNEL',?,?,'QUANTITY',0,?::numeric,0,?::timestamptz,'夹具',?)",
              UUID.randomUUID(),
              who.tenantId(),
              policyId,
              shop,
              "店",
              new BigDecimal(quantity),
              ALLOCATED_AT.minusDays(1),
              user);
          return null;
        });
  }

  /** 一条保底生效记录（7.8.1.2）：配了保底却没兑现。 */
  void priorityResolution(
      AccessService.Access who, UUID runId, UUID shop, int order, String requested, String granted) {
    access.run(
        who,
        "warehouse.inventory.update",
        () -> {
          BigDecimal req = new BigDecimal(requested);
          BigDecimal got = new BigDecimal(granted);
          BigDecimal short1 = req.subtract(got);
          sql.execute(
              "insert into allocation_priority_resolution (tenant_id,run_id,reference_id,"
                  + "source_system,reference_label,floor_order,floor_mode,floor_ratio,"
                  + "floor_quantity,safety_quantity,requested_qty,granted_qty,shortfall_qty,"
                  + "status,shortfall_reason,residual_qty,base_allocated_qty,allocated_qty,"
                  + "suppressed,suppression_reason)"
                  + " values (?,?,?,'CHANNEL','店二',?,'QUANTITY',0,5,0,?::numeric,?::numeric,?::numeric,"
                  + "?::text,?::text,0,40,0,true,'POOL_EXHAUSTED')",
              who.tenantId(),
              runId,
              shop,
              order,
              req,
              got,
              short1,
              short1.signum() == 0 ? "FULFILLED" : "UNFULFILLED",
              short1.signum() == 0 ? null : "POOL_EXHAUSTED");
          return null;
        });
  }

  /** 一次分摊。{@code quantities} 是各店分到的件数（按店顺序）。 */
  UUID run(
      AccessService.Access who,
      UUID policyId,
      UUID skuId,
      String available,
      String allocatable,
      String allocatedTotal,
      List<UUID> shops,
      List<String> quantities,
      OffsetDateTime at) {
    UUID runId = UUID.randomUUID();
    access.run(
        who,
        "warehouse.inventory.update",
        () -> {
          BigDecimal avail = new BigDecimal(available);
          BigDecimal alloc = new BigDecimal(allocatable);
          BigDecimal total = new BigDecimal(allocatedTotal);
          sql.execute(
              "insert into allocation_run (id,tenant_id,policy_id,method,sku_id,allocated_at,"
                  + "sales_window_start,sales_window_end,available_quantity,buffer_quantity,"
                  + "allocatable_quantity,allocated_total,unallocated_quantity,note,"
                  + "created_by_membership_id)"
                  + " values (?,?,?,'FIXED_PRIORITY',?,?::timestamptz,?,?,?::numeric,?::numeric,"
                  + "?::numeric,?::numeric,?::numeric,'夹具',?)",
              runId,
              who.tenantId(),
              policyId,
              skuId,
              at,
              at.toLocalDate().minusDays(30),
              at.toLocalDate(),
              avail,
              avail.subtract(alloc),
              alloc,
              total,
              alloc.subtract(total),
              user);
          int order = 0;
          for (int i = 0; i < shops.size(); i++) {
            order++;
            BigDecimal qty = new BigDecimal(quantities.get(i));
            sql.execute(
                "insert into allocation_run_line (tenant_id,run_id,source_system,reference_id,"
                    + "reference_label,shop_operating_mode,allocation_order,sales_units,sales_share,"
                    + "allocated_qty,outcome) values (?,?,'CHANNEL',?,?,'SELF_FULFILLED',?,0,null,"
                    + "?::numeric,?::text)",
                who.tenantId(),
                runId,
                shops.get(i),
                "店-" + (i + 1),
                order,
                qty,
                qty.signum() == 0 ? "DEMAND_EXHAUSTED" : "OK");
          }
          return null;
        });
    return runId;
  }

  /** 登记一条销量（7.8.1.1 的 allocation_shop_sales：店铺销量的唯一真相来源）。 */
  void sales(AccessService.Access who, UUID shop, UUID skuId, String units, int days) {
    access.run(
        who,
        "warehouse.inventory.update",
        () -> {
          sql.execute(
              "insert into allocation_shop_sales (id,tenant_id,source_system,reference_id,"
                  + "reference_label,sku_id,period_start,period_end,units_sold,"
                  + "recorded_by_membership_id) values (?,?,'CHANNEL',?,?,?,?,?,?::numeric,?)",
              UUID.randomUUID(),
              who.tenantId(),
              shop,
              "店",
              skuId,
              ALLOCATED_AT.toLocalDate().minusDays(days),
              ALLOCATED_AT.toLocalDate(),
              new BigDecimal(units),
              user);
          return null;
        });
  }

  /** 账本库存（7.1 库存账本）：balance + bucket 一起写，守恒式由延迟约束钉住。 */
  void stock(
      AccessService.Access who, UUID skuId, UUID warehouseId, UUID locationId, String onHand,
      String reserved) {
    access.run(
        who,
        "warehouse.inventory.update",
        () -> {
          UUID balanceId = UUID.randomUUID();
          sql.execute(
              "insert into inventory_balance (id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values (?,?,?,?,?,?,?::numeric,?::numeric)",
              balanceId,
              who.tenantId(),
              skuId,
              warehouseId,
              idFor(who.tenantId(), 1),
              idFor(who.tenantId(), 5),
              new BigDecimal(onHand),
              new BigDecimal(reserved));
          sql.execute(
              "insert into stock_bucket (id,tenant_id,legal_entity_id,sku_id,warehouse_id,"
                  + "location_id,lot_id,quality_status_id,balance_id,on_hand,reserved)"
                  + " values (?,?,?,?,?,?,null,?,?,?::numeric,?::numeric)",
              UUID.randomUUID(),
              who.tenantId(),
              idFor(who.tenantId(), 1),
              skuId,
              warehouseId,
              locationId,
              idFor(who.tenantId(), 5),
              balanceId,
              new BigDecimal(onHand),
              new BigDecimal(reserved));
          return null;
        });
  }

  /** 评估一次。 */
  StockoutEvaluationResult evaluate(AccessService.Access who) {
    return access.run(
        who, "warehouse.inventory.update", () -> service.evaluate(new StockoutEvaluationRequest(null, null, null, null, null, null, null)));
  }

  StarvationAlertPage starvations(AccessService.Access who) {
    return access.run(
        who,
        "warehouse.inventory.read",
        () -> service.starvations(new StarvationAlertQuery(null, null, null, null, null, null, null, 0, 100)));
  }

  StockoutAlertPage stockouts(AccessService.Access who) {
    return access.run(
        who,
        "warehouse.inventory.read",
        () -> service.stockouts(new StockoutAlertQuery(null, null, null, null, null, 0, 100)));
  }

  // ================================================================ 完整场景

  /**
   * 一套覆盖四种缺货状态与三种饥饿状态的场景。
   *
   * <ul>
   *   <li>SKU-1：货在、店二连续三轮分到 0 件（登记了需求）⇒ 饥饿；店一/店三/店四分到了 ⇒ 正常
   *   <li>SKU-2：全网没有货（账本可用量 0）⇒ 全网缺货；它的店铺**不进**饥饿清单
   *   <li>SKU-3：账本里查不到任何库存记录 ⇒ 不知道（不是 0 件）；店三对它没有销量登记 ⇒ 不知道饿不饿
   *   <li>SKU-4：账本里有货但没有销量登记 ⇒ 不知道影响面
   * </ul>
   */
  void scenario(AccessService.Access who) {
    UUID tenant = who.tenantId();
    UUID policyId = policy(who);
    floor(who, policyId, shopTwo, "5");

    // 本租户的 SKU / 仓 / 库位 —— 全部按租户派生，绝不跨租户引用。
    UUID skuOne = idFor(tenant, 2);
    UUID warehouseOne = idFor(tenant, 3);
    UUID warehouseTwo = idFor(tenant, 4);
    UUID locOne = idFor(tenant, 6);
    UUID locTwo = idFor(tenant, 7);
    UUID skuStockout = idFor(tenant, 8);
    UUID skuNoLedger = idFor(tenant, 9);
    UUID skuUnknownDemand = idFor(tenant, 10);
    UUID skuWarehouseShort = idFor(tenant, 11);

    stock(who, skuOne, warehouseOne, locOne, "300", "0");
    stock(who, skuStockout, warehouseOne, locOne, "0", "0");
    stock(who, skuUnknownDemand, warehouseOne, locOne, "10", "0");
    // SKU-5：WH-1 有 20 件、WH-2 一件没有 ⇒ 全网有货但这个仓断了。
    stock(who, skuWarehouseShort, warehouseOne, locOne, "20", "0");
    stock(who, skuWarehouseShort, warehouseTwo, locTwo, "0", "0");

    // SKU-1：三轮分摊，每轮 100 件可分。店二三轮都是 0 件。
    for (int i = 0; i < 3; i++) {
      UUID runId =
          run(
              who,
              policyId,
              skuOne,
              "100",
              "100",
              "100",
              List.of(shopOne, shopTwo, shopThree, shopFour),
              List.of("60", "0", "40", "0"),
              ALLOCATED_AT.minusDays(i));
      if (i == 0) {
        priorityResolution(who, runId, shopTwo, 2, "10", "0");
      }
    }
    sales(who, shopOne, skuOne, "100", 10);
    sales(who, shopTwo, skuOne, "30", 10);
    sales(who, shopThree, skuOne, "100", 10);
    sales(who, shopFour, skuOne, "20", 10);

    // SKU-2：全网没货。四家店这一轮全部 0 件。
    for (int i = 0; i < 3; i++) {
      run(
          who,
          policyId,
          skuStockout,
          "0",
          "0",
          "0",
          List.of(shopOne, shopTwo, shopThree, shopFour),
          List.of("0", "0", "0", "0"),
          ALLOCATED_AT.minusDays(i));
    }
    sales(who, shopOne, skuStockout, "100", 10);

    // SKU-3：分摊过、但账本里查不到任何库存记录。
    for (int i = 0; i < 2; i++) {
      run(
          who,
          policyId,
          skuNoLedger,
          "50",
          "50",
          "0",
          List.of(shopOne, shopThree),
          List.of("0", "0"),
          ALLOCATED_AT.minusDays(i));
    }

    // SKU-4：账本里有 10 件，但没有销量登记。
    run(
        who,
        policyId,
        skuUnknownDemand,
        "10",
        "10",
        "10",
        List.of(shopOne),
        List.of("10"),
        ALLOCATED_AT);

    // SKU-5：分摊过、登记了需求，但 WH-2 一件没有。
    run(
        who,
        policyId,
        skuWarehouseShort,
        "20",
        "20",
        "20",
        List.of(shopOne),
        List.of("20"),
        ALLOCATED_AT);
    sales(who, shopOne, skuWarehouseShort, "50", 10);
  }

  // ================================================================ 两类问题分得开

  @Test
  void 饥饿与缺货分别落进各自的表不混在一起() {
    scenario(actorA);
    var result = evaluate(actorA);

    var starvations = starvations(actorA);
    var stockouts = stockouts(actorA);

    // 饥饿表里只有 SKU-1 / SKU-3 的行（SKU-2 全网缺货 ⇒ 被排除）。
    assertThat(starvations.rows()).isNotEmpty();
    assertThat(starvations.rows())
        .allSatisfy(
            row -> {
              assertThat(row.skuId()).isNotEqualTo(idFor(a, 8));
              assertThat(row.alertState()).isNotEqualTo(StockoutState.NETWORK_STOCKED_OUT);
            });
    // 缺货表里**没有** SKU-1 的饥饿行 —— 两张表各自只装自己那类问题。
    assertThat(stockouts.rows())
        .allSatisfy(row -> assertThat(row.skuId()).isNotEqualTo(sku));
    assertThat(result.excludedByStockoutCount()).isPositive();
  }

  @Test
  void 全网缺货的店铺被排除在饥饿清单之外并被计数() {
    scenario(actorA);
    var result = evaluate(actorA);

    // SKU-2 全网缺货 ⇒ 四家店的组合（SKU × 店铺）全部被排除（而不是被当成饥饿）。
    assertThat(result.excludedByStockoutCount()).isEqualTo(4);
    assertThat(starvations(actorA).rows())
        .noneSatisfy(row -> assertThat(row.skuId()).isEqualTo(idFor(a, 8)));
  }

  // ================================================================ 饥饿判定

  @Test
  void 饥饿判定真的用到销量保底与分摊事实() {
    scenario(actorA);
    evaluate(actorA);

    var row =
        starvations(actorA).rows().stream()
            .filter(r -> r.skuId().equals(sku) && r.referenceId().equals(shopTwo))
            .findFirst()
            .orElseThrow();

    assertThat(row.alertState()).isEqualTo(StarvationState.STARVED);
    assertThat(row.demandUnits()).isEqualByComparingTo("30");
    assertThat(row.allocatedTotal()).isEqualByComparingTo("0");
    assertThat(row.coverageRatio()).isEqualByComparingTo("0");
    assertThat(row.zeroRoundCount()).isEqualTo(3);
    assertThat(row.observedRunCount()).isEqualTo(3);
    // 同期总可用量 > 0 ⇒ 货在、这家店分不到 ⇒ 是饥饿不是缺货。
    assertThat(row.maxRunAvailableQuantity()).isEqualByComparingTo("100");
    // 配了保底却没兑现 ⇒ 根因是保底，严重度最高。
    assertThat(row.floorShortfallQuantity()).isEqualByComparingTo("10");
    assertThat(row.floorStatus()).isEqualTo("UNFULFILLED");
    assertThat(row.rootCause()).isEqualTo(StarvationRootCause.FLOOR);
    assertThat(row.severity()).isEqualTo(Severity.CRITICAL);
    assertThat(row.demandState()).isEqualTo(DemandState.KNOWN);
  }

  @Test
  void 没有保底但优先级靠后时建议调优先级并指向名册() {
    scenario(actorA);
    evaluate(actorA);

    var row =
        starvations(actorA).rows().stream()
            .filter(r -> r.skuId().equals(sku) && r.referenceId().equals(shopFour))
            .findFirst()
            .orElseThrow();

    assertThat(row.alertState()).isEqualTo(StarvationState.STARVED);
    assertThat(row.memberPriority()).isEqualTo(4);
    assertThat(row.recommendation().kind()).isEqualTo(RecommendationKind.RAISE_PRIORITY);
    assertThat(row.recommendation().target()).isEqualTo("allocation_policy_member");
    assertThat(row.recommendation().currentValue()).isEqualByComparingTo("4");
    assertThat(row.recommendation().suggestedValue()).isEqualByComparingTo("1");
    assertThat(row.recommendation().referenceId()).isEqualTo(shopFour);
  }

  @Test
  void 有保底档时建议调保底并给出具体件数() {
    scenario(actorA);
    evaluate(actorA);

    var row =
        starvations(actorA).rows().stream()
            .filter(r -> r.skuId().equals(sku) && r.referenceId().equals(shopTwo))
            .findFirst()
            .orElseThrow();

    assertThat(row.recommendation().kind()).isEqualTo(RecommendationKind.RAISE_FLOOR);
    assertThat(row.recommendation().target()).isEqualTo("allocation_floor");
    assertThat(row.recommendation().currentValue()).isEqualByComparingTo("5");
    // 窗口内需求 30 件摊 3 轮 ⇒ 每轮应得 10 件。
    assertThat(row.recommendation().suggestedValue()).isEqualByComparingTo("10");
    assertThat(row.recommendation().policyId()).isNotNull();
  }

  @Test
  void 没有销量登记时判不知道而不是零需求() {
    scenario(actorA);
    evaluate(actorA);

    var row =
        starvations(actorA).rows().stream()
            .filter(r -> r.skuId().equals(idFor(a, 9)) && r.referenceId().equals(shopThree))
            .findFirst()
            .orElseThrow();

    assertThat(row.alertState()).isEqualTo(StarvationState.UNKNOWN_DEMAND);
    assertThat(row.demandState()).isEqualTo(DemandState.UNKNOWN);
    // 「不知道」绝不能读成 0 件需求。
    assertThat(row.demandUnits()).isNull();
    assertThat(row.coverageRatio()).isNull();
    assertThat(row.recommendation().kind()).isEqualTo(RecommendationKind.REGISTER_SALES);
    assertThat(row.recommendation().target()).isEqualTo("allocation_shop_sales");
  }

  // ================================================================ 缺货判定

  @Test
  void 账本里查不到库存记录时判不知道而不是零件() {
    scenario(actorA);
    evaluate(actorA);

    var row =
        stockouts(actorA).rows().stream()
            .filter(r -> r.skuId().equals(idFor(a, 9)))
            .findFirst()
            .orElseThrow();

    assertThat(row.alertState()).isEqualTo(StockoutState.NO_LEDGER_RECORD);
    // 「查不到记录」不是「一件都没有」。
    assertThat(row.availableQuantity()).isNull();
    assertThat(row.onHandQuantity()).isNull();
    assertThat(row.warehouseId()).isNull();
    assertThat(row.recommendation().kind()).isEqualTo(RecommendationKind.NONE);
  }

  @Test
  void 全网缺货时建议补货并给出缺口件数() {
    scenario(actorA);
    evaluate(actorA);

    var row =
        stockouts(actorA).rows().stream()
            .filter(r -> r.skuId().equals(idFor(a, 8)))
            .findFirst()
            .orElseThrow();

    assertThat(row.alertState()).isEqualTo(StockoutState.NETWORK_STOCKED_OUT);
    assertThat(row.networkAvailableQuantity()).isEqualByComparingTo("0");
    assertThat(row.demandUnits()).isEqualByComparingTo("100");
    assertThat(row.severity()).isEqualTo(Severity.CRITICAL);
    assertThat(row.recommendation().kind()).isEqualTo(RecommendationKind.REPLENISH);
    assertThat(row.recommendation().target()).isEqualTo("6.2.1.2");
    // 缺口 = 需求 − 全网可用量。
    assertThat(row.recommendation().suggestedValue()).isEqualByComparingTo("100");
  }

  @Test
  void 仓断货与全网缺货是两个取值() {
    scenario(actorA);
    evaluate(actorA);

    var rows = stockouts(actorA).rows();
    var warehouseShort = rows.stream().filter(r -> r.alertState() == StockoutState.WAREHOUSE_STOCKED_OUT).toList();
    var networkShort = rows.stream().filter(r -> r.alertState() == StockoutState.NETWORK_STOCKED_OUT).toList();

    assertThat(networkShort).isNotEmpty();
    assertThat(warehouseShort).isNotEmpty();
    // 两者是不同的取值、不同的处置：全网缺货是补货，仓断货是调拨 / 补到该仓。
    assertThat(networkShort.get(0).recommendation().target()).isEqualTo("6.2.1.2");
    assertThat(warehouseShort.get(0).recommendation().reason()).contains("调拨");
  }

  @Test
  void 覆盖不足判为风险而不是缺货() {
    scenario(actorA);
    evaluate(actorA);

    var row =
        stockouts(actorA).rows().stream()
            .filter(r -> r.skuId().equals(idFor(a, 11)) && r.alertState() == StockoutState.AT_RISK)
            .findFirst()
            .orElseThrow();

    assertThat(row.networkAvailableQuantity()).isEqualByComparingTo("20");
    assertThat(row.demandUnits()).isEqualByComparingTo("50");
    assertThat(row.severity()).isEqualTo(Severity.WARNING);
  }

  @Test
  void 没有销量登记时缺货侧也判不知道() {
    scenario(actorA);
    evaluate(actorA);

    var row =
        stockouts(actorA).rows().stream()
            .filter(r -> r.skuId().equals(idFor(a, 10)))
            .findFirst()
            .orElseThrow();

    assertThat(row.alertState()).isEqualTo(StockoutState.UNKNOWN_DEMAND);
    assertThat(row.demandUnits()).isNull();
    assertThat(row.demandState()).isEqualTo(DemandState.UNKNOWN);
    assertThat(row.recommendation().kind()).isEqualTo(RecommendationKind.REGISTER_SALES);
  }

  // ================================================================ 时间与生命周期

  @Test
  void 评估时刻写回再读出来逐字相等() {
    scenario(actorA);
    evaluate(actorA);

    var row = starvations(actorA).rows().get(0);
    assertThat(row.detectedAt()).isNotNull();
    var stored =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                sql.fetchOne(
                        "select detected_at from starvation_alert where id = ?::uuid",
                        row.id())
                    .get(0, OffsetDateTime.class));
    assertThat(stored).isEqualTo(row.detectedAt());
  }

  @Test
  void 重新评估后不再命中的旧预警被自动关闭而不是无声消失() throws Exception {
    scenario(actorA);
    evaluate(actorA);
    var before = starvations(actorA).rows().stream().filter(r -> r.referenceId().equals(shopTwo)).findFirst().orElseThrow();
    assertThat(before.lifecycleState()).isEqualTo(LifecycleState.OPEN);

    // 店二的销量登记改成 0 ⇒ 它不再缺货 ⇒ 重新评估后旧预警应当被自动关闭。
    access.run(
        actorA,
        "warehouse.inventory.update",
        () -> {
          sql.execute(
              "update allocation_shop_sales set units_sold = 0 where tenant_id = ?::uuid"
                  + " and reference_id = ?::uuid and sku_id = ?::uuid",
              a,
              shopTwo,
              sku);
          return null;
        });
    Thread.sleep(5);
    evaluate(actorA);

    var after =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                service.starvation(before.id()));
    assertThat(after.lifecycleState()).isEqualTo(LifecycleState.RESOLVED);
    assertThat(after.resolutionNote()).contains("不再命中");
  }

  @Test
  void 认领与处置走显式状态机() {
    scenario(actorA);
    evaluate(actorA);
    var row = starvations(actorA).rows().get(0);

    var acked = access.run(actorA, "warehouse.inventory.update", () -> service.acknowledgeStarvation(row.id(), user));
    assertThat(acked.lifecycleState()).isEqualTo(LifecycleState.ACKNOWLEDGED);

    var resolved =
        access.run(
            actorA,
            "warehouse.inventory.update",
            () -> service.resolveStarvation(row.id(), "已把该店保底调到 10 件", user));
    assertThat(resolved.lifecycleState()).isEqualTo(LifecycleState.RESOLVED);

    var after = access.run(actorA, "warehouse.inventory.read", () -> service.starvation(row.id()));
    assertThat(after.resolutionNote()).isEqualTo("已把该店保底调到 10 件");
  }

  /**
   * 问题复发时预警会重新打开，而且**复发次数必须自增** ——
   * 「这个问题被处置过、又复发过几次」这件事在行上留得下痕迹。
   */
  @Test
  void 问题复发时预警重新打开并累加复发次数() throws Exception {
    scenario(actorA);
    evaluate(actorA);
    var row =
        starvations(actorA).rows().stream()
            .filter(r -> r.skuId().equals(sku) && r.referenceId().equals(shopTwo))
            .findFirst()
            .orElseThrow();
    access.run(
        actorA,
        "warehouse.inventory.update",
        () -> service.resolveStarvation(row.id(), "先按建议调了保底", user));

    Thread.sleep(5);
    evaluate(actorA);

    var after = access.run(actorA, "warehouse.inventory.read", () -> service.starvation(row.id()));
    assertThat(after.lifecycleState()).isEqualTo(LifecycleState.OPEN);
    assertThat(after.recurrenceCount()).isEqualTo(1);
    // 复发重开时上一次的处置说明要清掉 ——「不再命中」在问题重新出现之后就是错的。
    assertThat(after.resolutionNote()).isEmpty();
  }

  @Test
  void 处置必须写明做了什么() {    scenario(actorA);
    evaluate(actorA);
    var row = starvations(actorA).rows().get(0);
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () -> service.resolveStarvation(row.id(), "   ", user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("处置必须写明");
  }

  // ================================================================ DB 级约束

  @Test
  void 预警行不可删除() {
    scenario(actorA);
    evaluate(actorA);
    var row = starvations(actorA).rows().get(0);
    // 权限层先收一道（warehouse_app 没有 DELETE 权限），DB 层再收一道（触发器）。
    // 两条防线任意一条生效都算「删不掉」，所以断言的是「删操作失败」这件事本身。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () -> sql.execute("delete from starvation_alert where id = ?::uuid", row.id())))
        .rootCause()
        .hasMessageContaining("permission denied for table starvation_alert");
  }

  @Test
  void 新表强制行级安全() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), "warehouse_app", "test-only");
        var s = c.createStatement()) {
      for (var table : List.of("starvation_alert", "stockout_alert")) {
        var meta =
            s.executeQuery(
                "select relforcerowsecurity from pg_class where relname = '" + table + "'");
        meta.next();
        assertThat(meta.getBoolean(1)).as(table + " 必须 FORCE RLS").isTrue();
      }
      // 无租户上下文时读不到任何行。
      var meta = s.executeQuery("select count(*) from starvation_alert");
      meta.next();
      assertThat(meta.getLong(1)).isZero();
    }
  }

  @Test
  void 新迁移没有改动既有模块的表() throws Exception {
    // V192 只建两张新表：给既有表加列或改 CHECK 会打掉别的车道的直写夹具。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      var meta =
          s.executeQuery(
              "select count(*) from information_schema.columns"
                  + " where table_name in ('allocation_run','allocation_run_line','allocation_shop_sales',"
                  + "'allocation_policy','allocation_policy_member','allocation_floor',"
                  + "'allocation_priority_resolution','stock_bucket','inventory_balance','warehouse')"
                  + " and column_name = 'starvation_state'");
      meta.next();
      assertThat(meta.getLong(1)).isZero();
    }
  }

  // ================================================================ 端点与权限

  @Test
  void 能力端点如实报出未交付的三环() {
    var c = service.capabilities();
    assertThat(c.performanceAvailable()).isFalse();
    assertThat(c.performanceOwnerModule()).isEqualTo("17");
    assertThat(c.replenishmentAvailable()).isFalse();
    assertThat(c.replenishmentOwnerModule()).isEqualTo("6.2.1.2");
    assertThat(c.workbenchAlertAvailable()).isFalse();
    assertThat(c.workbenchAlertOwnerModule()).isEqualTo("1");
    assertThat(c.dimensions())
        .anySatisfy(
            d -> {
              assertThat(d.available()).isFalse();
              assertThat(d.ownerModule()).isNotBlank();
            });
    assertThat(c.separationNote()).contains("没货卖").contains("没分到货");
  }

  @Test
  void 只读权限能看但不能评估() throws Exception {
    scenario(actorA);
    http.perform(
            get("/warehouse/inventory/stockout-alerts/starvations")
                .header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk());
    http.perform(
            post("/warehouse/inventory/stockout-alerts/evaluate")
                .header("Authorization", "Bearer read-only")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void 端点返回可解析的两份清单() throws Exception {
    scenario(actorA);
    evaluate(actorA);

    http.perform(
            get("/warehouse/inventory/stockout-alerts/starvations")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows").isArray())
        .andExpect(jsonPath("$.summary.starvedCount").exists());

    http.perform(
            get("/warehouse/inventory/stockout-alerts/stockouts")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows").isArray())
        .andExpect(jsonPath("$.summary.networkStockedOutCount").exists());
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() {
    scenario(actorA);
    evaluate(actorA);
    scenario(actorB);
    evaluate(actorB);

    var aRows = starvations(actorA).rows();
    var bRows = starvations(actorB).rows();
    assertThat(aRows).isNotEmpty();
    assertThat(bRows).isNotEmpty();
    // 两边的行 id 互不相交（各租户的行集合完全隔离）。
    assertThat(aRows.stream().map(StarvationAlertRow::id).toList())
        .doesNotContainAnyElementsOf(bRows.stream().map(StarvationAlertRow::id).toList());

    var aStock = stockouts(actorA).rows();
    var bStock = stockouts(actorB).rows();
    assertThat(aStock.stream().map(StockoutAlertRow::id).toList())
        .doesNotContainAnyElementsOf(bStock.stream().map(StockoutAlertRow::id).toList());
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    scenario(actorA);
    evaluate(actorA);

    // 身份是 B、伪造 X-Tenant-Id 指向 A ⇒ B 只能看到 B 自己的（空）。
    http.perform(
            get("/warehouse/inventory/stockout-alerts/starvations")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
    // 身份是 A、伪造头指向 B ⇒ A 仍然是 A。
    http.perform(
            get("/warehouse/inventory/stockout-alerts/starvations")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThan(0)));
  }

  @Test
  void 断言三_连接池归还后不串租户() throws Exception {
    scenario(actorA);
    evaluate(actorA);
    scenario(actorB);
    evaluate(actorB);

    // 池大小为 1，同一条连接被反复借还：交替跑两个租户 20 轮，
    // 每一轮都要同时校验两条路径都不串租户。
    //
    // 判据是**行里的 SKU 属于哪个租户**：两个租户的 SKU 是按租户派生的，
    // 所以「A 的连接返回了 B 的 SKU」就是串租户的实锤 ——
    // 只比行数或比 id 集合都太弱（id 天然不同，比不出串号）。
    for (int i = 0; i < 20; i++) {
      var pageA =
          access.run(
              actorA,
              "warehouse.inventory.read",
              () -> service.starvations(new StarvationAlertQuery(null, null, null, null, null, null, null, 0, 100)));
      var pageB =
          access.run(
              actorB,
              "warehouse.inventory.read",
              () -> service.starvations(new StarvationAlertQuery(null, null, null, null, null, null, null, 0, 100)));
      assertThat(pageA.total()).isPositive();
      assertThat(pageB.total()).isPositive();
      assertThat(pageA.rows())
          .allSatisfy(
              row -> {
                assertThat(row.skuId()).isIn(sku, idFor(a, 8), idFor(a, 9), idFor(a, 10), idFor(a, 11));
                assertThat(row.skuId()).isNotIn(idFor(b, 2), idFor(b, 8), idFor(b, 9), idFor(b, 10), idFor(b, 11));
              });
      assertThat(pageB.rows())
          .allSatisfy(
              row -> {
                assertThat(row.skuId()).isIn(idFor(b, 2), idFor(b, 8), idFor(b, 9), idFor(b, 10), idFor(b, 11));
                assertThat(row.skuId()).isNotIn(sku, idFor(a, 8), idFor(a, 9), idFor(a, 10), idFor(a, 11));
              });

      var stockA =
          access.run(
              actorA,
              "warehouse.inventory.read",
              () -> service.stockouts(new StockoutAlertQuery(null, null, null, null, null, 0, 100)));
      var stockB =
          access.run(
              actorB,
              "warehouse.inventory.read",
              () -> service.stockouts(new StockoutAlertQuery(null, null, null, null, null, 0, 100)));
      assertThat(stockA.total()).isPositive();
      assertThat(stockB.total()).isPositive();
      assertThat(stockA.rows())
          .allSatisfy(row -> assertThat(row.skuId()).isNotIn(idFor(b, 2), idFor(b, 8), idFor(b, 9), idFor(b, 10), idFor(b, 11)));
      assertThat(stockB.rows())
          .allSatisfy(row -> assertThat(row.skuId()).isNotIn(sku, idFor(a, 8), idFor(a, 9), idFor(a, 10), idFor(a, 11)));
    }
  }
}
