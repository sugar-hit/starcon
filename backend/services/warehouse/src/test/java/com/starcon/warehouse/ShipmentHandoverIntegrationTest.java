package com.starcon.warehouse;

import static com.starcon.warehouse.PackingModels.*;
import static com.starcon.warehouse.ShipmentHandoverModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 8.3.4.1 发货交接的集成测试。
 *
 * <p>真 PostgreSQL 16 + Testcontainers，<b>池容量 1</b>（只有复用同一条连接才能证明连接池 归还后不串租户）。多租户三断言逐条实跑：两租户互不可见、伪造
 * tenant header 无效、 连接池归还后不串租户。
 *
 * <p>本模块最要紧的两条断言单独占两个用例： <b>「实发扣减与预占消费在同一事务」</b>（{@link
 * #confirmConsumesReservationInSameTransaction()}） 与 <b>「缺货时整单回滚、一件都不扣」</b>（{@link
 * #insufficientStockRollsBackEverything()}）。 前者证明预占确实被消费掉了（不是「消费了一半」也不是「没消费」），后者证明不会出现
 * 「扣了能扣的、把扣不动的留在货���」这种最恶心的半边状态。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ShipmentHandoverIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      List.of("warehouse.inventory.read", "warehouse.inventory.update");

  static final ZoneId WAREHOUSE_ZONE = ZoneId.of("Asia/Shanghai");

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-handover-" + tenant + "-" + ordinal).getBytes());
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

  @Autowired ShipmentHandoverService service;
  @Autowired PackingService packing;
  @Autowired RecountScanService recounts;
  @Autowired PickTaskService pickTasks;
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
      s.execute(
          "truncate shipment_handover_event, shipment_handover_reservation,"
              + " shipment_handover_item, shipment_handover_parcel, shipment_handover,"
              + " packing_event, parcel_item, parcel, packing_order, packing_material,"
              + " recount_event, recount_exception, recount_scan, recount_line, recount_session,"
              + " recount_rule, pick_wave_event, pick_wave_task, pick_wave, pick_wave_rule,"
              + " pick_task_serial, pick_task_pick, pick_task_exception, pick_task_line,"
              + " pick_task_event, pick_task, outbound_pick_request, outbound_order_event,"
              + " outbound_order_line, outbound_order, reservation_entry, reservation_allocation,"
              + " inventory_reservation, inventory_serial_operation, inventory_serial_movement,"
              + " inventory_serial, stock_bucket, inventory_balance, warehouse_location,"
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
            for (int i = 2; i <= 10; i++) {
              sql.execute(
                  "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                  idFor(tenant, i),
                  tenant,
                  "SKU-" + (i - 1),
                  "商品" + (i - 1));
            }
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 9),
                tenant,
                "SELLABLE",
                "可售",
                "SELLABLE");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,kind,country_code,timezone,"
                    + "default_legal_entity_id,status) values(?,?,?,?,?,?,?,?,?)",
                idFor(tenant, 11),
                tenant,
                "WH-" + (tenant.equals(a) ? "A" : "B"),
                "本租仓库",
                "LOCAL",
                tenant.equals(a) ? "CN" : "US",
                WAREHOUSE_ZONE.getId(),
                idFor(tenant, 1),
                "ACTIVE");
            return null;
          });
    }
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  <T> T as(UUID tenant, java.util.function.Supplier<T> work) {
    return access.run(actorFor(tenant), "warehouse.inventory.read", work);
  }

  <T> T asWrite(UUID tenant, java.util.function.Supplier<T> work) {
    return access.run(actorFor(tenant), "warehouse.inventory.update", work);
  }

  UUID wh(UUID tenant) {
    return idFor(tenant, 11);
  }

  UUID sku(UUID tenant, int n) {
    return idFor(tenant, 1 + n);
  }

  UUID sellable(UUID tenant) {
    return idFor(tenant, 9);
  }

  // ================================================================ 夹具

  UUID location(UUID tenant, String code, String type) {
    return as(
        tenant,
        () -> {
          var existing =
              sql.fetchOne(
                  "select id from warehouse_location where tenant_id"
                      + " = current_setting('app.current_tenant', true)::uuid"
                      + " and warehouse_id=? and code=?",
                  wh(tenant),
                  code);
          if (existing != null) return existing.get("id", UUID.class);
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

  /** 建一条在手库存。<b>reserved 恒为 0</b>：默认「没有实物预占」，要预占请显式调 reserveStock。 */
  void bucket(UUID tenant, UUID loc, UUID skuId, String onHand) {
    as(
        tenant,
        () -> {
          UUID balance = UUID.randomUUID();
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,cast(? as numeric),0)",
              balance,
              tenant,
              skuId,
              wh(tenant),
              idFor(tenant, 1),
              sellable(tenant),
              onHand);
          sql.execute(
              "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,"
                  + "location_id,lot_id,quality_status_id,balance_id,on_hand,reserved,"
                  + "last_movement_at) values(?,?,?,?,?,?,null,?,?,cast(? as numeric),0,"
                  + "?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              idFor(tenant, 1),
              skuId,
              wh(tenant),
              loc,
              sellable(tenant),
              balance,
              onHand,
              OffsetDateTime.now());
          return null;
        });
  }

  /** 打包区（SHIPPING）库位 id：拣完的货都落在这里。 */
  UUID staging(UUID tenant) {
    return as(
        tenant,
        () ->
            sql.fetchOne(
                    "select id from warehouse_location where tenant_id"
                        + " = current_setting('app.current_tenant', true)::uuid"
                        + " and warehouse_id=? and type='SHIPPING'",
                    wh(tenant))
                .get("id", UUID.class));
  }

  /**
   * 只动<b>打包区那一个 bucket</b> + 对应 balance，量按 delta 同步增减。
   *
   * <p>刻意不写「把所有 bucket 都设成某个值」：那会让 {@code inventory_balance} 与 {@code stock_bucket} 的汇总之和不等， V2
   * 的延迟约束触发器会在提交那一刻拒掉 —— 而它拒得对。
   */
  void adjustStagingStock(UUID tenant, UUID skuId, String delta) {
    as(
        tenant,
        () -> {
          UUID loc = staging(tenant);
          sql.execute(
              "update stock_bucket set on_hand=on_hand+cast(? as numeric),"
                  + "last_movement_at=greatest(?::timestamptz,last_movement_at"
                  + " + interval '1 microsecond') where tenant_id"
                  + " = current_setting('app.current_tenant', true)::uuid"
                  + " and sku_id=? and warehouse_id=? and location_id=?",
              delta,
              OffsetDateTime.now(),
              skuId,
              wh(tenant),
              loc);
          sql.execute(
              "update inventory_balance set on_hand=on_hand+cast(? as numeric),updated_at=now()"
                  + " where tenant_id = current_setting('app.current_tenant', true)::uuid"
                  + " and sku_id=? and warehouse_id=?",
              delta,
              skuId,
              wh(tenant));
          return null;
        });
  }

  /**
   * 铺<b>实物预占</b>（7.1.1.1 那一侧：{@code stock_bucket.reserved} 与 {@code inventory_balance.reserved}）。
   *
   * <p>刻意与 {@link #reserve} 分开：前者是「货被圈住了」，后者是「有一条承诺分配」。 两者是两本账（V123
   * §四），本模块交接时<b>同时</b>动它们，而测试必须能分别铺开， 否则「两本账」这件事在用例里根本区分不出来。
   */
  void reserveStock(UUID tenant, UUID skuId, String amount) {
    as(
        tenant,
        () -> {
          UUID loc = staging(tenant);
          sql.execute(
              "update stock_bucket set reserved=reserved+cast(? as numeric),"
                  + "last_movement_at=greatest(?::timestamptz,last_movement_at"
                  + " + interval '1 microsecond') where tenant_id"
                  + " = current_setting('app.current_tenant', true)::uuid"
                  + " and sku_id=? and warehouse_id=? and location_id=?",
              amount,
              OffsetDateTime.now(),
              skuId,
              wh(tenant),
              loc);
          sql.execute(
              "update inventory_balance set reserved=reserved+cast(? as numeric),updated_at=now()"
                  + " where tenant_id = current_setting('app.current_tenant', true)::uuid"
                  + " and sku_id=? and warehouse_id=?",
              amount,
              skuId,
              wh(tenant));
          return null;
        });
  }

  UUID serial(UUID tenant, UUID skuId, String number, UUID loc) {
    return as(
        tenant,
        () -> {
          UUID id = UUID.randomUUID();
          sql.execute(
              "insert into inventory_serial(id,tenant_id,serial_number,sku_id,status,"
                  + "current_location_id) values(?,?,?,?,'IN_STOCK',?)",
              id,
              tenant,
              number,
              skuId,
              loc);
          return id;
        });
  }

  String asCode(UUID tenant, UUID skuId) {
    return as(
        tenant,
        () ->
            sql.fetchOne(
                    "select code from sku_ref where tenant_id"
                        + " = current_setting('app.current_tenant', true)::uuid and id = ?",
                    skuId)
                .get("code", String.class));
  }

  /** 建一张出库单（明细 + 拣货交接），每行同一数量。 */
  UUID outbound(UUID tenant, String code, String quantity, UUID... skuIds) {
    var codes = new java.util.LinkedHashMap<UUID, String>();
    for (UUID skuId : skuIds) codes.put(skuId, asCode(tenant, skuId));
    return as(
        tenant,
        () -> {
          UUID orderId = UUID.randomUUID();
          sql.execute(
              "insert into outbound_order(id,tenant_id,order_code,source_type,source_external_ref,"
                  + "warehouse_id,carrier_code,status,created_by_user_id)"
                  + " values(?,?,?,'SALES',?,?,'SF','PENDING_PICKING',?)",
              orderId,
              tenant,
              code,
              "SO-" + code,
              wh(tenant),
              user);
          BigDecimal total = BigDecimal.ZERO;
          int lineNo = 0;
          for (UUID skuId : skuIds) {
            lineNo++;
            sql.execute(
                "insert into outbound_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,"
                    + "sku_name,quantity) values(?,?,?,?,?,?,?,cast(? as numeric))",
                UUID.randomUUID(),
                tenant,
                orderId,
                lineNo,
                skuId,
                codes.get(skuId),
                "商品" + lineNo,
                quantity);
            total = total.add(new BigDecimal(quantity));
          }
          sql.execute(
              "insert into outbound_pick_request(id,tenant_id,order_id,line_count,"
                  + "total_piece_count,requested_by_user_id,requested_at)"
                  + " values(?,?,?,?,cast(? as numeric),?,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              orderId,
              skuIds.length,
              total,
              user,
              OffsetDateTime.now());
          sql.execute(
              "update outbound_order set status='PICKING' where tenant_id=? and id=?",
              tenant,
              orderId);
          return orderId;
        });
  }

  void ensureStaging(UUID tenant) {
    if (as(
            tenant,
            () ->
                sql.fetchOne(
                    "select 1 as ok from warehouse_location where tenant_id"
                        + " = current_setting('app.current_tenant', true)::uuid"
                        + " and warehouse_id=? and type='SHIPPING' limit 1",
                    wh(tenant)))
        == null) {
      location(tenant, "OUT", "SHIPPING");
    }
  }

  void finishPicking(UUID tenant, UUID orderId, Map<Integer, List<UUID>> serials) {
    ensureStaging(tenant);
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
    for (int i = 0; i < lines.size(); i++) {
      var line = lines.get(i);
      List<UUID> lineSerials = serials == null ? null : serials.getOrDefault(i, List.of());
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
                      lineSerials,
                      null,
                      null),
                  user));
    }
    asWrite(tenant, () -> pickTasks.complete(task, user));
  }

  UUID passedRecount(UUID tenant, UUID orderId, Map<Integer, List<String>> scansByIndex) {
    var session =
        asWrite(
            tenant,
            () ->
                recounts.open(new RecountModels.RecountOpenInput(orderId, null, user, null), user));
    int i = 0;
    for (var line : asWrite(tenant, () -> recounts.detail(session.id().toString()).lines())) {
      List<String> values = scansByIndex == null ? null : scansByIndex.getOrDefault(i, List.of());
      if (values == null) {
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
      } else {
        for (String value : values) {
          asWrite(
              tenant,
              () ->
                  recounts.scan(
                      session.id().toString(),
                      new RecountModels.RecountScanInput("SERIAL", value, null, null),
                      user));
        }
      }
      i++;
    }
    var report = asWrite(tenant, () -> recounts.complete(session.id().toString(), user));
    assertThat(report.verdict()).isEqualTo("PASSED");
    return session.id();
  }

  PackingMaterialRow carton(UUID tenant, String code, String tare, String max) {
    return asWrite(
        tenant,
        () ->
            packing.createMaterial(
                new PackingMaterialInput(
                    code,
                    "纸箱 " + code,
                    "PACKAGING",
                    "CARTON",
                    wh(tenant),
                    "30",
                    "20",
                    "10",
                    tare,
                    max),
                user));
  }

  /** 一路走到「已打包」：开单 + 一箱 + 装齐 + 称重 + 完成。 */
  PackingCreated packedOrder(UUID tenant, UUID sessionId, String code) {
    var order =
        asWrite(tenant, () -> packing.open(new PackingOpenInput(sessionId, user, null), user));
    var material = carton(tenant, "C-" + code, "200", "5000");
    var parcel =
        asWrite(
            tenant,
            () ->
                packing.addParcel(
                    order.id().toString(),
                    new ParcelAddInput(material.id(), null, null, null),
                    user));
    for (var line : as(tenant, () -> packing.detail(order.id().toString()).baseline())) {
      asWrite(
          tenant,
          () ->
              packing.addItem(
                  order.id().toString(),
                  parcel.id().toString(),
                  new ParcelItemInput(line.id(), line.expectedQuantity(), null, null),
                  user));
    }
    asWrite(
        tenant,
        () ->
            packing.measure(
                order.id().toString(),
                parcel.id().toString(),
                new ParcelMeasureInput("1200", "30", "20", "10", null, null),
                user));
    return asWrite(
        tenant,
        () -> packing.complete(order.id().toString(), new PackingCompleteInput(null, null), user));
  }

  /** 一路走到「已打包」的非序列号版本（逐行扫条码）。 */
  record Ready(UUID orderId, UUID sessionId, PackingCreated packing) {}

  Ready ready(UUID tenant, String code, String quantity, UUID... skuIds) {
    return ready(tenant, code, quantity, "100", skuIds);
  }

  /** 初始库存可指定的版本：缺货用例要「先拣得动、装完之后再被调减」，否则在 8.3.2.1 就被拒了。 */
  Ready ready(UUID tenant, String code, String quantity, String stock, UUID... skuIds) {
    UUID loc = location(tenant, "A-1", "STORAGE");
    for (UUID skuId : skuIds) bucket(tenant, loc, skuId, stock);
    UUID orderId = outbound(tenant, code, quantity, skuIds);
    finishPicking(tenant, orderId, null);
    UUID sessionId = passedRecount(tenant, orderId, null);
    return new Ready(orderId, sessionId, packedOrder(tenant, sessionId, code));
  }

  HandoverRow openHandover(UUID tenant, UUID packingOrderId) {
    return asWrite(
        tenant,
        () -> service.open(new HandoverOpenInput(packingOrderId, null, null, null, null), user));
  }

  HandoverRow waybill(UUID tenant, UUID handoverId, String carrier, String no) {
    return asWrite(
        tenant,
        () ->
            service.registerWaybills(
                handoverId.toString(),
                new HandoverWaybillInput(carrier, "顺丰速运", no, "王师傅", "13800000000", null, null),
                user));
  }

  HandoverConfirmed confirm(UUID tenant, UUID handoverId, String at) {
    return asWrite(
        tenant,
        () ->
            service.confirm(
                handoverId.toString(),
                new HandoverConfirmInput(user, at, "王师傅", "13800000000", null),
                user));
  }

  /**
   * 铺一条<b>已承诺</b>的预占（7.2.1.2 的口径：建行 → ALLOCATED 分录 → 承诺 → COMMITTED 分录 → 父预占推进
   * COMMITTED）。这是本模块「预占转实扣」的另一半账。
   */
  record Reserved(UUID reservationId, UUID allocationId, String reservationNo) {}

  Reserved reserve(UUID tenant, UUID skuId, UUID loc, String quantity) {
    return as(
        tenant,
        () -> {
          UUID reservationId = UUID.randomUUID();
          String no = "RSV-" + UUID.randomUUID().toString().substring(0, 8);
          sql.execute(
              "insert into inventory_reservation(id,tenant_id,reservation_no,source_system,"
                  + "source_type,source_ref_code,legal_entity_id,sku_id,requested_quantity)"
                  + " values(?,?,?,'TRADE','SALES_ORDER',?,?,?,cast(? as numeric))",
              reservationId,
              tenant,
              no,
              "SO-1",
              idFor(tenant, 1),
              skuId,
              quantity);
          UUID allocationId = UUID.randomUUID();
          sql.execute(
              "insert into reservation_allocation(id,tenant_id,reservation_id,warehouse_id,location_id,"
                  + "allocated_quantity) values(?,?,?,?,?,cast(? as numeric))",
              allocationId,
              tenant,
              reservationId,
              wh(tenant),
              loc,
              quantity);
          sql.execute(
              "insert into reservation_entry(id,tenant_id,reservation_id,allocation_id,transition,"
                  + "from_status,to_status,quantity,occurred_at) values(?,?,?,?,'ALLOCATED',null,"
                  + "'HELD',cast(? as numeric),?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              reservationId,
              allocationId,
              quantity,
              OffsetDateTime.now());
          sql.execute(
              "update reservation_allocation set status='COMMITTED',committed_at=?::timestamptz"
                  + " where tenant_id=? and id=?",
              OffsetDateTime.now(),
              tenant,
              allocationId);
          sql.execute(
              "insert into reservation_entry(id,tenant_id,reservation_id,allocation_id,transition,"
                  + "from_status,to_status,quantity,occurred_at) values(?,?,?,?,'COMMITTED','HELD',"
                  + "'COMMITTED',cast(? as numeric),?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              reservationId,
              allocationId,
              quantity,
              OffsetDateTime.now());
          sql.execute(
              "update inventory_reservation set status='COMMITTED',committed_at=?::timestamptz,"
                  + "allocated_quantity=cast(? as numeric) where tenant_id=? and id=?",
              OffsetDateTime.now(),
              quantity,
              tenant,
              reservationId);
          return new Reserved(reservationId, allocationId, no);
        });
  }

  BigDecimal balanceOnHand(UUID tenant, UUID skuId) {
    return as(
        tenant,
        () ->
            sql.fetchOne(
                    "select coalesce(sum(on_hand),0) as q from inventory_balance where tenant_id"
                        + " = current_setting('app.current_tenant', true)::uuid"
                        + " and sku_id=? and warehouse_id=?",
                    skuId,
                    wh(tenant))
                .get("q", BigDecimal.class));
  }

  BigDecimal balanceReserved(UUID tenant, UUID skuId) {
    return as(
        tenant,
        () ->
            sql.fetchOne(
                    "select coalesce(sum(reserved),0) as q from inventory_balance where tenant_id"
                        + " = current_setting('app.current_tenant', true)::uuid"
                        + " and sku_id=? and warehouse_id=?",
                    skuId,
                    wh(tenant))
                .get("q", BigDecimal.class));
  }

  String outboundStatus(UUID tenant, UUID orderId) {
    return as(
        tenant,
        () ->
            sql.fetchOne(
                    "select status from outbound_order where tenant_id"
                        + " = current_setting('app.current_tenant', true)::uuid and id=?",
                    orderId)
                .get("status", String.class));
  }

  long handoverCount(UUID tenant) {
    return as(
        tenant,
        () ->
            ((Number)
                    sql.fetchOne(
                            "select count(*) from shipment_handover where tenant_id"
                                + " = current_setting('app.current_tenant', true)::uuid")
                        .get(0))
                .longValue());
  }

  // ================================================================ 开单与冻结

  @Test
  void openFreezesParcelsAndItemsWithoutTouchingInventory() {
    var r = ready(a, "OB-HO-1", "2", sku(a, 1));
    assertThat(outboundStatus(a, r.orderId())).isEqualTo("PENDING_SHIPMENT");

    var handover = openHandover(a, r.packing().id());
    assertThat(handover.status()).isEqualTo("OPEN");
    assertThat(handover.parcelCount()).isEqualTo(1);
    assertThat(handover.parcelItemCount()).isEqualTo(1);
    assertThat(handover.totalQuantity()).isEqualTo("2");
    assertThat(handover.deductedQuantity()).isEqualTo("0");
    assertThat(handover.masterWaybillNo()).isEmpty();

    var detail = as(a, () -> service.detail(handover.id().toString()));
    assertThat(detail.parcels()).hasSize(1);
    assertThat(detail.parcels().get(0).grossWeightG()).isEqualTo("1200");
    assertThat(detail.parcels().get(0).volumeCbm()).isEqualTo("0.006");
    assertThat(detail.items()).hasSize(1);
    assertThat(detail.items().get(0).skuCode()).isEqualTo("SKU-1");
    // **开单不动库存**：实发扣减只发生在 confirm 那一个事务里。
    assertThat(balanceOnHand(a, sku(a, 1))).isEqualByComparingTo("100");
    assertThat(outboundStatus(a, r.orderId())).isEqualTo("PENDING_SHIPMENT");
  }

  @Test
  void cannotOpenHandoverFromUnfinishedPacking() {
    UUID loc = location(a, "A-1", "STORAGE");
    bucket(a, loc, sku(a, 1), "100");
    UUID orderId = outbound(a, "OB-OPEN", "2", sku(a, 1));
    finishPicking(a, orderId, null);
    UUID sessionId = passedRecount(a, orderId, null);
    var order = asWrite(a, () -> packing.open(new PackingOpenInput(sessionId, user, null), user));

    // 打包单还在 OPEN：货还没封箱，交不出去。
    assertThat(catchThrowable(() -> openHandover(a, order.id()))).hasMessageContaining("已打包");
  }

  @Test
  void cannotOpenTwoHandoversForSameOutboundOrder() {
    var r = ready(a, "OB-DUP", "2", sku(a, 1));
    openHandover(a, r.packing().id());
    // 同一个打包单再开一张：DB 的 UNIQUE(tenant_id, packing_order_id) 当场拒。
    // 只断言「确实被拒」不比对错误文案 —— PG 的报错文案随 locale 变，比文案脆。
    assertThat(catchThrowable(() -> openHandover(a, r.packing().id()))).isNotNull();
    assertThat(handoverCount(a)).isEqualTo(1);
  }

  // ================================================================ 运单号

  @Test
  void waybillRegistrationRequiresCarrierAndWaybill() {
    var r = ready(a, "OB-WB", "2", sku(a, 1));
    var handover = openHandover(a, r.packing().id());

    // 承运渠道与运单号缺一不可。
    assertThat(
            catchThrowable(
                () ->
                    asWrite(
                        a,
                        () ->
                            service.registerWaybills(
                                handover.id().toString(),
                                new HandoverWaybillInput("", "顺丰", "SF123", null, null, null, null),
                                user))))
        .hasMessageContaining("carrierCode 不能为空");
    assertThat(
            catchThrowable(
                () ->
                    asWrite(
                        a,
                        () ->
                            service.registerWaybills(
                                handover.id().toString(),
                                new HandoverWaybillInput("SF", "顺丰", "  ", null, null, null, null),
                                user))))
        .hasMessageContaining("masterWaybillNo 不能为空");
    // 开单时只填运单号不填渠道：DB 的 CHECK 与服务层都拒。
    assertThat(
            catchThrowable(
                () ->
                    asWrite(
                        a,
                        () ->
                            service.open(
                                new HandoverOpenInput(r.packing().id(), "", null, "SF123", null),
                                user))))
        .hasMessageContaining("承运渠道");

    var registered = waybill(a, handover.id(), "SF", "SF123456");
    assertThat(registered.carrierCode()).isEqualTo("SF");
    assertThat(registered.masterWaybillNo()).isEqualTo("SF123456");
    assertThat(registered.receiverName()).isEqualTo("王师傅");
    // 交接人**还没有**登记：它与交接时刻是一对事实，到交接确认那一刻才成立。
    assertThat(registered.handoverUserId()).isNull();
    assertThat(registered.handoverAt()).isNull();
    // 登记运单号同样不动库存。
    assertThat(balanceOnHand(a, sku(a, 1))).isEqualByComparingTo("100");
  }

  @Test
  void completionBlocksUntilCarrierWaybillAndStockAreReady() {
    var r = ready(a, "OB-CP", "2", sku(a, 1));
    var handover = openHandover(a, r.packing().id());

    var before = as(a, () -> service.completionOf(handover.id().toString()));
    assertThat(before.handableOver()).isFalse();
    assertThat(before.blockingReasons()).anyMatch(s -> s.contains("承运渠道"));
    assertThat(before.blockingReasons()).anyMatch(s -> s.contains("运单号"));

    var after =
        as(
            a,
            () -> {
              waybill(a, handover.id(), "SF", "SF123");
              return service.completionOf(handover.id().toString());
            });
    assertThat(after.handableOver()).isTrue();
    assertThat(after.carrierRegistered()).isTrue();
    assertThat(after.waybillRegistered()).isTrue();
  }

  @Test
  void completionReportsShortagePerSku() {
    var r = ready(a, "OB-SHORT", "500", "600", sku(a, 1));
    // 货装完之后、交接之前，库存被调减（盘点差异 / 损耗）。
    // 刻意不写成「拣不够」：拣不动会先在 8.3.2.1 被拒，压根到不了交接这一步。
    adjustStagingStock(a, sku(a, 1), "-500");
    var handover = openHandover(a, r.packing().id());
    waybill(a, handover.id(), "SF", "SF123");

    var report = as(a, () -> service.completionOf(handover.id().toString()));
    assertThat(report.handableOver()).isFalse();
    assertThat(report.shortQuantity()).isEqualTo("400");
    assertThat(report.blockingReasons()).anyMatch(x -> x.contains("库存不足"));
    assertThat(report.shortSkus()).anyMatch(s -> s.startsWith("SKU-1"));
  }

  // ================================================================ 交接确认：实发扣减

  @Test
  void confirmDeductsInventoryAndWritesShipmentLedger() {
    var r = ready(a, "OB-DED", "2", sku(a, 1));
    var handover = openHandover(a, r.packing().id());
    waybill(a, handover.id(), "SF", "SF123456");
    var done = confirm(a, handover.id(), null);

    // ---- 实物侧：on_hand 减；默认没有实物预占，所以 reserved 与 reserved_delta 都是 0
    assertThat(balanceOnHand(a, sku(a, 1))).isEqualByComparingTo("98");
    assertThat(balanceReserved(a, sku(a, 1))).isEqualByComparingTo("0");

    // ---- 台账：一笔 SHIPMENT 流水 + 带符号分录，ref 指回这张交接单
    var tx =
        as(
            a,
            () ->
                sql.fetchOne(
                    "select id,kind,ref_type,ref_id,occurred_at from inventory_transaction"
                        + " where tenant_id = current_setting('app.current_tenant', true)::uuid"
                        + " and ref_type='SHIPMENT_HANDOVER' and ref_id=?",
                    handover.id()));
    assertThat(tx.get("kind", String.class)).isEqualTo("SHIPMENT");
    assertThat(tx.get("ref_type", String.class)).isEqualTo("SHIPMENT_HANDOVER");
    assertThat(tx.get("ref_id", UUID.class)).isEqualTo(handover.id());
    assertThat(tx.get("occurred_at", OffsetDateTime.class)).isNotNull();
    assertThat(done.inventoryTransactionId()).isEqualTo(tx.get("id", UUID.class));

    var entry =
        as(
            a,
            () ->
                sql.fetchOne(
                    "select on_hand_delta,reserved_delta from inventory_entry"
                        + " where tenant_id = current_setting('app.current_tenant', true)::uuid"
                        + " and transaction_id=?",
                    done.inventoryTransactionId()));
    assertThat(entry.get("on_hand_delta", BigDecimal.class)).isEqualByComparingTo("-2");
    assertThat(entry.get("reserved_delta", BigDecimal.class)).isEqualByComparingTo("0");

    // ---- 单头与出库单
    var after = as(a, () -> service.detail(handover.id().toString()));
    assertThat(after.order().status()).isEqualTo("HANDED_OVER");
    assertThat(after.order().deductedQuantity()).isEqualTo("2");
    assertThat(after.items().get(0).deductedQuantity()).isEqualTo("2");
    assertThat(after.items().get(0).balanceId()).isNotNull();
    assertThat(outboundStatus(a, r.orderId())).isEqualTo("SHIPPED");
    // 发货事件：出库单时间线上有一条 PENDING_SHIPMENT → SHIPPED
    assertThat(
            as(
                a,
                () ->
                    ((Number)
                            sql.fetchOne(
                                    "select count(*) from outbound_order_event"
                                        + " where tenant_id = current_setting('app.current_tenant', true)::uuid"
                                        + " and order_id=? and to_status='SHIPPED'",
                                    r.orderId())
                                .get(0))
                        .longValue()))
        .isEqualTo(1);
  }

  @Test
  void confirmWritesBackTheGivenHandoverTimeWithoutShift() {
    var r = ready(a, "OB-TIME", "2", sku(a, 1));
    var handover = openHandover(a, r.packing().id());
    waybill(a, handover.id(), "SF", "SF123");
    // 带偏移量的时刻：若走 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s。
    var at = OffsetDateTime.of(2026, 10, 14, 9, 30, 0, 0, ZoneOffset.ofHours(8));
    confirm(a, handover.id(), at.toString());

    var readBack =
        as(
            a,
            () ->
                sql.fetchOne(
                        "select handover_at from shipment_handover where tenant_id"
                            + " = current_setting('app.current_tenant', true)::uuid and id=?",
                        handover.id())
                    .get("handover_at", OffsetDateTime.class));
    assertThat(readBack.toInstant()).isEqualTo(at.toInstant());
    // 出库单的 shipped_at 是同一个事实，不许出现两个不同的「发货时刻」。
    var shippedAt =
        as(
            a,
            () ->
                sql.fetchOne(
                        "select shipped_at from outbound_order where tenant_id"
                            + " = current_setting('app.current_tenant', true)::uuid and id=?",
                        r.orderId())
                    .get("shipped_at", OffsetDateTime.class));
    assertThat(shippedAt.toInstant()).isEqualTo(at.toInstant());
  }

  @Test
  void confirmIsIdempotentGuardAgainstDoubleDeduction() {
    var r = ready(a, "OB-2X", "2", sku(a, 1));
    var handover = openHandover(a, r.packing().id());
    waybill(a, handover.id(), "SF", "SF123");
    confirm(a, handover.id(), null);

    // 第二次确认：交接单已终结，DB 的状态守卫拒。
    assertThat(catchThrowable(() -> confirm(a, handover.id(), null))).hasMessageContaining("已交接");
    // 库存没有被动第二次。
    assertThat(balanceOnHand(a, sku(a, 1))).isEqualByComparingTo("98");
    assertThat(
            as(
                a,
                () ->
                    ((Number)
                            sql.fetchOne(
                                    "select count(*) from inventory_transaction"
                                        + " where tenant_id = current_setting('app.current_tenant', true)::uuid"
                                        + " and ref_type='SHIPMENT_HANDOVER' and ref_id=?",
                                    handover.id())
                                .get(0))
                        .longValue()))
        .isEqualTo(1);
  }

  @Test
  void insufficientStockRollsBackEverything() {
    var r = ready(a, "OB-ROLL", "500", "600", sku(a, 1));
    adjustStagingStock(a, sku(a, 1), "-500");
    var handover = openHandover(a, r.packing().id());
    waybill(a, handover.id(), "SF", "SF123");
    BigDecimal onHandBefore = balanceOnHand(a, sku(a, 1));
    BigDecimal reservedBefore = balanceReserved(a, sku(a, 1));

    // 库存只有 100，够不上 500：整单失败。
    assertThat(catchThrowable(() -> confirm(a, handover.id(), null)))
        .hasMessageContaining("可用库存不足");

    // **一件都没扣**：整单失败而不是「扣得动的先扣上」。
    assertThat(balanceOnHand(a, sku(a, 1))).isEqualByComparingTo(onHandBefore);
    assertThat(balanceReserved(a, sku(a, 1))).isEqualByComparingTo(reservedBefore);
    // 出库流水没有留下（同一事务回滚）
    assertThat(
            as(
                a,
                () ->
                    ((Number)
                            sql.fetchOne(
                                    "select count(*) from inventory_transaction"
                                        + " where tenant_id = current_setting('app.current_tenant', true)::uuid"
                                        + " and ref_type='SHIPMENT_HANDOVER' and ref_id=?",
                                    handover.id())
                                .get(0))
                        .longValue()))
        .isZero();
    // 交接单仍在「交接中」，出库单仍在「待发货」
    assertThat(as(a, () -> service.detail(handover.id().toString())).order().status())
        .isEqualTo("OPEN");
    assertThat(outboundStatus(a, r.orderId())).isEqualTo("PENDING_SHIPMENT");
  }

  // ================================================================ 交接确认：预占转实扣

  @Test
  void confirmConsumesReservationInSameTransaction() {
    UUID loc = location(a, "A-1", "STORAGE");
    bucket(a, loc, sku(a, 1), "100");
    UUID orderId = outbound(a, "OB-RSV", "2", sku(a, 1));
    finishPicking(a, orderId, null);
    UUID sessionId = passedRecount(a, orderId, null);
    var packed = packedOrder(a, sessionId, "OB-RSV");

    // 两本账都铺：实物预占 2 件（7.1.1.1）+ 承诺分配 2 件（7.2.1.2）。
    reserveStock(a, sku(a, 1), "2");
    UUID stagingLoc = staging(a);
    var reserved = reserve(a, sku(a, 1), stagingLoc, "2");

    var handover = openHandover(a, packed.id());
    // 预占候选被显式列出来（空列表也有明确含义：不预占出库）。
    var candidates = as(a, () -> service.detail(handover.id().toString())).reservationCandidates();
    assertThat(candidates).anyMatch(c -> c.reservationId().equals(reserved.reservationId()));

    waybill(a, handover.id(), "SF", "SF123");
    var done = confirm(a, handover.id(), null);

    // ---- 实物侧：预占的 2 件被释放，on_hand 减 2
    assertThat(balanceOnHand(a, sku(a, 1))).isEqualByComparingTo("98");
    assertThat(balanceReserved(a, sku(a, 1))).isEqualByComparingTo("0");

    // ---- 承诺侧：分配 COMMITTED → CONSUMED，量恰好等于出库量
    var allocation =
        as(
            a,
            () ->
                sql.fetchOne(
                    "select status,consumed_quantity,remaining,settled_at from reservation_allocation"
                        + " where tenant_id = current_setting('app.current_tenant', true)::uuid and id=?",
                    reserved.allocationId()));
    assertThat(allocation.get("status", String.class)).isEqualTo("CONSUMED");
    assertThat(allocation.get("consumed_quantity", BigDecimal.class)).isEqualByComparingTo("2");
    assertThat(allocation.get("remaining", BigDecimal.class)).isEqualByComparingTo("0");
    assertThat(allocation.get("settled_at", OffsetDateTime.class)).isNotNull();

    // ---- 父预占：整笔消耗完且覆盖需求量 → CONSUMED（不是「消费了一半还挂在 HELD」）
    var parent =
        as(
            a,
            () ->
                sql.fetchOne(
                    "select status,consumed_quantity,remaining,settled_at from inventory_reservation"
                        + " where tenant_id = current_setting('app.current_tenant', true)::uuid and id=?",
                    reserved.reservationId()));
    assertThat(parent.get("status", String.class)).isEqualTo("CONSUMED");
    assertThat(parent.get("consumed_quantity", BigDecimal.class)).isEqualByComparingTo("2");
    assertThat(parent.get("remaining", BigDecimal.class)).isEqualByComparingTo("0");

    // ---- 两本账都留下了可查的凭据
    var entry =
        as(
            a,
            () ->
                sql.fetchOne(
                    "select transition,from_status,to_status,quantity from reservation_entry"
                        + " where tenant_id = current_setting('app.current_tenant', true)::uuid"
                        + " and reservation_id=? and transition='CONSUMED'",
                    reserved.reservationId()));
    assertThat(entry.get("from_status", String.class)).isEqualTo("COMMITTED");
    assertThat(entry.get("to_status", String.class)).isEqualTo("CONSUMED");
    assertThat(entry.get("quantity", BigDecimal.class)).isEqualByComparingTo("2");

    var consumed = as(a, () -> service.detail(handover.id().toString())).reservations();
    assertThat(consumed).hasSize(1);
    assertThat(consumed.get(0).reservationNo()).isEqualTo(reserved.reservationNo());
    assertThat(consumed.get(0).quantity()).isEqualTo("2");
    assertThat(done.reservationLineCount()).isEqualTo(1);
    assertThat(done.reservedDeductedQuantity()).isEqualTo("2");
    // 消费完就没有「可消费的候选」了。
    assertThat(as(a, () -> service.detail(handover.id().toString())).reservationCandidates())
        .noneMatch(c -> c.reservationId().equals(reserved.reservationId()));
  }

  @Test
  void handoverWithoutReservationShipsWithoutInventingOne() {
    var r = ready(a, "OB-NORSV", "2", sku(a, 1));
    var handover = openHandover(a, r.packing().id());
    // 没有在册预占时，判据如实说「未预占出库」，而不是「预占已消耗」。
    var report = as(a, () -> service.completionOf(handover.id().toString()));
    assertThat(report.warnings()).anyMatch(s -> s.contains("未预占出库"));

    waybill(a, handover.id(), "SF", "SF123");
    var done = confirm(a, handover.id(), null);
    assertThat(done.reservationLineCount()).isZero();
    assertThat(done.reservedDeductedQuantity()).isEqualTo("0");
    assertThat(as(a, () -> service.detail(handover.id().toString())).reservations()).isEmpty();
    // 两本账都空：实物预占侧 released 0、承诺侧一条分配都没消费。
    assertThat(balanceReserved(a, sku(a, 1))).isEqualByComparingTo("0");
  }

  @Test
  void partialReservationOnlyReleasesWhatWasReserved() {
    UUID loc = location(a, "A-1", "STORAGE");
    bucket(a, loc, sku(a, 1), "100");
    UUID orderId = outbound(a, "OB-PART", "6", sku(a, 1));
    finishPicking(a, orderId, null);
    UUID sessionId = passedRecount(a, orderId, null);
    var packed = packedOrder(a, sessionId, "OB-PART");
    UUID stagingLoc = staging(a);
    // 只预占了 2 件，实际要出 6 件：4 件本来没被谁圈住。
    reserveStock(a, sku(a, 1), "2");
    var reserved = reserve(a, sku(a, 1), stagingLoc, "2");

    var handover = openHandover(a, packed.id());
    waybill(a, handover.id(), "SF", "SF123");
    var done = confirm(a, handover.id(), null);

    assertThat(done.reservedDeductedQuantity()).isEqualTo("2");
    assertThat(
            as(
                a,
                () ->
                    sql.fetchOne(
                            "select status,consumed_quantity from reservation_allocation"
                                + " where tenant_id = current_setting('app.current_tenant', true)::uuid and id=?",
                            reserved.allocationId())
                        .get("status", String.class)))
        .isEqualTo("CONSUMED");
    // 预占整笔被吃干净了 —— 不会留一个「还剩 0 但状态是 COMMITTED」的悬空行。
    assertThat(
            as(
                a,
                () ->
                    sql.fetchOne(
                            "select status from inventory_reservation"
                                + " where tenant_id = current_setting('app.current_tenant', true)::uuid and id=?",
                            reserved.reservationId())
                        .get("status", String.class)))
        .isEqualTo("CONSUMED");
    // 实物侧 reserved 减的是 min(2, 6) = 2 —— 被圈的只有 2 件，
    // 另外 4 件本来就没被谁圈住，硬减成 6 是在编账。
    assertThat(balanceOnHand(a, sku(a, 1))).isEqualByComparingTo("94");
    assertThat(balanceReserved(a, sku(a, 1))).isEqualByComparingTo("0");
  }

  // ================================================================ 序列号

  @Test
  void serialTrackedItemBecomesSoldOnHandover() {
    UUID loc = location(a, "A-1", "STORAGE");
    bucket(a, loc, sku(a, 1), "100");
    UUID serialId = serial(a, sku(a, 1), "SN-H1", loc);
    UUID orderId = outbound(a, "OB-SER", "1", sku(a, 1));
    finishPicking(a, orderId, Map.of(0, List.of(serialId)));
    UUID sessionId = passedRecount(a, orderId, Map.of(0, List.of("SN-H1")));

    var order = asWrite(a, () -> packing.open(new PackingOpenInput(sessionId, user, null), user));
    var material = carton(a, "C-HSER", "200", "5000");
    var parcel =
        asWrite(
            a,
            () ->
                packing.addParcel(
                    order.id().toString(),
                    new ParcelAddInput(material.id(), null, null, null),
                    user));
    var line = as(a, () -> packing.detail(order.id().toString()).baseline()).get(0);
    asWrite(
        a,
        () ->
            packing.addItem(
                order.id().toString(),
                parcel.id().toString(),
                new ParcelItemInput(line.id(), "1", serialId, null),
                user));
    asWrite(
        a,
        () ->
            packing.measure(
                order.id().toString(),
                parcel.id().toString(),
                new ParcelMeasureInput("1200", "30", "20", "10", null, null),
                user));
    var packed =
        asWrite(
            a,
            () ->
                packing.complete(
                    order.id().toString(), new PackingCompleteInput(null, null), user));

    var handover = openHandover(a, packed.id());
    waybill(a, handover.id(), "SF", "SF123");
    var done = confirm(a, handover.id(), null);
    assertThat(done.serialCount()).isEqualTo(1);

    // 货离开了仓库，档案就不能还写着「在库」—— 否则售后环节会被凭空多卖一次。
    var serialAfter =
        as(
            a,
            () ->
                sql.fetchOne(
                    "select status,current_location_id from inventory_serial where tenant_id"
                        + " = current_setting('app.current_tenant', true)::uuid and id=?",
                    serialId));
    assertThat(serialAfter.get("status", String.class)).isEqualTo("SOLD");
    assertThat(serialAfter.get("current_location_id", UUID.class)).isNull();
    // 出库操作头与逐件流转都在（V58 的口径）。
    assertThat(
            as(
                a,
                () ->
                    ((Number)
                            sql.fetchOne(
                                    "select count(*) from inventory_serial_operation where tenant_id"
                                        + " = current_setting('app.current_tenant', true)::uuid"
                                        + " and direction='OUTBOUND' and ref_id=?",
                                    handover.id())
                                .get(0))
                        .longValue()))
        .isEqualTo(1);
    assertThat(
            as(
                a,
                () ->
                    ((Number)
                            sql.fetchOne(
                                    "select count(*) from inventory_serial_movement where tenant_id"
                                        + " = current_setting('app.current_tenant', true)::uuid and serial_id=?",
                                    serialId)
                                .get(0))
                        .longValue()))
        .isEqualTo(1);
  }

  // ================================================================ 作废

  @Test
  void cancelDoesNotTouchInventory() {
    var r = ready(a, "OB-CANCEL", "2", sku(a, 1));
    var handover = openHandover(a, r.packing().id());
    waybill(a, handover.id(), "SF", "SF123");

    // 原因必填
    assertThat(
            catchThrowable(
                () ->
                    asWrite(
                        a,
                        () ->
                            service.cancel(
                                handover.id().toString(), new HandoverCancelInput("  "), user))))
        .hasMessageContaining("必须写原因");

    var cancelled =
        asWrite(
            a,
            () ->
                service.cancel(
                    handover.id().toString(), new HandoverCancelInput("承运商爽约，重新约车"), user));
    assertThat(cancelled.status()).isEqualTo("CANCELLED");
    // 库存一分没动。
    assertThat(balanceOnHand(a, sku(a, 1))).isEqualByComparingTo("100");
    assertThat(outboundStatus(a, r.orderId())).isEqualTo("PENDING_SHIPMENT");

    // 终态不可复活：已作废的单不能再确认交接。
    assertThat(catchThrowable(() -> confirm(a, handover.id(), null))).hasMessageContaining("已作废");
  }

  // ================================================================ 列表与查询

  @Test
  void listIsRealPagedFilteredAndSearchable() {
    var r1 = ready(a, "OB-L1", "2", sku(a, 1));
    var r2 = ready(a, "OB-L2", "3", sku(a, 2));
    var h1 = openHandover(a, r1.packing().id());
    var h2 = openHandover(a, r2.packing().id());
    waybill(a, h1.id(), "SF", "SF111111");
    waybill(a, h2.id(), "YTO", "YT222222");

    var all =
        as(a, () -> service.page(wh(a), null, null, null, null, null, null, "CREATED_AT", 0, 50));
    assertThat(all.total()).isEqualTo(2);
    assertThat(all.summary().totalHandovers()).isEqualTo(2);
    assertThat(all.summary().totalQuantity()).isEqualTo("5");

    var byCarrier =
        as(a, () -> service.page(wh(a), null, null, null, "SF", null, null, "CREATED_AT", 0, 50));
    assertThat(byCarrier.total()).isEqualTo(1);
    assertThat(byCarrier.items().get(0).id()).isEqualTo(h1.id());

    // 按运单号搜（客服接到「我的单号查不到」时的第一跳）
    var byKeyword =
        as(
            a,
            () ->
                service.page(wh(a), null, null, null, null, null, "YT222222", "CREATED_AT", 0, 50));
    assertThat(byKeyword.total()).isEqualTo(1);
    assertThat(byKeyword.items().get(0).id()).isEqualTo(h2.id());

    // 真分页：size=1 时总数仍是 2，页内只有一条
    var page0 =
        as(a, () -> service.page(wh(a), null, null, null, null, null, null, "CREATED_AT", 0, 1));
    assertThat(page0.items()).hasSize(1);
    assertThat(page0.total()).isEqualTo(2);
    var page1 =
        as(a, () -> service.page(wh(a), null, null, null, null, null, null, "CREATED_AT", 1, 1));
    assertThat(page1.items()).hasSize(1);
    assertThat(page1.items().get(0).id()).isNotEqualTo(page0.items().get(0).id());
  }

  @Test
  void capabilitiesDeclareInventoryOwnershipAndDownstreamBoundaries() throws Exception {
    http.perform(
            get("/warehouse/shipment-handovers/capabilities")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.touchesInventory").value(true))
        .andExpect(jsonPath("$.upstreamModule").value("8.3.3.2"));
  }

  @Test
  void readOnlyPrincipalCannotConfirmHandover() throws Exception {
    var r = ready(a, "OB-RO", "2", sku(a, 1));
    var handover = openHandover(a, r.packing().id());
    waybill(a, handover.id(), "SF", "SF123");
    // 只有 warehouse.inventory.read 的人不许交接：确认要动库存。
    // 权限校验在 Controller 的 call() 里（Service 自己不查权限），所以这一条必须走 HTTP。
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/warehouse/shipment-handovers/" + handover.id() + "/confirm")
                .header("Authorization", "Bearer read-only")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    // 一件都没扣。
    assertThat(balanceOnHand(a, sku(a, 1))).isEqualByComparingTo("100");
    assertThat(outboundStatus(a, r.orderId())).isEqualTo("PENDING_SHIPMENT");
  }

  // ================================================================ 多租户三断言

  @Test
  void twoTenantsCannotSeeEachOther() {
    var ra = ready(a, "OB-ISO-A", "2", sku(a, 1));
    var rb = ready(b, "OB-ISO-B", "2", sku(b, 1));
    var ha = openHandover(a, ra.packing().id());
    var hb = openHandover(b, rb.packing().id());

    // B 用 A 的交接单 id 取详情 → 404
    assertThat(catchThrowable(() -> as(b, () -> service.detail(ha.id().toString()))))
        .hasMessageContaining("不存在");
    // B 在 A 的交接单上登记运单号 → 404（写路径同样隔离）
    assertThat(
            catchThrowable(
                () ->
                    asWrite(
                        b,
                        () ->
                            service.registerWaybills(
                                ha.id().toString(),
                                new HandoverWaybillInput(
                                    "SF", "顺丰", "SF999", null, null, null, null),
                                user))))
        .hasMessageContaining("不存在");
    // B 确认 A 的交接 → 404
    assertThat(
            catchThrowable(
                () ->
                    asWrite(
                        b,
                        () ->
                            service.confirm(
                                ha.id().toString(),
                                new HandoverConfirmInput(user, null, null, null, null),
                                user))))
        .hasMessageContaining("不存在");
    // B 开不了 A 的打包单的交接单
    assertThat(catchThrowable(() -> openHandover(b, ra.packing().id())))
        .hasMessageContaining("不存在");
    // B 的列表里只有自己那一张
    var bList =
        as(b, () -> service.page(wh(b), null, null, null, null, null, null, "CREATED_AT", 0, 50));
    assertThat(bList.total()).isEqualTo(1);
    assertThat(bList.items().get(0).id()).isEqualTo(hb.id());
    // A 的库存没被 B 的确认动过
    assertThat(balanceOnHand(a, sku(a, 1))).isEqualByComparingTo("100");
    assertThat(balanceOnHand(b, sku(b, 1))).isEqualByComparingTo("100");
  }

  @Test
  void forgedTenantHeaderIsIgnored() throws Exception {
    var r = ready(a, "OB-FT", "2", sku(a, 1));
    var handover = openHandover(a, r.packing().id());
    // 伪造 X-Tenant-Id 头：代理忽略它，身份由 Authorization 解析。
    // 连身份头也伪造时结果是 401，而不是「换成 B 的租户」。
    var forged =
        http.perform(
                get("/warehouse/shipment-handovers/" + handover.id())
                    .header("Authorization", "Bearer forged")
                    .header("X-Tenant-Id", b.toString()))
            .andReturn()
            .getResponse();
    assertThat(forged.getStatus()).isEqualTo(401);

    // 带上真实身份但伪造租户头：看到的仍然只有 A 自己那一张
    http.perform(
            get("/warehouse/shipment-handovers")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].handoverCode").value(handover.handoverCode()));
  }

  @Test
  void pooledConnectionDoesNotLeakTenantAcrossReturns() {
    var ra = ready(a, "OB-POOL-A", "2", sku(a, 1));
    var rb = ready(b, "OB-POOL-B", "2", sku(b, 1));
    var ha = openHandover(a, ra.packing().id());
    var hb = openHandover(b, rb.packing().id());
    // A → B → A 三轮：连接池只有 1 条连接，每次归还后都可能被下一个人拿到。
    for (int round = 0; round < 3; round++) {
      assertThat(
              as(
                      a,
                      () ->
                          service.page(
                              wh(a), null, null, null, null, null, null, "HANDOVER_CODE", 0, 50))
                  .items()
                  .get(0)
                  .id())
          .isEqualTo(ha.id());
      assertThat(
              as(
                      b,
                      () ->
                          service.page(
                              wh(b), null, null, null, null, null, null, "HANDOVER_CODE", 0, 50))
                  .items()
                  .get(0)
                  .id())
          .isEqualTo(hb.id());
      assertThat(as(a, () -> service.detail(ha.id().toString())).order().handoverCode())
          .isEqualTo(ha.handoverCode());
      assertThat(as(b, () -> service.detail(hb.id().toString())).order().handoverCode())
          .isEqualTo(hb.handoverCode());
      // A 看到的库存永远是 A 的，与 B 刚拿过同一条连接无关。
      assertThat(balanceOnHand(a, sku(a, 1))).isEqualByComparingTo("100");
      assertThat(balanceOnHand(b, sku(b, 1))).isEqualByComparingTo("100");
    }
  }
}
