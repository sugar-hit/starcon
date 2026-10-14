package com.starcon.warehouse;

import static com.starcon.warehouse.PackingModels.*;
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
 * 8.3.3.2 打包与称重的集成测试。
 *
 * <p>真 PostgreSQL 16 + Testcontainers，<b>池容量 1</b>（只有复用同一条连接才能证明连接池
 * 归还后不串租户）。多租户三断言逐条实跑：两租户互不可见、伪造 tenant header 无效、
 * 连接池归还后不串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PackingIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      List.of("warehouse.inventory.read", "warehouse.inventory.update");

  static final ZoneId WAREHOUSE_ZONE = ZoneId.of("Asia/Shanghai");

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-packing-" + tenant + "-" + ordinal).getBytes());
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
          actor(user, a, List.of(grant("ALL", List.of("warehouse.inventory.read")))));
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

  @Autowired PackingService service;
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
          "truncate packing_event, parcel_item, parcel, packing_order, packing_material,"
              + " recount_event, recount_exception, recount_scan, recount_line, recount_session,"
              + " recount_rule, pick_wave_event, pick_wave_task, pick_wave, pick_wave_rule,"
              + " pick_task_serial, pick_task_pick, pick_task_exception, pick_task_line,"
              + " pick_task_event, pick_task, outbound_pick_request, outbound_order_event,"
              + " outbound_order_line, outbound_order, inventory_serial, stock_bucket,"
              + " inventory_balance, warehouse_location, inventory_lot,"
              + " inventory_quality_status, legal_entity_ref, sku_ref, warehouse cascade");
      s.execute("truncate inventory_entry, inventory_transaction restart identity cascade");
    }
    for (var tenant : List.of(a, b)) {
      access.run(
          actorFor(tenant),
          "warehouse.inventory.read",
          () -> {
            sql.execute(
                "insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 1), tenant, "LE-1", "主体一");
            for (int i = 2; i <= 10; i++) {
              sql.execute(
                  "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                  idFor(tenant, i), tenant, "SKU-" + (i - 1), "商品" + (i - 1));
            }
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 9), tenant, "SELLABLE", "可售", "SELLABLE");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,kind,country_code,timezone,"
                    + "default_legal_entity_id,status) values(?,?,?,?,?,?,?,?,?)",
                idFor(tenant, 11), tenant, "WH-" + (tenant.equals(a) ? "A" : "B"), "本租仓库",
                "LOCAL", tenant.equals(a) ? "CN" : "US", WAREHOUSE_ZONE.getId(),
                idFor(tenant, 1), "ACTIVE");
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
          var existing = sql.fetchOne(
              "select id from warehouse_location where tenant_id"
                  + " = current_setting('app.current_tenant', true)::uuid"
                  + " and warehouse_id=? and code=?",
              wh(tenant), code);
          if (existing != null) return existing.get("id", UUID.class);
          UUID id = UUID.randomUUID();
          sql.execute(
              "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,status,"
                  + "pickable) values(?,?,?,?,?,?,'ACTIVE',true)",
              id, tenant, wh(tenant), code, code + " 位", type);
          return id;
        });
  }

  void bucket(UUID tenant, UUID loc, UUID skuId, String onHand) {
    as(
        tenant,
        () -> {
          var existing = sql.fetchOne(
              "select id from inventory_balance where tenant_id"
                  + " = current_setting('app.current_tenant', true)::uuid"
                  + " and sku_id=? and warehouse_id=?",
              skuId, wh(tenant));
          UUID balance;
          if (existing != null) {
            balance = existing.get("id", UUID.class);
            sql.execute(
                "update inventory_balance set on_hand=on_hand+cast(? as numeric),"
                    + "reserved=reserved+cast(? as numeric) where id=?",
                onHand, onHand, balance);
          } else {
            balance = UUID.randomUUID();
            sql.execute(
                "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                    + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,cast(? as numeric),"
                    + "cast(? as numeric))",
                balance, tenant, skuId, wh(tenant), idFor(tenant, 1), sellable(tenant), onHand, onHand);
          }
          var bucketRow = sql.fetchOne(
              "select id from stock_bucket where tenant_id"
                  + " = current_setting('app.current_tenant', true)::uuid"
                  + " and sku_id=? and warehouse_id=? and location_id=?",
              skuId, wh(tenant), loc);
          if (bucketRow != null) {
            sql.execute(
                "update stock_bucket set on_hand=on_hand+cast(? as numeric),"
                    + "reserved=reserved+cast(? as numeric),last_movement_at=?::timestamptz where id=?",
                onHand, onHand, OffsetDateTime.now().plusSeconds(1),
                bucketRow.get("id", UUID.class));
          } else {
            sql.execute(
                "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,"
                    + "location_id,lot_id,quality_status_id,balance_id,on_hand,reserved,"
                    + "last_movement_at) values(?,?,?,?,?,?,null,?,?,cast(? as numeric),"
                    + "cast(? as numeric),?::timestamptz)",
                UUID.randomUUID(), tenant, idFor(tenant, 1), skuId, wh(tenant), loc,
                sellable(tenant), balance, onHand, onHand, OffsetDateTime.now());
          }
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
              id, tenant, number, skuId, loc);
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
              orderId, tenant, code, "SO-" + code, wh(tenant), user);
          BigDecimal total = BigDecimal.ZERO;
          int lineNo = 0;
          for (UUID skuId : skuIds) {
            lineNo++;
            sql.execute(
                "insert into outbound_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,"
                    + "sku_name,quantity) values(?,?,?,?,?,?,?,cast(? as numeric))",
                UUID.randomUUID(), tenant, orderId, lineNo, skuId,
                codes.get(skuId), "商品" + lineNo, quantity);
            total = total.add(new BigDecimal(quantity));
          }
          sql.execute(
              "insert into outbound_pick_request(id,tenant_id,order_id,line_count,"
                  + "total_piece_count,requested_by_user_id,requested_at)"
                  + " values(?,?,?,?,cast(? as numeric),?,?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, skuIds.length, total, user, OffsetDateTime.now());
          sql.execute(
              "update outbound_order set status='PICKING' where tenant_id=? and id=?",
              tenant, orderId);
          return orderId;
        });
  }

  /**
   * 打包区暂存库位：8.3.2.1 要求「拣完的货必须有一个明确去处」。
   * 打包不碰库存 —— 货还在这个 SHIPPING 库位里，这正是「打包只读」的物理前提。
   */
  void ensureStaging(UUID tenant) {
    if (as(tenant, () -> sql.fetchOne(
            "select 1 as ok from warehouse_location where tenant_id"
                + " = current_setting('app.current_tenant', true)::uuid"
                + " and warehouse_id=? and type='SHIPPING' limit 1",
            wh(tenant))) == null) {
      location(tenant, "OUT", "SHIPPING");
    }
  }

  /** 走 8.3.2.1 的真实入口生成拣货任务并推到「已完成」。 */
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
        () ->
            pickTasks.assign(
                task, new PickTaskModels.PickTaskAssignInput(user, null), user));
    for (int i = 0; i < lines.size(); i++) {
      var line = lines.get(i);
      List<UUID> lineSerials = serials == null ? null : serials.getOrDefault(i, List.of());
      asWrite(
          tenant,
          () ->
              pickTasks.recordPick(
                  task,
                  new PickTaskModels.PickTaskPickInput(
                      line.id(), line.sourceLocationId(), null, line.plannedQuantity(),
                      lineSerials, null, null),
                  user));
    }
    asWrite(tenant, () -> pickTasks.complete(task, user));
  }

  /** 走 8.3.3.1 的真实入口把复核推到「通过」，出库单随之落到 PENDING_PACKING。 */
  UUID passedRecount(UUID tenant, UUID orderId, Map<Integer, List<String>> scansByIndex) {
    var session =
        asWrite(
                tenant,
                () ->
                    recounts.open(
                        new RecountModels.RecountOpenInput(orderId, null, user, null), user));
    int i = 0;
    for (var line : asWrite(tenant, () -> recounts.detail(session.id().toString()).lines())) {
      List<String> values = scansByIndex == null ? null : scansByIndex.getOrDefault(i, List.of());
      if (values == null) {
        // 按条码扫够应发件数。
        int times = new BigDecimal(line.expectedQuantity()).intValueExact();
        for (int k = 0; k < times; k++) {
          asWrite(
              tenant,
              () ->
                  recounts.scan(
                      session.id().toString(),
                      new RecountModels.RecountScanInput(
                          "BARCODE", line.skuCode(), null, null),
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
            service.createMaterial(
                new PackingMaterialInput(code, "纸箱 " + code, "PACKAGING", "CARTON", wh(tenant),
                    "30", "20", "10", tare, max),
                user));
  }

  PackingMaterialRow filler(UUID tenant, String code) {
    return asWrite(
        tenant,
        () ->
            service.createMaterial(
                new PackingMaterialInput(code, "气泡柱 " + code, "CUSHIONING", "BUBBLE",
                    wh(tenant), "30", "20", "10", "0", "0"),
                user));
  }

  PackingCreated openOrder(UUID tenant, UUID recountSessionId) {
    return asWrite(
        tenant,
        () ->
            service.open(new PackingOpenInput(recountSessionId, user, null), user));
  }

  ParcelRow addParcel(UUID tenant, UUID orderId, UUID materialId, UUID fillerId) {
    return asWrite(
        tenant,
        () ->
            service.addParcel(
                orderId.toString(),
                new ParcelAddInput(materialId, fillerId, null, null),
                user));
  }

  ParcelItemRow addItem(UUID tenant, UUID orderId, UUID parcelId, UUID lineId, String qty,
      UUID serialId) {
    return asWrite(
        tenant,
        () ->
            service.addItem(
                orderId.toString(),
                parcelId.toString(),
                new ParcelItemInput(lineId, qty, serialId, null),
                user));
  }

  ParcelRow measure(UUID tenant, UUID orderId, UUID parcelId, String gross, String l, String w,
      String h) {
    return asWrite(
        tenant,
        () ->
            service.measure(
                orderId.toString(),
                parcelId.toString(),
                new ParcelMeasureInput(gross, l, w, h, null, null),
                user));
  }

  /** 一路走到「可以结算」：开单 + 一箱 + 装齐 + 称重。 */
  record Ready(UUID orderId, UUID sessionId, PackingCreated packing, UUID parcelId,
               PackingMaterialRow material) {}

  Ready ready(UUID tenant, String code, String quantity, Map<Integer, List<String>> scans,
      UUID... skuIds) {
    UUID loc = location(tenant, "A-1", "STORAGE");
    for (UUID skuId : skuIds) bucket(tenant, loc, skuId, "100");
    UUID orderId = outbound(tenant, code, quantity, skuIds);
    finishPicking(tenant, orderId, null);
    UUID sessionId = passedRecount(tenant, orderId, scans);
    var packing = openOrder(tenant, sessionId);
    var material = carton(tenant, "C-" + code, "200", "5000");
    var parcel = addParcel(tenant, packing.id(), material.id(), null);
    for (var line : as(tenant, () -> service.detail(packing.id().toString()).baseline())) {
      addItem(tenant, packing.id(), parcel.id(), line.id(), quantity, null);
    }
    measure(tenant, packing.id(), parcel.id(), "1200", "30", "20", "10");
    return new Ready(orderId, sessionId, packing, parcel.id(), material);
  }

  List<PackingBaselineRow> baseline(UUID tenant, UUID orderId) {
    return as(tenant, () -> service.detail(orderId.toString()).baseline());
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

  long balanceOnHand(UUID tenant, UUID skuId) {
    return as(
            tenant,
            () ->
                sql.fetchOne(
                        "select on_hand from inventory_balance where tenant_id"
                            + " = current_setting('app.current_tenant', true)::uuid"
                            + " and sku_id=? and warehouse_id=?",
                        skuId, wh(tenant))
                    .get("on_hand", BigDecimal.class)
                    .longValue());
  }

  long inventoryEntryCount(UUID tenant) {
    return as(
            tenant,
            () ->
                sql.fetchOne(
                        "select count(*) as c from inventory_entry where tenant_id"
                            + " = current_setting('app.current_tenant', true)::uuid")
                    .get("c", Long.class));
  }

  /** 用超级用户直连跑一段 SQL —— 用来证明「绕开服务层也过不去」。 */
  void superuserSql(String... statements) throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      for (var statement : statements) s.execute(statement);
    }
  }

  // ================================================================ 能力声明

  @Test
  void capabilitiesSayWhatIsMeasuredHereAndWhoseJobsAreRefused() {
    var caps = service.capabilities();
    // 实测重量体积是本模块的产出：口径必须写在返回里，而不是靠列名猜。
    assertThat(caps.weightUnit()).isEqualTo("g（克）");
    assertThat(caps.dimensionUnit()).isEqualTo("cm（厘米）");
    assertThat(caps.volumeUnit()).isEqualTo("cbm（立方米）");
    assertThat(caps.weightBasis()).contains("禁浮点");
    assertThat(caps.dimensionBasis()).contains("长×宽×高/10⁶");
    // 体积重归 10 物流：本模块拒算，不假装有除数。
    assertThat(caps.volumetricWeightBasis()).contains("10 物流").contains("除数");
    // 3.7.2 属 catalog，未接入就显式报 false，而不是编一份物理属性。
    assertThat(caps.declaredInformationBasis()).contains("catalog");
    assertThat(caps.unavailable()).extracting(UnavailableLink::ownerModule)
        .contains("3.7.2.1", "8.3.4.1", "10", "8.3.4.2");
    // 打包是只读记录动作：一件货都不搬。
    assertThat(caps.touchesInventory()).isFalse();
    assertThat(caps.statuses()).containsExactly("OPEN", "PACKED", "FAILED", "CANCELLED");
    // 刻意没有「已发货」：那是 8.3.4.1 的。
    assertThat(caps.statuses()).doesNotContain("SHIPPED");
  }

  // ================================================================ 包材目录

  @Test
  void materialCatalogueIsARealDirectoryAndRetireIsOneWay() {
    var box = carton(a, "C-1", "200", "5000");
    assertThat(box.materialRoleLabel()).isEqualTo("包材");
    assertThat(box.outerLengthCm()).isEqualTo("30");
    assertThat(box.tareWeightG()).isEqualTo("200");

    // 填充物不进净重口径：给填充物填自重当场拒。
    assertThat(catchThrowable(() -> asWrite(a, () -> service.createMaterial(
        new PackingMaterialInput("F-1", "填充", "CUSHIONING", "BUBBLE", wh(a), "30", "20", "10",
            "50", "0"), user))))
        .hasMessageContaining("tareWeightG");

    // 三边必填：包材外形是实测的上界，没有外形就守不住「选错包材」。
    assertThat(catchThrowable(() -> asWrite(a, () -> service.createMaterial(
        new PackingMaterialInput("C-X", "空外形", "PACKAGING", "CARTON", wh(a), null, "20", "10",
            "100", "0"), user))))
        .hasMessageContaining("outerLengthCm");

    // 真分页 + 真筛选
    filler(a, "F-2");
    var page = as(a, () -> service.materials(wh(a), "PACKAGING", null, null, null, 0, 50));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items().get(0).materialCode()).isEqualTo("C-1");
    assertThat(as(a, () -> service.materials(null, "CUSHIONING", null, null, null, 0, 50)).total())
        .isEqualTo(1);

    // 停用不能复活，且必须写原因
    asWrite(a, () -> service.retireMaterial(box.id().toString(),
        new PackingMaterialRetireInput("换新箱型"), user));
    assertThat(catchThrowable(() -> asWrite(a, () -> service.retireMaterial(box.id().toString(),
        new PackingMaterialRetireInput("再停一次"), user))))
        .hasMessageContaining("已经是停用状态");
    var detail = as(a, () -> service.material(box.id().toString()));
    assertThat(detail.material().status()).isEqualTo("RETIRED");
    assertThat(detail.material().retiredAt()).isNotNull();
  }

  // ================================================================ 开单

  @Test
  void openRequiresAPassedRecountAndFreezesItsBaseline() {
    UUID loc = location(a, "A-1", "STORAGE");
    bucket(a, loc, sku(a, 1), "100");
    UUID orderId = outbound(a, "OB-OPEN", "2", sku(a, 1));
    finishPicking(a, orderId, null);

    // 还没复核通过时开不了打包单
    assertThat(catchThrowable(() -> asWrite(a, () -> service.open(
        new PackingOpenInput(UUID.randomUUID(), user, null), user))))
        .hasMessageContaining("不存在");
    assertThat(outboundStatus(a, orderId)).isEqualTo("PENDING_RECHECK");

    UUID sessionId = passedRecount(a, orderId, null);
    var packing = openOrder(a, sessionId);
    assertThat(packing.status()).isEqualTo("OPEN");
    // 基线是从复核单冻结下来的，不是调用方写的
    assertThat(packing.baselineQuantity()).isEqualTo("2");
    assertThat(packing.baselineLineCount()).isEqualTo(1);
    assertThat(packing.recountSessionCode()).isNotBlank();
    assertThat(packing.packingBasis()).contains("复核单").contains("体积重不在本模块算");

    // 一张复核单只装一次
    assertThat(catchThrowable(() -> openOrder(a, sessionId)))
        .hasMessageContaining("已经有一张打包单");
  }

  @Test
  void everyBaselineLineMustBePackedBeforeCompletion() {
    UUID loc = location(a, "A-1", "STORAGE");
    bucket(a, loc, sku(a, 1), "100");
    bucket(a, loc, sku(a, 2), "100");
    UUID orderId = outbound(a, "OB-SHORT", "2", sku(a, 1), sku(a, 2));
    finishPicking(a, orderId, null);
    UUID sessionId = passedRecount(a, orderId, null);
    var packing = openOrder(a, sessionId);
    var material = carton(a, "C-S", "200", "5000");
    var parcel = addParcel(a, packing.id(), material.id(), null);

    var lines = baseline(a, packing.id());
    addItem(a, packing.id(), parcel.id(), lines.get(0).id(), "2", null);
    measure(a, packing.id(), parcel.id(), "1200", "30", "20", "10");

    // 第二行还没装：不能完成，且要说得清差多少
    var report = as(a, () -> service.completionOf(packing.id().toString()));
    assertThat(report.packable()).isFalse();
    assertThat(report.blockingReasons())
        .anySatisfy(r -> assertThat(r).contains("实装 2 件与复核基线 4 件不相等"));
    assertThat(report.blockingReasons())
        .noneSatisfy(r -> assertThat(r).contains("一件明细都没有"));
    assertThat(report.shortfallQuantity()).isEqualTo("2");
    assertThat(catchThrowable(() -> asWrite(a, () -> service.complete(
        packing.id().toString(), new PackingCompleteInput(null, null), user))))
        .hasMessageContaining("还不能完成");
  }

  // ================================================================ 包裹与明细

  @Test
  void parcelMustUsePackagingRoleAndFillerMustBeCushioningRole() {
    var r = ready(a, "OB-ROLE", "2", null, sku(a, 1));
    var f = filler(a, "F-R");
    // 把填充物当包材用 → 拒
    assertThat(catchThrowable(() -> asWrite(a, () -> service.addParcel(
        r.packing().id().toString(), new ParcelAddInput(f.id(), null, null, null), user))))
        .hasMessageContaining("填充物");
    // 把包材当填充物用 → 拒
    assertThat(catchThrowable(() -> asWrite(a, () -> service.addParcel(
        r.packing().id().toString(),
        new ParcelAddInput(r.material().id(), r.material().id(), null, null), user))))
        .hasMessageContaining("填充物");
  }

  @Test
  void parcelItemsMustTraceBackToARecountBaselineLineAndNeverOverPack() {
    var r = ready(a, "OB-TRACE", "2", null, sku(a, 1));
    var lines = baseline(a, r.packing().id());
    // 基线应发 2 件：一个包裹装 2 件可以，再装 1 件就超发了
    assertThat(catchThrowable(() -> addItem(a, r.packing().id(), r.parcelId(), lines.get(0).id(),
        "1", null)))
        .hasMessageContaining("超装");

    // 明细必须指回复核基线行：凭空来的 id 直接拒
    assertThat(catchThrowable(() -> addItem(a, r.packing().id(), r.parcelId(),
        UUID.randomUUID(), "1", null)))
        .hasMessageContaining("不存在");

    // 详情里能看到「哪些 SKU、多少件」—— 蓝图点名要的那份可追溯性
    var detail = as(a, () -> service.detail(r.packing().id().toString()));
    assertThat(detail.items()).hasSize(1);
    assertThat(detail.items().get(0).skuCode()).isEqualTo("SKU-1");
    assertThat(detail.items().get(0).quantity()).isEqualTo("2");
    assertThat(detail.items().get(0).recountLineId()).isEqualTo(lines.get(0).id());
    assertThat(detail.baseline().get(0).fullyPacked()).isTrue();
  }

  @Test
  void serialGoodsMustBePackedOneSerialPerItem() {
    UUID loc = location(a, "A-1", "STORAGE");
    bucket(a, loc, sku(a, 1), "100");
    UUID s1 = serial(a, sku(a, 1), "SN-1", loc);
    UUID s2 = serial(a, sku(a, 1), "SN-2", loc);

    UUID orderId = outbound(a, "OB-SER", "2", sku(a, 1));
    finishPicking(a, orderId, Map.of(0, List.of(s1, s2)));
    UUID sessionId = passedRecount(a, orderId, Map.of(0, List.of("SN-1", "SN-2")));
    var packing = openOrder(a, sessionId);
    var material = carton(a, "C-SER", "200", "5000");
    var parcel = addParcel(a, packing.id(), material.id(), null);
    var line = baseline(a, packing.id()).get(0);
    assertThat(line.requiresSerial()).isTrue();

    // 逐件核对的行：一行一件，没有序列号当场拒
    assertThat(catchThrowable(() -> addItem(a, packing.id(), parcel.id(), line.id(), "1", null)))
        .hasMessageContaining("一行一号");
    // 件数不为 1 也拒（CHECK 会兜，但服务层先说人话）
    assertThat(catchThrowable(() -> addItem(a, packing.id(), parcel.id(), line.id(), "2", s1)))
        .hasMessageContaining("件数必须恒为 1");

    var item = addItem(a, packing.id(), parcel.id(), line.id(), "1", s1);
    assertThat(item.serialNumber()).isEqualTo("SN-1");
    addItem(a, packing.id(), parcel.id(), line.id(), "1", s2);
    assertThat(baseline(a, packing.id()).get(0).fullyPacked()).isTrue();
  }

  // ================================================================ 称重与体积

  @Test
  void measureDerivesNetWeightAndVolumeInGramsAndCubicMetres() {
    var r = ready(a, "OB-MEAS", "2", null, sku(a, 1));
    var parcel = as(a, () -> service.detail(r.packing().id().toString())).parcels().get(0);
    // 1200 g 毛重 − 200 g 自重 = 1000 g 净重；30×20×10 cm = 0.006 cbm
    assertThat(parcel.measuredGrossWeightG()).isEqualTo("1200");
    assertThat(parcel.tareWeightG()).isEqualTo("200");
    assertThat(parcel.netContentWeightG()).isEqualTo("1000");
    assertThat(parcel.volumeCbm()).isEqualTo("0.006");
    assertThat(parcel.measured()).isTrue();
    // 单头合计由触发器汇总，不是服务层自己加的
    var packing = as(a, () -> service.detail(r.packing().id().toString())).order();
    assertThat(packing.parcelCount()).isEqualTo(1);
    assertThat(packing.parcelItemCount()).isEqualTo(1);
    assertThat(packing.measuredParcelCount()).isEqualTo(1);
    assertThat(packing.totalGrossWeightG()).isEqualTo("1200");
    assertThat(packing.totalVolumeCbm()).isEqualTo("0.006");
    assertThat(packing.packedQuantity()).isEqualTo("2");
  }

  @Test
  void overWeightOutOfSizeBoxAndUnderTareAreAllRejected() {
    var r = ready(a, "OB-GUARD", "2", null, sku(a, 1));
    UUID id = r.packing().id();
    // 超承重
    assertThat(catchThrowable(() -> measure(a, id, r.parcelId(), "6000", "30", "20", "10")))
        .hasMessageContaining("承重上限");
    // 外形超出包材（30×20×10）→ 选错包材
    assertThat(catchThrowable(() -> measure(a, id, r.parcelId(), "1200", "40", "20", "10")))
        .hasMessageContaining("选错包材");
    // 毛重比包材自重还轻 → 秤没归零
    assertThat(catchThrowable(() -> measure(a, id, r.parcelId(), "100", "30", "20", "10")))
        .hasMessageContaining("秤没归零");
    // 三边必填：没量尺寸就没有体积，交给 10 物流的产出缺一半
    assertThat(catchThrowable(() -> asWrite(a, () -> service.measure(id.toString(),
        r.parcelId().toString(),
        new ParcelMeasureInput("1200", "30", null, "10", null, null), user))))
        .hasMessageContaining("outerWidthCm");
  }

  @Test
  void measuredAtKeepsTheOffsetTheScaleGaveUs() {
    var r = ready(a, "OB-TZ", "2", null, sku(a, 1));
    // 东八区 2026-03-01 08:00 应该是 2026-03-01T00:00Z ——
    // 走 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s，把早班记录写成前一天下午。
    var read = asWrite(a, () -> service.measure(r.packing().id().toString(),
        r.parcelId().toString(),
        new ParcelMeasureInput("1200", "30", "20", "10", "2026-03-01T08:00:00+08:00", null), user));
    OffsetDateTime stored = read.measuredAt();
    assertThat(stored.toInstant()).isEqualTo(OffsetDateTime.parse("2026-03-01T08:00:00+08:00").toInstant());
    // 写回 DB 的值也要对（不只是在内存里对）
    OffsetDateTime inDb = as(a, () -> sql.fetchOne(
        "select measured_at from parcel where tenant_id"
            + " = current_setting('app.current_tenant', true)::uuid and id=?",
        r.parcelId()).get("measured_at", OffsetDateTime.class));
    assertThat(inDb.toInstant()).isEqualTo(OffsetDateTime.parse("2026-03-01T08:00:00+08:00").toInstant());
  }

  @Test
  void labelPrintIsReferencedNotReRendered() {
    var r = ready(a, "OB-LABEL", "2", null, sku(a, 1));
    UUID job = printJob(a);
    var parcel = asWrite(a, () -> service.recordLabel(r.packing().id().toString(),
        r.parcelId().toString(), new ParcelLabelInput(job, null, null), user));
    assertThat(parcel.labelPrintJobId()).isEqualTo(job);
    assertThat(parcel.labelPrintCount()).isEqualTo(1);
    // 再印一次是 +1（次数只增不减，权威在 8.3.4.2 的 label_print_log）
    var again = asWrite(a, () -> service.recordLabel(r.packing().id().toString(),
        r.parcelId().toString(), new ParcelLabelInput(job, null, null), user));
    assertThat(again.labelPrintCount()).isEqualTo(2);
    // 引用一个别的租户的打印任务 → 404
    UUID foreign = printJob(b);
    assertThat(catchThrowable(() -> asWrite(a, () -> service.recordLabel(
        r.packing().id().toString(), r.parcelId().toString(),
        new ParcelLabelInput(foreign, null, null), user))))
        .hasMessageContaining("不存在");
  }

  /** 造一张 8.3.4.2 已交付的打印任务（面单内容与次数的权威在它那边）。 */
  UUID printJob(UUID tenant) {
    return as(
        tenant,
        () -> {
          UUID templateId = UUID.randomUUID();
          sql.execute(
              "insert into print_template(id,tenant_id,code,name,object_type,paper_size)"
                  + " values(?,?,?,?,'SHIPPING_LABEL','A4')",
              templateId, tenant, "T-" + templateId.toString().substring(0, 6), "面单模板");
          UUID id = UUID.randomUUID();
          sql.execute(
              "insert into print_job(id,tenant_id,job_no,template_id,status) values(?,?,?,?,'DRAFT')",
              id, tenant, "PJ-" + id.toString().substring(0, 6), templateId);
          return id;
        });
  }

  // ================================================================ 申报快照

  /**
   * 申报快照：本服务真读得到的只有报关明细（V85，同服务）；读不到就如实说读不到。
   *
   * <p>蓝图输入写的「3.7.2 物理属性」属 catalog，本服务跨服务读不到 —— 所以这里断言的是
   * <b>没有编</b>：本租户没有这个 SKU 的报关明细时，HS 编码与申报品名恒为空，
   * 来源恒为 NOT_FOUND，且标签里点名 catalog 才是归属方。
   *
   * <p>正向的 CUSTOMS_DECLARATION_LINE 分支要造一条真报关明细，而 V85 的报关明细要求它的
   * 来源发运行已有<b>在途腿</b>（7.5.1.2 的发货分录守卫），那属于本车道之外的链路；
   * 那条分支与本测试走的是同一个 SELECT，缺口在此显式记下而不是假装覆盖了。
   */
  @Test
  void declarationSnapshotSaysNotFoundRatherThanInventingHsCode() {
    var r = ready(a, "OB-DECL", "2", null, sku(a, 1));
    var item = as(a, () -> service.detail(r.packing().id().toString())).items().get(0);
    assertThat(item.declarationSource()).isEqualTo("NOT_FOUND");
    assertThat(item.declarationSourceLabel()).contains("catalog");
    assertThat(item.hsCode()).isEmpty();
    assertThat(item.declaredItemName()).isEmpty();
    assertThat(item.declaredOriginCountryCode()).isEmpty();

    // 快照是**逐明细**读的：换一个 SKU 重开一张单，新明细同样是 NOT_FOUND，
    // 不会因为上一件读过就把别的 SKU 的申报信息串过来。
    UUID loc = location(a, "A-2", "STORAGE");
    bucket(a, loc, sku(a, 2), "100");
    UUID orderId = outbound(a, "OB-DECL2", "1", sku(a, 2));
    finishPicking(a, orderId, null);
    UUID sessionId = passedRecount(a, orderId, null);
    var packing2 = openOrder(a, sessionId);
    var material2 = carton(a, "C-DECL2", "200", "5000");
    var parcel2 = addParcel(a, packing2.id(), material2.id(), null);
    var line2 = baseline(a, packing2.id()).get(0);
    var item2 = addItem(a, packing2.id(), parcel2.id(), line2.id(), "1", null);
    assertThat(item2.declarationSource()).isEqualTo("NOT_FOUND");
    assertThat(item2.hsCode()).isEmpty();
  }

  // ================================================================ 完成 / 失败 / 取消

  @Test
  void completeMovesOutboundToPendingShipmentAndNeverToShipped() {
    var r = ready(a, "OB-DONE", "2", null, sku(a, 1));
    long onHandBefore = balanceOnHand(a, sku(a, 1));
    long entriesBefore = inventoryEntryCount(a);

    var report = as(a, () -> service.completionOf(r.packing().id().toString()));
    assertThat(report.packable()).isTrue();
    assertThat(report.blockingReasons()).isEmpty();

    var done = asWrite(a, () -> service.complete(r.packing().id().toString(),
        new PackingCompleteInput(null, null), user));
    assertThat(done.status()).isEqualTo("PACKED");
    assertThat(done.completedAt()).isNotNull();
    // 交给 8.3.4.1「待发货」，**不是已发货**（运单号与承运渠道是下游的事）
    assertThat(outboundStatus(a, r.orderId())).isEqualTo("PENDING_SHIPMENT");
    // 打包是只读记录：库存一行都没动，也没写分录
    assertThat(balanceOnHand(a, sku(a, 1))).isEqualTo(onHandBefore);
    assertThat(inventoryEntryCount(a)).isEqualTo(entriesBefore);

    var detail = as(a, () -> service.detail(r.packing().id().toString()));
    assertThat(detail.timeline()).extracting(PackingEventRow::eventType)
        .contains("OPENED", "PACKAGE_ADDED", "ITEM_ADDED", "MEASURED", "PACKED");
    // 详情显式报出四段「不由本模块负责」的相邻能力
    assertThat(detail.unavailable()).extracting(UnavailableLink::ownerModule)
        .contains("8.3.4.1", "10", "3.7.2.1");

    // 终态不可再动
    assertThat(catchThrowable(() -> addItem(a, r.packing().id(), r.parcelId(),
        baseline(a, r.packing().id()).get(0).id(), "1", null)))
        .hasMessageContaining("不能再装明细");
    assertThat(catchThrowable(() -> asWrite(a, () -> service.cancel(r.packing().id().toString(),
        new PackingCancelInput("反悔"), user))))
        .hasMessageContaining("不能再取消");
  }

  @Test
  void unmeasuredParcelBlocksCompletion() {
    UUID loc = location(a, "A-1", "STORAGE");
    bucket(a, loc, sku(a, 1), "100");
    UUID orderId = outbound(a, "OB-NOMEAS", "2", sku(a, 1));
    finishPicking(a, orderId, null);
    UUID sessionId = passedRecount(a, orderId, null);
    var packing = openOrder(a, sessionId);
    var material = carton(a, "C-NM", "200", "5000");
    var parcel = addParcel(a, packing.id(), material.id(), null);
    addItem(a, packing.id(), parcel.id(), baseline(a, packing.id()).get(0).id(), "2", null);
    // 装了但还没上秤
    var report = as(a, () -> service.completionOf(packing.id().toString()));
    assertThat(report.packable()).isFalse();
    assertThat(report.unmeasuredParcelCount()).isEqualTo(1);
    assertThat(report.blockingReasons()).anySatisfy(r -> assertThat(r).contains("没称重或没量尺寸"));
  }

  @Test
  void failSendsOutboundBackToRecheckAndCancelLeavesInventoryAlone() {
    var r = ready(a, "OB-FAIL", "2", null, sku(a, 1));
    long onHandBefore = balanceOnHand(a, sku(a, 1));
    var failed = asWrite(a, () -> service.fail(r.packing().id().toString(),
        new PackingFailInput("装箱时发现少一件"), user));
    assertThat(failed.status()).isEqualTo("FAILED");
    // V72 留的返工边：打包发现缺件 → 退回复核
    assertThat(outboundStatus(a, r.orderId())).isEqualTo("PENDING_RECHECK");
    assertThat(balanceOnHand(a, sku(a, 1))).isEqualTo(onHandBefore);
    // 失败必须写原因（在还开着的单上验：已失败的单会先撞状态冲突）
    var r0 = ready(a, "OB-FAIL0", "2", null, sku(a, 1));
    assertThat(catchThrowable(() -> asWrite(a, () -> service.fail(
        r0.packing().id().toString(), new PackingFailInput(" "), user))))
        .hasMessageContaining("原因");

    var r2 = ready(a, "OB-CANCEL", "2", null, sku(a, 1));
    long onHandBeforeCancel = balanceOnHand(a, sku(a, 1));
    var cancelled = asWrite(a, () -> service.cancel(r2.packing().id().toString(),
        new PackingCancelInput("客户改地址，重新打包"), user));
    assertThat(cancelled.status()).isEqualTo("CANCELLED");
    // 取消不动库存，也不改出库单状态（货还在打包区等着重打）
    assertThat(outboundStatus(a, r2.orderId())).isEqualTo("PENDING_PACKING");
    assertThat(balanceOnHand(a, sku(a, 1))).isEqualTo(onHandBeforeCancel);
  }

  @Test
  void databaseRefusesToPackAnOrderWithAnEmptyParcelEvenWhenTheServiceIsBypassed()
      throws Exception {
    var r2 = ready(a, "OB-BYPASS2", "2", null, sku(a, 1));
    asWrite(a, () -> service.addParcel(r2.packing().id().toString(),
        new ParcelAddInput(r2.material().id(), null, null, null), user));
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
      c.setAutoCommit(false);
      try (var s = c.createStatement()) {
        s.execute("select set_config('app.current_tenant','" + a + "',false)");
        s.execute("update packing_order set status='PACKED',completed_at=now(),packer_user_id='"
            + user + "' where id='" + r2.packing().id() + "'");
        c.commit();
      } catch (Exception e) {
        c.rollback();
        assertThat(e.getMessage()).contains("一件明细都没有");
        return;
      }
      throw new AssertionError("空箱的打包单被绕开服务层判成 PACKED 了");
    }
  }

  @Test
  void allFiveTablesForceRowLevelSecurity() {
    var forced =
        as(
            a,
            () ->
                sql.fetch(
                        "select relname, relrowsecurity, relforcerowsecurity from pg_class"
                            + " where relname in ('packing_material','packing_order','parcel',"
                            + "'parcel_item','packing_event') order by relname")
                    .stream()
                    .map(r -> r.get("relname", String.class) + ":"
                        + r.get("relrowsecurity", Boolean.class) + "/"
                        + r.get("relforcerowsecurity", Boolean.class))
                    .toList());
    assertThat(forced).hasSize(5);
    assertThat(forced).allSatisfy(row -> assertThat(row).endsWith("true/true"));
  }

  @Test
  void listPaginatesFiltersAndSummarises() {
    ready(a, "OB-L1", "2", null, sku(a, 1));
    ready(a, "OB-L2", "1", null, sku(a, 2));
    var page = as(a, () -> service.page(null, null, null, null, null, null, "STARTED_AT", 0, 50));
    assertThat(page.total()).isEqualTo(2);
    // 汇总不受筛选影响
    assertThat(page.summary().totalOrders()).isEqualTo(2);
    assertThat(page.summary().parcels()).isEqualTo(2);
    assertThat(page.summary().unmeasuredParcels()).isZero();
    assertThat(page.summary().packedQuantity()).isEqualTo("3");

    var filtered = as(a, () -> service.page(null, null, null, "OPEN", null, "OB-L1",
        "STARTED_AT", 0, 50));
    assertThat(filtered.total()).isEqualTo(1);
    assertThat(filtered.items().get(0).outboundOrderCode()).isEqualTo("OB-L1");
    assertThat(filtered.summary().totalOrders()).isEqualTo(2);

    assertThat(catchThrowable(() -> as(a, () -> service.page(null, null, null, "BOGUS", null,
        null, "STARTED_AT", 0, 50))))
        .hasMessageContaining("status");
    assertThat(catchThrowable(() -> as(a, () -> service.page(null, null, null, null, null, null,
        "DROPME", 0, 50))))
        .hasMessageContaining("sort");
    // 真分页
    assertThat(as(a, () -> service.page(null, null, null, null, null, null, "STARTED_AT", 0, 1))
            .items()).hasSize(1);
    assertThat(as(a, () -> service.page(null, null, null, null, null, null, "STARTED_AT", 1, 1))
            .items()).hasSize(1);
  }

  // ================================================================ 多租户三断言

  @Test
  void twoTenantsCannotSeeEachOther() {
    var ra = ready(a, "OB-ISO-A", "2", null, sku(a, 1));
    var rb = ready(b, "OB-ISO-B", "2", null, sku(b, 1));

    // B 用 A 的打包单 id 取详情 → 404
    assertThat(catchThrowable(() -> as(b, () -> service.detail(ra.packing().id().toString()))))
        .hasMessageContaining("不存在");
    // B 在 A 的打包单上建包裹 → 404（写路径同样隔离）
    assertThat(catchThrowable(() -> asWrite(b, () -> service.addParcel(
        ra.packing().id().toString(), new ParcelAddInput(ra.material().id(), null, null, null), user))))
        .hasMessageContaining("不存在");
    // B 完成 A 的打包单 → 404
    assertThat(catchThrowable(() -> asWrite(b, () -> service.complete(
        ra.packing().id().toString(), new PackingCompleteInput(null, null), user))))
        .hasMessageContaining("不存在");
    // B 也看不到 A 的包材
    assertThat(catchThrowable(() -> as(b, () -> service.material(ra.material().id().toString()))))
        .hasMessageContaining("不存在");
    // B 的列表里只有自己那一张
    var bList = as(b, () -> service.page(null, null, null, null, null, null, "STARTED_AT", 0, 50));
    assertThat(bList.total()).isEqualTo(1);
    assertThat(bList.items().get(0).id()).isEqualTo(rb.packing().id());
    // B 开不了 A 的复核单的打包单
    assertThat(catchThrowable(() -> openOrder(b, ra.sessionId())))
        .hasMessageContaining("不存在");
  }

  @Test
  void forgedTenantHeaderIsIgnored() throws Exception {
    var r = ready(a, "OB-FT", "2", null, sku(a, 1));
    // 伪造 X-Tenant-Id 头：代理忽略它，身份由 Authorization 解析。
    // 连身份头也伪造时结果是 401，而不是「换成 B 的租户」。
    var forged =
        http.perform(
                get("/warehouse/packing-orders/" + r.packing().id())
                    .header("Authorization", "Bearer forged")
                    .header("X-Tenant-Id", b.toString()))
            .andReturn()
            .getResponse();
    assertThat(forged.getStatus()).isEqualTo(401);

    // 带上真实身份但伪造租户头：看到的仍然只有 A 自己那一张
    http.perform(
                get("/warehouse/packing-orders")
                    .header("Authorization", "Bearer tenant-a")
                    .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].packingCode").value(r.packing().packingCode()));
  }

  @Test
  void pooledConnectionDoesNotLeakTenantAcrossReturns() {
    var ra = ready(a, "OB-POOL-A", "2", null, sku(a, 1));
    var rb = ready(b, "OB-POOL-B", "2", null, sku(b, 1));
    // A → B → A 三轮：连接池只有 1 条连接，每次归还后都可能被下一个人拿到。
    for (int round = 0; round < 3; round++) {
      assertThat(as(a, () -> service.page(null, null, null, null, null, null, "PACKING_CODE",
                  0, 50)).items().get(0).id()).isEqualTo(ra.packing().id());
      assertThat(as(b, () -> service.page(null, null, null, null, null, null, "PACKING_CODE",
                  0, 50)).items().get(0).id()).isEqualTo(rb.packing().id());
      assertThat(as(a, () -> service.detail(ra.packing().id().toString())).order().packingCode())
          .isEqualTo(ra.packing().packingCode());
      assertThat(as(b, () -> service.detail(rb.packing().id().toString())).order().packingCode())
          .isEqualTo(rb.packing().packingCode());
      assertThat(as(a, () -> service.materials(null, "CUSHIONING", null, null, null, 0, 50)).total())
          .isZero();
    }
  }
}