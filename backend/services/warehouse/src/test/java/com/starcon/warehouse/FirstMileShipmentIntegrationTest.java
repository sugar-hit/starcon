package com.starcon.warehouse;

import static com.starcon.warehouse.FirstMileShipmentModels.*;
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
import org.jooq.Record;
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
 * 10.4.1.1 头程发运单：货权与成本节点（Incoterm × 风险承担方）入库进契约、
 * **发出不扣在手**、发货分录只经 7.5.1.2 登记、同一批货不能发两次、已发出是终态，
 * 以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>夹具写法与 {@link GoodsReceiptIntegrationTest} / {@link InventoryInTransitIntegrationTest}
 * 一致（同样的 Testcontainers、同样的 fake identity server、同样的按租户派生 UUID、
 * 同样的 TRUNCATE 清场），派生 id 的前缀不同，避免三条车道的夹具互相污染。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirstMileShipmentIntegrationTest {
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

  static final UUID leA = idFor(a, 1);
  static final UUID skuA = idFor(a, 2);
  static final UUID whA = idFor(a, 4);
  static final UUID whUs = idFor(a, 5);
  static final UUID qSellable = idFor(a, 6);
  static final UUID skuA2 = idFor(a, 3);
  static final UUID lotA1 = idFor(a, 10);
  static final UUID receiptA = idFor(a, 20);
  static final UUID receiptEventLineA = idFor(a, 23);
  static final UUID balanceA = idFor(a, 30);

  static final OffsetDateTime ETA =
      OffsetDateTime.of(2026, 11, 20, 9, 45, 0, 0, ZoneOffset.ofHours(8));

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-firstmile-test-" + tenant + "-" + ordinal).getBytes());
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
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired FirstMileShipmentService service;
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
    // TRUNCATE 不触发行级触发器（append-only 只挡 UPDATE/DELETE），以超级用户连接执行可绕过
    // FORCE RLS —— 只用于测试夹具重建，不给应用角色开这个口子。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate first_mile_shipment_event, first_mile_shipment_leg_receipt,"
              + " first_mile_shipment_line, first_mile_shipment,"
              + " goods_receipt_discrepancy, goods_receipt_event_serial,"
              + " goods_receipt_event_line, goods_receipt_event, goods_receipt_line,"
              + " goods_receipt, in_transit_event, inventory_transfer,"
              + " stock_bucket, warehouse_location, inventory_lot, inventory_entry,"
              + " inventory_transaction, inventory_balance, inventory_quality_status, warehouse,"
              + " sku_ref, legal_entity_ref restart identity cascade");
    }
    // **每个租户各一套完整夹具**：法人、两个 SKU、国内仓、海外仓、质量状态，
    // 以及 8.2.1.1 的收货单 + 逐次实收行（头程发运的货物来源）与一条在手余额。
    // B 租户也必须有自己那份 —— 否则「B 也能建单」会退化成「B 因缺来源而建不出来」，
    // 证明不了 RLS，只证明得了外键。
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
                idFor(tenant, 2), tenant, "SKU-1", "商品一");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 3), tenant, "SKU-2", "商品二");
            // 国内起运仓（CN）与海外目的仓（US）：头程的「目的仓」是本租户 warehouse
            // 表里的海外仓行，不是第三方海外仓服务商账户（10.4.2.1 未交付）。
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone)"
                    + " values(?,?,?,?,?,?)",
                idFor(tenant, 4), tenant, "WH-SH", "上海仓", "CN", "Asia/Shanghai");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone)"
                    + " values(?,?,?,?,?,?)",
                idFor(tenant, 5), tenant, "WH-LA", "洛杉矶海外仓", "US", "America/Los_Angeles");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role)"
                    + " values(?,?,?,?,?)",
                idFor(tenant, 6), tenant, "SELLABLE", "可售", "SELLABLE");
            seedReceipt(tenant);
            return null;
          });
    }
  }

  /** 按租户派生 8.2.1.1 收货单 + 逐次实收行（实收 100 / 拒收 0）+ 一条在手余额。 */
  private void seedReceipt(UUID tenant) {
    sql.execute(
        "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,pickable)"
            + " values(?,?,?,?,?,'STORAGE',true)",
        idFor(tenant, 8), tenant, idFor(tenant, 4), "SH-01", "库位 SH-01");
    sql.execute(
        "insert into inventory_lot(id,tenant_id,sku_id,lot_code,expires_on) values(?,?,?,?,?)",
        idFor(tenant, 10), tenant, idFor(tenant, 2), "LOT-1", LocalDate.of(2027, 3, 1));
    // status='RECEIVED' 必须与「有实收事件」自洽（V70 的延迟约束触发器）
    sql.execute(
        "insert into goods_receipt(id,tenant_id,receipt_code,source_type,source_ref_code,"
            + "warehouse_id,legal_entity_id,status,created_by)"
            + " values(?,?,?,'TRANSFER','TO-1',?,?,'RECEIVED',?)",
        idFor(tenant, 20), tenant, "GR-20261001-000001", idFor(tenant, 4), idFor(tenant, 1), user);
    sql.execute(
        "insert into goods_receipt_line(id,tenant_id,receipt_id,sku_id,quality_status_id,"
            + "expected_quantity) values(?,?,?,?,?,?)",
        idFor(tenant, 21), tenant, idFor(tenant, 20), idFor(tenant, 2), idFor(tenant, 6),
        new BigDecimal("100"));
    sql.execute(
        "insert into goods_receipt_event(id,tenant_id,receipt_id,received_at,location_id,"
            + "waybill_no,actor_id) values(?,?,?,?::timestamptz,?,?,?)",
        idFor(tenant, 22), tenant, idFor(tenant, 20),
        OffsetDateTime.of(2026, 10, 1, 9, 30, 0, 0, ZoneOffset.ofHours(8)),
        idFor(tenant, 8), "WB-1", user);
    sql.execute(
        "insert into goods_receipt_event_line(id,tenant_id,event_id,line_id,received_quantity,"
            + "rejected_quantity,lot_id,expiry_date) values(?,?,?,?,?,0,?,?)",
        idFor(tenant, 23), tenant, idFor(tenant, 22), idFor(tenant, 21), new BigDecimal("100"),
        idFor(tenant, 10), LocalDate.of(2027, 3, 1));
    // 在手余额：用来断言「发出**不扣在手**」
    sql.execute(
        "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
            + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,0)",
        idFor(tenant, 30), tenant, idFor(tenant, 2), idFor(tenant, 4), idFor(tenant, 1),
        idFor(tenant, 6), new BigDecimal("40"));
    sql.execute(
        "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,location_id,"
            + "lot_id,quality_status_id,balance_id,on_hand,reserved,last_movement_at)"
            + " values(?,?,?,?,?,?,null,?,?,?,0,now())",
        idFor(tenant, 31), tenant, idFor(tenant, 1), idFor(tenant, 2), idFor(tenant, 4),
        idFor(tenant, 8), idFor(tenant, 6), idFor(tenant, 30), new BigDecimal("40"));
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  // ================================================================ 请求构造器

  /**
   * 建单请求的可变构造器。
   *
   * <p>{@link FirstMileCreateRequest} 有 24 个组件，直接写 record 构造器每次都要数参数 ——
   * 数错会编译失败（还好），数「对」但顺序错会静默填错列（那才可怕）。用可变字段 + build()
   * 把默认值集中在一处，测试里只写真正要变的那一两个字段。
   */
  private static final class Req {
    String sourceType = "GOODS_RECEIPT";
    UUID sourceRefId;
    String sourceRefCode = "GR-20261001-000001";
    UUID legalEntityId;
    UUID originWarehouseId;
    UUID destinationWarehouseId;
    String destinationCountryCode = "US";
    String destinationName = "洛杉矶海外仓";
    String transportMode = "SEA";
    String carrierName = "中远海运";
    String bookingNo = "COSCO-1234";
    String vesselOrFlightNo = "COSCO SHIPPING ARIES";
    String incoterm = "FOB";
    String riskBearer = "SELLER";
    String currency = "USD";
    BigDecimal freightCost = new BigDecimal("2200.0000");
    BigDecimal insuranceCost = new BigDecimal("150.0000");
    BigDecimal dutyEstimate = new BigDecimal("380.0000");
    BigDecimal handlingCost = new BigDecimal("260.0000");
    String customsBroker = "某某报关行";
    OffsetDateTime departAt = ETA.minusDays(30);
    OffsetDateTime expectedArriveAt = ETA;
    String notes = "首批头程";
    List<FirstMileLineRequest> lines = List.of();

    Req lines(List<FirstMileLineRequest> value) {
      this.lines = value;
      return this;
    }

    FirstMileCreateRequest build() {
      return new FirstMileCreateRequest(
          sourceType, sourceRefId, sourceRefCode, legalEntityId, originWarehouseId,
          destinationWarehouseId, destinationCountryCode, destinationName, transportMode,
          carrierName, bookingNo, vesselOrFlightNo, incoterm, riskBearer, currency, freightCost,
          insuranceCost, dutyEstimate, handlingCost, customsBroker, departAt, expectedArriveAt,
          notes, lines);
    }
  }

  /** 改单请求的可变构造器。理由同 {@link Req}。 */
  private static final class Upd {
    Long expectedVersion = 0L;
    String sourceRefCode = "GR-20261001-000001";
    UUID destinationWarehouseId;
    String destinationCountryCode = "US";
    String destinationName = "洛杉矶海外仓";
    String transportMode = "AIR";
    String carrierName = "联邦快递";
    String bookingNo = "FX-1";
    String vesselOrFlightNo = "FX-003";
    String incoterm = "CIF";
    String riskBearer = "SELLER";
    String currency = "USD";
    BigDecimal freightCost = new BigDecimal("300.0000");
    BigDecimal insuranceCost = BigDecimal.ZERO;
    BigDecimal dutyEstimate = BigDecimal.ZERO;
    BigDecimal handlingCost = BigDecimal.ZERO;
    String customsBroker = "";
    OffsetDateTime departAt = null;
    OffsetDateTime expectedArriveAt = ETA;
    String notes = "改成空运";
    List<FirstMileLineRequest> lines = List.of();

    Upd expectedVersion(Long value) {
      this.expectedVersion = value;
      return this;
    }

    FirstMileUpdateRequest build() {
      return new FirstMileUpdateRequest(
          expectedVersion, sourceRefCode, destinationWarehouseId, destinationCountryCode,
          destinationName, transportMode, carrierName, bookingNo, vesselOrFlightNo, incoterm,
          riskBearer, currency, freightCost, insuranceCost, dutyEstimate, handlingCost,
          customsBroker, departAt, expectedArriveAt, notes, lines);
    }
  }

  private Req req(UUID tenant) {
    var r = new Req();
    r.sourceRefId = idFor(tenant, 20);
    r.legalEntityId = idFor(tenant, 1);
    r.originWarehouseId = idFor(tenant, 4);
    r.destinationWarehouseId = idFor(tenant, 5);
    r.lines = List.of(lineFor(tenant, "100"));
    return r;
  }

  private Upd upd(UUID tenant) {
    var u = new Upd();
    u.destinationWarehouseId = idFor(tenant, 5);
    u.lines = List.of(lineFor(tenant, "60"));
    return u;
  }

  private FirstMileLineRequest lineFor(UUID tenant, String qty) {
    return line(
        idFor(tenant, 2), idFor(tenant, 6), idFor(tenant, 23), idFor(tenant, 10), qty,
        "棉制男士衬衫");
  }

  /** 一条申报齐备的明细行（申报品名 / HS / 货值 / 毛净重 / 箱数都填好）。 */
  private FirstMileLineRequest line(
      UUID sku, UUID quality, UUID sourceLine, UUID lot, String qty, String itemName) {
    return new FirstMileLineRequest(
        sku, quality, sourceLine, lot, new BigDecimal(qty), itemName, "6205200090", "ACME", "CN",
        new BigDecimal("12.5000"),
        new BigDecimal(qty).multiply(new BigDecimal("12.5000")),
        new BigDecimal("180.0000"),
        new BigDecimal("160.0000"),
        new BigDecimal("1.250000"),
        12);
  }

  // ================================================================ 夹具助手

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

  FirstMileSaved createShipment(String qty) {
    return asAWrite(() -> service.create(a, req(a).lines(List.of(lineFor(a, qty))).build()));
  }

  FirstMileSaved confirmShipment(UUID id, long version) {
    return asAWrite(
        () ->
            service.confirm(
                id, new FirstMileConfirmRequest(version, ETA.minusDays(30), ETA, "已订舱")));
  }

  FirstMileSaved dispatchShipment(UUID id, long version) {
    return asAWrite(
        () -> service.dispatch(a, id, new FirstMileDispatchRequest(version, null, "已装船", List.of())));
  }

  UUID lineIdOf(UUID shipmentId) {
    return asA(
        () ->
            sql.fetchOne("select id from first_mile_shipment_line where shipment_id=?", shipmentId)
                .get(0, UUID.class));
  }

  void dispatchLine(UUID shipmentId, long version, String qty) {
    asAWrite(
        () ->
            service.dispatch(
                a,
                shipmentId,
                new FirstMileDispatchRequest(
                    version,
                    null,
                    null,
                    List.of(
                        new FirstMileDispatchLineRequest(
                            lineIdOf(shipmentId), new BigDecimal(qty))))));
  }

  /**
   * 绕过服务层直接写一条在途腿回执。
   *
   * <p>只用于 DB 触发器测试：{@code first_mile_shipment_leg_receipt} 对
   * {@code inventory_transfer} 是**逻辑引用不建外键**（那张表的写入所有者是 7.5.1.2），
   * 所以这里可以造一条不存在的在途腿 id —— 这正是「本模块只调 7.5.1.2 的端点、
   * 自己只留回执」这条边界在测试里的可核对形态。
   */
  void rawLeg(UUID shipmentId, UUID lineId, String qty) {
    sql.execute(
        "insert into first_mile_shipment_leg_receipt(id,tenant_id,shipment_id,line_id,"
            + "transit_leg_id,source_system,source_reference,sku_id,quantity,shipped_at,"
            + "expected_arrive_at,registered_at,registered_by)"
            + " values(?,?,?,?,?,'FIRST_MILE',?,?,?,?::timestamptz,?::timestamptz,"
            + "?::timestamptz,?)",
        UUID.randomUUID(), a, shipmentId, lineId, UUID.randomUUID(),
        "RAW-" + UUID.randomUUID(), skuA, new BigDecimal(qty), ETA.minusDays(30), ETA,
        OffsetDateTime.now(), user);
  }

  BigDecimal onHand() {
    return asA(
        () -> sql.fetchOne("select coalesce(sum(on_hand),0) from inventory_balance")
            .get(0, BigDecimal.class));
  }

  long transactionCount() {
    return asA(() -> sql.fetchOne("select count(*) from inventory_transaction").get(0, Long.class));
  }

  long firstMileLegCount() {
    return asA(
        () ->
            sql.fetchOne(
                    "select count(*) from inventory_transfer where source_system='FIRST_MILE'")
                .get(0, Long.class));
  }

  Record transitLegOf(String sourceReference) {
    return asA(
        () ->
            sql.fetchOne(
                "select * from inventory_transfer where source_system='FIRST_MILE'"
                    + " and source_reference=?",
                sourceReference));
  }

  FirstMileQuery query(Integer page, Integer size) {
    return new FirstMileQuery(null, null, null, null, null, null, null, null, null, page, size);
  }

  // ================================================================ 能力

  @Test
  @DisplayName("能力端点：收货来源真通、供应商来源与五个下游显式不可用并给出归属模块")
  void 能力_未交付依赖显式声明() throws Exception {
    http.perform(get("/warehouse/first-mile-shipments/capabilities")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sources[?(@.value=='GOODS_RECEIPT')].available").value(true))
        .andExpect(jsonPath("$.sources[?(@.value=='GOODS_RECEIPT')].ownerModule").value("8.2.1.1"))
        .andExpect(
            jsonPath("$.sources[?(@.value=='GOODS_RECEIPT')].href")
                .value("/warehouse/goods-receipts"))
        .andExpect(jsonPath("$.sources[?(@.value=='SUPPLIER_PURCHASE')].available").value(false))
        .andExpect(jsonPath("$.sources[?(@.value=='SUPPLIER_PURCHASE')].ownerModule").value("6.3.2.2"))
        .andExpect(
            jsonPath("$.sources[?(@.value=='SUPPLIER_PURCHASE')].href").value("/purchase/orders"))
        .andExpect(jsonPath("$.sources[?(@.value=='SUPPLIER_PURCHASE')].note").isNotEmpty())
        // 下游五个维度：报关 / HS 字典 / 海外仓连接 / 在途跟踪 / 落地成本，全部未交付
        .andExpect(jsonPath("$.dependencies.length()").value(5))
        .andExpect(jsonPath("$.dependencies[?(@.available==true)]").isEmpty())
        .andExpect(jsonPath("$.dependencies[0].ownerModule").isNotEmpty())
        .andExpect(jsonPath("$.dependencies[0].href").isNotEmpty())
        .andExpect(jsonPath("$.transitRegistrationNote").isNotEmpty())
        .andExpect(jsonPath("$.titleTransferNote").isNotEmpty())
        .andExpect(jsonPath("$.declarationNote").isNotEmpty())
        .andExpect(jsonPath("$.costNote").isNotEmpty());
  }

  @Test
  @DisplayName("能力端点：11 个贸易术语各带风险转移点与允许的风险承担方；状态机 16 条转换只 5 条允许")
  void 能力_贸易术语与状态机() {
    var caps = asA(() -> service.capabilities());
    assertThat(caps.incoterms()).hasSize(11);
    var exw = caps.incoterms().stream().filter(i -> i.value().equals("EXW")).findFirst().orElseThrow();
    assertThat(exw.allowedRiskBearers()).containsExactly("BUYER");
    var ddp = caps.incoterms().stream().filter(i -> i.value().equals("DDP")).findFirst().orElseThrow();
    assertThat(ddp.allowedRiskBearers()).containsExactly("SELLER");
    var fob = caps.incoterms().stream().filter(i -> i.value().equals("FOB")).findFirst().orElseThrow();
    assertThat(fob.allowedRiskBearers()).hasSize(3);
    assertThat(fob.riskPoint()).isNotBlank();
    // 16 条全枚举；允许 6 条：DRAFT→DRAFT/CONFIRMED/CANCELLED + CONFIRMED→CONFIRMED/DISPATCHED/CANCELLED
    assertThat(caps.transitions()).hasSize(16);
    assertThat(caps.transitions().stream().filter(ShipmentTransition::allowed).count()).isEqualTo(6);
    // 自环标注为改单（前端据此决定「编辑」按钮）
    assertThat(
            caps.transitions().stream()
                .filter(t -> t.allowed() && t.from().equals(t.to()))
                .map(ShipmentTransition::note)
                .toList())
        .allMatch(n -> n.contains("改单"));
    // 两个终态一个出口都没有
    assertThat(
            caps.transitions().stream()
                .filter(t -> t.from().equals("DISPATCHED") || t.from().equals("CANCELLED"))
                .filter(ShipmentTransition::allowed)
                .count())
        .isZero();
    // 四种运输方式带真实单数（本车道此刻为 0，不是写死的假词表）
    assertThat(caps.transportModes()).hasSize(4);
    assertThat(caps.transportModes().stream().map(TransportModeOption::value))
        .containsExactly("SEA", "AIR", "RAIL", "EXPRESS");
    assertThat(caps.riskBearers()).hasSize(3);
    assertThat(caps.sources()).hasSize(2);
  }

  // ================================================================ 建单

  @Test
  @DisplayName("建单：货权与成本节点真的入库进契约，单号与行号由 DB 生成")
  void 建单_货权与成本节点入库() {
    var saved = createShipment("100");
    // FM-YYYYMMDD-NNNNNN = 3 + 8 + 1 + 6 = 18
    assertThat(saved.shipmentCode()).startsWith("FM-").hasSize(18);
    assertThat(saved.status()).isEqualTo("DRAFT");
    assertThat(saved.incoterm()).isEqualTo("FOB");
    assertThat(saved.riskBearer()).isEqualTo("SELLER");
    // 成本预估：2200 + 150 + 380 + 260 = 2990，全 NUMERIC 无浮点误差
    assertThat(saved.totalCost()).isEqualByComparingTo("2990.0000");
    assertThat(saved.plannedQuantity()).isEqualByComparingTo("100.000000");
    assertThat(saved.shippedQuantity()).isEqualByComparingTo("0");

    var row = asA(() -> service.page(query(0, 50)).rows().get(0));
    assertThat(row.incoterm()).isEqualTo("FOB");
    assertThat(row.riskBearer()).isEqualTo("SELLER");
    assertThat(row.transportModeLabel()).isEqualTo("海运");
    assertThat(row.originWarehouseName()).isEqualTo("上海仓");
    assertThat(row.destinationWarehouseName()).isEqualTo("洛杉矶海外仓");
    assertThat(row.destinationCountryCode()).isEqualTo("US");
    assertThat(row.declaredValue()).isEqualByComparingTo("1250.0000");

    var detail = asA(() -> service.detail(saved.id()));
    assertThat(detail.lines()).hasSize(1);
    var l = detail.lines().get(0);
    assertThat(l.lineNo()).isEqualTo(1);
    assertThat(l.declaredItemName()).isEqualTo("棉制男士衬衫");
    assertThat(l.hsCode()).isEqualTo("6205200090");
    assertThat(l.originCountryCode()).isEqualTo("CN");
    assertThat(l.lotCode()).isEqualTo("LOT-1");
    // 批次是**逐次实收**上的属性：来源单号与到货时刻必须可追
    assertThat(l.sourceReceiptCode()).isEqualTo("GR-20261001-000001");
    assertThat(l.sourceWaybillNo()).isEqualTo("WB-1");
    assertThat(l.sourceReceivedAt()).isNotNull();
    // 效期用 date 存：读回不漂成 02-28
    assertThat(l.lotExpiryDate()).isEqualTo(LocalDate.of(2027, 3, 1));
    assertThat(detail.events()).hasSize(1);
    assertThat(detail.events().get(0).kind()).isEqualTo("CREATED");
    assertThat(detail.transitLegs()).isEmpty();
  }

  @Test
  @DisplayName("建单：供应商来源（6 采购未交付）直接拒绝并说明归属模块，且一条都没落库")
  void 建单_供应商来源被拒() {
    var r = req(a);
    r.sourceType = "SUPPLIER_PURCHASE";
    r.sourceRefId = null;
    r.sourceRefCode = "PO-2026-001";
    assertThatThrownBy(() -> asAWrite(() -> service.create(a, r.build())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("6.3.2.2")
        .hasMessageContaining("尚未交付");
    assertThat(
            asA(() -> sql.fetchOne("select count(*) from first_mile_shipment").get(0, Long.class)))
        .isEqualTo(0L);
  }

  @Test
  @DisplayName("建单：Incoterm 与风险承担方不自洽被拒（EXW 只能配 BUYER、DDP 只能配 SELLER）")
  void 建单_风险承担方自洽() {
    for (var bad :
        List.of(
            new String[] {"EXW", "SELLER"},
            new String[] {"EXW", "CARRIER"},
            new String[] {"DDP", "BUYER"},
            new String[] {"DDP", "CARRIER"})) {
      var r = req(a);
      r.incoterm = bad[0];
      r.riskBearer = bad[1];
      assertThatThrownBy(() -> asAWrite(() -> service.create(a, r.build())))
          .as("Incoterm=%s 风险承担方=%s 必须被拒", bad[0], bad[1])
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (var ok : List.of(new String[] {"EXW", "BUYER"}, new String[] {"DDP", "SELLER"})) {
      var r = req(a);
      r.incoterm = ok[0];
      r.riskBearer = ok[1];
      assertThat(asAWrite(() -> service.create(a, r.build())).incoterm()).isEqualTo(ok[0]);
    }
  }

  @Test
  @DisplayName("建单：必须指明实收批次、计划量不超实收、同一批次不许两行、批次与 SKU 必须对得上")
  void 建单_来源批次校验() {
    // 不指批次
    var noBatch = req(a);
    noBatch.lines =
        List.of(
            new FirstMileLineRequest(
                skuA, qSellable, null, lotA1, new BigDecimal("10"), "棉制衬衫", "", "", "CN",
                null, null, null, null, null, null));
    assertThatThrownBy(() -> asAWrite(() -> service.create(a, noBatch.build())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("批次");

    // 计划量超实收（实收 100）
    assertThatThrownBy(
            () -> asAWrite(() -> service.create(a, req(a).lines(List.of(lineFor(a, "101"))).build())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("超过该实收批次还能发走的量");

    // 同一批次两行
    var dup = req(a);
    dup.lines = List.of(lineFor(a, "10"), lineFor(a, "10"));
    assertThatThrownBy(() -> asAWrite(() -> service.create(a, dup.build())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("多行");

    // 批次与 SKU 对不上（用 SKU-2 指 SKU-1 的实收批次）
    var wrongSku = req(a);
    wrongSku.lines = List.of(line(skuA2, qSellable, receiptEventLineA, lotA1, "10", "棉制衬衫"));
    assertThatThrownBy(() -> asAWrite(() -> service.create(a, wrongSku.build())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不是同一个");

    // 批次与实收行不一致
    var wrongLot = req(a);
    wrongLot.lines =
        List.of(line(skuA, qSellable, receiptEventLineA, UUID.randomUUID(), "10", "棉制衬衫"));
    assertThatThrownBy(() -> asAWrite(() -> service.create(a, wrongLot.build())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不一致");

    // 申报品名必填：照搬销售标题是退单主因，不允许空
    var noName = req(a);
    noName.lines =
        List.of(
            new FirstMileLineRequest(
                skuA, qSellable, receiptEventLineA, lotA1, new BigDecimal("10"), "  ", "", "", "CN",
                null, null, null, null, null, null));
    assertThatThrownBy(() -> asAWrite(() -> service.create(a, noName.build())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("申报品名");
  }

  @Test
  @DisplayName("建单：起止仓同仓 / 仓不存在 / 目的国非两位 / ETA 早于开船都被拒；目的国自动大写")
  void 建单_基础字段校验() {
    var same = req(a);
    same.destinationWarehouseId = whA;
    assertThatThrownBy(() -> asAWrite(() -> service.create(a, same.build())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能是同一个");

    var missing = req(a);
    missing.destinationWarehouseId = UUID.randomUUID();
    assertThatThrownBy(() -> asAWrite(() -> service.create(a, missing.build())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不存在或无权访问");

    var badCountry = req(a);
    badCountry.destinationCountryCode = "USA";
    assertThatThrownBy(() -> asAWrite(() -> service.create(a, badCountry.build())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("两位国家代码");

    var backEta = req(a);
    // departAt 默认 ETA-30；ETA 早于它 = 录反
    backEta.expectedArriveAt = ETA.minusDays(40);
    assertThatThrownBy(() -> asAWrite(() -> service.create(a, backEta.build())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("早于");

    var lower = req(a);
    lower.destinationCountryCode = "us";
    assertThat(asAWrite(() -> service.create(a, lower.build())).id()).isNotNull();
    // 目的地国家统一大写落库（筛选用 upper() 比对，大小写不该影响结果）
    assertThat(
            asA(
                () ->
                    sql.fetchOne(
                            "select distinct destination_country_code from first_mile_shipment")
                        .get(0, String.class)))
        .isEqualTo("US");
  }

  @Test
  @DisplayName("建单：币种非三位字母被拒；默认 USD 如实返回（不静默换算）")
  void 建单_币种校验() {
    // 空币种 → 默认 USD 并**如实返回**（不静默换算成别的币）
    var noCurrency = req(a);
    noCurrency.currency = null;
    assertThat(asAWrite(() -> service.create(a, noCurrency.build())).id()).isNotNull();
    assertThat(asA(() -> service.page(query(0, 50)).rows().get(0).currency())).isEqualTo("USD");
    var bad = req(a);
    bad.currency = "美元";
    assertThatThrownBy(() -> asAWrite(() -> service.create(a, bad.build())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("三位大写字母");
    // 显式 CNY 正常
    var cny = req(a);
    cny.currency = "cny";
    asAWrite(() -> service.create(a, cny.build()));
    assertThat(asA(() -> service.page(query(0, 50)).rows()).stream()
            .map(FirstMileRow::currency).toList())
        .containsExactlyInAnyOrder("USD", "CNY");
  }

  // ================================================================ 改单 / 确认

  @Test
  @DisplayName("改单：乐观锁缺失或版本不符都拒绝；草稿可整份替换明细")
  void 改单_乐观锁() {
    var saved = createShipment("100");
    assertThatThrownBy(() -> asAWrite(() -> service.update(saved.id(), null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expectedVersion");
    assertThatThrownBy(
            () -> asAWrite(() -> service.update(saved.id(), upd(a).expectedVersion(99L).build())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已被他人修改");
    var updated = asAWrite(() -> service.update(saved.id(), upd(a).build()));
    assertThat(updated.version()).isEqualTo(1L);
    var detail = asA(() -> service.detail(saved.id()));
    assertThat(detail.shipment().transportMode()).isEqualTo("AIR");
    assertThat(detail.shipment().incoterm()).isEqualTo("CIF");
    assertThat(detail.lines()).hasSize(1);
    assertThat(detail.lines().get(0).plannedQuantity()).isEqualByComparingTo("60");
    // 改单也留痕
    assertThat(detail.events().stream().map(FirstMileEventRow::kind)).contains("UPDATED");
  }

  @Test
  @DisplayName("确认：申报信息不齐（货值 0 / 毛重 0 / 箱数 0）逐项被拒；正常单确认成功且不动任何账")
  void 确认_申报完整性() {
    // 申报货值 0
    var zeroValue = req(a);
    zeroValue.lines =
        List.of(
            new FirstMileLineRequest(
                skuA, qSellable, receiptEventLineA, lotA1, new BigDecimal("10"), "棉制衬衫", "",
                "", "CN", new BigDecimal("12.5"), BigDecimal.ZERO, new BigDecimal("10"),
                new BigDecimal("9"), new BigDecimal("0.1"), 1));
    var zero = asAWrite(() -> service.create(a, zeroValue.build()));
    assertThatThrownBy(() -> confirmShipment(zero.id(), 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("申报货值为 0");

    // 毛重 0
    var noWeight = req(a);
    noWeight.lines =
        List.of(
            new FirstMileLineRequest(
                skuA, qSellable, receiptEventLineA, lotA1, new BigDecimal("10"), "棉制衬衫", "",
                "", "CN", new BigDecimal("12.5"), new BigDecimal("125"), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("0.1"), 1));
    var noW = asAWrite(() -> service.create(a, noWeight.build()));
    assertThatThrownBy(() -> confirmShipment(noW.id(), 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("毛重为 0");

    // 箱数 0
    var noCarton = req(a);
    noCarton.lines =
        List.of(
            new FirstMileLineRequest(
                skuA, qSellable, receiptEventLineA, lotA1, new BigDecimal("10"), "棉制衬衫", "",
                "", "CN", new BigDecimal("12.5"), new BigDecimal("125"), new BigDecimal("10"),
                new BigDecimal("9"), new BigDecimal("0.1"), 0));
    var noC = asAWrite(() -> service.create(a, noCarton.build()));
    assertThatThrownBy(() -> confirmShipment(noC.id(), 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("箱数为 0");

    // 正常单确认成功
    var ok = createShipment("100");
    var confirmed = confirmShipment(ok.id(), 0L);
    assertThat(confirmed.status()).isEqualTo("CONFIRMED");
    // 确认不动任何账：在途腿数仍为 0
    assertThat(firstMileLegCount()).isEqualTo(0L);
    assertThat(
            asA(() -> sql.fetchOne("select count(*) from inventory_transfer").get(0, Long.class)))
        .isEqualTo(0L);
  }

  @Test
  @DisplayName("确认：缺预计到港时间被拒（它是 7.5.1.2 逾期预警的计算依据）")
  void 确认_必须给ETA() {
    var noEta = req(a);
    noEta.expectedArriveAt = null;
    noEta.departAt = null;
    var saved = asAWrite(() -> service.create(a, noEta.build()));
    // 单上与请求里都没有 ETA
    assertThatThrownBy(
            () ->
                asAWrite(
                    () ->
                        service.confirm(
                            saved.id(), new FirstMileConfirmRequest(0L, null, null, null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("预计到港");
    // 给了 departAt 但没给 ETA 同样拒绝
    assertThatThrownBy(
            () ->
                asAWrite(
                    () ->
                        service.confirm(
                            saved.id(),
                            new FirstMileConfirmRequest(0L, ETA.minusDays(30), null, null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("预计到港");
  }

  // ================================================================ 发出

  @Test
  @DisplayName("发出：发货分录真在 7.5.1.2 名下（FIRST_MILE 腿 + 幂等锚点），且**不扣在手**")
  void 发出_登记在途腿且不动在手() {
    var saved = createShipment("100");
    var confirmed = confirmShipment(saved.id(), 0L);
    BigDecimal handBefore = onHand();
    long txBefore = transactionCount();

    var dispatched = dispatchShipment(saved.id(), confirmed.version());
    assertThat(dispatched.status()).isEqualTo("DISPATCHED");
    assertThat(dispatched.shippedQuantity()).isEqualByComparingTo("100");
    assertThat(dispatched.transitLegCount()).isEqualTo(1L);
    assertThat(dispatched.dispatchedAt()).isNotNull();

    // ===== 7.5.1.2 那一侧真的多了一条在途腿 =====
    var leg = transitLegOf(saved.shipmentCode() + "-001");
    assertThat(leg).isNotNull();
    assertThat(leg.get("transit_kind", String.class)).isEqualTo("FIRST_MILE");
    assertThat(leg.get("status", String.class)).isEqualTo("IN_TRANSIT");
    assertThat(leg.get("source_system", String.class)).isEqualTo("FIRST_MILE");
    assertThat(leg.get("quantity", BigDecimal.class)).isEqualByComparingTo("100");
    assertThat(leg.get("lot_id", UUID.class)).isEqualTo(lotA1);
    assertThat(leg.get("from_warehouse_id", UUID.class)).isEqualTo(whA);
    assertThat(leg.get("to_warehouse_id", UUID.class)).isEqualTo(whUs);
    assertThat(leg.get("carrier_name", String.class)).isEqualTo("中远海运");
    assertThat(leg.get("tracking_no", String.class)).isEqualTo("COSCO-1234");
    assertThat(leg.get("expected_arrive_at", OffsetDateTime.class)).isNotNull();

    // ===== 关键口径：发出不扣在手、不写任何库存分录 =====
    assertThat(onHand()).isEqualByComparingTo(handBefore);
    assertThat(transactionCount()).isEqualTo(txBefore);
    assertThat(
            asA(
                () ->
                    sql.fetchOne(
                            "select count(*) from inventory_entry where balance_id=?", balanceA)
                        .get(0, Long.class)))
        .isEqualTo(0L);

    // 详情里在途腿与 7.5.1.2 的状态并排可读
    var detail = asA(() -> service.detail(saved.id()));
    assertThat(detail.transitLegs()).hasSize(1);
    var g = detail.transitLegs().get(0);
    assertThat(g.transitKind()).isEqualTo("FIRST_MILE");
    assertThat(g.transitStatus()).isEqualTo("IN_TRANSIT");
    assertThat(g.quantity()).isEqualByComparingTo("100");
    assertThat(g.settledQuantity()).isEqualByComparingTo("0");
    assertThat(g.outstandingQuantity()).isEqualByComparingTo("100");
    assertThat(g.sourceReference()).isEqualTo(saved.shipmentCode() + "-001");
    assertThat(detail.lines().get(0).transitLegId()).isEqualTo(leg.get("id", UUID.class));
    assertThat(detail.lines().get(0).pendingQuantity()).isEqualByComparingTo("0");
    assertThat(detail.events().stream().map(FirstMileEventRow::kind)).contains("DISPATCHED");
  }

  @Test
  @DisplayName("发出：已发出是终态，重复发出 / 改单 / 取消都被拒，且不双记在途")
  void 发出_终态保护() {
    var saved = createShipment("100");
    var confirmed = confirmShipment(saved.id(), 0L);
    var dispatched = dispatchShipment(saved.id(), confirmed.version());

    assertThatThrownBy(() -> dispatchShipment(saved.id(), dispatched.version()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已发出是终态");
    assertThatThrownBy(
            () -> asAWrite(() -> service.update(saved.id(), upd(a).expectedVersion(dispatched.version()).build())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已发出是终态");
    assertThatThrownBy(
            () ->
                asAWrite(
                    () ->
                        service.cancel(
                            saved.id(),
                            new FirstMileCancelRequest(dispatched.version(), "不发了", null))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("7.5.1.2");
    // 关键：三次失败之后在途腿仍然只有一条
    assertThat(firstMileLegCount()).isEqualTo(1L);
  }

  @Test
  @DisplayName("发出：只能从已确认发出；草稿直接发被拒且不留在途")
  void 发出_必须先确认() {
    var saved = createShipment("100");
    assertThatThrownBy(() -> dispatchShipment(saved.id(), 0L))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("只有已确认");
    assertThat(
            asA(() -> sql.fetchOne("select count(*) from inventory_transfer").get(0, Long.class)))
        .isEqualTo(0L);
  }

  @Test
  @DisplayName("发出：逐行部分发运，余量可另开单；同一批货不能发两次（累计上限）")
  void 发出_部分发运与累计上限() {
    // 行内发出量不得超过计划量（先在可发量还足的时候做这一条）
    var over = createShipment("100");
    var overC = confirmShipment(over.id(), 0L);
    assertThatThrownBy(
            () ->
                asAWrite(
                    () ->
                        service.dispatch(
                            a,
                            over.id(),
                            new FirstMileDispatchRequest(
                                overC.version(),
                                null,
                                "超发",
                                List.of(
                                    new FirstMileDispatchLineRequest(
                                        lineIdOf(over.id()), new BigDecimal("200")))))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("超过计划量");

    var first = createShipment("100");
    // 第二张单必须在第一张发出**之前**建：可发量还是 100，41 建得出来；
    // 发出 60 之后再建就只能建 ≤ 40 的单（那是建单校验，由建单_来源批次校验 覆盖）
    var second = createShipment("41");
    dispatchLine(first.id(), confirmShipment(first.id(), 0L).version(), "60");
    var d1 = asA(() -> service.detail(first.id()));
    assertThat(d1.lines().get(0).shippedQuantity()).isEqualByComparingTo("60");
    assertThat(d1.lines().get(0).pendingQuantity()).isEqualByComparingTo("40");
    assertThat(firstMileLegCount()).isEqualTo(1L);

    // 第二张单要发 41 → 累计 101 > 实收 100，发起被拒且整个事务回滚
    var c2 = confirmShipment(second.id(), 0L);
    assertThatThrownBy(() -> dispatchShipment(second.id(), c2.version()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("超过该实收批次还能发走的量");
    assertThat(firstMileLegCount()).isEqualTo(1L);
    // 回滚干净：第二单仍是已确认、发出量 0
    var second2 = asA(() -> service.detail(second.id()));
    assertThat(second2.shipment().status()).isEqualTo("CONFIRMED");
    assertThat(second2.lines().get(0).shippedQuantity()).isEqualByComparingTo("0");
    assertThat(second2.transitLegs()).isEmpty();

    // 余下的 40 正好可以再发一单
    var third = createShipment("40");
    var c3 = confirmShipment(third.id(), 0L);
    assertThat(dispatchShipment(third.id(), c3.version()).shippedQuantity())
        .isEqualByComparingTo("40");
    // 累计 = 实收 100，此时连建单都发不动了（不给留下永远发不出去的计划）
    assertThatThrownBy(() -> createShipment("1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("超过该实收批次还能发走的量");
  }

  @Test
  @DisplayName("发出：发 0 被拒（已发出是终态，这一行会永远留在计划里发不出去）")
  void 发出_不允许零发运() {
    var saved = createShipment("100");
    var c = confirmShipment(saved.id(), 0L);
    assertThatThrownBy(
            () ->
                asAWrite(
                    () ->
                        service.dispatch(
                            a,
                            saved.id(),
                            new FirstMileDispatchRequest(
                                c.version(),
                                null,
                                "不发",
                                List.of(
                                    new FirstMileDispatchLineRequest(
                                        lineIdOf(saved.id()), BigDecimal.ZERO))))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("实际发出为 0");
  }

  @Test
  @DisplayName("DB 不变量：绕过服务层改状态（不登记在途腿）与超实收发运，都被延迟约束触发器挡下")
  void DB_触发器挡住绕过的写() {
    var saved = createShipment("100");
    var confirmed = confirmShipment(saved.id(), 0L);
    // 绕过服务层直接改单头状态 + 改发出量，但不登记在途腿 → first_mile_leg 在提交时报错
    assertThatThrownBy(
            () ->
                asAWrite(
                    () -> {
                      sql.execute(
                          "update first_mile_shipment set status='DISPATCHED',"
                              + " dispatched_at=?::timestamptz where id=?",
                          OffsetDateTime.now(), saved.id());
                      sql.execute(
                          "update first_mile_shipment_line set shipped_quantity=100"
                              + " where shipment_id=?",
                          saved.id());
                      return null;
                    }))
        .hasMessageContaining("在途腿");
    // 事务回滚：单仍在已确认、发出量为 0
    var after = asA(() -> service.detail(saved.id()));
    assertThat(after.shipment().status()).isEqualTo("CONFIRMED");
    assertThat(after.lines().get(0).shippedQuantity()).isEqualByComparingTo("0");
    assertThat(confirmed.status()).isEqualTo("CONFIRMED");

    // 第二张单也指同一实收批次：两张单各发一部分，累计不得超过实收 100。
    // 每行都补一条在途腿回执，让「发货分录不可缺」那条约束不掺和 ——
    // 否则先触发的是它，报错会指错地方。
    // 顺序：先改发出量再插回执。guard_first_mile_leg_line 是**即时**触发器，
    // 它拿本行发出量与回执量对账；反过来插会对着 0 报错，永远测不到 over_ship。
    var second = asAWrite(() -> service.create(a, req(a).lines(List.of(lineFor(a, "60"))).build()));
    UUID firstLine = lineIdOf(saved.id());
    UUID secondLine = lineIdOf(second.id());
    assertThatThrownBy(
            () ->
                asAWrite(
                    () -> {
                      sql.execute(
                          "update first_mile_shipment_line set shipped_quantity=50 where id=?",
                          firstLine);
                      sql.execute(
                          "update first_mile_shipment_line set shipped_quantity=60 where id=?",
                          secondLine);
                      rawLeg(saved.id(), firstLine, "50");
                      rawLeg(second.id(), secondLine, "60");
                      return null;
                    }))
        .hasMessageContaining("同一批货不能发两次");
    // 回滚干净：两行发出量都还是 0
    assertThat(asA(() -> service.detail(saved.id())).lines().get(0).shippedQuantity())
        .isEqualByComparingTo("0");
    assertThat(asA(() -> service.detail(second.id())).lines().get(0).shippedQuantity())
        .isEqualByComparingTo("0");
  }

  @Test
  @DisplayName("DB 不变量：状态与时间戳不自洽被挡（草稿不能带发出时间；取消必须给原因）")
  void DB_状态时间戳自洽() {
    var saved = createShipment("100");
    assertThatThrownBy(
            () ->
                asAWrite(
                    () -> {
                      sql.execute(
                          "update first_mile_shipment set dispatched_at=?::timestamptz where id=?",
                          OffsetDateTime.now(), saved.id());
                      return null;
                    }))
        .hasMessageContaining("不自洽");
    assertThatThrownBy(
            () ->
                asAWrite(
                    () -> {
                      sql.execute(
                          "update first_mile_shipment set status='CANCELLED',"
                              + " cancelled_at=?::timestamptz where id=?",
                          OffsetDateTime.now(), saved.id());
                      return null;
                    }))
        .hasMessageContaining("取消必须给原因");
  }

  // ================================================================ 取消

  @Test
  @DisplayName("取消：必须给原因；取消后不再出现在「在路上」筛选里，也不发在途")
  void 取消_必须给原因() {
    var saved = createShipment("100");
    assertThatThrownBy(
            () ->
                asAWrite(
                    () -> service.cancel(saved.id(), new FirstMileCancelRequest(0L, "  ", null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("取消必须给原因");
    var cancelled =
        asAWrite(
            () ->
                service.cancel(
                    saved.id(), new FirstMileCancelRequest(0L, "船期取消，改下一班", "客户同意")));
    assertThat(cancelled.status()).isEqualTo("CANCELLED");
    assertThat(cancelled.cancelledAt()).isNotNull();
    assertThat(asA(() -> service.detail(saved.id())).shipment().cancelReason())
        .isEqualTo("船期取消，改下一班");
    assertThat(
            asA(
                () ->
                    service.page(
                            new FirstMileQuery(
                                null, null, null, null, null, null, true, null, null, 0, 50))
                        .total()))
        .isEqualTo(0L);
    assertThat(firstMileLegCount()).isEqualTo(0L);
  }

  // ================================================================ 选货助手

  @Test
  @DisplayName("选货助手：可发量 = 实收 − 拒收 − 已被其他发运单占用；发完后候选项消失")
  void 选货_可发量口径() throws Exception {
    var page = asA(() -> service.sourceCandidates(null, null, null, null, 0, 50));
    assertThat(page.candidateCount()).isEqualTo(1L);
    var c = page.rows().get(0);
    assertThat(c.receiptEventLineId()).isEqualTo(receiptEventLineA);
    assertThat(c.receiptCode()).isEqualTo("GR-20261001-000001");
    assertThat(c.waybillNo()).isEqualTo("WB-1");
    assertThat(c.skuCode()).isEqualTo("SKU-1");
    assertThat(c.qualityStatusName()).isEqualTo("可售");
    assertThat(c.lotCode()).isEqualTo("LOT-1");
    assertThat(c.lotExpiryDate()).isEqualTo(LocalDate.of(2027, 3, 1));
    assertThat(c.receivedQuantity()).isEqualByComparingTo("100");
    assertThat(c.alreadyShippedQuantity()).isEqualByComparingTo("0");
    assertThat(c.shippableQuantity()).isEqualByComparingTo("100");
    assertThat(c.warehouseName()).isEqualTo("上海仓");
    assertThat(page.totalShippableQuantity()).isEqualByComparingTo("100");

    // 发走 60 之后，可发量必须变成 40
    var saved = createShipment("100");
    dispatchLine(saved.id(), confirmShipment(saved.id(), 0L).version(), "60");
    var after = asA(() -> service.sourceCandidates(null, null, null, null, 0, 50));
    assertThat(after.rows().get(0).alreadyShippedQuantity()).isEqualByComparingTo("60");
    assertThat(after.rows().get(0).shippableQuantity()).isEqualByComparingTo("40");
    assertThat(after.totalShippableQuantity()).isEqualByComparingTo("40");

    // 收窄筛选：按收货单 / SKU / 仓 / 关键词
    assertThat(
            asA(
                () ->
                    service.sourceCandidates(receiptA, skuA, whA, "GR-20261001", 0, 50)
                        .candidateCount()))
        .isEqualTo(1L);
    assertThat(
            asA(
                () ->
                    service.sourceCandidates(receiptA, skuA2, whA, null, 0, 50).candidateCount()))
        .isEqualTo(0L);
    assertThat(
            asA(
                () ->
                    service.sourceCandidates(null, null, null, "不存在的批次", 0, 50)
                        .candidateCount()))
        .isEqualTo(0L);

    // 发完之后候选项消失（可发量 0 不该出现在「还能发」清单里）
    var third = createShipment("40");
    dispatchLine(third.id(), confirmShipment(third.id(), 0L).version(), "40");
    assertThat(asA(() -> service.sourceCandidates(null, null, null, null, 0, 50)).candidateCount())
        .isEqualTo(0L);

    // 端点同样可读
    http.perform(
            get("/warehouse/first-mile-shipments/source-candidates?skuId=" + skuA)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.candidateCount").value(0));
  }

  // ================================================================ 列表 / 汇总 / 分页

  @Test
  @DisplayName("列表：真分页两页不重叠、汇总不受分页影响、筛选与排序白名单真实生效")
  void 列表_分页汇总筛选排序() {
    for (int i = 0; i < 5; i++) {
      var saved = createShipment(String.valueOf(10 + i));
      if (i % 2 == 0) {
        confirmShipment(saved.id(), 0L);
      }
    }
    var p1 = asA(() -> service.page(query(0, 2)));
    var p2 = asA(() -> service.page(query(1, 2)));
    assertThat(p1.total()).isEqualTo(5L);
    assertThat(p1.rows()).hasSize(2);
    assertThat(p2.rows()).hasSize(2);
    assertThat(p1.rows().stream().map(FirstMileRow::id).toList())
        .doesNotContainAnyElementsOf(p2.rows().stream().map(FirstMileRow::id).toList());
    // 汇总**不受分页影响**：两页的汇总必须完全一致
    assertThat(p1.summary()).isEqualTo(p2.summary());
    assertThat(p1.summary().shipmentCount()).isEqualTo(5L);
    assertThat(p1.summary().lineCount()).isEqualTo(5L);
    // i=0,2,4 被确认 → 3 已确认 / 2 草稿
    assertThat(p1.summary().draftCount()).isEqualTo(2L);
    assertThat(p1.summary().confirmedCount()).isEqualTo(3L);
    assertThat(p1.summary().seaCount()).isEqualTo(5L);
    assertThat(p1.summary().airCount()).isEqualTo(0L);
    assertThat(p1.summary().totalCost()).isEqualByComparingTo("14950.0000");
    // 计划量 10+11+12+13+14 = 60 件 × 12.5 = 750
    assertThat(p1.summary().declaredValue()).isEqualByComparingTo("750.0000");
    assertThat(p1.summary().plannedQuantity()).isEqualByComparingTo("60.000000");
    assertThat(p1.summary().shippedQuantity()).isEqualByComparingTo("0");
    assertThat(p1.statusOptions()).hasSize(4);
    assertThat(p1.transportModes()).hasSize(4);
    // 仓库下拉必须含**海外仓**（目的仓就取自这里）
    assertThat(p1.warehouseOptions()).extracting(WarehouseOption::countryCode)
        .contains("CN", "US");

    // 筛选：状态
    var onlyConfirmed =
        asA(
            () ->
                service.page(
                    new FirstMileQuery(
                        List.of("CONFIRMED"), null, null, null, null, null, null, null, null, 0,
                        50)));
    assertThat(onlyConfirmed.total()).isEqualTo(3L);
    // 筛选：运输方式
    assertThat(
            asA(
                () ->
                    service.page(
                        new FirstMileQuery(
                            null, "SEA", null, null, null, null, null, null, null, 0, 50))
                        .total()))
        .isEqualTo(5L);
    assertThat(
            asA(
                () ->
                    service.page(
                        new FirstMileQuery(
                            null, "AIR", null, null, null, null, null, null, null, 0, 50))
                        .total()))
        .isEqualTo(0L);
    // 筛选：货权节点 incoterm
    assertThat(
            asA(
                () ->
                    service.page(
                        new FirstMileQuery(
                            null, null, "FOB", null, null, null, null, null, null, 0, 50))
                        .total()))
        .isEqualTo(5L);
    assertThat(
            asA(
                () ->
                    service.page(
                        new FirstMileQuery(
                            null, null, "CIF", null, null, null, null, null, null, 0, 50))
                        .total()))
        .isEqualTo(0L);
    // 筛选：起止仓 + 目的地国家（大小写不敏感）
    assertThat(
            asA(
                () ->
                    service.page(
                            new FirstMileQuery(
                                null, null, null, whA, whUs, "us", null, null, null, 0, 50))
                        .total()))
        .isEqualTo(5L);
    assertThat(
            asA(
                () ->
                    service.page(
                            new FirstMileQuery(
                                null, null, null, whUs, whUs, null, null, null, null, 0, 50))
                        .total()))
        .isEqualTo(0L);
    // 筛选：只看在路上（已确认 3 单）
    assertThat(
            asA(
                () ->
                    service.page(
                            new FirstMileQuery(
                                null, null, null, null, null, null, true, null, null, 0, 50))
                        .total()))
        .isEqualTo(3L);
    // 筛选：关键词真的模糊检索
    assertThat(
            asA(
                () ->
                    service.page(
                            new FirstMileQuery(
                                null, null, null, null, null, null, null, "COSCO-1234", null, 0,
                                50))
                        .total()))
        .isEqualTo(5L);
    assertThat(
            asA(
                () ->
                    service.page(
                            new FirstMileQuery(
                                null, null, null, null, null, null, null, "不存在的承运商", null, 0,
                                50))
                        .total()))
        .isEqualTo(0L);
    // 排序：白名单 5 键都真的生效
    for (var sort :
        List.of("CREATED_AT", "SHIPMENT_CODE", "EXPECTED_ARRIVE_AT", "DEPART_AT", "UPDATED_AT")) {
      assertThat(
              asA(
                      () ->
                          service.page(
                                  new FirstMileQuery(
                                      null, null, null, null, null, null, null, null, sort, 0, 50))
                              .rows()))
          .as("排序键 %s 必须可用", sort)
          .hasSize(5);
    }
    // 非法排序**静默回落到白名单默认键**，绝不当作 SQL 表达式执行
    var injected =
        asA(
            () ->
                service.page(
                        new FirstMileQuery(
                            null, null, null, null, null, null, null, null,
                            "created_at; drop table first_mile_shipment", 0, 50))
                    .rows());
    assertThat(injected).hasSize(5);
    assertThat(
            asA(
                () ->
                    sql.fetchOne("select count(*) from first_mile_shipment").get(0, Long.class)))
        .isEqualTo(5L);
    var byEta =
        asA(
            () ->
                service.page(
                        new FirstMileQuery(
                            null, null, null, null, null, null, null, null, "EXPECTED_ARRIVE_AT",
                            0, 50))
                    .rows());
    var byCode =
        asA(
            () ->
                service.page(
                        new FirstMileQuery(
                            null, null, null, null, null, null, null, null, "SHIPMENT_CODE", 0, 50))
                    .rows());
    assertThat(byEta).hasSize(5);
    assertThat(byCode).hasSize(5);
    // 非法筛选值直接 400（不静默忽略）
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.page(
                            new FirstMileQuery(
                                null, "TELEPORT", null, null, null, null, null, null, null, 0,
                                50))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("运输方式");
  }

  // ================================================================ 时间口径

  @Test
  @DisplayName("timestamptz 带 +08:00 写入后，读回 Instant 与墙钟零漂移（不静默偏 -28800s）")
  void 时间_带时区零漂移() {
    var saved = createShipment("100");
    dispatchShipment(saved.id(), confirmShipment(saved.id(), 0L).version());

    var rawEta =
        asA(
            () ->
                sql.fetchOne(
                        "select expected_arrive_at from first_mile_shipment where id=?", saved.id())
                    .get(0, OffsetDateTime.class));
    assertThat(rawEta.toInstant()).isEqualTo(ETA.toInstant());
    assertThat(rawEta.getOffset()).isEqualTo(ZoneOffset.ofHours(8));
    assertThat(rawEta.getHour()).isEqualTo(9);
    assertThat(rawEta.getMinute()).isEqualTo(45);
    // UTC 墙钟 = 01:45Z，同一瞬间
    assertThat(rawEta.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime())
        .isEqualTo(java.time.LocalDateTime.of(2026, 11, 20, 1, 45));

    // 7.5.1.2 那一侧的 ETA 与发出时刻也是同一瞬间 / 同一计划
    var leg = transitLegOf(saved.shipmentCode() + "-001");
    assertThat(leg.get("expected_arrive_at", OffsetDateTime.class).toInstant())
        .isEqualTo(ETA.toInstant());
    assertThat(leg.get("shipped_at", OffsetDateTime.class).toInstant())
        .isEqualTo(ETA.minusDays(30).toInstant());
    // 回执里的时刻与在途腿一致
    var receipt =
        asA(
            () ->
                sql.fetchOne(
                        "select shipped_at,expected_arrive_at,registered_at"
                            + " from first_mile_shipment_leg_receipt where shipment_id=?",
                        saved.id()));
    assertThat(receipt.get("shipped_at", OffsetDateTime.class).toInstant())
        .isEqualTo(ETA.minusDays(30).toInstant());
    assertThat(receipt.get("expected_arrive_at", OffsetDateTime.class).toInstant())
        .isEqualTo(ETA.toInstant());
  }

  // ================================================================ 租户隔离

  @Test
  @DisplayName("断言一：两租户互不可见（列表为空、详情/在途腿 404、写动作全被拒）")
  void 断言一_两租户互不可见() {
    var saved = createShipment("100");
    dispatchShipment(saved.id(), confirmShipment(saved.id(), 0L).version());

    // B 租户列表为空
    assertThat(asB(() -> service.page(query(0, 50)).total())).isEqualTo(0L);
    // B 租户详情 404
    assertThatThrownBy(() -> asB(() -> service.detail(saved.id())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或无权查看");
    // B 租户改单 / 发出 / 取消全部 404
    assertThatThrownBy(() -> asB(() -> service.update(saved.id(), null)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> asBWrite(() -> service.dispatch(b, saved.id(), null)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> asB(() -> service.cancel(saved.id(), null)))
        .isInstanceOf(ResponseStatusException.class);
    // B 租户选货助手只看到**自己**那份夹具批次（A 的批次一条都不在里面）
    var bCandidates = asB(() -> service.sourceCandidates(null, null, null, null, 0, 50));
    assertThat(bCandidates.candidateCount()).isEqualTo(1L);
    assertThat(bCandidates.rows().get(0).receiptId()).isEqualTo(idFor(b, 20));
    assertThat(bCandidates.rows().get(0).receiptId()).isNotEqualTo(receiptA);
    // B 租户**能**用自己那份收货单建单，且只看到自己那张（不是「B 因缺来源而建不出来」）
    var mine = asBWrite(() -> service.create(b, req(b).build()));
    assertThat(mine.shipmentCode()).startsWith("FM-");
    assertThat(asB(() -> service.page(query(0, 50)).rows().get(0).sourceRefId()))
        .isEqualTo(idFor(b, 20));
    assertThat(asB(() -> service.page(query(0, 50)).total())).isEqualTo(1L);
  }

  @Test
  @DisplayName("断言二：伪造 X-Tenant-Id 头无效（身份是 B 却指向 A 只能看到 B 自己的）")
  void 断言二_伪造租户头无效() throws Exception {
    createShipment("100");
    http.perform(get("/warehouse/first-mile-shipments")
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
    // 反向：身份是 A 伪造 B 的头也只能看到 A 自己的
    http.perform(get("/warehouse/first-mile-shipments")
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));
    // 伪造头也不能让 B 读到 A 的详情（在途腿子资源同理）
    http.perform(get("/warehouse/first-mile-shipments/" + receiptA)
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());
    // 伪造头也不能让 B 读到 A 的在途腿子资源
    var mine = asA(() -> service.detail(
        asA(() -> service.page(query(0, 50)).rows().get(0).id())));
    http.perform(
            get("/warehouse/first-mile-shipments/" + mine.shipment().id() + "/transit-legs")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());
    // 伪造头也不能让 B 看见 A 的可发批次：B 只看到**自己**那份夹具批次
    var bCandidate =
        http.perform(
                get("/warehouse/first-mile-shipments/source-candidates")
                    .header("Authorization", "Bearer tenant-b")
                    .header("X-Tenant-Id", a.toString()))
            .andExpect(status().isOk())
            .andReturn();
    var bId =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(bCandidate.getResponse().getContentAsString())
            .get("rows")
            .get(0)
            .get("receiptId")
            .asText();
    assertThat(bId).isEqualTo(idFor(b, 20).toString());
    assertThat(bId).isNotEqualTo(receiptA.toString());
  }

  @Test
  @DisplayName("断言三：连接池归还后不串租户（池大小为 1，交替读写两租户 20 轮）")
  void 断言三_连接池归还不串租户() {
    var mineA = createShipment("100");
    var mineB = asBWrite(() -> service.create(b, req(b).build()));
    for (int i = 0; i < 20; i++) {
      var aRows = asA(() -> service.page(query(0, 50)).rows());
      var bRows = asB(() -> service.page(query(0, 50)).rows());
      assertThat(aRows).hasSize(1);
      assertThat(aRows.get(0).id()).isEqualTo(mineA.id());
      assertThat(bRows).hasSize(1);
      assertThat(bRows.get(0).id()).isEqualTo(mineB.id());
    }
    // 归还后 A 依然只看到自己
    assertThat(asA(() -> service.page(query(0, 50)).rows().get(0).shipmentCode()))
        .isEqualTo(mineA.shipmentCode());
    // A 的在途腿也只有自己那一条（隔离到读侧聚合为止）
    dispatchShipment(mineA.id(), confirmShipment(mineA.id(), 0L).version());
    assertThat(firstMileLegCount()).isEqualTo(1L);
    assertThat(
            asB(
                () ->
                    sql.fetchOne("select count(*) from inventory_transfer")
                        .get(0, Long.class)))
        .isEqualTo(0L);
  }

  // ================================================================ RLS 与端点

  @Test
  @DisplayName("四张业务表都 FORCE RLS")
  void RLS_逐张表断言() {
    for (var table :
        List.of(
            "first_mile_shipment",
            "first_mile_shipment_line",
            "first_mile_shipment_leg_receipt",
            "first_mile_shipment_event")) {
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
  @DisplayName("端点：401 无凭据、403 权限不足、400 缺字段与格式、409 版本冲突、404 不存在")
  void 端点_错误码() throws Exception {
    http.perform(get("/warehouse/first-mile-shipments")).andExpect(status().isUnauthorized());
    http.perform(get("/warehouse/first-mile-shipments").header("Authorization", "Bearer nope"))
        .andExpect(status().isUnauthorized());
    http.perform(get("/warehouse/first-mile-shipments/capabilities"))
        .andExpect(status().isUnauthorized());

    // 403：只有读权限却要建单
    http.perform(
            post("/warehouse/first-mile-shipments")
                .header("Authorization", "Bearer read-only")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());

    // 400：缺必填项
    http.perform(
            post("/warehouse/first-mile-shipments")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isBadRequest());

    // 400：预计到港早于开船
    http.perform(
            post("/warehouse/first-mile-shipments")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    """
                    {"sourceType":"GOODS_RECEIPT","legalEntityId":"%s","originWarehouseId":"%s",
                     "destinationWarehouseId":"%s","destinationCountryCode":"US",
                     "transportMode":"SEA","incoterm":"FOB","riskBearer":"SELLER",
                     "departAt":"2026-11-20T09:45:00+08:00",
                     "expectedArriveAt":"2026-10-20T09:45:00+08:00","lines":[]}
                    """
                        .formatted(leA, whA, whUs)))
        .andExpect(status().isBadRequest());

    // 400：EXW 配 SELLER 不自洽
    http.perform(
            post("/warehouse/first-mile-shipments")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    """
                    {"sourceType":"GOODS_RECEIPT","sourceRefId":"%s","legalEntityId":"%s",
                     "originWarehouseId":"%s","destinationWarehouseId":"%s",
                     "destinationCountryCode":"US","transportMode":"SEA",
                     "incoterm":"EXW","riskBearer":"SELLER","lines":[]}
                    """
                        .formatted(receiptA, leA, whA, whUs)))
        .andExpect(status().isBadRequest());

    // 409：乐观锁版本不符
    var saved = createShipment("100");
    http.perform(
            put("/warehouse/first-mile-shipments/" + saved.id())
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"expectedVersion\":99}"))
        .andExpect(status().isConflict());
    // 400：缺 expectedVersion
    http.perform(
            put("/warehouse/first-mile-shipments/" + saved.id())
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isBadRequest());

    // 404：单不存在
    http.perform(
            get("/warehouse/first-mile-shipments/" + UUID.randomUUID())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
    http.perform(
            get("/warehouse/first-mile-shipments/" + UUID.randomUUID() + "/transit-legs")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("端点：完整链路（建单 → 确认 → 发出）在途腿子资源与货权节点可读")
  void 端点_完整链路() throws Exception {
    var body =
        """
        {"sourceType":"GOODS_RECEIPT","sourceRefId":"%s","sourceRefCode":"GR-20261001-000001",
         "legalEntityId":"%s","originWarehouseId":"%s","destinationWarehouseId":"%s",
         "destinationCountryCode":"US","destinationName":"洛杉矶海外仓","transportMode":"SEA",
         "carrierName":"中远海运","bookingNo":"COSCO-1234","vesselOrFlightNo":"ARIES",
         "incoterm":"FOB","riskBearer":"SELLER","currency":"USD","freightCost":2200,
         "insuranceCost":150,"dutyEstimate":380,"handlingCost":260,
         "departAt":"2026-10-20T09:45:00+08:00","expectedArriveAt":"2026-11-20T09:45:00+08:00",
         "notes":"首批头程",
         "lines":[{"skuId":"%s","qualityStatusId":"%s","sourceReceiptEventLineId":"%s",
                   "lotId":"%s","plannedQuantity":100,"declaredItemName":"棉制男士衬衫",
                   "hsCode":"6205200090","brand":"ACME","originCountryCode":"CN",
                   "declaredUnitValue":12.5,"declaredValue":1250,"grossWeightKg":180,
                   "netWeightKg":160,"volumeCbm":1.25,"cartonCount":12}]}
        """
            .formatted(
                receiptA, leA, whA, whUs, skuA, qSellable, receiptEventLineA, lotA1);

    var created =
        http.perform(
                post("/warehouse/first-mile-shipments")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.incoterm").value("FOB"))
            .andExpect(jsonPath("$.incotermLabel").value("FOB 装运港船上交货"))
            .andExpect(jsonPath("$.riskBearer").value("SELLER"))
            .andExpect(jsonPath("$.totalCost").value(2990.0000))
            .andReturn();
    var shipmentId = json(created).get("id").asText();
    var version = json(created).get("version").asLong();

    http.perform(
            post("/warehouse/first-mile-shipments/" + shipmentId + "/confirm")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"expectedVersion\":" + version + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONFIRMED"));

    http.perform(
            post("/warehouse/first-mile-shipments/" + shipmentId + "/dispatch")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"expectedVersion\":" + (version + 1) + ",\"notes\":\"已装船\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DISPATCHED"))
        .andExpect(jsonPath("$.transitLegCount").value(1));

    http.perform(
            get("/warehouse/first-mile-shipments/" + shipmentId + "/transit-legs")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].transitKind").value("FIRST_MILE"))
        .andExpect(jsonPath("$[0].transitStatus").value("IN_TRANSIT"))
        .andExpect(jsonPath("$[0].sourceSystem").value("FIRST_MILE"))
        .andExpect(jsonPath("$[0].outstandingQuantity").value(100.0000));

    http.perform(
            get("/warehouse/first-mile-shipments/" + shipmentId)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lines.length()").value(1))
        .andExpect(jsonPath("$.lines[0].declaredItemName").value("棉制男士衬衫"))
        .andExpect(jsonPath("$.transitLegs.length()").value(1))
        .andExpect(jsonPath("$.transitNote").isNotEmpty())
        .andExpect(jsonPath("$.titleTransferNote").isNotEmpty())
        // 已发出是终态 → 没有合法下一步，这是**正确**的空数组，不是缺实现
        .andExpect(jsonPath("$.transitions").isEmpty());

    // 列表端点带汇总与下拉
    http.perform(get("/warehouse/first-mile-shipments?sort=EXPECTED_ARRIVE_AT")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.summary.dispatchedCount").value(1))
        .andExpect(jsonPath("$.rows[0].incoterm").value("FOB"))
        .andExpect(jsonPath("$.rows[0].riskBearer").value("SELLER"))
        .andExpect(jsonPath("$.statusOptions.length()").value(4))
        .andExpect(jsonPath("$.transportModes.length()").value(4));
  }

  @Test
  @DisplayName("端点：取消链路")
  void 端点_取消链路() throws Exception {
    var created =
        http.perform(
                post("/warehouse/first-mile-shipments")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType("application/json")
                    .content(
                        """
                        {"sourceType":"GOODS_RECEIPT","sourceRefId":"%s","legalEntityId":"%s",
                         "originWarehouseId":"%s","destinationWarehouseId":"%s",
                         "destinationCountryCode":"US","transportMode":"EXPRESS",
                         "incoterm":"DDP","riskBearer":"SELLER",
                         "expectedArriveAt":"2026-11-20T09:45:00+08:00",
                         "lines":[{"skuId":"%s","qualityStatusId":"%s",
                                   "sourceReceiptEventLineId":"%s","lotId":"%s",
                                   "plannedQuantity":10,"declaredItemName":"棉制男士衬衫",
                                   "originCountryCode":"CN",
                                   "declaredUnitValue":12.5,"declaredValue":125,
                                   "grossWeightKg":18,"netWeightKg":16,"volumeCbm":0.125,
                                   "cartonCount":1}]}
                        """
                            .formatted(receiptA, leA, whA, whUs, skuA, qSellable,
                                receiptEventLineA, lotA1)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.incoterm").value("DDP"))
            .andReturn();
    var id = json(created).get("id").asText();
    http.perform(
            post("/warehouse/first-mile-shipments/" + id + "/cancel")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"expectedVersion\":0,\"reason\":\"船期取消\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
    // 取消后不能再确认
    http.perform(
            post("/warehouse/first-mile-shipments/" + id + "/confirm")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"expectedVersion\":1}"))
        .andExpect(status().isConflict());
  }

  private static com.fasterxml.jackson.databind.JsonNode json(
      org.springframework.test.web.servlet.MvcResult result) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper()
          .readTree(result.getResponse().getContentAsString());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
