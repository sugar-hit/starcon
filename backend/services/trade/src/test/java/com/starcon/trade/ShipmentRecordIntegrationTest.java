package com.starcon.trade;

import static com.starcon.trade.ShipmentRecordModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 9.4.1.2 发货记录。
 *
 * <p>本测试是 <b>V127 迁移那些 DB 级不变量的独立复验</b>：不采信任何外部结论，
 * 逐条在真实 PostgreSQL 16 上跑一遍，并且走 HTTP 端点而不只直连数据库，
 * 以便同时验证「服务层 + DB 层」两层都没有把闸门漏掉。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>warehouse 那一格是跨服务替身，不是本模块的数据替身</b>：
 * 8.3.4.1 的交接结果在 warehouse 服务自己的库里，AGENTS.md 禁止 trade 直连那个库，
 * 所以这里用 {@code com.sun.net.httpserver} 起一个 8.3.4.1 形状的只读端点
 * （与本仓库其它跨服务端口测试同一手法，例如 PackingQueryPort 读 8.3.3.2）。
 * 发货记录、包裹、轨迹观测、可见状态、回传登记、事件**全部真落 PostgreSQL 16**。
 *
 * <p><b>夹具里的时刻一律 pin 死</b>（{@link #SHIP_AT} / {@link #SLA_DUE} 等）：
 * 迟发率、有效追踪率、迟发标记全是「被断言的时间字段」，
 * 若用 {@code now()} 构造，同一个用例只在某个钟点通过，批次调度会随机引爆。
 *
 * <p><b>本测试要钉住的八条</b>：
 * <ol>
 *   <li><b>已发货只有一个来源</b>：同步经 8.3.4.1 授权端点，且只登记 HANDED_OVER；
 *       仓库不可达时 503 而不是空清单；</li>
 *   <li><b>幂等</b>：同一张交接单同步两次仍是一行 —— 迟发率的分母被翻倍就当场失真；</li>
 *   <li><b>三个状态列登记而不伪造</b>：未登记落独立 UNSET 桶，
 *       快照列脱离凭据行改不动（绕过服务层写 SQL 同样被拒）；</li>
 *   <li><b>「跳过」与「漏了」分开</b>：SKIPPED 必须带原因，且与 UNSET 是两个桶；</li>
 *   <li><b>绩效口径在服务端一处</b>：迟发率分母不含作废、不含未登记时限；</li>
 *   <li><b>终态不可回退</b>，凭据行 append-only（权限 + 触发器两层）；</li>
 *   <li><b>零跨服务外键、零 ALTER 兄弟表</b>；</li>
 *   <li><b>多租户三断言</b>：两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ShipmentRecordIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static final UUID whA = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer warehouse;
  static final ObjectMapper json = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /**
   * 夹具里 pin 死的时刻。**刻意不用 now()**：
   * 发货时刻、时限、轨迹观测时刻、回传登记时刻全是本模块要断言的字段，
   * 用墙上时钟构造会让这些用例只在某个钟点通过。
   */
  static final OffsetDateTime SHIP_AT = OffsetDateTime.parse("2026-03-01T10:15:30+08:00");
  static final OffsetDateTime SLA_DUE = OffsetDateTime.parse("2026-03-02T00:00:00+08:00");
  static final OffsetDateTime SLA_DUE_LATE = OffsetDateTime.parse("2026-02-27T00:00:00+08:00");
  static final OffsetDateTime TRACKED_AT = OffsetDateTime.parse("2026-03-03T08:00:00+08:00");
  static final OffsetDateTime CALLBACK_AT = OffsetDateTime.parse("2026-03-03T09:00:00+08:00");

  /** warehouse 侧「已确认交接」的交接单：id → 视图。测试通过它控制上游返回。 */
  static final Map<UUID, Map<String, Object>> handovers = new ConcurrentHashMap<>();

  /** warehouse 侧每张交接单的逐箱明细：handoverId → 包裹列表。 */
  static final Map<UUID, List<Map<String, Object>>> handoverParcels =
      new ConcurrentHashMap<>();

  /** 让 8.3.4.1 的只读替身整体不可用（模拟仓库服务挂了 / 网络不通）。 */
  static volatile boolean warehouseDown = false;

  /**
   * 运单号发号器：逐票**唯一**。
   *
   * <p>刻意不写成固定值：一票一个号才符合真实承运商行为，也才能用关键字筛选
   * 断言「真筛选」（号都相同时，任何关键字都命中全部行，这条断言会假绿）。
   */
  static final java.util.concurrent.atomic.AtomicInteger WAYBILL_SEQ =
      new java.util.concurrent.atomic.AtomicInteger();

  static final List<String> allPermissions =
      java.util.stream.Stream.of("fulfillment")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "trade." + p + "." + op))
          .toList();

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
        s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
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
            byte[] body = json.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer read-only",
          actor(user, a, null, List.of(grant("ALL", List.of("trade.fulfillment.read")))));
      identity.start();

      // 8.3.4.1 的只读替身：列表（?status=HANDED_OVER）与详情两个形状。
      // 只读端点**不返回租户上下文**，租户仍由 trade 侧已认证的 bearer 决定 ——
      // 这正是「两道 RLS」而不是「替对方决定租户」的意思。
      warehouse = HttpServer.create(new InetSocketAddress(0), 0);
      warehouse.createContext(
          "/warehouse/shipment-handovers",
          e -> {
            String path = e.getRequestURI().getPath();
            if (e.getRequestHeaders().getFirst("Authorization") == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            if (warehouseDown) {
              e.sendResponseHeaders(503, -1);
              e.close();
              return;
            }
            if ("/warehouse/shipment-handovers".equals(path)) {
              List<Map<String, Object>> items = new ArrayList<>();
              for (var row : handovers.values()) {
                // 列表端点不返回逐箱明细（与 8.3.4.1 的真实形状一致），
                // 所以 trade 必须逐张取详情才能拿到逐箱运单号。
                var copy = new java.util.LinkedHashMap<>(row);
                copy.remove("parcels");
                items.add(copy);
              }
              send(e, Map.of("items", items, "page", 0, "size", 100, "total", items.size()));
              return;
            }
            String id = path.substring(path.lastIndexOf('/') + 1);
            var row = handovers.get(UUID.fromString(id));
            if (row == null) {
              e.sendResponseHeaders(404, -1);
              e.close();
              return;
            }
            var copy = new java.util.LinkedHashMap<>(row);
            // 刻意对齐 8.3.4.1 的 HandoverDetail 形状：**parcels 与 order 平级**，
            // 不是嵌在 order 里面。嵌错一层的话逐箱运单号会整段丢失，
            // 而一票多箱的有效追踪与买家举证恰恰都按箱走。
            send(
                e,
                Map.of(
                    "order",
                    copy,
                    "parcels",
                    handoverParcels.getOrDefault(row.get("id"), List.of())));
          });
      warehouse.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static void send(com.sun.net.httpserver.HttpExchange e, Object payload) throws java.io.IOException {
    byte[] body = json.writeValueAsBytes(payload);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(200, body.length);
    e.getResponseBody().write(body);
    e.close();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "trade_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("WAREHOUSE_URL", () -> "http://localhost:" + warehouse.getAddress().getPort());
    // 单连接：只有复用同一条连接才能证明归还后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired ShipmentRecordService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    warehouse.stop(0);
    pg.stop();
  }

  AccessService.Access actorA;
  AccessService.Access actorB;

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "trade.fulfillment.update", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "trade.fulfillment.update", work);
  }

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    handovers.clear();
    handoverParcels.clear();
    warehouseDown = false;
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate shipment_record_event,shipment_record_callback,"
              + " shipment_record_visibility,shipment_record_tracking_observation,"
              + " shipment_record_parcel,shipment_record cascade");
      // 9.4.1.1 的投影表在本模块里只被**只读 join**：夹具直接铺数据，
      // 本模块的写入路径一行都不碰它。
      s.execute("truncate shipment_pending_parcel,shipment_pending_unit cascade");
    }
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  /** 往 8.3.4.1 的只读替身里放一张交接单（默认 status=HANDED_OVER）。 */
  UUID upstreamHandover(
      UUID packingId, String carrierCode, int parcels, OffsetDateTime handoverAt) {
    return upstreamHandover(packingId, carrierCode, parcels, handoverAt, "HANDED_OVER");
  }

  UUID upstreamHandover(
      UUID packingId,
      String carrierCode,
      int parcels,
      OffsetDateTime handoverAt,
      String status) {
    UUID handoverId = UUID.randomUUID();
    List<Map<String, Object>> parcelList = new ArrayList<>();
    for (int i = 1; i <= parcels; i++) {
      parcelList.add(
          new java.util.LinkedHashMap<>(
              Map.of(
                  "id", UUID.randomUUID(),
                  "parcelId", UUID.randomUUID(),
                  "parcelNo", i,
                  "parcelCode", "PC-" + i,
                  "parcelWaybillNo", "",
                  "effectiveWaybillNo", "WB-" + carrierCode + "-" + i,
                  "grossWeightG", new BigDecimal("1250.000").add(new BigDecimal(i * 100)),
                  "volumeCbm", new BigDecimal("0.012500"),
                  "itemCount", 2,
                  "totalQuantity", new BigDecimal("3.000000"))));
    }
    handoverParcels.put(handoverId, parcelList);
    handovers.put(
        handoverId,
        new java.util.LinkedHashMap<>(
            Map.ofEntries(
                Map.entry("id", handoverId),
                Map.entry("handoverCode", "SH-20260301-" + handoverId.toString().substring(0, 8)),
                Map.entry("packingOrderId", packingId),
                Map.entry("outboundOrderId", UUID.randomUUID()),
                Map.entry("outboundOrderCode", "OB-" + carrierCode),
                Map.entry("warehouseId", whA),
                Map.entry("warehouseCode", "WH-SH-01"),
                Map.entry("carrierCode", carrierCode),
                Map.entry("carrierName", carrierCode + " 物流"),
                Map.entry(
                    "masterWaybillNo",
                    "MB-" + carrierCode + "-" + WAYBILL_SEQ.incrementAndGet()),
                Map.entry("handoverAt", handoverAt.toString()),
                Map.entry("status", status),
                Map.entry("parcelCount", parcels),
                Map.entry("parcelItemCount", parcels * 2),
                Map.entry("totalQuantity", new BigDecimal(parcels * 3 + ".000000")),
                Map.entry("totalGrossWeightG", new BigDecimal("1500.000")),
                Map.entry("totalVolumeCbm", new BigDecimal("0.025000")))));
    return handoverId;
  }

  /**
   * 铺一条 9.4.1.1 的投影行 —— 本模块靠它拿到订单快照与发货时限。
   *
   * <p>直接以表所有者身份 INSERT：同服务同库的**只读**引用在生产路径上，
   * 夹具需要造出「9.4.1.1 已经同步过这批货」这个前提。
   */
  void seedPendingUnit(
      UUID tenant, UUID packingId, String salesOrderNo, OffsetDateTime slaDeadlineAt) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute(
          "insert into shipment_pending_unit (id,tenant_id,source_packing_order_id,"
              + " source_outbound_order_id,packed_at,parcel_count,total_weight_kg,warehouse_id,"
              + " warehouse_ref,outbound_order_no,sales_order_id,sales_order_no,shop_ref,"
              + " buyer_name,destination_country,carrier_channel,carrier_code,sla_deadline_at,"
              + " sla_source,sla_registered_by_membership_id,sla_registered_at,synced_at) values ('"
              + UUID.randomUUID()
              + "','"
              + tenant
              + "','"
              + packingId
              + "','"
              + UUID.randomUUID()
              + "','"
              + TRACKED_AT
              + "',1,1.500000,'"
              + whA
              + "','WH-SH-01','OB-1','"
              + UUID.randomUUID()
              + "','"
              + salesOrderNo
              + "','SHOP-01','测试买家','US','THIRD_PARTY','SF',"
              + (slaDeadlineAt == null ? "null" : "'" + slaDeadlineAt + "'")
              + ","
              + (slaDeadlineAt == null ? "''" : "'MANUAL_ATTESTATION'")
              + ","
              + (slaDeadlineAt == null ? "null" : "'" + user + "'")
              + ","
              + (slaDeadlineAt == null ? "null" : "'" + SLA_DUE + "'")
              + ",'"
              + TRACKED_AT
              + "')");
    } catch (java.sql.SQLException e) {
      throw new AssertionError("铺 9.4.1.1 投影夹具失败：" + e.getMessage(), e);
    }
  }

  ShipmentRecordSyncResult sync() {
    return asA(() -> service.sync(new ShipmentRecordSyncInput(100), "Bearer tenant-a"));
  }

  List<ShipmentRecordRow> records() {
    return asA(
        () ->
            service.records(
                null, null, null, null, null, null, 0, 100).items());
  }

  ShipmentRecordRow firstRecord() {
    return records().get(0);
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================
  @Test
  void 能力声明如实报出三处上游缺口并点名ownerModule() throws Exception {
    http.perform(
            get("/trade/fulfillment/shipment-records/capabilities")
                .header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions.length()").value(5))
        .andExpect(jsonPath("$.dimensions[0].dimension").value("SHIPMENT_FACT"))
        .andExpect(jsonPath("$.dimensions[0].available").value(true))
        .andExpect(jsonPath("$.dimensions[2].dimension").value("TRACKING_STATUS"))
        .andExpect(jsonPath("$.dimensions[2].available").value(false))
        .andExpect(jsonPath("$.dimensions[2].ownerModule").value("10.3 物流轨迹 / 10 物流下单"))
        .andExpect(jsonPath("$.dimensions[3].available").value(false))
        .andExpect(jsonPath("$.dimensions[4].dimension").value("PLATFORM_CALLBACK_STATUS"))
        .andExpect(jsonPath("$.dimensions[4].available").value(false))
        .andExpect(jsonPath("$.dimensions[4].ownerModule").value("9.4.2.1 / 2.5.1.1"))
        // 三处缺口都在响应里点名了 ownerModule 与影响，不允许悄悄省略
        .andExpect(content().string(org.hamcrest.Matchers.containsString("10.3")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("2.5.1.1")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("9.4.2.1")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("8.3.4.1")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("UNSET")))
        .andExpect(jsonPath("$.statuses.length()").value(5))
        .andExpect(jsonPath("$.trackingStatuses.length()").value(9))
        .andExpect(jsonPath("$.groupByOptions.length()").value(3));
  }

  // ==========================================================================
  // 同步：已发货事实的唯一来源
  // ==========================================================================
  @Test
  void 同步登记已确认交接的交运事实且时刻零漂移() {
    UUID packingId = UUID.randomUUID();
    seedPendingUnit(a, packingId, "SO-1001", SLA_DUE);
    upstreamHandover(packingId, "SF", 2, SHIP_AT);

    var result = sync();
    assertThat(result.fetchedHandovers()).isEqualTo(1);
    assertThat(result.insertedRecords()).isEqualTo(1);
    assertThat(result.updatedRecords()).isZero();
    assertThat(result.parcelRowsUpserted()).isEqualTo(2);
    assertThat(result.recordsWithOrderSnapshot()).isEqualTo(1);

    var row = firstRecord();
    assertThat(row.carrierCode()).isEqualTo("SF");
    assertThat(row.masterWaybillNo()).startsWith("MB-SF-");
    assertThat(row.salesOrderNo()).isEqualTo("SO-1001");
    assertThat(row.shopRef()).isEqualTo("SHOP-01");
    // **断言写回的值**（不是「没抛异常」）：timestamptz 往返必须落在同一瞬间。
    // 用 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s，这条断言就是拦它的。
    assertThat(row.shippedAt().toInstant()).as("发货时刻必须零漂移").isEqualTo(SHIP_AT.toInstant());
    assertThat(row.slaDeadlineAt().toInstant()).isEqualTo(SLA_DUE.toInstant());
    assertThat(row.status()).isEqualTo(ShipmentRecordStatus.SHIPPED);
    assertThat(row.parcelCount()).isEqualTo(2);
    assertThat(row.totalGrossWeightG()).isEqualByComparingTo(new BigDecimal("1500.000"));

    // 逐箱运单号逐箱留（有效追踪与买家举证都按箱走）
    var detail = asA(() -> service.record(row.id().toString()));
    assertThat(detail.parcels()).hasSize(2);
    assertThat(detail.parcels())
        .extracting(ShipmentRecordParcelRow::waybillNo)
        .containsExactly("WB-SF-1", "WB-SF-2");
  }

  @Test
  void 同步只登记已确认交接的交接单且幂等() {
    UUID packingId = UUID.randomUUID();
    upstreamHandover(packingId, "SF", 1, SHIP_AT);
    // 混一张 OPEN：货还没交出去，不许登记成已发货
    upstreamHandover(packingId, "DHL", 1, SHIP_AT, "OPEN");

    var first = sync();
    assertThat(first.fetchedHandovers()).isEqualTo(2);
    assertThat(first.insertedRecords()).as("OPEN 那一票不算已发货").isEqualTo(1);
    assertThat(first.skippedHandovers()).isEqualTo(1);

    // 幂等：再同步一次仍是一行 —— 迟发率的分母被翻倍就当场失真
    var second = sync();
    assertThat(second.insertedRecords()).isZero();
    assertThat(second.updatedRecords()).isEqualTo(1);
    assertThat(asA(() -> service.records(null, null, null, null, null, null, 0, 100).totalElements()))
        .isEqualTo(1);
  }

  @Test
  void 仓库不可达时503而不是空清单() {
    upstreamHandover(UUID.randomUUID(), "SF", 1, SHIP_AT);
    warehouseDown = true;
    assertThatThrownBy(() -> sync())
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("仓库服务");
    // 关键：一张都没有落库。空清单会被当成「今天一票都没发」而让运营收工。
    assertThat(records()).isEmpty();
  }

  @Test
  void 没有订单快照时留空且如实报数() {
    // 不铺 9.4.1.1 的投影：模拟「9.4.1.1 还没同步过这批货」
    upstreamHandover(UUID.randomUUID(), "SF", 1, SHIP_AT);
    var result = sync();
    assertThat(result.recordsWithoutOrderSnapshot()).isEqualTo(1);
    assertThat(result.recordsWithoutSla()).isEqualTo(1);
    var row = firstRecord();
    assertThat(row.salesOrderNo()).as("读不到就留空，不编一个占位订单号").isEmpty();
    assertThat(row.slaDeadlineAt()).isNull();
    // 同步本身不因为读不到订单快照而失败 —— 发货事实不依赖它
    assertThat(row.masterWaybillNo()).startsWith("MB-SF-");
  }

  // ==========================================================================
  // 三列状态：登记而不伪造
  // ==========================================================================
  @Test
  void 刚同步的三列状态都落独立UNSET桶() {
    upstreamHandover(UUID.randomUUID(), "SF", 1, SHIP_AT);
    sync();
    var row = firstRecord();
    assertThat(row.trackingStatus())
        .as("10.3 未交付：未登记轨迹不是「轨迹正常」")
        .isEqualTo(ShipmentTrackingStatus.UNSET);
    assertThat(row.buyerVisibleStatus()).isEqualTo(BuyerVisibleStatus.UNSET);
    assertThat(row.platformCallbackStatus())
        .as("压根没登记 = 漏了")
        .isEqualTo(PlatformCallbackStatus.UNSET);
    assertThat(row.trackingObservationsPresent()).isFalse();
    assertThat(row.lateAgainstSla()).isFalse();

    var summary = asA(
        () ->
            service.records(null, null, null, null, null, null, 0, 50).summary());
    assertThat(summary.unsetTrackingRecords()).isEqualTo(1);
    assertThat(summary.unsetVisibilityRecords()).isEqualTo(1);
    assertThat(summary.unsetCallbackRecords()).isEqualTo(1);
    assertThat(summary.noSlaRecords()).isEqualTo(1);
  }

  @Test
  void 登记轨迹观测后快照等于凭据行且逐箱也更新() {
    upstreamHandover(UUID.randomUUID(), "SF", 2, SHIP_AT);
    sync();
    var record = firstRecord();
    var detail = asA(() -> service.record(record.id().toString()));
    UUID parcelId = detail.parcels().get(0).id();

    asA(
        () ->
            service.registerTracking(
                record.id().toString(),
                new ShipmentTrackingObservationInput(
                    ShipmentTrackingStatus.IN_TRANSIT,
                    ShipmentObservationSource.MANUAL_ATTESTATION,
                    parcelId,
                    "TRK-SF-0001",
                    "承运商轨迹号",
                    TRACKED_AT),
                user));

    var after = asA(() -> service.record(record.id().toString()));
    assertThat(after.record().trackingStatus()).isEqualTo(ShipmentTrackingStatus.IN_TRANSIT);
    assertThat(after.tracking()).hasSize(1);
    // **断言写回的时刻**：观测时刻必须是观测方给的 pinned 值，不能被写入时刻顶替
    assertThat(after.tracking().get(0).observedAt().toInstant())
        .isEqualTo(TRACKED_AT.toInstant());
    assertThat(after.tracking().get(0).parcelNo()).isEqualTo(1);
    // 逐箱行也带上了自己的最新轨迹
    assertThat(after.parcels().get(0).currentTrackingStatus())
        .isEqualTo(ShipmentTrackingStatus.IN_TRANSIT);
    assertThat(after.parcels().get(1).currentTrackingStatus())
        .as("没登记的箱子仍是 UNSET，不能跟着同票第一箱一起变成在途")
        .isEqualTo(ShipmentTrackingStatus.UNSET);
    // 快照脱离凭据行改不动：绕过服务层直接写 SQL 也选不出来
    assertDbRefused(
        () -> asTenantA("update shipment_record set tracking_status = 'DELIVERED'").run(),
        "轨迹快照",
        "23514");
  }

  @Test
  void 平台回传的跳过与漏了是两个桶且跳过必须带原因() {
    upstreamHandover(UUID.randomUUID(), "SF", 1, SHIP_AT);
    sync();
    var record = firstRecord();

    // 漏了：UNSET 是一个独立桶，不与 SKIPPED 合并
    assertThat(record.platformCallbackStatus()).isEqualTo(PlatformCallbackStatus.UNSET);

    // 跳过必须带原因 —— 否则「跳过」与「漏了」在事后追责时无法区分
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.registerCallback(
                            record.id().toString(),
                            new ShipmentCallbackInput(
                                PlatformCallbackStatus.SKIPPED,
                                ShipmentObservationSource.MANUAL_ATTESTATION,
                                "",
                                "",
                                "",
                                CALLBACK_AT),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("原因");

    asA(
        () ->
            service.registerCallback(
                record.id().toString(),
                new ShipmentCallbackInput(
                    PlatformCallbackStatus.SKIPPED,
                    ShipmentObservationSource.MANUAL_ATTESTATION,
                    "",
                    "",
                    "店铺已关闭该订单的平台面，不再回传",
                    CALLBACK_AT),
                user));
    var after = firstRecord();
    assertThat(after.platformCallbackStatus()).isEqualTo(PlatformCallbackStatus.SKIPPED);
    assertThat(after.platformCallbackStatusLabel()).isEqualTo("决定不回传");

    // 平台已受理必须带回执凭据（DB CHECK）
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.registerCallback(
                            record.id().toString(),
                            new ShipmentCallbackInput(
                                PlatformCallbackStatus.ACKNOWLEDGED,
                                ShipmentObservationSource.CHANNEL_SERVICE,
                                "REQ-1",
                                "",
                                "平台受理",
                                CALLBACK_AT),
                            user)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);

    // 登记买家可见状态：人工登记必须写依据
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.registerVisibility(
                            record.id().toString(),
                            new ShipmentVisibilityInput(
                                BuyerVisibleStatus.VISIBLE,
                                BuyerVisibilitySource.MANUAL_ATTESTATION,
                                "",
                                CALLBACK_AT),
                            user)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    asA(
        () ->
            service.registerVisibility(
                record.id().toString(),
                new ShipmentVisibilityInput(
                    BuyerVisibleStatus.VISIBLE,
                    BuyerVisibilitySource.MANUAL_ATTESTATION,
                    "平台面已开放买家查看物流",
                    CALLBACK_AT),
                user));
    assertThat(firstRecord().buyerVisibleStatus()).isEqualTo(BuyerVisibleStatus.VISIBLE);
  }

  // ==========================================================================
  // 绩效口径
  // ==========================================================================
  @Test
  void 迟发率与有效追踪率口径不含作废也不含未登记时限() {
    // 准时一票
    UUID p1 = UUID.randomUUID();
    seedPendingUnit(a, p1, "SO-1", SLA_DUE);
    upstreamHandover(p1, "SF", 1, SHIP_AT);
    // 迟发一票（时限早于发货时刻）
    UUID p2 = UUID.randomUUID();
    seedPendingUnit(a, p2, "SO-2", SLA_DUE_LATE);
    upstreamHandover(p2, "SF", 1, SHIP_AT);
    // 没有时限一票
    upstreamHandover(UUID.randomUUID(), "SF", 1, SHIP_AT);
    sync();

    var report = asA(
        () ->
            service.performance(null, null, null, null, null));
    assertThat(report.evaluatedRecords()).isEqualTo(3);
    assertThat(report.onTimeRecords()).isEqualTo(1);
    assertThat(report.lateRecords()).isEqualTo(1);
    assertThat(report.unsetSlaRecords())
        .as("没时限既不算准时也不算迟发")
        .isEqualTo(1);
    // 分母只算登记过时限的两票：1/2 = 0.5000（把没时限的当准时会让迟发率系统性偏低）
    assertThat(report.lateRate()).isEqualByComparingTo(new BigDecimal("0.5000"));
    assertThat(report.onTimeRate()).isEqualByComparingTo(new BigDecimal("0.5000"));
    // 有效追踪：三票都没有轨迹观测
    assertThat(report.trackableRecords()).isZero();
    assertThat(report.validTrackingRate()).isEqualByComparingTo(new BigDecimal("0.0000"));

    // 给第一票补一条轨迹观测 → 有效追踪率变成 1/3
    var first = records().get(0);
    asA(
        () ->
            service.registerTracking(
                first.id().toString(),
                new ShipmentTrackingObservationInput(
                    ShipmentTrackingStatus.IN_TRANSIT,
                    ShipmentObservationSource.MANUAL_ATTESTATION,
                    null,
                    "TRK-1",
                    "",
                    TRACKED_AT),
                user));
    var after = asA(() -> service.performance(null, null, null, null, null));
    assertThat(after.trackableRecords()).isEqualTo(1);
    assertThat(after.untrackableRecords()).isEqualTo(2);
    assertThat(after.validTrackingRate()).isEqualByComparingTo(new BigDecimal("0.3333"));

    // 作废一票后，作废的那票不进分母（撤销的是错发货，不是迟发货）
    asA(
        () ->
            service.changeStatus(
                first.id().toString(),
                new ShipmentStatusInput(ShipmentRecordStatus.VOID, "上游交接作废，这一票不算发货"),
                user));
    var afterVoid = asA(() -> service.performance(null, null, null, null, null));
    assertThat(afterVoid.evaluatedRecords()).isEqualTo(2);
  }

  @Test
  void 状态只能单向推进且终态不可回退() {
    upstreamHandover(UUID.randomUUID(), "SF", 1, SHIP_AT);
    sync();
    var record = firstRecord();

    // SHIPPED 直接跳 DELIVERED 不合法（DB 触发器与 DB CHECK 两层）
    assertDbRefused(
        () ->
            asA(
                () ->
                    service.changeStatus(
                        record.id().toString(),
                        new ShipmentStatusInput(ShipmentRecordStatus.DELIVERED, "想直接置妥投"),
                        user)),
        "shipment_record",
        "23514");

    asA(
        () ->
            service.changeStatus(
                record.id().toString(),
                new ShipmentStatusInput(ShipmentRecordStatus.IN_TRANSIT, "承运商已收件"),
                user));
    asA(
        () ->
            service.changeStatus(
                record.id().toString(),
                new ShipmentStatusInput(ShipmentRecordStatus.DELIVERED, "买家已签收"),
                user));
    assertThat(firstRecord().status()).isEqualTo(ShipmentRecordStatus.DELIVERED);

    // 终态不可再动
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.changeStatus(
                            record.id().toString(),
                            new ShipmentStatusInput(ShipmentRecordStatus.RETURNED, "又想改成退回"),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("终态");
    // 终态也不接受追加登记
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.registerTracking(
                            record.id().toString(),
                            new ShipmentTrackingObservationInput(
                                ShipmentTrackingStatus.RETURNED,
                                ShipmentObservationSource.MANUAL_ATTESTATION,
                                null,
                                "TRK-X",
                                "",
                                TRACKED_AT),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("终态");
  }

  // ==========================================================================
  // 看板 / 分页 / 筛选
  // ==========================================================================
  @Test
  void 分组看板是真聚合且UNSET独立成桶() {
    UUID p1 = UUID.randomUUID();
    upstreamHandover(p1, "SF", 1, SHIP_AT);
    UUID p2 = UUID.randomUUID();
    upstreamHandover(p2, "DHL", 2, SHIP_AT);
    sync();

    var board =
        asA(() -> service.board(null, null, null, null, ShipmentRecordGroupBy.CARRIER));
    assertThat(board.groups()).hasSize(2);
    assertThat(board.summary().totalRecords()).isEqualTo(2);

    // 按轨迹状态分组：三票都在 UNSET 桶里，**不会**被并进正常桶
    var byTracking =
        asA(() -> service.board(null, null, null, null, ShipmentRecordGroupBy.TRACKING));
    assertThat(byTracking.groups()).hasSize(1);
    assertThat(byTracking.groups().get(0).groupKey()).isEqualTo("UNSET");
    assertThat(byTracking.groups().get(0).groupLabel()).isEqualTo("未登记轨迹");
    assertThat(byTracking.groups().get(0).recordCount()).isEqualTo(2);

    // 按平台回传状态分组：同样只落在 UNSET
    var byCallback =
        asA(() -> service.board(null, null, null, null, ShipmentRecordGroupBy.PLATFORM_CALLBACK));
    assertThat(byCallback.groups()).hasSize(1);
    assertThat(byCallback.groups().get(0).groupKey()).isEqualTo("UNSET");
    assertThat(byCallback.groups().get(0).groupLabel()).isEqualTo("未登记");
  }

  @Test
  void 真分页真筛选且汇总不受分页影响() {
    for (int i = 0; i < 5; i++) {
      upstreamHandover(UUID.randomUUID(), "SF", 1, SHIP_AT.plusHours(i));
    }
    sync();
    var firstPage = asA(() -> service.records(null, null, null, null, null, null, 0, 2));
    assertThat(firstPage.items()).hasSize(2);
    assertThat(firstPage.totalElements()).isEqualTo(5);
    assertThat(firstPage.totalPages()).isEqualTo(3);
    assertThat(firstPage.summary().totalRecords()).as("汇总不受分页影响").isEqualTo(5);

    var secondPage = asA(() -> service.records(null, null, null, null, null, null, 1, 2));
    assertThat(secondPage.items())
        .extracting(ShipmentRecordRow::id)
        .doesNotContainAnyElementsOf(
            firstPage.items().stream().map(ShipmentRecordRow::id).toList());

    // 关键字筛选是真筛选
    var waybill = firstPage.items().get(0).masterWaybillNo();
    assertThat(
            asA(() -> service.records(null, null, null, null, waybill, null, 0, 50)).items())
        .hasSize(1);
    // 承运商筛选
    assertThat(asA(() -> service.records("DHL", null, null, null, null, null, 0, 50)).items())
        .isEmpty();
  }

  // ==========================================================================
  // DB 级闸门
  // ==========================================================================
  @Test
  void 六张表逐张强制RLS且starcon_app拿不到() throws Exception {
    var tables =
        List.of(
            "shipment_record",
            "shipment_record_parcel",
            "shipment_record_tracking_observation",
            "shipment_record_visibility",
            "shipment_record_callback",
            "shipment_record_event");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      for (var t : tables) {
        try (var rs =
            s.executeQuery("select relforcerowsecurity from pg_class where relname = '" + t + "'")) {
          assertThat(rs.next()).as(t + " 必须存在").isTrue();
          assertThat(rs.getBoolean(1)).as(t + " 必须 FORCE RLS").isTrue();
        }
      }
    }
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), "trade_app", "test-only");
        var s = c.createStatement()) {
      for (var t : tables) {
        // 读的是**布尔值本身**而不是「有没有这一行」—— 只判 next() 恒为 true，
        // 会让这条断言看起来在跑、实际上什么都没验。
        try (var rs =
            s.executeQuery(
                "select has_table_privilege('starcon_app','" + t + "','SELECT') as can_read")) {
          assertThat(rs.next()).isTrue();
          assertThat(rs.getBoolean(1)).as(t + " 不该对跨服务只读角色开放").isFalse();
        }
      }
    }
  }

  @Test
  void 凭据行appendOnly有两层且不是死代码() {
    upstreamHandover(UUID.randomUUID(), "SF", 1, SHIP_AT);
    sync();
    var record = firstRecord();
    asA(
        () ->
            service.registerTracking(
                record.id().toString(),
                new ShipmentTrackingObservationInput(
                    ShipmentTrackingStatus.PICKED_UP,
                    ShipmentObservationSource.MANUAL_ATTESTATION,
                    null,
                    "TRK-IMM",
                    "",
                    TRACKED_AT),
                user));

    // 第一层：对运行角色 REVOKE 权限
    assertDbRefused(
        () ->
            asTradeApp(
                    "update shipment_record_tracking_observation set detail = '改一笔'")
                .run(),
        "",
        "42501");
    // 第二层：以**表所有者**身份再撞一次 —— 否则触发器就是永远跑不到的死代码
    assertDbRefused(
        () ->
            asTenantA("delete from shipment_record_tracking_observation").run(),
        "append-only",
        "23514");
    assertDbRefused(
        () -> asTenantA("update shipment_record_event set detail = '改一笔'").run(),
        "append-only",
        "23514");
  }

  @Test
  void 零跨服务外键且不碰兄弟模块的表() throws Exception {
    // 本模块的 FK 只允许指向本服务自己的表：一条跨服务 FK 都是 AGENTS.md 禁止的
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      try (var rs =
          s.executeQuery(
              """
              select c.conrelid::regclass::text as mine, c.confrelid::regclass::text as target
                from pg_constraint c
                join pg_class t on t.oid = c.conrelid
               where c.contype = 'f' and t.relnamespace = 'public'::regnamespace
                 and t.relname like 'shipment_record%'
               order by t.relname
              """)) {
        assertThat(rs.next()).as("本模块至少有一个同服务 FK").isTrue();
        while (rs.next()) {
          assertThat(rs.getString("target"))
              .as("跨服务外键被 AGENTS.md 禁止：%s → %s", rs.getString("mine"), rs.getString("target"))
              .startsWith("shipment_record");
        }
      }
      // 不碰兄弟模块的表：本迁移零 ALTER，9.4.1.1 的两张表一个列都没多
      for (var sibling : List.of("shipment_pending_unit", "shipment_pending_parcel")) {
        try (var rs =
            s.executeQuery(
                "select count(*) from information_schema.columns"
                    + " where table_name = '"
                    + sibling
                    + "' and (column_name like '%callback%' or column_name like '%tracking%'"
                    + " or column_name like '%visibility%')")) {
          assertThat(rs.next()).isTrue();
          assertThat(rs.getInt(1)).as(sibling + " 不该被本模块加上状态列").isZero();
        }
      }
    }
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    UUID p1 = UUID.randomUUID();
    upstreamHandover(p1, "SF", 2, SHIP_AT);
    upstreamHandover(UUID.randomUUID(), "DHL", 1, SHIP_AT);
    sync();

    var pageA = asA(() -> service.records(null, null, null, null, null, null, 0, 50));
    var pageB = asB(() -> service.records(null, null, null, null, null, null, 0, 50));
    assertThat(pageA.items()).hasSize(2);
    assertThat(pageB.items()).as("B 租户一条都看不到").isEmpty();

    var boardB = asB(() -> service.board(null, null, null, null, ShipmentRecordGroupBy.CARRIER));
    assertThat(boardB.groups()).isEmpty();
    assertThat(boardB.summary().totalRecords()).isZero();

    var perfB = asB(() -> service.performance(null, null, null, null, null));
    assertThat(perfB.evaluatedRecords()).isZero();

    var recordA = pageA.items().get(0).id();
    assertThatThrownBy(() -> asB(() -> service.record(recordA.toString())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在");
    // 事件流同样隔离
    assertThat(asB(() -> service.events(null, null, 0, 50)).items()).isEmpty();
  }

  // ==========================================================================
  // 断言 2：伪造租户上下文无效
  // ==========================================================================
  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    upstreamHandover(UUID.randomUUID(), "SF", 1, SHIP_AT);
    sync();
    var recordId = firstRecord().id();

    // (1) 事务外没有「环境租户」：任何租户的行都看不见
    assertThat(sql.fetch("select id from shipment_record").size()).isZero();

    // B 的会话 + 伪造指向 A 的头 → 依然 404
    http.perform(
            get("/trade/fulfillment/shipment-records/records/{id}", recordId)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    // A 的会话 + 伪造指向 B 的头 → 仍按 A 解析
    http.perform(
            get("/trade/fulfillment/shipment-records/records/{id}", recordId)
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isOk());

    // 写入路径同样不认头：B 会话伪造 A 的头，登记落不到 A
    http.perform(
            post("/trade/fulfillment/shipment-records/records/{id}/tracking", recordId)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"status":"IN_TRANSIT","source":"MANUAL_ATTESTATION","observationRef":"TRK-FORGED","observedAt":"2026-03-03T08:00:00+08:00"}"""))
        .andExpect(status().isNotFound());
    assertThat(
            asA(
                () ->
                    sql.fetchOne(
                            "select count(*) from shipment_record_tracking_observation"
                                + " where observation_ref = 'TRK-FORGED'")
                        .get(0, Long.class)))
        .as("伪造头不得把凭据行写进别的租户")
        .isZero();

    // (2) 提交后租户上下文必须已清空
    assertThat(sql.fetch("select id from shipment_record").size()).isZero();
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    upstreamHandover(UUID.randomUUID(), "SF", 1, SHIP_AT);
    sync();
    // 池大小为 1：下面每次读写都复用同一条连接
    for (int i = 0; i < 5; i++) {
      assertThat(asA(() -> service.records(null, null, null, null, null, null, 0, 50)).items())
          .as("第 %d 轮 A", i)
          .isNotEmpty();
      assertThat(asB(() -> service.records(null, null, null, null, null, null, 0, 50)).items())
          .as("第 %d 轮 B", i)
          .isEmpty();
      assertThat(sql.fetch("select id from shipment_record").size())
          .as("第 %d 轮事务外", i)
          .isZero();
    }
  }

  // ==========================================================================
  // HTTP 边界
  // ==========================================================================
  @Test
  void 同步与清单端点真的走HTTP且未认证与只读被拒() throws Exception {
    upstreamHandover(UUID.randomUUID(), "SF", 1, SHIP_AT);
    http.perform(
            post("/trade/fulfillment/shipment-records/sync")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pageSize\":100}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.insertedRecords").value(1));
    http.perform(
            get("/trade/fulfillment/shipment-records/records")
                .header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
    http.perform(get("/trade/fulfillment/shipment-records/performance"))
        .andExpect(status().isUnauthorized());
    http.perform(get("/trade/fulfillment/shipment-records/records"))
        .andExpect(status().isUnauthorized());
    http.perform(
            post("/trade/fulfillment/shipment-records/sync")
                .header("Authorization", "Bearer read-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pageSize\":100}"))
        .andExpect(status().isForbidden());
    http.perform(
            get("/trade/fulfillment/shipment-records/records/{id}", UUID.randomUUID())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
    http.perform(
            get("/trade/fulfillment/shipment-records/records/not-a-uuid")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
    http.perform(
            get("/trade/fulfillment/shipment-records/events")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  static java.sql.SQLException rootSqlException(Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof java.sql.SQLException sql) return sql;
      current = current.getCause();
    }
    throw new AssertionError("没有找到 SQLException，捕获到的异常是：" + error, error);
  }

  void assertDbRefused(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable work,
      String expectedFragment,
      String... acceptableStates) {
    var error = catchThrowable(work);
    var sql = rootSqlException(error);
    assertThat(sql.getSQLState())
        .as("必须被 DB 拒绝，实际 SQLState=%s，消息=%s", sql.getSQLState(), sql.getMessage())
        .isIn(acceptableStates);
    if ("23514".equals(sql.getSQLState()) && !expectedFragment.isEmpty()) {
      assertThat(sql.getMessage()).as("拒绝理由必须可读").contains(expectedFragment);
    }
  }

  @FunctionalInterface
  interface OwnerSql {
    void run() throws Exception;
  }

  /** 以**表所有者**身份直连执行一条 SQL（租户 A）。 */
  static OwnerSql asTenantA(String sqlText) {
    return asOwner(a, sqlText);
  }

  static OwnerSql asOwner(UUID tenant, String sqlText) {
    return () -> {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var st = c.createStatement()) {
        st.execute("select set_config('app.current_tenant','" + tenant + "',false)");
        st.execute(sqlText);
      }
    };
  }

  /** 以**运行角色 trade_app** 身份执行一条 SQL（租户 A）—— 验权限层那一道。 */
  static OwnerSql asTradeApp(String sqlText) {
    return () -> {
      try (var c = DriverManager.getConnection(pg.getJdbcUrl(), "trade_app", "test-only");
          var st = c.createStatement()) {
        st.execute("select set_config('app.current_tenant','" + a + "',false)");
        st.execute(sqlText);
      }
    };
  }
}
