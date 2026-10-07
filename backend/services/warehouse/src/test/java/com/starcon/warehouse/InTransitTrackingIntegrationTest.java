package com.starcon.warehouse;

import static com.starcon.warehouse.InTransitTrackingModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
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
 * 10.4.1.2 在途跟踪。
 *
 * <p>本测试要证明的不是「接口通不通」，而是六条会算错的口径：
 *
 * <ol>
 *   <li><b>预警提前到预计到港前若干天</b>，且档位边界由**本租户阈值表**决定 ——
 *       改一行阈值，同一条跟踪的档位随之改变（证明分级不是代码里的常量）。
 *   <li><b>不建第二张在途账</b>：发运 / 已了结 / 未达三个数一律 join 7.5.1.2 的
 *       {@code inventory_transfer} 现算，到货 60 是真的走了 7.5.1.2 的到达登记。
 *   <li><b>timestamptz 真的写回正确时刻</b>（含时区偏移），不是只断言非空。
 *   <li><b>阶段只进不退</b>，服务层与 DB 触发器两处同一套序号。
 *   <li><b>收货确认只记事实不写库存</b>：确认后 {@code inventory_transfer} 一行未改。
 *   <li><b>租户隔离三断言</b>：互不可见、伪造头无效、连接池归还不串租户。
 * </ol>
 *
 * <p>夹具写法与 {@link FirstMileShipmentIntegrationTest} 一致（同样的 Testcontainers、
 * 同样的 fake identity server、同样的按租户派生 UUID、同样的 TRUNCATE 清场），
 * 派生 id 前缀不同，避免与别的车道互相污染。
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InTransitTrackingIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final java.util.Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final List<String> allPermissions =
      java.util.stream.Stream.of("inventory")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "warehouse." + p + "." + op))
          .toList();

  /** ETA 相对现在的天数 —— 分级断言全用「相对现在」，换一天跑也不会翻车。 */
  static final int ETA_ONT = 40, ETA_WATCH = 5, ETA_WARNING = 2, ETA_OVERDUE = -2;

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-transittrack-test-" + tenant + "-" + ordinal).getBytes());
  }

  static AccessService.Access actor(
      UUID uid, UUID tenant, UUID dept, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        dept,
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
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer read-only",
          actor(user, a, null, List.of(grant("ALL", List.of("warehouse.inventory.read")))));
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
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired InTransitTrackingService tracking;
  @Autowired FirstMileShipmentService shipments;
  @Autowired InventoryInTransitService inTransit;
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
    // TRUNCATE 以超级用户连接执行可绕过 FORCE RLS —— 只用于夹具重建，不给应用角色开这个口子。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate first_mile_delay_alert, first_mile_tracking_event, first_mile_delay_threshold,"
              + " first_mile_tracking, first_mile_shipment_event, first_mile_shipment_leg_receipt,"
              + " first_mile_shipment_line, first_mile_shipment,"
              + " goods_receipt_discrepancy, goods_receipt_event_serial,"
              + " goods_receipt_event_line, goods_receipt_event, goods_receipt_line,"
              + " goods_receipt, in_transit_event, inventory_transfer,"
              + " stock_bucket, warehouse_location, inventory_lot, inventory_entry,"
              + " inventory_transaction, inventory_balance, inventory_quality_status, warehouse,"
              + " sku_ref, legal_entity_ref restart identity cascade");
    }
    // 每个租户各一套完整夹具：B 也必须有自己那份，否则「B 看不到 A」会退化成
    // 「B 因缺夹具而建不出来」，证明不了 RLS，只证明得了外键。
    for (var tenant : List.of(a, b)) {
      access.run(
          actorFor(tenant),
          "warehouse.inventory.read",
          () -> {
            seedTenant(tenant);
            return null;
          });
    }
  }

  private void seedTenant(UUID tenant) {
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
        "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
        idFor(tenant, 4), tenant, "WH-SH", "上海仓", "CN", "Asia/Shanghai");
    sql.execute(
        "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
        idFor(tenant, 5), tenant, "WH-LA", "洛杉矶海外仓", "US", "America/Los_Angeles");
    sql.execute(
        "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,pickable)"
            + " values(?,?,?,?,?,'STORAGE',true)",
        idFor(tenant, 6), tenant, idFor(tenant, 4), "SH-01", "库位 SH-01");
    sql.execute(
        "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,pickable)"
            + " values(?,?,?,?,?,'STORAGE',true)",
        idFor(tenant, 7), tenant, idFor(tenant, 5), "LA-01", "库位 LA-01");
    sql.execute(
        "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
        idFor(tenant, 8), tenant, "SELLABLE", "可售", "SELLABLE");
    sql.execute(
        "insert into inventory_lot(id,tenant_id,sku_id,lot_code,expires_on) values(?,?,?,?,?)",
        idFor(tenant, 9), tenant, idFor(tenant, 2), "LOT-1", LocalDate.of(2027, 3, 1));
    // 8.2.1.1 收货单 + 逐次实收行：头程发运的货物来源。
    sql.execute(
        "insert into goods_receipt(id,tenant_id,receipt_code,source_type,source_ref_code,"
            + "warehouse_id,legal_entity_id,status,created_by) values(?,?,?,'TRANSFER','TO-1',?,?,'RECEIVED',?)",
        idFor(tenant, 20), tenant, "GR-20261001-000001", idFor(tenant, 4), idFor(tenant, 1), user);
    sql.execute(
        "insert into goods_receipt_line(id,tenant_id,receipt_id,sku_id,quality_status_id,"
            + "expected_quantity) values(?,?,?,?,?,?)",
        idFor(tenant, 21), tenant, idFor(tenant, 20), idFor(tenant, 2), idFor(tenant, 8),
        new BigDecimal("2000"));
    sql.execute(
        "insert into goods_receipt_event(id,tenant_id,receipt_id,received_at,location_id,"
            + "waybill_no,actor_id) values(?,?,?,?::timestamptz,?,?,?)",
        idFor(tenant, 22), tenant, idFor(tenant, 20),
        OffsetDateTime.of(2026, 10, 1, 9, 30, 0, 0, ZoneOffset.ofHours(8)),
        idFor(tenant, 6), "WB-1", user);
    sql.execute(
        "insert into goods_receipt_event_line(id,tenant_id,event_id,line_id,received_quantity,"
            + "rejected_quantity,lot_id,expiry_date) values(?,?,?,?,?,0,?,?)",
        idFor(tenant, 23), tenant, idFor(tenant, 22), idFor(tenant, 21), new BigDecimal("2000"),
        idFor(tenant, 9), LocalDate.of(2027, 3, 1));
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "warehouse.inventory.read", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "warehouse.inventory.read", work);
  }

  <T> T asAWrite(java.util.function.Supplier<T> work) {
    return access.run(actorA, "warehouse.inventory.update", work);
  }

  <T> T asBWrite(java.util.function.Supplier<T> work) {
    return access.run(actorB, "warehouse.inventory.update", work);
  }

  // ================================================================ 夹具：走 10.4.1.1 + 7.5.1.2 的真路径

  /** ETA：相对现在的天数。 */
  static OffsetDateTime eta(int daysFromNow) {
    return OffsetDateTime.now(ZoneOffset.ofHours(8)).plusDays(daysFromNow).withMinute(0).withSecond(0).withNano(0);
  }

  private FirstMileShipmentModels.FirstMileCreateRequest shipmentReq(UUID tenant, int etaDays) {
    return new FirstMileShipmentModels.FirstMileCreateRequest(
        "GOODS_RECEIPT", idFor(tenant, 20), "GR-20261001-000001",
        idFor(tenant, 1), idFor(tenant, 4), idFor(tenant, 5), "US", "洛杉矶海外仓",
        "SEA", "中远海运", "COSCO-" + etaDays, "COSCO SHIPPING ARIES", "FOB", "SELLER", "USD",
        new BigDecimal("2200.0000"), new BigDecimal("150.0000"), new BigDecimal("380.0000"),
        new BigDecimal("260.0000"), "某某报关行", eta(etaDays - 30), eta(etaDays), "首批头程",
        List.of(
            new FirstMileShipmentModels.FirstMileLineRequest(
                idFor(tenant, 2), idFor(tenant, 8), idFor(tenant, 23), idFor(tenant, 9),
                new BigDecimal("100"), "棉制男士衬衫", "6205200090", "ACME", "CN",
                new BigDecimal("12.5000"), new BigDecimal("1250.0000"), new BigDecimal("180.0000"),
                new BigDecimal("160.0000"), new BigDecimal("1.250000"), 12)));
  }

  /** 建一张发运单（草稿）。 */
  UUID draft(UUID tenant, int etaDays) {
    return asAWrite(() -> shipments.create(tenant, shipmentReq(tenant, etaDays)).id());
  }

  /** 建 → 确认 → 发出。发出时 10.4.1.1 真在 7.5.1.2 登记了 FIRST_MILE 在途腿。 */
  UUID dispatched(UUID tenant, int etaDays, String qty) {
    return dispatchedAs(tenant, etaDays, qty, false);
  }

  /** 同上，但按租户选写入通道（B 租户必须走 B 的成员关系，不能借用 A 的权限）。 */
  UUID dispatchedAs(UUID tenant, int etaDays, String qty, boolean asTenantB) {
    java.util.function.Supplier<UUID> work =
        asTenantB
            ? () -> asBWrite(() -> buildDispatched(tenant, etaDays, qty))
            : () -> asAWrite(() -> buildDispatched(tenant, etaDays, qty));
    return work.get();
  }

  /** 建 → 确认 → 发出，返回发运单 id。发出时 10.4.1.1 真在 7.5.1.2 登记了 FIRST_MILE 在途腿。 */
  private UUID buildDispatched(UUID tenant, int etaDays, String qty) {
    var s = shipments.create(tenant, shipmentReq(tenant, etaDays));
    var c =
        shipments.confirm(
            s.id(),
            new FirstMileShipmentModels.FirstMileConfirmRequest(
                s.version(), eta(etaDays - 30), eta(etaDays), "已订舱"));
    var lineId =
        sql.fetchOne("select id from first_mile_shipment_line where shipment_id=?", s.id())
            .get(0, UUID.class);
    shipments.dispatch(
        tenant,
        s.id(),
        new FirstMileShipmentModels.FirstMileDispatchRequest(
            c.version(), null, "已装船",
            List.of(
                new FirstMileShipmentModels.FirstMileDispatchLineRequest(
                    lineId, new BigDecimal(qty)))));
    return s.id();
  }

  /** 建跟踪（默认在起运地）。 */
  TrackingRow createTracking(UUID tenant, UUID shipmentId) {
    return asAWrite(
        () ->
            tracking.create(
                user,
                new TrackingCreateRequest(shipmentId, "上海洋山港", eta(0), null, null, null, null)));
  }

  // ================================================================ 建跟踪的前置约束

  @Test
  @DisplayName("只有已发出的发运单才谈得上在途：草稿建跟踪被拒，且必须给已发出单建得出")
  void 建跟踪_必须是已发出() {
    var draftId = draft(a, ETA_ONT);
    assertThatThrownBy(
            () ->
                asAWrite(
                    () ->
                        tracking.create(
                            user, new TrackingCreateRequest(draftId, null, null, null, null, null, null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("货还没发出");

    var dispatchedId = dispatched(a, ETA_ONT, "100");
    assertThat(createTracking(a, dispatchedId).shipmentId()).isEqualTo(dispatchedId);
  }

  @Test
  @DisplayName("一张发运单只有一条跟踪记录：重复建返回 409 而不是让「这批货在哪」出现两个答案")
  void 建跟踪_一张发运单一条() {
    var shipmentId = dispatched(a, ETA_ONT, "100");
    var mine = createTracking(a, shipmentId);
    assertThatThrownBy(() -> createTracking(a, shipmentId))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已建立跟踪记录");
    assertThat(asA(() -> tracking.page(new TrackingQuery(null, null, null, null, null, null, null, null,
        null, null, null, 0, 50)).total())).isEqualTo(1L);
    assertThat(mine.status()).isEqualTo("TRACKING");
  }

  // ================================================================ 预警按剩余窗口提前分级

  @Test
  @DisplayName("预警提前到预计到港前若干天：剩余 40 天无预警 / 5 天提示 / 2 天预警 / 已过港严重延误")
  void 预警_按剩余窗口分级() {
    var onTrack = dispatched(a, ETA_ONT, "100");
    var watch = dispatched(a, ETA_WATCH, "100");
    var warning = dispatched(a, ETA_WARNING, "100");
    var overdue = dispatched(a, ETA_OVERDUE, "100");
    createTracking(a, onTrack);
    createTracking(a, watch);
    createTracking(a, warning);
    createTracking(a, overdue);

    // 四条各自评估：只有真有档位的才落预警行。
    assertThat(asAWrite(() -> tracking.raiseAlerts(idOfShipment(onTrack), user))).isZero();
    assertThat(asAWrite(() -> tracking.raiseAlerts(idOfShipment(watch), user))).isEqualTo(1);
    assertThat(asAWrite(() -> tracking.raiseAlerts(idOfShipment(warning), user))).isEqualTo(1);
    assertThat(asAWrite(() -> tracking.raiseAlerts(idOfShipment(overdue), user))).isEqualTo(1);

    var page = asA(() -> tracking.page(query(null, null, null, null, 0, 50)));
    assertThat(levelOf(page, onTrack)).isEqualTo("ON_TRACK");
    assertThat(levelOf(page, watch)).isEqualTo("WATCH");
    assertThat(levelOf(page, warning)).isEqualTo("WARNING");
    assertThat(levelOf(page, overdue)).isEqualTo("CRITICAL");
    // 全量汇总：合计不受分页影响（这里刻意只看 page size=2 的第一页，合计仍要是全量）。
    var firstPage = asA(() -> tracking.page(query(null, null, null, null, 0, 2)));
    assertThat(firstPage.rows()).hasSize(2);
    assertThat(firstPage.total()).isEqualTo(4L);
    assertThat(firstPage.watchCount()).isEqualTo(1L);
    assertThat(firstPage.warningCount()).isEqualTo(1L);
    assertThat(firstPage.criticalCount()).isEqualTo(1L);
    assertThat(firstPage.overdueCount()).isEqualTo(1L);
    assertThat(firstPage.trackingTotal()).isEqualTo(4L);
  }

  @Test
  @DisplayName("阈值落库可配置：改一行阈值，同一条跟踪的档位立刻随之改变（不是代码常量）")
  void 阈值_改一行就换口径() {
    var shipmentId = dispatched(a, ETA_WATCH, "100"); // 剩余 5 天
    createTracking(a, shipmentId);
    var trackingId = idOfShipment(shipmentId);
    assertThat(asAWrite(() -> tracking.raiseAlerts(trackingId, user))).isEqualTo(1);
    assertThat(levelOf(asA(() -> tracking.page(query(null, null, null, null, 0, 50))), shipmentId))
        .isEqualTo("WATCH");

    // 把 warning 提前量从 3 放宽到 9：剩余 5 天现在就进预警区。
    // watch 必须一并抬到 9 —— 三档递减是硬约束（否则「进观察区」反而比「进提示区」晚，
    // 分级会静默倒挂），所以这一改是**两档一起动**，不是单行孤立地改 warning。
    var defaults = asA(() -> tracking.thresholds()).stream()
        .filter(t -> t.transportMode().equals("*") && t.countryCode().equals("*"))
        .findFirst()
        .orElseThrow();
    asAWrite(
        () ->
            tracking.updateThreshold(
                defaults.id(), new DelayThresholdUpdateRequest(9, 9, 0, "两档一并放宽到 9 天")));

    assertThat(levelOf(asA(() -> tracking.page(query(null, null, null, null, 0, 50))), shipmentId))
        .isEqualTo("WARNING");
    // 口径真的进了 DB：读回来是改后的值。
    assertThat(
            asA(() -> tracking.thresholds()).stream()
                .filter(t -> t.id().equals(defaults.id()))
                .findFirst()
                .orElseThrow()
                .warningLeadDays())
        .isEqualTo(9);
    // 递减性钉死：warning 大于 watch 会被拒（否则「进观察区」反而比「进提示区」晚）。
    assertThatThrownBy(
            () ->
                asAWrite(
                    () ->
                        tracking.updateThreshold(
                            defaults.id(), new DelayThresholdUpdateRequest(3, 9, 0, "倒挂"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能大于");
  }

  @Test
  @DisplayName("全新租户没有阈值行时惰性补一行默认口径（读路径不 409，也不用代码常量兜底）")
  void 阈值_全新租户惰性bootstrap() {
    var fresh = UUID.randomUUID();
    var saved = actors.get("Bearer tenant-a");
    var other =
        new AccessService.Access(
            user, fresh, "Fresh", UUID.randomUUID(), null, allPermissions,
            List.of(grant("ALL", allPermissions)));
    // 第一次要口径（看板就会触发）即惰性补出默认行，不是 409，也不是空列表。
    var after =
        access.run(
            other,
            "warehouse.inventory.read",
            () -> tracking.page(query(null, null, null, null, 0, 50)).rows());
    assertThat(after).isEmpty(); // 全新租户没有跟踪记录，这是对的
    var rows = access.run(other, "warehouse.inventory.read", () -> tracking.thresholds());
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).watchLeadDays()).isEqualTo(7);
    assertThat(rows.get(0).warningLeadDays()).isEqualTo(3);
    assertThat(rows.get(0).criticalLeadDays()).isZero();
    assertThat(saved).isNotNull();
  }

  @Test
  @DisplayName("预警幂等：同档位重复评估只刷新不堆行；档位回落自动关掉更轻的旧档")
  void 预警_幂等与回落() {
    var shipmentId = dispatched(a, ETA_OVERDUE, "100"); // 已过港 → CRITICAL
    createTracking(a, shipmentId);
    var trackingId = idOfShipment(shipmentId);
    assertThat(asAWrite(() -> tracking.raiseAlerts(trackingId, user))).isEqualTo(1);
    // 再评估两次：都不该新增行。
    assertThat(asAWrite(() -> tracking.raiseAlerts(trackingId, user))).isZero();
    assertThat(asAWrite(() -> tracking.raiseAlerts(trackingId, user))).isZero();
    assertThat(asA(() -> tracking.detail(trackingId).alerts())).hasSize(1);

    // 改期把 ETA 拉远 → 回到 ON_TRACK，所有未结预警自动解除。
    asAWrite(
        () ->
            tracking.recordEvent(
                trackingId,
                user,
                ev().kind("ETA_REVISED").eta(eta(60), "货代通知延后")
                    .detail("货代通知延后 60 天").occurredAt(eta(0)).build()));
    var detail = asA(() -> tracking.detail(trackingId));
    assertThat(detail.tracking().delayLevel()).isEqualTo("ON_TRACK");
    assertThat(
            detail.alerts().stream().filter(x -> !x.status().equals("CLOSED")).count())
        .isZero();
    // 改期事件留下了前后值与改期次数，延误历史不被抹平。
    var revised =
        detail.events().stream().filter(e -> e.kind().equals("ETA_REVISED")).findFirst().orElseThrow();
    assertThat(revised.etaBefore()).isNotNull();
    assertThat(revised.etaAfter()).isNotNull();
    assertThat(detail.tracking().etaRevisedCount()).isEqualTo(1);
    assertThat(detail.tracking().originalEta()).isNotNull();
  }

  // ================================================================ 不建第二张在途账

  @Test
  @DisplayName("在途数量一律 join 7.5.1.2：发运 100 / 已了结 60 / 未达 40，本模块不持有余额")
  void 数量_读7_5_1_2现算() {
    var shipmentId = dispatched(a, ETA_ONT, "100");
    createTracking(a, shipmentId);
    var legId =
        asA(
                () ->
                    sql.fetchOne(
                            "select transit_leg_id from first_mile_shipment_leg_receipt where shipment_id=?",
                            shipmentId)
                        .get(0, UUID.class));
    // 真走 7.5.1.2 的到达登记落 60（不是直接改 settled_quantity，那里有约束触发器）。
    asAWrite(
        () ->
            inTransit.arrive(
                legId,
                new com.starcon.warehouse.InventoryInTransitModels.InTransitArriveRequest(
                    0L, eta(0), idFor(a, 7), idFor(a, 9), "到港卸柜", new BigDecimal("60"))));

    var detail = asA(() -> tracking.detail(idOfShipment(shipmentId)));
    assertThat(detail.legs()).hasSize(1);
    var leg = detail.legs().get(0);
    assertThat(leg.shippedQuantity()).isEqualByComparingTo("100");
    assertThat(leg.settledQuantity()).isEqualByComparingTo("60");
    assertThat(leg.remainingQuantity()).isEqualByComparingTo("40");
    // 看板行上的两个数与腿一致：不是本表自己存的一份。
    assertThat(detail.tracking().inTransitQuantity()).isEqualByComparingTo("40");
    assertThat(detail.tracking().settledQuantity()).isEqualByComparingTo("60");
    assertThat(detail.tracking().legCount()).isEqualTo(1L);
  }

  @Test
  @DisplayName("收货确认只记事实不写库存：确认后阶段 RECEIVED、状态 CLOSED，在途腿一行未改")
  void 收货确认_不写库存() {
    var shipmentId = dispatched(a, ETA_ONT, "100");
    var trackingId = createTracking(a, shipmentId).id();
    var legBefore = asA(() -> legRow(shipmentId).toString());
    var receiptAt = eta(-1);
    asAWrite(
        () ->
            tracking.confirmReceipt(
                trackingId,
                user,
                new TrackingReceiptRequest("GRC-2026-001", receiptAt, "外箱轻微破损 2 件")));

    var detail = asA(() -> tracking.detail(trackingId));
    assertThat(detail.tracking().trackingStage()).isEqualTo("RECEIVED");
    assertThat(detail.tracking().status()).isEqualTo("CLOSED");
    assertThat(detail.tracking().receiptConfirmedAt()).isNotNull();
    // timestamptz 写回：断言具体时刻与时区，不是只断言非空。
    assertThat(detail.tracking().receiptConfirmedAt().toInstant())
        .isEqualTo(receiptAt.toInstant());
    // 本模块一个库存字段都没碰。
    assertThat(asA(() -> legRow(shipmentId).toString())).isEqualTo(legBefore);
    // 已闭环后不再接受位置/阶段类事件（DB 触发器 guard_first_mile_tracking_event_source 兜底）。
    assertThatThrownBy(
            () ->
                asAWrite(
                    () ->
                        tracking.recordEvent(
                            trackingId,
                            user,
                            ev().kind("LOCATION").location("东京", eta(0))
                                .detail("还在海上").occurredAt(eta(0)).build())))
        .isInstanceOf(ResponseStatusException.class);
  }

  /**
   * 该发运单在 7.5.1.2 名下那条在途腿的三列快照。
   *
   * <p>按 {@code first_mile_shipment_leg_receipt.transit_leg_id} 定位，而不是拿发运单 id
   * 去 like {@code inventory_transfer.source_reference}：那里的锚点是
   * 「发运单**号**-行号」（V80 定的幂等锚点），拿 UUID 去 like 永远匹配不到，
   * 断言会在「查不到行」上空转，等于没验。
   */
  private org.jooq.Record legRow(UUID shipmentId) {
    return sql.fetchOne(
            "select t.status,t.quantity,t.settled_quantity"
                + " from first_mile_shipment_leg_receipt g"
                + " join inventory_transfer t on t.tenant_id=g.tenant_id and t.id=g.transit_leg_id"
                + " where g.shipment_id=?",
            shipmentId);
  }

  // ================================================================ 节点事件与来源纪律

  @Test
  @DisplayName("timestamptz 往返正确：位置发生时间与改期后的 ETA 都断言写回值")
  void 节点_时间往返() {
    var shipmentId = dispatched(a, ETA_ONT, "100");
    createTracking(a, shipmentId);
    var trackingId = idOfShipment(shipmentId);
    var locatedAt = OffsetDateTime.of(2026, 10, 20, 14, 5, 0, 0, ZoneOffset.ofHours(-7));
    asAWrite(
        () ->
            tracking.recordEvent(
                trackingId,
                user,
                ev().kind("LOCATION").location("Singapore", locatedAt)
                    .detail("过境新加坡").occurredAt(locatedAt).build()));
    var detail = asA(() -> tracking.detail(trackingId));
    assertThat(detail.tracking().currentLocation()).isEqualTo("Singapore");
    assertThat(detail.tracking().locationOccurredAt().toInstant()).isEqualTo(locatedAt.toInstant());

    var newEta = OffsetDateTime.of(2026, 12, 1, 8, 0, 0, 0, ZoneOffset.ofHours(8));
    asAWrite(
        () ->
            tracking.recordEvent(
                trackingId,
                user,
                ev().kind("ETA_REVISED").source("FORWARDER", "FF-BOOK-77").eta(newEta, "船公司跳港")
                    .detail("船公司通知跳港").occurredAt(eta(0)).build()));
    var after = asA(() -> tracking.detail(trackingId));
    assertThat(after.tracking().revisedEta().toInstant()).isEqualTo(newEta.toInstant());
    assertThat(after.tracking().effectiveEta().toInstant()).isEqualTo(newEta.toInstant());
    // 外部来源的单号必须留痕。
    var external =
        after.events().stream().filter(e -> e.kind().equals("ETA_REVISED")).findFirst().orElseThrow();
    assertThat(external.sourceSystem()).isEqualTo("FORWARDER");
    assertThat(external.referenceId()).isEqualTo("FF-BOOK-77");
  }

  @Test
  @DisplayName("外部来源必须有单号、人工必须有操作人；扣关/退单必须有原因")
  void 事件_来源与原因纪律() {
    var shipmentId = dispatched(a, ETA_ONT, "100");
    var trackingId = createTracking(a, shipmentId).id();

    // 外部来源无单号 → 拒。
    assertThatThrownBy(
            () ->
                asAWrite(
                    () ->
                        tracking.recordEvent(
                            trackingId,
                            user,
                            ev().kind("LOCATION").source("CARRIER", "").location("上海", eta(0))
                                .detail("船司报的").occurredAt(eta(0)).build())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("referenceId");

    // 扣关无原因 → 拒。
    assertThatThrownBy(
            () ->
                asAWrite(
                    () ->
                        tracking.recordEvent(
                            trackingId,
                            user,
                            ev().kind("CUSTOMS").customs("HELD", null)
                                .detail("被扣关").occurredAt(eta(0)).build())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须给出原因");

    // 有原因就能落。
    asAWrite(
        () ->
            tracking.recordEvent(
                trackingId,
                user,
                ev().kind("CUSTOMS").source("CUSTOMS_BROKER", "DECL-9911").customs("HELD", "查验未通过，缺少成分报告")
                    .detail("扣关").occurredAt(eta(0)).build()));
    assertThat(asA(() -> tracking.detail(trackingId).tracking().customsStatus())).isEqualTo("HELD");
  }

  @Test
  @DisplayName("阶段只进不退：服务层与 DB 触发器同一套序号，倒退被拒")
  void 阶段_只进不退() {
    var shipmentId = dispatched(a, ETA_ONT, "100");
    var trackingId = createTracking(a, shipmentId).id();
    asAWrite(
        () ->
            tracking.recordEvent(
                trackingId,
                user,
                ev().kind("STAGE").toStage("IN_CUSTOMS")
                    .detail("已到港进关").occurredAt(eta(0)).build()));
    assertThat(asA(() -> tracking.detail(trackingId).tracking().trackingStage()))
        .isEqualTo("IN_CUSTOMS");
    // 倒退（IN_CUSTOMS → IN_TRANSIT）被服务层拒。
    assertThatThrownBy(
            () ->
                asAWrite(
                    () ->
                        tracking.recordEvent(
                            trackingId,
                            user,
                            ev().kind("STAGE").toStage("IN_TRANSIT")
                                .detail("往回退").occurredAt(eta(0)).build())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不允许");
    // 任何阶段都能进 EXCEPTION（出事不需要先走到某一步）。
    asAWrite(
        () ->
            tracking.recordEvent(
                trackingId,
                user,
                ev().kind("STAGE").toStage("EXCEPTION")
                    .detail("查验期间货损").occurredAt(eta(0)).build()));
    assertThat(asA(() -> tracking.detail(trackingId).tracking().trackingStage()))
        .isEqualTo("EXCEPTION");
  }

  // ================================================================ 能力声明

  @Test
  @DisplayName("capabilities 如实声明外部同步未接入，不编造同步任务")
  void 能力_未交付依赖逐条声明() {
    var caps = asA(tracking::capabilities);
    assertThat(caps.dependencies())
        .filteredOn(d -> d.code().equals("FORWARDER_CONNECTOR"))
        .singleElement()
        .satisfies(d -> {
          assertThat(d.available()).isFalse();
          assertThat(d.ownerModule()).isEqualTo("10.4.2.1");
          assertThat(d.note()).contains("未交付");
        });
    // 已交付的依赖标 true：跟踪真的挂在 10.4.1.1 发运单上，在途余量真的读 7.5.1.2。
    assertThat(
            caps.dependencies().stream()
                .filter(d -> d.code().equals("IN_TRANSIT_STOCK"))
                .findFirst()
                .orElseThrow()
                .available())
        .isTrue();
    // 补货（6.2.1.1）未交付：如实标不可用，不反向调用。
    assertThat(
            caps.dependencies().stream()
                .filter(d -> d.code().equals("REPLENISHMENT"))
                .findFirst()
                .orElseThrow()
                .available())
        .isFalse();
    // 两级状态机都随响应返回。
    assertThat(caps.statusTransitions()).isNotEmpty();
    assertThat(caps.stageTransitions()).isNotEmpty();
    assertThat(caps.delayLevels()).extracting(TrackingOption::code).contains("CRITICAL", "WATCH");
  }

  // ================================================================ RLS 与端点

  @Test
  @DisplayName("四张业务表都 FORCE RLS")
  void RLS_逐张表断言() {
    for (var table :
        List.of(
            "first_mile_tracking",
            "first_mile_tracking_event",
            "first_mile_delay_alert",
            "first_mile_delay_threshold")) {
      assertThat(
              asA(
                  () ->
                      sql.fetchOne(
                              "select relforcerowsecurity from pg_class where relname=?", table)
                          .get(0, Boolean.class)))
          .as("%s 必须 FORCE ROW LEVEL SECURITY", table)
          .isTrue();
    }
  }

  @Test
  @DisplayName("端点：401 无凭据、403 权限不足、400 缺字段与格式、404 不存在、阈值可 PUT")
  void 端点_错误码() throws Exception {
    http.perform(get("/warehouse/transit-trackings")).andExpect(status().isUnauthorized());
    http.perform(get("/warehouse/transit-trackings").header("Authorization", "Bearer nope"))
        .andExpect(status().isUnauthorized());
    http.perform(get("/warehouse/transit-trackings/capabilities"))
        .andExpect(status().isUnauthorized());

    // 403：只有读权限却要建跟踪
    http.perform(
            post("/warehouse/transit-trackings")
                .header("Authorization", "Bearer read-only")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());

    // 400：缺 shipmentId
    http.perform(
            post("/warehouse/transit-trackings")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isBadRequest());

    // 404：详情不存在
    http.perform(
            get("/warehouse/transit-trackings/" + UUID.randomUUID())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());

    // 阈值：GET 补出默认行，PUT 改口径
    var thresholds =
        http.perform(
                get("/warehouse/transit-trackings/thresholds")
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn();
    var id =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(thresholds.getResponse().getContentAsString())
            .get(0)
            .get("id")
            .asText();
    http.perform(
            put("/warehouse/transit-trackings/thresholds/" + id)
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"watchLeadDays\":10,\"warningLeadDays\":5,\"criticalLeadDays\":1}"))
        .andExpect(status().isOk());

    // 400：三级倒挂
    http.perform(
            put("/warehouse/transit-trackings/thresholds/" + id)
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"watchLeadDays\":2,\"warningLeadDays\":9,\"criticalLeadDays\":0}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("端点串通：建跟踪 → 登记货代位置 → 看板 → 预警 → 确认 → 关闭，全程经 HTTP")
  void 端点_串通() throws Exception {
    var shipmentId = dispatched(a, ETA_WATCH, "100");
    // 位置必须带发生时间：服务层与 DB 触发器 guard_first_mile_tracking_stage 同一条纪律，
    // 「什么时候的事」问不出来的那条位置不许入库。
    var created =
        http.perform(
                post("/warehouse/transit-trackings")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType("application/json")
                    .content(
                        "{\"shipmentId\":\""
                            + shipmentId
                            + "\",\"currentLocation\":\"上海洋山港\",\"locationOccurredAt\":\"2026-10-08T09:00:00+08:00\"}"))
            .andExpect(status().isOk())
            .andReturn();
    var id =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(created.getResponse().getContentAsString())
            .get("id")
            .asText();

    http.perform(
            post("/warehouse/transit-trackings/" + id + "/events")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"kind\":\"LOCATION\",\"sourceSystem\":\"FORWARDER\",\"referenceId\":\"FF-1\","
                        + "\"locationText\":\"Singapore\",\"locationOccurredAt\":\"2026-10-20T14:05:00-07:00\"}"))
        .andExpect(status().isOk());

    // 再评估一次：上一条 LOCATION 事件末尾已自动重算过一次，
    // 所以这里返回 0（本次新落库条数，幂等刷新），而不是又堆一行。
    http.perform(
            post("/warehouse/transit-trackings/" + id + "/alerts/evaluate")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.raised").value(0));

    var board =
        http.perform(
                get("/warehouse/transit-trackings?delayedOnly=true")
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn();
    var boardJson =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(board.getResponse().getContentAsString());
    assertThat(boardJson.get("total").asLong()).isEqualTo(1L);
    assertThat(boardJson.get("rows").get(0).get("delayLevel").asText()).isEqualTo("WATCH");
    assertThat(boardJson.get("rows").get(0).get("currentLocation").asText()).isEqualTo("Singapore");
    assertThat(boardJson.get("watchCount").asLong()).isEqualTo(1L);

    var alertId =
        asA(
                () ->
                    sql.fetchOne(
                            "select id from first_mile_delay_alert where tracking_id=?::uuid order by raised_at desc",
                            id)
                        .get(0, UUID.class))
            .toString();
    http.perform(
            post("/warehouse/transit-trackings/alerts/" + alertId + "/acknowledge")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"note\":\"已联系货代\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));

    http.perform(
            post("/warehouse/transit-trackings/" + id + "/close")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"reason\":\"转由、目的仓自行跟踪\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CLOSED"));
  }

  // ================================================================ 租户隔离三断言

  @Test
  @DisplayName("断言一：两租户互不可见（列表为空、详情 404、写动作全被拒，且 B 建得出自己的）")
  void 断言一_两租户互不可见() {
    var mineA = createTracking(a, dispatched(a, ETA_ONT, "100"));

    // B 租户列表为空
    assertThat(asB(() -> tracking.page(query(null, null, null, null, 0, 50)).total())).isZero();
    // B 租户详情 404
    assertThatThrownBy(() -> asB(() -> tracking.detail(mineA.id())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或无权查看");
    // B 租户登记节点 / 收货确认 / 关闭全被拒
    assertThatThrownBy(
            () ->
                asBWrite(
                    () ->
                        tracking.recordEvent(
                            mineA.id(),
                            user,
                            ev().kind("NOTE").detail("偷看").occurredAt(eta(0)).build())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(
            () -> asBWrite(() -> tracking.confirmReceipt(mineA.id(), user, null)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> asBWrite(() -> tracking.close(mineA.id(), user, null)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> asBWrite(() -> tracking.acknowledge(UUID.randomUUID(), user, null)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> asBWrite(() -> tracking.updateThreshold(UUID.randomUUID(), null)))
        .isInstanceOf(ResponseStatusException.class);
    // B 租户**能**用自己那份夹具建出跟踪（不是「B 因缺来源而建不出来」）
    var mineB = asBWrite(() -> tracking.create(
        user,
        new TrackingCreateRequest(
            dispatchedAs(b, ETA_ONT, "100", true),
            "上海洋山港", eta(0), null, null, null, null)));
    assertThat(asB(() -> tracking.page(query(null, null, null, null, 0, 50)).rows()))
        .extracting(TrackingRow::id)
        .containsExactly(mineB.id());
    // B 的阈值表里只有自己那一份默认行
    assertThat(asB(() -> tracking.thresholds())).hasSize(1);
    assertThat(asB(() -> tracking.detail(mineB.id()).tracking().shipmentId())).isNotNull();
    assertThat(mineB.id()).isNotEqualTo(mineA.id());
  }

  @Test
  @DisplayName("断言二：伪造 X-Tenant-Id 头无效（身份是 B 却指向 A 只能看到 B 自己的）")
  void 断言二_伪造租户头无效() throws Exception {
    createTracking(a, dispatched(a, ETA_ONT, "100"));
    http.perform(
            get("/warehouse/transit-trackings")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
    // 反向：身份是 A 伪造 B 的头也只能看到 A 自己的
    http.perform(
            get("/warehouse/transit-trackings")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));
    // 伪造头不能让 B 读到 A 的详情
    var mineA = asA(() -> tracking.page(query(null, null, null, null, 0, 50)).rows().get(0));
    http.perform(
            get("/warehouse/transit-trackings/" + mineA.id())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());
    // 伪造头也不能让 B 读到 A 的阈值行：B 只会拿到**自己那份**默认行（惰性 bootstrap），
    // 判据是行 id 与 A 的不同 —— 断言「长度为 0」反而会把 bootstrap 判成 bug。
    var aThresholdId =
        asA(() -> tracking.thresholds()).stream()
            .filter(t -> t.transportMode().equals("*") && t.countryCode().equals("*"))
            .findFirst()
            .orElseThrow()
            .id();
    var bSeen =
        http.perform(
                get("/warehouse/transit-trackings/thresholds")
                    .header("Authorization", "Bearer tenant-b")
                    .header("X-Tenant-Id", a.toString()))
            .andExpect(status().isOk())
            .andReturn();
    var bIds =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(bSeen.getResponse().getContentAsString())
            .findValuesAsText("id");
    assertThat(bIds).hasSize(1);
    assertThat(bIds).doesNotContain(aThresholdId.toString());
    // 反向：身份是 A 伪造 B 的头，也拿不到 B 那份行。
    var aIds =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(
                http.perform(
                        get("/warehouse/transit-trackings/thresholds")
                            .header("Authorization", "Bearer tenant-a")
                            .header("X-Tenant-Id", b.toString()))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .findValuesAsText("id");
    assertThat(aIds).doesNotContain(bIds.get(0));
  }

  @Test
  @DisplayName("断言三：连接池归还后不串租户（池大小为 1，交替读写两租户 20 轮）")
  void 断言三_连接池归还不串租户() {
    var mineA = createTracking(a, dispatched(a, ETA_ONT, "100"));
    var mineB =
        asBWrite(
            () ->
                tracking.create(
                    user,
                    new TrackingCreateRequest(
                        dispatchedAs(b, ETA_ONT, "100", true),
                        "上海洋山港", eta(0), null, null, null, null)));
    for (int i = 0; i < 20; i++) {
      assertThat(asA(() -> tracking.page(query(null, null, null, null, 0, 50)).rows()))
          .extracting(TrackingRow::id)
          .containsExactly(mineA.id());
      assertThat(asB(() -> tracking.page(query(null, null, null, null, 0, 50)).rows()))
          .extracting(TrackingRow::id)
          .containsExactly(mineB.id());
      // 归还后再写：A 的事件不能落到 B 的跟踪上
      if (i == 0) {
        asAWrite(
            () ->
                tracking.recordEvent(
                    mineA.id(),
                    user,
                    ev().kind("NOTE").detail("A 的备注").occurredAt(eta(0)).build()));
      }
    }
    assertThat(asA(() -> tracking.detail(mineA.id()).events()).size()).isGreaterThanOrEqualTo(2);
    // B 的事件流里一条 A 的都没有
    assertThat(asB(() -> tracking.detail(mineB.id()).events()).stream()
            .anyMatch(e -> e.detail() != null && e.detail().contains("A 的备注")))
        .isFalse();
    // 事件行也确实按 tenant_id 分开落库
    assertThat(
            asA(() -> sql.fetch("select distinct tenant_id from first_mile_tracking_event").size()))
        .isEqualTo(1);
  }

  /**
   * 节点事件请求的可变构造器。
   *
   * <p>{@link TrackingEventRequest} 有 12 个组件，直接写 record 构造器每次都要数参数 ——
   * 填错顺序会静默把值写进别的列。这里把默认值集中在一处，测试里只写真正要变的那一两个字段。
   */
  private static final class Ev {
    String kind;
    String sourceSystem = "MANUAL";
    String referenceId;
    String locationText;
    OffsetDateTime locationOccurredAt;
    OffsetDateTime etaAfter;
    String reason;
    String customsStatus;
    String customsHoldReason;
    String toStage;
    String detail;
    OffsetDateTime occurredAt;

    Ev kind(String v) {
      this.kind = v;
      return this;
    }

    Ev source(String system, String ref) {
      this.sourceSystem = system;
      this.referenceId = ref;
      return this;
    }

    Ev location(String text, OffsetDateTime at) {
      this.locationText = text;
      this.locationOccurredAt = at;
      return this;
    }

    Ev eta(OffsetDateTime after, String why) {
      this.etaAfter = after;
      this.reason = why;
      return this;
    }

    Ev customs(String status, String holdReason) {
      this.customsStatus = status;
      this.customsHoldReason = holdReason;
      return this;
    }

    Ev toStage(String v) {
      this.toStage = v;
      return this;
    }

    Ev detail(String v) {
      this.detail = v;
      return this;
    }

    Ev occurredAt(OffsetDateTime v) {
      this.occurredAt = v;
      return this;
    }

    TrackingEventRequest build() {
      return new TrackingEventRequest(
          kind, sourceSystem, referenceId, locationText, locationOccurredAt, etaAfter, reason,
          customsStatus, customsHoldReason, toStage, detail, occurredAt);
    }
  }

  static Ev ev() {
    return new Ev();
  }

  // ================================================================ 助手

  static TrackingQuery query(
      List<String> status,
      List<String> stage,
      List<String> customs,
      List<String> level,
      int page,
      int size) {
    return new TrackingQuery(
        status, stage, customs, level, null, null, null, null, null, null, null, page, size);
  }

  /** 该发运单对应的跟踪记录 id（按发运单查，跟踪是一发运单一条）。 */
  UUID idOfShipment(UUID shipmentId) {
    return asA(
        () ->
            sql.fetchOne(
                    "select id from first_mile_tracking where tenant_id=? and shipment_id=?",
                    a,
                    shipmentId)
                .get(0, UUID.class));
  }

  static String levelOf(TransitTrackPage page, UUID shipmentId) {
    return page.rows().stream()
        .filter(r -> r.shipmentId().equals(shipmentId))
        .findFirst()
        .orElseThrow()
        .delayLevel();
  }
}