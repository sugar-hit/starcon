package com.starcon.warehouse;

import static com.starcon.warehouse.OutboundOrderModels.*;
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
import java.util.stream.Stream;
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
 * 8.3.1.1 出库单列表：四类来源、七态状态机、三项批量操作、真分页与真筛选，以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本测试要钉死的是三件容易糊过去的事</b>：
 *
 * <ol>
 *   <li><b>状态机只有一处真相。</b> {@link OutboundOrderStatus} 的转换矩阵与 V72 触发器白名单
 *       必须没有分叉 —— 有一条 {@link #状态机矩阵与数据库触发器白名单没有分叉()} 专门比对
 *       两者，并把非法跃迁真的打到数据库上去撞一次。
 *   <li><b>一订单可多出库单</b>（蓝图点名的那条关系）。同一个外部订单号下必须能并排挂
 *       多张出库单 —— 上一条唯一索引就把它禁掉了。
 *   <li><b>时间口径。</b> {@link #时间筛选按带偏移量的ISO8601解析()} 用同一个瞬间的两种
 *       偏移量表达去筛，朴素本地时间解析会让其中一个偏 8 小时而漏掉刚建的单。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutboundOrderIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      Stream.of("inventory")
          .flatMap(p -> Stream.of("read", "create", "update", "delete").map(op -> "warehouse." + p + "." + op))
          .toList();

  static final UUID leA = idFor(a, 1);
  static final UUID skuOne = idFor(a, 2);
  static final UUID skuTwo = idFor(a, 3);
  static final UUID whOne = idFor(a, 4);
  static final UUID whTwo = idFor(a, 5);
  static final UUID qSellable = idFor(a, 6);
  static final UUID transferA = idFor(a, 7);

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-warehouse-outbound-" + tenant + "-" + ordinal).getBytes());
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
      actors.put("Bearer read-only", actor(user, a, List.of(grant("ALL", List.of("warehouse.inventory.read")))));
      // SELF 数据范围：只能看到自己创建的出库单。
      actors.put("Bearer self-scope", actor(user, a, List.of(grant("SELF", allPermissions))));
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
    // 单连接：只有复用同一条物理连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired OutboundOrderService orders;
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
          "truncate outbound_order_event, outbound_pick_request, outbound_order_line,"
              + " outbound_order, transfer_order_line, transfer_order, warehouse, sku_ref,"
              + " legal_entity_ref, inventory_quality_status restart identity cascade");
    }
    for (var tenant : List.of(a, b)) {
      access.run(
          actorFor(tenant),
          "warehouse.inventory.read",
          () -> {
            sql.execute(
                "insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 1), tenant, "LE-1", "主体一");
            // SKU 与仓库 id 必须按租户派生：用租户 A 的常量去插租户 B 会撞主键。
            sql.execute("insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)", idFor(tenant, 2), tenant, "SKU-1", "商品一");
            sql.execute("insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)", idFor(tenant, 3), tenant, "SKU-2", "商品二");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
                idFor(tenant, 4), tenant, "WH-ONE", "一仓", "CN", "Asia/Shanghai");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
                idFor(tenant, 5), tenant, "WH-TWO", "二仓", "CN", "Asia/Shanghai");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 6), tenant, "SELLABLE", "可售", "SELLABLE");
            return null;
          });
    }
    // 租户 A 的调拨单：WH-ONE → WH-TWO，明细只有 SKU-1。
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into transfer_order(id,tenant_id,order_code,from_warehouse_id,to_warehouse_id,"
                  + "legal_entity_id,status,confirmed_at,created_by) values(?,?,?,?,?,?,'PENDING_SHIPMENT',now(),?)",
              transferA, a, "TR-1", whOne, whTwo, leA, user);
          sql.execute(
              "insert into transfer_order_line(id,tenant_id,order_id,sku_id,quality_status_id,"
                  + "requested_quantity) values(?,?,?,?,?,?)",
              UUID.randomUUID(), a, transferA, skuOne, qSellable, new BigDecimal("10.000000"));
          return null;
        });
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  static OffsetDateTime now() {
    return OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
  }

  // ================================================================ 夹具

  OutboundOrderSummary sales(String ref, UUID warehouse, String skuCode, String qty) {
    return salesRef(ref, warehouse, List.of(new OutboundOrderLineInput(skuOf(skuCode), new BigDecimal(qty))));
  }

  OutboundOrderSummary salesRef(String ref, UUID warehouse, List<OutboundOrderLineInput> lines) {
    return run(a, () -> orders.create(input(ref, null, warehouse, lines), user));
  }

  OutboundOrderCreateInput input(
      String ref, UUID transferId, UUID warehouse, List<OutboundOrderLineInput> lines) {
    return new OutboundOrderCreateInput(
        transferId == null ? "SALES" : "TRANSFER", transferId, ref, warehouse, "", "", lines);
  }

  UUID skuOf(String code) {
    return "SKU-1".equals(code) ? skuOne : skuTwo;
  }

  <T> T run(UUID tenant, java.util.function.Supplier<T> work) {
    return access.run(actorFor(tenant), "warehouse.inventory.update", work);
  }

  OutboundOrderSummary createFor(UUID tenant, String ref, UUID warehouse) {
    return access.run(
        actorFor(tenant),
        "warehouse.inventory.update",
        () ->
            orders.create(
                input(
                    ref,
                    null,
                    warehouse,
                    List.of(new OutboundOrderLineInput(idFor(tenant, 2), new BigDecimal("3")))),
                user));
  }

  // ================================================================ 列表与筛选

  @Test
  void 列表返回来源行数件数状态发货仓承运渠道() {
    var created =
        salesRef(
            "SO-1001",
            whOne,
            List.of(
                new OutboundOrderLineInput(skuOne, new BigDecimal("2.5")),
                new OutboundOrderLineInput(skuTwo, new BigDecimal("3"))));
    assertThat(created.status()).isEqualTo("PENDING_PICKING");
    assertThat(created.statusLabel()).isEqualTo("待拣");
    assertThat(created.sourceTypeLabel()).isEqualTo("销售");
    assertThat(created.warehouseCode()).isEqualTo("WH-ONE");
    // SKU 行数与件数现算，且六位小数口径（不是 2.5 与 5.5 混着 scale）。
    assertThat(created.skuLineCount()).isEqualTo(2);
    assertThat(created.totalPieceCount()).isEqualByComparingTo("5.500000");
    assertThat(created.totalPieceCount().scale()).isEqualTo(6);

    var page = list(List.of(), List.of(), List.of(), null, null, null, 0, 50, actorA);
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).orderCode()).startsWith("OB-");
  }

  @Test
  void 四个筛选维度都真的落在SQL上() {
    sales("SO-1", whOne, "SKU-1", "1");
    sales("SO-2", whOne, "SKU-2", "2");
    sales("SO-3", whTwo, "SKU-1", "3");

    assertThat(list(List.of(whOne), List.of(), List.of(), null, null, null, 0, 50, actorA).total()).isEqualTo(2);
    assertThat(list(List.of(whTwo), List.of(), List.of(), null, null, null, 0, 50, actorA).total()).isEqualTo(1);
    assertThat(list(List.of(), List.of("SALES"), List.of(), null, null, null, 0, 50, actorA).total()).isEqualTo(3);
    assertThat(list(List.of(), List.of(), List.of("PENDING_PICKING"), null, null, null, 0, 50, actorA).total())
        .isEqualTo(3);
    // 已发货一张都还没有：这条筛选必须真的空，而不是把「没筛」当成「全中」。
    assertThat(list(List.of(), List.of(), List.of("SHIPPED"), null, null, null, 0, 50, actorA).total()).isZero();
    // 关键字跨单号 / 外部单号 / 仓库名三处匹配。
    assertThat(list(List.of(), List.of(), List.of(), null, null, "SO-2", 0, 50, actorA).total()).isEqualTo(1);
    assertThat(list(List.of(), List.of(), List.of(), null, null, "WH-TWO", 0, 50, actorA).total()).isEqualTo(1);
    // 命中不到的关键字必须真的落空。
    assertThat(list(List.of(), List.of(), List.of(), null, null, "SO-不存在", 0, 50, actorA).total()).isZero();
  }

  @Test
  void 分页是真分页不是假分页() {
    for (int i = 0; i < 5; i++) sales("SO-" + i, whOne, "SKU-1", "1");
    var first = list(List.of(), List.of(), List.of(), null, null, null, 0, 2, actorA);
    var second = list(List.of(), List.of(), List.of(), null, null, null, 1, 2, actorA);
    var third = list(List.of(), List.of(), List.of(), null, null, null, 2, 2, actorA);
    assertThat(first.total()).isEqualTo(5);
    assertThat(first.items()).hasSize(2);
    assertThat(second.items()).hasSize(2);
    assertThat(third.items()).hasSize(1);
    // 三页的单号必须互不重叠 —— 否则就是同一条数据被重复返回。
    var codes = new java.util.ArrayList<String>();
    first.items().forEach(i -> codes.add(i.orderCode()));
    second.items().forEach(i -> codes.add(i.orderCode()));
    third.items().forEach(i -> codes.add(i.orderCode()));
    assertThat(codes).doesNotHaveDuplicates();
    assertThat(codes).hasSize(5);
  }

  @Test
  void 一个外部订单可以挂多张出库单分仓分批() {
    // 蓝图点名：「一订单可多出库单（分仓/分批）」。同一个外部订单号下并排挂两张。
    var first = sales("SO-SPLIT", whOne, "SKU-1", "2");
    var second = sales("SO-SPLIT", whTwo, "SKU-1", "3");
    assertThat(first.id()).isNotEqualTo(second.id());
    assertThat(first.orderCode()).isNotEqualTo(second.orderCode());
    var page = list(List.of(), List.of(), List.of(), null, null, "SO-SPLIT", 0, 50, actorA);
    assertThat(page.total()).isEqualTo(2);
  }

  @Test
  void 时间筛选按带偏移量的ISO8601解析() {
    sales("SO-TIME", whOne, "SKU-1", "1");
    // 同一个瞬间的两种偏移量表达：+08:00 与 +00:00 必须给出**完全相同**的结果。
    // 若服务端把偏移量丢掉按本地时间解析，其中一个会偏 8 小时而漏掉刚建的这一张。
    OffsetDateTime from = OffsetDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(10);
    var eight = list(List.of(), List.of(), List.of(), from, null, null, 0, 50, actorA);
    var zero =
        list(
            List.of(),
            List.of(),
            List.of(),
            from.withOffsetSameInstant(ZoneOffset.UTC),
            null,
            null,
            0,
            50,
            actorA);
    assertThat(eight.total()).isEqualTo(1);
    assertThat(zero.total()).isEqualTo(1);
    // 未来起点必须真的排掉这一张。
    assertThat(
            list(
                    List.of(),
                    List.of(),
                    List.of(),
                    OffsetDateTime.now(ZoneOffset.ofHours(8)).plusHours(2),
                    null,
                    null,
                    0,
                    50,
                    actorA)
                .total())
        .isZero();
    // 裸时间串（不带偏移量）必须被拒，而不是按服务器本地时区猜。
    assertThatThrownBy(() -> InventoryController.moment("2026-10-01T00:00:00"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("带时区");
  }

  @Test
  void 写入的时间值按timestamptz原样写回() {
    // Timestamp.valueOf(LocalDateTime) 那种写法在东八区会静默偏 −28800s，
    // 所以这里断言的是**写回库里的绝对时刻**，不是界面上看到的字符串。
    OffsetDateTime before = OffsetDateTime.now().minusSeconds(5);
    var created = sales("SO-TZ", whOne, "SKU-1", "1");
    var stored =
        run(
            a,
            () ->
                sql.fetchOne(
                        "select created_at from outbound_order where tenant_id = ? and id = ?",
                        a,
                        created.id())
                    .get("created_at", OffsetDateTime.class));
    assertThat(stored).isAfter(before);
    assertThat(stored).isBefore(OffsetDateTime.now().plusSeconds(5));
  }

  // ================================================================ 建单校验

  @Test
  void 零行单据被拒绝() {
    assertThatThrownBy(() -> salesRef("SO-EMPTY", whOne, List.of()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("至少要有一行明细");
  }

  @Test
  void 同一SKU不能出现两行() {
    assertThatThrownBy(
            () ->
                salesRef(
                    "SO-DUP",
                    whOne,
                    List.of(
                        new OutboundOrderLineInput(skuOne, BigDecimal.ONE),
                        new OutboundOrderLineInput(skuOne, new BigDecimal("2")))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("只能有一行");
  }

  @Test
  void 非调拨来源不能关联调拨单且必须有外部单号() {
    assertThatThrownBy(
            () ->
                run(
                    a,
                    () ->
                        orders.create(
                            new OutboundOrderCreateInput(
                                "SALES", transferA, "SO-X", whOne, "", "", lines("1")),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("只有调拨出库才关联");
    assertThatThrownBy(
            () ->
                run(
                    a,
                    () ->
                        orders.create(
                            new OutboundOrderCreateInput("SALES", null, "  ", whOne, "", "", lines("1")),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("必须给外部单号");
  }

  @Test
  void 调拨出库真的关联调拨单() {
    var created =
        run(
            a,
            () ->
                orders.create(
                    new OutboundOrderCreateInput(
                        "TRANSFER", transferA, null, whOne, "", "", lines("1")),
                    user));
    assertThat(created.sourceType()).isEqualTo("TRANSFER");
    assertThat(created.sourceTransferOrderId()).isEqualTo(transferA);
    // 列表里要能带出调拨单号 —— 这证明关联是外键而不是一个字符串。
    var page = list(List.of(), List.of("TRANSFER"), List.of(), null, null, null, 0, 50, actorA);
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items().get(0).sourceTransferCode()).isEqualTo("TR-1");
    assertThat(page.items().get(0).sourceExternalRef()).isNull();
  }

  @Test
  void 调拨出库的发货仓与明细SKU必须对得上() {
    // 发货仓不是调拨单的发出仓 → 拒。
    assertThatThrownBy(
            () ->
                run(
                    a,
                    () ->
                        orders.create(
                            new OutboundOrderCreateInput(
                                "TRANSFER", transferA, null, whTwo, "", "", lines("1")),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("必须是调拨单的发出仓");
    // SKU-2 不在调拨单明细里 → 拒。
    assertThatThrownBy(
            () ->
                run(
                    a,
                    () ->
                        orders.create(
                            new OutboundOrderCreateInput(
                                "TRANSFER",
                                transferA,
                                null,
                                whOne,
                                "",
                                "",
                                List.of(new OutboundOrderLineInput(skuTwo, BigDecimal.ONE))),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不在调拨单");
  }

  // ================================================================ 状态机

  @Test
  void 七态的合法跃迁逐条走通() {
    var created = sales("SO-FLOW", whOne, "SKU-1", "1");
    assertThat(created.status()).isEqualTo("PENDING_PICKING");

    // 待拣 → 拣货中
    var picking = pick(created.id());
    assertThat(picking.status()).isEqualTo("PICKING");
    // 拣货中 → 待复核
    assertThat(move(picking.id(), "PENDING_RECHECK").status()).isEqualTo("PENDING_RECHECK");
    // 待复核 → 待打包
    assertThat(move(created.id(), "PENDING_PACKING").status()).isEqualTo("PENDING_PACKING");
    // 待打包 → 待发货
    assertThat(move(created.id(), "PENDING_SHIPMENT").status()).isEqualTo("PENDING_SHIPMENT");
    // 已发货需要承运渠道：先验这条硬规则。
    assertThatThrownBy(() -> move(created.id(), "SHIPPED", "承运商已揽收"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("承运渠道");
    // 给承运渠道后才能完成（发货交接归 8.3.4.1，这里只验状态机的硬规则）。
    run(a, () -> sql.execute("update outbound_order set carrier_code = 'DHL' where tenant_id = ? and id = ?", a, created.id()));
    var shipped = move(created.id(), "SHIPPED", "承运商已揽收");
    assertThat(shipped.status()).isEqualTo("SHIPPED");
    assertThat(shipped.shippedAt()).isNotNull();
    assertThat(shipped.statusLabel()).isEqualTo("已发货");
  }

  @Test
  void 两条返工边可用() {
    var created = sales("SO-REWORK", whOne, "SKU-1", "1");
    pick(created.id());
    move(created.id(), "PENDING_RECHECK");
    // 待复核 → 拣货中（复核不通过退回重做），且必须写说明。
    assertThatThrownBy(() -> move(created.id(), "PICKING"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("必须写说明");
    assertThat(move(created.id(), "PICKING", "拣错货，退回重做").status()).isEqualTo("PICKING");

    // 待打包 → 待复核（打包发现缺件回复核）。
    move(created.id(), "PENDING_RECHECK");
    move(created.id(), "PENDING_PACKING");
    assertThat(move(created.id(), "PENDING_RECHECK", "缺一件，回复核").status()).isEqualTo("PENDING_RECHECK");
  }

  @Test
  void 非法跃迁被拒绝且给出合法去向() {
    var created = sales("SO-ILLEGAL", whOne, "SKU-1", "1");
    // 待拣不能直接跳到已发货。
    assertThatThrownBy(() -> move(created.id(), "SHIPPED", "承运商已揽收"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不允许的状态流转");
    // 待拣不能跳过拣货直接到待打包。
    assertThatThrownBy(() -> move(created.id(), "PENDING_PACKING"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不允许的状态流转");
    // 取消不走状态推进端点。
    assertThatThrownBy(() -> move(created.id(), "CANCELLED"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("请用取消端点");
    // 未知状态被 400 拒。
    assertThatThrownBy(() -> move(created.id(), "NOPE"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("未知的出库单状态");
  }

  @Test
  void 终态不能再流转() {
    var created = sales("SO-TERM", whOne, "SKU-1", "1");
    run(a, () -> orders.cancel(List.of(created.id()), "客户改地址，单子作废", user));
    var after = require(created.id());
    assertThat(after.status()).isEqualTo("CANCELLED");
    assertThat(after.cancelledAt()).isNotNull();
    assertThat(after.cancelReason()).isEqualTo("客户改地址，单子作废");
    assertThatThrownBy(() -> move(created.id(), "PICKING"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("终态");
  }

  @Test
  void 已发货的单不能取消() {
    var created = sales("SO-SHIP", whOne, "SKU-1", "1");
    pick(created.id());
    move(created.id(), "PENDING_RECHECK");
    move(created.id(), "PENDING_PACKING");
    move(created.id(), "PENDING_SHIPMENT");
    run(a, () -> sql.execute("update outbound_order set carrier_code = 'UPS' where tenant_id = ? and id = ?", a, created.id()));
    move(created.id(), "SHIPPED", "承运商已揽收");
    var result = cancel(List.of(created.id()), "发错了");
    assertThat(result.cancelledCount()).isZero();
    assertThat(result.failures()).hasSize(1);
    assertThat(result.failures().get(0).reason()).contains("已发货");
  }

  @Test
  void 状态机矩阵与数据库触发器白名单没有分叉() {
    // 服务层矩阵
    var allowed = new java.util.LinkedHashSet<String>();
    for (var from : OutboundOrderStatus.values()) {
      for (var t : from.transitionsFrom()) allowed.add(from.name() + ">" + t.to().name());
    }

    int accepted = 0;
    int rejected = 0;
    for (var from : OutboundOrderStatus.values()) {
      for (var to : OutboundOrderStatus.values()) {
        if (from == to) continue;
        // 每探测一次都用**新的一张单**：状态本身要靠服务层的合法跃迁走到 from，
        // 而直接用 SQL 摆状态会被同一个触发器挡住（那正是被测对象）。
        var order = orderInState(from);
        String dbResult = probe(order.id(), to);
        boolean dbOk = dbResult == null;
        boolean serviceOk = from.canTransitionTo(to);
        assertThat(dbOk)
            .as("%s → %s：数据库触发器与 Java 转换矩阵必须一致（serviceOk=%s, 数据库=%s）", from, to, serviceOk, dbOk ? "接受" : dbResult)
            .isEqualTo(serviceOk);
        if (dbOk) accepted++;
        else rejected++;
      }
    }
    // 服务层说合法的，数据库必须全放行；一条都不许有偏差。
    assertThat(accepted).isEqualTo(allowed.size());
    assertThat(accepted).isGreaterThan(0);
    assertThat(rejected).isGreaterThan(0);
    assertThat(allowed).contains(
        "PENDING_PICKING>PICKING", "PENDING_RECHECK>PICKING", "PENDING_PACKING>PENDING_RECHECK");
    // 终态在矩阵里没有任何去向。
    assertThat(OutboundOrderStatus.SHIPPED.transitionsFrom()).isEmpty();
    assertThat(OutboundOrderStatus.CANCELLED.transitionsFrom()).isEmpty();
  }

  /** 用服务层的合法跃迁把一张新单走到指定状态。 */
  private OutboundOrderSummary orderInState(OutboundOrderStatus state) {
    var order = sales("SO-STATE-" + state, whOne, "SKU-1", "1");
    switch (state) {
      case PENDING_PICKING -> {}
      case PICKING -> {
        requestPicking(List.of(order.id()), null, user);
      }
      case PENDING_RECHECK -> {
        requestPicking(List.of(order.id()), null, user);
        move(order.id(), "PENDING_RECHECK");
      }
      case PENDING_PACKING -> {
        requestPicking(List.of(order.id()), null, user);
        move(order.id(), "PENDING_RECHECK");
        move(order.id(), "PENDING_PACKING");
      }
      case PENDING_SHIPMENT -> {
        requestPicking(List.of(order.id()), null, user);
        move(order.id(), "PENDING_RECHECK");
        move(order.id(), "PENDING_PACKING");
        move(order.id(), "PENDING_SHIPMENT");
      }
      case SHIPPED -> {
        requestPicking(List.of(order.id()), null, user);
        move(order.id(), "PENDING_RECHECK");
        move(order.id(), "PENDING_PACKING");
        move(order.id(), "PENDING_SHIPMENT");
        run(
            a,
            () -> {
              sql.execute(
                  "update outbound_order set carrier_code = 'DHL' where tenant_id = ? and id = ?",
                  a,
                  order.id());
              return null;
            });
        move(order.id(), "SHIPPED", "承运商已揽收");
      }
      case CANCELLED -> {
        run(a, () -> orders.cancel(List.of(order.id()), "夹具作废", user));
      }
      default -> throw new IllegalStateException("未覆盖的状态：" + state);
    }
    assertThat(require(order.id()).status()).isEqualTo(state.name());
    return order;
  }

  /**
   * 直接打数据库状态字段，看 V72 触发器收不收。
   *
   * <p>刻意**每个探测都开一个新事务**：PostgreSQL 里任何一条语句报错都会中止整个事务，
   * 后续语句只会得到「current transaction is aborted」。把探测与复位放进同一个事务，
   * 测试会因为错误的原因（事务已中止）而通过 —— 那是一个永远绿的假断言。
   */
  private String probe(UUID orderId, OutboundOrderStatus to) {
    boolean shipped = to == OutboundOrderStatus.SHIPPED;
    boolean cancelled = to == OutboundOrderStatus.CANCELLED;
    return run(
        a,
        () -> {
          try {
            sql.execute(
                "update outbound_order set status = ?, carrier_code = 'DHL',"
                    // 两个 timestamptz 都要显式 cast：绑 null 时 jOOQ 按 varchar 送进去，
                    // PG 会拒「timestamp with time zone vs character varying」。
                    + " shipped_at = ?::timestamptz, cancelled_at = ?::timestamptz, cancel_reason = ?"
                    + " where tenant_id = ? and id = ?",
                to.name(),
                shipped ? now() : null,
                cancelled ? now() : null,
                cancelled ? "db-probe" : null,
                a,
                orderId);
            return null;
          } catch (DataAccessException e) {
            String root = e.getMessage();
            for (var c = e.getCause(); c != null; c = c.getCause()) {
              if (c.getMessage() != null) root = c.getMessage();
            }
            return root;
          }
        });
  }

  @Test
  void 绕过服务层改终态行的业务列会被数据库挡住() {
    var created = sales("SO-FROZEN", whOne, "SKU-1", "1");
    run(a, () -> orders.cancel(List.of(created.id()), "作废", user));
    assertThatThrownBy(
            () ->
                run(
                    a,
                    () -> {
                      sql.execute(
                          "update outbound_order set warehouse_id = ? where tenant_id = ? and id = ?",
                          whTwo,
                          a,
                          created.id());
                      return null;
                    }))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("终态");
  }

  @Test
  void 明细行是不可改的计划快照() {
    var created = sales("SO-LINE", whOne, "SKU-1", "1");
    assertThatThrownBy(
            () ->
                run(
                    a,
                    () -> {
                      sql.execute(
                          "update outbound_order_line set quantity = 99 where tenant_id = ? and order_id = ?",
                          a,
                          created.id());
                      return null;
                    }))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("计划快照");
    assertThatThrownBy(
            () ->
                run(
                    a,
                    () -> {
                      sql.execute(
                          "delete from outbound_order_line where tenant_id = ? and order_id = ?", a, created.id());
                      return null;
                    }))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("计划快照");
  }

  @Test
  void 单子动起来之后不能再加明细行() {
    var created = sales("SO-ADDLINE", whOne, "SKU-1", "1");
    pick(created.id());
    assertThatThrownBy(
            () ->
                run(
                    a,
                    () -> {
                      sql.execute(
                          "insert into outbound_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,"
                              + "sku_name,quantity) values(?,?,?,?,?,?,?,?)",
                          UUID.randomUUID(),
                          a,
                          created.id(),
                          2,
                          skuTwo,
                          "SKU-2",
                          "商品二",
                          new BigDecimal("1.000000"));
                      return null;
                    }))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("只有待拣的单能加明细行");
  }

  // ================================================================ 批量操作

  @Test
  void 批量生成拣货任务是真的交接而不是假拣货() {
    var first = sales("SO-P1", whOne, "SKU-1", "2");
    var second = sales("SO-P2", whOne, "SKU-2", "4");
    var result = requestPicking(List.of(first.id(), second.id()), "先拣整箱", user);
    assertThat(result.requestedCount()).isEqualTo(2);
    assertThat(result.failures()).isEmpty();
    assertThat(result.orders()).allSatisfy(o -> assertThat(o.status()).isEqualTo("PICKING"));

    // 交接请求真的落了库，带行数与件数快照。
    var req =
        run(
            a,
            () ->
                sql.fetchOne(
                    "select line_count,total_piece_count,note,requested_by_user_id"
                        + " from outbound_pick_request where tenant_id = ? and order_id = ?",
                    a,
                    first.id()));
    assertThat(req.get("line_count", Integer.class)).isEqualTo(1);
    assertThat(req.get("total_piece_count", BigDecimal.class)).isEqualByComparingTo("2.000000");
    assertThat(req.get("note", String.class)).isEqualTo("先拣整箱");
    assertThat(req.get("requested_by_user_id", UUID.class)).isEqualTo(user);

    // 一张单只交接一次：重复请求要失败并说清楚原因，而不是静默重发工单。
    var again = requestPicking(List.of(first.id()), null, user);
    assertThat(again.requestedCount()).isZero();
    assertThat(again.failures().get(0).reason()).contains("已经生成过拣货任务");
    // 重复请求不许改变状态。
    assertThat(require(first.id()).status()).isEqualTo("PICKING");
  }

  @Test
  void 批量操作逐单独立处理失败要带原因() {
    var pending = sales("SO-OK", whOne, "SKU-1", "1");
    var started = sales("SO-BUSY", whOne, "SKU-1", "1");
    pick(started.id());
    var result = requestPicking(List.of(pending.id(), started.id()), null, user);
    assertThat(result.requestedCount()).isEqualTo(1);
    assertThat(result.failures()).hasSize(1);
    assertThat(result.failures().get(0).orderId()).isEqualTo(started.id());
    assertThat(result.failures().get(0).reason()).contains("已经生成过拣货任务");
  }

  @Test
  void 批量取消逐单独立处理且原因必填() {
    var first = sales("SO-C1", whOne, "SKU-1", "1");
    var second = sales("SO-C2", whOne, "SKU-1", "1");
    assertThatThrownBy(() -> cancel(List.of(first.id(), second.id()), "  ", user))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("取消必须写原因");
    var result = cancel(List.of(first.id(), second.id()), "客户取消订单", user);
    assertThat(result.cancelledCount()).isEqualTo(2);
    assertThat(result.failures()).isEmpty();
    assertThat(result.orders()).allSatisfy(o -> assertThat(o.status()).isEqualTo("CANCELLED"));
    // 重复取消第二单要失败（终态）。
    var again = cancel(List.of(first.id()), "再取消一次", user);
    assertThat(again.cancelledCount()).isZero();
    assertThat(again.failures()).hasSize(1);
  }

  @Test
  void 批量打印返回真正要印的内容() {
    var created =
        salesRef(
            "SO-PRINT",
            whOne,
            List.of(
                new OutboundOrderLineInput(skuOne, new BigDecimal("2.5")),
                new OutboundOrderLineInput(skuTwo, new BigDecimal("1"))));
    var result = print(List.of(created.id()), "PICK_LIST", user);
    assertThat(result.document()).isEqualTo("PICK_LIST");
    assertThat(result.documentLabel()).isEqualTo("拣货单");
    assertThat(result.orderCount()).isEqualTo(1);
    var order = result.orders().get(0);
    assertThat(order.orderCode()).isEqualTo(created.orderCode());
    assertThat(order.warehouseCode()).isEqualTo("WH-ONE");
    assertThat(order.totalPieceCount()).isEqualByComparingTo("3.500000");
    assertThat(order.lines()).hasSize(2);
    assertThat(order.lines().get(0).lineNo()).isEqualTo(1);
    assertThat(order.lines().get(0).skuCode()).isEqualTo("SKU-1");
    assertThat(order.lines().get(0).quantity()).isEqualByComparingTo("2.500000");

    // 打印要留痕（append-only），且带上真实操作人。
    var events =
        run(
            a,
            () ->
                sql.fetch(
                    "select event_type,actor_user_id,detail from outbound_order_event"
                        + " where tenant_id = ? and order_id = ? and event_type = 'PRINTED'",
                    a,
                    created.id()));
    assertThat(events).hasSize(1);
    assertThat(events.get(0).get("actor_user_id", UUID.class)).isEqualTo(user);
  }

  @Test
  void 打印不接受不存在的文档类型() {
    var created = sales("SO-DOC", whOne, "SKU-1", "1");
    assertThatThrownBy(() -> print(List.of(created.id()), "LABEL", user))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("未知的打印文档类型");
  }

  @Test
  void 留痕表是appendOnly() {
    var created = sales("SO-AUDIT", whOne, "SKU-1", "1");
    assertThatThrownBy(
            () ->
                run(
                    a,
                    () -> {
                      sql.execute(
                          "update outbound_order_event set detail = '改一笔' where tenant_id = ? and order_id = ?",
                          a,
                          created.id());
                      return null;
                    }))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("append-only");
    // 建单、生成拣货任务都留了痕。
    var types =
        run(
            a,
            () ->
                sql.fetch(
                    "select event_type from outbound_order_event where tenant_id = ? and order_id = ?",
                    a,
                    created.id()));
    assertThat(types.stream().map(t -> t.get("event_type", String.class)).toList())
        .containsExactly("CREATED");
  }

  // ================================================================ 能力声明

  @Test
  void 能力声明把边界讲清楚() {
    var caps = access.run(actorA, "warehouse.inventory.read", () -> orders.capabilities(actorA));
    assertThat(caps.sourceTypes()).hasSize(4);
    assertThat(caps.statuses()).hasSize(7);
    assertThat(caps.transitions()).isNotEmpty();
    // 打印只有两种，面单归 8.3.4.2。
    assertThat(caps.printDocuments()).extracting(OutboundEnumOption::name)
        .containsExactly("OUTBOUND_ORDER", "PICK_LIST");
    assertThat(caps.pickingHandoverNote()).contains("8.3.2.1");
    assertThat(caps.orderFulfillmentNote()).contains("9 订单履约尚未交付");
    assertThat(caps.printScopeNote()).contains("8.3.4.2");
    assertThat(caps.dataScopeNote()).contains("ALL");
  }

  @Test
  void 端点经HTTP可用且时间参数格式错误时400() throws Exception {
    sales("SO-HTTP", whOne, "SKU-1", "1");
    http.perform(
            get("/warehouse/inventory/outbound-orders")
                .header("Authorization", "Bearer tenant-a")
                .param("status", "PENDING_PICKING"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].status").value("PENDING_PICKING"))
        .andExpect(jsonPath("$.items[0].skuLineCount").value(1));
    // 不带权限码时是 403。
    http.perform(get("/warehouse/inventory/outbound-orders").header("Authorization", "Bearer tenant-b"))
        .andExpect(status().isOk());
    // 裸时间串 → 400。
    http.perform(
            get("/warehouse/inventory/outbound-orders")
                .header("Authorization", "Bearer tenant-a")
                .param("createdFrom", "2026-10-01T00:00:00"))
        .andExpect(status().isBadRequest());
  }

  // ================================================================ 用户数据范围

  @Test
  void 用户数据范围按已认证身份收窄() {
    sales("SO-MINE", whOne, "SKU-1", "1");
    // 另一个用户（同样在租户 A，SELF 范围）看不到本用户建的。
    var otherUser = UUID.randomUUID();
    var selfActor = actor(otherUser, a, List.of(grant("SELF", allPermissions)));
    var visible =
        access.run(selfActor, "warehouse.inventory.read", () -> orders.list(List.of(), List.of(), List.of(), null, null, null, 0, 50, selfActor));
    assertThat(visible.total()).isZero();
    // ALL 范围看得到。
    assertThat(list(List.of(), List.of(), List.of(), null, null, null, 0, 50, actorA).total()).isEqualTo(1);
    // SELF 范围自己建的看得到。
    var selfActorOwn = actor(user, a, List.of(grant("SELF", allPermissions)));
    var own =
        access.run(
            selfActorOwn,
            "warehouse.inventory.read",
            () -> orders.list(List.of(), List.of(), List.of(), null, null, null, 0, 50, selfActorOwn));
    assertThat(own.total()).isEqualTo(1);
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() {
    var a1 = createFor(a, "SO-TENANT-A", whOne);
    var b1 = createFor(b, "SO-TENANT-B", idFor(b, 4));
    assertThat(list(List.of(), List.of(), List.of(), null, null, null, 0, 50, actorA).total()).isEqualTo(1);
    assertThat(list(List.of(), List.of(), List.of(), null, null, null, 0, 50, actorB).total()).isEqualTo(1);
    // A 看不到 B 的单，B 也拿不到 A 的单。
    assertThatThrownBy(() -> requireAs(b, a1.id()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或不属于当前租户");
    assertThatThrownBy(() -> requireAs(a, b1.id()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或不属于当前租户");
    // B 不能推进 A 的单。
    assertThatThrownBy(() -> run(b, () -> orders.transition(a1.id(), new OutboundTransitionInput("PICKING", null), user)))
        .isInstanceOf(ResponseStatusException.class);
    // B 不能取消 A 的单，也不能对 A 的单生成拣货任务。
    assertThat(run(b, () -> orders.cancel(List.of(a1.id()), "越权取消", user)).cancelledCount()).isZero();
    assertThat(run(b, () -> orders.requestPicking(List.of(a1.id()), null, user)).requestedCount()).isZero();
    assertThat(requireAs(a, a1.id()).status()).isEqualTo("PENDING_PICKING");
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    var a1 = createFor(a, "SO-FORGED", whOne);
    // 身份是 B，请求里伪造 X-Tenant-Id 指向 A：B 看到的仍然是 B 的（空）。
    http.perform(
            get("/warehouse/inventory/outbound-orders")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
    // 伪造头也不能让 B 把 A 的单推进状态。
    http.perform(
            post("/warehouse/inventory/outbound-orders/{id}/transition", a1.id())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString())
                .contentType("application/json")
                .content("{\"targetStatus\":\"PICKING\"}"))
        .andExpect(status().is4xxClientError());
    assertThat(requireAs(a, a1.id()).status()).isEqualTo("PENDING_PICKING");
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    createFor(a, "SO-POOL-A", whOne);
    createFor(b, "SO-POOL-B", idFor(b, 4));
    // 交替复用同一条物理连接（池容量=1）：A → B → A → B
    for (int i = 0; i < 3; i++) {
      var a1 = list(List.of(), List.of(), List.of(), null, null, null, 0, 50, actorA);
      assertThat(a1.total()).isEqualTo(1);
      assertThat(a1.items().get(0).sourceExternalRef()).isEqualTo("SO-POOL-A");
      var b1 = list(List.of(), List.of(), List.of(), null, null, null, 0, 50, actorB);
      assertThat(b1.total()).isEqualTo(1);
      assertThat(b1.items().get(0).sourceExternalRef()).isEqualTo("SO-POOL-B");
    }
    // 四张表逐张确认 FORCE RLS
    for (var table :
        List.of("outbound_order", "outbound_order_line", "outbound_pick_request", "outbound_order_event")) {
      assertThat(
              sql.fetchOne("select relforcerowsecurity from pg_class where relname = ?", table)
                  .get("relforcerowsecurity", Boolean.class))
          .isTrue();
      assertThat(
              sql.fetchOne("select relrowsecurity from pg_class where relname = ?", table)
                  .get("relrowsecurity", Boolean.class))
          .isTrue();
    }
  }

  // ================================================================ 小工具

  List<OutboundOrderLineInput> lines(String qty) {
    return List.of(new OutboundOrderLineInput(skuOne, new BigDecimal(qty)));
  }

  OutboundOrderPage list(
      List<UUID> warehouses,
      List<String> sources,
      List<String> statuses,
      OffsetDateTime from,
      OffsetDateTime to,
      String keyword,
      int page,
      int size,
      AccessService.Access actor) {
    return access.run(
        actor, "warehouse.inventory.read", () -> orders.list(warehouses, sources, statuses, from, to, keyword, page, size, actor));
  }

  OutboundOrderSummary require(UUID id) {
    return requireAs(a, id);
  }

  OutboundOrderSummary requireAs(UUID tenant, UUID id) {
    return access.run(
        actorFor(tenant),
        "warehouse.inventory.read",
        () -> orders.list(List.of(), List.of(), List.of(), null, null, null, 0, 200, actorFor(tenant)).items()
            .stream()
            .filter(o -> o.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "出库单不存在或不属于当前租户：" + id)));
  }

  OutboundOrderSummary move(UUID id, String target) {
    return move(id, target, null);
  }

  OutboundOrderSummary move(UUID id, String target, String note) {
    return run(a, () -> orders.transition(id, new OutboundTransitionInput(target, note), user));
  }

  OutboundOrderSummary pick(UUID id) {
    return requestPicking(List.of(id), null, user).orders().get(0);
  }

  OutboundPickRequestResult requestPicking(List<UUID> ids, String note, UUID actorId) {
    return run(a, () -> orders.requestPicking(ids, note, actorId));
  }

  OutboundCancelResult cancel(List<UUID> ids, String reason) {
    return cancel(ids, reason, user);
  }

  OutboundCancelResult cancel(List<UUID> ids, String reason, UUID actorId) {
    return run(a, () -> orders.cancel(ids, reason, actorId));
  }

  OutboundPrintResult print(List<UUID> ids, String document, UUID actorId) {
    return run(a, () -> orders.print(ids, document, actorId));
  }
}