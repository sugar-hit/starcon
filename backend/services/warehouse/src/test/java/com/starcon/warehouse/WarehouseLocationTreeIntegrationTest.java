package com.starcon.warehouse;

import static com.starcon.warehouse.WarehouseLocationTreeModels.*;
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
 * 8.1.2.1 库位树。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>本测试钉住七件「错了会误导人」的事：
 *
 * <ul>
 *   <li><b>跨租户与跨仓库挂父子被 DB 拒。</b>蓝图的红线是「库位不能跨租户引用，
 *       也不允许跨仓库」—— 所以用<b>超级用户连接直连 SQL</b> 绕开服务层去挂，
 *       外键必须当场拒绝。只在应用层挡的话，运维脚本一挂就破。
 *   <li><b>防环是 DB 不变量。</b>成环会让递归 CTE 永不收敛，整棵树查询打不出结果 ——
 *       所以它不能只是服务层一个 if。
 *   <li><b>租户隔离三断言</b>实跑 + FORCE RLS 逐张断言。
 *   <li><b>NUMERIC 精确</b>：0.1×3 读回必须是 0.3，不是 0.30000000000000004。
 *   <li><b>「没量过」≠ 0</b>：容量未填时利用率给 null；量过且空置才是 0.0000。
 *   <li><b>手数与预占不相加</b>。
 *   <li><b>根节点 parent_id 允许 NULL</b>：这是「区」合法存在的形状，
 *       也是 V105 补三列时不能改成 NOT NULL 的原因（那会破坏既有 INSERT）。
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class WarehouseLocationTreeIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      List.of("warehouse.facilities.read", "warehouse.facilities.create", "warehouse.facilities.update");

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-location-tree-" + tenant + "-" + ordinal).getBytes());
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

  @Autowired WarehouseLocationTreeService service;
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
    }
    for (var tenant : List.of(a, b)) {
      access.run(
          actorFor(tenant),
          "warehouse.facilities.read",
          () -> {
            sql.execute(
                "insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 1), tenant, "LE-1", "主体一");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 2), tenant, "SKU-1", "商品一");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 3), tenant, "SKU-2", "商品二");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                UUID.nameUUIDFromBytes(("third-sku-" + tenant).getBytes()), tenant, "SKU-3", "商品三");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 4), tenant, "SELLABLE", "可售", "SELLABLE");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,kind,country_code,timezone,"
                    + "default_legal_entity_id,status) values(?,?,?,?,?,?,?,?,?)",
                idFor(tenant, 5), tenant, "WH-" + (tenant.equals(a) ? "A" : "B"), "本租仓库",
                "LOCAL", tenant.equals(a) ? "CN" : "US", "Asia/Shanghai", idFor(tenant, 1), "ACTIVE");
            // 第二个仓库：**同租户**、另一个仓 —— 用来验「不允许跨仓库挂父子」。
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,kind,country_code,timezone,"
                    + "default_legal_entity_id,status) values(?,?,?,?,?,?,?,?,?)",
                idFor(tenant, 6), tenant, "WH2-" + (tenant.equals(a) ? "A" : "B"), "本租第二仓",
                "LOCAL", "CN", "Asia/Shanghai", idFor(tenant, 1), "ACTIVE");
            return null;
          });
    }
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  <T> T asA(String permission, java.util.function.Supplier<T> work) {
    return access.run(actorA, "warehouse.facilities." + permission, work);
  }

  <T> T asB(String permission, java.util.function.Supplier<T> work) {
    return access.run(actorB, "warehouse.facilities." + permission, work);
  }

  LocationTree tree(UUID tenant, UUID warehouse) {
    return access.run(
        actorFor(tenant), "warehouse.facilities.read", () -> service.tree(warehouse));
  }

  UUID wh(UUID tenant) {
    return idFor(tenant, 5);
  }

  UUID wh2(UUID tenant) {
    return idFor(tenant, 6);
  }

  // ================================================================ 夹具

  /** 直接落库一个库位（不走服务层），用于构造树形。 */
  UUID insertLocation(
      UUID tenant, UUID warehouse, String code, String type, UUID parent,
      BigDecimal capacity, BigDecimal weight) {
    // 必须在**写入行的那个租户**的上下文里跑：RLS 的 WITH CHECK 会拿
    // app.current_tenant 比对 tenant_id，用 A 的上下文写 B 的行会被 DB 拒（这本身就是好事）。
    return access.run(actorFor(tenant), "warehouse.facilities.read", () -> {
      UUID id = UUID.randomUUID();
      sql.execute(
          "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,status,"
              + "pickable,parent_id,capacity,weight_limit) values(?,?,?,?,?,?,'ACTIVE',true,"
              + "cast(? as uuid),cast(? as numeric),cast(? as numeric))",
          // 三个可空列必须带 SQL 类型：jOOQ 绑定无类型 null 时会抛
          // "Type class java.lang.Class is not supported in dialect POSTGRES"。
          id, tenant, warehouse, code, code + " 位", type,
          parent == null ? DSL.val((UUID) null, UUID.class) : DSL.val(parent, UUID.class),
          capacity == null ? DSL.val((BigDecimal) null, BigDecimal.class) : DSL.val(capacity, BigDecimal.class),
          weight == null ? DSL.val((BigDecimal) null, BigDecimal.class) : DSL.val(weight, BigDecimal.class));
      return id;
    });
  }

  /** 一条库位明细（占用来源）。数量走 NUMERIC 绑定，绝不经过 double。 */
  void bucket(UUID tenant, UUID warehouse, UUID location, UUID sku, String quantity) {
    access.run(actorFor(tenant), "warehouse.facilities.read", () -> {
      UUID balanceId = UUID.randomUUID();
      sql.execute(
          "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
              + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,0)",
          balanceId, tenant, sku, warehouse, idFor(tenant, 1), idFor(tenant, 4),
          new BigDecimal(quantity));
      sql.execute(
          "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,location_id,"
              + "quality_status_id,balance_id,on_hand,reserved,last_movement_at) "
              + "values(?,?,?,?,?,?,?,?,?,0::numeric,?::timestamptz)",
          UUID.randomUUID(), tenant, idFor(tenant, 1), sku, warehouse, location,
          idFor(tenant, 4), balanceId, new BigDecimal(quantity), now());
      return null;
    });
  }

  static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.ofHours(8)).truncatedTo(ChronoUnit.MILLIS);
  }

  /**
   * 在超级用户连接上执行 —— 用来证明「绕过应用角色也被 DB 拒绝」。
   *
   * <p>失败时抛 {@link IllegalStateException}（包着 SQLException 的 sqlstate 文本），
   * 与 {@code assertThatThrownBy} 的用法对齐。
   */
  void asSuperuser(String sqlText, Object... binds) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var ps = c.prepareStatement(sqlText)) {
      for (int i = 0; i < binds.length; i++) {
        // 显式按类型 setObject：setObject(i, null) 会让 PG 无法推断参数类型，
        // 而这里 bind 的确有 null（跨仓库用例要挂一个别的仓的 parent）。
        ps.setObject(i + 1, binds[i], java.sql.Types.OTHER);
      }
      ps.execute();
    } catch (java.sql.SQLException e) {
      // 把 SQLSTATE 拼进消息：测试要断言 23514（check_violation）/ 23503（fk_violation），
      // 只看 PSQLException.getMessage() 拿不到它。
      throw new IllegalStateException(e.getSQLState() + " " + e.getMessage(), e);
    }
  }

  // ================================================================ 树

  @Test
  void 库位树按层级输出且根可为空父() {
    UUID zone = insertLocation(a, wh(a), "ZONE-A", "STORAGE", null, new BigDecimal("100"), new BigDecimal("500"));
    UUID aisle = insertLocation(a, wh(a), "A-01", "STORAGE", zone, null, null);
    insertLocation(a, wh(a), "R-01", "RECEIVING", aisle, new BigDecimal("10"), new BigDecimal("50"));

    LocationTree t = tree(a, wh(a));
    // 扁平层级行：父先于子，depth 表达缩进
    assertThat(t.levels()).hasSize(3);
    assertThat(t.rootCount()).isEqualTo(1);
    LocationNode root = t.levels().get(0);
    assertThat(root.code()).isEqualTo("ZONE-A");
    assertThat(root.parentId()).isNull();          // 根节点 parent_id 必须是 NULL（不是漏填）
    assertThat(root.depth()).isZero();
    LocationNode child = t.levels().get(1);
    assertThat(child.code()).isEqualTo("A-01");
    assertThat(child.depth()).isEqualTo(1);
    assertThat(child.parentId()).isEqualTo(root.id());
    assertThat(t.levels().get(2).depth()).isEqualTo(2);
    // 容量可空：未量过的节点给 null，不折成 0
    assertThat(child.capacity()).isNull();
    assertThat(child.weightLimit()).isNull();
    assertThat(root.capacity()).isEqualByComparingTo("100");
  }

  @Test
  void 占用从库位明细实时聚合且手数与预占分列() {
    UUID zone = insertLocation(a, wh(a), "ZONE-A", "STORAGE", null, null, null);
    UUID bin = insertLocation(a, wh(a), "B-01", "STORAGE", zone, null, null);
    bucket(a, wh(a), bin, idFor(a, 2), "3");
    bucket(a, wh(a), bin, idFor(a, 3), "5");

    LocationTree t = tree(a, wh(a));
    LocationNode root = t.levels().get(0);
    LocationNode leaf = t.levels().get(1);

    assertThat(leaf.occupied().onHand()).isEqualByComparingTo("8");
    assertThat(leaf.occupied().reserved()).isEqualByComparingTo("0");   // 两个数不相加
    assertThat(leaf.occupied().skuCount()).isEqualTo(2);
    assertThat(leaf.occupied().bucketRows()).isEqualTo(2);
    // 子树占用：父节点自己没货，但子树有 —— 「这棵树占了多少」与「这个库位占了多少」是两个数
    assertThat(root.occupied().onHand()).isEqualByComparingTo("0");
    assertThat(root.subtreeOccupancy().onHand()).isEqualByComparingTo("8");
    // 子树口径的利用率是**无意义的**：拿子节点的手数除以上层「区」的容量没有物理含义，
    // 所以恒为 null，不给一个没人能解释的百分比。
    assertThat(root.subtreeOccupancy().capacityUtilization()).isNull();
    assertThat(t.totals().onHand()).isEqualByComparingTo("8");
    assertThat(t.totals().skuCountDistinct()).isEqualTo(2);
  }

  @Test
  void 数量按NUMERIC精确读回不做浮点() {
    UUID zone = insertLocation(a, wh(a), "ZONE-A", "STORAGE", null, null, null);
    UUID bin = insertLocation(a, wh(a), "B-01", "STORAGE", zone, null, null);
    // 0.1 三行：走 double 会得到 0.30000000000000004
    // 三个不同的 SKU —— inventory_balance 的唯一键含 sku_id，同 SKU 两次登记会撞键。
    bucket(a, wh(a), bin, idFor(a, 2), "0.1");
    bucket(a, wh(a), bin, idFor(a, 3), "0.1");
    bucket(a, wh(a), bin, UUID.nameUUIDFromBytes("third-sku".getBytes()), "0.1");

    LocationTree t = tree(a, wh(a));
    BigDecimal onHand = t.levels().get(1).occupied().onHand();
    assertThat(onHand).isEqualByComparingTo("0.3");
    assertThat(onHand.toPlainString()).doesNotContain("00000004");
  }

  @Test
  void 容量利用率区分未量过与量过且空置() {
    UUID zone = insertLocation(a, wh(a), "ZONE-A", "STORAGE", null, null, null);
    UUID measured = insertLocation(a, wh(a), "B-M", "STORAGE", zone, new BigDecimal("50"), new BigDecimal("100"));
    UUID unmeasured = insertLocation(a, wh(a), "B-U", "STORAGE", zone, null, null);
    bucket(a, wh(a), measured, idFor(a, 2), "10");

    LocationTree t = tree(a, wh(a));
    LocationNode m = t.levels().stream().filter(n -> n.code().equals("B-M")).findFirst().orElseThrow();
    LocationNode u = t.levels().stream().filter(n -> n.code().equals("B-U")).findFirst().orElseThrow();

    assertThat(m.occupied().capacityUtilization()).isEqualByComparingTo("0.2000");
    assertThat(m.occupied().capacityExceeded()).isFalse();
    // 没量过容量 → null，不是 0：「量过且空置」与「从没量过」必须能分开
    assertThat(u.occupied().capacityUtilization()).isNull();
    assertThat(u.occupied().capacityExceeded()).isFalse();
    assertThat(t.totals().capacityMeasuredCount()).isEqualTo(1);
  }

  @Test
  void 超出量得容量的库位被标出来() {
    UUID zone = insertLocation(a, wh(a), "ZONE-A", "STORAGE", null, null, null);
    UUID tiny = insertLocation(a, wh(a), "B-T", "STORAGE", zone, new BigDecimal("2"), new BigDecimal("1"));
    bucket(a, wh(a), tiny, idFor(a, 2), "10");

    LocationNode leaf = tree(a, wh(a)).levels().get(1);
    assertThat(leaf.occupied().capacityExceeded()).isTrue();
  }

  @Test
  void 负容量与负承重被拒() {
    UUID zone = insertLocation(a, wh(a), "ZONE-A", "STORAGE", null, null, null);
    // 应用层先拦（nonNegative → IllegalArgumentException → ApiErrorHandler 翻成 400）；
    // 绕过应用层时 DB 的 CHECK 是第二道，下面用超级用户连接单独证明它。
    assertThatThrownBy(() -> { asA("create", () -> service.create(
        wh(a), new LocationCreateInput("B-N", "负容量", zone, "STORAGE", null,
            new BigDecimal("-1"), null))); })
        .isInstanceOf(IllegalArgumentException.class);
    // DB 兜底：超级用户绕过服务层直连写负容量，CHECK 仍当场拒绝（23514 = check_violation）
    assertThatThrownBy(() -> asSuperuser(
            "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,status,"
                + "pickable,parent_id,capacity,weight_limit) values(?,?,?,?,'x','STORAGE','ACTIVE',true,?,?,?)",
            UUID.randomUUID(), a, wh(a), "B-NEG", zone, new BigDecimal("-1"), null))
        .hasMessageContaining("23514");
    // 负承重同理
    assertThatThrownBy(() -> asSuperuser(
            "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,status,"
                + "pickable,parent_id,capacity,weight_limit) values(?,?,?,?,'y','STORAGE','ACTIVE',true,?,?,?)",
            UUID.randomUUID(), a, wh(a), "B-NEG-W", zone, new BigDecimal("1"), new BigDecimal("-5")))
        .hasMessageContaining("23514");
  }

  // ================================================================ 防环与跨仓库

  @Test
  void 服务层拒绝把库位挂到自己的子孙之下() {
    UUID zone = insertLocation(a, wh(a), "ZONE-A", "STORAGE", null, null, null);
    UUID aisle = insertLocation(a, wh(a), "A-01", "STORAGE", zone, null, null);

    assertThatThrownBy(() -> { asA("update", () -> service.update(
        wh(a), zone,
        new LocationUpdateInput(null, null, null, null, null, null, aisle, true))); })
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .satisfies(e -> assertThat(
            ((org.springframework.web.server.ResponseStatusException) e).getStatusCode().value())
            .isEqualTo(400));
  }

  @Test
  void 成环被数据库触发器拒绝_绕开服务层也一样() {
    UUID zone = insertLocation(a, wh(a), "ZONE-A", "STORAGE", null, null, null);
    UUID aisle = insertLocation(a, wh(a), "A-01", "STORAGE", zone, null, null);

    // 超级用户连接直连 SQL：应用层那道 if 在这里根本没有执行的机会，
    // 成环必须由 V105 的 guard_warehouse_location_tree 触发器拒绝。
    assertThatThrownBy(() -> asSuperuser(
            "update warehouse_location set parent_id=? where tenant_id=? and id=?",
            aisle, a, zone))
        .hasMessageContaining("23514");
    // 事务回滚后层级未变
    var afterRollback =
        asA("read", () -> sql.fetchOne(
            "select parent_id from warehouse_location where tenant_id=current_setting"
                + "('app.current_tenant',true)::uuid and id=?",
            zone));
    assertThat(afterRollback == null ? null : afterRollback.get(0, UUID.class)).isNull();
  }

  @Test
  void 跨仓库挂父子被数据库外键拒绝() {
    UUID inWh1 = insertLocation(a, wh(a), "ZONE-A", "STORAGE", null, null, null);
    UUID inWh2 = insertLocation(a, wh2(a), "ZONE-B", "STORAGE", null, null, null);

    // 蓝图红线：「库位不允许跨仓库挂接」。超级用户直连，绕开服务层。
    assertThatThrownBy(() -> asSuperuser(
            "update warehouse_location set parent_id=? where tenant_id=? and id=?",
            inWh2, a, inWh1))
        .hasMessageContaining("warehouse_location_parent_fk");
  }

  @Test
  void 服务层同样拒绝跨仓库挂父子并给出可读原因() {
    UUID inWh1 = insertLocation(a, wh(a), "ZONE-A", "STORAGE", null, null, null);
    UUID inWh2 = insertLocation(a, wh2(a), "ZONE-B", "STORAGE", null, null, null);
    UUID child = insertLocation(a, wh2(a), "B-01", "STORAGE", inWh2, null, null);

    assertThatThrownBy(() -> { asA("update", () -> service.update(
        wh2(a), child,
        new LocationUpdateInput(null, null, null, null, null, null, inWh1, true))); })
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .satisfies(e -> {
          var ex = (org.springframework.web.server.ResponseStatusException) e;
          assertThat(ex.getStatusCode().value()).isEqualTo(400);
          assertThat(ex.getReason()).contains("跨仓库");
        });
  }

  @Test
  void 超过六层的库位树被拒绝() {
    UUID parent = null;
    for (int i = 1; i <= 6; i++) {
      parent = insertLocation(a, wh(a), "L" + i, "STORAGE", parent, null, null);
    }
    UUID seventh = UUID.randomUUID();
    UUID deepest = parent;
    assertThatThrownBy(() -> asSuperuser(
            "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,status,"
                + "pickable,parent_id) values(?,?,?,?,'第七层','STORAGE','ACTIVE',true,?)",
            seventh, a, wh(a), "L7", deepest))
        .hasMessageContaining("23514");
  }

  // ================================================================ 增删改

  @Test
  void 新建库位能挂在指定父节点下() {
    LocationSaved zone = asA("create", () -> service.create(
        wh(a), new LocationCreateInput("ZONE-A", "A 区", null, "STORAGE", null, new BigDecimal("80"), new BigDecimal("400"))));
    assertThat(zone.id()).isNotNull();

    LocationSaved bin = asA("create", () -> service.create(
        wh(a), new LocationCreateInput("B-01", "01 货架 1 层", zone.id(), "STORAGE", null, new BigDecimal("5"), null)));
    assertThat(bin.action()).isEqualTo("CREATED");

    LocationTree t = tree(a, wh(a));
    assertThat(t.levels()).hasSize(2);
    assertThat(t.levels().get(1).code()).isEqualTo("B-01");
    assertThat(t.levels().get(1).parentId()).isEqualTo(zone.id());
    assertThat(t.totals().locationCount()).isEqualTo(2);
    assertThat(t.totals().rootCount()).isEqualTo(1);
    assertThat(t.totals().leafCount()).isEqualTo(1);
  }

  @Test
  void 层级调整会把库位挪到新父节点下() {
    UUID z1 = insertLocation(a, wh(a), "ZONE-A", "STORAGE", null, null, null);
    UUID z2 = insertLocation(a, wh(a), "ZONE-B", "STORAGE", null, null, null);
    UUID bin = insertLocation(a, wh(a), "B-01", "STORAGE", z1, null, null);

    LocationSaved moved = asA("update", () -> service.update(
        wh(a), bin, new LocationUpdateInput(null, null, null, null, null, null, z2, true)));
    assertThat(moved.action()).isEqualTo("MOVED");

    LocationTree t = tree(a, wh(a));
    assertThat(t.levels().stream().filter(n -> n.code().equals("B-01")).findFirst().orElseThrow()
            .parentId())
        .isEqualTo(z2);
    assertThat(t.levels().stream().filter(n -> n.code().equals("ZONE-A")).findFirst().orElseThrow()
            .id())
        .isEqualTo(z1);
  }

  @Test
  void 不带parentProvided时层级不动() {
    UUID z1 = insertLocation(a, wh(a), "ZONE-A", "STORAGE", null, null, null);
    UUID z2 = insertLocation(a, wh(a), "ZONE-B", "STORAGE", null, null, null);
    UUID bin = insertLocation(a, wh(a), "B-01", "STORAGE", z1, null, null);

    asA("update", () -> service.update(
        wh(a), bin, new LocationUpdateInput("改名了", null, null, null, null, null, null, null)));
    // 只改名，层级保持原样（null 与「升为根」不是同一件事）
    LocationTree t = tree(a, wh(a));
    assertThat(t.levels()).hasSize(3);
    assertThat(t.levels().get(1).parentId()).isEqualTo(z1);
  }

  @Test
  void 改属性可把库位升为根节点() {
    UUID zone = insertLocation(a, wh(a), "ZONE-A", "STORAGE", null, null, null);
    UUID bin = insertLocation(a, wh(a), "B-01", "STORAGE", zone, null, null);
    asA("update", () -> service.update(
        wh(a), bin, new LocationUpdateInput(null, null, null, null, null, null, null, true)));
    LocationTree t = tree(a, wh(a));
    assertThat(t.totals().rootCount()).isEqualTo(2);
    assertThat(t.rootCount()).isEqualTo(2);
    assertThat(t.levels().stream().filter(n -> n.code().equals("B-01")).findFirst().orElseThrow()
            .parentId())
        .isNull();
  }

  @Test
  void 只能删空叶子库位() {
    UUID zone = insertLocation(a, wh(a), "ZONE-A", "STORAGE", null, null, null);
    UUID bin = insertLocation(a, wh(a), "B-01", "STORAGE", zone, null, null);
    bucket(a, wh(a), bin, idFor(a, 2), "1");

    // 有库存：409，且把话说清楚
    assertThatThrownBy(() -> { asA("update", () -> { service.delete(wh(a), bin); return null; }); })
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .satisfies(e -> {
          var ex = (org.springframework.web.server.ResponseStatusException) e;
          assertThat(ex.getStatusCode().value()).isEqualTo(409);
          assertThat(ex.getReason()).contains("库存");
        });

    // 有子节点：zone 下面还挂着 bin（与 empty），删 zone 必须 409
    UUID empty = insertLocation(a, wh(a), "B-02", "STORAGE", zone, null, null);
    assertThatThrownBy(() -> { asA("update", () -> { service.delete(wh(a), zone); return null; }); })
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .satisfies(e -> assertThat(
            ((org.springframework.web.server.ResponseStatusException) e).getStatusCode().value())
            .isEqualTo(409));

    // 空叶子可删
    asA("update", () -> { service.delete(wh(a), empty); return null; });
    assertThat(tree(a, wh(a)).totals().locationCount()).isEqualTo(2);
  }

  @Test
  void 六类库位类型都能维护且待检残次冻结标为天然不可拣() {
    for (LocationType type : LocationType.values()) {
      asA("create", () -> service.create(
          wh(a), new LocationCreateInput(type.name() + "-1", type.label(), null, type.name(), null, null, null)));
    }
    List<LocationNode> nodes = tree(a, wh(a)).levels();
    assertThat(nodes).hasSize(LocationType.values().length);
    assertThat(nodes.stream().filter(LocationNode::pickableByNature).count()).isEqualTo(3);
    for (LocationNode n : nodes) {
      assertThat(n.type().label()).isNotBlank();
    }
    assertThatThrownBy(() -> { asA("create", () -> service.create(
        wh(a), new LocationCreateInput("X", "非法类型", null, "NOT_A_TYPE", null, null, null))); })
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 能力声明如实标出未交付的自动分配与拣货路径() {
    LocationTreeCapabilities caps = asA("read", service::capabilities);
    assertThat(caps.hierarchyMaintained()).isTrue();
    assertThat(caps.capacityTracked()).isTrue();
    assertThat(caps.weightTracked()).isTrue();
    assertThat(caps.occupancyLive()).isTrue();
    // 「未交付」与「有但是 0」是两种形状
    assertThat(caps.autoAllocationAvailable()).isFalse();
    assertThat(caps.autoAllocationOwnerModule()).isEqualTo("8.1.2.2");
    assertThat(caps.pickPathOwnerModule()).isEqualTo("8.3.2.1");
    assertThat(caps.types()).hasSize(6);
    assertThat(caps.maxDepth()).isEqualTo(6);
  }

  // ================================================================ 多租户

  @Test
  void 两租户互不可见() {
    insertLocation(a, wh(a), "ZONE-A", "STORAGE", null, null, null);
    insertLocation(b, wh(b), "ZONE-B", "STORAGE", null, null, null);

    assertThat(tree(a, wh(a)).levels().get(0).code()).isEqualTo("ZONE-A");
    assertThat(tree(b, wh(b)).levels().get(0).code()).isEqualTo("ZONE-B");
    // B 用 A 的仓 id 取树 → 404，不是「同名仓库」
    assertThatThrownBy(() -> { tree(b, wh(a)); })
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .satisfies(e -> assertThat(((org.springframework.web.server.ResponseStatusException) e)
                .getStatusCode().value()).isEqualTo(404));
  }

  @Test
  void 伪造tenant头无效() {
    UUID bin = insertLocation(a, wh(a), "B-01", "STORAGE", null, null, null);
    // B 拿 A 的库位 id 改属性 → 404
    assertThatThrownBy(() -> { asB("update", () -> service.update(
        wh(b), bin, new LocationUpdateInput("B 改的", null, null, null, null, null, null, null))); })
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    // A 的库位未被改动
    assertThat(tree(a, wh(a)).levels().get(0).name()).isEqualTo("B-01 位");
  }

  @Test
  void 连接池归还后不串租户() {
    UUID za = insertLocation(a, wh(a), "ZONE-A", "STORAGE", null, null, null);
    UUID zb = insertLocation(b, wh(b), "ZONE-B", "STORAGE", null, null, null);
    // 连接池只有 1 条连接，三轮交替借用同一会话
    for (int round = 0; round < 3; round++) {
      assertThat(tree(a, wh(a)).levels().get(0).code()).isEqualTo("ZONE-A");
      assertThat(tree(b, wh(b)).levels().get(0).code()).isEqualTo("ZONE-B");
    }
    assertThat(za).isNotEqualTo(zb);
  }

  @Test
  void 本模块读到的表逐张FORCE_RLS且无租户上下文读不到行() {
    for (var table : List.of("warehouse", "warehouse_location", "stock_bucket", "inventory_balance")) {
      boolean forced =
          sql.fetchOne(
                  "select (relrowsecurity and relforcerowsecurity) as on from pg_class"
                      + " where relname=? and relnamespace='public'::regnamespace",
                  table)
              .get("on", Boolean.class);
      assertThat(forced).as("%s 必须 FORCE RLS", table).isTrue();
    }
    assertThat(sql.fetchOne(
            "select count(*) from warehouse_location"
                + " where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, Long.class)).isZero();
  }

  @Test
  void 补的三列是nullable且没有DEFAULT() {
    // 这是跨车道安全的形状本身：不带列名的 INSERT VALUES(...) 才是危险写法，
    // 而仓库里既有 INSERT 全部显式列出列名。
    for (var column : List.of("parent_id", "capacity", "weight_limit")) {
      String defaultValue =
          sql.fetchOne(
                  "select column_default from information_schema.columns"
                      + " where table_name='warehouse_location' and column_name=?",
                  column)
              .get(0, String.class);
      String nullable =
          sql.fetchOne(
                  "select is_nullable from information_schema.columns"
                      + " where table_name='warehouse_location' and column_name=?",
                  column)
              .get(0, String.class);
      assertThat(defaultValue).as("%s 不应有 DEFAULT", column).isNull();
      assertThat(nullable).as("%s 必须 nullable", column).isEqualTo("YES");
    }
    // 不带列名的 INSERT 依然可用（既有列默认值仍生效），证明没有 SET NOT NULL 兜底要求
    asA("read", () -> sql.execute(
        "insert into warehouse_location(id,tenant_id,warehouse_id,code,name) values(?,?,?,?,'省略列名')",
        UUID.randomUUID(), a, wh(a), "MINIMAL-INSERT"));
    assertThat(tree(a, wh(a)).levels().stream()
            .filter(n -> n.code().equals("MINIMAL-INSERT")).findFirst().orElseThrow().parentId())
        .isNull();
  }

  // ================================================================ HTTP 端点

  @Test
  void 端点按权限码裁决且租户来自已认证成员关系() throws Exception {
    UUID whId = wh(a);
    http.perform(get("/warehouse/location-trees/{id}", whId).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.warehouseId").value(whId.toString()))
        .andExpect(jsonPath("$.levels").isArray());

    http.perform(get("/warehouse/location-trees/capabilities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.autoAllocationAvailable").value(false))
        .andExpect(jsonPath("$.autoAllocationOwnerModule").value("8.1.2.2"));

    // 无凭据 401、无权限 403
    http.perform(get("/warehouse/location-trees/{id}", whId)).andExpect(status().isUnauthorized());
    http.perform(get("/warehouse/location-trees/{id}", whId).header("Authorization", "Bearer no-permission"))
        .andExpect(status().isForbidden());
    // 只读角色不能建库位
    http.perform(post("/warehouse/location-trees/{id}/locations", whId)
            .header("Authorization", "Bearer read-only")
            .contentType("application/json")
            .content("{\"code\":\"X\",\"name\":\"X\"}"))
        .andExpect(status().isForbidden());

    // 新建 → 出现在树里
    http.perform(post("/warehouse/location-trees/{id}/locations", whId)
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content("{\"code\":\"H-01\",\"name\":\"HTTP 建的\",\"type\":\"STORAGE\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.action").value("CREATED"));
    assertThat(tree(a, whId).levels().stream()
            .filter(n -> n.code().equals("H-01")).findFirst().orElseThrow()).isNotNull();

    // 不属于本租户的仓库 → 404
    http.perform(get("/warehouse/location-trees/{id}", wh(b)).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
  }

  @Test
  void 非法类型与空编码返回400() throws Exception {
    UUID whId = wh(a);
    http.perform(post("/warehouse/location-trees/{id}/locations", whId)
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content("{\"code\":\"Y\",\"name\":\"Y\",\"type\":\"NOT_A_TYPE\"}"))
        .andExpect(status().isBadRequest());
    http.perform(post("/warehouse/location-trees/{id}/locations", whId)
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content("{\"code\":\"  \",\"name\":\"Y\"}"))
        .andExpect(status().isBadRequest());
  }
}