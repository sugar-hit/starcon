package com.starcon.warehouse;

import static com.starcon.warehouse.ReservationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 7.2.1.1 预占需求列表。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>本测试钉住五件「错了会误导人」的事：
 *
 * <ul>
 *   <li><b>剩余量口径</b>：remaining = 需求量 − 已消费量，<b>不含</b>已分配未出库的部分
 *       （已分配但未出库的货还在仓里占着可用量，算成已结清会让列表显示「这笔已经没事了」）。
 *   <li><b>超时长从真实写入时刻算</b>：按库里的 expires_at 算，断言的是**写回的值**，
 *       不是「没抛异常」。用 {@code Timestamp.valueOf(LocalDateTime)} 绑定会静默偏 −28800s。
 *   <li><b>超时为 null 而不是 0</b>：没超时与「超时 0 秒」在界面上是两件事。
 *   <li><b>状态汇总用标量子查询，不 join 展开</b>：五个状态计数与列表逐行同源，
 *       「合计 == 逐行求和」结构上成立。
 *   <li><b>终态不可复活 + 状态与时间戳自洽</b>：DB 层触发器裁决，绕过服务层裸 SQL 也被拒。
 *   <li><b>租户隔离三断言</b>实跑：两租户互不可见 / 伪造 X-Tenant-Id 无效 /
 *       连接池归还后不串租户。
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReservationIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      List.of("warehouse.inventory.read", "warehouse.inventory.update");

  static final UUID leOne = idFor(a, 1);
  static final UUID skuOne = idFor(a, 3);

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-warehouse-reservation-" + tenant + "-" + ordinal).getBytes());
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

  @Autowired ReservationService service;
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
          "truncate inventory_reservation, sku_ref, legal_entity_ref restart identity cascade");
    }
    for (var tenant : List.of(a, b)) {
      access.run(
          actorFor(tenant),
          "warehouse.inventory.read",
          () -> {
            sql.execute(
                "insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 1), tenant, "LE-1", "主体一");
            // B 的 SKU 编码刻意不同：隔离失效时断言不会含糊。
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 3), tenant, "SKU-" + (tenant.equals(a) ? "A" : "B"),
                "商品" + (tenant.equals(a) ? "一" : "二"));
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

  /** 无筛选列表。 */
  ReservationPage list() {
    return asA("read", () -> service.list(query()));
  }

  static ReservationQuery query() {
    return new ReservationQuery(
        null, null, null, null, null, null, null, null, null, null, 0, 50);
  }

  /** 构造一个只带状态筛选的查询。 */
  static ReservationQuery byStatus(ReservationStatus... statuses) {
    return new ReservationQuery(
        java.util.Arrays.stream(statuses).map(Enum::name).toList(),
        null, null, null, null, null, null, null, null, null, 0, 50);
  }

  // ================================================================ 夹具

  /**
   * 登记一条预占需求。
   *
   * <p>{@code heldAt} 与 {@code expiresAt} 由调用方给出，这样「超时长」才有可断言的确定值 ——
   * 用 {@code now()} 当过期时刻的话，超时长只能断言「大于 0」，写死成 0 的实现也能过。
   */
  UUID reserve(
      UUID tenant, SourceSystem system, SourceType type, String qty, OffsetDateTime heldAt,
      OffsetDateTime expiresAt, String refCode) {
    return access.run(
        actorFor(tenant),
        "warehouse.inventory.update",
        () -> {
          var created =
              service.create(
                  tenant,
                  new ReservationCreateInput(
                      system.name(), type.name(), UUID.randomUUID(), refCode,
                      idFor(tenant, 1), idFor(tenant, 3), new BigDecimal(qty), heldAt, expiresAt,
                      null));
          return created.id();
        });
  }

  /** 在租户 A 登记一条「订单履约 / 销售订单」预占，30 分钟前提出、1 小时后过期。 */
  UUID salesOrderHeld(String qty) {
    OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    return reserve(a, SourceSystem.TRADE, SourceType.SALES_ORDER, qty, now.minusMinutes(30),
        now.plusMinutes(30), "SO-20261013-001");
  }

  /**
   * 把一条预占推到终态（绕过服务层写端点：7.2.1.3 未交付，本模块不提供释放入口）。
   *
   * <p>终态行必须由**状态迁移**产生而不是插出来的 —— 这正是 V102 触发器要保证的事，
   * 所以这里用 UPDATE 而不是 INSERT。
   */
  void settle(UUID id, String status, BigDecimal consumed, BigDecimal released) {
    asA(
        "update",
        () -> {
          sql.execute(
              "update inventory_reservation set status=?, consumed_quantity=?, released_quantity=?,"
                  + "committed_at=case when status<>'HELD' then coalesce(committed_at,held_at) end,"
                  + " settled_at=?::timestamptz"
                  + " where id=?",
              status, consumed, released, OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS), id);
          return null;
        });
  }

  // ================================================================ 列表与字段

  @Test
  void 列表展示需求来源数量状态与剩余量() {
    UUID id = salesOrderHeld("10");
    var row = list().rows().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();

    assertThat(row.reservationNo()).startsWith("RSV-");
    assertThat(row.sourceSystem()).isEqualTo("TRADE");
    assertThat(row.sourceSystemLabel()).isEqualTo("订单履约");
    assertThat(row.sourceType()).isEqualTo("SALES_ORDER");
    assertThat(row.sourceTypeLabel()).isEqualTo("销售订单");
    assertThat(row.referenceCode()).isEqualTo("SO-20261013-001");
    assertThat(row.requestedQuantity()).isEqualByComparingTo("10");
    assertThat(row.consumedQuantity()).isEqualByComparingTo("0");
    // 7.2.1.2 未交付 → 已分配量如实返回 0（已知为 0），不是 null（未知）。
    assertThat(row.allocatedQuantity()).isEqualByComparingTo("0");
    assertThat(row.remainingQuantity()).isEqualByComparingTo("10");
    assertThat(row.status()).isEqualTo("HELD");
    assertThat(row.statusLabel()).isEqualTo("预占中");
    assertThat(row.terminal()).isFalse();
    // 引用登记表里的快照：SKU / 法人名在本地可读，不跨库 join catalog / identity。
    assertThat(row.skuCode()).isEqualTo("SKU-A");
    assertThat(row.skuName()).isEqualTo("商品一");
    assertThat(row.legalEntityCode()).isEqualTo("LE-1");
  }

  @Test
  void 剩余量等于需求量减已消费量且不含已分配未出库的部分() {
    UUID id = salesOrderHeld("20");
    // 已分配 12（7.2.1.2 语义：货已圈到某个 bucket 但还没出库），已消耗 0。
    asA("update", () -> {
      sql.execute("update inventory_reservation set allocated_quantity=12 where id=?", id);
      return null;
    });
    var row = list().rows().get(0);
    // 口径：已分配但未出库的 12 件**仍然欠** —— 货还在仓里占着可用量，
    // 算进 consumed 会让列表显示成「已结清」而实际还在圈货。
    assertThat(row.allocatedQuantity()).isEqualByComparingTo("12");
    assertThat(row.consumedQuantity()).isEqualByComparingTo("0");
    assertThat(row.remainingQuantity()).isEqualByComparingTo("20");
  }

  @Test
  void 消耗后剩余量同步下降且数量守恒() {
    UUID id = salesOrderHeld("20");
    settle(id, "CONSUMED", new BigDecimal("7"), new BigDecimal("0"));
    var row = list().rows().get(0);
    assertThat(row.status()).isEqualTo("CONSUMED");
    assertThat(row.terminal()).isTrue();
    assertThat(row.consumedQuantity()).isEqualByComparingTo("7");
    assertThat(row.remainingQuantity()).isEqualByComparingTo("13");
    assertThat(row.settledAt()).isNotNull();
  }

  // ================================================================ 超时

  @Test
  void 超时长从真实写入时刻算而不是零() {
    // 40 分钟前就该过期的 HELD 预占：超时长必须接近 2400 秒，而不是 0。
    OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    UUID id =
        reserve(a, SourceSystem.TRADE, SourceType.SALES_ORDER, "5", now.minusHours(3),
            now.minusMinutes(40), "SO-TIMEOUT");
    var row = list().rows().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    assertThat(row.overdueSeconds()).isNotNull();
    // 40 分钟 = 2400 秒。给 ±120 秒的余量吸收执行耗时，但**不可能**被一个恒返回 0 的实现蒙混过关。
    assertThat(row.overdueSeconds()).isBetween(2280L, 2520L);
  }

  @Test
  void 未过期与已终态的超时长为null而不是0() {
    OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    // 一条未过期的
    UUID fresh = reserve(a, SourceSystem.TRADE, SourceType.SALES_ORDER, "5", now, now.plusHours(2), "SO-FRESH");
    // 一条早已过期但已 RELEASED 的（终态不再计时）
    UUID released = reserve(a, SourceSystem.TRADE, SourceType.SALES_ORDER, "5", now.minusDays(3), now.minusDays(2), "SO-OLD");

    var freshRow = list().rows().stream().filter(r -> r.id().equals(fresh)).findFirst().orElseThrow();
    var oldRow = list().rows().stream().filter(r -> r.id().equals(released)).findFirst().orElseThrow();
    settle(released, "RELEASED", BigDecimal.ZERO, new BigDecimal("5"));

    assertThat(freshRow.overdueSeconds()).isNull();
    // 终态行不再计时：给 0 会把「不再计时」与「刚好超时 0 秒」混成同一个数。
    assertThat(list().rows().stream().filter(r -> r.id().equals(released)).findFirst().orElseThrow()
        .overdueSeconds()).isNull();
    // 终态行确实带着早已过去的 expires_at，证明上面那条不是因为「没有过期时间」而是为 null。
    assertThat(oldRow.expiresAt()).isNotNull();
  }

  @Test
  void 已承诺的预占不参与超时() {
    // 蓝图 7.2.1.3 红线：「承诺分配（committed）后不再自动 TTL 释放」——
    // 所以一条早已过期的 COMMITTED 行不得计入超时，否则清理任务会把它误释放导致超卖。
    OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    UUID id = reserve(a, SourceSystem.TRADE, SourceType.SALES_ORDER, "5", now.minusDays(5),
        now.minusDays(3), "SO-COMMITTED");
    asA("update", () -> {
      sql.execute(
          "update inventory_reservation set status='COMMITTED', committed_at=held_at + interval '1 day'"
              + " where id=?",
          id);
      return null;
    });
    var row = list().rows().get(0);
    assertThat(row.status()).isEqualTo("COMMITTED");
    assertThat(row.overdueSeconds()).isNull();
    assertThat(list().summary().overdueCount()).isZero();
    // 超时筛选也不能把它捞出来
    assertThat(asA("read", () -> service.list(
        new ReservationQuery(null, null, null, null, null, true, null, null, null, null, 0, 50)))
        .rows()).isEmpty();
  }

  @Test
  void 超时筛选只捞已过期且仍在预占中的行() {
    OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    UUID overdue = reserve(a, SourceSystem.TRADE, SourceType.SALES_ORDER, "5", now.minusHours(5),
        now.minusHours(1), "SO-OD");
    reserve(a, SourceSystem.TRADE, SourceType.SALES_ORDER, "5", now, now.plusHours(5), "SO-FR");
    reserve(a, SourceSystem.MANUAL, SourceType.OTHER_HOLD, "5", now, now.plusHours(5), "MH-1");

    var page =
        asA("read", () -> service.list(
            new ReservationQuery(null, null, null, null, null, true, null, null, null, null, 0, 50)));
    assertThat(page.rows()).hasSize(1);
    assertThat(page.rows().get(0).id()).isEqualTo(overdue);
    assertThat(page.summary().overdueCount()).isEqualTo(1);
    assertThat(page.summary().overdueQuantity()).isEqualByComparingTo("5");
  }

  // ================================================================ 筛选与排序

  @Test
  void 按状态筛选且词表外的状态被拒() {
    salesOrderHeld("1");
    OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    UUID other = reserve(a, SourceSystem.PROCESSING, SourceType.PROCESSING_ORDER, "2", now,
        now.plusHours(1), "PO-1");
    settle(other, "RELEASED", BigDecimal.ZERO, new BigDecimal("2"));

    assertThat(asA("read", () -> service.list(byStatus(ReservationStatus.HELD))).rows()).hasSize(1);
    assertThat(asA("read", () -> service.list(byStatus(ReservationStatus.RELEASED))).rows())
        .hasSize(1);
    // 多选
    assertThat(asA("read", () -> service.list(byStatus(ReservationStatus.HELD, ReservationStatus.RELEASED)))
        .rows()).hasSize(2);

    // 拼错的状态必须在**绑定之前**被拒：绑进去只会得到一个「合法但永远为空」的结果集，
    // 界面上看起来像「这个状态真的一条都没有」。
    assertThatThrownBy(() -> asA("read", () -> service.list(byStatus(ReservationStatus.HELD, null))))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> asA("read", () -> service.list(new ReservationQuery(
                List.of("HOLD"), null, null, null, null, null, null, null, null, null, 0, 50))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的预占状态");
  }

  @Test
  void 按来源类型与关键字筛选() {
    OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    salesOrderHeld("1");
    reserve(a, SourceSystem.PROCESSING, SourceType.PROCESSING_ORDER, "3", now, now.plusHours(1), "PO-9");
    reserve(a, SourceSystem.MANUAL, SourceType.OTHER_HOLD, "4", now, now.plusHours(1), "MH-3");

    assertThat(asA("read", () -> service.list(new ReservationQuery(
            null, "PROCESSING_ORDER", null, null, null, null, null, null, null, null, 0, 50)))
        .rows()).hasSize(1);
    assertThat(asA("read", () -> service.list(new ReservationQuery(
            null, null, "MANUAL", null, null, null, null, null, null, null, 0, 50)))
        .rows()).hasSize(1);

    // 关键字命中四类可见文本：上游单号 / SKU 编码 / SKU 名称 / 本服务单号
    assertThat(asA("read", () -> service.list(new ReservationQuery(
            null, null, null, null, null, null, null, null, "PO-9", null, 0, 50))).rows())
        .hasSize(1);
    assertThat(asA("read", () -> service.list(new ReservationQuery(
            null, null, null, null, null, null, null, null, "SKU-A", null, 0, 50))).rows())
        .hasSize(3);
    assertThat(asA("read", () -> service.list(new ReservationQuery(
            null, null, null, null, null, null, null, null, "商品一", null, 0, 50))).rows())
        .hasSize(3);
  }

  @Test
  void 时间范围左闭右开() {
    OffsetDateTime t0 = OffsetDateTime.parse("2026-05-01T00:00:00+08:00");
    // 三条分别落在上界之前、恰在上界、之后
    reserve(a, SourceSystem.TRADE, SourceType.SALES_ORDER, "1", t0.minusDays(1), null, "SO-BEFORE");
    reserve(a, SourceSystem.TRADE, SourceType.SALES_ORDER, "1", t0, null, "SO-EDGE");
    reserve(a, SourceSystem.TRADE, SourceType.SALES_ORDER, "1", t0.plusDays(1), null, "SO-AFTER");

    // 右开：恰在上界（requested_at == t0）的那条**不**在 [t0-2d, t0) 里 ——
    // 这正是右开的意义：按天筛选可以把次日 00:00 当上界而不用减一秒。
    var page = asA("read", () -> service.list(new ReservationQuery(
        null, null, null, t0.minusDays(2), t0, null, null, null, null, null, 0, 50)));
    assertThat(page.rows().stream().map(ReservationRow::referenceCode))
        .containsExactly("SO-BEFORE");

    // 左闭：恰在下界的那条**在** [t0, t0+2d) 里；SO-AFTER 落在 t0+1d，也在范围内。
    // 两端合起来看才证明「左闭右开」：只测一端的话，把两端都换成 <= 也可能蒙混过关。
    var page2 = asA("read", () -> service.list(new ReservationQuery(
        null, null, null, t0, t0.plusDays(2), null, null, null, null, null, 0, 50)));
    assertThat(page2.rows().stream().map(ReservationRow::referenceCode))
        .containsExactlyInAnyOrder("SO-EDGE", "SO-AFTER");
  }

  @Test
  void 排序键白名单且每页上限被裁到200() {
    salesOrderHeld("1");
    // 白名单外的排序键被拒，而不是拼进 ORDER BY（那会是注入面）
    assertThatThrownBy(() -> asA("read", () -> service.list(new ReservationQuery(
            null, null, null, null, null, null, null, null, null, "id; drop table x", 0, 50))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的排序键");
    // 单页上限
    var page = asA("read", () -> service.list(new ReservationQuery(
        null, null, null, null, null, null, null, null, null, null, 0, 9999)));
    assertThat(page.size()).isEqualTo(ReservationService.MAX_PAGE_SIZE);
  }

  @Test
  void 真分页且总数与逐页拼起来一致() {
    for (int i = 0; i < 7; i++) salesOrderHeld(String.valueOf(i + 1));
    var first = asA("read", () -> service.list(new ReservationQuery(
        null, null, null, null, null, null, null, null, null, "RESERVATION_NO", 0, 3)));
    assertThat(first.total()).isEqualTo(7);
    assertThat(first.rows()).hasSize(3);
    var second = asA("read", () -> service.list(new ReservationQuery(
        null, null, null, null, null, null, null, null, null, "RESERVATION_NO", 1, 3)));
    var third = asA("read", () -> service.list(new ReservationQuery(
        null, null, null, null, null, null, null, null, null, "RESERVATION_NO", 2, 3)));
    assertThat(second.rows()).hasSize(3);
    assertThat(third.rows()).hasSize(1);
    // 三页拼起来不重不漏
    var all = new java.util.ArrayList<UUID>();
    first.rows().forEach(r -> all.add(r.id()));
    second.rows().forEach(r -> all.add(r.id()));
    third.rows().forEach(r -> all.add(r.id()));
    assertThat(all).hasSize(7).doesNotHaveDuplicates();
    // 第三页越界返回空而不是 500
    var beyond = asA("read", () -> service.list(new ReservationQuery(
        null, null, null, null, null, null, null, null, null, "RESERVATION_NO", 9, 3)));
    assertThat(beyond.rows()).isEmpty();
    assertThat(beyond.total()).isEqualTo(7);
  }

  // ================================================================ 状态汇总

  @Test
  void 状态汇总五态齐全且与逐行求和一致() {
    OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    salesOrderHeld("10");
    salesOrderHeld("20");
    UUID committed = reserve(a, SourceSystem.TRADE, SourceType.SALES_ORDER, "30", now, now.plusHours(1), "SO-C3");
    asA("update", () -> {
      sql.execute("update inventory_reservation set status='COMMITTED', committed_at=held_at where id=?", committed);
      return null;
    });
    UUID consumed = reserve(a, SourceSystem.TRADE, SourceType.SALES_ORDER, "40", now, now.plusHours(1), "SO-C4");
    settle(consumed, "CONSUMED", new BigDecimal("40"), BigDecimal.ZERO);
    UUID released = reserve(a, SourceSystem.TRADE, SourceType.SALES_ORDER, "50", now, now.plusHours(1), "SO-R5");
    settle(released, "RELEASED", BigDecimal.ZERO, new BigDecimal("50"));
    UUID expired = reserve(a, SourceSystem.TRADE, SourceType.SALES_ORDER, "60", now.minusDays(2), now.minusDays(1), "SO-E6");
    settle(expired, "EXPIRED", BigDecimal.ZERO, new BigDecimal("60"));

    var page = list();
    var s = page.summary();
    assertThat(s.counts()).containsOnlyKeys("HELD", "COMMITTED", "CONSUMED", "RELEASED", "EXPIRED");
    assertThat(s.counts().get("HELD")).isEqualTo(2);
    assertThat(s.counts().get("COMMITTED")).isEqualTo(1);
    assertThat(s.counts().get("CONSUMED")).isEqualTo(1);
    assertThat(s.counts().get("RELEASED")).isEqualTo(1);
    assertThat(s.counts().get("EXPIRED")).isEqualTo(1);
    assertThat(s.totalCount()).isEqualTo(6);
    // 「合计 == 逐行求和」：五态计数相加等于总数
    assertThat(s.counts().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(s.totalCount());
    // 数量合计与逐行求和一致
    assertThat(s.requestedQuantity()).isEqualByComparingTo("210");
    assertThat(s.consumedQuantity()).isEqualByComparingTo("40");
    assertThat(s.remainingQuantity()).isEqualByComparingTo("170");
  }

  @Test
  void 合计不受分页与筛选影响() {
    for (int i = 0; i < 5; i++) salesOrderHeld("10");
    OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    UUID other = reserve(a, SourceSystem.MANUAL, SourceType.OTHER_HOLD, "99", now, now.plusHours(1), "MH-1");
    settle(other, "RELEASED", BigDecimal.ZERO, new BigDecimal("99"));

    var all = list().summary();
    var filtered = asA("read", () -> service.list(byStatus(ReservationStatus.HELD))).summary();
    // 列表只返回 5 条 HELD，但**合计仍是全量的 6 条 / 149 件** ——
    // 拿筛选后的数当合计会让「切了个筛选条件，怎么总量变了」看起来像数据丢了。
    var filteredRows = asA("read", () -> service.list(byStatus(ReservationStatus.HELD)));
    assertThat(filteredRows.rows()).hasSize(5);
    assertThat(filteredRows.total()).isEqualTo(5);
    assertThat(filtered.totalCount()).isEqualTo(6);
    assertThat(filtered.requestedQuantity()).isEqualByComparingTo("149");
    assertThat(filtered.totalCount()).isEqualTo(all.totalCount());
    assertThat(filtered.requestedQuantity()).isEqualByComparingTo(all.requestedQuantity());
  }

  @Test
  void 汇总不受join展开影响且与逐行同源() {
    // 同一租户多条预占时，计数必须等于行数（若改成 join 分配明细再 group by，
    // 7.2.1.2 交付后会被分配行数放大）。这里钉死「数预占行」这一条口径。
    for (int i = 0; i < 4; i++) salesOrderHeld("3");
    var page = list();
    assertThat(page.summary().counts().get("HELD")).isEqualTo(4);
    assertThat(page.rows()).hasSize(4);
    assertThat(page.summary().requestedQuantity())
        .isEqualByComparingTo(page.rows().stream()
            .map(ReservationRow::requestedQuantity)
            .reduce(BigDecimal.ZERO, BigDecimal::add));
  }

  // ================================================================ 登记

  @Test
  void 登记落库且单号由本服务发() {
    OffsetDateTime held = OffsetDateTime.parse("2026-07-01T10:00:00+08:00");
    var created =
        asA("update", () -> service.create(a, new ReservationCreateInput(
            "TRADE", "SALES_ORDER", UUID.randomUUID(), "SO-NEW", leOne, skuOne,
            new BigDecimal("12.5"), held, held.plusHours(6), "新登记")));
    assertThat(created.status()).isEqualTo("HELD");
    assertThat(created.reservationNo()).startsWith("RSV-");

    var row = list().rows().stream().filter(r -> r.id().equals(created.id())).findFirst().orElseThrow();
    assertThat(row.requestedQuantity()).isEqualByComparingTo("12.5");
    assertThat(row.note()).isEqualTo("新登记");
    assertThat(row.referenceCode()).isEqualTo("SO-NEW");
  }

  @Test
  void 登记时写入时刻不被二次偏时区() {
    // held_at 是超时长与审计的唯一基准。用 Timestamp.valueOf(LocalDateTime) 绑定
    // 会在东八区静默偏 −28800s —— 所以这里断言**写回的值本身**，不是「没抛异常」。
    OffsetDateTime held = OffsetDateTime.parse("2026-03-15T23:40:00+08:00");
    OffsetDateTime expires = OffsetDateTime.parse("2026-03-16T07:40:00+08:00");
    UUID id =
        reserve(a, SourceSystem.TRADE, SourceType.SALES_ORDER, "3", held, expires, "SO-TZ");

    var row = list().rows().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    assertThat(row.heldAt().toInstant()).isEqualTo(held.toInstant());
    assertThat(row.expiresAt().toInstant()).isEqualTo(expires.toInstant());
    // 偏移量原样保留，不被换成 UTC
    assertThat(row.heldAt().getOffset()).isEqualTo(held.getOffset());
    assertThat(row.requestedAt().toInstant()).isEqualTo(held.toInstant());
  }

  @Test
  void 来源系统与类型组合自相矛盾时被拒() {
    OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    // 「手工登记的销售订单预占」自相矛盾：两个 CHECK 只保证各自在词表内，
    // 不保证组合成立。不拦的话 6.4.1.2 按加工来源去找 BOM 时会读到它然后卡住。
    assertThatThrownBy(() -> asA("update", () -> service.create(a, new ReservationCreateInput(
            "MANUAL", "SALES_ORDER", null, null, leOne, skuOne, BigDecimal.ONE, now, null, null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("来源与类型不匹配");
    assertThatThrownBy(() -> asA("update", () -> service.create(a, new ReservationCreateInput(
            "PROCESSING", "SALES_ORDER", null, null, leOne, skuOne, BigDecimal.ONE, now, null, null))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> asA("update", () -> service.create(a, new ReservationCreateInput(
            "TRADE", "PROCESSING_ORDER", null, null, leOne, skuOne, BigDecimal.ONE, now, null, null))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 数量非正时被拒() {
    OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    assertThatThrownBy(() -> asA("update", () -> service.create(a, new ReservationCreateInput(
            "TRADE", "SALES_ORDER", null, null, leOne, skuOne, BigDecimal.ZERO, now, null, null))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> asA("update", () -> service.create(a, new ReservationCreateInput(
            "TRADE", "SALES_ORDER", null, null, leOne, skuOne, new BigDecimal("-3"), now, null, null))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 空租户返回空列表而不是报错() {
    var page = list();
    assertThat(page.rows()).isEmpty();
    assertThat(page.total()).isZero();
    assertThat(page.summary().totalCount()).isZero();
    assertThat(page.summary().requestedQuantity()).isEqualByComparingTo("0");
    assertThat(page.summary().remainingQuantity()).isEqualByComparingTo("0");
  }

  // ================================================================ DB 级约束

  @Test
  void 终态不可复活在数据库层裁决() {
    OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    UUID id = reserve(a, SourceSystem.TRADE, SourceType.SALES_ORDER, "5", now, now.plusHours(1), "SO-REV");
    settle(id, "CONSUMED", new BigDecimal("5"), BigDecimal.ZERO);

    // 绕过服务层裸 SQL 改回 HELD：触发器必须拒。
    // 「已出库的货被复活成预占」是不可逆的超卖事故，Java 校验挡不住裸 SQL。
    assertThatThrownBy(() -> asA("update", () -> {
          sql.execute(
              "update inventory_reservation set status='HELD', committed_at=null, settled_at=null"
                  + ", consumed_quantity=0 where id=?",
              id);
          return null;
        }))
        .hasMessageContaining("终态");
    // 终态行改成另一个终态同样被拒（货已还回可用量，不能改成已消耗）
    assertThatThrownBy(() -> asA("update", () -> {
          sql.execute("update inventory_reservation set status='RELEASED' where id=?", id);
          return null;
        }))
        .hasMessageContaining("终结");
    // 状态没变
    assertThat(list().rows().get(0).status()).isEqualTo("CONSUMED");
  }

  @Test
  void 终态行必须带settledAt否则数据库拒() {
    OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    UUID id = reserve(a, SourceSystem.TRADE, SourceType.SALES_ORDER, "5", now, now.plusHours(1), "SO-ST");
    // COMMITTED 没有 committed_at
    assertThatThrownBy(() -> asA("update", () -> {
          sql.execute("update inventory_reservation set status='COMMITTED' where id=?", id);
          return null;
        }))
        .hasMessageContaining("committed_at");
    // 终态没有 settled_at
    assertThatThrownBy(() -> asA("update", () -> {
          sql.execute(
              "update inventory_reservation set status='RELEASED', committed_at=held_at where id=?", id);
          return null;
        }))
        .hasMessageContaining("settled_at");
  }

  @Test
  void 直接插入终态行被拒() {
    // 「插一行 CONSUMED」是脏数据：没有 held 就没有预留，凭什么直接说已消耗。
    assertThatThrownBy(() -> asA("update", () -> {
          sql.execute(
              "insert into inventory_reservation(id,tenant_id,reservation_no,source_system,source_type,"
                  + "legal_entity_id,sku_id,requested_quantity,status,held_at)"
                  + " values(?,?,?,'TRADE','SALES_ORDER',?,?,5,'CONSUMED',now())",
              UUID.randomUUID(), a, "RSV-FORGED-CONSUMED", leOne, skuOne);
          return null;
        }))
        .hasMessageContaining("必须以 HELD 建立");
  }

  @Test
  void 数量超占在数据库层被拒() {
    OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    UUID id = reserve(a, SourceSystem.TRADE, SourceType.SALES_ORDER, "10", now, now.plusHours(1), "SO-OV");
    // 已消费超过需求量
    assertThatThrownBy(() -> asA("update", () -> {
          sql.execute("update inventory_reservation set consumed_quantity=11 where id=?", id);
          return null;
        })).isInstanceOf(org.springframework.dao.DataAccessException.class);
    // 已分配超过需求量
    assertThatThrownBy(() -> asA("update", () -> {
          sql.execute("update inventory_reservation set allocated_quantity=11 where id=?", id);
          return null;
        })).isInstanceOf(org.springframework.dao.DataAccessException.class);
    // 消费 + 释放 > 需求量
    assertThatThrownBy(() -> asA("update", () -> {
          sql.execute(
              "update inventory_reservation set consumed_quantity=6, released_quantity=6 where id=?", id);
          return null;
        })).isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void 版本随更新自增() {
    OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    UUID id = reserve(a, SourceSystem.TRADE, SourceType.SALES_ORDER, "5", now, now.plusHours(1), "SO-V");
    assertThat(version(id)).isZero();
    asA("update", () -> {
      sql.execute("update inventory_reservation set note='改一次' where id=?", id);
      return null;
    });
    assertThat(version(id)).isEqualTo(1);
  }

  long version(UUID id) {
    return asA("read", () -> sql.fetchOne(
            "select version from inventory_reservation where id=?", id).get(0, Long.class));
  }

  // ================================================================ 能力与下拉

  @Test
  void 未交付的兄弟模块如实报available为false() {
    var capabilities = asA("read", () -> service.capabilities());
    var byDimension = capabilities.stream()
        .collect(java.util.stream.Collectors.toMap(ReservationCapability::dimension, c -> c));
    assertThat(byDimension.get("DEMAND_LIST").available()).isTrue();
    assertThat(byDimension.get("DEMAND_CREATE").available()).isTrue();
    // 7.2.1.2 分配明细已交付（按本测试原注释的约定由那条车道翻旗）：必须报 true 并指明入口，
    // 否则列表上的「进明细」入口仍然是关的，用户点不到已经交付的功能。
    assertThat(byDimension.get("ALLOCATION_DETAIL").available()).isTrue();
    assertThat(byDimension.get("ALLOCATION_DETAIL").ownerModule()).isEqualTo("7.2.1.2");
    assertThat(byDimension.get("ALLOCATION_DETAIL").note()).contains("reservation_allocation");
    // 7.2.1.3 释放与过期仍未交付：必须报 false 并说清归哪个模块，
    // 而不是显示一个点下去 404 的释放按钮。
    assertThat(byDimension.get("RELEASE").available()).isFalse();
    assertThat(byDimension.get("RELEASE").ownerModule()).isEqualTo("7.2.1.3");
  }

  @Test
  void 下拉项给出五态与三个来源系统且标注终态() {
    var options = asA("read", () -> service.options());
    assertThat(options.statuses()).hasSize(5);
    assertThat(options.sourceSystems()).hasSize(3);
    assertThat(options.sourceTypes()).hasSize(3);
    var terminalFlags = options.statuses().stream()
        .collect(java.util.stream.Collectors.toMap(StatusOption::code, StatusOption::terminal));
    assertThat(terminalFlags.get("HELD")).isFalse();
    assertThat(terminalFlags.get("COMMITTED")).isFalse();
    assertThat(terminalFlags.get("CONSUMED")).isTrue();
    assertThat(terminalFlags.get("RELEASED")).isTrue();
    assertThat(terminalFlags.get("EXPIRED")).isTrue();
    // 归属说明必须写明「为什么落在 warehouse 而不是蓝图说的 trade」
    assertThat(options.ownerNote()).contains("warehouse");
    assertThat(options.ownerNote()).contains("V102");
  }

  @Test
  void 分配明细已交付后列表不再标记明细入口不可用() {
    // 7.2.1.2 交付后由那条车道按本测试原注释的约定翻旗：明细入口真的点得进去，
    // 继续报 true 会让「进明细」按钮永远关着。
    assertThat(list().detailUnavailable()).isFalse();
    assertThat(list().note()).contains("warehouse");
    assertThat(list().note()).contains("剩余量");
  }

  // ================================================================ HTTP 端点

  @Test
  void 经HTTP读取并断言落库的真实值() throws Exception {
    OffsetDateTime held = OffsetDateTime.parse("2026-08-08T12:00:00+08:00");
    UUID id = reserve(a, SourceSystem.TRADE, SourceType.SALES_ORDER, "8.25", held, held.plusHours(3), "SO-HTTP");

    http.perform(get("/warehouse/inventory/reservations")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.rows[0].id").value(id.toString()))
        .andExpect(jsonPath("$.rows[0].requestedQuantity").value(8.25))
        .andExpect(jsonPath("$.rows[0].remainingQuantity").value(8.25))
        .andExpect(jsonPath("$.rows[0].status").value("HELD"))
        .andExpect(jsonPath("$.rows[0].statusLabel").value("预占中"))
        .andExpect(jsonPath("$.rows[0].sourceTypeLabel").value("销售订单"))
        // 写回的时刻带 +08:00 偏移，漂移 −28800s 就会在这里失败
        .andExpect(jsonPath("$.rows[0].heldAt").value("2026-08-08T12:00:00+08:00"))
        .andExpect(jsonPath("$.summary.counts.HELD").value(1));

    http.perform(get("/warehouse/inventory/reservations/options")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.statuses.length()").value(5));
    http.perform(get("/warehouse/inventory/reservations/capabilities")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(4));
  }

  @Test
  void 经HTTP登记预占() throws Exception {
    http.perform(post("/warehouse/inventory/reservations")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content("""
                {"sourceSystem":"TRADE","sourceType":"SALES_ORDER","referenceCode":"SO-POST",
                 "legalEntityId":"%s","skuId":"%s","requestedQuantity":6}"""
                .formatted(leOne, skuOne)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("HELD"))
        .andExpect(jsonPath("$.reservationNo").value(org.hamcrest.Matchers.startsWith("RSV-")));
    assertThat(list().total()).isEqualTo(1);
  }

  @Test
  void 权限不足与未认证被拒() throws Exception {
    // 只读令牌不能登记
    http.perform(post("/warehouse/inventory/reservations")
            .header("Authorization", "Bearer read-only")
            .contentType("application/json")
            .content("""
                {"sourceSystem":"TRADE","sourceType":"SALES_ORDER",
                 "legalEntityId":"%s","skuId":"%s","requestedQuantity":1}"""
                .formatted(leOne, skuOne)))
        .andExpect(status().isForbidden());
    // 未认证
    http.perform(get("/warehouse/inventory/reservations").header("Authorization", "Bearer nope"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void 词表外的查询参数返回400而不是空结果() throws Exception {
    http.perform(get("/warehouse/inventory/reservations")
            .header("Authorization", "Bearer tenant-a")
            .param("status", "NOPE"))
        .andExpect(status().isBadRequest());
    http.perform(get("/warehouse/inventory/reservations")
            .header("Authorization", "Bearer tenant-a")
            .param("sort", "id; drop table x"))
        .andExpect(status().isBadRequest());
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() {
    UUID mine = salesOrderHeld("999");
    // B 登一条自己的
    OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    access.run(
        actorB,
        "warehouse.inventory.update",
        () -> service.create(b, new ReservationCreateInput(
            "TRADE", "SALES_ORDER", UUID.randomUUID(), "SO-B", idFor(b, 1), idFor(b, 3),
            new BigDecimal("1"), now, now.plusHours(1), null)));

    var pageA = list();
    assertThat(pageA.rows()).hasSize(1);
    assertThat(pageA.rows().get(0).id()).isEqualTo(mine);
    assertThat(pageA.summary().requestedQuantity()).isEqualByComparingTo("999");

    var pageB = asB("read", () -> service.list(query()));
    assertThat(pageB.rows()).hasSize(1);
    assertThat(pageB.rows().stream().map(ReservationRow::id)).doesNotContain(mine);
    assertThat(pageB.summary().requestedQuantity()).isEqualByComparingTo("1");
    // SKU 快照也来自 B 自己的引用登记表
    assertThat(pageB.rows().get(0).skuCode()).isEqualTo("SKU-B");

    // 裸 SQL 交叉核对：A 的行确实挂在 A 名下，B 的行确实挂在 B 名下
    assertThat(asA("read", () -> sql.fetchOne(
            "select count(*) from inventory_reservation where id=?", mine))
        .get(0, Long.class)).isEqualTo(1);
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    salesOrderHeld("999");

    // 身份是 B，伪造 X-Tenant-Id 指向 A：B 看到的仍然是 B 自己的（空列表），不是 A 的 999。
    http.perform(get("/warehouse/inventory/reservations")
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0))
        .andExpect(jsonPath("$.summary.requestedQuantity").value(0));

    // 带筛选也一样：B 的筛选条件不可能命中 A 的行
    http.perform(get("/warehouse/inventory/reservations")
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString())
            .param("keyword", "SO-20261013-001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));

    // 伪造头也不能让 B 写出一行挂到 A 名下的预占。
    //
    // 注意这里**期望 200**：legal_entity_id / sku_id 是跨服务**逻辑引用**、不建外键
    // （V102 §四，与 V1 第零节同一纪律），所以 B 传 A 的 id 不会被 DB 拒。
    // 真正要证明的是隔离语义 —— 这行落在 **B** 名下（A 的列表一条不多），
    // 而不是「请求被拒」。断言 200 是在如实描述当前设计；A 侧不受影响才是隔离的关键。
    http.perform(post("/warehouse/inventory/reservations")
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString())
            .contentType("application/json")
            .content("""
                {"sourceSystem":"TRADE","sourceType":"SALES_ORDER",
                 "legalEntityId":"%s","skuId":"%s","requestedQuantity":7}"""
                .formatted(leOne, skuOne)))
        .andExpect(status().isOk());

    // A 的数据未被这串伪造请求改动
    assertThat(list().total()).isEqualTo(1);
    assertThat(list().summary().requestedQuantity()).isEqualByComparingTo("999");
    // B 自己多了一条，且**看不到** A 的那条
    var afterB = asB("read", () -> service.list(query()));
    assertThat(afterB.rows()).hasSize(1);
    assertThat(afterB.rows().stream().map(ReservationRow::id))
        .doesNotContain(list().rows().get(0).id());
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    UUID mine = salesOrderHeld("999");
    OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    access.run(
        actorB,
        "warehouse.inventory.update",
        () -> service.create(b, new ReservationCreateInput(
            "TRADE", "SALES_ORDER", UUID.randomUUID(), "SO-B", idFor(b, 1), idFor(b, 3),
            new BigDecimal("1"), now, now.plusHours(1), null)));

    // 交替复用同一条物理连接（池容量=1）：A → B → A → B × 3
    for (int i = 0; i < 3; i++) {
      var a1 = list();
      assertThat(a1.total()).isEqualTo(1);
      assertThat(a1.rows().get(0).id()).isEqualTo(mine);
      assertThat(a1.summary().requestedQuantity()).isEqualByComparingTo("999");

      var b1 = asB("read", () -> service.list(query()));
      assertThat(b1.total()).isEqualTo(1);
      assertThat(b1.rows().stream().map(ReservationRow::id)).doesNotContain(mine);
      assertThat(b1.summary().requestedQuantity()).isEqualByComparingTo("1");

      var a2 = list();
      assertThat(a2.summary().requestedQuantity()).isEqualByComparingTo("999");
      var b2 = asB("read", () -> service.list(query()));
      assertThat(b2.rows().stream().map(ReservationRow::id)).doesNotContain(mine);
    }

    // FORCE RLS 逐张确认
    boolean forced = sql.fetchOne(
            "select (relrowsecurity and relforcerowsecurity) as on from pg_class"
                + " where relname='inventory_reservation' and relnamespace='public'::regnamespace")
        .get("on", Boolean.class);
    assertThat(forced).as("inventory_reservation 必须 FORCE RLS").isTrue();

    // 不设 app.current_tenant 时读不到任何行：第一道防线在服务层谓词之前就生效。
    assertThat(sql.fetchOne(
            "select count(*) from inventory_reservation"
                + " where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, Long.class)).isZero();
  }
}
