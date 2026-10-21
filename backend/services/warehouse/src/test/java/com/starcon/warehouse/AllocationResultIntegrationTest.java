package com.starcon.warehouse;

import static com.starcon.warehouse.AllocationResultModels.*;
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
 * 7.8.2.1 分摊结果查看：DB 级约束、敞口算式、UNKNOWN 语义、以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>夹具写法与 {@code AllocationPolicyIntegrationTest}（7.8.1.1）、
 * {@code AllocationPriorityIntegrationTest}（7.8.1.2）完全一致 —— 同样的 Testcontainers、
 * 同样的 fake identity server、同样的按租户派生 UUID、同样的 TRUNCATE 清场。
 *
 * <p><b>这个类最该守住的三件事</b>：
 *
 * <ol>
 *   <li><b>「不知道」不是一个 0</b>：本地没有回写状态时，行上的回写数 / 已用 / 剩余 /
 *       敞口全是 null，状态是 UNKNOWN。断言的是 null 而**不是** 0。
 *   <li><b>敞口是算出来的</b>：平台挂着的数比我方剩余多多少，就是多少件敞口；
 *       超过保留缓冲即判「缓冲不足，同步窗口内会超卖」。
 *   <li><b>时间往返逐字相等</b>：{@code as_of} 写进去再读出来必须是同一个时刻
 *       （偏 8 小时这种错在这里必须被抓住）。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AllocationResultIntegrationTest {
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
  static final UUID quality = idFor(a, 5);

  /** 店铺是 channel 服务的实体：这里只造逻辑 id，warehouse 侧不建外键也不查它的表。 */
  static final UUID shopOne = UUID.randomUUID();

  static final UUID shopTwo = UUID.randomUUID();
  static final UUID shopThree = UUID.randomUUID();

  /** 一次分摊的时刻。三条观测都必须**晚于**它（V189 的触发器钉住）。 */
  static final OffsetDateTime ALLOCATED_AT = OffsetDateTime.parse("2026-10-19T08:00:00+08:00");

  static final OffsetDateTime OBSERVED_AT = OffsetDateTime.parse("2026-10-19T10:00:00+08:00");

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-warehouse-result-" + tenant + "-" + ordinal).getBytes());
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

  @Autowired AllocationResultService service;
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
          "truncate allocation_writeback_snapshot, allocation_priority_resolution,"
              + " allocation_floor, allocation_run_line, allocation_run, allocation_shop_sales,"
              + " allocation_policy_member, allocation_policy, sellable_policy_warehouse,"
              + " sellable_policy, inventory_balance, inventory_quality_status, warehouse,"
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
                "WH-OWN",
                "自有仓",
                "CN",
                "Asia/Shanghai");
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

  /** 一档分摊策略（全租户兜底、无缓冲、FIXED_PRIORITY）+ 三家店的名册。 */
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
          for (var shop : List.of(shopOne, shopTwo, shopThree)) {
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

  /**
   * 一档 7.2.2.1 的可售量口径（平台回写缓冲）。
   *
   * @param scopeType DEFAULT 或 SHOP
   * @param ratio 平台可售数保留缓冲比例
   */
  void sellablePolicy(AccessService.Access who, String scopeType, UUID referenceId, String ratio) {
    access.run(
        who,
        "warehouse.inventory.update",
        () -> {
          String poolKey = referenceId == null ? "EXCL-" : "EXCL-" + referenceId;
          sql.execute(
              "insert into sellable_policy (effective_from,id,tenant_id,scope_type,source_system,"
                  + "reference_id,reference_label,pool_mode,pool_key,oversell_allowed,"
                  + "oversell_tolerance,platform_stock_scope,buffer_ratio,include_in_transit,note,"
                  + "created_by_membership_id)"
                  + " values (?::timestamptz,?,?,?,?,?,?,'EXCLUSIVE',?,false,0,'OWN_WAREHOUSE',"
                  + "?::numeric,false,'夹具',?)",
              ALLOCATED_AT.minusDays(1),
              UUID.randomUUID(),
              who.tenantId(),
              scopeType,
              referenceId == null ? "" : "CHANNEL",
              referenceId,
              referenceId == null ? "兜底档" : "店档",
              poolKey,
              ratio,
              user);
          return null;
        });
  }

  /**
   * 一次分摊：可用 120 = 分摊缓冲 20 + 可分配 100；分摊 100（40 / 30 / 30）。
   *
   * @return run id
   */
  UUID run(AccessService.Access who, UUID policyId) {
    UUID runId = UUID.randomUUID();
    access.run(
        who,
        "warehouse.inventory.update",
        () -> {
          sql.execute(
              "insert into allocation_run (id,tenant_id,policy_id,method,sku_id,allocated_at,"
                  + "sales_window_start,sales_window_end,available_quantity,buffer_quantity,"
                  + "allocatable_quantity,allocated_total,unallocated_quantity,note,"
                  + "created_by_membership_id)"
                  + " values (?,?,?,'FIXED_PRIORITY',?,?::timestamptz,?,?,120,20,100,100,0,'夹具',?)",
              runId,
              who.tenantId(),
              policyId,
              sku,
              ALLOCATED_AT,
              ALLOCATED_AT.toLocalDate().minusDays(30),
              ALLOCATED_AT.toLocalDate(),
              user);
          int order = 0;
          for (var pair :
              List.of(
                  new Object[] {shopOne, "店一", 40},
                  new Object[] {shopTwo, "店二", 30},
                  new Object[] {shopThree, "店三", 30})) {
            order++;
            sql.execute(
                "insert into allocation_run_line (tenant_id,run_id,source_system,reference_id,"
                    + "reference_label,shop_operating_mode,allocation_order,sales_units,sales_share,"
                    + "allocated_qty,outcome) values (?,?,'CHANNEL',?,?,'SELF_FULFILLED',?,0,null,"
                    + "?::numeric,'OK')",
                who.tenantId(),
                runId,
                pair[0],
                pair[1],
                order,
                pair[2]);
          }
          return null;
        });
    return runId;
  }

  /** 一条保底生效记录（7.8.1.2 的表），用来验证分摊结果里带得出保底信息。 */
  void floor(AccessService.Access who, UUID runId, UUID shop) {
    access.run(
        who,
        "warehouse.inventory.update",
        () -> {
          sql.execute(
              "insert into allocation_priority_resolution (tenant_id,run_id,reference_id,"
                  + "source_system,reference_label,floor_order,floor_mode,floor_ratio,"
                  + "floor_quantity,safety_quantity,requested_qty,granted_qty,shortfall_qty,"
                  + "status,shortfall_reason,residual_qty,base_allocated_qty,allocated_qty,"
                  + "suppressed,suppression_reason) values (?,?,?,'CHANNEL','店一',1,'QUANTITY',0,"
                  + "10,0,10,10,0,'FULFILLED',null,30,40,40,false,null)",
              who.tenantId(),
              runId,
              shop);
          return null;
        });
  }

  /** 登记一条回写观测（走服务层，好让校验规则也一起被验）。 */
  AllocationWritebackSaved observe(
      AccessService.Access who,
      UUID runId,
      UUID shop,
      String state,
      String attempted,
      String written,
      String consumed,
      String reason,
      OffsetDateTime asOf) {
    // 仓是本服务实体，复合外键钉住「这个仓确实属于本租户」——
    // 跨租户用错仓会被 DB 直接拒。
    UUID warehouse = who.tenantId().equals(a) ? wh : idFor(b, 3);
    return access.run(
        who,
        "warehouse.inventory.update",
        () ->
            service.record(
                new AllocationWritebackInput(
                    runId,
                    "CHANNEL",
                    shop,
                    "店",
                    warehouse,
                    state,
                    new BigDecimal(attempted),
                    written == null ? null : new BigDecimal(written),
                    consumed == null ? null : new BigDecimal(consumed),
                    reason,
                    "API",
                    "obs-" + UUID.randomUUID(),
                    asOf),
                user));
  }

  /** 取分摊明细的一行。 */
  AllocationResultRow row(AccessService.Access who, UUID runId, UUID shop) {
    var page =
        access.run(
            who,
            "warehouse.inventory.read",
            () -> service.results(new AllocationResultQuery(runId, null, null, shop, null, null, null, null, 0, 50)));
    assertThat(page.rows()).as("分摊明细里必须有这一行").hasSize(1);
    return page.rows().get(0);
  }

  // ================================================================ 分摊明细

  @Test
  void 没有任何回写状态时判未知而不是零() {
    UUID policyId = policy(actorA);
    sellablePolicy(actorA, "DEFAULT", null, "0.2");
    UUID runId = run(actorA, policyId);

    var r = row(actorA, runId, shopOne);

    assertThat(r.writebackState()).as("本地没收到回写状态就是 UNKNOWN").isEqualTo(WritebackState.UNKNOWN);
    assertThat(r.platformQuantity()).as("平台挂着的数：不知道就是 null，绝不是 0").isNull();
    assertThat(r.consumedQuantity()).as("已用：不知道就是 null，绝不是 0").isNull();
    assertThat(r.remainingQuantity()).as("剩余：算不出来就是 null，绝不是 0").isNull();
    assertThat(r.exposureQuantity()).as("敞口：算不出来就是 null，绝不是 0").isNull();
    assertThat(r.exposureState()).isEqualTo(ExposureState.UNKNOWN);
    assertThat(r.consistencyState()).isEqualTo(ConsistencyState.UNKNOWN);
    // 分摊量是 7.8.1.1 的事实，与回写状态无关，必须照样读得出来。
    assertThat(r.allocatedQuantity()).isEqualByComparingTo("40");
    assertThat(r.remainingQuantity()).isNotEqualTo(BigDecimal.ZERO);
  }

  @Test
  void 回写成功后四个数与敞口都算得出来() {
    UUID policyId = policy(actorA);
    sellablePolicy(actorA, "DEFAULT", null, "0.2");
    UUID runId = run(actorA, policyId);

    // 平台挂着 40 件，我方已用 5 件 → 剩余 35；缓冲 20% → 应挂 32，保留 8。
    observe(actorA, runId, shopOne, "SUCCEEDED", "40", "40", "5", null, OBSERVED_AT);

    var r = row(actorA, runId, shopOne);

    assertThat(r.writebackState()).isEqualTo(WritebackState.SUCCEEDED);
    assertThat(r.platformQuantity()).isEqualByComparingTo("40");
    assertThat(r.consumedQuantity()).isEqualByComparingTo("5");
    assertThat(r.remainingQuantity()).isEqualByComparingTo("35");
    assertThat(r.bufferRatio()).isEqualByComparingTo("0.2");
    assertThat(r.bufferConfigured()).isTrue();
    // 可挂 = trunc(40 × 0.8) = 32；保留缓冲 = 40 − 32 = 8。
    assertThat(r.writableQuantity()).isEqualByComparingTo("32");
    assertThat(r.bufferQuantity()).isEqualByComparingTo("8");
    // 平台挂 40 > 应挂 32 → 挂多了。
    assertThat(r.consistencyState()).isEqualTo(ConsistencyState.OVER_ADVERTISED);
    // 敞口 = 40 − 35 = 5，在缓冲 8 之内 → 兜得住。
    assertThat(r.exposureQuantity()).isEqualByComparingTo("5");
    assertThat(r.exposureState()).isEqualTo(ExposureState.WITHIN_BUFFER);
  }

  @Test
  void 缓冲不足时风险敞口被算出来并暴露() {
    UUID policyId = policy(actorA);
    sellablePolicy(actorA, "DEFAULT", null, "0.2");
    UUID runId = run(actorA, policyId);

    // 店二分到 30 件 → 缓冲 = 30 − trunc(30 × 0.8) = 6，应挂 24。
    // 平台还挂着 30 件，我方已用 25 件 → 剩余 5 → 敞口 25 > 缓冲 6。
    observe(actorA, runId, shopTwo, "SUCCEEDED", "30", "30", "25", null, OBSERVED_AT);

    var r = row(actorA, runId, shopTwo);

    assertThat(r.remainingQuantity()).isEqualByComparingTo("5");
    assertThat(r.bufferQuantity()).isEqualByComparingTo("6");
    assertThat(r.exposureQuantity()).as("敞口必须是一个算出来的数").isEqualByComparingTo("25");
    assertThat(r.exposureState())
        .as("敞口超过缓冲 = 蓝图说的「缓冲不足会超卖」")
        .isEqualTo(ExposureState.BEYOND_BUFFER);
    assertThat(r.consistencyState()).isEqualTo(ConsistencyState.OVER_ADVERTISED);
  }

  @Test
  void 店铺档的口径覆盖全租户兜底档() {
    UUID policyId = policy(actorA);
    sellablePolicy(actorA, "DEFAULT", null, "0.2");
    // 店三另配一档 50% 缓冲：SHOP 档优先于 DEFAULT 档。
    sellablePolicy(actorA, "SHOP", shopThree, "0.5");
    UUID runId = run(actorA, policyId);

    var r = row(actorA, runId, shopThree);

    // 可挂 = trunc(30 × 0.5) = 15，保留 15。
    assertThat(r.bufferRatio()).isEqualByComparingTo("0.5");
    assertThat(r.writableQuantity()).isEqualByComparingTo("15");
    assertThat(r.bufferQuantity()).isEqualByComparingTo("15");
  }

  @Test
  void 没有配置口径时如实报未配置而不是按零缓冲() {
    UUID policyId = policy(actorA);
    UUID runId = run(actorA, policyId);

    var r = row(actorA, runId, shopOne);

    assertThat(r.bufferRatio()).as("没配口径就是 null").isNull();
    assertThat(r.bufferConfigured()).isFalse();
    assertThat(r.bufferQuantity()).as("没配口径时缓冲是 null，不是 0").isNull();
    assertThat(r.writableQuantity()).isNull();
  }

  @Test
  void 失败的回写不改变平台当前挂着的数() {
    UUID policyId = policy(actorA);
    sellablePolicy(actorA, "DEFAULT", null, "0.2");
    UUID runId = run(actorA, policyId);

    observe(actorA, runId, shopOne, "SUCCEEDED", "40", "40", "5", null, OBSERVED_AT);
    // 第二次想把 32 件写上去，失败了：平台仍然挂着 40。
    observe(
        actorA,
        runId,
        shopOne,
        "FAILED",
        "32",
        null,
        null,
        "平台接口 500",
        OBSERVED_AT.plusHours(1));

    var r = row(actorA, runId, shopOne);

    assertThat(r.writebackState()).isEqualTo(WritebackState.FAILED);
    assertThat(r.failureReason()).isEqualTo("平台接口 500");
    assertThat(r.platformQuantity())
        .as("失败的回写不改变平台当前挂着的数：仍是上一次成功回写的 40")
        .isEqualByComparingTo("40");
    assertThat(r.attemptedQuantity()).isEqualByComparingTo("32");
    assertThat(r.consumedQuantity())
        .as("失败的那次观测没有新的已用数，但已用数也不该倒退成 0")
        .isEqualByComparingTo("5");
  }

  @Test
  void 观测时刻写回再读出来逐字相等() {
    UUID policyId = policy(actorA);
    sellablePolicy(actorA, "DEFAULT", null, "0.2");
    UUID runId = run(actorA, policyId);

    var saved =
        observe(actorA, runId, shopOne, "SUCCEEDED", "40", "40", "5", null, OBSERVED_AT);
    assertThat(saved.asOf().toInstant())
        .as("timestamptz 往返：偏 8 小时这种错必须在这里被抓住")
        .isEqualTo(OBSERVED_AT.toInstant());

    var page =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.writebacks(runId, null, null, null, null, 0, 50));
    assertThat(page.observations()).hasSize(1);
    assertThat(page.observations().get(0).asOf().toInstant()).isEqualTo(OBSERVED_AT.toInstant());
    assertThat(page.observations().get(0).state()).isEqualTo(WritebackState.SUCCEEDED);
  }

  @Test
  void 分摊结果里带得出保底信息() {
    UUID policyId = policy(actorA);
    sellablePolicy(actorA, "DEFAULT", null, "0.2");
    UUID runId = run(actorA, policyId);
    floor(actorA, runId, shopOne);

    var r = row(actorA, runId, shopOne);

    assertThat(r.floorStatus()).isEqualTo("FULFILLED");
    assertThat(r.floorShortfallQuantity()).isEqualByComparingTo("0");
    assertThat(r.suppressed()).isFalse();
  }

  // ================================================================ 筛选与核对

  @Test
  void 按仓筛选时仓归属未知的行数被报出来而不是无声消失() {
    UUID policyId = policy(actorA);
    sellablePolicy(actorA, "DEFAULT", null, "0.2");
    UUID runId = run(actorA, policyId);
    observe(actorA, runId, shopOne, "SUCCEEDED", "40", "40", "5", null, OBSERVED_AT);

    var all =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.results(new AllocationResultQuery(runId, null, null, null, null, null, null, null, 0, 50)));
    assertThat(all.total()).isEqualTo(3);

    var byWarehouse =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                service.results(
                    new AllocationResultQuery(runId, null, wh, null, null, null, null, null, 0, 50)));
    assertThat(byWarehouse.total()).as("只有店一有回写观测、才有仓归属").isEqualTo(1);
    assertThat(byWarehouse.warehouseUnattributedCount())
        .as("另外两行尚未回填、归属不到仓，这个数必须被报出来")
        .isEqualTo(2);
  }

  @Test
  void scope能筛出缓冲不足的行() {
    UUID policyId = policy(actorA);
    sellablePolicy(actorA, "DEFAULT", null, "0.2");
    UUID runId = run(actorA, policyId);
    observe(actorA, runId, shopTwo, "SUCCEEDED", "30", "30", "25", null, OBSERVED_AT);

    var beyond =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                service.results(
                    new AllocationResultQuery(runId, null, null, null, null, null, null, "BEYOND_BUFFER", 0, 50)));
    assertThat(beyond.total()).isEqualTo(1);
    assertThat(beyond.rows().get(0).referenceId()).isEqualTo(shopTwo);
    assertThat(beyond.summary().beyondBufferCount()).isEqualTo(1);

    var unknown =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                service.results(
                    new AllocationResultQuery(runId, null, null, null, null, null, null, "UNKNOWN", 0, 50)));
    assertThat(unknown.total()).as("店一与店三都没有回写状态").isEqualTo(2);
    assertThat(unknown.summary().unknownStateCount()).isEqualTo(2);
  }

  @Test
  void 核对报告逐轮给出结论() {
    UUID policyId = policy(actorA);
    sellablePolicy(actorA, "DEFAULT", null, "0.2");
    UUID runId = run(actorA, policyId);
    observe(actorA, runId, shopTwo, "SUCCEEDED", "30", "30", "25", null, OBSERVED_AT);

    var report =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                service.reconciliation(
                    new AllocationResultQuery(runId, null, null, null, null, null, null, null, 0, 50)));

    assertThat(report.runs()).hasSize(1);
    var one = report.runs().get(0);
    assertThat(one.runId()).isEqualTo(runId);
    assertThat(one.shopCount()).isEqualTo(3);
    assertThat(one.unknownStateCount()).isEqualTo(2);
    assertThat(one.beyondBufferCount()).isEqualTo(1);
    assertThat(one.exposureTotal()).isEqualByComparingTo("25");
    assertThat(report.summary().exposureComputableCount()).isEqualTo(1);
    assertThat(report.note()).contains("UNKNOWN");
  }

  @Test
  void 分摊轮次列表报出还有几家店没回填() {
    UUID policyId = policy(actorA);
    sellablePolicy(actorA, "DEFAULT", null, "0.2");
    UUID runId = run(actorA, policyId);
    observe(actorA, runId, shopOne, "SUCCEEDED", "40", "40", "5", null, OBSERVED_AT);

    var page = access.run(actorA, "warehouse.inventory.read", () -> service.runs(null, null, null, null, 0, 50));

    assertThat(page.runs()).hasSize(1);
    var one = page.runs().get(0);
    assertThat(one.runId()).isEqualTo(runId);
    assertThat(one.shopCount()).isEqualTo(3);
    assertThat(one.observedShopCount()).isEqualTo(1);
    assertThat(one.unknownShopCount()).isEqualTo(2);
  }

  // ================================================================ DB 级约束

  @Test
  void 观测是不可改不可删的() {
    UUID policyId = policy(actorA);
    sellablePolicy(actorA, "DEFAULT", null, "0.2");
    UUID runId = run(actorA, policyId);
    observe(actorA, runId, shopOne, "SUCCEEDED", "40", "40", "5", null, OBSERVED_AT);

    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), "warehouse_app", "test-only");
        var s = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.execute(
                      "update allocation_writeback_snapshot set written_quantity = 999"))
          .as("观测是「当时平台挂的是多少」的唯一凭据，不许改")
          .isInstanceOf(java.sql.SQLException.class);
      assertThatThrownBy(() -> s.execute("delete from allocation_writeback_snapshot"))
          .as("观测不许删")
          .isInstanceOf(java.sql.SQLException.class);
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void 观测必须挂在一次真实存在的分摊行上() {
    UUID policyId = policy(actorA);
    sellablePolicy(actorA, "DEFAULT", null, "0.2");
    UUID runId = run(actorA, policyId);

    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () -> {
                      sql.execute(
                          "insert into allocation_writeback_snapshot (id,tenant_id,run_id,"
                              + "source_system,reference_id,reference_label,warehouse_id,state,"
                              + "attempted_quantity,written_quantity,consumed_quantity,failure_reason,"
                              + "source_kind,source_reference,as_of,recorded_by_membership_id)"
                              + " values (?,?,?,'CHANNEL',?,'不存在的店',?,'SUCCEEDED',10,10,0,'',"
                              + "'MANUAL','x',?::timestamptz,?)",
                          UUID.randomUUID(),
                          a,
                          runId,
                          UUID.randomUUID(),
                          wh,
                          OBSERVED_AT,
                          user);
                      return null;
                    }))
        .as("给一个不存在的分摊行记回写状态必须被拒")
        .hasMessageContaining("allocation_writeback_snapshot");
  }

  @Test
  void 采集时刻不得早于分摊时刻() {
    UUID policyId = policy(actorA);
    sellablePolicy(actorA, "DEFAULT", null, "0.2");
    UUID runId = run(actorA, policyId);

    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () -> {
                      sql.execute(
                          "insert into allocation_writeback_snapshot (id,tenant_id,run_id,"
                              + "source_system,reference_id,reference_label,warehouse_id,state,"
                              + "attempted_quantity,written_quantity,consumed_quantity,failure_reason,"
                              + "source_kind,source_reference,as_of,recorded_by_membership_id)"
                              + " values (?,?,?,'CHANNEL',?,'店一',?,'SUCCEEDED',10,10,0,'',"
                              + "'MANUAL','x',?::timestamptz,?)",
                          UUID.randomUUID(),
                          a,
                          runId,
                          shopOne,
                          wh,
                          ALLOCATED_AT.minusHours(1),
                          user);
                      return null;
                    }))
        .as("比分摊还早的「回写状态」不是观测而是编造")
        .hasMessageContaining("不是观测而是编造");
  }

  @Test
  void 成功必须带确认值失败必须带原因() {
    UUID policyId = policy(actorA);
    sellablePolicy(actorA, "DEFAULT", null, "0.2");
    UUID runId = run(actorA, policyId);

    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () -> {
                      sql.execute(
                          "insert into allocation_writeback_snapshot (id,tenant_id,run_id,"
                              + "source_system,reference_id,reference_label,warehouse_id,state,"
                              + "attempted_quantity,written_quantity,consumed_quantity,failure_reason,"
                              + "source_kind,source_reference,as_of,recorded_by_membership_id)"
                              + " values (?,?,?,'CHANNEL',?,'店一',?,'SUCCEEDED',10,null,0,'',"
                              + "'MANUAL','x',?::timestamptz,?)",
                          UUID.randomUUID(),
                          a,
                          runId,
                          shopOne,
                          wh,
                          OBSERVED_AT,
                          user);
                      return null;
                    }))
        .as("成功了却不知道平台挂了多少，这次观测什么也没证明")
        .hasMessageContaining("allocation_writeback_snapshot");

    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () -> {
                      sql.execute(
                          "insert into allocation_writeback_snapshot (id,tenant_id,run_id,"
                              + "source_system,reference_id,reference_label,warehouse_id,state,"
                              + "attempted_quantity,written_quantity,consumed_quantity,failure_reason,"
                              + "source_kind,source_reference,as_of,recorded_by_membership_id)"
                              + " values (?,?,?,'CHANNEL',?,'店一',?,'FAILED',10,null,0,'',"
                              + "'MANUAL','x',?::timestamptz,?)",
                          UUID.randomUUID(),
                          a,
                          runId,
                          shopOne,
                          wh,
                          OBSERVED_AT,
                          user);
                      return null;
                    }))
        .as("没有原因的失败在复盘时与「没发生过」长得一模一样")
        .hasMessageContaining("allocation_writeback_snapshot");
  }

  @Test
  void 服务层拒绝不合法的观测() {
    UUID policyId = policy(actorA);
    sellablePolicy(actorA, "DEFAULT", null, "0.2");
    UUID runId = run(actorA, policyId);

    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () ->
                        service.record(
                            new AllocationWritebackInput(
                                runId,
                                "CHANNEL",
                                shopOne,
                                "店一",
                                wh,
                                "SUCCEEDED",
                                new BigDecimal("40"),
                                null,
                                null,
                                "",
                                "API",
                                "x",
                                OBSERVED_AT),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("writtenQuantity");

    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () ->
                        service.record(
                            new AllocationWritebackInput(
                                runId,
                                "CHANNEL",
                                shopOne,
                                "店一",
                                wh,
                                "FAILED",
                                new BigDecimal("40"),
                                null,
                                null,
                                "",
                                "API",
                                "x",
                                OBSERVED_AT),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("失败原因");
  }

  @Test
  void 新表强制行级安全() {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), "warehouse_app", "test-only");
        var s = c.createStatement()) {
      var rs = s.executeQuery("select count(*) from allocation_writeback_snapshot");
      rs.next();
      assertThat(rs.getLong(1)).as("无租户上下文时必须查不到任何行").isZero();
      var meta =
          s.executeQuery(
              "select relforcerowsecurity from pg_class where relname = 'allocation_writeback_snapshot'");
      meta.next();
      assertThat(meta.getBoolean(1)).as("必须 FORCE RLS").isTrue();
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void 新迁移没有改动既有模块的表() {
    // V189 只建一张新表：给既有表加列或改 CHECK 会打掉别的车道的直写夹具。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      for (var table :
          List.of(
              "allocation_policy",
              "allocation_policy_member",
              "allocation_shop_sales",
              "allocation_run",
              "allocation_run_line",
              "allocation_floor",
              "allocation_priority_resolution",
              "sellable_policy",
              "sellable_policy_warehouse",
              "warehouse",
              "sku_ref")) {
        var exists =
            s.executeQuery(
                "select count(*) from information_schema.tables"
                    + " where table_schema = 'public' and table_name = '"
                    + table
                    + "'");
        exists.next();
        assertThat(exists.getLong(1)).as(table + " 必须仍在（本迁移不得删改）").isEqualTo(1);
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  // ================================================================ 端点与权限

  @Test
  void 只读权限能看但不能登记回写状态() throws Exception {
    UUID policyId = policy(actorA);
    sellablePolicy(actorA, "DEFAULT", null, "0.2");
    UUID runId = run(actorA, policyId);

    http.perform(
            get("/warehouse/inventory/allocation-results/results")
                .header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk());
    http.perform(
            post("/warehouse/inventory/allocation-results/writebacks")
                .header("Authorization", "Bearer read-only")
                .contentType("application/json")
                .content(
                    "{\"runId\":\""
                        + runId
                        + "\",\"sourceSystem\":\"CHANNEL\",\"referenceId\":\""
                        + shopOne
                        + "\",\"warehouseId\":\""
                        + wh
                        + "\",\"state\":\"SUCCEEDED\",\"attemptedQuantity\":40,"
                        + "\"writtenQuantity\":40,\"sourceKind\":\"MANUAL\","
                        + "\"sourceReference\":\"x\",\"asOf\":\"2026-10-19T10:00:00+08:00\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void 端点返回可解析的分摊结果() throws Exception {
    UUID policyId = policy(actorA);
    sellablePolicy(actorA, "DEFAULT", null, "0.2");
    UUID runId = run(actorA, policyId);
    observe(actorA, runId, shopOne, "SUCCEEDED", "40", "40", "5", null, OBSERVED_AT);

    http.perform(
            get("/warehouse/inventory/allocation-results/results")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(3))
        .andExpect(jsonPath("$.summary.unknownStateCount").value(2))
        .andExpect(jsonPath("$.rows[0].writebackState").value("SUCCEEDED"));

    http.perform(
            get("/warehouse/inventory/allocation-results/capabilities")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.writebackProjectionAvailable").value(false))
        .andExpect(jsonPath("$.writebackStates[0].code").value("UNKNOWN"));
  }

  @Test
  void 能力端点如实报出跨服务边界与未知态语义() {
    var c = service.capabilities();
    assertThat(c.writebackProjectionAvailable())
        .as("平台回写状态在 channel 服务，本服务不跨服务读表")
        .isFalse();
    assertThat(c.writebackProjectionNote()).contains("不读 channel 的表");
    assertThat(c.unknownStateNote()).contains("绝不按 0 处理");
    assertThat(c.bufferNote()).contains("三个不同的东西");
    assertThat(c.exposureNote()).contains("缓冲不足");
    assertThat(c.writebackStates())
        .extracting(ResultCapabilityOption::code)
        .contains("UNKNOWN", "SUCCEEDED", "FAILED", "PENDING");
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() {
    UUID policyA = policy(actorA);
    sellablePolicy(actorA, "DEFAULT", null, "0.2");
    UUID runA = run(actorA, policyA);
    observe(actorA, runA, shopOne, "SUCCEEDED", "40", "40", "5", null, OBSERVED_AT);

    UUID policyB = policy(actorB);
    sellablePolicy(actorB, "DEFAULT", null, "0.2");
    UUID runB = run(actorB, policyB);
    observe(actorB, runB, shopOne, "SUCCEEDED", "30", "30", "25", null, OBSERVED_AT);

    var pageA =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.results(new AllocationResultQuery(null, null, null, null, null, null, null, null, 0, 50)));
    var pageB =
        access.run(
            actorB,
            "warehouse.inventory.read",
            () -> service.results(new AllocationResultQuery(null, null, null, null, null, null, null, null, 0, 50)));
    assertThat(pageA.total()).isEqualTo(3);
    assertThat(pageB.total()).isEqualTo(3);
    // 读到的是自己的那一批：A 店的已用是 5，B 店是 25。
    assertThat(row(actorA, runA, shopOne).consumedQuantity()).isEqualByComparingTo("5");
    assertThat(row(actorB, runB, shopOne).consumedQuantity()).isEqualByComparingTo("25");

    // 拿 A 的分摊轮次去 B 那里查 → 查不到任何行。
    var bSeesA =
        access.run(
            actorB,
            "warehouse.inventory.read",
            () -> service.results(new AllocationResultQuery(runA, null, null, null, null, null, null, null, 0, 50)));
    assertThat(bSeesA.total()).as("B 读不到 A 的分摊结果").isZero();

    // 回写观测同样隔离。
    assertThat(
            access
                .run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> service.writebacks(null, null, null, null, null, 0, 50))
                .observations())
        .hasSize(1);
    assertThat(
            access
                .run(
                    actorB,
                    "warehouse.inventory.read",
                    () -> service.writebacks(runA, null, null, null, null, 0, 50))
                .total())
        .as("B 读不到 A 的回写观测")
        .isZero();
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    UUID policyA = policy(actorA);
    sellablePolicy(actorA, "DEFAULT", null, "0.2");
    UUID runA = run(actorA, policyA);
    observe(actorA, runA, shopOne, "SUCCEEDED", "40", "40", "5", null, OBSERVED_AT);

    // 身份是 B、伪造 X-Tenant-Id 指向 A → B 只能看到 B 自己的（空）
    http.perform(
            get("/warehouse/inventory/allocation-results/results")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));

    // 身份是 A、伪造 B 的头 → A 仍然只看得到自己的
    http.perform(
            get("/warehouse/inventory/allocation-results/results")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(3));

    // 写入路径同样不受伪造头影响：B 的请求写不进 A 的租户。
    http.perform(
            post("/warehouse/inventory/allocation-results/writebacks")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString())
                .contentType("application/json")
                .content(
                    "{\"runId\":\""
                        + runA
                        + "\",\"sourceSystem\":\"CHANNEL\",\"referenceId\":\""
                        + shopTwo
                        + "\",\"warehouseId\":\""
                        + wh
                        + "\",\"state\":\"SUCCEEDED\",\"attemptedQuantity\":30,"
                        + "\"writtenQuantity\":30,\"sourceKind\":\"MANUAL\","
                        + "\"sourceReference\":\"forged\",\"asOf\":\"2026-10-19T10:00:00+08:00\"}"))
        .andExpect(status().is4xxClientError());
    assertThat(
            access
                .run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> service.writebacks(runA, null, null, null, null, 0, 50))
                .total())
        .as("伪造头的写入必须没有落进 A 的租户")
        .isEqualTo(1);
  }

  @Test
  void 断言三_连接池归还后不串租户() throws Exception {
    UUID policyA = policy(actorA);
    sellablePolicy(actorA, "DEFAULT", null, "0.2");
    UUID runA = run(actorA, policyA);
    observe(actorA, runA, shopOne, "SUCCEEDED", "40", "40", "5", null, OBSERVED_AT);

    UUID policyB = policy(actorB);
    sellablePolicy(actorB, "DEFAULT", null, "0.2");
    UUID runB = run(actorB, policyB);
    observe(actorB, runB, shopOne, "SUCCEEDED", "30", "30", "25", null, OBSERVED_AT);

    // 池大小为 1，同一条连接被反复借还：交替跑两个租户 20 轮，
    // 每一轮都要同时校验回查、核对与写入三条路径都不串租户。
    for (int i = 0; i < 20; i++) {
      var pageA =
          access.run(
              actorA,
              "warehouse.inventory.read",
              () -> service.results(new AllocationResultQuery(null, null, null, null, null, null, null, null, 0, 50)));
      var pageB =
          access.run(
              actorB,
              "warehouse.inventory.read",
              () -> service.results(new AllocationResultQuery(null, null, null, null, null, null, null, null, 0, 50)));
      assertThat(pageA.total()).isEqualTo(3);
      assertThat(pageB.total()).isEqualTo(3);
      assertThat(row(actorA, runA, shopOne).consumedQuantity()).isEqualByComparingTo("5");
      assertThat(row(actorB, runB, shopOne).consumedQuantity()).isEqualByComparingTo("25");

      var reportA =
          access.run(
              actorA,
              "warehouse.inventory.read",
              () ->
                  service.reconciliation(
                      new AllocationResultQuery(null, null, null, null, null, null, null, null, 0, 50)));
      assertThat(reportA.runs()).as("A 只看得到自己那一轮").hasSize(1);
      assertThat(reportA.runs().get(0).runId()).isEqualTo(runA);

      var reportB =
          access.run(
              actorB,
              "warehouse.inventory.read",
              () ->
                  service.reconciliation(
                      new AllocationResultQuery(null, null, null, null, null, null, null, null, 0, 50)));
      assertThat(reportB.runs()).hasSize(1);
      assertThat(reportB.runs().get(0).runId()).isEqualTo(runB);

      // 写入路径：B 追加一条观测，A 的观测数必须纹丝不动。
      final int round = i;
      access.run(
          actorB,
          "warehouse.inventory.update",
          () ->
              service.record(
                  new AllocationWritebackInput(
                      runB,
                      "CHANNEL",
                      shopTwo,
                      "店二",
                      idFor(b, 3),
                      "SUCCEEDED",
                      new BigDecimal("30"),
                      new BigDecimal("30"),
                      new BigDecimal("1"),
                      "",
                      "API",
                      "loop-" + round,
                      OBSERVED_AT.plusMinutes(round)),
                  user));
      assertThat(
              access
                  .run(
                      actorA,
                      "warehouse.inventory.read",
                      () -> service.writebacks(runA, null, null, null, null, 0, 50))
                  .total())
          .as("B 写了这么多次，A 的观测数必须纹丝不动")
          .isEqualTo(1);
    }
  }
}
