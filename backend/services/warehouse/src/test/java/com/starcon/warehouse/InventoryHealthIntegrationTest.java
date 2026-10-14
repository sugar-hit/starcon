package com.starcon.warehouse;

import static com.starcon.warehouse.InventoryHealthModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 7.6.2.1 库存健康：指标可算性、**不可用指标如实标记**、准确率红线、 负库存在结构上不可能（实跑 DB 约束证明），以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接： 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>夹具写法与 {@link InventoryTransferIntegrationTest} 完全一致（同样的 Testcontainers、 同样的 fake identity
 * server、同样的按租户派生 UUID、同样的 TRUNCATE 清场）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InventoryHealthIntegrationTest {
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

  static final UUID leA = idFor(a, 1);
  static final UUID skuA = idFor(a, 2);
  static final UUID whSh = idFor(a, 4);
  static final UUID qSellable = idFor(a, 6);
  static final UUID locSh1 = idFor(a, 8);

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-health-test-" + tenant + "-" + ordinal).getBytes());
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
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer read-only",
          actor(user, a, null, List.of(grant("ALL", List.of("warehouse.inventory.read")))));
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

  @Autowired InventoryHealthService service;
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
    // TRUNCATE 不触发行级触发器（append-only 只挡 UPDATE/DELETE），以超级用户连接执行可绕过
    // FORCE RLS —— 只用于测试夹具重建，不给应用角色开这个口子。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate inventory_health_verdict, inventory_health_metric, inventory_health_assessment,"
              + " inventory_reconciliation_finding, inventory_reconciliation_run,"
              + " stocktake_line, stocktake_task, stocktake_sheet,"
              + " stock_bucket, warehouse_location, inventory_entry, inventory_transaction,"
              + " inventory_balance, inventory_quality_status, warehouse, sku_ref,"
              + " legal_entity_ref restart identity cascade");
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
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone)"
                    + " values(?,?,?,?,?,?)",
                idFor(tenant, 4),
                tenant,
                "WH-SH",
                "上海仓",
                "CN",
                "Asia/Shanghai");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role)"
                    + " values(?,?,?,?,?)",
                idFor(tenant, 6),
                tenant,
                "SELLABLE",
                "可售",
                "SELLABLE");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,pickable)"
                    + " values(?,?,?,?,?,'STORAGE',true)",
                idFor(tenant, 8),
                tenant,
                idFor(tenant, 4),
                "SH-A-01",
                "库位 SH-A-01");
            return null;
          });
    }
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "warehouse.inventory.read", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "warehouse.inventory.read", work);
  }

  // ================================================================ 夹具

  void seedBalance(UUID tenant, String onHand) {
    access.run(
        actorFor(tenant),
        "warehouse.inventory.read",
        () -> {
          var balanceId = UUID.randomUUID();
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,0)",
              balanceId,
              tenant,
              idFor(tenant, 2),
              idFor(tenant, 4),
              idFor(tenant, 1),
              idFor(tenant, 6),
              new BigDecimal(onHand));
          sql.execute(
              "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,"
                  + "location_id,lot_id,quality_status_id,balance_id,on_hand,reserved,"
                  + "last_movement_at) values(?,?,?,?,?,?,null,?,?,?,0,now())",
              UUID.randomUUID(),
              tenant,
              idFor(tenant, 1),
              idFor(tenant, 2),
              idFor(tenant, 4),
              idFor(tenant, 8),
              idFor(tenant, 6),
              balanceId,
              new BigDecimal(onHand));
          return null;
        });
  }

  /**
   * 直接插一条核对结论（V54 已 append-only，但 INSERT 是允许的 —— 核对服务本身就是这么写的）。
   *
   * <p>刻意只写结论不写 finding：本模块只需要「核了多少行、对不上多少行」这一个口径。
   */
  void seedReconciliation(
      UUID tenant, UUID warehouse, long checked, long discrepancies, OffsetDateTime at) {
    access.run(
        actorFor(tenant),
        "warehouse.inventory.read",
        () -> {
          // 夹具落点往前挪一小时并截断到秒：默认评估窗口是「左闭右开、右边界截断到秒」的
          // [now-30d, now)，直接用 now() 会因为带毫秒而落在窗口外，夹具自己把自己排除掉了。
          var when = at.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).minusHours(1);
          sql.execute(
              "insert into inventory_reconciliation_run(created_at,id,tenant_id,scope_type,scope_key,"
                  + "scope_label,window_from,window_to,status,checked_balance_count,"
                  + "discrepancy_count,verdict_note,triggered_by)"
                  // created_at 的 cast 在第 1 位、window 的两个 cast 在第 7/8 位，末位是 uuid 普通绑定。
                  + " values (?::timestamptz,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,?,?,?)",
              when,
              UUID.randomUUID(),
              tenant,
              warehouse == null ? "ALL" : "WAREHOUSE",
              warehouse,
              warehouse == null ? "全部余额行" : "仓库 WH-SH",
              when.minusDays(30),
              when,
              discrepancies == 0 ? "CONSISTENT" : "DISCREPANT",
              checked,
              discrepancies,
              "夹具",
              user);
          return null;
        });
  }

  /**
   * 建一张盘点单并录一行实盘数。
   *
   * <p>{@code countedQty} 与账面数一致则该行「准确」，不一致则产生差异。 V59 的冻结触发器只挡改列，不挡 INSERT，所以可以一次把行建到位。
   */
  void seedStocktake(
      UUID tenant, String code, String book, String counted, OffsetDateTime frozenAt) {
    access.run(
        actorFor(tenant),
        "warehouse.inventory.read",
        () -> {
          // 同 seedReconciliation：落点前挪一小时并截断到秒，确保落在默认评估窗口内。
          var when = frozenAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).minusHours(1);
          var sheetId = UUID.randomUUID();
          sql.execute(
              "insert into stocktake_sheet(id,tenant_id,code,scope_type,scope_key,scope_label,"
                  + "task_unit,frozen_at,status,note,created_by_user_id)"
                  + " values (?,?,?,'WAREHOUSE',?,?,'BY_LOCATION',?::timestamptz,'COUNTED','',?)",
              sheetId,
              tenant,
              code,
              idFor(tenant, 4),
              "上海仓",
              when,
              user);
          var taskId = UUID.randomUUID();
          sql.execute(
              "insert into stocktake_task(id,tenant_id,sheet_id,task_unit,task_key,task_code,task_name)"
                  + " values(?,?,?,'BY_LOCATION',?,?,'库位 SH-A-01')",
              taskId,
              tenant,
              sheetId,
              idFor(tenant, 8),
              "SH-A-01");
          sql.execute(
              "insert into stocktake_line(id,tenant_id,sheet_id,bucket_id,legal_entity_id,sku_id,"
                  + "warehouse_id,location_id,lot_id,quality_status_id,sku_code,sku_name,"
                  + "warehouse_code,location_code,lot_code,quality_status_code,book_on_hand,"
                  + "task_id,counted_qty,counted_at,counted_by_user_id)"
                  + " values(?,?,?,(select id from stock_bucket where tenant_id=? limit 1),?,?,?,?,"
                  + "null,?,?,'商品一','WH-SH','SH-A-01','','SELLABLE',?,?,?,?::timestamptz,?)",
              UUID.randomUUID(),
              tenant,
              sheetId,
              tenant,
              idFor(tenant, 1),
              idFor(tenant, 2),
              idFor(tenant, 4),
              idFor(tenant, 8),
              idFor(tenant, 6),
              "SKU-1",
              new BigDecimal(book),
              taskId,
              counted == null ? null : new BigDecimal(counted),
              counted == null ? null : when,
              counted == null ? null : user);
          return null;
        });
  }

  InventoryHealthMetric metric(InventoryHealthScorecard card, HealthMetricType type) {
    return card.metrics().stream().filter(m -> m.metricType() == type).findFirst().orElseThrow();
  }

  // ================================================================ 准确率：核对口径

  @Test
  void 核对口径_准确率可算且扣分可追溯() {
    seedBalance(a, "100");
    seedReconciliation(a, null, 100, 5, OffsetDateTime.now(ZoneOffset.ofHours(8)));

    var result = asA(() -> service.assess(new InventoryHealthQuery(null, null, null, null), user));
    var accuracy = metric(result.tenant(), HealthMetricType.ACCURACY);

    assertThat(accuracy.availability()).isEqualTo(MetricAvailability.AVAILABLE);
    assertThat(accuracy.value()).isEqualByComparingTo(new BigDecimal("0.950000"));
    assertThat(accuracy.source()).isEqualTo("RECONCILIATION");
    assertThat(accuracy.numerator()).isEqualByComparingTo("95");
    assertThat(accuracy.denominator()).isEqualByComparingTo("100");

    // 扣分 = (1 − 0.95) × 70 = 3.50，得分 96.50
    assertThat(metric(result.tenant(), HealthMetricType.ACCURACY).deduction())
        .isEqualByComparingTo("3.50");
    assertThat(result.tenant().score()).isEqualByComparingTo("96.50");
    assertThat(result.tenant().grade()).isEqualTo(HealthGrade.HEALTHY);
    // 0.95 < 0.99 → 阻断（蓝图的硬指标）
    assertThat(result.tenant().blocked()).isTrue();
    assertThat(result.tenant().blockReason()).contains("0.95").contains("0.99");
  }

  @Test
  void 核对口径_准确率达标则不阻断() {
    seedBalance(a, "100");
    seedReconciliation(a, null, 1000, 2, OffsetDateTime.now(ZoneOffset.ofHours(8)));

    var result = asA(() -> service.assess(new InventoryHealthQuery(null, null, null, null), user));

    assertThat(metric(result.tenant(), HealthMetricType.ACCURACY).value())
        .isEqualByComparingTo("0.998000");
    assertThat(result.tenant().blocked()).isFalse();
    assertThat(result.tenant().blockReason()).isEmpty();
    assertThat(result.tenant().grade()).isEqualTo(HealthGrade.HEALTHY);
  }

  @Test
  void 核对口径_取窗口内最后一次批次而不是平均() {
    seedBalance(a, "100");
    var base = OffsetDateTime.now(ZoneOffset.ofHours(8));
    // 第一次很糟（100 行差 50 行 → 0.5），最后一次很好（100 行差 0 行 → 1.0）。
    // 若按窗口内平均会得到 0.75，按最后一次应得到 1.0。
    seedReconciliation(a, null, 100, 50, base.minusDays(5));
    seedReconciliation(a, null, 100, 0, base.minusDays(1));

    var result = asA(() -> service.assess(new InventoryHealthQuery(null, null, null, null), user));

    assertThat(metric(result.tenant(), HealthMetricType.ACCURACY).value())
        .isEqualByComparingTo("1.000000");
  }

  // ================================================================ 准确率：盘点口径

  @Test
  void 无核对批次时回落到盘点口径() {
    seedBalance(a, "100");
    seedStocktake(a, "PD-1", "100", "100", OffsetDateTime.now(ZoneOffset.ofHours(8)));
    seedStocktake(a, "PD-2", "100", "90", OffsetDateTime.now(ZoneOffset.ofHours(8)).minusDays(1));

    var result = asA(() -> service.assess(new InventoryHealthQuery(null, null, null, null), user));
    var accuracy = metric(result.tenant(), HealthMetricType.ACCURACY);

    // 2 行已录实盘，1 行一致 → 0.5
    assertThat(accuracy.availability()).isEqualTo(MetricAvailability.AVAILABLE);
    assertThat(accuracy.source()).isEqualTo("STOCKTAKE");
    assertThat(accuracy.value()).isEqualByComparingTo("0.500000");
    assertThat(accuracy.numerator()).isEqualByComparingTo("1");
    assertThat(accuracy.denominator()).isEqualByComparingTo("2");
  }

  @Test
  void 未录实盘数的盘点行不计入准确率() {
    seedBalance(a, "100");
    seedStocktake(a, "PD-1", "100", null, OffsetDateTime.now(ZoneOffset.ofHours(8)));

    var result = asA(() -> service.assess(new InventoryHealthQuery(null, null, null, null), user));

    // 没有任何已录实盘行 → 没有样本，不是「准确率 0」
    assertThat(metric(result.tenant(), HealthMetricType.ACCURACY).availability())
        .isEqualTo(MetricAvailability.NO_DATA);
  }

  // ================================================================ 不可算指标必须如实标记

  @Test
  void 四个依赖未交付上游的指标标记不可用且不带值() {
    seedBalance(a, "100");
    seedReconciliation(a, null, 100, 0, OffsetDateTime.now(ZoneOffset.ofHours(8)));

    var result = asA(() -> service.assess(new InventoryHealthQuery(null, null, null, null), user));

    // 六个指标行恒定存在
    assertThat(result.tenant().metrics()).hasSize(HealthMetricType.all().size());
    assertThat(result.tenant().totalMetricCount()).isEqualTo(6);

    for (var type :
        List.of(
            HealthMetricType.TURNOVER,
            HealthMetricType.STOCKOUT_RATE,
            HealthMetricType.OVERSTOCK_RATE,
            HealthMetricType.STAGNANT_SHARE)) {
      var m = metric(result.tenant(), type);
      assertThat(m.availability()).as("%s 应标记不可用", type).isEqualTo(MetricAvailability.UNAVAILABLE);
      assertThat(m.value()).as("%s 不可用时不得带值", type).isNull();
      assertThat(m.blockedByModule()).as("%s 必须记下阻塞模块", type).isNotBlank();
      assertThat(m.basis()).as("%s 必须给出原因", type).isNotBlank();
    }
    assertThat(metric(result.tenant(), HealthMetricType.TURNOVER).blockedByModule())
        .isEqualTo("13");
    assertThat(metric(result.tenant(), HealthMetricType.STAGNANT_SHARE).blockedByModule())
        .isEqualTo("7.6.1");

    // 只有 2 个可算 → 覆盖度必须如实报出来
    assertThat(result.tenant().availableMetricCount()).isEqualTo(2);
  }

  @Test
  void 不可用指标在落库后仍然不可用且不带值() {
    seedBalance(a, "100");
    seedReconciliation(a, null, 100, 0, OffsetDateTime.now(ZoneOffset.ofHours(8)));
    asA(() -> service.assess(new InventoryHealthQuery(null, null, null, null), user));

    // 从库里读回来，而不是从内存对象 —— DB 的 CHECK 也必须接受这个组合。
    // 按 scope 过滤：一次评估有「租户 + 每个仓」多份，各六行。
    var rows =
        asA(
            () ->
                sql.fetch(
                    "select m.metric_type, m.metric_state, m.value, m.blocked_by_module, m.reason"
                        + " from inventory_health_metric m"
                        + " join inventory_health_assessment a on a.tenant_id = m.tenant_id"
                        + " and a.id = m.assessment_id"
                        + " where a.scope_type = 'TENANT'"));
    assertThat(rows).hasSize(6);
    for (var r : rows) {
      var state = r.get("metric_state", String.class);
      var value = r.get("value", BigDecimal.class);
      if (state.equals("AVAILABLE")) {
        assertThat(r.get("value", BigDecimal.class)).as(state).isNotNull();
      } else {
        assertThat(value).as(state + " 不得带值").isNull();
        assertThat(r.get("reason", String.class)).as(state + " 必须给出原因").isNotBlank();
        if (state.equals("UNAVAILABLE")) {
          // NO_DATA 是"上游在、但这个窗口没样本"，它不需要阻塞模块；
          // UNAVAILABLE 才是"上游没交付"，必须记下是谁。
          assertThat(r.get("blocked_by_module", String.class))
              .as("UNAVAILABLE 必须记阻塞模块")
              .isNotNull();
        }
      }
    }
  }

  // ================================================================ 负库存

  @Test
  void 负库存发生次数恒为零且由DB约束证明() {
    seedBalance(a, "100");
    seedReconciliation(a, null, 100, 0, OffsetDateTime.now(ZoneOffset.ofHours(8)));

    var result = asA(() -> service.assess(new InventoryHealthQuery(null, null, null, null), user));
    var negative = metric(result.tenant(), HealthMetricType.NEGATIVE_STOCK);

    assertThat(negative.availability()).isEqualTo(MetricAvailability.AVAILABLE);
    assertThat(negative.value()).isEqualByComparingTo("0");

    // 这个 0 不是编的：DB 层面确实写不进负库存（V1 / V2 的 CHECK）。
    // 不断言具体约束名：一条 INSERT 可能同时违反多条 CHECK，PG 报哪条不固定。
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,"
                                + "legal_entity_id,quality_status_id,on_hand,reserved)"
                                + " values(?,?,?,?,?,?,?,0)",
                            UUID.randomUUID(),
                            a,
                            skuA,
                            whSh,
                            leA,
                            qSellable,
                            new BigDecimal("-1"))))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("inventory_balance");

    // 预占成负数（available<0）同样被挡住
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update inventory_balance set reserved = on_hand + 1 where tenant_id = ?",
                            a)))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("inventory_balance");
  }

  // ================================================================ 覆盖度与评级

  @Test
  void 无任何核对证据时不得报健康() {
    seedBalance(a, "100");

    var result = asA(() -> service.assess(new InventoryHealthQuery(null, null, null, null), user));
    var card = result.tenant();

    // 负库存恒为 0 会白送满分 —— 若照常评级就会得到「没核过账但显示健康」
    assertThat(card.score()).isEqualByComparingTo("100.00");
    assertThat(card.grade()).isEqualTo(HealthGrade.UNVERIFIABLE);
    assertThat(card.blocked()).isTrue();
    assertThat(card.blockReason()).contains("无证据");
    assertThat(metric(card, HealthMetricType.ACCURACY).availability())
        .isEqualTo(MetricAvailability.NO_DATA);
  }

  @Test
  void 按仓库打分_仓库口径只认本仓的核对批次() {
    seedBalance(a, "100");
    var base = OffsetDateTime.now(ZoneOffset.ofHours(8));
    // 全仓核对很好（1.0），但上海仓自己的批次很差（0.5）→ 仓库口径应取 0.5
    seedReconciliation(a, null, 100, 0, base.minusDays(2));
    seedReconciliation(a, whSh, 100, 50, base.minusDays(1));

    var result = asA(() -> service.assess(new InventoryHealthQuery(null, null, null, null), user));

    var warehouseCard =
        result.warehouses().stream()
            .filter(c -> whSh.equals(c.scopeKey()))
            .findFirst()
            .orElseThrow();
    assertThat(metric(warehouseCard, HealthMetricType.ACCURACY).value())
        .isEqualByComparingTo("0.500000");
    // 全租户口径取的是 ALL 批次，不是仓库批次
    assertThat(metric(result.tenant(), HealthMetricType.ACCURACY).value())
        .isEqualByComparingTo("1.000000");
  }

  // ================================================================ 门槛与问题清单

  @Test
  void 准确率红线低于阈值时阻断且给出证据() {
    seedBalance(a, "100");
    seedReconciliation(a, null, 100, 5, OffsetDateTime.now(ZoneOffset.ofHours(8)));
    asA(() -> service.assess(new InventoryHealthQuery(null, null, null, null), user));

    var gate = asA(() -> service.gate(new InventoryHealthQuery(null, null, null, null)));

    assertThat(gate.blocked()).isTrue();
    assertThat(gate.blockReason()).contains("低于阈值").contains("RECONCILIATION");
    assertThat(gate.accuracyState()).isEqualTo(MetricAvailability.AVAILABLE);
    assertThat(gate.lastReconciledAt()).isNotNull();
    assertThat(gate.issues())
        .extracting(InventoryHealthIssue::severity)
        .contains(HealthIssueSeverity.CRITICAL);
  }

  @Test
  void 尚未评估时红线按最保守口径阻断() {
    var gate = asA(() -> service.gate(new InventoryHealthQuery(null, null, null, null)));

    assertThat(gate.blocked()).isTrue();
    assertThat(gate.accuracyState()).isEqualTo(MetricAvailability.NO_DATA);
    assertThat(gate.blockReason()).contains("尚无库存健康评估结论");
  }

  @Test
  void 阈值超出0到1被拒() {
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.assess(
                            new InventoryHealthQuery(null, null, null, new BigDecimal("1.5")),
                            user)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("0..1");
  }

  @Test
  void 窗口起点不早于终点被拒() {
    var at = OffsetDateTime.now(ZoneOffset.ofHours(8));
    assertThatThrownBy(
            () -> asA(() -> service.assess(new InventoryHealthQuery(at, at, null, null), user)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("左闭右开");
  }

  @Test
  void 问题清单与扣分同源且带证据() {
    seedBalance(a, "100");
    seedReconciliation(a, null, 100, 5, OffsetDateTime.now(ZoneOffset.ofHours(8)));
    asA(() -> service.assess(new InventoryHealthQuery(null, null, null, null), user));

    var page = asA(() -> service.issues(new InventoryHealthQuery(null, null, null, null), 0, 50));

    assertThat(page.total()).isPositive();
    assertThat(page.criticalCount()).isPositive();
    assertThat(page.issues())
        .allSatisfy(
            i -> {
              assertThat(i.recommendation()).isNotBlank();
              assertThat(i.evidence()).isNotBlank();
              assertThat(i.detail()).isNotBlank();
            });
    // 覆盖度不足必须作为问题说出来，否则部分维度的分数会被当成整体健康度
    assertThat(page.issues()).extracting(InventoryHealthIssue::title).contains("评分只覆盖部分指标");
    // 严重度排序：CRITICAL 在最前
    assertThat(page.issues().get(0).severity()).isEqualTo(HealthIssueSeverity.CRITICAL);
  }

  @Test
  void 趋势按两个口径分别成序列() {
    seedBalance(a, "100");
    var base = OffsetDateTime.now(ZoneOffset.ofHours(8));
    seedReconciliation(a, null, 100, 10, base.minusDays(3));
    seedReconciliation(a, null, 100, 0, base.minusDays(2));
    seedStocktake(a, "PD-1", "100", "100", base.minusDays(1));

    var trend = asA(() -> service.trend(new InventoryHealthQuery(null, null, null, null)));

    assertThat(trend.points())
        .extracting(InventoryHealthTrendPoint::source)
        .containsExactly("RECONCILIATION", "RECONCILIATION", "STOCKTAKE");
    assertThat(trend.points().get(0).accuracy()).isEqualByComparingTo("0.900000");
    assertThat(trend.points().get(1).accuracy()).isEqualByComparingTo("1.000000");
    assertThat(trend.points().get(2).accuracy()).isEqualByComparingTo("1.000000");
    // 每个点都要能指出是哪一次核对 / 哪一张盘点单
    assertThat(trend.points()).allSatisfy(p -> assertThat(p.sourceId()).isNotNull());
  }

  // ================================================================ 时间戳写回带时区

  @Test
  void 窗口时间戳写回带时区偏移() {
    seedBalance(a, "100");
    seedReconciliation(a, null, 100, 0, OffsetDateTime.now(ZoneOffset.ofHours(8)));
    var from = OffsetDateTime.now(ZoneOffset.ofHours(8)).minusDays(10).withNano(0);
    var to = OffsetDateTime.now(ZoneOffset.ofHours(8)).withNano(0);

    asA(() -> service.assess(new InventoryHealthQuery(from, to, null, null), user));

    var stored =
        asA(
            () ->
                sql.fetchOne(
                    "select a.window_from, a.window_to, a.created_at from inventory_health_assessment a"
                        + " where a.scope_type = 'TENANT' limit 1"));
    // 断言写回的值而不是重新格式化：timestamptz 必须带着 +08:00 回来
    assertThat(stored.get("window_from", OffsetDateTime.class)).isEqualTo(from);
    assertThat(stored.get("window_to", OffsetDateTime.class)).isEqualTo(to);
    assertThat(stored.get("window_from", OffsetDateTime.class).getOffset())
        .isEqualTo(ZoneOffset.ofHours(8));
  }

  // ================================================================ 端点

  @Test
  void 端点返回六项指标与红线判定() throws Exception {
    seedBalance(a, "100");
    seedReconciliation(a, null, 100, 5, OffsetDateTime.now(ZoneOffset.ofHours(8)));
    http.perform(
            post("/warehouse/inventory/health/assess")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenant.totalMetricCount").value(6))
        .andExpect(jsonPath("$.tenant.availableMetricCount").value(2))
        .andExpect(jsonPath("$.tenant.metrics.length()").value(6))
        .andExpect(jsonPath("$.tenant.blocked").value(true));

    http.perform(get("/warehouse/inventory/health").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2))
        .andExpect(jsonPath("$.scorecards[0].metrics.length()").value(6));

    http.perform(get("/warehouse/inventory/health/gate").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.blocked").value(true))
        .andExpect(jsonPath("$.accuracyState").value("AVAILABLE"));

    http.perform(
            get("/warehouse/inventory/health/trend").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.points.length()").value(1));

    http.perform(
            get("/warehouse/inventory/health/issues").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThan(0)));
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() {
    seedBalance(a, "100");
    seedReconciliation(a, null, 100, 0, OffsetDateTime.now(ZoneOffset.ofHours(8)));
    seedBalance(b, "77");
    seedReconciliation(b, null, 100, 40, OffsetDateTime.now(ZoneOffset.ofHours(8)));

    asA(() -> service.assess(new InventoryHealthQuery(null, null, null, null), user));
    asB(() -> service.assess(new InventoryHealthQuery(null, null, null, null), user));

    var pa = asA(() -> service.page(new InventoryHealthQuery(null, null, null, null), 0, 50));
    var pb = asB(() -> service.page(new InventoryHealthQuery(null, null, null, null), 0, 50));

    // A 账全对（准确率 1.0，未阻断），B 有 40% 差异（准确率 0.6，阻断）
    assertThat(pa.scorecards()).hasSize(2);
    assertThat(pb.scorecards()).hasSize(2);
    assertThat(metric(pa.scorecards().get(0), HealthMetricType.ACCURACY).value())
        .isEqualByComparingTo("1.000000");
    assertThat(metric(pb.scorecards().get(0), HealthMetricType.ACCURACY).value())
        .isEqualByComparingTo("0.600000");
    assertThat(pa.scorecards().get(0).blocked()).isFalse();
    assertThat(pb.scorecards().get(0).blocked()).isTrue();

    // 落库的评估行数也互不可见：A 只看得到自己那 2 张评分卡（1 租户 + 1 仓）
    var countA =
        asA(() -> sql.fetchOne("select count(*) from inventory_health_verdict").get(0, Long.class));
    var countB =
        asB(() -> sql.fetchOne("select count(*) from inventory_health_verdict").get(0, Long.class));
    assertThat(countA).isEqualTo(2L);
    assertThat(countB).isEqualTo(2L);
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    seedBalance(a, "100");
    seedReconciliation(a, null, 100, 0, OffsetDateTime.now(ZoneOffset.ofHours(8)));
    seedBalance(b, "77");
    seedReconciliation(b, null, 100, 40, OffsetDateTime.now(ZoneOffset.ofHours(8)));
    asA(() -> service.assess(new InventoryHealthQuery(null, null, null, null), user));
    asB(() -> service.assess(new InventoryHealthQuery(null, null, null, null), user));

    // 身份是 B 但伪造 X-Tenant-Id 指向 A → 只能看到 B 自己的（准确率 0.6，阻断）
    http.perform(
            get("/warehouse/inventory/health")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.scorecards[0].blocked").value(true));

    // 反向：身份是 A 伪造 B 的头也拿不到 B 的数据（未阻断）
    http.perform(
            get("/warehouse/inventory/health")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.scorecards[0].blocked").value(false));

    // 红线端点同样不受伪造头影响
    http.perform(
            get("/warehouse/inventory/health/gate")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accuracyRate").value(0.6));

    // 未认证 401
    http.perform(get("/warehouse/inventory/health")).andExpect(status().isUnauthorized());
    // 触发评估要 update 权限：只有 read 的角色不能落结论
    http.perform(
            post("/warehouse/inventory/health/assess")
                .header("Authorization", "Bearer read-only")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    seedBalance(a, "100");
    seedReconciliation(a, null, 100, 0, OffsetDateTime.now(ZoneOffset.ofHours(8)));
    seedBalance(b, "77");
    seedReconciliation(b, null, 100, 40, OffsetDateTime.now(ZoneOffset.ofHours(8)));
    asA(() -> service.assess(new InventoryHealthQuery(null, null, null, null), user));
    asB(() -> service.assess(new InventoryHealthQuery(null, null, null, null), user));

    // 池大小为 1，同一条连接被反复借还：交替访问两个租户 20 轮，
    // 每一轮都校验评分卡、红线与问题清单三条路径都不串租户。
    for (int i = 0; i < 20; i++) {
      var ga = asA(() -> service.gate(new InventoryHealthQuery(null, null, null, null)));
      var gb = asB(() -> service.gate(new InventoryHealthQuery(null, null, null, null)));

      assertThat(ga.blocked()).as("A 轮 %d：账全对不该被阻断", i).isFalse();
      assertThat(ga.accuracyRate()).as("A 轮 %d", i).isEqualByComparingTo("1.000000");

      assertThat(gb.blocked()).as("B 轮 %d：有差异必须阻断", i).isTrue();
      assertThat(gb.accuracyRate()).as("B 轮 %d", i).isEqualByComparingTo("0.600000");
    }
  }
}
