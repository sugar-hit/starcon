package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static com.starcon.catalog.PricingTemplateModels.*;
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
import java.util.concurrent.atomic.AtomicInteger;
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
 * 5.3.2.1 定价模板：真实 PostgreSQL 16 + 强制 RLS 下的模板 CRUD、基准与调整项、
 * 「一键应用生成建议价」，以及**建议价真的来自 5.3.1.1 的测算**这件事。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS</b>：模板、调整项、适用对象、应用留痕全部真的落库。
 * 连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>跨服务依赖的处理</b>：成本构成的头程与关税来自 warehouse（另一个数据库），
 * 平台目录来自 channel（第三个数据库）。生产路径是真实的 {@code RestClient} HTTP 调用，
 * 本测试用两个 {@link HttpServer} 起确定性替身来满足它们 —— 替身只存在于测试代码里，
 * 所以它证明的是<b>生产代码真的走 HTTP 且透传了租户上下文</b>。
 *
 * <p><b>本文件最重要的六个断言</b>：
 *
 * <ol>
 *   <li>{@link #固定毛利的建议价来自利润测算器()}：建议价**不是本模块编的数字** ——
 *       它等于 5.3.1.1 解出来的目标毛利价（35 = (5 + 16) ÷ (1 − 0.15 − 0.25)）。
 *   <li>{@link #成本加成的建议价等于成本基数乘以加成率()}：加成基数 = 5.3.1.1 瀑布图里的
 *       单位成本合计（16 成本 + 5 物流 = 21），加成 30% ⇒ 27.3。
 *   <li>{@link #成本不完整时拒绝给建议价()}：缺平台费/物流费 ⇒ <b>409</b>，
 *       而不是给一个虚高的价（下游 5.4.1.1 会拿它直接改价）。
 *   <li>{@link #站点差异化只命中目标站点()} 与 {@link #库存周转调整在超过阈值时打折()}：
 *       两类调整项都真的改建议价，且命中/不命中都有可读的理由。
 *   <li>{@link #新品与老品用不同模板()}：阶段不符 ⇒ 409，不静默套用。
 *   <li>{@link #建议价是建议价不是生效价()}：应用只留痕，**不改价**。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class PricingTemplateIntegrationTest {
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
          UUID tenant =
              "Bearer tenant-b".equals(e.getRequestHeaders().getFirst("Authorization")) ? b : a;
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
            String id =
                path.substring("/warehouse/duty-taxes/".length(), path.length() - "/lines".length());
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
  @Autowired PricingTemplateService templates;
  @Autowired LifecycleService lifecycle;
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
  }

  // ================================================================ 夹具

  <T> T tx(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.read", work);
  }

  <T> T write(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.update", work);
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

  /** 已上架（老品）的 SKU：走 3.1.1.3 的真实状态机，不直接改状态列。 */
  UUID onSaleSku(AccessService.Access who, String prefix) {
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
    UUID skuId =
        write(
            who,
            () ->
                catalog.saveSku(
                    who,
                    spuId,
                    null,
                    new SkuInput(code, "SKU " + code, unique("BC"), "PCS", "ACTIVE", List.of(), 0)));
    for (var to : List.of("PENDING_LISTING", "ON_SALE")) {
      long version =
          tx(
              who,
              () -> sql.fetchOne("select version from spu where id=?", spuId).get(0, Long.class));
      write(
          who,
          () -> {
            lifecycle.transition(who, spuId, new TransitionInput(to, "夹具：上架", null, version), null);
            return null;
          });
    }
    // 成本构成同样要有（否则测算不完整 ⇒ 建议价被拒，与本用例要验的阶段无关）。
    costFixture(who, skuId, prefix);
    return skuId;
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
   * 成本构成夹具：采购 10.00 / 头程 2.00 / 关税 3.00 / 包装 1.00 / 其他直接成本 0。
   *
   * <p>⇒ 5.3.1.1 的单位成本合计 = 16.00。
   */
  void costFixture(AccessService.Access who, UUID skuId, String prefix) {
    UUID roleId = supplierRole(who, prefix + "-SUP");
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
  }

  /**
   * 一个完整夹具：SKU + 成本构成（采购 10.00 / 头程 2.00 / 关税 3.00 / 包装 1.00 / 其他直接成本 0）。
   *
   * <p>⇒ 5.3.1.1 的单位成本合计 = 16.00。
   */
  UUID pricedSku(AccessService.Access who, String prefix) {
    UUID skuId = sku(who, prefix + "-SKU");
    costFixture(who, skuId, prefix);
    return skuId;
  }

  static String bearer(AccessService.Access who) {
    return "Bearer " + (who.tenantId().equals(a) ? "tenant-a" : "tenant-b");
  }

  // ================================================================ 请求构造

  /** 模板输入（夹具）：带平台费率 0.15 与物流单价 5（5.3.1.1 的手工假设口径）。 */
  private TemplateInput templateInput(
      String code,
      BaseMethod method,
      BigDecimal baseRate,
      ProductStage stage,
      List<TemplateAdjustmentInput> adjustments,
      List<ScopeInput> scopes) {
    return new TemplateInput(
        code,
        "模板 " + code,
        "夹具模板",
        method,
        baseRate,
        method == BaseMethod.COMPETITOR_FOLLOW ? new BigDecimal("100.00") : null,
        method == BaseMethod.COMPETITOR_FOLLOW ? "USD" : null,
        stage,
        "USD",
        new BigDecimal("0.15"),
        null,
        new BigDecimal("5"),
        null,
        null,
        true,
        OffsetDateTime.now(ZoneOffset.UTC),
        null,
        "夹具",
        adjustments == null ? List.of() : adjustments,
        scopes == null ? List.of() : scopes,
        null);
  }

  private TemplateDetail save(AccessService.Access who, TemplateInput input) {
    return write(who, () -> templates.save(who, null, input));
  }

  private ApplyResult apply(AccessService.Access who, UUID templateId, ApplyRequest request) {
    return write(who, () -> templates.apply(who, templateId, request, bearer(who)));
  }

  /** 默认的应用请求：目标店铺 US、平台 AMAZON、币种 USD、数量 1，口径全部沿用模板。 */
  private ApplyRequest applyFor(UUID skuId, String siteCode) {
    return new ApplyRequest(
        skuId,
        siteCode,
        "AMAZON",
        "USD",
        BigDecimal.ONE,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        "夹具应用");
  }

  /** 带当前可售天数的应用请求（库存周转调整用）。 */
  private ApplyRequest applyForTurnover(UUID skuId, String siteCode, BigDecimal daysOfCover) {
    return new ApplyRequest(
        skuId,
        siteCode,
        "AMAZON",
        "USD",
        BigDecimal.ONE,
        null,
        null,
        daysOfCover,
        null,
        null,
        null,
        null,
        null,
        "夹具应用");
  }

  /** 跨币种的应用请求（售价币种 ≠ 成本币种，且不给汇率）。 */
  private ApplyRequest applyForCrossCurrency(UUID skuId) {
    return new ApplyRequest(
        skuId,
        "US",
        "AMAZON",
        "EUR",
        BigDecimal.ONE,
        null,
        null,
        null,
        null,
        null,
        null,
        "USD",
        null,
        "夹具应用");
  }

  // ================================================================ 断言

  /** 模板落库 + 调整项与适用对象整体写入 + 按适用对象检索。 */
  @Test
  void 模板落库并按适用对象检索() {
    var input =
        templateInput(
            unique("TPL"),
            BaseMethod.FIXED_MARGIN,
            new BigDecimal("0.25"),
            ProductStage.NEW,
            List.of(
                new TemplateAdjustmentInput(
                    AdjustmentType.SITE_DIFFERENTIAL, "US", new BigDecimal("0.02"), null, null, "站点差异")),
            List.of(new ScopeInput(ScopeType.CATEGORY, "ELEC", null)));
    var saved = save(actor, input);

    assertThat(saved.summary().code()).isEqualTo(input.code());
    assertThat(saved.baseMethod()).isEqualTo(BaseMethod.FIXED_MARGIN);
    assertThat(saved.adjustments()).hasSize(1);
    assertThat(saved.adjustments().get(0).siteCode()).isEqualTo("US");
    assertThat(saved.scopes()).hasSize(1);
    assertThat(saved.scopes().get(0).ref()).isEqualTo("ELEC");
    assertThat(saved.summary().adjustmentCount()).isEqualTo(1);
    assertThat(saved.summary().scopeCount()).isEqualTo(1);

    // 按适用对象检索能命中。
    var page =
        tx(
            actor,
            () ->
                templates.list(
                    new PricingTemplateService.TemplateQuery(
                        null, null, null, null, ScopeType.CATEGORY, "ELEC", 0, 20)));
    assertThat(page.items().stream().map(TemplateSummary::id)).contains(saved.summary().id());
    // 按商品阶段筛选：这条模板是新品模板。
    var newOnly =
        tx(
            actor,
            () ->
                templates.list(
                    new PricingTemplateService.TemplateQuery(
                        null, ProductStage.NEW, null, null, null, null, 0, 200)));
    assertThat(newOnly.items().stream().map(TemplateSummary::id)).contains(saved.summary().id());
    // 真分页：总数与页大小都对得上。
    assertThat(page.total()).isGreaterThanOrEqualTo(1);
    assertThat(page.size()).isEqualTo(20);

    // 修改走乐观锁：版本对不上时 409。
    var stale = inputWithVersion(input, saved.version() - 1);
    assertThatThrownBy(() -> write(actor, () -> templates.save(actor, saved.summary().id(), stale)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));
  }

  /**
   * 固定毛利的建议价来自 5.3.1.1 的目标毛利求解。
   *
   * <p>🔴 这是本模块最要紧的一条断言：建议价**不是本模块编的数字**。
   * 5.3.1.1 的方程：售价 =（平台固定费 0 + 物流 5 + 已知成本 16）÷（1 − 平台费率 0.15 − 目标毛利率 0.25）
   * = 21 ÷ 0.6 = 35。
   */
  @Test
  void 固定毛利的建议价来自利润测算器() {
    UUID skuId = pricedSku(actor, "MARGIN");
    var template =
        save(
            actor,
            templateInput(
                unique("TPL"),
                BaseMethod.FIXED_MARGIN,
                new BigDecimal("0.25"),
                ProductStage.NEW,
                List.of(),
                List.of()));
    var result = apply(actor, template.summary().id(), applyFor(skuId, "US"));

    assertThat(result.suggestedPrice()).isEqualByComparingTo("35.0000");
    assertThat(result.decisionReady()).isTrue();
    // 复核后的毛利率恰好等于目标毛利率 —— 说明建议价真的是 5.3.1.1 算出来的。
    assertThat(result.grossMarginRate()).isEqualByComparingTo("0.250000");
    assertThat(result.grossProfit()).isEqualByComparingTo("8.7500");
    // 响应里带回 5.3.1.1 的完整测算（瀑布图 / 缺口清单 / 来源）。
    assertThat(result.calculation()).isNotNull();
    assertThat(result.calculation().waterfall()).isNotEmpty();
    assertThat(result.calculation().blockingGaps()).isNotEmpty();
  }

  /**
   * 成本加成：加成基数 = 5.3.1.1 瀑布图里的单位成本合计（16 成本 + 5 物流 = 21），加成 30% ⇒ 27.3。
   */
  @Test
  void 成本加成的建议价等于成本基数乘以加成率() {
    UUID skuId = pricedSku(actor, "PLUS");
    var template =
        save(
            actor,
            templateInput(
                unique("TPL"),
                BaseMethod.COST_PLUS,
                new BigDecimal("0.30"),
                ProductStage.NEW,
                List.of(),
                List.of()));
    var result = apply(actor, template.summary().id(), applyFor(skuId, "US"));

    // 21 × 1.30 = 27.30
    assertThat(result.suggestedPrice()).isEqualByComparingTo("27.3000");
    assertThat(result.effectiveRate()).isEqualByComparingTo("0.300000");
    // 复核：27.3 − 0.15 × 27.3 − 5 − 16 = 2.205
    assertThat(result.grossProfit()).isEqualByComparingTo("2.2050");
    assertThat(result.grossMarginRate()).isEqualByComparingTo("0.080769");
  }

  /**
   * 竞品跟随时没有登记竞品价 ⇒ 拒绝保存（400）。
   *
   * <p>本仓**没有**竞品价模块 ⇒ 竞品价只能人工登记，系统不会凭空生成一个。
   * 服务层的「应用」路径上还有同一道检查（防御性）：模板上没有、应用时也没传 ⇒ 拒绝给价。
   */
  @Test
  void 竞品跟随时没有登记竞品价就拒绝() {
    pricedSku(actor, "COMP-NO");
    var template =
        save(
            actor,
            templateInput(
                unique("TPL"),
                BaseMethod.COMPETITOR_FOLLOW,
                new BigDecimal("-0.05"),
                ProductStage.NEW,
                List.of(),
                List.of()));
    var detail = tx(actor, () -> templates.detail(template.summary().id()));
    var cleared =
        new TemplateInput(
            detail.summary().code(),
            detail.summary().name(),
            detail.description(),
            BaseMethod.COMPETITOR_FOLLOW,
            detail.baseRate(),
            null,
            null,
            detail.productStage(),
            detail.currency(),
            detail.platformFeeRate(),
            detail.platformFeeFixed(),
            detail.logisticsCostPerUnit(),
            detail.costCurrency(),
            detail.fxRate(),
            detail.enabled(),
            detail.effectiveFrom(),
            detail.effectiveTo(),
            detail.note(),
            List.of(),
            List.of(),
            detail.version());
    assertThatThrownBy(() -> write(actor, () -> templates.save(actor, template.summary().id(), cleared)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> {
              var status = (ResponseStatusException) e;
              assertThat(status.getStatusCode().value()).isEqualTo(400);
              assertThat(status.getReason()).contains("竞品基准价");
            });
  }

  /** 竞品跟随：建议价 = 竞品价 × (1 + 跟随率)，毛利由 5.3.1.1 复核。 */
  @Test
  void 竞品跟随的建议价来自竞品价与跟随率() {
    UUID skuId = pricedSku(actor, "COMP");
    var template =
        save(
            actor,
            templateInput(
                unique("TPL"),
                BaseMethod.COMPETITOR_FOLLOW,
                new BigDecimal("-0.05"),
                ProductStage.NEW,
                List.of(),
                List.of()));
    var result = apply(actor, template.summary().id(), applyFor(skuId, "US"));

    // 100 × (1 − 0.05) = 95
    assertThat(result.suggestedPrice()).isEqualByComparingTo("95.0000");
    // 复核：95 − 0.15 × 95 − 5 − 16 = 59.75
    assertThat(result.grossProfit()).isEqualByComparingTo("59.7500");
    assertThat(result.decisionReady()).isTrue();
  }

  /**
   * 🔴 成本不完整 ⇒ <b>拒绝</b>给建议价（409），而不是给一个虚高的价。
   *
   * <p>下游 5.4.1.1 调价单会拿这个建议价直接改价 ⇒ 一个虚高的建议价会造成真实亏损。
   */
  @Test
  void 成本不完整时拒绝给建议价() {
    UUID skuId = pricedSku(actor, "GAP");
    var input =
        templateInput(
            unique("TPL"), BaseMethod.FIXED_MARGIN, new BigDecimal("0.25"), ProductStage.NEW,
            List.of(), List.of());
    // 把平台费率与物流单价清掉 ⇒ 5.3.1.1 的扣减不全。
    var noAssumption =
        new TemplateInput(
            input.code(),
            input.name(),
            input.description(),
            input.baseMethod(),
            input.baseRate(),
            null,
            null,
            input.productStage(),
            input.currency(),
            null,
            null,
            null,
            null,
            null,
            true,
            input.effectiveFrom(),
            null,
            input.note(),
            List.of(),
            List.of(),
            null);
    var template = save(actor, noAssumption);
    assertThatThrownBy(() -> apply(actor, template.summary().id(), applyFor(skuId, "US")))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> {
              var status = (ResponseStatusException) e;
              assertThat(status.getStatusCode().value()).isEqualTo(409);
              assertThat(status.getReason()).contains("不给建议价");
              assertThat(status.getReason()).contains("平台费用");
            });
  }

  /** 站点差异化：只命中目标站点，且真的改基准率与建议价。 */
  @Test
  void 站点差异化只命中目标站点() {
    UUID skuId = pricedSku(actor, "SITE");
    var template =
        save(
            actor,
            templateInput(
                unique("TPL"),
                BaseMethod.FIXED_MARGIN,
                new BigDecimal("0.25"),
                ProductStage.NEW,
                List.of(
                    new TemplateAdjustmentInput(
                        AdjustmentType.SITE_DIFFERENTIAL, "US", new BigDecimal("0.05"), null, null, "US 站点")),
                List.of()));
    var hit = apply(actor, template.summary().id(), applyFor(skuId, "US"));
    // 目标毛利率 0.25 + 0.05 = 0.30 ⇒ 21 ÷ (1 − 0.15 − 0.30) = 21 ÷ 0.55 = 38.1818
    assertThat(hit.effectiveRate()).isEqualByComparingTo("0.300000");
    assertThat(hit.suggestedPrice()).isEqualByComparingTo("38.1818");
    assertThat(hit.appliedAdjustments())
        .anySatisfy(
            adjustment -> {
              assertThat(adjustment.type()).isEqualTo(AdjustmentType.SITE_DIFFERENTIAL);
              assertThat(adjustment.applied()).isTrue();
              assertThat(adjustment.reason()).contains("US");
            });

    // 换一个没配差异化的站点 ⇒ 不命中，基准率不变。
    var miss = apply(actor, template.summary().id(), applyFor(skuId, "DE"));
    assertThat(miss.effectiveRate()).isEqualByComparingTo("0.250000");
    assertThat(miss.suggestedPrice()).isEqualByComparingTo("35.0000");
    assertThat(miss.appliedAdjustments())
        .anySatisfy(
            adjustment -> {
              assertThat(adjustment.type()).isEqualTo(AdjustmentType.SITE_DIFFERENTIAL);
              assertThat(adjustment.applied()).isFalse();
              assertThat(adjustment.reason()).contains("DE");
            });
  }

  /** 库存周转调整：可售天数超过阈值 ⇒ 打折；没超 ⇒ 不打折。 */
  @Test
  void 库存周转调整在超过阈值时打折() {
    UUID skuId = pricedSku(actor, "TURN");
    var template =
        save(
            actor,
            templateInput(
                unique("TPL"),
                BaseMethod.FIXED_MARGIN,
                new BigDecimal("0.25"),
                ProductStage.NEW,
                List.of(
                    new TemplateAdjustmentInput(
                        AdjustmentType.INVENTORY_TURNOVER,
                        null,
                        null,
                        new BigDecimal("90"),
                        new BigDecimal("0.10"),
                        "滞销 10% 折扣")),
                List.of()));
    var slow = apply(actor, template.summary().id(), applyForTurnover(skuId, "US", new BigDecimal("120")));
    // 35 × (1 − 0.10) = 31.50
    assertThat(slow.suggestedPrice()).isEqualByComparingTo("31.5000");
    assertThat(slow.appliedAdjustments())
        .anySatisfy(
            adjustment -> {
              assertThat(adjustment.type()).isEqualTo(AdjustmentType.INVENTORY_TURNOVER);
              assertThat(adjustment.applied()).isTrue();
              assertThat(adjustment.reason()).contains("滞销");
            });

    var fast = apply(actor, template.summary().id(), applyForTurnover(skuId, "US", new BigDecimal("30")));
    assertThat(fast.suggestedPrice()).isEqualByComparingTo("35.0000");
    assertThat(fast.appliedAdjustments())
        .anySatisfy(
            adjustment -> {
              assertThat(adjustment.type()).isEqualTo(AdjustmentType.INVENTORY_TURNOVER);
              assertThat(adjustment.applied()).isFalse();
              assertThat(adjustment.reason()).contains("未超过阈值");
            });
  }

  /** 模板配了库存周转调整但没填可售天数 ⇒ 拒绝（不按「不打折」糊过去）。 */
  @Test
  void 库存周转调整没填可售天数就拒绝() {
    UUID skuId = pricedSku(actor, "TURN-MISS");
    var template =
        save(
            actor,
            templateInput(
                unique("TPL"),
                BaseMethod.FIXED_MARGIN,
                new BigDecimal("0.25"),
                ProductStage.NEW,
                List.of(
                    new TemplateAdjustmentInput(
                        AdjustmentType.INVENTORY_TURNOVER,
                        null,
                        null,
                        new BigDecimal("90"),
                        new BigDecimal("0.10"),
                        "滞销 10% 折扣")),
                List.of()));
    assertThatThrownBy(() -> apply(actor, template.summary().id(), applyFor(skuId, "US")))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> {
              var status = (ResponseStatusException) e;
              assertThat(status.getStatusCode().value()).isEqualTo(400);
              assertThat(status.getReason()).contains("可售天数");
            });
  }

  /** 新品与老品用不同模板：阶段不符 ⇒ 409，不静默套用。 */
  @Test
  void 新品与老品用不同模板() {
    UUID newSku = pricedSku(actor, "STAGE-NEW");
    UUID oldSku = onSaleSku(actor, "STAGE-OLD");
    var newTemplate =
        save(
            actor,
            templateInput(
                unique("TPL-N"), BaseMethod.FIXED_MARGIN, new BigDecimal("0.25"), ProductStage.NEW,
                List.of(), List.of()));
    var oldTemplate =
        save(
            actor,
            templateInput(
                unique("TPL-O"), BaseMethod.FIXED_MARGIN, new BigDecimal("0.25"), ProductStage.EXISTING,
                List.of(), List.of()));

    // 老品模板套到新品上 ⇒ 409。
    assertThatThrownBy(() -> apply(actor, oldTemplate.summary().id(), applyFor(newSku, "US")))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> {
              var status = (ResponseStatusException) e;
              assertThat(status.getStatusCode().value()).isEqualTo(409);
              assertThat(status.getReason()).contains("新品与老品用不同模板");
            });

    // 老品套老品 ⇒ 通过（老品的 SPU 已经上架 ⇒ 阶段 = EXISTING）。
    var ok = apply(actor, oldTemplate.summary().id(), applyFor(oldSku, "US"));
    assertThat(ok.suggestedPrice()).isNotNull();
  }

  /** 适用对象不匹配 ⇒ 409（模板是「给谁定的价」）。 */
  @Test
  void 适用对象不匹配时拒绝() {
    UUID skuId = pricedSku(actor, "SCOPE");
    var template =
        save(
            actor,
            templateInput(
                unique("TPL"),
                BaseMethod.FIXED_MARGIN,
                new BigDecimal("0.25"),
                ProductStage.NEW,
                List.of(),
                List.of(new ScopeInput(ScopeType.CATEGORY, "NOT-MATCH", null))));
    assertThatThrownBy(() -> apply(actor, template.summary().id(), applyFor(skuId, "US")))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> {
              var status = (ResponseStatusException) e;
              assertThat(status.getStatusCode().value()).isEqualTo(409);
              assertThat(status.getReason()).contains("适用对象");
            });
  }

  /** 跨币种必须要汇率：不按 1:1 折算。 */
  @Test
  void 跨币种没有汇率就拒绝() {
    UUID skuId = pricedSku(actor, "FX");
    var template =
        save(
            actor,
            templateInput(
                unique("TPL"),
                BaseMethod.FIXED_MARGIN,
                new BigDecimal("0.25"),
                ProductStage.NEW,
                List.of(),
                List.of()));
    assertThatThrownBy(() -> apply(actor, template.summary().id(), applyForCrossCurrency(skuId)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> {
              var status = (ResponseStatusException) e;
              assertThat(status.getStatusCode().value()).isEqualTo(400);
              assertThat(status.getReason()).contains("汇率");
            });
  }

  /** 建议价是**建议价**：只留痕，不改价（本模块不建价目表、不动生效价）。 */
  @Test
  void 建议价是建议价不是生效价() {
    UUID skuId = pricedSku(actor, "SUGGEST");
    var skuBefore =
        tx(
            actor,
            () ->
                sql.fetchOne("select version, updated_at from sku where id=?", skuId));
    long skuVersionBefore = skuBefore.get("version", Long.class);
    OffsetDateTime skuUpdatedBefore = skuBefore.get("updated_at", OffsetDateTime.class);
    var template =
        save(
            actor,
            templateInput(
                unique("TPL"),
                BaseMethod.FIXED_MARGIN,
                new BigDecimal("0.25"),
                ProductStage.NEW,
                List.of(),
                List.of()));
    var result = apply(actor, template.summary().id(), applyFor(skuId, "US"));

    // 留痕存在，且写的是建议价。
    var applications =
        tx(
            actor,
            () ->
                templates.listApplications(
                    new PricingTemplateService.ApplicationQuery(
                        template.summary().id(), skuId, 0, 20)));
    assertThat(applications.items()).hasSize(1);
    var row = applications.items().get(0);
    assertThat(row.suggestedPrice()).isEqualByComparingTo("35.0000");
    assertThat(row.decisionReady()).isTrue();
    assertThat(row.grossMarginRate()).isEqualByComparingTo("0.250000");
    assertThat(row.id()).isEqualTo(result.applicationId());

    // 本模块**不改生效价**：商品行本身一个字节都没动（改价是 5.4.1.1 调价单的写入所有权）。
    var skuAfter =
        tx(
            actor,
            () ->
                sql.fetchOne("select version, updated_at from sku where id=?", skuId));
    assertThat(skuAfter.get("version", Long.class)).isEqualTo(skuVersionBefore);
    assertThat(skuAfter.get("updated_at", OffsetDateTime.class)).isEqualTo(skuUpdatedBefore);
  }

  /** 只有读权限时写端点 403。 */
  @Test
  void 只有读权限时写端点403() throws Exception {
    var template =
        templateInput(
            unique("TPL"), BaseMethod.FIXED_MARGIN, new BigDecimal("0.25"), ProductStage.NEW,
            List.of(), List.of());
    http.perform(
            post("/catalog/pricing-templates/templates")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content(JSON.writeValueAsBytes(template)))
        .andExpect(status().isForbidden());
  }

  /** 能力声明与目录端点可用（页面顶部固定显示它们）。 */
  @Test
  void 能力声明与目录端点可用() {
    var capabilities = tx(actor, () -> templates.capabilities());
    assertThat(capabilities.baseMethods()).hasSize(3);
    assertThat(capabilities.adjustmentTypes()).hasSize(2);
    assertThat(capabilities.scopeTypes()).hasSize(3);
    assertThat(capabilities.productStages()).hasSize(2);
    assertThat(capabilities.priceListNote()).contains("怎么算");
    assertThat(capabilities.competitorNote()).contains("由人登记");

    var directory = tx(actor, () -> templates.directory());
    assertThat(directory.categories()).isNotNull();
    assertThat(directory.brands()).isNotNull();
  }

  /** 带同一个模板输入构造一个带版本的输入（用于乐观锁断言）。 */
  private TemplateInput inputWithVersion(TemplateInput input, long version) {
    return new TemplateInput(
        input.code(),
        input.name(),
        input.description(),
        input.baseMethod(),
        input.baseRate(),
        input.competitorReferencePrice(),
        input.competitorCurrency(),
        input.productStage(),
        input.currency(),
        input.platformFeeRate(),
        input.platformFeeFixed(),
        input.logisticsCostPerUnit(),
        input.costCurrency(),
        input.fxRate(),
        input.enabled(),
        input.effectiveFrom(),
        input.effectiveTo(),
        input.note(),
        input.adjustments(),
        input.scopes(),
        version);
  }
}
