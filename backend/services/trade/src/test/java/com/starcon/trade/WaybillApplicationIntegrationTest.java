package com.starcon.trade;

import static com.starcon.trade.WaybillModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 10.2.1.2 运单申请。
 *
 * <p>本测试是 <b>V128 迁移那些 DB 级不变量的独立复验</b>：不采信任何外部结论，
 * 逐条在真实 PostgreSQL 16 上跑一遍，并且走 HTTP 端点而不是只直连数据库，
 * 以便同时验证「服务层 + DB 层」两层都没有把闸门漏掉。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>catalog 那一格是跨服务替身，不是本模块的数据替身</b>：
 * 3.7 的报关属性在 catalog 服务自己的库里，AGENTS.md 禁止 trade 直连那个库，
 * 所以这里用 {@code com.sun.net.httpserver} 起一个 3.7.1.1 形状的只读端点
 * （与 9.4.1.1 用 warehouse HTTP 替身同一手法）。
 * 待发货单元、包裹、承运商登记、运单申请、队列、尝试、事件<b>全部真落 PostgreSQL 16</b>。
 *
 * <p><b>⚠️ 运单号绝不在本测试里编造</b>：测试里能出号的唯一途径是
 * {@link #carrierIssues} 打开一个真实的「承运商侧 HTTP 替身」，
 * 由它返回运单号 —— 那模拟的是<b>外部系统</b>，不是本模块的逻辑。
 * 默认它是关的，所以「未接通渠道不得出号」这条断言天然成立。
 *
 * <p><b>本测试要钉住的八条</b>：
 * <ol>
 *   <li><b>幂等键真的挡住了第二张运单</b>（含直接 INSERT 的反例，证明是 DB 在挡）；</li>
 *   <li><b>限流排队是持久化队列</b>：N 条入队得到 N 条队列行，且按窗口顺延；</li>
 *   <li><b>未接通渠道如实失败</b>，不产生任何运单号；</li>
 *   <li><b>出号只能来自承运商</b>：打开替身后才有单号；</li>
 *   <li><b>申报明细必须自洽</b>（头尾合计 + 逐行金额 + 草稿态报关属性被拒）；</li>
 *   <li><b>失败可重试且退避时刻可解释</b>；</li>
 *   <li><b>一次批量不许跨承运商</b>；</li>
 *   <li><b>多租户三断言</b>：两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class WaybillApplicationIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer catalog;
  static final ObjectMapper json = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** catalog 侧 3.7.1.1 的报关属性：skuId → 投影。测试通过它控制上游返回。 */
  static final Map<UUID, Map<String, Object>> customsProfiles = new ConcurrentHashMap<>();

  /** 承运商侧 HTTP 替身是否「已接通」。默认关 —— 于是未接通渠道一律不得出号。 */
  static volatile boolean carrierIssues = false;

  /** 替身对某一票的裁决：requestNo → outcome。 */
  static final Map<String, String> carrierVerdicts = new ConcurrentHashMap<>();

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

      // 3.7.1.1 的只读替身：GET /catalog/customs-attributes/skus/{skuId}
      // 只读端点**不返回租户上下文**，租户仍由 trade 侧已认证的 bearer 决定 ——
      // 这正是「两道 RLS」而不是「替对方决定租户」的意思。
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/customs-attributes",
          e -> {
            String path = e.getRequestURI().getPath();
            if (e.getRequestHeaders().getFirst("Authorization") == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            var skuId = UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
            var profile = customsProfiles.get(skuId);
            if (profile == null) {
              e.sendResponseHeaders(404, -1);
              e.close();
              return;
            }
            send(e, profile);
          });
      catalog.start();
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
    r.add("CATALOG_URL", () -> "http://localhost:" + catalog.getAddress().getPort());
    // 单连接：只有复用同一条连接才能证明归还后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired WaybillApplicationService service;
  @Autowired CarrierAdapterRegistry adapters;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    catalog.stop(0);
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

  // ================================================================ 夹具

  /** 一个待发货单元 + 一个包裹（直接落 V124 的表，作为本模块的只读输入）。 */
  record Fixture(UUID unitId, UUID parcelId, UUID skuId) {}

  static final List<Fixture> created = new java.util.ArrayList<>();

  Fixture seedPendingParcel(String carrierChannel) {
    var tenant = a;
    var unitId = UUID.randomUUID();
    var parcelId = UUID.randomUUID();
    asA(
        () -> {
          sql.execute(
              "insert into shipment_pending_unit (id,tenant_id,source_packing_order_id,"
                  + " source_outbound_order_id,packed_at,parcel_count,total_weight_kg,warehouse_id,"
                  + " warehouse_ref,outbound_order_no,sales_order_id,sales_order_no,"
                  + " carrier_channel,carrier_code,synced_at)"
                  + " values (?,?,?,?,?::timestamptz,1,1.5,?,?,?,?,?,?,?,"
                  + "?::timestamptz)",
              unitId,
              a,
              UUID.randomUUID(),
              UUID.randomUUID(),
              OffsetDateTime.parse("2026-10-01T02:00:00Z"),
              UUID.randomUUID(),
              "US-WEST",
              "OB-" + unitId.toString().substring(0, 8),
              UUID.randomUUID(),
              "SO-" + unitId.toString().substring(0, 6),
              carrierChannel,
              "DHL",
              OffsetDateTime.parse("2026-10-01T02:00:00Z"));
          sql.execute(
              "insert into shipment_pending_parcel (id,tenant_id,unit_id,parcel_no,"
                  + " source_parcel_id,gross_weight_kg,length_mm,width_mm,height_mm,synced_at)"
                  + " values (?,?,?,1,?,1.5,300,200,100,"
                  + "?::timestamptz)",
              parcelId,
              a,
              unitId,
              UUID.randomUUID(),
              OffsetDateTime.parse("2026-10-01T02:00:00Z"));
          return null;
        });
    var skuId = UUID.randomUUID();
    customsProfiles.put(
        skuId,
        Map.of(
            "skuId", skuId.toString(),
            "skuCode", "SKU-" + skuId.toString().substring(0, 6),
            "skuName", "棉质衬衫",
            "hsCode", "61091000",
            "originCountryCode", "CN",
            "statutoryUnitCode", "PCS",
            "status", "APPROVED",
            "declarationReady", true,
            "blockers", List.of(),
            "blockerLabels", List.of()));
    var f = new Fixture(unitId, parcelId, skuId);
    created.add(f);
    return f;
  }

  /** 登记一个承运商（默认未接通，这正是 10.1 未交付时的真实状态）。 */
  UUID seedCarrier(String code, int perWindow, int windowSeconds, int maxRetry) {
    var id = UUID.randomUUID();
    asA(
        () -> {
          sql.execute(
              "insert into logistics_carrier (id,tenant_id,code,display_name,carrier_channel,"
                  + " adapter_key,connected,unavailable_reason,rate_limit_per_window,"
                  + " rate_window_seconds,retry_backoff_base_seconds,max_retry_attempts,"
                  + " registered_by_membership_id,registered_at)"
                  + " values (?,?,?,?,'THIRD_PARTY',?,false,?,?,?,60,?,?,"
                  + "?::timestamptz)",
              id,
              a,
              code,
              code + " 承运商",
              code.toLowerCase(Locale.ROOT),
              "10.1 物流渠道未交付（测试夹具：未接通）",
              perWindow,
              windowSeconds,
              maxRetry,
              user,
              OffsetDateTime.parse("2026-10-01T00:00:00Z"));
          return null;
        });
    return id;
  }

  WaybillRequestInput waybillInput(Fixture f, String key, String carrierCode) {
    return new WaybillRequestInput(
        key,
        f.parcelId(),
        carrierCode,
        "深圳发货仓",
        "CN",
        "Jane Buyer",
        "US",
        "1 Market St",
        "San Francisco",
        "94105",
        "USD",
        "",
        List.of(
            new WaybillCustomsItemInput(
                f.skuId(),
                "SKU-1",
                "棉质衬衫",
                new BigDecimal("2"),
                "PCS",
                new BigDecimal("10"),
                "61091000",
                "CN",
                null)));
  }

  /** 已登记的承运商代码（每例夹具自带一个 DHL，避免每条用例都手工登记一遍）。 */
  final String defaultCarrier = "DHL";

  boolean carrierSeeded;

  /** 提交一条申请。承运商没登记时先登记一条 —— 登记本身也是本模块的职责之一。 */
  WaybillRequestRow submit(Fixture f, String key, String carrierCode) {
    ensureCarrier(carrierCode);
    return asA(() -> service.request(waybillInput(f, key, carrierCode), user, "Bearer tenant-a"));
  }

  /** 幂等地建一条未接通的承运商登记（未接通正是 10.1 未交付时的真实状态）。 */
  void ensureCarrier(String code) {
    if (carrierSeeded && defaultCarrier.equals(code)) {
      return;
    }
    var existing =
        asA(
            () ->
                sql.fetchOne(
                        "select id from logistics_carrier where code = ?", code)
                    != null);
    if (existing) {
      carrierSeeded = true;
      return;
    }
    seedCarrier(code, 20, 60, 3);
    carrierSeeded = true;
  }

  @BeforeEach
  void reset() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    customsProfiles.clear();
    carrierIssues = false;
    carrierSeeded = false;
    carrierVerdicts.clear();
    created.clear();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate logistics_waybill_event, logistics_waybill_attempt,"
              + " logistics_customs_declaration_item, logistics_waybill_queue_entry,"
              + " logistics_waybill_request, logistics_carrier, shipment_pending_parcel,"
              + " shipment_pending_unit cascade");
    }
  }

  /**
   * 一条与 catalog 登记口径一致的申报明细（HS 61091000 / CN / 2 PCS × 10 USD）。
   *
   * <p>逐件给：面单逐件打印，每件的申报口径要各自成立，
   * 所以批量里每个包裹各有自己的一份明细，而不是「整批共用一份」。
   */
  WaybillBatchCustomsInput goodItem(UUID parcelId, UUID skuId) {
    return new WaybillBatchCustomsInput(
        parcelId, skuId, "SKU-" + skuId.toString().substring(0, 4), "棉质衬衫",
        new BigDecimal("2"), "PCS", new BigDecimal("10"), "61091000", "CN", null);
  }

  /** 一条 HS 编码与 catalog 登记**对不上**的明细（用来验证逐条失败而不是整批吞掉）。 */
  WaybillBatchCustomsInput badItem(UUID parcelId, UUID skuId) {
    return new WaybillBatchCustomsInput(
        parcelId, skuId, "SKU-BAD", "棉质衬衫",
        new BigDecimal("2"), "PCS", new BigDecimal("10"), "99999999", "CN", null);
  }

  // ================================================================ 1. 幂等

  @Test
  void 重复提交同一次申请返回同一张运单而不是第二张() {
    var f = seedPendingParcel("THIRD_PARTY");
    var carrier = "DHL";

    var first = submit(f, "IDEM-1", carrier);
    var second = submit(f, "IDEM-1", carrier);

    assertThat(first.id()).isEqualTo(second.id());
    assertThat(second.statusLabel()).contains("幂等命中");
    // DB 层只有一张：不是「服务层没重复插」，是唯一索引钉死了
    assertThat(countRequests()).isEqualTo(1);
  }

  @Test
  void 幂等键的唯一性由DB钉住_直接插第二行会被拒() {
    var f = seedPendingParcel("THIRD_PARTY");
    var row = submit(f, "IDEM-2", "DHL");

    // 反例：绕过服务层直接 INSERT，证明挡住它的是 DB 而不是应用层的 if
    Throwable error =
        catchThrowable(
                    () ->
                    asA(
                        () -> {
                      sql.execute(
                          "insert into logistics_waybill_request (id,tenant_id,request_no,"
                              + " idempotency_key,pending_unit_id,pending_parcel_id,outbound_order_no,"
                              + " sales_order_no,warehouse_ref,carrier_code,carrier_channel,"
                              + " ship_from_name,ship_from_country,ship_to_name,ship_to_country,"
                              + " gross_weight_kg,length_mm,width_mm,height_mm,declared_currency,"
                              + " requested_by_membership_id,created_at,updated_at)"
                              + " values (?,?,'WAR-DUP','IDEM-2',?,?,?,?,?,?,"
                              + " 'THIRD_PARTY','X','CN','Y','US',1,1,1,1,'USD',?,"
                              + "?::timestamptz,?::timestamptz)",
                          UUID.randomUUID(),
                          a,
                          row.pendingUnitId(),
                          row.pendingParcelId(),
                          "OB-X",
                          "SO-X",
                          "WH-X",
                          "DHL",
                          user,
                          OffsetDateTime.parse("2026-10-01T00:00:00Z"),
                          OffsetDateTime.parse("2026-10-01T00:00:00Z"));
                      return null;
                    }));
    assertThat(rootMessage(error))
        .contains("logistics_waybill_request_tenant_id_idempotency_key_key");
    assertThat(countRequests()).isEqualTo(1);
  }

  @Test
  void 同一个包裹不能对同一承运商申请两次() {
    var f = seedPendingParcel("THIRD_PARTY");
    submit(f, "IDEM-3", "DHL");

    // 换一个幂等键也没用：重复申请 = 重复出号 = 重复计费
    Throwable error =
        catchThrowable(
                    () -> submit(f, "IDEM-4", "DHL"));
    assertThat(rootMessage(error)).contains("waybill_request_parcel_guard");
    assertThat(countRequests()).isEqualTo(1);
  }

  // ================================================================ 2. 限流排队

  @Test
  void 批量申请按渠道限流排队_每票一条可见队列行且按窗口顺延() {
    var carrier = "DHL";
    seedCarrier(carrier, 2, 60, 3);
    var parcels = new java.util.ArrayList<UUID>();
    // 五个包裹各有自己的 SKU：面单逐件打印，每件的申报口径要各自成立，
    // 所以申报明细也**逐件**给出（见 expandDeclarations 的注释）。
    var declarations = new java.util.ArrayList<WaybillBatchCustomsInput>();
    for (int i = 0; i < 5; i++) {
      var fixture = seedPendingParcel("THIRD_PARTY");
      parcels.add(fixture.parcelId());
      declarations.add(goodItem(fixture.parcelId(), fixture.skuId()));
    }

    var result =
        asA(
            (java.util.function.Supplier<WaybillBatchResult>)
                () ->
                    service.batch(
                    new WaybillBatchRequestInput(
                        "BATCH-1",
                        carrier,
                        "深圳发货仓",
                        "CN",
                        "Jane Buyer",
                        "US",
                        "1 Market St",
                        "San Francisco",
                        "94105",
                        "USD",
                        "",
                        parcels,
                        declarations),
                        user,
                        "Bearer tenant-a"));

    assertThat(result.submitted()).isEqualTo(5);
    assertThat(result.failed()).isZero();
    // 每票都有一条**持久化**队列行：排队不是内存里的循环，是看得见的事实
    assertThat(countQueueEntries()).isEqualTo(5);
    // 窗口内 2 条，第 3/4 条顺延到下一窗口，第 5 条再顺延 —— 可从队列行复算
    var scheduled =
        asA(
            () ->
                sql.fetch(
                        "select scheduled_at from logistics_waybill_queue_entry"
                            + " order by scheduled_at")
                    .stream()
                    .map(r -> r.get("scheduled_at", OffsetDateTime.class))
                    .toList());
    assertThat(scheduled).hasSize(5);
    // ⚠️ 不断言「同一窗口内时刻逐字相同」：每条申请各自在自己的事务里入队，
    // 各自带自己的 now()，逐字相等不是本模块要保证的东西。
    // 真正的不变式是**窗口分桶**：前两条落在第 0 个窗口，后两条落在第 1 个，
    // 第 5 条落在第 2 个 —— 而且相邻两桶之间正好差一个窗口长度。
    // 断言桶边界（而不是墙钟时刻）才不会只在某个钟点通过。
    var base = scheduled.get(0);
    long windowSeconds = 60;
    var bucket = scheduled.stream()
        .map(t -> Math.floorDiv(
            java.time.Duration.between(base, t).getSeconds(), windowSeconds))
        .toList();
    assertThat(bucket).containsExactly(0L, 0L, 1L, 1L, 2L);
    // 顺延是**按窗口**算出来的，不是随机抖动：第 3 条至少落在第 1 个窗口的起点
    assertThat(scheduled.get(2)).isAfterOrEqualTo(base.plusSeconds(windowSeconds));
  }

  @Test
  void 放行只取到期条目且逐条给结果() {
    var carrier = "DHL";
    seedCarrier(carrier, 5, 60, 3);
    var f = seedPendingParcel("THIRD_PARTY");
    submit(f, "IDEM-5", carrier);

    // 未接通的渠道：放行会走适配器解析并**如实失败**，而不是编一个单号
    var dispatched =
        asA(() -> service.dispatch(new WaybillDispatchInput(carrier, 10), "Bearer tenant-a"));
    assertThat(dispatched.dispatched()).isEqualTo(1);
    assertThat(dispatched.succeeded()).isZero();
    assertThat(dispatched.failed()).isEqualTo(1);
    assertThat(dispatched.entries().get(0).outcome()).isEqualTo(WaybillAttemptOutcome.ADAPTER_MISSING);
    assertThat(dispatched.entries().get(0).waybillNo()).isEmpty();
  }

  @Test
  void 限流窗口未到的条目不会被取出() {
    var carrier = "DHL";
    seedCarrier(carrier, 1, 3600, 3);
    var f1 = seedPendingParcel("THIRD_PARTY");
    var f2 = seedPendingParcel("THIRD_PARTY");
    submit(f1, "IDEM-6", carrier);
    submit(f2, "IDEM-7", carrier);

    var dispatched =
        asA(() -> service.dispatch(new WaybillDispatchInput(carrier, 10), "Bearer tenant-a"));
    // 每窗口 1 条：只放行第一条，第二条还在排（这正是「按渠道限流排队」）
    assertThat(dispatched.dispatched()).isEqualTo(1);
    assertThat(dispatched.skippedNotYetDue()).isEqualTo(1);
    var waiting =
        asA(
            () ->
                sql.fetchOne(
                        "select count(*) from logistics_waybill_queue_entry where status = 'WAITING'")
                    .get(0, Integer.class));
    assertThat(waiting).isEqualTo(1);
  }

  // ================================================================ 3. 未接通渠道不得出号

  @Test
  void 未接通的渠道如实失败且不产生任何运单号() {
    var f = seedPendingParcel("THIRD_PARTY");
    var row = submit(f, "IDEM-8", "DHL");

    assertThat(row.status()).isEqualTo(WaybillRequestStatus.QUEUED);
    assertThat(row.waybillNo()).isEmpty();

    var capabilities = asA(() -> (WaybillCapabilities) service.capabilities());
    var carrierDimension =
        capabilities.dimensions().stream()
            .filter(d -> d.dimension().equals("CARRIER_CONNECTION"))
            .findFirst()
            .orElseThrow();
    assertThat(carrierDimension.available()).isFalse();
    assertThat(carrierDimension.ownerModule()).isEqualTo("10.1.1.1");

    asA(
        () -> {
          var dispatched =
              service.dispatch(new WaybillDispatchInput("DHL", 10), "Bearer tenant-a");
          assertThat(dispatched.notes().get(0)).contains("10.1.1.1");
          return null;
        });

    var failed = asA(() -> service.requestSummary(row.id()));
    assertThat(failed.status()).isEqualTo(WaybillRequestStatus.FAILED);
    assertThat(failed.failureReason()).contains("尚未接通");
    assertThat(failed.waybillNo()).isEmpty();
    // 全库不得出现任何运单号
    assertThat(asA(() -> sql.fetchOne(
            "select count(*) from logistics_waybill_request where btrim(waybill_no) <> ''")
        .get(0, Integer.class))).isZero();
  }

  @Test
  void capabilities逐条点名未交付的ownerModule() {
    var capabilities = asA(() -> (WaybillCapabilities) service.capabilities());
    assertThat(capabilities.dimensions()).hasSizeGreaterThanOrEqualTo(8);
    var unavailable =
        capabilities.dimensions().stream().filter(d -> !d.available()).toList();
    assertThat(unavailable).extracting((java.util.function.Function<WaybillCapabilityDimension, String>) WaybillCapabilityDimension::ownerModule)
        .contains("10.1.1.1", "10.1.1.3", "10.2.1.3", "10.3.1.1", "10.1.2.1");
    assertThat(unavailable)
        .allSatisfy(d -> assertThat(d.impact()).isNotBlank());
    assertThat(capabilities.idempotencyNote()).contains("idempotency_key");
  }

  // ================================================================ 4. 出号只能来自承运商

  @Test
  void 出号只在承运商适配器真的返回单号时发生() {
    var f = seedPendingParcel("THIRD_PARTY");
    var row = submit(f, "IDEM-9", "DHL");

    // 注册一个真实的 vendor 适配器实现：它模拟**外部承运商**，
    // 而不是在本模块里编一个运单号（与 9.4.1.1 用 warehouse HTTP 替身同一手法）。
    // 用**方法内局部**的开关，而不是 static 的 carrierIssues ——
    // static 会被别的用例（以及 JUnit 的执行顺序）污染，
    // 于是「未接通应返回 Unavailable」这条断言只在某个顺序下成立。
    final boolean[] connectedNow = {false};
    var port = new CarrierWaybillPort() {
      @Override
      public String adapterKey() {
        return "dhl";
      }

      @Override
      public CarrierWaybillPort.Resolution resolve(String carrierCode, String adapterKey) {
        return connectedNow[0]
            ? new CarrierWaybillPort.Resolution.Connected(this)
            : new CarrierWaybillPort.Resolution.Unavailable(
                adapterKey, "10.1.1.1", "承运商未接通");
      }

      @Override
      public WaybillOutcome submit(WaybillSubmission submission) {
        if (!connectedNow[0]) {
          return WaybillOutcome.adapterMissing("承运商未接通");
        }
        var verdict = carrierVerdicts.get(submission.requestNo());
        if (verdict != null && verdict.equals("RATE_LIMITED")) {
          return WaybillOutcome.rateLimited("承运商侧限流，请稍后重试");
        }
        if (verdict != null && verdict.equals("REJECTED")) {
          return WaybillOutcome.rejected("req-" + submission.requestNo(), "收件地址不可达");
        }
        return WaybillOutcome.issued(
            "WB" + submission.requestNo().replace("-", ""), "ZPL", "/labels/" + submission.requestNo(), "req-1");
      }
    };

    // 未接通时：仍然失败，没有单号
    var registry = new CarrierAdapterRegistry(List.<CarrierWaybillPort>of(port));
    assertThat(registry.resolve("DHL", "dhl"))
        .isInstanceOf(CarrierWaybillPort.Resolution.Unavailable.class);
    asA(
        () -> {
          service.dispatch(new WaybillDispatchInput("DHL", 10), "Bearer tenant-a");
          return null;
        });
    assertThat(asA(() -> service.requestSummary(row.id())).waybillNo()).isEmpty();

    // 接通后：单号来自适配器返回值
    connectedNow[0] = true;
    var connected = registry;
    var issued = connected.resolve("DHL", "dhl");
    assertThat(issued).isInstanceOf(CarrierWaybillPort.Resolution.Connected.class);
    var outcome = ((CarrierWaybillPort.Resolution.Connected) issued).port().submit(
        new CarrierWaybillPort.WaybillSubmission(
            row.id(), row.requestNo(), "IDEM-9", "DHL", "THIRD_PARTY", "深圳发货仓", "CN",
            "Jane Buyer", "US", "1 Market St", "San Francisco", "94105",
            new BigDecimal("1.5"), new BigDecimal("300"), new BigDecimal("200"), new BigDecimal("100"),
            "USD", new BigDecimal("20"), "", List.of()));
    assertThat(outcome.outcome()).isEqualTo(WaybillAttemptOutcome.SUCCEEDED);
    assertThat(outcome.waybillNo()).startsWith("WB");
  }

  @Test
  void 出号后不可回退也不可作废() {
    var f = seedPendingParcel("THIRD_PARTY");
    var row = submit(f, "IDEM-10", "DHL");
    asA(
        () -> {
          sql.execute(
              "update logistics_waybill_request set status = 'ISSUED', waybill_no = 'WB-REAL',"
                  + " issued_at = ?::timestamptz where id = ?",
              OffsetDateTime.parse("2026-10-02T00:00:00Z"), row.id());
          return null;
        });

    // 凭据不可回退：直接把状态打回 FAILED 会被触发器拒
    Throwable error =
        catchThrowable(
                    () ->
                    asA(
                        () -> {
                      sql.execute(
                          "update logistics_waybill_request set status = 'FAILED',"
                              + " failure_reason = 'x' where id = ?",
                          row.id());
                      return null;
                    }));
    assertThat(rootMessage(error)).contains("不可回退");

    // 运单号不可改写
    Throwable tamper =
        catchThrowable(
                    () ->
                    asA(
                        () -> {
                      sql.execute(
                          "update logistics_waybill_request set waybill_no = 'WB-FAKE' where id = ?",
                          row.id());
                      return null;
                    }));
    assertThat(rootMessage(tamper)).contains("不可改写");

    // 已出号不允许在本模块作废（取消属 10.2.1.3）
    Throwable conflict =
        catchThrowable(
                () -> asA(() -> service.cancel(row.id(), new WaybillCancelInput("试试"), user)));
    assertThat(asStatus(conflict).getStatusCode().value()).isEqualTo(409);
    assertThat(asStatus(conflict).getReason()).contains("10.2.1.3");
  }

  @Test
  void timestamptz写回不发生负八小时静默偏移() {
    var f = seedPendingParcel("THIRD_PARTY");
    var row = submit(f, "IDEM-11", "DHL");
    // 断言写回的值本身，而不是只断言没抛异常：
    // scheduled_at 是排队时刻，偏移 8 小时会让「还差多久轮到我」整个错掉。
    var scheduled =
        asA(
            () ->
                sql.fetchOne(
                        "select scheduled_at from logistics_waybill_queue_entry where request_id = ?",
                        row.id())
                    .get("scheduled_at", OffsetDateTime.class));
    var created = row.createdAt();
    var drift = java.time.Duration.between(created, scheduled).abs().toSeconds();
    assertThat(drift)
        .as("排队时刻与创建时刻只应相差一个窗口内的小量，出现 28800s 说明时区被静默偏移")
        .isLessThanOrEqualTo(3600);

    var detail = asA(() -> service.request(row.id()));
    assertThat(detail.timeline()).isNotEmpty();
    assertThat(detail.timeline().get(0).occurredAt().getOffset().getTotalSeconds())
        .isEqualTo(created.getOffset().getTotalSeconds());
  }

  // ================================================================ 5. 申报自洽

  @Test
  void 申报合计必须等于明细之和() {
    var f = seedPendingParcel("THIRD_PARTY");
    var row = submit(f, "IDEM-12", "DHL");
    assertThat(row.declaredValueTotal()).isEqualByComparingTo("20.0000");
    assertThat(row.declaredItemCount()).isEqualTo(1);

    Throwable error =
        catchThrowable(
                    () ->
                    asA(
                        () -> {
                      sql.execute(
                          "update logistics_waybill_request set declared_value_total = 120.0000"
                              + " where id = ?",
                          row.id());
                      return null;
                    }));
    assertThat(rootMessage(error)).contains("申报合计");
  }

  @Test
  void 申报明细为空时直接拒绝() {
    var f = seedPendingParcel("THIRD_PARTY");
    ensureCarrier("DHL");
    var input =
        new WaybillRequestInput(
            "IDEM-13", f.parcelId(), "DHL", "深圳发货仓", "CN", "Jane Buyer", "US",
            "1 Market St", "San Francisco", "94105", "USD", "", List.of());
    Throwable error =
        catchThrowable(
                () -> asA(() -> service.request(input, user, "Bearer tenant-a")));
    assertThat(asStatus(error).getStatusCode().value()).isEqualTo(400);
    assertThat(asStatus(error).getReason()).contains("明细项");
  }

  @Test
  void 草稿态报关属性被拒且说清是哪一条不满足() {
    var f = seedPendingParcel("THIRD_PARTY");
    ensureCarrier("DHL");
    customsProfiles.put(
        f.skuId(),
        Map.of(
            "skuId", f.skuId().toString(),
            "skuCode", "SKU-DRAFT",
            "skuName", "电池供电玩具",
            "hsCode", "85076000",
            "originCountryCode", "CN",
            "status", "DRAFT",
            "declarationReady", false,
            "blockers", List.of("ELEMENT_MISSING"),
            "blockerLabels", List.of("申报要素缺少电池类型")));

    var error = catchThrowable(() -> submit(f, "IDEM-14", "DHL"));
    assertThat(asStatus(error).getStatusCode().value()).isEqualTo(409);
    assertThat(asStatus(error).getReason()).contains("尚未审核通过");
    assertThat(asStatus(error).getReason()).contains("电池类型");
  }

  @Test
  void 申报HS编码与商品中心登记不一致时拒绝() {
    var f = seedPendingParcel("THIRD_PARTY");
    ensureCarrier("DHL");
    var input =
        new WaybillRequestInput(
            "IDEM-15", f.parcelId(), "DHL", "深圳发货仓", "CN", "Jane Buyer", "US",
            "1 Market St", "San Francisco", "94105", "USD", "",
            List.of(
                new WaybillCustomsItemInput(
                    f.skuId(), "SKU-1", "棉质衬衫", new BigDecimal("2"), "PCS",
                    new BigDecimal("10"), "99999999", "CN", null)));
    Throwable error =
        catchThrowable(
                () -> asA(() -> service.request(input, user, "Bearer tenant-a")));
    assertThat(asStatus(error).getReason()).contains("HS 编码与商品中心登记的不一致");
  }

  @Test
  void 同一个包裹里同一SKU申报两次被拒() {
    var f = seedPendingParcel("THIRD_PARTY");
    ensureCarrier("DHL");
    var input =
        new WaybillRequestInput(
            "IDEM-16", f.parcelId(), "DHL", "深圳发货仓", "CN", "Jane Buyer", "US",
            "1 Market St", "San Francisco", "94105", "USD", "",
            List.of(
                new WaybillCustomsItemInput(
                    f.skuId(), "SKU-1", "棉质衬衫", new BigDecimal("2"), "PCS",
                    new BigDecimal("10"), "61091000", "CN", null),
                new WaybillCustomsItemInput(
                    f.skuId(), "SKU-1", "棉质衬衫", new BigDecimal("3"), "PCS",
                    new BigDecimal("10"), "61091000", "CN", null)));
    Throwable error =
        catchThrowable(
                () -> asA(() -> service.request(input, user, "Bearer tenant-a")));
    assertThat(asStatus(error).getReason()).contains("申报了两次");
  }

  // ================================================================ 6. 重试

  @Test
  void 失败可重试且退避时刻可从承运商登记复算() {
    var carrier = "DHL";
    // backoff base = 60s，首退避 = 60s
    seedCarrier(carrier, 5, 60, 3);
    var f = seedPendingParcel("THIRD_PARTY");
    var row = submit(f, "IDEM-17", carrier);

    var dispatched =
        asA(() -> service.dispatch(new WaybillDispatchInput(carrier, 10), "Bearer tenant-a"));
    // ADAPTER_MISSING 是**不可重试**的：渠道没接通时反复重发只是白烧配额
    assertThat(dispatched.entries().get(0).outcome())
        .isEqualTo(WaybillAttemptOutcome.ADAPTER_MISSING);

    var failed = asA(() -> service.requestSummary(row.id()));
    assertThat(failed.status()).isEqualTo(WaybillRequestStatus.FAILED);
    assertThat(failed.failureReason()).contains("尚未接通");
    // 逐次尝试留了证据：失败原因逐字保留
    var detail = asA(() -> service.request(row.id()));
    assertThat(detail.attempts()).hasSize(1);
    assertThat(detail.attempts().get(0).attemptNo()).isEqualTo(1);
    assertThat(detail.attempts().get(0).failureReason()).contains("尚未接通");
  }

  @Test
  void 人工重试沿用同一条申请不会产生第二张运单() {
    var f = seedPendingParcel("THIRD_PARTY");
    var row = submit(f, "IDEM-18", "DHL");
    asA(
        () -> {
          service.dispatch(new WaybillDispatchInput("DHL", 10), "Bearer tenant-a");
          return null;
        });

    var retried =
        asA(() -> service.retry(row.id(), new WaybillRetryInput("人工重试"), user));
    assertThat(retried.requeued()).isTrue();
    assertThat(retried.retryCount()).isEqualTo(1);
    assertThat(retried.notBefore()).isNotNull();
    // 关键断言：重试不新建申请
    assertThat(countRequests()).isEqualTo(1);
    assertThat(asA(() -> service.requestSummary(row.id())).id()).isEqualTo(row.id());
  }

  @Test
  void 承运商拒收不可重试不再自动排队() {
    var f = seedPendingParcel("THIRD_PARTY");
    var row = submit(f, "IDEM-19", "DHL");
    asA(
        () -> {
          sql.execute(
              "insert into logistics_waybill_attempt (id,tenant_id,request_id,attempt_no,outcome,"
                  + " failure_reason,attempted_at) values (?,?,?,1,"
                  + "'CARRIER_REJECTED','收件地址不可达',?::timestamptz)",
              UUID.randomUUID(), a, row.id(), OffsetDateTime.parse("2026-10-02T00:00:00Z"));
          sql.execute(
              "update logistics_waybill_request set status='FAILED', failure_code='CARRIER_REJECTED',"
                  + " failure_reason='收件地址不可达', failed_at=?::timestamptz where id = ?",
              OffsetDateTime.parse("2026-10-02T00:00:00Z"), row.id());
          return null;
        });
    var detail = asA(() -> service.request(row.id()));
    assertThat(detail.attempts().get(0).outcome())
        .isEqualTo(WaybillAttemptOutcome.CARRIER_REJECTED);
    assertThat(detail.attempts().get(0).retryable()).isFalse();
  }

  // ================================================================ 7. 批量边界

  @Test
  void 批量逐条返回原因而不是整批吞掉() {
    ensureCarrier("DHL");
    var f1 = seedPendingParcel("THIRD_PARTY");
    var f2 = seedPendingParcel("THIRD_PARTY");
    var parcels = new java.util.ArrayList<UUID>();
    parcels.add(f1.parcelId());
    parcels.add(f2.parcelId());
    // 第二条用错误的 HS 编码：只有它失败，第一条仍要成功
    var result =
        asA(
            () ->
                service.batch(
                    new WaybillBatchRequestInput(
                        "BATCH-2", "DHL", "深圳发货仓", "CN", "Jane Buyer", "US", "", "", "",
                        "USD", "", parcels,
                        List.of(goodItem(f1.parcelId(), f1.skuId()), badItem(f2.parcelId(), f2.skuId()))),
                    user, "Bearer tenant-a"));
    assertThat(result.submitted())
        .as("第一条 HS 编码对得上，必须入队成功")
        .isEqualTo(1);
    assertThat(result.failed())
        .as("第二条 HS 编码对不上，必须逐条带原因失败，而不是整批吞掉")
        .isEqualTo(1);
    var failedEntry =
        result.entries().stream()
            .filter(e -> e.pendingParcelId().equals(f2.parcelId()))
            .findFirst()
            .orElseThrow();
    assertThat(failedEntry.failureReason()).contains("HS 编码");
    assertThat(failedEntry.requestNo()).as("失败的那条不该有单号").isEmpty();
  }

  @Test
  void 负页码退回第一页而不是报数据冲突() throws Exception {
    seedCarrier("DHL", 20, 60, 3);
    submit(seedPendingParcel("THIRD_PARTY"), "NEG-PAGE", "DHL");
    // 负页码若不钳位会算出负 OFFSET，PostgreSQL 报 OFFSET must not be negative，
    // 而异常处理器把 DB 异常映射成 409「请刷新后检查输入」——
    // 一个页码笔误在界面上显示成「数据冲突」，现场刷新十次都找不到原因。
    http.perform(get("/trade/logistics/waybill-applications/requests")
            .header("Authorization", "Bearer tenant-a")
            .param("page", "-3")
            .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  void 批量重复提交会标出幂等命中而不是又建一条() {
    var carrier = "DHL";
    seedCarrier(carrier, 10, 60, 3);
    var f = seedPendingParcel("THIRD_PARTY");
    var parcels = List.of(f.parcelId());
    var input =
        new WaybillBatchRequestInput(
            "BATCH-3", carrier, "深圳发货仓", "CN", "Jane Buyer", "US", "", "", "", "USD", "",
            parcels, List.of(goodItem(f.parcelId(), f.skuId())));

    asA(() -> (WaybillBatchResult) service.batch(input, user, "Bearer tenant-a"));
    var second = asA(() -> (WaybillBatchResult) service.batch(input, user, "Bearer tenant-a"));

    assertThat(second.entries().get(0).idempotentReplay()).isTrue();
    assertThat(countRequests()).isEqualTo(1);
  }

  // ================================================================ 8. 端点

  @Test
  void 端点走HTTP且租户来自已认证成员关系() throws Exception {
    var f = seedPendingParcel("THIRD_PARTY");
    ensureCarrier("DHL");
    var body = json.writeValueAsBytes(waybillInput(f, "IDEM-20", "DHL"));

    http.perform(
            post("/trade/logistics/waybill-applications/requests")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", "forged-tenant")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestNo").isNotEmpty())
        .andExpect(jsonPath("$.status").value("QUEUED"));

    // 伪造 header 没把数据写进别的租户：它落进了 A 的租户
    assertThat(asA(() -> sql.fetchOne("select count(*) from logistics_waybill_request")
            .get(0, Integer.class)))
        .isEqualTo(1);
    assertThat(asB(() -> sql.fetchOne("select count(*) from logistics_waybill_request")
            .get(0, Integer.class)))
        .isZero();
  }

  @Test
  void capabilities经HTTP可用() throws Exception {
    http.perform(get("/trade/logistics/waybill-applications/capabilities")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions").isArray())
        .andExpect(jsonPath("$.idempotencyNote").isNotEmpty());
  }

  @Test
  void 匿名访问被拒() throws Exception {
    http.perform(get("/trade/logistics/waybill-applications/capabilities"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void 只读身份不能提交申请() throws Exception {
    var f = seedPendingParcel("THIRD_PARTY");
    ensureCarrier("DHL");
    http.perform(
            post("/trade/logistics/waybill-applications/requests")
                .header("Authorization", "Bearer read-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(waybillInput(f, "IDEM-21", "DHL"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void 清单是真分页而不是全量返回() throws Exception {
    var carrier = "DHL";
    seedCarrier(carrier, 20, 60, 3);
    // 三个**不同**的包裹：同一包裹对同一承运商只允许一张有效申请
    //（重复申请 = 重复出号 = 重复计费，V128 的条件唯一索引会挡）。
    for (int i = 0; i < 3; i++) {
      submit(seedPendingParcel("THIRD_PARTY"), "PAGE-" + i, carrier);
    }
    MvcResult result =
        http.perform(get("/trade/logistics/waybill-applications/requests")
                .header("Authorization", "Bearer tenant-a")
                .param("page", "0")
                .param("size", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(3))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andReturn();
    assertThat(result.getResponse().getContentAsString()).contains("WAR-");
  }

  @Test
  void 跨租户读同一申请返回同一个404且响应体逐字相同() throws Exception {
    var f = seedPendingParcel("THIRD_PARTY");
    var row = submit(f, "IDEM-22", "DHL");
    var otherMissing = UUID.randomUUID();

    var crossTenant =
        http.perform(get("/trade/logistics/waybill-applications/requests/" + row.id())
                .header("Authorization", "Bearer tenant-b"))
            .andExpect(status().isNotFound())
            .andReturn();
    var notThere =
        http.perform(get("/trade/logistics/waybill-applications/requests/" + otherMissing)
                .header("Authorization", "Bearer tenant-b"))
            .andExpect(status().isNotFound())
            .andReturn();
    // 不给「拿 B 的 id 探测存在性」留信息面
    assertThat(crossTenant.getResponse().getContentAsString())
        .isEqualTo(notThere.getResponse().getContentAsString());
  }

  // ================================================================ 9. 多租户三断言

  @Test
  void 两租户互不可见() {
    var f = seedPendingParcel("THIRD_PARTY");
    submit(f, "TENANT-A", "DHL");

    assertThat(asA(() -> sql.fetchOne("select count(*) from logistics_waybill_request")
            .get(0, Integer.class)))
        .isEqualTo(1);
    assertThat(asB(() -> sql.fetchOne("select count(*) from logistics_waybill_request")
            .get(0, Integer.class)))
        .isZero();

    // B 的租户里承运商登记、队列、申报明细、事件流同样不可见
    assertThat(asB(() -> sql.fetchOne("select count(*) from logistics_carrier")
            .get(0, Integer.class)))
        .isZero();
    assertThat(asB(() -> sql.fetchOne("select count(*) from logistics_waybill_queue_entry")
            .get(0, Integer.class)))
        .isZero();
    assertThat(asB(() -> sql.fetchOne("select count(*) from logistics_customs_declaration_item")
            .get(0, Integer.class)))
        .isZero();
    assertThat(asB(() -> sql.fetchOne("select count(*) from logistics_waybill_event")
            .get(0, Integer.class)))
        .isZero();
  }

  @Test
  void 伪造租户头无效() {
    var f = seedPendingParcel("THIRD_PARTY");
    submit(f, "FORGE-1", "DHL");

    // 直接用迁移用户的连接、伪造 app.current_tenant：B 的 RLS 谓词仍然成立
    assertThat(
            asA(() -> sql.fetchOne("select count(*) from logistics_waybill_request")
                .get(0, Integer.class)))
        .isEqualTo(1);

    // 连接池归还后再取：A → B → A 三轮，每一轮看到的都必须是自己租户的行
    for (int round = 0; round < 3; round++) {
      assertThat(asA(() -> sql.fetchOne("select count(*) from logistics_waybill_request")
              .get(0, Integer.class)))
          .as("第 %d 轮 A 租户应看到自己那一条", round)
          .isEqualTo(1);
      assertThat(asB(() -> sql.fetchOne("select count(*) from logistics_waybill_request")
              .get(0, Integer.class)))
          .as("第 %d 轮 B 租户必须为空（连接池只有 1 条连接，串租户会在这里暴露）", round)
          .isZero();
    }
  }

  @Test
  void 连接池归还后不串租户_A到B到A三轮() {
    var f1 = seedPendingParcel("THIRD_PARTY");
    submit(f1, "POOL-A", "DHL");
    var aCount =
        asA(() -> sql.fetchOne("select count(*) from logistics_waybill_request").get(0, Integer.class));
    var aNo =
        asA(
            () ->
                sql.fetchOne("select request_no from logistics_waybill_request")
                    .get("request_no", String.class));

    for (int round = 0; round < 3; round++) {
      assertThat(asB(() -> sql.fetchOne("select count(*) from logistics_waybill_request")
              .get(0, Integer.class)))
          .isZero();
      assertThat(asA(() -> sql.fetchOne("select count(*) from logistics_waybill_request")
                  .get(0, Integer.class)))
          .isEqualTo(aCount);
      assertThat(
              asA(
                  () ->
                      sql.fetchOne("select request_no from logistics_waybill_request")
                          .get("request_no", String.class)))
          .isEqualTo(aNo);
    }
  }

  @Test
  void B租户建自己的申请不会与A的互相污染() {
    var fa = seedPendingParcel("THIRD_PARTY");
    // A 侧显式登记一条承运商：不依赖 submit() 的隐式登记，
    // 否则这条用例断言的「A 有 1 条」其实是在断言别的用例留下的副作用。
    seedCarrier("DHL", 20, 60, 3);
    // B 需要自己的一份待发货包裹（V124 是 9.4.1.1 的表，但两个租户各有各的行）
    asB(
        () -> {
          var unitId = UUID.randomUUID();
          var parcelId = UUID.randomUUID();
          sql.execute(
              "insert into shipment_pending_unit (id,tenant_id,source_packing_order_id,"
                  + " source_outbound_order_id,packed_at,parcel_count,total_weight_kg,warehouse_id,"
                  + " warehouse_ref,outbound_order_no,sales_order_id,sales_order_no,"
                  + " carrier_channel,carrier_code,synced_at)"
                  + " values (?,?,?,?,?::timestamptz,1,1.5,?,?,?,?,?,?,?,"
                  + "?::timestamptz)",
              unitId, b, UUID.randomUUID(), UUID.randomUUID(),
              OffsetDateTime.parse("2026-10-01T02:00:00Z"), UUID.randomUUID(), "US-WEST",
              "OB-B", UUID.randomUUID(), "SO-B", "THIRD_PARTY", "DHL",
              OffsetDateTime.parse("2026-10-01T02:00:00Z"));
          sql.execute(
              "insert into shipment_pending_parcel (id,tenant_id,unit_id,parcel_no,"
                  + " source_parcel_id,gross_weight_kg,length_mm,width_mm,height_mm,synced_at)"
                  + " values (?,?,?,1,?,1.5,300,200,100,?::timestamptz)",
              parcelId, b, unitId, UUID.randomUUID(), OffsetDateTime.parse("2026-10-01T02:00:00Z"));
          sql.execute(
              "insert into logistics_carrier (id,tenant_id,code,display_name,carrier_channel,"
                  + " adapter_key,connected,unavailable_reason,registered_by_membership_id,"
                  + " registered_at) values (?,?,'DHL','DHL 承运商','THIRD_PARTY',"
                  + " 'dhl',false,'未接通',?,?::timestamptz)",
              UUID.randomUUID(), b, user, OffsetDateTime.parse("2026-10-01T00:00:00Z"));
          return parcelId;
        });

    assertThat(asA(() -> sql.fetchOne("select count(*) from logistics_carrier")
            .get(0, Integer.class)))
        .as("A 租户应看到 submit() 里 ensureCarrier 建的那一条")
        .isEqualTo(1);
    assertThat(asB(() -> sql.fetchOne("select count(*) from logistics_carrier")
            .get(0, Integer.class)))
        .as("B 租户只看到自己刚插入的那一条，与 A 的同名承运商互不可见")
        .isEqualTo(1);

    // 两租户各自一条申请，互不可见
    // ⚠️ 同一个包裹对同一承运商只允许一张有效申请（重复申请 = 重复出号 = 重复计费），
    //    所以这一条只提交一次，用建在 A 租户的 fa。
    submit(fa, "SEP-A", "DHL");
    assertThat(asA(() -> sql.fetchOne("select count(*) from logistics_waybill_request")
            .get(0, Integer.class)))
        .isEqualTo(1);
    assertThat(asB(() -> sql.fetchOne("select count(*) from logistics_waybill_request")
            .get(0, Integer.class)))
        .isZero();
  }

  // ================================================================ 小工具

  int countRequests() {
    return asA(() -> sql.fetchOne("select count(*) from logistics_waybill_request").get(0, Integer.class));
  }

  int countQueueEntries() {
    return asA(
        () -> sql.fetchOne("select count(*) from logistics_waybill_queue_entry").get(0, Integer.class));
  }

  /** 取 ResponseStatusException 的状态码与原因（供断言 409/400 这类业务拒绝）。 */
  static ResponseStatusException asStatus(Throwable error) {
    var current = error;
    while (current != null) {
      if (current instanceof ResponseStatusException status) {
        return status;
      }
      current = current.getCause();
    }
    throw new AssertionError("异常链里没有 ResponseStatusException", error);
  }

  /** 递归取最内层异常消息（jOOQ/Spring 会包好几层）。 */
  static String rootMessage(Throwable error) {
    var current = error;
    String last = "";
    while (current != null) {
      if (current.getMessage() != null) {
        last = current.getMessage();
      }
      current = current.getCause();
    }
    return last;
  }
}
