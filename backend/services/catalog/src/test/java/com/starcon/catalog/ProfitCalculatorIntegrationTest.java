package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static com.starcon.catalog.ProfitCalculatorModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
 * 5.3.1.1 利润测算器：真实 PostgreSQL 16 + 强制 RLS 下的决策就绪闸、租户隔离三断言、
 * 手工假设标注、方案落库与复算，以及上游不可达时**不拿 0 冒充「没有这笔费用」**。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS</b>：测算方案与物流阶梯全部真的落库。
 * 连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>跨服务依赖的处理</b>：成本构成的头程与关税来自 warehouse（另一个数据库），
 * 平台目录来自 channel（第三个数据库）。生产路径是真实的 {@code RestClient} HTTP 调用，
 * 本测试用两个 {@link HttpServer} 起确定性替身来满足它们 —— 替身只存在于测试代码里，
 * 且被断言「确实收到了 catalog 透传的 {@code Authorization} 头」，
 * 所以它证明的是<b>生产代码真的走 HTTP 且透传了租户上下文</b>，
 * 而不是把替身逻辑塞进了生产代码。
 *
 * <p><b>本文件最重要的五个断言</b>：
 *
 * <ol>
 *   <li>{@link #缺项时不输出毛利率数字()}：蓝图加粗句的技术落点。缺平台费用与物流费用时，
 *       毛利率是 <b>null</b> 而不是虚高的数字 —— 下游 5.3.2.1 模板与 5.4 调价会拿它直接改价。
 *   <li>{@link #租户隔离三断言覆盖方案与阶梯()}：定价方案里有成本口径，
 *       A 读到 B 的方案会直接让 A 的改价依据串味。
 *   <li>{@link #反向测算与正向测算走同一套扣减结构()}：蓝图要求反向测算不是另一套近似公式。
 *   <li>{@link #方案落库后能复算出同一个结果()}：蓝图输出句「参数可分享为方案」。
 *   <li>{@link #平台目录不可达时站点列表返回503而不是空列表()}：上游不可达 ≠ 上游没有数据。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProfitCalculatorIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer warehouse;
  static HttpServer channel;
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  static final Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final AtomicInteger SEQ = new AtomicInteger();

  static final LocalDate DAY_OLD = LocalDate.now().minusDays(30);
  static final LocalDate DAY_NEW = LocalDate.now().minusDays(10);
  static final LocalDate TODAY = LocalDate.now();

  /** warehouse 替身的头程发运单数据（tenant → 发运单）。 */
  static final Map<UUID, List<Map<String, Object>>> SHIPMENTS =
      new java.util.concurrent.ConcurrentHashMap<>();
  /** warehouse 替身的关税估算单数据（tenant → 估算单）。 */
  static final Map<UUID, List<Map<String, Object>>> DUTIES =
      new java.util.concurrent.ConcurrentHashMap<>();
  /** 渠道替身最近一次收到的 Authorization 头。 */
  static final AtomicReference<String> lastChannelBearer = new AtomicReference<>();
  /** 置位后渠道替身一律回 503。 */
  static final AtomicBoolean channelDown = new AtomicBoolean();
  /** 置位后仓库替身一律回 503：模拟「仓库服务不可用」。 */
  static final AtomicBoolean warehouseDown = new AtomicBoolean();

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

  static List<String> allPermissions() {
    return java.util.stream.Stream.of("spu", "sku", "attribute", "research", "compliance", "assets")
        .flatMap(
            p ->
                java.util.stream.Stream.of("read", "create", "update", "delete")
                    .map(op -> "catalog." + p + "." + op))
        .toList();
  }

  static {
    pg.start();
    try (var conn =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = conn.createStatement()) {
      s.execute("create role starcon_app nologin");
      s.execute("create role catalog_app login password 'test-only' nosuperuser nobypassrls");
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
            byte[] body = JSON.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      startWarehouseStub();
      startChannelStub();
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions()))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions()))));
      actors.put(
          "Bearer reader",
          actor(user, a, null, List.of(grant("ALL", List.of("catalog.spu.read", "catalog.sku.read")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** warehouse 替身：形状与 warehouse 的发运单 / 关税估算单端点逐字一致。 */
  static void startWarehouseStub() throws Exception {
    warehouse = HttpServer.create(new InetSocketAddress(0), 0);
    warehouse.createContext(
        "/warehouse",
        e -> {
          var path = e.getRequestURI().getPath();
          if (warehouseDown.get()) {
            e.sendResponseHeaders(503, -1);
            e.close();
            return;
          }
          UUID tenant =
              "Bearer tenant-b".equals(e.getRequestHeaders().getFirst("Authorization"))
                  ? b
                  : a;
          Object body;
          if (path.equals("/warehouse/first-mile-shipments")) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (var shipment : SHIPMENTS.getOrDefault(tenant, List.of())) {
              var row = new LinkedHashMap<String, Object>();
              row.put("id", shipment.get("id"));
              row.put("shipmentCode", shipment.get("shipmentCode"));
              row.put("currency", shipment.get("currency"));
              row.put("freightCost", shipment.get("freightCost"));
              row.put("insuranceCost", shipment.get("insuranceCost"));
              row.put("handlingCost", shipment.get("handlingCost"));
              row.put("status", shipment.get("status"));
              row.put("createdAt", shipment.get("createdAt"));
              rows.add(row);
            }
            body = Map.of("rows", rows, "total", rows.size(), "page", 0, "size", rows.size());
          } else if (path.startsWith("/warehouse/first-mile-shipments/")) {
            String id = path.substring("/warehouse/first-mile-shipments/".length());
            Map<String, Object> shipment = null;
            for (var candidate : SHIPMENTS.getOrDefault(tenant, List.of())) {
              if (id.equals(candidate.get("id"))) shipment = candidate;
            }
            if (shipment == null) {
              e.sendResponseHeaders(404, -1);
              e.close();
              return;
            }
            var row = new LinkedHashMap<String, Object>();
            row.put("id", shipment.get("id"));
            row.put("shipmentCode", shipment.get("shipmentCode"));
            row.put("currency", shipment.get("currency"));
            row.put("freightCost", shipment.get("freightCost"));
            row.put("insuranceCost", shipment.get("insuranceCost"));
            row.put("handlingCost", shipment.get("handlingCost"));
            row.put("status", shipment.get("status"));
            row.put("createdAt", shipment.get("createdAt"));
            body = Map.of("shipment", row, "lines", shipment.get("lines"));
          } else if (path.equals("/warehouse/duty-taxes")) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (var estimate : DUTIES.getOrDefault(tenant, List.of())) {
              var row = new LinkedHashMap<String, Object>();
              row.put("id", estimate.get("id"));
              row.put("estimateCode", estimate.get("estimateCode"));
              row.put("estimateCurrency", estimate.get("estimateCurrency"));
              row.put("status", estimate.get("status"));
              row.put("createdAt", estimate.get("createdAt"));
              rows.add(row);
            }
            body = Map.of("rows", rows, "page", 0, "size", rows.size(), "totalPages", 1);
          } else if (path.startsWith("/warehouse/duty-taxes/") && path.endsWith("/lines")) {
            String id = path.substring("/warehouse/duty-taxes/".length(), path.length() - "/lines".length());
            Map<String, Object> estimate = null;
            for (var candidate : DUTIES.getOrDefault(tenant, List.of())) {
              if (id.equals(candidate.get("id"))) estimate = candidate;
            }
            if (estimate == null) {
              e.sendResponseHeaders(404, -1);
              e.close();
              return;
            }
            body = estimate.get("lines");
          } else {
            e.sendResponseHeaders(404, -1);
            e.close();
            return;
          }
          byte[] out = JSON.writeValueAsBytes(body);
          e.getResponseHeaders().add("Content-Type", "application/json");
          e.sendResponseHeaders(200, out.length);
          e.getResponseBody().write(out);
          e.close();
        });
    warehouse.start();
  }

  /** channel 替身：形状与 channel 的 {@code GET /channel/platforms} 逐字一致。 */
  static void startChannelStub() throws Exception {
    channel = HttpServer.create(new InetSocketAddress(0), 0);
    channel.createContext(
        "/channel",
        e -> {
          lastChannelBearer.set(e.getRequestHeaders().getFirst("Authorization"));
          if (channelDown.get()) {
            e.sendResponseHeaders(503, -1);
            e.close();
            return;
          }
          var marketplace = new LinkedHashMap<String, Object>();
          marketplace.put("id", UUID.randomUUID().toString());
          marketplace.put("code", "US");
          marketplace.put("name", "美国站");
          marketplace.put("countryCode", "US");
          marketplace.put("defaultCurrency", "USD");
          marketplace.put("defaultTimezone", "America/Los_Angeles");
          marketplace.put("defaultLocale", "en-US");
          marketplace.put("currencyScale", 2);
          marketplace.put("vatRequired", false);
          var platform = new LinkedHashMap<String, Object>();
          platform.put("id", UUID.randomUUID().toString());
          platform.put("code", "AMAZON");
          platform.put("name", "Amazon");
          platform.put("integration", "OFFICIAL");
          platform.put("supportStatus", "SUPPORTED");
          platform.put("capabilities", List.of());
          platform.put("marketplaces", List.of(marketplace));
          byte[] out = JSON.writeValueAsBytes(List.of(platform));
          e.getResponseHeaders().add("Content-Type", "application/json");
          e.sendResponseHeaders(200, out.length);
          e.getResponseBody().write(out);
          e.close();
        });
    channel.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "catalog_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("WAREHOUSE_URL", () -> "http://localhost:" + warehouse.getAddress().getPort());
    r.add("CHANNEL_URL", () -> "http://localhost:" + channel.getAddress().getPort());
    // 单连接：只有复用同一条连接，才能真正证明归还之后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @AfterAll
  static void stop() {
    channel.stop(0);
    warehouse.stop(0);
    identity.stop(0);
    pg.stop();
  }

  @Autowired SkuCostCompositionService costs;
  @Autowired ProfitCalculatorService calculator;
  @Autowired CatalogService catalog;
  @Autowired PartyService parties;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  AccessService.Access actor;
  AccessService.Access actorB;

  @BeforeEach
  void setUp() {
    actor = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    unit(actor, "PCS");
    unit(actorB, "PCS");
    warehouseDown.set(false);
    channelDown.set(false);
  }

  // ================================================================ 夹具

  <T> T tx(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.read", work);
  }

  <T> T write(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.update", work);
  }

  void writeVoid(AccessService.Access who, Runnable work) {
    access.run(
        who,
        "catalog.spu.update",
        () -> {
          work.run();
          return null;
        });
  }

  static String unique(String prefix) {
    return prefix + "-" + SEQ.incrementAndGet();
  }

  void unit(AccessService.Access who, String code) {
    tx(
        who,
        () -> {
          sql.execute(
              "insert into unit_of_measure(id,tenant_id,code,name,dimension,scale,allow_purchase,"
                  + "allow_sale,allow_storage,enabled) values(?::uuid,?::uuid,?,?, 'COUNT',0,true,true,true,true)"
                  + " on conflict (tenant_id,code) do nothing",
              UUID.randomUUID(),
              who.tenantId(),
              code,
              code);
          return null;
        });
  }

  UUID sku(AccessService.Access who, String prefix) {
    String code = unique(prefix);
    UUID spuId =
        write(
            who,
            () ->
                catalog.saveSpu(
                    who,
                    null,
                    new SpuInput(
                        code + "-SPU", "SPU " + code, "", "Brand", "Category", "DRAFT", 0)));
    return write(
        who,
        () ->
            catalog.saveSku(
                who,
                spuId,
                null,
                new SkuInput(code, "SKU " + code, unique("BC"), "PCS", "ACTIVE", List.of(), 0)));
  }

  UUID supplierRole(AccessService.Access who, String prefix) {
    String code = unique(prefix);
    UUID partyId =
        write(
                who,
                () ->
                    parties.save(
                        who,
                        null,
                        new PartyInput(
                            code,
                            "供应商 " + code,
                            "ORGANIZATION",
                            "CN",
                            "mail@" + code.toLowerCase() + ".example",
                            null,
                            null,
                            "ACTIVE",
                            List.of(new PartyRoleInput("SUPPLIER", "ACTIVE", "")),
                            0)))
            .id();
    return tx(
        who,
        () ->
            sql.fetchOne(
                    "select id from party_role where tenant_id=? and party_id=? and role_type='SUPPLIER'",
                    who.tenantId(),
                    partyId)
                .get(0, UUID.class));
  }

  void delivery(
      AccessService.Access who,
      UUID roleId,
      UUID skuId,
      String ref,
      LocalDate received,
      String quantity,
      String amount,
      String currency,
      String purchaseUnit) {
    write(
        who,
        () -> {
          sql.execute(
              "insert into vendor_delivery_record(id,tenant_id,role_id,delivery_ref,sku_id,purchase_unit_code,"
                  + "quantity,amount,currency,ordered_at,due_date,received_date,note)"
                  + " values(?::uuid,?::uuid,?::uuid,?,?::uuid,?,?,?,?,?::timestamptz,?,?,?)",
              UUID.randomUUID(),
              who.tenantId(),
              roleId,
              ref,
              skuId,
              purchaseUnit,
              new BigDecimal(quantity),
              new BigDecimal(amount),
              currency,
              received.minusDays(3).atStartOfDay().atOffset(ZoneOffset.UTC),
              received,
              received,
              "");
          return null;
        });
  }

  void shipment(UUID tenant, UUID skuId, String currency, String freight, String qty) {
    var line = new LinkedHashMap<String, Object>();
    line.put("skuId", skuId.toString());
    line.put("plannedQuantity", new BigDecimal(qty));
    line.put("shippedQuantity", new BigDecimal(qty));
    var shipment = new LinkedHashMap<String, Object>();
    shipment.put("id", UUID.randomUUID().toString());
    shipment.put("shipmentCode", "FM-" + UUID.randomUUID().toString().substring(0, 8));
    shipment.put("currency", currency);
    shipment.put("freightCost", new BigDecimal(freight));
    shipment.put("insuranceCost", BigDecimal.ZERO);
    shipment.put("handlingCost", BigDecimal.ZERO);
    shipment.put("status", "DISPATCHED");
    shipment.put("createdAt", TODAY.minusDays(5).atStartOfDay().atOffset(ZoneOffset.UTC).toString());
    shipment.put("lines", List.of(line));
    SHIPMENTS.computeIfAbsent(tenant, k -> new ArrayList<>()).add(shipment);
  }

  void dutyEstimate(UUID tenant, UUID skuId, String currency, String uom, String qty, String duty) {
    var line = new LinkedHashMap<String, Object>();
    line.put("skuId", skuId.toString());
    line.put("uom", uom);
    line.put("quantity", new BigDecimal(qty));
    line.put("estimatedDutyAmount", new BigDecimal(duty));
    line.put("estimatedVatAmount", new BigDecimal("0"));
    line.put("estimatedLineTotalTax", new BigDecimal(duty));
    var estimate = new LinkedHashMap<String, Object>();
    estimate.put("id", UUID.randomUUID().toString());
    estimate.put("estimateCode", "DT-" + UUID.randomUUID().toString().substring(0, 8));
    estimate.put("estimateCurrency", currency);
    estimate.put("status", "LOCKED");
    estimate.put("createdAt", TODAY.minusDays(5).atStartOfDay().atOffset(ZoneOffset.UTC).toString());
    estimate.put("lines", List.of(line));
    DUTIES.computeIfAbsent(tenant, k -> new ArrayList<>()).add(estimate);
  }

  /**
   * 一个完整夹具：SKU + 成本构成（采购 / 头程 / 包装 / 关税 / 其他直接成本都有值）。
   *
   * <p>采购成本 10.00（100 件 1000 元）、头程分摊 2.00、关税 3.00、包装 1.00、其他直接成本 0。
   */
  UUID pricedSku(AccessService.Access who, String prefix) {
    UUID roleId = supplierRole(who, prefix + "-SUP");
    UUID skuId = sku(who, prefix + "-SKU");
    delivery(who, roleId, skuId, unique("PO"), DAY_NEW, "100", "1000", "USD", "PCS");
    shipment(who.tenantId(), skuId, "USD", "200", "100");
    dutyEstimate(who.tenantId(), skuId, "USD", "PCS", "100", "300");
    var effective = DAY_NEW.atStartOfDay().atOffset(ZoneOffset.UTC);
    write(
        who,
        () ->
            costs.save(
                who,
                skuId,
                new SkuCostCompositionModels.ProfileInput(
                    "MOVING_AVERAGE", "USD", effective, new BigDecimal("1.00"), "夹具成本构成", 0),
                bearer(who)));
    return skuId;
  }

  static String bearer(AccessService.Access who) {
    return "Bearer " + (who.tenantId().equals(a) ? "tenant-a" : "tenant-b");
  }

  // ================================================================ 请求构造

  private CalculateRequest request(
      UUID skuId, CalcMode mode, String price, String targetMargin, String feeRate, String logistics) {
    return new CalculateRequest(
        skuId,
        "US",
        "AMAZON",
        "USD",
        "USD",
        mode,
        price == null ? null : new BigDecimal(price),
        targetMargin == null ? null : new BigDecimal(targetMargin),
        BigDecimal.ONE,
        null,
        feeRate == null ? null : new BigDecimal(feeRate),
        null,
        logistics == null ? null : new BigDecimal(logistics),
        null,
        List.of(),
        DAY_OLD.minusDays(1),
        TODAY,
        "测试测算");
  }

  private ProfitCalculation calculate(AccessService.Access who, CalculateRequest request) {
    return tx(who, () -> calculator.calculate(request, bearer(who)));
  }

  private static WaterfallLine line(ProfitCalculation calculation, String code) {
    for (var item : calculation.waterfall()) {
      if (item.code().equals(code)) return item;
    }
    throw new AssertionError("瀑布图里没有这一行：" + code);
  }

  // ================================================================ 核心断言：缺项时不输出毛利率数字

  /**
   * 缺平台费用与物流费用时，**不输出**毛利率数字。
   *
   * <p>这是本模块最贵的一条断言：缺这两项算出来的毛利率是**系统性虚高**的
   * （平台佣金 8%~15% + 每单几美元的物流），而下游 5.3.2.1 模板与 5.4 调价
   * 会拿它直接改价。所以服务层必须返回 null，而不是一个看起来正常的数字。
   */
  @Test
  void 缺项时不输出毛利率数字() {
    UUID skuId = pricedSku(actor, "GAP");
    // 只给售价，不给平台费率与物流单价 ⇒ 这两项取不到。
    var calculation = calculate(actor, request(skuId, CalcMode.PRICE, "100", null, null, null));

    assertThat(calculation.decisionReady()).isFalse();
    assertThat(calculation.decisionBasis()).isEqualTo(DecisionBasis.INCOMPLETE);
    assertThat(calculation.grossProfit()).isNull();
    assertThat(calculation.grossMarginRate()).isNull();
    assertThat(calculation.breakEvenPrice()).isNull();
    assertThat(calculation.targetMarginPrice()).isNull();
    assertThat(calculation.breakEvenVolume()).isNull();
    // 但「剩余可覆盖缺失扣减的金额」有值：100 − 已知成本 16 = 84。
    assertThat(calculation.revenueLeftForMissingDeductions()).isEqualByComparingTo("84.0000");
    // 提示必须写明「不可信」，而且写进了响应里（不是只在前端渲染时才有）。
    assertThat(calculation.grossProfitSuppressedReason()).contains("不可信");

    // 瀑布图上**每一项扣减都在**，缺的那两项显示「未交付 / 该扣减未计入」。
    assertThat(calculation.waterfall()).hasSize(9); // 售价 + 7 项扣减 + 毛利
    var platformFee = line(calculation, "PLATFORM_FEE");
    assertThat(platformFee.available()).isFalse();
    assertThat(platformFee.amount()).isNull();
    assertThat(platformFee.unavailableReason()).contains("未交付");
    assertThat(platformFee.ownerModule()).contains("5.2.3.1");
    var logistics = line(calculation, "LOGISTICS");
    assertThat(logistics.available()).isFalse();
    assertThat(logistics.unavailableReason()).contains("未交付");
    assertThat(logistics.ownerModule()).contains("5.2.3.2");
    // 缺口清单点名归谁管。
    assertThat(calculation.blockingGaps())
        .anySatisfy(
            gap -> {
              assertThat(gap.code()).isEqualTo("PLATFORM_FEE");
              assertThat(gap.ownerModule()).contains("5.2.3.1");
              assertThat(gap.impact()).contains("虚高");
            });
    assertThat(calculation.blockingGaps())
        .anySatisfy(
            gap -> {
              assertThat(gap.code()).isEqualTo("LOGISTICS");
              assertThat(gap.ownerModule()).contains("5.2.3.2");
            });
  }

  /** 每一项扣减都能展开看到来源（哪张表 / 哪个上游模块 / 什么口径 / 什么生效时间）。 */
  @Test
  void 每一项扣减都能展开看来源() {
    UUID skuId = pricedSku(actor, "SRC");
    var calculation =
        calculate(actor, request(skuId, CalcMode.PRICE, "100", "0.25", "0.15", "5"));
    for (var item : calculation.waterfall()) {
      assertThat(item.source()).as("每一行都要有来源：" + item.code()).isNotNull();
      assertThat(item.source().ownerModule()).as("来源要点名归谁管：" + item.code()).isNotBlank();
      assertThat(item.source().basis()).as("来源要有口径：" + item.code()).isNotBlank();
    }
    // 采购成本这一项要指到真实的表与成本法。
    var purchase = line(calculation, "PURCHASE");
    assertThat(purchase.source().table()).contains("vendor_delivery_record");
    assertThat(purchase.source().ownerModule()).contains("5.2.1.1");
    assertThat(purchase.source().details()).isNotEmpty();
    // 生效时间要带上（成本构成的有效时间）。
    assertThat(purchase.source().effectiveFrom()).isNotNull();
    // 平台费用这一项要写明它是手工假设 + 归谁接管。
    var platformFee = line(calculation, "PLATFORM_FEE");
    assertThat(platformFee.sourceKind()).isEqualTo("MANUAL_ASSUMPTION");
    assertThat(platformFee.source().ownerModule()).contains("5.2.3.1");
    assertThat(platformFee.source().trace()).contains("手工假设");
  }

  /** 手工假设补齐后，数字回来了，但口径必须标成「含手工假设」。 */
  @Test
  void 手工假设补齐后数字回来但口径标成含手工假设() {
    UUID skuId = pricedSku(actor, "MANUAL");
    var calculation =
        calculate(actor, request(skuId, CalcMode.PRICE, "100", null, "0.15", "5"));
    assertThat(calculation.decisionReady()).isTrue();
    assertThat(calculation.decisionBasis())
        .isEqualTo(DecisionBasis.COMPLETE_FROM_MANUAL_ASSUMPTIONS);
    // 100 − 15（平台费）− 5（物流）− 16（采购 10 + 头程 2 + 包装 1 + 关税 3 + 其他 0）= 64
    assertThat(calculation.grossProfit()).isEqualByComparingTo("64.0000");
    assertThat(calculation.grossMarginRate()).isEqualByComparingTo("0.640000");
    // 口径提醒必须在（它不藏）：缺口清单里有一条「已用人工假设补上」。
    assertThat(calculation.blockingGaps())
        .anySatisfy(
            gap -> {
              assertThat(gap.code()).isEqualTo("PLATFORM_FEE");
              assertThat(gap.manualAssumptionAccepted()).isTrue();
              assertThat(gap.impact()).contains("手工假设");
            });
    assertThat(calculation.decisionNote()).contains("手工假设");
  }

  /** 反向测算与正向测算走同一套扣减结构：反解的售价代回正向，毛利率恰好等于目标值。 */
  @Test
  void 反向测算与正向测算走同一套扣减结构() {
    UUID skuId = pricedSku(actor, "REV");
    var reverse =
        calculate(actor, request(skuId, CalcMode.TARGET_MARGIN, null, "0.25", "0.15", "5"));
    assertThat(reverse.decisionReady()).isTrue();
    // 售价 = (0 + 5 + 16) ÷ (1 − 0.15 − 0.25) = 35
    assertThat(reverse.revenue()).isEqualByComparingTo("35.0000");
    assertThat(reverse.grossMarginRate()).isEqualByComparingTo("0.250000");

    // 同一个结构：把反解出来的售价按「按售价测算」再算一次，毛利率必须还是 0.25。
    var forward =
        calculate(
            actor,
            request(skuId, CalcMode.PRICE, reverse.revenue().toPlainString(), null, "0.15", "5"));
    assertThat(forward.grossMarginRate()).isEqualByComparingTo("0.250000");
    // 保本价也走同一个方程：毛利恰好为 0 的售价。
    assertThat(reverse.breakEvenPrice()).isEqualByComparingTo("24.7059");
  }

  /** 跨币种必须要汇率：不按 1:1 折算。 */
  @Test
  void 跨币种必须要汇率不按一比一折算() {
    UUID skuId = pricedSku(actor, "FX");
    var cross =
        new CalculateRequest(
            skuId,
            "US",
            "AMAZON",
            "EUR",
            "USD",
            CalcMode.PRICE,
            new BigDecimal("100"),
            null,
            BigDecimal.ONE,
            null,
            new BigDecimal("0.15"),
            null,
            new BigDecimal("5"),
            null,
            List.of(),
            DAY_OLD.minusDays(1),
            TODAY,
            "跨币种");
    // 没有汇率 ⇒ 400（拒绝测算），而不是按 1:1 折算。
    assertThatThrownBy(() -> calculate(actor, cross))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> {
              var status = (ResponseStatusException) e;
              assertThat(status.getStatusCode().value()).isEqualTo(400);
              assertThat(status.getReason()).contains("汇率");
            });

    // 给了汇率（手工假设）⇒ 成本被换算过去，方向写明。
    var withFx =
        new CalculateRequest(
            skuId,
            "US",
            "AMAZON",
            "EUR",
            "USD",
            CalcMode.PRICE,
            new BigDecimal("100"),
            null,
            BigDecimal.ONE,
            new BigDecimal("0.90"),
            new BigDecimal("0.15"),
            null,
            new BigDecimal("5"),
            null,
            List.of(),
            DAY_OLD.minusDays(1),
            TODAY,
            "跨币种");
    var calculation = calculate(actor, withFx);
    assertThat(calculation.fx().available()).isTrue();
    assertThat(calculation.fx().direction()).contains("1 USD = 0.90 EUR");
    assertThat(calculation.fx().sourceKind()).isEqualTo("MANUAL_ASSUMPTION");
    // 已知成本 16 USD × 0.9 = 14.4 EUR
    assertThat(calculation.knownDeductionTotal()).isEqualByComparingTo("14.4000");
  }

  // ================================================================ 租户隔离三断言

  /**
   * 租户隔离三断言，<b>并且覆盖方案行与物流阶梯行</b>。
   *
   * <p>定价方案里有成本口径与费率，A 读到 B 的方案会直接让 A 的改价依据串味。
   */
  @Test
  void 租户隔离三断言覆盖方案与阶梯() throws Exception {
    UUID mySku = pricedSku(actor, "ISO-A");
    UUID theirSku = pricedSku(actorB, "ISO-B");
    var myRequest = request(mySku, CalcMode.PRICE, "100", null, "0.15", "5");
    var theirRequest = request(theirSku, CalcMode.PRICE, "50", null, "0.10", "3");

    var myScenario = write(actor, () -> calculator.saveScenario(actor, myRequest, bearer(actor)));
    var theirScenario = write(actorB, () -> calculator.saveScenario(actorB, theirRequest, bearer(actorB)));

    // 两边都真的有数据 —— 否则下面的断言就是空的。
    assertThat(tx(actor, () -> calculator.getScenario(myScenario.summary().id(), bearer(actor))))
        .isNotNull();
    assertThat(tx(actorB, () -> calculator.getScenario(theirScenario.summary().id(), bearer(actorB))))
        .isNotNull();

    // 1) 两租户互不可见
    assertThatThrownBy(() -> tx(actor, () -> calculator.getScenario(theirScenario.summary().id(), bearer(actor))))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> calculator.getScenario(myScenario.summary().id(), bearer(actorB))))
        .isInstanceOf(ResponseStatusException.class);
    // 列表里混不进对方的方案。
    var myRows = tx(actor, () -> calculator.listScenarios(0, 200)).items();
    assertThat(myRows).isNotEmpty();
    assertThat(myRows.stream().map(ScenarioSummary::id)).doesNotContain(theirScenario.summary().id());
    assertThat(myRows.stream().map(ScenarioSummary::id)).contains(myScenario.summary().id());
    // 直接查库也读不到对方的那一行（RLS 兜底，不只是服务层过滤）。
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select count(*) from profit_calculator_scenario where id=?",
                                theirScenario.summary().id())
                            .get(0, Long.class))
                .longValue())
        .isZero();
    // 阶梯行同样互不可见。
    assertThat(
            tx(
                    actorB,
                    () ->
                        sql.fetchOne(
                                "select count(*) from profit_calculator_logistics_tier where scenario_id=?",
                                myScenario.summary().id())
                            .get(0, Long.class))
                .longValue())
        .isZero();

    // 2) 伪造 X-Tenant-Id 头无效：租户只来自已认证成员关系
    http.perform(
            get("/catalog/profit-calculator/scenarios/" + theirScenario.summary().id())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isNotFound());
    http.perform(
            get("/catalog/profit-calculator/scenarios/" + myScenario.summary().id())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    // 伪造头下的写入仍落在**调用者自己**名下 —— 断言行确实落在 A 名下，不只断言 B 看不见。
    http.perform(
            post("/catalog/profit-calculator/scenarios")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b)
                .contentType("application/json")
                .content(JSON.writeValueAsString(myRequest)))
        .andExpect(status().isOk());
    var owners =
        tx(
                actor,
                () ->
                    sql.fetch(
                            "select distinct tenant_id from profit_calculator_scenario where sku_id=?",
                            mySku))
            .stream()
            .map(row -> row.get(0, UUID.class))
            .toList();
    assertThat(owners).isNotEmpty();
    assertThat(owners).containsOnly(a);

    // 3) 连接池归还后不串租户（单连接反复交替），逐轮断言两个集合不相交
    for (int i = 0; i < 5; i++) {
      var mySet =
          tx(actor, () -> calculator.listScenarios(0, 200)).items().stream()
              .map(ScenarioSummary::id)
              .collect(java.util.stream.Collectors.toSet());
      var theirSet =
          tx(actorB, () -> calculator.listScenarios(0, 200)).items().stream()
              .map(ScenarioSummary::id)
              .collect(java.util.stream.Collectors.toSet());
      assertThat(mySet).isNotEmpty();
      assertThat(theirSet).isNotEmpty();
      assertThat(java.util.Collections.disjoint(mySet, theirSet)).isTrue();
      assertThatThrownBy(
              () -> tx(actor, () -> calculator.getScenario(theirScenario.summary().id(), bearer(actor))))
          .as("A 读 B 的测算方案必须失败而不是串过去")
          .isInstanceOf(ResponseStatusException.class);
    }
  }

  // ================================================================ 方案落库与复算

  /** 方案落库后能复算出同一个结果（蓝图输出句「参数可分享为方案」）。 */
  @Test
  void 方案落库后能复算出同一个结果() {
    UUID skuId = pricedSku(actor, "SAVE");
    var request =
        new CalculateRequest(
            skuId,
            "US",
            "AMAZON",
            "USD",
            "USD",
            CalcMode.PRICE,
            new BigDecimal("100"),
            new BigDecimal("0.25"),
            new BigDecimal("50"),
            null,
            new BigDecimal("0.15"),
            new BigDecimal("2"),
            new BigDecimal("4"),
            new BigDecimal("30"),
            List.of(
                new TierInput(new BigDecimal("20"), new BigDecimal("5")),
                new TierInput(null, new BigDecimal("3"))),
            DAY_OLD.minusDays(1),
            TODAY,
            "阶梯测算方案");

    var saved = write(actor, () -> calculator.saveScenario(actor, request, bearer(actor)));
    // 行真的落在 PostgreSQL 里。
    var row =
        tx(
            actor,
            () ->
                sql.fetchOne(
                    "select count(*), min(decision_basis) from profit_calculator_scenario where id=?",
                    saved.summary().id()));
    assertThat(row.get(0, Long.class)).isEqualTo(1L);
    assertThat(row.get(1, String.class)).isEqualTo("COMPLETE_FROM_MANUAL_ASSUMPTIONS");
    var tierCount =
        tx(
                actor,
                () ->
                    sql.fetchOne(
                            "select count(*) from profit_calculator_logistics_tier where scenario_id=?",
                            saved.summary().id())
                        .get(0, Long.class))
            .longValue();
    assertThat(tierCount).isEqualTo(2L);

    // 复算：同一套参数 ⇒ 同一个结果。
    var reopened = tx(actor, () -> calculator.getScenario(saved.summary().id(), bearer(actor)));
    assertThat(reopened.calculation()).isNotNull();
    assertThat(reopened.calculation().grossMarginRate())
        .isEqualByComparingTo(saved.calculation().grossMarginRate());
    assertThat(reopened.calculation().breakEvenPrice())
        .isEqualByComparingTo(saved.calculation().breakEvenPrice());
    assertThat(reopened.logisticsTiers()).hasSize(2);
    assertThat(reopened.request().note()).isEqualTo("阶梯测算方案");
    // 阶梯参与算术：50 单 = 前 20 单 5.00 + 后 30 单 3.00 = 190 ⇒ 每单位 3.80
    assertThat(reopened.calculation().knownDeductionTotal()).isEqualByComparingTo("16.0000");
    var logistics = line(reopened.calculation(), "LOGISTICS");
    assertThat(logistics.amount()).isEqualByComparingTo("3.8000");
    assertThat(reopened.calculation().logisticsBands()).hasSize(2);
  }

  /** 方案的物流阶梯最后一段必须无上限（否则超出阶梯的数量没有运费定义）。 */
  @Test
  void 物流阶梯最后一段必须无上限() {
    UUID skuId = pricedSku(actor, "TIER");
    var request =
        new CalculateRequest(
            skuId,
            "US",
            "AMAZON",
            "USD",
            "USD",
            CalcMode.PRICE,
            new BigDecimal("100"),
            null,
            BigDecimal.ONE,
            null,
            new BigDecimal("0.15"),
            null,
            new BigDecimal("4"),
            null,
            List.of(new TierInput(new BigDecimal("20"), new BigDecimal("5"))),
            DAY_OLD.minusDays(1),
            TODAY,
            "阶梯没有封顶");
    assertThatThrownBy(() -> calculate(actor, request))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getReason()).contains("无上限"));
  }

  // ================================================================ 上游不可达

  /** 平台目录不可达 ⇒ 站点列表返回 503，而不是空列表冒充「没有站点」。 */
  @Test
  void 平台目录不可达时站点列表返回503而不是空列表() throws Exception {
    channelDown.set(true);
    try {
      assertThatThrownBy(() -> tx(actor, () -> calculator.sites(bearer(actor))))
          .isInstanceOf(ChannelPlatformPort.ChannelPlatformUnavailableException.class);
      http.perform(get("/catalog/profit-calculator/sites").header("Authorization", "Bearer tenant-a"))
          .andExpect(status().isServiceUnavailable());
    } finally {
      channelDown.set(false);
    }
    // 上游恢复后又能读到。
    assertThat(tx(actor, () -> calculator.sites(bearer(actor)))).isNotEmpty();
    // 跨服务调用必须透传调用方自己的 Authorization 头。
    assertThat(lastChannelBearer.get()).isEqualTo("Bearer tenant-a");
  }

  /** 跨服务调用透传调用方的 Authorization 头（租户由对方从成员关系解析）。 */
  @Test
  void 跨服务调用透传调用方的Authorization头() {
    tx(actor, () -> calculator.sites(bearer(actor)));
    assertThat(lastChannelBearer.get()).isEqualTo("Bearer tenant-a");
    tx(actorB, () -> calculator.sites(bearer(actorB)));
    assertThat(lastChannelBearer.get()).isEqualTo("Bearer tenant-b");
  }

  // ================================================================ 权限

  /** 只有读权限时，写端点必须 403。 */
  @Test
  void 只有读权限时不能保存方案() throws Exception {
    var reader = actors.get("Bearer reader");
    UUID skuId = pricedSku(actor, "PERM");
    var request = request(skuId, CalcMode.PRICE, "100", null, "0.15", "5");
    http.perform(
            post("/catalog/profit-calculator/scenarios")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content(JSON.writeValueAsString(request)))
        .andExpect(status().isForbidden());
    assertThat(reader.permissions()).contains("catalog.spu.read");
  }

  // ================================================================ 能力声明

  /** 能力声明必须逐项标出「能不能取到、归谁管、缺了会怎样」。 */
  @Test
  void 能力声明逐项标出缺项与归属() {
    var capabilities = tx(actor, calculator::capabilities);
    assertThat(capabilities.deductions()).hasSize(7);
    var platformFee =
        capabilities.deductions().stream()
            .filter(d -> d.code().equals("PLATFORM_FEE"))
            .findFirst()
            .orElseThrow();
    assertThat(platformFee.available()).isFalse();
    assertThat(platformFee.ownerModule()).contains("5.2.3.1");
    assertThat(platformFee.impact()).contains("虚高");
    var logistics =
        capabilities.deductions().stream()
            .filter(d -> d.code().equals("LOGISTICS"))
            .findFirst()
            .orElseThrow();
    assertThat(logistics.available()).isFalse();
    assertThat(logistics.ownerModule()).contains("5.2.3.2");
    // 已交付的 5.2.1.1 分项标成可用。
    assertThat(
            capabilities.deductions().stream()
                .filter(d -> d.code().equals("PURCHASE"))
                .findFirst()
                .orElseThrow()
                .available())
        .isTrue();
    // 决策就绪闸必须写在声明里。
    assertThat(capabilities.decisionGateNote()).contains("不输出毛利率数字");
    assertThat(capabilities.fxNote()).contains("汇率");
  }
}
