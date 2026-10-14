package com.starcon.warehouse;

import static com.starcon.warehouse.LocationAllocationModels.*;
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
 * 8.1.2.2 库位自动分配。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>本测试钉住八件「错了会误导人」的事：
 *
 * <ul>
 *   <li><b>质量状态 → 库位类型是硬规则，且 DB 说了算。</b>蓝图红线是「残次品入残次库位是
 *       硬规则，不允许混放」。所以除了走服务层被拒，还用<b>超级用户连接直连 SQL</b>绕过
 *       服务层去插一条残次品进存储位的决策，V106 的触发器必须当场拒绝（23514）。
 *       只在应用层 if 的话，一条运维 SQL 就能把残次品放进正常库位。
 *   <li><b>「不允许混放」是独立的一条 DB 判据。</b>目标库位上已经压着别的质量角色的货时，
 *       即使那个库位的<b>类型</b>被改成对的，混放也必须被拒 —— 类型可以被 8.1.2.1 改，
 *       所以混放不能只靠类型兜着。
 *   <li><b>Java 映射与 DB 函数必须一致。</b>两侧各有一份三值映射，一条不一致就会出现
 *       「服务层放行、DB 拒绝」的诡异 500。逐条比对。
 *   <li><b>没量过 ≠ 够。</b>库位没量过容量 / 调用方没给需求容量时，
 *       {@code capacityEvaluated=false} 且给 CAPACITY_UNKNOWN 说明（0 分），
 *       绝不悄悄当成通过；而<b>量出超容的库位根本不进候选</b>。
 *   <li><b>score = 各条理由权重之和。</b>「为什么它排第一」可以逐条加起来验证，
 *       不需要额外解释一个算不出来的数字。
 *   <li><b>同一收货明细行不得双指派</b>（部分唯一索引，DB 不变量而不是应用层先查一次）。
 *   <li><b>NUMERIC 精确</b>：0.1×3 读回必须是 0.3，不是 0.30000000000000004。
 *   <li><b>租户隔离三断言</b>实跑 + FORCE RLS 逐张断言 + 时间字段断言<b>写回的那个瞬间</b>。
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class LocationAllocationIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      List.of("warehouse.facilities.read", "warehouse.facilities.update");

  // 固定序号的夹具 id：跨租户唯一，便于「A 的 id 在 B 那边不存在」这类断言
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-location-allocation-" + tenant + "-" + ordinal).getBytes());
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

  @Autowired LocationAllocationService service;
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
          "truncate location_allocation, stock_bucket, inventory_balance, goods_receipt_event,"
              + " goods_receipt_line, goods_receipt, warehouse_location, inventory_quality_status,"
              + " legal_entity_ref, sku_ref, warehouse restart identity cascade");
    }
    for (var tenant : List.of(a, b)) {
      as(tenant, "read", () -> {
        sql.execute("insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
            idFor(tenant, 1), tenant, "LE-1", "主体一");
        sql.execute("insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
            idFor(tenant, 2), tenant, "SKU-1", "商品一");
        sql.execute("insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
            idFor(tenant, 3), tenant, "SKU-2", "商品二");
        sql.execute("insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
            idFor(tenant, 9), tenant, "SKU-3", "商品三");
        sql.execute("insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
            idFor(tenant, 4), tenant, "SELLABLE", "可售", "SELLABLE");
        sql.execute("insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
            idFor(tenant, 7), tenant, "PENDING", "待检", "PENDING_INSPECTION");
        sql.execute("insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
            idFor(tenant, 8), tenant, "DEFECT", "残次", "DEFECTIVE");
        sql.execute(
            "insert into warehouse(id,tenant_id,code,name,kind,country_code,timezone,"
                + "default_legal_entity_id,status) values(?,?,?,?,?,?,?,?,?)",
            idFor(tenant, 5), tenant, "WH-" + (tenant.equals(a) ? "A" : "B"), "本租仓库",
            "LOCAL", tenant.equals(a) ? "CN" : "US", "Asia/Shanghai", idFor(tenant, 1), "ACTIVE");
        return null;
      });
    }
  }

  // ================================================================ 夹具助手

  <T> T as(UUID tenant, String permission, java.util.function.Supplier<T> work) {
    return access.run(
        tenant.equals(a) ? actorA : actorB, "warehouse.facilities." + permission, work);
  }

  <T> T asA(String permission, java.util.function.Supplier<T> work) {
    return as(a, permission, work);
  }

  <T> T asB(String permission, java.util.function.Supplier<T> work) {
    return as(b, permission, work);
  }

  UUID wh(UUID tenant) {
    return idFor(tenant, 5);
  }

  UUID sku1(UUID tenant) {
    return idFor(tenant, 2);
  }

  UUID sku2(UUID tenant) {
    return idFor(tenant, 3);
  }

  UUID sku3(UUID tenant) {
    return idFor(tenant, 9);
  }

  UUID quality(UUID tenant, String role) {
    return switch (role) {
      case "SELLABLE" -> idFor(tenant, 4);
      case "PENDING_INSPECTION" -> idFor(tenant, 7);
      default -> idFor(tenant, 8);
    };
  }

  /** 直接落库一个库位（不走服务层），用于构造六层树。 */
  UUID insertLocation(UUID tenant, String code, String type, UUID parent,
      BigDecimal capacity, BigDecimal weight) {
    return as(tenant, "read", () -> {
      UUID id = UUID.randomUUID();
      sql.execute(
          "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,status,"
              + "pickable,parent_id,capacity,weight_limit) values(?,?,?,?,?,?,'ACTIVE',true,"
              + "cast(? as uuid),cast(? as numeric),cast(? as numeric))",
          // 三个可空列必须带 SQL 类型：jOOQ 绑定无类型 null 时会抛
          // "Type class java.lang.Class is not supported in dialect POSTGRES"。
          id, tenant, wh(tenant), code, code + " 位", type,
          parent == null ? DSL.val((UUID) null, UUID.class) : DSL.val(parent, UUID.class),
          capacity == null ? DSL.val((BigDecimal) null, BigDecimal.class) : DSL.val(capacity, BigDecimal.class),
          weight == null ? DSL.val((BigDecimal) null, BigDecimal.class) : DSL.val(weight, BigDecimal.class));
      return id;
    });
  }

  /**
   * 一条库存明细（占用来源）。数量走 NUMERIC 绑定，绝不经过 double。
   *
   * <p>balance 复用：同一个 SKU × 仓库 × 法人 × 质量状态在 V1 里只有一行，
   * 同一个 SKU 出现在两个库位时必须**复用**那行而不是再插一行（那是 23505）。
   */
  void bucket(UUID tenant, UUID location, UUID sku, String role, String quantity) {
    as(tenant, "read", () -> {
      UUID qs = quality(tenant, role);
      var existing =
          sql.fetchOne(
              "select id from inventory_balance"
                  + " where tenant_id = current_setting('app.current_tenant', true)::uuid"
                  + " and sku_id = ?::uuid and warehouse_id = ?::uuid"
                  + " and legal_entity_id = ?::uuid and quality_status_id = ?::uuid",
              sku, wh(tenant), idFor(tenant, 1), qs);
      UUID balanceId = existing == null ? UUID.randomUUID() : existing.get(0, UUID.class);
      if (existing == null) {
        sql.execute(
            "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,0)",
            balanceId, tenant, sku, wh(tenant), idFor(tenant, 1), qs, new BigDecimal(quantity));
      } else {
        // 已在同一 balance 上加量（同一个 SKU 出现在两个库位是常见形状）
        sql.execute(
            "update inventory_balance set on_hand = on_hand + cast(? as numeric)"
                + " where tenant_id = current_setting('app.current_tenant', true)::uuid and id = ?::uuid",
            new BigDecimal(quantity), balanceId);
      }
      sql.execute(
          "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,location_id,"
              + "quality_status_id,balance_id,on_hand,reserved,last_movement_at) "
              + "values(?,?,?,?,?,?,?,?,?,0::numeric,?::timestamptz)",
          UUID.randomUUID(), tenant, idFor(tenant, 1), sku, wh(tenant), location, qs, balanceId,
          new BigDecimal(quantity), now());
      return null;
    });
  }

  /** 一张收货单 + 一行明细 + 一次实收事件（货落在 dock 库位）。 */
  record Receipt(UUID receiptId, UUID lineId) {}

  Receipt receipt(UUID tenant, UUID sku, String role, String quantity, UUID dockLocation) {
    return as(tenant, "read", () -> {
      UUID receiptId = UUID.randomUUID();
      UUID lineId = UUID.randomUUID();
      sql.execute(
          "insert into goods_receipt(id,tenant_id,receipt_code,source_type,source_ref_id,"
              + "source_ref_code,warehouse_id,legal_entity_id,status,created_by) "
              + "values(?,?,?,?,?,?,?,?, 'PARTIALLY_RECEIVED',?)",
          receiptId, tenant, "GR-" + UUID.randomUUID().toString().substring(0, 8),
          "PURCHASE", UUID.randomUUID(), "PO-1", wh(tenant), idFor(tenant, 1), user);
      sql.execute(
          "insert into goods_receipt_line(id,tenant_id,receipt_id,sku_id,quality_status_id,"
              + "expected_quantity,received_quantity) values(?,?,?,?,?,?,?)",
          lineId, tenant, receiptId, sku, quality(tenant, role), new BigDecimal(quantity),
          new BigDecimal(quantity));
      sql.execute(
          "insert into goods_receipt_event(id,tenant_id,receipt_id,received_at,location_id,actor_id) "
              + "values(?,?,?,?::timestamptz,?,?)",
          UUID.randomUUID(), tenant, receiptId, now(), dockLocation, user);
      return new Receipt(receiptId, lineId);
    });
  }

  /** 常见夹具：根「区」→ dock（收货暂存，depth 1），返回 dock。 */
  UUID dock(UUID tenant) {
    UUID zone = insertLocation(tenant, "ZONE", "RECEIVING", null, null, null);
    return insertLocation(tenant, "DOCK", "RECEIVING", zone, null, null);
  }

  List<LocationAllocationLinePlan> recommend(UUID tenant, Receipt r, BigDecimal cap, BigDecimal wt) {
    return as(tenant, "read",
        () -> service.recommend(new LocationAllocationRecommendInput(r.receiptId(), null, cap, wt, 10)));
  }

  List<LocationAllocationLinePlan> recommend(UUID tenant, Receipt r) {
    return recommend(tenant, r, null, null);
  }

  LocationAllocationSaved confirm(UUID tenant, Receipt r, UUID locationId, String quantity) {
    return as(tenant, "update", () -> service.confirm(
        new LocationAllocationConfirmInput(
            r.receiptId(), r.lineId(), locationId,
            new BigDecimal(quantity), null, null),
        user));
  }

  static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.ofHours(8)).truncatedTo(ChronoUnit.MILLIS);
  }

  /** 用超级用户直连 SQL 绕过服务层 —— 用来证明 DB 判据独立存在。 */
  static void superuser(String sql, Object... args) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var st = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) st.setObject(i + 1, args[i]);
      st.execute();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * 用超级用户直连执行并返回 SQLSTATE（"NO_ERROR" 表示成功）。
   *
   * <p>取 {@link java.sql.SQLException#getSQLState()} 而不是去 message 里找 "23514"：
   * PG 的报错文本是中文业务文案，**不含** SQLSTATE，用文本匹配会漏判。
   */
  static String superuserError(String sql, Object... args) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var st = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) st.setObject(i + 1, args[i]);
      st.execute();
      return "NO_ERROR";
    } catch (java.sql.SQLException e) {
      return e.getSQLState();
    } catch (Exception e) {
      return "UNEXPECTED:" + e.getMessage();
    }
  }

  // ================================================================ 硬规则：质量状态决定库位类型

  @Test
  void 三种质量状态各自只推自己那类库位() {
    UUID zone = insertLocation(a, "ZONE", "RECEIVING", null, null, null);
    UUID dock = insertLocation(a, "DOCK", "RECEIVING", zone, null, null);
    insertLocation(a, "S-01", "STORAGE", zone, null, null);
    insertLocation(a, "Q-01", "QUARANTINE", zone, null, null);
    insertLocation(a, "D-01", "DEFECTIVE", zone, null, null);

    for (var caseRow : List.of(
        new String[] {"SELLABLE", "STORAGE"},
        new String[] {"PENDING_INSPECTION", "QUARANTINE"},
        new String[] {"DEFECTIVE", "DEFECTIVE"})) {
      Receipt r = receipt(a, sku1(a), caseRow[0], "10", dock);
      var plan = recommend(a, r).get(0);
      assertThat(plan.requiredType().name()).as("硬规则推出的目标类型").isEqualTo(caseRow[1]);
      assertThat(plan.candidates()).as("%s 的候选不得出现别的类型", caseRow[0]).isNotEmpty();
      assertThat(plan.candidates())
          .allSatisfy(c -> assertThat(c.type().name()).isEqualTo(caseRow[1]));
    }
  }

  @Test
  void 收货暂存发货暂存与冻结库位都不是自动分配目标() {
    UUID zone = insertLocation(a, "ZONE", "RECEIVING", null, null, null);
    UUID dock = insertLocation(a, "DOCK", "RECEIVING", zone, null, null);
    insertLocation(a, "S-01", "STORAGE", zone, null, null);
    insertLocation(a, "SHIP-01", "SHIPPING", zone, null, null);
    insertLocation(a, "FRZ-01", "FROZEN", zone, null, null);

    Receipt r = receipt(a, sku1(a), "SELLABLE", "10", dock);
    var plan = recommend(a, r).get(0);
    assertThat(plan.candidates())
        .allSatisfy(c -> assertThat(c.type().name()).isEqualTo("STORAGE"));
    assertThat(plan.candidates()).noneSatisfy(c -> assertThat(c.code()).isEqualTo("SHIP-01"));
    assertThat(plan.candidates()).noneSatisfy(c -> assertThat(c.code()).isEqualTo("FRZ-01"));
  }

  @Test
  void 服务层选到非硬规则库位被拒() {
    UUID zone = insertLocation(a, "ZONE", "RECEIVING", null, null, null);
    UUID dock = insertLocation(a, "DOCK", "RECEIVING", zone, null, null);
    UUID storage = insertLocation(a, "S-01", "STORAGE", zone, null, null);

    Receipt r = receipt(a, sku1(a), "DEFECTIVE", "10", dock);
    assertThatThrownBy(() -> confirm(a, r, storage, "10"))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .satisfies(e -> assertThat(((org.springframework.web.server.ResponseStatusException) e)
                .getStatusCode().value()).isEqualTo(400))
        .hasMessageContaining("残次");
  }

  @Test
  void 绕过服务层把残次品写进存储库位被DB拒() {
    UUID zone = insertLocation(a, "ZONE", "RECEIVING", null, null, null);
    UUID dock = insertLocation(a, "DOCK", "RECEIVING", zone, null, null);
    UUID storage = insertLocation(a, "S-01", "STORAGE", zone, null, null);
    Receipt r = receipt(a, sku1(a), "DEFECTIVE", "10", dock);

    // 超级用户直连：绕开服务层、绕开 RLS。DB 触发器仍然必须拒绝。
    String sql = """
        insert into location_allocation
          (id,tenant_id,receipt_id,receipt_line_id,sku_id,quality_status_id,warehouse_id,
           source_location_id,location_id,allocate_quantity,required_type,rule_summary,created_by)
        values (?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,
                cast(? as numeric),'STORAGE','超级用户直连写入',?::uuid)
        """;
    assertThat(superuserError(sql, UUID.randomUUID(), a, r.receiptId(), r.lineId(), sku1(a),
            quality(a, "DEFECTIVE"), wh(a), dock, storage, "10", user))
        .as("残次品进 STORAGE 必须被 V106 触发器拒（23514）").isEqualTo("23514");
  }

  @Test
  void 不允许混放被DB独立拒绝() {
    UUID zone = insertLocation(a, "ZONE", "RECEIVING", null, null, null);
    UUID dock = insertLocation(a, "DOCK", "RECEIVING", zone, null, null);
    // 一个 DEFECTIVE 类型的库位，但里面已经压着**可售**货：类型对、实物混放。
    UUID defective = insertLocation(a, "D-01", "DEFECTIVE", zone, null, null);
    bucket(a, defective, sku2(a), "SELLABLE", "5");
    Receipt r = receipt(a, sku1(a), "DEFECTIVE", "10", dock);

    String sql = """
        insert into location_allocation
          (id,tenant_id,receipt_id,receipt_line_id,sku_id,quality_status_id,warehouse_id,
           source_location_id,location_id,allocate_quantity,required_type,rule_summary,created_by)
        values (?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,
                cast(? as numeric),'DEFECTIVE','超级用户直连写入',?::uuid)
        """;
    // 类型是 DEFECTIVE（对得上硬规则），但库位上已压着 SELLABLE 的货 —— 只有独立查
    // 当前实际存放的 bucket 才拦得住。类型可以被 8.1.2.1 改，所以混放不能只靠类型兜着。
    assertThat(superuserError(sql, UUID.randomUUID(), a, r.receiptId(), r.lineId(), sku1(a),
            quality(a, "DEFECTIVE"), wh(a), dock, defective, "10", user))
        .as("混放必须被 V106 触发器拒（23514）").isEqualTo("23514");
  }

  @Test
  void Java映射与DB函数逐条一致() {
    for (var role : List.of("SELLABLE", "PENDING_INSPECTION", "DEFECTIVE")) {
      var row = sql.fetchOne("select location_allocation_required_type(?) as t", role);
      assertThat(row.get("t", String.class))
          .as("Java 的 PlacementRule 与 DB 函数必须给出同一个目标库位类型")
          .isEqualTo(PlacementRule.of(role).requiredType().name());
    }
    // 第三类（冻结）不是任何角色的目标
    assertThat(sql.fetchOne("select location_allocation_required_type('FROZEN') as t").get("t", String.class))
        .isNull();
  }

  // ================================================================ 规则与理由

  @Test
  void score等于各条理由权重之和() {
    UUID zone = insertLocation(a, "ZONE", "RECEIVING", null, null, null);
    UUID dock = insertLocation(a, "DOCK", "RECEIVING", zone, null, null);
    UUID s1 = insertLocation(a, "S-01", "STORAGE", zone, new BigDecimal("100"), new BigDecimal("50"));
    insertLocation(a, "S-02", "STORAGE", zone, new BigDecimal("100"), new BigDecimal("50"));

    Receipt r = receipt(a, sku1(a), "SELLABLE", "10", dock);
    var plan = recommend(a, r, new BigDecimal("20"), new BigDecimal("10")).get(0);
    assertThat(plan.candidates()).isNotEmpty();
    for (var c : plan.candidates()) {
      int sum = c.reasons().stream().mapToInt(LocationAllocationReason::weight).sum();
      assertThat(c.score())
          .as("候选 %s 的 score 必须等于各条理由权重之和（%s）", c.code(), c.reasons())
          .isEqualTo(sum);
      assertThat(c.reasons()).isNotEmpty();
    }
    // 更近的 S-01（depth 1）应排在 S-02 之前
    assertThat(plan.candidates().get(0).code()).isEqualTo("S-01");
    assertThat(s1).isNotNull();
  }

  @Test
  void 上架路径跳数按库位树算出且更近的排在前面() {
    UUID zone = insertLocation(a, "ZONE", "RECEIVING", null, null, null);
    UUID dock = insertLocation(a, "DOCK", "RECEIVING", zone, null, null);
    UUID row1 = insertLocation(a, "ROW-1", "RECEIVING", zone, null, null);
    UUID shelf1 = insertLocation(a, "SHELF-1", "RECEIVING", row1, null, null);
    insertLocation(a, "NEAR", "STORAGE", row1, null, null);
    UUID row2 = insertLocation(a, "ROW-2", "RECEIVING", zone, null, null);
    UUID shelf2 = insertLocation(a, "SHELF-2", "RECEIVING", row2, null, null);
    insertLocation(a, "FAR", "STORAGE", shelf2, null, null);
    assertThat(shelf1).isNotNull();

    Receipt r = receipt(a, sku1(a), "SELLABLE", "10", dock);
    var plan = recommend(a, r).get(0);
    var near = plan.candidates().stream().filter(c -> c.code().equals("NEAR")).findFirst().orElseThrow();
    var far = plan.candidates().stream().filter(c -> c.code().equals("FAR")).findFirst().orElseThrow();

    // dock 在 depth 1，NEAR 在 depth 2（共用 ZONE），FAR 在 depth 3
    assertThat(near.distance()).isEqualTo(1);
    assertThat(far.distance()).isEqualTo(2);
    assertThat(near.depth()).isLessThan(far.depth());
    assertThat(plan.candidates().get(0).code()).isEqualTo("NEAR");
    assertThat(near.reasons())
        .extracting(LocationAllocationReason::code)
        .contains(ReasonCode.SHORTEST_PATH);
  }

  @Test
  void 同品类聚类取自真实共置关系而不是编造的类目() {
    UUID zone = insertLocation(a, "ZONE", "RECEIVING", null, null, null);
    UUID dock = insertLocation(a, "DOCK", "RECEIVING", zone, null, null);
    // 历史共置：SKU-1 与 SKU-2 曾同在 OLD 库位
    UUID old = insertLocation(a, "OLD", "STORAGE", zone, null, null);
    bucket(a, old, sku1(a), "SELLABLE", "5");
    bucket(a, old, sku2(a), "SELLABLE", "5");
    // CLOSE 库位上正压着 SKU-2（与入库的 SKU-1 有外部共置关系）
    UUID close = insertLocation(a, "CLOSE", "STORAGE", zone, null, null);
    bucket(a, close, sku2(a), "SELLABLE", "5");
    UUID lonely = insertLocation(a, "LONELY", "STORAGE", zone, null, null);
    bucket(a, lonely, sku3(a), "SELLABLE", "5");

    Receipt r = receipt(a, sku1(a), "SELLABLE", "10", dock);
    var plan = recommend(a, r).get(0);
    var closeCandidate = plan.candidates().stream().filter(c -> c.code().equals("CLOSE")).findFirst().orElseThrow();
    var lonelyCandidate = plan.candidates().stream().filter(c -> c.code().equals("LONELY")).findFirst().orElseThrow();

    assertThat(closeCandidate.affinitySkus())
        .as("CLOSE 上压着与入库 SKU 历史共置的 SKU-2").isEqualTo(1L);
    assertThat(lonelyCandidate.affinitySkus()).as("SKU-3 没有与 SKU-1 共置过").isZero();
    assertThat(closeCandidate.reasons()).extracting(LocationAllocationReason::code)
        .contains(ReasonCode.CATEGORY_CLUSTER);
    // OLD 上本来就住着入库这批货本身：最强的聚类信号
    var oldCandidate = plan.candidates().stream().filter(c -> c.code().equals("OLD")).findFirst().orElseThrow();
    assertThat(oldCandidate.reasons()).extracting(LocationAllocationReason::code)
        .contains(ReasonCode.CATEGORY_CLUSTER);
  }

  @Test
  void 没量过容量给无法判定而不是当成通过() {
    UUID zone = insertLocation(a, "ZONE", "RECEIVING", null, null, null);
    UUID dock = insertLocation(a, "DOCK", "RECEIVING", zone, null, null);
    // UNKNOWN 库位没量过容量（capacity IS NULL），MEASURED 量过但装不下
    UUID unknown = insertLocation(a, "UNKNOWN", "STORAGE", zone, null, null);
    insertLocation(a, "MEASURED", "STORAGE", zone, new BigDecimal("5"), new BigDecimal("5"));
    assertThat(unknown).isNotNull();

    Receipt r = receipt(a, sku1(a), "SELLABLE", "10", dock);
    var plan = recommend(a, r, new BigDecimal("20"), new BigDecimal("20")).get(0);

    assertThat(plan.candidates())
        .as("量出超容的库位不该出现在候选里：装不下不是「差一点的推荐」")
        .allSatisfy(c -> assertThat(c.code()).isNotEqualTo("MEASURED"));

    var unknownCandidate = plan.candidates().stream()
        .filter(c -> c.code().equals("UNKNOWN")).findFirst().orElseThrow();
    assertThat(unknownCandidate.capacityEvaluated()).as("没量过容量 = 判不了").isFalse();
    assertThat(unknownCandidate.remainingCapacity()).as("没量过 ≠ 0").isNull();
    assertThat(unknownCandidate.weightEvaluated()).isFalse();
    assertThat(unknownCandidate.reasons()).extracting(LocationAllocationReason::code)
        .contains(ReasonCode.CAPACITY_UNKNOWN, ReasonCode.WEIGHT_UNKNOWN);
    // 无法判定既不加分也不扣分：这两条的权重必须是 0
    assertThat(unknownCandidate.reasons())
        .filteredOn(r2 -> r2.code() == ReasonCode.CAPACITY_UNKNOWN || r2.code() == ReasonCode.WEIGHT_UNKNOWN)
        .allSatisfy(r2 -> assertThat(r2.weight()).isZero());
    // 且被如实报告为「排除了多少个」
    assertThat(plan.rejectedLocationCount()).isEqualTo(1L);
  }

  @Test
  void 容量与承重充足才进候选且余量是真算的() {
    UUID zone = insertLocation(a, "ZONE", "RECEIVING", null, null, null);
    UUID dock = insertLocation(a, "DOCK", "RECEIVING", zone, null, null);
    insertLocation(a, "S-01", "STORAGE", zone, new BigDecimal("100"), new BigDecimal("50"));

    Receipt r = receipt(a, sku1(a), "SELLABLE", "10", dock);
    var plan = recommend(a, r, new BigDecimal("30"), new BigDecimal("20")).get(0);
    var c = plan.candidates().get(0);
    assertThat(c.capacityEvaluated()).isTrue();
    assertThat(c.remainingCapacity()).isEqualByComparingTo("100");
    assertThat(c.remainingWeight()).isEqualByComparingTo("50");
    assertThat(c.reasons()).extracting(LocationAllocationReason::code)
        .contains(ReasonCode.CAPACITY_FIT, ReasonCode.WEIGHT_FIT, ReasonCode.EMPTY_LOCATION);

    // 刚好装满也要算「装得下」（边界：等于上限不是超限）
    var exact = recommend(a, r, new BigDecimal("100"), new BigDecimal("50")).get(0);
    assertThat(exact.candidates()).hasSize(1);
  }

  @Test
  void 未提供的上架承诺会被下一个候选看见() {
    UUID zone = insertLocation(a, "ZONE", "RECEIVING", null, null, null);
    UUID dock = insertLocation(a, "DOCK", "RECEIVING", zone, null, null);
    UUID only = insertLocation(a, "ONLY", "STORAGE", zone, new BigDecimal("100"), null);

    Receipt r = receipt(a, sku1(a), "SELLABLE", "10", dock);
    // 先承诺 80 容量
    as(a, "update", () -> service.confirm(
        new LocationAllocationConfirmInput(r.receiptId(), r.lineId(), only,
            new BigDecimal("10"), new BigDecimal("80"), null), user));

    // 再推一次（另一批货，同样要 30 容量）：只剩 20 装不下 → 不进候选
    Receipt r2 = receipt(a, sku2(a), "SELLABLE", "10", dock);
    var plan = recommend(a, r2, new BigDecimal("30"), null).get(0);
    assertThat(plan.candidates())
        .as("容量已被未取消的决策占掉 80，ONLY 只剩 20，装不下 30")
        .noneSatisfy(c -> assertThat(c.code()).isEqualTo("ONLY"));
  }

  @Test
  void 目标库位就是当前所在库位被拒() {
    UUID zone = insertLocation(a, "ZONE", "RECEIVING", null, null, null);
    UUID dock = insertLocation(a, "DOCK", "RECEIVING", zone, null, null);
    Receipt r = receipt(a, sku1(a), "SELLABLE", "10", dock);
    // dock 是 RECEIVING，选它连类型校验都过不了；这里验「不发生移动」的独立提示
    // ——用一个与目标类型相同、恰好是源位的形状不可能构造，所以直接断言类型校验优先。
    assertThatThrownBy(() -> confirm(a, r, dock, "10"))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .satisfies(e -> assertThat(((org.springframework.web.server.ResponseStatusException) e)
                .getStatusCode().value()).isEqualTo(400));
  }

  // ================================================================ 写入与不变量

  @Test
  void 选定后冻结规则快照() {
    UUID zone = insertLocation(a, "ZONE", "RECEIVING", null, null, null);
    UUID dock = insertLocation(a, "DOCK", "RECEIVING", zone, null, null);
    UUID target = insertLocation(a, "S-01", "STORAGE", zone, new BigDecimal("100"), null);
    Receipt r = receipt(a, sku1(a), "SELLABLE", "10", dock);

    var plan = recommend(a, r, new BigDecimal("20"), null).get(0);
    var saved = confirm(a, r, target, "10");
    assertThat(saved.status()).isEqualTo("PLANNED");

    var decision = as(a, "read", () -> service.byReceipt(r.receiptId())).get(0);
    assertThat(decision.locationId()).isEqualTo(target);
    assertThat(decision.sourceLocationId()).isEqualTo(dock);
    assertThat(decision.requiredType().name()).isEqualTo("STORAGE");
    assertThat(decision.sourceDistance())
        .as("路径跳数要与推荐算出来的一致").isEqualTo(plan.candidates().get(0).distance());
    assertThat(decision.ruleSummary()).contains("硬规则").contains("上架路径");
    assertThat(decision.allocateQuantity()).isEqualByComparingTo("10");
  }

  @Test
  void 数量用NUMERIC存且0点1乘3读回0点3() {
    UUID zone = insertLocation(a, "ZONE", "RECEIVING", null, null, null);
    UUID dock = insertLocation(a, "DOCK", "RECEIVING", zone, null, null);
    UUID target = insertLocation(a, "S-01", "STORAGE", zone, null, null);
    // 期望量 0.3：逐次 0.1 累加三次（收货是逐次实收的，见 V70 的实收事件设计）
    Receipt r = as(a, "read", () -> {
      UUID receiptId = UUID.randomUUID();
      UUID lineId = UUID.randomUUID();
      sql.execute(
          "insert into goods_receipt(id,tenant_id,receipt_code,source_type,source_ref_id,"
              + "source_ref_code,warehouse_id,legal_entity_id,status,created_by) "
              + "values(?,?,?,?,?,?,?,?, 'PARTIALLY_RECEIVED',?)",
          receiptId, a, "GR-DEC", "PURCHASE", UUID.randomUUID(), "PO-DEC",
          wh(a), idFor(a, 1), user);
      sql.execute(
          "insert into goods_receipt_line(id,tenant_id,receipt_id,sku_id,quality_status_id,"
              + "expected_quantity,received_quantity) values(?,?,?,?,?,?,?)",
          lineId, a, receiptId, sku1(a), quality(a, "SELLABLE"), new BigDecimal("0.3"),
          new BigDecimal("0.1"));
      sql.execute(
          "insert into goods_receipt_event(id,tenant_id,receipt_id,received_at,location_id,actor_id) "
              + "values(?,?,?,?::timestamptz,?,?)",
          UUID.randomUUID(), a, receiptId, now(), dock, user);
      return new Receipt(receiptId, lineId);
    });
    for (int i = 0; i < 2; i++) {
      as(a, "read", () -> sql.execute(
          "update goods_receipt_line set received_quantity = received_quantity + cast(? as numeric)"
              + " where tenant_id = current_setting('app.current_tenant',true)::uuid and id = ?::uuid",
          new BigDecimal("0.1"), r.lineId()));
    }
    // 实收累计到 0.3 之后才整批指派（先指派再累加就量错了余量，那是测试自己写错）
    confirm(a, r, target, "0.3");

    var decision = as(a, "read", () -> service.byReceipt(r.receiptId())).get(0);
    // 列是 numeric(19,6)，所以 0.3 读回是 "0.300000" —— 比 double 还多一位有效位。
    // 关键断言是它**不是**浮点残渣 0.30000000000000004。
    assertThat(decision.allocateQuantity())
        .as("0.1 × 3 必须精确等于 0.3（走 NUMERIC，不经过 double）")
        .isEqualByComparingTo("0.3");
    assertThat(decision.allocateQuantity().toPlainString())
        .as("scale 6 的读回形状")
        .isEqualTo("0.300000");
    assertThat(decision.allocateQuantity().toPlainString())
        .as("绝不能是浮点残渣")
        .isNotEqualTo("0.30000000000000004");
    // 余量也是 NUMERIC 算术：0.3 − 0.3 = 0，不是 -2.2E-17
    assertThat(recommend(a, r).get(0).outstandingQuantity())
        .as("0.3 − 0.3 必须正好是 0")
        .isEqualByComparingTo("0");
  }

  @Test
  void 同一明细行不得双指派() {
    UUID zone = insertLocation(a, "ZONE", "RECEIVING", null, null, null);
    UUID dock = insertLocation(a, "DOCK", "RECEIVING", zone, null, null);
    UUID s1 = insertLocation(a, "S-01", "STORAGE", zone, null, null);
    UUID s2 = insertLocation(a, "S-02", "STORAGE", zone, null, null);
    Receipt r = receipt(a, sku1(a), "SELLABLE", "10", dock);

    confirm(a, r, s1, "5");
    // 同一行第二次指派 → 部分唯一索引 23505（DB 不变量，不是应用层先查一次：
    // 并发下两个请求都能查到「还没有」）
    assertThatThrownBy(() -> confirm(a, r, s2, "5"))
        .hasMessageContaining("location_allocation_line_open");

    // 余量也已按已指派量扣减
    var plan = recommend(a, r).get(0);
    assertThat(plan.outstandingQuantity()).isEqualByComparingTo("5");
  }

  @Test
  void 上架数量超过未指派余量被拒() {
    UUID zone = insertLocation(a, "ZONE", "RECEIVING", null, null, null);
    UUID dock = insertLocation(a, "DOCK", "RECEIVING", zone, null, null);
    UUID s1 = insertLocation(a, "S-01", "STORAGE", zone, null, null);
    Receipt r = receipt(a, sku1(a), "SELLABLE", "10", dock);

    assertThatThrownBy(() -> confirm(a, r, s1, "11"))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .satisfies(e -> assertThat(((org.springframework.web.server.ResponseStatusException) e)
                .getStatusCode().value()).isEqualTo(409));
  }

  @Test
  void 撤销必须带原因且撤销后可重新指派() {
    UUID zone = insertLocation(a, "ZONE", "RECEIVING", null, null, null);
    UUID dock = insertLocation(a, "DOCK", "RECEIVING", zone, null, null);
    UUID s1 = insertLocation(a, "S-01", "STORAGE", zone, null, null);
    UUID s2 = insertLocation(a, "S-02", "STORAGE", zone, null, null);
    // 实收 20：撤销后重新指派 10，再第三次指派时**余量还够**，
    // 冲突才会落在「同一行双指派」这条约束上，而不是先撞 409 数量校验。
    Receipt r = receipt(a, sku1(a), "SELLABLE", "20", dock);

    var saved = confirm(a, r, s1, "10");
    // 空原因 → 服务层 400；绕过的空白串也被 DB 的 CHECK 拒
    assertThatThrownBy(() -> as(a, "update", () -> service.cancel(saved.id(), new LocationAllocationCancelInput("  "))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> as(a, "update", () ->
        service.cancel(saved.id(), new LocationAllocationCancelInput(null))))
        .isInstanceOf(IllegalArgumentException.class);

    as(a, "update", () -> service.cancel(saved.id(), new LocationAllocationCancelInput("客户改单了")));
    // 撤销即释放该明细行的占用（部分唯一索引只约束未取消的决策），所以可以重新指派
    var again = confirm(a, r, s2, "10");
    assertThat(again.status()).isEqualTo("PLANNED");
    // 而重新指派**之后**再指派第三个才是双指派
    UUID s3 = insertLocation(a, "S-03", "STORAGE", zone, null, null);
    assertThatThrownBy(() -> confirm(a, r, s3, "10"))
        .as("同一行只能有一个未取消的决策")
        .hasMessageContaining("location_allocation_line_open");
    var decisions = as(a, "read", () -> service.byReceipt(r.receiptId()));
    assertThat(decisions).hasSize(2);
    assertThat(decisions).extracting(LocationAllocationDecision::status)
        .containsExactlyInAnyOrder("CANCELLED", "PLANNED");
  }

  @Test
  void 释放时间断言写回的那个瞬间() {
    UUID zone = insertLocation(a, "ZONE", "RECEIVING", null, null, null);
    UUID dock = insertLocation(a, "DOCK", "RECEIVING", zone, null, null);
    UUID s1 = insertLocation(a, "S-01", "STORAGE", zone, null, null);
    Receipt r = receipt(a, sku1(a), "SELLABLE", "10", dock);
    var saved = confirm(a, r, s1, "10");

    // 前后各放宽 1 秒：PG 的 now() 带微秒，秒级边界会把写回值判到区间外（假失败）
    OffsetDateTime before = now().minusSeconds(1);
    var released = as(a, "update", () -> service.release(saved.id(), user));
    OffsetDateTime after = now().plusSeconds(1);

    assertThat(released.status()).isEqualTo("RELEASED");
    assertThat(released.releasedAt()).as("释放时间必须断言写回的值，不能只断言没抛异常").isNotNull();
    // 比 Instant 而不是直接比 OffsetDateTime：两者的偏移量不同，compareTo 会先比
    // 本地日历时间，PG 读回的偏移量与 +08:00 不同时就会假失败。
    assertThat(released.releasedAt().toInstant())
        .isBetween(before.toInstant(), after.toInstant());
    // 同一瞬间（换算到 +08:00）：Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s
    var raw = as(a, "read", () -> sql.fetchOne(
        "select released_at from location_allocation where id = ?::uuid", saved.id()));
    assertThat(raw.get("released_at", OffsetDateTime.class).toInstant())
        .isEqualTo(released.releasedAt().toInstant());
  }

  @Test
  void 没有实收记录就没有可计算的上架起点() {
    UUID zone = insertLocation(a, "ZONE", "RECEIVING", null, null, null);
    UUID dock = insertLocation(a, "DOCK", "RECEIVING", zone, null, null);
    insertLocation(a, "S-01", "STORAGE", zone, null, null);
    // 造一张**没有实收事件**的收货单：上架路径没有起点，不能编一个距离出来
    Receipt r = as(a, "read", () -> {
      UUID receiptId = UUID.randomUUID();
      UUID lineId = UUID.randomUUID();
      sql.execute(
          "insert into goods_receipt(id,tenant_id,receipt_code,source_type,source_ref_id,"
              + "source_ref_code,warehouse_id,legal_entity_id,status,created_by) "
              + "values(?,?,?,?,?,?,?,?, 'DRAFT',?)",
          receiptId, a, "GR-NO-EVENT", "PURCHASE", UUID.randomUUID(), "PO-X",
          wh(a), idFor(a, 1), user);
      sql.execute(
          "insert into goods_receipt_line(id,tenant_id,receipt_id,sku_id,quality_status_id,"
              + "expected_quantity,received_quantity) values(?,?,?,?,?,?,?)",
          lineId, a, receiptId, sku1(a), quality(a, "SELLABLE"), new BigDecimal("10"), new BigDecimal("0"));
      return new Receipt(receiptId, lineId);
    });
    assertThat(dock).isNotNull();
    assertThatThrownBy(() -> recommend(a, r))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .satisfies(e -> assertThat(((org.springframework.web.server.ResponseStatusException) e)
                .getStatusCode().value()).isEqualTo(409))
        .hasMessageContaining("没有可计算的上架起点");
  }

  // ================================================================ 读：清单

  @Test
  void 清单真分页排序白名单且合计不受分页影响() {
    UUID zone = insertLocation(a, "ZONE", "RECEIVING", null, null, null);
    UUID dock = insertLocation(a, "DOCK", "RECEIVING", zone, null, null);
    UUID s1 = insertLocation(a, "S-01", "STORAGE", zone, null, null);
    UUID s2 = insertLocation(a, "S-02", "STORAGE", zone, null, null);

    for (int i = 0; i < 5; i++) {
      Receipt r = receipt(a, i % 2 == 0 ? sku1(a) : sku2(a), "SELLABLE", "10", dock);
      confirm(a, r, i % 2 == 0 ? s1 : s2, "10");
    }

    var all = as(a, "read", () -> service.list(new LocationAllocationQuery(null, null, null, null, null, 1, 20)));
    assertThat(all.total()).isEqualTo(5L);
    assertThat(all.totals().decisionCount()).isEqualTo(5L);
    assertThat(all.totals().allocateQuantity()).isEqualByComparingTo("50");

    var firstPage = as(a, "read", () -> service.list(new LocationAllocationQuery(null, null, null, null, null, 1, 2)));
    assertThat(firstPage.rows()).hasSize(2);
    assertThat(firstPage.total()).isEqualTo(5L);
    assertThat(firstPage.totals().decisionCount())
        .as("合计不受分页影响").isEqualTo(5L);

    var secondPage = as(a, "read", () -> service.list(new LocationAllocationQuery(null, null, null, null, null, 2, 2)));
    assertThat(secondPage.rows()).hasSize(2);
    assertThat(secondPage.rows()).doesNotContainAnyElementsOf(firstPage.rows());

    // 筛选
    var filtered = as(a, "read", () -> service.list(
        new LocationAllocationQuery(wh(a), "PLANNED", "SELLABLE", "S-01", "CREATED_AT", 1, 20)));
    assertThat(filtered.rows()).hasSize(3);
    assertThat(filtered.totals().decisionCount()).as("合计按筛选口径算").isEqualTo(3L);

    // 非法排序键 400，不静默忽略
    assertThatThrownBy(() -> as(a, "read", () -> service.list(
        new LocationAllocationQuery(null, null, null, null, "NOT_A_SORT_KEY", 1, 20))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("白名单");
  }

  // ================================================================ 多租户三断言

  @Test
  void 两租户互不可见() {
    UUID zoneA = insertLocation(a, "ZONE", "RECEIVING", null, null, null);
    UUID dockA = insertLocation(a, "DOCK", "RECEIVING", zoneA, null, null);
    UUID sA = insertLocation(a, "S-01", "STORAGE", zoneA, null, null);
    Receipt ra = receipt(a, sku1(a), "SELLABLE", "10", dockA);
    confirm(a, ra, sA, "10");

    UUID zoneB = insertLocation(b, "ZONE", "RECEIVING", null, null, null);
    UUID dockB = insertLocation(b, "DOCK", "RECEIVING", zoneB, null, null);
    UUID sB = insertLocation(b, "S-01", "STORAGE", zoneB, null, null);
    Receipt rb = receipt(b, sku1(b), "SELLABLE", "10", dockB);
    confirm(b, rb, sB, "10");

    assertThat(as(a, "read", () -> service.byReceipt(ra.receiptId()))).hasSize(1);
    assertThat(as(b, "read", () -> service.byReceipt(rb.receiptId()))).hasSize(1);
    assertThat(as(a, "read", () -> service.list(new LocationAllocationQuery(null, null, null, null, null, 1, 20))).total())
        .as("A 只看得到自己的 1 条").isEqualTo(1L);
    assertThat(as(b, "read", () -> service.list(new LocationAllocationQuery(null, null, null, null, null, 1, 20))).total())
        .as("B 只看得到自己的 1 条").isEqualTo(1L);

    // B 拿 A 的收货单 → 404（「不存在」与「不属于你」不可区分）
    assertThatThrownBy(() -> as(b, "read", () -> service.byReceipt(ra.receiptId())))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .satisfies(e -> assertThat(((org.springframework.web.server.ResponseStatusException) e)
                .getStatusCode().value()).isEqualTo(404));
  }

  @Test
  void 伪造tenant头无效() {
    UUID zoneA = insertLocation(a, "ZONE", "RECEIVING", null, null, null);
    UUID dockA = insertLocation(a, "DOCK", "RECEIVING", zoneA, null, null);
    UUID sA = insertLocation(a, "S-01", "STORAGE", zoneA, null, null);
    Receipt ra = receipt(a, sku1(a), "SELLABLE", "10", dockA);
    var saved = confirm(a, ra, sA, "10");

    // B 拿 A 的决策 id 去读/改 —— 租户来自已认证成员关系，客户端伪造的头进不来也改不动
    assertThatThrownBy(() -> as(b, "update", () -> service.release(saved.id(), user)))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .satisfies(e -> assertThat(((org.springframework.web.server.ResponseStatusException) e)
                .getStatusCode().value()).isEqualTo(404));
    // A 的决策没被动过
    assertThat(as(a, "read", () -> service.byReceipt(ra.receiptId())).get(0).status())
        .isEqualTo("PLANNED");

    // B 在自己的上下文里指派 A 的收货明细行 → 404（A 的行对 B 不可见）
    assertThatThrownBy(() -> as(b, "update", () -> service.confirm(
        new LocationAllocationConfirmInput(ra.receiptId(), ra.lineId(), sA,
            new BigDecimal("10"), null, null), user)))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }

  @Test
  void 连接池归还后不串租户() {
    UUID zoneA = insertLocation(a, "ZONE", "RECEIVING", null, null, null);
    UUID dockA = insertLocation(a, "DOCK", "RECEIVING", zoneA, null, null);
    UUID sA = insertLocation(a, "S-01", "STORAGE", zoneA, null, null);
    Receipt ra = receipt(a, sku1(a), "SELLABLE", "10", dockA);
    confirm(a, ra, sA, "10");

    UUID zoneB = insertLocation(b, "ZONE", "RECEIVING", null, null, null);
    UUID dockB = insertLocation(b, "DOCK", "RECEIVING", zoneB, null, null);
    UUID sB = insertLocation(b, "S-01", "STORAGE", zoneB, null, null);
    Receipt rb = receipt(b, sku1(b), "SELLABLE", "10", dockB);
    confirm(b, rb, sB, "10");

    // 连接池只有 1 条连接，三轮交替借用同一会话
    for (int round = 0; round < 3; round++) {
      var forA = as(a, "read", () -> service.list(new LocationAllocationQuery(null, null, null, null, null, 1, 20)));
      var forB = as(b, "read", () -> service.list(new LocationAllocationQuery(null, null, null, null, null, 1, 20)));
      assertThat(forA.total()).as("第 %d 轮 A 侧", round).isEqualTo(1L);
      assertThat(forB.total()).as("第 %d 轮 B 侧", round).isEqualTo(1L);
      assertThat(as(a, "read", () -> service.byReceipt(ra.receiptId())).get(0).receiptId())
          .isEqualTo(ra.receiptId());
      assertThat(as(b, "read", () -> service.byReceipt(rb.receiptId())).get(0).receiptId())
          .isEqualTo(rb.receiptId());
    }
  }

  @Test
  void 本表FORCE_RLS且无租户上下文读不到行() {
    for (var table : List.of("location_allocation", "warehouse_location", "stock_bucket",
        "inventory_balance", "goods_receipt", "goods_receipt_line", "goods_receipt_event",
        "inventory_quality_status")) {
      boolean forced = sql.fetchOne(
              "select (relrowsecurity and relforcerowsecurity) as on from pg_class"
                  + " where relname=? and relnamespace='public'::regnamespace", table)
          .get("on", Boolean.class);
      assertThat(forced).as("%s 必须 FORCE RLS", table).isTrue();
    }
    UUID zoneA = insertLocation(a, "ZONE", "RECEIVING", null, null, null);
    UUID dockA = insertLocation(a, "DOCK", "RECEIVING", zoneA, null, null);
    UUID sA = insertLocation(a, "S-01", "STORAGE", zoneA, null, null);
    Receipt ra = receipt(a, sku1(a), "SELLABLE", "10", dockA);
    confirm(a, ra, sA, "10");

    assertThat(sql.fetchOne(
            "select count(*) from location_allocation"
                + " where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, Long.class)).as("没有 app.current_tenant 时读到 0 行").isZero();
  }

  // ================================================================ HTTP 端点

  @Test
  void 能力声明如实标出四条未交付() throws Exception {
    http.perform(get("/warehouse/location-allocations/capabilities")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hardPlacementRuleEnforced").value(true))
        .andExpect(jsonPath("$.occupancyLive").value(true))
        .andExpect(jsonPath("$.pathDistanceAvailable").value(true))
        .andExpect(jsonPath("$.routingGraphAvailable").value(false))
        .andExpect(jsonPath("$.routingGraphOwnerModule").value("8.3.2.1"))
        .andExpect(jsonPath("$.catalogCategoryAvailable").value(false))
        .andExpect(jsonPath("$.catalogCategoryOwnerModule").value("3.3.1.1"))
        .andExpect(jsonPath("$.stockVolumeAvailable").value(false))
        .andExpect(jsonPath("$.putAwayTaskAvailable").value(false))
        .andExpect(jsonPath("$.putAwayTaskOwnerModule").value("8.2.2.1"))
        .andExpect(jsonPath("$.rules").isArray())
        .andExpect(jsonPath("$.targetTypes").isArray());
  }

  @Test
  void 端点按权限码裁决() throws Exception {
    http.perform(get("/warehouse/location-allocations/list"))
        .andExpect(status().isUnauthorized());
    http.perform(get("/warehouse/location-allocations/list").header("Authorization", "Bearer no-permission"))
        .andExpect(status().isForbidden());
    http.perform(get("/warehouse/location-allocations/list").header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk());

    UUID zone = insertLocation(a, "ZONE", "RECEIVING", null, null, null);
    UUID dock = insertLocation(a, "DOCK", "RECEIVING", zone, null, null);
    UUID s1 = insertLocation(a, "S-01", "STORAGE", zone, null, null);
    Receipt r = receipt(a, sku1(a), "SELLABLE", "10", dock);

    // 只读角色不能选定库位
    http.perform(post("/warehouse/location-allocations")
            .header("Authorization", "Bearer read-only")
            .contentType("application/json")
            .content("""
                {"receiptId":"%s","receiptLineId":"%s","locationId":"%s","allocateQuantity":10}
                """.formatted(r.receiptId(), r.lineId(), s1)))
        .andExpect(status().isForbidden());

    // 正常选定 → 出现在清单里
    http.perform(post("/warehouse/location-allocations")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content("""
                {"receiptId":"%s","receiptLineId":"%s","locationId":"%s","allocateQuantity":10}
                """.formatted(r.receiptId(), r.lineId(), s1)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PLANNED"))
        .andExpect(jsonPath("$.requiredRule").value("SELLABLE"));

    http.perform(get("/warehouse/location-allocations/list").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.totals.decisionCount").value(1));

    http.perform(get("/warehouse/location-allocations/by-receipt/{id}", r.receiptId())
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].locationCode").value("S-01"))
        .andExpect(jsonPath("$[0].requiredType").value("STORAGE"));

    // 非法排序键 400
    http.perform(get("/warehouse/location-allocations/list").param("sort", "NOT_A_SORT_KEY")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isBadRequest());

    // 不属于本租户的收货单 → 404
    http.perform(get("/warehouse/location-allocations/by-receipt/{id}", UUID.randomUUID())
            .header("Authorization", "Bearer tenant-b"))
        .andExpect(status().isNotFound());
  }

  @Test
  void 推荐端点返回带理由的候选列表() throws Exception {
    UUID zone = insertLocation(a, "ZONE", "RECEIVING", null, null, null);
    UUID dock = insertLocation(a, "DOCK", "RECEIVING", zone, null, null);
    insertLocation(a, "S-01", "STORAGE", zone, new BigDecimal("100"), new BigDecimal("50"));
    Receipt r = receipt(a, sku1(a), "SELLABLE", "10", dock);

    http.perform(post("/warehouse/location-allocations/recommend")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content("""
                {"receiptId":"%s","requiredCapacity":20,"requiredWeight":10}
                """.formatted(r.receiptId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].requiredType").value("STORAGE"))
        .andExpect(jsonPath("$[0].requiredTypeLabel").value("存储"))
        .andExpect(jsonPath("$[0].candidates[0].code").value("S-01"))
        .andExpect(jsonPath("$[0].candidates[0].capacityEvaluated").value(true))
        .andExpect(jsonPath("$[0].candidates[0].reasons").isArray())
        .andExpect(jsonPath("$[0].sourceLocationCode").value("DOCK"));
  }
}
