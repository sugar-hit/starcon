package com.starcon.warehouse;

import static com.starcon.warehouse.LocationUtilizationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 8.5.2.1 库位利用率。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>本测试钉住八件「错了会误导人」的事：
 *
 * <ul>
 *   <li><b>容量利用率真用 capacity 列。</b>库位容量是 8.1.2.1（V105）补的
 *       {@code warehouse_location.capacity}，本模块读它算分母 —— 不是拿一个占位容积常量。
 *       用例里同一个库位改一次 capacity，报表里的利用率必须跟着变。
 *   <li><b>三处分母边界一律 null，绝不 NaN、绝不假装 100%。</b>容量未量 / 容量为 0 /
 *       在手为 0，三种情况的断言都单独写死。
 *   <li><b>出库量摊分配平。</b>{@code Σ库位摊分出库量 == 仓库真实出库量} ——
 *       同一 SKU 散在两个库位时最容易被重复计数，这条断言就是防它的。
 *   <li><b>NUMERIC 精确</b>：0.1×3 读回必须是 0.3，不是 0.30000000000000004。
 *   <li><b>热力图是真数据驱动</b>：强度来自利用率/在手相对值，metric 字段说清来源，
 *       且「容量未量」与「量过」走不同 metric。
 *   <li><b>租户隔离三断言</b>实跑 + FORCE RLS 逐张断言。
 *   <li><b>建议由真实数据触发</b>：没有触发条件就不该出现（空仓不该冒出任何建议）。
 *   <li><b>合计不受筛选影响</b>。
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class LocationUtilizationIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      List.of("warehouse.facilities.read", "warehouse.facilities.create", "warehouse.facilities.update");

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-location-utilization-" + tenant + "-" + ordinal).getBytes());
  }

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenant, "Test", UUID.randomUUID(), null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(), grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static {
    pg.start();
    try {
      try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
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
            byte[] body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer read-only",
          actor(user, a, List.of(grant("ALL", List.of("warehouse.facilities.read")))));
      actors.put(
          "Bearer no-permission",
          actor(user, a, List.of(grant("ALL", List.of("warehouse.other.read")))));
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

  @Autowired LocationUtilizationService service;
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
          "truncate stock_bucket, inventory_balance, warehouse_location, inventory_quality_status,"
              + " legal_entity_ref, sku_ref, warehouse restart identity cascade");
      s.execute("truncate inventory_entry, inventory_transaction restart identity cascade");
    }
    for (var tenant : List.of(a, b)) {
      access.run(
          actorFor(tenant),
          "warehouse.facilities.read",
          () -> {
            sql.execute(
                "insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 1), tenant, "LE-1", "主体一");
            for (int i = 2; i <= 6; i++) {
              sql.execute(
                  "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                  idFor(tenant, i), tenant, "SKU-" + (i - 1), "商品" + (i - 1));
            }
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 7), tenant, "SELLABLE", "可售", "SELLABLE");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,kind,country_code,timezone,"
                    + "default_legal_entity_id,status) values(?,?,?,?,?,?,?,?,?)",
                idFor(tenant, 8), tenant, "WH-" + (tenant.equals(a) ? "A" : "B"), "本租仓库",
                "LOCAL", tenant.equals(a) ? "CN" : "US", "Asia/Shanghai", idFor(tenant, 1), "ACTIVE");
            return null;
          });
    }
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  UUID wh(UUID tenant) {
    return idFor(tenant, 8);
  }

  UUID sku(UUID tenant, int ordinal) {
    return idFor(tenant, ordinal);
  }

  LocationUtilizationReport report(UUID tenant) {
    return access.run(
        actorFor(tenant), "warehouse.facilities.read",
        () -> service.report(wh(tenant), new LocationUtilizationQuery(null, null, null, null, null, null)));
  }

  LocationUtilizationReport report(UUID tenant, LocationUtilizationQuery q) {
    return access.run(
        actorFor(tenant), "warehouse.facilities.read", () -> service.report(wh(tenant), q));
  }

  // ================================================================ 夹具

  UUID insertLocation(
      UUID tenant, String code, String type, boolean pickable, BigDecimal capacity) {
    return access.run(actorFor(tenant), "warehouse.facilities.read", () -> {
      UUID id = UUID.randomUUID();
      sql.execute(
          "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,status,"
              + "pickable,capacity) values(?,?,?,?,?,?,'ACTIVE',?,cast(? as numeric))",
          id, tenant, wh(tenant), code, code + " 位", type, pickable,
          capacity == null
              ? DSL.val((BigDecimal) null, BigDecimal.class)
              : DSL.val(capacity, BigDecimal.class));
      return id;
    });
  }

  /**
   * 落一条 bucket（占用来源）。
   *
   * <p><b>同一 SKU 重复调用时复用同一条 balance</b>，不是新建一条 ——
   * {@code inventory_balance} 的唯一键是 {@code (tenant, sku, warehouse, legal_entity, quality)}，
   * 一个「仓库 × SKU」只有一个可用量决策单位（V1 的分层纪律），库位维度的拆分在 bucket 上。
   * 复用它才能造出「一个 SKU 散在多个库位」这个真实形状，也才测得到摊分配平。
   */
  void bucket(UUID tenant, UUID location, UUID skuId, String quantity) {
    access.run(actorFor(tenant), "warehouse.facilities.read", () -> {
      // fetchOne + get(0, ...)：jOOQ 的 fetchValue(sql, Class, args) 重载在本仓库的
      // jOOQ 版本上会把 Class 当成绑定值（报 "Type class java.lang.Class is not supported"）。
      var found =
          sql.fetchOne(
              """
              select id from inventory_balance
               where tenant_id = current_setting('app.current_tenant', true)::uuid
                 and sku_id = ? and warehouse_id = ?
              """,
              skuId, wh(tenant));
      UUID balanceId = found == null ? null : found.get(0, UUID.class);
      if (balanceId == null) {
        balanceId = UUID.randomUUID();
        sql.execute(
            "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,0)",
            balanceId, tenant, skuId, wh(tenant), idFor(tenant, 1), idFor(tenant, 7),
            new BigDecimal(quantity));
      } else {
        // 复用既有 balance 时把在手量累加上，保持 V2 的延迟守恒式 Σbucket == balance 成立。
        sql.execute(
            "update inventory_balance set on_hand = on_hand + cast(? as numeric) "
                + "where tenant_id = current_setting('app.current_tenant', true)::uuid and id = ?",
            new BigDecimal(quantity), balanceId);
      }
      sql.execute(
          "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,location_id,"
              + "quality_status_id,balance_id,on_hand,reserved,last_movement_at) "
              + "values(?,?,?,?,?,?,?,?,?,0::numeric,?::timestamptz)",
          UUID.randomUUID(), tenant, idFor(tenant, 1), skuId, wh(tenant), location,
          idFor(tenant, 7), balanceId, new BigDecimal(quantity), daysAgo(30));
      return null;
    });
  }

  /**
   * 追加一条**真实出库分录**（inventory_transaction kind='SHIPMENT' + inventory_entry）。
   *
   * <p>{@code on_hand_delta} 为负数（出库方向）。本模块只读这两张表，不改任何数量 ——
   * 所以这是「已经发生过的出库」这一既成事实，与 bucket 上的当前余量互不干扰。
   */
  void shipment(UUID tenant, UUID skuId, String quantity, int daysAgo) {
    access.run(actorFor(tenant), "warehouse.facilities.read", () -> {
      var balance =
          sql.fetchOne(
              "select id from inventory_balance where tenant_id = "
                  + "current_setting('app.current_tenant', true)::uuid and sku_id = ? and warehouse_id = ?",
              skuId, wh(tenant));
      if (balance == null) return null;
      UUID txId = UUID.randomUUID();
      sql.execute(
          "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,actor_id,notes) "
              + "values(?,?,'SHIPMENT','OUTBOUND_ORDER',?,?::timestamptz,?,'库位利用率测试出库')",
          txId, tenant, UUID.randomUUID(), daysAgo(daysAgo), user);
      sql.execute(
          "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,reserved_delta) "
              + "values(?,?,?,?,?,0)",
          UUID.randomUUID(), tenant, txId, balance.get("id", UUID.class),
          new BigDecimal(quantity).negate());
      return null;
    });
  }

  static OffsetDateTime daysAgo(int d) {
    return OffsetDateTime.now(ZoneOffset.ofHours(8)).minusDays(d).truncatedTo(ChronoUnit.MILLIS);
  }

  // ================================================================ 核心口径

  @Test
  void 容量利用率真用capacity列且改容量利用率跟着变() {
    UUID loc = insertLocation(a, "LOC-A", "STORAGE", true, new BigDecimal("10"));
    bucket(a, loc, sku(a, 2), "4");

    var row = report(a).locations().stream()
        .filter(r -> r.code().equals("LOC-A")).findFirst().orElseThrow();
    assertThat(row.capacity()).isEqualByComparingTo(new BigDecimal("10"));
    assertThat(row.onHand()).isEqualByComparingTo(new BigDecimal("4"));
    assertThat(row.capacityUtilization()).isEqualByComparingTo(new BigDecimal("0.400000"));
    assertThat(row.capacityMeasured()).isTrue();

    // 同一批货，改容量 → 利用率必须跟着变。写死容积常量的实现会在这里露馅。
    access.run(actorA, "warehouse.facilities.read", () -> {
      sql.execute(
          "update warehouse_location set capacity = cast(5 as numeric) "
              + "where tenant_id = current_setting('app.current_tenant', true)::uuid and id = ?",
          loc);
      return null;
    });
    var after = report(a).locations().stream()
        .filter(r -> r.code().equals("LOC-A")).findFirst().orElseThrow();
    assertThat(after.capacityUtilization()).isEqualByComparingTo(new BigDecimal("0.800000"));
  }

  @Test
  void 容量未量与容量为零都给null而不是0或100() {
    UUID unmeasured = insertLocation(a, "LOC-UNMEAS", "STORAGE", true, null);
    UUID zeroCap = insertLocation(a, "LOC-ZERO", "STORAGE", true, new BigDecimal("0"));
    insertLocation(a, "LOC-IDLE", "STORAGE", true, new BigDecimal("10"));
    bucket(a, unmeasured, sku(a, 2), "5");
    bucket(a, zeroCap, sku(a, 3), "4");

    var byCode = report(a).locations().stream()
        .collect(java.util.stream.Collectors.toMap(r -> r.code(), r -> r));

    // 容量从没量过：缺的是**知识**，给 0 会被读成「量过，是空的」。
    var u = byCode.get("LOC-UNMEAS");
    assertThat(u.capacity()).isNull();
    assertThat(u.capacityUtilization()).isNull();
    assertThat(u.capacityMeasured()).isFalse();
    assertThat(u.capacityZero()).isFalse();
    assertThat(u.grade()).isEqualTo(LocationUtilizationGrade.UNMEASURED);

    // 容量量出来是 0：缺的是**定义**（除数本身是 0）。
    // 给 0 会读成「一个都没放」，给 100% 是在替物理事实编数 —— 两者都不许。
    var z = byCode.get("LOC-ZERO");
    assertThat(z.capacity()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(z.capacityUtilization()).isNull();
    assertThat(z.capacityMeasured()).isTrue();
    assertThat(z.capacityZero()).isTrue();
    assertThat(z.capacityExceeded()).isFalse();
    assertThat(z.grade()).isEqualTo(LocationUtilizationGrade.UNMEASURED);

    // NaN 的兜底断言：任何一格都不得出现 NaN，也不得「假装 100%」——
    // 空库位的利用率先验过容量，给的是 0.000000 而不是「未量」或 100%。
    var idle = byCode.get("LOC-IDLE");
    assertThat(idle.capacityUtilization()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void 在手为零时周转率无定义给null而不是0() {
    UUID emptySpot = insertLocation(a, "LOC-IDLE", "STORAGE", true, new BigDecimal("50"));
    assertThat(emptySpot).isNotNull();

    var row = report(a).locations().stream()
        .filter(r -> r.code().equals("LOC-IDLE")).findFirst().orElseThrow();
    assertThat(row.onHand()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(row.skuCount()).isZero();
    // 出库 0 ÷ 在手 0 不是「0 倍」，是除以零。返回 0 会说「周转很慢」，
    // 而事实是「压根没货」—— 那正是空置库位要处理的形态。
    assertThat(row.turnover()).isNull();
    assertThat(row.turnoverComputable()).isFalse();
    assertThat(row.outboundQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(row.dwellDays()).isNull();   // 从没放过货 ≠ 0 天
    assertThat(row.grade()).isEqualTo(LocationUtilizationGrade.IDLE);
  }

  @Test
  void 合计利用率仅在全部库位量过容量时才给() {
    insertLocation(a, "L1", "STORAGE", true, new BigDecimal("10"));
    insertLocation(a, "L2", "STORAGE", true, null); // 没量过
    var partial = report(a).totals();
    assertThat(partial.locationCount()).isEqualTo(2);
    assertThat(partial.capacityMeasuredCount()).isEqualTo(1);
    // 把已量的容量相加当分母，会算出一个没人测过的比例 → 给 null。
    assertThat(partial.capacityUtilization()).isNull();
    assertThat(partial.utilizationComputable()).isFalse();

    access.run(actorA, "warehouse.facilities.read", () -> {
      sql.execute(
          "update warehouse_location set capacity = cast(20 as numeric) "
              + "where tenant_id = current_setting('app.current_tenant', true)::uuid and code = 'L2'");
      return null;
    });
    var full = report(a).totals();
    assertThat(full.capacityMeasuredCount()).isEqualTo(2);
    assertThat(full.utilizationComputable()).isTrue();
    assertThat(full.capacityUtilization()).isNotNull();
  }

  // ================================================================ 档位与热力

  @Test
  void 爆满空置低效三档由真实数据触发() {
    // 爆满：在手 12 > 容量 10，且窗口内没出库 → 同时命中「低效」
    UUID full = insertLocation(a, "LOC-FULL", "STORAGE", true, new BigDecimal("10"));
    bucket(a, full, sku(a, 2), "12");
    // 空置：无货但占着地方
    insertLocation(a, "LOC-IDLE", "STORAGE", true, new BigDecimal("50"));
    // 低效：压着 50 件一件没出
    UUID stuck = insertLocation(a, "LOC-STUCK", "STORAGE", true, new BigDecimal("20"));
    bucket(a, stuck, sku(a, 3), "50");

    var rep = report(a);
    var byCode = rep.locations().stream()
        .collect(java.util.stream.Collectors.toMap(r -> r.code(), r -> r));

    var f = byCode.get("LOC-FULL");
    assertThat(f.grade()).isEqualTo(LocationUtilizationGrade.OVERFULL);
    assertThat(f.capacityExceeded()).isTrue();
    assertThat(f.capacityUtilization()).isEqualByComparingTo(new BigDecimal("1.200000"));
    assertThat(byCode.get("LOC-IDLE").grade()).isEqualTo(LocationUtilizationGrade.IDLE);

    // 建议是**派生**出来的：爆满 + 空置 + 低效各自出一条，缺数据就不出。
    assertThat(rep.advice()).extracting(LocationUtilizationAdvice::kind)
        .contains(
            LocationUtilizationAdviceKind.OVERFULL_LOCATION,
            LocationUtilizationAdviceKind.IDLE_LOCATION,
            LocationUtilizationAdviceKind.INEFFICIENT_LOCATION);
    // 严重度：爆满是 HIGH
    assertThat(rep.advice()).filteredOn(a -> a.kind() == LocationUtilizationAdviceKind.OVERFULL_LOCATION)
        .allMatch(a -> a.severity() == LocationUtilizationSeverity.HIGH);
    // 每条建议都写明落地归谁
    assertThat(rep.advice()).allMatch(a -> a.message() != null && !a.message().isBlank());
  }

  @Test
  void 空仓不该冒出任何建议() {
    insertLocation(a, "L1", "STORAGE", true, new BigDecimal("10"));
    insertLocation(a, "L2", "STORAGE", true, new BigDecimal("10"));
    // 空仓不该冒出**需要动货**的建议。两个库位确实是空置（这条会出），
    // 但不该有爆满/低效/容量未量 —— 那才是「按建议去动货」的信号。
    var rep = report(a);
    assertThat(rep.locations()).hasSize(2);
    assertThat(rep.totals().onHand()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(rep.totals().turnover()).isNull();
    assertThat(rep.totals().turnoverComputable()).isFalse();
    assertThat(rep.advice()).extracting(LocationUtilizationAdvice::kind)
        .doesNotContain(
            LocationUtilizationAdviceKind.OVERFULL_LOCATION,
            LocationUtilizationAdviceKind.INEFFICIENT_LOCATION,
            LocationUtilizationAdviceKind.UNMEASURED_LOCATION);
    // 空置建议按库位逐条给出（这正是蓝图点名要识别的形态）
    assertThat(rep.advice()).extracting(LocationUtilizationAdvice::kind)
        .containsOnly(LocationUtilizationAdviceKind.IDLE_LOCATION);
  }

  @Test
  void 正好装满是高位而不是爆满() {
    UUID exact = insertLocation(a, "LOC-EXACT", "STORAGE", true, new BigDecimal("10"));
    bucket(a, exact, sku(a, 2), "10");
    var row = report(a).locations().stream()
        .filter(r -> r.code().equals("LOC-EXACT")).findFirst().orElseThrow();
    // 正好装满是「刚好放不下下一件」，仍属高位；只有 > 1.0 才叫爆满。
    assertThat(row.capacityUtilization()).isEqualByComparingTo(BigDecimal.ONE);
    assertThat(row.capacityExceeded()).isFalse();
    assertThat(row.grade()).isEqualTo(LocationUtilizationGrade.HIGH);
  }

  @Test
  void 热力图是真数据驱动且说清强度来源() {
    UUID measured = insertLocation(a, "LOC-HOT", "STORAGE", true, new BigDecimal("10"));
    bucket(a, measured, sku(a, 2), "9");
    UUID unmeasured = insertLocation(a, "LOC-COLD", "STORAGE", true, null);
    bucket(a, unmeasured, sku(a, 3), "3");
    // 量过容量且确实为空：利用率是**真值 0**，不是「未量」—— 所以走 CAPACITY_UTILIZATION。
    insertLocation(a, "LOC-EMPTY", "STORAGE", true, new BigDecimal("10"));
    // 从没量过容量且为空：两个「没有」叠在一起，连相对值都没得比 → IDLE。
    insertLocation(a, "LOC-BLANK", "STORAGE", true, null);

    var heat = report(a).heatmap();
    assertThat(heat).hasSize(4);

    var hot = heat.stream().filter(c -> c.code().equals("LOC-HOT")).findFirst().orElseThrow();
    assertThat(hot.metric()).isEqualTo(LocationUtilizationMetric.CAPACITY_UTILIZATION);
    assertThat(hot.heat()).isEqualTo(90);   // 0.9 × 100，来自真利用率
    assertThat(hot.value()).isEqualByComparingTo(new BigDecimal("0.900000"));

    // 容量量不出来 → 退回「在手量相对值」，并**说破**用的是另一个指标，
    // 而不是悄悄换个分母还叫同一个名字。
    var cold = heat.stream().filter(c -> c.code().equals("LOC-COLD")).findFirst().orElseThrow();
    assertThat(cold.metric()).isEqualTo(LocationUtilizationMetric.ON_HAND_RELATIVE);
    assertThat(cold.capacityUtilization()).isNull();
    assertThat(cold.heat()).isEqualTo(33);   // 3 / 9 × 100

    // 量过且空：0% 是关于事实的断言（容量 10、货 0 件），所以它是 CAPACITY_UTILIZATION。
    var empty = heat.stream().filter(c -> c.code().equals("LOC-EMPTY")).findFirst().orElseThrow();
    assertThat(empty.metric()).isEqualTo(LocationUtilizationMetric.CAPACITY_UTILIZATION);
    assertThat(empty.heat()).isZero();
    assertThat(empty.value()).isEqualByComparingTo(BigDecimal.ZERO);

    // 从没量过 + 没货：没有任何可比量，强度恒为 0 且 metric 说破是 IDLE。
    var blank = heat.stream().filter(c -> c.code().equals("LOC-BLANK")).findFirst().orElseThrow();
    assertThat(blank.metric()).isEqualTo(LocationUtilizationMetric.IDLE);
    assertThat(blank.heat()).isZero();
    assertThat(blank.value()).isNull();

    for (var c : heat) {
      assertThat(c.heat()).isBetween(0, 100);
    }
  }

  // ================================================================ 出库频次摊分

  @Test
  void 出库量按在手占比摊分且合计不重复计数() {
    UUID loc1 = insertLocation(a, "L-1", "STORAGE", true, new BigDecimal("100"));
    UUID loc2 = insertLocation(a, "L-2", "STORAGE", true, new BigDecimal("100"));
    // 同一 SKU 散在两个库位：这是最容易重复计数的形状。
    bucket(a, loc1, sku(a, 2), "4");
    bucket(a, loc2, sku(a, 2), "6");
    // 另一 SKU 只在 L-1
    bucket(a, loc1, sku(a, 3), "5");
    shipment(a, sku(a, 2), "10", 5);
    shipment(a, sku(a, 3), "3", 5);

    var rep = report(a);
    var byCode = rep.locations().stream()
        .collect(java.util.stream.Collectors.toMap(r -> r.code(), r -> r));

    // SKU-1 在手共 10，出库 10 → L-1 分 4（40%）、L-2 分 6（60%）
    assertThat(byCode.get("L-1").outboundQuantity()).isEqualByComparingTo(new BigDecimal("7.000000"));
    assertThat(byCode.get("L-2").outboundQuantity()).isEqualByComparingTo(new BigDecimal("6.000000"));

    // 摊分的关键性质：各库位之和 == 仓库真实出库量（不重复、不漏）
    BigDecimal sum = rep.locations().stream()
        .map(LocationUtilizationRow::outboundQuantity)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(sum).isEqualByComparingTo(rep.totals().outboundQuantity());
    assertThat(rep.totals().outboundQuantity()).isEqualByComparingTo(new BigDecimal("13"));

    // 周转率：L-1 = 7 / 9；L-2 = 6 / 6 = 1
    assertThat(byCode.get("L-2").turnover()).isEqualByComparingTo(BigDecimal.ONE);
    assertThat(byCode.get("L-1").turnover()).isNotNull();
  }

  @Test
  void 窗口外的出库不算频次() {
    UUID loc = insertLocation(a, "L-1", "STORAGE", true, new BigDecimal("100"));
    bucket(a, loc, sku(a, 2), "10");
    shipment(a, sku(a, 2), "4", 200);  // 200 天前，默认窗口 30 天之外
    assertThat(report(a).totals().outboundQuantity()).isEqualByComparingTo(BigDecimal.ZERO);

    // 缩短窗口到 365 天就能看见 —— 证明「看不见」是窗口过滤，不是没数据。
    var wide = report(a, new LocationUtilizationQuery(365, null, null, null, null, null));
    assertThat(wide.totals().outboundQuantity()).isEqualByComparingTo(new BigDecimal("4"));
  }

  @Test
  void sku分散触发拣货路径建议() {
    UUID l1 = insertLocation(a, "L-1", "STORAGE", true, new BigDecimal("100"));
    UUID l2 = insertLocation(a, "L-2", "STORAGE", true, new BigDecimal("100"));
    bucket(a, l1, sku(a, 2), "4");
    bucket(a, l2, sku(a, 2), "6");
    shipment(a, sku(a, 2), "10", 3);

    var rep = report(a);
    var dispersion = rep.advice().stream()
        .filter(x -> x.kind() == LocationUtilizationAdviceKind.SKU_DISPERSION).toList();
    assertThat(dispersion).isNotEmpty();
    // 离散度是**真数**：这个 SKU 在 2 个库位，所以每个库位记 1
    assertThat(rep.locations()).allMatch(r -> r.skuDispersion() == 1);
    // 建议必须指认落地归属（拣货路径属 8.3.2.1）
    assertThat(dispersion).allMatch(x -> x.ownerModule().startsWith("8.3.2.1"));
  }

  @Test
  void 高频货在不可拣库位触发优先建议() {
    UUID frozen = insertLocation(a, "LOC-FROZEN", "FROZEN", false, new BigDecimal("1000"));
    bucket(a, frozen, sku(a, 2), "500");
    shipment(a, sku(a, 2), "300", 4);

    var advice = report(a).advice();
    var hit = advice.stream()
        .filter(x -> x.kind() == LocationUtilizationAdviceKind.HIGH_FREQUENT_NON_PICKABLE)
        .toList();
    assertThat(hit).isNotEmpty();
    assertThat(hit.get(0).severity()).isEqualTo(LocationUtilizationSeverity.HIGH);
    assertThat(hit.get(0).ownerModule()).startsWith("8.3.2.1");
  }

  @Test
  void 数字走NUMERIC不经过浮点() {
    UUID loc = insertLocation(a, "LOC-FRAC", "STORAGE", true, new BigDecimal("10"));
    bucket(a, loc, sku(a, 2), "0.1");
    bucket(a, loc, sku(a, 3), "0.1");
    bucket(a, loc, sku(a, 4), "0.1");

    var row = report(a).locations().stream()
        .filter(r -> r.code().equals("LOC-FRAC")).findFirst().orElseThrow();
    // 0.1 × 3 必须是 0.3，不是 0.30000000000000004
    assertThat(row.onHand()).isEqualByComparingTo(new BigDecimal("0.3"));
    assertThat(row.onHand().toPlainString()).isEqualTo("0.3000");
    assertThat(row.skuCount()).isEqualTo(3);
  }

  // ================================================================ 筛选

  @Test
  void 筛选只影响行不影响合计() {
    insertLocation(a, "HIT", "STORAGE", true, new BigDecimal("10"));
    UUID miss = insertLocation(a, "MISS", "STORAGE", true, new BigDecimal("1000"));
    bucket(a, miss, sku(a, 2), "1");

    var all = report(a);
    assertThat(all.locations()).hasSize(2);

    var over90 = report(a, new LocationUtilizationQuery(null, null, null, null,
        new BigDecimal("0.90"), null));
    assertThat(over90.locations()).isEmpty();
    // 合计仍是全仓真相
    assertThat(over90.totals().locationCount()).isEqualTo(2);

    var graded = report(a, new LocationUtilizationQuery(null, "LOW", null, null, null, null));
    assertThat(graded.locations()).extracting(LocationUtilizationRow::code).containsExactly("MISS");
    assertThat(graded.totals().locationCount()).isEqualTo(2);
  }

  @Test
  void 非法筛选与非法窗口返回400() throws Exception {
    UUID loc = insertLocation(a, "L1", "STORAGE", true, new BigDecimal("10"));
    http.perform(get("/warehouse/location-utilization/{id}", wh(a))
            .header("Authorization", "Bearer tenant-a").param("windowDays", "0"))
        .andExpect(status().isBadRequest());
    http.perform(get("/warehouse/location-utilization/{id}", wh(a))
            .header("Authorization", "Bearer tenant-a").param("windowDays", "9999"))
        .andExpect(status().isBadRequest());
    http.perform(get("/warehouse/location-utilization/{id}", wh(a))
            .header("Authorization", "Bearer tenant-a").param("grade", "NOT_A_GRADE"))
        .andExpect(status().isBadRequest());
    http.perform(get("/warehouse/location-utilization/{id}/locations/{lid}", wh(a), loc)
            .header("Authorization", "Bearer tenant-a").param("type", "NOT_A_TYPE"))
        .andExpect(status().isBadRequest());
  }

  // ================================================================ 详情

  @Test
  void 详情逐sku拆分且占比分母为零时给null() {
    UUID l1 = insertLocation(a, "L-1", "STORAGE", true, new BigDecimal("100"));
    UUID l2 = insertLocation(a, "L-2", "STORAGE", true, new BigDecimal("100"));
    bucket(a, l1, sku(a, 2), "3");
    bucket(a, l2, sku(a, 2), "7");
    bucket(a, l1, sku(a, 3), "2");
    shipment(a, sku(a, 2), "5", 3);

    var detail = access.run(actorA, "warehouse.facilities.read",
        () -> service.detail(wh(a), l1, new LocationUtilizationQuery(null, null, null, null, null, null)));

    assertThat(detail.location().code()).isEqualTo("L-1");
    assertThat(detail.skus()).hasSize(2);
    // 按出库量降序：出过货的排前面
    assertThat(detail.skus().get(0).skuId()).isEqualTo(sku(a, 2));
    assertThat(detail.skus().get(0).outboundQuantity()).isEqualByComparingTo(new BigDecimal("5"));
    // 占该 SKU 全仓在手量的比例：3 / 10 = 0.3
    assertThat(detail.skus().get(0).shareOfSkuOnHand()).isEqualByComparingTo(new BigDecimal("0.300000"));
    // 这个 SKU 散在 2 个库位 → 离散度 1
    assertThat(detail.skus().get(0).locationCount()).isEqualTo(2);
    assertThat(detail.skus().get(0).skuDispersion()).isEqualTo(1);
    assertThat(detail.skus().get(0).dwellDays()).isNotNull();

    // 不属于本租户的库位 → 404
    UUID foreign = insertLocation(b, "B-L", "STORAGE", true, new BigDecimal("10"));
    assertThatThrownBy(() -> access.run(actorA, "warehouse.facilities.read",
        () -> service.detail(wh(a), foreign, new LocationUtilizationQuery(null, null, null, null, null, null))))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }

  // ================================================================ 能力声明

  @Test
  void 能力声明如实报出粒度与未交付模块() {
    var caps = access.run(actorA, "warehouse.facilities.read", () -> service.capabilities());
    assertThat(caps.capacityUtilizationLive()).isTrue();
    assertThat(caps.turnoverLive()).isTrue();
    assertThat(caps.heatmapLive()).isTrue();
    assertThat(caps.adviceGenerated()).isTrue();
    // 8.1.2.2 自动分配与 8.3.2.1 拣货路径本车道不交付 —— 如实报 false，不假装能落地
    assertThat(caps.autoAllocationAvailable()).isFalse();
    assertThat(caps.autoAllocationOwnerModule()).contains("8.1.2.2");
    assertThat(caps.pickPathOptimizationAvailable()).isFalse();
    assertThat(caps.pickPathOwnerModule()).contains("8.3.2.1");
    // 归集粒度随响应返回，不只写在文档里
    assertThat(caps.outboundFrequencyGrain()).contains("摊分").contains("location_id");
    assertThat(caps.turnoverBasis()).contains("窗口内翻了多少倍");
    assertThat(caps.grades()).hasSize(6);
    assertThat(caps.defaultWindowDays()).isEqualTo(30);
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 租户隔离三断言实跑() throws Exception {
    UUID aLoc = insertLocation(a, "A-LOC", "STORAGE", true, new BigDecimal("10"));
    bucket(a, aLoc, sku(a, 2), "5");
    UUID bLoc = insertLocation(b, "B-LOC", "STORAGE", true, new BigDecimal("10"));
    bucket(b, bLoc, sku(b, 2), "5");

    // ① 两租户互不可见
    var ra = report(a);
    assertThat(ra.locations()).extracting(LocationUtilizationRow::code).containsExactly("A-LOC");
    var rb = report(b);
    assertThat(rb.locations()).extracting(LocationUtilizationRow::code).containsExactly("B-LOC");
    // B 用 A 的仓 id → 404，而不是「同名仓库」或空列表
    assertThatThrownBy(() -> access.run(actorB, "warehouse.facilities.read",
        () -> service.report(wh(a), new LocationUtilizationQuery(null, null, null, null, null, null))))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

    // ② 伪造 X-Tenant-Id 头无效
    http.perform(get("/warehouse/location-utilization/{id}", wh(a))
            .header("Authorization", "Bearer tenant-b").header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());
    // 伪造头下拿到的仍然只有自己的行
    http.perform(get("/warehouse/location-utilization/{id}", wh(b))
            .header("Authorization", "Bearer tenant-b").header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.locations[0].code").value("B-LOC"));

    // ③ 连接池归还后不串租户（单连接池上 A→B→A→B 三轮）
    for (int i = 0; i < 3; i++) {
      assertThat(report(a).locations()).extracting(LocationUtilizationRow::code).containsExactly("A-LOC");
      assertThat(report(b).locations()).extracting(LocationUtilizationRow::code).containsExactly("B-LOC");
    }

    // 不设 app.current_tenant → 读到 0 行（RLS 生效）
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), "warehouse_app", "test-only");
        var st = c.createStatement();
        var rs = st.executeQuery(
            "select count(*) from warehouse_location")) {
      rs.next();
      assertThat(rs.getLong(1)).isZero();
    }
  }

  @Test
  void 本模块读到的表逐张断言FORCE_RLS() throws Exception {
    List<String> tables =
        List.of(
            "warehouse_location", "stock_bucket", "inventory_balance",
            "inventory_transaction", "inventory_entry");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var st = c.createStatement()) {
      for (var table : tables) {
        var rs =
            st.executeQuery(
                "select relforcerowsecurity from pg_class where relname = '" + table + "'");
        rs.next();
        assertThat(rs.getBoolean(1)).as("%s 必须 FORCE RLS", table).isTrue();
      }
    }
  }

  // ================================================================ 端点

  @Test
  void 端点与鉴权() throws Exception {
    UUID loc = insertLocation(a, "L-1", "STORAGE", true, new BigDecimal("10"));
    bucket(a, loc, sku(a, 2), "5");

    http.perform(get("/warehouse/location-utilization/capabilities")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.autoAllocationAvailable").value(false))
        .andExpect(jsonPath("$.outboundFrequencyGrain").isNotEmpty());

    http.perform(get("/warehouse/location-utilization/{id}", wh(a))
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.locations[0].code").value("L-1"))
        .andExpect(jsonPath("$.heatmap").isArray())
        .andExpect(jsonPath("$.totals.locationCount").value(1));

    http.perform(get("/warehouse/location-utilization/{id}/locations/{lid}", wh(a), loc)
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.skus").isArray());

    // 无凭据 401、无权限 403
    http.perform(get("/warehouse/location-utilization/{id}", wh(a))).andExpect(status().isUnauthorized());
    http.perform(get("/warehouse/location-utilization/{id}", wh(a))
            .header("Authorization", "Bearer no-permission")).andExpect(status().isForbidden());
    // 只读角色能看（本模块是只读分析）
    http.perform(get("/warehouse/location-utilization/{id}", wh(a))
            .header("Authorization", "Bearer read-only")).andExpect(status().isOk());
    // 别人的仓 404
    http.perform(get("/warehouse/location-utilization/{id}", wh(b))
            .header("Authorization", "Bearer tenant-a")).andExpect(status().isNotFound());
  }
}
