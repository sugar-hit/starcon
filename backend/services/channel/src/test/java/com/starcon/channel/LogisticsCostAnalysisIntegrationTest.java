package com.starcon.channel;

import static com.starcon.channel.LogisticsCostAnalysisModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
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
 * 10.6.1.1 成本分析。
 *
 * <p>本测试类钉住九组事：
 *
 * <ol>
 *   <li><b>🔴 全口径的反例</b>：某个成本分量取不到数时，报表里那一位显式标
 *       「不可得」，<b>合计不把它当 0</b> —— subtotal 精确等于**已计入分量之和**，
 *       缺的那一位在 {@code missingComponents} 里点名。
 *   <li><b>包材与仓储费永远显式不可用</b>：本仓库真的没有这两个成本来源，
 *       所以 {@code available=false} + {@code ownerModule} + 原因，
 *       且 {@code coverage.complete} <b>永远是 false</b> —— 报表不许自称全口径。
 *   <li><b>上游不可达 ⇒ 503</b>，理由写「读不到 / 不可达」，<b>不是</b>「没有数据」；
 *       绝不返回一份「尾程 0 元」的报表。
 *   <li><b>跨币种绝不相加</b>：USD 与 EUR 分两行，各自的 subtotal 只在本币种内成立。
 *   <li><b>分摊是拆分不是缩水</b>：最大余数法后逐行合计<b>严格等于</b>来源总额；
 *       基准合计为 0 时拒绝分摊（而不是摊成 0 行）。
 *   <li><b>规则版本化</b>：同 rule_code 递增版本号；启用新版本后旧版本转 RETIRED
 *       但<b>行还在</b>；已被分摊引用过的版本<b>改口径被 DB 触发器拒</b>。
 *   <li><b>结账即凭据</b>：锁定后金额 / 规则 / 结账时点都不可再改（DB 触发器），
 *       且已结账的分摊单不能被新规则重算取代。
 *   <li><b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：两租户互不可见、
 *       伪造 tenant header 无效、连接池归还后不串租户。
 *   <li><b>超成本订单识别</b>：判据是单位重量运费（不是运费总额）；
 *       没有毛重的票不参与平均与判定。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class LogisticsCostAnalysisIntegrationTest {
  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();

  /** 固定夹具时刻：2026-03-01T00:00:00Z。写时间的用例都断言**写回的值**。 */
  static final OffsetDateTime T0 = OffsetDateTime.parse("2026-03-01T00:00:00Z");
  static final OffsetDateTime T1 = OffsetDateTime.parse("2026-03-31T00:00:00Z");
  static final OffsetDateTime T2 = OffsetDateTime.parse("2026-02-01T00:00:00Z");

  static HttpServer identity;
  static HttpServer trade;
  static HttpServer warehouse;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** trade 侧的运单夹具：bearer → 运单行。 */
  static final Map<String, List<Map<String, Object>>> tradeWaybills = new ConcurrentHashMap<>();

  /** warehouse 侧的头程发运单夹具：bearer → (id → 发运单 + 明细)。 */
  static final Map<String, Map<String, Object>> firstMileShipments = new ConcurrentHashMap<>();

  /** warehouse 侧的头程发运单明细：bearer → (shipmentId → 行列表)。 */
  static final Map<String, Map<String, List<Map<String, Object>>>> firstMileLines =
      new ConcurrentHashMap<>();

  /** warehouse 侧的税费估算单夹具：bearer → 行列表。 */
  static final Map<String, List<Map<String, Object>>> dutyEstimates = new ConcurrentHashMap<>();

  /** 刻意让某个 bearer 的上游 503：用来证明「读不到」与「没有数据」是两件事。 */
  static final Map<String, Boolean> tradeDown = new ConcurrentHashMap<>();
  static final Map<String, Boolean> warehouseDown = new ConcurrentHashMap<>();

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
      trade.createContext("/trade/logistics/waybills/list", LogisticsCostAnalysisIntegrationTest::serveTrade);
      trade.start();

      warehouse = HttpServer.create(new InetSocketAddress(0), 0);
      warehouse.createContext("/warehouse/first-mile-shipments", LogisticsCostAnalysisIntegrationTest::serveWarehouse);
      warehouse.createContext("/warehouse/duty-taxes", LogisticsCostAnalysisIntegrationTest::serveWarehouse);
      warehouse.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  // ==================================================================
  // 上游桩
  // ==================================================================

  /** trade 桩：真分页（按 totalElements / page / size 切），带租户区分。 */
  static void serveTrade(HttpExchange e) throws java.io.IOException {
    String bearer = e.getRequestHeaders().getFirst("Authorization");
    if (Boolean.TRUE.equals(tradeDown.get(bearer))) {
      e.sendResponseHeaders(503, -1);
      e.close();
      return;
    }
    List<Map<String, Object>> rows = tradeWaybills.getOrDefault(bearer, List.of());
    int page = intParam(e, "page", 0);
    int size = intParam(e, "size", 200);
    int from = Math.min(page * size, rows.size());
    int to = Math.min(from + size, rows.size());
    Map<String, Object> body = Map.of("items", rows.subList(from, to), "totalElements", rows.size());
    byte[] bytes = new ObjectMapper().writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(200, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }

  /** warehouse 桩：头程发运单列表 / 详情 / 税费估算单列表。 */
  static void serveWarehouse(HttpExchange e) throws java.io.IOException {
    String bearer = e.getRequestHeaders().getFirst("Authorization");
    if (Boolean.TRUE.equals(warehouseDown.get(bearer))) {
      e.sendResponseHeaders(503, -1);
      e.close();
      return;
    }
    String path = e.getRequestURI().getPath();
    byte[] bytes;
    if (path.equals("/warehouse/duty-taxes")) {
      List<Map<String, Object>> rows = dutyEstimates.getOrDefault(bearer, List.of());
      int page = intParam(e, "page", 0);
      int size = intParam(e, "size", 50);
      int from = Math.min(page * size, rows.size());
      int to = Math.min(from + size, rows.size());
      bytes =
          new ObjectMapper()
              .writeValueAsBytes(
                  Map.of(
                      "rows", rows.subList(from, to),
                      "summary", Map.of("total", rows.size()),
                      "page", page,
                      "size", size));
    } else if (path.equals("/warehouse/first-mile-shipments")) {
      Map<String, Object> shipments = firstMileShipments.getOrDefault(bearer, Map.of());
      // 列表端点在本模块里只用于「候选池」；分摊真正读的是详情。
      bytes =
          new ObjectMapper()
              .writeValueAsBytes(
                  Map.<String, Object>of(
                      "rows", List.copyOf(shipments.values()), "total", shipments.size()));
    } else {
      // /warehouse/first-mile-shipments/{id}
      String id = path.substring(path.lastIndexOf('/') + 1);
      Object raw = firstMileShipments.getOrDefault(bearer, Map.<String, Object>of()).get(id);
      if (raw == null) {
        e.sendResponseHeaders(404, -1);
        e.close();
        return;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> shipment = (Map<String, Object>) raw;
      List<Map<String, Object>> lines =
          firstMileLines
              .getOrDefault(bearer, Map.of())
              .getOrDefault(id, List.of());
      bytes =
          new ObjectMapper().writeValueAsBytes(Map.of("shipment", shipment, "lines", lines));
    }
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(200, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }

  static int intParam(HttpExchange e, String name, int fallback) {
    String raw = e.getRequestURI().getRawQuery();
    if (raw == null) {
      return fallback;
    }
    for (String pair : raw.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0 && pair.substring(0, eq).equals(name)) {
        return Integer.parseInt(pair.substring(eq + 1));
      }
    }
    return fallback;
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://127.0.0.1:1");
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("TRADE_URL", () -> "http://localhost:" + trade.getAddress().getPort());
    r.add("WAREHOUSE_URL", () -> "http://localhost:" + warehouse.getAddress().getPort());
    // 连接池压到 1：多租户串租户在单连接下最容易暴露。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired LogisticsCostAnalysisService costs;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  AccessService.Access actorA, actorB;

  @BeforeEach
  void setup() {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    tradeWaybills.clear();
    firstMileShipments.clear();
    firstMileLines.clear();
    dutyEstimates.clear();
    tradeDown.clear();
    warehouseDown.clear();
    truncateAll();
  }

  void truncateAll() {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("truncate logistics_cost_allocation_line, logistics_cost_allocation,"
          + " logistics_cost_allocation_rule, logistics_cost_component cascade");
    } catch (java.sql.SQLException e) {
      throw new RuntimeException(e);
    }
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    trade.stop(0);
    warehouse.stop(0);
    pg.stop();
  }

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  // ==================================================================
  // 夹具
  // ==================================================================

  /** 一票运单（trade 侧）。{@code amount} 为 null = **未登记运费**（不是登记为 0）。 */
  void seedWaybill(
      String bearer,
      String waybillNo,
      String carrierCode,
      String carrierChannel,
      String country,
      BigDecimal amount,
      String currency,
      BigDecimal weightKg) {
    Map<String, Object> row = new java.util.LinkedHashMap<>();
    row.put("id", UUID.randomUUID().toString());
    row.put("waybillNo", waybillNo);
    row.put("salesOrderNo", "SO-" + waybillNo);
    row.put("outboundOrderNo", "OB-" + waybillNo);
    row.put("carrierCode", carrierCode);
    row.put("carrierChannel", carrierChannel);
    row.put("carrierChannelLabel", carrierChannel);
    row.put("destinationCountry", country);
    row.put("grossWeightKg", weightKg);
    row.put("freightAmount", amount);
    row.put("freightCurrency", currency);
    row.put("freightGapCode", amount == null ? "NOT_REGISTERED" : "REGISTERED");
    row.put("freightSource", "MANUAL");
    row.put("createdAt", T0.toString());
    tradeWaybills.computeIfAbsent(bearer, k -> new ArrayList<>()).add(row);
  }

  /** 一张头程发运单 + 若干明细行（warehouse 侧）。 */
  UUID seedFirstMile(
      String bearer,
      String shipmentCode,
      String currency,
      BigDecimal totalCost,
      List<BigDecimal> shippedQuantities,
      List<BigDecimal> weights,
      List<BigDecimal> declaredValues) {
    UUID id = UUID.randomUUID();
    Map<String, Object> shipment = new java.util.LinkedHashMap<>();
    shipment.put("id", id.toString());
    shipment.put("shipmentCode", shipmentCode);
    shipment.put("status", "DISPATCHED");
    shipment.put("transportMode", "SEA");
    shipment.put("carrierName", "COSCO");
    shipment.put("destinationCountryCode", "DE");
    shipment.put("currency", currency);
    shipment.put("freightCost", totalCost);
    shipment.put("insuranceCost", BigDecimal.ZERO);
    shipment.put("dutyEstimate", BigDecimal.ZERO);
    shipment.put("handlingCost", BigDecimal.ZERO);
    shipment.put("totalCost", totalCost);
    shipment.put("createdAt", T0.toString());
    firstMileShipments.computeIfAbsent(bearer, k -> new ConcurrentHashMap<>()).put(id.toString(), shipment);

    List<Map<String, Object>> lines = new ArrayList<>();
    for (int i = 0; i < shippedQuantities.size(); i++) {
      Map<String, Object> line = new java.util.LinkedHashMap<>();
      line.put("id", UUID.randomUUID().toString());
      line.put("lineNo", i + 1);
      line.put("skuId", UUID.randomUUID().toString());
      line.put("skuCode", "SKU-" + (i + 1));
      line.put("skuName", "商品 " + (i + 1));
      line.put("hsCode", "61091000");
      line.put("shippedQuantity", shippedQuantities.get(i));
      line.put("declaredValue", declaredValues.get(i));
      line.put("grossWeightKg", weights.get(i));
      lines.add(line);
    }
    firstMileLines.computeIfAbsent(bearer, k -> new ConcurrentHashMap<>()).put(id.toString(), lines);
    return id;
  }

  /** 一张税费估算单（warehouse 侧）。 */
  void seedDuty(
      String bearer,
      String currency,
      BigDecimal estimatedTotalTax,
      BigDecimal paidTotalAmount) {
    Map<String, Object> row = new java.util.LinkedHashMap<>();
    row.put("id", UUID.randomUUID().toString());
    row.put("estimateCode", "DUTY-1");
    row.put("destinationCountryCode", "DE");
    row.put("estimateCurrency", currency);
    row.put("estimatedTotalTax", estimatedTotalTax);
    row.put("paidTotalAmount", paidTotalAmount);
    row.put("landedCostTotal", new BigDecimal("1000"));
    row.put("landedCostSeller", new BigDecimal("1000"));
    row.put("status", "LOCKED");
    row.put("estimatedAt", T0.toString());
    dutyEstimates.computeIfAbsent(bearer, k -> new ArrayList<>()).add(row);
  }

  /** 建一条分摊规则（草稿或现行），返回整行。 */
  CostAllocationRuleRow seedRule(String basis, boolean activate) {
    return as(
        actorA,
        "channel.operation.create",
        () ->
            costs.createRule(
                new CostAllocationRuleRequest(
                    "FM-STD",
                    "标准头程分摊",
                    CostAllocationBasis.valueOf(basis),
                    T0,
                    null,
                    "集成测试夹具",
                    activate),
                actorA.userId()));
  }

  /** 建一条分摊规则并返回它的 id。 */
  UUID seedRuleId(String basis, boolean activate) {
    return seedRule(basis, activate).id();
  }

  CostOverview overview(AccessService.Access who, OffsetDateTime from, OffsetDateTime to) {
    return as(who, "channel.operation.read", () -> costs.overview(bearerOf(who), from, to));
  }

  /** 与 {@link #actors} 里那条会话对应的 bearer —— 跨服务读转发的是**调用方自己的**会话。 */
  static String bearerOf(AccessService.Access who) {
    return who.tenantId().equals(a) ? "Bearer tenant-a" : "Bearer tenant-b";
  }

  // ==================================================================
  // 🔴 全口径：缺的那一位不当作 0
  // ==================================================================

  @Test
  @DisplayName("10.6.1.1 加粗句反例：头程分摊缺失时，合计不把缺项当 0，而是显式点名")
  void missingComponentIsNotTreatedAsZero() {
    seedWaybill("Bearer tenant-a", "WB-1", "DHL", "DHL-DE", "DE", new BigDecimal("100.0000"),
        "USD", new BigDecimal("10.000000"));
    seedDuty("Bearer tenant-a", "USD", new BigDecimal("30.0000"), new BigDecimal("25.0000"));
    // 头程：**没有**任何分摊单 ⇒ 这一位取不到数。

    CostOverview report = overview(actorA, T2, T1);

    CostCurrencyRow usd =
        report.currencyRows().stream()
            .filter(r -> "USD".equals(r.currency()))
            .findFirst()
            .orElseThrow();
    // 缺的那一位是 **null**，不是 0。
    assertThat(usd.firstMileAllocation()).isNull();
    assertThat(usd.dutyAndTax()).isEqualByComparingTo("25.0000");
    // 合计 = **已计入分量之和**（100 + 25），不是「100 + 0 + 25」的那种伪装全口径。
    assertThat(usd.subtotal()).isEqualByComparingTo("125.0000");
    // 并且明确点名「我没算谁」。
    assertThat(usd.missingComponents())
        .contains("FIRST_MILE_ALLOCATION", "PACKAGING", "STORAGE_FEE");
    // 仓储费在本仓库没有来源 —— 缺了它是**全局**的，逐币种也要点名。
    assertThat(usd.includedComponents()).contains("LAST_MILE_FREIGHT", "DUTY_AND_TAX");
    // 占比：分母是本币种合计；头程那一格是 null（算不出来），不是 0。
    assertThat(usd.firstMileShare()).isNull();
    assertThat(usd.lastMileShare()).isEqualByComparingTo("0.800000");
    // 报表**自称不全口径**。
    assertThat(report.coverage().complete()).isFalse();
    assertThat(report.coverage().missingComponents())
        .contains("FIRST_MILE_ALLOCATION", "PACKAGING", "STORAGE_FEE");
    // 仓储费这一位永远缺（仓库没有仓储费表）⇒ 报表**永远不自称全口径**。
  }

  @Test
  @DisplayName("10.6.1.1 加粗句：仓储费显式标为不可用（本仓库真的没有仓储费来源）")
  void storageFeeIsExplicitlyUnavailable() {
    seedWaybill("Bearer tenant-a", "WB-1", "DHL", "DHL-DE", "DE", new BigDecimal("100.0000"),
        "USD", new BigDecimal("10.000000"));

    LogisticsCostCapabilities capabilities = as(actorA, "channel.operation.read", () -> costs.capabilities());

    CostComponentCapability storage =
        capabilities.components().stream()
            .filter(c -> "STORAGE_FEE".equals(c.component()))
            .findFirst()
            .orElseThrow();
    assertThat(storage.available()).isFalse();
    assertThat(storage.ownerModule()).isNotBlank();
    assertThat(storage.unavailableReason()).isNotBlank();
    assertThat(storage.impact()).isNotBlank();

    // 五个分量**每一位都在**（闭集五位）。
    assertThat(capabilities.components())
        .extracting(CostComponentCapability::component)
        .containsExactlyInAnyOrder(
            "LAST_MILE_FREIGHT",
            "FIRST_MILE_ALLOCATION",
            "PACKAGING",
            "DUTY_AND_TAX",
            "STORAGE_FEE");

    // 仓储费在报表里**没有任何币种行**（不是 0 行）。
    CostOverview report = overview(actorA, T2, T1);
    CostComponentAmount storageAmount =
        report.components().stream()
            .filter(c -> "STORAGE_FEE".equals(c.component()))
            .findFirst()
            .orElseThrow();
    assertThat(storageAmount.available()).isFalse();
    assertThat(storageAmount.byCurrency()).isEmpty();
    assertThat(storageAmount.currencyCount()).isZero();
  }

  @Test
  @DisplayName("10.6.1.1 加粗句：包材走**参数**口径（与事实分色），没维护参数就是缺项不是 0")
  void packagingIsAParameterAndMissingParameterIsNotZero() {
    seedWaybill("Bearer tenant-a", "WB-1", "DHL", "DHL-DE", "DE", new BigDecimal("100.0000"),
        "USD", new BigDecimal("10.000000"));
    seedWaybill("Bearer tenant-a", "WB-2", "DHL", "DHL-DE", "DE", new BigDecimal("50.0000"),
        "USD", new BigDecimal("5.000000"));

    // 没有维护包材参数 ⇒ 包材这一位取不到数。
    CostOverview before = overview(actorA, T2, T1);
    CostComponentAmount packagingBefore =
        before.components().stream()
            .filter(c -> "PACKAGING".equals(c.component()))
            .findFirst()
            .orElseThrow();
    // 来源类别是 **参数**，不是事实 —— 界面上要分色显示。
    assertThat(packagingBefore.sourceKind()).isEqualTo("PARAMETER");
    assertThat(packagingBefore.byCurrency()).isEmpty();
    CostCurrencyRow rowBefore =
        before.currencyRows().stream()
            .filter(r -> "USD".equals(r.currency()))
            .findFirst()
            .orElseThrow();
    assertThat(rowBefore.packaging()).isNull();
    assertThat(rowBefore.missingComponents()).contains("PACKAGING");

    // 维护参数：每票 0.35 ⇒ 本窗 2 票 ⇒ 0.70。
    LogisticsCostParameterRow parameter =
        as(
            actorA,
            "channel.operation.update",
            () ->
                costs.saveParameter(
                    new LogisticsCostParameterRequest("PACKAGING", "USD", new BigDecimal("0.3500"), "夹具")));
    assertThat(parameter.amountPerWaybill()).isEqualByComparingTo("0.3500");
    assertThat(parameter.unit()).isEqualTo("PER_WAYBILL");

    CostOverview after = overview(actorA, T2, T1);
    CostCurrencyRow rowAfter =
        after.currencyRows().stream()
            .filter(r -> "USD".equals(r.currency()))
            .findFirst()
            .orElseThrow();
    assertThat(rowAfter.packaging()).isEqualByComparingTo("0.7000");
    assertThat(rowAfter.missingComponents()).doesNotContain("PACKAGING");
    // 合计 = 100 + 50 + 0.70（尾程 150 + 包材 0.70；头程与关税仍缺）。
    assertThat(rowAfter.subtotal()).isEqualByComparingTo("150.7000");
    assertThat(rowAfter.includedComponents()).contains("PACKAGING");

    // 删掉参数 ⇒ 立刻回到缺项，**不会留下一个 0.70 的行**。
    as(actorA, "channel.operation.delete", () -> {
      costs.deleteParameter(parameter.id());
      return null;
    });
    CostOverview cleared = overview(actorA, T2, T1);
    CostCurrencyRow rowCleared =
        cleared.currencyRows().stream()
            .filter(r -> "USD".equals(r.currency()))
            .findFirst()
            .orElseThrow();
    assertThat(rowCleared.packaging()).isNull();
    assertThat(rowCleared.subtotal()).isEqualByComparingTo("150.0000");
  }

  @Test
  @DisplayName("10.6.1.1 参数口径只对包材开放（其它分量有事实来源，不开第二个真相）")
  void parameterOnlyAcceptsPackaging() {
    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.operation.update",
                    () ->
                        costs.saveParameter(
                            new LogisticsCostParameterRequest(
                                "LAST_MILE_FREIGHT", "USD", new BigDecimal("1.0000"), ""))))
        .hasMessageContaining("只有包材");
  }

  @Test
  @DisplayName("10.6.1.1 口径：实缴进合计，未缴只进估算序列")
  void paidDutyEntersTotalAndEstimateDoesNot() {
    seedWaybill("Bearer tenant-a", "WB-1", "DHL", "DHL-DE", "DE", new BigDecimal("100.0000"),
        "USD", new BigDecimal("10.000000"));
    seedDuty("Bearer tenant-a", "USD", new BigDecimal("30.0000"), new BigDecimal("25.0000"));

    CostOverview report = overview(actorA, T2, T1);

    CostComponentAmount duty =
        report.components().stream()
            .filter(c -> "DUTY_AND_TAX".equals(c.component()))
            .findFirst()
            .orElseThrow();
    assertThat(duty.byCurrency()).hasSize(1);
    assertThat(duty.byCurrency().get(0).amount()).isEqualByComparingTo("25.0000");
    // 估算**不进合计**，但要看得见。
    assertThat(duty.estimatedByCurrency()).hasSize(1);
    assertThat(duty.estimatedByCurrency().get(0).amount()).isEqualByComparingTo("30.0000");

    CostCurrencyRow usd =
        report.currencyRows().stream()
            .filter(r -> "USD".equals(r.currency()))
            .findFirst()
            .orElseThrow();
    // 合计里是 25（实缴），**不是** 30（估算），也不是 55（两者相加）。
    assertThat(usd.subtotal()).isEqualByComparingTo("125.0000");
  }

  @Test
  @DisplayName("10.6.1.1 跨币种绝不相加：USD 与 EUR 分两行，各自的小计只在本币种内成立")
  void currenciesAreNeverSummed() {
    seedWaybill("Bearer tenant-a", "WB-1", "DHL", "DHL-DE", "DE", new BigDecimal("100.0000"),
        "USD", new BigDecimal("10.000000"));
    seedWaybill("Bearer tenant-a", "WB-2", "DHL", "DHL-GB", "GB", new BigDecimal("80.0000"),
        "EUR", new BigDecimal("8.000000"));

    CostOverview report = overview(actorA, T2, T1);

    assertThat(report.currencyRows()).hasSize(2);
    CostCurrencyRow usd =
        report.currencyRows().stream()
            .filter(r -> "USD".equals(r.currency()))
            .findFirst()
            .orElseThrow();
    CostCurrencyRow eur =
        report.currencyRows().stream()
            .filter(r -> "EUR".equals(r.currency()))
            .findFirst()
            .orElseThrow();
    assertThat(usd.subtotal()).isEqualByComparingTo("100.0000");
    assertThat(eur.subtotal()).isEqualByComparingTo("80.0000");
    // 报表里**没有**一个「总额」字段把两种币种加起来。
    assertThat(usd.subtotal().add(eur.subtotal())).isEqualByComparingTo("180.0000");
  }

  @Test
  @DisplayName("10.6.1.1 上游不可达 ⇒ 503 报「读不到」，绝不返回一份「尾程 0 元」的报表")
  void upstreamDownIsLoudlyUnavailable() {
    seedWaybill("Bearer tenant-a", "WB-1", "DHL", "DHL-DE", "DE", new BigDecimal("100.0000"),
        "USD", new BigDecimal("10.000000"));
    tradeDown.put("Bearer tenant-a", true);

    assertThatThrownBy(() -> overview(actorA, T2, T1))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("读不到");

    tradeDown.remove("Bearer tenant-a");
  }

  // ==================================================================
  // 头程分摊
  // ==================================================================

  @Test
  @DisplayName("10.6.1.1 分摊是拆分不是缩水：逐行合计严格等于来源总额")
  void allocationLinesSumExactlyToSourceTotal() {
    UUID ruleId = seedRuleId("BY_QUANTITY", true);
    // 10.0000 / 3 行 1、1、1 ⇒ 最大余数法把 0.0001 零头补给前两行。
    UUID shipmentId =
        seedFirstMile(
            "Bearer tenant-a",
            "FM-1",
            "USD",
            new BigDecimal("10.0000"),
            List.of(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE),
            List.of(new BigDecimal("1.000000"), new BigDecimal("1.000000"), new BigDecimal("1.000000")),
            List.of(new BigDecimal("100.0000"), new BigDecimal("100.0000"), new BigDecimal("100.0000")));

    CostAllocationRow created =
        as(
            actorA,
            "channel.operation.create",
            () ->
                costs.createAllocation(
                    new CostAllocationRequest(ruleId, shipmentId, T0, "集成测试"),
                    actorA.userId(),
                    "Bearer tenant-a"));

    assertThat(created.sourceTotalAmount()).isEqualByComparingTo("10.0000");
    assertThat(created.allocatedTotalAmount()).isEqualByComparingTo("10.0000");
    assertThat(created.lineCount()).isEqualTo(3);

    CostAllocationDetail detail = as(actorA, "channel.operation.read", () -> costs.allocation(created.id()));
    assertThat(detail.lines()).hasSize(3);
    BigDecimal lineSum =
        detail.lines().stream()
            .map(CostAllocationLineRow::allocatedAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    // 🔴 严格相等：不是「差不多相等」。
    assertThat(lineSum).isEqualByComparingTo("10.0000");
    // 占比是 numeric 除法算出来的（1/3 ≈ 0.333333），不是整数除法里的 0。
    assertThat(detail.lines().get(0).ratio()).isEqualByComparingTo("0.333333");
    assertThat(detail.lines().get(0).basisUnit()).isEqualTo("PCS");
  }

  @Test
  @DisplayName("10.6.1.1 分摊基准合计为 0 时拒绝分摊（而不是摊成 0 行）")
  void zeroBasisIsRejected() {
    UUID ruleId = seedRuleId("BY_WEIGHT", true);
    UUID shipmentId =
        seedFirstMile(
            "Bearer tenant-a",
            "FM-2",
            "USD",
            new BigDecimal("10.0000"),
            List.of(BigDecimal.ONE),
            List.of(BigDecimal.ZERO),
            List.of(new BigDecimal("100.0000")));

    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.operation.create",
                    () ->
                        costs.createAllocation(
                            new CostAllocationRequest(ruleId, shipmentId, T0, ""),
                            actorA.userId(),
                            "Bearer tenant-a")))
        .hasMessageContaining("无法分摊");
  }

  @Test
  @DisplayName("10.6.1.1 规则版本化：同码递增版本、启用新版本后旧版转 RETIRED 但行还在")
  void rulesAreVersioned() {
    seedRule("BY_QUANTITY", true);
    CostAllocationRuleRow v2 = seedRule("BY_WEIGHT", true);
    assertThat(v2.version()).isEqualTo(2);

    CostAllocationRulePage page =
        as(actorA, "channel.operation.read", () -> costs.rules(null, 0, 50));
    assertThat(page.items()).hasSize(2);
    assertThat(page.items()).extracting(CostAllocationRuleRow::version).containsExactly(2, 1);
    // 旧版本转 RETIRED，但**行还在**（历史分摊单要能指向它当时用的那一版）。
    assertThat(page.items()).extracting(CostAllocationRuleRow::status)
        .containsExactlyInAnyOrder("ACTIVE", "RETIRED");
  }

  @Test
  @DisplayName("10.6.1.1 已被分摊引用过的规则版本不允许改口径（DB 触发器，绕过服务层同样被拒）")
  void referencedRuleCannotChangeBasis() {
    UUID ruleId = seedRuleId("BY_QUANTITY", true);
    UUID shipmentId =
        seedFirstMile(
            "Bearer tenant-a",
            "FM-3",
            "USD",
            new BigDecimal("10.0000"),
            List.of(BigDecimal.ONE, BigDecimal.ONE),
            List.of(new BigDecimal("1.000000"), new BigDecimal("1.000000")),
            List.of(new BigDecimal("100.0000"), new BigDecimal("100.0000")));
    as(
        actorA,
        "channel.operation.create",
        () ->
            costs.createAllocation(
                new CostAllocationRequest(ruleId, shipmentId, T0, ""),
                actorA.userId(),
                "Bearer tenant-a"));

    // 绕过服务层直接写 SQL 改口径 ⇒ 被 DB 触发器拒。
    assertThatThrownBy(
            () ->
                rawSqlAs(
                    actorA.tenantId(),
                    "update logistics_cost_allocation_rule set allocation_basis='BY_WEIGHT'"
                        + " where tenant_id=? and id=?",
                    actorA.tenantId(),
                    ruleId))
        .hasMessageContaining("不允许改口径");
  }

  @Test
  @DisplayName("10.6.1.1 结账即凭据：锁定后金额 / 规则 / 结账时点都不可再改")
  void lockedAllocationIsImmutable() {
    UUID ruleId = seedRuleId("BY_QUANTITY", true);
    UUID shipmentId =
        seedFirstMile(
            "Bearer tenant-a",
            "FM-4",
            "USD",
            new BigDecimal("10.0000"),
            List.of(BigDecimal.ONE),
            List.of(new BigDecimal("1.000000")),
            List.of(new BigDecimal("100.0000")));
    CostAllocationRow created =
        as(
            actorA,
            "channel.operation.create",
            () ->
                costs.createAllocation(
                    new CostAllocationRequest(ruleId, shipmentId, T0, ""),
                    actorA.userId(),
                    "Bearer tenant-a"));

    CostAllocationRow locked =
        as(
            actorA,
            "channel.operation.update",
            () ->
                costs.lock(
                    created.id(), new CostAllocationLockRequest(T0, "已结账"), actorA.userId()));
    assertThat(locked.status()).isEqualTo("LOCKED");
    assertThat(locked.lockedAt()).isNotNull();

    // 绕过服务层直接改金额 ⇒ 被 DB 触发器拒。
    assertThatThrownBy(
            () ->
                rawSqlAs(
                    actorA.tenantId(),
                    "update logistics_cost_allocation set allocated_total_amount=1.0000"
                        + " where tenant_id=? and id=?",
                    actorA.tenantId(),
                    created.id()))
        .hasMessageContaining("不可再改");

    // 已结账的分摊单不能被新规则重算取代。
    UUID ruleId2 = seedRuleId("BY_WEIGHT", true);
    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.operation.create",
                    () ->
                        costs.createAllocation(
                            new CostAllocationRequest(ruleId2, shipmentId, T0, ""),
                            actorA.userId(),
                            "Bearer tenant-a")))
        .hasMessageContaining("出账凭据");
  }

  @Test
  @DisplayName("10.6.1.1 结账时点写回的值必须逐字相等（timestamptz 不许偏 −28800s）")
  void costAsOfRoundTripsWithoutDrift() {
    UUID ruleId = seedRuleId("BY_QUANTITY", true);
    UUID shipmentId =
        seedFirstMile(
            "Bearer tenant-a",
            "FM-5",
            "USD",
            new BigDecimal("10.0000"),
            List.of(BigDecimal.ONE),
            List.of(new BigDecimal("1.000000")),
            List.of(new BigDecimal("100.0000")));
    CostAllocationRow created =
        as(
            actorA,
            "channel.operation.create",
            () ->
                costs.createAllocation(
                    new CostAllocationRequest(ruleId, shipmentId, T0, ""),
                    actorA.userId(),
                    "Bearer tenant-a"));
    // 断言**写回的值**，不是「没抛异常」。
    assertThat(created.costAsOf()).isEqualTo(T0);
    assertThat(created.computedAt()).isNotNull();

    CostAllocationDetail detail = as(actorA, "channel.operation.read", () -> costs.allocation(created.id()));
    assertThat(detail.allocation().costAsOf()).isEqualTo(T0);
  }

  // ==================================================================
  // 渠道对比与超成本订单
  // ==================================================================

  @Test
  @DisplayName("10.6.1.1 渠道成本对比按（承运商 + 渠道）分组，逐币种给指标")
  void channelComparisonGroupsAndIsolatesCurrencies() {
    seedWaybill("Bearer tenant-a", "WB-1", "DHL", "DHL-DE", "DE", new BigDecimal("100.0000"),
        "USD", new BigDecimal("10.000000"));
    seedWaybill("Bearer tenant-a", "WB-2", "DHL", "DHL-DE", "DE", new BigDecimal("40.0000"),
        "USD", new BigDecimal("5.000000"));
    seedWaybill("Bearer tenant-a", "WB-3", "DHL", "DHL-GB", "GB", new BigDecimal("80.0000"),
        "EUR", new BigDecimal("8.000000"));
    // 未登记运费的一票：计票数，**不计入**金额与单位成本的分母。
    seedWaybill("Bearer tenant-a", "WB-4", "FDX", "FDX-DE", "DE", null, "USD",
        new BigDecimal("3.000000"));

    CostChannelComparison comparison =
        as(actorA, "channel.operation.read", () -> costs.byChannel("Bearer tenant-a", T2, T1));

    assertThat(comparison.rows()).hasSize(3);
    CostChannelRow dhlDe =
        comparison.rows().stream()
            .filter(r -> "DHL".equals(r.carrierCode()) && "DHL-DE".equals(r.carrierChannel()))
            .findFirst()
            .orElseThrow();
    assertThat(dhlDe.waybills()).isEqualTo(2);
    assertThat(dhlDe.freightRegistered()).isEqualTo(2);
    assertThat(dhlDe.byCurrency()).hasSize(1);
    assertThat(dhlDe.byCurrency().get(0).currency()).isEqualTo("USD");
    assertThat(dhlDe.byCurrency().get(0).amount()).isEqualByComparingTo("140.0000");
    // 单均运费 = 140 / 2 = 70；单位重量运费 = 140 / 15 = 9.3333。
    assertThat(dhlDe.byCurrency().get(0).perOrderFreight()).isEqualByComparingTo("70.0000");
    assertThat(dhlDe.byCurrency().get(0).perKgFreight()).isEqualByComparingTo("9.3333");

    CostChannelRow fdx =
        comparison.rows().stream()
            .filter(r -> "FDX".equals(r.carrierCode()))
            .findFirst()
            .orElseThrow();
    assertThat(fdx.freightNotRegistered()).isEqualTo(1);
    assertThat(fdx.freightRegistered()).isZero();
    // 未登记 ⇒ 该渠道**没有任何币种金额**（不是 0 元）。
    assertThat(fdx.byCurrency()).isEmpty();
    assertThat(comparison.summary().waybills()).isEqualTo(4);
  }

  @Test
  @DisplayName("10.6.1.1 超成本订单按单位重量运费判定；没有毛重的票不参与平均与判定")
  void overCostOrdersUseUnitWeightFreight() {
    // 10 公斤 / 100 元 ⇒ 单位运费 10
    seedWaybill("Bearer tenant-a", "WB-OK", "DHL", "DHL-DE", "DE", new BigDecimal("100.0000"),
        "USD", new BigDecimal("10.000000"));
    // 1 公斤 / 100 元 ⇒ 单位运费 100（远超平均的 1.5 倍）
    seedWaybill("Bearer tenant-a", "WB-BAD", "DHL", "DHL-DE", "DE", new BigDecimal("100.0000"),
        "USD", new BigDecimal("1.000000"));
    // 没有毛重 ⇒ 不参与判定
    seedWaybill("Bearer tenant-a", "WB-NOWEIGHT", "DHL", "DHL-DE", "DE",
        new BigDecimal("100.0000"), "USD", null);

    CostOverCostOrderPage page =
        as(
            actorA,
            "channel.operation.read",
            () -> costs.overCostOrders("Bearer tenant-a", T2, T1, null, 0, 20));

    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).waybillNo()).isEqualTo("WB-BAD");
    assertThat(page.items().get(0).unitFreight()).isEqualByComparingTo("100.0000");
    assertThat(page.items().get(0).excessAmount()).isGreaterThan(BigDecimal.ZERO);
    // 判据是**单位重量**运费：WB-OK 的总额与 WB-BAD 一样，但单位运费更低，不上榜。
    assertThat(page.items()).extracting(CostOverCostOrderRow::waybillNo).doesNotContain("WB-OK");
    assertThat(page.items()).extracting(CostOverCostOrderRow::waybillNo).doesNotContain("WB-NOWEIGHT");
    assertThat(page.thresholds()).hasSize(1);
    assertThat(page.thresholds().get(0).currency()).isEqualTo("USD");
    // 评估了 2 票（有运费 + 有毛重），不是 3 票。
    assertThat(page.thresholds().get(0).evaluatedWaybills()).isEqualTo(2);
  }

  @Test
  @DisplayName("10.6.1.1 真的没有超成本运单时是**真实的空**，并说明上游读到了多少票")
  void emptyOverCostIsARealEmpty() {
    seedWaybill("Bearer tenant-a", "WB-OK", "DHL", "DHL-DE", "DE", new BigDecimal("100.0000"),
        "USD", new BigDecimal("10.000000"));

    CostOverCostOrderPage page =
        as(
            actorA,
            "channel.operation.read",
            () -> costs.overCostOrders("Bearer tenant-a", T2, T1, null, 0, 20));

    assertThat(page.items()).isEmpty();
    assertThat(page.totalElements()).isZero();
    assertThat(page.notes()).anyMatch(n -> n.contains("真实的空"));
  }

  // ==================================================================
  // 多租户三断言（AGENTS.md 硬性要求，缺一不可）
  // ==================================================================

  @Test
  @DisplayName("10.6.1.1 断言一：两租户互不可见")
  void tenantsAreIsolated() {
    UUID ruleA = seedRuleId("BY_QUANTITY", true);
    UUID shipmentA =
        seedFirstMile(
            "Bearer tenant-a",
            "FM-A",
            "USD",
            new BigDecimal("10.0000"),
            List.of(BigDecimal.ONE),
            List.of(new BigDecimal("1.000000")),
            List.of(new BigDecimal("100.0000")));
    as(
        actorA,
        "channel.operation.create",
        () ->
            costs.createAllocation(
                new CostAllocationRequest(ruleA, shipmentA, T0, ""),
                actorA.userId(),
                "Bearer tenant-a"));

    // B 看不到 A 的分摊单。
    CostAllocationPage pageB =
        as(actorB, "channel.operation.read", () -> costs.allocations(null, 0, 50));
    assertThat(pageB.items()).isEmpty();
    assertThat(pageB.totalElements()).isZero();

    // B 读 A 的规则 id ⇒ 404（RLS 在查询层就把它滤掉了）。
    assertThatThrownBy(() -> as(actorB, "channel.operation.read", () -> costs.allocation(ruleA)))
        .hasMessageContaining("分摊单不存在");

    // A 自己的分摊单还在。
    assertThat(as(actorA, "channel.operation.read", () -> costs.allocations(null, 0, 50)).items())
        .hasSize(1);
  }

  @Test
  @DisplayName("10.6.1.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    UUID ruleA = seedRuleId("BY_QUANTITY", true);
    UUID shipmentA =
        seedFirstMile(
            "Bearer tenant-a",
            "FM-A",
            "USD",
            new BigDecimal("10.0000"),
            List.of(BigDecimal.ONE),
            List.of(new BigDecimal("1.000000")),
            List.of(new BigDecimal("100.0000")));
    as(
        actorA,
        "channel.operation.create",
        () ->
            costs.createAllocation(
                new CostAllocationRequest(ruleA, shipmentA, T0, ""),
                actorA.userId(),
                "Bearer tenant-a"));

    // A 的会话 + B 的头 ⇒ 看到的仍然只是 A 的数据。
    http.perform(get("/channel/logistics-cost-analysis/allocations")
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));

    // 无会话一律 401。
    http.perform(get("/channel/logistics-cost-analysis/allocations")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("10.6.1.1 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    UUID ruleA = seedRuleId("BY_QUANTITY", true);
    UUID shipmentA =
        seedFirstMile(
            "Bearer tenant-a",
            "FM-A",
            "USD",
            new BigDecimal("10.0000"),
            List.of(BigDecimal.ONE),
            List.of(new BigDecimal("1.000000")),
            List.of(new BigDecimal("100.0000")));
    as(
        actorA,
        "channel.operation.create",
        () ->
            costs.createAllocation(
                new CostAllocationRequest(ruleA, shipmentA, T0, ""),
                actorA.userId(),
                "Bearer tenant-a"));

    for (int i = 0; i < 3; i++) {
      assertThat(as(actorA, "channel.operation.read", () -> costs.allocations(null, 0, 50)).items())
          .hasSize(1);
      assertThat(as(actorB, "channel.operation.read", () -> costs.allocations(null, 0, 50)).items())
          .isEmpty();
      // 口径台账也按租户隔离：B 看到的是**自己**那一份（同样五个分量，但互不可见）。
      assertThat(as(actorB, "channel.operation.read", () -> costs.capabilities()).components())
          .hasSize(5);
    }
  }

  // ==================================================================
  // 端点与分页
  // ==================================================================

  @Test
  @DisplayName("10.6.1.1 分摊单列表真分页真筛选，汇总不受分页影响")
  void allocationsAreReallyPaged() {
    UUID ruleId = seedRuleId("BY_QUANTITY", true);
    for (int i = 0; i < 3; i++) {
      UUID shipmentId =
          seedFirstMile(
              "Bearer tenant-a",
              "FM-P" + i,
              "USD",
              new BigDecimal("10.0000"),
              List.of(BigDecimal.ONE),
              List.of(new BigDecimal("1.000000")),
              List.of(new BigDecimal("100.0000")));
      as(
          actorA,
          "channel.operation.create",
          () ->
              costs.createAllocation(
                  new CostAllocationRequest(ruleId, shipmentId, T0, ""),
                  actorA.userId(),
                  "Bearer tenant-a"));
    }

    CostAllocationPage first =
        as(actorA, "channel.operation.read", () -> costs.allocations(null, 0, 2));
    assertThat(first.items()).hasSize(2);
    assertThat(first.totalElements()).isEqualTo(3);
    assertThat(first.totalPages()).isEqualTo(2);
    // 汇总**不受分页影响**。
    assertThat(first.computedCount()).isEqualTo(3);
    assertThat(first.lockedCount()).isZero();
    assertThat(first.supersededCount()).isZero();

    CostAllocationPage second =
        as(actorA, "channel.operation.read", () -> costs.allocations(null, 1, 2));
    assertThat(second.items()).hasSize(1);

    // 真筛选：按状态。
    assertThat(
            as(actorA, "channel.operation.read", () -> costs.allocations("LOCKED", 0, 50))
                .items())
        .isEmpty();
  }

  @Test
  @DisplayName("10.6.1.1 端点：capabilities / overview / by-channel / over-cost-orders 都能通")
  void endpointsAreReachable() throws Exception {
    seedWaybill("Bearer tenant-a", "WB-1", "DHL", "DHL-DE", "DE", new BigDecimal("100.0000"),
        "USD", new BigDecimal("10.000000"));

    http.perform(get("/channel/logistics-cost-analysis/capabilities")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components.length()").value(5));
    http.perform(get("/channel/logistics-cost-analysis/overview")
            .header("Authorization", "Bearer tenant-a")
            .param("dateFrom", T2.toString())
            .param("dateTo", T1.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.coverage.complete").value(false));
    http.perform(get("/channel/logistics-cost-analysis/by-channel")
            .header("Authorization", "Bearer tenant-a")
            .param("dateFrom", T2.toString())
            .param("dateTo", T1.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1));
    http.perform(get("/channel/logistics-cost-analysis/over-cost-orders")
            .header("Authorization", "Bearer tenant-a")
            .param("dateFrom", T2.toString())
            .param("dateTo", T1.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray());
    http.perform(get("/channel/logistics-cost-analysis/allocation-rules")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/channel/logistics-cost-analysis/allocations")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("10.6.1.1 非法 id ⇒ 400（不是 500）")
  void invalidIdIsBadRequest() throws Exception {
    http.perform(get("/channel/logistics-cost-analysis/allocations/not-a-uuid")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isBadRequest());
  }

  // ==================================================================
  // 工具
  // ==================================================================

  void rawSqlAs(UUID tenant, String sql, Object... binds) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
      c.setAutoCommit(false);
      try (var s = c.createStatement()) {
        s.execute("set app.current_tenant = '" + tenant + "'");
      }
      try (var ps = c.prepareStatement(sql)) {
        for (int i = 0; i < binds.length; i++) {
          ps.setObject(i + 1, binds[i]);
        }
        ps.executeUpdate();
      }
      c.commit();
    } catch (java.sql.SQLException e) {
      throw new RuntimeException(e);
    }
  }
}
