package com.starcon.channel;

import static com.starcon.channel.ReturnDispositionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
 * 10.7.1.1 退货接收与处置决策。
 *
 * <p>本测试类钉死八组事：
 *
 * <ol>
 *   <li><b>🔴 处置方式必须显式给出</b>（蓝图加粗句）：请求少给一个 {@code dispositionMethod}
 *       ⇒ 422；<b>直接 INSERT 落库</b>少给一列 ⇒ <b>DB 拒</b>（23502 not-null violation），
 *       绝不会悄悄落成「转销售」。</li>
 *   <li><b>🔴 「可再售」必须由判定逻辑显式给出</b>：上游质检结论不是 RESALABLE ⇒ 拒；
 *       <b>上游根本没有质检结论</b> ⇒ 同样拒（没有证据不等于大概率能卖）；
 *       命中品牌方 / 平台政策的再销售限制 ⇒ 拒。三条都在服务端判，
 *       <b>DB 再钉一道</b>（CHECK + 触发器）。</li>
 *   <li><b>🔴 拿不准就落到人工决策</b>：没有决策的接收登记是
 *       {@code PENDING_DECISION}，不是「转销售」。</li>
 *   <li><b>处置单必须逐字复述它的决策</b>：去向相同、数量相同、决策只能被消费一次
 *       （DB 触发器，绕过服务层直接写 SQL 一样被拒）。</li>
 *   <li><b>成本与残值是两种口径</b>：成本明细币种必须与处置单成本币种逐字相同；
 *       残值币种可以不同；合计是查询时现算的 SUM（不落表）。</li>
 *   <li><b>库存状态变更指令只出事实</b>：指令恒为 PENDING、消费方恒为 7.4.1.1
 *       （消费方未交付），运行角色对它没有 UPDATE / DELETE 权限。</li>
 *   <li><b>上游读不到 ≠ 上游没有</b>：上游 5xx ⇒ 503（理由写「读不到」），
 *       绝不返回空集合冒充「上游没有退货」。</li>
 *   <li><b>时刻一律钉死</b>：timestamptz 往返不偏 8 小时，且断言**写回的值**。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReturnDispositionIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer trade;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 复用 identity 已注册的 channel 权限码：本模块不新增权限码，identity 零改动。 */
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

  // ---- 上游夹具（trade 11.2 退货管理的**真实 API 形状**）----
  /** receiptId → 实收单（头 + 行）。 */
  static final Map<String, Map<String, Object>> receipts = new ConcurrentHashMap<>();
  /** receiptId → 质检单列表行。 */
  static final Map<String, List<Map<String, Object>>> inspections = new ConcurrentHashMap<>();
  /** inspectionId → 质检留痕。 */
  static final Map<String, List<Map<String, Object>>> inspectionEvents = new ConcurrentHashMap<>();
  /** 这些 receiptId 让上游回 5xx（用来证明「读不到」与「没有」是两件事）。 */
  static final java.util.Set<String> brokenReceipts = Collections.synchronizedSet(new java.util.HashSet<>());
  /** 端口转发过来的 bearer（证明租户隔离是两道：上游按调用方的成员关系解析租户）。 */
  static final List<String> upstreamBears = Collections.synchronizedList(new ArrayList<>());

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

      trade = HttpServer.create(new InetSocketAddress(0), 0);
      trade.createContext(
          "/trade/return-receipts",
          e -> {
            String bear = e.getRequestHeaders().getFirst("Authorization");
            if (bear != null) {
              upstreamBears.add(bear);
            }
            String id = e.getRequestURI().getPath().substring("/trade/return-receipts/".length());
            if (brokenReceipts.contains(id)) {
              // 上游真的坏了：必须映射成 503「读不到」，不能返回空集合。
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            Map<String, Object> receipt = receipts.get(id);
            if (receipt == null) {
              e.sendResponseHeaders(404, -1);
              e.close();
              return;
            }
            writeJson(e, receipt);
          });
      trade.createContext(
          "/trade/return-quality/inspections",
          e -> {
            String bear = e.getRequestHeaders().getFirst("Authorization");
            if (bear != null) {
              upstreamBears.add(bear);
            }
            String path = e.getRequestURI().getPath();
            String query = e.getRequestURI().getQuery();
            Map<String, String> params = new LinkedHashMap<>();
            if (query != null) {
              for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                  params.put(pair.substring(0, eq), pair.substring(eq + 1));
                }
              }
            }
            if (path.length() > "/trade/return-quality/inspections/".length()) {
              // 详情：质检留痕
              String inspectionId = path.substring("/trade/return-quality/inspections/".length());
              Map<String, Object> detail = new LinkedHashMap<>();
              detail.put("events", inspectionEvents.getOrDefault(inspectionId, List.of()));
              writeJson(e, detail);
              return;
            }
            // 列表：按 receiptId + status 过滤（与 trade 侧同形）
            String receiptId = params.get("receiptId");
            String status = params.get("status");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> row : inspections.getOrDefault(receiptId, List.of())) {
              if (status != null && !status.isEmpty() && !status.equals(row.get("inspectionStatus"))) {
                continue;
              }
              rows.add(row);
            }
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("rows", rows);
            page.put("page", 0);
            page.put("size", 200);
            page.put("total", rows.size());
            writeJson(e, page);
          });
      trade.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static void writeJson(com.sun.net.httpserver.HttpExchange e, Object payload) throws java.io.IOException {
    byte[] body = new ObjectMapper().writeValueAsBytes(payload);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(200, body.length);
    e.getResponseBody().write(body);
    e.close();
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

  @Autowired ReturnDispositionService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  AccessService.Access actorA, actorB;

  @BeforeEach
  void setup() {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    receipts.clear();
    inspections.clear();
    inspectionEvents.clear();
    brokenReceipts.clear();
    upstreamBears.clear();
    truncateAll();
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    trade.stop(0);
    pg.stop();
  }

  void truncateAll() {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // CASCADE：这些表当前还没有外键引用，但以后任何车道给它们加外键时，
      // 引用方不在同一条 truncate 语句里 PG 会直接拒
      // （cannot truncate a table referenced in a foreign key constraint），级联一并清干净。
      s.execute("truncate return_disposition_event, return_disposition_cost,"
          + " return_inventory_change_instruction, return_disposition_order,"
          + " return_disposition_decision, return_resale_restriction,"
          + " return_receiving_registration cascade");
    } catch (java.sql.SQLException e) {
      throw new RuntimeException(e);
    }
  }

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  // ==================================================================
  // 上游夹具
  // ==================================================================

  static final UUID RECEIPT = UUID.randomUUID();
  static final UUID LINE_RESALABLE = UUID.randomUUID();
  static final UUID LINE_DEFECTIVE = UUID.randomUUID();
  static final UUID LINE_UNINSPECTED = UUID.randomUUID();
  static final UUID RECEIPT_DRAFT = UUID.randomUUID();
  static final UUID RECEIPT_BROKEN = UUID.randomUUID();

  /** 铺一套上游实收 + 质检夹具（每个用例重铺，避免用例之间互相污染）。 */
  void seedUpstream() {
    Map<String, Object> receipt = new LinkedHashMap<>();
    Map<String, Object> head = new LinkedHashMap<>();
    head.put("id", RECEIPT);
    head.put("receiptNo", "RR-2026-0001");
    head.put("receiptStatus", "POSTED");
    receipt.put("head", head);
    receipt.put(
        "lines",
        List.of(
            line(LINE_RESALABLE, "SKU-RESALABLE", "可再销售样机", "B-001", "10"),
            line(LINE_DEFECTIVE, "SKU-DEFECTIVE", "残次样机", "B-002", "5"),
            line(LINE_UNINSPECTED, "SKU-NOQC", "没质检的样机", "B-003", "3")));
    receipts.put(RECEIPT.toString(), receipt);

    Map<String, Object> draft = new LinkedHashMap<>();
    Map<String, Object> draftHead = new LinkedHashMap<>();
    draftHead.put("id", RECEIPT_DRAFT);
    draftHead.put("receiptNo", "RR-2026-0002");
    draftHead.put("receiptStatus", "DRAFT");
    draft.put("head", draftHead);
    draft.put("lines", List.of(line(UUID.randomUUID(), "SKU-DRAFT", "还在录的货", "B-009", "1")));
    receipts.put(RECEIPT_DRAFT.toString(), draft);

    brokenReceipts.add(RECEIPT_BROKEN.toString());

    // 质检：只有 L1 判成可再销售，L2 判成残次，L3 根本没有质检单
    inspections.put(
        RECEIPT.toString(),
        List.of(
            inspection(UUID.randomUUID(), RECEIPT, LINE_RESALABLE, "RESALABLE", "120.00", "30.00", "USD"),
            inspection(UUID.randomUUID(), RECEIPT, LINE_DEFECTIVE, "DEFECTIVE", "60.00", "0.00", "USD")));
  }

  static Map<String, Object> line(UUID id, String sku, String name, String batch, String qty) {
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("id", id);
    line.put("skuCode", sku);
    line.put("skuName", name);
    line.put("batchNo", batch);
    line.put("receivedQty", new BigDecimal(qty));
    return line;
  }

  static Map<String, Object> inspection(
      UUID id, UUID receiptId, UUID lineId, String verdict, String original, String salvage, String currency) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", id);
    row.put("receiptId", receiptId);
    row.put("receiptLineId", lineId);
    row.put("inspectionStatus", "CONCLUDED");
    row.put("verdict", verdict);
    row.put("originalValue", new BigDecimal(original));
    row.put("salvageAmount", new BigDecimal(salvage));
    row.put("salvageCurrency", currency);
    inspectionEvents.put(
        id.toString(),
        List.of(Map.of("eventType", "CONCLUDED", "occurredAt", "2026-03-01T02:00:00Z")));
    return row;
  }

  RegisterReceivingRequest registerRequest(UUID lineId, String warehouseType) {
    return new RegisterReceivingRequest(
        RECEIPT, lineId, warehouseType, "WH-DE-1", "DE", receivedQty(lineId),
        OffsetDateTime.of(2026, 3, 4, 5, 6, 7, 0, ZoneOffset.UTC), "BRAND-A", "AMAZON", "集成测试登记");
  }

  /** 上游实收行的实收数量（登记数量不得超过它）。 */
  static BigDecimal receivedQty(UUID lineId) {
    if (LINE_DEFECTIVE.equals(lineId)) {
      return new BigDecimal("5");
    }
    if (LINE_UNINSPECTED.equals(lineId)) {
      return new BigDecimal("3");
    }
    return new BigDecimal("10");
  }

  ReturnReceivingRow register(UUID lineId, String warehouseType) {
    return as(actorA, "channel.operation.create",
        () -> service.register(registerRequest(lineId, warehouseType), "Bearer tenant-a"));
  }

  // ==================================================================
  // 1. 能力声明
  // ==================================================================

  @Test
  @DisplayName("10.7.1.1 能力声明：三大去向 + 转销售两道否决 + 未交付下游标注 owner")
  void capabilitiesDeclareTheExplicitDecisionRule() {
    var caps = as(actorA, "channel.operation.read", () -> service.capabilities());

    assertThat(caps.dispositionMethods())
        .containsExactlyInAnyOrder("RESALE", "RETURN_TO_COUNTRY", "DISCARD");
    assertThat(caps.warehouseTypes()).containsExactlyInAnyOrder("LOCAL", "OVERSEAS", "PLATFORM");
    assertThat(caps.costTypes())
        .containsExactlyInAnyOrder("RETURN_FREIGHT", "DESTROY_FEE", "PLATFORM_HANDLING_FEE", "OTHER");
    assertThat(caps.decisionNote()).contains("没有默认值");

    var decision =
        caps.dimensions().stream()
            .filter(d -> d.dimension().equals("disposition_decision"))
            .findFirst()
            .orElseThrow();
    assertThat(decision.available()).isTrue();
    assertThat(decision.description()).contains("显式");

    var veto =
        caps.dimensions().stream()
            .filter(d -> d.dimension().equals("resale_veto"))
            .findFirst()
            .orElseThrow();
    assertThat(veto.available()).isTrue();

    // 🔴 12 财务（finance 服务）不存在：成本以本服务自有事实落地，owner 写清楚
    var cost =
        caps.dimensions().stream()
            .filter(d -> d.dimension().equals("disposition_cost"))
            .findFirst()
            .orElseThrow();
    assertThat(cost.source()).contains("return_disposition_cost");
    assertThat(cost.impact()).contains("本服务").contains("12 财务");
    assertThat(caps.financeNote()).contains("不存在");

    // 🔴 7 库存消费方未交付：指令恒为 PENDING，本模块不动 warehouse 的表
    var inventory =
        caps.dimensions().stream()
            .filter(d -> d.dimension().equals("inventory_instruction"))
            .findFirst()
            .orElseThrow();
    assertThat(inventory.ownerModule()).isEqualTo("7.4.1.1");
    assertThat(inventory.description()).contains("只出事实");
    assertThat(inventory.impact()).contains("PENDING").contains("7.4.1.1");
  }

  // ==================================================================
  // 2. 接收登记
  // ==================================================================

  @Test
  @DisplayName("10.7.1.1 接收登记：本地仓 / 海外仓 / 平台仓三视角 + 时刻往返不偏 8 小时")
  void registerReceivingAcrossWarehouseTypes() {
    seedUpstream();

    var row = register(LINE_RESALABLE, "OVERSEAS");
    assertThat(row.registrationNo()).startsWith("RCV");
    assertThat(row.warehouseType()).isEqualTo("OVERSEAS");
    assertThat(row.warehouseTypeLabel()).isEqualTo("海外仓");
    assertThat(row.skuCode()).isEqualTo("SKU-RESALABLE");
    assertThat(row.receiptNo()).isEqualTo("RR-2026-0001");
    assertThat(row.receivedQty()).isEqualByComparingTo("10");
    // 🔴 时刻：断言**写回的值**，不是「没抛异常」
    assertThat(row.receivedAt())
        .as("timestamptz 往返必须逐字相同（用 Timestamp.valueOf 会静默偏 −28800s）")
        .isEqualTo(OffsetDateTime.of(2026, 3, 4, 5, 6, 7, 0, ZoneOffset.UTC));

    // 🔴 还没有任何决策 ⇒ 处置状态是「待人工决策」，**不是**「转销售」
    assertThat(row.dispositionState()).isEqualTo("PENDING_DECISION");
    assertThat(row.dispositionMethod()).isNull();
    assertThat(row.decisionId()).isNull();

    // 一条上游实收行只登记一次
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> register(LINE_RESALABLE, "OVERSEAS")))
        .as("同一条实收行重复登记必须被拒").isNotNull();
  }

  @Test
  @DisplayName("10.7.1.1 接收登记：上游实收单还没入库（POSTED）⇒ 409，不拿会改行的单据签字")
  void registerRejectsUnpostedReceipt() {
    seedUpstream();
    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(actorA, "channel.operation.create",
                    () ->
                        service.register(
                            new RegisterReceivingRequest(
                                RECEIPT_DRAFT, UUID.randomUUID(), "LOCAL", "WH-DE-1", "DE",
                                BigDecimal.ONE, null, null, null, null),
                            "Bearer tenant-a")));
    assertThat(error).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(((org.springframework.web.server.ResponseStatusException) error).getStatusCode().value())
        .isEqualTo(409);
  }

  @Test
  @DisplayName("10.7.1.1 上游读不到 ⇒ 503（理由写「读不到」），绝不返回空集合冒充「没有退货」")
  void upstreamUnavailableIs503() {
    seedUpstream();
    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(actorA, "channel.operation.create",
                    () ->
                        service.register(
                            new RegisterReceivingRequest(
                                RECEIPT_BROKEN, UUID.randomUUID(), "LOCAL", "WH-DE-1", "DE",
                                BigDecimal.ONE, null, null, null, null),
                            "Bearer tenant-a")));
    assertThat(error).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    var rse = (org.springframework.web.server.ResponseStatusException) error;
    assertThat(rse.getStatusCode().value()).isEqualTo(503);
    assertThat(rse.getReason()).contains("读不到");
  }

  @Test
  @DisplayName("10.7.1.1 端口转发的是调用方自己的 bearer（本服务绝不自己传租户号）")
  void portForwardsCallerBearer() {
    seedUpstream();
    register(LINE_RESALABLE, "LOCAL");
    assertThat(upstreamBears).contains("Bearer tenant-a");
    assertThat(upstreamBears)
        .as("本服务不许发明 X-Tenant-Id 传给上游")
        .noneMatch(b -> b != null && b.contains("Tenant-Id"));
  }

  // ==================================================================
  // 3. 🔴 处置方式必须显式
  // ==================================================================

  @Test
  @DisplayName("10.7.1.1 🔴 不给处置方式 ⇒ 422，绝不悄悄落成「转销售」")
  void decisionWithoutMethodIsRejected() {
    seedUpstream();
    var row = register(LINE_RESALABLE, "LOCAL");

    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(actorA, "channel.operation.create",
                    () ->
                        service.decide(
                            new DecideDispositionRequest(
                                row.id(), null, new BigDecimal("1"), "随便判一下", null),
                            "Bearer tenant-a")));
    assertThat(error).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    var rse = (org.springframework.web.server.ResponseStatusException) error;
    assertThat(rse.getStatusCode().value()).isEqualTo(422);
    assertThat(rse.getReason()).contains("RESALE").contains("RETURN_TO_COUNTRY").contains("DISCARD");

    // 一条决策都没落库
    assertThat(as(actorA, "channel.operation.read", () -> service.decisions(null, null, 0, 50)).rows())
        .isEmpty();
  }

  @Test
  @DisplayName("10.7.1.1 🔴 空串 / 未知取值同样被拒（白名单只有三值）")
  void decisionWithUnknownMethodIsRejected() {
    seedUpstream();
    var row = register(LINE_RESALABLE, "LOCAL");
    for (String bad :
        new String[] {"", "  ", "KEEP", "RESALABLE", "默认转销售", "转销售（可再售）", "RESALE,RETURN_TO_COUNTRY"}) {
      var error =
          org.assertj.core.api.Assertions.catchThrowable(
              () ->
                  as(actorA, "channel.operation.create",
                      () ->
                          service.decide(
                              new DecideDispositionRequest(
                                  row.id(), bad, new BigDecimal("1"), "随便判一下", null),
                              "Bearer tenant-a")));
      assertThat(error)
          .as("处置方式「%s」必须被拒", bad)
          .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    assertThat(as(actorA, "channel.operation.read", () -> service.decisions(null, null, 0, 50)).rows())
        .as("被拒的处置方式一条都不许落库").isEmpty();
  }

  @Test
  @DisplayName("10.7.1.1 处置方式大小写归一化是**显式值的写法差异**，不是默认值")
  void lowercaseExplicitMethodIsNormalizedNotDefaulted() {
    seedUpstream();
    var row = register(LINE_RESALABLE, "LOCAL");
    var decision =
        as(actorA, "channel.operation.create",
            () ->
                service.decide(
                    new DecideDispositionRequest(
                        row.id(), "resale", new BigDecimal("1"), "显式写了 resale", null),
                    "Bearer tenant-a"));
    assertThat(decision.dispositionMethod()).isEqualTo("RESALE");
    // 落库的仍是**人显式给的那一个去向**，不是服务层挑的
    assertThat(decision.decisionBasis()).isEqualTo("显式写了 resale");
  }

  @Test
  @DisplayName("10.7.1.1 🔴 直接 INSERT 落库不给处置方式 ⇒ DB 拒（23502），没有列默认值")
  void directInsertWithoutMethodFailsInDatabase() {
    seedUpstream();
    var row = register(LINE_RESALABLE, "LOCAL");

    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                execAsTenant(
                    a,
                    "insert into return_disposition_decision"
                        + " (id,tenant_id,registration_id,receipt_id,receipt_line_id,receipt_no,"
                        + "  sku_code,sku_name,batch_no,brand_code,platform_code,decided_qty,"
                        + "  decision_basis,decided_by,decided_at,resale_restricted,status,decision_no)"
                        + " values ('" + UUID.randomUUID() + "','" + a + "','" + row.id() + "','"
                        + row.receiptId() + "','" + row.receiptLineId() + "','" + row.receiptNo() + "','"
                        + row.skuCode() + "','" + row.skuName() + "','" + row.batchNo() + "','"
                        + row.brandCode() + "','" + row.platformCode() + "',1,'绕过服务层', '"
                        + user + "', now(), false, 'RECORDED', 'RDC-HACK')"));
    assertThat(error).isInstanceOf(SQLException.class);
    assertThat(((SQLException) error).getSQLState())
        .as("不给处置方式必须是 not-null violation（列上不许有 DEFAULT 'RESALE'）")
        .isEqualTo("23502");
  }

  @Test
  @DisplayName("10.7.1.1 🔴 直接 INSERT 判成转销售但没有「可再销售」的质检结论 ⇒ DB 拒（23514）")
  void directInsertResaleWithoutQualityFailsInDatabase() {
    seedUpstream();
    var row = register(LINE_RESALABLE, "LOCAL");

    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                execAsTenant(
                    a,
                    "insert into return_disposition_decision"
                        + " (id,tenant_id,registration_id,receipt_id,receipt_line_id,receipt_no,"
                        + "  sku_code,sku_name,batch_no,brand_code,platform_code,disposition_method,"
                        + "  decided_qty,decision_basis,decided_by,decided_at,resale_restricted,status,decision_no)"
                        + " values ('" + UUID.randomUUID() + "','" + a + "','" + row.id() + "','"
                        + row.receiptId() + "','" + row.receiptLineId() + "','" + row.receiptNo() + "','"
                        + row.skuCode() + "','" + row.skuName() + "','" + row.batchNo() + "','"
                        + row.brandCode() + "','" + row.platformCode() + "','RESALE',"
                        + "1,'绕过服务层', '" + user + "', now(), false, 'RECORDED', 'RDC-HACK2')"));
    assertThat(error).isInstanceOf(SQLException.class);
    assertThat(((SQLException) error).getSQLState())
        .as("判成转销售就必须带 RESALABLE 的质检结论")
        .isEqualTo("23514");
  }

  // ==================================================================
  // 4. 🔴 转销售的两道否决
  // ==================================================================

  @Test
  @DisplayName("10.7.1.1 🔴 质检判成残次 ⇒ 转销售被拒；退运回国 / 弃置销毁可判")
  void resaleRejectedWhenQualityNotResalable() {
    seedUpstream();
    var row = register(LINE_DEFECTIVE, "LOCAL");

    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(actorA, "channel.operation.create",
                    () ->
                        service.decide(
                            new DecideDispositionRequest(
                                row.id(), "RESALE", new BigDecimal("5"), "看着还行", null),
                            "Bearer tenant-a")));
    assertThat(error).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    var rse = (org.springframework.web.server.ResponseStatusException) error;
    assertThat(rse.getStatusCode().value()).isEqualTo(422);
    assertThat(rse.getReason()).contains("残次");

    // 另两个去向可判 —— 判定逻辑不替人挑，只是给出依据
    var discard =
        as(actorA, "channel.operation.create",
            () ->
                service.decide(
                    new DecideDispositionRequest(
                        row.id(), "DISCARD", new BigDecimal("5"), "残次品没有销售价值", null),
                    "Bearer tenant-a"));
    assertThat(discard.dispositionMethod()).isEqualTo("DISCARD");
    assertThat(discard.status()).isEqualTo("RECORDED");
  }

  @Test
  @DisplayName("10.7.1.1 🔴 上游根本没有质检结论 ⇒ 转销售被拒（没有证据不等于能再卖）")
  void resaleRejectedWithoutAnyQualityVerdict() {
    seedUpstream();
    var row = register(LINE_UNINSPECTED, "LOCAL");

    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(actorA, "channel.operation.create",
                    () ->
                        service.decide(
                            new DecideDispositionRequest(
                                row.id(), "RESALE", new BigDecimal("3"), "看起来是好的", null),
                            "Bearer tenant-a")));
    assertThat(error).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    var rse = (org.springframework.web.server.ResponseStatusException) error;
    assertThat(rse.getStatusCode().value()).isEqualTo(422);
    assertThat(rse.getReason()).contains("质检");
  }

  @Test
  @DisplayName("10.7.1.1 🔴 命中再销售限制（品牌方 / 平台政策）⇒ 转销售被拒")
  void resaleRejectedByResaleRestriction() {
    seedUpstream();
    var row = register(LINE_RESALABLE, "LOCAL");

    // 先把上游质检结论摆成可再销售（否则被质量理由挡住，看不到政策这一道）
    var restriction =
        as(actorA, "channel.operation.create",
            () ->
                service.saveRestriction(
                    new SaveRestrictionRequest(
                        "BRAND", "BRAND-A", "BRAND_REQUIREMENT", "品牌方要求全部召回销毁",
                        OffsetDateTime.now().minusDays(1), null)));
    assertThat(restriction.status()).isEqualTo("ACTIVE");

    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(actorA, "channel.operation.create",
                    () ->
                        service.decide(
                            new DecideDispositionRequest(
                                row.id(), "RESALE", new BigDecimal("1"), "客户说不影响使用", null),
                            "Bearer tenant-a")));
    assertThat(error).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    var rse = (org.springframework.web.server.ResponseStatusException) error;
    assertThat(rse.getStatusCode().value()).isEqualTo(422);
    assertThat(rse.getReason()).contains("再销售限制").contains("BRAND-A");

    // 🔴 撤销限制后，同一批货可以判成转销售（限制是配置，撤销即生效）
    as(actorA, "channel.operation.update",
        () -> service.revokeRestriction(restriction.id(), new RevokeRestrictionRequest("品牌方改口径了")));
    var decision =
        as(actorA, "channel.operation.create",
            () ->
                service.decide(
                    new DecideDispositionRequest(
                        row.id(), "RESALE", new BigDecimal("1"), "品牌方已同意再销售", null),
                    "Bearer tenant-a"));
    assertThat(decision.dispositionMethod()).isEqualTo("RESALE");
    assertThat(decision.resaleRestricted()).isFalse();
  }

  @Test
  @DisplayName("10.7.1.1 🔴 单品限制（SKU）同样否决转销售")
  void resaleRejectedBySkuRestriction() {
    seedUpstream();
    var row = register(LINE_RESALABLE, "LOCAL");
    as(actorA, "channel.operation.create",
        () ->
            service.saveRestriction(
                new SaveRestrictionRequest(
                    "SKU", "SKU-RESALABLE", "PLATFORM_POLICY", "平台要求该 SKU 一律销毁",
                    OffsetDateTime.now().minusDays(1), null)));

    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(actorA, "channel.operation.create",
                    () ->
                        service.decide(
                            new DecideDispositionRequest(
                                row.id(), "RESALE", new BigDecimal("1"), "再卖一次试试", null),
                            "Bearer tenant-a")));
    assertThat(error).isNotNull();
    assertThat(((org.springframework.web.server.ResponseStatusException) error).getReason())
        .contains("SKU-RESALABLE");
  }

  @Test
  @DisplayName("10.7.1.1 🔴 判定依据为空 ⇒ 拒（转卖/退运/弃置对利润影响巨大，必须留下凭什么）")
  void decisionRequiresBasis() {
    seedUpstream();
    var row = register(LINE_RESALABLE, "LOCAL");
    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(actorA, "channel.operation.create",
                    () ->
                        service.decide(
                            new DecideDispositionRequest(
                                row.id(), "RETURN_TO_COUNTRY", new BigDecimal("1"), "  ", null),
                            "Bearer tenant-a")));
    assertThat(error).isNotNull();
    assertThat(((org.springframework.web.server.ResponseStatusException) error).getStatusCode().value())
        .isEqualTo(400);
  }

  @Test
  @DisplayName("10.7.1.1 判定数量超过接收数量 ⇒ 拒")
  void decisionQuantityCannotExceedReceived() {
    seedUpstream();
    var row = register(LINE_RESALABLE, "LOCAL");
    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(actorA, "channel.operation.create",
                    () ->
                        service.decide(
                            new DecideDispositionRequest(
                                row.id(), "RETURN_TO_COUNTRY", new BigDecimal("11"), "多写了一件", null),
                            "Bearer tenant-a")));
    assertThat(error).isNotNull();
    assertThat(((org.springframework.web.server.ResponseStatusException) error).getStatusCode().value())
        .isEqualTo(422);
  }

  @Test
  @DisplayName("10.7.1.1 可行性判定（只读）：三个去向各自允许还是被挡，且不替人做决定")
  void eligibilityExplainsBlockingReasons() {
    seedUpstream();
    var row = register(LINE_DEFECTIVE, "LOCAL");

    var eligibility = as(actorA, "channel.operation.read",
        () -> service.eligibility(row.id(), "Bearer tenant-a"));

    var resale = eligibility.methods().stream().filter(m -> m.method().equals("RESALE")).findFirst().orElseThrow();
    assertThat(resale.allowed()).isFalse();
    assertThat(resale.blockingReasons()).isNotEmpty();
    assertThat(eligibility.qualityVerdict()).isEqualTo("DEFECTIVE");
    assertThat(eligibility.qualityVerdictLabel()).isEqualTo("残次");

    var repatriation =
        eligibility.methods().stream().filter(m -> m.method().equals("RETURN_TO_COUNTRY")).findFirst().orElseThrow();
    assertThat(repatriation.allowed()).isTrue();
    var discard = eligibility.methods().stream().filter(m -> m.method().equals("DISCARD")).findFirst().orElseThrow();
    assertThat(discard.allowed()).isTrue();
  }

  // ==================================================================
  // 5. 处置单
  // ==================================================================

  @Test
  @DisplayName("10.7.1.1 处置单：去向来自决策、成本逐笔记、残值单独记、合计查询时现算")
  void issueOrderWithCostsAndSalvage() {
    seedUpstream();
    var row = register(LINE_RESALABLE, "LOCAL");
    var decision =
        as(actorA, "channel.operation.create",
            () ->
                service.decide(
                    new DecideDispositionRequest(
                        row.id(), "RESALE", new BigDecimal("4"), "质检可再销售且无政策限制", null),
                    "Bearer tenant-a"));

    var order =
        as(actorA, "channel.operation.create",
            () ->
                service.issueOrder(
                    new IssueDispositionOrderRequest(
                        decision.id(), "USD", "EUR", new BigDecimal("120.00"),
                        OffsetDateTime.of(2026, 3, 5, 6, 7, 8, 0, ZoneOffset.UTC), "集成测试下发",
                        List.of(
                            new ReturnCostDraft("RETURN_FREIGHT", new BigDecimal("12.34"), "USD",
                                "FREIGHT-1", null, "退运代理费"),
                            new ReturnCostDraft("PLATFORM_HANDLING_FEE", new BigDecimal("5.00"), "USD",
                                "AMAZON", null, "平台处理费")))));

    assertThat(order.dispositionNo()).startsWith("RDO");
    assertThat(order.dispositionMethod()).isEqualTo("RESALE");
    assertThat(order.disposedQty()).isEqualByComparingTo("4");
    assertThat(order.costCurrency()).isEqualTo("USD");
    assertThat(order.salvageCurrency()).isEqualTo("EUR");
    assertThat(order.salvageAmount()).isEqualByComparingTo("120.00");
    // 🔴 合计是现算的 SUM（不落表）
    assertThat(order.totalCostAmount()).isEqualByComparingTo("17.34");
    assertThat(order.costItemCount()).isEqualTo(2);
    assertThat(order.status()).isEqualTo("ISSUED");
    assertThat(order.issuedAt())
        .as("timestamptz 往返必须逐字相同")
        .isEqualTo(OffsetDateTime.of(2026, 3, 5, 6, 7, 8, 0, ZoneOffset.UTC));

    // 决策被消费 ⇒ 终态，不能再下发一次
    var again =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(actorA, "channel.operation.create",
                    () ->
                        service.issueOrder(
                            new IssueDispositionOrderRequest(
                                decision.id(), "USD", "EUR", BigDecimal.ZERO, null, null, List.of()))));
    assertThat(again).isNotNull();
    assertThat(((org.springframework.web.server.ResponseStatusException) again).getStatusCode().value())
        .isEqualTo(409);
  }

  @Test
  @DisplayName("10.7.1.1 🔴 处置单必须逐字复述决策：绕过服务层写 SQL 也一样被拒")
  void orderMustMatchItsDecision() {
    seedUpstream();
    var row = register(LINE_RESALABLE, "LOCAL");
    var decision =
        as(actorA, "channel.operation.create",
            () ->
                service.decide(
                    new DecideDispositionRequest(
                        row.id(), "DISCARD", new BigDecimal("4"), "没有销售价值", null),
                    "Bearer tenant-a"));

    // 数量对不上 ⇒ 触发器拒
    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                execAsTenant(
                    a,
                    "insert into return_disposition_order"
                        + " (id,tenant_id,decision_id,registration_id,receipt_id,receipt_line_id,"
                        + "  receipt_no,sku_code,sku_name,batch_no,brand_code,platform_code,"
                        + "  disposition_method,disposed_qty,cost_currency,salvage_currency,salvage_amount,"
                        + "  status,issued_by,issued_at,disposition_no)"
                        + " values ('" + UUID.randomUUID() + "','" + a + "','" + decision.id() + "','"
                        + row.id() + "','" + row.receiptId() + "','" + row.receiptLineId() + "','"
                        + row.receiptNo() + "','" + row.skuCode() + "','" + row.skuName() + "','"
                        + row.batchNo() + "','" + row.brandCode() + "','" + row.platformCode() + "',"
                        + "'RESALE', 99, 'USD','USD', 0, 'ISSUED', '" + user + "', now(), 'RDO-HACK')"));
    assertThat(error).isInstanceOf(SQLException.class);
    assertThat(((SQLException) error).getSQLState()).isEqualTo("23514");
  }

  @Test
  @DisplayName("10.7.1.1 🔴 成本明细币种必须与处置单成本币种逐字相同（混币种的合计没有意义）")
  void costCurrencyMustMatchOrderCurrency() {
    seedUpstream();
    var row = register(LINE_RESALABLE, "LOCAL");
    var decision =
        as(actorA, "channel.operation.create",
            () ->
                service.decide(
                    new DecideDispositionRequest(
                        row.id(), "RETURN_TO_COUNTRY", new BigDecimal("2"), "退运回国", null),
                    "Bearer tenant-a"));
    var order =
        as(actorA, "channel.operation.create",
            () ->
                service.issueOrder(
                    new IssueDispositionOrderRequest(
                        decision.id(), "USD", "EUR", BigDecimal.ZERO, null, null, List.of())));

    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(actorA, "channel.operation.create",
                    () ->
                        service.recordCost(
                            order.id(),
                            new RecordCostRequest(
                                "RETURN_FREIGHT", new BigDecimal("9.99"), "EUR", "F1", null, "错币种"))));
    assertThat(error).isNotNull();
    assertThat(((org.springframework.web.server.ResponseStatusException) error).getStatusCode().value())
        .isEqualTo(422);
  }

  @Test
  @DisplayName("10.7.1.1 库存状态变更指令只出事实：恒为 PENDING、消费方恒为 7.4.1.1")
  void inventoryInstructionIsOnlyProduced() {
    seedUpstream();
    var row = register(LINE_RESALABLE, "LOCAL");
    var decision =
        as(actorA, "channel.operation.create",
            () ->
                service.decide(
                    new DecideDispositionRequest(
                        row.id(), "RETURN_TO_COUNTRY", new BigDecimal("2"), "退运回国", null),
                    "Bearer tenant-a"));
    var order =
        as(actorA, "channel.operation.create",
            () ->
                service.issueOrder(
                    new IssueDispositionOrderRequest(
                        decision.id(), "USD", "EUR", BigDecimal.ZERO, null, null, List.of())));

    var detail = as(actorA, "channel.operation.read", () -> service.orderDetail(order.id()));
    assertThat(detail.instruction()).isNotNull();
    assertThat(detail.instruction().instructionStatus()).isEqualTo("PENDING");
    assertThat(detail.instruction().consumerModule()).isEqualTo("7.4.1.1");
    assertThat(detail.instruction().instructionType()).isEqualTo("HOLD_FOR_REPATRIATION");
    assertThat(detail.instruction().targetInventoryState()).isEqualTo("RETURN_PENDING");
    assertThat(detail.instruction().qty()).isEqualByComparingTo("2");

    // 🔴 运行角色对本表没有 UPDATE / DELETE ⇒ 「在本模块里把它标成已消费」没有 SQL 路径
    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                execAsTenant(
                    a,
                    "update return_inventory_change_instruction set instruction_status='CONSUMED'"));
    assertThat(error).isInstanceOf(SQLException.class);
  }

  @Test
  @DisplayName("10.7.1.1 撤销处置单：撤销不撤回已产出的指令，撤销后不能再记成本")
  void cancelOrderKeepsInstructionAndBlocksCosts() {
    seedUpstream();
    var row = register(LINE_RESALABLE, "LOCAL");
    var decision =
        as(actorA, "channel.operation.create",
            () ->
                service.decide(
                    new DecideDispositionRequest(
                        row.id(), "DISCARD", new BigDecimal("2"), "弃置销毁", null),
                    "Bearer tenant-a"));
    var order =
        as(actorA, "channel.operation.create",
            () ->
                service.issueOrder(
                    new IssueDispositionOrderRequest(
                        decision.id(), "USD", "USD", BigDecimal.ZERO, null, null, List.of())));

    var cancelled =
        as(actorA, "channel.operation.update",
            () -> service.cancelOrder(order.id(), new CancelDispositionOrderRequest("判错了", null)));
    assertThat(cancelled.status()).isEqualTo("CANCELLED");

    var detail = as(actorA, "channel.operation.read", () -> service.orderDetail(order.id()));
    assertThat(detail.instruction())
        .as("撤销不撤回已产出的指令：指令是「当时下达过什么」的事实")
        .isNotNull();

    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(actorA, "channel.operation.create",
                    () ->
                        service.recordCost(
                            order.id(),
                            new RecordCostRequest("DESTROY_FEE", new BigDecimal("3.00"), "USD", "D1", null, ""))));
    assertThat(error).isNotNull();
    assertThat(((org.springframework.web.server.ResponseStatusException) error).getStatusCode().value())
        .isEqualTo(409);
  }

  // ==================================================================
  // 6. 概览 / 列表 / 留痕
  // ==================================================================

  @Test
  @DisplayName("10.7.1.1 概览：待人工决策数、三个去向分布、成本与残值按币种分行")
  void overviewSplitsByCurrencyAndCountsPending() {
    seedUpstream();
    var row = register(LINE_RESALABLE, "LOCAL");
    assertThat(as(actorA, "channel.operation.read", () -> service.overview()).pendingDecisionCount())
        .isEqualTo(1);

    var decision =
        as(actorA, "channel.operation.create",
            () ->
                service.decide(
                    new DecideDispositionRequest(
                        row.id(), "DISCARD", new BigDecimal("2"), "弃置销毁", null),
                    "Bearer tenant-a"));
    as(actorA, "channel.operation.create",
        () ->
            service.issueOrder(
                new IssueDispositionOrderRequest(
                    decision.id(), "USD", "EUR", new BigDecimal("10.00"), null, null,
                    List.of(new ReturnCostDraft("DESTROY_FEE", new BigDecimal("4.00"), "USD", "D1", null, "")))));

    var overview = as(actorA, "channel.operation.read", () -> service.overview());
    assertThat(overview.registrationCount()).isEqualTo(1);
    assertThat(overview.issuedOrderCount()).isEqualTo(1);
    assertThat(overview.discardCount()).isEqualTo(1);
    assertThat(overview.resaleCount()).isZero();
    // 🔴 成本与残值分币种，绝不相加
    assertThat(overview.costTotals()).hasSize(1);
    assertThat(overview.costTotals().get(0).currency()).isEqualTo("USD");
    assertThat(overview.costTotals().get(0).amount()).isEqualByComparingTo("4.00");
    assertThat(overview.salvageTotals()).hasSize(1);
    assertThat(overview.salvageTotals().get(0).currency()).isEqualTo("EUR");
    assertThat(overview.salvageTotals().get(0).amount()).isEqualByComparingTo("10.00");
  }

  @Test
  @DisplayName("10.7.1.1 真分页真筛选：处置状态筛选与关键字搜索")
  void registrationsFilterAndPaginate() {
    seedUpstream();
    register(LINE_RESALABLE, "LOCAL");
    register(LINE_DEFECTIVE, "OVERSEAS");

    var pending = as(actorA, "channel.operation.read",
        () -> service.registrations(null, null, "PENDING_DECISION", 0, 50));
    assertThat(pending.rows()).hasSize(2);
    assertThat(pending.total()).isEqualTo(2);

    var byWarehouse = as(actorA, "channel.operation.read",
        () -> service.registrations(null, "OVERSEAS", null, 0, 50));
    assertThat(byWarehouse.rows()).hasSize(1);
    assertThat(byWarehouse.rows().get(0).warehouseType()).isEqualTo("OVERSEAS");

    var byKeyword = as(actorA, "channel.operation.read",
        () -> service.registrations("SKU-DEFECTIVE", null, null, 0, 50));
    assertThat(byKeyword.rows()).hasSize(1);

    var firstPage = as(actorA, "channel.operation.read",
        () -> service.registrations(null, null, null, 0, 1));
    assertThat(firstPage.rows()).hasSize(1);
    assertThat(firstPage.total()).isEqualTo(2);
    var secondPage = as(actorA, "channel.operation.read",
        () -> service.registrations(null, null, null, 1, 1));
    assertThat(secondPage.rows()).hasSize(1);
    assertThat(secondPage.rows().get(0).id()).isNotEqualTo(firstPage.rows().get(0).id());

    var bad =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> as(actorA, "channel.operation.read",
                () -> service.registrations(null, null, "NOT_A_STATE", 0, 50)));
    assertThat(bad).isNotNull();
  }

  @Test
  @DisplayName("10.7.1.1 留痕追加式：接收 → 决策 → 下发 → 指令 → 成本逐条可追")
  void eventsTellTheWholeStory() {
    seedUpstream();
    var row = register(LINE_RESALABLE, "LOCAL");
    var decision =
        as(actorA, "channel.operation.create",
            () ->
                service.decide(
                    new DecideDispositionRequest(
                        row.id(), "RESALE", new BigDecimal("2"), "可再销售", null),
                    "Bearer tenant-a"));
    var order =
        as(actorA, "channel.operation.create",
            () ->
                service.issueOrder(
                    new IssueDispositionOrderRequest(
                        decision.id(), "USD", "USD", BigDecimal.ZERO, null, null,
                        List.of(new ReturnCostDraft("OTHER", new BigDecimal("1.00"), "USD", "V", null, "")))));

    var events = as(actorA, "channel.operation.read",
        () -> service.events(null, null, order.id(), 0, 50));
    assertThat(events.rows()).extracting(e -> e.eventType())
        .contains("ORDER_ISSUED", "COST_RECORDED", "INSTRUCTION_ISSUED");

    var byRegistration = as(actorA, "channel.operation.read",
        () -> service.events(row.id(), null, null, 0, 50));
    assertThat(byRegistration.rows()).extracting(e -> e.eventType())
        .contains("RECEIVED", "DECIDED");

    // 追加式：UPDATE 被触发器拦
    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> execAsTenant(a, "update return_disposition_event set detail='改了'"));
    assertThat(error).isInstanceOf(SQLException.class);
  }

  @Test
  @DisplayName("10.7.1.1 HTTP 端点：能力声明 / 概览 / 列表都真的通")
  void httpEndpointsRespond() throws Exception {
    seedUpstream();
    register(LINE_RESALABLE, "LOCAL");

    http.perform(get("/channel/return-dispositions/capabilities")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dispositionMethods").isArray())
        .andExpect(jsonPath("$.dispositionMethods.length()").value(3));
    http.perform(get("/channel/return-dispositions/overview")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.registrationCount").value(1));
    http.perform(get("/channel/return-dispositions/registrations")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1));
    http.perform(get("/channel/return-dispositions/registrations")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[0].dispositionState").value("PENDING_DECISION"));
    // 无会话一律 401
    http.perform(get("/channel/return-dispositions/overview"))
        .andExpect(status().isUnauthorized());
  }

  // ==================================================================
  // 工具
  // ==================================================================

  static final String ALL_SCOPE_GRANTS =
      "[{\"permissions\":[\"channel.operation.read\",\"channel.operation.create\","
          + "\"channel.operation.update\",\"channel.operation.delete\"],\"scopeMode\":\"ALL\"}]";

  /** 以应用角色（channel_app）执行 SQL —— RLS 真实生效。 */
  static void execAsTenant(UUID tenant, String sql) throws SQLException {
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(sql);
    }
  }
}
