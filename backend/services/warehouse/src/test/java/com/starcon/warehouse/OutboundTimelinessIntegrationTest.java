package com.starcon.warehouse;

import static com.starcon.warehouse.OutboundTimelinessModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 8.5.3.1 出库时效。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本测试钉住九件「错了会误导人」的事：</b>
 *
 * <ul>
 *   <li><b>时长断言钉死输入时间戳。</b>本模块是挂钟依赖的重灾区：时长是「两个时间戳之差」。所以夹具里的下单/开工/完成时刻全部是<b>写死的常量</b>，不用 {@code now()} 构造一个会被断言的时长字段。 末位有一组<b>对照用例</b>：把下单时刻挪到窗口外后，同一条断言必须随之改变 —— 写死常量的测试改输入没反应，就说明它压根没在验证时长。
 *   <li><b>四环节都在。</b>订单到出库 / 拣货 / 打包 / 交接四列必须都是真算出来的数，不是常量。
 *   <li><b>终点未发生 = null，不是 0。</b>没有订单时刻投影的单，「订单到出库时长」必须为 null 并计入缺失样本数。夹成 0 会让「还没发」显示成「超快」。
 *   <li><b>阈值真的可配。</b>改阈值后超时判定必须跟着变；且仓库覆盖压过租户默认（两级解析），每条阈值带来源。
 *   <li><b>瓶颈只看内部三环节。</b>总时长（订单到出库）恒最大，把它算进排名会让结论恒为零。
 *   <li><b>超时明细能定位到具体订单 + 具体环节。</b>
 *   <li><b>租户隔离三断言</b>实跑 + 逐张 FORCE RLS 断言。
 *   <li><b>分布不能只给平均数。</b>分位与分桶都要有。
 *   <li><b>跨租户写入被拒</b>：A 租户投递订单时刻到 B 租户的出库单 → 404。
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutboundTimelinessIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      List.of("warehouse.inventory.read", "warehouse.inventory.update");

  // ------------------------------------------------------------ 钉死的时钟常量
  // 全用 UTC 固定瞬间，不用 now()：时长 = 终点 − 起点，任何一处飘动都会让断言变成
  // 「测的是今天几点几分」而不是「测的是两个时间戳之差」。
  /** 订单下单时刻（UTC）。 */
  static final OffsetDateTime T_ORDER = OffsetDateTime.of(2026, 3, 2, 1, 0, 0, 0, ZoneOffset.UTC);
  /** 拣货开始 = 下单后 1 小时 → 拣货时长应为 120 分钟。 */
  static final OffsetDateTime T_PICK_START = OffsetDateTime.of(2026, 3, 2, 2, 0, 0, 0, ZoneOffset.UTC);
  /** 拣货完成 = 开始后 120 分钟。 */
  static final OffsetDateTime T_PICK_END = OffsetDateTime.of(2026, 3, 2, 4, 0, 0, 0, ZoneOffset.UTC);
  /** 打包开始 = 拣货完成后 30 分钟 → 打包时长应为 45 分钟。 */
  static final OffsetDateTime T_PACK_START = OffsetDateTime.of(2026, 3, 2, 4, 30, 0, 0, ZoneOffset.UTC);
  /** 打包完成 = 开始后 45 分钟。 */
  static final OffsetDateTime T_PACK_END = OffsetDateTime.of(2026, 3, 2, 5, 15, 0, 0, ZoneOffset.UTC);
  /** 交接单建单 = 打包完成后 15 分钟 → 交接时长应为 30 分钟。 */
  static final OffsetDateTime T_HANDOVER_OPEN = OffsetDateTime.of(2026, 3, 2, 5, 30, 0, 0, ZoneOffset.UTC);
  /** 实际交接 = 建单后 30 分钟。 */
  static final OffsetDateTime T_HANDOVER_AT = OffsetDateTime.of(2026, 3, 2, 6, 0, 0, 0, ZoneOffset.UTC);
  /** 发货时刻 → 订单到出库 = T_SHIPPED − T_ORDER = 5 天 5 小时 = 7500 分钟。 */
  static final OffsetDateTime T_SHIPPED = OffsetDateTime.of(2026, 3, 7, 6, 0, 0, 0, ZoneOffset.UTC);
  /** 区间首末（Asia/Shanghai 自然日，锚住上面的 UTC 瞬间）。 */
  static final LocalDate FROM = LocalDate.of(2026, 3, 1);
  static final LocalDate TO = LocalDate.of(2026, 3, 31);

  /** 期望值：全部由上面的常量算出来，不是另填的一组数。 */
  static final BigDecimal EXPECT_ORDER_TO_OUTBOUND = new BigDecimal("7500.000000");
  static final BigDecimal EXPECT_PICKING = new BigDecimal("120.000000");
  static final BigDecimal EXPECT_PACKING = new BigDecimal("45.000000");
  static final BigDecimal EXPECT_HANDOVER = new BigDecimal("30.000000");

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-outbound-timeliness-" + tenant + "-" + ordinal).getBytes());
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
            byte[] body = new ObjectMapper().writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer read-only", actor(user, a, List.of(grant("ALL", List.of("warehouse.inventory.read")))));
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

  @Autowired OutboundTimelinessService service;
  @Autowired PickTaskService pickTasks;
  @Autowired RecountScanService recounts;
  @Autowired PackingService packing;
  @Autowired ShipmentHandoverService handovers;
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
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // truncate 顺序按外键：先子后父。与 8.3.4.1 的 truncate 列表同一套覆盖范围 ——
      // 本模块的夹具会经服务层建出 8.3 全链路的行，少截一张就会在下一条用例里撞唯一键。
      s.execute(
          "truncate outbound_order_time_snapshot, outbound_timeliness_threshold,"
              + " shipment_handover_event, shipment_handover_reservation,"
              + " shipment_handover_item, shipment_handover_parcel, shipment_handover,"
              + " packing_event, parcel_item, parcel, packing_order, packing_material,"
              + " recount_event, recount_exception, recount_scan, recount_line, recount_session,"
              + " recount_rule, pick_task_serial, pick_task_pick, pick_task_exception,"
              + " pick_task_line, pick_task_event, pick_task, outbound_pick_request,"
              + " outbound_order_event, outbound_order_line, outbound_order,"
              + " inventory_serial_operation, inventory_serial_movement, inventory_serial,"
              + " stock_bucket, inventory_balance, warehouse_location,"
              + " inventory_lot, inventory_quality_status, legal_entity_ref, sku_ref, warehouse"
              + " cascade");
      s.execute("truncate inventory_entry, inventory_transaction restart identity cascade");
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
                "SKU-TIMELY",
                "时效测试商品");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role)"
                    + " values(?,?,?,?,?)",
                idFor(tenant, 3),
                tenant,
                "SELLABLE",
                "可售",
                "SELLABLE");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,kind,country_code,timezone,"
                    + "default_legal_entity_id,status) values(?,?,?,?,?,?,?,?,?)",
                idFor(tenant, 4),
                tenant,
                "WH-" + (tenant.equals(a) ? "A" : "B"),
                tenant.equals(a) ? "A 仓" : "B 仓",
                "LOCAL",
                "CN",
                "Asia/Shanghai",
                idFor(tenant, 1),
                "ACTIVE");
            return null;
          });
    }
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  <T> T asRead(UUID tenant, java.util.function.Supplier<T> work) {
    return access.run(actorFor(tenant), "warehouse.inventory.read", work);
  }

  <T> T asWrite(UUID tenant, java.util.function.Supplier<T> work) {
    return access.run(actorFor(tenant), "warehouse.inventory.update", work);
  }

  UUID wh(UUID tenant) {
    return idFor(tenant, 4);
  }

  UUID sku(UUID tenant) {
    return idFor(tenant, 2);
  }

  UUID sellable(UUID tenant) {
    return idFor(tenant, 3);
  }

  // ============================================================ 夹具

  // ============================================================ 夹具

  UUID location(UUID tenant, String code, String type) {
    return asRead(
        tenant,
        () -> {
          var existing =
              sql.fetchOne(
                  "select id from warehouse_location where tenant_id = ?"
                      + " and warehouse_id=? and code=?",
                  tenant,
                  wh(tenant),
                  code);
          if (existing != null) {
            return existing.get("id", UUID.class);
          }
          UUID id = UUID.randomUUID();
          sql.execute(
              "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,status,"
                  + "pickable) values(?,?,?,?,?,?,'ACTIVE',true)",
              id,
              tenant,
              wh(tenant),
              code,
              code + " 位",
              type);
          return id;
        });
  }

  /**
   * 在手库存。reserved 恒为 0 —— 本模块只读作业时间戳，不碰库存语义。
   *
   * <p><b>幂等</b>：{@code inventory_balance} 对 (租户, SKU, 仓, 法人, 质量状态) 唯一，
   * 一条用例里建多张出库单时第二次插入就会撞键。命中已有余额就补货而不是再插一行 ——
   * 本模块测的是时间戳，库存够不够不是被测对象。
   */
  void bucket(UUID tenant, UUID loc, String onHand) {
    asRead(
        tenant,
        () -> {
          var existing =
              sql.fetchOne(
                  "select id,on_hand from inventory_balance where tenant_id=? and sku_id=?"
                      + " and warehouse_id=? and legal_entity_id=? and quality_status_id=?",
                  tenant,
                  sku(tenant),
                  wh(tenant),
                  idFor(tenant, 1),
                  sellable(tenant));
          UUID balance;
          if (existing != null) {
            balance = existing.get("id", UUID.class);
            var have = existing.get("on_hand", java.math.BigDecimal.class);
            sql.execute(
                "update inventory_balance set on_hand=? where tenant_id=? and id=?",
                have.add(new BigDecimal(onHand)),
                tenant,
                balance);
          } else {
            balance = UUID.randomUUID();
            sql.execute(
                "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                    + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,cast(? as numeric),0)",
                balance,
                tenant,
                sku(tenant),
                wh(tenant),
                idFor(tenant, 1),
                sellable(tenant),
                onHand);
          }
          sql.execute(
              "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,"
                  + "location_id,lot_id,quality_status_id,balance_id,on_hand,reserved,"
                  + "last_movement_at) values(?,?,?,?,?,?,null,?,?,cast(? as numeric),0,"
                  + "?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              idFor(tenant, 1),
              sku(tenant),
              wh(tenant),
              loc,
              sellable(tenant),
              balance,
              onHand,
              T_ORDER);
          return null;
        });
  }

  /**
   * 建一张已发货的完整出库单（拣货 / 打包 / 交接三环节时间戳齐备）。
   *
   * <p><b>走 8.3 的真实服务层，不裸插终态行。</b>这不是形式主义：V121 的
   * {@code parcel_measure_guard} 要求包裹只能在打包单还是 {@code OPEN} 时插入，
   * {@code guard_packing_order_status_fn} 又要求判 {@code PACKED} 时每个包裹都已装齐并称重；
   * V123 的交接确认守卫进一步要求明细行实扣量等于应发量且挂在具体库存行上。
   * 裸 SQL 插一行终态要么当场被守卫拒，要么造出现实里不存在的形状 —— 时效列读的就是这几张表，
   * 夹具一旦失真，四条时长断言就是在验一个不存在的状态。
   *
   * <p><b>链路走完后把四类时间戳回钉到文件头的常量</b>（{@link #pinTimestamps}）：
   * 服务层一律用 {@code now()}，而时长断言必须是「两个写死瞬间之差」，不能变成「测的是今天几点几分」。
   */
  UUID shippedOrder(UUID tenant, int ordinal, OffsetDateTime shippedAt) {
    var outboundId = asWrite(tenant, () -> buildShipped(tenant, ordinal, shippedAt));
    asWrite(tenant, () -> {
      pinTimestamps(tenant, outboundId, shippedAt);
      return null;
    });
    return outboundId;
  }

  /** 全链路：建单 → 拣货完成 → 复核通过 → 打包完成 → 交接确认 → 发货。 */
  UUID buildShipped(UUID tenant, int ordinal, OffsetDateTime shippedAt) {
    var storage = location(tenant, "ST-" + ordinal, "STORAGE");
    // 拣完的货必须有一个明确去处：拣货任务要求本仓有启用的 SHIPPING 暂存位当打包区。
    // 刻意**每仓只建一个**（不按 ordinal 建）：一个仓有两个启用的发货暂存位时，
    // 拣货任务会拒着要调用方指定 —— 多张出库单的用例就会撞上这条守卫。
    location(tenant, "SHIP", "SHIPPING");
    bucket(tenant, storage, "100");

    var outboundId = UUID.randomUUID();
    sql.execute(
        "insert into outbound_order(id,tenant_id,order_code,source_type,source_external_ref,"
            + " warehouse_id,carrier_code,status,created_by_user_id,created_at,updated_at)"
            + " values(?,?,?,'SALES',?,?,'CARR-1','PENDING_PICKING',?,?::timestamptz,"
            + "?::timestamptz)",
        outboundId,
        tenant,
        "OB-TEST-" + ordinal,
        "SO-" + ordinal,
        wh(tenant),
        user,
        T_ORDER,
        T_ORDER);
    var lineId = UUID.randomUUID();
    sql.execute(
        "insert into outbound_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
            + "quantity) values(?,?,?,1,?,?,?,1.000000)",
        lineId,
        tenant,
        outboundId,
        sku(tenant),
        "SKU-TIMELY",
        "时效测试商品");

    // 8.3.1.1 → 8.3.2.1 的交接请求：拣货任务是「接手方」，没有这张单它连开工都做不到。
    // 走服务层就必须先补上这道交接 —— 这正是「夹具照抄现实链路」的价值：
    // 裸插终态行永远发现不了中间少了一道交接。
    sql.execute(
        "insert into outbound_pick_request(id,tenant_id,order_id,line_count,"
            + "total_piece_count,requested_by_user_id,requested_at)"
            + " values(?,?,?,1,1.000000,?,?::timestamptz)",
        UUID.randomUUID(),
        tenant,
        outboundId,
        user,
        T_ORDER);
    // 交接请求落定后出库单进入「拣货中」：V72 的状态机只认 PENDING_PICKING → PICKING 这一条边。
    sql.execute(
        "update outbound_order set status='PICKING' where tenant_id=? and id=?",
        tenant,
        outboundId);

    finishPicking(tenant, outboundId);
    var sessionId = passedRecount(tenant, outboundId);
    var packingId = packedOrder(tenant, sessionId, ordinal);
    handedOver(tenant, packingId, shippedAt);

    // 发货：V72 只认 PENDING_SHIPMENT → SHIPPED，且 SHIPPED 必须带 shipped_at 与承运渠道。
    // shipped_at 真正的值由 pinTimestamps 落（这里先满足 CHECK 的非空要求）。
    sql.execute(
        "update outbound_order set status='PENDING_SHIPMENT'"
            + " where tenant_id=? and id=? and status='PENDING_PACKING'",
        tenant,
        outboundId);
    sql.execute(
        "update outbound_order set status='SHIPPED', shipped_at=?::timestamptz"
            + " where tenant_id=? and id=?",
        shippedAt,
        tenant,
        outboundId);
    return outboundId;
  }

  /** 拣货：开任务 → 指派 → 逐行登记 → 完成。 */
  void finishPicking(UUID tenant, UUID orderId) {
    var task =
        asWrite(
                tenant,
                () ->
                    pickTasks.generate(
                        new PickTaskModels.PickTaskGenerateInput(orderId, null, null), user))
            .id();
    var lines = asWrite(tenant, () -> pickTasks.detail(task).lines());
    asWrite(
        tenant,
        () -> pickTasks.assign(task, new PickTaskModels.PickTaskAssignInput(user, null), user));
    for (var line : lines) {
      asWrite(
          tenant,
          () ->
              pickTasks.recordPick(
                  task,
                  new PickTaskModels.PickTaskPickInput(
                      line.id(),
                      line.sourceLocationId(),
                      null,
                      line.plannedQuantity(),
                      null,
                      null,
                      null),
                  user));
    }
    asWrite(tenant, () -> pickTasks.complete(task, user));
  }

  /** 复核：开单 → 扫齐 → 通过。 */
  UUID passedRecount(UUID tenant, UUID orderId) {
    var session =
        asWrite(
            tenant,
            () -> recounts.open(new RecountModels.RecountOpenInput(orderId, null, user, null), user));
    for (var line : asWrite(tenant, () -> recounts.detail(session.id().toString()).lines())) {
      int times = new BigDecimal(line.expectedQuantity()).intValueExact();
      for (int k = 0; k < times; k++) {
        asWrite(
            tenant,
            () ->
                recounts.scan(
                    session.id().toString(),
                    new RecountModels.RecountScanInput("BARCODE", line.skuCode(), null, null),
                    user));
      }
    }
    var verdict = asWrite(tenant, () -> recounts.complete(session.id().toString(), user));
    assertThat(verdict.verdict()).isEqualTo("PASSED");
    return session.id();
  }

  /** 打包：开单 → 建箱 → 装齐 → 称重量尺 → 完成。 */
  UUID packedOrder(UUID tenant, UUID sessionId, int ordinal) {
    var order =
        asWrite(
            tenant, () -> packing.open(new PackingModels.PackingOpenInput(sessionId, user, null), user));
    var material =
        asWrite(
            tenant,
            () ->
                packing.createMaterial(
                    new PackingModels.PackingMaterialInput(
                        "MAT-" + ordinal,
                        "标准纸箱",
                        "PACKAGING",
                        "CARTON",
                        wh(tenant),
                        "30",
                        "20",
                        "15",
                        "200.000",
                        "5000.000"),
                    user));
    var parcel =
        asWrite(
            tenant,
            () ->
                packing.addParcel(
                    order.id().toString(),
                    new PackingModels.ParcelAddInput(material.id(), null, null, null),
                    user));
    for (var line : asRead(tenant, () -> packing.detail(order.id().toString()).baseline())) {
      asWrite(
          tenant,
          () ->
              packing.addItem(
                  order.id().toString(),
                  parcel.id().toString(),
                  new PackingModels.ParcelItemInput(line.id(), line.expectedQuantity(), null, null),
                  user));
    }
    asWrite(
        tenant,
        () ->
            packing.measure(
                order.id().toString(),
                parcel.id().toString(),
                new PackingModels.ParcelMeasureInput("1200.000", "30", "20", "15", null, null),
                user));
    return asWrite(
            tenant,
            () ->
                packing.complete(
                    order.id().toString(), new PackingModels.PackingCompleteInput(user, null), user))
        .id();
  }

  /** 交接：开单 → 登记承运商与运单号 → 确认交接。 */
  void handedOver(UUID tenant, UUID packingOrderId, OffsetDateTime handoverAt) {
    var handover =
        asWrite(
            tenant,
            () ->
                handovers.open(
                    new ShipmentHandoverModels.HandoverOpenInput(
                        packingOrderId, null, null, null, null),
                    user));
    asWrite(
        tenant,
        () ->
            handovers.registerWaybills(
                handover.id().toString(),
                new ShipmentHandoverModels.HandoverWaybillInput(
                    "CARR-1", "顺丰速运", "WB-" + handover.id().toString().substring(0, 8),
                    "王师傅", "13800000000", null, null),
                user));
    asWrite(
        tenant,
        () ->
            handovers.confirm(
                handover.id().toString(),
                new ShipmentHandoverModels.HandoverConfirmInput(
                    user, handoverAt.toString(), "王师傅", "13800000000", null),
                user));
  }

  /**
   * 把四个环节的时间戳回钉到文件头的常量。
   *
   * <p>服务层一律用 {@code now()}，而本模块的断言必须钉在「两个写死瞬间之差」上。
   * 这一步只改时间列，不碰状态与计量 —— V72 的终态冻结只锁来源/发货仓/承运渠道/创建人，
   * {@code shipped_at} 不在其中，所以回钉发货时刻是合法的。
   *
   * <p>顺序有讲究：{@code pack/handover} 的 {@code completed_at} 与状态有同进同出的 CHECK，
   * 所以时间与状态在同一条 UPDATE 里落。
   */
  void pinTimestamps(UUID tenant, UUID outboundId, OffsetDateTime shippedAt) {
    sql.execute(
        "update pick_task set started_at=?::timestamptz, completed_at=?::timestamptz"
            + " where tenant_id=? and outbound_order_id=?",
        T_PICK_START,
        T_PICK_END,
        tenant,
        outboundId);
    sql.execute(
        "update packing_order set started_at=?::timestamptz, completed_at=?::timestamptz"
            + " where tenant_id=? and outbound_order_id=?",
        T_PACK_START,
        T_PACK_END,
        tenant,
        outboundId);
    sql.execute(
        "update shipment_handover set created_at=?::timestamptz, handover_at=?::timestamptz,"
            + " completed_at=?::timestamptz where tenant_id=? and outbound_order_id=?",
        T_HANDOVER_OPEN,
        T_HANDOVER_AT,
        T_HANDOVER_AT,
        tenant,
        outboundId);
    sql.execute(
        "update outbound_order set status='SHIPPED', shipped_at=?::timestamptz"
            + " where tenant_id=? and id=?",
        shippedAt,
        tenant,
        outboundId);
  }

  OutboundTimelinessRow pushOrderTime(UUID tenant, UUID outboundId, OffsetDateTime placedAt) {
    return access.run(
        actorFor(tenant),
        "warehouse.inventory.update",
        () ->
            service.recordOrderTime(
                new OutboundOrderTimeInput(outboundId, "SALES", placedAt, "9.1.1"), user));
  }

  OutboundThresholdView configure(UUID tenant, UUID warehouseId, String stage, String minutes) {
    return access.run(
        actorFor(tenant),
        "warehouse.inventory.update",
        () ->
            service.configureThreshold(
                new OutboundThresholdInput(warehouseId, stage, new BigDecimal(minutes), true, "测试阈值"),
                user));
  }

  OutboundTimelinessReport report(UUID tenant, UUID warehouseId) {
    return access.run(
        actorFor(tenant),
        "warehouse.inventory.read",
        () -> service.report(FROM, TO, warehouseId, 0, 50));
  }

  // ============================================================ 四环节都在

  @Test
  void fourStagesAreRealComputedDurations() {
    var outboundId = shippedOrder(a, 1, T_SHIPPED);
    pushOrderTime(a, outboundId, T_ORDER);

    var report = report(a, wh(a));

    assertThat(report.shippedOrderCount()).isEqualTo(1);
    assertThat(report.rows()).hasSize(1);
    var row = report.rows().get(0);
    // 四列全部由「两个时间戳之差」算出来，不是常量。
    assertThat(row.orderToOutboundMinutes()).isEqualByComparingTo(EXPECT_ORDER_TO_OUTBOUND);
    assertThat(row.pickingMinutes()).isEqualByComparingTo(EXPECT_PICKING);
    assertThat(row.packingMinutes()).isEqualByComparingTo(EXPECT_PACKING);
    assertThat(row.handoverMinutes()).isEqualByComparingTo(EXPECT_HANDOVER);
  }

  /**
   * <b>对照用例：把输入时间戳改成窗口外的值重跑，断言结果必须随之改变。</b>
   *
   * <p>这条用例是防「断言其实没在验证时长」的：如果改了下单时刻而 {@code orderToOutboundMinutes}
   * 纹丝不动，说明这一列要么读的是别的东西、要么根本没参与计算（写死常量）。 改早 48 小时 → 时长多 2880 分钟。
   */
  @Test
  void movingInputTimestampChangesTheDuration() {
    var outboundId = shippedOrder(a, 1, T_SHIPPED);
    pushOrderTime(a, outboundId, T_ORDER);
    var before = report(a, wh(a)).rows().get(0).orderToOutboundMinutes();
    assertThat(before).isEqualByComparingTo(EXPECT_ORDER_TO_OUTBOUND);

    // 同一条出库单，把下单时刻往前挪 48 小时（幂等 upsert 覆盖）。
    var shifted = T_ORDER.minusHours(48);
    pushOrderTime(a, outboundId, shifted);
    var after = report(a, wh(a)).rows().get(0).orderToOutboundMinutes();

    assertThat(after)
        .isEqualByComparingTo(EXPECT_ORDER_TO_OUTBOUND.add(new BigDecimal("2880.000000")))
        .isNotEqualByComparingTo(before);
  }

  /** 同样对照「终点」侧：发货时刻提前 120 分钟，订单到出库时长必须同步减少。 */
  @Test
  void movingShipTimestampChangesTheDuration() {
    var outboundId = shippedOrder(a, 1, T_SHIPPED);
    pushOrderTime(a, outboundId, T_ORDER);
    assertThat(report(a, wh(a)).rows().get(0).orderToOutboundMinutes())
        .isEqualByComparingTo(EXPECT_ORDER_TO_OUTBOUND);

    access.run(
        actorA,
        "warehouse.inventory.update",
        () -> {
          sql.execute(
              "update outbound_order set shipped_at = ?::timestamptz"
                  + " where tenant_id = ? and id = ?",
              T_SHIPPED.minusMinutes(120),
              a,
              outboundId);
          return null;
        });

    assertThat(report(a, wh(a)).rows().get(0).orderToOutboundMinutes())
        .isEqualByComparingTo(EXPECT_ORDER_TO_OUTBOUND.subtract(new BigDecimal("120.000000")));
  }

  // ============================================================ 缺投影 = 不可计算

  @Test
  void missingOrderTimestampIsNullNotZero() {
    var outboundId = shippedOrder(a, 1, T_SHIPPED);
    // 刻意**不**投递订单时刻。

    var report = report(a, wh(a));
    var row = report.rows().get(0);

    // 缺起点 → null，绝不是 0（夹成 0 会把「还没发」显示成「超快」）。
    assertThat(row.orderToOutboundMinutes()).isNull();
    // 其它三个环节的时刻齐备，必须照常算出来。
    assertThat(row.pickingMinutes()).isEqualByComparingTo(EXPECT_PICKING);
    assertThat(row.packingMinutes()).isEqualByComparingTo(EXPECT_PACKING);
    assertThat(row.handoverMinutes()).isEqualByComparingTo(EXPECT_HANDOVER);
    // 缺失样本数单独报，不混进分母。
    assertThat(report.missingSampleCounts()).containsEntry("ORDER_TO_OUTBOUND", 1L);
    // 该环节 sampledCount = 0 → 平均/分位全为 null，不是 0。
    var dist =
        report.distribution().stream()
            .filter(d -> d.stageCode().equals("ORDER_TO_OUTBOUND"))
            .findFirst()
            .orElseThrow();
    assertThat(dist.sampledCount()).isZero();
    assertThat(dist.avgMinutes()).isNull();
    assertThat(dist.p50Minutes()).isNull();
    assertThat(dist.maxMinutes()).isNull();
    // 覆盖率 0%（1 张单里 0 张可算）。
    assertThat(dist.coverageRate()).isEqualByComparingTo("0.0000");
  }

  /** 投影写入后同一条单立刻变得可算 —— 证明这一列真的从投影表读。 */
  @Test
  void pushingOrderTimestampMakesItComputable() {
    var outboundId = shippedOrder(a, 1, T_SHIPPED);
    assertThat(report(a, wh(a)).rows().get(0).orderToOutboundMinutes()).isNull();

    pushOrderTime(a, outboundId, T_ORDER);

    var row = report(a, wh(a)).rows().get(0);
    assertThat(row.orderToOutboundMinutes()).isEqualByComparingTo(EXPECT_ORDER_TO_OUTBOUND);
    assertThat(report(a, wh(a)).missingSampleCounts())
        .containsEntry("ORDER_TO_OUTBOUND", 0L);
  }

  /** 负时长（终点早于起点）= null，不夹成 0。 */
  @Test
  void negativeDurationIsNullNotClampedToZero() {
    var outboundId = shippedOrder(a, 1, T_SHIPPED);
    // 下单时刻晚于发货时刻：时序颠倒的脏数据。
    pushOrderTime(a, outboundId, T_SHIPPED.plusHours(10));

    var row = report(a, wh(a)).rows().get(0);
    assertThat(row.orderToOutboundMinutes()).isNull();
  }

  /** 窗口外的单子不可见 —— 证明筛选真的按 shipped_at 的自然日走。 */
  @Test
  void ordersOutsideWindowAreInvisible() {
    // 发货在 2026-06，与 3 月的区间差三个月。
    var outboundId =
        shippedOrder(a, 1, OffsetDateTime.of(2026, 6, 15, 6, 0, 0, 0, ZoneOffset.UTC));
    pushOrderTime(a, outboundId, T_ORDER);

    assertThat(report(a, wh(a)).shippedOrderCount()).isZero();
    assertThat(report(a, wh(a)).rows()).isEmpty();
  }

  // ============================================================ 阈值可配

  @Test
  void thresholdIsConfigurableAndChangesOverdueJudgement() {
    var outboundId = shippedOrder(a, 1, T_SHIPPED);
    pushOrderTime(a, outboundId, T_ORDER);

    // 内置兜底：拣货阈值 240 分钟，实际 120 → 不超时。
    assertThat(report(a, wh(a)).rows().get(0).overdueStages()).doesNotContain("PICKING");

    // 夹具总时长 7500 分钟超过内置兜底 1440，那条超时是真实存在的；先把总时长的阈值
    // 抬到它之上，本用例才能单独观察「改 PICKING 阈值 → 超时判定跟着变」这一件事。
    configure(a, null, "ORDER_TO_OUTBOUND", "10000");
    // 配一个 60 分钟的租户默认阈值 → 120 > 60 → 超时。
    configure(a, null, "PICKING", "60");

    var report = report(a, wh(a));
    assertThat(report.rows().get(0).overdueStages()).contains("PICKING");
    assertThat(report.rows().get(0).overdueStageCount()).isEqualTo(1);
    // 阈值来源如实报出，且每个环节都带来源。
    var picking =
        report.thresholds().stream()
            .filter(t -> t.stageCode().equals("PICKING"))
            .findFirst()
            .orElseThrow();
    assertThat(picking.thresholdMinutes()).isEqualByComparingTo("60.000000");
    assertThat(picking.source()).isEqualTo(ThresholdSource.TENANT.name());
    // 没配的环节仍报 BUILTIN。
    assertThat(
            report.thresholds().stream()
                .filter(t -> t.stageCode().equals("HANDOVER"))
                .findFirst()
                .orElseThrow()
                .source())
        .isEqualTo(ThresholdSource.BUILTIN.name());
  }

  @Test
  void warehouseThresholdOverridesTenantDefault() {
    var outboundId = shippedOrder(a, 1, T_SHIPPED);
    pushOrderTime(a, outboundId, T_ORDER);

    configure(a, null, "PACKING", "10"); // 租户默认：10 分钟 → 45 > 10 超时
    configure(a, wh(a), "PACKING", "600"); // 仓库覆盖：600 分钟 → 45 不超时

    var report = report(a, wh(a));
    assertThat(report.rows().get(0).overdueStages()).doesNotContain("PACKING");
    var packing =
        report.thresholds().stream()
            .filter(t -> t.stageCode().equals("PACKING"))
            .findFirst()
            .orElseThrow();
    assertThat(packing.thresholdMinutes()).isEqualByComparingTo("600.000000");
    assertThat(packing.source()).isEqualTo(ThresholdSource.WAREHOUSE.name());
  }

  /** 阈值幂等：重复配置同一个 (仓库, 环节) 只改那一行，不插第二条。 */
  @Test
  void thresholdUpsertIsIdempotent() {
    configure(a, null, "HANDOVER", "100");
    configure(a, null, "HANDOVER", "250");

    var count =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                sql.fetchOne(
                        "select count(*)::bigint from outbound_timeliness_threshold"
                            + " where tenant_id = ? and stage_code = 'HANDOVER'",
                        a)
                    .get(0, Long.class));
    assertThat(count).isEqualTo(1L);
    assertThat(report(a, wh(a)).thresholds().stream()
            .filter(t -> t.stageCode().equals("HANDOVER"))
            .findFirst()
            .orElseThrow()
            .thresholdMinutes())
        .isEqualByComparingTo("250.000000");
  }

  @Test
  void negativeThresholdRejected() {
    assertThatThrownBy(
            () -> configure(a, null, "PICKING", "-1"))
        .hasMessageContaining("阈值不能为负数");
  }

  @Test
  void unknownStageRejectedWithAvailableValues() {
    assertThatThrownBy(() -> configure(a, null, "NOT_A_STAGE", "10"))
        .hasMessageContaining("未知的环节");
  }

  // ============================================================ 瓶颈 + 明细 + 分布

  @Test
  void bottleneckIsPickedAmongInternalStagesOnly() {
    var outboundId = shippedOrder(a, 1, T_SHIPPED);
    pushOrderTime(a, outboundId, T_ORDER);

    // 让打包成为唯一超时的内部环节；订单到出库（7500 分钟）总时长远大于任何环节。
    configure(a, null, "PACKING", "10");

    var report = report(a, wh(a));
    var bottleneck = report.bottleneck();

    assertThat(bottleneck.stageCode()).isEqualTo("PACKING");
    assertThat(bottleneck.overdueCount()).isEqualTo(1);
    assertThat(bottleneck.sampledCount()).isEqualTo(1);
    assertThat(bottleneck.basis()).contains("总时长（订单到出库）不参与排名");
    // 总时长确实最大，但它不该出现在瓶颈里。
    var total =
        report.distribution().stream()
            .filter(d -> d.stageCode().equals("ORDER_TO_OUTBOUND"))
            .findFirst()
            .orElseThrow();
    assertThat(total.avgMinutes()).isGreaterThan(bottleneck.avgMinutes());
  }

  @Test
  void bottleneckIsNullWhenNoInternalStageHasSamples() {
    var report = report(a, wh(a));
    assertThat(report.bottleneck().stageCode()).isNull();
    // 不给占位环节，并说清「这不等于没有瓶颈」。
    assertThat(report.bottleneck().basis()).contains("无法判定瓶颈");
  }

  @Test
  void overdueDetailLocatesOrderAndStage() {
    var outboundId = shippedOrder(a, 1, T_SHIPPED);
    pushOrderTime(a, outboundId, T_ORDER);
    configure(a, null, "PICKING", "60"); // 120 > 60
    // 夹具的订单到出库是 7500 分钟，本身就超过内置兜底 1440 —— 那是另一条真实超时，
    // 但这条用例要验的是「明细能定位到具体环节」，所以把总时长的阈值抬到它之上，
    // 让 PICKING 成为**唯一**超时环节，明细条数才能与断言一一对应。
    configure(a, null, "ORDER_TO_OUTBOUND", "10000");

    var report = report(a, wh(a));
    assertThat(report.overdue().total()).isEqualTo(1);
    var detail = report.overdue().items().get(0);
    assertThat(detail.outboundOrderId()).isEqualTo(outboundId);
    assertThat(detail.stageCode()).isEqualTo("PICKING");
    assertThat(detail.stageLabel()).isEqualTo("拣货时长");
    assertThat(detail.actualMinutes()).isEqualByComparingTo(EXPECT_PICKING);
    assertThat(detail.thresholdMinutes()).isEqualByComparingTo("60.000000");
    // 超出多少分钟 —— 绩效申诉要的是这个差值。
    assertThat(detail.excessMinutes()).isEqualByComparingTo("60.000000");
    // 环节自己的终点时刻，不是发货时刻。
    assertThat(detail.occurredAt()).isEqualTo(T_PICK_END);
    // 超时单数与超时单率（分母 = 已发货单数）。
    assertThat(report.overdueOrderCount()).isEqualTo(1);
    assertThat(report.overdueOrderRate()).isEqualByComparingTo("100.0000");
  }

  @Test
  void distributionIsNotJustAnAverage() {
    // 三张单：30 / 90 / 150 分钟的打包时长 —— 刻意取三个落在不同分位桶的值，
    // 且中位数（90）与最大值（150）都能把分位数与分桶区分开。
    // 刻意不用 30*i：那样是 30/60/90，90 与 60 同落「2 小时内」桶，第二个桶会装 2 单，
    // 分桶就区分不出「中位数」和「最大值」了（这条断言最初就是这么写错的）。
    long[] packMinutesPerOrder = {30L, 90L, 150L};
    for (int i = 1; i <= 3; i++) {
      final long packMinutes = packMinutesPerOrder[i - 1];
      var outboundId = shippedOrder(a, i, T_SHIPPED.plusDays(i));
      access.run(
          actorA,
          "warehouse.inventory.update",
          () -> {
            sql.execute(
                "update packing_order set started_at = ?::timestamptz,"
                    + " completed_at = ?::timestamptz where tenant_id = ? and outbound_order_id = ?",
                T_PACK_START,
                T_PACK_START.plusMinutes(packMinutes),
                a,
                outboundId);
            return null;
          });
      pushOrderTime(a, outboundId, T_ORDER);
    }

    var packing =
        report(a, wh(a)).distribution().stream()
            .filter(d -> d.stageCode().equals("PACKING"))
            .findFirst()
            .orElseThrow();

    assertThat(packing.sampledCount()).isEqualTo(3);
    // (30+90+150)/3 = 90。平均数单看会以为「和 p50 一样」，但分布要看的是分位数能不能分开。
    assertThat(packing.avgMinutes()).isEqualByComparingTo("90.000000");
    assertThat(packing.p50Minutes()).isEqualByComparingTo("90.000000");
    assertThat(packing.maxMinutes()).isEqualByComparingTo("150.000000");
    // p90 走线性插值：[30,90,150] 的 p90 落在 90 与 150 之间，必非空且大于 p50。
    assertThat(packing.p90Minutes()).isNotNull();
    assertThat(packing.p90Minutes()).isGreaterThan(packing.p50Minutes());
    // 分桶：30 分钟内 1 单、2 小时内 1 单、8 小时内 1 单。
    var buckets = packing.buckets();
    assertThat(buckets).hasSize(5);
    assertThat(buckets.get(0).orderCount()).isEqualTo(1);
    assertThat(buckets.get(1).orderCount()).isEqualTo(1);
    assertThat(buckets.get(2).orderCount()).isEqualTo(1);
    assertThat(buckets.get(3).orderCount()).isZero();
    assertThat(buckets.get(4).orderCount()).isZero();
    assertThat(buckets.get(0).share()).isEqualByComparingTo("33.3333");
  }

  // ============================================================ 写入校验

  @Test
  void pushingOrderTimeRejectsMissingTimestamp() {
    var outboundId = shippedOrder(a, 1, T_SHIPPED);
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () ->
                        service.recordOrderTime(
                            new OutboundOrderTimeInput(outboundId, "SALES", null, "9.1.1"), user)))
        .hasMessageContaining("必须提供订单下单时刻");
  }

  @Test
  void pushingOrderTimeRejectsMismatchedSourceType() {
    var outboundId = shippedOrder(a, 1, T_SHIPPED);
    // 出库单是 SALES，报成 TRANSFER 必须被拒：同一个「订单时刻」被贴到另一类单上，
    // 会算出一条现实里不存在的时效，而这条数是要拿去绩效申诉的。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () ->
                        service.recordOrderTime(
                            new OutboundOrderTimeInput(
                                outboundId, "TRANSFER", T_ORDER, "9.1.1"),
                            user)))
        .hasMessageContaining("来源类型与出库单不一致");
    // 被拒后没有落库：来源类型不符的投递不许留下一行半截快照。
    assertThat(
            asRead(
                    a,
                    () ->
                        sql.fetchOne(
                                "select count(*)::bigint from outbound_order_time_snapshot"
                                    + " where tenant_id=? and outbound_order_id=?",
                                a,
                                outboundId)
                            .get(0, Long.class)))
        .isZero();
  }

  // ============================================================ 多租户三断言

  @Test
  void tenantIsolationThreeAssertions() throws Exception {
    var aOrder = shippedOrder(a, 1, T_SHIPPED);
    var bOrder = shippedOrder(b, 1, T_SHIPPED);
    pushOrderTime(a, aOrder, T_ORDER);
    pushOrderTime(b, bOrder, T_ORDER);

    // ① 两租户互不可见：A 只看得到自己的那张单。
    var reportA = report(a, wh(a));
    var reportB = report(b, wh(b));
    assertThat(reportA.rows()).extracting(OutboundTimelinessRow::outboundOrderId)
        .containsExactly(aOrder);
    assertThat(reportB.rows()).extracting(OutboundTimelinessRow::outboundOrderId)
        .containsExactly(bOrder);

    // 用 B 的出库单 id 去查 → 查不到（写入与读取都按租户隔离）。
    assertThatThrownBy(() -> pushOrderTime(a, bOrder, T_ORDER))
        .hasMessageContaining("不存在");

    // ② 伪造 X-Tenant-Id 头无效：租户只来自已认证成员关系。
    http.perform(
            get("/warehouse/outbound-timeliness/report")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString())
                .param("from", FROM.toString())
                .param("to", TO.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[0].outboundOrderId").value(aOrder.toString()));

    // ③ 连接池归还后不串租户：单连接下 A → B → A 三轮。
    for (int i = 0; i < 3; i++) {
      assertThat(report(a, wh(a)).rows()).extracting(OutboundTimelinessRow::outboundOrderId)
          .containsExactly(aOrder);
      assertThat(report(b, wh(b)).rows()).extracting(OutboundTimelinessRow::outboundOrderId)
          .containsExactly(bOrder);
    }
  }

  @Test
  void bothTablesForceRowLevelSecurity() {
    for (var table :
        List.of("outbound_order_time_snapshot", "outbound_timeliness_threshold")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("表 " + table + " 存在").isNotNull();
      assertThat(row.get("relrowsecurity", Boolean.class)).as(table + " RLS 已启用").isTrue();
      assertThat(row.get("relforcerowsecurity", Boolean.class))
          .as(table + " FORCE RLS 已启用")
          .isTrue();
    }
  }

  // ============================================================ HTTP 端点

  @Test
  void capabilitiesReportBasisAndThresholdSources() throws Exception {
    configure(a, null, "PICKING", "90");

    http.perform(get("/warehouse/outbound-timeliness/capabilities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stages.length()").value(4))
        .andExpect(jsonPath("$.timezone").value("Asia/Shanghai"))
        .andExpect(jsonPath("$.builtinThresholds.length()").value(4))
        // 租户默认已配 → 生效阈值报 TENANT。
        .andExpect(jsonPath("$.effectiveThresholds[?(@.stageCode=='PICKING')].source").value("TENANT"))
        // 未配 → 报 BUILTIN，且兜底值对外声明。
        .andExpect(
            jsonPath("$.effectiveThresholds[?(@.stageCode=='PACKING')].source").value("BUILTIN"))
        .andExpect(jsonPath("$.orderTimestampSourceNote").value(org.hamcrest.Matchers.containsString("读不到")))
        .andExpect(jsonPath("$.missingSampleBasisNote").value(org.hamcrest.Matchers.containsString("不是 0")));
  }

  @Test
  void reportEndpointReturnsDistributionBottleneckAndOverdue() throws Exception {
    var outboundId = shippedOrder(a, 1, T_SHIPPED);
    pushOrderTime(a, outboundId, T_ORDER);
    configure(a, null, "PACKING", "10");
    // 与 overdueDetailLocatesOrderAndStage 同理：把总时长阈值抬上去，
    // 让 PACKING 成为唯一超时环节，明细条数才与断言一一对应。
    configure(a, null, "ORDER_TO_OUTBOUND", "10000");

    http.perform(
            get("/warehouse/outbound-timeliness/report")
                .header("Authorization", "Bearer tenant-a")
                .param("from", FROM.toString())
                .param("to", TO.toString())
                .param("warehouseId", wh(a).toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.shippedOrderCount").value(1))
        .andExpect(jsonPath("$.distribution.length()").value(4))
        .andExpect(jsonPath("$.bottleneck.stageCode").value("PACKING"))
        .andExpect(jsonPath("$.overdue.total").value(1))
        .andExpect(jsonPath("$.overdue.items[0].stageCode").value("PACKING"))
        .andExpect(jsonPath("$.rows[0].packingMinutes").value(45.000000))
        .andExpect(jsonPath("$.rows[0].pickingMinutes").value(120.000000))
        .andExpect(jsonPath("$.missingSampleCounts.ORDER_TO_OUTBOUND").value(0));
  }

  @Test
  void recordOrderTimeEndpointWritesProjection() throws Exception {
    var outboundId = shippedOrder(a, 1, T_SHIPPED);

    http.perform(
            post("/warehouse/outbound-timeliness/order-times")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    new ObjectMapper()
                        .writeValueAsString(
                            Map.of(
                                "outboundOrderId", outboundId.toString(),
                                "sourceType", "SALES",
                                "orderPlacedAt", T_ORDER.toString(),
                                "sourceModule", "9.1.1"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orderToOutboundMinutes").value(7500.000000));

    assertThat(report(a, wh(a)).rows().get(0).orderToOutboundMinutes())
        .isEqualByComparingTo(EXPECT_ORDER_TO_OUTBOUND);
  }

  @Test
  void configureThresholdEndpointWritesConfig() throws Exception {
    http.perform(
            put("/warehouse/outbound-timeliness/thresholds")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    new ObjectMapper()
                        .writeValueAsString(
                            Map.of(
                                "warehouseId", wh(a).toString(),
                                "stageCode", "PACKING",
                                "thresholdMinutes", 33.5,
                                "note", "Amazon 截单"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.thresholdMinutes").value(33.500000))
        .andExpect(jsonPath("$.source").value("WAREHOUSE"));
  }

  // ============================================================ 权限

  @Test
  void permissionCodesAreEnforced() throws Exception {
    // 无任何 warehouse 权限 → 403。
    http.perform(
            get("/warehouse/outbound-timeliness/report").header("Authorization", "Bearer no-permission"))
        .andExpect(status().isForbidden());
    // 未登录 → 401。
    http.perform(get("/warehouse/outbound-timeliness/report")).andExpect(status().isUnauthorized());
    // 只有 read → 能读报表，但不能配阈值（update）。
    http.perform(get("/warehouse/outbound-timeliness/report").header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk());
    http.perform(
            put("/warehouse/outbound-timeliness/thresholds")
                .header("Authorization", "Bearer read-only")
                .contentType("application/json")
                .content(
                    new ObjectMapper()
                        .writeValueAsString(
                            Map.of("stageCode", "PICKING", "thresholdMinutes", 10))))
        .andExpect(status().isForbidden());
  }

  @Test
  void rangeAndWarehouseValidation() {
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> service.report(TO, FROM, null, 0, 20)))
        .hasMessageContaining("结束日期不能早于开始日期");

    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> service.report(FROM.minusDays(500), TO, null, 0, 20)))
        .hasMessageContaining("不能超过");

    // 不属于本租户的仓库 → 404（与「不存在」同形，不给猜 id 的侧信道）。
    assertThatThrownBy(() -> report(a, wh(b)))
        .hasMessageContaining("不存在");
  }
}
