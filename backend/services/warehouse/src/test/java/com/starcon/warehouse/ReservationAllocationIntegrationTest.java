package com.starcon.warehouse;

import static com.starcon.warehouse.ReservationAllocationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 7.2.1.2 预占分配明细。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>本测试钉住七件「错了会误导人」的事：
 *
 * <ul>
 *   <li><b>分配到的是具体 bucket</b>：仓 / 货位 / 批次三维都可读可筛，货位与批次可空时有明确口径。
 *   <li><b>跨行守恒</b>：分配之和超过需求量被 <b>DB 层</b> 拒（触发器），不是靠 Java 判断 ——
 *       Java 判断可以被一条裸 SQL 绕开，而超卖不可逆。
 *   <li><b>bucket 数与分配笔数分开</b>：同一个 bucket 分两次圈只算一个 bucket。
 *   <li><b>时间线断言写回的值</b>：{@code occurredAt} 与 {@code quantity} 都按传入值逐字比对，
 *       不是「没抛异常」。用 {@code Timestamp.valueOf(LocalDateTime)} 绑定会静默偏 −28800s。
 *   <li><b>分配状态变，预占状态跟着变</b>：全承诺 → 预占 COMMITTED；覆盖需求量全消耗 → CONSUMED。
 *       不同步的话超时清理会把已承诺发货的货误释放，直接超卖。
 *   <li><b>流水 append-only</b>：UPDATE / DELETE 被 DB 拒。改得动一条历史，追溯结论就能被编出来。
 *   <li><b>租户隔离三断言</b>实跑：两租户互不可见 / 伪造 X-Tenant-Id 无效 /
 *       连接池归还后不串租户。
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReservationAllocationIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      List.of("warehouse.inventory.read", "warehouse.inventory.update");

  static final UUID leOne = idFor(a, 1);
  static final UUID skuOne = idFor(a, 3);
  static final UUID warehouseOne = idFor(a, 5);
  static final UUID locationOne = idFor(a, 6);
  static final UUID locationTwo = idFor(a, 7);
  static final UUID lotOne = idFor(a, 8);

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-reservation-allocation-" + tenant + "-" + ordinal).getBytes());
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
      try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        s.execute(
            "create role warehouse_app login password 'test-only' nosuperuser nobypassrls");
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

  @Autowired ReservationService reservations;
  @Autowired ReservationAllocationService service;
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
          "truncate reservation_entry, reservation_allocation, inventory_reservation,"
              + " inventory_lot, warehouse_location, warehouse, sku_ref, legal_entity_ref"
              + " restart identity cascade");
    }
    for (var tenant : List.of(a, b)) {
      access.run(
          actorFor(tenant),
          "warehouse.inventory.read",
          () -> {
            sql.execute(
                "insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 1), tenant, "LE-1", "主体一");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 3), tenant, "SKU-" + (tenant.equals(a) ? "A" : "B"), "商品一");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone)"
                    + " values(?,?,?,?,?,?)",
                idFor(tenant, 5), tenant, "WH-1", "主仓", "CN", "Asia/Shanghai");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name)"
                    + " values(?,?,?,?,?)",
                idFor(tenant, 6), tenant, idFor(tenant, 5), "L-01", "货位一");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name)"
                    + " values(?,?,?,?,?)",
                idFor(tenant, 7), tenant, idFor(tenant, 5), "L-02", "货位二");
            // received_on 是业务日期（date，不是字符串）：批次效期与成本分摊都按业务日算。
            sql.execute(
                "insert into inventory_lot(id,tenant_id,sku_id,lot_code,received_on)"
                    + " values(?,?,?,?,?)",
                idFor(tenant, 8), tenant, idFor(tenant, 3), "LOT-A",
                java.time.LocalDate.parse("2026-10-01"));
            return null;
          });
    }
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  <T> T asA(String permission, java.util.function.Supplier<T> work) {
    return access.run(actorA, "warehouse.inventory." + permission, work);
  }

  <T> T asB(String permission, java.util.function.Supplier<T> work) {
    return access.run(actorB, "warehouse.inventory." + permission, work);
  }

  // ================================================================ 夹具

  /** 登记一条预占需求（走 7.2.1.1 的写入端点，两个模块共用同一条链路）。 */
  UUID reserve(String qty) {
    return asA(
        "update",
        () -> {
          var created =
              reservations.create(
                  a,
                  new ReservationModels.ReservationCreateInput(
                      "TRADE", "SALES_ORDER", UUID.randomUUID(), "SO-20261013-001",
                      leOne, skuOne, new BigDecimal(qty), null, null, null));
          return created.id();
        });
  }

  /** 登记一条分配（走本模块的写入端点）。 */
  ReservationAllocationCreated allocate(
      UUID reservationId, UUID locationId, UUID lotId, String qty, OffsetDateTime at) {
    return asA(
        "update",
        () ->
            service.allocate(
                a,
                reservationId,
                new ReservationAllocationCreateInput(
                    warehouseOne, locationId, lotId, new BigDecimal(qty), at, "首批")));
  }

  static ReservationAllocationQuery query() {
    return new ReservationAllocationQuery(null, null, null, null, null, null, 0, 50);
  }

  ReservationAllocationPage detail(UUID reservationId) {
    return asA("read", () -> service.allocations(a, reservationId, query()));
  }

  ReservationAllocationPage detail(UUID reservationId, ReservationAllocationQuery q) {
    return asA("read", () -> service.allocations(a, reservationId, q));
  }

  /** 时间戳按毫秒截断后传入，断言写回值时逐字可比。 */
  static OffsetDateTime at(String iso) {
    return OffsetDateTime.parse(iso);
  }

  /**
   * 断言写回的时间值。
   *
   * <p>比的是 {@link Instant} 而不是 {@code OffsetDateTime.equals}：后者同时比瞬时与偏移量，
   * 而 jOOQ 读回的是会话时区的偏移量，与传入的偏移量不同时刻字面不等、
   * 但**表示的是同一时刻**。那样断言会失败，而实现其实是对的。
   */
  static void assertInstant(OffsetDateTime actual, OffsetDateTime expected) {
    assertThat(actual).as("时间值必须逐字写回").isNotNull();
    assertThat(actual.toInstant()).isEqualTo(expected.toInstant());
  }

  // ================================================================ 明细行

  @Test
  void 明细行展示仓货位批次与三个数量() throws Exception {
    UUID reservationId = reserve("20");
    OffsetDateTime when = at("2026-10-13T09:00:00+08:00");
    allocate(reservationId, locationOne, lotOne, "12", when);

    var row = detail(reservationId).rows().get(0);
    // bucket 三维可读：这是「分配到具体 bucket 而不是只记总量」的全部意义 ——
    // 拿不到仓 / 货位 / 批次，批次追溯与成本分摊都无从谈起。
    assertThat(row.warehouseId()).isEqualTo(warehouseOne);
    assertThat(row.warehouseCode()).isEqualTo("WH-1");
    assertThat(row.warehouseName()).isEqualTo("主仓");
    assertThat(row.locationCode()).isEqualTo("L-01");
    assertThat(row.locationName()).isEqualTo("货位一");
    assertThat(row.lotCode()).isEqualTo("LOT-A");
    assertThat(row.status()).isEqualTo("HELD");
    assertThat(row.statusLabel()).isEqualTo("预占中");
    assertThat(row.terminal()).isFalse();
    assertThat(row.occupying()).isTrue();
    assertThat(row.allocatedQuantity()).isEqualByComparingTo("12");
    assertThat(row.consumedQuantity()).isEqualByComparingTo("0");
    assertThat(row.releasedQuantity()).isEqualByComparingTo("0");
    assertThat(row.remainingQuantity()).isEqualByComparingTo("12");
    assertThat(row.transitionCount()).isEqualTo(1);
    // 时间断言的是写回的值，不是「没抛异常」。
    assertInstant(row.allocatedAt(), when);
    assertThat(row.committedAt()).isNull();
    assertThat(row.settledAt()).isNull();
  }

  @Test
  void 抬头与需求列表同源且未分配量等于需求量减已分配量() throws Exception {
    UUID reservationId = reserve("20");
    allocate(reservationId, locationOne, lotOne, "12", at("2026-10-13T09:00:00+08:00"));

    var page = detail(reservationId);
    assertThat(page.reservation().requestedQuantity()).isEqualByComparingTo("20");
    // 父表 allocated_quantity 由 V107 的触发器回写，这里断言的是**回写后的值**。
    assertThat(page.reservation().allocatedQuantity()).isEqualByComparingTo("12");
    assertThat(page.reservation().remainingQuantity()).isEqualByComparingTo("20");
    assertThat(page.summary().unallocatedQuantity()).isEqualByComparingTo("8");
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.timelineCount()).isEqualTo(1);
    assertThat(page.reservation().skuCode()).isEqualTo("SKU-A");
    assertThat(page.reservation().reservationNo()).startsWith("RSV-");
  }

  @Test
  void 没有货位与批次的分配也如实展示为null() throws Exception {
    // 虚拟仓 / 直发仓没有货位档案，非批次管理品没有批次 —— 这两种都必须是合法写入，
    // 而不是被「三维必填」拒掉。展示层给 null，界面显示「—」。
    UUID reservationId = reserve("5");
    allocate(reservationId, null, null, "5", at("2026-10-13T09:00:00+08:00"));

    var row = detail(reservationId).rows().get(0);
    assertThat(row.warehouseCode()).isEqualTo("WH-1");
    assertThat(row.locationId()).isNull();
    assertThat(row.locationCode()).isNull();
    assertThat(row.lotId()).isNull();
    assertThat(row.lotCode()).isNull();
  }

  @Test
  void 汇总不受分页与筛选影响() throws Exception {
    UUID reservationId = reserve("20");
    allocate(reservationId, locationOne, lotOne, "5", at("2026-10-13T09:00:00+08:00"));
    allocate(reservationId, locationTwo, lotOne, "7", at("2026-10-13T10:00:00+08:00"));
    asA("update", () -> service.commit(a, reservationId, allocationIdAt(reservationId, 0),
        new ReservationAllocationTransitionInput(null, at("2026-10-13T11:00:00+08:00"), null)));

    // 筛选 + 分页后：行数变了，汇总不能变。
    var filtered =
        detail(
            reservationId,
            new ReservationAllocationQuery(
                List.of("COMMITTED"), null, null, null, null, "QUANTITY", 0, 1));
    assertThat(filtered.rows()).hasSize(1);
    assertThat(filtered.total()).isEqualTo(1);
    assertThat(filtered.summary().totalCount()).isEqualTo(2);
    assertThat(filtered.summary().allocatedQuantity()).isEqualByComparingTo("12");
    assertThat(filtered.summary().counts().get("COMMITTED")).isEqualTo(1);
    assertThat(filtered.summary().counts().get("HELD")).isEqualTo(1);
    // 时间线总数也不受筛选影响。
    assertThat(filtered.timelineCount()).isEqualTo(3);
  }

  @Test
  void bucket数与分配笔数分开报() throws Exception {
    // 同一个 bucket 分两次圈：笔数是 2，bucket 数必须是 1 ——
    // 只报笔数的话「圈了几个地方」会被虚高一倍。
    UUID reservationId = reserve("20");
    allocate(reservationId, locationOne, lotOne, "5", at("2026-10-13T09:00:00+08:00"));
    allocate(reservationId, locationOne, lotOne, "3", at("2026-10-13T10:00:00+08:00"));
    allocate(reservationId, locationTwo, null, "4", at("2026-10-13T11:00:00+08:00"));

    var summary = detail(reservationId).summary();
    assertThat(summary.totalCount()).isEqualTo(3);
    assertThat(summary.bucketCount()).isEqualTo(2);
    assertThat(summary.allocatedQuantity()).isEqualByComparingTo("12");
    assertThat(summary.remainingQuantity()).isEqualByComparingTo("12");
  }

  @Test
  void 下拉项只列本预占出现过的仓货位批次并带上各自的量() throws Exception {
    UUID reservationId = reserve("20");
    allocate(reservationId, locationOne, lotOne, "5", at("2026-10-13T09:00:00+08:00"));
    allocate(reservationId, locationOne, lotOne, "3", at("2026-10-13T10:00:00+08:00"));
    allocate(reservationId, locationTwo, null, "4", at("2026-10-13T11:00:00+08:00"));

    var options = asA("read", () -> service.options(a, reservationId));
    assertThat(options.warehouses()).hasSize(1);
    assertThat(options.warehouses().get(0).code()).isEqualTo("WH-1");
    assertThat(options.warehouses().get(0).quantity()).isEqualByComparingTo("12");
    assertThat(options.locations()).hasSize(2);
    // 下拉里就能看出「这个货位上圈了 8 件」：L-01 圈了两次共 8，L-02 圈了 4。
    var l01 = options.locations().stream().filter(o -> o.code().equals("L-01")).findFirst().orElseThrow();
    assertThat(l01.quantity()).isEqualByComparingTo("8");
    assertThat(l01.allocationCount()).isEqualTo(2);
    // 批次只有一个；没有批次的分配不产生一个「无批次」的假候选值。
    assertThat(options.lots()).hasSize(1);
    assertThat(options.lots().get(0).code()).isEqualTo("LOT-A");
    // 状态五项固定全给，某态 0 条时也有键。
    assertThat(options.statuses()).hasSize(5);
    // 可触发迁移**不含**释放与过期：那属 7.2.1.3。
    assertThat(options.transitions().stream().map(ReservationAllocationOption::code))
        .containsExactly("ALLOCATE", "COMMIT", "CONSUME");
  }

  @Test
  void 登记目标覆盖全部在用仓货位而批次按预占的SKU筛() throws Exception {
    // 候选值（筛选器用）只含本预占出现过的；登记目标（表单用）必须覆盖全量在用 ——
    // 新建的预占一条分配都没有，拿候选集去填登记表就是死结。
    UUID reservationId = reserve("20");

    // 一个在用的次仓 + 一个归档仓 + 一个别的 SKU 的批次
    UUID activeWarehouse = UUID.randomUUID();
    UUID archivedWarehouse = UUID.randomUUID();
    UUID otherSku = UUID.randomUUID();
    UUID otherLot = UUID.randomUUID();
    asA(
        "update",
        () -> {
          sql.execute(
              "insert into warehouse(id,tenant_id,code,name,country_code,timezone,status)"
                  + " values(?,?,?,?,?,?,?)",
              activeWarehouse, a, "WH-2", "次仓", "CN", "Asia/Shanghai", "ACTIVE");
          sql.execute(
              "insert into warehouse(id,tenant_id,code,name,country_code,timezone,status)"
                  + " values(?,?,?,?,?,?,?)",
              archivedWarehouse, a, "WH-OLD", "归档仓", "CN", "Asia/Shanghai", "ARCHIVED");
          sql.execute(
              "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,status)"
                  + " values(?,?,?,?,?,?)",
              UUID.randomUUID(), a, activeWarehouse, "L-10", "次仓货位", "ACTIVE");
          sql.execute("insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
              otherSku, a, "SKU-OTHER", "别的商品");
          sql.execute(
              "insert into inventory_lot(id,tenant_id,sku_id,lot_code,status) values(?,?,?,?,?)",
              otherLot, a, otherSku, "LOT-OTHER", "ACTIVE");
          return null;
        });

    var targets = asA("read", () -> service.options(a, reservationId)).targets();
    // 主仓 + 次仓；归档仓不出现 —— 把它倒进选择框只会让人选到一个提交后必然被拒的组合。
    assertThat(targets.warehouses().stream().map(ReservationAllocationTarget::code))
        .containsExactlyInAnyOrder("WH-1", "WH-2");
    // 货位按仓分组（parentId 指回所属仓），前端据此按选中的仓过滤
    var secondWarehouseLocations =
        targets.locations().stream()
            .filter(l -> l.parentId().equals(activeWarehouse))
            .toList();
    assertThat(secondWarehouseLocations).hasSize(1);
    assertThat(secondWarehouseLocations.get(0).code()).isEqualTo("L-10");
    // 批次按**预占的 SKU** 过滤：别的商品的批次列出来十有八九会被选错
    assertThat(targets.lots().stream().map(ReservationAllocationTarget::code))
        .containsExactly("LOT-A");
    // 批次没有名称列，name 恒为空而不是空串
    assertThat(targets.lots().get(0).name()).isNull();
  }

  // ================================================================ 写入与守恒

  @Test
  void 分配之和超过需求量被数据库层拒绝() throws Exception {
    UUID reservationId = reserve("10");
    allocate(reservationId, locationOne, lotOne, "6", at("2026-10-13T09:00:00+08:00"));

    // 6 + 6 = 12 > 10：超卖。拒它的是 V107 的 guard 触发器而不是 Java 判断 ——
    // Java 判断能被一条裸 SQL 绕开，而超卖不可逆。
    // 每次失败单独一个事务：PG 里一条语句失败后整个事务进入 aborted 状态，
    // 同一事务里再跑任何语句都会得到「current transaction is aborted」。
    assertThatThrownBy(
            () -> allocate(reservationId, locationTwo, lotOne, "6", at("2026-10-13T10:00:00+08:00")))
        .isInstanceOf(org.springframework.dao.DataAccessException.class)
        .hasMessageContaining("超卖");

    // 拒掉之后原状不变：仍然只有 6。
    var page = detail(reservationId);
    assertThat(page.rows()).hasSize(1);
    assertThat(page.reservation().allocatedQuantity()).isEqualByComparingTo("6");
  }

  @Test
  void 超卖的写入经HTTP返回409而不是500() throws Exception {
    UUID reservationId = reserve("10");
    allocate(reservationId, locationOne, lotOne, "6", at("2026-10-13T09:00:00+08:00"));

    http.perform(
            post("/warehouse/inventory/reservations/{id}/allocations", reservationId)
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    """
                    {"warehouseId":"%s","locationId":"%s","lotId":"%s","allocatedQuantity":6}"""
                        .formatted(warehouseOne, locationTwo, lotOne)))
        .andExpect(status().isConflict());
  }

  @Test
  void 货位不属于所声明的仓被拒() throws Exception {
    UUID reservationId = reserve("10");
    // 造一个属于别的仓的货位
    UUID otherWarehouse = UUID.randomUUID();
    UUID foreignLocation = UUID.randomUUID();
    asA(
        "update",
        () -> {
          sql.execute(
              "insert into warehouse(id,tenant_id,code,name,country_code,timezone)"
                  + " values(?,?,?,?,?,?)",
              otherWarehouse, a, "WH-2", "次仓", "CN", "Asia/Shanghai");
          sql.execute(
              "insert into warehouse_location(id,tenant_id,warehouse_id,code,name)"
                  + " values(?,?,?,?,?)",
              foreignLocation, a, otherWarehouse, "L-99", "别的仓的货位");
          return null;
        });

    assertThatThrownBy(
            () ->
                allocate(
                    reservationId, foreignLocation, lotOne, "5", at("2026-10-13T09:00:00+08:00")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不属于仓库");
  }

  @Test
  void 批次的商品与预占不一致被拒() throws Exception {
    UUID reservationId = reserve("10");
    UUID otherSku = UUID.randomUUID();
    UUID otherLot = UUID.randomUUID();
    asA(
        "update",
        () -> {
          sql.execute("insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
              otherSku, a, "SKU-OTHER", "别的商品");
          sql.execute(
              "insert into inventory_lot(id,tenant_id,sku_id,lot_code) values(?,?,?,?)",
              otherLot, a, otherSku, "LOT-B");
          return null;
        });

    // 把 B 品的批次挂到 A 品的预占上：效期判断与成本分摊都会算到错的 SKU 上。
    assertThatThrownBy(
            () ->
                allocate(
                    reservationId, locationOne, otherLot, "5", at("2026-10-13T09:00:00+08:00")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不是同一个商品");
  }

  @Test
  void 分配数量非正被拒() throws Exception {
    UUID reservationId = reserve("10");
    assertThatThrownBy(
            () ->
                allocate(reservationId, locationOne, lotOne, "0", at("2026-10-13T09:00:00+08:00")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须为正");
  }

  // ================================================================ 状态迁移与时间线

  @Test
  void 时间线按发生时刻正序并逐字断言写回的值() throws Exception {
    UUID reservationId = reserve("20");
    OffsetDateTime allocatedAt = at("2026-10-13T09:00:00+08:00");
    OffsetDateTime committedAt = at("2026-10-13T10:30:00+08:00");
    OffsetDateTime consumedAt = at("2026-10-13T11:45:00+08:00");

    var created = allocate(reservationId, locationOne, lotOne, "12", allocatedAt);
    asA(
        "update",
        () ->
            service.commit(
                a, reservationId, created.allocationId(),
                new ReservationAllocationTransitionInput(null, committedAt, "运营确认")));
    asA(
        "update",
        () ->
            service.consume(
                a, reservationId, created.allocationId(),
                new ReservationAllocationTransitionInput(new BigDecimal("12"), consumedAt, "整单出库")));

    var timeline =
        asA(
            "read",
            () -> service.timeline(a, reservationId, new ReservationAllocationTimelineQuery(null, null, 0, 50)));
    assertThat(timeline.total()).isEqualTo(3);
    assertThat(timeline.entries().stream().map(ReservationAllocationEntryRow::transition))
        .containsExactly("ALLOCATED", "COMMITTED", "CONSUMED");

    var allocated = timeline.entries().get(0);
    assertThat(allocated.level()).isEqualTo("ALLOCATION");
    // 建行那条的 from 是 null 而不是 HELD：「此前无状态」与「此前就是 HELD」在审计上不是一回事。
    assertThat(allocated.fromStatus()).isNull();
    assertThat(allocated.toStatus()).isEqualTo("HELD");
    assertThat(allocated.toStatusLabel()).isEqualTo("预占中");
    assertThat(allocated.quantity()).isEqualByComparingTo("12");
    assertInstant(allocated.occurredAt(), allocatedAt);

    var committed = timeline.entries().get(1);
    assertThat(committed.fromStatus()).isEqualTo("HELD");
    assertThat(committed.toStatus()).isEqualTo("COMMITTED");
    assertThat(committed.quantity()).isEqualByComparingTo("12");
    assertInstant(committed.occurredAt(), committedAt);
    assertThat(committed.reason()).isEqualTo("运营确认");
    // 分配级分录带上 bucket 快照，时间线不必再跳回明细页才知道是哪一格货。
    assertThat(committed.warehouseCode()).isEqualTo("WH-1");
    assertThat(committed.locationCode()).isEqualTo("L-01");
    assertThat(committed.lotCode()).isEqualTo("LOT-A");

    var consumed = timeline.entries().get(2);
    assertThat(consumed.fromStatus()).isEqualTo("COMMITTED");
    assertThat(consumed.toStatus()).isEqualTo("CONSUMED");
    assertThat(consumed.quantity()).isEqualByComparingTo("12");
    assertInstant(consumed.occurredAt(), consumedAt);
  }

  @Test
  void 时间线可按分配与迁移类型筛选() throws Exception {
    UUID reservationId = reserve("20");
    var first = allocate(reservationId, locationOne, lotOne, "5", at("2026-10-13T09:00:00+08:00"));
    allocate(reservationId, locationTwo, lotOne, "7", at("2026-10-13T10:00:00+08:00"));

    var onlyFirst =
        asA(
            "read",
            () ->
                service.timeline(
                    a, reservationId, new ReservationAllocationTimelineQuery(first.allocationId(), null, 0, 50)));
    assertThat(onlyFirst.entries()).hasSize(1);
    assertThat(onlyFirst.entries().get(0).allocationId()).isEqualTo(first.allocationId());

    // 词表外的迁移类型必须被拒，而不是静默返回空集。
    assertThatThrownBy(
            () ->
                asA(
                    "read",
                    () ->
                        service.timeline(
                            a, reservationId,
                            new ReservationAllocationTimelineQuery(null, "WHATEVER", 0, 50))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的迁移类型");
  }

  @Test
  void 流水appendOnly改与删都被拒() throws Exception {
    UUID reservationId = reserve("10");
    var created = allocate(reservationId, locationOne, lotOne, "5", at("2026-10-13T09:00:00+08:00"));
    UUID entryId = created.entryId();

    assertThatThrownBy(
            () ->
                asA(
                    "update",
                    () -> {
                      sql.execute(
                          "update reservation_entry set quantity=999 where id=?", entryId);
                      return null;
                    }))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () ->
                asA(
                    "update",
                    () -> {
                      sql.execute("delete from reservation_entry where id=?", entryId);
                      return null;
                    }))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);

    // 值没被动过。改得动一条历史，追溯结论就能被编出来。
    var timeline =
        asA(
            "read",
            () -> service.timeline(a, reservationId, new ReservationAllocationTimelineQuery(null, null, 0, 50)));
    assertThat(timeline.entries().get(0).quantity()).isEqualByComparingTo("5");
  }

  @Test
  void 分录不得与事实脱节() throws Exception {
    UUID reservationId = reserve("10");
    var created = allocate(reservationId, locationOne, lotOne, "5", at("2026-10-13T09:00:00+08:00"));

    // 凭空追加一条「它曾经变成 COMMITTED」的假流水：分配此刻仍是 HELD，被守卫拒。
    assertThatThrownBy(
            () ->
                asA(
                    "update",
                    () -> {
                      sql.execute(
                          "insert into reservation_entry(id,tenant_id,reservation_id,allocation_id,"
                              + "transition,from_status,to_status,quantity,occurred_at)"
                              + " values(?,?,?,?,?,?,?,?,now())",
                          UUID.randomUUID(), a, reservationId, created.allocationId(),
                          "COMMITTED", "HELD", "COMMITTED", new BigDecimal("5"));
                      return null;
                    }))
        .isInstanceOf(org.springframework.dao.DataAccessException.class)
        .hasMessageContaining("流水不得与事实脱节");
  }

  @Test
  void 非法迁移矩阵被拒() throws Exception {
    UUID reservationId = reserve("10");
    var created = allocate(reservationId, locationOne, lotOne, "5", at("2026-10-13T09:00:00+08:00"));

    // HELD 不能直接跳到 CONSUMED：没承诺就出库等于货没圈住就走了，那正是超卖的定义。
    assertThatThrownBy(
            () ->
                asA(
                    "update",
                    () -> service.consume(
                        a, reservationId, created.allocationId(), null)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已承诺");
  }

  @Test
  void 终态不可复活且bucket不可改指() throws Exception {
    UUID reservationId = reserve("10");
    var created = allocate(reservationId, locationOne, lotOne, "5", at("2026-10-13T09:00:00+08:00"));
    asA(
        "update",
        () -> service.commit(a, reservationId, created.allocationId(), null));
    asA(
        "update",
        () ->
            service.consume(
                a, reservationId, created.allocationId(), null));

    // 已终结的分配不能被改回去
    assertThatThrownBy(
            () ->
                asA(
                    "update",
                    () -> {
                      sql.execute(
                          "update reservation_allocation set status='HELD' where id=?",
                          created.allocationId());
                      return null;
                    }))
        .isInstanceOf(org.springframework.dao.DataAccessException.class)
        .hasMessageContaining("不能迁回");

    // bucket 不可改指：改掉等于把「当初圈在哪」这段审计痕迹改写掉。
    // 用一条**还在 HELD** 的分配来单独验证这条守卫 —— 在已终结的行上改指，
    // 会被「终态不可再改」先挡掉，看到的就不是这条守卫了。
    ReservationAllocationCreated open = allocate(reservationId, locationTwo, lotOne, "1", at("2026-10-13T12:00:00+08:00"));
    assertThatThrownBy(
            () ->
                asA(
                    "update",
                    () -> {
                      sql.execute(
                          "update reservation_allocation set location_id=? where id=?",
                          locationOne, open.allocationId());
                      return null;
                    }))
        .isInstanceOf(org.springframework.dao.DataAccessException.class)
        .hasMessageContaining("不可改指");

    // 改指被拒之后，那一行仍然指着它当初圈的那个货位
    assertThat(detail(reservationId).rows().stream()
            .filter(r -> r.id().equals(open.allocationId()))
            .findFirst()
            .orElseThrow()
            .locationCode())
        .isEqualTo("L-02");
  }

  // ================================================================ 预占状态联动

  @Test
  void 分配全部承诺时预占同步进入已承诺() throws Exception {
    UUID reservationId = reserve("20");
    var first = allocate(reservationId, locationOne, lotOne, "8", at("2026-10-13T09:00:00+08:00"));
    var second = allocate(reservationId, locationTwo, lotOne, "4", at("2026-10-13T10:00:00+08:00"));

    // 部分承诺：预占仍留 HELD —— 它确实还有没承诺的部分，继续参与超时是对的。
    asA("update", () -> service.commit(a, reservationId, first.allocationId(), null));
    assertThat(detail(reservationId).reservation().status()).isEqualTo("HELD");

    // 全部承诺：预占同步进 COMMITTED。
    // 不同步的话超时清理会把已承诺发货的货误释放 —— 蓝图 7.2.1.3 点名的超卖场景。
    asA("update", () -> service.commit(a, reservationId, second.allocationId(), null));
    var header = detail(reservationId).reservation();
    assertThat(header.status()).isEqualTo("COMMITTED");
    assertThat(header.statusLabel()).isEqualTo("已承诺");
    assertThat(header.committedAt()).isNotNull();
    assertThat(header.terminal()).isFalse();
  }

  @Test
  void 全部消耗且覆盖需求量时预占进入已消耗并对齐已消费量() throws Exception {
    UUID reservationId = reserve("10");
    var created = allocate(reservationId, locationOne, lotOne, "10", at("2026-10-13T09:00:00+08:00"));
    asA("update", () -> service.commit(a, reservationId, created.allocationId(), null));
    asA("update", () -> service.consume(a, reservationId, created.allocationId(), null));

    var header = detail(reservationId).reservation();
    assertThat(header.status()).isEqualTo("CONSUMED");
    assertThat(header.terminal()).isTrue();
    assertThat(header.consumedQuantity()).isEqualByComparingTo("10");
    assertThat(header.remainingQuantity()).isEqualByComparingTo("0");
    assertThat(header.settledAt()).isNotNull();
    var row = detail(reservationId).rows().get(0);
    assertThat(row.status()).isEqualTo("CONSUMED");
    assertThat(row.consumedQuantity()).isEqualByComparingTo("10");
    assertThat(row.remainingQuantity()).isEqualByComparingTo("0");
  }

  @Test
  void 部分消耗时预占不终结但已消费量对齐() throws Exception {
    UUID reservationId = reserve("10");
    var created = allocate(reservationId, locationOne, lotOne, "10", at("2026-10-13T09:00:00+08:00"));
    asA("update", () -> service.commit(a, reservationId, created.allocationId(), null));
    asA(
        "update",
        () ->
            service.consume(
                a, reservationId, created.allocationId(),
                new ReservationAllocationTransitionInput(new BigDecimal("4"), null, "先出 4 件")));

    var header = detail(reservationId).reservation();
    // 没覆盖需求量就不该进终态 —— 否则「还有 6 件没出」会被显示成「已结清」。
    assertThat(header.status()).isEqualTo("COMMITTED");
    assertThat(header.consumedQuantity()).isEqualByComparingTo("4");
    assertThat(header.remainingQuantity()).isEqualByComparingTo("6");
  }

  @Test
  void 消耗数量超过该分配剩余量被拒() throws Exception {
    UUID reservationId = reserve("10");
    var created = allocate(reservationId, locationOne, lotOne, "6", at("2026-10-13T09:00:00+08:00"));
    asA("update", () -> service.commit(a, reservationId, created.allocationId(), null));

    assertThatThrownBy(
            () ->
                asA(
                    "update",
                    () ->
                        service.consume(
                            a, reservationId, created.allocationId(),
                            new ReservationAllocationTransitionInput(new BigDecimal("7"), null, null))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("超过该分配的剩余量");
  }

  @Test
  void 已终结的预占不能再分配() throws Exception {
    UUID reservationId = reserve("10");
    var created = allocate(reservationId, locationOne, lotOne, "10", at("2026-10-13T09:00:00+08:00"));
    asA("update", () -> service.commit(a, reservationId, created.allocationId(), null));
    asA("update", () -> service.consume(a, reservationId, created.allocationId(), null));

    assertThatThrownBy(
            () -> allocate(reservationId, locationTwo, lotOne, "1", null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已终结");
  }

  // ================================================================ 筛选、排序与空态

  @Test
  void 按状态与bucket筛选并支持关键字() throws Exception {
    UUID reservationId = reserve("20");
    var first = allocate(reservationId, locationOne, lotOne, "5", at("2026-10-13T09:00:00+08:00"));
    allocate(reservationId, locationTwo, lotOne, "7", at("2026-10-13T10:00:00+08:00"));
    asA("update", () -> service.commit(a, reservationId, first.allocationId(), null));

    assertThat(detail(reservationId, query()).rows()).hasSize(2);
    assertThat(
            detail(reservationId, byStatus("COMMITTED")).rows().stream()
                .map(ReservationAllocationRow::id))
        .containsExactly(first.allocationId());
    assertThat(detail(reservationId, byWarehouse(warehouseOne)).rows()).hasSize(2);
    assertThat(detail(reservationId, byLocation(locationTwo)).rows()).hasSize(1);
    assertThat(detail(reservationId, byLot(lotOne)).rows()).hasSize(2);

    // 关键字命中仓编码 / 货位编码 / 批次号三处可见文本
    assertThat(detail(reservationId, byKeyword("L-02")).rows()).hasSize(1);
    assertThat(detail(reservationId, byKeyword("LOT-A")).rows()).hasSize(2);
    assertThat(detail(reservationId, byKeyword("主仓")).rows()).hasSize(2);

    // 词表外的状态与排序键必须在绑定之前被拒
    assertThatThrownBy(() -> detail(reservationId, byStatus("HOLD")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的分配状态");
    assertThatThrownBy(
            () ->
                detail(
                    reservationId,
                    new ReservationAllocationQuery(null, null, null, null, null, "WHATEVER", 0, 50)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的排序键");
  }

  @Test
  void 按bucket排序按可读编码而不是uuid() throws Exception {
    UUID reservationId = reserve("20");
    allocate(reservationId, locationTwo, null, "5", at("2026-10-13T09:00:00+08:00"));
    allocate(reservationId, locationOne, null, "5", at("2026-10-13T10:00:00+08:00"));

    var rows =
        detail(
                reservationId,
                new ReservationAllocationQuery(null, null, null, null, null, "BUCKET", 0, 50))
            .rows();
    // L-01 在 L-02 之前 —— 按 uuid 排的话这个顺序毫无意义。
    assertThat(rows.get(0).locationCode()).isEqualTo("L-01");
    assertThat(rows.get(1).locationCode()).isEqualTo("L-02");
  }

  @Test
  void 空预占返回空明细与空时间线而不是报错() throws Exception {
    UUID reservationId = reserve("10");
    var page = detail(reservationId);
    assertThat(page.rows()).isEmpty();
    assertThat(page.total()).isZero();
    assertThat(page.timelineCount()).isZero();
    assertThat(page.summary().totalCount()).isZero();
    assertThat(page.summary().bucketCount()).isZero();
    assertThat(page.summary().allocatedQuantity()).isEqualByComparingTo("0");
    // 未分配量 = 需求量 − 已分配量 = 需求量
    assertThat(page.summary().unallocatedQuantity()).isEqualByComparingTo("10");

    var timeline =
        asA(
            "read",
            () -> service.timeline(a, reservationId, new ReservationAllocationTimelineQuery(null, null, 0, 50)));
    assertThat(timeline.entries()).isEmpty();
    assertThat(timeline.total()).isZero();
  }

  @Test
  void 不存在的预占返回404而不是空壳数据() throws Exception {
    UUID missing = UUID.randomUUID();
    assertThatThrownBy(() -> detail(missing))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或无权查看");
  }

  @Test
  void 能力声明在释放交付后翻成已接入并指明入口() throws Exception {
    var capabilities = asA("read", () -> service.capabilities());
    var release =
        capabilities.stream()
            .filter(c -> c.dimension().equals("ALLOCATION_RELEASE"))
            .findFirst()
            .orElseThrow();
    // 7.2.1.3 交付后由那条车道回填（见 ReservationReleaseService.capabilities）：
    // 继续报 false 会让前端禁用一个已经能用的入口。
    assertThat(release.available()).isTrue();
    assertThat(release.ownerModule()).isEqualTo("7.2.1.3");
    // 声明必须指到真实入口，否则「已接入」只是一句话
    assertThat(release.note()).contains("/reservations/{id}/release");
    assertThat(release.note()).contains("expire-overdue");
    // 本模块自己的两个维度仍然是已接入
    for (var dimension : List.of("ALLOCATION_DETAIL", "ALLOCATION_REGISTER", "ALLOCATION_COMMIT",
        "ALLOCATION_CONSUME")) {
      var one =
          capabilities.stream()
              .filter(c -> c.dimension().equals(dimension))
              .findFirst()
              .orElseThrow();
      assertThat(one.available()).as(dimension).isTrue();
    }
  }

  @Test
  void 只读角色不能登记分配() throws Exception {
    UUID reservationId = reserve("10");
    http.perform(
            post("/warehouse/inventory/reservations/{id}/allocations", reservationId)
                .header("Authorization", "Bearer read-only")
                .contentType("application/json")
                .content(
                    """
                    {"warehouseId":"%s","locationId":"%s","lotId":"%s","allocatedQuantity":5}"""
                        .formatted(warehouseOne, locationOne, lotOne)))
        .andExpect(status().isForbidden());
  }

  @Test
  void 明细端点经HTTP返回分配行与时间线() throws Exception {
    UUID reservationId = reserve("20");
    var created = allocate(reservationId, locationOne, lotOne, "12", at("2026-10-13T09:00:00+08:00"));

    http.perform(
            get("/warehouse/inventory/reservations/{id}/allocations", reservationId)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[0].warehouseCode").value("WH-1"))
        .andExpect(jsonPath("$.rows[0].locationCode").value("L-01"))
        .andExpect(jsonPath("$.rows[0].lotCode").value("LOT-A"))
        .andExpect(jsonPath("$.rows[0].allocatedQuantity").value(12))
        .andExpect(jsonPath("$.rows[0].status").value("HELD"))
        .andExpect(jsonPath("$.summary.unallocatedQuantity").value(8))
        .andExpect(jsonPath("$.timelineCount").value(1));

    http.perform(
            get("/warehouse/inventory/reservations/{id}/timeline", reservationId)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entries[0].transition").value("ALLOCATED"))
        .andExpect(jsonPath("$.entries[0].fromStatus").doesNotExist())
        .andExpect(jsonPath("$.entries[0].toStatus").value("HELD"))
        .andExpect(jsonPath("$.total").value(1));

    http.perform(
            get("/warehouse/inventory/reservations/{id}/allocation-options", reservationId)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.statuses.length()").value(5))
        .andExpect(jsonPath("$.transitions.length()").value(3))
        .andExpect(jsonPath("$.lots[0].code").value("LOT-A"));

    http.perform(
            get("/warehouse/inventory/reservations/{id}/capabilities", reservationId)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].dimension").value("ALLOCATION_DETAIL"))
        .andExpect(jsonPath("$[4].ownerModule").value("7.2.1.3"));

    http.perform(
            post("/warehouse/inventory/reservations/{r}/allocations/{a}/commit", reservationId, created.allocationId())
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.toStatus").value("COMMITTED"))
        .andExpect(jsonPath("$.reservationStatus").value("COMMITTED"));
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() throws Exception {
    UUID mine = reserve("999");
    var mineAllocation = allocate(mine, locationOne, lotOne, "12", at("2026-10-13T09:00:00+08:00"));

    // B 登记一条自己的预占并分配
    UUID theirs =
        asB(
            "update",
            () -> {
              var created =
                  reservations.create(
                      b,
                      new ReservationModels.ReservationCreateInput(
                          "TRADE", "SALES_ORDER", UUID.randomUUID(), "SO-B",
                          idFor(b, 1), idFor(b, 3), new BigDecimal("1"), null, null, null));
              return created.id();
            });
    ReservationAllocationCreated theirsAllocation =
        asB(
            "update",
            () ->
                service.allocate(
                    b, theirs,
                    new ReservationAllocationCreateInput(
                        idFor(b, 5), idFor(b, 6), idFor(b, 8), new BigDecimal("1"), null, null)));

    var minePage = detail(mine);
    assertThat(minePage.rows()).hasSize(1);
    assertThat(minePage.rows().get(0).id()).isEqualTo(mineAllocation.allocationId());
    assertThat(minePage.summary().allocatedQuantity()).isEqualByComparingTo("12");

    var theirPage = asB("read", () -> service.allocations(b, theirs, query()));
    assertThat(theirPage.rows().stream().map(ReservationAllocationRow::id))
        .containsExactly(theirsAllocation.allocationId())
        .doesNotContain(mineAllocation.allocationId());
    assertThat(theirPage.reservation().skuCode()).isEqualTo("SKU-B");
    assertThat(theirPage.timelineCount()).isEqualTo(1);

    // B 拿着 A 的预占 id 读明细 → 404。给 403 等于确认「这条存在、只是不归你管」，
    // 那是信息泄露：猜 id 就能试出别人的预占是否存在。
    assertThatThrownBy(() -> asB("read", () -> service.allocations(b, mine, query())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或无权查看");

    // 时间线同样隔离
    assertThatThrownBy(
            () ->
                asB(
                    "read",
                    () ->
                        service.timeline(
                            b, mine, new ReservationAllocationTimelineQuery(null, null, 0, 50))))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    UUID mine = reserve("999");
    allocate(mine, locationOne, lotOne, "12", at("2026-10-13T09:00:00+08:00"));

    // 身份是 B，伪造 X-Tenant-Id 指向 A：拿 A 的预占 id 也只会得到 404。
    http.perform(
            get("/warehouse/inventory/reservations/{id}/allocations", mine)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());

    http.perform(
            get("/warehouse/inventory/reservations/{id}/timeline", mine)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());

    // 伪造头也不能让 B 把一行分配挂到 A 的预占上。
    //
    // 注意这里**期望 404**：预占在 B 的上下文里不存在，
    // 所以请求在服务层就被拒，根本走不到插入那一步。
    http.perform(
            post("/warehouse/inventory/reservations/{id}/allocations", mine)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString())
                .contentType("application/json")
                .content(
                    """
                    {"warehouseId":"%s","locationId":"%s","lotId":"%s","allocatedQuantity":5}"""
                        .formatted(warehouseOne, locationOne, lotOne)))
        .andExpect(status().isNotFound());

    // A 的数据未被这串伪造请求改动
    var afterA = detail(mine);
    assertThat(afterA.rows()).hasSize(1);
    assertThat(afterA.summary().allocatedQuantity()).isEqualByComparingTo("12");
    assertThat(afterA.timelineCount()).isEqualTo(1);
  }

  @Test
  void 断言三_连接池归还后不串租户() throws Exception {
    UUID mine = reserve("999");
    var mineAllocation = allocate(mine, locationOne, lotOne, "12", at("2026-10-13T09:00:00+08:00"));
    UUID theirs =
        asB(
            "update",
            () -> {
              var created =
                  reservations.create(
                      b,
                      new ReservationModels.ReservationCreateInput(
                          "TRADE", "SALES_ORDER", UUID.randomUUID(), "SO-B",
                          idFor(b, 1), idFor(b, 3), new BigDecimal("1"), null, null, null));
              return created.id();
            });
    ReservationAllocationCreated theirsAllocation =
        asB(
            "update",
            () ->
                service.allocate(
                    b, theirs,
                    new ReservationAllocationCreateInput(
                        idFor(b, 5), idFor(b, 6), idFor(b, 8), new BigDecimal("1"), null, null)));

    // 交替复用同一条物理连接（池容量=1）：A → B → A → B × 3
    for (int i = 0; i < 3; i++) {
      var aPage = detail(mine);
      assertThat(aPage.rows().stream().map(ReservationAllocationRow::id))
          .containsExactly(mineAllocation.allocationId());
      assertThat(aPage.summary().allocatedQuantity()).isEqualByComparingTo("12");
      assertThat(aPage.timelineCount()).isEqualTo(1);

      var bPage = asB("read", () -> service.allocations(b, theirs, query()));
      assertThat(bPage.rows().stream().map(ReservationAllocationRow::id))
          .containsExactly(theirsAllocation.allocationId());
      assertThat(bPage.summary().allocatedQuantity()).isEqualByComparingTo("1");

      // B 的时间线上绝不能出现 A 的分配 id
      var bTimeline =
          asB(
              "read",
              () ->
                  service.timeline(
                      b, theirs, new ReservationAllocationTimelineQuery(null, null, 0, 50)));
      assertThat(bTimeline.entries().stream().map(ReservationAllocationEntryRow::allocationId))
          .doesNotContain(mineAllocation.allocationId());
    }

    // FORCE RLS 逐张确认
    for (var table : List.of("reservation_allocation", "reservation_entry")) {
      boolean forced =
          sql.fetchOne(
                  "select (relrowsecurity and relforcerowsecurity) as on from pg_class"
                      + " where relname=? and relnamespace='public'::regnamespace",
                  table)
              .get("on", Boolean.class);
      assertThat(forced).as(table + " 必须 FORCE RLS").isTrue();
    }

    // 不设 app.current_tenant 时读不到任何行：第一道防线在服务层谓词之前就生效。
    assertThat(
            sql.fetchOne("select count(*) from reservation_allocation").get(0, Long.class))
        .isZero();
    assertThat(sql.fetchOne("select count(*) from reservation_entry").get(0, Long.class)).isZero();
  }

  // ================================================================ 小工具

  /** 第 n 条分配（按圈定时间正序），让断言不依赖列表排序实现。 */
  UUID allocationIdAt(UUID reservationId, int index) {
    return asA(
        "read",
        () -> {
          var ids =
              sql.fetch(
                      "select id from reservation_allocation where tenant_id ="
                          + " current_setting('app.current_tenant', true)::uuid"
                          + " and reservation_id=? order by allocated_at, id",
                      reservationId)
                  .map(r -> r.get(0, UUID.class));
          assertThat(ids.size()).as("第 " + index + " 条分配应当存在").isGreaterThan(index);
          return ids.get(index);
        });
  }

  static ReservationAllocationQuery byStatus(String... statuses) {
    return new ReservationAllocationQuery(List.of(statuses), null, null, null, null, null, 0, 50);
  }

  static ReservationAllocationQuery byWarehouse(UUID warehouseId) {
    return new ReservationAllocationQuery(null, warehouseId, null, null, null, null, 0, 50);
  }

  static ReservationAllocationQuery byLocation(UUID locationId) {
    return new ReservationAllocationQuery(null, null, locationId, null, null, null, 0, 50);
  }

  static ReservationAllocationQuery byLot(UUID lotId) {
    return new ReservationAllocationQuery(null, null, null, lotId, null, null, 0, 50);
  }

  static ReservationAllocationQuery byKeyword(String keyword) {
    return new ReservationAllocationQuery(null, null, null, null, keyword, null, 0, 50);
  }

  /** 确认本测试文件用到的状态码确实是我们以为的那个。 */
  @Test
  void 断言使用的HTTP状态码来自Spring定义() throws Exception {
    assertThat(HttpStatus.CONFLICT.value()).isEqualTo(409);
    assertThat(HttpStatus.NOT_FOUND.value()).isEqualTo(404);
    // 时间断言用的 Instant 比较不是摆设：同一 Instant 不同偏移量应当相等
    assertThat(Instant.parse("2026-10-13T01:00:00Z"))
        .isEqualTo(OffsetDateTime.parse("2026-10-13T09:00:00+08:00").toInstant());
  }
}
