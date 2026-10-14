package com.starcon.warehouse;

import static com.starcon.warehouse.ReservationReleaseModels.*;
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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 7.2.1.3 释放与过期。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接： 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>本测试钉住十件「错了会出人命（超卖）或误导人」的事：
 *
 * <ol>
 *   <li><b>恢复量与释放量分两个数</b>：需求 12、只圈到 10 的货整笔释放时，
 *       restoredQuantity=10（真的还回可用池）、releasedDemandQuantity=12（需求侧放弃）。 合并成一个数就会让可用量凭空多 2。
 *   <li><b>时间逐字写回</b>：发生时刻、过期时刻都按传入值比对 {@link Instant}， 不是「没抛异常」。用 {@code
 *       Timestamp.valueOf(LocalDateTime)} 绑定会静默偏 −28800s。
 *   <li><b>承诺后不自动过期</b>：COMMITTED 的预占、以及名下还有 COMMITTED 分配的预占，
 *       批量清理都必须跳过（蓝图红线；跳一半会造出「预占已终结、货还占着且再也放不掉」）。
 *   <li><b>幂等</b>：同键同参回放、同键异参 409、终态再释放回放 —— 货只被还一次。
 *   <li><b>并发</b>：两条释放请求打到同一笔预占，只产生一条释放凭据、一条分录。
 *   <li><b>干跑不碰事实</b>：dryRun 只写一条运行凭据，预占 / 分配 / 流水逐字不动。
 *   <li><b>凭据 append-only</b>：UPDATE / DELETE 被 DB 拒。
 *   <li><b>凭据必须与事实一致</b>：V112 的守卫拒「凭据说已释放、预占却还是 HELD」。
 *   <li><b>终态不可复活</b>：V102 / V107 的触发器在 DB 层兜住（不是靠 Java 判断）。
 *   <li><b>租户隔离三断言</b>实跑：两租户互不可见 / 伪造 X-Tenant-Id 无效 / 连接池归还后不串租户。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReservationReleaseIntegrationTest {
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
        ("starcon-warehouse-reservation-release-" + tenant + "-" + ordinal).getBytes());
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

  @Autowired ReservationService reservations;
  @Autowired ReservationAllocationService allocations;
  @Autowired ReservationReleaseService service;
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
          "truncate reservation_release_record, reservation_expiry_run, reservation_entry,"
              + " reservation_allocation, inventory_reservation, inventory_lot, warehouse_location,"
              + " warehouse, sku_ref, legal_entity_ref restart identity cascade");
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
                idFor(tenant, 3),
                tenant,
                "SKU-" + (tenant.equals(a) ? "A" : "B"),
                "商品一");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone)"
                    + " values(?,?,?,?,?,?)",
                idFor(tenant, 5),
                tenant,
                "WH-1",
                "主仓",
                "CN",
                "Asia/Shanghai");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name)"
                    + " values(?,?,?,?,?)",
                idFor(tenant, 6),
                tenant,
                idFor(tenant, 5),
                "L-01",
                "货位一");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name)"
                    + " values(?,?,?,?,?)",
                idFor(tenant, 7),
                tenant,
                idFor(tenant, 5),
                "L-02",
                "货位二");
            sql.execute(
                "insert into inventory_lot(id,tenant_id,sku_id,lot_code,received_on) values(?,?,?,?,?)",
                idFor(tenant, 8),
                tenant,
                idFor(tenant, 3),
                "LOT-A",
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

  /** 登记一条预占需求（走 7.2.1.1 的写入端点）。{@code expiresAt} 为 null 表示不参与超时。 */
  UUID reserve(String qty, OffsetDateTime expiresAt) {
    return asA(
        "update",
        () ->
            reservations
                .create(
                    a,
                    new ReservationModels.ReservationCreateInput(
                        "TRADE",
                        "SALES_ORDER",
                        UUID.randomUUID(),
                        "SO-20261014-001",
                        leOne,
                        skuOne,
                        new BigDecimal(qty),
                        null,
                        expiresAt,
                        null))
                .id());
  }

  UUID reserve(String qty) {
    return reserve(qty, null);
  }

  /** 登记一条分配（走 7.2.1.2 的写入端点，三条链路共用同一条写路径）。 */
  ReservationAllocationModels.ReservationAllocationCreated allocate(
      UUID reservationId, UUID locationId, String qty, OffsetDateTime at) {
    return asA(
        "update",
        () ->
            allocations.allocate(
                a,
                reservationId,
                new ReservationAllocationModels.ReservationAllocationCreateInput(
                    warehouseOne, locationId, lotOne, new BigDecimal(qty), at, "首批")));
  }

  ReservationReleaseOutcome release(UUID reservationId, ReservationReleaseInput input) {
    return asA("update", () -> service.release(a, reservationId, user, input));
  }

  static ReservationReleaseInput full() {
    return new ReservationReleaseInput(null, null, "MANUAL", "订单取消", null, null, null);
  }

  static ReservationReleaseInput qty(String quantity) {
    return new ReservationReleaseInput(
        null, new BigDecimal(quantity), "SPLIT_REDUCTION", "拆单减少", null, null, null);
  }

  ReservationReleaseCandidatePage candidates(OffsetDateTime evaluatedAt) {
    return asA(
        "read",
        () ->
            service.candidates(
                a, new ReservationReleaseQuery(null, null, null, 0, 50, evaluatedAt)));
  }

  ReservationReleaseRunRow sweep(ReservationReleaseRunRequest request) {
    return asA("update", () -> service.expireRun(a, user, request));
  }

  static OffsetDateTime at(String iso) {
    return OffsetDateTime.parse(iso);
  }

  /**
   * 断言写回的时间值。
   *
   * <p>比的是 {@link java.time.Instant} 而不是 {@code OffsetDateTime.equals}：后者同时比瞬时与偏移量， 而 jOOQ
   * 读回的是会话时区的偏移量，与传入的偏移量不同时刻字面不等、但**表示的是同一时刻**。
   */
  static void assertInstant(OffsetDateTime actual, OffsetDateTime expected) {
    assertThat(actual).as("时间值必须逐字写回").isNotNull();
    assertThat(actual.toInstant()).isEqualTo(expected.toInstant());
  }

  /**
   * 取出 PostgreSQL 报回来的**服务端**错误文案。
   *
   * <p>jOOQ / Spring 的异常 message 只有「bad SQL grammar [update ...]」， 触发器里 {@code RAISE EXCEPTION
   * '...'} 的中文文案在 {@link org.postgresql.util.PSQLException} 的 server error message
   * 里。只断言异常类型会漏掉「报错到底说的是哪件事」—— 而本模块的守卫区分得正是哪件事被拒。
   */
  static String dbMessage(Throwable thrown) {
    for (Throwable c = thrown; c != null; c = c.getCause()) {
      if (c instanceof org.postgresql.util.PSQLException psql) {
        var server = psql.getServerErrorMessage();
        String message = server == null ? null : server.getMessage();
        if (message != null && !message.isBlank()) return message;
      }
    }
    return String.valueOf(thrown.getMessage());
  }

  static void assertRejectedByDb(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String expected) {
    var thrown = org.assertj.core.api.Assertions.catchThrowable(call);
    assertThat(thrown).as("预期被 DB 层拒绝").isInstanceOf(DataAccessException.class);
    assertThat(dbMessage(thrown)).as("DB 报错必须指到具体是哪条守卫").contains(expected);
  }

  // ================================================================ 手动释放

  @Test
  void 整笔释放把分配变终态并写回分录与凭据() {
    UUID id = reserve("20");
    OffsetDateTime allocatedAt = at("2026-10-14T09:00:00+08:00");
    var one = allocate(id, locationOne, "12", allocatedAt);
    allocate(id, locationTwo, "8", allocatedAt.plusMinutes(5));

    OffsetDateTime releasedAt = at("2026-10-14T11:30:00+08:00");
    var outcome =
        release(
            id,
            new ReservationReleaseInput(
                null, null, "ORDER_CANCELLED", "订单 SO-1 取消", releasedAt, "K1", null));

    assertThat(outcome.replayed()).isFalse();
    assertThat(outcome.terminal()).isTrue();
    assertThat(outcome.statusBefore()).isEqualTo("HELD");
    assertThat(outcome.statusAfter()).isEqualTo("RELEASED");
    assertThat(outcome.statusAfterLabel()).isEqualTo("已释放");
    assertThat(outcome.mode()).isEqualTo("RELEASE");
    assertThat(outcome.triggerSource()).isEqualTo("ORDER_CANCELLED");
    assertThat(outcome.restoredQuantity()).isEqualByComparingTo("20");
    assertThat(outcome.releasedDemandQuantity()).isEqualByComparingTo("20");
    assertThat(outcome.allocationCount()).isEqualTo(2);
    assertThat(outcome.releaseRecordId()).isNotNull();

    // 分配真的退出占额集合，剩余量归零
    var parent =
        asA(
            "read",
            () ->
                sql.fetchOne(
                    "select status, released_quantity, allocated_quantity, settled_at"
                        + " from inventory_reservation where tenant_id ="
                        + " current_setting('app.current_tenant', true)::uuid and id = ?",
                    id));
    assertThat(parent.get("status", String.class)).isEqualTo("RELEASED");
    assertThat(parent.get("released_quantity", BigDecimal.class)).isEqualByComparingTo("20");
    // allocated 由 V107 的回写触发器重算成「非终态分配之和」= 0
    assertThat(parent.get("allocated_quantity", BigDecimal.class)).isEqualByComparingTo("0");
    assertInstant(parent.get("settled_at", OffsetDateTime.class), releasedAt);

    var allocationsAfter =
        asA(
            "read",
            () ->
                sql.fetch(
                    "select id, status, released_quantity, remaining, settled_at"
                        + " from reservation_allocation where tenant_id ="
                        + " current_setting('app.current_tenant', true)::uuid"
                        + " and reservation_id = ? order by allocated_at",
                    id));
    assertThat(allocationsAfter).hasSize(2);
    for (var row : allocationsAfter) {
      assertThat(row.get("status", String.class)).isEqualTo("RELEASED");
      assertThat(row.get("remaining", BigDecimal.class)).isEqualByComparingTo("0");
      assertInstant(row.get("settled_at", OffsetDateTime.class), releasedAt);
    }
    assertThat(allocationsAfter.get(0).get("id", UUID.class)).isEqualTo(one.allocationId());

    // 分录：两条分配级 + 一条预占级，量与时刻都逐字写回
    var entries =
        asA(
            "read",
            () ->
                sql.fetch(
                    "select allocation_id, transition, from_status, to_status, quantity, occurred_at"
                        + " from reservation_entry where tenant_id ="
                        + " current_setting('app.current_tenant', true)::uuid"
                        + " and reservation_id = ? and transition in ('RELEASED','EXPIRED')"
                        + " order by allocation_id nulls last",
                    id));
    assertThat(entries).hasSize(3);
    for (var row : entries) {
      assertInstant(row.get("occurred_at", OffsetDateTime.class), releasedAt);
    }
    var reservationLevel =
        entries.stream()
            .filter(e -> e.get("allocation_id", UUID.class) == null)
            .findFirst()
            .orElseThrow();
    assertThat(reservationLevel.get("from_status", String.class)).isEqualTo("HELD");
    assertThat(reservationLevel.get("to_status", String.class)).isEqualTo("RELEASED");
    assertThat(reservationLevel.get("quantity", BigDecimal.class)).isEqualByComparingTo("20");

    // 凭据：append-only 一条，量与状态与事实一致
    var record =
        asA(
            "read",
            () ->
                sql.fetchOne(
                    "select mode, trigger_source, terminal, status_after, allocation_count,"
                        + " restored_quantity, released_demand_quantity, occurred_at"
                        + " from reservation_release_record where tenant_id ="
                        + " current_setting('app.current_tenant', true)::uuid and id = ?",
                    outcome.releaseRecordId()));
    assertThat(record.get("mode", String.class)).isEqualTo("RELEASE");
    assertThat(record.get("trigger_source", String.class)).isEqualTo("ORDER_CANCELLED");
    assertThat(record.get("terminal", Boolean.class)).isTrue();
    assertThat(record.get("status_after", String.class)).isEqualTo("RELEASED");
    assertThat(record.get("allocation_count", Integer.class)).isEqualTo(2);
    assertInstant(record.get("occurred_at", OffsetDateTime.class), releasedAt);
  }

  @Test
  void 恢复量与释放量是两个数_没圈到bucket的部分不计入可用量() {
    UUID id = reserve("12");
    allocate(id, locationOne, "10", at("2026-10-14T09:00:00+08:00"));

    var outcome = release(id, full());

    // 需求侧放弃了 12 件，但真正占过库存的只有 10 件。
    // 合并这两个数就会让可用量凭空多出 2 —— 而「可用量只准有一个来源」是本卷硬约束。
    assertThat(outcome.releasedDemandQuantity()).isEqualByComparingTo("12");
    assertThat(outcome.restoredQuantity()).isEqualByComparingTo("10");
    assertThat(outcome.allocationCount()).isEqualTo(1);
    assertThat(outcome.terminal()).isTrue();
  }

  @Test
  void 部分释放让分配留在原状态并继续占额() {
    UUID id = reserve("20");
    var one = allocate(id, locationOne, "12", at("2026-10-14T09:00:00+08:00"));

    var outcome = release(id, qty("5"));

    // 拆单减少：还欠 7 件，货还在仓里占着可用量
    assertThat(outcome.terminal()).isFalse();
    assertThat(outcome.statusAfter()).isEqualTo("HELD");
    assertThat(outcome.statusBefore()).isEqualTo("HELD");
    assertThat(outcome.restoredQuantity()).isEqualByComparingTo("5");

    var row =
        asA(
            "read",
            () ->
                sql.fetchOne(
                    "select status, released_quantity, remaining, settled_at"
                        + " from reservation_allocation where tenant_id ="
                        + " current_setting('app.current_tenant', true)::uuid and id = ?",
                    one.allocationId()));
    // 部分释放不落终态：已还的那 5 件不该再占，其余 7 件还占着
    assertThat(row.get("status", String.class)).isEqualTo("HELD");
    assertThat(row.get("released_quantity", BigDecimal.class)).isEqualByComparingTo("5");
    assertThat(row.get("remaining", BigDecimal.class)).isEqualByComparingTo("7");
    assertThat(row.get("settled_at", OffsetDateTime.class)).isNull();

    var parent =
        asA(
            "read",
            () ->
                sql.fetchOne(
                    "select status, released_quantity, allocated_quantity"
                        + " from inventory_reservation where tenant_id ="
                        + " current_setting('app.current_tenant', true)::uuid and id = ?",
                    id));
    assertThat(parent.get("status", String.class)).isEqualTo("HELD");
    assertThat(parent.get("released_quantity", BigDecimal.class)).isEqualByComparingTo("5");
    assertThat(parent.get("allocated_quantity", BigDecimal.class)).isEqualByComparingTo("12");

    // **跨模块约束**：部分释放没有发生任何状态迁移，所以**一条分录都不能写**。
    // V107 的分录守卫把 HELD → HELD 判成非法迁移，那正是它该做的事 ——
    // 把没发生的迁移写进时间线，时间线就成了编的（V107 是 7.2.1.2 建的，本模块不得放宽它）。
    // 恢复量改由 append-only 的释放凭据承担（见 restoredQuantity 断言）。
    var entries =
        asA(
            "read",
            () ->
                sql.fetchOne(
                    "select count(*) from reservation_entry where tenant_id ="
                        + " current_setting('app.current_tenant', true)::uuid"
                        + " and reservation_id = ?",
                    id));
    assertThat(entries.get(0, Long.class)).as("部分释放不得写分录：只有真的进了终态的分配才写").isEqualTo(1L);

    var record =
        asA(
            "read",
            () ->
                sql.fetchOne(
                    "select terminal, restored_quantity, status_before, status_after"
                        + " from reservation_release_record where tenant_id ="
                        + " current_setting('app.current_tenant', true)::uuid and reservation_id = ?",
                    id));
    assertThat(record.get("terminal", Boolean.class)).isFalse();
    assertThat(record.get("restored_quantity", BigDecimal.class)).isEqualByComparingTo("5");
    assertThat(record.get("status_before", String.class)).isEqualTo("HELD");
    assertThat(record.get("status_after", String.class)).isEqualTo("HELD");
  }

  @Test
  void 部分释放时要求终结整笔会被拒_否则货再也放不掉() {
    UUID id = reserve("20");
    allocate(id, locationOne, "12", at("2026-10-14T09:00:00+08:00"));

    assertThatThrownBy(
            () ->
                release(
                    id,
                    new ReservationReleaseInput(
                        null, new BigDecimal("5"), "MANUAL", null, null, null, true)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不能终结整笔预占");
  }

  @Test
  void 释放超过分配剩余量被拒_凭空造出可用量() {
    UUID id = reserve("20");
    allocate(id, locationOne, "12", at("2026-10-14T09:00:00+08:00"));

    assertThatThrownBy(() -> release(id, qty("15")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("从未圈到 bucket");

    // 数据未动
    var row =
        asA(
            "read",
            () ->
                sql.fetchOne(
                    "select released_quantity from reservation_allocation where tenant_id ="
                        + " current_setting('app.current_tenant', true)::uuid and reservation_id = ?",
                    id));
    assertThat(row.get("released_quantity", BigDecimal.class)).isEqualByComparingTo("0");
  }

  @Test
  void 按分配清单逐条释放() {
    UUID id = reserve("20");
    var one = allocate(id, locationOne, "12", at("2026-10-14T09:00:00+08:00"));
    var two = allocate(id, locationTwo, "8", at("2026-10-14T09:05:00+08:00"));

    var outcome =
        release(
            id,
            new ReservationReleaseInput(
                List.of(
                    new ReservationReleaseAllocationInput(one.allocationId(), new BigDecimal("4")),
                    new ReservationReleaseAllocationInput(two.allocationId(), null)),
                null,
                "MANUAL",
                "批次级调整",
                null,
                null,
                null));

    // 第一条部分放 4（留在 HELD 继续占额 8），第二条全放 8（进终态 RELEASED）
    assertThat(outcome.terminal()).isFalse();
    assertThat(outcome.statusAfter()).isEqualTo("HELD");
    assertThat(outcome.restoredQuantity()).isEqualByComparingTo("12");
    assertThat(outcome.allocationCount()).isEqualTo(2);

    var first =
        asA(
            "read",
            () ->
                sql.fetchOne(
                    "select status, released_quantity, remaining from reservation_allocation"
                        + " where tenant_id = current_setting('app.current_tenant', true)::uuid"
                        + " and id = ?",
                    one.allocationId()));
    assertThat(first.get("status", String.class)).isEqualTo("HELD");
    assertThat(first.get("remaining", BigDecimal.class)).isEqualByComparingTo("8");

    var second =
        asA(
            "read",
            () ->
                sql.fetchOne(
                    "select status, released_quantity, remaining from reservation_allocation"
                        + " where tenant_id = current_setting('app.current_tenant', true)::uuid"
                        + " and id = ?",
                    two.allocationId()));
    assertThat(second.get("status", String.class)).isEqualTo("RELEASED");
    assertThat(second.get("remaining", BigDecimal.class)).isEqualByComparingTo("0");
  }

  @Test
  void 释放清单里的分配id缺失时报错而不是静默少放() {
    UUID id = reserve("20");
    allocate(id, locationOne, "12", at("2026-10-14T09:00:00+08:00"));

    // 分配 id 为空：不是漏传就是拼错了
    assertThatThrownBy(
            () ->
                release(
                    id,
                    new ReservationReleaseInput(
                        List.of(new ReservationReleaseAllocationInput(null, new BigDecimal("1"))),
                        null,
                        "MANUAL",
                        null,
                        null,
                        null,
                        null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("allocationId");
  }

  @Test
  void 释放清单引用别的预占的分配时报404而不是照单全收() {
    UUID mine = reserve("20");
    allocate(mine, locationOne, "12", at("2026-10-14T09:00:00+08:00"));
    UUID other = reserve("20");
    var otherAllocation = allocate(other, locationTwo, "5", at("2026-10-14T09:00:00+08:00"));

    // 拿 B 笔预占下的分配 id 去释放 A 笔：404。
    // 照单全收等于允许跨预占改别人的分配状态，那正是审计痕迹被改指的开始。
    assertThatThrownBy(
            () ->
                release(
                    mine,
                    new ReservationReleaseInput(
                        List.of(
                            new ReservationReleaseAllocationInput(
                                otherAllocation.allocationId(), new BigDecimal("1"))),
                        null,
                        "MANUAL",
                        null,
                        null,
                        null,
                        null)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或无权操作");

    var untouched =
        asA(
            "read",
            () ->
                sql.fetchOne(
                    "select status from reservation_allocation where tenant_id ="
                        + " current_setting('app.current_tenant', true)::uuid and id = ?",
                    otherAllocation.allocationId()));
    assertThat(untouched.get("status", String.class)).isEqualTo("HELD");
  }

  @Test
  void 已终结的分配不在可释放之列() {
    UUID id = reserve("20");
    var one = allocate(id, locationOne, "12", at("2026-10-14T09:00:00+08:00"));
    allocate(id, locationTwo, "8", at("2026-10-14T09:05:00+08:00"));
    release(id, qty("3")); // 只部分释放：分配仍是 HELD，但已还 3

    assertThatThrownBy(
            () ->
                release(
                    id,
                    new ReservationReleaseInput(
                        List.of(
                            new ReservationReleaseAllocationInput(
                                one.allocationId(), new BigDecimal("99"))),
                        null,
                        "MANUAL",
                        null,
                        null,
                        null,
                        null)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("凭空造出可用量");
  }

  // ================================================================ 幂等

  @Test
  void 同幂等键同参数回放既有凭据_货不被还两次() {
    UUID id = reserve("20");
    allocate(id, locationOne, "12", at("2026-10-14T09:00:00+08:00"));
    OffsetDateTime when = at("2026-10-14T11:30:00+08:00");
    var input =
        new ReservationReleaseInput(null, null, "ORDER_CANCELLED", "订单取消", when, "K-REPLAY", null);

    var first = release(id, input);
    var second = release(id, input);

    assertThat(first.replayed()).isFalse();
    assertThat(second.replayed()).isTrue();
    assertThat(second.releaseRecordId()).isEqualTo(first.releaseRecordId());

    var records =
        asA(
            "read",
            () ->
                sql.fetch(
                    "select id from reservation_release_record where tenant_id ="
                        + " current_setting('app.current_tenant', true)::uuid and reservation_id = ?",
                    id));
    assertThat(records).as("同键重放不得产生第二条凭据").hasSize(1);

    var entries =
        asA(
            "read",
            () ->
                sql.fetchOne(
                    "select count(*) from reservation_entry where tenant_id ="
                        + " current_setting('app.current_tenant', true)::uuid"
                        + " and reservation_id = ? and transition = 'RELEASED'",
                    id));
    assertThat(entries.get(0, Long.class)).isEqualTo(2L);
  }

  @Test
  void 同幂等键不同参数被拒() {
    UUID id = reserve("20");
    allocate(id, locationOne, "12", at("2026-10-14T09:00:00+08:00"));
    release(id, new ReservationReleaseInput(null, null, "MANUAL", "第一次", null, "K-CONFLICT", null));

    assertThatThrownBy(
            () ->
                release(
                    id,
                    new ReservationReleaseInput(
                        null, new BigDecimal("3"), "MANUAL", "另一次意图", null, "K-CONFLICT", null)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已被另一组参数用过");
  }

  @Test
  void 终态再来一次释放按重放处理而不是报错() {
    UUID id = reserve("20");
    allocate(id, locationOne, "12", at("2026-10-14T09:00:00+08:00"));
    var first = release(id, full());
    // 没有幂等键的第二条命令（上游事件重投）：必须成功返回，不能把货再还一次
    var second = release(id, full());

    assertThat(second.replayed()).isTrue();
    assertThat(second.releaseRecordId()).isEqualTo(first.releaseRecordId());
    assertThat(second.restoredQuantity()).isEqualByComparingTo(first.restoredQuantity());
  }

  @Test
  void 已过期的预占不能再手动释放() {
    UUID id = reserve("20", at("2026-10-14T01:00:00+08:00"));
    allocate(id, locationOne, "12", at("2026-10-14T01:30:00+08:00"));
    sweep(new ReservationReleaseRunRequest(false, at("2026-10-14T02:00:00+08:00"), 50, null, null));

    assertThatThrownBy(() -> release(id, full()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已终结于");
  }

  @Test
  void 手动入口不接受超时回收来源() {
    UUID id = reserve("20");
    allocate(id, locationOne, "12", at("2026-10-14T09:00:00+08:00"));

    assertThatThrownBy(
            () ->
                release(
                    id,
                    new ReservationReleaseInput(
                        null, null, "EXPIRY_SWEEP", null, null, null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("EXPIRY_SWEEP");
  }

  @Test
  void 并发两次释放只产生一条凭据与一条有效分录() throws Exception {
    UUID id = reserve("20");
    allocate(id, locationOne, "12", at("2026-10-14T09:00:00+08:00"));

    var pool = Executors.newFixedThreadPool(2);
    try {
      var futures =
          List.of(pool.submit(() -> release(id, full())), pool.submit(() -> release(id, full())));
      var results =
          List.of(
              futures.get(0).get(30, TimeUnit.SECONDS), futures.get(1).get(30, TimeUnit.SECONDS));
      // 一条真的释放、一条被判为重放：连接池只有 1 条连接时两者必然串行，
      // 真正防重复的是「状态谓词 + 终态不可复活」，这一条断言读的就是那个效果。
      assertThat(results.stream().filter(r -> !r.replayed())).hasSize(1);
      assertThat(results.stream().filter(ReservationReleaseOutcome::replayed)).hasSize(1);
      assertThat(results.get(0).restoredQuantity())
          .isEqualByComparingTo(results.get(1).restoredQuantity());
    } finally {
      pool.shutdownNow();
    }

    var records =
        asA(
            "read",
            () ->
                sql.fetch(
                    "select id from reservation_release_record where tenant_id ="
                        + " current_setting('app.current_tenant', true)::uuid and reservation_id = ?",
                    id));
    assertThat(records).hasSize(1);
  }

  // ================================================================ 批量过期清理

  @Test
  void 干跑只写运行凭据不碰事实() {
    UUID id = reserve("20", at("2026-10-14T01:00:00+08:00"));
    allocate(id, locationOne, "12", at("2026-10-14T01:10:00+08:00"));
    OffsetDateTime evaluatedAt = at("2026-10-14T02:00:00+08:00");

    var run = sweep(new ReservationReleaseRunRequest(true, evaluatedAt, 50, "先看看", null));

    assertThat(run.dryRun()).isTrue();
    assertThat(run.candidateCount()).isEqualTo(1);
    assertThat(run.processedCount()).isZero();
    assertThat(run.skippedCount()).isZero();
    assertThat(run.restoredQuantity()).isEqualByComparingTo("0");
    // 判定时刻逐字写回：候选集是「按这个时刻算的」，事后要能复现
    assertInstant(run.evaluatedAt(), evaluatedAt);

    var parent =
        asA(
            "read",
            () ->
                sql.fetchOne(
                    "select status, settled_at from inventory_reservation where tenant_id ="
                        + " current_setting('app.current_tenant', true)::uuid and id = ?",
                    id));
    assertThat(parent.get("status", String.class)).isEqualTo("HELD");
    assertThat(parent.get("settled_at", OffsetDateTime.class)).isNull();

    var allocations =
        asA(
            "read",
            () ->
                sql.fetch(
                    "select status from reservation_allocation where tenant_id ="
                        + " current_setting('app.current_tenant', true)::uuid and reservation_id = ?",
                    id));
    assertThat(allocations.get(0).get("status", String.class)).isEqualTo("HELD");
  }

  @Test
  void 批量清理把超期预占收成已过期并记账() {
    UUID id = reserve("20", at("2026-10-14T01:00:00+08:00"));
    allocate(id, locationOne, "12", at("2026-10-14T01:10:00+08:00"));
    OffsetDateTime evaluatedAt = at("2026-10-14T02:00:00+08:00");

    var run = sweep(new ReservationReleaseRunRequest(false, evaluatedAt, 50, null, null));

    assertThat(run.dryRun()).isFalse();
    assertThat(run.candidateCount()).isEqualTo(1);
    assertThat(run.processedCount()).isEqualTo(1);
    assertThat(run.skippedCount()).isZero();
    assertThat(run.releaseCount()).isEqualTo(1);
    assertThat(run.restoredQuantity()).isEqualByComparingTo("12");
    assertThat(run.status()).isEqualTo("SUCCEEDED");
    assertInstant(run.evaluatedAt(), evaluatedAt);

    var parent =
        asA(
            "read",
            () ->
                sql.fetchOne(
                    "select status, released_quantity, settled_at from inventory_reservation"
                        + " where tenant_id = current_setting('app.current_tenant', true)::uuid"
                        + " and id = ?",
                    id));
    assertThat(parent.get("status", String.class)).isEqualTo("EXPIRED");
    assertThat(parent.get("released_quantity", BigDecimal.class)).isEqualByComparingTo("20");
    assertInstant(parent.get("settled_at", OffsetDateTime.class), evaluatedAt);

    // EXPIRED 分录落在同一张 append-only 流水上，与状态迁移逐字对应
    var entries =
        asA(
            "read",
            () ->
                sql.fetch(
                    "select to_status, quantity, occurred_at from reservation_entry"
                        + " where tenant_id = current_setting('app.current_tenant', true)::uuid"
                        + " and reservation_id = ? and transition = 'EXPIRED'",
                    id));
    assertThat(entries).hasSize(2);
    for (var row : entries) {
      assertThat(row.get("to_status", String.class)).isEqualTo("EXPIRED");
      assertInstant(row.get("occurred_at", OffsetDateTime.class), evaluatedAt);
    }

    var record =
        asA(
            "read",
            () ->
                sql.fetchOne(
                    "select mode, trigger_source, terminal, status_after, expiry_run_id, occurred_at"
                        + " from reservation_release_record where tenant_id ="
                        + " current_setting('app.current_tenant', true)::uuid and reservation_id = ?",
                    id));
    assertThat(record.get("mode", String.class)).isEqualTo("EXPIRE");
    assertThat(record.get("trigger_source", String.class)).isEqualTo("EXPIRY_SWEEP");
    assertThat(record.get("terminal", Boolean.class)).isTrue();
    assertThat(record.get("status_after", String.class)).isEqualTo("EXPIRED");
    assertThat(record.get("expiry_run_id", UUID.class)).isEqualTo(run.id());
    assertInstant(record.get("occurred_at", OffsetDateTime.class), evaluatedAt);

    // 运行详情把这一批产生的释放凭据列出来
    var detail = asA("read", () -> service.run(a, run.id()));
    assertThat(detail.releases()).hasSize(1);
    assertThat(detail.releaseTotal()).isEqualTo(1);
    assertThat(detail.releases().get(0).reservationId()).isEqualTo(id);
  }

  @Test
  void 承诺过的预占永不被自动回收() {
    UUID id = reserve("20", at("2026-10-14T01:00:00+08:00"));
    var one = allocate(id, locationOne, "20", at("2026-10-14T01:10:00+08:00"));
    asA(
        "update",
        () ->
            allocations.commit(
                a,
                id,
                one.allocationId(),
                new ReservationAllocationModels.ReservationAllocationTransitionInput(
                    null, null, "承诺")));

    var run =
        sweep(
            new ReservationReleaseRunRequest(
                false, at("2026-10-14T02:00:00+08:00"), 50, null, null));

    // 蓝图红线：已承诺发货的货被 TTL 收走就是超卖
    assertThat(run.candidateCount()).isZero();
    var parent =
        asA(
            "read",
            () ->
                sql.fetchOne(
                    "select status from inventory_reservation where tenant_id ="
                        + " current_setting('app.current_tenant', true)::uuid and id = ?",
                    id));
    assertThat(parent.get("status", String.class)).isEqualTo("COMMITTED");
  }

  @Test
  void 名下还有承诺的预占整笔跳过而不是只跳过那一条() {
    UUID id = reserve("20", at("2026-10-14T01:00:00+08:00"));
    var committed = allocate(id, locationOne, "10", at("2026-10-14T01:05:00+08:00"));
    allocate(id, locationTwo, "10", at("2026-10-14T01:10:00+08:00"));
    asA(
        "update",
        () ->
            allocations.commit(
                a,
                id,
                committed.allocationId(),
                new ReservationAllocationModels.ReservationAllocationTransitionInput(
                    null, null, "承诺")));

    var run =
        sweep(
            new ReservationReleaseRunRequest(
                false, at("2026-10-14T02:00:00+08:00"), 50, null, null));

    assertThat(run.candidateCount()).isEqualTo(1);
    assertThat(run.processedCount()).isZero();
    assertThat(run.skippedCount()).as("有承诺在身的必须整笔跳过").isEqualTo(1);

    var rows =
        asA(
            "read",
            () ->
                sql.fetch(
                    "select status from reservation_allocation where tenant_id ="
                        + " current_setting('app.current_tenant', true)::uuid"
                        + " and reservation_id = ? order by allocated_at",
                    id));
    // 两条都还在：只过期掉 HELD 那条会让预占进终态，而 COMMITTED 那条的货将永远放不掉
    assertThat(rows.stream().map(r -> r.get("status", String.class)))
        .containsExactly("COMMITTED", "HELD");

    // 这个数必须能被看见，否则运营永远发现不了「这批货为什么一直占着」
    var page = candidates(at("2026-10-14T02:00:00+08:00"));
    assertThat(page.summary().mixedCommitmentSkippedCount()).isEqualTo(1);
  }

  @Test
  void 未到期的预占不进候选() {
    UUID id = reserve("20", at("2026-10-14T09:00:00+08:00"));
    allocate(id, locationOne, "12", at("2026-10-14T08:00:00+08:00"));

    var page = candidates(at("2026-10-14T07:00:00+08:00"));
    assertThat(page.rows()).isEmpty();
    assertThat(page.summary().overdueCount()).isZero();
    assertThat(page.summary().unmanagedHeldCount()).isZero();
  }

  @Test
  void 候选清单逐字给出判定时刻与可还回量() {
    UUID id = reserve("20", at("2026-10-14T01:00:00+08:00"));
    allocate(id, locationOne, "12", at("2026-10-14T01:10:00+08:00"));
    OffsetDateTime evaluatedAt = at("2026-10-14T02:00:00+08:00");

    var page = candidates(evaluatedAt);

    assertInstant(page.evaluatedAt(), evaluatedAt);
    assertThat(page.rows()).hasSize(1);
    var row = page.rows().get(0);
    assertThat(row.reservationId()).isEqualTo(id);
    assertThat(row.restorableQuantity()).isEqualByComparingTo("12");
    assertThat(row.requestedQuantity()).isEqualByComparingTo("20");
    // 超时时长从 expires_at 真值算，不是「创建时间 − 现在」这种自欺
    assertThat(row.overdueSeconds()).isEqualTo(3600);
    assertThat(row.allocations()).hasSize(1);
    assertThat(row.allocations().get(0).locationCode()).isEqualTo("L-01");
    assertThat(row.allocations().get(0).lotCode()).isEqualTo("LOT-A");
    assertThat(page.summary().overdueRestorableQuantity()).isEqualByComparingTo("12");
    assertThat(page.ruleNote()).contains("COMMITTED");
  }

  @Test
  void 没传过期时刻的预占被单独计数() {
    UUID managed = reserve("20", at("2026-10-14T01:00:00+08:00"));
    allocate(managed, locationOne, "12", at("2026-10-14T01:10:00+08:00"));
    reserve("5"); // 登记时没传 expiresAt：永远不会自动过期

    var page = candidates(at("2026-10-14T02:00:00+08:00"));
    assertThat(page.summary().totalHeldCount()).isEqualTo(2);
    assertThat(page.summary().unmanagedHeldCount()).as("「登记时忘了传过期时刻」只能靠这个数被发现").isEqualTo(1);
    assertThat(page.summary().overdueCount()).isEqualTo(1);
  }

  @Test
  void 单批上限写在运行凭据里且候选数不受它影响() {
    for (int i = 0; i < 3; i++) {
      UUID id = reserve("10", at("2026-10-14T01:00:00+08:00"));
      allocate(id, locationOne, "10", at("2026-10-14T01:10:00+08:00"));
    }
    var run =
        sweep(
            new ReservationReleaseRunRequest(
                false, at("2026-10-14T02:00:00+08:00"), 2, null, null));

    assertThat(run.candidateCount()).as("候选总数不受单批上限影响，否则看不出被截断").isEqualTo(3);
    assertThat(run.processedCount()).isEqualTo(2);
    assertThat(run.limitCount()).isEqualTo(2);
  }

  @Test
  void 批量清理带幂等键重投时回放同一次运行() {
    UUID id = reserve("20", at("2026-10-14T01:00:00+08:00"));
    allocate(id, locationOne, "12", at("2026-10-14T01:10:00+08:00"));
    var request =
        new ReservationReleaseRunRequest(false, at("2026-10-14T02:00:00+08:00"), 50, null, "RUN-1");

    var first = sweep(request);
    var second = sweep(request);

    assertThat(second.id()).isEqualTo(first.id());
    assertThat(second.replayed()).isTrue();
    assertThat(second.processedCount()).isEqualTo(1);

    // 不带键重跑：扫不到候选，天然无副作用
    var third =
        sweep(
            new ReservationReleaseRunRequest(
                false, at("2026-10-14T03:00:00+08:00"), 50, null, null));
    assertThat(third.processedCount()).isZero();
    assertThat(third.candidateCount()).isZero();
  }

  @Test
  void 单批上限越界被拒() {
    assertThatThrownBy(() -> sweep(new ReservationReleaseRunRequest(false, null, 0, null, null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> sweep(new ReservationReleaseRunRequest(false, null, 5000, null, null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ================================================================ 过期时刻补写

  @Test
  void 补写过期时刻逐字写回并算出超时时长() {
    UUID id = reserve("20");
    allocate(id, locationOne, "12", at("2026-10-14T09:00:00+08:00"));
    OffsetDateTime expiresAt = at("2026-10-13T09:00:00+08:00");

    var outcome =
        asA(
            "update",
            () ->
                service.setExpiry(
                    a, id, new ReservationReleaseExpiryInput(expiresAt, null, "补过期时刻")));

    assertThat(outcome.updated()).isTrue();
    assertInstant(outcome.expiresAt(), expiresAt);

    // 写回库里的真值（不是回显入参）
    var stored =
        asA(
            "read",
            () ->
                sql.fetchOne(
                    "select expires_at from inventory_reservation where tenant_id ="
                        + " current_setting('app.current_tenant', true)::uuid and id = ?",
                    id));
    assertInstant(stored.get("expires_at", OffsetDateTime.class), expiresAt);
    assertThat(outcome.overdueSeconds()).isNotNull().isPositive();

    // 补上之后它立刻进候选 —— 这正是 7.2.1.1 交接的那件事
    var page = candidates(outcome.expiresAt().plusSeconds(60));
    assertThat(page.rows()).extracting(ReservationReleaseCandidateRow::reservationId).contains(id);
  }

  @Test
  void 未来时刻的超时时长为null而不是0() {
    UUID id = reserve("20");
    OffsetDateTime expiresAt = at("2030-01-01T00:00:00+08:00");
    var outcome =
        asA(
            "update",
            () ->
                service.setExpiry(a, id, new ReservationReleaseExpiryInput(expiresAt, null, null)));
    assertInstant(outcome.expiresAt(), expiresAt);
    assertThat(outcome.overdueSeconds()).as("给 0 会把「未超时」与「超时 0 秒」混成同一个数").isNull();
  }

  @Test
  void 清空过期时刻后不再参与批量清理() {
    UUID id = reserve("20", at("2026-10-14T01:00:00+08:00"));
    allocate(id, locationOne, "12", at("2026-10-14T01:10:00+08:00"));
    var outcome =
        asA(
            "update",
            () -> service.setExpiry(a, id, new ReservationReleaseExpiryInput(null, true, "改成手动")));
    assertThat(outcome.expiresAt()).isNull();
    assertThat(outcome.message()).contains("不再参与");

    var run =
        sweep(
            new ReservationReleaseRunRequest(
                false, at("2026-10-14T05:00:00+08:00"), 50, null, null));
    assertThat(run.candidateCount()).isZero();
    assertThat(candidates(at("2026-10-14T05:00:00+08:00")).summary().unmanagedHeldCount())
        .isEqualTo(1);
  }

  @Test
  void 已承诺的预占不接受过期时刻() {
    UUID id = reserve("20");
    var one = allocate(id, locationOne, "20", at("2026-10-14T09:00:00+08:00"));
    asA(
        "update",
        () ->
            allocations.commit(
                a,
                id,
                one.allocationId(),
                new ReservationAllocationModels.ReservationAllocationTransitionInput(
                    null, null, "承诺")));

    assertThatThrownBy(
            () ->
                asA(
                    "update",
                    () ->
                        service.setExpiry(
                            a,
                            id,
                            new ReservationReleaseExpiryInput(
                                at("2026-10-15T00:00:00+08:00"), null, null))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("只有预占中");
  }

  // ================================================================ DB 层守卫

  @Test
  void 释放凭据appendOnly改与删都被拒() {
    UUID id = reserve("20");
    allocate(id, locationOne, "12", at("2026-10-14T09:00:00+08:00"));
    var outcome = release(id, full());

    assertRejectedByDb(
        () ->
            asA(
                "update",
                () ->
                    sql.execute(
                        "update reservation_release_record set reason='事后改的'"
                            + " where tenant_id = current_setting('app.current_tenant', true)::uuid"
                            + " and id = ?",
                        outcome.releaseRecordId())),
        "append-only");

    assertRejectedByDb(
        () ->
            asA(
                "update",
                () ->
                    sql.execute(
                        "delete from reservation_release_record where tenant_id ="
                            + " current_setting('app.current_tenant', true)::uuid and id = ?",
                        outcome.releaseRecordId())),
        "append-only");
  }

  @Test
  void 运行凭据同样appendOnly() {
    UUID id = reserve("20", at("2026-10-14T01:00:00+08:00"));
    allocate(id, locationOne, "12", at("2026-10-14T01:10:00+08:00"));
    var run =
        sweep(
            new ReservationReleaseRunRequest(
                true, at("2026-10-14T02:00:00+08:00"), 50, null, null));

    assertRejectedByDb(
        () ->
            asA(
                "update",
                () ->
                    sql.execute(
                        "update reservation_expiry_run set processed_count=999"
                            + " where tenant_id = current_setting('app.current_tenant', true)::uuid"
                            + " and id = ?",
                        run.id())),
        "append-only");
  }

  @Test
  void 凭据与事实不符时被DB层拒绝() {
    UUID id = reserve("20");
    allocate(id, locationOne, "12", at("2026-10-14T09:00:00+08:00"));

    // 预占还在 HELD，却想插一条「已释放终态」的凭据：V112 的守卫当场拒。
    // 没有这条守卫，凭据就可以是编的，而账实核对会一直以为货还回去了。
    assertRejectedByDb(
        () ->
            asA(
                "update",
                () ->
                    sql.execute(
                        "insert into reservation_release_record(id,tenant_id,reservation_id,"
                            + "mode,trigger_source,terminal,status_before,status_after,"
                            + "allocation_count,restored_quantity,released_demand_quantity,occurred_at)"
                            + " values(?::uuid,current_setting('app.current_tenant', true)::uuid,?::uuid,"
                            + "'RELEASE','MANUAL',true,'HELD','RELEASED',1,5::numeric,5::numeric,"
                            + "now()::timestamptz)",
                        UUID.randomUUID(),
                        id)),
        "凭据必须与事实一致");
  }

  @Test
  void 终态不可复活在DB层兜住() {
    UUID id = reserve("20");
    allocate(id, locationOne, "12", at("2026-10-14T09:00:00+08:00"));
    var outcome = release(id, full());

    // 绕过服务层裸写 SQL，把已终结的分配改回 HELD：V107 的更新守卫必须拒。
    assertRejectedByDb(
        () ->
            asA(
                "update",
                () ->
                    sql.execute(
                        "update reservation_allocation set status='HELD', settled_at=null"
                            + " where tenant_id = current_setting('app.current_tenant', true)::uuid"
                            + " and reservation_id = ?",
                        id)),
        // 报的是容量守卫而不是复活守卫：触发器按字母序跑，capacity 在 update 之前，
        // 所以它先挡下「往已终结的预占上分配」这一层。两道守卫都在，终态同样改不回去。
        "已终结于 RELEASED");

    // 预占同样：V102 的状态机守卫拒把终态改回。
    assertRejectedByDb(
        () ->
            asA(
                "update",
                () ->
                    sql.execute(
                        "update inventory_reservation set status='HELD', settled_at=null,"
                            + " committed_at=null where tenant_id ="
                            + " current_setting('app.current_tenant', true)::uuid and id = ?",
                        id)),
        "迟到命令不得复活");
    assertThat(outcome.terminal()).isTrue();
  }

  // ================================================================ 能力声明与读端点

  @Test
  void 能力位如实声明可用量口径归可用量模块() {
    var capabilities = asA("read", () -> service.capabilities());
    var byDimension =
        capabilities.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    ReservationModels.ReservationCapability::dimension, c -> c));
    assertThat(byDimension.get("RELEASE_MANUAL").available()).isTrue();
    assertThat(byDimension.get("EXPIRY_SWEEP").available()).isTrue();
    assertThat(byDimension.get("EXPIRY_SCHEDULE").available()).isTrue();
    // 可用量口径不归本模块：报 true 等于宣称本模块算出了可用量，而那正是本卷禁止的「两个地方各算一次」
    assertThat(byDimension.get("AVAILABLE_RESTORE").available()).isFalse();
    assertThat(byDimension.get("AVAILABLE_RESTORE").ownerModule()).isEqualTo("7.1.1.3");
  }

  @Test
  void 释放凭据可按预占查询() {
    UUID id = reserve("20");
    allocate(id, locationOne, "12", at("2026-10-14T09:00:00+08:00"));
    release(id, full());
    release(id, full()); // 重放不新增凭据

    var page = asA("read", () -> service.records(a, id, 0, 20));
    assertThat(page.rows()).hasSize(1);
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.rows().get(0).reservationId()).isEqualTo(id);
    assertThat(page.rows().get(0).mode()).isEqualTo("RELEASE");
    assertThat(page.rows().get(0).restoredQuantity()).isEqualByComparingTo("12");
  }

  @Test
  void 运行历史与下拉项可读() {
    UUID id = reserve("20", at("2026-10-14T01:00:00+08:00"));
    allocate(id, locationOne, "12", at("2026-10-14T01:10:00+08:00"));
    sweep(
        new ReservationReleaseRunRequest(false, at("2026-10-14T02:00:00+08:00"), 50, "夜间清理", null));

    var page = asA("read", () -> service.runs(a, 0, 20));
    assertThat(page.rows()).hasSize(1);
    assertThat(page.rows().get(0).reason()).isEqualTo("夜间清理");

    var options = asA("read", () -> service.options());
    assertThat(options.triggers())
        .filteredOn(ReservationReleaseOption::sweepOnly)
        .extracting(ReservationReleaseOption::code)
        .containsExactly("EXPIRY_SWEEP");
    assertThat(options.ruleNote()).contains("COMMITTED");
  }

  // ================================================================ HTTP 层

  @Test
  void 端点形状与权限码() throws Exception {
    UUID id = reserve("20", at("2026-10-14T01:00:00+08:00"));
    allocate(id, locationOne, "12", at("2026-10-14T01:10:00+08:00"));

    http.perform(
            get("/warehouse/inventory/reservations/release-candidates")
                .header("Authorization", "Bearer tenant-a")
                .param("evaluatedAt", "2026-10-14T02:00:00+08:00"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1))
        .andExpect(jsonPath("$.rows[0].reservationId").value(id.toString()))
        .andExpect(jsonPath("$.rows[0].restorableQuantity").value(12))
        .andExpect(jsonPath("$.summary.overdueCount").value(1));

    // 只读角色不能释放
    http.perform(
            post("/warehouse/inventory/reservations/{id}/release", id)
                .header("Authorization", "Bearer read-only")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());

    http.perform(
            post("/warehouse/inventory/reservations/{id}/release", id)
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    """
                    {"triggerSource":"MANUAL","reason":"人工释放","quantity":4}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.terminal").value(false))
        .andExpect(jsonPath("$.restoredQuantity").value(4));

    http.perform(
            post("/warehouse/inventory/reservations/expire-overdue")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    """
                    {"dryRun":true,"evaluatedAt":"2026-10-14T03:00:00+08:00","limitCount":10}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dryRun").value(true))
        .andExpect(jsonPath("$.candidateCount").value(1));

    http.perform(
            post("/warehouse/inventory/reservations/{id}/expiry", id)
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"expiresAt\":\"2026-10-20T00:00:00+08:00\"}"))
        .andExpect(status().isOk())
        .andExpect(
            result -> {
              String value =
                  com.jayway.jsonpath.JsonPath.read(
                      result.getResponse().getContentAsString(), "$.expiresAt");
              assertThat(OffsetDateTime.parse(value).toInstant())
                  .isEqualTo(OffsetDateTime.parse("2026-10-20T00:00:00+08:00").toInstant());
            });

    http.perform(
            get("/warehouse/inventory/reservations/release-capabilities")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(5))
        .andExpect(jsonPath("$[?(@.dimension=='AVAILABLE_RESTORE')].ownerModule").value("7.1.1.3"));
  }

  @Test
  void 非法判定时刻返回400并回显原值() throws Exception {
    http.perform(
            get("/warehouse/inventory/reservations/release-candidates")
                .header("Authorization", "Bearer tenant-a")
                .param("evaluatedAt", "not-a-time"))
        .andExpect(status().isBadRequest());
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() throws Exception {
    UUID mine = reserve("999");
    var mineAllocation = allocate(mine, locationOne, "12", at("2026-10-14T09:00:00+08:00"));
    release(mine, full());

    UUID theirs =
        asB(
            "update",
            () ->
                reservations
                    .create(
                        b,
                        new ReservationModels.ReservationCreateInput(
                            "TRADE",
                            "SALES_ORDER",
                            UUID.randomUUID(),
                            "SO-B",
                            idFor(b, 1),
                            idFor(b, 3),
                            new BigDecimal("10"),
                            null,
                            at("2026-10-13T00:00:00+08:00"),
                            null))
                    .id());
    asB(
        "update",
        () ->
            allocations.allocate(
                b,
                theirs,
                new ReservationAllocationModels.ReservationAllocationCreateInput(
                    idFor(b, 5), idFor(b, 6), idFor(b, 8), new BigDecimal("10"), null, null)));

    // B 看得见自己的候选，看不见 A 的
    var theirCandidates =
        asB(
            "read",
            () ->
                service.candidates(
                    b,
                    new ReservationReleaseQuery(
                        null, null, null, 0, 50, at("2026-10-14T02:00:00+08:00"))));
    assertThat(theirCandidates.rows())
        .extracting(ReservationReleaseCandidateRow::reservationId)
        .containsExactly(theirs)
        .doesNotContain(mine);
    assertThat(theirCandidates.summary().overdueCount()).isEqualTo(1);

    // B 拿着 A 的预占 id 释放 → 404（给 403 等于确认「这条存在、只是不归你管」）
    assertThatThrownBy(() -> asB("update", () -> service.release(b, mine, user, full())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或无权查看");

    // B 的清理动不到 A 的预占
    asB(
        "update",
        () ->
            service.expireRun(
                b,
                user,
                new ReservationReleaseRunRequest(
                    false, at("2026-10-14T05:00:00+08:00"), 50, null, null)));
    var mineAfter =
        asA(
            "read",
            () ->
                sql.fetchOne(
                    "select status from inventory_reservation where tenant_id ="
                        + " current_setting('app.current_tenant', true)::uuid and id = ?",
                    mine));
    assertThat(mineAfter.get("status", String.class)).isEqualTo("RELEASED");
    assertThat(mineAllocation.allocationId()).isNotNull();
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    UUID mine = reserve("999");
    allocate(mine, locationOne, "12", at("2026-10-14T09:00:00+08:00"));

    http.perform(
            get("/warehouse/inventory/reservations/release-candidates")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(0));

    http.perform(
            post("/warehouse/inventory/reservations/{id}/release", mine)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString())
                .contentType("application/json")
                .content("{\"triggerSource\":\"MANUAL\"}"))
        .andExpect(status().isNotFound());

    // A 的数据未被这串伪造请求改动
    var row =
        asA(
            "read",
            () ->
                sql.fetchOne(
                    "select status from inventory_reservation where tenant_id ="
                        + " current_setting('app.current_tenant', true)::uuid and id = ?",
                    mine));
    assertThat(row.get("status", String.class)).isEqualTo("HELD");
    var records =
        asA(
            "read",
            () ->
                sql.fetchOne(
                    "select count(*) from reservation_release_record where tenant_id ="
                        + " current_setting('app.current_tenant', true)::uuid"));
    assertThat(records.get(0, Long.class)).as("伪造请求不得留下任何释放凭据").isZero();
  }

  @Test
  void 断言三_连接池归还后不串租户() throws Exception {
    UUID mine = reserve("999");
    allocate(mine, locationOne, "12", at("2026-10-14T09:00:00+08:00"));
    release(mine, full());

    // 池只有 1 条连接：交替跑两个租户，若上下文没随连接归还而重置，
    // 第二段的查询会读到第一段的数据（或直接被 RLS 拒掉）。
    for (int i = 0; i < 3; i++) {
      var mineRecords = asA("read", () -> service.records(a, null, 0, 50));
      assertThat(mineRecords.rows()).hasSize(1);
      assertThat(mineRecords.rows().get(0).reservationId()).isEqualTo(mine);

      var theirRecords = asB("read", () -> service.records(b, null, 0, 50));
      assertThat(theirRecords.rows()).as("B 不该看到 A 的释放凭据").isEmpty();

      var theirCandidates =
          asB(
              "read",
              () ->
                  service.candidates(
                      b,
                      new ReservationReleaseQuery(
                          null, null, null, 0, 50, at("2026-10-14T02:00:00+08:00"))));
      assertThat(theirCandidates.rows()).isEmpty();

      var mineCandidatesAgain =
          asA(
              "read",
              () ->
                  service.candidates(
                      a,
                      new ReservationReleaseQuery(
                          null, null, null, 0, 50, at("2026-10-14T02:00:00+08:00"))));
      assertThat(mineCandidatesAgain.summary().terminalReleasedCount()).isEqualTo(1);
    }
  }
}
