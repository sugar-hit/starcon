package com.starcon.trade;

import static com.starcon.trade.PendingShipmentModels.*;
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
 * 9.4.1.1 待发货。
 *
 * <p>本测试是 <b>V124 迁移那些 DB 级不变量的独立复验</b>：不采信任何外部结论， 逐条在真实 PostgreSQL 16 上跑一遍，并且走 HTTP 端点而不是只直连数据库，
 * 以便同时验证「服务层 + DB 层」两层都没有把闸门漏掉。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接： 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>warehouse 那一格是跨服务替身，不是本模块的数据替身</b>： 8.3.3.2 的打包结果在 warehouse 服务自己的库里，AGENTS.md 禁止 trade
 * 直连那个库， 所以这里用 {@code com.sun.net.httpserver} 起一个 8.3.3.2 形状的只读端点 （与本仓库其它跨服务端口测试同一手法，例如
 * BomVersionPort 读 catalog）。 待发货单元、包裹、批量任务、运单、事件**全部真落 PostgreSQL 16**。
 *
 * <p><b>本测试要钉住的六条</b>：
 *
 * <ol>
 *   <li><b>数据来源只有一个</b>：同步经 8.3.3.2 授权端点，仓库不可达时 503 而不是空清单；
 *   <li><b>四个分组维度是真 GROUP BY</b>，不是把一页在 Java 里数；
 *   <li><b>一次任务不许跨承运商</b>（蓝图点名的大促期效率闸）；
 *   <li><b>三个批量操作是真任务</b>：SUCCEEDED 要求零失败零跳过， 有跳过只能是 PARTIAL，面单没凭据时必须显式 SKIPPED 而不是伪装成功；
 *   <li><b>本模块最远只到 HANDED_OVER</b>：已发货不是本模块能置的状态；
 *   <li><b>多租户三断言</b>：两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class PendingShipmentIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static final UUID whA = UUID.randomUUID(), whB = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer warehouse;
  static final ObjectMapper json = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** warehouse 侧「已完成打包」的打包单：id → 视图。测试通过它控制上游返回。 */
  static final Map<UUID, Map<String, Object>> packingOrders = new ConcurrentHashMap<>();

  /**
   * 让 8.3.3.2 的只读替身整体不可用（模拟仓库服务挂了 / 网络不通）。
   *
   * <p>用标志位而不是真的 {@code stop()} 再 {@code start()}：后者换端口后 {@code @DynamicPropertySource}
   * 已经绑定了旧端口，重启出来的替身根本收不到请求， 测试会「通过」但其实什么都没验到。
   */
  static volatile boolean warehouseDown = false;

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

      // 8.3.3.2 的只读替身：列表（?status=COMPLETED）与详情两个形状。
      // 只读端点**不返回租户上下文**，租户仍由 trade 侧已认证的 bearer 决定 ——
      // 这正是「两道 RLS」而不是「替对方决定租户」的意思。
      warehouse = HttpServer.create(new InetSocketAddress(0), 0);
      warehouse.createContext(
          "/warehouse/packing-orders",
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
            if ("/warehouse/packing-orders".equals(path)) {
              List<Map<String, Object>> items = new ArrayList<>();
              for (var row : packingOrders.values()) {
                // 列表端点不返回包裹读数（与 8.3.3.2 的真实形状一致），
                // 所以 trade 必须逐张取详情才能拿到称重结果。
                var copy = new java.util.LinkedHashMap<>(row);
                copy.remove("parcels");
                items.add(copy);
              }
              send(
                  e, Map.of("items", items, "page", 0, "size", 100, "totalElements", items.size()));
              return;
            }
            String id = path.substring(path.lastIndexOf('/') + 1);
            var row = packingOrders.get(UUID.fromString(id));
            if (row == null) {
              e.sendResponseHeaders(404, -1);
              e.close();
              return;
            }
            send(e, Map.of("packingOrder", row));
          });
      warehouse.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static void send(com.sun.net.httpserver.HttpExchange e, Object payload)
      throws java.io.IOException {
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

  @Autowired PendingShipmentService service;
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
    packingOrders.clear();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate shipment_event,shipment_waybill,shipment_batch_task_item,"
              + " shipment_batch_task,shipment_pending_parcel,shipment_pending_unit cascade");
    }
  }

  // ==========================================================================
  // 夹具：warehouse 侧一张已完成打包的出库单
  // ==========================================================================

  /** 往 8.3.3.2 的只读替身里放一张已完成打包的打包单。 */
  UUID upstreamPacking(
      UUID outboundId, String outboundNo, UUID warehouseId, String warehouseRef, int parcels) {
    UUID packingId = UUID.randomUUID();
    List<Map<String, Object>> parcelList = new ArrayList<>();
    for (int i = 1; i <= parcels; i++) {
      parcelList.add(
          Map.of(
              "id",
              UUID.randomUUID(),
              "parcelNo",
              i,
              "status",
              "PACKED",
              "grossWeightKg",
              new BigDecimal("1.250000").add(new BigDecimal(i)),
              "lengthMm",
              new BigDecimal("300.000000"),
              "widthMm",
              new BigDecimal("200.000000"),
              "heightMm",
              new BigDecimal("150.000000"),
              "labelPrinted",
              false,
              "labelJobRef",
              ""));
    }
    packingOrders.put(
        packingId,
        new java.util.LinkedHashMap<>(
            Map.of(
                "id", packingId,
                "outboundOrderId", outboundId,
                "outboundOrderNo", outboundNo,
                "warehouseId", warehouseId,
                "warehouseRef", warehouseRef,
                "status", "COMPLETED",
                "completedAt", OffsetDateTime.now().minusHours(6).toString(),
                "parcels", parcelList)));
    return packingId;
  }

  PendingShipmentSyncInput syncInput(String carrierCode, List<CarrierAssignment> carriers) {
    return new PendingShipmentSyncInput(null, "THIRD_PARTY", carrierCode, carriers, 100);
  }

  CarrierAssignment assignment(UUID outboundId, String no) {
    return new CarrierAssignment(outboundId, no);
  }

  /**
   * 同步一次并返回该租户当前的全部待发货单元。
   *
   * <p>每次调用先<b>清空</b>上游替身的内容：真实世界里 8.3.3.2 返回的是「当前」 的已完成打包单，两次同步之间上游不会凭空多出上一批货。如果不���空，
   * 第二次同步会把上一批也拉回来、并且用这次的默认承运商把它们**覆盖**掉 —— 那样测出来的「两个承运商两组」是假的（实际上只剩一组）。
   */
  List<PendingShipmentUnitRow> seedUnits(String carrierCode, int count) {
    packingOrders.clear();
    List<CarrierAssignment> carriers = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      UUID outbound = UUID.randomUUID();
      String no = "OB-" + carrierCode + "-" + i;
      upstreamPacking(outbound, no, whA, "WH-SH-01", 1 + (i % 2));
      carriers.add(assignment(outbound, no));
    }
    asA(() -> service.sync(syncInput(carrierCode, carriers), user, "Bearer tenant-a"));
    return asA(() -> service.units(null, null, null, null, null, null, 0, 100)).items();
  }

  // ==========================================================================
  // 数据来源：授权 API，仓库不可达时显式失败而不是空清单
  // ==========================================================================

  @Test
  void 同步_从授权接口读入并落库_幂等() {
    var outbound = UUID.randomUUID();
    var packingId = upstreamPacking(outbound, "OB-SYNC-1", whA, "WH-SH-01", 2);

    var first =
        asA(
            () ->
                service.sync(
                    syncInput("SF", List.of(assignment(outbound, "OB-SYNC-1"))),
                    user,
                    "Bearer tenant-a"));
    assertThat(first.insertedUnits()).isEqualTo(1);
    assertThat(first.updatedUnits()).isZero();
    assertThat(first.parcelRowsUpserted()).isEqualTo(2);

    // 包裹读数真的落库了（重量不是 0、尺寸不是空）
    var units = asA(() -> service.units(null, null, null, null, null, null, 0, 50)).items();
    assertThat(units).hasSize(1);
    assertThat(units.get(0).sourcePackingOrderId()).isEqualTo(packingId);
    assertThat(units.get(0).parcelCount()).isEqualTo(2);
    assertThat(units.get(0).totalWeightKg()).isGreaterThan(new BigDecimal("2"));

    var detail = asA(() -> service.unit(units.get(0).id().toString()));
    assertThat(detail.parcels()).hasSize(2);
    assertThat(detail.parcels())
        .allSatisfy(
            p -> {
              assertThat(p.grossWeightKg()).isGreaterThan(BigDecimal.ZERO);
              assertThat(p.lengthMm()).isEqualByComparingTo(new BigDecimal("300.000000"));
              assertThat(p.labelPrinted()).isFalse();
            });

    // 幂等：再同步一次是更新，不是插第二行
    var second =
        asA(
            () ->
                service.sync(
                    syncInput("SF", List.of(assignment(outbound, "OB-SYNC-1"))),
                    user,
                    "Bearer tenant-a"));
    assertThat(second.insertedUnits()).isZero();
    assertThat(second.updatedUnits()).isEqualTo(1);
    assertThat(asA(() -> service.units(null, null, null, null, null, null, 0, 50)).items())
        .hasSize(1);
  }

  @Test
  void 同步_仓库不可达时显式失败_绝不返回空清单冒充没有待发货() {
    var outbound = UUID.randomUUID();
    upstreamPacking(outbound, "OB-DOWN", whA, "WH-SH-01", 1);
    warehouseDown = true;
    try {
      assertThatThrownBy(
              () ->
                  asA(
                      () ->
                          service.sync(
                              syncInput("SF", List.of(assignment(outbound, "OB-DOWN"))),
                              user,
                              "Bearer tenant-a")))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("暂时");
      // 一行都没落：空清单会被现场当成「今天没有待发货的货」
      assertThat(asA(() -> service.units(null, null, null, null, null, null, 0, 50)).items())
          .isEmpty();
    } finally {
      warehouseDown = false;
    }
  }

  // ==========================================================================
  // 四个分组维度：真 GROUP BY
  // ==========================================================================

  @Test
  void 看板_按承运商分组是真聚合_混进别的承运商会多出一组() {
    seedUnits("SF", 2);
    seedUnits("DHL", 3);

    var board =
        asA(() -> service.board(null, null, null, null, null, PendingShipmentGroupBy.CARRIER));
    assertThat(board.groups())
        .extracting(PendingShipmentGroup::groupKey)
        .containsExactlyInAnyOrder("SF", "DHL");
    assertThat(board.groups())
        .filteredOn(g -> g.groupKey().equals("DHL"))
        .singleElement()
        .satisfies(g -> assertThat(g.unitCount()).isEqualTo(3));
    // 汇总不受分页影响，且是全量
    assertThat(board.summary().totalUnits()).isEqualTo(5);
  }

  @Test
  void 看板_四个分组维度都可用且落在真实列上() {
    seedUnits("SF", 2);

    var byWarehouse =
        asA(() -> service.board(null, null, null, null, null, PendingShipmentGroupBy.WAREHOUSE));
    assertThat(byWarehouse.groups())
        .extracting(PendingShipmentGroup::groupLabel)
        .contains("WH-SH-01");

    var byChannel =
        asA(
            () ->
                service.board(
                    null, null, null, null, null, PendingShipmentGroupBy.CARRIER_CHANNEL));
    assertThat(byChannel.groups())
        .extracting(PendingShipmentGroup::groupKey)
        .contains("THIRD_PARTY");

    // 未登记时限的单元必须落在 UNSET 桶，而不是被混进「今天到期」
    var bySla =
        asA(() -> service.board(null, null, null, null, null, PendingShipmentGroupBy.SLA_DEADLINE));
    assertThat(bySla.groups()).extracting(PendingShipmentGroup::groupKey).contains("UNSET");
    assertThat(bySla.summary().unsetSlaUnits()).isEqualTo(2);
    assertThat(bySla.summary().dueTodayUnits()).isZero();

    // 分组筛选与列表筛选口径一致
    var filtered =
        asA(() -> service.board(null, null, "SF", null, null, PendingShipmentGroupBy.CARRIER));
    assertThat(filtered.summary().totalUnits()).isEqualTo(2);
  }

  // ==========================================================================
  // 发货时限登记：登记而不伪造，且时间零漂移
  // ==========================================================================

  @Test
  void 发货时限_登记后落进正确的时间桶_时间零漂移() throws Exception {
    var unit = seedUnits("SF", 1).get(0);
    // End of the database UTC day, with PostgreSQL-compatible microsecond precision.
    OffsetDateTime deadline =
        OffsetDateTime.now(java.time.ZoneOffset.UTC)
            .withHour(23)
            .withMinute(59)
            .withSecond(59)
            .withNano(999_000_000);
    var registered =
        asA(
            () ->
                service.registerSla(
                    unit.id().toString(),
                    new ShipmentSlaInput(deadline, ShipmentSlaSource.MANUAL_ATTESTATION, "平台后台截图"),
                    user));

    assertThat(registered.slaSource()).isEqualTo("MANUAL_ATTESTATION");
    // 断言写回的值本身（不是「没抛异常」）：timestamptz + OffsetDateTime 必须零漂移
    assertThat(registered.slaDeadline()).isEqualTo(deadline);

    // 同一个瞬间写回 DB 再读，仍是同一个瞬间
    var fromDb =
        asA(
            () ->
                sql.fetchOne(
                        "select sla_deadline_at from shipment_pending_unit where id = ?", unit.id())
                    .get(0, OffsetDateTime.class));
    assertThat(fromDb.toInstant()).isEqualTo(deadline.toInstant());

    var board =
        asA(() -> service.board(null, null, null, null, null, PendingShipmentGroupBy.SLA_DEADLINE));
    assertThat(board.groups()).extracting(PendingShipmentGroup::groupKey).contains("DUE_TODAY");
    assertThat(board.summary().unsetSlaUnits()).isZero();

    // 缺依据的登记被拒（校验在控制器层，走真实 HTTP）
    http.perform(
            post("/trade/fulfillment/pending-shipment/units/{id}/sla", unit.id())
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"deadlineAt\":\"2030-01-01T00:00:00+08:00\","
                        + "\"source\":\"PLATFORM_FEED\",\"note\":\"x\"}"))
        .andExpect(status().is4xxClientError());
  }

  // ==========================================================================
  // 批量任务：一次不许跨承运商
  // ==========================================================================

  @Test
  void 批量任务_跨承运商当场拒_并列出冲突承运商() {
    seedUnits("SF", 2);
    seedUnits("DHL", 1);
    // 一张 SF + 一张 DHL 混在一起建任务
    var all = asA(() -> service.units(null, null, null, null, null, null, 0, 50)).items();
    var mixed =
        List.of(
            all.stream().filter(r -> r.carrierCode().equals("SF")).findFirst().orElseThrow().id(),
            all.stream().filter(r -> r.carrierCode().equals("DHL")).findFirst().orElseThrow().id());

    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.createTask(
                            new ShipmentBatchTaskInput(
                                ShipmentBatchTaskType.HANDOVER, mixed, null, null, null, "大促交接"),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("跨承运商")
        .hasMessageContaining("SF")
        .hasMessageContaining("DHL");
    assertThat(asA(() -> service.tasks(null, null, null, null, 0, 50)).items()).isEmpty();
  }

  @Test
  void 批量任务_承运商为空的单元进不了任务() {
    // 不同步承运商 → 单元 carrierCode 为空
    var outbound = UUID.randomUUID();
    upstreamPacking(outbound, "OB-NOCARRIER", whA, "WH-SH-01", 1);
    asA(
        () ->
            service.sync(
                new PendingShipmentSyncInput(
                    null, null, "", List.of(assignment(outbound, "OB-NOCARRIER")), 100),
                user,
                "Bearer tenant-a"));
    var unit = asA(() -> service.units(null, null, null, null, null, null, 0, 50)).items().get(0);
    assertThat(unit.carrierCode()).isEmpty();

    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.createTask(
                            new ShipmentBatchTaskInput(
                                ShipmentBatchTaskType.HANDOVER,
                                List.of(unit.id()),
                                null,
                                null,
                                "SF",
                                "大促交接"),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("还没选承运商");
  }

  // ==========================================================================
  // 批量申请运单号：真任务 + 逐条结果 + 承运商回执
  // ==========================================================================

  @Test
  void 批量申请运单号_任务真落库且逐条有结果_回执后才推单元状态() {
    var units = seedUnits("SF", 2);
    var task =
        asA(
            () ->
                service.createTask(
                    new ShipmentBatchTaskInput(
                        ShipmentBatchTaskType.WAYBILL_REQUEST,
                        units.stream().map(PendingShipmentUnitRow::id).toList(),
                        null,
                        ShipmentCarrierChannel.THIRD_PARTY,
                        "SF",
                        "大促批量取号"),
                    user));
    assertThat(task.task().taskNo()).startsWith("SBT-");
    assertThat(task.task().status()).isEqualTo(ShipmentBatchStatus.PENDING);
    assertThat(task.items()).hasSize(2);

    var executed = asA(() -> service.executeTask(task.task().id().toString(), user));
    assertThat(executed.task().status()).isEqualTo(ShipmentBatchStatus.SUCCEEDED);
    assertThat(executed.items())
        .allSatisfy(i -> assertThat(i.status()).isEqualTo(ShipmentTaskItemStatus.SUCCEEDED));
    // 运单是 REQUESTED（还没有单号）：不伪造已签发
    var waybills = asA(() -> service.unit(units.get(0).id().toString()).waybills());
    assertThat(waybills)
        .singleElement()
        .satisfies(
            w -> {
              assertThat(w.status()).isEqualTo(ShipmentWaybillStatus.REQUESTED);
              assertThat(w.waybillNo()).isEmpty();
            });
    // 单元还没推进：没有单号就不许标成「已申请运单号」
    assertThat(asA(() -> service.unit(units.get(0).id().toString())).unit().status())
        .isEqualTo(PendingShipmentStatus.PENDING);

    // 登记承运商回执 → 单元才推进
    var acked =
        asA(
            () ->
                service.recordWaybillAck(
                    task.task().id().toString(),
                    units.get(0).id(),
                    new ShipmentWaybillAckInput(
                        ShipmentWaybillStatus.ISSUED, "SF1234567890", "req-1", ""),
                    user));
    assertThat(acked).isNotNull();
    assertThat(asA(() -> service.unit(units.get(0).id().toString())).unit().status())
        .isEqualTo(PendingShipmentStatus.WAYBILL_REQUESTED);
    assertThat(asA(() -> service.unit(units.get(0).id().toString())).unit().waybillNo())
        .isEqualTo("SF1234567890");

    // 终态不可重复执行
    assertThatThrownBy(() -> asA(() -> service.executeTask(task.task().id().toString(), user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("终态");
  }

  // ==========================================================================
  // 批量打单：逐包裹，缺凭据必须显式 SKIPPED
  // ==========================================================================

  @Test
  void 批量打单_逐包裹建行_没凭据时显式跳过而不是伪装成功() {
    var units = seedUnits("SF", 1);
    var unitId = units.get(0).id();
    var parcels = asA(() -> service.unit(unitId.toString())).parcels();
    assertThat(parcels).hasSizeGreaterThanOrEqualTo(1);

    var task =
        asA(
            () ->
                service.createTask(
                    new ShipmentBatchTaskInput(
                        ShipmentBatchTaskType.LABEL_PRINT,
                        List.of(unitId),
                        null,
                        ShipmentCarrierChannel.THIRD_PARTY,
                        "SF",
                        "大促批量打单"),
                    user));
    // 打单的对象是**包裹**：一票几个箱子就有几条
    assertThat(task.items()).hasSize(parcels.size());
    assertThat(task.task().unitCount()).isEqualTo(parcels.size());

    var executed = asA(() -> service.executeTask(task.task().id().toString(), user));
    assertThat(executed.items())
        .allSatisfy(
            i -> {
              assertThat(i.status()).isEqualTo(ShipmentTaskItemStatus.SKIPPED);
              assertThat(i.detail()).contains("8.3.4.2");
            });
    // 有跳过就不许是 SUCCEEDED
    assertThat(executed.task().status()).isNotEqualTo(ShipmentBatchStatus.SUCCEEDED);
    assertThat(asA(() -> service.unit(unitId.toString())).unit().status())
        .isEqualTo(PendingShipmentStatus.PENDING);

    // 回填真实打印凭据后重跑才成功
    var parcel = parcels.get(0);
    asA(
        () ->
            service.recordLabelPrinted(
                task.task().id().toString(),
                unitId,
                parcel.id(),
                new PendingShipmentLabelInput("job-8f21c"),
                user));
    assertThat(asA(() -> service.unit(unitId.toString())).parcels())
        .filteredOn(p -> p.id().equals(parcel.id()))
        .singleElement()
        .satisfies(
            p -> {
              assertThat(p.labelPrinted()).isTrue();
              assertThat(p.labelPrintedAt()).isNotNull();
              assertThat(p.labelJobRef()).isEqualTo("job-8f21c");
            });
  }

  @Test
  void 批量打单_包裹都打过就不该再建任务() {
    var units = seedUnits("SF", 1);
    var unitId = units.get(0).id();
    var parcels = asA(() -> service.unit(unitId.toString())).parcels();
    var task =
        asA(
            () ->
                service.createTask(
                    new ShipmentBatchTaskInput(
                        ShipmentBatchTaskType.LABEL_PRINT,
                        List.of(unitId),
                        null,
                        ShipmentCarrierChannel.THIRD_PARTY,
                        "SF",
                        "重复打单检查"),
                    user));
    for (var p : parcels) {
      asA(
          () ->
              service.recordLabelPrinted(
                  task.task().id().toString(),
                  unitId,
                  p.id(),
                  new PendingShipmentLabelInput("job-1"),
                  user));
    }
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.createTask(
                            new ShipmentBatchTaskInput(
                                ShipmentBatchTaskType.LABEL_PRINT,
                                List.of(unitId),
                                null,
                                null,
                                "SF",
                                "重复打单检查"),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("都已经打过单");
  }

  // ==========================================================================
  // 批量交接：本模块最远态，**不是已发货**
  // ==========================================================================

  @Test
  void 批量交接_推到HANDED_OVER为止_本模块产不出已发货() {
    var units = seedUnits("SF", 2);
    var task =
        asA(
            () ->
                service.createTask(
                    new ShipmentBatchTaskInput(
                        ShipmentBatchTaskType.HANDOVER,
                        units.stream().map(PendingShipmentUnitRow::id).toList(),
                        whA,
                        ShipmentCarrierChannel.THIRD_PARTY,
                        "SF",
                        "大促批量交接"),
                    user));
    var executed = asA(() -> service.executeTask(task.task().id().toString(), user));
    assertThat(executed.task().status()).isEqualTo(ShipmentBatchStatus.SUCCEEDED);
    assertThat(executed.items())
        .allSatisfy(i -> assertThat(i.status()).isEqualTo(ShipmentTaskItemStatus.SUCCEEDED));

    for (var u : units) {
      var row = asA(() -> service.unit(u.id().toString())).unit();
      assertThat(row.status()).isEqualTo(PendingShipmentStatus.HANDED_OVER);
      assertThat(row.handedOverAt()).isNotNull();
    }

    // DB 层的状态机拒绝「已交接 → 已发货」这类越界推进
    assertDbRefused(
        () ->
            asA(
                () ->
                    sql.execute(
                        "update shipment_pending_unit set status='SHIPPED' where id = ?",
                        units.get(0).id())),
        "不产生已发货",
        "23514");
  }

  @Test
  void 已交接的单元不能再进任务_作废必须给原因() {
    var units = seedUnits("SF", 1);
    var task =
        asA(
            () ->
                service.createTask(
                    new ShipmentBatchTaskInput(
                        ShipmentBatchTaskType.HANDOVER,
                        List.of(units.get(0).id()),
                        whA,
                        null,
                        "SF",
                        "第一次交接"),
                    user));
    asA(() -> service.executeTask(task.task().id().toString(), user));

    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.createTask(
                            new ShipmentBatchTaskInput(
                                ShipmentBatchTaskType.HANDOVER,
                                List.of(units.get(0).id()),
                                whA,
                                null,
                                "SF",
                                "重复交接检查"),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已交接");

    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.cancelUnit(
                            units.get(0).id().toString(), new ShipmentCancelInput("太短"), user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已交接");
  }

  // ==========================================================================
  // DB 级不变量：绕过服务层写 SQL 同样被拒
  // ==========================================================================

  @Test
  void 任务结束计数必须与逐条一致_绕过服务层写SQL也被拒() {
    var units = seedUnits("SF", 2);
    var task =
        asA(
            () ->
                service.createTask(
                    new ShipmentBatchTaskInput(
                        ShipmentBatchTaskType.HANDOVER,
                        units.stream().map(PendingShipmentUnitRow::id).toList(),
                        whA,
                        null,
                        "SF",
                        "计数一致性检查"),
                    user));
    asA(
        () ->
            sql.execute(
                "update shipment_batch_task set status='RUNNING', started_at = now() where id = ?",
                task.task().id()));

    // 全部成功但逐条还有 PENDING：计数对不上
    assertDbRefused(
        () ->
            asA(
                () ->
                    sql.execute(
                        "update shipment_batch_task set status='SUCCEEDED', succeeded_count = 2,"
                            + " failed_count = 0, skipped_count = 0, finished_at = now() where id = ?",
                        task.task().id())),
        "结束计数",
        "23514");
  }

  @Test
  void 事件流是append_only凭据_运行角色被拒且触发器不是死代码() throws Exception {
    seedUnits("SF", 1);
    var task =
        asA(
            () ->
                service.createTask(
                    new ShipmentBatchTaskInput(
                        ShipmentBatchTaskType.WAYBILL_REQUEST,
                        asA(() -> service.units(null, null, null, null, null, null, 0, 50))
                            .items()
                            .stream()
                            .map(PendingShipmentUnitRow::id)
                            .toList(),
                        null,
                        null,
                        "SF",
                        "凭据append-only检查"),
                    user));
    // 第 1 层：运行角色已被 REVOKE
    assertDbRefused(
        () ->
            asA(
                () ->
                    sql.execute(
                        "update shipment_event set detail = '改得好看一点' where task_id = ?",
                        task.task().id())),
        "",
        "42501",
        "23514");
    // 先断言这条凭据行**确实存在**：否则「删不掉」可能只是因为压根没这一行，
    // 那样这条断言就什么都没验到。
    assertThat(
            asA(
                () ->
                    countOfHelper(
                        "select count(*) from shipment_event where task_id = ?", task.task().id())))
        .as("被当作凭据保护的事件行必须真实存在")
        .isEqualTo(1);
    // 注意 {@code asTableOwner(...)} 只是**造**出一条待执行的语句，
    // 不调 .run() 的话这段什么都不会跑 —— 断言会「通过」而其实什么都没验。
    assertDbRefused(
        () ->
            asTableOwner("delete from shipment_event where task_id = '" + task.task().id() + "'")
                .run(),
        "append-only",
        "23514");
  }

  @Test
  void 六张表逐张强制RLS且starcon_app拿不到() throws Exception {
    var tables =
        List.of(
            "shipment_pending_unit",
            "shipment_pending_parcel",
            "shipment_batch_task",
            "shipment_batch_task_item",
            "shipment_waybill",
            "shipment_event");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      for (var t : tables) {
        try (var rs =
            s.executeQuery(
                "select relforcerowsecurity from pg_class where relname = '" + t + "'")) {
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
  void 真分页且汇总不受分页影响() {
    seedUnits("SF", 5);
    var firstPage = asA(() -> service.units(null, null, null, null, null, null, 0, 2));
    assertThat(firstPage.items()).hasSize(2);
    assertThat(firstPage.totalElements()).isEqualTo(5);
    assertThat(firstPage.totalPages()).isEqualTo(3);
    assertThat(firstPage.summary().totalUnits()).as("汇总不受分页影响").isEqualTo(5);

    var secondPage = asA(() -> service.units(null, null, null, null, null, null, 1, 2));
    assertThat(secondPage.items()).hasSize(2);
    assertThat(secondPage.items())
        .extracting(PendingShipmentUnitRow::id)
        .doesNotContainAnyElementsOf(
            firstPage.items().stream().map(PendingShipmentUnitRow::id).toList());

    // 关键字筛选是真筛选
    var all = asA(() -> service.units(null, null, null, null, null, null, 0, 50)).items();
    var keyword = all.get(0).outboundOrderNo();
    assertThat(asA(() -> service.units(null, null, null, null, keyword, null, 0, 50)).items())
        .hasSize(1);
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    seedUnits("SF", 2);
    seedUnits("DHL", 1);

    var pageA = asA(() -> service.units(null, null, null, null, null, null, 0, 50));
    var pageB = asB(() -> service.units(null, null, null, null, null, null, 0, 50));
    assertThat(pageA.items()).isNotEmpty();
    assertThat(pageB.items()).as("B 租户一条都看不到").isEmpty();

    var boardA =
        asA(() -> service.board(null, null, null, null, null, PendingShipmentGroupBy.CARRIER));
    var boardB =
        asB(() -> service.board(null, null, null, null, null, PendingShipmentGroupBy.CARRIER));
    assertThat(boardB.groups()).isEmpty();
    assertThat(boardB.summary().totalUnits()).isZero();
    assertThat(boardA.summary().totalUnits()).isEqualTo(3);

    var unitA = pageA.items().get(0).id();
    assertThatThrownBy(() -> asB(() -> service.unit(unitA.toString())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在");

    // 批量任务与运单同样隔离：B 看不到 A 的任务
    assertThat(asB(() -> service.tasks(null, null, null, null, 0, 50)).items()).isEmpty();
    assertThatThrownBy(
            () ->
                asB(
                    () ->
                        service.createTask(
                            new ShipmentBatchTaskInput(
                                ShipmentBatchTaskType.HANDOVER,
                                List.of(unitA),
                                whA,
                                null,
                                "SF",
                                "跨租户尝试"),
                            user)))
        .isInstanceOf(ResponseStatusException.class);
  }

  // ==========================================================================
  // 断言 2：伪造租户上下文无效
  // ==========================================================================
  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    seedUnits("SF", 1);
    var unitId =
        asA(() -> service.units(null, null, null, null, null, null, 0, 50)).items().get(0).id();

    // (1) 事务外没有「环境租户」：任何租户的行都看不见
    assertThat(sql.fetch("select id from shipment_pending_unit").size()).isZero();

    // B 的会话 + 伪造指向 A 的头 → 依然 404
    http.perform(
            get("/trade/fulfillment/pending-shipment/units/{id}", unitId)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    // A 的会话 + 伪造指向 B 的头 → 仍按 A 解析
    http.perform(
            get("/trade/fulfillment/pending-shipment/units/{id}", unitId)
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isOk());

    // 写入路径同样不认头：B 会话伪造 A 的头，落库仍属 B
    http.perform(
            post("/trade/fulfillment/pending-shipment/units/{id}/sla", unitId)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"deadlineAt":"2030-01-01T00:00:00+08:00","source":"MANUAL_ATTESTATION","note":"伪造头的登记"}"""))
        .andExpect(status().isNotFound());
    var inA =
        asA(
            () ->
                sql.fetchOne("select sla_note from shipment_pending_unit where id = ?", unitId)
                    .get(0, String.class));
    assertThat(inA).as("伪造头不得把数据写进别的租户").isEqualTo("");

    // (2) 提交后租户上下文必须已清空
    assertThat(sql.fetch("select id from shipment_pending_unit").size()).isZero();
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    seedUnits("SF", 1);
    // 池大小为 1：下面每次读写都复用同一条连接
    for (int i = 0; i < 5; i++) {
      assertThat(asA(() -> service.units(null, null, null, null, null, null, 0, 50)).items())
          .as("第 %d 轮 A", i)
          .isNotEmpty();
      assertThat(asB(() -> service.units(null, null, null, null, null, null, 0, 50)).items())
          .as("第 %d 轮 B", i)
          .isEmpty();
      assertThat(sql.fetch("select id from shipment_pending_unit").size())
          .as("第 %d 轮事务外", i)
          .isZero();
    }
  }

  // ==========================================================================
  // HTTP 边界
  // ==========================================================================
  @Test
  void 能力声明如实报出上游缺口() throws Exception {
    http.perform(
            get("/trade/fulfillment/pending-shipment/capabilities")
                .header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions.length()").value(5))
        .andExpect(jsonPath("$.dimensions[0].dimension").value("PACKING_RESULT"))
        .andExpect(jsonPath("$.dimensions[0].available").value(true))
        .andExpect(jsonPath("$.groupByOptions.length()").value(4))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("PLATFORM_SLA")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("2.5.1.1")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("8.3.4.1")));
  }

  @Test
  void 未认证与只读角色的写操作被拒() throws Exception {
    seedUnits("SF", 1);
    var unitId =
        asA(() -> service.units(null, null, null, null, null, null, 0, 50)).items().get(0).id();
    http.perform(
            post("/trade/fulfillment/pending-shipment/units/{id}/cancel", unitId)
                .header("Authorization", "Bearer read-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"只读角色不该能作废\"}"))
        .andExpect(status().isForbidden());
    http.perform(
            post("/trade/fulfillment/pending-shipment/units/{id}/cancel", unitId)
                .header("Authorization", "Bearer nobody")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"未认证不该能作废\"}"))
        .andExpect(status().isUnauthorized());
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

  long countOfHelper(String text, Object... binds) {
    org.jooq.Record r = sql.fetchOne(text, binds);
    return r == null ? 0L : ((Number) r.get(0)).longValue();
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
    if ("23514".equals(sql.getSQLState())) {
      assertThat(sql.getMessage()).as("拒绝理由必须可读").contains(expectedFragment);
    }
  }

  @FunctionalInterface
  interface OwnerSql {
    void run() throws Exception;
  }

  /**
   * 以**表所有者**身份直连执行一条 SQL。
   *
   * <p>存在的理由：append-only 有两层（对运行角色 REVOKE 权限 42501， 以及 BEFORE 触发器 23514）。只测前者的话触发器就成了永远跑不到的死代码。
   * FORCE RLS 对 owner 同样生效，所以仍要显式绑定 {@code app.current_tenant}。
   */
  static OwnerSql asTableOwner(String sqlText) {
    return () -> {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var st = c.createStatement()) {
        st.execute("select set_config('app.current_tenant','" + a + "',false)");
        st.execute(sqlText);
      }
    };
  }
}
