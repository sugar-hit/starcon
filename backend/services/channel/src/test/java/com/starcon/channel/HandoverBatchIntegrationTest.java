package com.starcon.channel;

import static com.starcon.channel.HandoverBatchModels.*;
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
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 10.3.2.1 交运批次。
 *
 * <p>本测试类钉四组事：
 *
 * <ol>
 *   <li><b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：两租户互不可见、
 *       伪造 tenant header 无效、连接池归还后不串租户
 *   <li><b>🔴 交接清单是法律凭据</b>（蓝图加粗句「批次交接是责任分界点：批次内包裹的
 *       丢失责任在承运商，需要交接清单留证」）：append-only 双保险
 *       （REVOKE + 触发器各试一次）、已交接批次的成员不可被静默摘出
 *   <li><b>组批不变量</b>：一条批次不许跨承运商（DB 触发器）、批次头计数不接受
 *       调用方指定（DB 触发器复查逐件清单）
 *   <li><b>运单号缺口如实留空</b>：10.2.1.2 未交付时不拿出库单号冒充运单号
 * </ol>
 *
 * <p><b>时间一律 pin 住</b>：交运时刻（{@code HANDOVER_AT}）是夹具里的固定值，
 * 不取墙上时钟，也不依赖 {@code now()} —— 否则「按交运时间分档」的用例
 * 只会在某个钟点通过。写回的时刻<b>断言实际读回的值</b>，不只断言没抛异常。
 */
@SpringBootTest
@AutoConfigureMockMvc
class HandoverBatchIntegrationTest {
  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer trade;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 复用的交运时刻：夹具 pin 住，测试不依赖墙上时钟。 */
  static final OffsetDateTime HANDOVER_AT = OffsetDateTime.parse("2026-10-15T09:30:00Z");
  static final OffsetDateTime PACKED_AT = OffsetDateTime.parse("2026-10-14T18:00:00Z");
  static final OffsetDateTime HANDED_OVER_AT = OffsetDateTime.parse("2026-10-15T07:00:00Z");

  /**
   * 上游 9.4.1.1 的单元夹具：**按 bearer 分租户**（外层 key = 调用方 bearer，内层 key = 上游 unit id）。
   *
   * <p>真实 trade 侧也有自己的 RLS：租户 B 的会话读不到租户 A 的待发货单元。
   * 夹具若不分租户，B 同步时会把 A 的那一票也拉进来，于是「B 的候选池为空」
   * 这类断言就假失败了 —— 而且那不是代码的错，是夹具比真实上游更宽。
   */
  static final Map<String, Map<String, Map<String, Object>>> upstreamByBearer =
      new ConcurrentHashMap<>();
  static final List<String> upstreamBears = new ArrayList<>();

  /** 复用 identity 已注册的 channel 权限码：本模块不新增权限码，identity 服务零改动。 */
  static final List<String> allPermissions =
      java.util.stream.Stream.of("registry", "connection", "shop", "sync", "operation")
          .flatMap(
              p ->
                  java.util.stream.Stream.concat(
                      java.util.stream.Stream.of("read", "create", "update", "delete")
                          .map(op -> "channel." + p + "." + op),
                      p.equals("sync")
                          ? java.util.stream.Stream.of("channel.sync.trigger", "channel.sync.complete")
                          : java.util.stream.Stream.empty()))
          .distinct()
          .toList();

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenant, "Test", UUID.randomUUID(), null,
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
        s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='starcon_app')"
            + " then create role starcon_app nologin; end if; end $$");
        s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='channel_app')"
            + " then create role channel_app login password 'test-only' nosuperuser nobypassrls; end if; end $$");
        s.execute("alter role channel_app login password 'test-only' nosuperuser nobypassrls");
      }
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            AccessService.Access who = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (who == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = new ObjectMapper().writeValueAsBytes(who);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
      actors.put("Bearer tenant-a", actor(user, a, grants));
      actors.put("Bearer tenant-b", actor(user, b, grants));
      identity.start();

      // ---- 上游 9.4.1.1 的**真实 API 形状**（不是内存里造对象）----
      // 端口 PendingShipmentQueryPort 真的发 HTTP 过来，所以这里必须在 HTTP 层
      // 断言它转发了调用方的 bearer —— 那是租户隔离「两道」的第一道。
      trade = HttpServer.create(new InetSocketAddress(0), 0);
      trade.createContext(
          "/trade/fulfillment/pending-shipment/units",
          e -> {
            String bear = e.getRequestHeaders().getFirst("Authorization");
            if (bear != null) {
              upstreamBears.add(bear);
            }
            // 列表与详情是**两个形状**：列表是分页信封，详情是单头 + 逐件（与 trade 同形）。
            String id = pathTail(e.getRequestURI().getPath());
            // 真实 trade 侧也按已认证成员关系解析租户并强制 RLS：
            // 拿 B 的会话读不到 A 的单元，夹具必须同形，否则断言测的是夹具而不是代码。
            Map<String, Map<String, Object>> mine =
                upstreamByBearer.getOrDefault(String.valueOf(bear), Map.of());
            Object payload;
            if (id != null) {
              Map<String, Object> unit = mine.get(id);
              if (unit == null) {
                e.sendResponseHeaders(404, -1);
                e.close();
                return;
              }
              Map<String, Object> detail = new java.util.LinkedHashMap<>();
              detail.put("unit", unitRow(unit));
              detail.put("parcels", unit.get("parcels"));
              detail.put("waybills", unit.get("waybills"));
              payload = detail;
            } else {
              List<Object> items = new ArrayList<>();
              for (Map<String, Object> unit : mine.values()) {
                items.add(unitRow(unit));
              }
              Map<String, Object> page = new java.util.LinkedHashMap<>();
              page.put("items", items);
              page.put("page", 0);
              page.put("size", 200);
              page.put("totalElements", items.size());
              payload = page;
            }
            byte[] body = new ObjectMapper().writeValueAsBytes(payload);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      trade.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** 取路径最后一段（列表路径返回 null）。 */
  static String pathTail(String path) {
    var parts = path.split("/");
    String last = parts[parts.length - 1];
    return last.equals("units") ? null : last;
  }

  /** 上游单元单头（列表与详情共用同一条形状）。 */
  static Map<String, Object> unitRow(Map<String, Object> unit) {
    Map<String, Object> row = new java.util.LinkedHashMap<>();
    row.put("id", unit.get("id"));
    row.put("sourcePackingOrderId", unit.get("sourcePackingOrderId"));
    row.put("warehouseRef", unit.get("warehouseRef"));
    row.put("outboundOrderNo", unit.get("outboundOrderNo"));
    row.put("salesOrderNo", unit.get("salesOrderNo"));
    row.put("destinationCountry", unit.get("destinationCountry"));
    row.put("carrierCode", unit.get("carrierCode"));
    row.put("carrierChannel", unit.get("carrierChannel"));
    row.put("parcelCount", unit.get("parcelCount"));
    row.put("totalWeightKg", unit.get("totalWeightKg"));
    row.put("status", "HANDED_OVER");
    row.put("waybillNo", unit.get("waybillNo"));
    row.put("packedAt", unit.get("packedAt"));
    row.put("handedOverAt", unit.get("handedOverAt"));
    return row;
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://localhost:" + trade.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired HandoverBatchService handovers;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  AccessService.Access actorA, actorB;

  @BeforeEach
  void setup() {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    upstreamByBearer.clear();
    upstreamBears.clear();
    // 每个用例从空候选池起步：本类的断言大量用**绝对计数**（"同步后 3 条"），
    // 不清就会把上一个用例的残留算进来。用迁移账号（绕过 RLS）清两个租户。
    truncateAll();
  }

  /** 清空本模块四张表（两个租户都清）。凭据表先清，否则批次删不掉。 */
  void truncateAll() {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // CASCADE：V217 的 logistics_pickup_signoff_case 以外键引用 handover_manifest_item /
      // handover_batch，引用方不在同一条 truncate 语句里，PG 会直接拒
      // （cannot truncate a table referenced in a foreign key constraint），级联一并清干净。
      s.execute(
          "truncate handover_event, handover_manifest_item, handover_candidate, handover_batch cascade");
    } catch (java.sql.SQLException e) {
      throw new RuntimeException(e);
    }
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    trade.stop(0);
    pg.stop();
  }

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  // ==================================================================
  // 夹具：上游 9.4.1.1 的一票 HANDED_OVER 单元
  // ==================================================================

  /** 往上游假服务放一票单元。waybill 为空 ⇒ 模拟 10.2.1.2 未交付（无运单号来源）。 */
  void upstreamUnit(String bearer, String carrierCode, int parcels, String weight,
      String waybillNo, String outboundNo) {
    UUID unitId = UUID.randomUUID();
    List<Object> parcelList = new ArrayList<>();
    for (int i = 1; i <= parcels; i++) {
      parcelList.add(Map.of(
          "id", UUID.randomUUID().toString(),
          "parcelNo", i,
          "grossWeightKg", new BigDecimal(weight).divide(new BigDecimal(parcels), 6,
              java.math.RoundingMode.HALF_UP).toString(),
          "lengthMm", "200.000000", "widthMm", "150.000000", "heightMm", "80.000000"));
    }
    List<Object> waybillList =
        waybillNo == null || waybillNo.isEmpty()
            ? List.of()
            : List.of(Map.of(
                "id", UUID.randomUUID().toString(), "carrierCode", carrierCode,
                "status", "ISSUED", "waybillNo", waybillNo, "errorMessage", ""));
    Map<String, Object> unit = new java.util.LinkedHashMap<>();
    unit.put("id", unitId.toString());
    unit.put("sourcePackingOrderId", UUID.randomUUID().toString());
    unit.put("warehouseRef", "WH-SH-01");
    unit.put("outboundOrderNo", outboundNo);
    unit.put("salesOrderNo", "SO-" + outboundNo);
    unit.put("destinationCountry", "US");
    unit.put("carrierCode", carrierCode);
    unit.put("carrierChannel", "THIRD_PARTY");
    unit.put("parcelCount", parcels);
    unit.put("totalWeightKg", weight);
    unit.put("waybillNo", waybillNo == null ? "" : waybillNo);
    unit.put("packedAt", PACKED_AT.toString());
    unit.put("handedOverAt", HANDED_OVER_AT.toString());
    unit.put("parcels", parcelList);
    unit.put("waybills", waybillList);
    upstreamByBearer.computeIfAbsent(bearer, k -> new ConcurrentHashMap<>())
        .put(unitId.toString(), unit);
  }

  /** 同步候选池（走真实 HTTP 端口）。 */
  SyncCandidatesResult sync(AccessService.Access who, String bearer) {
    return as(who, "channel.operation.create",
        () -> handovers.syncCandidates(
            new SyncCandidatesRequest(null, "", "", 0, 200), bearer));
  }

  /** 取一条候选。 */
  HandoverCandidateRow candidate(AccessService.Access who, int index) {
    return as(who, "channel.operation.read", () -> handovers.candidates(null, "", "PENDING", null, 0, 50))
        .items().get(index);
  }

  /** 组一批（逐件清单）。 */
  HandoverBatchDetail batch(AccessService.Access who, String carrierCode, OffsetDateTime at,
      List<ManifestItemInput> items) {
    return as(who, "channel.operation.create",
        () -> handovers.createBatch(new CreateBatchRequest(carrierCode, at, null, "WH-SH-01", items),
            who.membershipId()));
  }

  List<ManifestItemInput> manifestOf(HandoverCandidateRow c, int parcels, String waybillNo) {
    List<ManifestItemInput> items = new ArrayList<>();
    for (int i = 1; i <= parcels; i++) {
      items.add(new ManifestItemInput(
          c.id(), i, "BOX-" + c.outboundOrderNo() + "-" + i,
          waybillNo,
          waybillNo == null || waybillNo.isEmpty() ? "UPSTREAM_NOT_ISSUED" : "",
          waybillNo == null || waybillNo.isEmpty() ? "上游尚未签发运单号（10.2.1.2 未交付）" : "",
          new BigDecimal("1.500000"), new BigDecimal("200.000000"),
          new BigDecimal("150.000000"), new BigDecimal("80.000000"),
          "逐件实测"));
    }
    return items;
  }

  // ==================================================================
  // 端到端：同步 → 组批 → 交接 → 回执
  // ==================================================================

  @Test
  @DisplayName("10.3.2.1 端到端：拉上游 HANDED_OVER → 按承运商组批 → 登记交接 → 带回回执")
  void endToEnd() {
    upstreamUnit("Bearer tenant-a", "DHL_EXPRESS", 2, "3.000000", "WB-1001", "OUT-1");
    upstreamUnit("Bearer tenant-a", "DHL_EXPRESS", 1, "1.200000", "WB-1002", "OUT-2");
    upstreamUnit("Bearer tenant-a", "FEDEX", 1, "2.500000", "WB-2001", "OUT-3");

    SyncCandidatesResult synced = sync(actorA, "Bearer tenant-a");
    assertThat(synced.fetched()).isEqualTo(3);
    assertThat(synced.created()).isEqualTo(3);

    // 候选池按承运商真聚合
    var summary = as(actorA, "channel.operation.read", handovers::candidateSummary);
    assertThat(summary.pendingCount()).isEqualTo(3);
    assertThat(summary.byCarrier()).hasSize(2);
    assertThat(summary.byCarrier())
        .filteredOn(g -> g.carrierCode().equals("DHL_EXPRESS"))
        .singleElement()
        .satisfies(g -> {
          assertThat(g.unitCount()).isEqualTo(2);
          assertThat(g.parcelCount()).isEqualTo(3);
          assertThat(g.totalWeightKg()).isEqualByComparingTo("4.200000");
          // 分档 = handed_over_at 的 UTC 日期（pin 住的时刻，不取墙上时钟）
          assertThat(g.handoverBucket().toString()).isEqualTo("2026-10-15");
        });

    // 组批：只把 DHL 的两票组一批（一条批次不许跨承运商）
    HandoverCandidateRow first = candidate(actorA, 0);
    HandoverCandidateRow second = candidate(actorA, 1);
    assertThat(first.carrierCode()).isEqualTo(second.carrierCode());
    List<ManifestItemInput> items = new ArrayList<>();
    items.addAll(manifestOf(first, 2, "WB-1001"));
    items.addAll(manifestOf(second, 1, "WB-1002"));

    HandoverBatchDetail created = batch(actorA, "DHL_EXPRESS", HANDOVER_AT, items);
    assertThat(created.batch().batchNo()).startsWith("HNB-20261015");
    assertThat(created.batch().status()).isEqualTo("DRAFT");
    assertThat(created.batch().unitCount()).isEqualTo(2);
    assertThat(created.batch().parcelCount()).isEqualTo(3);
    assertThat(created.batch().handoverBucket().toString()).isEqualTo("2026-10-15");
    // 🔴 写回的时刻断言实际读回的值（timestamptz 绑定没有静默偏 8 小时）
    assertThat(created.batch().handoverAt()).isEqualTo(HANDOVER_AT);
    assertThat(created.manifest()).hasSize(3);
    assertThat(created.manifest())
        .allSatisfy(m -> assertThat(m.handoverAt()).isEqualTo(HANDOVER_AT));

    // 登记交接 → DISPATCH_PENDING（承运商通道未接通，如实停在待接通）
    HandoverBatchDetail dispatched = as(actorA, "channel.operation.update",
        () -> handovers.dispatch(created.batch().id(),
            new DispatchRequest(HANDOVER_AT, "已交给 DHL 现场"), actorA.membershipId()));
    assertThat(dispatched.batch().status()).isEqualTo("DISPATCH_PENDING");
    assertThat(dispatched.batch().dispatchedAt()).isEqualTo(HANDOVER_AT);
    assertThat(dispatched.batch().dispatchedByMembershipId()).isEqualTo(actorA.membershipId());

    // 带回回执（ACCEPTED）→ HANDED_OVER，责任分界点已过
    HandoverBatchDetail receipted = as(actorA, "channel.operation.update",
        () -> handovers.recordReceipt(created.batch().id(),
            new ReceiptRequest("RCPT-7788", "ACCEPTED", "", HANDOVER_AT), actorA.membershipId()));
    assertThat(receipted.batch().status()).isEqualTo("HANDED_OVER");
    assertThat(receipted.batch().receiptRef()).isEqualTo("RCPT-7788");
    assertThat(receipted.batch().receiptRecordedAt()).isEqualTo(HANDOVER_AT);

    // 时间线留痕（append-only）
    assertThat(receipted.timeline()).isNotEmpty();
    assertThat(receipted.timeline()).extracting(HandoverBatchEventRow::eventKind)
        .contains("BATCH_CREATED", "DISPATCH_PENDING", "RECEIPT_RECORDED");

    // 交接清单导出 = 详情同一份凭据
    HandoverManifestExport export =
        as(actorA, "channel.operation.read", () -> handovers.manifest(created.batch().id()));
    assertThat(export.items()).hasSize(3);
    assertThat(export.totalWeightKg()).isEqualByComparingTo("4.500000");
    assertThat(export.boundaryNotes()).isNotEmpty();
  }

  @Test
  @DisplayName("10.3.2.1 跨服务只经授权 API：端口转发的是调用方自己的 bearer（不是本服务替对方决定租户）")
  void upstreamCallForwardsCallerBearer() {
    upstreamUnit("Bearer tenant-a", "DHL_EXPRESS", 1, "1.000000", "WB-3001", "OUT-9");
    sync(actorA, "Bearer tenant-a");

    // 上游 HTTP 层真实收到调用方的 bearer
    assertThat(upstreamBears).contains("Bearer tenant-a");
    // 本服务**没有**向 trade 传任何租户号（租户由 trade 侧按成员关系解析）
    assertThat(upstreamBears).doesNotContain("Bearer tenant-b");
  }

  @Test
  @DisplayName("10.3.2.1 运单号缺口如实留空并写明原因（绝不拿出库单号冒充运单号）")
  void missingWaybillIsStatedNotFaked() {
    // 上游这一票没有签发运单号（10.2.1.2 未交付）
    upstreamUnit("Bearer tenant-a", "DHL_EXPRESS", 1, "1.000000", "", "OUT-NOWB");
    sync(actorA, "Bearer tenant-a");

    HandoverCandidateRow c = candidate(actorA, 0);
    assertThat(c.waybillNo()).isEmpty();
    assertThat(c.waybillGapCode()).isEqualTo("UPSTREAM_NOT_ISSUED");
    assertThat(c.waybillGapReason()).contains("10.2.1.2");

    HandoverBatchDetail created =
        batch(actorA, "DHL_EXPRESS", HANDOVER_AT, manifestOf(c, 1, ""));
    HandoverManifestItemRow item = created.manifest().get(0);
    assertThat(item.waybillNo()).isEmpty();
    assertThat(item.waybillGapCode()).isEqualTo("UPSTREAM_NOT_ISSUED");
    assertThat(item.waybillGapReason()).isNotBlank();
    // 关键：**清单里绝不能出现出库单号冒充运单号**
    assertThat(item.waybillNo()).doesNotContain(item.outboundOrderNo());
    assertThat(item.waybillNo()).isEmpty();
  }

  @Test
  @DisplayName("10.3.2.1 无运单号又没写原因时必须被拒（答不出「为什么没有」就不该落库）")
  void missingWaybillWithoutReasonIsRejected() {
    upstreamUnit("Bearer tenant-a", "DHL_EXPRESS", 1, "1.000000", "WB-4001", "OUT-4");
    sync(actorA, "Bearer tenant-a");
    HandoverCandidateRow c = candidate(actorA, 0);

    List<ManifestItemInput> items = List.of(new ManifestItemInput(
        c.id(), 1, "BOX-1", "", "", "",           // 运单号空 + 缺口原因为空
        new BigDecimal("1.5"), new BigDecimal("200"), new BigDecimal("150"),
        new BigDecimal("80"), ""));

    assertThatThrownBy(() -> batch(actorA, "DHL_EXPRESS", HANDOVER_AT, items))
        .hasMessageContaining("运单号");
  }

  // ==================================================================
  // 🔴 组批不变量
  // ==================================================================

  @Test
  @DisplayName("10.3.2.1 一条批次不许跨承运商：服务层与 DB 触发器各挡一次")
  void batchCannotSpanCarriers() {
    upstreamUnit("Bearer tenant-a", "DHL_EXPRESS", 1, "1.000000", "WB-5001", "OUT-5");
    upstreamUnit("Bearer tenant-a", "FEDEX", 1, "1.000000", "WB-5002", "OUT-6");
    sync(actorA, "Bearer tenant-a");

    HandoverCandidateRow dhl = as(actorA, "channel.operation.read",
        () -> handovers.candidates(null, "DHL_EXPRESS", "PENDING", null, 0, 50)).items().get(0);
    HandoverCandidateRow fedex = as(actorA, "channel.operation.read",
        () -> handovers.candidates(null, "FEDEX", "PENDING", null, 0, 50)).items().get(0);

    List<ManifestItemInput> mixed = new ArrayList<>();
    mixed.addAll(manifestOf(dhl, 1, "WB-5001"));
    mixed.addAll(manifestOf(fedex, 1, "WB-5002"));

    // 服务层先拒，并说清是哪一件、哪个承运商
    assertThatThrownBy(() -> batch(actorA, "DHL_EXPRESS", HANDOVER_AT, mixed))
        .hasMessageContaining("不许跨承运商");

    // 绕过服务层直接写 SQL：DB 触发器照样拒
    assertThatThrownBy(() -> as(actorA, "channel.operation.read", () -> {
      UUID bid = java.util.UUID.randomUUID();
      sqlExecute("insert into handover_batch (id,tenant_id,batch_no,carrier_code,carrier_channel,"
          + " handover_at,handover_bucket,status) values ('" + bid + "','" + a
          + "','HNB-MIXED-1','DHL_EXPRESS','THIRD_PARTY',?::timestamptz,"
          + " ?::timestamptz::date,'DRAFT')", HANDOVER_AT.toString(), "2026-10-15");
      sqlExecute("update handover_candidate set status='BATCHED', batch_id='" + bid
          + "' where id='" + fedex.id() + "'");
      return null;
    })).hasMessageContaining("跨承运商");
  }

  @Test
  @DisplayName("10.3.2.1 批次头计数不接受调用方指定：直接写 SQL 改成好看的数会被拒")
  void batchCountsAreRecomputedByDb() {
    upstreamUnit("Bearer tenant-a", "DHL_EXPRESS", 1, "1.000000", "WB-6001", "OUT-7");
    sync(actorA, "Bearer tenant-a");
    HandoverCandidateRow c = candidate(actorA, 0);
    HandoverBatchDetail created = batch(actorA, "DHL_EXPRESS", HANDOVER_AT, manifestOf(c, 1, "WB-6001"));
    UUID batchId = created.batch().id();

    // 绕过服务层把计数改成 999：DB 触发器重算逐件清单并拒绝
    Throwable e = catchThrowable(() -> as(actorA, "channel.operation.read", () -> {
      sqlExecute("update handover_batch set parcel_count=999, unit_count=999 where id='" + batchId + "'");
      return null;
    }));
    assertThat(rootMessage(e)).contains("计数");

    // 计数仍是逐件清单的真实值
    HandoverBatchDetail after =
        as(actorA, "channel.operation.read", () -> handovers.detail(batchId));
    assertThat(after.batch().parcelCount()).isEqualTo(1);
    assertThat(after.batch().unitCount()).isEqualTo(1);
  }

  // ==================================================================
  // 🔴 交接清单是法律凭据：append-only 双保险 + 已交接批次不可静默改写
  // ==================================================================

  @Test
  @DisplayName("10.3.2.1 🔴 交接清单 append-only：应用账号没权限、超级账号也被触发器拒")
  void manifestIsAppendOnly() throws Exception {
    upstreamUnit("Bearer tenant-a", "DHL_EXPRESS", 1, "1.000000", "WB-7001", "OUT-8");
    sync(actorA, "Bearer tenant-a");
    HandoverCandidateRow c = candidate(actorA, 0);
    HandoverBatchDetail created = batch(actorA, "DHL_EXPRESS", HANDOVER_AT, manifestOf(c, 1, "WB-7001"));
    UUID batchId = created.batch().id();
    assertThat(created.manifest()).hasSize(1);

    // (a) 应用账号：权限层就拒（只给了 SELECT/INSERT）
    try (var conn = DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only")) {
      for (String stmt : List.of(
          "update handover_manifest_item set waybill_no='TAMPERED'",
          "delete from handover_manifest_item")) {
        Throwable e = catchThrowable(() -> {
          try {
            conn.createStatement().execute(stmt);
          } catch (java.sql.SQLException ex) {
            throw new RuntimeException(ex);
          }
        });
        assertThat(rootMessage(e)).contains("permission denied");
      }
    }

    // (b) 迁移超级账号（等价于绕过服务层与权限面直连）：触发器照样拒
    assertThat(catchThrowable(() -> adminExecute(
        "update handover_manifest_item set waybill_no='TAMPERED'")))
        .hasMessageContaining("不允许修改或删除");
    assertThat(catchThrowable(() -> adminExecute(
        "delete from handover_manifest_item"))).isNotNull();

    // 内容一个字节都没变
    HandoverBatchDetail after =
        as(actorA, "channel.operation.read", () -> handovers.detail(batchId));
    assertThat(after.manifest().get(0).waybillNo()).isEqualTo("WB-7001");
  }

  @Test
  @DisplayName("10.3.2.1 🔴 已交接批次的成员不可被静默摘出（交接内容不可静默改写）")
  void handedOverBatchIsFrozen() {
    upstreamUnit("Bearer tenant-a", "DHL_EXPRESS", 1, "1.000000", "WB-8001", "OUT-10");
    sync(actorA, "Bearer tenant-a");
    HandoverCandidateRow c = candidate(actorA, 0);
    HandoverBatchDetail created = batch(actorA, "DHL_EXPRESS", HANDOVER_AT, manifestOf(c, 1, "WB-8001"));
    UUID batchId = created.batch().id();

    as(actorA, "channel.operation.update", () -> handovers.dispatch(batchId,
        new DispatchRequest(HANDOVER_AT, ""), actorA.membershipId()));
    as(actorA, "channel.operation.update", () -> handovers.recordReceipt(batchId,
        new ReceiptRequest("RCPT-1", "ACCEPTED", "", HANDOVER_AT), actorA.membershipId()));
    assertThat(as(actorA, "channel.operation.read", () -> handovers.detail(batchId))
        .batch().status()).isEqualTo("HANDED_OVER");

    // 把件退回候选池 / 换到别的批次 / 物理删批次 —— 三条路都被拒
    Throwable back = catchThrowable(() -> adminExecute(
        "update handover_candidate set status='PENDING', batch_id=null where id='" + c.id() + "'"));
    assertThat(rootMessage(back)).contains("退回候选池");

    Throwable del = catchThrowable(() -> adminExecute(
        "delete from handover_batch where id='" + batchId + "'"));
    assertThat(rootMessage(del)).contains("不允许删除");

    // 状态不可回退（HANDED_OVER 是责任分界终态）
    Throwable rewind = catchThrowable(() -> adminExecute(
        "update handover_batch set status='DRAFT' where id='" + batchId + "'"));
    assertThat(rootMessage(rewind)).contains("状态不可从");

    // 整批仍完整可读
    HandoverBatchDetail after =
        as(actorA, "channel.operation.read", () -> handovers.detail(batchId));
    assertThat(after.manifest()).hasSize(1);
    assertThat(after.batch().status()).isEqualTo("HANDED_OVER");
  }

  @Test
  @DisplayName("10.3.2.1 有差异 / 拒收的回执必须写明原因，且如实标出不被并进「已交接」")
  void discrepancyReceiptMustBeExplained() {
    upstreamUnit("Bearer tenant-a", "DHL_EXPRESS", 1, "1.000000", "WB-8101", "OUT-11");
    sync(actorA, "Bearer tenant-a");
    HandoverCandidateRow c = candidate(actorA, 0);
    HandoverBatchDetail created = batch(actorA, "DHL_EXPRESS", HANDOVER_AT, manifestOf(c, 1, "WB-8101"));
    UUID batchId = created.batch().id();
    as(actorA, "channel.operation.update", () -> handovers.dispatch(batchId,
        new DispatchRequest(HANDOVER_AT, ""), actorA.membershipId()));

    // 有差异但不写原因 → 拒（否则无法发起理赔）
    assertThatThrownBy(() -> as(actorA, "channel.operation.update",
        () -> handovers.recordReceipt(batchId,
            new ReceiptRequest("RCPT-2", "ACCEPTED_WITH_DISCREPANCY", "", HANDOVER_AT),
            actorA.membershipId())))
        .hasMessageContaining("差异");

    // 拒收：批次**不**推进到 HANDED_OVER（承运商没接，责任还没转过去）
    HandoverBatchDetail rejected = as(actorA, "channel.operation.update",
        () -> handovers.recordReceipt(batchId,
            new ReceiptRequest("RCPT-3", "REJECTED", "少收 1 箱", HANDOVER_AT),
            actorA.membershipId()));
    assertThat(rejected.batch().status()).isEqualTo("DISPATCH_PENDING");
    assertThat(rejected.batch().receiptStatusLabel()).isEqualTo("拒收");
    assertThat(rejected.batch().receiptDetail()).isEqualTo("少收 1 箱");
    // 汇总里「有差异/被拒」是独立计数，不被并进已交接
    HandoverBatchSummary summary =
        as(actorA, "channel.operation.read", () -> handovers.batchSummary());
    assertThat(summary.receiptMismatchCount()).isEqualTo(1);
    assertThat(summary.handedOverCount()).isZero();
  }

  // ==================================================================
  // 多租户三断言
  // ==================================================================

  @Test
  @DisplayName("10.3.2.1 断言一：两租户互不可见")
  void tenantsAreIsolated() {
    upstreamUnit("Bearer tenant-a", "DHL_EXPRESS", 1, "1.000000", "WB-9001", "OUT-A");
    sync(actorA, "Bearer tenant-a");
    HandoverCandidateRow c = candidate(actorA, 0);
    HandoverBatchDetail created = batch(actorA, "DHL_EXPRESS", HANDOVER_AT, manifestOf(c, 1, "WB-9001"));
    UUID batchId = created.batch().id();

    // 租户 B 看不到 A 的候选、批次、清单
    assertThat(as(actorB, "channel.operation.read", () -> handovers.candidates(null, "", null, null, 0, 50))
        .items()).isEmpty();
    assertThat(as(actorB, "channel.operation.read", () -> handovers.batches(null, null, null, null, 0, 50))
        .items()).isEmpty();
    assertThatThrownBy(() -> as(actorB, "channel.operation.read", () -> handovers.detail(batchId)))
        .hasMessageContaining("交运批次不存在");

    // B 自己的候选池是空的（A 的那一票在 B 的租户边界之外），两边各自独立
    assertThat(as(actorB, "channel.operation.read",
        () -> handovers.candidates(null, "", "PENDING", null, 0, 50)).items()).isEmpty();
    assertThat(as(actorA, "channel.operation.read", () -> handovers.batches(null, null, null, null, 0, 50))
        .items()).extracting(HandoverBatchRow::id).containsExactly(batchId);
  }

  @Test
  @DisplayName("10.3.2.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    upstreamUnit("Bearer tenant-a", "DHL_EXPRESS", 1, "1.000000", "WB-9101", "OUT-B");
    sync(actorA, "Bearer tenant-a");
    HandoverCandidateRow c = candidate(actorA, 0);
    HandoverBatchDetail created = batch(actorA, "DHL_EXPRESS", HANDOVER_AT, manifestOf(c, 1, "WB-9101"));
    UUID batchId = created.batch().id();

    // 租户 B 也组一批，用来证明「A 的会话 + B 的头」看到的仍只是 A 的数据
    upstreamUnit("Bearer tenant-b", "FEDEX", 1, "1.000000", "WB-9102", "OUT-C");
    sync(actorB, "Bearer tenant-b");
    HandoverCandidateRow bCandidate = as(actorB, "channel.operation.read",
        () -> handovers.candidates(null, "", "PENDING", null, 0, 50)).items().get(0);
    HandoverBatchDetail bBatch =
        batch(actorB, "FEDEX", HANDOVER_AT, manifestOf(bCandidate, 1, "WB-9102"));

    http.perform(get("/channel/handover-batches").header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[*].id", org.hamcrest.Matchers.hasItem(batchId.toString())))
        .andExpect(jsonPath("$.items[*].id", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(
            bBatch.batch().id().toString()))));

    // 拿 B 的 id 去 A 的会话里读：跨租户与不存在返回**逐字相同**的 404
    http.perform(get("/channel/handover-batches/{id}", bBatch.batch().id())
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
    http.perform(get("/channel/handover-batches/{id}",
            java.util.UUID.randomUUID()).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value("交运批次不存在"));

    // 无会话一律 401
    http.perform(get("/channel/handover-batches").header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("10.3.2.1 断言三：连接池归还后不串租户")
  void pooledConnectionsDoNotBleedTenants() {
    upstreamUnit("Bearer tenant-a", "DHL_EXPRESS", 1, "1.000000", "WB-9201", "OUT-D");
    sync(actorA, "Bearer tenant-a");
    HandoverCandidateRow aCandidate = candidate(actorA, 0);
    HandoverBatchDetail aBatch =
        batch(actorA, "DHL_EXPRESS", HANDOVER_AT, manifestOf(aCandidate, 1, "WB-9201"));

    upstreamUnit("Bearer tenant-b", "FEDEX", 1, "1.000000", "WB-9202", "OUT-E");
    sync(actorB, "Bearer tenant-b");
    HandoverCandidateRow bCandidate = as(actorB, "channel.operation.read",
        () -> handovers.candidates(null, "", "PENDING", null, 0, 50)).items().get(0);
    HandoverBatchDetail bBatch =
        batch(actorB, "FEDEX", HANDOVER_AT, manifestOf(bCandidate, 1, "WB-9202"));

    // 同一个池上交替跑 A/B/A/B
    for (int i = 0; i < 3; i++) {
      assertThat(as(actorA, "channel.operation.read", () -> handovers.batches(null, null, null, null, 0, 50))
          .items()).extracting(HandoverBatchRow::id).containsExactly(aBatch.batch().id());
      assertThat(as(actorB, "channel.operation.read", () -> handovers.batches(null, null, null, null, 0, 50))
          .items()).extracting(HandoverBatchRow::id).containsExactly(bBatch.batch().id());
      assertThat(as(actorA, "channel.operation.read", () -> handovers.candidates(null, "", null, null, 0, 50))
          .items()).allSatisfy(c -> assertThat(c.waybillNo()).isEqualTo("WB-9201"));
    }
  }

  // ==================================================================
  // 端点与能力声明
  // ==================================================================

  @Test
  @DisplayName("10.3.2.1 端点可真实操作（MockMvc 走完整控制器链路）")
  void endpointsDriveTheFlow() throws Exception {
    upstreamUnit("Bearer tenant-a", "DHL_EXPRESS", 1, "2.000000", "WB-9301", "OUT-F");

    http.perform(post("/channel/handover-batches/candidates/sync")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content("{\"carrierCode\":\"\",\"page\":0,\"size\":50}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fetched").value(1))
        .andExpect(jsonPath("$.created").value(1));

    http.perform(get("/channel/handover-batches/candidates")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].carrierCode").value("DHL_EXPRESS"))
        .andExpect(jsonPath("$.summary.byCarrier[0].carrierCode").value("DHL_EXPRESS"));

    // 组批预览是真 GROUP BY
    http.perform(get("/channel/handover-batches/group-preview")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].carrierCode").value("DHL_EXPRESS"))
        .andExpect(jsonPath("$[0].handoverBucket").value("2026-10-15"));

    // 伪造会话拿不到数据
    http.perform(get("/channel/handover-batches")).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("10.3.2.1 capabilities：未交付的段显式 available=false 并点名 ownerModule")
  void capabilitiesDeclareGapsHonestly() {
    HandoverCapabilities caps = as(actorA, "channel.operation.read", handovers::capabilities);
    assertThat(caps.dimensions())
        .filteredOn(d -> d.dimension().equals("handover_manifest"))
        .singleElement()
        .satisfies(d -> assertThat(d.available()).isTrue());

    // 未交付的每一项都必须 available=false + ownerModule + impact，三者齐备
    assertThat(caps.dimensions())
        .filteredOn(d -> !d.available())
        .isNotEmpty()
        .allSatisfy(d -> {
          assertThat(d.ownerModule()).as("ownerModule 不能为空：" + d.dimension()).isNotBlank();
          assertThat(d.impact()).as("impact 不能为空：" + d.dimension()).isNotBlank();
        });

    assertThat(caps.dimensions())
        .filteredOn(d -> d.dimension().equals("carrier_dispatch"))
        .singleElement()
        .satisfies(d -> {
          assertThat(d.available()).isFalse();
          assertThat(d.ownerModule()).isEqualTo("10.1.1.1");
        });
    assertThat(caps.dimensions())
        .filteredOn(d -> d.dimension().equals("waybill_number"))
        .singleElement()
        .satisfies(d -> {
          assertThat(d.available()).isFalse();
          assertThat(d.ownerModule()).isEqualTo("10.2.1.2");
        });
    assertThat(caps.dimensions())
        .filteredOn(d -> d.dimension().equals("pickup_confirmation"))
        .singleElement()
        .satisfies(d -> assertThat(d.ownerModule()).isEqualTo("10.3.2.2"));
  }

  // ---- 小工具 ----

  /** 以应用账号（RLS + 权限面生效）执行 DML，验证 DB 层的真实拒绝行为。 */
  private void sqlExecute(String statement, Object... binds) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + a + "',false)");
      PreparedStatementRunner.run(c, statement, binds);
    } catch (java.sql.SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * 以迁移账号（超级权限）执行 DML —— 等价于「绕过服务层与权限面直连数据库」。
   * 触发器必须在这条路上依然拦住改写行为，否则不变量就只是靠约定。
   */
  private void adminExecute(String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + a + "',false)");
      s.execute(statement);
    } catch (java.sql.SQLException e) {
      throw new RuntimeException(e);
    }
  }

  private static final class PreparedStatementRunner {
    static void run(java.sql.Connection c, String statement, Object... binds)
        throws java.sql.SQLException {
      try (var ps = c.prepareStatement(statement)) {
        for (int i = 0; i < binds.length; i++) {
          ps.setObject(i + 1, binds[i]);
        }
        ps.execute();
      }
    }
  }

  private static String rootMessage(Throwable t) {
    Throwable cur = t;
    while (cur.getCause() != null) cur = cur.getCause();
    return cur.getMessage() == null ? "" : cur.getMessage();
  }
}
