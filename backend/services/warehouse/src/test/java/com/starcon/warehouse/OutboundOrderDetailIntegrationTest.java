package com.starcon.warehouse;

import static com.starcon.warehouse.OutboundOrderDetailModels.*;
import static com.starcon.warehouse.OutboundOrderModels.*;
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
 * 8.3.1.2 出库单详情：单头 + 明细行 + 已拣/已发口径 + 拣货交接 + 作业时间线 + 四段不可得能力
 * + 作业操作入口，以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本测试要钉死的是四件最容易糊过去的事</b>：
 *
 * <ol>
 *   <li><b>已拣量是 null 而不是 0。</b> 8.3.2.1 未交付 → 没有 picking_task 可查。
 *       {@link #已拣量交付前是null而不是0()} 直接断言逐行为 null —— 填 0 会让作业员
 *       以为一件都没拣，那是假数字，比不显示更危险。
 *   <li><b>已发量只在 SHIPPED 时是真数。</b> SHIPPED 是终态且 DB 层冻结、明细行不可改，
 *       所以「已发量 = 计划件数」成立；未到已发货的状态必须是 null，{@link #未到已发货时已发量为空()}
 *       逐状态验一遍，钉死「绝不按拣货进度推算发货进度」。
 *   <li><b>作业操作入口与 8.3.1.1 共用同一个转换矩阵。</b>
 *       {@link #操作入口与8_3_1_1转换矩阵没有分叉()} 逐状态比对详情返回的 actions 与
 *       {@code OutboundOrderStatus.transitionsFrom()}，防止详情页另写一份状态机。
 *   <li><b>四段拿不到的数据必须显式 available=false。</b>
 *       {@link #拿不到的四段显式available为false()} 钉住它们既不返回空集冒充成功，
 *       也不给出编造的 ownerModule / href。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutboundOrderDetailIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      Stream.of("inventory")
          .flatMap(p -> Stream.of("read", "create", "update", "delete").map(op -> "warehouse." + p + "." + op))
          .toList();

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-warehouse-outbound-detail-" + tenant + "-" + ordinal).getBytes());
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
      actors.put("Bearer no-read", actor(user, a, List.of(grant("ALL", List.of("warehouse.inventory.update")))));
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
  @Autowired OutboundOrderDetailService details;
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

  static UUID skuOne(UUID t) {
    return idFor(t, 2);
  }

  static UUID whOne(UUID t) {
    return idFor(t, 4);
  }

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
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                skuOne(tenant), tenant, "SKU-1", "商品一");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 3), tenant, "SKU-2", "商品二");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
                whOne(tenant), tenant, "WH-ONE", "一仓", "CN", "Asia/Shanghai");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
                idFor(tenant, 5), tenant, "WH-TWO", "二仓", "CN", "Asia/Shanghai");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 6), tenant, "SELLABLE", "可售", "SELLABLE");
            return null;
          });
    }
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  // ================================================================ 夹具

  <T> T run(UUID tenant, java.util.function.Supplier<T> work) {
    return access.run(actorFor(tenant), "warehouse.inventory.update", work);
  }

  /**
   * 建一张销售来源的出库单。{@code skuQty} 交替落在 SKU-1 / SKU-2 上，
   * 元素个数就是明细行数。
   */
  OutboundOrderSummary create(UUID tenant, String ref, String... skuQty) {
    var lines = new java.util.ArrayList<OutboundOrderLineInput>();
    for (int i = 0; i < skuQty.length; i++) {
      lines.add(
          new OutboundOrderLineInput(
              i == 1 ? idFor(tenant, 3) : idFor(tenant, 2), new BigDecimal(skuQty[i])));
    }
    return run(
        tenant,
        () ->
            orders.create(
                new OutboundOrderCreateInput("SALES", null, ref, whOne(tenant), "", "", lines), user));
  }

  OutboundOrderDetail detailOf(UUID tenant, UUID orderId) {
    return access.run(actorFor(tenant), "warehouse.inventory.read", () -> details.detail(orderId));
  }

  void transition(UUID orderId, String to) {
    run(a, () -> orders.transition(orderId, new OutboundTransitionInput(to, "详情测试推进"), user));
  }

  // ================================================================ 详情基本形状

  @Test
  void 详情返回单头明细行件数与时间线() {
    var created = create(a, "SO-DETAIL-1", "2.5", "3");
    var d = detailOf(a, created.id());

    assertThat(d.id()).isEqualTo(created.id());
    assertThat(d.orderCode()).isEqualTo(created.orderCode());
    assertThat(d.sourceType()).isEqualTo("SALES");
    assertThat(d.sourceTypeLabel()).isEqualTo("销售");
    assertThat(d.sourceExternalRef()).isEqualTo("SO-DETAIL-1");
    assertThat(d.warehouseCode()).isEqualTo("WH-ONE");
    assertThat(d.status()).isEqualTo("PENDING_PICKING");
    assertThat(d.statusLabel()).isEqualTo("待拣");
    assertThat(d.terminal()).isFalse();

    // 明细行按 line_no 升序，行数与件数现算（不存列，V72 第五节）。
    assertThat(d.lines()).hasSize(2);
    assertThat(d.lines().get(0).lineNo()).isEqualTo(1);
    assertThat(d.lines().get(1).lineNo()).isEqualTo(2);
    assertThat(d.skuLineCount()).isEqualTo(2);
    assertThat(d.totalPieceCount()).isEqualByComparingTo("5.500000");
    assertThat(d.totalPieceCount().scale()).isEqualTo(6);

    // 建单会写一条 CREATED 事件。
    assertThat(d.timeline()).hasSize(1);
    assertThat(d.timelineEventCount()).isEqualTo(1);
    assertThat(d.timeline().get(0).eventType()).isEqualTo("CREATED");
    assertThat(d.timeline().get(0).eventTypeLabel()).isEqualTo("建单");
    assertThat(d.timeline().get(0).toStatus()).isEqualTo("PENDING_PICKING");
    assertThat(d.timeline().get(0).toStatusLabel()).isEqualTo("待拣");
    assertThat(d.timeline().get(0).actorUserId()).isEqualTo(user);
  }

  @Test
  void 跨租户与不存在返回同一个404() {
    var a1 = create(a, "SO-404", "1");
    // 存在的但不属于当前租户 → 404
    assertThatThrownBy(() -> detailOf(b, a1.id()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或不属于当前租户");
    // 完全不存在的 → 同一个 404：让 A 拿 B 的 id 探测存在性本身就是泄露面。
    assertThatThrownBy(() -> detailOf(a, UUID.randomUUID()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或不属于当前租户");
  }

  // ================================================================ 已拣量 / 已发量口径

  @Test
  void 已拣量交付前是null而不是0() {
    var created = create(a, "SO-PICKED", "2", "4");
    run(
        a,
        () ->
            orders.requestPicking(
                List.of(created.id()), "拣货交接说明", user));
    var d = detailOf(a, created.id());

    // 逐行已拣量必须是 null：8.3.2.1 未交付，没有 picking_task 可查。
    // 断言 null 而不是 0 —— 0 会被读成「一件都没拣」，那是假数字。
    for (var line : d.lines()) {
      assertThat(line.pickedQuantity()).as("已拣量必须是 null 而不是 0").isNull();
    }
    // 数量口径显式标注不可得，并给出归属模块与原因。
    var picked = d.quantities().stream().filter(q -> q.quantity().equals("PICKED")).findFirst().orElseThrow();
    assertThat(picked.available()).isFalse();
    assertThat(picked.value()).isNull();
    assertThat(picked.ownerModule()).isEqualTo("8.3.2.1");
    assertThat(picked.reason()).contains("picking_task");
    // 但交接请求本身是真实的、可读的。
    assertThat(d.pickRequested()).isTrue();
    assertThat(d.pickRequest()).isNotNull();
    assertThat(d.pickRequest().lineCount()).isEqualTo(2);
    assertThat(d.pickRequest().totalPieceCount()).isEqualByComparingTo("6.000000");
    assertThat(d.pickRequest().note()).isEqualTo("拣货交接说明");
  }

  @Test
  void 未交接时pickRequest为null而不是空对象() {
    var created = create(a, "SO-NO-HANDOVER", "1");
    var d = detailOf(a, created.id());
    assertThat(d.pickRequested()).isFalse();
    assertThat(d.pickRequest()).isNull();
  }

  @Test
  void 未到已发货时已发量为空() {
    var created = create(a, "SO-NOT-SHIPPED", "3", "1");
    // 逐个非终态验一遍：已发量必须一直是 null。
    transition(created.id(), "PICKING");
    transition(created.id(), "PENDING_RECHECK");
    transition(created.id(), "PENDING_PACKING");
    transition(created.id(), "PENDING_SHIPMENT");
    var d = detailOf(a, created.id());
    assertThat(d.status()).isEqualTo("PENDING_SHIPMENT");
    for (var line : d.lines()) {
      assertThat(line.shippedQuantity()).as("未到已发货时已发量必须为空").isNull();
      assertThat(line.pickedQuantity()).isNull();
    }
    var shipped = d.quantities().stream().filter(q -> q.quantity().equals("SHIPPED")).findFirst().orElseThrow();
    assertThat(shipped.available()).isFalse();
    assertThat(shipped.value()).isNull();
    assertThat(shipped.ownerModule()).isEqualTo("8.3.4.1");
  }

  @Test
  void 已发货时已发量等于计划件数() {
    var created = create(a, "SO-SHIPPED", "2.5", "3");
    // 先补承运渠道：SHIPPED 要求有承运商（V72 的 CHECK 钉死），且发货交接归 8.3.4.1，
    // 本模块不提供在这里改承运渠道的入口 —— 用底层 SQL 模拟上游写入。
    run(
        a,
        () ->
            sql.execute(
                "update outbound_order set carrier_code = 'DHL' where tenant_id = ? and id = ?",
                a,
                created.id()));
    transition(created.id(), "PICKING");
    transition(created.id(), "PENDING_RECHECK");
    transition(created.id(), "PENDING_PACKING");
    transition(created.id(), "PENDING_SHIPMENT");
    transition(created.id(), "SHIPPED");

    var d = detailOf(a, created.id());
    assertThat(d.status()).isEqualTo("SHIPPED");
    assertThat(d.terminal()).isTrue();
    assertThat(d.carrierCode()).isEqualTo("DHL");
    assertThat(d.shippedAt()).isNotNull();
    // SHIPPED 是终态且 DB 层冻结 + 明细不可改 ⇒ 已发量 = 计划件数成立。
    for (var line : d.lines()) {
      assertThat(line.shippedQuantity()).isNotNull();
      assertThat(line.shippedQuantity()).isEqualByComparingTo(line.plannedQuantity());
    }
    var shipped = d.quantities().stream().filter(q -> q.quantity().equals("SHIPPED")).findFirst().orElseThrow();
    assertThat(shipped.available()).isTrue();
    assertThat(shipped.value()).isEqualByComparingTo("5.500000");
  }

  // ================================================================ 作业操作入口

  @Test
  void 操作入口与8_3_1_1转换矩阵没有分叉() {
    var created = create(a, "SO-ACTIONS", "1");
    // 逐状态比对：详情返回的 actions 必须与 OutboundOrderStatus.transitionsFrom() 完全一致。
    // 防止详情页另写一份状态机（那是并行开发最容易发生的分叉）。
    OutboundOrderStatus status = OutboundOrderStatus.PENDING_PICKING;
    String[] path = {"PICKING", "PENDING_RECHECK", "PENDING_PACKING", "PENDING_SHIPMENT", "SHIPPED"};
    for (var step : path) {
      if (status == OutboundOrderStatus.PENDING_SHIPMENT) {
        run(
            a,
            () ->
                sql.execute(
                    "update outbound_order set carrier_code = 'SF' where tenant_id = ? and id = ?",
                    a,
                    created.id()));
      }
      transition(created.id(), step);
      status = status.transitionTo(OutboundOrderStatus.valueOf(step)).to();

      var d = detailOf(a, created.id());
      var expected = status.transitionsFrom().stream().map(t -> t.to().name()).toList();
      assertThat(d.actions().stream().map(x -> x.toStatus()).toList())
          .as("状态 " + status.name() + " 的操作入口必须与转换矩阵一致")
          .isEqualTo(expected);
      // 每个动作都要标注归属模块与前置条件。
      for (var action : d.actions()) {
        assertThat(action.ownerModule()).as("每个动作都必须有归属模块").isNotBlank();
        assertThat(action.note()).isNotBlank();
      }
    }
    // 终态没有合法下一步。
    var shipped = detailOf(a, created.id());
    assertThat(shipped.actions()).isEmpty();
  }

  @Test
  void 待复核的两个返工去向都给出且要求写原因() {
    var created = create(a, "SO-REWORK", "1");
    transition(created.id(), "PICKING");
    transition(created.id(), "PENDING_RECHECK");
    var d = detailOf(a, created.id());
    var targets = d.actions().stream().map(x -> x.toStatus()).toList();
    assertThat(targets).containsExactly("PENDING_PACKING", "PICKING", "CANCELLED");
    // 复核不通过退回拣货要写原因（这条边最容易在详情页被漏掉提示）。
    var back = d.actions().stream().filter(x -> x.toStatus().equals("PICKING")).findFirst().orElseThrow();
    assertThat(back.reasonRequired()).isTrue();
    assertThat(back.ownerModule()).contains("8.3.2.1");
    // 复核通过去打包不要求写原因。
    var forward = d.actions().stream().filter(x -> x.toStatus().equals("PENDING_PACKING")).findFirst().orElseThrow();
    assertThat(forward.reasonRequired()).isFalse();
    assertThat(forward.ownerModule()).contains("8.3.3.2");
  }

  // ================================================================ 四段不可得能力

  @Test
  void 拿不到的四段显式available为false() {
    var created = create(a, "SO-LINKS", "1");
    var d = detailOf(a, created.id());

    // 关联销售订单：9 订单未交付 → 不可得，且必须给出归属模块与原因。
    assertThat(d.linkedOrder().available()).isFalse();
    assertThat(d.linkedOrder().ownerModule()).isEqualTo("9.1.1.2");
    assertThat(d.linkedOrder().reason()).contains("尚未交付");

    // 买家地址：同上，且刻意不抄地址快照。
    assertThat(d.shipToAddress().available()).isFalse();
    assertThat(d.shipToAddress().ownerModule()).isEqualTo("9.1.1.2");

    // 预占引用：归 7.2.1。
    assertThat(d.reservationRefs().available()).isFalse();
    assertThat(d.reservationRefs().ownerModule()).isEqualTo("7.2.1.1");
    assertThat(d.reservationRefs().href()).isEqualTo("/inventory/reservation");

    // 包裹列表：归 8.3.4.1。承运渠道那一段反而是 available=true（单头已登记的事实）。
    assertThat(d.packages()).hasSize(2);
    var packages = d.packages().get(0);
    assertThat(packages.available()).isFalse();
    assertThat(packages.ownerModule()).isEqualTo("8.3.4.1");
    var carrier = d.packages().get(1);
    assertThat(carrier.available()).isTrue();
  }

  @Test
  void 调拨来源的关联订单说明仍指向9而不是调拨单() {
    // TRANSFER 来源与 transfer_order 是真关联（单头能显示调拨单号），但「关联的**销售订单**」
    // 仍然拿不到 —— 说明里必须如实区分这两件事，不能让作业员以为调拨单号就是订单。
    var transferId = idFor(a, 7);
    run(
        a,
        () -> {
          sql.execute(
              "insert into transfer_order(id,tenant_id,order_code,from_warehouse_id,to_warehouse_id,"
                  + "legal_entity_id,status,confirmed_at,created_by) "
                  + "values(?,?,?,?,?,?,'PENDING_SHIPMENT',?::timestamptz,?)",
              transferId,
              a,
              "TR-DETAIL-1",
              whOne(a),
              idFor(a, 5),
              idFor(a, 1),
              OffsetDateTime.now(),
              user);
          // 明细行必须真的在调拨单里 —— 8.3.1.1 建单时会校验，发一个调拨单上没有的 SKU
          // 在业务上不可能成立（这条校验不是本模块的，但它挡住了本夹具）。
          sql.execute(
              "insert into transfer_order_line(id,tenant_id,order_id,sku_id,quality_status_id,"
                  + "requested_quantity) values(?,?,?,?,?,?)",
              UUID.randomUUID(),
              a,
              transferId,
              idFor(a, 2),
              idFor(a, 6),
              new BigDecimal("2.000000"));
          return null;
        });
    var created =
        run(
            a,
            () ->
                orders.create(
                    new OutboundOrderCreateInput(
                        "TRANSFER",
                        transferId,
                        null,
                        whOne(a),
                        "",
                        "",
                        List.of(new OutboundOrderLineInput(idFor(a, 2), new BigDecimal("2")))),
                    user));
    var d = detailOf(a, created.id());
    // 真关联：调拨单号可读。
    assertThat(d.sourceType()).isEqualTo("TRANSFER");
    assertThat(d.sourceTransferCode()).isEqualTo("TR-DETAIL-1");
    // 但销售订单仍然不可得，且原因里点名调拨单。
    assertThat(d.linkedOrder().available()).isFalse();
    assertThat(d.linkedOrder().ownerModule()).isEqualTo("9.1.1.2");
    assertThat(d.linkedOrder().reason()).contains("调拨");
  }

  @Test
  void 能力声明报出六段边界与两条数量口径() {
    var caps = details.capabilities();
    assertThat(caps.sections()).hasSize(4);
    assertThat(caps.quantityPolicies()).hasSize(2);
    var picked = caps.quantityPolicies().stream().filter(q -> q.quantity().equals("PICKED")).findFirst().orElseThrow();
    assertThat(picked.available()).isFalse();
    assertThat(picked.ownerModule()).isEqualTo("8.3.2.1");
    assertThat(caps.pickedQuantityNote()).contains("8.3.2.1");
    assertThat(caps.shippedQuantityNote()).contains("8.3.4.1");
    assertThat(caps.linkedOrderNote()).isNotBlank();
    assertThat(caps.reservationNote()).contains("7.2.1");
    assertThat(caps.packageNote()).contains("8.3.4.1");
    assertThat(caps.actionOwnerModules()).contains("8.3.2.1", "8.3.4.1");
  }

  // ================================================================ 时间口径

  @Test
  void 写入的时间值按timestamptz原样写回() {
    // Timestamp.valueOf(LocalDateTime) 那种写法在东八区会静默偏 −28800s，
    // 所以这里断言的是**写回库里的绝对时刻**，不是界面上看到的字符串。
    OffsetDateTime before = OffsetDateTime.now().minusSeconds(5);
    var created = create(a, "SO-TZ-DETAIL", "1");
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

  @Test
  void 时间线同一毫秒的两条事件顺序稳定() {
    var created = create(a, "SO-TIMELINE-ORDER", "1");
    // 生成拣货任务写**一条** PICK_REQUESTED 事件（它同时承载了待拣 → 拣货中的跃迁，
    // 不会另写一条 STATUS_CHANGED），所以总数是 2。
    run(a, () -> orders.requestPicking(List.of(created.id()), null, user));
    var d = detailOf(a, created.id());
    assertThat(d.timeline()).hasSize(2);
    assertThat(d.timeline().get(0).eventType()).isEqualTo("CREATED");
    assertThat(d.timeline().get(1).eventType()).isEqualTo("PICK_REQUESTED");
    // 这一条同时带上了状态跃迁的两端，时间线上读得懂发生了什么。
    assertThat(d.timeline().get(1).fromStatus()).isEqualTo("PENDING_PICKING");
    assertThat(d.timeline().get(1).toStatus()).isEqualTo("PICKING");
    assertThat(d.timeline().get(1).fromStatusLabel()).isEqualTo("待拣");
    assertThat(d.timeline().get(1).toStatusLabel()).isEqualTo("拣货中");
    // 升序读时间线：occurred_at 不降。
    for (int i = 1; i < d.timeline().size(); i++) {
      assertThat(d.timeline().get(i).occurredAt())
          .isAfterOrEqualTo(d.timeline().get(i - 1).occurredAt());
    }
  }

  // ================================================================ HTTP 端点

  @Test
  void 详情端点返回真实数据() throws Exception {
    var created = create(a, "SO-HTTP", "2");
    http.perform(get("/warehouse/inventory/outbound-orders/{id}", created.id())
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(created.id().toString()))
        .andExpect(jsonPath("$.sourceExternalRef").value("SO-HTTP"))
        .andExpect(jsonPath("$.lines[0].pickedQuantity").doesNotExist())
        .andExpect(jsonPath("$.skuLineCount").value(1));
  }

  @Test
  void 跨租户访问详情返回404() throws Exception {
    var a1 = create(a, "SO-HTTP-CROSS", "1");
    http.perform(get("/warehouse/inventory/outbound-orders/{id}", a1.id())
            .header("Authorization", "Bearer tenant-b"))
        .andExpect(status().isNotFound());
  }

  @Test
  void 无读权限被拒() throws Exception {
    var created = create(a, "SO-HTTP-NOPERM", "1");
    http.perform(get("/warehouse/inventory/outbound-orders/{id}", created.id())
            .header("Authorization", "Bearer no-read"))
        .andExpect(status().is4xxClientError());
  }

  @Test
  void 详情能力端点可访问() throws Exception {
    http.perform(get("/warehouse/inventory/outbound-orders/detail-capabilities")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.quantityPolicies.length()").value(2));
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() {
    var a1 = create(a, "SO-TENANT-A", "1");
    create(b, "SO-TENANT-B", "5");
    // A 读到的是 A 的单，B 读到的是 B 的单，明细互不可见。
    var da = detailOf(a, a1.id());
    assertThat(da.sourceExternalRef()).isEqualTo("SO-TENANT-A");
    assertThat(da.lines().get(0).plannedQuantity()).isEqualByComparingTo("1.000000");
    // B 拿不到 A 的单详情。
    assertThatThrownBy(() -> detailOf(b, a1.id()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或不属于当前租户");
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    var a1 = create(a, "SO-FORGED", "1");
    // 身份是 B，请求里伪造 X-Tenant-Id 指向 A：B 看到的仍然是 B 的（404，A 的单对它不可见）。
    http.perform(get("/warehouse/inventory/outbound-orders/{id}", a1.id())
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());
    // 伪造头也不能让 B 读到 A 的明细行数。
    var bOrder = create(b, "SO-FORGED-B", "1");
    http.perform(get("/warehouse/inventory/outbound-orders/{id}", bOrder.id())
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sourceExternalRef").value("SO-FORGED-B"))
        .andExpect(jsonPath("$.skuLineCount").value(1));
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    var a1 = create(a, "SO-POOL-A", "1");
    var b1 = create(b, "SO-POOL-B", "9");
    // 交替复用同一条物理连接（池容量=1）：A → B → A → B
    for (int i = 0; i < 3; i++) {
      var da = detailOf(a, a1.id());
      assertThat(da.sourceExternalRef()).isEqualTo("SO-POOL-A");
      assertThat(da.lines().get(0).plannedQuantity()).isEqualByComparingTo("1.000000");
      var db = detailOf(b, b1.id());
      assertThat(db.sourceExternalRef()).isEqualTo("SO-POOL-B");
      assertThat(db.lines().get(0).plannedQuantity()).isEqualByComparingTo("9.000000");
    }
    // 本模块新建的四张表（本车道只读、未新建表）逐张确认 FORCE RLS 仍在位。
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
}